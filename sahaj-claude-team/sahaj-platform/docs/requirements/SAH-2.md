# SAH-2 · Tenancy, users, roles and audit
Module: platform
Mockup screen: Foundation (no screen)
Source: tech-lead backlog (playbook), 31 Oct 2026

## Problem

Every later screen in the mockup — the clinic console, the Sahaj admin portal, IPD cards, the
clinic-setup "Team and access" panel — assumes a logged-in person who belongs to exactly one
clinic (or, for the admin portal, to the platform or to one or more organizations), whose role
decides what they may see and do, and whose every action can be traced after the fact. None of
that exists yet (ADR-001, decision D3: no JPA, Flyway or PostgreSQL driver in SAH-1). SAH-2 adds
the tenancy, identity, role/permission and audit foundation so that SAH-1's scaffold can carry
real, isolated, auditable data from SAH-3 onward.

This is a foundation story: it has no screen of its own. It is proven by every later story that
depends on it (sign-in, clinic setup → Team and access, the IPD roles-and-access card, the Sahaj
admin portal's organization isolation check) working correctly, and by cross-tenant access being
provably refused.

## Who uses it (roles) and in which setting

- **Clinic roles** (the mockup's `ROLES` demo set and `PC_R` / "Team and access" full set): owner,
  doctor, visiting doctor, nurse, front desk, billing/accountant. All sign in to one clinic
  (tenant). The owner always has every permission (`can=k=>S.role==='owner'||...`); other roles
  have only what the owner has switched on for them.
- **Platform admin** (Sahaj's own staff, e.g. "Neha Joshi" in `AX_PERSONA.platform`): no single
  organization; can switch between organizations through the admin portal's organization
  directory/switcher, after passing platform sign-in.
- **Organization admin** (e.g. "Dr. Meera Kulkarni" in `AX_PERSONA.org`, `orgs:['o1','o2']`): sees
  and manages only the organizations in that list — never the whole platform, never another
  organization's data.
- **Setting:** a front-desk computer shared by several staff in one clinic (hence PIN sign-in);
  a nurse's device inside a ward (hence per-ward patient scope); a hospital with many branches
  under one owner; Sahaj's own support/operations staff working across many tenant organizations.

## In scope

- The tenant concept: an **organization** (the clinic or hospital group) that owns data, with
  room for one or more **branches** under it, matching `S.branches` and the branch-level rate
  overrides shown in Clinic setup → Tariff. Phase 1 may ship a single default branch per
  organization, but the data must anticipate more than one.
- **`app_user`**: a person who signs in, identified by mobile number (mobile + 6-digit one-time
  code is the mockup's sign-in, `S.auth`, no password for clinic staff), who can also set a
  shared-computer PIN (`S.sec.pin`, 4-digit, "the PIN tells the system who is working").
- **Roles**: the clinic roles listed above, plus platform admin and organization admin. Roles are
  bundles of permissions; the owner can change which role may do which action, per the mockup's
  "Team and access" permission matrix (`S.perm`, `PERM`, `COLS`) and the admin portal's own
  Roles & Permissions screen (`AX_PERMS`, `axRoleData`, custom roles).
- **Permissions**, enforced server-side on every request — the mockup is explicit that hiding a
  button or menu entry is not the protection ("Hiding a button is not the protection. The server
  checks these permissions on every request...").
- **Tenant isolation**: every tenant-owned row carries an organization/tenant ID; PostgreSQL
  row-level security enforces it; the application connects as `sahaj_app`, which cannot bypass
  RLS; the tenant context (`app.tenant_id`) is set per request from the signed-in user's session,
  never from anything the caller sends in the request body or query string. This is the mockup's
  own stated model in "Organization Isolation" (`axIso`): "every record carries its organization
  ID, every query is filtered server-side from the session, and the UI is a convenience, not the
  protection", proven by its isolation check that expects a cross-organization read to come back
  `403 Forbidden · organization in request does not match session` and to be logged.
- **Audit**, two kinds, both append-only (cannot be edited or deleted, by anyone including the
  owner):
  1. **Configuration/action audit** — who changed what and when, with an undo snapshot for the
     most recent entry (`S.audit`, `cfgLog`, and the admin portal's parallel `S.ax.audit`,
     `axAudit`, scoped per organization and shown on "Audit Trail").
  2. **Clinical/record access audit** — who opened, viewed, or acted on which record, append-only,
     cannot be edited or deleted (`S.access`, `ptLog`; and the IPD "Safety" screen's `sfLog`, the
     mockup's own description: "every view of a patient record goes into the access log").
- IPD-specific access rules that this foundation must support, even though the IPD card itself is
  a later story: per-role **scope** (all patients / their wards / their own — `C.acl.scope`,
  `pcVisiblePts`), per-field-group **view/edit** (`C.acl.data`, `none`/`view`/`edit`), per-action
  **allow** (`C.acl.act`), and a time-boxed **emergency access** override outside a nurse's normal
  scope, which is itself logged and later marked reviewed (`sfbg`, `sfbgrev`, "the owner has been
  told").
- The **menu registry** concept the admin portal uses (`AX_APPS`, `S.ax.menu`, `axVisible`): one
  registry of navigable entries, each scoped `platform` or `org`, filtered by persona, role and
  feature flag. SAH-2 establishes that permissions and scope — not menu entries — are the
  authority; the menu itself is built in a later platform story.
- The **owner OTP** step the mockup requires for refunds and data export, and the PIN/idle-lock
  settings the owner can switch for the whole clinic (`S.sec.otp`, `S.sec.lock`).

## Out of scope

- All screens: sign-in UI, "Team and access" UI, the IPD roles-and-access card UI, the admin
  portal's audit/isolation pages. Those are solution-architect/db-engineer/developer and later
  BA stories once SAH-2's rules exist to build against.
- API endpoints, request/response shapes, table and column names, index design — solution-architect
  and db-engineer.
- The OTP delivery mechanism itself (SMS/WhatsApp gateway) — `CLAUDE.md` places notifications
  outside phase 1; see open questions.
- ABDM, HL7, payment gateway identity federation — out of phase 1 per `CLAUDE.md`.
- Health Risk Assessment of any kind — DEC-005 removes it from the product; "HRA" in this and
  every later document means Human Resource management (staff, shifts, roster, leave, attendance,
  holidays), not a clinical risk module.
- Notifications telling the owner about emergency access, exports, or escalations (the mockup's
  toast "the owner has been told") — the notification delivery is Later; this story only requires
  that the triggering event is captured in the audit trail so a later story can notify from it.

## Business rules

1. Every tenant-owned row belongs to exactly one organization (tenant). An organization may have
   one or more branches; branch is a property of the organization's data, not a separate tenant
   boundary — row-level security partitions by organization, not by branch.
2. A clinic-staff `app_user` signs in with a mobile number and a one-time code; no password exists
   for clinic staff roles. Platform admins sign in differently (the mockup shows a 6-digit
   authenticator code, not mobile OTP) — kept as a distinct admin sign-in path, not the same as
   clinic staff sign-in.
3. An `app_user` may optionally have a 4-digit PIN for shared-computer sign-in, scoped to the one
   organization they belong to. PIN sign-in, once switched on for a clinic, is on for every active
   staff member of that clinic (`S.sec.pin` is an organization-wide switch, not per-person opt-in,
   per the mockup: "switched on for everyone").
4. Every permission check happens on the server, for every request, independent of what the UI
   shows or hides. A UI affordance being visible or hidden has no bearing on whether the action is
   allowed.
5. The owner role of a clinic always has every permission that role could have; the owner cannot
   be locked out of their own clinic's permission matrix (mockup: the owner column is permanently
   ticked/locked in "Team and access").
6. A platform admin's session carries no single organization; every admin-portal action that
   targets an organization must identify the target in the session/UI switcher, not trust an
   organization ID in the request, consistent with rule 10.
7. An organization admin's session is scoped to the fixed list of organizations assigned to them.
   They can never read, list, switch to, or act on an organization outside that list, including by
   guessing or supplying its ID directly.
8. A request that carries a tenant/organization identifier in its body or query string that does
   not match the organization bound to the caller's session is refused with `403 Forbidden`, and
   that refusal itself is written to the audit trail. The session's organization always wins; the
   request's claimed organization is never trusted (mirrors the mockup's own isolation check).
9. `app.tenant_id` (or equivalent session tenant context) is set once per request, from the
   authenticated session, before any tenant-scoped query runs, and is never derived from
   request-body or query-string input.
10. The database role the application uses to serve requests (`sahaj_app`) cannot bypass row-level
    security under any circumstance, including administrative or support actions; there is no
    superuser escape hatch reachable from request-handling code. Flyway migrations run as a
    separate role (`sahaj_migrator`) that is never used to serve requests.
11. Configuration/action audit entries record at minimum: who (the acting user), what changed,
    and when, plus enough of a before-state to support one-step undo of the most recent change.
    Audit entries, once written, cannot be edited or deleted by any role, including the owner and
    platform admin.
12. Clinical/record access audit entries record at minimum: who, what record, what action (view,
    edit, print, collect payment, etc.) and when. They are append-only: no update or delete path
    exists for any role.
13. A nurse's (or any scoped clinical role's) default visibility of patient records is limited by
    the scope the owner has set for their role: all patients, their ward(s) only, or only patients
    assigned to them. The owner's own scope is always "all patients" and cannot be narrowed.
14. Emergency access that lets a clinical user see a record outside their normal scope is
    time-boxed (the mockup uses 30 minutes), requires a stated reason, is written to the
    append-only access audit at the moment it is opened, and surfaces for later review (the mockup
    shows the owner marking it "reviewed"); it cannot be opened silently or retroactively.
15. A role without a given permission receives `403 Forbidden` when it attempts the corresponding
    action, whatever the requesting UI displayed. Malformed or missing required input on an
    otherwise-permitted request receives `400 Bad Request`, not `403`.
16. Owner OTP, where the owner has switched it on, is required before a refund or a data export
    completes, even for a user who already knows the owner's PIN — i.e. OTP is a second factor on
    top of normal sign-in/permission, not a replacement for either.
17. Any user whose account is switched off (`staffon` → "cannot sign in") is refused sign-in and
    every API call, immediately, regardless of any still-valid session token they may be holding
    — session validity must be re-checked against current account status, not cached indefinitely.

## Patient-safety and privacy points

- Record access must be traceable to a named person at all times (append-only access audit) —
  this is what lets a safety review reconstruct who saw or changed a patient's record and when,
  matching the mockup's IPD Safety screen use of the same audit trail for escalation and
  medicine-administration review.
- Emergency (break-glass) access to a patient record outside a clinician's normal scope must never
  be invisible: it is logged at the moment it happens and flagged for the owner to review, so that
  "I needed to see this patient urgently" is always auditable, not a bypass of role scope.
- A wrong-patient (wristband-mismatch) stop, a blocked medicine order, and a doctor's override of a
  safety warning all appear in the mockup's clinical audit (`sfLog`) as `deny`/`order` entries —
  SAH-2 must guarantee the audit mechanism itself cannot be defeated (no edit, no delete) so that
  these safety-critical entries are permanently trustworthy. The clinical content of those checks
  (allergy, duplicate, interaction logic) is out of scope for SAH-2; only the audit guarantee is.
- Staff (HR) data and patient clinical data must never share a tenant-isolation or audit boundary
  in a way that lets one leak into the other's access log — consistent with DEC-005's reasoning
  for keeping `hra` (Human Resource management) and clinical data apart.
- No patient name, mobile number, ABHA number or clinical note is ever written into a log that is
  not the designated, access-controlled audit trail (`CLAUDE.md`: "Never log patient names, mobile
  numbers, ABHA numbers or clinical notes" — this applies to application/diagnostic logs; the
  access audit itself is a privacy-sensitive store that must be protected like patient data, not
  an exemption from this rule).

## Plan or entitlement

Tenancy, identity, roles, permissions, RLS and audit are foundation for every plan; they are not
gated by an entitlement flag themselves. (Branch-level rate overrides, multi-branch, and the
organization-admin layer may map to specific plan tiers — left as an open question below, since
the mockup does not show plan-gating for these specifically.)

## Open questions for the human

1. **OTP delivery.** The mockup signs clinic staff in with a mobile one-time code and shows SMS as
   the channel ("Code sent to +91 ..."). `CLAUDE.md` places notifications (SMS/WhatsApp) outside
   phase 1 as a separate service. Which provider sends the OTP in phase 1, and what happens when
   that provider is unreachable or the code never arrives — is there a fallback channel, a manual
   verification path, or does sign-in simply fail until retried? This blocks the architect's choice
   of whether phase 1 needs a minimal OTP-sending integration despite "notifications are Later."
2. **Organization layer above the clinic.** The mockup's admin portal shows Sahaj → Partner →
   Clinic group → Clinic, and an organization admin managing several clinics (`AX_PERSONA.org.orgs
   = ['o1','o2']`). Is a multi-clinic organization account in scope for phase 1 tenancy (i.e. can
   one `app_user` with the organization-admin role legitimately span more than one tenant/clinic
   in the database), or does phase 1 ship one tenant = one clinic, with the organization-admin
   persona deferred? This changes whether "tenant" and "organization" are the same row or two
   related concepts.
3. **Audit module ownership.** `CLAUDE.md` names `platform` as the module owning tenancy, users
   and roles. Confirm the append-only clinical/record access audit (used by `opd`/`ipd` screens
   too) also lives in `platform`'s schema/package, rather than being duplicated or owned per
   clinical module. If it is to live in `platform`, how do `opd`/`ipd`/`billing` modules write to
   it without violating the "modules talk only through `api` or domain events" rule — is that an
   architecture question for the solution-architect to answer, or does the human want to decide
   the module boundary itself first?
4. **Platform admin sign-in.** The mockup shows platform admins signing in with a 6-digit
   authenticator code (not mobile OTP, not clinic PIN) — is that a distinct sign-in mechanism
   (e.g. TOTP/authenticator app) to actually build in phase 1, or a mockup placeholder?
5. **PIN length and strength.** The mockup uses a 4-digit PIN for shared-computer sign-in. Is a
   4-digit PIN (10,000 combinations, shared-computer context) acceptable for phase 1, or does the
   human want a minimum PIN strength/lockout-after-attempts rule specified now, before the
   db-engineer and developers build it?
6. **Owner OTP channel and fallback.** Same open question as #1, specifically for the owner's
   refund/export OTP (`S.sec.otp`) — same provider, same fallback question.
7. **Session/token lifetime and revocation.** The mockup does not show session length or "sign out
   all devices." Is there a required maximum session lifetime, idle timeout beyond the
   shared-computer screen lock (`S.sec.lock`), or forced-logout-on-role-change rule the human wants
   specified before the architect designs sessions?
8. **Branch as tenant boundary or not.** Confirmed in-scope as a data concept (rule 1), but should
   RLS ever partition by branch (e.g. a front-desk user restricted to one branch's patients) in
   phase 1, or is branch purely a billing/rate concept with no access-scoping implication until a
   later story? The mockup does not show a branch-scoped role.
