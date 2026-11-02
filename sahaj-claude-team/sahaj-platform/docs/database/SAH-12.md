# SAH-12 · OPD workflow configuration — database design

Schema: `opd` (existing). Migration:
`backend/src/main/resources/db/migration/V2026_11_01_1044__opd_create_workflow_tables.sql`.

## Tables

### `opd.workflow_draft`
One row per tenant — the in-progress, unpublished edit of the step list. **Lazy-created on first
write by the app**, same pattern as `billing.bill` (SAH-10) and `opd.booking_rules` (SAH-11): this
migration seeds no row per tenant; until the owner first saves a draft, `GET /opd/workflow/draft`
is synthesized by the service layer from the current published version's steps (or the fixed
defaults below if no version exists yet either).

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| tenant_id | uuid | no | — | PK, FK `platform.tenant(id)` |
| steps | jsonb | no | — | shape below; not check-constrained — JSON shape validation is a Java-layer concern per the story |
| updated_at | timestamptz | yes | null | set on each save |
| updated_by | uuid | yes | null | |
| version | bigint | no | 0 | optimistic lock |

**RLS:** ENABLE + FORCE, policy `p_workflow_draft_tenant` (`for all`) on
`tenant_id = platform.current_tenant()`.

**Grants:** `select, insert, update` to `sahaj_app`. No delete — "discard draft" means
overwriting the row back to the current published version's steps, not removing it.

### `opd.workflow_version`
Append-only history of published step lists. Every "Publish" inserts a new row and bumps
`version_no`; a visit created under an earlier version keeps following that version's steps for
its whole life (AC3), via `opd.visit.workflow_version_no` below.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | — | FK `platform.tenant(id)` |
| version_no | integer | no | — | `> 0`; unique per tenant |
| steps | jsonb | no | — | same shape as the draft |
| published_at | timestamptz | no | `now()` | |
| published_by | uuid | no | — | |

**Indexes:** `uq_workflow_version_tenant_version_no` unique `(tenant_id, version_no)` — one row
per version per tenant. `ix_workflow_version_tenant_version_no (tenant_id, version_no desc)` —
"find the current version" (`max(version_no)` for a tenant) and the version-history list (newest
first) for the flow-preview "what changed" screen.

**RLS:** ENABLE + FORCE, policy `p_workflow_version_select` (select) and
`p_workflow_version_insert` (insert) on tenant, fail-closed.

**Grants:** `select, insert` only to `sahaj_app` — no update, no delete, same append-only pattern
as `billing.day_closing` (SAH-10): a published version is permanent history, never edited or
removed, even by a later publish. (No deny-mutation trigger was added, unlike
`billing.payment` — the story does not call for that extra layer here and `day_closing` is the
closer precedent: a configuration-history record, not a money-compliance record.)

No row is seeded by this migration. The first `GET /opd/workflow/current` or the first token
issued on a tenant with no version yet lazily inserts `version_no = 1` from the fixed defaults
(see "`steps` shape" below), so there is always at least one real, queryable version once the
clinic is in use.

### `opd.visit` (existing, SAH-9) — additive column
| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| workflow_version_no | integer | no | 1 | which published `opd.workflow_version.version_no` this visit's stage transitions follow; stamped once at creation (`issueToken`), never changed |

Purely additive `alter table ... add column if not exists ... default 1` — existing rows get `1`
automatically, nothing else about the table changes, and a previous (pre-SAH-12) app version can
still read/write every other column on this table unaffected.

**Why a plain integer, not a foreign key to `opd.workflow_version`:** a version row is guaranteed
to exist for every tenant by the time any visit can be created (the lazy v1-create rule above runs
on first use, before `issueToken` can stamp anything), so referential integrity is never actually
at risk in practice. But the two tables are intentionally looked up independently by
`(tenant_id, version_no)` — `opd.visit` doesn't join to `opd.workflow_version` to render the queue
board; it only needs its own snapshot number to recompute its *own* enabled-stage list via
`moveStage`. Making it a real FK would add a cross-table dependency and an index for no query this
story needs, for a value that is semantically "the version number *as of* token issue", not a
live reference to a row that must always be dereferenced. It's a value snapshot, not a
relationship.

