# SAH-1 · Project scaffold — handoff log

Branch: `feature/SAH-1-project-scaffold`
Mockup screen: Foundation (no screen)
Module: platform

---

## Handoff · SAH-1 · business-analyst → solution-architect
Status: DONE
Mockup screen: Foundation (no screen)
Files changed:
- `docs/requirements/SAH-1.md` — created. Problem, roles (dev team only, no clinical role), in/out of scope, 8 numbered business rules, safety/privacy note, 4 open questions.
- `docs/stories/SAH-1.md` — created. 5 stories SAH-1-1..SAH-1-5, all Must, each with Given/When/Then acceptance criteria.

Validation: `node scripts/validate.mjs docs` → PASSED (`docs: mockup references and story format OK`).
Run by tech-lead, not the analyst: the business-analyst has no Bash tool, so it hand-traced the
rules and asked for confirmation. Confirmed green.

Scope check by tech-lead: correct. No tenancy/auth/RLS/audit (SAH-2, SAH-3), no app shell or
theme (SAH-4), no API/table/screen design. Matches the playbook's Stage A "Done when" line.

### Tech-lead decisions on the analyst's open questions

**Q1 — must SAH-1 tests require a live PostgreSQL database? DECIDED: no.**
SAH-1 adds no schema, no entity and no data access. A Spring context-load test and the Modulith
verification test need no database. The first real `sahaj_test` dependency belongs to SAH-2, which
adds the first Flyway migration. Consequence for the architect and java-developer: SAH-1 must not
leave an auto-configured `DataSource` that the context tries to open on startup — either leave JPA
and Flyway off the classpath at this stage, or exclude the datasource autoconfiguration until SAH-2.
Reversible, and it keeps SAH-1 unblocked while the local database is still unverified.

**Q2 — pin Angular/Node versions? DECIDED: already answered by `CLAUDE.md`.**
`CLAUDE.md` says "Angular current major" and "Node 20+ LTS". Take what `ng new` installs today and
record the exact version in the development handoff. No separate pinning policy for SAH-1.

**Q3 — should the Modulith test also enforce one-schema-per-module? DECIDED: no, not yet.**
`CLAUDE.md` states the boundary rule as "modules talk only through their `api` package or domain
events". That is what the test enforces at SAH-1. No module owns a schema yet; revisit at SAH-2
when the first schema appears.

**Q4 — who owns `.env.example`? RESOLVED BY FACT: humans only.**
Not a judgement call. `.claude/hooks/guard-ownership.mjs` assigns ownership by path regex; a
root-level `.env.example` matches no area, so it falls through to `owner = 'human'`,
`area = 'protected'`, and the hook blocks every agent that tries to write it.

This makes one acceptance criterion in story SAH-1-4 unsatisfiable by any agent: "`.env.example`
is re-created at the repository root". The file is tracked in git and only deleted in the working
tree, so the human restores it with
`git -C D:\generi_clinical_platform restore sahaj-claude-team/sahaj-platform/.env.example`.
Developer agents must instead report any new variable they need in their handoff, for the human to
add. Flagged to the human.

Open questions / requests for other owners: none outstanding. Q1-Q3 decided above, Q4 resolved.

Next: **waiting for the human's `stories` approval** before the solution-architect may start.
The hook blocks solution-architect until then.

---

## Tech-lead note · stage 3 plan for SAH-1

The playbook lists SAH-1's agents as java-developer, angular-developer and qa-engineer, with no
solution-architect — correct, since a scaffold exposes no endpoints and needs no `docs/api/*.yaml`.

But `.claude/hooks/guard-ownership.mjs` requires **both** `stories` and `contract` APPROVED before
java-developer, angular-developer or qa-engineer may write. So SAH-1 needs a `contract` approval
line regardless of having no API surface.

Human decision (31 Oct 2026): **run the solution-architect for an ADR.** It writes an ADR covering
the scaffold baseline — module package layout, Spring Modulith configuration, the
no-`DataSource`-until-SAH-2 decision from Q1, and the Angular + Material version baseline. No
OpenAPI file. The human then approves `contract` as "ADR reviewed, no API surface in this story".

