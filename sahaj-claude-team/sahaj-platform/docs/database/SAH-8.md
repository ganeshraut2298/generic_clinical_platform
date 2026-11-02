# SAH-8 · Patients — database design

Schema: `patient` (already created by `scripts/db/schemas.sql`, which also sets the default
grants `sahaj_app` needs — no schema-level migration was needed, only the tables below).

## Tables

### `patient.patient_uhid_counter`
One row per tenant: the next UHID number to allocate.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| tenant_id | uuid | no | — | PK, FK `platform.tenant(id)` |
| next_value | bigint | no | 1 | `> 0` |
| updated_at | timestamptz | no | now() | |

RLS: ENABLE + FORCE, policy `tenant_id = platform.current_tenant()`. Grants: `select, insert, update`
to `sahaj_app` (no delete — a counter is never removed).

### Function `patient.next_uhid()`
Returns `text`, e.g. `PT-00001`. Allocates the next number for the tenant bound to the current
transaction via `INSERT ... ON CONFLICT (tenant_id) DO UPDATE SET next_value = next_value + 1
RETURNING next_value`, formatted `'PT-' || lpad(seq, 5, '0')`.

**Why not a native PostgreSQL `SEQUENCE`:** a `SEQUENCE` is one global counter per object; getting
a per-tenant sequence would mean creating one `CREATE SEQUENCE` per tenant (DDL, needs elevated
privilege, awkward at signup time, and does not scale cleanly to 10,000 clinics). A counter table
with one row per tenant, incremented by a single `INSERT ... ON CONFLICT ... RETURNING`, is
race-safe (the statement takes a row lock on that tenant's counter row) and needs no DDL per tenant.

**Prefix:** the story's AC1 mentions a possible per-tenant prefix (`SSC-00001` style) but no such
convention exists yet anywhere in the schema (`platform.clinic_profile` has no prefix column).
Per DEC-008 (feature-first, sensible default), every tenant uses the fixed prefix `PT` for now.
If a later story adds a configurable prefix, change `next_uhid()` to read it from
`platform.clinic_profile` and keep the format the same — existing UHIDs are not renumbered.

**Function privilege model:** `next_uhid()` is `SECURITY INVOKER` (the default), so it runs as
whichever role calls it (`sahaj_app` in production) and is still subject to the RLS policy on
`patient.patient_uhid_counter` — it can never allocate a number for a tenant other than the one
bound to the transaction.

### `patient.patient`
One row per family member registered at the clinic.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| uhid | text | no | `patient.next_uhid()` | unique per tenant, format `PT-00001` |
| name | text | no | — | 1–120 chars (trimmed) |
| age | smallint | no | — | 0–120 |
| sex | char(1) | no | — | `F`, `M`, `O` |
| mobile | char(10) | no | — | `^[6-9][0-9]{9}$`; **not unique** — a family shares one number |
| area | text | yes | null | ≤160 chars |
| language | text | no | `'mr'` | `mr`, `hi`, `en` |
| allergy | text | yes | null | ≤400 chars, free text |
| chronic | text | yes | null | ≤400 chars, free text (also the "chronic tag" on the list) |
| abha_linked | boolean | no | `false` | flag only — no ABHA linking flow in phase 1 |
| merged_into_id | uuid | yes | null | self-FK; set when this row has been soft-merged into another |
| created_at / created_by / updated_at / updated_by / version | — | — | — | standard audit + optimistic lock columns |

Constraints: `ck_patient_not_self_merged` (`merged_into_id <> id`); all free-text fields length-
checked; `uhid` format-checked.

**Indexes**
- `uq_patient_tenant_uhid (tenant_id, uhid)` unique — UHID lookup and uniqueness.
- `ix_patient_tenant_mobile (tenant_id, mobile)` — family lookup ("who shares this number", AC2/AC6)
  and search-by-mobile on the patient list.
- `ix_patient_name_trgm` GIN `(name gin_trgm_ops)` (`pg_trgm` extension) — search-by-name on the
  patient list (AC3). Chosen over a prefix (`text_pattern_ops`) index, which is used elsewhere in
  this codebase for `opd.medicine`/`opd.diagnosis`, because the mockup's patient search box is one
  free-text field clinic staff use with name *fragments*, not just a "starts with" field.
- `ix_patient_tenant_active (tenant_id) WHERE merged_into_id IS NULL` — every list/search query
  starts `where tenant_id = ... and merged_into_id is null`; this partial index serves that.

**RLS:** ENABLE + FORCE, policy `p_patient_tenant` using/with check
`tenant_id = platform.current_tenant()` (fail-closed: `current_tenant()` returns `NULL` when
`app.tenant_id` is unset, and `tenant_id = NULL` is never true).

**Grants:** `select, insert, update` to `sahaj_app`. **No delete** — a patient record is clinical
data and is never hard-deleted; "merge" (AC5) is a soft update that sets `merged_into_id` on the
losing record. The application must filter `merged_into_id is null` on every list/search query and
must still allow a direct `GET /patients/{id}` on a merged-away id (per the story) — that read
works today since merged rows are not deleted, just excluded from the default list/search.

## Not in this story
No `patient.visit` or billing tables — those arrive with SAH-9/SAH-10. The record page's
visits/bills tabs have nothing to query yet; the application returns empty/placeholder data.

## Expected rows, retention
Up to a few thousand patients per OPD clinic per year, tens of thousands for a large IPD hospital;
well under partitioning thresholds. Never deleted (clinical data); retained indefinitely, merges
kept as pointers for traceability.

## Backward compatibility
New tables only (schema `patient` was empty before this story); no existing migration was edited.
A previous app version that does not know about `patient.*` is unaffected — it simply doesn't query
these tables.
