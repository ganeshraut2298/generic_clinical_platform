# ADR-001 · Scaffold baseline for the Sahaj modular monolith

Story: SAH-1 · Project scaffold
Mockup screen: Foundation (no screen)
Module: platform
Status: ACCEPTED (31 Oct 2026)
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Supersedes: none · Superseded by: none
Related: `docs/requirements/SAH-1.md`, `docs/stories/SAH-1.md` (SAH-1-1..SAH-1-5),
`docs/handoffs/SAH-1.md` (tech-lead decisions Q1-Q4), `docs/decisions/DEC-001-relax-approval-gates-to-merge-only.md`

---

## Context

Sahaj has no running application. `backend/` holds only an empty Flyway migration folder and
`frontend/` only a placeholder. `CLAUDE.md` already fixes the target shape — one Spring Boot app
(Java 17), one Angular app, one PostgreSQL 16 database, six modules with hard boundaries — but
nothing in the repository enforces or even records it.

SAH-1 creates that skeleton and nothing else. It adds **no REST endpoint, no entity, no schema and
no screen**, so there is no API contract for this story and no `docs/api/*.yaml` file is written.
This ADR exists so that SAH-2 onward build on a written baseline instead of inferring one from
scaffold code, and so the tech-lead's decisions of 31 Oct 2026 (handoff Q1-Q4) have a permanent
home.

Two forces shape the decisions below:

1. **`validate all` is the definition of done** (`CLAUDE.md` rule 4). `scripts/validate.mjs` runs
   `docs()`, then `db()` — which calls `backend()` → `mvn -B -q -f backend/pom.xml verify` — then
   `frontend()`. So any Spring context that tries to open a database connection at startup makes
   the gate fail.
2. **PostgreSQL is not yet verified on this machine.** `sahaj_dev` / `sahaj_test` existence could
   not be confirmed during SAH-1 pre-flight, and SAH-1 has nothing to migrate.

## Decisions

### D1 · Modular monolith layout, one Maven application at `backend/`

One Spring Boot 3.x application on Java 17, built with Maven, rooted at `backend/`. Six module
packages as **direct sub-packages** of the application base package `com.sahaj`:

`com.sahaj.platform` · `com.sahaj.patient` · `com.sahaj.opd` · `com.sahaj.ipd` ·
`com.sahaj.hra` · `com.sahaj.billing`

Each module has at minimum an `api` sub-package. A module may depend only on another module's
`api` package or on a domain event; everything outside `api` is that module's internal
implementation and is off limits to every other module. No module gains a REST controller, entity,
repository or service in this story.

Why direct sub-packages of `com.sahaj`: Spring Modulith derives module boundaries from the
packages immediately below the application class's package. Placing the six modules anywhere else
makes the boundary check in D2 inert.

Not decided here: package internals, class names, layering inside a module, build-file contents.
Those are the java-developer's to choose, inside D1 and D2.

### D2 · Spring Modulith verification enforces the `api` rule only, at SAH-1

Spring Modulith is on the backend classpath and an automated verification test fails the build when
one module references a class in another module's package outside that module's `api` package. The
`api` sub-package of each module is its only exposed named interface; all other sub-packages are
internal. The test must run and pass against the six skeletal modules as a smoke proof that the
check is wired in.

**Scope limit (tech-lead decision Q3): the check enforces the `api`-package rule and nothing else.**
It does **not** enforce one-schema-per-module. No module owns a PostgreSQL schema yet — the first
arrives with SAH-2 — so a schema rule would have nothing to verify and would have to be written
against imagined names. Revisit at SAH-2, when the first schema exists.

The verification is static analysis of the compiled classes; it starts no Spring context and needs
no database, which keeps it compatible with D3.

### D3 · No `DataSource` on the startup path until SAH-2

**Chosen mechanism: `spring-boot-starter-data-jpa`, `flyway-core` and the PostgreSQL JDBC driver
are left out of `backend/pom.xml` entirely at SAH-1.** No datasource autoconfiguration exclusion is
used, because none is needed when the dependency is absent.

This implements tech-lead decision Q1 ("SAH-1 tests must pass with no live PostgreSQL"). Only
starters that open no outbound connection during context refresh belong on the SAH-1 classpath.

Options considered:

- **A · Omit JPA/Flyway/driver from the pom (chosen).** The context cannot try to connect, because
  there is nothing to connect with. `mvn verify` and therefore `validate all` pass on a machine
  with no PostgreSQL. SAH-2 enabling persistence is a visible, reviewable dependency addition.
  Cost: the java-developer cannot write any data access in this story — which the story already
  forbids.
- **B · Include JPA/Flyway and exclude `DataSourceAutoConfiguration`** (via
  `@SpringBootApplication(exclude = …)` or `spring.autoconfigure.exclude`). Rejected: it leaves a
  latent trap. The dependency is present, so a repository or `@Entity` added later compiles and
  then fails at runtime with a confusing error, and the exclusion is easy to forget to remove. It
  also puts a temporary workaround in production configuration rather than in the build file.
