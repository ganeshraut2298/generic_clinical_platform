# SAH-11 · Appointments and booking rules — database design

Schema: `opd` (existing). Migration:
`backend/src/main/resources/db/migration/V2026_11_01_1043__opd_create_appointment.sql`.

## Tables

### `opd.doctor_slot_template`
One row per DOCTOR/VISITING_DOCTOR membership — the clinic's slot pattern for that doctor.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| doctor_membership_id | uuid | no | — | PK, FK `platform.membership(id)` |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)`, denormalized (same pattern as `platform.doctor_profile`) so RLS needs no join |
| slot_minutes | integer | no | 15 | `5–60` |
| working_days | smallint[] | no | `{1,2,3,4,5,6}` | values `⊆ {0..6}`, 0=Sunday |
| walk_in_every_nth | integer | no | 0 | `>= 0`; 0 = no slots reserved for walk-ins |
| max_online_per_day | integer | no | 0 | `>= 0` |
| updated_at/by, version | — | — | — | audit + optimistic lock (no `created_at/by`: the row is upserted into existence, not meaningfully "created" by a user action) |

**Index:** `ix_doctor_slot_template_tenant (tenant_id)` — `GET /opd/doctor-slots` (every doctor, no filter).

**RLS:** ENABLE + FORCE, policy `p_doctor_slot_template_tenant` (`for all`) on `tenant_id = platform.current_tenant()`.

**Grants:** `select, insert, update` to `sahaj_app`. No delete — edited in place.

### `opd.doctor_slot_session`
Up to two sessions (e.g. morning/evening) per doctor's template.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| doctor_membership_id | uuid | no | — | FK `opd.doctor_slot_template(doctor_membership_id)` |
| session_index | smallint | no | — | `0` or `1` |
| start_time | time | no | — | |
| end_time | time | no | — | `> start_time` |

**Indexes:** `ix_doctor_slot_session_tenant_doctor (tenant_id, doctor_membership_id)` — load a
doctor's sessions with the template. `uq_doctor_slot_session_doctor_index` unique
`(doctor_membership_id, session_index)` — at most one row per session slot.

**RLS:** ENABLE + FORCE, policy `p_doctor_slot_session_tenant` (`for all`) on tenant.

**Grants:** `select, insert, update, delete` to `sahaj_app` — this is the one table in the story
with a delete grant. Sessions are meant to be replaced wholesale on save (delete-then-insert or
upsert by `(doctor_membership_id, session_index)` in one transaction); java-developer may call
delete here. Every other table below keeps no-delete.

### `opd.booking_rules`
One row per tenant. **Lazy-created on first write by the app** — this migration does not seed a
row per tenant, same pattern as `billing.bill` (SAH-10): until the owner first saves booking
rules, `GET /opd/booking-rules` is synthesized by the service layer from the column defaults
below (so the API always returns a row, even before any write exists).

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| tenant_id | uuid | no | — | PK, FK `platform.tenant(id)` |
| close_before_hours | integer | no | 1 | `0–48` |
| remind_before_hours | integer | no | 2 | `0–48`; stored, unused until a notification channel exists |
| no_show_after_minutes | integer | no | 15 | `0–180` |
| follow_priority | boolean | no | true | |
| self_book | boolean | no | false | stored, unused until a patient-facing booking channel exists |
| overbook | boolean | no | false | |
| updated_at/by, version | — | — | — | audit + optimistic lock |

**RLS:** ENABLE + FORCE, policy `p_booking_rules_tenant` (`for all`) on tenant.

**Grants:** `select, insert, update` to `sahaj_app`. No delete.

