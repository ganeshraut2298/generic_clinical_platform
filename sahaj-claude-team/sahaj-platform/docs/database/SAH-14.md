# SAH-14 · Follow-up lists — database design

Schema: `opd` (existing). Migration:
`backend/src/main/resources/db/migration/V2026_11_04_1000__opd_create_follow_up.sql`
(version `V2026_11_02_1000` was deliberately deleted and is not reused).

## Table `opd.follow_up`
One row per visit that leaves the doctor step forward while the visit's own workflow version has
`followup` on.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| patient_id | uuid | no | — | FK `patient.patient(id)` |
| doctor_membership_id | uuid | no | — | FK `platform.membership(id)` |
| source_visit_id | uuid | no | — | FK `opd.visit(id)`, **unique** (`uq_follow_up_source_visit`) |
| due_date | date | no | — | source visit's Asia/Kolkata day + `followUpDays` |
| reason | text | no | — | `char_length(reason) <= 200` |
| returned_visit_id | uuid | yes | null | FK `opd.visit(id)`; set when the patient returns |
| returned_at | timestamptz | yes | null | set together with `returned_visit_id` |
| created_at | timestamptz | no | `now()` | |
| created_by | uuid | no | — | |
| updated_at | timestamptz | yes | null | |
| updated_by | uuid | yes | null | |
| version | bigint | no | 0 | optimistic lock |

Status (`DUE`, `MISSED`, `UPCOMING`, `RETURNED`) is **not stored**; the service computes it from
`returned_visit_id` and today's IST date, so it cannot go stale.

## Keys and indexes
- `uq_follow_up_source_visit` unique `(source_visit_id)`: one follow-up per visit; makes
  `insert ... on conflict (source_visit_id) do nothing` idempotent for a retried `moveStage`.
- `ix_follow_up_tenant_due_date (tenant_id, due_date)`: list tabs and the four counts, filtered and
  ordered by due date within a tenant.
- `ix_follow_up_open_by_patient_doctor (tenant_id, patient_id, doctor_membership_id) where
  returned_visit_id is null`: the return lookup in `issueToken` (oldest open row for the
  tenant+patient+doctor). Partial, so it stays small as rows are closed.

## Tenant scope and RLS
`tenant_id` defaults to `platform.current_tenant()`. ENABLE + FORCE RLS, policy
`p_follow_up_tenant` (`for all to sahaj_app`) using and with check
`tenant_id = platform.current_tenant()`, fail-closed.

## Grants
`select, insert, update` to `sahaj_app`; `revoke all` from `sahaj_app` and `public` first. No
delete: a follow-up is closed by setting `returned_*`, never removed.

## Expected rows, retention
At most one per OPD consultation that has the step on. A busy clinic at about 100 consultations a day is roughly 30,000 rows per tenant per year; most clinics are far smaller. Every index is tenant-prefixed. Revisit partitioning (by due_date range) if the total passes 50 million. Retention: never deleted (patient care record).

## Backward compatibility
New table only; no existing migration edited. A previous app version ignores it entirely.

## Note for java-developer
1. **Create** in `VisitService.moveStage`, same transaction, when the move is forward out of
   `WITH_DOCTOR` and the visit's *own stamped* `workflow_version_no` has `followup` on (not the
   tenant's current version). Use `insert ... on conflict (source_visit_id) do nothing`.
   `due_date` = source visit's Asia/Kolkata calendar day + `followUpDays`; `reason` <= 200 chars;
   `created_by` = acting user.
2. **Close** in `VisitService.issueToken`, same transaction, after the new visit is inserted: update
   the **oldest open** row (`returned_visit_id is null`, order by `due_date, created_at`, limit 1) for
   tenant + patient + doctor whose `source_visit_id` is not the new visit; set `returned_visit_id`,
   `returned_at = now()`, `updated_at`, `updated_by`. A different doctor must not close it.
3. **Status is computed**, never stored: `RETURNED` if `returned_visit_id` not null; else by IST
   today vs `due_date`: `due_date = today` DUE, `< today` MISSED, `> today` UPCOMING.
4. Rows are never deleted; the app role has no delete grant. Never log the patient mobile or
   `reason`.
