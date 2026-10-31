# Security review · SAH-1 · Project scaffold

Branch: `feature/SAH-1-project-scaffold`
Reviewer: security-reviewer (read-only)
Date: 31 Oct 2026
Scope: `backend/`, `frontend/`, `application.yml`, lockfiles, `.gitignore`, git history of credential files.
Calibrated to a scaffold — absence of auth (SAH-3), tenancy/RLS/audit (SAH-2) and any datasource is
**not** reported as a finding.

## Verdict: FAIL — 1 Critical, 0 High, 5 Medium, 7 Low

**Escalated to the human as DEC-003. The story cannot merge.**

The scaffold the developers wrote is clean. The Critical is a credential committed to `main` before
this branch existed, which `git diff main` therefore does not show.

---

## Critical

### C1 · A real PostgreSQL superuser password is committed to git history

**File:** `.env.example`, `PG_SUPERUSER_PASSWORD` (deleted in the working tree, still tracked and in history)
**Reachable from:** both `main` and `feature/SAH-1-project-scaffold`

**Verified independently by the tech-lead** (value deliberately not reproduced here):

| Check | Result |
|---|---|
| `PG_SUPERUSER_PASSWORD` on `main` equals `change-me`? | **No** — it is a real value |
| How many of the three password vars are still `change-me`? | **2 of 3** — only `PG_SUPERUSER_PASSWORD` was filled in |
| `SAHAJ_DB_MIGRATOR_PASSWORD`, `SAHAJ_DB_APP_PASSWORD` | still `change-me` — not affected |

So exactly one real credential is committed, and it is the **superuser** one.

**Risk.** `scripts/setup/create-database.mjs` passes this variable as `PGPASSWORD` to connect as
the PostgreSQL superuser, and it explicitly rejects any value starting with `change-me` — so a
committed non-`change-me` value is by construction the working credential. Breaks `CLAUDE.md`'s
non-negotiable "No secrets in code" rule and falsifies SAH-1-4's first acceptance criterion
("when the repository is searched for ... passwords ... then none are found in committed source").
OWASP ASVS V14.1.4 / V6.4.1, CWE-798, CWE-540. DPDP Act s.8(5) requires reasonable security
safeguards for systems that will hold patients' health data.

**Exploitability today: low.** Git is local-only — no remote, no CI (`CLAUDE.md`) — and the account
is a local dev instance. Rated Critical anyway because:
- Git history is permanent; the exposure window never closes on its own. It opens the first time a
  remote, backup, archive or the planned GitHub migration happens.
- Deleting the file, which the current working tree does, removes **nothing** from history.
- **The remediation previously documented made it worse.** ADR-001 D5 and `docs/handoffs/SAH-1.md`
  both instructed the human to `git restore .env.example`, which re-materialises the secret on disk.
  The tech-lead issued that instruction three times in session before this review caught it. Both
  documents and the instruction are now corrected.

**Fix (human — repo root and `scripts/` are humans-only):**
1. Rotate the local `postgres` password, and rotate it anywhere else the value was reused. Note it
   was also pasted into the session chat, so treat it as compromised regardless of git.
2. Restore `.env.example`, then immediately reset `PG_SUPERUSER_PASSWORD` to `change-me` before
   committing. Real values live only in `.env`, which is correctly git-ignored (verified).
3. Purge history before any remote is added. The repo is three commits old and local, so a fresh
   squashed initial commit is simpler and safer than `git filter-repo --replace-text`.
4. Consider adding a secret-scan leg to `scripts/validate.mjs`: fail if any tracked file matches
   `PASSWORD=`/`SECRET=`/`TOKEN=` with a value that is not `change-me` or empty. The gate has no
   such check, which is why this survived three commits and two agent stages.

## High

None.

## Medium

### M1 · `SahajApplicationTests` pins the `dev` profile — will hit `sahaj_dev` at SAH-2
`backend/src/test/java/com/sahaj/SahajApplicationTests.java` — `@ActiveProfiles("dev")`.
Harmless today (no datasource). But `.claude/rules/java-spring.md` requires tests to run with
profile `test` against `sahaj_test`, and permits `Flyway clean` only there. The moment SAH-2 performs
the D3 reversal, this test opens a context against the developer's **dev** database, and a later
`@DataJpaTest` inheriting the pattern could migrate or clean `sahaj_dev`. Exactly the "scaffold
choice that makes SAH-2 unsafe" category. **Fix:** use `@ActiveProfiles("test")` or drop the
annotation — the criterion is only "the context loads".

