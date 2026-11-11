# SAH-37 code review · IPD patient card setup

Reviewer: code-reviewer, read-only. Date: 9 Nov 2026. The review was done by reading the code, with no builds or tests, while QA was still running. Only the SAH-37 parts of the shared tree were judged.

**Verdict: APPROVE WITH COMMENTS.** There is no Blocker and no Major finding.

## Findings
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | CardSetupService.java:259-270 (`requireCurrent`); JdbcCardSetupRepository; CardSetupStoredIT.java:317-350 | **Revision restart.** After a discard, the new draft starts again at revision 1, so a stale editor can overwrite another owner's new draft (AC12). The extra `baseVersion` comparison adds nothing, because a draft's base version always equals the live version. The unit test at CardSetupServiceTest:360 covers a state that cannot occur. Same as security finding 2. | java-developer (db-engineer if a sequence is used) | FIX: the draft token never repeats. Remove the dead check. QA flips the IT. |
| 2 | Minor | docs/api/ipd.yaml (card-setup 403s); CardSetupService.java:55-56; AdmissionService.java:517-528 | **The 403 texts never reach the client.** `@PreAuthorize` refuses first, and the shared handler always sends the fixed forbidden text. The contract text is wrong and the constants are dead. | solution-architect (contract), java-developer | FIX: the contract says that the shared forbidden text is used. Delete the dead constants. |
| 3 | Minor | frontend/.../ipcard/card-setup.store.ts:467-469; ipcard.page.html:49 | **A saved draft cannot be removed from the screen.** Save and Discard show only when the editor differs from the live setup. If the owner edits back to equal the live setup, the pill says "No unpublished changes" while the draft still exists on the server and comes back on the next open. | angular-developer | FIX: offer Save draft and Discard draft when there are unsaved edits or a server draft. |
| 4 | Minor | CardSetupRepair.java:130 | On read, repair removes only control characters. Validation also refuses invisible and text-direction characters. A damaged stored row could put a right-to-left override in a label. It is not reachable through the API. | java-developer | FIX: the same TextRules in repair |
| 5 | Minor | CardAccessRule.java:36 | A second copy of the deposit roles (`DEPOSIT`) duplicates `AdmissionService.DEPOSIT_ROLES`. A drift would show a Deposit button that the endpoint refuses. | java-developer / qa-engineer | FIX: one copy, with a parity test |
| 6 | Minor | config/overview/overview.page.ts:41,134 | The Overview calls `GET /card-setup` whenever IPD is available. Roles outside the four list roles would get a swallowed 403 and a warn line each time. | angular-developer | FIX: call it only for FRONT_DESK, NURSE, DOCTOR and OWNER, and use takeUntilDestroyed |
| 7 | Nit | CardSetupJson.java:25 | The strict read still accepts `"on":"true"`, `1` and `"3"`, but the contract says boolean and integer. | java-developer | FIX: turn scalar coercion off |
| 8 | Nit | CardSetupController.java:38,53,59 | The write bodies are `JsonNode`, not `@Valid` DTOs. The reason is in the Javadoc and covered by CardSetupIT. | — | Accept as a documented exception |
| 9 | Nit | CardSetupService.java:293,297 | `Optional<CardDraft> draft = null`, and a pointless copy `safeNames`. | java-developer | FIX (cheap) |
| 10 | Nit | card-setup.model.ts:294; card-setup.logic.ts | The browser keeps a copy of the safety fields and the standard card, and derives the deposit-low flag. | angular-developer | FIX: a spec that compares the copy with the catalogue fixture |
| 11 | Nit | ipd/card/card-actions.component.html | In the preview the buttons stay in the accessibility tree. | angular-developer | FIX: `inert` on the preview card |
| 12 | Nit | AdmittedCards.FactsPlan | In Compact, Temp and RR are sent when switched on but never drawn. | — | Accept. The owner chose those fields. |
| 13 | Nit | CardSetupStoredIT.java:317 | The "known gap" test asserts the defect as green. | qa-engineer | Flip it when #1 is fixed. |

