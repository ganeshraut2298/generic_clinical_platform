# SAH-9 · Queue and tokens — database design

Schema: `opd` (already exists from SAH-4/SAH-7).

## Tables

### `opd.visit_token_counter`
One row per tenant per day: the next token number to allocate.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| tenant_id | uuid | no | — | PK (with token_date), FK `platform.tenant(id)` |
| token_date | date | no | — | PK (with tenant_id) |
| next_value | integer | no | 1 | `> 0` |
| updated_at | timestamptz | no | now() | |

RLS: ENABLE + FORCE, policy `tenant_id = platform.current_tenant()`. Grants: `select, insert,
update` to `sahaj_app` (no delete — a day's counter row is kept forever so a token number for
that day is never reused).

### Function `opd.next_token_no()`
Returns `integer`. Allocates the next number for the tenant bound to the current transaction and
`current_date`, via `INSERT ... ON CONFLICT (tenant_id, token_date) DO UPDATE SET next_value =
next_value + 1 RETURNING next_value`. Same pattern as SAH-8's `patient.next_uhid()` (one counter
row, race-safe via the row lock the `INSERT ... ON CONFLICT` takes), keyed on `(tenant_id,
token_date)` instead of just `tenant_id` so the sequence resets every day.

**Function privilege model:** `SECURITY INVOKER` (default) — runs as `sahaj_app`, still subject to
the RLS policy on `opd.visit_token_counter`, so it can never allocate a token for a tenant other
than the one bound to the transaction.

**Not a column default — called explicitly.** Unlike `patient.next_uhid()` (which *is* wired as
the `uhid` column default on `patient.patient`), `opd.next_token_no()` is **not** a default on
`opd.visit.token_no`. The service layer must call `select opd.next_token_no()` itself, inside the
same transaction that inserts the `opd.visit` row, for two reasons:
1. The allocated number is needed before the `Visit` response can be built (the API returns
   `tokenNo` in the `201` body), and
2. `opd.visit.token_date` is a plain stored column (not derived from `created_at`); the
   application should pass the same `current_date` value used by the counter if it ever needs to
   align them explicitly, though in practice both default to `current_date` at the same statement
   time inside one transaction and will agree.

java-developer: call `opd.next_token_no()` first, then insert `opd.visit` with that value in
`token_no` (leave `token_date` to its default). Do not generate the token number in Java — it
must come from this function so numbering is race-safe per tenant.

### `opd.visit`
One row per token issued to a patient for a doctor on a given day.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| patient_id | uuid | no | — | FK `patient.patient(id)` — cross-module exception, allowed by `postgresql.md` |
| doctor_membership_id | uuid | no | — | FK `platform.membership(id)` |
| visit_type | text | no | — | `NEW`, `FOLLOW_UP` |
| stage | text | no | `'WAITING'` | `WAITING`, `VITALS`, `WITH_DOCTOR`, `COUNSEL`, `BILLING`, `DONE` |
| token_no | integer | no | — | from `opd.next_token_no()`, `> 0` |
| token_date | date | no | `current_date` | paired with `token_no` for the daily-unique constraint |
| fee_paise | bigint | no | 0 | `>= 0`, integer paise (matches `platform.doctor_profile.fee_paise`) |
| created_at / created_by / updated_at / updated_by / version | — | — | — | standard audit + optimistic lock columns |

**Why a stored `token_date` column, not `created_at::date` generated:** the counter allocates by
`current_date` at the moment `opd.next_token_no()` runs; tying uniqueness to a generated expression
on `created_at` would assume the insert's `created_at` and the counter call happen at the exact
same instant, which is fragile the moment any other statement runs first in the same transaction.
A plain stored column, defaulted to `current_date` like the counter table, keeps both in agreement
by construction without a generated-column dependency.

**Indexes**
- `uq_visit_tenant_token_date_token (tenant_id, token_date, token_no)` unique — the story's "a
  token number is unique per clinic per day".
- `ix_visit_tenant_created_at (tenant_id, created_at)` — "today's visits" board query
  (`where tenant_id = ... and created_at >= start_of_today`), AC9.
- `ix_visit_tenant_patient (tenant_id, patient_id)` — per-patient visit history and the
  follow-up-window computation in AC5 ("last visit with this doctor within follow_up_days").
- `ix_visit_tenant_doctor (tenant_id, doctor_membership_id)` — per-doctor queue / follow-up lookup.

**RLS:** ENABLE + FORCE, policy `p_visit_tenant` using/with check
`tenant_id = platform.current_tenant()` (fail-closed).

**Grants:** `select, insert, update` to `sahaj_app`. **No delete** — a visit is never removed,
only advanced through the stage state machine to `DONE`.

**Stage state machine:** enforced in the service layer, not the database (no trigger). The DB only
constrains `stage` to the six known values via `ck_visit_stage`; which forward transitions are
legal for a given clinic (vitals on/off, counsel configured or not) depends on `opd.opd_settings`,
which the Java service already reads. Backward moves (owner-only, explicit PATCH) are likewise an
application-layer rule.

## Not in this story
No link yet to consultation content (SAH-10) or billing/payment (SAH-11); `fee_paise` is shown,
not collected. Counsel/Billing stages exist only as placeholders in the check constraint.

## Expected rows, retention
Up to a few hundred visits per OPD clinic per day (unbounded per AC6), so low tens of thousands
per clinic per year; well under partitioning thresholds even at 10,000 clinics. Never deleted
(clinical/operational data); retained indefinitely.

## Backward compatibility
New tables only (`opd.visit`, `opd.visit_token_counter` did not exist before); no existing
migration was edited. A previous app version that does not know about `opd.visit*` is unaffected.
