# QA report · SAH-35, SAH-36, SAH-38 (release audit, DEC-025)

Status: COMPLETE (8 Nov 2026). Verdict at the end: NOT READY until the human adds the approval lines and makes the decisions listed below; technically green.

## Phase 0 · Discover (code-reviewer, read-only)
Earlier review findings verified fixed in code: R35-1, R35-2, R35-3, R35-5; R36-3, R36-4, R36-5/S36-2, R36-7, R36-8, R36-9; R38-1, R38-4, R38-5; S38-1, S38-3, I38-5. Still open by earlier decision: R36-2 ("+N" chip), R36-6 (bill fields to DOCTOR/NURSE), R38-2, R38-3, R38-6, S38-2, `.env.example` entries, approval lines.

### Validation matrix (field × rule × layer)
| # | Input | Rule | FE | BE | DB |
|---|---|---|---|---|---|
| 1-2 | GET /ipd/safety, /safety/summary | roles OWNER/DOCTOR/NURSE (token + membership) | tab shown only if allowed | ✔ SafetyService role, module, membership | ✔ RLS |
| 3 | GET /ipd/admissions `status` | only ADMITTED | constant | ✔ 400 `status` | n/a |
| 4 | Card search box | contains match, trimmed, case-insensitive (never sent) | ✔ | n/a (device only, AC19) | n/a |
| 5 | Ward select | from server list | ✔ | n/a (device only) | n/a |
| 6-7 | View, cards per row, start view | fixed enums; abilities from server | ✔ | ✔ abilities + per-call role checks | n/a |
| 8 | dose path `{id}` | UUID, order in caller's clinic | server data | ✔ 404 tenant-bound | ✔ RLS + composite FK |
| 9 | `clientKey` | UUID, replay rules | ✔ new key per change | ✔ | ✔ uuid + unique |
| 10 | `slotAt` | today's dose time (IST), window | server value | ✔ | ✔ checks, unique, guard |
| 11 | `outcome` | GIVEN / NOT_GIVEN | fixed buttons | ✔ | ✔ checks |
| 12 | `wristband` | max 200, must equal UHID (NFKC) | ✔ maxlength, Enter blocked | ✔ | ✔ 1-40 stored |
| 13 | `identityConfirmed` | JSON true | ✔ checkbox | ✔ | ✔ check |
| 14 | `secondNurseId` | active NURSE, not caller | server list | ✔ tenant-bound | ✔ other-than-caller check (role is cross-schema: BE only) |
| 15 | `secondNursePin` | 4 digits | ✔ maxlength 4, digits only (no pre-send check: G-9) | ✔ format before compare, pair cap, locks | ✔ guard + event table |
| 16 | `reasonKey` | 5 keys | ✔ select | ✔ | ✔ check |
| 17 | MAR path id | tenant 404 | server data | ✔ | ✔ RLS |
| 18 | `seenThrough` | ≤ 40 chars, ISO, ≥ 2020-01-01, ≤ +1 day | passes server value | ✔ before any DB call | ✔ capped at DB clock |
| 19-22 | notice, history path id, history paging (newest 100), PIN reset path id | own data / OWNER only / 30 per hour | ✔ | ✔ 403 before 404, 429 | ✔ RLS, FKs, guard |

### Tenant isolation / IDOR
No defect: every statement binds `tenant_id` under FORCE RLS; ids from paths are looked up within the tenant (404 otherwise); owner checks come from the re-read membership; FRONT_DESK never loads allergy data.

