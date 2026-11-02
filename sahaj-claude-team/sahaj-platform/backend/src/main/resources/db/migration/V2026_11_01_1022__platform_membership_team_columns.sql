-- SAH-6 · platform.membership : columns the Team and access page needs.
-- display_name: the name this clinic shows for the person. platform.app_user.full_name is global
--   (one person, many clinics), so an owner renaming a staff member must not rename that person
--   in other clinics. Null means "use app_user.full_name" (all rows written before SAH-6).
-- created_by / updated_by: who made the change, per the platform column convention. The full
--   before and after trail stays in platform.audit_event.
-- Additive and nullable: the previous app version is unaffected. Grants and RLS are table-level
-- and already cover the new columns.
alter table platform.membership
  add column if not exists display_name text,
  add column if not exists created_by uuid,
  add column if not exists updated_by uuid;

alter table platform.membership
  add constraint ck_membership_display_name
  check (display_name is null or char_length(btrim(display_name)) between 1 and 200);

comment on column platform.membership.display_name is
  'Clinic-specific display name. Null = platform.app_user.full_name. SAH-6 renames write this, never app_user.full_name.';
