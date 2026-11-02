# Database design · SAH-6 (Team and access)

Module: platform · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-6.md`, `docs/api/platform.yaml` (SAH-6 paths), SAH-1/3/4 design notes, migrations 1000-1019.

## What is reused (nothing duplicated)
- `platform.membership` is the team member (role, status ACTIVE/INACTIVE/REMOVED, `last_login_at`). `platform.app_user` holds the login mobile. `platform.doctor_profile` holds registration no, degree, fee. `platform.audit_event` is the trail. `platform.refresh_token` is revoked on deactivate.
- No invitation table: invitations are out of scope (story section 6). Add staff creates the `app_user` (or reuses one found by mobile) and the `membership` directly.
- Not designed (not in the story): doctor leave and shifts, clinic switch, deactivation reason. Who and when comes from `audit_event`.

## Migrations (1020 onward, forward-only)
| File | Does |
| --- | --- |
| `V2026_11_01_1020__platform_create_role_permission_default.sql` | Global reference table `platform.role_permission_default` (no RLS, 56 rows, SELECT only for the app) |
| `V2026_11_01_1021__platform_create_role_permission.sql` | `platform.role_permission` (RLS, FORCE) and function `platform.effective_role_permissions()` |
| `V2026_11_01_1022__platform_membership_team_columns.sql` | `membership.display_name`, `created_by`, `updated_by` (all nullable) |
| `V2026_11_01_1023__platform_membership_last_owner_guard.sql` | Trigger `trg_membership_last_owner` |
| `V2026_11_01_1024__platform_doctor_profile_specialty_optional.sql` | `doctor_profile.specialty` drops NOT NULL |
| `V2026_11_01_1025__platform_role_permission_default_role_grid.sql` | Updates global defaults for DOCTOR, BILLING, PHARMACIST, COUNSELLOR (data only, no tenant rows) |

## Tables
### platform.role_permission (RLS, FORCE)
| Column | Type | Null | Notes |
| --- | --- | --- | --- |
| tenant_id | uuid | no | default `current_tenant()`, fk `platform.tenant` |
| role | text | no | the 8 roles of the API `Role` enum |
| permission | text | no | token, vitals, collect, cancel, refund, revenue, export |
| allowed | boolean | no | |
| created_at, updated_at, updated_by, version | | | standard |

- PK `(tenant_id, role, permission)` serves "overrides of this clinic" and upsert.
- `ck_role_permission_owner_locked`: OWNER rows must be allowed (mockup locks the owner column on).
- Policy `p_role_permission_tenant` FOR ALL on `current_tenant()`. Grants: SELECT, INSERT, UPDATE. No DELETE.
- Deviation from the story ("seeded per tenant"): only changed cells are stored. Defaults live in `role_permission_default`; the function merges them. Reason: no per-tenant seed step at sign-up, no migration that must write into every tenant under FORCE RLS, and new roles or permissions reach existing clinics with one reference insert.
- Rows: up to 56 per tenant, normally 0-10. About 0.6 million at 10,100 tenants. No partitioning. Never deleted.

### platform.role_permission_default (global, no RLS)
`(role, permission) -> allowed`, from the mockup `S.perm`: Front desk = token, collect. Nurse = token, vitals. Visiting doctor = vitals. OWNER = all. Everything else off. Roles the mockup matrix does not show (DOCTOR, BILLING, PHARMACIST, COUNSELLOR) are an assumption, set by `V2026_11_01_1025__platform_role_permission_default_role_grid.sql` (updates global default rows only; 1020 is unchanged). Needs human confirmation. Final default grid (Y = allowed):

| Permission | Owner | Doctor | Visiting doctor | Nurse | Front desk | Billing | Pharmacist | Counsellor |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| token (make tokens, book appointments) | Y | Y | - | Y | Y | - | - | - |
| vitals (enter vitals) | Y | Y | Y | Y | - | - | - | - |
| collect (collect payments) | Y | - | - | - | Y | Y | - | - |
| cancel (cancel a bill) | Y | - | - | - | - | - | - | - |
| refund (give refunds) | Y | - | - | - | - | - | - | - |
| revenue (see revenue) | Y | - | - | - | - | Y | - | - |
| export (export patient data) | Y | - | - | - | - | - | - | - |

Consult, prescribe, dispense, view patients and notes, and discounts within the limits are not matrix keys. They follow the role (NavigationPolicy) and the clinic discount limits, so they are not stored here. Pharmacist and Counsellor stay "nothing beyond their own steps" because no existing key fits them. The owner can change any cell per clinic.

### platform.membership (changed)
- `display_name text null` (1-200 when set). The clinic's name for the person. `app_user.full_name` is global, so a rename on Team and access must write `display_name`, never `full_name`. Show `coalesce(display_name, full_name)` everywhere (list, audit snapshots, past entries).
- `created_by`, `updated_by uuid null`: actor user id.
- `trg_membership_last_owner` (BEFORE UPDATE OF role, status): refuses with SQLSTATE 23514 and message starting `last_owner` if the change would leave the clinic with no other ACTIVE owner. It locks the other active owners, so two owners switching each other off cannot both succeed. Backstop only; the service must still check first and return the clean 409.

### platform.doctor_profile (changed)
`specialty` is now nullable (check still applies when set), because `DoctorDetails.specialty` is optional on Add staff. SAH-4 reads must tolerate null.

## RLS and access summary
- `role_permission`: tenant policy, fail-closed. `role_permission_default`: global reference, SELECT only.
- Pre-tenant lookups: none needed. Everything SAH-6 does runs with the owner's tenant bound. `app_user` has no RLS, so finding a person by mobile works. Revoking a deactivated person's tokens works with the existing `p_refresh_token_tenant` (UPDATE `revoked_at` where `user_id = :target`). No 1011/1012-style policy is added.

## Retention and compatibility
- Nothing is hard-deleted. REMOVED and INACTIVE keep the row, so past entries keep the name.
- Backward compatible: all changes are additive or relaxing. The previous app version runs unchanged.

## For java-developer
1. Bind `app.tenant_id` as usual. Read the matrix with `select role, permission, allowed from platform.effective_role_permissions()`. Save with `insert ... on conflict (tenant_id, role, permission) do update set allowed = excluded.allowed, updated_at = now(), updated_by = ?, version = platform.role_permission.version + 1`. Reject OWNER `allowed=false` with 400 before the DB does (23514 otherwise). Do not write `tenant_id`; it defaults from the bound tenant.
2. Names: write and rename via `membership.display_name`. On Add staff, if the mobile already exists in `app_user` (another clinic), reuse that row and do not change its `full_name`. If new, set `full_name` and `display_name` to the entered name.
3. Duplicate mobile (AC12): `uq_membership_tenant_user (tenant_id, user_id)` is the guard; map 23505 to 409. INACTIVE and REMOVED rows still count as on the team. Recommended: allow reactivate from INACTIVE and REMOVED, so a removed person can be brought back without a new row.
4. Plan limit: count `membership` rows with `status='ACTIVE'` for the tenant (add and reactivate). To stop two concurrent adds both passing, first run `select id from platform.tenant where id = current_tenant() for update` in the same transaction. The app has UPDATE on `platform.tenant`, so the lock is allowed.
5. Last owner: check in the service; map 23514 whose message starts `last_owner` to 409 as the race backstop. Also treat a PostgreSQL deadlock (40P01) on deactivate/remove as a retryable 409.
6. Deactivate/remove: set `status`, `updated_by`, `updated_at`, bump `version`; in the same transaction `update platform.refresh_token set revoked_at = now() where user_id = :target and revoked_at is null`. The login and the 60-second membership check must require `status='ACTIVE'`.
7. Doctors: for DOCTOR/VISITING_DOCTOR, upsert `doctor_profile` on `membership_id`. Convert `feeRupees` to `fee_paise` (x100). Use `app_user.mobile` as the login mobile; leave `doctor_profile.mobile` null for SAH-6 doctors. Changing a doctor to a non-doctor role keeps the `doctor_profile` row.
8. Masking: the list returns `••••` plus the last 4 of `app_user.mobile`. Audit before/after JSON uses the same masking. `audit_event` insert columns are unchanged (actor_user_id, action, entity_type, entity_id = membership id, before_json, after_json).
9. `last_login_at` already exists on `membership`; nothing new.
