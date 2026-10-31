---
paths:
  - "backend/src/main/resources/db/migration/**"
  - "docs/database/**"
---

# PostgreSQL and Flyway rules

- File names: `V<YYYY>_<MM>_<DD>_<HHMM>__<module>_<what>.sql` (for example `V2027_01_15_1030__opd_create_appointment.sql`). Repeatable views/functions: `R__<name>.sql`. Timestamps avoid version clashes between branches.
- Forward-only. Never edit a migration after it is merged; write a new one. Destructive changes (drop, rename) use expand-and-contract over two releases.
- One schema per module (`platform`, `patient`, `opd`, `ipd`, `hra`, `billing`); no foreign keys across modules except to `platform.tenant` and `patient.patient`.
- Every tenant-owned table: `tenant_id uuid not null`, an index that starts with `tenant_id`, and `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` with a policy on `current_setting('app.tenant_id')::uuid`.
- Keys: `id uuid primary key default gen_random_uuid()`. Columns `created_at`, `created_by`, `updated_at`, `updated_by`, and `version bigint` for optimistic locking.
- Types: `timestamptz` for time, `numeric(12,2)` for money, `text` with `check` constraints instead of free enums, `jsonb` only for configurable forms and card layouts, with a `schema_version`.
- Names: snake_case, plural tables, `fk_`, `ix_`, `uq_`, `ck_` prefixes.
- Clinical and billing rows are never hard-deleted; use `status` and keep history.
- Large tables (vitals, audit, MAR): plan monthly partitioning before they exceed 50 million rows.
- Seed data: only reference data (roles, master lists). No real patient data, ever.
