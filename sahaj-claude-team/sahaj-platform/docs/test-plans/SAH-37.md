# Test plan SAH-37 · IPD patient card setup (Clinic setup → Patient card; the Admitted cards that follow it)

Story: `docs/stories/SAH-37.md` (66 acceptance criteria, numbered 1-66 through the file). Decisions: ADR-015, `docs/architecture/SAH-37-patient-card-setup.md`, `docs/database/SAH-37.md`, DEC-027, DEC-028.
Mockup screen: Clinic setup → Patient card (screen-map row 29), used on IPD → Admitted (SAH-36).
Written 9 Nov 2026 by qa-engineer; finished in resume 2 (18:56). Status: see "Results" at the end. Verdict after the fix-pass re-run (9 Nov 2026, 21:55): **PASS, no open defect.** D1, D2 and D3 are closed and re-tested (section "Re-run after the fix pass" at the end).

## What is tested where

| Layer | Files | What it proves |
| --- | --- | --- |
| Backend integration, real `sahaj_test` database, as `sahaj_app`, MockMvc through the full filter chain, profile `test` | `backend/src/test/java/com/sahaj/platform/it/CardSetupIT.java` (26 tests), `CardSetupStoredIT.java` (10), `CardFactsIT.java` (11), `CardSetupPlansIT.java` (1), `AdmittedCardsIT.java` (13, three assertions rescoped), shared helpers `CardSetupSupport.java` | The contract: draft, publish, versions, two-owner conflicts, validation V1-V14 with paths and messages, role gating (403), other clinic invisible (RLS), Read-only and module-off, history lines, repair on read, the stored tables (FORCE RLS, append-only, grants, checks), the extra card facts per role (absent from the raw JSON, not null), `cardFacts=all` for the owner only, the tile marks at every band edge, the query plans |
| Browser (Playwright, production build, 360 px and 1440 px) | `frontend/e2e/patient-card-setup.spec.ts` (63 tests per project, 126 runs), `frontend/e2e/sah37-world.ts` (the mock server) | What the screens do with the answers. **Every API call is mocked in the browser** (`page.route`), because no backend is started in this lane, as in the other specs; the mock server is written from `docs/api/ipd.yaml`. So the browser tests prove the screens; the server rules are proved by the integration tests only |
| Frontend unit tests (angular-developer, not mine) | `patient-card.layouts.spec.ts`, `card-setup.logic.spec.ts`, `ipcard.page.spec.ts` ... | Pure rules in the browser (pill count, tile words, card drawing) |

## Roles and data used by the integration tests

OWNER, DOCTOR, NURSE, FRONT_DESK (the four roles that have the Admitted list); BILLING, PHARMACIST, VISITING_DOCTOR, COUNSELLOR (no IPD card access). A second OWNER of the same clinic (`secondOwner`). A second clinic for isolation. Fictional patients only (`ward()` / `admitNamed`), mobiles from `uniqueMobile()`.

## Coverage: acceptance criterion to test

IT = integration test (class.method, shortened to the part of the name that tells the case). E2E = `patient-card-setup.spec.ts` (title starts with the AC numbers), run at 360 and 1440. Result is from the final runs listed under "Results".

| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 1 | Entry after Discharge, text, chips, tips; IPD Off: not in menu, API 403 `module-off` | E2E "AC1 the entry sits after Discharge...", "AC1 IPD Off..."; E2E "AC1 IPD Off: the section shows the plan notice and asks the server for nothing"; IT CardSetupIT.whenIpdIsOffTheSetupAndEveryWriteAre403ModuleOff | pass |
| 2 | Never published: v1 teal pill, no unpublished changes, Reset and Publish disabled, v1 in the list | E2E "AC2 ..."; IT CardSetupIT.aClinicThatNeverPublishedSeesTheStandardCardAsV1AndNothingIsStored | pass |
| 3 | A change only changes the draft; amber pill counts the changed parts; Publish enabled; staff still see the live card | E2E "AC3 a change only changes the draft..."; IT CardSetupIT.saveDraftStoresItForTheOwnerWithRevisionAndChangedParts...; CardFactsIT.thePublishedSetupTravelsInTheListAnswer... (drafts are not on the ward) | pass after the fix pass (D2 closed: E2E "AC3 WITNESS" passes at both sizes) |
| 4 | Save draft stored on the server, toast, "saved 11:05 AM", history line; leave warning | E2E "AC4 Save draft...", "AC4 leaving with unsaved edits asks first...", "AC4 no warning when nothing was changed"; IT CardSetupIT.saveDraftAndPublishWriteOneHistoryLineEach... | pass |
| 5 | Discard removes the draft, toast; none: no Discard | E2E "AC5 ..."; IT CardSetupIT.discardRemovesTheDraftWritesNoHistoryAndWithNoDraftIsA200 | pass |
| 6 | Reset to default makes the draft standard; disabled when equal | E2E "AC6 ..."; IT CardSetupIT.aDraftThatEqualsTheLiveSetupRemovesTheDraftAndWritesNoHistoryLine (the server side of "equal") | pass |
| 7 | Publish: next version, draft removed, blue pill / teal when default, toast with parts, history line, "Back to the Sahaj standard card" | E2E "AC7 AC9 Publish makes the next version..."; IT CardSetupIT.publishMakesTheDraftTheNextVersionDeletesTheDraft...; saveDraftAndPublishWriteOneHistoryLine... | pass |
| 8 | Draft equals live: no pill, Publish disabled, nothing published; double tap makes one version | E2E "AC8 a double tap..."; IT CardSetupIT.publishingWhatIsAlreadyLiveIs409UnchangedAndStoresNothing; twoPublishesAtTheSameMomentMakeExactlyOneVersion...; aSecondPublishWithTheOldBaseVersionIs409... | pass |
| 9 | Versions: v<n>, what changed, person and time, newest first, one Live, no restore | E2E "AC7 AC9 ..."; IT CardSetupIT.publishMakes...VersionListIsNewestFirstWithOneLive; theVersionListHoldsAtMost50Rows...; aSecondOwnerIsNamedAsThePersonWhoPublished | pass |
| 10 | Non-owner sees the live setup only, controls disabled, never the draft; any write 403 | E2E "AC10 AC25 AC35 <role>: ..." (3 roles); IT CardSetupIT.theDraftAndTheVersionsAreAbsentFromTheRawJsonOfEveryNonOwner...; everyWriteByAnyoneButTheOwnerIs403AndNothingIsStored | pass |
| 11 | Roles without IPD access 403; another clinic never reads or changes the setup, draft, versions (RLS) | IT CardSetupIT.theFourListRolesMayReadTheLiveSetupAndEveryOtherRoleGets403; anotherClinicsSetupDraftAndVersionsAreInvisibleAndCannotBeChanged; CardSetupStoredIT.bothTablesHaveForcedRowLevelSecurity...; withNoClinicBoundNothingIsVisibleAndNothingCanBeWritten... | pass |
| 12 | Two owners: the later save or publish is refused with the exact message and stores nothing | IT CardSetupIT.anOwnerWhoLoadedBeforeAnotherSavedADraft...; aStaleDraftRevisionIsRefusedForSaveDiscardAndPublish; aDraftDiscardedByAnotherOwnerCannotBeSavedOrPublished...; anOwnerWhoLoadedBeforeAnotherPublishedIsRefused...; twoPublishesAtTheSameMoment...; E2E "AC12 a conflict...", "AC12 the same message when a discard meets..." | pass |
| 13 | Busy state, failed save keeps the edits and says so; load failure with Try again | E2E "AC13 a slow save...", "AC13 a card setup that cannot be loaded..." | pass |
| 14 | IPD Read-only: owner reads, writes not offered, server 409 `ipd-readonly` | E2E "AC14 IPD Read-only...", "AC14 the server answers 409 ipd-readonly..."; IT CardSetupIT.whenIpdIsReadOnlyTheOwnerReadsButEveryWriteIs409IpdReadOnly... | pass |
| 15 | Overview tile line "<Layout> · Sahaj default\|customized · v<n>" and " · draft" | E2E "AC15 ..." (2 tests) | pass |
| 16 | History and versions hold no patient data or labels, names of the parts only | IT CardSetupIT.saveDraftAndPublishWriteOneHistoryLine... (parts and version numbers only); theDraftAndTheVersionsAreAbsent...NoLabelOrNameLeaks | pass |
| 17 | The Layout and sections tab: panels, templates, layouts with `aria-pressed`, cards per row | E2E "AC17 AC20 AC21 AC22 ..." | pass |
| 18 | A template replaces layout, sections, fields, actions; keeps cards per row; toast | E2E "AC18 ..." | pass |
| 19 | Layout choice redraws the preview, hides nothing | E2E "AC17 AC20 AC21 AC22 ..." and "AC3 ..." (preview follows) | pass |
| 20 | 2, 3 or 4 per row becomes the default and opens the Admitted grid | E2E "AC17 AC20 ...", "AC20 AC54 the published Cards per row is the opening grid"; IT CardFactsIT.thePublishedSetupTravels... (`cardsPerRow` 2) | pass |
| 21 | Sections panel: text, seven sections, "N of M fields on", handle, arrows, switch | E2E "AC17 AC20 AC21 AC22 ..." | pass |
| 22 | Header "Always on"; Workflow status, Vitals, Clinical "Safety · always on"; server refuses them off or out of place | E2E "AC17 AC20 AC21 AC22 ..."; IT CardSetupIT.everyRuleOfTheValidatorIs400WithItsPathAndMessage... | pass |
| 23 | Optional sections switch off; a section that holds a safety field cannot | E2E "AC23 ..."; IT CardSetupIT.aSafetyFieldMayMoveToAnotherSectionOnlyWhileThatSectionIsOn; everyRuleOfTheValidator... | pass |
| 24 | Drag (mouse) and Move up / down (keyboard, touch) with focus and polite announcement; header stays first | E2E "AC24 AC36 keyboard moves...", "AC24 a mouse drag..." (drag skipped on the phone project by design) | pass (mouse drag: 1 run skipped at 360) |
| 25 | Non-owner: controls disabled; server 403 | E2E "AC10 AC25 AC35 ..."; IT CardSetupIT.everyWriteByAnyoneButTheOwnerIs403... | pass |
| 26 | Unknown template/layout/columns/section 400; safety section off 400 "The <section> section is a safety section and stays on." | IT CardSetupIT.everyRuleOfTheValidatorIs400WithItsPath...; aBodyThatIsNotTheDeclaredShapeIs400WithAProblemBody... | pass |
| 27 | Fields tab: text, groups, label box, switch, arrows, "Drop a field here" | E2E "AC27 AC29 AC32 ..." | pass |
| 28 | Catalogue holds exactly the fields with data; default placement and on/off of the mockup | IT CardSetupIT.theStandardSetupIsTheSah36CardPlusTheLockedAllergiesAndEarlyWarningLinesAndTheCatalogueHoldsOnlyFieldsWithData; E2E "AC27 AC29 AC32 ..." | pass |
| 29 | Locked fields: Required / Safety pills, no switch; off 400 | E2E "AC27 AC29 AC32 ..."; IT CardSetupIT.everyRuleOfTheValidator... | pass |
| 30 | A safety field moves only into a section that is on; into the header it sits after the name | E2E "AC30 ..."; IT CardSetupIT.aSafetyFieldMayMoveToAnotherSectionOnlyWhileThatSectionIsOn; everyRuleOfTheValidator... | pass |
| 31 | Label trimmed, 40 characters, plain text, empty = standard name | E2E "AC31 ..."; IT CardSetupIT.theLabelLimitIs40CharactersNotBytesAndMarkupIsKeptAsTypedAndTrimmed | pass |
| 32 | Safety fields and the name cannot be renamed (box disabled; server refuses a changed label) | E2E "AC27 AC29 AC32 ..."; IT CardSetupIT.everyRuleOfTheValidator... (label of a locked field) | pass |
| 33 | A role does not get a field it may not see; Mobile only to OWNER and FRONT_DESK; preview "Hidden for <role>" | IT CardFactsIT.factsSwitchedOnAreSentToTheRolesThatMaySeeThem...; aFactIsAbsentWhenItsFieldIsOff...; theCardAbilitiesAndTheVisibleGroups...; E2E "AC46 AC48 ...", "AC56 a Nurse card has no mobile..." ; **the Mobile value: CardFactsIT.theMobileValueIsTheNumberOfThePatient... FAILS, Defect D1** | pass after the fix pass (D1 closed: the Mobile value test is green) |
| 34 | Vitals tiles switchable; Early warning locked; no vitals: "No vitals recorded yet", "Last recorded" amber "Not recorded" | IT CardFactsIT.aBlankReasonShowsAsUnderEvaluationAndAPatientWithNoVitalsHasNullTilesAndNoScore (the data side: null tiles, no score); E2E "AC54 AC55 ..." (3 layouts) and the unit test patient-card.layouts.spec.ts (the words) | pass |
| 35 | Non-owner 403; unknown key / duplicate / wrong section / missing locked field 400; stored setup lacking a locked item is repaired on read | IT CardSetupIT.everyRuleOfTheValidator...; CardSetupStoredIT.aStoredSetupThatLacksLockedItemsIsRepairedOnEveryRead...; aStoredDraftWithMissingLockedItemsIsRepairedForTheOwnerOnly; E2E "AC10 AC25 AC35 ..." | pass |
| 36 | Roles and accessible names: tabs, switches `aria-checked`, move buttons, label boxes, live region | E2E "AC24 AC36 ...", "AC36 roles and names ..." | pass |
| 37 | Quick actions tab: five actions in order, Deposit off by default, no Case paper / Update stage | E2E "AC37 AC38 ..." | pass |
| 38 | Fixed "who" lines; no "Change who can use each action" button | E2E "AC37 AC38 ..." | pass |
| 39 | Off = hidden for everyone; on = only the roles of the line; not in Read-only for Transfer, Discharge, Deposit | E2E "AC39 a quick action switched off...", "AC40 AC43 AC39 Deposit is not drawn..."; IT CardFactsIT.theCardAbilitiesAndTheVisibleGroupsFollowTheRoleAndDepositFollowsReadOnly | pass |
| 40 | Deposit action for OWNER and FRONT_DESK opens the admission sheet at the deposit part | E2E "AC40 the Deposit action ..."; IT CardFactsIT.theCardAbilities... (`card.deposit`) | pass |
| 41 | Actions in the published order; Nursing primary; none on: no row, name still opens the sheet | E2E "AC39 a quick action switched off..." and "AC54 AC55 AC58 the published layout ..." (order) ; IT CardFactsIT.thePublishedSetupTravels... (order `deposit, nursing, emr, discharge`) | pass |
| 42 | Sheets remain reachable from the admission sheet when Nursing / EMR are off | No new code: the sheets are unchanged. The existing IPD specs (nursing, safety, mar, medicine-round, discharge, bed-transfer, emergency, admitted-cards) were run again | see "Results" |
| 43 | Non-owner 403; unknown / duplicate action 400; role may not call an action the setup shows | IT CardSetupIT.everyWriteByAnyoneButTheOwnerIs403...; everyRuleOfTheValidator...; existing endpoint ITs (SAH-36 AC44); E2E "AC40 AC43 AC39 ..." | pass |
| 44 | Live preview panel: title, "Showing your draft / the live card", Desktop / Tablet | E2E "AC44 AC45 AC49 ..." | pass |
| 45 | View as (Owner, Doctor, Nurse, Front desk; default Nurse), Patient select | E2E "AC44 AC45 AC49 ..." | pass |
| 46 | The preview draws the draft as the ward does, for the chosen role; absent not greyed; redraws at once | E2E "AC46 AC48 View as ..." | pass |
| 47 | Preview card is inert; nothing written | E2E "AC47 AC51 the preview card is inert ..." | pass |
| 48 | "Hidden for <role>: N field(s), M action(s)" | E2E "AC46 AC48 ..." | pass |
| 49 | Desktop and Tablet notes, 768 px frame | E2E "AC44 AC45 AC49 ..." | pass |
| 50 | Nobody admitted: one fictional example patient, never saved or sent | E2E "AC50 AC52 nobody admitted ..." | pass |
| 51 | High score: red outline; unseen escalation flag | E2E "AC47 AC51 ..." | pass |
| 52 | Preview asks for no more data than the owner's own role may read; non-owner never asks for patients | E2E "AC50 AC52 ..."; IT CardFactsIT.cardFactsAllGivesTheOwnerEveryFactWhateverIsPublishedAndEveryoneElseGets403 (only the owner may ask for every fact, no record-access row) | pass |
| 53 | Failed patient load: example patient and notice | E2E "AC53 ..." | pass |
| 54 | Admitted cards follow the published setup (layout, sections, order, labels, on fields, actions, cards per row); v1 when never published | IT CardFactsIT.thePublishedSetupTravelsInTheListAnswer...ReachesEveryRoleAtTheNextRead; theDefaultAnswerIsTheSah36FactsPlusTheCardSetup...; E2E "AC54 AC55 no setup ever published ...", "AC54 AC55 AC58 the published layout <layout>" (3), "AC20 AC54 ..." | pass |
| 55 | Standard, Compact, Expanded drawn as specified | E2E "AC54 AC55 AC58 the published layout compact / standard / expanded" | pass |
| 56 | A role gets only its groups; FRONT_DESK never gets vitals, allergies, diagnosis; NURSE / DOCTOR never billing; Mobile only OWNER and FRONT_DESK | IT CardFactsIT.factsSwitchedOnAreSentToTheRoles...; theDefaultAnswer...; aFactIsAbsentWhen...; AdmittedCardsIT.aFrontDeskAnswerHasNoClinicalKey... (rescoped, see below); E2E "AC56 ..." (2) | pass (D1 closed: the Mobile value reaches OWNER and FRONT_DESK only) |
| 57 | Locked fields always on the card of OWNER, DOCTOR, NURSE; Allergies / Early warning words; repaired on read | IT CardFactsIT.theDefaultAnswer... (locked lines in the default answer); CardSetupStoredIT repair tests; E2E "AC57 AC55 Compact always shows the allergy ..." | pass |
| 58 | Tile marks "Check" / "Critical" with a title, from the SAH-30 points | IT CardFactsIT.theTileMarksFollowTheSah30PointsAtEveryBandEdgeAndTheDiastolicIsNeverMarked (41 band edges); theMarkReachesTheCardOfADoctor...; E2E "AC54 AC55 AC58 ..." | pass |
| 59 | Table view unchanged; search, filter, paging apply | E2E "AC59 the Table view is unchanged by the setup"; existing admitted-cards.spec.ts | pass |
| 60 | A publish reaches each person at the next reload; never half old, half new | IT CardFactsIT.thePublishedSetupTravels...ReachesEveryRoleAtTheNextRead (one `cardSetup` per answer); E2E "AC60 a change published while the page is open ..." | pass |
| 61 | Unreadable stored setup: standard card, `fallback`, list still loads; notice text | IT CardSetupStoredIT.aStoredSetupThatCannotBeReadAtAllShowsTheStandardCardWithFallbackAndTheListStillLoads; E2E "AC61 ..." (2) | pass |
| 62 | "Customize card" for the owner only (absent, not disabled, for others) | E2E "AC62 ..." (owner + 3 roles) | pass |
| 63 | No record-access row for the setup or the list; no patient data in the setup, versions, history | IT CardSetupIT (`Stored.access` unchanged by every write and refused write); CardFactsIT.cardFactsAllGivesTheOwner... (record-access count unchanged) ; existing AdmittedCardsIT.theListWritesNoRecordAccessRow... | pass |
| 64 | Tablet 2 per row, 44 px targets; one per row at 700 px; long text cut | E2E "AC64 phone and desktop ...", "AC64 a phone shows one card per row ..." | pass |
| 65 | Another clinic's card never appears; Read-only / Trial draw the same setup; IPD Off lock page | IT CardSetupIT.anotherClinicsSetupDraftAndVersionsAreInvisible...; CardFactsIT.theCardAbilities... (Read-only); E2E "AC40 AC43 AC39 ...", "AC1 IPD Off" | pass |
| 66 | Slow network: SAH-36 loading / Try again; no previous user's setup after sign-out and sign-in | E2E "SAH-40 a 403 ... clears everything" (2 pages), "AC13 ... Try again". The sign-out part is the SAH-40 clearing, tested by the angular unit tests; not separately driven in the browser | partial (see note) |