- **C · Require a live `sahaj_test` now.** Rejected: blocks SAH-1 on unverified local
  infrastructure for zero benefit, since there is nothing to migrate or query.

**How SAH-2 reverses this.** SAH-2 (tenancy, users, roles, audit, row-level security) is the story
that owns the reversal, in one step and with no code to unpick:

1. Add `spring-boot-starter-data-jpa`, `flyway-core` and `org.postgresql:postgresql` to
   `backend/pom.xml`.
2. Supply datasource configuration from environment variables only (D5) — URL, user, password,
   plus the `sahaj_app` / `sahaj_migrator` role split from `CLAUDE.md`.
3. Add the first Flyway migration under `backend/src/main/resources/db/migration/`.
4. Accept that `mvn verify`, and so `validate backend` / `validate db` / `validate all`, require a
   live local PostgreSQL 16 with `sahaj_dev` and `sahaj_test` from that point on. The human must
   have run `node scripts/setup/create-database.mjs` before SAH-2's validation gate.
5. Re-examine D2's scope limit once the first module owns a schema.

Nothing in SAH-1 needs to be deleted or rewritten for this; the reversal is additive.

### D4 · Angular baseline: one app at `frontend/`, current major, Angular Material

One Angular application (current major version) at `frontend/`, with Angular Material as the
component library, and working `lint`, `test` and `build` npm scripts. No second frontend app, no
micro-frontends.

**Version policy (tech-lead decision Q2): take what `ng new` installs today.** `CLAUDE.md` already
says "Angular current major" and "Node 20+ LTS"; SAH-1 invents no additional pinning policy. The
angular-developer records the exact installed Angular, Angular Material and Node versions in its
handoff so drift is visible before SAH-4.

**The `test` script must run headless.** `scripts/validate.mjs` invokes it CI-style as
`npm --prefix frontend test -- --watch=false --browsers=ChromeHeadless`, so the `test` script has
to pass those flags through to the Angular CLI and must not require an interactive browser or a
watch loop. Same for `lint` and `build`: exit code 0, non-interactive, on a clean checkout.

No route, component layout, navigation or theme is decided here. The app shell, sidebar, header,
theme, Simple/Advanced mode and language switching are SAH-4 and must match
`docs/design/mockup/sahaj-mockup.html`.

### D5 · Configuration from environment variables only; `.env.example` is human-owned

No secret — password, API key, token, or connection string carrying credentials — exists in
committed source, build files or configuration. Every value the scaffold needs at runtime (for
example the server port and the active Spring profile) is read from an environment variable, with
at most a non-secret default.

**Ownership constraint (tech-lead decision Q4, resolved by fact, not judgement):** `.env.example`
sits at the repository root, which `.claude/hooks/guard-ownership.mjs` maps to `owner = 'human'`,
`area = 'protected'`. **No agent can write or restore it.** The hook blocks every attempt.

Consequences:

- Story SAH-1-4's acceptance criterion "`.env.example` is re-created at the repository root" is not
  satisfiable by any agent. The file is tracked in git and deleted only in the working tree; the
  human restores it with
  `git -C D:\generi_clinical_platform restore sahaj-claude-team/sahaj-platform/.env.example`.
- From SAH-1 onward, **any agent that introduces a new environment variable must name it, with a
  placeholder value and a one-line description, in its handoff** for the human to add to
  `.env.example`. This is now the standing convention; it is not a one-off for SAH-1.

### D6 · What this scaffold explicitly does not contain

Recorded so later stories are not tempted to pull work forward, and so reviewers can reject it:

| Concern | Story that owns it |
| --- | --- |
| `tenant_id`, row-level security, `app.tenant_id`, `app_user`, `role`, `audit_event` | SAH-2 |
| Sign-in, JWT session, logout, lockout, role checks on endpoints | SAH-3 |
| App shell, sidebar, header, theme, Simple/Advanced, language switching | SAH-4 |
| Plans and module entitlements | SAH-7 |
| Any REST endpoint, OpenAPI contract, entity, table or screen content | SAH-2 onward |
| Integration gateway (ABDM, HL7, payments), notifications, documents, analytics | not in phase 1 at all |

The phase 1 exclusions in the last row are `CLAUDE.md` policy, not a sequencing choice. Screens
needing them are marked "Later" in `docs/design/screen-map.md`.

## Consequences

**Positive**

- SAH-2 onward inherit a fixed place for backend and frontend code and a boundary check that fails
  the build, so the modular-monolith rule is enforced by the build rather than by code review.
- `validate all` passes on a developer machine with no PostgreSQL for the duration of SAH-1, which
  unblocks the story while local database setup is still unconfirmed.