### `opd.appointment`
One row per booked appointment. Never deleted — cancelled via `status`, which frees the slot.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| patient_id | uuid | no | — | FK `patient.patient(id)` (cross-module exception per `postgresql.md`) |
| doctor_membership_id | uuid | no | — | FK `platform.membership(id)` |
| appt_date | date | no | — | |
| appt_time | time | no | — | |
| status | text | no | `BOOKED` | `BOOKED`, `ARRIVED`, `NOSHOW`, `CANCELLED` |
| visit_type | text | no | — | `NEW`, `FOLLOW_UP` |
| source | text | no | — | `PHONE`, `WHATSAPP`, `FRONT_DESK` |
| note | text | yes | null | `<= 400` chars |
| visit_id | uuid | yes | null | FK `opd.visit(id)`, set by "Create token" |
| created_at/by, updated_at/by, version | — | — | — | standard audit + optimistic lock |

**Indexes:** `ix_appointment_tenant_doctor_date (tenant_id, doctor_membership_id, appt_date)` —
the Appointments board query (a doctor's day). `uq_appointment_tenant_doctor_date_time_active`
**partial** unique index on `(tenant_id, doctor_membership_id, appt_date, appt_time) where status
<> 'CANCELLED'` — one active appointment per slot, but a cancelled slot can be rebooked (the
story's technical design calls for this explicitly; a plain unique constraint would block
rebooking a cancelled slot forever).

**RLS:** ENABLE + FORCE, policy `p_appointment_tenant` (`for all`) on tenant, fail-closed.

**Grants:** `select, insert, update` to `sahaj_app`. **No delete** — an appointment is cancelled
via `status`, never removed, matching `opd.visit`/`billing.payment`'s "clinical record, never
hard-deleted" rule.

## Cross-module references
`opd.appointment.patient_id` → `patient.patient(id)` (the one named exception in
`postgresql.md`, same as `opd.visit.patient_id`). `opd.appointment.doctor_membership_id` and
`opd.doctor_slot_template.doctor_membership_id` → `platform.membership(id)`, same pattern as
`opd.visit.doctor_membership_id`. `opd.appointment.visit_id` → `opd.visit(id)` is in-module (no
exception needed). No new FK into `patient` or `platform` schemas beyond what SAH-9/SAH-4 already
established.

## Expected rows, retention
`doctor_slot_template`/`doctor_slot_session`: one row (resp. up to two) per doctor membership —
same order of magnitude as `platform.doctor_profile`, well under a hundred per tenant.
`booking_rules`: at most one row per tenant. `appointment`: a handful to a few dozen per doctor
per working day; at 10,000 clinics × ~5 doctors × ~30 appointments/day × 300 days/year this is in
the low hundreds of millions across the whole platform over several years — well under the 50
million-row-per-table partitioning threshold for any single tenant, and the existing
`(tenant_id, doctor_membership_id, appt_date)` index keeps per-tenant, per-doctor, per-day reads
cheap; partitioning is not needed yet but `appt_date` is the natural partition key if it is ever
needed.

Retention: `appointment` rows are never deleted (cancelled via `status`, kept as history,
matching `opd.visit`). `doctor_slot_template`/`doctor_slot_session`/`booking_rules` are
configuration, edited in place, also never deleted.

## Backward compatibility
New tables only (`opd.doctor_slot_template`, `opd.doctor_slot_session`, `opd.booking_rules`,
`opd.appointment` did not exist before); no existing migration was edited. A previous app version
that does not know about these tables is unaffected.

## Change: slot index made non-unique (V2026_11_01_1045)

`uq_appointment_tenant_doctor_date_time_active` (partial unique, status <> 'CANCELLED') is dropped. It made
the `overbook` booking rule impossible (two active appointments in one slot raised SQLSTATE 23505, a 500)
and blocked rebooking a NOSHOW slot, because the service treats only BOOKED/ARRIVED as occupying.
Replaced by the plain index `ix_appointment_tenant_doctor_date_time` on
`(tenant_id, doctor_membership_id, appt_date, appt_time) where status in ('BOOKED','ARRIVED')`, which serves the
slot-conflict lookup. Slot uniqueness is now enforced by `AppointmentService.create` (409
`appointment-slot-taken` when overbook is off), since the rule is tenant-configurable. V1043 is unchanged
(Flyway checksum). Backward compatible: dropping a constraint only loosens the schema.
