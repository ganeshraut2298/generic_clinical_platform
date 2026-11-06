# SAH-20 database design: roster and attendance

Migration: `V2026_11_09_1000__hra_roster_and_attendance.sql` (forward-only, new).

| Table | Key | Columns | Index | Grants to sahaj_app |
|---|---|---|---|---|
| hra.roster_shift | PK (tenant_id, membership_id, work_date) | shift char(1) in M/E/N/X, updated_at, updated_by | (tenant_id, work_date) for the 7-day read | select, insert, update, delete |
| hra.roster_state | PK tenant_id | published_at, published_by (lazy row) | PK | select, insert, update |
| hra.attendance | PK (tenant_id, membership_id, work_date) | status in/late/abs, marked_time (IST, null only for abs), marked_by, marked_at, version | (tenant_id, work_date) for today's sheet | select, insert, update (no delete) |

- All tables: `tenant_id` default `platform.current_tenant()`, FK to `platform.tenant`, ENABLE + FORCE RLS, policy `tenant_id = platform.current_tenant()`.
- `membership_id` has no FK: the project rule (`.claude/rules/postgresql.md`) allows cross-schema FKs only to `platform.tenant` and `patient.patient`; the app validates the member through `platform.api`. (Older opd tables do have such FKs; the hra module follows the rule.)
- Rows per tenant: roster about team size x 7 to 30 live days (about 3,000 per year for 10 staff, purge of old weeks possible); attendance about team size x 365 (about 3,650 per year for 10 staff). Far below partitioning needs.
- Retention: roster rows are scheduling data (deletable); attendance and publish marker are never deleted.
- Compatibility: purely additive new tables; the previous app version is unaffected.