### Gap list (Phase 1/2 work)
| ID | Sev | Problem | Decision | Owner |
|---|---|---|---|---|
| G-1 | 3 | No body-size limit on signed-in JSON endpoints (only public 4 KB) | FIX: cap /api/v1/** bodies (e.g. 64 KB), 413 problem+json | java-developer |
| G-2 | 3 | A failed 60 s reload with 403 (role changed, removed, IPD off) keeps old clinical data on screen | FIX: on 403 clear the list/board and show the refusal; stale data only for network/5xx | angular-developer |
| G-3 | 3 | Focus after Give goes to the heading, not the next row (AC19, ADR-012 D4) | FIX to match AC19 | angular-developer |
| G-4 | 3 | No request log with user id (compensating control of S36-1) | FIX: one INFO request-log line (method, path template, status, tenantId, userId, time; no query values, no names); retention 180 days = human | java-developer, human |
| G-5 | 3 | Contrast: "Going home today" about 3.6:1, `--ink-3` small text about 4.4:1 | HUMAN: token colours come from the mockup; measure in Phase 2 | human |
| G-6 | 3 | 4-per-row tiny action text also applies when the grid shows 2 or 1 columns; possible clipping at 1366 px | FIX | angular-developer |
| G-7 | 3 | e2e only at 360 and 1440 px | FIX: add 1920/1366/1024/768/375 + 125% zoom runs | qa-engineer |
| G-8 | 3 | No approval lines; ADR-012/013/014 PROPOSED | HUMAN (blocks READY by workflow) | human, architect |
| G-9 | 4 | No FE pre-check of second nurse + 4-digit PIN | FIX with the contract text | angular-developer |
| G-10 | 4 | Search has no maxlength; live region chatters per keystroke | FIX: maxlength 100, debounce 400 ms | angular-developer |
| G-11 | 4 | ESC/backdrop closes a filled Give sheet | KEEP (mockup: plain close); human may decide | human |
| G-12 | 4 | Unreadable JSON field always "Choose a valid option" | FIX: contract text for `seenThrough`/`clientKey` | java-developer |
| G-13 | 4 | DB error first line logged can quote a value | FIX: cut before the quoted value | java-developer |
| G-14 | 4 | Skipped-order WARN repeats every 60 s per user | FIX: once per order per minute | java-developer |
| G-15 | 4 | Possible U+202F before AM/PM from Intl | CHECK in Phase 2, normalise if seen | angular-developer |
| G-16 | 4 | Malformed `{id}` 400 not listed in four operations | FIX contract text | solution-architect |

### Mockup vs acceptance-criteria conflicts (not guessed; for the human)
- M-1 (SAH-36): mockup resets page size to 24 on every toolbar action incl. "Cards per row"; AC8/build only on search, ward, view.
- M-2 (SAH-35): mockup Give button 36 px `btn sm pri`; AC19/build 44 px `mat-flat-button`.
- M-3 (SAH-35): mockup shows "Your patients" tile to every non-owner incl. DOCTOR; AC9/build NURSE only.
- M-4 (SAH-36): AC25 says both "Going home today" and "Deposit low" show; mockup/AC26/build show one plus "+N".
- M-5: AC19/ADR-012 say next row's Give; build went to same row/heading (being fixed as G-3).
- M-6: mockup Team page "PIN set" / "Send PIN link"; build "Reset PIN" (DEC-017).

## Phase 1-2 · Fixes (in progress)
## Phase 3-4 · Tests (in progress)
## Phase 5 · Regression and sign-off (pending)

### Baseline before the audit fixes (tech lead, 8 Nov 2026)
- `node scripts/validate.mjs backend`: PASSED. Unit 3,259 / 0 failures / 0 errors; integration 1,666 / 0 failures / 0 errors / 26 skipped (dev sample admin preconditions). Includes the first run of migration V2026_12_03_1000 and its guard tests. (An earlier run the same evening was void: `backend/target` was rebuilt mid-run, most likely by the IDE's Java build, giving 238 ClassNotFound 500s.)
- Frontend: lint pass, 146 unit files pass, production build pass (before the SAH-39 and audit changes).
- Start-up check `mvn -f backend/pom.xml spring-boot:run` (dev): compiled, Flyway validated 78 migrations on `sahaj_dev` (already at 2026.12.03.1000), context initialised in 31 s; stopped only because port 8080 was in use by an already running Sahaj backend (started 7 Nov 19:37). Note: `SAHAJ_PIN_PEPPER` is not set in this environment, so staff PIN checks are off in dev (human: set it in `.env`).

## Phase 1-2 · Fixes made (8 Nov 2026)
| ID | Fix | Where | Tests |
|---|---|---|---|
| G-1 | 413 problem+json for bodies over 64 KB on all signed-in /api/v1/** (public stays 4 KB); env `SAHAJ_MAX_REQUEST_BODY_BYTES` | RequestBodySizeLimitFilter.java, SecurityConfig.java:132,163, application.yml | unit 14; IT by QA |
| G-2 | 403 on a 60 s reload clears the Admitted list / Safety board and shows the refusal; stale data only for network/5xx | ipd.page.ts (refuse), safety.component.ts (reloadFailed) | unit 5; e2e by QA |
| G-3 | Focus after Give goes to the next row's Give (same index), else last, else heading | medicine-round.component.ts:104,148 | unit; e2e by QA |
| G-4 | `sahaj.access` INFO line per API request: method, path template, status, duration, tenantId, userId, requestId; no query/body/names | AccessLogFilter.java (new), RequestLogContextFilter.java | unit 9; IT by QA |
| G-6 | 4-per-row small action text only where 4 columns render (>1100 px) | patient-card.component.scss | browser check by QA |
| G-9 | High-alert Given without nurse / 4-digit PIN sends nothing, shows the contract text | give-sheet.component.ts, mar.model.ts:118,365 | unit 5 |
| G-10 | Search maxlength 100; announcement 400 ms after typing | admitted-panel.component.* | unit 2 (+5 adapted) |
| G-12 | Unreadable `seenThrough`, `clientKey`, `identityUnknown`, `version`, `seniorCalls`, `slotAt`, `checkToken`, `outcome` → contract text "Reload the page and try again." | ProblemExceptionHandler.java | unit 16 |
| G-13 | DB error log line cut before quoted values | ProblemExceptionHandler.primaryDbMessage | unit |
| G-14 | Skipped-order WARN once per (tenant, order) per 10 min | SkippedOrderWarnings.java (new), MedicineRoundReader.java | unit 5 + 9 |
| G-15 | Plain space before AM/PM in all formatters | shared/format/plain-space.ts + 6 formatters | unit 4 |
| G-16 | Malformed `{id}` 400 listed in 4 operations; 413 note in both contracts | docs/api/platform.yaml, ipd.yaml (YAML OK) | IT by QA |

Unit totals after fixes: backend 3,301 / 0 failures; frontend 2,109 / 0 failures; lint and build pass.

## Phase 3-4 · Browser tests (qa-engineer, 8 Nov 2026; detail in docs/test-plans/SAH-35-36-38-audit-e2e.md)
- New: `frontend/e2e/layout-matrix.spec.ts` (12 tests at 1920, 1366, 1024, 768, 375 px and 125 % zoom at 1920 and 1366 = 84 runs, 84 pass), `audit-sah35-36-38.spec.ts`, `audit-world.ts`.
- Run 1 found 12 failures. Fixed:
  - Admitted table "Open" button was 38 px high; it is now 44 px (`admitted-panel.component.scss`, angular-developer). 14/14 pass.
  - The `dialogReady` helper hit the Material 22.2.5 open-animation race at 1920 px (`sah31-world.ts`, the same fix in `safety.spec.ts` `openResponse`). This was a test-side fix.
  - The `mar.spec.ts` focus test was out of date after G-9. It is rewritten with no assertion removed.
- The 12 earlier failures then passed 3 of 3 times.
- The whole e2e folder ran once with 2,000 tests: 1,986 passed, 8 skipped by design and 6 failed.
  - 4 failures are SAH-39 `home-background.spec.ts` (1-7, 1-8). They are outside this audit and sent to the SAH-39 owner.
  - 2 failures are flaky and passed when re-run alone:
    - `nursing.spec.ts:1266`: the button measured 35.2 px against a 36 px minimum under load. It passed 3/3 alone.
    - `safety.spec.ts:1280`: the same dialog race, fixed. `safety.spec.ts` then passed 424/424.
- Contrast measured (G-5, human decision):
  - "Going home today" card flag: 3.60:1.
  - "Going home today" KPI label: 4.34:1.
  - Stale notice: 4.28:1.
  - "Admitted on" label and card identity line: 4.50:1, exactly at the limit.

## Phase 5 · Regression (8 Nov 2026)
- `node scripts/validate.mjs backend`: PASSED, exit 0, with no ClassNotFound (no IDE interference).
  - Unit: 3,301 tests, 0 failures, 0 errors.
  - Integration: 1,696 tests, 0 failures, 0 errors, 26 skipped. The skips are the dev sample admin preconditions, as before.
  - The 30 new integration tests are ApiAccessLogIT, ReleaseAuditHardeningIT, RequestBodyLimitIT and the MedicineRoundIT warning count.
- Frontend: lint passes, 151 files with 2,110 unit tests pass, and the production build passes. These runs were by the angular-developer after the last frontend change.
  - `node scripts/validate.mjs frontend` cannot run because of the `--browsers` flag in the script. Fixing it is a human item.
- E2e: see Phase 3-4 above.
- Code and security re-review of the fix pass: code APPROVE WITH COMMENTS (docs/reviews/SAH-35-36-38-audit-code.md); security PASS WITH CONDITIONS (docs/reviews/SAH-35-36-38-audit-security.md). Their fixes are listed below, in the section "Review fix pass".

### Test inventory
| Layer | Count | Result |
|---|---|---|
| Backend unit | 3,301 | pass |
| Backend integration (real `sahaj_test`) | 1,696 | pass (26 precondition skips) |
| Frontend unit | 2,110 | pass |
| E2e, whole folder | 2,000 | 1,986 pass, 8 skipped by design, 6 explained (2 flaky, re-run green; 4 SAH-39, since fixed) |
| Layout matrix (7 sizes) | 84 | pass |

## Risks
- **R-1 Performance (open, human/angular-developer).** At 1920×1080 the first frame of an opening dialog can take more than 150 ms. The cause is the `backdrop-filter` (frosted glass) layers behind it. The sheet opens and works, but on low-end ward PCs it may feel slow. Fewer blur layers would change the mockup look, so this needs a human decision.
- **R-2.** The e2e specs are gitignored, so new and changed specs need `git add -f` at commit time.
- **R-3.** `nursing.spec.ts:1266` is a timing-sensitive height check under 4 workers. It is flaky, not a defect.

## Deferred
| ID | Sev | Reason | Reproduction |
|---|---|---|---|
| D-1 | 4 | Fallback text "Choose a valid option" for an unreadable JSON value is not contract text for any field (e.g. `reasonKey` contract "Choose why the dose is not given.", `secondNurseId` "Choose a nurse from the list."). No real client can send it (only a wrong JSON type). Needs a per-field map or a generic contract wording (solution-architect). | POST dose with `"reasonKey": {}` → 400 fieldError `reasonKey` "Choose a valid option" |
| G-5 | 3 | Contrast of mockup colours ("Going home today" ~3.6:1, `--ink-3` small text ~4.4:1): human decision (mockup) | measured numbers in the audit e2e plan |
| G-11 | 4 | Give sheet closes on ESC/backdrop without confirm (as in the mockup): human decision | open Give, type, press ESC |

## Review fix pass (8 Nov 2026)
| Review item | Fix | Where | Proof |
|---|---|---|---|
| Sec #1 (Medium) | The admin chain now writes `sahaj.access` lines, with the admin id as userId, and caps request bodies at 4 KB | AdminSecurityConfig.java:75-78, AdminJwtAuthenticationFilter.java:66, RequestLogContextFilter.recordUser | AdminChainLogAndLimitIT (9, new; admin repositories mocked because the admin tables are a human prerequisite on sahaj_test) |
| Sec #3 / Code #4 | A request without a signed-in user is capped at 4 KB | RequestBodySizeLimitFilter.java:79-92 | RequestBodyLimitIT 12 (4,095/4,096 → 401; 4,097 → 413; chunked and declared) |
| Sec #4 / Code #1 | DB log line keeps table and constraint names and cuts values (quotes, `Detail`, `Key (`, text after a colon) | ProblemExceptionHandler.java:412, 456 | unit 20; ReleaseAuditHardeningIT 10 |
| Code #5 | `/**` is logged as `unmatched` | AccessLogFilter.java:34,64 | ApiAccessLogIT (tightened) |
| Code #6 | Test-only constructor removed | MedicineRoundReader.java:46 | unit |
| Code #2 | 400 for a malformed `{id}` is listed on every `{id}` operation (29 IPD, 7 platform); 413 is listed on every operation that takes a body (43); shared `BadRequest` and `PayloadTooLarge` responses | docs/api/ipd.yaml, platform.yaml (YAML OK) | — |
| Code #3 | Shell clock shows "11:05 AM"; the comma before the year is removed ("Sat, 24 Oct 2026") | shared/format/ist-clock.ts | unit (ist-clock.spec, new) |
| Code #7, #9 | Import and member order | 4 format/model files, admitted-panel, give-sheet | lint |
| Sec Info #9 | The PIN is cleared when the browser pre-check refuses because no nurse is chosen | give-sheet.component.ts ~263 | unit; mar.spec.ts 198/198 |
| Sec Low (SAH-39 review) | The local test server answers 400 for a malformed URL instead of crashing | frontend/e2e/serve.mjs | — |

Admin 4 KB cap check: no request body in admin.yaml can be larger than 4 KB today. The closest is PlanChange, at most about 3.1 KB when fully escaped. Any future admin free-text field must be checked against this cap.

### Final gate (8 Nov 2026)
- `node scripts/validate.mjs backend`: PASSED. Unit 3,311 / 0 failures; integration 1,708 / 0 failures / 26 precondition skips.
- Frontend:
  - Lint passes.
  - 2,117 unit tests pass (153 files).
  - The production build passes.
  - `validate frontend` is blocked by the `--browsers` flag in the script (human fix).
- E2e after the fixes:
  - mar, audit and layout-matrix specs: 332 run. Both failures were one `mar.spec` test that still expected the old PIN behaviour. QA rewrote it to the new behaviour, then `mar.spec` passed 198/198.
  - Layout matrix: 84/84.
  - The whole-folder run (1,986/2,000) took place before this small fix pass.

## Verdict: NOT READY (process), technically green
There are no open Sev-1 or Sev-2 findings, no failing tests, and no Critical or High security findings. It is NOT READY only because of these human items:
1. **G-8 approvals.** docs/approvals.md needs `stories` and `contract` lines for SAH-35, SAH-36 and SAH-38. The contract lines must cover the final `ipd.yaml` and `platform.yaml`. ADR-012, ADR-013 and ADR-014 are still PROPOSED.
2. **Source IP and retention.** Decide whether the `sahaj.access` line carries the source IP (Sec #2, CERT-In vs DPDP), and confirm 180-day retention.
3. **R-1 performance.** The first frame of a dialog takes more than 150 ms at 1920 px because of the frosted-glass layers. Keep the mockup look, or reduce the blur.
4. **G-5 contrast.** Three labels are below 4.5:1: 3.60, 4.34 and 4.28. These are mockup colours.
5. **G-11, M-1 to M-6, and D-1** (listed above).
6. **Follow-up.** The Emergency queue and the pharmacist store keep stale names after a 403 (Sec #5). This needs a separate story.
7. **Tooling.** The `scripts/validate.mjs` `--browsers` flag; the `ADM-2.md` file name.

## Proposed commits (the human commits; agents never do)
Run `git add -f frontend/e2e/...` for the e2e files, because that folder is gitignored. SAH-39 files (home, kinetic-background, `_tokens.scss` and `_primitives.scss` glass parts, home-background.spec, SAH-39 docs) go in their own commits. Suggested order:
1. `fix(validation): cap request bodies (64 KB signed-in, 4 KB public, anonymous and admin) with 413 problem+json`. Files: RequestBodySizeLimitFilter, SecurityConfig, AdminSecurityConfig, application.yml.
2. `fix(validation): reload-page texts for unreadable fields; DB log keeps identifiers, cuts values`. File: ProblemExceptionHandler.
3. `feat(logging): sahaj.access request line with tenant and user id (incl. admin chain); throttle skipped-order warning`. Files: AccessLogFilter, RequestLogContextFilter, AdminJwtAuthenticationFilter, SkippedOrderWarnings, MedicineRoundReader.
4. `fix(ui): IPD 403 reload clears data; focus next Give; high-alert pre-check clears PIN; search maxlength and announce delay; 44 px table button; AM/PM and date format`. Files: frontend/src/app/features/ipd/**, shared/format/**.
5. `style(frontend): formatting-only re-wraps`. Files: team.page.ts, emergency.model.ts, pin.model.ts, config-format.ts.
6. `docs(api): shared 400 bad-request and 413 payload-too-large responses; seenThrough bounds`. Files: docs/api/ipd.yaml, docs/api/platform.yaml.
7. `test(unit): release audit unit tests`. Backend and frontend specs.
8. `test(integration): ApiAccessLogIT, RequestBodyLimitIT, ReleaseAuditHardeningIT, AdminChainLogAndLimitIT, MedicineRoundIT`.
9. `test(e2e): layout matrix at 5 widths and 125% zoom; audit spec; dialogReady race; G-9 focus tests; serve.mjs 400`. Files: frontend/e2e/** and playwright.config.ts (the layout projects only; the 15 s expect timeout belongs to SAH-39).
10. `docs(qa): QA report, audit test plans and screenshots`.

Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
