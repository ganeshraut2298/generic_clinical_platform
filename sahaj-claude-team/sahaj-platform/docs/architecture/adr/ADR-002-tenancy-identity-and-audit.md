# ADR-002 · Tenancy, identity, authorization and audit

Story: SAH-2 · Tenancy, users, roles and audit
Mockup screen: Foundation (no screen)
Module: platform
Status: PROPOSED (31 Oct 2026) — awaiting `SAH-2 · contract · APPROVED`
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Supersedes: none · Superseded by: none
Related: `docs/requirements/SAH-2.md`, `docs/stories/SAH-2.md` (SAH-2-1..SAH-2-6),
`docs/architecture/adr/ADR-001-scaffold-baseline.md` (D2, D3),
`docs/reviews/SAH-1-security.md` (M2, and the `FORCE ROW LEVEL SECURITY` note),
`docs/decisions/DEC-005-hra-means-two-things.md`, `docs/api/platform.yaml`

---

## Context

SAH-1 produced a skeleton with no database on the startup path (ADR-001 D3). SAH-2 is the story
that reverses that and lays the foundation every later story stands on: a tenant boundary that
holds, a person who can sign in, a permission that is checked on the server, and an audit trail
that cannot be rewritten.

Everything below is anchored in `docs/design/mockup/sahaj-mockup.html`, which is the design
authority. The passages that drive these decisions:

| Mockup | What it fixes |
| --- | --- |
| `axIso` · "Organization Isolation" | "Every record carries its organization ID"; "Every query is filtered on the server — the organization comes from the signed-in session, never from the page"; the isolation check returns `403 Forbidden · organization in request does not match session` **and is logged**; "Sahaj staff see counts only. Opening records needs the owner's OTP and is logged." |
| `pLogin` · `sendotp` / `verify` | Clinic staff sign in with a 10-digit mobile number and a 6-digit one-time code. "No password to remember: a one-time code each time." |
| `admin-in` | Platform/organization admins sign in with a **6-digit code from an authenticator app** — a different mechanism from clinic staff. |
| `S.sec` = `{pin:false, lock:10, otp:true}`, panel `f-sec` | A clinic-wide PIN switch ("Everyone signs in with their own 4-digit PIN … The PIN tells the system who is working"), an idle screen-lock in 5-minute steps, and "OTP to the owner's phone for refunds and data export — even if someone knows the owner's PIN". |
| `S.staff[].on` · "cannot sign in" | A person is switched off, not deleted: "Their past entries stay in the records under their name." |
| `S.perm` / `PERM` / `COLS` / `can()` | The owner column is a padlock, never a checkbox: `can = k => S.role==='owner' || !!(S.perm[k]&&S.perm[k][S.role])`. |
| `AX_PERMS` · `axRoleData` · `axRoles` | Nine permission groups with per-action ticks, system roles plus custom roles per organization, and: "The server checks these permissions on every request; hiding a menu item never replaces them." |
| `axMenuMgmt` · Menu Visibility | "Hiding a menu item only tidies the screen. The server still checks the permission (`platform.orgs.view`, for example) on every request." |
| `S.audit` · `cfgLog` · `cfgSnap` · `cfgundo` | Configuration audit: who, when, what, plus a snapshot; "The latest one can be undone"; undo is owner-only. |
| `S.access` · `ptLog` | Access audit: "Pooja Gaikwad opened the file of Sneha Kale, 10:58 AM". Every view, vitals entry, print and collection. |
| `sfLog` · `sfbg` · `sfbgrev` | Clinical audit with kinds `view`/`give`/`order`/`deny`/`esc`/`bg`; break-glass "Open for 30 minutes" with a stated reason, then "Mark reviewed". |
| `C.acl` (`scope` / `data` / `act`), `pcVisiblePts` | Per-role patient scope `all`/`ward`/`mine`, per-field-group `none`/`view`/`edit`, per-action allow. |
| `AX_PERSONA` · `axCanOrg` · `axSwitchOrg` | Platform admin: `orgs:[]`, switches into any organization. Organization admin: `orgs:['o1','o2']`, fixed list. |
| `AX_APPS` · `S.ax.menu` · `axVisible` | One menu registry, each entry scoped `platform` or `org`, each carrying a required `perm`. |

Two documents outside the mockup also bind this ADR. **DEC-005**: `hra` is Human Resource
management; there is no Health Risk Assessment module, and staff (HR) data must not share an audit
or isolation boundary with patient clinical data. **`CLAUDE.md`**: six modules, cross-module access
only through another module's `api` package or a domain event, `tenant_id` + row-level security,
`sahaj_app` cannot bypass RLS, Flyway runs as `sahaj_migrator`, and no gateway, notifications,
documents or analytics service in phase 1.

---

## Decisions

### D1 · The tenant is the organization. Branch is a column, not a boundary

One RLS key: `tenant_id`, which **is** the organization id. Every tenant-owned row in every
module's schema carries it. A `branch_id` column exists on rows that belong to a branch, and
branch is used for rate overrides (Clinic setup → Tariff) and reporting, but **no RLS policy
partitions by branch in phase 1.**

Answering requirement open question 8 (architectural, so I answer it): branch must not be an RLS
boundary now.

- The mockup has no branch-scoped role. `C.acl.scope` offers `all` / `ward` / `mine` — ward, not
  branch. Inventing a branch predicate would be designing a rule the mockup does not contain.
- `axIso` has an explicit switch, "Patient records shared between this organization's branches …
  Never shared with other organizations". Sharing across branches is the organization's own
  setting. A second RLS predicate would hard-code the opposite of a configurable choice.
- Adding branch to RLS later is additive (a second session setting and an extra `AND` in the
  policy). Removing it is not. Start with the boundary the mockup states and nothing wider.

What the db-engineer must still do: put `branch_id` on the tables that need it now, so the later
story is an `ALTER POLICY`, not a data backfill.

### D2 · `app.tenant_id` is bound at the start of every transaction, from the session only

Three layers, in order.

**1 · Resolve.** A servlet filter (`TenantContextFilter`), ordered immediately after the Spring
Security filter chain, reads the authenticated principal, loads the session row, and places an
immutable `TenantContext { actorKind, userId, tenantId, sessionId, state }` in a request-scoped
`ThreadLocal`, cleared in a `finally` block. It reads the tenant from the **session row in the
database**, never from the request. `tenant_id` is not accepted from a body field, a query
parameter, a path segment or a header — not even `X-Tenant-Id`, which does not exist in this API.

**2 · Bind.** The tenant reaches PostgreSQL as the first statement of every transaction:

```sql
SET LOCAL app.tenant_id   = '…';   -- omitted entirely for a platform session with no active org
SET LOCAL app.actor_kind  = 'clinic' | 'platform';
SET LOCAL app.actor_id    = '…';
```

Chosen mechanism: a Spring AOP advice ordered **inside** `TransactionInterceptor`, so it runs
after the transaction has started and before any repository call, issuing the `SET LOCAL`
statements through the transaction's own `EntityManager`.

Options considered:

- **A · A `DataSource` proxy that runs `SET SESSION` on `getConnection()` and `RESET` on
  `close()`.** Rejected. It needs `SET SESSION` (connection-scoped) because the pool hands out
  connections outside a transaction, and that makes correctness depend on the reset actually
  running. A missed reset — an exception path, an evicted connection, a `Statement` leak — leaves
  one tenant's identity on a pooled connection for the next request. The failure is silent and it
  is a cross-tenant data leak, the one failure we cannot accept.
- **B · Hikari `connection-init-sql`.** Rejected outright. It runs once per *physical* connection,
  so it cannot carry a per-request value at all.
- **C · `SET LOCAL` inside the transaction (chosen).** `SET LOCAL` is transaction-scoped:
  PostgreSQL discards it at `COMMIT` **and** at `ROLLBACK`. There is no reset to forget and no
  code path that can skip the cleanup, because the cleanup is the transaction ending. The cost is
  that every tenant-scoped operation must run inside an explicit transaction — which D4 makes a
  build failure rather than a hope.

