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