- The no-secrets rule and the "report new environment variables in your handoff" convention are in
  place before the first credential (SAH-2's datasource) exists.
- The decisions behind the scaffold are reviewable text, so a later story can disagree with a
  recorded decision instead of silently drifting from an inferred one.

**Negative / accepted costs**

- SAH-1's backend proves very little at runtime: a context load and a static boundary check. There
  is no end-to-end proof that the app can reach PostgreSQL until SAH-2.
- D3 makes SAH-2 the story where the local-database prerequisite bites. SAH-2 will fail its
  validation gate on any machine where `create-database.mjs` has not been run. That cost is moved,
  not removed.
- D2's narrow scope means the one-schema-per-module rule is unenforced until it is revisited. Until
  then it is a review-time rule only.
- Taking whatever `ng new` installs (D4) means the Angular major can drift between SAH-1 and SAH-4
  if the scaffold is ever regenerated. Mitigated by recording exact versions in the development
  handoff.
- `.env.example` cannot be maintained by the agents that know what belongs in it (D5), so it can
  fall behind the code. Mitigated only by the handoff convention and the human acting on it.

## Data needed (for the db-engineer)

**None. SAH-1 requires no database work at all.**

- Entities: none.
- Fields: none.
- Relations: none.
- Tenant scope: not applicable — no tenant table exists yet.
- Retention: not applicable — no data is stored.
- Migrations: none. `backend/src/main/resources/db/migration/` stays empty apart from its
  `.gitkeep`, which `validate db` accepts.
- Audit: nothing in this story is a clinical or billing action, so no audit requirement arises.

The first schema arrives with **SAH-2 (tenancy, users, roles, audit)**: `tenant_id` on every tenant
table, row-level security enabled on each, the `sahaj_app` / `sahaj_migrator` role split, and the
first Flyway migration. Per D3, SAH-2 is also the story that adds JPA, Flyway and the PostgreSQL
driver to `backend/pom.xml`. The db-engineer has nothing to do for SAH-1.

---

## Handoff · SAH-1 · solution-architect → java-developer ∥ angular-developer
Status: DONE
Mockup screen: Foundation (no screen)
Files changed:
- `docs/architecture/adr/ADR-001-scaffold-baseline.md` — created. Records the SAH-1 scaffold
  baseline: modular monolith layout and six module packages (D1), Spring Modulith `api`-rule
  enforcement with the one-schema-per-module rule deliberately out of scope until SAH-2 (D2), the
  no-`DataSource`-until-SAH-2 decision with its chosen mechanism and reversal plan (D3), the
  Angular + Material baseline including the headless `test` requirement (D4), environment-variable
  configuration and the human-owned `.env.example` constraint (D5), and an explicit
  not-in-this-scaffold list (D6). Includes the "Data needed" section: none for SAH-1.
- No `docs/api/*.yaml` written — SAH-1 adds no REST endpoint, so there is no contract to specify.
  An empty or speculative OpenAPI file would be worse than none.

Validation: `node scripts/validate.mjs docs` → expected PASSED (`docs: mockup references and story
format OK`). The solution-architect has no Bash tool, so this was not executed here. The ADR adds
no file under `docs/requirements/` or `docs/stories/`, the only folders `docs()` inspects, so it
cannot change that result. Tech lead: please run it to confirm green before stage 6.

Decisions made:
- D1 Six module packages directly under `com.sahaj`; cross-module access only via another module's
  `api` package or a domain event.
- D2 Modulith verification enforces the `api` rule only at SAH-1 (tech-lead Q3). One-schema-per-
  module deferred to SAH-2.
- D3 JPA, Flyway and the PostgreSQL driver are **left out of `backend/pom.xml`** at SAH-1, rather
  than included with `DataSourceAutoConfiguration` excluded. Chosen because an absent dependency
  cannot misconfigure and leaves no latent trap; SAH-2 reverses it additively (deps + env config +
  first migration + accepting the live-database prerequisite).
- D4 Angular current major as installed by `ng new`, Angular Material, exact versions to be
  recorded in the development handoff. `test` must run headless with
  `--watch=false --browsers=ChromeHeadless`.
- D5 Environment variables only, no secrets committed. `.env.example` is human-owned; new variables
  go in the agent's handoff.

Open questions / requests for other owners:
- **For the human (owner of the repo root):** restore the deleted `.env.example` with
  `git -C D:\generi_clinical_platform restore sahaj-claude-team/sahaj-platform/.env.example`. No
  agent can create it (D5), so story SAH-1-4's "re-created at the repository root" criterion stays
  unmet until this is done.
- **For java-developer:** D3 means no `@Entity`, no repository and no datasource configuration in
  this story. If you find any SAH-1 acceptance criterion needs one, stop and report NEEDS DECISION
  rather than adding the dependency.
- **For java-developer:** report in your handoff every environment variable the backend reads
  (for example server port, active profile) with a placeholder value, for the human to add to
  `.env.example`.
- **For angular-developer:** report the exact Angular, Angular Material and Node versions `ng new`
  installed, plus any environment variable the frontend build or app reads.
- **For db-engineer:** nothing to do for SAH-1 (see "Data needed"). Your first work is SAH-2.
- **For the SAH-2 architect (likely me):** D2's scope limit and D3's reversal plan are the two items
  to pick up when the first schema lands.
- No disagreement with any decision handed to me; all five were documented as given.