**3 · Fail closed.** The policy predicate in D3 reads `current_setting('app.tenant_id', true)`,
which returns `NULL` when the setting is absent. `tenant_id = NULL` is `NULL`, which is not
`TRUE`, so **no row is visible and no row can be inserted.** Missing tenant context therefore
yields an empty result set, never "all tenants". There is no `COALESCE` to a wildcard anywhere in
any policy, and a reviewer should treat one as a blocker.

Fail-closed also applies above the database: `TenantContextFilter` rejects the request before any
handler runs when the session is absent (`401`), revoked or belonging to a switched-off account
(`401`), idle-locked (`403 session-locked`), or when a tenant-scoped route is reached by a session
with no bound tenant (`403 no-active-organization`). A platform-admin session that has not switched
into an organization is exactly this last case, and the empty result set in layer 3 is its second
line of defence.

**The one legitimate tenant identifier in a request.** The unauthenticated sign-in endpoints
(`POST /auth/clinic/code-request`, `/auth/clinic/code-verify`, `/auth/clinic/pin`) take a clinic
identifier, because there is no session yet to take it from. That identifier selects the tenant the
new session will be *bound to*; it never widens an existing session. Every authenticated endpoint
is subject to D6's mismatch rule instead.

### D3 · RLS policy shape, and why the owner must not be trusted either

Tables are owned by `sahaj_migrator`. `sahaj_app` is `NOSUPERUSER NOBYPASSRLS`, is not the owner of
any table, and holds only the `GRANT`s it needs. `scripts/db/init.sql` already creates it that way.

One helper and one policy per tenant table:

```sql
CREATE FUNCTION platform.current_tenant() RETURNS uuid
  LANGUAGE sql STABLE
  AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

ALTER TABLE <schema>.<table> ENABLE  ROW LEVEL SECURITY;
ALTER TABLE <schema>.<table> FORCE   ROW LEVEL SECURITY;

CREATE POLICY p_tenant ON <schema>.<table>
  FOR ALL TO sahaj_app
  USING      (tenant_id = platform.current_tenant())
  WITH CHECK (tenant_id = platform.current_tenant());

ALTER TABLE <schema>.<table>
  ALTER COLUMN tenant_id SET DEFAULT platform.current_tenant();
```

Three things in that block are load-bearing.

**`FORCE ROW LEVEL SECURITY`, not just `ENABLE`.** This is the point the security reviewer raised
on SAH-1 (`docs/reviews/SAH-1-security.md`): *a table owner bypasses RLS unless `FORCE ROW LEVEL
SECURITY` is set.* Without `FORCE`, `sahaj_migrator` — which owns every table — sees and writes
every tenant's rows with policies silently inert. That matters in two ways. It makes any
migration, backfill or support query run by the migrator a cross-tenant operation by default. And
it makes *verification* worthless: a test that connects as the migrator proves nothing, because
the migrator was never subject to the policy. So:

> **Policies are verified as `sahaj_app`. Never as `sahaj_migrator`, never as a superuser.**
> A cross-tenant test that passes as the migrator is a false negative and must be rejected in
> review.

`FORCE` does not bind a superuser. That is why there is no superuser credential anywhere on the
request path and why `sahaj_app` is `NOSUPERUSER` — the only remaining bypass is a role the
application cannot become.

**`tenant_id NOT NULL DEFAULT platform.current_tenant()`.** The application never writes
`tenant_id`; the database fills it from the session. This is how SAH-2-1's first acceptance
criterion ("it carries the organization ID of the session that created it, set by the application,
not supplied by the caller") is satisfied structurally rather than by every developer remembering.
`WITH CHECK` then rejects a row whose `tenant_id` disagrees with the session even if some code
does set it explicitly.

**Platform-scope tables.** The tenant directory, the menu registry, global masters, platform users,
platform sessions and the platform-scope audit have no `tenant_id`; they are not tenant-owned. They
get a different policy, keyed on the actor kind bound in D2:

```sql
CREATE POLICY p_platform ON platform.<table>
  FOR ALL TO sahaj_app
  USING (current_setting('app.actor_kind', true) = 'platform');
```

with one exception: the `tenant` (organization) row itself is readable by its own clinic session
through a second policy `USING (id = platform.current_tenant())`, because a clinic must be able to
read its own profile. Nothing lets a clinic session read another organization's row.

This concentrates trust in one place: the D2 binding advice is the **only** code permitted to
issue `SET LOCAL app.*` or `set_config('app.…')`. D4 makes that an enforced rule, not a note.

### D4 · Pooling, and the rules that keep `SET LOCAL` honest

- **Two datasources, two roles.** The request-serving Hikari pool authenticates as `sahaj_app`. A
  second, separate datasource for Flyway authenticates as `sahaj_migrator` with
  `maximum-pool-size: 1`, and is closed after migration. The request pool never holds migrator
  credentials, so no request-handling code path can reach a role that owns tables.
- **Why pooling is safe here.** `SET LOCAL` lives and dies with the transaction. A connection
  returned to the pool after commit or rollback carries no `app.*` setting, so the classic
  "pooled connection leaks the previous tenant" failure cannot occur. Hikari needs no custom
  eviction, no `connection-test-query` trick and no per-tenant pool.
- **`spring.jpa.open-in-view: false`.** Not a style preference. With it `true`, Hibernate keeps a
  session open past the transaction and lazy loads fire outside it, where `SET LOCAL` has already
  been discarded — queries would then run with no tenant and silently return nothing (or, worse,
  if anyone ever "fixes" that with a session-scoped setting, everything). Reviewers should treat
  `open-in-view: true` as a blocker.
- **No autocommit data access.** In autocommit mode `SET LOCAL` affects only the statement it is
  in, so tenant data access must be inside an explicit transaction. Enforced by the binding advice
  itself: if `TransactionSynchronizationManager.isActualTransactionActive()` is false when a
  tenant-scoped repository is entered, it throws. An unbound query becomes a loud failure in
  development instead of an empty list in production.
- **Three enforcement tests** (qa-engineer / java-developer):
  1. No occurrence of `SET LOCAL app.`, `set_config('app.`, `SET SESSION` or `connection-init-sql`
     anywhere in `backend/src/main` outside the single binding class.
  2. Every table with a `tenant_id` column has `pg_class.relrowsecurity` **and**
     `relforcerowsecurity` true and at least one policy. (`scripts/validate.mjs` already fails a
     migration that creates a `tenant_id` table without `ENABLE ROW LEVEL SECURITY`; the `FORCE`
     half is new — see the handoff, the script is human-owned.)
  3. A cross-tenant test executed **as `sahaj_app`** for every tenant table: tenant A's session
     sees zero of tenant B's rows and cannot insert a row carrying B's id.
- **Threads other than the request thread.** A `ThreadLocal` does not cross `@Async`,
  `@Scheduled` or Spring Modulith's `@ApplicationModuleListener` (which is `@TransactionalEventListener`
  + `@Async`). Rule: every domain event carries its `tenantId` in the payload, and the listener
  re-binds it through the same advice before touching data. A listener that reads the ambient
  `TenantContext` is a bug; there is nothing to read.

### D5 · Identity: two separate domains, opaque server-side sessions