### M2 · Spring Boot 3.3.4 is out of open-source support
`backend/pom.xml`. The 3.3.x line left free maintenance in mid-2025, so the scaffold starts ~16
months behind on security releases. Advisories on these coordinates include CVE-2024-38819/38820
(Framework, fixed 6.1.14), CVE-2025-22233 (DataBinder, fixed 6.1.19) and CVE-2025-22235 (actuator
authorization bypass, fixed 3.3.12). **None is exploitable in SAH-1** — no web starter, no listening
socket, no `DataBinder`, no actuator (verified). **Becomes High the day SAH-3 adds
`spring-boot-starter-web` and Spring Security.** Fix in SAH-2 while the blast radius is one file.

### M3 · The `spring-modulith-apt` exclusion masks a real version mismatch, not a dirty `.m2`
`backend/pom.xml` — Modulith 1.3.0 on Boot 3.3.4.

**This contradicted the code-reviewer, so the tech-lead verified it directly. The
security-reviewer is right.** `spring-modulith-apt-1.3.0.pom` declares:

```
spring-boot                         3.4.0
spring-boot-configuration-processor 3.4.0
```

Modulith 1.3.x targets Boot **3.4.x**. `spring-boot-starter-parent:3.3.4` downgrades the managed
version of the processor's own dependency to 3.3.4, and the processor — compiled against 3.4.0's
`JsonWriter` — fails with `NoClassDefFoundError: org/springframework/boot/json/JsonWriter$Members`.
`JsonWriter` exists in `spring-boot-3.4.0.jar` and not in `3.3.4.jar`.

So a cache purge will **reproduce** the failure, not fix it. The Boot-3.3-compatible Modulith line
is 1.2.x. **Fix:** align the pair rather than excluding — Boot 3.5.x + Modulith 1.4.x (preferred,
also resolves M2), or Modulith 1.2.x + Boot 3.3.x. Then drop the exclusion. Leaving a mismatched
pair plus a silencing exclusion is a supply-chain trap for the next developer.

### M4 · Google Fonts loaded from Google's CDN — third-party transfer out of India
`frontend/src/index.html` — `preconnect` plus two stylesheets from `fonts.googleapis.com` /
`fonts.gstatic.com`, generated by `ng new`. Unlike `app.html`, `index.html` survives SAH-4.
Every clinic session makes an unconditional request to Google, disclosing client IP, `User-Agent`
and `Referer` to a third-party processor outside India. No patient data in the payload, but the
metadata is a cross-border transfer with no contract, notice or consent, cutting against
`CLAUDE.md`'s "all patient data stays in India" posture and DPDP transparency duties. It also forces
any future CSP to allowlist a remote `style-src`/`font-src`, permanently weakening SAH-4's policy.
**Fix:** self-host Roboto and Material Symbols (`@fontsource/roboto` + local icon font), drop both
`preconnect` hints, and set `<title>` to Sahaj (currently `Frontend`).

### M5 · The approval-gate hook is disabled while the docs still claim it is enforced
`.claude/hooks/guard-ownership.mjs` — `const NEED = {};`. Human-authorised (DEC-001), not an agent
overstep, and not contested. The finding is that only **step 1** of DEC-001 was applied: `CLAUDE.md`
rule 2 still says "Hooks block an agent whose earlier stage is not approved", and `docs/approvals.md`
still says "An agent cannot start its stage until the earlier stage is APPROVED here" while the
table holds **zero** rows. A control the documentation asserts and the code does not enforce is
worse than either state alone — the next reviewer or agent trusts the wrong one, and `merge` is now
the single remaining human checkpoint for a health-data platform. **Fix:** apply DEC-001 steps 2-4.

## Low

- **L1 · logback 1.5.8** — CVE-2024-12798 (ACE via `JaninoEventEvaluator`), CVE-2024-12801 (SSRF via
  `SaxEventRecorder`), fixed in 1.5.13. Both need attacker-controlled logback *configuration*, which
  is committed, not user-supplied, so real risk is negligible. Fixed for free by M2's bump.
