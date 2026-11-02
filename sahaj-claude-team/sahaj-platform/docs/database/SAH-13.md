# SAH-13 · Database design: Front-desk appointments workspace

Module: **opd**
Stories: SAH-13-1 to SAH-13-10
API contract: `docs/api/opd.yaml`

---

## Tables

### 1. `opd.session_config` — Doctor session configuration
Defines each doctor's sessions (morning/evening) with slot duration.

| Column | Type | Null | Default | Check / Notes |
|--------|------|------|---------|---------------|
| `id` | uuid | N | `gen_random_uuid()` | PK |
| `tenant_id` | uuid | N | | FK → `platform.tenant(id)` |
| `doctor_id` | uuid | N | | FK → `platform.team_member(id)` (role = DOCTOR/VISITING_DOCTOR) |
| `session_code` | text | N | | `MORNING` or `EVENING` |
| `start_time` | time | N | | e.g., `09:00` |
| `end_time` | time | N | | e.g., `13:00` |
| `slot_minutes` | int | N | 15 | 10, 15, 20, 30 |
| `is_active` | boolean | N | true | |
| `created_at` | timestamptz | N | `now()` | |
| `created_by` | uuid | N | | FK → `platform.app_user(id)` |
| `updated_at` | timestamptz | | | |
| `updated_by` | uuid | | | FK → `platform.app_user(id)` |
| `version` | bigint | N | 0 | Optimistic locking |

**Keys & Indexes**
- PK: `id`
- UQ: `(tenant_id, doctor_id, session_code)` — one config per doctor per session
- IX: `ix_session_config_tenant_doctor (tenant_id, doctor_id)` — list sessions for a doctor
- IX: `ix_session_config_tenant_active (tenant_id, is_active)` — active sessions

**RLS Policy**
```sql
alter table opd.session_config enable row level security;
alter table opd.session_config force row level security;
create policy p_session_config_tenant on opd.session_config
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);
```

**Retention**: Never deleted. Soft-delete via `is_active = false`.
**Rows/tenant/year**: ~50 (10 doctors × 2 sessions × few changes)

---

### 2. `opd.appointment_slot` — Individual time slots
One row per bookable slot per day per doctor per session. Generated in advance (e.g., 90 days out).

| Column | Type | Null | Default | Check / Notes |
|--------|------|------|---------|---------------|
| `id` | uuid | N | `gen_random_uuid()` | PK |
| `tenant_id` | uuid | N | | FK → `platform.tenant(id)` |
| `session_config_id` | uuid | N | | FK → `opd.session_config(id)` |
| `doctor_id` | uuid | N | | Denormalized FK → `platform.team_member(id)` |
| `slot_date` | date | N | | The calendar date |
| `session_code` | text | N | | `MORNING` / `EVENING` (denormalized) |
| `start_time` | time | N | | e.g., `09:15` |
| `end_time` | time | N | | e.g., `09:30` |
| `state` | text | N | `FREE` | `FREE`, `BOOKED`, `ARRIVED`, `NO_SHOW`, `WALK_IN`, `PAST`, `BLOCKED` |
| `patient_id` | uuid | | | FK → `patient.patient(id)` (null when FREE) |
| `visit_id` | uuid | | | FK → `opd.visit(id)` (set on ARRIVED) |
| `is_walkin_quota` | boolean | N | false | Reserved for walk-ins |
| `notes` | text | | | Booking notes |
| `created_at` | timestamptz | N | `now()` | |
| `created_by` | uuid | | | FK → `platform.app_user(id)` |
| `updated_at` | timestamptz | | | |
| `updated_by` | uuid | | | FK → `platform.app_user(id)` |
| `version` | bigint | N | 0 | Optimistic locking |

**Keys & Indexes**
- PK: `id`
- UQ: `(tenant_id, session_config_id, slot_date, start_time)` — no duplicate slots
- IX: `ix_appt_slot_tenant_date (tenant_id, slot_date)` — **main query: slots for a date**
- IX: `ix_appt_slot_tenant_doctor_date (tenant_id, doctor_id, slot_date)` — doctor's day
- IX: `ix_appt_slot_tenant_patient (tenant_id, patient_id)` — patient's appointments
- IX: `ix_appt_slot_tenant_state (tenant_id, state)` — find FREE/BOOKED slots
- IX: `ix_appt_slot_tenant_walkin (tenant_id, is_walkin_quota, slot_date)` — walk-in quota check

**RLS Policy**
```sql
alter table opd.appointment_slot enable row level security;
alter table opd.appointment_slot force row level security;
create policy p_appt_slot_tenant on opd.appointment_slot
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);
```

