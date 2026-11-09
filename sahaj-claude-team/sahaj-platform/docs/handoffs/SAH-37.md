# SAH-37 · IPD patient card setup — handoffs

Mockup: Clinic setup → Patient card (screen-map row 29); used on IPD → Admitted (SAH-36).
Branch: the current working branch. The tree holds uncommitted audit, SAH-39, SAH-40 and SAH-41 work, so no new branch could be created; the human splits the commits.
Go-ahead: human in chat, 9 Nov 2026: "U continue the further storys from my side approved and u can start". The tech lead records this as a go-ahead, not as `stories`/`contract` lines in docs/approvals.md.
Out of scope: Roles and access / ward scoping (OQ-33-2), which changes access control and needs a human decision; break-glass (SAH-34).

## business-analyst (9 Nov 2026, running)
- **Done (9 Nov 2026).**
  - Wrote docs/requirements/SAH-37.md (21 rules) and docs/stories/SAH-37.md: 6 stories and 66 acceptance criteria. The stories cover the setup status bar, draft, publish and versions; Layout and sections; Fields with locked safety fields; Quick actions (Should); the live preview; and Admitted cards that follow the published card, with a "Customize card" button for the owner.
  - Out of scope: Roles and access and ward scoping, break-glass, stages, Assigned nurse, Case paper, Last nursing note, restoring old versions.
  - Safety questions OQ-37-1, -2, -5 and -11 go to DEC-027. OQ-37-3, -4 and -6 to -10 proceed on the recommendations.
  - Requests for the architect: the stored shape (live setup, versions, one draft) with RLS; the server checks locked fields; GET /admissions gets the extra card facts, role-gated and minimised; the cost of the 60-second poll; whether the admission has a diagnosis; one shared card component.
- **Validation (tech lead):** `validate docs` fails only on the known ADM-2.md file name.
- **DEC-027 decided (human, 9 Nov 2026):** the default card is the SAH-36 card plus the locked Allergies line, and the owner can add vitals and diagnosis. The other safety points take the safer defaults.

## solution-architect (9 Nov 2026, running)
- **Done (9 Nov 2026).**
  - New files: ADR-015 (docs/architecture/adr/ADR-015-ipd-patient-card-setup.md) and docs/architecture/SAH-37-patient-card-setup.md.
  - ipd.yaml, additive only:
    - GET /card-setup;
    - PUT and DELETE /card-setup/draft;
    - POST /card-setup/publish;
    - GET /admissions gains cardSetup, card.deposit, card.visibleGroups, clinical.diagnosis, clinical.latestVitals and mobile, plus `cardFacts=all` for OWNER only.
  - Stored shape: ipd.card_setup_version (append-only, starting at v2) and ipd.card_setup_draft (one per clinic, with a revision counter), both with FORCE RLS. No destructive change.
  - Diagnosis comes from `ipd.admission.reason`. Tile marks use the EarlyWarningScore points.
  - Open points O1–O7 go to DEC-028, where the defaults were applied.
- **Validation (tech lead):** ipd.yaml parses with SnakeYAML (52 paths). `validate docs` fails only on ADM-2.md.
- **Approval:** covered by the human's chat go-ahead for further stories (DEC-006). The `SAH-37 | stories` and `contract` lines in docs/approvals.md are still needed from the human.

## db-engineer (9 Nov 2026, running)
- **Done (9 Nov 2026).**
  - New migration V2026_12_04_1000__ipd_card_setup.sql. It creates ipd.card_setup_version (append-only; FORCE RLS; column-level insert grants; triggers refuse update, delete and truncate) and ipd.card_setup_draft (tenant pk; FORCE RLS; revision counter).
  - New docs/database/SAH-37.md. No destructive change.
  - `validate db` PASSED (agent run).
  - EXPLAIN plans were not run, because there is no SQL path. Expected plans are recorded; QA or the developer pastes the real output.
  - **Notes for the java-developer:**
    - 23505 on the version unique key or the draft pkey maps to 409 `card-setup-changed`.
    - A 23514 or 42501 is a code bug and stays 500.
    - Compare the canonical JSON form, not the stored text.
    - The revision restarts at 1, so also compare base_version.

## tech lead
- `validate db` re-run by the tech lead (9 Nov 2026), holding the backend lock: PASSED, exit 0. Lock released.

