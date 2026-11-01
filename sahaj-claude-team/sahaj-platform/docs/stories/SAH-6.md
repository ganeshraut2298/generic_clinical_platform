# SAH-6 · Team and access (user management)
Mockup screen: Clinic setup → Team and access
Module: platform

## 1. Story
As a clinic owner, I want to add staff, set their roles and switch them off, so that each person sees only what they should and I can trace every change.

## Acceptance criteria
1. Given the owner adds a doctor with a mobile, then that doctor can log in and sees the doctor menu.
2. Given the owner deactivates a staff member, then their sessions end within one minute and they cannot log in.
3. Given a front-desk user, when they open Team and access, then they cannot change anything.
4. Given the clinic is at its plan's user limit, when the owner adds one more, then it is refused with the plan message.
5. Given any change, then the audit log shows who changed what and when, with before and after values.
6. Given the owner opens the page, then each staff member shows name, mobile masked except the last 4 digits, role, status and last login.
7. Given Add staff, then the form asks Name, Mobile and Role; an invalid name shows "Enter the name" and an invalid mobile shows "Enter a 10-digit mobile number".
8. Given roles, then owner, doctor, visiting doctor, nurse, front desk, billing and pharmacist can be chosen.
9. Given a switched-off person, then the row is dimmed, says "cannot sign in", and their past entries keep their name.
10. Given the last active owner, when anyone tries to deactivate or change them, then it is refused.
11. Given a non-owner calling a change API, then it returns 403.
12. Given a mobile already on the team, then adding it again is refused.
13. Given the permission table "Who can do what", then the owner column is locked on and the owner can tick other roles; changes apply straight away.
14. Given a doctor, then the owner can enter the registration number; a missing one is highlighted, and the note says it prints on every case paper.

## 3. Technical design
- Uses `platform.membership`, `platform.app_user`, `doctor_profile`, `audit_event`. New table `platform.role_permission`: `tenant_id`, `role`, `permission`, `allowed`, RLS, seeded per tenant with mockup defaults: `token`, `vitals`, `collect`, `cancel`, `refund`, `revenue`, `export`.
- Masking happens on the server; the full mobile is never returned by the list API. The owner sees the full number only when typing it on add.
- Deactivate sets `status=INACTIVE` and revokes refresh tokens. The active-membership check from SAH-3 (cache at most 60 seconds) ends access within a minute.
- Remove access sets `status=REMOVED`. No row is ever hard-deleted.
- Limits come from `PlanLimits`; violation returns 409 `plan-limit`, using the same problem type as SAH-4. Reactivation also checks the limit.
- Every change writes `audit_event` with before and after JSON and mobiles masked.
- Owner-only is checked on the server with method security.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/team` | owner, others read per permission | none | `TeamMember[]` | 403 |
| POST | `/team` | owner | `{name, mobile, role, doctor?}` | 201 `TeamMember` | 400, 403, 409 |
| PATCH | `/team/{id}` | owner | `{role?, name?, doctor?}` | `TeamMember` | 400, 403, 404, 409 last owner |
| POST | `/team/{id}/deactivate` | owner | none | `TeamMember` | 403, 404, 409 last owner |
| POST | `/team/{id}/reactivate` | owner | none | `TeamMember` | 403, 404, 409 limit |
| DELETE | `/team/{id}` | owner | none | 204 | 403, 404, 409 last owner |
| GET | `/role-permissions` | owner | none | `RolePermission[]` | 403 |
| PUT | `/role-permissions` | owner | `RolePermission[]` | `RolePermission[]` | 400, 403 |

All paths are under `/api/v1/platform`. Another tenant's id returns 404.

## 5. Tasks
### Tasks — Backend
- Migration, service, endpoints, audit, tenant checks, session revocation.
- Tests: every criterion; deactivated user refused within the cache window; another tenant's member returns 404; masked mobile in every response.

### Tasks — Frontend
- Clinic setup layout with its section nav (shared with SAH-7) and the Team and access page per mockup `cfgTeam`.
- Read-only state for non-owners, with the pill "View only".
- Unit tests. Playwright smoke: add a staff member, deactivate them, see the plan-limit message.

## 6. Out of scope, Open questions
Out of scope: leave and shifts, sending invitations.
Open questions:
- **Shared computer safety** (4-digit PIN, screen lock, owner OTP for refunds) is on this mockup page but not in your requirement. Recommended: show it as "Coming later", and build it with its own story.
- **Roles differ.** The mockup's staff roles are Front desk, Nurse, Counsellor, Attendant and Pharmacist; your list has billing and no counsellor or attendant, and wizard step 3 offers Counsellor. Recommended: use your list plus Counsellor and Attendant so the wizard and this page match, with both mapped to minimal menus.
- **Mobile masking and last login** are in your requirement but not the mockup, which shows the full mobile. Treated as additions unless you object.
- **"Remove access"** is not in the mockup, which only switches people off. Recommended: keep your action, as a soft remove.

## 7. Status log
