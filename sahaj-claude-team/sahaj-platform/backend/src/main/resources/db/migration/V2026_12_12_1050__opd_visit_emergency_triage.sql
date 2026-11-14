-- SAH-67-4 (ADR-025 D11, "Data needed" item 6) · The Emergency visit type with a triage level on opd.visit.
-- Forward-only, additive; nothing is dropped, renamed or rewritten. RLS, policy and the table-level select, insert, update
-- grants are unchanged (they cover the new columns). The previous application version never writes the columns and
-- never writes EMERGENCY, so it keeps working.
-- 1. visit_type gains EMERGENCY (precedent: V2026_12_09_1000 added REVISIT). Widened, never narrowed.
-- 2. triage_level (1 to 5) and triage_scale (3 or 5): the clinic's triage scale of ipd at the time of the change, copied as a
--    number (no foreign key to ipd). Both null unless visit_type is EMERGENCY; changing the type back sets both to null in the
--    same update (the level stays only in the audit trail). The level is never above the scale.
-- All new checks hold for every existing row (all of them NEW, FOLLOW_UP or REVISIT with no level), so they are added NOT VALID
-- and validated at once, as in the earlier migrations; validation scans opd.visit without blocking writes.
-- No index: the Queue reads the clinic's visits of today by created_at and groups in memory.

alter table opd.visit
  add column if not exists triage_level smallint,
  add column if not exists triage_scale smallint;

alter table opd.visit drop constraint ck_visit_type;
alter table opd.visit
  add constraint ck_visit_type check (visit_type in ('NEW', 'FOLLOW_UP', 'REVISIT', 'EMERGENCY')) not valid;
alter table opd.visit validate constraint ck_visit_type;

alter table opd.visit
  add constraint ck_visit_triage_level check (triage_level is null or triage_level between 1 and 5) not valid,
  add constraint ck_visit_triage_scale check (triage_scale is null or triage_scale in (3, 5)) not valid,
  add constraint ck_visit_triage_pair check ((triage_level is null) = (triage_scale is null)) not valid,
  add constraint ck_visit_triage_within_scale check (triage_level is null or triage_level <= triage_scale) not valid,
  add constraint ck_visit_triage_emergency check ((visit_type = 'EMERGENCY') = (triage_level is not null)) not valid;

alter table opd.visit validate constraint ck_visit_triage_level;
alter table opd.visit validate constraint ck_visit_triage_scale;
alter table opd.visit validate constraint ck_visit_triage_pair;
alter table opd.visit validate constraint ck_visit_triage_within_scale;
alter table opd.visit validate constraint ck_visit_triage_emergency;

comment on constraint ck_visit_type on opd.visit is
  'visit_type is NEW, FOLLOW_UP, REVISIT or EMERGENCY (EMERGENCY added by SAH-67-4, ADR-025 D11; widening only).';
comment on constraint ck_visit_triage_emergency on opd.visit is
  'SAH-67-4: an EMERGENCY visit has a triage level and only an EMERGENCY visit has one.';
comment on column opd.visit.triage_level is 'Triage level 1 (most urgent) to 5, set only while visit_type is EMERGENCY (SAH-67-4). Clinical triage fact; never in a log line.';
comment on column opd.visit.triage_scale is 'The triage scale in use when the level was set: 3 or 5 levels (ipd.config.triage_scale, SAH-28, or the default 3 when the clinic has no Emergency setup). triage_level is never above it.';