Note on AC66: the "previous user" part is only exercised through the 403-clears-the-page tests (SAH-40 pattern). A full sign-out / sign-in in the browser is not driven here because the API is mocked per test (every test starts with a new context and so cannot show a previous user).

## Cases that the stories do not number (negative, role, tenant isolation, boundary, security)

| Case | Test | Result |
| --- | --- | --- |
| Another clinic: cannot read, save, discard, publish, and cannot name the other clinic's row | CardSetupIT.anotherClinicsSetupDraftAndVersionsAreInvisibleAndCannotBeChanged | pass |
| RLS: both tables FORCE RLS, only the declared policies; with no clinic bound nothing is visible or writable | CardSetupStoredIT.bothTablesHaveForcedRowLevelSecurityAndOnlyTheAppPoliciesOfTheDesign; withNoClinicBoundNothingIsVisible... | pass |
| Append-only versions (update, delete, truncate refused for everyone); column-level insert grants; draft cannot move to another clinic | CardSetupStoredIT.theVersionTableIsAppendOnly...; theDraftTableAllowsReplaceAndDelete... | pass |
| CHECK constraints and the unique version key as backstops | CardSetupStoredIT.theCheckConstraintsAndTheUniqueVersionKeyRefuse... | pass |
| 400 with a problem body: every validator rule, wrong JSON shape, unknown parameter `cardFacts=bogus` | CardSetupIT.everyRuleOfTheValidator...; aBodyThatIsNotTheDeclaredShape...; CardFactsIT.aRoleWithoutTheListGets403AndAnInvalidFactsParameterIs400 | pass |
| Boundary: label of 40 characters (not bytes), 41 refused; at most 50 versions listed, v1 kept | CardSetupIT.theLabelLimitIs40Characters...; theVersionListHoldsAtMost50Rows... | pass |
| Tile mark band edges (SpO2 91/92, pulse 40/41/50/51/90/91/130/131, RR 8/9/11/12/20/21/24/25, temp 35.0/35.1/36.0/36.1/38.0/38.1, systolic 90/91/110/111/219/220, diastolic never marked) | CardFactsIT.theTileMarksFollowTheSah30Points... | pass |
| Role read again from the membership: a nurse moved to front desk loses the clinical facts at once; an owner token of a demoted owner gets 403 | CardFactsIT.factsSwitchedOn...; cardFactsAll... | pass |
| Front desk raw JSON holds no clinical key even with every fact switched on | CardFactsIT.factsSwitchedOn...; AdmittedCardsIT.aFrontDeskAnswer...; ...afterAMerge | pass |
| A label with markup is kept as typed | CardSetupIT.theLabelLimitIs40Characters...MarkupIsKeptAsTyped; E2E "AC31" | pass |
| Revision restarts at 1 (the "revision hole") | CardSetupStoredIT.knownGapAStaleEditorAtRevision1CanOverwrite... (documents the gap, passes while the gap exists); aStaleEditorIsRefusedWhenTheRecreatedDraftHasAnotherBaseVersion... | pass after the fix pass: flipped to `CardSetupStoredIT.aStaleEditorHoldingTheOldRevisionIsRefused...` (409 on Save draft and Publish) (D3 closed) |
| Query plans of the section 8 statements on a clinic of 300 admitted patients | CardSetupPlansIT (output below) | pass |