- **L2 · `npm audit`: 18 moderate, all dev-only** — root cause `minimatch < 11.0.1`
  (GHSA-g3hv-75wg-m249, ReDoS) via the eslint chain. `npm audit --omit=dev` reports **0**, so nothing
  ships to a browser. Hygiene otherwise good: all 544 lockfile entries resolve to
  `registry.npmjs.org`, every one has an `integrity` hash, no typosquats, and the only
  `hasInstallScript` packages are expected native builds. Fix when upstream releases; do not force.
- **L3 · No CSP, Angular auto-CSP off** — `frontend/angular.json`. Correct for a scaffold with no
  screens; flagged because SAH-4 is the cheap moment, and M4 will block a strict policy if left.
  Positive: production build does not enable `sourceMap`, `outputHashing: all` is set, and
  `defaultConfiguration` is `production`.
- **L4 · Committing the `.env.example` deletion loses the only record of required variables** — with
  the pre-code gates gone (M5) and the file writable only by the human, the handoff table is
  otherwise the sole record of backend config. Restore with placeholders rather than merging the
  deletion.
- **L5 · No Maven wrapper** — `scripts/validate.mjs` falls back to whatever `mvn` is on PATH, so the
  toolchain is unpinned per machine. `mvn wrapper:wrapper` in SAH-2.
- **L6 · Unreviewed third-party tool-recording hook in the repo** —
  `.github/modernize/java-upgrade/hooks/scripts/recordToolUse.{sh,ps1}`, a VS Code "App
  Modernization" artifact, not from this story. It appends raw tool-call JSON — including terminal
  command lines, which routinely contain credentials — under `.github/modernize/`. It self-ignores
  via its own `.gitignore` (`**/*`) and no session files exist, but it is an unowned write path that
  can capture secrets into the working tree, and it falls outside `CLAUDE.md`'s ownership table.
  **Delete the directory if the extension is not in use.**
- **L7 · Default `ng new` template still present** — `frontend/src/app/app.html` with outbound links
  to `angular.dev`, `github.com`, `x.com`, `youtube.com`. Removed at SAH-4 by design; recorded so the
  SAH-4 reviewer confirms it. `app.ts` also lacks `ChangeDetectionStrategy.OnPush`, which
  `.claude/rules/angular.md` mandates — code-reviewer's call, not security.

## Info — checked and found correct

- **Config genuinely environment-driven.** `application.yml` contains exactly `${SERVER_PORT:8080}`
  and `${SPRING_PROFILES_ACTIVE:dev}`, both non-secret defaults. No password, key, token, keystore or
  credential-bearing connection string anywhere in `backend/src`, `backend/pom.xml`, `frontend/src`
  or `frontend/package.json`.
- **`.gitignore` correct.** `git check-ignore -v .env` → ignored; `.env.example` not ignored (the
  trailing `!.env.example` correctly overrides `.env.*`). The real `.env` exists on disk, untracked,
  and was not read. `backend/target/` ignored.
- **ADR D3 verified, not assumed.** `mvn dependency:tree` shows no `spring-boot-starter-web`, no
  Tomcat, no JPA, no `flyway-core`, no `org.postgresql:postgresql`. No `DataSource` can be
  autoconfigured, so the Tomcat and JDBC CVE classes are simply absent.
- **No logging or error-disclosure misconfiguration.** Zero occurrences of `actuator`, `management.*`,
  `logging.level`, `DEBUG`, `TRACE` or `include-stacktrace` in `backend/`.
- **No unsafe Angular patterns.** No `bypassSecurityTrust*`, `innerHTML`, `eval(`, `localStorage`,
  `sessionStorage` or `console.log`. Sanitisation untouched.
- **Nothing obstructs the SAH-2 tenancy model.** `scripts/db/init.sql` already creates `sahaj_app` as
  `NOSUPERUSER NOBYPASSRLS` and non-owner, so RLS will bind correctly. **Worth remembering at SAH-2:
  a table owner bypasses RLS unless `FORCE ROW LEVEL SECURITY` is set — verify policies as
  `sahaj_app`, not as the migrator.**
- **The gate already has one tenancy control.** `scripts/validate.mjs` fails any migration creating a
  table with `tenant_id` but no `ENABLE ROW LEVEL SECURITY`. It will earn its keep at SAH-2.
- Access control, IDOR, parameterised SQL, output masking, audit entries and consent checks are all
  **not applicable** — no endpoint, no SQL, no entity, no patient field, no clinical or billing
  action. Confirmed absent rather than assumed.
