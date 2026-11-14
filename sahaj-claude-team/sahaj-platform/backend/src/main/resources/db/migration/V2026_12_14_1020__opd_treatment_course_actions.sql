-- SAH-75 (ADR-026 D3, D4, D7; DEC-052; contract approved in DEC-053) · Treatment course: signed-estimate flag and the course actions
-- (mark done, missed, rebook, reschedule, remove, regimen of a chemo cycle). Forward-only, additive.
-- Design note: docs/database/SAH-75.md. Tables changed: opd.treatment_course, opd.treatment_course_sitting (V2026_12_10_1120).
--
-- NO BACKFILL, NO REWRITE: every new column is nullable (or boolean not null with a constant default, which PostgreSQL stores without
-- rewriting the table). No existing tenant row is updated. Existing sittings keep done_source null and are read by the application as
-- "SIGN" (ADR-026 D4); old courses keep estimate_signed false and the application reads a note equal to "estimate signed by patient"
-- (ignoring case) as signed. Row level security, policies and the tenant-first indexes of both tables are unchanged; there is no new
-- delete grant (clinical plan data is never deleted: Remove sets removed_at and the row stays).
--
-- Sitting state (computed on read, ADR-026 D4): REMOVED if removed_at; else DONE if done_at (or the linked visit is signed / the
-- admission discharged); else MISSED if missed_at; else TODAY or PLANNED. The checks below keep the stored columns consistent.

set local lock_timeout = '30s';

-- ---------------------------------------------------------------------------------------------
-- 1. opd.treatment_course.estimate_signed: set by "start from plan" when the estimate is signed (replaces the note text).
-- ---------------------------------------------------------------------------------------------
alter table opd.treatment_course
  add column if not exists estimate_signed boolean not null default false;

grant update (estimate_signed) on opd.treatment_course to sahaj_app;

-- ---------------------------------------------------------------------------------------------
-- 2. opd.treatment_course_sitting: missed, removed, who marked it done, and the regimen of a chemo cycle.
--    *_by_membership_id are opaque platform.membership ids (cross-module, no foreign key), as doctor_membership_id is.
--    regimen_key refers to the immutable protocol library (same module); the application sets it for CHEMO courses only.
-- ---------------------------------------------------------------------------------------------
alter table opd.treatment_course_sitting
  add column if not exists missed_at timestamptz,
  add column if not exists missed_by_membership_id uuid,
  add column if not exists removed_at timestamptz,
  add column if not exists removed_by_membership_id uuid,
  add column if not exists done_source text,
  add column if not exists done_by_membership_id uuid,
  add column if not exists regimen_key text;

alter table opd.treatment_course_sitting
  add constraint ck_treatment_course_sitting_missed_pair
    check ((missed_at is null) = (missed_by_membership_id is null)),
  add constraint ck_treatment_course_sitting_removed_pair
    check ((removed_at is null) = (removed_by_membership_id is null)),
  add constraint ck_treatment_course_sitting_done_source
    check (done_source is null or done_source in ('MARK_DONE', 'SIGN')),
  -- done_source and done_by only exist together with done_at; "Mark done" always names who pressed it
  add constraint ck_treatment_course_sitting_done_source_needs_done
    check (done_source is null or done_at is not null),
  add constraint ck_treatment_course_sitting_done_by_needs_done
    check (done_by_membership_id is null or done_at is not null),
  add constraint ck_treatment_course_sitting_mark_done_by
    check (done_source is distinct from 'MARK_DONE' or done_by_membership_id is not null),
  -- a removed sitting was never done; a missed sitting is neither done nor removed
  add constraint ck_treatment_course_sitting_removed_not_done
    check (removed_at is null or done_at is null),
  add constraint ck_treatment_course_sitting_missed_state
    check (missed_at is null or (done_at is null and removed_at is null)),
  add constraint fk_treatment_course_sitting_regimen
    foreign key (regimen_key) references opd.emr_chemo_protocol (protocol_key);

-- Column-limited updates (the schema default privileges were revoked in V2026_12_10_1120; insert is table-level there).
grant update (missed_at, missed_by_membership_id, removed_at, removed_by_membership_id,
              done_source, done_by_membership_id, regimen_key)
  on opd.treatment_course_sitting to sahaj_app;

-- No new index: the sittings of a course in order, and "a visit / an admission belongs to one sitting", are served by
-- uq_treatment_course_sitting_no, uq_treatment_course_sitting_visit and uq_treatment_course_sitting_admission (V2026_12_10_1120);
-- a partial index on removed_at is not needed for a course of tens of sittings.

comment on column opd.treatment_course.estimate_signed is
  'True when the course was started from a plan whose estimate the patient signed (SAH-75, ADR-026 D3). False for older rows: the application also reads a note equal to "estimate signed by patient" (ignoring case) as signed.';
comment on column opd.treatment_course_sitting.missed_at is
  'Set by "Patient did not come" (SAH-75-10): the sitting is MISSED, no charge. Cleared by Rebook. Pair with missed_by_membership_id. Never with done_at or removed_at.';
comment on column opd.treatment_course_sitting.removed_at is
  'Set by "Remove" (SAH-75-10): the sitting is not returned or counted any more; the row is never deleted. Pair with removed_by_membership_id. Never with done_at.';
comment on column opd.treatment_course_sitting.done_source is
  'How done_at was set: MARK_DONE (the button) or SIGN (the visit was signed). Null on rows written before SAH-75: read as SIGN. Only set together with done_at.';
comment on column opd.treatment_course_sitting.done_by_membership_id is
  'Membership that pressed Mark done (always set for MARK_DONE). Opaque id, no foreign key (cross-module).';
comment on column opd.treatment_course_sitting.regimen_key is
  'Chemo regimen of a CHEMO cycle sitting (SAH-75-7, ADR-026 D7): protocol key from opd.emr_chemo_protocol. Null for every other kind and for old rows (the chip then reads C<n>). Only CHEMO courses use it: an application rule, no cross-row check.';
