# SAH-90..93 · Front desk console · database design

Migration: `V2026_12_18_1000__opd_front_desk_console.sql` (ADR-032 "Data needed"). All additive; no drop, rename, rewrite or backfill. No new table. RLS and grants unchanged: every touched table already has forced RLS on `tenant_id = platform.current_tenant()`; nothing is cross-tenant. Rows are never deleted (retention as before). Rows per tenant: no new rows; two nullable columns on existing rows, so growth is as before (visits and follow-ups per year).

Backward compatibility: the previous app version runs unchanged (new columns nullable, new function and indexes only added).

| # | Change | Why | Rollback |
| --- | --- | --- | --- |
| 1 | `opd.doctor_slot_template.close_before_hours integer null`, `ck_doctor_slot_template_close_before_hours` (0..48) | per-doctor cut-off, null = clinic value (SAH-91-2) | `alter table opd.doctor_slot_template drop column close_before_hours;` (drops the check) |
| 2 | `opd.follow_up.not_needed_at timestamptz null`, `not_needed_by uuid null`, `ck_follow_up_not_needed_pair` (both null or both set) | "Not needed" (SAH-93-1) | `alter table opd.follow_up drop constraint ck_follow_up_not_needed_pair, drop column not_needed_at, drop column not_needed_by;` |
| 3 | Partial index `ix_follow_up_tenant_due_to_book (tenant_id, due_date)` where `returned_visit_id is null and appointment_id is null and not_needed_at is null` | To-book list: due date <= today, oldest first, limit 200 (D8) | `drop index opd.ix_follow_up_tenant_due_to_book;` |
| 4 | `opd.visit.consult_started_at`, `consult_ended_at timestamptz null`, `ck_visit_consult_order` (end >= start when both set) | consultation timing (SAH-92-3); null for older visits | `alter table opd.visit drop constraint ck_visit_consult_order, drop column consult_started_at, drop column consult_ended_at;` |
| 5 | `opd.next_token_no(p_token_date date) returns integer`, same row-lock body (`insert ... on conflict do update ... returning`), security invoker, execute to `sahaj_app` (revoked from public). Zero-arg version kept | token day pinned to IST (D6) | `drop function opd.next_token_no(date);` (zero-arg unaffected) |
| 6 | Partial index `ix_appointment_tenant_date_active (tenant_id, appt_date)` where `status <> 'CANCELLED'` | all-doctor date-range reads (snapshot, board); `(tenant_id, doctor_membership_id, appt_date)` stays for single-doctor reads | `drop index opd.ix_appointment_tenant_date_active;` |
| 7 | No change | see below | none |

Flyway is forward-only: a rollback is a new migration with the statements above. Dropping a column after the new app has written it loses that data, so export first.

Index sizes: both indexes are small (appointments and follow-ups per clinic over years); plain `create index` is fine (short lock). Index 3 holds only open follow-ups.

## appointment.version (item 7)

`opd.appointment.version` exists: `bigint not null default 0` (V2026_11_01_1043). There is **no trigger** on `opd.appointment`; it is only a column. Today `JdbcAppointmentRepository` increments it in SQL in its status update and its token-link update (`version = version + 1`). **The java-developer increments it in SQL** (`version = version + 1`) in every update, including the new reschedule (same row: doctor, date, time, status, `version + 1`), and compares `where id = ? and version = ?` where the client sends one. It is bigint, so read `appointmentVersion` as a long. No DB change.

## Lock order (ADR-032 D6)

appointment row (`select ... for update`) → slot advisory lock (reschedule only) → token counter row (inside `opd.next_token_no(date)`). Booking takes only the slot lock. Follow-up booking: follow-up row `for update` → slot lock (through D4) → appointment insert or update. No path takes the token counter before an appointment row, so there is no cycle.

## Notes for the java-developer

- Call `opd.next_token_no(:today_ist)` and insert `opd.visit.token_date` with the same date, in the same transaction.
- The to-book query must repeat the index predicate exactly (`returned_visit_id is null and appointment_id is null and not_needed_at is null`) to use index 3; likewise `status <> 'CANCELLED'` for index 6.
- Set `consult_started_at` and `consult_ended_at` once (for example `coalesce(consult_started_at, now())`); set `not_needed_at` and `not_needed_by` together.
