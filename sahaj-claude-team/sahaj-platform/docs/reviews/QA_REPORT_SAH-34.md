# QA report SAH-34 (IPD ward scoping and break-glass emergency access)

Date: 10 Nov 2026 (QA resumed after a network drop). Branch: `feature/SAH-39-home-kinetic-background` (mixed tree). Story: `docs/stories/SAH-34.md` (77 acceptance criteria; built: 34B, 34C, 34E, 34F and the data part of 34A; NOT built: the 34A screen, 34D, 34G). Test plan: `docs/test-plans/SAH-34.md` (criterion -> test -> result, real query plans).

**Verdict: PASS.** All SAH-34 acceptance criteria that are built are covered by a passing test; no product defect was found; two low performance findings (F-1, F-2) and the open human items in section 7 remain. The 34A screen, 34D and 34G are not built and are not tested.

## 1. Validation matrix

| Check | Command | Result |
| --- | --- | --- |
| SAH-34 integration tests | `mvn verify -Dit.test=...` on `sahaj_test` | WardAssignmentIT 13/13, WardScopeEnforcementIT 15/15, EmergencyAccessIT 16/16, EmergencyAccessReviewIT 11/11, EmergencyAccessTableIT 9/9, ScopeRaceIT 6/6, ScopePlansIT 1/1, SafetyChangeTokenIT 9/9 (changed), ScopeEdgeCasesIT 11/11 (9 on its first run, 2 added afterwards, all inside the full gate) |
| Backend gate | `node scripts/validate.mjs backend` | **PASSED** (exit 0, 15:05 to 15:51 IST): unit 3,744 run, 0 failed; integration 1,900 run (140 classes), 0 failed, 0 errors, 26 skipped (the same 26 skipped as in the java-developer run; none in SAH-34 classes). `ScopeEdgeCasesIT` 11/11 and `ScopePlansIT` 1/1 inside the gate |
| Lint | `npm --prefix frontend run lint` | pass |
| FE unit tests | `npm --prefix frontend test -- --no-watch` | pass: 179 files, **2,761** tests |
| Build | `npm --prefix frontend run build` | pass |
| New e2e spec | `ward-scope.spec.ts`: 49 tests x 2 projects | 98/98; `--repeat-each=3` 294/294 (5.1 min) |
| Mutation checks (mock made wrong on purpose, 49 tests each) | `SAH34_MUTATE=...` | drop-scope 26 fail, wrong-n 7, hide-waiting 1, refuse-ok 2, unmask-bed 1: every mutation is caught |
| Whole e2e folder, once | `npx playwright test` (2,612 tests, 25 files, 1.2 h) | 2,558 passed, 45 skipped by design, 9 failed. `--last-failed`: 7 pass (load flakes: `ERR_NETWORK_IO_SUSPENDED`, timeouts under full-folder load); 2 = `packages.spec.ts` (SAH-29), see section 2 (test updated, passes) |
| `validate frontend` | `node scripts/validate.mjs frontend` | known tooling failure (not run again): the `--browsers=ChromeHeadless` flag needs `@vitest/browser-playwright`. Checked: `ng test --browsers=ChromeHeadless` fails with exactly that message; without the flag all 2,761 pass |
| `validate docs` | `node scripts/validate.mjs docs` | known failure on `ADM-2.md` (not run again, not touched) |

## 2. Defects and findings (QA does not change product code)

**No product defect was found by the SAH-34 tests.** Every red result in this session was a mistake in a test or its set-up and was fixed in the test, not weakened (the list is in section 3).