## java-developer ∥ angular-developer (9 Nov 2026, running)
- **angular-developer done (9 Nov 2026).**
  - **Setup page:** /app/config/ipcard has the status bar, draft, discard, reset and publish, the three tabs, the live preview, the versions list, the leave warning and the 409 messages. It is read-only for non-owners and when IPD is Read-only.
  - **Shared card:** PatientCardComponent, with ResolvedCardSetup as input, draws Compact, Standard and Expanded. The Admitted page and the preview both use it.
  - **Admitted page:** follows the published setup and has a "Customize card" button for the owner only.
  - **Deposit:** opens the admission sheet at the deposit part.
  - **SAH-40:** a 403 clears the data on both pages.
  - **Results:** lint passes, 2,407 unit tests pass (about 165 new) and the build passes.
  - **Differences from the mockup:** only those in DEC-027 and DEC-028.
  - **Readings for the tech lead:** 7 points. Added to DEC-028 as defaults: Early warning shown on Compact, "Last recorded: Not recorded", the preview frame width, the example patient only for non-owners, the fallback card, display-only role labels, and the fixed load-error text.
- **java-developer done (9 Nov 2026).**
  - **Endpoints:** GET /card-setup; PUT and DELETE /card-setup/draft; POST /card-setup/publish; the GET /admissions additions and `cardFacts=all` for OWNER only. CardAccessRule now holds the SAH-36 abilities unchanged.
  - **Validation:** V1–V14 run on save and publish; repair on read happens in memory only.
  - **Audit:** IPD_CARD_DRAFT_SAVED and IPD_CARD_PUBLISHED, with no labels or patient data.
  - **Tests:** 118 new unit tests. Unit total 3,461 run, 0 failed.
  - **`validate backend` FAILED.** 1,708 ITs ran, 3 failed. All 3 are existing AdmittedCardsIT assertions (lines 74, 117 and 167) that expect exact keys or no "clinical" anywhere in the JSON. The new contract keys break them. QA fixes these by scoping the checks to `items`.
  - **Open points:**
    - The revision restarts at 1. A stale editor could overwrite a recreated draft that has the same base_version (Low; as the contract specifies).
    - The contract's 403 text "Only the clinic owner can change the patient card." is replaced by the shared forbidden text.

