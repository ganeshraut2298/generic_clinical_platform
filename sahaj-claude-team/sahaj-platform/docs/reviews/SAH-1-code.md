# Code review · SAH-1 · Project scaffold

Branch: `feature/SAH-1-project-scaffold`
Reviewer: code-reviewer (read-only)
Date: 31 Oct 2026

## Verdict: APPROVE WITH COMMENTS — 0 Blockers, 0 Majors, 1 Minor, 3 Nits

**But see the correction below: this review's central conclusion on the pom was wrong.** The
security review reached the opposite finding on the same evidence, and the tech-lead verified the
security review is correct. The story is held on DEC-003 regardless, for a Critical the security
review found.

The reviewer reproduced the build itself (`mvn -o test`): `SahajApplicationTests` (context load,
dev profile, no DB) and `ModularityTests` (`ApplicationModules.verify()`) both pass against the
real `backend/pom.xml`. `frontend/` default-generated content confirmed untouched.

---

## Correction · the `spring-modulith-apt` exclusion

The tech-lead asked both reviewers to judge whether excluding `spring-modulith-apt` was a real fix
or a workaround masking a dirty local `.m2` cache. **The two reviewers disagreed, so the tech-lead
resolved it with direct evidence.**

| | Conclusion |
|---|---|
| code-reviewer | Dirty/mixed local `.m2` cache. Modulith 1.3.0 + Boot 3.3.4 have "no real compatibility problem". Keep the exclusion, fix the comment wording. |
| security-reviewer (M3) | A genuine version mismatch. Modulith 1.3.x targets Boot 3.4.x. Align the versions instead of excluding. |
| **tech-lead verification** | **security-reviewer is correct.** |

Evidence: `spring-modulith-apt-1.3.0.pom` declares `spring-boot 3.4.0` and
`spring-boot-configuration-processor 3.4.0`. `spring-boot-starter-parent:3.3.4` downgrades the
processor's own dependency to 3.3.4; the processor, compiled against 3.4.0's `JsonWriter`, then
fails with `NoClassDefFoundError: org/springframework/boot/json/JsonWriter$Members`. `JsonWriter`
exists in `spring-boot-3.4.0.jar` and not in `3.3.4.jar`.

The code-reviewer observed the same two jars in the local cache and the same missing class, but
inferred cache pollution from them. Its own `dependency:tree` result — the processor resolving to
3.3.4 — was in fact the *mechanism of the bug*, not evidence against it. A
`dependency:purge-local-repository` will reproduce the failure, not fix it.

Consequence: the exclusion is not merely a comment-accuracy issue. It silences a real mismatch.
The fix is to align the pair — Boot 3.5.x + Modulith 1.4.x (also resolves security M2, Boot 3.3.4
being out of support), or Modulith 1.2.x + Boot 3.3.x — and then drop the exclusion. Carried into
DEC-003 for SAH-2.

The reviewer's secondary point stands and is useful: `spring-modulith-apt` is genuinely unneeded for
`ApplicationModules.verify()` (it only generates module documentation via the `Documenter` API), so
removing it costs no functionality either way.

---

## D1/D2 compliance — confirmed real, not vacuous

`com.sahaj.{platform,patient,opd,ipd,hra,billing}` are direct sub-packages of `com.sahaj`, the same
level as `SahajApplication`, each with an `api` sub-package. Spring Modulith derives boundaries from
packages directly below the `@SpringBootApplication` class, so the check is **live, not inert** —
which was the specific risk the tech-lead asked about.

Since every module is empty except `package-info.java`, this is only a smoke proof that the check is
wired in. It cannot yet prove the check *catches* a violation, because there is no second module with
real code to violate anything. That is an accepted, documented limitation (ADR-001 D2), not a defect.

## D3 compliance — confirmed

`backend/pom.xml` carries only `spring-boot-starter`, `spring-modulith-starter-core`/`-test` and
`spring-boot-starter-test`. No `spring-boot-starter-data-jpa`, no `flyway-core`, no PostgreSQL driver.
No `@Entity`, repository or `DataSource` config found by search.

## D5 compliance — confirmed within the diff

No secrets, passwords, tokens or connection strings in `backend/` or `frontend/` across
`.java/.xml/.yml/.ts/.json`. `application.yml` reads only `SERVER_PORT` and
`SPRING_PROFILES_ACTIVE` with non-secret defaults.

**Note:** this is correct for the diff but not for the repository. The security review found a real
superuser password committed in `.env.example` on `main` (C1). `git diff main` does not show it, which
is why this review missed it — a useful lesson about scoping a review to the diff.

## Story coverage

- **SAH-1-1 · met.** Pom exists, Java 17 + Boot 3.x, six module packages each with `api`, context
  loads in `dev` with no DB, `backend/src` tree replaced.
- **SAH-1-2 · met as scoped** by ADR-001 D2 (smoke proof; genuinely wired in).
- **SAH-1-3 · met** for `lint`/`build`; `test` passes when run directly. The failure under
  `scripts/validate.mjs` is DEC-002's Karma-flag/Vitest mismatch in a human-owned file, outside both
  developers' reach. Correctly not logged as a developer defect.
- **SAH-1-4 ·** nothing beyond the known-unmet `.env.example` item is missed. Env vars used are
  correctly reported in the handoff.

## Ownership — no violations

java-developer touched only `backend/pom.xml`, `backend/src/main/java/**`,
`backend/src/main/resources/application.yml`, and two files directly under
`backend/src/test/java/com/sahaj/` — not under an `it/` package, so QA's territory is untouched.
`backend/src/main/resources/db/migration/` left with `.gitkeep` only. angular-developer touched only
`frontend/` and created nothing under `frontend/e2e/`. Neither touched `scripts/`, `CLAUDE.md`,
`.claude/`, `.env*` or `docs/design/`.

## Scope creep — none

No tenancy/RLS/audit (SAH-2), no auth (SAH-3), no app shell/theme/routes (SAH-4). `frontend/src/app/*`
is still the default `ng new` scaffold.

## Findings

| # | Severity | File | Finding | Fix |
|---|---|---|---|---|
| 1 | Minor | `backend/pom.xml` | Comment on the `spring-modulith-apt` exclusion misattributes the cause to cached-artifact incompatibility. Per the correction above, the real cause is a genuine Modulith 1.3.x / Boot 3.3.x version mismatch — so the comment is wrong in a way that would mislead the next developer into purging their cache fruitlessly. | Superseded by DEC-003: align Boot and Modulith versions in SAH-2 and drop the exclusion entirely. |
| 2 | Nit | `ModularityTests.java` | No `@DisplayName` clarifying that this only proves the check is wired in, not that it catches violations. Class Javadoc already says so. | None required. |
| 3 | Nit | `frontend/package.json` | `test` script is plain `ng test` with no headless flags baked in. | Revisit alongside DEC-002. |
| 4 | Nit | `frontend/src/app/app.ts` | Lacks `ChangeDetectionStrategy.OnPush`, which `.claude/rules/angular.md` mandates. Raised by the security-reviewer as a code-review matter. | Default generated file, replaced at SAH-4. Fix there. |

## Overall

The scaffold does what SAH-1-1 through SAH-1-3 ask and honours ADR-001's D1-D6 faithfully. Nothing in
the developers' own work blocks merge. The story is held on DEC-003 for the committed credential
(security C1) and will take the Boot/Modulith realignment as part of SAH-2.