| Id | Severity | Where | Finding | Owner | State |
| --- | --- | --- | --- | --- | --- |
| F-1 | Low (performance, grows with use) | `JdbcWardScopeRepository.LIVE_GRANTS` and `HAS_LIVE_GRANT` (S2 plan) | Liveness is `g.expires_at > clock_timestamp()`, a volatile function, so the planner uses only `tenant_id, membership_id` as the index condition and reads **every grant the member ever opened** on every scoped request (nurse with 5 grants: 0.22 ms; nurse with 3,650 old grants: 3,650 index entries, 92 heap blocks, 2.3 ms). Suggested fix: add the implied stable bound `and g.expires_at > now() - interval '1 minute'` to both statements (a row live on `clock_timestamp()` also satisfies `expires_at > now()`), so the descending index range stops at the live entries; then re-run `ScopePlansIT` (S2b) | java-developer (statement), db-engineer (plan) | open |
| F-2 | Low (performance, data dependent) | `GET /clinical-events`, R4 / R4b | A scoped nurse with no ward and no grant reads most of the log to find her own entries (951 events read, 142,308 join-filter removals, 28 to 34 ms on 36,000 events; a nurse with two wards and a grant: 639 read, 13 ms; the owner: 3 ms). The design note already names the revisit trigger and the cure (`(tenant_id, actor, at desc, seq desc)` index, own `create index concurrently` migration) | db-engineer | open, decide before a hospital reaches a million events |
| F-3 | Info | `docs/database/SAH-34.md` section 10, `docs/architecture/SAH-34-...md` 1.5 | Expected plans do not match what runs: the narrow admitted-set read and the `admitted` count use `ux_admission_tenant_bed_admitted` (not `ix_admission_tenant_status_admitted_at`); R1 to R3 and C2 do not apply; the R4 statement also left-joins the grant table (by primary key). Real plans: test plan section 4 | db-engineer (they own section 10) | open |
| F-4 | Info | SAH-29 e2e `packages.spec.ts` "403 module-off ... Try again recovers" | It failed because the angular-developer removed the Try again button on module-off on purpose (SAH-29 code review findings 6-8: "a Try again button that cannot help"). QA updated that one test to the reviewed behaviour (fixed text, no Try again button); passes in both projects. Not a product defect | qa-engineer | closed |
| F-5 | Info | stories AC 41 and 43 | "Before the first answer the chip is absent", "a failed first load" and "sign out then another user sees nothing of the previous user" are covered by the frontend unit tests (`admitted-panel.scope.spec.ts`, `ipd.page.spec.ts`) and the SAH-36 browser tests, not repeated in the new spec | angular-developer | closed |
| F-6 | Info | `ScopeGuard.effectiveRole` | A token that says NURSE with a membership that now says DOCTOR is treated as NURSE (narrowed); a DOCTOR token with a DOCTOR membership is not. Fail-closed, and tested (`ScopeEdgeCasesIT.aMemberWhoseRoleChanged...`). `mine` is read from the membership role, so a FRONT_DESK token on an OWNER membership gets `mine`; cannot happen with real sign-in | none | closed |

