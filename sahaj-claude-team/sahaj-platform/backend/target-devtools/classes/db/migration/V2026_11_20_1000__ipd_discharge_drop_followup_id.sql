-- SAH-27 follow-up. Forward-only. Drop ipd.discharge.followup_id.
-- Why: ipd.discharge is insert-only (update trigger denies) and the opd.follow_up id exists only after
-- FollowUpPort.createForDischarge, which runs last, so the column could never be filled. The link is traced from
-- opd.follow_up.source_admission_id (unique, V2026_11_18_1010). No code reads or writes the column.
-- Safe: the column is always null, so no data is lost. Dropping a column does not fire row triggers.

-- 1. The check that mentioned the column goes first, by name (it would also go with the column).
--    ck_discharge_followup_due ((discharge_type = 'death') = (followup_due is null)) is not touched.
alter table ipd.discharge drop constraint if exists ck_discharge_followup_id;

-- 2. The column. Its column-level insert grant to sahaj_app is removed with it.
alter table ipd.discharge drop column if exists followup_id;

comment on table ipd.discharge is 'IPD discharge record (SAH-27). Insert only, one per admission. followup_due is null for death. The OPD follow-up is traced from opd.follow_up.source_admission_id.';