This gives the Q1-Q3 decisions a permanent home in `docs/architecture/` and gives SAH-2's architect
a recorded baseline to build on.

Approvals still needed, in order:
1. `| SAH-1 | stories | APPROVED | Ganesh | <date> |` → unblocks solution-architect
2. `| SAH-1 | contract | APPROVED | Ganesh | <date> |` → unblocks java-developer, angular-developer, qa-engineer

---

## Handoff · SAH-1 · solution-architect → developers
Status: DONE
Files changed:
- `docs/architecture/adr/ADR-001-scaffold-baseline.md` — created. Six decisions D1-D6.
- No `docs/api/*.yaml` — correct, SAH-1 exposes no endpoint.

Validation: `node scripts/validate.mjs docs` → PASSED (run by tech-lead; architect has no Bash tool).

Notable: the architect added a constraint of its own — the six module packages must be *direct*
sub-packages of `com.sahaj`, because Spring Modulith derives boundaries from the packages
immediately below the application class. Put them deeper and the D2 boundary check is inert.
Good catch; accepted. For D3 it chose to omit JPA/Flyway/driver from `pom.xml` entirely rather
than excluding `DataSourceAutoConfiguration`, on the grounds that an absent dependency cannot
misconfigure. Accepted; SAH-2 reverses it as a 5-step additive change documented in the ADR.

---

## Handoff · SAH-1 · java-developer → qa-engineer
Status: DONE
Files changed:
- `backend/pom.xml` — Spring Boot 3.3.4, Java 17, Spring Modulith 1.3.0 BOM. `spring-boot-starter`
  only: no web, no JPA, no Flyway, no DB driver, per ADR D3.
- `backend/src/main/java/com/sahaj/SahajApplication.java`
- `backend/src/main/java/com/sahaj/{platform,patient,opd,ipd,hra,billing}/package-info.java`
- `.../{module}/api/package-info.java` — the six `api` sub-packages
- `backend/src/main/resources/application.yml` — `server.port`, `spring.profiles.active` only, both env-driven
- `backend/src/test/java/com/sahaj/SahajApplicationTests.java` — context load, no DB
- `backend/src/test/java/com/sahaj/ModularityTests.java` — `ApplicationModules.of(...).verify()`

Validation: `node scripts/validate.mjs backend` → **PASSED** (re-run and confirmed by tech-lead).
`validate db` → PASSED. db-engineer's and qa-engineer's areas untouched.

Environment variables for the human to add to `.env.example` (no agent can write it):
| Variable | Placeholder | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | Active Spring profile |

**For the reviewers — one item needs a second opinion.** The java-developer excluded
`spring-modulith-apt` from `spring-modulith-starter-core`, because on this machine it pulled
`spring-boot-configuration-processor:3.3.4` and broke compilation with
`NoClassDefFoundError: org/springframework/boot/json/JsonWriter$Members`. The exclusion is
defensible — `spring-modulith-apt` only generates module documentation and is not needed by
`ApplicationModules.verify()` — but the stated cause reads more like a **corrupted or mixed local
`.m2` cache** than a genuine version incompatibility, since `JsonWriter` is not a Boot 3.3.4 class.
code-reviewer: please judge whether this is the right fix or a workaround masking a dirty local
Maven cache, and whether it should be reverted after a `mvn dependency:purge-local-repository`.

---

## Handoff · SAH-1 · angular-developer → qa-engineer
Status: **BLOCKED** — see `docs/decisions/DEC-002-validate-frontend-test-flag.md`
Files changed:
- `frontend/` — Angular 22.2.5 app via `ng new`, Angular Material 22.2.5, Angular CDK 22.2.5,
  ESLint via `ng add @angular-eslint/schematics`. Default generated app content kept (no shell,
  no theme, no routes — that is SAH-4). `frontend/.gitkeep` removed.
- Versions: Angular CLI 22.2.5, core 22.2.4, TypeScript 6.0.3, RxJS 7.8.2, Node 26.13.0, npm 11.2.0.
  Test runner: `@angular/build:unit-test` → **Vitest 5.0.5 on jsdom** (not Karma).

Environment variables introduced: **none.** Angular 22 generates no `environment.ts`, and this
story builds no screen or service that reads config.