Observations that are not defects: a `TRUNCATE` of `ipd.emergency_access_grant` or `ipd.ward_assignment` by the table owner is refused with `0A000` (another table's foreign key refers to it) before the `deny_audit_mutation` trigger can answer `42501`; both are refusals and the test accepts either. A scoped nurse's board shows "All within target" under the scope note when she has no escalation (AC 37 only requires the note above it).

## 3. Test-side mistakes found and fixed this session (none was a product defect)

Patient needed an allergy for the order endpoint; a nurse may not take deposits; the bed status key is `occ`; FRONT_DESK needs a real member in the role test; problem bodies have no `instance`; the aged-grant helper read the clock twice (the `expires_at = opened_at + 30 minutes` check refused it); a count was not filtered by tenant; a bed was created after the "nothing written" snapshot; the bed limit of a clinic is 300; e2e: two elements carry the empty text (the visible one and the sr-only announcement), an ambiguous "Patient" label, the query-parameter order, "All within target" is allowed under the scope note, the mock pharmacist queue adds 1 to the owner tab number, Playwright refuses to click an `aria-disabled` tile without `force`, a mock delay of 600 ms was too short for a double-tap race.

## 4. Test inventory (new or changed by QA in this task)

Backend (`backend/src/test/java/com/sahaj/platform/it/`): `ScopeSupport` (shared), `WardAssignmentIT` 13, `WardScopeEnforcementIT` 15, `EmergencyAccessIT` 16, `EmergencyAccessReviewIT` 11, `EmergencyAccessTableIT` 9, `ScopeRaceIT` 6, `ScopeEdgeCasesIT` 11, `ScopePlansIT` 1 (seeds 26 wards, 300 admitted, 36,000 events, 3,655 grants; writes `backend/target/sah34-explain.txt`), `SafetyChangeTokenIT` changed (OWNER 7 keys, DOCTOR and NURSE 6): 82 new IT methods. The java-developer's unit tests (about 113 new, 34 extended, including `IpdScopeCoverageTest`) are part of the gate and not rewritten.

Browser (`frontend/e2e/`, API mocked): `sah34-world.ts` (mock world with `scope`, `mine`, `emergencyAccess`, the picker, grant, review list and review endpoints, an `outsideScope` bed tile, 403 `outside-scope` on reads and writes, five mutation switches), `ward-scope.spec.ts` 49 tests: chip and list 12, sheet 12, Safety scope notes 5, owner tile and panel 10 (incl. the tab number), bed board 1, `outside-scope` on sheets 3, layouts 6 (375, 620, 1440, owner at 375, keyboard path, dark theme). Changed: `packages.spec.ts` (one test, F-4).

Docs: `docs/test-plans/SAH-34.md`, this report. Not touched: `docs/database/SAH-34.md` (db-engineer's).

## 5. Required coverage, checked against the brief

Tenant isolation of assignments and grants (WA, EAT, EAR, EA, WSE); a scoped nurse with no ward sees nothing and gets 403 `outside-scope` (WSE); switch off = unchanged for NURSE (WSE, SE, the whole existing IPD suite in the gate); OWNER / DOCTOR boards unchanged with an escalation in an unassigned ward (WSE); raw JSON of a refused answer holds no name, UHID, bed or ward (WSE, `type,title,status,detail` only); every guarded read and write refused with nothing written (WSE, 17 tables snapshot); break-glass open (one grant, `bg` event with `grant_id`, record-access row, audit `IPD_EMERGENCY_ACCESS_OPENED`, 30 minutes from the database clock), 409 already-in-scope, non-nurse roles 403 with the fixed text, picker fields, owner keyset list of 25, owner review once, second review 409, grantee 403, two owners at once one 200 and one 409, two taps one row, real expiry then 403 and list drop, trigger refusals (update of `expires_at`, second review, self review, delete), transfer racing a vitals save, expiry racing a dose, `malformed-request` for an unknown field on the three bodies without echoing it, repeated `on: true` while a nurse has no ward is 409 and changes nothing, ward assignments PUT whole set / ended nurses / audit ids only, `outsideScope` JSON presence and absence, `SafetyBoard.scope` absent for OWNER and DOCTOR, `AdmissionList.scope` for every role, owner tab number includes the waiting grants (WSE, EA, EAR, WA, SR, EAT, SE; E for the tab number), logs hold no names (SE).

**Not covered, and why:** a grant opened and then the grantee's role changed to VISITING_DOCTOR (34D not built); the 34A screen; the 34D / 34G stories; a save refused after a **transfer by the user herself** in the browser (the Nursing vitals save is covered in the browser; other sheets' saves by the frontend unit tests `ipd-scope-sheets.spec.ts`); hard-timing of the 60-second poll against a real clock (a fake clock is used); real expiry in the browser (the browser tests prove what the screen does with a 403, the integration tests prove the 30 minutes).

## 6. Risks

1. **Clinical (OQ-34-26, OQ-34-23, OQ-34-15):** a covering nurse who loses a patient from her list can miss a deteriorating patient. Built mitigations are tested: the scope notes on the list and the board, OWNER and DOCTOR never narrowed, the other-wards past-target number, Emergency access. The switch is off for every clinic and no screen can turn it on yet. The clinical sign-off owner must read ADR-016 section 8 before any clinic switches the scope on.
2. **Typed values after a refused save are not kept** (OQ-34-15 default): a nurse who typed vitals at minute 30 loses them; tested as "Nothing was saved."
3. **Performance at scale:** F-1 and F-2 above. The in-memory list filter is bounded by the admitted set (300 rows read in 1.2 ms); the revisit trigger is recorded in the design note.
4. **The only gate for scoping is the switch** (off by default); until the 34A screen exists nobody can turn it on except through the API, so the break-glass path has no real user yet.
5. **Review is not automatic:** an owner who never reviews gets only the amber tile and the tab number (OQ-34-31).

## 7. For the human

- The 34A screen (not in the mockup); 34D role answers (OQ-31-8, OQ-33-1(b)); 34G stays out of scope.
- OQ-34-15 (typed values after a refused save), OQ-34-26 (the red notice for other wards, removable) and the other safety deviations; OQ-34-31 (go-live conditions, privacy register entries, retention of grants).
- Wording sign-off of the new texts (DEC-031 addendum FE-2 and OQ-34-27): "We could not load the patients. Try again.", "Loading the patients", "We could not mark that as reviewed. Try again.", "We could not load more. Try again.", "Already reviewed." as a toast, the tooltip ending "The owner sets your wards in Clinic setup.".
- Clinical sign-off (OQ-30-6) before any clinic switches the scope on.
- ADR number clash: `ADR-016-ipd-ward-scoping-and-break-glass.md` and `ADR-016-admin-directory-and-modules.md`; the design record uses ADR-017.
- `docs/approvals.md` has no `SAH-34` lines (`stories`, `contract`, `merge`); the human adds them.
- The tracked screenshots under `docs/test-plans/*-shots` change on every whole-folder e2e run (they changed again; not restored): decide whether they stay tracked.

## 8. Proposed commits (not made)

1. Backend tests: `git add backend/src/test/java/com/sahaj/platform/it/{ScopeSupport,WardAssignmentIT,WardScopeEnforcementIT,EmergencyAccessIT,EmergencyAccessReviewIT,EmergencyAccessTableIT,ScopeRaceIT,ScopeEdgeCasesIT,ScopePlansIT,SafetyChangeTokenIT}.java`
2. Browser tests (the folder is ignored, so force): `git add -f frontend/e2e/sah34-world.ts frontend/e2e/ward-scope.spec.ts frontend/e2e/packages.spec.ts`
3. Docs: `git add docs/test-plans/SAH-34.md docs/reviews/QA_REPORT_SAH-34.md`
Do not add the regenerated screenshots (`docs/test-plans/*-shots/*`) or `frontend/e2e/serve.mjs` (modified by someone else).

## 9. Handoff

Next agents: code-reviewer and security-reviewer. Both should read `docs/test-plans/SAH-34.md` section 4 (plans) and F-1, F-2.

## 10. Follow-up after the review fix pass (qa-engineer, 10 to 11 Nov 2026)

Scope: the QA queue from the security review, the code review, the architect's section 9 "Added 10 Nov 2026" and the java-developer's third handoff. Plan and results: `docs/test-plans/SAH-34.md` section 6 (interim notes: `docs/test-plans/SAH-34-followup.md`). QA changed only tests; **no product defect was found** and no main code was edited.

| Check | Result |
| --- | --- |
| Targeted integration tests | `EmergencyAccessIT` 20/20 (was 16; 4 new, 1 updated), `ScopeReviewFixIT` 7/7 (new), `SafetyChangeTokenIT` 9/9, `ScopeRaceIT` 6/6, `ScopePlansIT` 1/1 (rewritten), `SetupAndShellIT` 29/29, `ClinicMastersIT` 39/39, `SeniorCallIT` 10/10, `PackageTableIT` 15/15, `LifecycleEdgeCasesIT` 10/10, `WalkInTokenJourneyIT` 1/1 |
| Browser | `ward-scope.spec.ts` (51 tests with the two new focus tests) and `packages.spec.ts` (35 tests): 172 of 172 pass over `phone-360` and `desktop-1440` (2.5 min) |
| `node scripts/validate.mjs backend` | **PASSED** (exit 0, 00:42 to 01:27 IST on 11 Nov): unit 3,763 run (243 classes), 0 failed; integration 1,938 run (142 classes), 0 failed, 0 errors, 26 skipped (the same pre-existing skips). An earlier run of the same gate (23:53 to 00:40) failed on one test, `SetupAndShellIT.slugAvailabilityReportsFreeTakenReservedAndInvalid`, caused by the first 24-character version of `UniqueSlugs` (see below); fixed and re-run |

### Coverage table (this follow-up)

| Item | Test | Result |
| --- | --- | --- |
| Picker audit: exactly one `IPD_EMERGENCY_ACCESS_PICKER_VIEWED` row with `{"count": N}`, no ids/names/bed, no record-access row, no clinical event (AC 78) | `EmergencyAccessIT.thePickerListsTheAdmittedPatients...WritesNothing` (updated) | pass |
| Count 0 writes a row; two reads two rows; Open writes only its own action | `EmergencyAccessIT.anEmptyPickerStillWritesOneAuditRow...` | pass |
| 401 and 403 write nothing | `EmergencyAccessIT.aRefusedOrUnauthenticatedPickerRead...` | pass |
| Audit write fails: 500, no list, nothing kept (the harness can force it: a trigger on `platform.audit_event` for the test's tenant and action, dropped in `finally`) | `EmergencyAccessIT.whenTheAuditWriteFailsThePickerAnswers500...` | pass |
| IPD Off: role first on the picker and Open; read-only still answers | `EmergencyAccessIT.aReadOnlyIpdStillAnswers...` | pass |
| Owner token `bg:<count>:<newest id>`, none at 0, DOCTOR and NURSE unchanged (AC 79) | `ScopeReviewFixIT` (4 tests) and `SafetyChangeTokenIT` | pass |
| Identical rows for a scoped nurse with no ward and no grant, paging included (AC 81, F-2 part 1) | `ScopeReviewFixIT.aScopedNurseWithNoWardAndNoGrant...` | pass |
| Guard before the state check on a discharged admission's order and escalation (AC 14, finding 3) | `ScopeReviewFixIT.forAnOrderOrAnEscalationOfADischargedAdmission...` | pass |
| Discharged reads by the last ward's nurses, never through a grant (AC 80) | `ScopeReviewFixIT.aDischargedAdmissionIsReadableOnlyBy...` | pass |
| S2b after F-1 (live-range scan), R4 with the new text, R4d, work-count budgets, evidence output (finding 6) | `ScopePlansIT` | pass |
| Race margin 30 s and a less brittle lock-waiter match (finding 5) | `ScopeRaceIT` | pass |
| SAH-29 module-off text without Try again; 500 and offline keep the old text | `packages.spec.ts` | pass |
| Focus lands on `#ipd-panel` after an outside-scope refusal (finding 9) | `ward-scope.spec.ts` (2 new tests) | pass |

### Numbers (`ScopePlansIT`, 300 admitted, 36,010 events, 90,000 doses, 3,680 grants)

- **F-1 fixed:** S2b reads 0 rows (was 3,650 index entries, 2.3 ms), 0.12 ms.
- **The 170 ms is gone:** the real scoped-nurse statement R4 takes 10.5 ms and reads 2,393 rows; the same statement with the old `= any(cast(:wards as uuid[]))` text (R4x) takes 231 ms and reads 98,381. The request `GET /clinical-events?limit=50` as a scoped nurse is **15 ms** best of 5 in MockMvc (was 200 ms; the java-developer expected about 10 ms or less, so a little above that; the ceiling asserted is 150 ms). Owner 7 ms.
- **R4d** (no ward, no grant, 10 own entries, `ACTOR_ONLY`): 9.0 ms, a bitmap scan of the whole log (36,012 entries read, 36,002 filtered). Far below the 1 s the db-engineer calculated; about 250 ms at a million events by proportion (not measured). The go-live trigger in design 10.4 (R4d over 100 ms) is reached at about 400,000 events per clinic.
- **`JdbcMedicineRoundRepository.DOSES_BETWEEN`** (same cast pattern, 90,000 doses, 300 admissions, one day): 3.9 ms, 2,400 rows read, a `Bitmap Index Scan` where the cast is an index condition (evaluated once, not per row); the 12-admission scoped case 0.31 ms, and the `in (select unnest(...))` form is the same (0.33 ms). **No change needed** there; the per-row cast problem only bites where the array is a filter on a table read in time order (the clinical-events page).

### Slug flake (item 5)

- **Cause (probable, not proven):** `OpdJourneySupport.completeWizard` and `WalkInTokenJourneyIT` built the web address as `sahaj-` plus 8 hex characters of a random UUID (32 bits). `sahaj_test` is never cleaned and holds every clinic of every run, so each new clinic has a small chance (tens of thousands of old rows over 4.3 billion) of meeting an old slug; a gate run makes 1,500 or more clinics, so a collision in some gate is a matter of time (SeniorCallIT and PackageTableIT hit it once each). I could not count the stored slugs (I do not read `.env`), so the arithmetic is an estimate.
- **Fix:** `UniqueSlugs` (new): `sahaj-` + 8 characters of the millisecond clock (base 36) + 2 of a per-JVM counter + 6 random hex = 22 characters: unique inside one JVM by construction, and 24 random bits across JVMs. The first version used 24 characters and the gate failed `SetupAndShellIT.slugAvailabilityReportsFreeTakenReservedAndInvalid` (a taken 24-character address leaves no room for the "-2" alternatives); the 22-character form fixed it.

### Open items and notes for the next agents

- **Plan-test data growth:** each `ScopePlansIT` run commits about 36,000 events, 90,000 doses and 3,680 grants for a new clinic in `sahaj_test` (4 full seeds this session, about 520,000 rows). Not cleaned, as instructed; the tech lead or the human decides if `sahaj_test` should be recreated.
- **Not tested:** the 34A screen, 34D (grantee whose role changes), real expiry in a browser. The SAH-42 dev-tools cases and the full dev-tools profile run belong to the other QA agent and were not touched.
- The browser focus tests pass against the current `dist`; I did not mutate `ipd.page.ts` (main code) to prove they would fail without the `afterNextRender` repair.