**Clinic staff (`app_user`).** Belongs to exactly one tenant. Identified by a 10-digit Indian
mobile number. No password exists — the mockup is explicit ("No password to remember: a one-time
code each time"). Sign-in paths:

- **Mobile + one-time code.** 6 digits, 5-minute validity, single use, bound to one request. At
  most 5 verify attempts per code and 5 code requests per mobile per hour. Both the
  "mobile unknown" and "code wrong" cases return the same generic failure, so the endpoint is not
  a staff-directory oracle.
- **Shared-computer PIN.** 4 digits, as the mockup shows. Available only when the clinic's
  organization-wide `pinSignIn` switch is on — it is "switched on for everyone", not a per-person
  opt-in (requirement rule 3). The PIN identifies the individual, so the resulting session is that
  named person's, never a shared one; this is what makes `ptLog`-style attribution possible. PIN
  hashed with Argon2id, never stored or logged in clear. 5 consecutive failures lock PIN sign-in
  for that user for 15 minutes; mobile OTP still works, so a locked PIN never locks a nurse out of
  a ward.
- Setting or changing one's own PIN requires a fresh one-time code. A PIN cannot be set by anyone
  other than its owner, including the clinic owner — the owner can only send a setup link
  (`staffpin`, "Send PIN link").

**Sahaj's own staff (`platform_user`) — a separate identity domain.** Different table, different
sign-in endpoint, different session table, different token audience. A platform user is never an
`app_user` and never holds a clinic role.

Answering requirement open question 4 (architectural, so I answer it with a recommendation):
**build it as TOTP (RFC 6238), not as a placeholder.** The mockup shows "Code from authenticator
app", and TOTP is the one strong second factor that needs **no** notifications service — which is
exactly the constraint phase 1 is under. Building mobile OTP for platform staff instead would
create the dependency `CLAUDE.md` excludes. TOTP is ~40 lines against a standard library.
Confirm with the human, but the phase-1-compatible answer is the one the mockup already draws.

Why two tables rather than one `app_user` with a nullable `tenant_id`: a nullable tenant key
destroys D3's invariant. `tenant_id NOT NULL` plus a fail-closed policy is the whole isolation
argument; a table where `NULL` means "belongs to everyone" needs a special-case policy, and that
special case would sit on the table holding every credential in the system. Separate domains keep
every tenant table strictly `NOT NULL`.

**Sessions are opaque, server-side and re-checked.** A 256-bit random token, stored only as a
SHA-256 hash in `platform.user_session`, presented as `Authorization: Bearer <token>`.

Not a self-contained JWT. Requirement rule 17 demands that switching an account off refuses "every
API call … immediately", and SAH-2-3 demands a permission change take effect "for every subsequent
request … without requiring them to sign in again". A JWT carries a snapshot of exactly the facts
that must not be stale. The cost is one primary-key lookup per request, which is the cheapest query
in the system; at 10,000 clinics it is not the bottleneck, and no cache is introduced in phase 1
because "immediately" leaves no room for one.

Answering requirement open question 7 (architectural, so I propose; human to confirm the numbers):

| Property | Value | Why |
| --- | --- | --- |
| Absolute session lifetime | 12 hours | One clinic working day. A front-desk session should not survive to the next morning. |
| Idle lock | the clinic's `S.sec.lock`, default 10 minutes, range 5–60 in steps of 5 | The mockup's stepper and default. Range chosen by me; it is an operational setting, not a clinical rule, and is cheap to change. |
| Idle lock behaviour | session → `LOCKED`, not ended | Mockup: the screen locks, the sign-in does not end. Unlock with PIN, or with a one-time code where no PIN is set. |
| Locked session calling an API | `403` with problem type `session-locked` | Distinguishable from a real permission failure so the UI can show the lock screen rather than "not allowed". |
| Account switched off | all that user's sessions revoked in the same transaction, **and** `app_user.active` re-checked per request | Belt and braces: revocation handles the normal path, the per-request check handles a row written by any other path. |
| Role or permission change | nothing to invalidate — permissions are resolved per request | SAH-2-3. |
| Sign-out | revokes the presented session only | "Sign out everywhere" is not in the mockup; not built. |

**Where the OTP sender plugs in, without building it.** One internal interface,
`OneTimeCodeSender`, with exactly one method. Phase 1 wiring:

- `dev` profile → a logging implementation that records the code with the mobile number **masked to
  its last 4 digits**. It exists so a developer can sign in locally.
- `test` profile → a deterministic code generator so integration tests can verify without reading
  a hash.
- any other profile → **no implementation is wired**, and `POST /auth/clinic/code-request` answers
  `503` with problem type `otp-channel-unavailable`.

That is the honest phase-1 position: requirement open questions 1 and 6 (which provider, what
fallback) are the human's to answer, and until they do, the port exists and the adapter does not.
Nothing in SAH-2 builds a notifications service, and nothing in SAH-2 pretends an SMS was sent.

### D6 · Authorization: permission per request, menus are decoration

**Model.** `permission` (a catalogue of stable keys) → `role` (tenant-scoped; system roles seeded
per tenant, plus custom roles) → `role_permission` (the owner's editable matrix) →
`app_user_role`. Keys follow the shape the mockup's own menu registry uses — `platform.orgs.view`
for platform scope, `clinic.<area>.<action>` for organization scope, derived from `AX_PERMS`:

```
clinic.patients.{view,create,edit,merge,export}   clinic.billing.{view,collect,discount,refund,cancel}
clinic.appointments.{view,create,cancel}          clinic.reports.{view,export}
clinic.queue.{view,create,move}                   clinic.masters.{view,edit}
clinic.consultation.{view,write}                  clinic.settings.{view,edit}
clinic.vitals.enter                               clinic.users.{view,edit}
clinic.audit.view                                 clinic.emergency_access.{open,review}
platform.orgs.{view,edit}  platform.users.{view,edit}  platform.roles.{view,edit}
platform.menu.edit         platform.audit.view
```

SAH-2-3 says this story does not freeze the key names, so the catalogue is data in a table, not an
enum in code, and a later module story adds keys with a migration rather than a contract change.

**The owner is a padlock, not a checkbox.** `role.kind = 'OWNER'` short-circuits the resolver to
"allow", exactly as `can()` does in the mockup. No `role_permission` row can reduce it, and any
write to the owner role's grants is refused `409`. Likewise `role_scope` for the owner is fixed at
`ALL` and cannot be narrowed (requirement rule 13).

**One enforcement point, default deny.** Every controller operation carries
`@RequiresPermission("…")`. A convention test enumerates every request-mapped handler method and
**fails the build** if one has neither `@RequiresPermission` nor an explicit `@PublicEndpoint`.
This matters more than the check itself: the realistic failure mode is not a wrong permission key,
it is a new endpoint with none, and that should break `mvn verify` rather than ship.

**Hiding is never protecting.** The menu registry stores a `perm` per entry and the admin portal's
Menu Visibility screen says so in its own words. This ADR restates it as a rule for reviewers: a
finding of the form "the button is hidden for that role" is never an answer to "is the endpoint
protected". The server check is independent of, and unaware of, what the UI rendered.

**Row scope and field groups (SAH-2-4) sit inside the tenant boundary, not in RLS.**
`role_scope.patient_scope ∈ {ALL, WARD, OWN}` and `role_field_group_access.access ∈
{NONE, VIEW, EDIT}` are stored in `platform` and exposed through `platform.api`. They are applied
as a query predicate and a response projection **in the owning module** (`patient`, `ipd`),
because `WARD` depends on ward and nurse-assignment data that `ipd` owns and `platform` must not
read. Reasoning for keeping them out of RLS: an RLS policy on a patient table would have to join
to `ipd`'s assignment tables, which is cross-module table access — the exact thing `CLAUDE.md`
forbids — and would put clinical workflow logic in a database policy where no one reviews it.

The non-negotiable part: the scope predicate is applied in **one** shared place per module, before
any caller-supplied search, filter, sort or pagination parameter, so that no combination of
query parameters can widen it. RLS remains the hard tenant boundary; scope is a strictly narrower
filter *within* one tenant. A scope failure is never the only thing between two tenants.

**Out-of-scope versus out-of-tenant, and what status code says what.** Getting this wrong leaks
existence, so it is fixed here:

| Situation | Status | Reason |
| --- | --- | --- |
| Request body/query names an organization that differs from the session's | **403** `tenant-mismatch`, audited | The mockup's own isolation check: "403 Forbidden · organization in request does not match session … blocked, and logged in the audit trail". |
| Addressing an id that belongs to another tenant, with no organization named | **404** | RLS makes the row absent. Returning 403 would confirm it exists elsewhere. |
| Addressing a patient inside the tenant but outside the caller's scope | **404** | Same reason: 403 would confirm the patient exists in this clinic to someone not allowed to know. |
| Role lacks the permission for the operation | **403** `permission-denied`, audited | SAH-2-3, requirement rule 15. |
| Writing a field group the role has `NONE` on | **403** `field-group-denied` | SAH-2-4. Reads omit the group instead. |
| Malformed or missing input on an otherwise-permitted request | **400** | Requirement rule 15: 400, never 403. Validation runs before the permission check only where it must; the distinction the story cares about is that a role failure is never reported as bad input and vice versa. |

**Break-glass.** `emergency_access_grant { actor, module, entity_type, entity_id, reason,
opened_at, expires_at = opened_at + 30 min, reviewed_at, reviewed_by }`, matching `sfbgok`. No
reason → `400`, no grant. The grant is one more input to the scope predicate, so an expired grant
simply stops widening it — there is no separate expiry job and no silent continuation. Opening it
writes an access-audit entry of kind `BREAK_GLASS` in the same transaction as the grant, so a
grant without an audit row cannot exist.

### D7 · Audit: a narrow `platform.api` port, called synchronously — not a domain event

This answers requirement open question 3, which the business analyst routed here: *how do `opd`,
`ipd` and `billing` write to an audit log owned by `platform` without breaking the boundary?*

**Decision: a port.** `com.sahaj.platform.api.AuditPort`, with two methods — one for the
configuration/action trail, one for the record-access trail. Each module calls it **inside its own
transaction**. Modules never see `platform.config_audit_event` or `platform.access_audit_event`;
they pass a value object and `platform` owns the table, the schema and the shape.

Options considered:

- **A · Each module publishes a domain event; `platform` consumes it (rejected).**
  - Spring Modulith's `@ApplicationModuleListener` is `@TransactionalEventListener(AFTER_COMMIT)`
    plus `@Async`. The consequence is that the clinical write commits first and the audit row is
    attempted afterwards, in a different thread and a different transaction. If that second
    transaction fails, the action happened and the audit says it did not. For a trail whose
    purpose is reconstructing who gave which medicine (`sfLog` `give` / `deny` / `order`), silent
    loss is the one outcome that must be impossible.
  - Half the entries SAH-2 requires are **refusals**: a cross-tenant attempt (SAH-2-1), a
    permission denial (SAH-2-3), a rejected emergency access. A refused request has no business
    transaction to commit, so there is no "after commit" for an event to hang off.
  - Modulith's event publication registry can make delivery durable, but it does so by writing a
    row in the publisher's transaction — i.e. it reintroduces a synchronous write and then adds an
    asynchronous hop, retries and a replay story on top. More moving parts for a weaker guarantee.
- **B · A narrow `platform.api` port, synchronous, same transaction (chosen).**
  - It is already legal: `CLAUDE.md` permits a module to depend on another module's `api` package.
    No new rule, nothing for Spring Modulith's boundary test to be taught.
  - The dependency direction is already correct. `platform` is the foundation; `opd`, `ipd` and
    `billing` depend on it. Events are the tool for the *wrong* direction — when the depended-upon
    module needs to react to the depender and a direct call would cycle. There is no cycle to
    break here, so an event buys nothing and costs atomicity.
  - Atomicity is the whole point: the audit row and the action it describes commit or roll back
    together. "Rolled back, so never happened, so not audited" is correct; "committed but
    unaudited" is not.
  - It is testable in one transaction, which QA can assert directly.
- **C · Each module owns its own audit table (rejected).** Six append-only trails with six
  retention rules and six `REVOKE` sets, and an owner who must read all of them to answer "who
  did this". The admin portal's Audit Trail is a single list.

**Where events still belong.** `platform` publishes `EmergencyAccessOpened`,
`CrossTenantAccessRefused` and similar **after** the audit row commits, as derived,
non-authoritative signals. A later notification story consumes them to tell the owner ("the owner
has been told"). The requirement is explicit that SAH-2 only has to capture the triggering event in
the audit trail; this is how a later story gets it without SAH-2 building a notifications service.

**Two tables, both in `platform`, both tenant-scoped with RLS.**

- `config_audit_event` — who, what (human-readable), when, area, plus a `before_snapshot` jsonb
  for one-step undo (`cfgSnap`/`cfgundo`). Undo writes a **new** entry and marks the original
  `undone_by`; nothing is deleted.
- `access_audit_event` — who, which record, which action, when, kind ∈
  `VIEW | EDIT | PRINT | COLLECT | GIVE | ORDER | DENY | ESCALATE | BREAK_GLASS | EXPORT`, after
  `ptLog` and `sfLog`.

Split rather than one table because the two differ in every dimension that matters: volume (one is
per configuration change, the other per record view — orders of magnitude apart), retention,
read pattern, and privacy class. `access_audit_event` references patient records, so it is
protected as patient data, not as operational logging. Which is also the answer to the
requirement's privacy point: the application log never carries a patient name, mobile number, ABHA
number or clinical note; the access audit does carry record references, and that is precisely why
it is an access-controlled store behind `clinic.audit.view` and not a log file.

**Cross-module references are opaque.** An audit row records `(module, entity_type, entity_id)` as
plain values with **no foreign key** into another module's schema. A FK from
`platform.access_audit_event` to `patient.patient` would be cross-module table access and would
also make a patient row undeletable by the audit trail's existence. Referential integrity across
that line is deliberately not enforced.

**DEC-005 boundary.** HR actions (staff, roster, leave, attendance) audit into the configuration
trail with `module = 'hra'`. They never enter `access_audit_event`. The requirement is explicit
that staff data and patient clinical data must not share an audit boundary in a way that lets one
leak into the other's access log; one column of separation is not enough, so the separation is the
table.

**Append-only is enforced, not agreed.** Five layers, of which the first three are the enforcement
and the last two are defence in depth:

1. **Grants.** `GRANT SELECT, INSERT` to `sahaj_app` and nothing more, plus an explicit
   `REVOKE UPDATE, DELETE ON platform.config_audit_event, platform.access_audit_event FROM sahaj_app, PUBLIC;`
   A convention would be a comment; this is the privilege system.
2. **Policies with no UPDATE or DELETE policy at all.** Under RLS, a command with no matching
   policy is denied. So even if someone later grants `UPDATE` by accident, there is no policy to
   permit it and the statement still fails. `FORCE ROW LEVEL SECURITY` extends this to the table
   owner.
3. **A trigger.** `BEFORE UPDATE OR DELETE … FOR EACH ROW EXECUTE FUNCTION platform.deny_mutation()`
   raising an exception. This catches `sahaj_migrator` and any role added in future, including
   during a migration. A superuser can disable triggers; that is accepted and is the reason no
   superuser credential exists on the request path.
4. **No mutation in the mapping.** Entities mapped `@Immutable`, no setters, no `@Modifying`
   repository method, no `deleteBy…`.
5. **No endpoint.** `docs/api/platform.yaml` defines no `PUT`, `PATCH` or `DELETE` under
   `/audit/**`. Such a request gets `405`, and the attempt is itself audited.

Retention is a legal question, not an architectural one, and I am not inventing it — see the open
questions. The architectural requirement is that retention be implemented as **partition drop by
month**, never as `DELETE`, because a `DELETE` path would contradict layers 1 to 3. The
db-engineer should range-partition both audit tables by `occurred_at` month from day one; adding
partitioning later means rewriting the table.

### D8 · Platform admin versus organization admin

**Both use exactly the same RLS path as a clinic user.** There is no bypass role, no second
datasource, no `SECURITY DEFINER` wrapper that reads across tenants. This is the core of the
decision: platform staff do not get a wider door, they get the same door and a key to specific
rooms.

- A platform session starts with **no** `app.tenant_id` (mockup: `AX_PERSONA.platform.orgs = []`).
  Every tenant table therefore returns zero rows — D2's fail-closed default doing real work.
- Switching into organization X (`axSwitchOrg`) sets `user_session.active_tenant_id`, after which
  the same `SET LOCAL app.tenant_id` binding applies. The switch is a state change on the session
  row, audited, and it is the *only* way a platform session acquires a tenant.
- Platform-scope tables are reached through `app.actor_kind = 'platform'` (D3), never by widening
  the tenant predicate.
- **Organization admin** = `platform_user` plus rows in `platform_user_org_grant`. `axCanOrg`
  becomes a grant lookup. Switching to an organization not granted → `403`, audited (SAH-2-6).
  The directory list is filtered by grants server-side; a direct `GET` of a non-granted
  organization returns `404`, so enumeration by guessed id yields nothing either way.
- **Yes, platform staff are a separate identity domain** (D5): separate table, sign-in, session
  table and token audience, and a separate platform-scope audit stream with no `tenant_id`. The
  reason is isolation, not tidiness — a shared user table needs a nullable tenant key, and that
  nullable key would sit on the credential store for the whole platform.
- **"Sahaj staff see counts only."** `axIso` states it plainly: "Opening records needs the owner's
  OTP and is logged." So a switched-in platform session is, by default, limited to aggregate and
  configuration reads; reading tenant record rows requires an owner-approved, time-boxed
  elevation, and every such read writes an `access_audit_event` attributed to the platform user
  with the platform persona recorded (SAH-2-6). In phase 1 the elevation **grant and its audit** are
  built; the owner-OTP delivery that approves it depends on requirement open questions 1 and 6, so
  with no OTP channel configured the elevation cannot be obtained and the reads stay refused. That
  is the correct fail-closed behaviour, and it is worth saying out loud: until the human answers
  the OTP question, Sahaj staff cannot open patient records at all. That is a feature.

Requirement open question 2 (does an organization admin span several tenants?) stays with the
human, but this model is deliberately indifferent to the answer: `app_user` is always exactly one
tenant, and multi-organization reach lives entirely in the platform identity domain as grants. Both
answers are buildable on this ADR without a schema change.

### D9 · The ADR-001 D3 reversal — confirmed, with four additions

ADR-001's five steps are confirmed. SAH-2 adds:

1. `spring-boot-starter-data-jpa`, `flyway-core` and `org.postgresql:postgresql` to
   `backend/pom.xml`. Also `spring-boot-starter-web`, `spring-boot-starter-security` and
   `spring-boot-starter-validation`, which SAH-2 needs for the first endpoints — ADR-001 assigned
   sign-in to SAH-3, but SAH-2's own stories require the sign-in and permission endpoints, so they
   land here.
2. Datasource configuration from environment variables only (ADR-001 D5). URL, and **two**
   credential pairs for the `sahaj_app` / `sahaj_migrator` split.
3. The first Flyway migration under `backend/src/main/resources/db/migration/`, creating the
   `platform` schema's tables, the `current_tenant()` helper, RLS with `FORCE`, the grants and the
   audit `REVOKE`s and trigger.
4. `mvn verify`, and so `validate backend` / `validate db` / `validate all`, now require a live
   local PostgreSQL 16 with `sahaj_dev` and `sahaj_test`. **The human must run
   `node scripts/setup/create-database.mjs` before SAH-2's validation gate.** Accepted cost,
   recorded in ADR-001 and now due.
5. D2's scope limit is revisited: `platform` is the first module to own a schema.

Additions:

6. **Two datasources, not one** (D4). Flyway is configured with `sahaj_migrator` explicitly and a
   pool of 1; `spring.datasource` carries `sahaj_app` only. `spring.flyway.create-schemas: false`
   and `spring.flyway.schemas: platform` — schemas are created by `create-database.mjs`, which is
   human-owned, so Flyway must not try.
7. **`spring.jpa.hibernate.ddl-auto: validate`** (never `update`, never `create-drop`, in any
   profile including `test`) and **`spring.jpa.open-in-view: false`** (D4 — correctness, not
   style). Flyway is the only thing that changes schema.
8. **D2's one-schema-per-module rule becomes enforceable.** Spring Modulith cannot see SQL, so the
   practical check is a convention test: every `@Entity`/`@Table` in `com.sahaj.<m>` must declare
   `schema = "<m>"`. Combined with the no-cross-module-FK rule in D7, that gives the schema
   boundary a build-time check. Recommended to java-developer; the ADR fixes the rule, not the
   implementation.
9. **Spring Boot version.** `docs/reviews/SAH-1-security.md` M2 says the 3.3.x line is out of
   open-source support and advises upgrading "in SAH-2 while the blast radius is one file". Step 1
   adds `spring-boot-starter-web` and Spring Security, which is precisely the event M2 says turns
   those advisories High (CVE-2025-22235 is an actuator authorization bypass; CVE-2025-22233 is
   `DataBinder`). Recommendation: upgrade to the current supported Spring Boot 3.x **in the same
   commit that adds the web and security starters**. `backend/pom.xml` is the java-developer's
   file; this is a request, not a change I can make.

---

## Consequences

**Positive**

- Tenant isolation has three independent layers (session-only resolution, transaction-scoped
  binding, fail-closed policy) and the default on every one of them is "no rows". A bug in the
  application layer produces an empty list, not another clinic's patients.
- `SET LOCAL` removes the pooled-connection leak class entirely rather than mitigating it, at the
  price of one enforceable rule (everything in a transaction).
- `FORCE ROW LEVEL SECURITY` plus "verify as `sahaj_app`" closes the gap the SAH-1 security review
  flagged, and makes the cross-tenant test meaningful instead of decorative.
- Opaque server-side sessions make requirement rule 17 ("immediately") and SAH-2-3 ("without
  signing in again") true by construction, with no revocation list and no token-versioning scheme.
- The synchronous audit port keeps the audit row atomic with its action and works for refusals,
  while staying inside `CLAUDE.md`'s existing boundary rule — no new architecture concept.
- Append-only rests on privileges, policies and a trigger. An attacker or a careless migration
  would have to defeat three independent mechanisms, and a superuser, to rewrite history.
- Platform staff reuse the clinic RLS path, so there is no second access-control implementation to
  keep correct, and an unswitched platform session can read nothing.
- Permissions live in tables, so later modules add keys with a migration instead of a contract
  change, and SAH-2-3's "this story does not freeze the key names" is honoured.

**Negative / accepted costs**

- One session lookup per request, uncached. Deliberate: a cache contradicts "immediately". If it
  ever hurts, the fix is a short TTL plus an explicit invalidation on account and role change, and
  that is a measured change, not a default.
- Every tenant-scoped operation must be inside a transaction. Read-only `GET`s that would
  otherwise run with autocommit now open a transaction. Small cost, enforced by a thrown
  exception rather than discovered in production.
- Patient scope and field-group access are application-layer, not RLS. A missed call site is a
  real intra-tenant privacy bug, and RLS will not catch it. Mitigation: one shared predicate per
  module, plus the default-deny annotation test. This asymmetry is intentional — the tenant
  boundary gets database enforcement, the intra-tenant scope gets code enforcement — and reviewers
  should hold the single-call-site rule hard.
- `app.actor_kind` is a session setting that platform-table policies trust, so the D2 binding advice
  is a single trust concentration. Mitigated by the grep test making it the only writer, which is a
  weaker guarantee than the database's own. If the human prefers, the alternative is a
  `SECURITY DEFINER` function per platform-table read with an explicit allow-list — more code, one
  less trusted setting. Not chosen; worth revisiting if platform-scope tables multiply.
- `mvn verify` now needs a live database. `validate all` fails on any machine where
  `create-database.mjs` has not run. Moved from SAH-1, not removed.
- Audit volume. `access_audit_event` grows with every record view. Partitioning from day one is the
  mitigation; without a retention answer the table only grows.
- Two identity domains mean two sign-in flows, two session tables and two audit streams to keep
  correct. The isolation argument is worth it, but it is duplicated code.
- Phase 1 has no OTP channel, so clinic sign-in works only under the `dev` and `test` profiles and
  Sahaj staff cannot open patient records at all. Correct and fail-closed, but it means SAH-2 is
  not demonstrable end-to-end against a real phone until the human answers open question 1.

---

## Data needed (for the db-engineer)

All of this is the **`platform` schema**. No other module gains a table in SAH-2. Nothing here
holds a foreign key into another module's schema (D7).

**Conventions for every tenant table below:** `id uuid primary key default gen_random_uuid()`,
`tenant_id uuid not null default platform.current_tenant()`, `created_at timestamptz not null
default now()`, `created_by uuid`, `updated_at`, `updated_by`; `ENABLE` **and** `FORCE ROW LEVEL
SECURITY`; the `p_tenant` policy from D3; `tenant_id` first in every multi-column index.
Timestamps are `timestamptz`; the application renders IST.

### Tenant and structure

| Entity | Key fields | Notes |
| --- | --- | --- |
| `tenant` | `id`, `code` (unique, slug — the clinic's own web address), `name`, `name_local`, `type` (CLINIC / NURSING_HOME / HOSPITAL), `city`, `state`, `plan`, `specialty`, `active`, `created_at` | **The organization = the tenant.** Not tenant-scoped itself. Two policies: platform actors see all; a clinic session sees only `id = platform.current_tenant()`. |
| `branch` | `tenant_id`, `name`, `address`, `active`, `is_default` | Tenant-scoped, RLS on. **No branch RLS predicate** (D1). One default branch per tenant in phase 1. |
| `security_setting` | `tenant_id` (unique — one row per tenant), `pin_sign_in boolean default false`, `idle_lock_minutes int default 10 check (between 5 and 60 and divisible by 5)`, `owner_otp_required boolean default true` | Mirrors `S.sec`. Organization-wide, not per person. |

### Identity — clinic staff

| Entity | Key fields | Notes |
| --- | --- | --- |
| `app_user` | `tenant_id`, `mobile` (10 digits, **unique per tenant**), `full_name`, `designation` (free text: Front desk, Nurse, Counsellor, Attendant, Pharmacist — the mockup's add-staff list), `active boolean default true`, `pin_hash`, `pin_set_at`, `pin_failed_count`, `pin_locked_until`, `last_signed_in_at` | No password column. Ever. `pin_hash` is Argon2id. Never deleted — switched off (`active = false`) so past entries keep their name. |
| `app_user_role` | `tenant_id`, `app_user_id`, `role_id` | Phase 1: one role per user (the mockup shows one). Keep it a table, not a column, so a later story can allow more without a migration of `app_user`. |
| `one_time_code` | `tenant_id` nullable, `purpose` (SIGN_IN / PIN_SETUP / OWNER_STEP_UP), `mobile`, `code_hash`, `expires_at`, `consumed_at`, `attempt_count`, `created_ip` | Store the **hash**, not the code. TTL 5 min. A cleanup job deletes consumed and expired rows — this table is not audit and must not grow. Rate-limit counters keyed on `mobile` + hour. |
| `user_session` | `tenant_id` nullable, `actor_kind` (CLINIC / PLATFORM), `app_user_id` nullable, `platform_user_id` nullable, `token_hash` (SHA-256, unique), `active_tenant_id` nullable, `state` (ACTIVE / LOCKED / REVOKED), `created_at`, `last_seen_at`, `absolute_expires_at`, `locked_at`, `revoked_at`, `revoked_reason`, `device_label`, `ip` | One row per sign-in. `token_hash` indexed unique — this is the per-request lookup, so it must be the cheapest query in the system. Exactly one of `app_user_id` / `platform_user_id` is set (check constraint). Not audit: revoked and expired rows are purged. |

### Identity — Sahaj's own staff (separate domain, D5/D8)

| Entity | Key fields | Notes |
| --- | --- | --- |
| `platform_user` | `login_id` (unique), `full_name`, `platform_role` (SUPER_ADMIN / SUPPORT / BILLING / AUDITOR / ORG_ADMIN — mockup `AROLE` plus `orgadmin`), `totp_secret_encrypted`, `totp_enrolled_at`, `active`, `failed_count`, `locked_until` | **No `tenant_id`.** Policy: `app.actor_kind = 'platform'`. `totp_secret_encrypted` is a secret at rest — key from an environment variable, never in a migration. |
| `platform_user_org_grant` | `platform_user_id`, `tenant_id`, `granted_at`, `granted_by` | This is `AX_PERSONA.org.orgs`. A platform admin with `SUPER_ADMIN`/`SUPPORT` has no rows and may switch into any tenant; an `ORG_ADMIN` may switch only into its granted rows. Drives `axCanOrg`. |
| `platform_record_elevation` | `platform_user_id`, `tenant_id`, `approved_by_owner_user_id`, `reason`, `opened_at`, `expires_at`, `revoked_at` | "Sahaj staff see counts only. Opening records needs the owner's OTP and is logged." No live row → tenant record reads refused. |

### Roles, permissions, scope

| Entity | Key fields | Notes |
| --- | --- | --- |
| `permission` | `key` (unique, e.g. `clinic.billing.refund`), `module`, `area_label`, `action_label`, `scope` (CLINIC / PLATFORM), `active` | **Not tenant-scoped** — a global catalogue, readable by every session. Seeded from `AX_PERMS` plus `S.perm` (see D6). New keys arrive by migration. |
| `role` | `tenant_id`, `key`, `name`, `kind` (OWNER / SYSTEM / CUSTOM), `patient_scope` (ALL / WARD / OWN), `active` | Tenant-scoped, because the owner edits them per clinic and may create custom roles (`axrolenew`). Seed per tenant: OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING (mockup `PC_R`). `kind = 'OWNER'` is exactly one row per tenant, `patient_scope` forced to ALL, undeletable, uneditable. `SYSTEM` roles are undeletable; `CUSTOM` are deletable when unused. |
| `role_permission` | `tenant_id`, `role_id`, `permission_key` | The owner's matrix (`S.perm`, `axperm`). **No rows are written for the OWNER role** — the resolver short-circuits (D6). Seeded for the other roles from `axRoleData`'s sets. |
| `role_field_group_access` | `tenant_id`, `role_id`, `field_group` (free text key: identity / contact / clinical / vitals / billing — `PC_G`), `access` (NONE / VIEW / EDIT) | `C.acl.data`. Keep `field_group` a string, not an enum, so IPD stories add groups without a migration here. |
| `role_action_allow` | `tenant_id`, `role_id`, `action_key` (nursing / emr / casepaper / transfer / discharge / deposit / stage — `PC_A`), `allowed boolean` | `C.acl.act`. Same reasoning for the string key. |

### Access control events

| Entity | Key fields | Notes |
| --- | --- | --- |
| `emergency_access_grant` | `tenant_id`, `app_user_id`, `target_module`, `target_entity_type`, `target_entity_id`, `reason` (10–500 chars, **not null**), `opened_at`, `expires_at` (= opened_at + 30 min), `reviewed_at`, `reviewed_by` | `sfbg` / `sfbgok` / `sfbgrev`. No FK to `ipd`. Index `(tenant_id, expires_at)` for the owner's review list and `(tenant_id, app_user_id, target_entity_id, expires_at)` for the scope predicate, which runs on every patient read. |
| `step_up_token` | `tenant_id`, `app_user_id`, `purpose` (REFUND / EXPORT / PLATFORM_RECORD_ACCESS), `token_hash`, `issued_at`, `expires_at` (5 min), `consumed_at` | The owner-OTP second factor (`S.sec.otp`). Short-lived, purged. |

### Audit — append-only (D7)

| Entity | Key fields | Notes |
| --- | --- | --- |
| `config_audit_event` | `tenant_id` **nullable** (null = a platform-scope action), `occurred_at`, `actor_kind`, `actor_id`, `actor_display_name` (denormalised — the name must survive the user row changing), `actor_persona` (CLINIC_OWNER / CLINIC_STAFF / PLATFORM_ADMIN / ORG_ADMIN), `module`, `area` (the mockup's `sec` / `area`), `summary` (human-readable, ≤ 500 chars), `before_snapshot jsonb`, `after_snapshot jsonb`, `outcome` (APPLIED / REFUSED), `refusal_code` (`tenant-mismatch`, `permission-denied`, …), `undone_by_event_id`, `request_id`, `ip` | `S.audit` + `S.ax.audit`. `before_snapshot` is what `cfgundo` restores. **Nullable `tenant_id`** is the one exception to the convention, for platform-scope rows; its policy is the union of the tenant predicate and `app.actor_kind = 'platform'`. Range-partition by `occurred_at` month. |
| `access_audit_event` | `tenant_id`, `occurred_at`, `actor_kind`, `actor_id`, `actor_display_name`, `action` (VIEW / EDIT / PRINT / COLLECT / GIVE / ORDER / DENY / ESCALATE / BREAK_GLASS / EXPORT), `target_module`, `target_entity_type`, `target_entity_id`, `summary` (≤ 500 chars), `via_emergency_access_grant_id`, `outcome`, `refusal_code`, `request_id`, `ip` | `S.access` / `ptLog` / `sfLog`. **No patient name, mobile, ABHA number or clinical note in `summary`** — record the reference, not the content; the reader joins through the owning module. Range-partition by `occurred_at` month. Index `(tenant_id, occurred_at desc)` and `(tenant_id, target_entity_id, occurred_at desc)`. |

**Both audit tables, exactly:**

- `GRANT SELECT, INSERT` to `sahaj_app`. No `UPDATE`, no `DELETE`.
- `REVOKE UPDATE, DELETE ON … FROM sahaj_app, PUBLIC;` explicitly, in the migration.
- `ENABLE` + `FORCE ROW LEVEL SECURITY`, with a `FOR SELECT` policy and a `FOR INSERT` policy and
  **no** `FOR UPDATE`, `FOR DELETE` or `FOR ALL` policy.
- `BEFORE UPDATE OR DELETE … FOR EACH ROW EXECUTE FUNCTION platform.deny_mutation()` raising
  `ERROR`.
- Retention by **partition drop**, never `DELETE`. Partition from the first migration.

### Tenant scope summary

- **Tenant-scoped, RLS on:** `branch`, `security_setting`, `app_user`, `app_user_role`,
  `user_session` (nullable while unauthenticated), `role`, `role_permission`,
  `role_field_group_access`, `role_action_allow`, `emergency_access_grant`, `step_up_token`,
  `access_audit_event`, `config_audit_event` (nullable tenant).
- **Platform-scope, `app.actor_kind = 'platform'` policy:** `platform_user`,
  `platform_user_org_grant`, `platform_record_elevation`.
- **Dual policy:** `tenant` (own row for a clinic session; all rows for a platform actor).
- **Global catalogue, readable by all:** `permission`.
- **Not tenant-scoped and deliberately transient:** `one_time_code` (keyed by mobile before a
  tenant is known).

### Retention

| Data | Retention | Who decides |
| --- | --- | --- |
| `one_time_code` | delete 24 h after expiry or consumption | architect — it is a credential, not a record |
| `step_up_token` | delete 24 h after expiry | architect |
| `user_session` | delete 30 days after revocation or expiry; the audit keeps the sign-in event | architect |
| `emergency_access_grant` | kept — it is evidence | architect |
| `config_audit_event` | **unresolved** | human (see open questions) |
| `access_audit_event` | **unresolved** | human (see open questions) |

### What the db-engineer must not do

- No `DELETE` or `UPDATE` path on either audit table, for any role, for any reason.
- No `COALESCE`, `OR true`, or `current_setting(..., true) IS NULL` escape in any policy predicate.
- No cross-schema foreign key.
- No `BYPASSRLS` on any role the application can authenticate as.
- Do not verify policies as `sahaj_migrator` — the migrator owns the tables, and only `FORCE`
  binds it. Verify as `sahaj_app`.

---

## Open questions

### Answered here (architectural)

| # | Question | Answer |
| --- | --- | --- |
| 3 | How do `opd`/`ipd`/`billing` write to `platform`'s audit without breaking the boundary? | **D7** — a narrow `platform.api` port called synchronously in the caller's transaction. Events are rejected for the authoritative write (no atomicity, no story for refusals) and kept for derived signals. |
| 4 | Is the platform admin's 6-digit authenticator code real or a placeholder? | **D5** — build it as TOTP (RFC 6238). It is what the mockup draws and the only strong second factor that needs no notifications service. Human to confirm. |
| 7 | Session lifetime, idle timeout, revocation | **D5** — 12 h absolute, idle lock = the clinic's `S.sec.lock` (default 10 min, 5–60), lock ≠ sign-out, immediate revocation on account switch-off, permissions resolved per request. Numbers are mine; the human may change them. |
| 8 | Is branch ever an RLS boundary? | **D1** — no, not in phase 1. `branch_id` exists as a column; no policy partitions by it. The mockup has no branch-scoped role and makes cross-branch sharing a tenant setting. |

### Still for the human

| # | Question | Why it is not mine |
| --- | --- | --- |
| 1, 6 | Which provider sends the sign-in OTP and the owner's refund/export OTP, and what is the fallback when it is unreachable? | Commercial and operational. D5 builds the port and leaves the adapter unwired, so sign-in fails closed outside `dev`/`test` until answered. This is the single biggest blocker to SAH-2 being demonstrable. |
| 2 | Does an organization admin legitimately span several clinics in phase 1? | Product scope. D8 is deliberately indifferent — `app_user` is always one tenant and multi-org reach is a platform-side grant — so either answer builds without a schema change. |
| 5 | Is a 4-digit PIN acceptable, and what lockout? | Risk acceptance. The mockup says 4 digits and I have not changed it; I have added Argon2id, 5 attempts and a 15-minute lock, with mobile OTP still available. Confirm or tighten. |
| New A | **Audit retention.** How long must `config_audit_event` and `access_audit_event` be kept? | Legal (DPDP, clinical-record retention, and whatever a hospital's accreditation requires). I will not invent a retention period for a clinical access log. Architecture is ready either way: partition by month, drop partitions. |
| New B | **May an organization admin or platform admin read `access_audit_event` (which references patient records), or only `config_audit_event`?** | Privacy policy. The mockup's admin portal Audit Trail shows configuration changes only; the clinical access log appears in the clinic app. Phase 1 position until told otherwise: platform and organization admins get `config_audit_event` only, and `access_audit_event` only inside an owner-approved elevation. |
| New C | **Default permission matrices for VISITING_DOCTOR, and whether Counsellor / Attendant / Pharmacist are roles or designations.** | `axRoleData` seeds Owner, Doctor, Front desk, Nurse, Accountant. `PC_R` adds Visiting doctor. The add-staff dropdown offers Counsellor, Attendant and Pharmacist with no permission set anywhere in the mockup. I have modelled them as *designations* on `app_user` plus tenant-creatable custom roles rather than invent matrices. Confirm. |
| New D | **SAH-2-6 asks for `400` on a switch to a non-existent organization id.** Taken literally that is an enumeration oracle: `400` would mean "no such organization" and `403` "exists, not yours", letting an organization admin map the platform by probing. The contract therefore returns `400` only for a **malformed** id and `403` uniformly for well-formed-but-unknown-or-not-granted. | A deliberate, reasoned deviation from an approved story. Flagged rather than changed quietly, per my brief. |

---

## Handoff · SAH-2 · solution-architect → db-engineer
Status: NEEDS DECISION (work is complete; four items below need the human before development)
Mockup screen: Foundation (no screen)

Files changed:
- `docs/architecture/adr/ADR-002-tenancy-identity-and-audit.md` — created. Nine decisions: tenant =
  organization with branch as a column not a boundary (D1); per-request `app.tenant_id` binding via
  `SET LOCAL` inside the transaction, fail closed (D2); RLS policy shape with `FORCE ROW LEVEL
  SECURITY` and "verify as `sahaj_app`" (D3); pooling rules and the enforcement tests (D4); two
  identity domains, opaque server-side sessions, OTP/PIN rules and the OTP plug point (D5);
  permission model with default-deny and the status-code table (D6); the audit boundary answer — a
  synchronous `platform.api` port, not a domain event — plus five-layer append-only enforcement
  (D7); platform versus organization admin on the same RLS path (D8); the ADR-001 D3 reversal
  confirmed with four additions (D9). Includes the full "Data needed" section.
- `docs/api/platform.yaml` — created. OpenAPI 3.1 for `/api/v1/platform`: sign-in (clinic OTP,
  clinic PIN, platform TOTP), session read/lock/unlock/sign-out, `GET /me`, users, roles,
  permissions, role access (scope, field groups, actions), security settings, owner step-up,
  emergency access, organizations and switch-organization, and both audit trails with undo. RFC
  9457 problem details throughout, page-based pagination, `x-required-permission` on every
  operation.

Validation: `node scripts/validate.mjs docs` → expected PASSED. I have no Bash tool, so this was
not executed. Neither file sits under `docs/requirements/` or `docs/stories/`, the only folders
`docs()` inspects, and the gate has no OpenAPI check today, so this result cannot change. Tech
lead: please run it before stage 5.

Decisions made: D1–D9 above. The four previously-open questions I answered as architectural are
3 (audit boundary), 4 (TOTP for platform staff), 7 (session lifetimes) and 8 (branch is not an RLS
boundary).

Open questions / requests for other owners:

**For the human (blocking or near-blocking)**
- Requirement open questions 1 and 6 — the OTP provider and fallback. Until answered, clinic
  sign-in works only under `dev` and `test` profiles and Sahaj staff cannot open patient records.
- New question A — audit retention periods. Needed before the db-engineer finalises partitioning.
- New question D — I deviated from SAH-2-6's literal `400`-on-non-existent-organization, because it
  is an enumeration oracle. Please confirm the `403`-uniform behaviour or tell me to follow the
  story literally.
- Requirement open questions 2 and 5, and new B and C, are not blocking; the model builds either
  way.
- **`.env.example` (human-owned, no agent can write it)** — please add:
  `SAHAJ_DB_URL`, `SAHAJ_DB_APP_USER`, `SAHAJ_DB_APP_PASSWORD`, `SAHAJ_DB_MIGRATOR_USER`,
  `SAHAJ_DB_MIGRATOR_PASSWORD`, `SAHAJ_TEST_DB_URL`, `SAHAJ_SESSION_ABSOLUTE_TTL_MINUTES=720`,
  `SAHAJ_SESSION_IDLE_LOCK_DEFAULT_MINUTES=10`, `SAHAJ_OTP_TTL_SECONDS=300`,
  `SAHAJ_OTP_MAX_VERIFY_ATTEMPTS=5`, `SAHAJ_OTP_MAX_REQUESTS_PER_HOUR=5`,
  `SAHAJ_PIN_MAX_ATTEMPTS=5`, `SAHAJ_PIN_LOCK_MINUTES=15`, `SAHAJ_OTP_SENDER=log`,
  `SAHAJ_TOTP_SECRET_ENCRYPTION_KEY`. All placeholders; no real value in the repository.
- **`scripts/validate.mjs` (human-owned)** — it already fails a migration with `tenant_id` and no
  `ENABLE ROW LEVEL SECURITY`. Please extend it to require `FORCE ROW LEVEL SECURITY` too. Without
  `FORCE`, the migrator owns the tables and the policies are inert for it, which is the exact gap
  `docs/reviews/SAH-1-security.md` flagged.
- **`node scripts/setup/create-database.mjs` must be run before SAH-2's validation gate.** From this
  story onward `mvn verify` needs a live `sahaj_dev` and `sahaj_test` (ADR-001 D3, now due).

**For db-engineer (your stage)**
- Build from "Data needed" above. The five non-negotiables: `FORCE ROW LEVEL SECURITY` on every
  tenant table; `tenant_id NOT NULL DEFAULT platform.current_tenant()`; no `COALESCE`/wildcard in
  any policy predicate; the audit `REVOKE` + no-UPDATE/DELETE-policy + `deny_mutation()` trigger;
  no cross-schema foreign key.
- Partition both audit tables by `occurred_at` month in the first migration, even though retention
  is unanswered. Adding partitioning later rewrites the table.
- `platform.current_tenant()` must use `current_setting('app.tenant_id', true)` — the `true`
  (missing_ok) argument is what makes an unset tenant return `NULL` and the policy match nothing.
  Without it the query errors instead, which is noisier but also fails closed; with it the
  behaviour is the fail-closed empty set this ADR specifies.
- Write the cross-tenant proof as `sahaj_app`. A test run as `sahaj_migrator` proves nothing.
- Schemas are created by `create-database.mjs` (human-owned), so set
  `spring.flyway.create-schemas: false`.

**For java-developer**
- `SET LOCAL app.*` / `set_config('app.…')` may appear in exactly one class. Everything else reads
  `TenantContext`.
- `spring.jpa.open-in-view: false` and `ddl-auto: validate` in every profile, including `test`.
- Two datasources: `sahaj_app` for requests, `sahaj_migrator` for Flyway with pool size 1.
- Every handler needs `@RequiresPermission` or `@PublicEndpoint`; add the convention test that
  fails the build when one has neither.
- **Spring Boot upgrade (ADR D9 item 9):** `docs/reviews/SAH-1-security.md` M2 rates the 3.3.x
  advisories as becoming High the moment `spring-boot-starter-web` and Spring Security are added —
  which is this story. Please upgrade to the current supported 3.x in the same commit. `pom.xml` is
  your file; I cannot change it.
- Dev-profile OTP logging must mask the mobile number to its last 4 digits. Never log a full
  mobile, a code, or a PIN.

**For qa-engineer**
- The three enforcement tests in D4, plus cross-tenant coverage as `sahaj_app` for every tenant
  table.
- Prove append-only directly: `UPDATE` and `DELETE` on both audit tables must fail as `sahaj_app`
  **and** as `sahaj_migrator`.
- Prove the pool cannot leak a tenant: two interleaved requests for different tenants on the same
  pooled connection.
- Prove a permission change takes effect on the next request with no re-sign-in, and that switching
  an account off kills a live session immediately.

**For angular-developer**
- `GET /api/v1/platform/me` returns permissions, patient scope, field-group access and allowed
  actions — drive the UI from it. Hiding is cosmetic; the server will refuse regardless, so handle
  `403` with problem type `permission-denied` as a normal, expected response.
- `403` with problem type `session-locked` means show the PIN lock screen, not "not allowed".

**Disagreements recorded, not acted on**
- ADR-001 D6 assigns sign-in and role checks to SAH-3. SAH-2's own stories (SAH-2-2, SAH-2-3,
  SAH-2-6) require those endpoints, so this contract includes them and SAH-3 becomes the screen.
  Not a change to ADR-001's intent, but recorded so the tech lead can re-scope SAH-3.
- SAH-2-6's `400`-on-non-existent-organization: see new question D. Flagged, not changed.
