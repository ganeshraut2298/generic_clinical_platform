# Sahaj — multi-tenant clinic and hospital platform

Sahaj is a configurable SaaS for Indian clinics (OPD) and hospitals (IPD), with Health Risk Assessment (HRA).
Targets: 10,000 OPD clinics and 100 IPD hospitals. All patient data stays in India.

## Strict rules (non-negotiable)

1. **The mockup is the design.** `docs/design/mockup/sahaj-mockup.html` decides screens, flows, labels, field order, states, roles and business rules. Every requirement and story names its mockup screen from `docs/design/screen-map.md`. Anything not in the mockup, or different from it, stops and goes to a human.
2. **The workflow is enforced.** Requirement → User story → Architecture → API contract → Database design → Development → Testing → Code review → Human approval. Hooks block an agent whose earlier stage is not approved in `docs/approvals.md`.
3. **Ownership is enforced.** Agents write only in their own folders (table below).
4. **Done means validated.** `node scripts/validate.mjs <area>` must pass before any task is complete.

## Architecture (phase 1)

- **Modular monolith:** one Spring Boot app (**Java 17**), one Angular app, one PostgreSQL 16 database.
- Modules with hard boundaries: `platform`, `patient`, `opd`, `ipd`, `hra`, `billing`. Each has its own PostgreSQL schema and Java package `com.sahaj.<module>`. Modules talk only through their `api` package or domain events.
- **Not in phase 1:** integration gateway (ABDM, HL7, payments), notifications (WhatsApp/SMS), documents service, analytics. Screens that need them are marked "Later" in the screen map.
- **Multi-tenancy:** `tenant_id` on every tenant table, enforced by PostgreSQL row-level security. The app connects as `sahaj_app` (cannot bypass RLS) and sets `app.tenant_id` per request from the logged-in user, never from the request body. Flyway runs as `sahaj_migrator`.
- **API:** REST + JSON, contract-first. `docs/api/<module>.yaml` (OpenAPI 3.1) is approved before code. Base path `/api/v1/<module>`.
- **Databases:** `sahaj_dev` (run the app) and `sahaj_test` (integration tests), created by `scripts/setup/create-database.mjs`.

## Ownership

| Path | Owner |
| --- | --- |
| `docs/requirements/`, `docs/stories/` | business-analyst |
| `docs/architecture/`, `docs/api/` | solution-architect |
| `docs/database/`, `backend/src/main/resources/db/migration/` | db-engineer |
| `backend/` (everything else) | java-developer |
| `frontend/` (except `frontend/e2e/`) | angular-developer |
| `frontend/e2e/`, `backend/src/test/java/**/it/`, `docs/test-plans/` | qa-engineer |
| `docs/handoffs/`, `docs/reviews/`, `docs/decisions/` | tech-lead (the main session) |
| `CLAUDE.md`, `.claude/`, `scripts/`, `docs/design/`, `docs/approvals.md`, `.env` | humans only |

code-reviewer and security-reviewer are read-only. **tech-lead** is the main session (set in `.claude/settings.json`): it drives every story through the agents and escalates complex decisions to the human. The backlog and order of work: `docs/process/playbook.md`.

## Process per story (details: `docs/process/workflow.md`)

1. tech-lead creates the branch `feature/SAH-<n>-<slug>` from `main`.
2. business-analyst → requirement + stories (skill: requirement-analysis). **Human approves `stories`.**
3. solution-architect → ADR if needed + API contract. **Human approves `contract`.**
4. db-engineer → design note + Flyway migration (skill: database-migration).
5. java-developer ∥ angular-developer → code + unit tests (skill: feature-development).
6. qa-engineer → test plan, integration and e2e tests, `validate all`.
7. code-reviewer ∥ security-reviewer → reports (skill: code-review).
8. **Human approves `merge`** and merges locally. Agents never push or merge.

## Rules for every agent

- One story ID per branch. Name it in every handoff, using the handoff block in `docs/process/workflow.md`.
- Need a change outside your folder? Write it in your handoff for the owner.
- No secrets in code. Config from environment variables (`.env.example` lists them). No real patient data anywhere.
- Never log patient names, mobile numbers, ABHA numbers or clinical notes.
- UI text: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST.

## Common commands

```bash
node scripts/setup/check-env.mjs                 # tools installed?
node scripts/setup/create-database.mjs           # create roles, sahaj_dev, sahaj_test, schemas
mvn -f backend/pom.xml spring-boot:run           # API on :8080 (profile dev)
mvn -f backend/pom.xml verify                    # unit + integration tests on sahaj_test
npm --prefix frontend start                      # Angular on :4200
npm --prefix frontend run lint | test | build
node scripts/validate.mjs frontend|backend|db|docs|all   # the gate
```

## Versions

Java 17 (Microsoft OpenJDK / Temurin) · Spring Boot 3.x · Spring Modulith · Maven 3.9 · Angular current major + Angular Material · Node 20+ LTS · PostgreSQL 16 · Flyway. Git is local only for now (no GitHub, no CI yet).
