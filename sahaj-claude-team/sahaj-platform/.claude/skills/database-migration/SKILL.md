---
name: database-migration
description: Design PostgreSQL tables with tenant row-level security and write safe forward-only Flyway migrations for a Sahaj story. Use when a story needs a new table, column, index or constraint.
---

# Database migration

## 1. Design note — `docs/database/<ID>.md`
- Tables and columns (type, null, default, check), keys and indexes with the query each index serves.
- Tenant scope and the RLS policy.
- Expected rows per tenant per year; partitioning if over 50 million rows in total.
- Retention and whether rows are ever deleted (clinical and billing: never).
- Backward compatibility: can the previous app version run against the new schema?

## 2. Migration — `backend/src/main/resources/db/migration/V<YYYY>_<MM>_<DD>_<HHMM>__<module>_<what>.sql`

Template for a tenant-owned table:

```sql
create table if not exists opd.appointment (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  patient_id  uuid not null references patient.patient(id),
  starts_at   timestamptz not null,
  status      text not null default 'booked'
              constraint ck_appointment_status check (status in ('booked','arrived','done','cancelled','no_show')),
  created_at  timestamptz not null default now(),
  created_by  uuid not null,
  updated_at  timestamptz,
  updated_by  uuid,
  version     bigint not null default 0
);
create index if not exists ix_appointment_tenant_starts on opd.appointment (tenant_id, starts_at);
alter table opd.appointment enable row level security;
alter table opd.appointment force row level security;
create policy p_appointment_tenant on opd.appointment
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);
```

## 3. Safety checks
- Large tables: `create index concurrently` in its own migration (Flyway: no transaction for that script).
- Adding a not-null column to a big table: add nullable → backfill in batches → add the constraint later.
- Renames and drops: expand and contract across two releases.
- Run `node scripts/validate.mjs db` (the backend tests clean `sahaj_test` and apply every migration from scratch).