## qa-engineer (9 Nov 2026), run 1 cut off by a network drop
- **Left behind:**
  - New ITs: CardFactsIT, CardSetupIT, CardSetupPlansIT, CardSetupStoredIT and CardSetupSupport. AdmittedCardsIT was updated.
  - Failsafe at 12:08: 48 tests, 2 failures, both in CardFactsIT (facts per role at line 197; the owner's mobile was "" at line 217).
  - frontend/e2e/patient-card-setup.spec.ts is written, but it is not known whether it was run. No test plan yet. No locks were left behind.
- Human in chat, 9 Nov 2026: "approved and go ahead for next steps". Recorded as a go-ahead.

## qa-engineer, resume (9 Nov 2026, running)
- **Cut off again (auth expiry, about 17:40).** It wrote docs/test-plans/SAH-37.md (16:55) and started a Maven verify (about 17:38) that is still running and holds the backend lock.

## qa-engineer, resume 2 (9 Nov 2026, running)
- It waits for the orphaned Maven run, takes over its lock, then finishes the CardFactsIT analysis, the e2e runs, the test plan and the gate.

## security-reviewer (9 Nov 2026, run while QA was finishing): PASS WITH CONDITIONS
- No Critical or High findings. Report: docs/reviews/SAH-37-security.md.
- **Go-live condition:** add Diagnosis, vitals and Mobile to the privacy register (finding 1a).
- **Fixes for the java-developer, after QA:**
  - the publish audit row records which sensitive facts are on (1b);
  - the draft revision never repeats (2);
  - no audit row for an unchanged draft (3);
  - a separate port method for mobiles (4). This also settles QA's failing mobile test;
  - `toString` without labels (8).
- Backlog: fictional names in the e2e data (5); the Table view data (6).
- The code review is running.

## code-reviewer (9 Nov 2026, run while QA was finishing): APPROVE WITH COMMENTS
- No Blocker or Major finding. Report: docs/reviews/SAH-37-code.md.
- **Fix pass, after QA finishes:**
  - java-developer: #1 (with security 2), #2 (dead constants), #4, #5, #7, #9, and from the security review 1b, 3, 4 and 8.
  - angular-developer: #3, #6, #10, #11.
  - solution-architect: #2 (the contract wording for the 403).
  - qa-engineer: flip the revision IT (#13).
- The fix pass waits for QA, because edits to the product code during a Maven or Playwright run would give inconsistent results.

## qa-engineer, resume 2 (9 Nov 2026): QA FAIL on 2 defects, everything else passes
- **Results:**
  - Backend integration: 1,755 of 1,756 pass. The failure is D1. Six more results in the first run came from the human's `mvn spring-boot:run` dev server rebuilding `backend/target` under the test run. A re-run of those classes under the lock passed.
  - New browser spec (patient-card-setup): 121 passed, 3 skipped by design and 2 failed (D2), stable over 3 runs.
  - Existing IPD browser specs: 1,104 passed, 0 failed.
  - Frontend: 2,411 unit tests pass, and lint and the build pass.
  - `validate frontend` and `validate docs` still fail on the known script flag and on the ADM-2.md name.
- **Real query plans** are in docs/test-plans/SAH-37.md.
  - Reading the latest readings of 300 patients takes 3.1 ms. Reading all rows takes 4.4 ms. Neither uses a sequential scan.
  - The full `GET /admissions` with 300 rows and every fact on takes 129 ms and is 305 KB.
- **Defects:**
  - D1 (Medium, java-developer): the owner's and front desk's mobile is always empty (PatientLookup.java:51). The test is red.
  - D2 (Low, angular-developer): moving a section alone shows "2 changes" and then "1 change" (card-setup.logic.ts:64-67). The test is red.
  - D3 (Low): the revision restart. The IT passes while the hole exists, and QA flips it after the fix.
  - Possible flake: admitted.model.spec / admitted.fixtures.
- **Process:** a `spring-boot:run` that rebuilds backend/target during `validate backend` corrupts the run.
- **Files for `git add -f`:**
  - the Card*IT files, CardSetupSupport and AdmittedCardsIT in backend/src/test/java/com/sahaj/platform/it/;
  - frontend/e2e/patient-card-setup.spec.ts, sah37-world.ts, sah36-list.ts and sah31-world.ts;
  - docs/test-plans/SAH-37.md.

## Fix pass (9 Nov 2026, running)
- **java-developer:**
  - D1 (a separate mobile lookup);
  - the revision token (D3, code #1, security #2);
  - security #1b, #3 and #8;
  - code #2, #4, #5, #7 and #9.
- **angular-developer:**
  - D2;
  - code #3, #6, #10 and #11;
  - the flaky test.
- **solution-architect:** the wording of the contract's 403 text and of the draft revision, plus the ADR-013 D15 note.
- **After the fix pass:**
  - QA re-runs CardFactsIT and CardSetupStoredIT (and flips the revision test), the new spec and the gate, and adds the width e2e from the width bug.
  - The two reviewers re-check.
- **solution-architect, contract wording fix (9 Nov 2026) done.**
  - ipd.yaml (parsed by the tech lead, OK):
    - the 403 text is the shared "You do not have access to this." on all card-setup operations and on `cardFacts`;
    - the draft revision is an opaque token that never repeats for a clinic (type unchanged, int64).
  - ADR-015 has an amendment section: A1 the 403 text, A2 the revision token, A3 the mobile lookup (a new method on `PatientLookupPort`; `findAllCurrent` stays free of mobiles).
  - ADR-013 got an "Amended by" line.
  - **For the human:** since the contract wording changed, you may want to re-approve `contract` (SAH-37).
  - **For QA:** flip the revision test to a 409, and any test that expects the old owner-only 403 text must expect the shared text.
- **angular-developer, fix pass done (9 Nov 2026).**
  - **D2:** the fields comparison no longer depends on the section order. Moving a section alone is now "1 change" before and after Save draft. The browser proof has not been run yet (see below).
  - **Code #3:** Save draft and Discard draft are offered when there are unsaved edits or a server draft. Save is disabled when the editor equals the saved draft.
  - **Code #6:** the Overview asks for the card setup only for FRONT_DESK, NURSE, DOCTOR and OWNER.
  - **Code #10:** a spec compares the browser's copies of the safety fields and the standard card with the catalogue.
  - **Code #11:** the preview card is `inert`.
  - **The flaky test, root cause:** the AC51 spec rewrote the shared `ROW` fixture, which is an order-dependent mutation, not the clock.
    - The fixture is now deep-frozen, and every call gets a fresh copy.
    - No assertion was weakened, and a guard spec was added.
  - **Results:** lint passes, 2,437 unit tests pass (26 new) over two runs, and the build passes.
  - **For QA:**
    - run the D2 witness spec "AC3 WITNESS: moving a section alone is one change" once no Maven build is active;
    - re-check the Save draft and Discard steps of the existing specs, because Save draft is now disabled when there is nothing to save.

## Re-check lane (9 Nov 2026, 19:50, human: "lets start")
- **The backend fix pass is still running.** The java-developer holds the backend lock (since 19:11), and its full `validate backend` is mid-run in the ITs. Its report has not arrived.
- **Running now, in parallel:**
  - qa-engineer: writes the width e2e and flips the revision test first. It waits for the backend lock. Then it re-runs the card ITs, the Playwright specs and the gate.
  - code-reviewer and security-reviewer: re-check by reading only. They may see mid-change code, and will say so.
  - Two db-engineers, SAH-29 and SAH-34: write the migrations (V2026_12_05_1000 and V2026_12_06_1000), and wait for the lock before `validate db`.
- **security-reviewer re-check (9 Nov 2026): PASS WITH CONDITIONS.**
  - Findings 1b, 2, 3, 4 and 8 are closed. Nothing is Critical, High or Medium.
  - Open: R1 (QA updates the revision ITs, in progress) and R2 (angular-developer: the 403 mocks in ipcard.page.spec.ts use the old texts; a nit).
  - Appended to docs/reviews/SAH-37-security.md.
- **code-reviewer re-check (9 Nov 2026): APPROVE WITH COMMENTS.**
  - Findings #1 to #7, #9, #10 and #11 are closed.
  - The width fix is correct and follows the mockup.
  - Nits to fix: the 403 mock texts in ipcard.page.spec.ts (#14, with security R2), and one contract sentence about an unchanged save keeping its revision (#15).
  - Appended to docs/reviews/SAH-37-code.md.

## java-developer, fix pass done (9 Nov 2026)
- **Gate:** `validate backend` exit 1.
  - Unit tests: 3,495 run, 0 failed (34 net new).
  - Integration tests: 1,756 run, 14 failed. All 14 are QA-owned revision ITs: 10 in CardSetupIT and 4 in CardSetupStoredIT. They hardcode revision 1, 2, 3.
  - **CardFactsIT 11/0: D1 is closed.** The nurse and doctor assertions are green.
  - No rebuild corruption this time. The backend lock is released.
- **Closed:**
  - D1 and security #4 (`PatientLookupPort.mobilesOf`).
  - The revision restart (a clock-based opaque token; the dead baseVersion check removed).
  - Security #1b (`factsOn`), #3 and #8.
  - Code #2, #4, #5, #7 and #9.
- **Decision (DEC-028 addendum): option A.** Clock-based revision with no schema change; the human can reverse it. QA changes the 14 tests to read the revision from the previous answer, and flips the known-gap test to a 409.
- **For the architect and db-engineer (docs):**
  - ADR-015 D5 and D6 and docs/database/SAH-37.md section 7 still say the revision "restarts at 1".
  - The contract needs one sentence: a save equal to the stored draft keeps the revision.
- **For the angular-developer:** the revision arrives as a value around 1.79e15, below 2^53. Confirm that the Angular side keeps it as a JS number without rounding, and fix the old owner-only 403 texts in ipcard.page.spec.ts (security R2, code #14).
- **db-engineer, doc fix (9 Nov 2026) done.** docs/database/SAH-37.md section 7 now describes the opaque clock-based revision, and section 8 has the measured plans (no sequential scan; R4 3.1 ms and R1 4.4 ms with 300 admitted patients). No migration.
- **solution-architect, doc nits (9 Nov 2026) done.** ipd.yaml parsed by the tech lead: OK. `validate docs` fails only on ADM-2.md. The revision is an opaque clock-based token, and a save equal to the stored draft keeps it. `PatientLookupPort.mobilesOf` and `factsOn` are in the ADR, the design note and the contract. The old "restarts at 1" text in this handoff (the QA entry and D3) is superseded by DEC-028 addendum option A.
- **angular-developer, small fix (9 Nov 2026) done.**
  - The 403 texts in ipcard.page.spec.ts now use the shared text (security R2, code #14).
  - The revision is checked: nothing rounds or computes it. Two new specs use 1790000000000001 through parse, the Save draft body and the Discard URL.
  - Lint passes, 2,439 unit tests pass and the build passes.

## qa-engineer, re-run (9 Nov 2026): SAH-37 PASS; the width fix PASS
- **Backend gate:** `validate backend` exit 0: unit 3,495 and integration 1,758, 0 failed, 26 existing precondition skips.
- **The fix pass is verified:**
  - D1, D2 and D3 are closed and re-tested.
  - The shared 403 text, `factsOn` in the publish audit row, and an unchanged draft writing nothing are covered by tests.
  - The revision tests read the token from the previous answer. The known-gap test is flipped: a stale editor gets 409 on Save draft and Publish, and the token never repeats.
- **Browser:**
  - The new spec patient-card-setup ran 3 times: 123 passed and 3 skipped by design, 0 failed.
  - The section-move witness passed 2 of 2.
  - The existing IPD specs: 1,104 of 1,104.
  - layout-matrix: 84 of 84.
- **Width spec (work-area-width.spec.ts):** 3 runs, 0 failed. With the old rule injected into a temporary copy, the 1920, 2560 and centred-column tests fail, so the spec detects the bug. The admin shell was not driven in a browser.
- **Frontend:** 2,439 unit tests, lint and the build pass.
- **No open defect.**
- **Notes:**
  - `sah37-world.ts` still counts the draft revision 1, 2, 3 in the browser mock. The large-number path is covered by unit specs.
  - The layout-matrix run rewrote tracked files under docs/test-plans/SAH-35-36-38-shots/. The human can drop those changes.
  - Backlog: fictional names and numbers in the e2e data.
- **Status: waiting for the human (`merge`).**
  - **Human lines:** `SAH-37 | stories`, `contract` (the wording changed, so re-approve) and `merge`.
  - **Go-live conditions:** the privacy register for diagnosis, vitals and mobile on the ward list (O3); a clinician signs off the default card and the tile marks (DEC-027); confirm DEC-028 O1 to O7 and the addendum.
- **Files for `git add -f`:** QA's list is in its report. It includes the Card*IT files and CardSetupSupport, AdmittedCardsIT, work-area-width.spec.ts, patient-card-setup.spec.ts, sah37-world.ts, sah36-list.ts, sah31-world.ts, docs/test-plans/SAH-37.md and BUG-width-expand-collapse.md.

## Release audit (DEC-032, 9 Nov 2026)
- **Phase 0 done (qa-engineer):** docs/test-plans/SAH-37-audit-validation.md.
  - It has 47 fields and a matrix of 41 rules. It finds 11 gaps: none Sev-1 or Sev-2, 4 Sev-3 and 7 Sev-4.
  - The server layer is sound.
- **Human decisions:** the inline label error (G-1, G-2) and the new V15 rule (G-4) are both approved. See DEC-032.
- **UI measure pass (Phase 2):** running.
- **Fix plan:**
  - architect (V15 in the design note and contract) ∥ db-engineer (G-5);
  - then java-developer (V15);
  - then angular-developer (G-1, G-2, G-3, V15, and the UI defects from the measure pass).
  - They go after the UI measure pass ends, so the build under test does not change under it.
- **solution-architect, V15 (9 Nov 2026) done.** ipd.yaml parsed by the tech lead: OK. `validate docs` fails only on ADM-2.md.
  - **V15:** a label may not equal another field's standard name or another field's label in the card, after NFC, trimming, collapsing spaces and case folding.
    - The error goes to the later field only: "This name is already used by another field."
    - It is a 400 at `setup.sections[i].fields[j].label`, and rule 15 comes last in the order.
    - Standard names of fields that are off are reserved too.
    - It is not applied on read, so stored rows stay readable.
  - **Browser:**
    - G-1: each `fieldErrors` label path is shown under its own box in the `.ferr` style, with `aria-invalid`, and focus goes to the first box in error.
    - G-2: checks on blur and on submit, with the same texts as the server.
    - G-3: a label equal to the field's own standard name is "" for the pill, Reset, the unsaved check and Publish.
    - On submit with a label error, the request is not sent.
  - **Added:** ADR-015 amendment A4.
  - **Open, optional:** the V12 text has no final period, and a contract change is not worth it.
  - **For the BA:** acceptance criteria AC-V15 and AC-inline, text in the architect's report.
- **business-analyst, audit ACs (9 Nov 2026) done.**
  - AC 67 (duplicate label, V15), AC 68 (inline label errors) and AC 69 (label equal to the standard name) are added to story SAH-37-3. The count is now 69, and nothing was renumbered.
  - A requirement rule 22 (field names stay distinct) was added, and so was a traceability line.
  - `validate docs` was run by the tech lead: it fails only on ADM-2.md.
  - Stale text outside the BA's folders: the architecture note line 3 and the QA test plan say 66 ACs. The tech lead updated this handoff's header. The architect and QA fix theirs when they next touch those files.
- **UI measure pass done (qa-engineer, 9 Nov 2026).**
  - 16 defects: 0 Sev-1, 4 Sev-2, 8 Sev-3 and 4 Sev-4. Report: docs/test-plans/SAH-37-audit-ui.md, with screenshots in docs/test-plans/SAH-37-audit-shots/.
  - **Sev-2:** D-1 (Fields tab label box at about 1100–1300 px), D-2 (the same at 375 px), D-3 (a sticky status bar on phones), D-4 (the BP unit overlaps the pulse tile).
  - **Human:** D-1 and D-2 are approved as a responsive fix. D-9 (contrast) waits for the human to change docs/design/tokens.md.
  - A throw-away measuring spec, frontend/e2e/audit-sah37-ui.spec.ts, is guarded by AUDIT_UI=1.
- **Next:** angular-developer fixes G-1, G-2, G-3, V15 (UI), and D-1 to D-16 except D-9. Then QA re-measures, adds regression tests, and the final gate and reviews follow.
- **angular-developer, audit fixes (9 Nov 2026) done in code. The layout fixes are NOT re-measured in a browser.**
  - **Validation gaps closed:** G-1, G-2, G-3, the V15 mirror and G-10. G-9, an optional counter, was not done.
    - Label errors show under their own box in the `.ferr` style, with `aria-invalid`, and focus goes to the first box in error.
    - Nothing is sent when a label is in error.
    - A label exactly equal to the field's own standard name counts as unchanged.
    - The V15 key reserves the standard names of all 25 fields.
  - **UI defects fixed:**
    - D-1 and D-2: the editor goes above the preview under 880 px, and rows stack in a narrow editor.
    - D-3, D-4 and D-15: restored from the mockup.
    - D-5 to D-8, D-10, D-13 and D-14.
    - D-12 partly: the pill buttons and the dialog buttons are 44 px; the patient-name link has a 44 px hit area.
  - **Not done:** D-11 (needs a measurement) and D-9 (the human changes the tokens, see DEC-032).
  - **Tests:** about 131 new unit tests. The full suite is 166 files and 2,570 tests, all passing. Lint and the build pass.
  - **Re-measuring** was not possible: the backend lock was present the whole time.
  - **Next:** QA re-measures with the audit spec, adds the browser regression tests (listed in the developer's report), and decides D-11 and the 20 px patient-name box.
  - **Inconsistency for the BA:** AC 69 says "any case", but the design note says exact. The BA changes AC 69 to say "exactly" (DEC-032).
- **BA, AC 67 and AC 69 wording fixed (9 Nov 2026).** Only the exact standard name (same case) is stored as empty. A case or spacing variant is never a V15 error, and it is a real label. `validate docs`, run by the tech lead, fails only on ADM-2.md.
- **db-engineer, G-5 (9 Nov 2026): migration written, NOT yet validated.**
  - **Files:** V2026_12_07_1000__ipd_card_setup_schema_version.sql and a new section 9 in docs/database/SAH-37.md.
  - **Migration:** `schema_version smallint not null default 1` on both card tables, with a check `>= 1` and no upper bound. The grants are additive, no existing row changes, and the append-only triggers are untouched.
  - **Not validated:** it polled for 60 minutes and never got the backend lock. The java-developer holds it (taken 22:32). The `validate db` run, and the java-developer's `validate backend`, apply the migration, so one run covers both.
  - **For the java-developer:**
    - name `schema_version = 1` in the insert and update column lists;
    - treat a higher stored version as unreadable and fall back to the standard setup with `fallback: true`, for a draft as well as a version;
    - map the two new check constraints to 500.
  - **For QA:** CardSetupStoredIT checks that the column exists with default 1, an insert without it works, and a value of 0 is refused (23514).
