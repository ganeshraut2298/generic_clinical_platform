-- SAH-4 · platform.membership : add COUNSELLOR to the allowed staff roles.
-- Mockup step 3 (staff roles) lists COUNSELLOR; docs/api/platform.yaml Role enum updated accordingly.
-- Forward-only: drop and recreate ck_membership_role with COUNSELLOR added, SAH-1 migration untouched.
alter table platform.membership
  drop constraint ck_membership_role;

alter table platform.membership
  add constraint ck_membership_role check (role in
    ('OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR'));