**Retention**: Never deleted. Old slots transition to `PAST` automatically.
**Rows/tenant/year**: ~500k (10 doctors × 2 sessions × 20 slots × 365 days). Partition by `slot_date` if > 10M total.

---

### 3. `opd.booking_rules` — Clinic-wide booking rules
Single row per tenant (upsert).

| Column | Type | Null | Default | Check / Notes |
|--------|------|------|---------|---------------|
| `id` | uuid | N | `gen_random_uuid()` | PK |
| `tenant_id` | uuid | N | | FK → `platform.tenant(id)` |
| `max_advance_days` | int | N | 30 | 1–90 |
| `overbook_allowed` | boolean | N | false | |
| `same_day_cutoff` | time | N | `10:00` | |
| `walkin_quota_per_session` | int | N | 2 | 0–10 |
| `slot_duration_minutes` | int | N | 15 | 10, 15, 20, 30 |
| `min_gap_minutes` | int | N | 0 | 0–30 |
| `created_at` | timestamptz | N | `now()` | |
| `created_by` | uuid | N | | FK → `platform.app_user(id)` |
| `updated_at` | timestamptz | | | |
| `updated_by` | uuid | | | FK → `platform.app_user(id)` |
| `version` | bigint | N | 0 | |

**Keys & Indexes**
- PK: `id`
- UQ: `(tenant_id)` — one rule set per clinic

**RLS Policy**
```sql
alter table opd.booking_rules enable row level security;
alter table opd.booking_rules force row level security;
create policy p_booking_rules_tenant on opd.booking_rules
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);
```

**Retention**: Never deleted. Updated in place.

---

### 4. `opd.visit` — Patient visit (created on arrival)
Links appointment to queue/consultation/billing.

| Column | Type | Null | Default | Check / Notes |
|--------|------|------|---------|---------------|
| `id` | uuid | N | `gen_random_uuid()` | PK |
| `tenant_id` | uuid | N | | FK → `platform.tenant(id)` |
| `slot_id` | uuid | N | | FK → `opd.appointment_slot(id)` |
| `patient_id` | uuid | N | | FK → `patient.patient(id)` |
| `doctor_id` | uuid | N | | FK → `platform.team_member(id)` |
| `token_number` | int | N | | Sequential per day per clinic |
| `status` | text | N | `WAITING` | `WAITING`, `IN_CONSULT`, `DONE`, `CANCELLED` |
| `arrived_at` | timestamptz | N | `now()` | |
| `started_at` | timestamptz | | | Consultation start |
| `completed_at` | timestamptz | | | Consultation end |
| `created_at` | timestamptz | N | `now()` | |
| `created_by` | uuid | N | | FK → `platform.app_user(id)` |
| `updated_at` | timestamptz | | | |
| `updated_by` | uuid | | | FK → `platform.app_user(id)` |
| `version` | bigint | N | 0 | |

**Keys & Indexes**
- PK: `id`
- UQ: `(tenant_id, slot_id)` — one visit per slot
- UQ: `(tenant_id, token_number, arrived_at::date)` — unique token per day
- IX: `ix_visit_tenant_patient (tenant_id, patient_id)` — patient's visits
- IX: `ix_visit_tenant_doctor_date (tenant_id, doctor_id, arrived_at)` — doctor's visits
- IX: `ix_visit_tenant_status (tenant_id, status)` — queue filtering

**RLS Policy**
```sql
alter table opd.visit enable row level security;
alter table opd.visit force row level security;
create policy p_visit_tenant on opd.visit
  using (tenant_id = current_setting('app.tenant_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id')::uuid);
```

**Retention**: Never deleted (clinical record).
**Rows/tenant/year**: ~50k (20 patients/day × 250 days)

---

## Slot Generation Strategy
- **When**: Nightly job (or on-demand) generates slots for next 90 days based on `session_config` and `booking_rules.slot_duration_minutes`.
- **Walk-in quota**: First N slots of each session marked `is_walkin_quota = true` (configurable per session).
- **BLOCKED**: Owner can manually block slots (doctor leave, etc.).
- **PAST transition**: Scheduled job marks slots `PAST` where `slot_date < CURRENT_DATE` and state = `FREE`.

---

## Backward Compatibility
- All new tables are additive. No existing tables modified.
- New columns are nullable or have defaults.
- API contract version 0.1.0 matches this schema.

---

## Open Questions
1. Should `appointment_slot` store `doctor_id` denormalized? (Yes, for query performance)
2. Token number: sequential per day per clinic, or per doctor? (Per clinic, for queue display)
3. Slot generation: Flyway migration creates initial slots, or app generates on startup? (App generates via scheduler)