-- SAH-6 · platform.role_permission_default : the "Who can do what" defaults, global reference data.
-- Source: mockup cfgTeam / S.perm defaults (Front desk, Nurse, Visiting doctor columns).
-- Global like platform.app_user: no clinic owns these rows, so no row level security.
-- A clinic overrides single cells in platform.role_permission; platform.effective_role_permissions()
-- merges the two. Roles the mockup matrix does not show (DOCTOR, BILLING, PHARMACIST, COUNSELLOR)
-- are a flagged assumption, see docs/database/SAH-6.md.
create table if not exists platform.role_permission_default (
  role       text not null
             constraint ck_role_permission_default_role check (role in
               ('OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR')),
  permission text not null
             constraint ck_role_permission_default_permission check (permission in
               ('token', 'vitals', 'collect', 'cancel', 'refund', 'revenue', 'export')),
  allowed    boolean not null,
  constraint pk_role_permission_default primary key (role, permission),
  constraint ck_role_permission_default_owner check (role <> 'OWNER' or allowed)
);

-- 8 roles x 7 permissions = 56 rows. Everything is off unless listed; OWNER is always on.
insert into platform.role_permission_default (role, permission, allowed)
select r.role, p.permission,
       r.role = 'OWNER'
       or (r.role, p.permission) in (
            ('FRONT_DESK', 'token'), ('FRONT_DESK', 'collect'),
            ('NURSE', 'token'), ('NURSE', 'vitals'),
            ('VISITING_DOCTOR', 'vitals'), ('DOCTOR', 'vitals'))
from (values ('OWNER'), ('DOCTOR'), ('VISITING_DOCTOR'), ('NURSE'),
             ('FRONT_DESK'), ('BILLING'), ('PHARMACIST'), ('COUNSELLOR')) as r (role)
cross join (values ('token'), ('vitals'), ('collect'), ('cancel'),
                   ('refund'), ('revenue'), ('export')) as p (permission)
on conflict (role, permission) do nothing;

-- Read-only for the app. Changed only by a later migration.
revoke all on platform.role_permission_default from sahaj_app;
revoke all on platform.role_permission_default from public;
grant select on platform.role_permission_default to sahaj_app;

comment on table platform.role_permission_default is
  'Reference data: default role by permission matrix from the mockup. Global, read-only for the app.';