## Confirmed
- **Contract.** The check order, absent versus null, discard with no draft, and the 409 for a publish equal to live all match the contract.
- **Locked safety fields.** They are validated on save and publish and repaired on read. The browser guard is only a guard.
- **Concurrency.** The advisory lock comes first, a duplicate key maps to 409, and a double publish makes one version.
- **Data minimisation.** A fact is sent only when the role's group allows it AND the setup switches it on. The default setup sends nothing new.
- **Decisions.** DEC-027, DEC-028 and the frontend readings F1–F7 are honoured.
- **SAH-36 is unchanged.** The edits to AdmittedCardsIT keep their privacy intent.
- **Boundaries and migration.** Module boundaries are clean, and the migration is forward-only with RLS.
- **Frontend.** All components use OnPush, signals and inject(), and have no leaks. A 403 clears the data (SAH-40).
- **Tests.** No skipped or disabled test in the new tests.

## Open before merge
Re-check the 2 CardFactsIT failures that were open at the time of the review. Security finding 4 explains the empty mobile: it is a product defect, because the patient lookup returns no mobiles.

## Re-check after the fix pass (9 Nov 2026): APPROVE WITH COMMENTS
Reviewed by reading only, while the backend developer's gate was still running. There is no Blocker and no Major finding.

**Closed:** #1 to #7, #9, #10 and #11. #8 and #12 stay accepted. #13 is QA's test flip, still to do.
- #1: `nextRevision` gives the clock in microseconds and at least the replaced revision plus 1. The dead `baseVersion` branch is gone.
- #2: the dead constants are gone.
- #3: `draftActions` shows Save and Discard when there are edits or a server draft.
- #4: repair applies the validator's TextRules.
- #5: `CardAccessRule.DEPOSIT` is the single copy, with a parity test.
- #6: the Overview call is limited to the four roles.
- #7: scalar coercion is off.
- #9: the draft handling is clear.
- #10: the safety copy spec compares with the fixture.
- #11: `inert` on the preview.

**Regression checks passed:**
- The mobile lookup: a separate method on the patient api, called only when needed (`never()` verified), and `findAllCurrent` is free of mobiles.
- `factsOn` has keys only.
- An unchanged draft writes nothing.
- `toString` is without labels.
- The fixture freeze, with no assertion weakened.
- QA's D2 fix matches the server's `changedParts`.

**Width fix:**
- The root cause is correct: the 1680 px cap centred in the work area, with the rail collapsed.
- The mockup's last rule is fluid, so the fix follows the mockup.
- DEC-013 discloses the deviation from option B.
- Risk: long lines at 2560 px. The guard spec reads the styles as text only; QA's e2e carries the layout proof.

**Still open (none block merge):**
| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| 14 | Nit | ipcard.page.spec.ts:165, 667, 676-682 | The specs still use the old owner-only 403 texts. | angular-developer | FIX (tiny), same as security R2 |
| 15 | Nit | docs/api/ipd.yaml (Save draft); the SQL column comment | The contract says the revision "changes on every save", but an unchanged save keeps it. The SQL comment is stale. | solution-architect | FIX: one sentence in the contract. Accept the SQL comment. |
| 16 | Nit | CardSetupService.java:133 | A redundant always-true comparison. | java-developer | Optional |
| 17 | Nit | card-setup.model.spec.ts | The reference is the hand-written fixture, not a server answer. | — | Accepted |
| 18 | Info | `nextRevision` | The clock assumption is documented. | — | None |

QA still has to re-run CardFactsIT and CardSetupStoredIT, flip the revision test, run the section-move witness and the width tests, and re-check the Save draft specs.

## Closure (9 Nov 2026)
#13 is closed: QA flipped the known-gap test. #14 is closed: the 403 mocks use the shared text. #15 is closed: the contract now says an unchanged save keeps its revision. The backend gate passed (unit 3,495, integration 1,758, 0 failed), and so did lint, the frontend unit tests (2,439) and the build. Verdict stays **APPROVE WITH COMMENTS**, with nothing open except accepted nits.

## Release audit re-check (DEC-032, 10 Nov 2026): APPROVE WITH COMMENTS
Reviewed by reading only (no builds or tests). Backend main code is unchanged since 62064bf; the working tree adds the two IT files and the D-11, D-17 and D-18 frontend fixes. No Blocker, no Major. V15 and G-5 are correct; both IT files are additions only (415 lines added, none removed); no assertion was weakened (the `sah31-world.ts:1308` edit is a data-free 404 like its siblings).

| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| A1 | Minor | patient-card.component.scss:190-200 | The mockup contradicts itself on card values (line 2079 wraps, line 2368 cuts). The code wraps, so it differs from the mockup for the Standard grid. | Human | Recorded as DEC-013 item 15 (default: wrap). Needs the human's answer before merge. |
| A2 | Minor | CardSetups.java:52-78; ipd.yaml GET /card-setup | A row with a newer `schema_version` reaches the owner as the standard setup with no warning, and publishing the standard setup answers 409 "unchanged". Only after an app roll-back. | solution-architect, then both developers | Backlog (same as security A1) |
| A3 | Minor | CardSetupValidator.nameKey vs card-label.rules.ts nameKey | Two copies of the name key with no shared test. They agree on every input that survives V12. | java-developer, angular-developer | Backlog: one JSON fixture read by both unit suites |
| A4 | Minor | card-label.rules.ts:119-127 | Check order differs from the server for a raw label over 400 with a control character. Rare (`maxlength="40"`). | angular-developer | Backlog |
| A5 | Minor | page-head.component.ts:20-29; ipd.page.ts:239-250 | D-11 is sound. The reserved two lines are a guess; at about 320 px the lead may wrap to three lines; the test measures 375 and 768 only. | angular-developer | QA is measuring 320 px now |
| A6 | Minor | sah37-layout-styles.spec.ts | The guard reads styles as text and could pass for a rule that never applies. The e2e measure and mutation check carry the proof. | angular-developer | Accept; not behaviour coverage |
| A7 | Minor (process) | frontend/e2e/ is gitignored | The e2e files need `git add -f`, or the regression guard is lost. | Human at commit | In the QA commit list |
| A8-A12 | Nit | CardSetupIT:1126, 1151 and the regression spec :383 write invisible characters raw; `aria-describedby` drops the hint line while an error shows; `--pcbar-h` via `style.setProperty`; fixed `waitForTimeout` pauses (flake risk); migration `add constraint` not idempotent (harmless under Flyway) | qa-engineer / angular-developer / db-engineer | Backlog |
| A13 | Nit | DEC-032 line 58 | Stale "BA changes AC 69". | Tech lead | Fixed |

Not verified by the reviewer: any test or build result, the 52 screenshots, the BP tile for screen readers when its unit is hidden. QA's full e2e re-run is still needed.

## D-11 retry addendum (10 Nov 2026): APPROVE WITH COMMENTS
Read-only re-check of the page-head sizer fix. No Blocker or Major. The settled DOM and CSS equal the earlier ones (the plain `<p class="lead">` is drawn when not reserved); the hidden sizer is an empty span with generated content and `visibility:hidden`, so it is not in `textContent` or the accessibility tree; `leadLike` and `leadPending` are right for success, partial answer, failure, Emergency tab, pharmacist, locked and list error; subscriptions use `takeUntilDestroyed`.

| # | Sev | File:line | Finding | Owner | Tech lead decision |
|---|---|---|---|---|---|
| B1 | Minor | DEC-033 residual 2 | The text understated the case: one failed lead call also moves the tiles (19 px at 320-375 px, 56 px at 621 px). | tech lead | Fixed in DEC-033. Sev-4 stays. |
| B2 | Minor | ipd.page.spec.ts (~302-356) | Missing unit paths: one call failing, Emergency tab, pharmacist, locked module. | angular-developer / QA | Backlog |
| B3 | Minor | sah37-layout-styles.spec.ts (D-11 block) | Source-text regex tests, one vacuous. The page-head spec (DOM) and QA's e2e carry the proof. | QA | Backlog (replace with a DOM check) |
| B4 | Nit | ipd.page.ts:71 | `const LOAD_ERROR ='…'` lost a space. | angular-developer | Backlog |
| B5 | Nit | page-head.component.ts:49 | `.reserve` is only a test hook (no CSS); `pointer-events:none` on the sizer is redundant. | angular-developer | Backlog |
| B6 | Nit | ipd.page.ts:420-423 | Old behaviour: a reload while both lead calls are in flight re-sends them. | — | Backlog |

Backlog improvements: keep the reserve on after a lead-call failure with the default words in the hidden sizer only; keep the last answered cut and percent in an in-memory service per tenant (removes residual 1); start the lead calls in parallel with the list. Not verified by the reviewer: any rendered result (taken from QA 5.2) and the final changed D-11 assertion.