Validation: `node scripts/validate.mjs frontend` → **FAILED**, reproduced by tech-lead.
Cause: `validate.mjs` passes the Karma flag `--browsers=ChromeHeadless` to a Vitest runner, which
reads it as "run in a real browser" and aborts for a missing browser provider.

Tech-lead's own verification: `lint` PASSED, `build` PASSED, `test -- --watch=false` PASSED (2/2).
The scaffold is sound; only the flag is wrong. The fix is in `scripts/validate.mjs`, which is
**humans-only**, so neither the angular-developer nor the tech-lead may apply it. The agent
correctly refused to downgrade Angular, retrofit Karma, install a browser provider or edit the
validator. Escalated as DEC-002, recommending the flag be dropped.

---

## CORRECTION · the `.env.example` restore instruction was unsafe

Earlier in this handoff, and three times in session, the tech-lead told the human to run
`git restore` on `.env.example`. **That instruction was wrong and is withdrawn.**

The security review (C1, verified by the tech-lead) found that the committed `.env.example` holds a
**real PostgreSQL superuser password**, not a placeholder. Restoring the file as instructed would
have re-materialised a live credential on disk as the next action in this story.

The corrected instruction is in `docs/decisions/DEC-003-committed-superuser-password.md`: rotate the
password first, then restore the file and immediately reset `PG_SUPERUSER_PASSWORD` to `change-me`
before committing anything.

Request for the solution-architect: ADR-001 decision D5 carries the same unsafe restore instruction.
It needs the same correction. The tech-lead cannot edit `docs/architecture/`.

---

## Handoff · SAH-1 · code-reviewer → tech-lead
Status: DONE — APPROVE WITH COMMENTS (0 Blockers, 0 Majors, 1 Minor, 3 Nits)
Report: `docs/reviews/SAH-1-code.md`

Confirmed D1/D2 live and not vacuous (module packages are direct children of `com.sahaj`, so the
Modulith check really binds), D3 and D5 clean within the diff, no ownership violations, no scope creep.

**Its central conclusion was wrong and the tech-lead overruled it** — see below.

## Handoff · SAH-1 · security-reviewer → tech-lead
Status: DONE — **FAIL: 1 Critical, 0 High, 5 Medium, 7 Low**
Report: `docs/reviews/SAH-1-security.md`
Escalated as `docs/decisions/DEC-003-committed-superuser-password.md`.

## Tech-lead resolution of a reviewer disagreement

The two reviewers reached opposite conclusions on the `spring-modulith-apt` exclusion, which is an
escalation trigger in its own right. The tech-lead resolved it with direct evidence instead:

- code-reviewer: dirty local `.m2` cache; Modulith 1.3.0 and Boot 3.3.4 are compatible.
- security-reviewer (M3): a genuine mismatch; Modulith 1.3.x targets Boot 3.4.x.
- **Verified: `spring-modulith-apt-1.3.0.pom` declares `spring-boot 3.4.0` and
  `spring-boot-configuration-processor 3.4.0`.** The security-reviewer is correct. A cache purge
  reproduces the failure rather than fixing it. The code-reviewer saw the right facts — two jars in
  the cache, the missing `JsonWriter` class — but read the mechanism of the bug as evidence against it.

Action: align Boot and Modulith in SAH-2 (Boot 3.5.x + Modulith 1.4.x preferred, which also clears
security M2) and drop the exclusion. Recorded in DEC-003.

## Stage status

| Stage | Status |
|---|---|
| business-analyst | DONE |
| solution-architect | DONE |
| db-engineer | Not required — ADR-001 "Data needed: none" |
| java-developer | DONE, `validate backend` PASSED |
| angular-developer | BLOCKED on DEC-002 (human-owned `scripts/validate.mjs`) |
| qa-engineer | **Not started** — its Stop hook runs `validate all`, which fails on DEC-002, so it would spin and report BLOCKED. Deliberately deferred. |
| code-reviewer | DONE |
| security-reviewer | DONE — FAIL |
| commit / merge | **Held on DEC-003** |

Two human decisions now block SAH-1: **DEC-002** (one-line flag fix) and **DEC-003** (Critical).