## `steps` shape (both `workflow_draft.steps` and `workflow_version.steps`)
Documented here, **not** enforced by a `jsonb_typeof`/`check` constraint — per the story's
technical design, JSON shape validation for this entity belongs to the Java layer (the publish
endpoint's 400 validation of fixed-steps/ordering rules), not the database.

A JSON array, in display/flow order, of objects `{key, on, owner}`:
- `key`: one of `token, queue, vitals, consult, counsel, casepaper, billing, followup`
- `on`: boolean
- `owner`: one of `FRONT_DESK, NURSE, DOCTOR, OWNER` — present only on steps that have an owner
  selector in the mockup (`vitals`, `counsel`); absent on the rest (`token`, `queue`, `consult`,
  `casepaper`, `billing`, `followup` have no stored owner)

Fixed defaults (for reference — the app writes these, this migration seeds no rows):
`token`/`queue`/`consult`/`billing` always `{on: true}` (no owner, locked on in the UI);
`vitals` `{on: true, owner: "NURSE"}`; `counsel` `{on: false, owner: "DOCTOR"}`;
`casepaper` `{on: true}`; `followup` `{on: true}`.

## Note for java-developer: the lazy-create pattern (two places)
1. **Draft, on first write.** `GET /opd/workflow/draft` never inserts anything — it reads
   `opd.workflow_draft` by `tenant_id` and, if no row, synthesizes a `WorkflowSteps` response from
   the current published version's steps (itself lazily created per point 2 below, so this is
   recursive the very first time a tenant is touched at all). The row is only ever written by
   `PUT /opd/workflow/draft` (upsert on `tenant_id`).
2. **Version 1, on first use.** `opd.workflow_version` starts empty for every tenant. The *first*
   caller that needs "the current version" — either `GET /opd/workflow/current`,
   `GET /opd/workflow/versions`, or `issueToken` computing a new visit's first stage — must, inside
   one transaction, check for `max(version_no)` for the tenant and, if none exists, insert
   `version_no = 1` with the fixed defaults above, `published_by` = the system/service-account
   concept already used elsewhere for "no human actor" rows if one exists, else the calling user's
   id, and only then proceed. Guard this against a race (two concurrent first-callers) with
   `insert ... on conflict (tenant_id, version_no) do nothing` followed by a re-select, the same way
   other lazy-create code in this codebase already handles the equivalent race for
   `billing.bill`/`opd.booking_rules`.
3. `opd.visit.workflow_version_no` is stamped once, at `issueToken`, from whatever "current
   version" resolved to in step 2 (never recomputed or changed afterwards — `moveStage` must read
   the *visit's own* stamped value, not the tenant's current one, to stay on AC3's "earlier version
   keeps its own steps for life" rule).

## Expected rows, retention
`workflow_draft`: at most one row per tenant, edited in place. `workflow_version`: one row per
publish — expected to be rare (a handful to a few dozen over a clinic's lifetime, nowhere near
partitioning scale even across 10,000 tenants). `opd.visit.workflow_version_no`: no new rows, just
one additive column on an existing table whose row-volume projection is unchanged from SAH-9/11.

Retention: `workflow_version` rows are never deleted (append-only history, same as
`billing.day_closing`/`billing.payment`). `workflow_draft` is configuration, edited in place, also
never deleted.

## Backward compatibility
`opd.workflow_draft` and `opd.workflow_version` are new tables — no existing migration was edited.
The `opd.visit` change is a single additive, defaulted column
(`add column if not exists workflow_version_no integer not null default 1`); a previous app
version that doesn't know this column exists can still read and write every other column on
`opd.visit` unaffected, and every existing row gets a valid default with no backfill step needed.
