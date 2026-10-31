# SAH-1 · Project scaffold
Module: platform
Mockup screen: Foundation (no screen)
Source: Playbook Stage A, `docs/process/playbook.md` row SAH-1; tech-lead kickoff, 31 Oct 2026

## Problem
Sahaj has no running application yet. `backend/` contains only an empty Flyway migration
folder (`backend/src/main/resources/db/migration/.gitkeep`) and `frontend/` contains only a
placeholder (`frontend/.gitkeep`). There is no Spring Boot app, no Maven build, no Angular
app, no module boundaries, and no environment-variable convention. Every later story (tenancy,
sign-in, OPD, IPD, billing, HRA) needs a working backend and frontend skeleton that already
enforces the module-boundary and no-secrets-in-code rules from `CLAUDE.md`, so that developers
build features inside guardrails instead of inventing structure story by story.

This story has no UI and serves developers and the platform itself, not clinic end users.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
No clinical or clinic-staff role uses this directly. The "users" are:
- **java-developer** and **angular-developer** — build features on top of the scaffold.
- **qa-engineer** — runs `validate all` against the scaffold on every story.
- **db-engineer** — adds Flyway migrations under the scaffolded `backend/` structure.
- **solution-architect** — relies on the six module packages existing when writing ADRs/contracts.

No clinic, ward or front-desk setting applies; this is build-time infrastructure only.

## In scope / Out of scope

**In scope**
- One Spring Boot 3.x application, Java 17, built with Maven, at `backend/`.
- The six module Java packages: `com.sahaj.platform`, `com.sahaj.patient`, `com.sahaj.opd`,
  `com.sahaj.ipd`, `com.sahaj.hra`, `com.sahaj.billing`, each with at minimum an `api`
  sub-package (per `CLAUDE.md`: "Modules talk only through their `api` package or domain
  events").
- Spring Modulith added to the backend, with an automated verification test that fails the
  build if a module reaches into another module's internals instead of its `api` package or a
  domain event.
- One Angular application (current major version) at `frontend/`, with Angular Material
  installed, and working `npm run lint`, `npm run test`, `npm run build` scripts.
- Configuration read from environment variables only; `.env.example` lists every variable the
  scaffold needs. No secrets committed to the repository.
- `node scripts/validate.mjs all` passes end to end on a clean checkout.

**Out of scope (future stories, per the playbook)**
- Tenancy, `app_user`, `role`, `audit_event`, row-level security (SAH-2).
- Sign-in, JWT session, logout, lockout (SAH-3).
- App shell, sidebar, header, theme, Simple/Advanced, language switching (SAH-4).
- Any REST endpoint, database table, or screen content.
- Any real PostgreSQL connectivity requirement for the scaffold's own tests (see open question).

## Business rules (numbered)
1. The backend is a single Spring Boot 3.x application on Java 17, built with Maven — not
   multiple services (`CLAUDE.md`: "Modular monolith").
2. The backend has exactly six module packages, each named `com.sahaj.<module>` for
   `platform`, `patient`, `opd`, `ipd`, `hra`, `billing`.
3. Modules may depend on another module's `api` package or domain events only; direct access
   to another module's internal (non-`api`) classes is a build failure, enforced by a Spring
   Modulith verification test.
4. The frontend is a single Angular application (current major) with Angular Material as the
   component library, not multiple frontend apps.
5. `npm --prefix frontend run lint`, `test` and `build` must each complete without error on a
   clean checkout.
6. No secret value (password, API key, connection string with credentials) is committed to the
   repository in any form. All configuration needed to run the scaffold is supplied through
   environment variables, and every variable name the scaffold reads is listed in
   `.env.example` (with no real values).
7. `node scripts/validate.mjs all` must pass on a clean checkout before this story is
   considered done, per `CLAUDE.md` rule 4 ("Done means validated").
8. This story introduces no REST endpoint, no database table/schema content, and no UI screen;
   anything resembling API design, table design or screen design belongs to the
   solution-architect, db-engineer or developers in later stories, not to this scaffold.

## Patient-safety and privacy points
None directly — this story contains no patient data, no clinical workflow and no UI. The
scaffold must, however, make the later patient-safety and privacy rules enforceable:
- The "no secrets in code" and "environment variables only" rules (`CLAUDE.md`) must be
  structurally in place now, since SAH-2 depends on it for `app.tenant_id` and credentials.
- The module boundary check (Spring Modulith) protects the architecture that later stories
  rely on to keep tenant and patient data access contained to the owning module's `api`.

## Plan or entitlement (which plan/module unlocks it)
Not applicable. This is foundational infrastructure, not a clinic-facing feature gated by a
plan or module entitlement (plans/entitlements are introduced in SAH-7).

## Open questions for the human
1. **Does `validate all` require a live PostgreSQL database for SAH-1?** `scripts/validate.mjs
   all` runs `mvn verify`, which runs integration tests against `sahaj_test`. Pre-flight could
   not confirm `sahaj_dev`/`sahaj_test` exist locally, and this story adds no schema or data
   access code at all (no tenancy yet — that's SAH-2). Should the SAH-1 scaffold's own tests
   (Modulith verification, a Spring context load, Angular lint/test/build) be designed to pass
   with **no live database**, deferring any real `sahaj_test` dependency to SAH-2 onward? Or do
   you want a live-database check wired in now even though there is nothing to migrate yet?
2. **Angular version and Node version pinning.** `CLAUDE.md` says "Angular current major" and
   "Node 20+ LTS" — should the scaffold pin an exact Angular major/minor now, or is "whatever
   `ng new` installs today" acceptable, to be revisited if it drifts before SAH-4?
3. **Spring Modulith verification strictness.** Should the Modulith test only fail on
   cross-module access to non-`api` packages (as stated in `CLAUDE.md`), or should it also
   enforce the one-schema-per-module database rule from day one even though no module has a
   schema yet (that arrives with SAH-2 and later data-owning stories)? Confirming now avoids
   rework when the first schema is added.
4. **`.env.example` ownership note:** `CLAUDE.md` lists `.env` as "humans only," but
   `.env.example` is the artifact the java-developer/angular-developer need to produce per the
   "Done when" criteria. Please confirm `.env.example` is written by the developer agents (not
   a human-only file like `.env` itself) — it currently shows as deleted in git status and
   needs to be re-created as part of this story.