## Existing tests changed

`AdmittedCardsIT`: three assertions (the java-developer's list: lines 74, 117, 167) broke because the list answer now carries `cardSetup`, `card.deposit` and `card.visibleGroups`.
- The key set of `card` now also expects `deposit` and `visibleGroups`.
- The two front-desk raw-JSON checks no longer search the whole text for the forbidden words: the published setup names sections and fields (`"clinical"`, `"allergies"`, ...) which are setup metadata, not patient data. New helper `assertNoClinicalForFrontDesk` checks (a) every forbidden key is absent from `items` and (b) from the whole answer without `cardSetup`, (c) the groups of the front desk are exactly `patient, contact, billing`, (d) no row has `clinical`. The privacy intent is unchanged and is stronger than before (it also checks the groups); the dedicated front-desk check with every fact switched on is in CardFactsIT.

`frontend/e2e` (existing SAH-31/36 specs and shared mocks, changed by design; nothing weakened):
- Found by the first re-run of the existing IPD specs: `admitted-cards.spec.ts` had 32 failing runs (16 tests x 2 projects). Cause: the mocked list answer of the old specs had no `cardSetup`, so the new Admitted page (correctly, AC61) showed "Could not load the card setup. Showing the standard card." and drew the fallback card, which has no identity line. The real server always sends `cardSetup`, `card.deposit` and `card.visibleGroups`.
- Fix (test side): `sah36-list.ts` `abilitiesOf` gains `deposit` and `visibleGroups`, new `standardCardSetup()` (the Sahaj standard card v1, resolved by `sah37-world.ts`), `upgradeList` and `sah31-world.ts` `listOf` send `cardSetup`. That fixed 30 of the 32.
- Two tests needed their expectation changed because the design changed (DEC-027): "the card shows, in order ..." now also expects the locked `Allergies` label last in the details; "the answer holds no clinical key" now removes `cardSetup` (section and field names are setup text) before it searches the forbidden keys, and also asserts `card.visibleGroups` is `patient, contact, billing` for the front desk. The privacy intent is kept.

## Defects found in the first run (all three CLOSED by the fix pass and re-tested on 9 Nov 2026, 20:45 to 21:50: D1 by `PatientLookupPort.mobilesOf`, D2 by the order-independent fields comparison, D3 by the clock-based revision token)

| ID | Owner | Where | What |
| --- | --- | --- | --- |
| D1 (Medium) | java-developer | `backend/src/main/java/com/sahaj/patient/application/PatientLookup.java:51` (`findAllCurrent` passes `null` for the mobile); read by `backend/src/main/java/com/sahaj/ipd/application/AdmittedCards.java:109` and `:119`; row built at `AdmissionService.java:307` | With Mobile switched on, `GET /admissions` sends `"mobile": null` to OWNER and FRONT_DESK, and `cardFacts=all` too, so the Mobile field of the card and of the owner's preview is always empty. The architect's note (SAH-37-patient-card-setup.md line 10: "`PatientSummary` already carries `mobile`; `findAllCurrent` exists") and ADR-015 D6 expect the number; ADR-013 D15 made `findAllCurrent` return no mobile. Expected per the contract: the mobile of the patient (merged patient resolved as for the name), for OWNER and FRONT_DESK only, only when Mobile is shown. Failing test: CardFactsIT.theMobileValueIsTheNumberOfThePatientForOwnerAndFrontDeskWhenMobileIsOnAndOfferedToOwnerInThePreview (line 218). Care for the fix: give the number only to the caller that asks (`wantMobile`), so no other module's use of `findAllCurrent` starts to carry a mobile |
| D2 (Low) | angular-developer | `frontend/src/app/features/ipd/card/card-setup.logic.ts:64-67` (`changedParts`, the `fields` comparison) | The unsaved-edit pill compares `sections.map(x => x.fields)` in section order, so moving a section alone counts as two changes ("sections" and "fields"). The server (`CardSetupCanonical.changedParts`) compares the field placement independent of the order of the sections (design note section 5), so it says one change, and after Save draft the pill jumps from "2 changes" to "1 change". Expected: one change. Failing test: E2E "AC3 WITNESS: moving a section alone is one change" (both projects) |
| D3 (Low, already known) | java-developer | draft revision restarts at 1 (ADR-015 D5; docs/database/SAH-37.md section 7) | Result recorded in the "revision-restart hole" section below: the hole is real and reachable |

## The revision-restart hole (result)

Question: can a stale editor holding (base_version 1, revision 1) overwrite a draft that another owner discarded and recreated on the same base?
Result: **yes, for both Save draft and Publish.** CardSetupStoredIT.knownGapAStaleEditor... shows that owner A (stale, base 1, revision 1) saves with HTTP 200, B's layout is overwritten and the revision becomes 2; and that A's Publish from the stale pair publishes v2 and deletes B's recreated draft without telling B. The service also compares `base_version`, and that closes the case where the recreated draft has another base (CardSetupStoredIT.aStaleEditorIsRefusedWhenTheRecreatedDraftHasAnotherBaseVersion... passes: 409 and nothing changes). Severity Low (needs two owners, a discard and a re-save in between, same base). Fix idea for the java-developer: never restart the revision (a sequence or the last revision kept in a tombstone), or a draft id. The known-gap test is written to go red when the hole is fixed, so it must then be turned into the refusing assertion.

## Query plans (docs/database/SAH-37.md section 8), real output

Run by CardSetupPlansIT as `sahaj_app` with `app.tenant_id` set, on `sahaj_test`, one clinic of 300 admitted admissions with 3,979 vitals rows (5 to 20 per admission), 2 published versions and 1 draft, after `analyze`. The test prints the whole report to the test log and to `backend/target/sah37-explain.txt` (that file was lost when `target` was rebuilt at 17:38; the text below is copied from the log of the `validate backend` run that started at 17:19, nothing edited except that the second planner setting is reduced to its execution time). Execution times (default settings; one cold-ish run):

| Statement | Plan (default settings) | Buffers | Execution |
| --- | --- | --- | --- |
| R7 live setup: `order by version desc limit 1` | Index Scan Backward using `uq_card_setup_version_tenant_version`, Index Cond tenant_id | 3 | 0.092 ms |
| Versions list `limit 50` | Bitmap Index Scan on `uq_card_setup_version_tenant_version`, then Sort (2 rows) | 6 | 0.115 ms |
| Draft read | Index Scan using `card_setup_draft_pkey`, 1 row | 2 | 0.177 ms |
| R4 latest readings of the admitted (lateral, five readings, `recorded_by`) | Bitmap Heap Scan on admission via `ux_admission_tenant_patient_admitted` (300 rows), then per admission an Index Scan using `ix_vitals_tenant_admission_recorded` (loops=300, Incremental Sort, Limit 1); no Seq Scan on vitals | 1,227 | 3.096 ms |
| R1 admitted rows with placement (with `a.reason`) | Hash Join admission / bed / ward, Bitmap Index Scan `ux_admission_tenant_patient_admitted`, `ix_bed_tenant_ward`, `uq_ward_tenant_id`, `uq_room_tenant_id`; the deposit sum is a per-row Index Scan `ix_deposit_tenant_admission` (loops=300); Sort; no Seq Scan on admission | 1,237 | 4.415 ms |
| `GET /admissions`, owner, every fact on, 300 rows (MockMvc) | one answer, 305,331 bytes | | 129 ms |
| the same for a nurse (no mobile) | 301,114 bytes | | 55 ms |

Reading: every new read is a single index probe; the readings query is one probe per admitted patient (300 loops, about 4 buffers each) with no scan of the vitals table; the plan did not change with `enable_seqscan = off` (execution 0.124, 0.175, 0.143, 3.075 and 4.196 ms). This agrees with the expected plans in section 8. The `Result ... One-Time Filter` nodes are the row-level-security policy evaluated once per query. (An earlier draft of this plan named the index `ux_admission_tenant_bed_admitted`; that was wrong, the log says `ux_admission_tenant_patient_admitted`.)

Real output, as the test printed it:

### R7 live card setup (latest version)
    select version, cast(setup as text) as setup, is_standard, array_to_string(changed_parts, ',') as changed_parts, published_by, published_at
    from ipd.card_setup_version
    where tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid
    order by version desc
    limit 1
default planner settings:
```
Limit  (cost=0.27..6.20 rows=1 width=93) (actual time=0.060..0.061 rows=1 loops=1)
  Buffers: shared hit=3
  ->  Result  (cost=0.27..12.14 rows=2 width=93) (actual time=0.059..0.060 rows=1 loops=1)
        One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
        Buffers: shared hit=3
        ->  Index Scan Backward using uq_card_setup_version_tenant_version on card_setup_version  (cost=0.27..12.12 rows=2 width=1836) (actual time=0.024..0.024 rows=1 loops=1)
              Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
              Buffers: shared hit=3
Planning:
  Buffers: shared hit=149
Planning Time: 3.101 ms
Execution Time: 0.092 ms
```
with enable_seqscan = off the plan is the same; Execution Time: 0.124 ms


### Versions list (limit 50)
    select version, is_standard, array_to_string(changed_parts, ',') as changed_parts, published_by, published_at
    from ipd.card_setup_version
    where tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid
    order by version desc
    limit 50
default planner settings:
```
Limit  (cost=11.25..11.26 rows=2 width=61) (actual time=0.061..0.062 rows=2 loops=1)
  Buffers: shared hit=6
  ->  Sort  (cost=11.25..11.26 rows=2 width=61) (actual time=0.060..0.061 rows=2 loops=1)
        Sort Key: version DESC
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=6
        ->  Result  (cost=4.30..11.24 rows=2 width=61) (actual time=0.042..0.043 rows=2 loops=1)
              One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
              Buffers: shared hit=3
              ->  Bitmap Heap Scan on card_setup_version  (cost=4.30..11.24 rows=2 width=51) (actual time=0.026..0.026 rows=2 loops=1)
                    Recheck Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                    Heap Blocks: exact=1
                    Buffers: shared hit=3
                    ->  Bitmap Index Scan on uq_card_setup_version_tenant_version  (cost=0.00..4.28 rows=2 width=0) (actual time=0.017..0.017 rows=2 loops=1)
                          Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                          Buffers: shared hit=2
Planning:
  Buffers: shared hit=136
Planning Time: 2.452 ms
Execution Time: 0.115 ms
```
with enable_seqscan = off the plan is the same; Execution Time: 0.175 ms


### Draft read
    select cast(setup as text) as setup, base_version, revision, saved_by, saved_at
    from ipd.card_setup_draft
    where tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid
default planner settings:
```
Result  (cost=0.14..8.16 rows=1 width=68) (actual time=0.066..0.067 rows=1 loops=1)
  One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
  Buffers: shared hit=2
  ->  Index Scan using card_setup_draft_pkey on card_setup_draft  (cost=0.14..8.16 rows=1 width=1857) (actual time=0.022..0.023 rows=1 loops=1)
        Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=117
Planning Time: 2.357 ms
Execution Time: 0.177 ms
```
with enable_seqscan = off the plan is the same; Execution Time: 0.143 ms


### R4 latest readings of the admitted (ADR-013 R4 with the five readings and recorded_by)
    select v.admission_id, v.ews_score, v.ews_risk, v.recorded_at, v.bp_systolic, v.bp_diastolic, v.pulse,
           v.spo2, v.temp_c, v.rr, v.recorded_by
    from ipd.admission a
    cross join lateral (
        select x.admission_id, x.ews_score, x.ews_risk, x.recorded_at, x.bp_systolic, x.bp_diastolic,
               x.pulse, x.spo2, x.temp_c, x.rr, x.recorded_by
        from ipd.vitals x
        where x.tenant_id = a.tenant_id and x.admission_id = a.id
        order by x.recorded_at desc, x.id desc
        limit 1) v
    where a.tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid and a.status = 'ADMITTED'
default planner settings:
```
Result  (cost=18.78..2766.83 rows=247 width=62) (actual time=0.164..3.014 rows=300 loops=1)
  One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
  Buffers: shared hit=1227
  ->  Nested Loop  (cost=18.78..2766.83 rows=247 width=62) (actual time=0.157..2.965 rows=300 loops=1)
        Buffers: shared hit=1227
        ->  Bitmap Heap Scan on admission a  (cost=10.33..671.02 rows=247 width=32) (actual time=0.078..0.164 rows=300 loops=1)
              Recheck Cond: ((tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid) AND (status = 'ADMITTED'::text))
              Heap Blocks: exact=8
              Buffers: shared hit=13
              ->  Bitmap Index Scan on ux_admission_tenant_patient_admitted  (cost=0.00..10.27 rows=247 width=0) (actual time=0.062..0.063 rows=300 loops=1)
                    Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                    Buffers: shared hit=5
        ->  Limit  (cost=8.44..8.46 rows=1 width=78) (actual time=0.009..0.009 rows=1 loops=300)
              Buffers: shared hit=1214
              ->  Incremental Sort  (cost=8.44..8.49 rows=2 width=78) (actual time=0.009..0.009 rows=1 loops=300)
                    Sort Key: x.recorded_at DESC, x.id DESC
                    Presorted Key: x.recorded_at
                    Full-sort Groups: 300  Sort Method: quicksort  Average Memory: 25kB  Peak Memory: 25kB
                    Buffers: shared hit=1214
                    ->  Result  (cost=0.41..8.43 rows=1 width=78) (actual time=0.007..0.008 rows=2 loops=300)
                          One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = a.tenant_id)
                          Buffers: shared hit=1205
                          ->  Index Scan using ix_vitals_tenant_admission_recorded on vitals x  (cost=0.41..8.43 rows=1 width=78) (actual time=0.004..0.005 rows=2 loops=300)
                                Index Cond: ((tenant_id = a.tenant_id) AND (admission_id = a.id))
                                Buffers: shared hit=1205
Planning:
  Buffers: shared hit=367
Planning Time: 8.594 ms
Execution Time: 3.096 ms
```
with enable_seqscan = off the plan is the same; Execution Time: 3.075 ms


### R1 admitted rows with placement (with a.reason)
    select a.id, a.ip_number, a.patient_id, a.bed_id, b.code as bed_code, b.category_key,
           w.name as ward_name, a.doctor_membership_id, a.admission_type, a.payer, a.reason, a.status,
           a.admitted_at, a.discharged_at,
           coalesce((select sum(d.amount_paise) from ipd.deposit d
                     where d.tenant_id = a.tenant_id and d.admission_id = a.id), 0) as deposit_total,
           b.ward_id as ward_id, r.name as room_name
    from ipd.admission a
    join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
    join ipd.ward w on w.tenant_id = b.tenant_id and w.id = b.ward_id
    left join ipd.room r on r.tenant_id = b.tenant_id and r.id = b.room_id
    where a.tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid and a.status = 'ADMITTED'
    order by a.admitted_at desc, a.id
default planner settings:
```
Sort  (cost=2682.54..2683.13 rows=233 width=202) (actual time=4.164..4.185 rows=300 loops=1)
  Sort Key: a.admitted_at DESC, a.id
  Sort Method: quicksort  Memory: 85kB
  Buffers: shared hit=1237
  ->  Result  (cost=31.39..2673.38 rows=233 width=202) (actual time=0.674..3.841 rows=300 loops=1)
        One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
        Buffers: shared hit=1231
        ->  Nested Loop Left Join  (cost=31.39..704.53 rows=233 width=186) (actual time=0.607..1.131 rows=300 loops=1)
              Join Filter: (r.id = b.room_id)
              Buffers: shared hit=31
              ->  Hash Join  (cost=30.96..692.59 rows=233 width=209) (actual time=0.563..0.931 rows=300 loops=1)
                    Hash Cond: (a.bed_id = b.id)
                    Buffers: shared hit=27
                    ->  Bitmap Heap Scan on admission a  (cost=10.33..671.02 rows=247 width=139) (actual time=0.109..0.221 rows=300 loops=1)
                          Recheck Cond: ((tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid) AND (status = 'ADMITTED'::text))
                          Heap Blocks: exact=8
                          Buffers: shared hit=13
                          ->  Bitmap Index Scan on ux_admission_tenant_patient_admitted  (cost=0.00..10.27 rows=247 width=0) (actual time=0.090..0.091 rows=300 loops=1)
                                Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                                Buffers: shared hit=5
                    ->  Hash  (cost=16.88..16.88 rows=300 width=86) (actual time=0.365..0.366 rows=300 loops=1)
                          Buckets: 1024  Batches: 1  Memory Usage: 44kB
                          Buffers: shared hit=14
                          ->  Nested Loop  (cost=0.83..16.88 rows=300 width=86) (actual time=0.091..0.253 rows=300 loops=1)
                                Buffers: shared hit=14
                                ->  Index Scan using uq_ward_tenant_id on ward w  (cost=0.41..8.43 rows=1 width=44) (actual time=0.043..0.044 rows=1 loops=1)
                                      Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                                      Buffers: shared hit=4
                                ->  Index Scan using ix_bed_tenant_ward on bed b  (cost=0.42..8.44 rows=1 width=74) (actual time=0.045..0.158 rows=300 loops=1)
                                      Index Cond: ((tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid) AND (ward_id = w.id))
                                      Buffers: shared hit=10
              ->  Materialize  (cost=0.41..8.44 rows=1 width=41) (actual time=0.000..0.000 rows=1 loops=300)
                    Buffers: shared hit=4
                    ->  Result  (cost=0.41..8.43 rows=1 width=41) (actual time=0.036..0.037 rows=1 loops=1)
                          One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                          Buffers: shared hit=4
                          ->  Index Scan using uq_room_tenant_id on room r  (cost=0.41..8.43 rows=1 width=41) (actual time=0.030..0.031 rows=1 loops=1)
                                Index Cond: (tenant_id = '1e80c04b-dc4e-4a49-a098-6f62ed528536'::uuid)
                                Buffers: shared hit=4
        SubPlan 1
          ->  Aggregate  (cost=8.44..8.45 rows=1 width=32) (actual time=0.008..0.008 rows=1 loops=300)
                Buffers: shared hit=1200
                ->  Result  (cost=0.41..8.43 rows=1 width=8) (actual time=0.007..0.007 rows=1 loops=300)
                      One-Time Filter: ((NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid = a.tenant_id)
                      Buffers: shared hit=1200
                      ->  Index Scan using ix_deposit_tenant_admission on deposit d  (cost=0.41..8.43 rows=1 width=8) (actual time=0.003..0.003 rows=1 loops=300)
                            Index Cond: ((tenant_id = a.tenant_id) AND (admission_id = a.id))
                            Buffers: shared hit=1200
Planning:
  Buffers: shared hit=670 read=8
Planning Time: 69.617 ms
Execution Time: 4.415 ms
```
with enable_seqscan = off the plan is the same; Execution Time: 4.196 ms



## Results

Updated at the end of the run; see the handoff block in `docs/handoffs/SAH-37.md` and the final report. (Filled in below.)

### Resume 2 (9 Nov 2026, from 17:44)

- **CardFactsIT failure (a)** (`factsSwitchedOnAreSentToTheRoles...`, reported at line 197 by the 12:08 run): a **test mistake**, already corrected by the 12:09 edit of the test (the comment above the last Mobile assertion states the rule the product follows: the token role and the membership role must both be a contact-group role, so a nurse moved to the front desk gets no Mobile until she signs in again). In the `validate backend` run that started at 17:19 the test passes (CardFactsIT: 11 run, 1 failure, and the one failure is D1).
- **CardFactsIT failure (b)** (the owner's mobile was "" instead of the number, line 218): a **product defect**, D1 above, confirmed again: `PatientLookup.findAllCurrent` still builds `PatientSummary` with a `null` mobile (file unchanged since 7 Nov), `AdmittedCards.patients` copies that `null`. The test stays red, product code is not touched.


### Backend run (`node scripts/validate.mjs backend`, started 17:19, finished 18:18)

| Number | Value |
| --- | --- |
| Integration tests run (failsafe, whole project) | 1,756 |
| Failures | 5 |
| Errors | 2 |
| Skipped | 26 (the existing precondition skips, none of them SAH-37) |
| SAH-37 integration tests in that run | CardSetupIT 26, CardSetupStoredIT 10, CardFactsIT 11, CardSetupPlansIT 1 (48 new) and AdmittedCardsIT 13 (changed) |

Of the 7 not-passing results, **1 is a real product defect (D1)** and **6 are a side effect of the run itself**: at 17:38 somebody started the dev server (`mvn spring-boot:run`, now the process on port 8080) while this run was still in progress, and that rebuilt the `backend/target` folder under the running tests. `DoseWithoutPepperIT` (2 errors: "class path resource TenantProbeController.class does not exist") and `PackagedJarDevToolsIT` (4 failures: "The packaged jar does not exist") are existing SAH-32 / SAH-8 tests that touch nothing of SAH-37. Re-run alone at 18:2x with the backend lock (`mvn verify -Dit.test=DoseWithoutPepperIT,PackagedJarDevToolsIT,CardFactsIT`): DoseWithoutPepperIT 2 of 2 pass, PackagedJarDevToolsIT 5 of 5 pass, CardFactsIT 11 run and 1 failure (D1 again, the same assertion at line 218, "[owner] expected 7811189799 but was empty"). The files of the first run in `backend/target/failsafe-reports` for the classes that ran before 17:38 were lost with the folder; the failure list above is taken from the Maven log, which prints failures only.

Verdict of the backend gate: **not green, because of D1 only.** Nothing else fails.

### Browser run (production build, mocked API), 18:20 to 18:48

| Run | Tests | Result |
| --- | --- | --- |
| `patient-card-setup.spec.ts`, run 1 of 3 (phone-360 and desktop-1440, 4 workers) | 126 | 121 passed, 3 skipped by design, 2 failed (D2 witness, both sizes) |
| run 2 of 3 | 126 | same: 121 / 3 / 2 |
| run 3 of 3 | 126 | same: 121 / 3 / 2 |
| existing IPD specs once (admitted-cards, nursing, safety, mar, medicine-round, discharge, bed-transfer, emergency; both sizes) | 1,104 | 1,104 passed, 0 failed (20.5 min) |

The 3 skips are the same in every run: the mouse drag at 360 px (a phone uses the arrows), "AC20 AC54 the published Cards per row is the opening grid" at 360 px (a phone shows one card per row whatever is published, AC64), and "AC64 a phone shows one card per row" at 1440 px (phone only). The two failures are the same test, "AC3 WITNESS", in all three runs: no flake was seen in the new spec, so no test-side change was needed in this resume.

### Gate (9 Nov 2026, 18:20 to 19:00)

| Check | Result |
| --- | --- |
| `node scripts/validate.mjs backend` (17:19 run) plus the targeted re-run | 1,756 integration tests; 1 real failure (D1, `CardFactsIT` line 218); the other 6 not-passing results pass on re-run (see above). **Not green because of D1.** |
| `npm --prefix frontend test -- --watch=false` | first run 18:48: 4 failed, 2,403 passed, all four in `admitted.model.spec.ts` (`cardFlags` and `foldFlags`: the shared fixture `ROW` came back with an escalation and EWS 8 "High"; no source file of that spec or fixture changed since 10:38, and the 17:17 run of the same code passed). Re-run 18:56: **163 files, 2,411 tests, all passed.** Looks like an order-dependent shared fixture in `admitted.fixtures.ts`; recorded for the angular-developer as a possible flake, not a SAH-37 failure |
| `npm --prefix frontend run lint` | passed ("All files pass linting") |
| `npm --prefix frontend run build` | passed (18:20) |
| `node scripts/validate.mjs frontend` | **fails, known and not a code failure**: its test step passes `--browsers=ChromeHeadless`, and the Angular Vitest builder refuses it ("The browsers option requires @vitest/browser-playwright ..."). `scripts/` is human-owned; lint passes in that run, and the same tests pass without the flag |
| `node scripts/validate.mjs docs` | **fails, known**: `docs/stories/ADM-2.md: name files by story ID, e.g. SAH-12.md` (an old file, not SAH-37) |

### Review items that touch QA (from `docs/reviews/SAH-37-code.md` and `SAH-37-security.md`)

- Code review #13 / security finding 2: after the java-developer makes the draft revision never repeat, flip `CardSetupStoredIT.knownGapAStaleEditorAtRevision1CanOverwrite...` into the refusing assertion (the test is written to go red when the hole closes).
- Security finding 4 (a separate port method for mobiles) is also the fix for D1. When it is in, `CardFactsIT.theMobileValueIsTheNumberOf...` should go green with no change; the nurse and doctor assertions (no `mobile` key, no number in the raw JSON) must stay green.
- Security finding 5 (Low, backlog, QA-owned): `frontend/e2e/sah37-world.ts` (lines 229-277) and `patient-card-setup.spec.ts` use Indian personal names and 10-digit mobile numbers in a valid series. They are mocked and follow the convention of the other worlds; to be replaced by clearly fictional names and numbers that cannot be dialled in all e2e worlds in one later pass (not changed now, so the green runs above stay valid).

## Re-run after the fix pass (9 Nov 2026, from 20:37)

Changes in the product that the tests follow: the draft revision is an opaque clock-based token (about 1.79e15, never repeats, a save equal to the stored draft keeps it); the 403 text of every card-setup operation and of `cardFacts` is the shared "You do not have access to this."; `PatientLookupPort.mobilesOf` gives the Mobile value; the publish history line carries `factsOn` (keys of the sensitive facts the card switches on).

### Test changes (qa-engineer, `backend/src/test/java/com/sahaj/platform/it/`)

| Change | Where |
| --- | --- |
| The known-gap test is flipped: after a discard and re-create on the same base, a stale editor gets 409 `card-setup-changed` on Save draft and on Publish, nothing is stored, the other owner's draft is intact, the current token is accepted, and the token never repeats over four more discard-and-recreate cycles | `CardSetupStoredIT.aStaleEditorHoldingTheOldRevisionIsRefusedOnSaveAndPublishAfterADiscardAndRecreateOnTheSameBase` (renamed from `knownGap...`) |
| The "another base version" test reads the stale token from the answer | `CardSetupStoredIT.aStaleEditorIsRefusedWhenTheRecreatedDraftHasAnotherBaseVersion...` |
| Ten tests that hard-coded revision 1, 2, 3 now read the token from the previous answer (`revisionOf(answer)` in `CardSetupSupport`); counts became "a new token" (greater than the old one) | `CardSetupIT` (save draft, equal-to-live, discard, publish, stale revision, discarded by another owner, other owner publishes, label limit, locked fields, history lines) and `CardSetupStoredIT` (repair on read, update of the draft) |
| Every 403 check of the card setup and of `cardFacts=all` now also asserts the shared text (new helper `forbiddenOf`) | `CardSetupIT` (owner-only writes, list roles, Read-only role order), `CardFactsIT` |
| The publish history line has the extra key `factsOn` (empty for the standard card) | `CardSetupIT.saveDraftAndPublishWriteOneHistoryLine...` |
| New: the publish line names the sensitive facts as keys only, in catalogue order (diagnosis, bp, mobile), never a label (security finding 1b) | `CardSetupIT.thePublishHistoryLineNamesTheSensitiveFactsSwitchedOnAsKeysOnly` |
| New: saving a draft equal to the stored draft keeps the token, the saver and the time and writes no second history line, also for a second owner (security finding 3) | `CardSetupIT.savingAnUnchangedDraftAgainKeepsTheTokenAndWritesNoSecondHistoryLine` |

### Targeted integration run (backend lock held, 20:38 to 20:45)

`mvn verify -Dit.test=CardFactsIT,CardSetupStoredIT,CardSetupIT,AdmittedCardsIT`: **62 run, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.** AdmittedCardsIT 13, CardFactsIT 11, CardSetupIT 28 (26 + 2 new), CardSetupStoredIT 10.
- **D1 closed:** `CardFactsIT.theMobileValueIsTheNumberOfThePatientForOwnerAndFrontDeskWhenMobileIsOnAndOfferedToOwnerInThePreview` passes; the nurse and doctor assertions (no `mobile` key, no number in the raw JSON) stay green.
- **D3 closed:** the revision-restart hole is shut for Save draft and for Publish.

### Full backend gate (`node scripts/validate.mjs backend`, 20:41 to 21:21, backend lock held)

**Exit 0.** Unit tests 3,495 run, 0 failed. Integration tests 1,758 run, 0 failures, 0 errors, 26 skipped (the existing precondition skips, none of them SAH-37). The 1,758 are the 1,756 of the earlier run plus the 2 new CardSetupIT tests. CardSetupPlansIT (1) ran and passed. No class-not-found or rebuild corruption this time (the human's dev server on port 8080 did not rebuild `backend/target`).

### Browser runs after the fix pass (production build rebuilt 21:23, frontend lock held, mocked API)

| Run | Tests | Result |
| --- | --- | --- |
| "AC3 WITNESS: moving a section alone is one change" (both projects) | 2 | 2 passed: **D2 closed** |
| `patient-card-setup.spec.ts`, run 1 of 3 (phone-360 and desktop-1440) | 126 | 123 passed, 3 skipped by design, 0 failed |
| run 2 of 3 | 126 | 123 / 3 / 0 |
| run 3 of 3 | 126 | 123 / 3 / 0 |
| `work-area-width.spec.ts`, 3 runs (project desktop-1440; the phone-360 copies skip by design) | 8 per run | 8 passed, 8 skipped, 0 failed, three times |
| `layout-matrix.spec.ts`, project layout-1920 | 12 | 12 passed (it did not depend on the old centred layout) |
| `layout-matrix.spec.ts`, all 7 projects | 84 | 84 passed |

The 3 skips in patient-card-setup are the same as before (mouse drag at 360 px, the opening grid at 360 px, the phone-only test at 1440 px). The Save draft / Discard steps of the existing specs were re-checked against the angular-developer's change (Save draft disabled when nothing is to be saved): they pass.

Mutation check of the new width spec (not committed): the old rule `max-width: 1680px; margin: 0 auto` was injected into a temporary copy of the spec. The 1920 and 2560 tests and the "never a centred column" test failed, and the 1440 and 1680 tests passed, as designed. So the width spec does detect the bug.

### Existing IPD browser specs, once (21:30 to 21:50)

`admitted-cards`, `safety`, `nursing`, `emergency`, `medicine-round`, `mar`, `discharge`, `bed-transfer` at 360 and 1440 px: **1,104 run, 1,104 passed, 0 failed, 0 skipped** (18.3 min).

### Frontend (frontend lock held)

| Check | Result |
| --- | --- |
| `npm --prefix frontend test -- --watch=false` | 165 files, **2,439 tests passed**, 0 failed (the earlier flake in `admitted.model.spec` did not appear) |
| `npm --prefix frontend run lint` | passed |
| `npm --prefix frontend run build` | passed |
| `validate frontend` / `validate docs` | not re-run in this pass; the known script flag and the ADM-2.md file name are not code failures (see above) |

### Coverage of the fix pass: finding to test to result

| Finding | Test | Result |
| --- | --- | --- |
| D1, security 4 (Mobile value) | CardFactsIT.theMobileValueIsTheNumberOfThePatientForOwnerAndFrontDeskWhenMobileIsOnAndOfferedToOwnerInThePreview (nurse and doctor: no key, no number) | pass |
| D2 (section move is one change) | E2E "AC3 WITNESS" (2 projects) | pass |
| D3, code 1, security 2 (revision never repeats) | CardSetupStoredIT.aStaleEditorHoldingTheOldRevisionIsRefusedOnSaveAndPublishAfterADiscardAndRecreateOnTheSameBase; ...AnotherBaseVersion... | pass |
| Contract A1 (shared 403 text) | CardSetupIT / CardFactsIT `forbiddenOf` on every write, list and `cardFacts=all` 403 | pass |
| Security 1b (`factsOn`) | CardSetupIT.thePublishHistoryLineNamesTheSensitiveFactsSwitchedOnAsKeysOnly; ...WriteOneHistoryLineEach... | pass |
| Security 3 (no history line for an unchanged draft) | CardSetupIT.savingAnUnchangedDraftAgainKeepsTheTokenAndWritesNoSecondHistoryLine | pass |
| Code 3 (Save draft disabled when nothing to save) | patient-card-setup.spec.ts (existing Save draft and Discard steps) | pass |
| Code 11 (inert preview) | E2E "AC47 AC51 the preview card is inert" | pass |

### Notes

- The browser test of the revision token uses the mock server of `sah37-world.ts`, whose draft revision still counts 1, 2, 3. That is fine for what the browser tests prove (the screen sends back what it received). The angular-developer added unit specs with 1790000000000001; the large-number path is not driven in a browser here.
- Security finding 5 (fictional names in the e2e data) is still a backlog item and untouched.
