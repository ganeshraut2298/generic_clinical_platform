# Test plan · SAH-78 Pharmacy & Inventory Phase 1 Masters · WP-A (78-1 to 78-4) and WP-B (78-5, 78-6, 78-7)

Author: qa-engineer · Date: 18 Nov 2026 (WP-A), updated 18 Nov 2026 for the WP-A re-check, F-11 and WP-B, and again for review fix round 2 (DEC-071, sections 18 to 20) · Branch: `feature/SAH-39-home-kinetic-background` (DEC-061 Answer 2, additive only)
Mockup screen: P&I mockup 16 "Setup: stores, staff, products, vendors" (`docs/handoffs/PHI-input/screens/16-*.html`), plus the NEW Unit settings card of 78-2 and the NEW Opening stock section of 78-7.
Sources: `docs/stories/SAH-78.md`, `docs/api/pharmacy.yaml` 0.1.3 (0.1.3 is 0.1.2 plus the additive 409 open-import and the line-request cap; 0.1.2 is 0.1.1 plus wording; the contract wins where a story differs, handoff decision 4), `docs/api/platform.yaml` 0.2.0, `docs/architecture/adr/ADR-028-pharmacy-inventory-module.md` revision 5 (revision 4 for the WP-B sections), `docs/database/SAH-78-pharmacy-masters.md` sections 3.5 and 9 (and migration B4 = V2026_12_17_1250), `docs/handoffs/SAH-78.md`.

Scope of fix round 2 (sections 18 to 20): N-1/L7, N-2, L8, N-8, I4, N-13, QA-78-4, the angular items N-3 to N-7 and I2, texts, N-14. Earlier scope: WP-A re-check after review fix round 1 (sections 2 to 8 and 10 to 11), F-11 (section 12), WP-B (sections 13 to 15). WP-C (78-8, 78-9, 78-10) is a placeholder (section 9).

## 1. Where the tests are

| Layer | File | What it covers |
| --- | --- | --- |
| Backend IT (real `sahaj_test`, MockMvc through the real security chain) | `backend/src/test/java/com/sahaj/platform/it/PharmacyModuleOffIT.java` | 78-1: switch Off, additive rule (menus, role list, module list, existing endpoints) |
| | `PharmacyMenuAndContextIT.java` | 78-1 menu key and access levels, `GET /context`, working store, setup summary |
| | `PharmacyUnitSettingsIT.java` | 78-2 |
| | `PharmacyStoresIT.java` | 78-3 |
| | `PharmacyStaffMappingIT.java` | 78-4 mapping, access columns, `receiveScheduleX`, re-map, remove |
| | `PharmacyStoreStaffRoleIT.java` | 78-4 Store staff role: 403 on existing APIs, plan user limit |
| | `PharmacyStoreGuardIT.java` + `backend/src/test/java/com/sahaj/pharmacy/it/PharmacyStoreProbe.java` | ADR-028 D4 store guard on the real stack (see 5.1) |
| | `PharmacyTablesIT.java` | design note section 9 for the WP-A tables, platform additive changes, sample seed, global tables |
| | `RowLevelSecurityCatalogIT.java` (edited) | `pharmacy` added to `SCHEMAS`; 3 global tables added to the global-table checks; new check of the 20 tenant tables |
| | `Sah78Support.java` | shared set-up (module switch fixture, real people, stores, concurrency helper) |
| | `PharmacyCandidatesWithoutStoreIT.java` | F-11: `GET /staff-candidates` without `storeId` |
| | `PharmacySampleGuardIT.java` | security M1: the `afterMigrate__pharmacy_sample_catalogue_absent_guard.sql` callback |
| | `PharmacyProductCatalogueIT.java` | 78-5: search, adopt, withdrawn, local settings, preferred vendor, barcode, stop using |
| | `PharmacyOwnProductIT.java` | 78-6: own product, duplicate check, `clientKey`, `U-<unit>-nnnn` numbering, units lock, adopted product read-only |
| | `PharmacyOpeningStockIT.java` | 78-7: template, start, rows, submit, NOT_ACCEPTED, approve by another owner, self-approval, reject, REPLACED, store guard, triggers |
| | `PharmacyProductTablesIT.java` | WP-B tables: cross-tenant isolation of the 5 new tables, product, setting, import, line and issue constraints and guards |
| | `Sah78BSupport.java` | shared WP-B set-up (own product bodies, vendor rows, import rows) |
| Playwright e2e (API mocked in the browser, as every other spec) | `frontend/e2e/pharmacy-setup.spec.ts` | WP-A happy paths and one error path per story, F-11, phone-360 and desktop-1440, and the R-1 check |
| | `frontend/e2e/pharmacy-products-opening-stock.spec.ts` | WP-B: Products (adopt, local settings, create own product, duplicate warning, reader) and Opening stock (upload, Not accepted, wrong template, approve by another owner, reject) |

Fixtures: clinics, owners and team members are real (sign-up, wizard, a `platform.membership` row per person). The module switch is written as the design note says, as `sahaj_app` with the tenant bound: `insert into platform.module_entitlement (tenant_id, pharmacy_on, updated_by) values (...) on conflict (tenant_id) do update set pharmacy_on = ...` (`Sah78Support.pharmacyOn`). All names, GSTINs and licence numbers are invented.

## 2. Story 78-1 · Module entry, tabs, shell

| # | Acceptance criterion | Test (class.method or e2e title) | Type |
| --- | --- | --- | --- |
| T78-1-01 | Module on, owner or mapped person sees one "Pharmacy & Inventory" entry | `PharmacyMenuAndContextIT.theOwnerGetsOnePharmacyEntryBeforeConfigInBothMenuModes`, `.aPharmacistWithNoMappingGetsTheEntryAndAMappedNurseDoesAndAnUnmappedNurseDoesNot`; e2e "owner: one sidebar entry opens Inventory setup..." | IT, e2e |
| T78-1-02 | Module off: entry hidden, every P&I API 403 `module-off`, even for the owner | `PharmacyModuleOffIT.everyPharmacyEndpointAnswers403ModuleOffEvenForTheOwnerAndWritesNothing` (33 operations, 15 of WP-A and 18 of WP-B, x 4 callers, no row, no audit), `.anExplicitPharmacyOffRowAnswersTheSameAsNoRow`, `.theModuleSwitchIsPerClinic...`; e2e "module off: no sidebar entry..." | IT, e2e |
| T78-1-02b | Contract order: module off before path id check | `PharmacyModuleOffIT.aMalformedPathIdWithTheModuleOffAnswersModuleOffFirst...` | IT |
| T78-1-03 | Switch Off: every menu, role list, existing screen and API unchanged | `PharmacyModuleOffIT.withTheModuleOffNoExistingRoleSeesAPharmacyMenuEntry...`, `.menusOfEveryExistingRoleAreIdenticalWithTheModuleOffAndWithItOnExceptTheOneNewKey` (8 roles), `.theTeamAndAccessMatrixAndTheModuleListAreUnchanged...` (56 cells, no `pharmacy` in /modules or /me), `.aPermissionCellForStoreStaff...`, `.existingTeamMembersKeepTheirRoles...`, `.existingEndpointsAnswerAsBefore...`; e2e "R-1: <role> opening /app ... lands on Today" (see 6) | IT, e2e |
| T78-1-04 | Module on, non-owner with no mapping: no access sentence, API 403 | `PharmacyMenuAndContextIT.anUnmappedStoreStaffMemberFrontDeskAndNurseGet403ForbiddenWithTheNoAccessText...` (7 roles x context, stores, unit, summary, working store); e2e "a person mapped to no store ... no-access sentence" | IT, e2e |
| T78-1-04b | Story says an unmapped pharmacist also gets 403; contract: PHARMACY_ACCESS includes any PHARMACIST member | `PharmacyMenuAndContextIT.thePharmacistWithTwoStoresMustPick...` (unmapped pharmacist reads context). Tested to the contract; difference for the BA (section 8) | IT |
| T78-1-05 | Store staff mapped: Pharmacy & Inventory is the only entry | `PharmacyMenuAndContextIT.aStoreStaffMemberWithNoMappingHasAnEmptyMenuAndOneMappedHasOnlyPharmacy`, `PharmacyStoreStaffRoleIT.theShellStillLoads...`; e2e "Store staff member mapped to one store..." | IT, e2e |
| T78-1-06 | Only released top entry (Inventory setup, a link in the navigation "Pharmacy and inventory", not a tab); no Inventory or Pharmacy entry, no empty tab, no "coming soon" | e2e owner happy path (navigation link list, no Inventory or Pharmacy link) | e2e |
| T78-1-07 | One `GET /context`; only listed capabilities | `PharmacyMenuAndContextIT.theOwnersContextHasTheUnit...`, `.thePharmacistWithTwoStores...`, `.aMappedStoreUserWithoutSetupView...` | IT |
| T78-1-08 | Setup header, "Unit: <clinic>", section tabs: the owner has three (Stores & staff, Products, Opening stock; vendors, rules and numbering arrive with WP-C) | e2e owner happy path ("Setup", "Unit: Test Family Clinic", tablist "Setup sections" with 3 tabs) | e2e |
| T78-1-09 | Live subtitles counted by the server | `PharmacyMenuAndContextIT.theSetupSummaryCountsActiveStoresAndDistinctMappedPeopleForThisClinicOnly`; e2e "owner: a section subtitle counts stores and mapped staff" | IT, e2e |
| T78-1-10 | Pharmacist / Central Store user: read-only with "View only", API 403 on change | `PharmacyMenuAndContextIT.thePharmacistAndACentralStoreUserMayReadTheSetupButEveryChangeIs403...`; e2e "pharmacist: Inventory setup is read-only..." | IT, e2e |
| T78-1-11 | Other mapped user: no Inventory setup tab | `PharmacyMenuAndContextIT.aMappedStoreUserWithoutSetupView...`, `.aStoreUserMappedAtTheCentralStoreNotAsCentralStoreUserCannotReadTheSetup`; e2e Store staff test | IT, e2e |
| T78-1-12 | Owner, no stores: "No stores yet. Create your Central Store to start." with "+ Store" | `PharmacyStoresIT.anEmptyUnitListsNoStore...`; e2e owner happy path | IT, e2e |
| T78-1-13 | Loading and error state "Could not load. Try again." with Try again | e2e "error path: the context cannot be read..." | e2e |
| T78-1-14 | Narrow screen usable | e2e "narrow and wide screens..." (phone-360 and desktop-1440, no page-level sideways scroll) | e2e |
| T78-1-15 | "End-to-end flow" and "USP vs LocalWell" not built | e2e owner happy path | e2e |

## 3. Story 78-2 · Unit settings (`PharmacyUnitSettingsIT`, e2e)

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-2-01 | Card shows unit name (read-only), GSTIN, licence number, valid till, Integration mode, FEFO On and Negative stock Blocked as facts with no switch | `theFirstVisitShowsTheDefaultsTheFixedFactsAndWritesNothing`; e2e owner happy path (no switch, no checkbox) | IT, e2e |
| T78-2-02 | First visit: Integrated (OQ-78-14, contract default), other fields empty, a read writes no row | same test | IT |
| T78-2-03 | Request to change FEFO or negative stock is 400, nothing changes | `aRequestThatTriesToChangeFefoNegativeStockOrTheClinicNameOrCodeIs400AndNothingChanges` (7 variants, incl. tenant id and unit id in the body); e2e (the card never sends them) | IT, e2e |
| T78-2-04 | Bad GSTIN is 400 "Enter a 15-character GSTIN"; empty allowed ("Not set") | `aBadGstinIs400WithTheFieldMessageAndNothingChanges` (7 bad values), `anEmptyGstinIsAllowedAnd...`, `aValidSaveUpperCases...`; e2e "Unit settings reject a bad GSTIN on the field..." | IT, e2e |
| T78-2-05 | Licence over 40 characters or not a date is 400; a past date is allowed and shown expired | `aLicenceNumberOver40CharactersOrADateThatIsNotADateIs400ButFortyCharactersAreAccepted`, `anEmptyGstinIsAllowedAndAPastLicenceDateIsAllowedAndShownExpired` (boundary: yesterday expired, today not) | IT |
| T78-2-06 | Integration mode change: confirmation first, then saved, audited with before and after, every person's `GET /context` shows it | `theNewModeShowsInEveryPersonsContextFromThenOn...`, `aValidSaveUpperCases...` (audit rows `PHARMACY_UNIT_SETTINGS_CHANGED`, `PHARMACY_INTEGRATION_MODE_CHANGED`); e2e (confirmation text, nothing sent before Confirm, then PUT with version and mode) | IT, e2e |
| T78-2-07 | Standalone: no OPD or IPD capability (also Integrated with IPD off keeps IPD parts hidden) | `theNewModeShowsInEveryPersonsContext...` (capabilities never list OPD or IPD), `ipdAvailableFollowsTheClinicsIpdModule`; e2e standalone note | IT, e2e |
| T78-2-08 | Standalone + Integrated-only action: 409 `integration-off` | NOT TESTABLE in WP-A: no 0.1.0 operation is Integrated-only (contract general rule 5). Test with the first operation that is | n/a |
| T78-2-09 | Non-owner 403 on change; pharmacist and Central Store user may read; others 403 | `onlyTheOwnerChangesAndThePharmacistAndCentralStoreUserRead` (7 roles) | IT |
| T78-2-10 | Another clinic's settings never visible; no id in the API | `anotherClinicNeverSeesMySettingsAndHasItsOwnVersionAndDefaults` | IT |
| T78-2-11 | Two owners saving at once: second gets 409 `settings-changed` with `current` | `aStaleVersionIs409SettingsChangedWithTheCurrentValues...`, `twoOwnersSavingAtTheSameMomentOneWinsAndTheOtherGets409...` (real threads); e2e "error path: a second owner saved first" | IT, e2e |
| T78-2-12 | Audit holds changed fields with before and after | `aValidSaveUpperCases...`, `savingTheSameValuesAgainChangesNothingAndWritesNoSecondAudit` | IT |
| T78-2-13 | Contract: required fields of the PUT (`gstin`, `drugLicenceNo`, `drugLicenceValidTill`) | `aSaveThatOmitsAFieldTheContractRequiresIs400AndDoesNotSilentlyClearTheSavedValues` | IT |
| T78-2-14 | Validation: missing or unknown mode, missing or negative version, unreadable body | `aMissingOrUnknownIntegrationModeAndAMissingVersion...` | IT |

## 4. Story 78-3 · Stores (`PharmacyStoresIT`, e2e)

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-3-01 | Tree: Central Store first, others by name, type, "Issues from", staff count, Central line | `theCentralStoreComesFirstAndEveryOtherStoreIssuesFromItWithTheDocumentedDefaults`; e2e "owner: a section subtitle..." (chips and lines) and owner happy path | IT, e2e |
| T78-3-02 | Detail card fields in order Store type, Issues from, Approver (HOD), Delegate approver, Sale, Consumption | e2e "owner: a section subtitle..." (labels in order) | e2e |
| T78-3-03 | "+ Store": Name and Code first; Issues from is the Central Store only (server sets it, client cannot) | `theIssuesFromAndTheStatusCannotBeSetByTheClient`; e2e owner happy path | IT, e2e |
| T78-3-04 | No Central Store: any other store 409 "Create the Central Store first."; second Central 409 "This unit already has a Central Store." | `anotherStoreBeforeTheCentralStoreIs409...` | IT |
| T78-3-05 | New store appears under Central; Sale default Yes for Pharmacy, Consumption default Yes for Ward; explicit values win | `theCentralStoreComesFirst...` | IT |
| T78-3-06 | Empty name or code 400 "Enter the store name"; repeated name or code 409, ignoring capitals | `emptyOrBadNameCodeAndTypeAreRefusedTogether...`, `aRepeatedNameOrCodeIgnoringCapitalsIs409EvenAgainstASwitchedOffStore...`, `renamingToAnotherStoresNameOrCodeIs409...`; e2e "error path: a repeated store name keeps the form open..." | IT, e2e |
| T78-3-07 | Name over 60, code over 10 or with other characters: 400 (boundaries 60 and 10 accepted, lower case upper-cased, name trimmed) | `emptyOrBadNameCode...`, `aCodeOfExactlyTenCharacters...` | IT |
| T78-3-08 | HOD and delegate: owner, doctor, nurse, pharmacist only; unmapped person is mapped as Approver or Delegate | `anHodChosenOnTheStoreIsMappedAsApproverInTheSameStep...`, `onlyAnOwnerDoctorNurseOrPharmacistMayBeHodOrDelegate...` (5 refused roles, unknown and foreign member) | IT |
| T78-3-09 | Delegate dates: required, end not before start, same person as HOD refused (plus contract: at most 90 calendar days counting both ends, so `to - from <= 89`; not in the past) | `aDelegateNeedsDatesInOrderAtMostNinetyDaysNotInThePastAndMustBeADifferentPersonFromTheHod` (day 90 = `today + 89` accepted; `today + 90` and `today + 91` refused "A delegation can be at most 90 days") | IT |
| T78-3-10 | Delegation ended: "Delegate ended 30 Nov" | `PharmacyStaffMappingIT.aDelegateWhoseDatesHaveEndedIs...` (API state ENDED); the row text is a unit test of the screen | IT |
| T78-3-11 | No active HOD: "No approver set" | `aStoreWithNoActiveHodShowsNoApproverSetAlsoWhenTheHodLosesAccessOrIsRemoved`; e2e (pill after Central Store create) | IT, e2e |
| T78-3-12 | Approved opening stock locks type, Issues from and code | `aStoreWithStockLocksTypeAndCodeCannotBeSwitchedOffButNameAndFlagsStillChange` (has_stock set by SQL, as WP-B will do); `PharmacyTablesIT.theStoreGuardTriggerKeepsHasStockOneWay...` | IT |
| T78-3-13 | Switch off keeps history, mappings stop counting; with stock 409; Central Store only after the others; safe repeat | `switchingAStoreOffKeepsItsHistory...`, `theCentralStoreCannotBeSwitchedOffWhileAnotherStoreIsActive...` | IT |
| T78-3-14 | Pharmacist and Central Store user: read-only (contract: lists all stores of the unit; story: only their own, handoff decision 4) | `aPharmacistReadsEveryStoreNotOnlyTheMappedOnes...`, `everyChangeToStoresByANonOwnerIs403...` | IT |
| T78-3-15 | Another clinic's store 404 on every operation; malformed id 400 | `anotherClinicsStoreIs404OnEveryOperation...` | IT |
| T78-3-16 | Change audited with before and after; stale version 409 `settings-changed` with current | `changingAStoreBumpsTheVersionAuditsBeforeAndAfter...` | IT |
| T78-3-17 | Type moves to and from Central refused (`central-store-exists`, `central-store-in-use`) | `theTypeCannotMoveToOrFromTheCentralStore...` | IT |
| T78-3-18 | Concurrent: two Central Store creates; two creates of the same name | `twoCentralStoreCreatesAtTheSameMoment...`, `twoCreatesOfTheSameNameAtTheSameMoment...` | IT |
| T78-3-19 | Contract: required fields of the store PUT (`saleAllowed`, `consumptionAllowed`, `hodMembershipId`, `delegate`) | `aChangeThatOmitsFieldsTheContractRequiresIs400AndDoesNotSilentlyClearTheFlagsOrTheHod` | IT |
| T78-3-20 | New HOD demotes the old one to Store user (kept); ending a delegation keeps the person as Store user | `aNewHodDemotesTheOldOneToStoreUser...` | IT |
| T78-3-21 | Non-owner create, change, switch off: 403, nothing written, no audit | `everyChangeToStoresByANonOwnerIs403AndLeavesNothingBehind` (6 roles) | IT |

## 5. Story 78-4 · Staff mapping, Store staff role, working store

### 5.1 Store guard on the real stack

WP-A has one store-bound operation, `PUT /context/working-store`, and it needs only a mapping. No product endpoint calls `StoreAccessGuard.require(caller, storeId, capability)` yet (the first one arrives with WP-B or SAH-79), so "a mapped caller without the capability gets 403 `store-permission-denied`" cannot be reached over HTTP with product code. `PharmacyStoreProbe` (a `@TestComponent` under `src/test`, imported only by `PharmacyStoreGuardIT`) calls the real `PharmacyGate` and the real `StoreAccessGuard` in a transaction declared `noRollbackFor = StoreAccessDeniedException`, behind the real security chain, problem handler, audit and PostgreSQL. It is not part of the product API.

### 5.2 Table

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-4-01 | Store staff role offered; with the module off the list is as before | `PharmacyModuleOffIT.existingTeamMembersKeepTheirRoles...` (POST /team STORE_STAFF accepted off; the option is hidden by the screen, decision 5), `PharmacyStoreStaffRoleIT.theStoreStaffRoleIsInTheTeamList...` | IT |
| T78-4-02 | Store staff: menu limited to P&I; other modules' APIs 403 | `PharmacyStoreStaffRoleIT.aStoreStaffMemberGets403OnTheApisOfOpdBillingPatientIpdHraAndPlatform...` (17 GETs and 7 writes, unmapped and mapped, module Off and On), `PharmacyMenuAndContextIT.aStoreStaffMemberWithNoMapping...` | IT |
| T78-4-03 | Store staff maps only as Store user or Central Store user; Approver, Delegate refused 400 (no Pharmacist store role exists) | `storeStaffCanBeMappedOnlyAsStoreUserOrCentralStoreUser...`, candidates test | IT |
| T78-4-04 | No existing role changes name, menu or permissions | `PharmacyModuleOffIT.menusOfEveryExistingRole...`, `.theTeamAndAccessMatrix...` | IT |
| T78-4-05 | Plan user limit counts Store staff (OQ-78-40, BA reading) | `PharmacyStoreStaffRoleIT.storeStaffCountsTowardThePlanUserLimitAndSwitchingOneOffReleasesTheSeat` | IT |
| T78-4-06 | "+ Map staff": person, store role (Central Store user only at the Central Store), delegate dates | `candidatesCarryTheAllowedStoreRolesPerTeamRole...`, `aCentralStoreUserRoleIsOnlyForTheCentralStore...`, `aDelegateNeedsBothDatesInOrder...`; e2e "owner: ... staff are mapped" | IT, e2e |
| T78-4-07 | Row: name, Sahaj role label, "HOD, <store>", store role chip, four access columns, Other stores | `theListShowsTheHodFirstThenTheDelegateThenByName...`, `aPersonAlreadyInTheStoreIs409AndOnePersonCanBeInSeveralStores...` (otherStores); e2e (row cells) | IT, e2e |
| T78-4-08 | Access columns derived by the server, owner cannot tick them | `theAccessColumnsAreWorkedOutByTheServer...` (8 combinations, list = context = mapping answer, flag flip), `aClientCannotSendTheAccessColumns...`; e2e (no checkbox) | IT, e2e |
| T78-4-09 | `receiveScheduleX` only for the team role PHARMACIST, whatever the store role; never the owner or Store staff | `receiveScheduleXIsGivenOnlyToTheTeamRolePharmacist...` (8 team roles x store roles, approver, owner), guard test `theCapabilitiesFollow...` | IT |
| T78-4-10 | Ended delegate: "Delegate · ended 30 Nov", Approve No, remains Store user | `aDelegateWhoseDatesHaveEndedIsShownEnded...` | IT |
| T78-4-11 | Already mapped 409; the same person in another store allowed | `aPersonAlreadyInTheStore...` | IT |
| T78-4-12 | Role not allowed for the person: 400 with field message; unknown, foreign, switched-off person 400 | `storeStaffCanBeMapped...`, `aCentralStoreUserRoleIsOnly...`, `aMemberThatIsMissingUnknownForeignOrSwitchedOff...`, `aMappingThatNoLongerFitsTheTeamRoleShowsNeedsReview...` | IT |
| T78-4-13 | Remove: at once, row kept as history, audited who removed whom, safe repeat | `removingAMappingIsAtOnceKeepsTheRowAsHistory...` | IT |
| T78-4-14 | Re-map reactivates the same row | `mappingAgainAPersonWhoWasRemovedReactivatesTheSameRowWithTheNewRole`; `PharmacyTablesIT` unique key | IT |
| T78-4-15 | Switched-off team member: mappings stop counting, "Cannot sign in", no working store | `PharmacyMenuAndContextIT.aSwitchedOffTeamMemberCanNoLongerUseTheModuleAndStopsCounting` | IT |
| T78-4-16 | Non-owner mapping API 403; pharmacist and Central Store user read; another clinic's store or person 404 or 400 | `everyMappingChangeByANonOwnerIs403...`, `aMappingOfAnotherStoreOrAnotherClinicOrAnUnknownIdIs404...`, `theCandidatesListNeedsAStoreOfThisClinicAndTheOwner` | IT |
| T78-4-17 | One HOD and one delegate per store (`approver-exists`); change and end delegation | `aSecondApproverOrDelegateInOneStoreIs409ApproverExists`, `changingTheRoleBumpsTheVersion...`, `changingToApproverWhileTheStoreHasOne...` | IT |
| T78-4-18 | Working store: one store is the working store; several: asked to pick, remembered, switch saved | `aPersonMappedToOneStoreHasItAsWorkingStore...`, `aPersonWithSeveralStoresPicksOne...`; e2e "a person with two stores is asked to pick..." | IT, e2e |
| T78-4-19 | Owner with no mapping: "You are not mapped to a store. Ask your owner." | e2e owner happy path | e2e |
| T78-4-20 | Unmapped, foreign, unknown or switched-off store: 404, working store unchanged | `anUnmappedUnknownForeignOrSwitchedOffStoreAnswers404...`, `PharmacyStoreGuardIT.anUnmappedForeignUnknownOrSwitchedOffStoreAnswersTheSame404...`; e2e (404 shows the pick error) | IT, e2e |
| T78-4-21 | Mapped, capability missing: 403 `store-permission-denied`, audit with ids only | `PharmacyStoreGuardIT.aMappedCallerWithoutTheCapabilityGets403StorePermissionDeniedAndOneAuditRowWithIdsOnly`, `.theOwnerReachesEveryActiveStore...`, `.theCapabilitiesFollowTheStoreRole...` (nothing cached) | IT |
| T78-4-22 | Mapping removed or store switched off while it was the working store: asked to pick again | `aRememberedStoreThatLaterLostItsMappingIsDropped...` | IT |
| T78-4-23 | Missing or malformed `storeId` on the working store: 400 | `aMissingOrMalformedStoreIdOnTheWorkingStoreIs400...` | IT |
| T78-4-24 | Phone or tablet: working-store select usable | e2e (phone-360 project, same specs) | e2e |
| T78-4-25 | Contract order: access level (403) before parameter validation (400) | `PharmacyStaffMappingIT.aNonOwnerWithoutAStoreIdGets403BeforeAnyValidation...` | IT |
| T78-4-26 | Store guard refuses before the guard is reached: unmapped role 403, module off 403, switched-off member 401, foreign mapping gives no access, tenant id from the request is ignored | `PharmacyStoreGuardIT.aCallerWithNoMappingAtAll...`, `.aSwitchedOffTeamMemberIsRefusedWith401...`, `.anotherClinicsMappingGivesNoAccessHere...` | IT |

## 6. R-1 check (CLOSED 18 Nov 2026, kept as a regression check)

`docs/handoffs/SAH-78.md`, tech lead review: `redirectTo: shellLanding` runs before the shell guard has loaded `/me`, so on a fresh load of `/app` the menu is `[]` and every user is sent to Pharmacy & Inventory instead of Today. e2e "R-1 landing" runs OWNER, DOCTOR, FRONT_DESK, NURSE and PHARMACIST (module Off) and the owner with the module On: each opens `/app` and must land on `/app/today`. A seventh check, a Store staff member with a store landing on Pharmacy & Inventory, is the intended new behaviour and passes. Result before the fix: the six "lands on Today" checks failed (12 runs). Result after the fix (angular-developer R-1 retry 1, a CanActivateFn guard on the `today` route): all pass, 38 of 38 in the file, re-run by QA on a fresh build (section 10a). The describe title now reads "R-1 landing (regression check, R-1 fixed)".

## 7. Database checks (design note section 9, WP-A tables) · `PharmacyTablesIT`, `RowLevelSecurityCatalogIT`

| # | Check | Test |
| --- | --- | --- |
| T78-DB-01 | Tenant A rows of `unit`, `store`, `store_staff`, `staff_preference` invisible to tenant B as `sahaj_app`; no tenant bound reads nothing; B cannot update A's rows | `PharmacyTablesIT.anotherTenantsRowsOf...` |
| T78-DB-02 | Insert with another tenant's id refused (row level security); insert pointing at another tenant's unit or store refused (same-tenant composite foreign key) | `PharmacyTablesIT.anInsertForAnotherTenantIsRefused...` |
| T78-DB-03 | No delete, truncate, references or trigger privilege on any of the 23 pharmacy tables; select, insert, update only on the 17 mutable tenant tables; select and insert on the 3 immutable; select only on the 3 global; nothing to PUBLIC | `PharmacyTablesIT.theApplicationCanNeverDeleteOrTruncate...` |
| T78-DB-04 | 20 tenant tables: NOT NULL `tenant_id` defaulting to the bound tenant, enabled and forced, exactly select, insert, update policies (immutable: select, insert) | `RowLevelSecurityCatalogIT.everyPharmacyTenantTable...` |
| T78-DB-05 | 3 global tables enabled and forced, exactly the select and migrator policies, no tenant column, no write grant | `RowLevelSecurityCatalogIT.globalCatalogueTablesHaveExactly...` (extended) and `PharmacyTablesIT.theGlobalTablesAreReadableWithNoTenant...` (migrator delete and truncate refused by trigger) |
| T78-DB-06 | One unit per tenant; unit code, GSTIN, licence, mode, product-number checks | `PharmacyTablesIT.theUnitTableHoldsOneRowPerTenant...` |
| T78-DB-07 | One active Central Store; `issues_from` null exactly for Central; not self; name and code unique ignoring case; name, code, type, status checks; a switched-off Central frees the slot | `PharmacyTablesIT.theStoreTableKeepsOneActiveCentralStore...` |
| T78-DB-08 | One row per person and store; one active HOD, one active delegate (a removed one does not block); delegate dates (null, reversed, `to - from` of 90 and 91 days refused, 89 accepted, that is 90 calendar days counting both ends (migration 1230), dates on a non-delegate refused); role and status checks | `PharmacyTablesIT.theStoreStaffTableKeepsOneRowPerPerson...` |
| T78-DB-09 | One preference per person; store of the same tenant | `PharmacyTablesIT.theStaffPreferenceHoldsOneRowPerPerson...` |
| T78-DB-10 | Store guard trigger: `has_stock` never true to false; type and code frozen once stock | `PharmacyTablesIT.theStoreGuardTriggerKeepsHasStockOneWay...` |
| T78-DB-11 | `ck_membership_role` accepts `STORE_STAFF` and every old role, refuses another | `PharmacyTablesIT.theWidenedRoleCheckAcceptsStoreStaff...` |
| T78-DB-12 | `module_entitlement.pharmacy_on` NOT NULL default false; a fresh row and a clinic with no row read Off | `PharmacyTablesIT.thePharmacyColumnIsNotNullWithDefaultFalse...` |
| T78-DB-13 | The 11 pharmacy migrations V2026_12_17_1000 to 1250 are applied in version order with 1250 last, none failed, and the Flyway checksum of each applied migration equals the CRC-32 of its file (no checksum change on 1000 to 1240) | `PharmacyTablesIT.theElevenPharmacyMigrationsAreAppliedInOrderWith1250LastNoneFailedAndTheChecksumsOf1000To1240AreTheFilesAsTheyAre` |
| T78-DB-14 | Sample seed: with the test profile placeholder `true`, 30 products, 6 barcodes, all sample and active, made-up manufacturers, duplicate key format; 13 reason defaults | `PharmacyTablesIT.withTheTestProfilePlaceholderTrue...` |
| T78-DB-15 | Seed inserts nothing when the placeholder is not exactly `true` (false, TRUE, empty, yes, 1) | `PharmacyTablesIT.theSeedScriptInsertsNothingWhenThePlaceholderIsNotExactlyTrue` |

Not tested here (written with the package that owns them): unique and check constraints of `product`, `opening_stock_*`, `vendor`, `approval_rule*`, `document_series`, `reason`, `print_template_setting`; `next_document_no()`; the approver-differs check of opening stock. Their schema-level RLS, grants and policies are already covered by T78-DB-03 and T78-DB-04.

## 8. Differences between the stories and the contract (tests follow the contract)

1. 78-1 AC 4: story says a pharmacist with no mapping gets 403; contract (`PHARMACY_ACCESS` includes any PHARMACIST member) and `NavigationPolicy` give the pharmacist the entry and `GET /context` answers 200. Tested to the contract. BA to align the story.
2. 78-3 AC "the list shows only the stores they are mapped to": contract and handoff decision 4: `GET /stores` lists all stores for SETUP_VIEW. Tested to the contract.
3. 78-4 and 78-7 "403 `outside-store`": 404 for an unmapped store (decision 4).
4. 78-4 "Pharmacist" store role: there is none; pharmacist powers come from the team role.
5. 78-2 AC 8 (`integration-off`) cannot be tested until an Integrated-only operation exists.
6. Delegate length (DEC-068 Q1 A, contract 0.1.1 "at most 90 calendar days counting both ends, `to - from > 89` refused"): a delegation of 90 calendar days, counting both the start and the end date, is the longest. In the tests the end date `from + 89` is accepted, and `from + 90` (91 days counting both ends) and `from + 91` are refused with "A delegation can be at most 90 days"; the database check (migration 1230) agrees: `2026-11-18` to `2027-02-15` is accepted, `2027-02-16` and `2027-02-17` are refused. The old wording "90 days exactly allowed, 91 refused" (rows T78-3-09 and T78-DB-08 before this update) is gone.
7. `GET /staff-candidates` without `storeId` (F-11, contract 0.1.1): the 0.1.0 tests expected 400 "Choose a store"; they now expect 200 (the list by team role). A bad or unknown `storeId` is still 400 or 404.
8. An unreadable body read after the path id but before the store guard (WP-B `start`, `addLines`, `reject`; tech lead accepted it, handoff 18 Nov 10:04): for a mapped caller without the capability a malformed body answers 400, where contract rule 2 says the store guard (403) comes first. The gate (module off, access level) does come first, and that is what the tests assert; the guard-before-body order is not asserted.

## 9. Placeholders

### WP-B (78-5, 78-6, 78-7): WRITTEN, see sections 13 to 15
- Written in this run: `PharmacyProductCatalogueIT`, `PharmacyOwnProductIT`, `PharmacyOpeningStockIT`, `PharmacyProductTablesIT`, `frontend/e2e/pharmacy-products-opening-stock.spec.ts`.
- Still open for WP-B: the 300 ms search target (performance test), the empty-catalogue sentence (DEC-070 Q4), and the e2e of "Create own product" as an inline card (DEC-070; the specs select by test id so they survive the move).

### WP-C (78-8, 78-9, 78-10): NOT YET WRITTEN
- 78-8 vendor, licence block (IST date, valid-till day still Active), preferred-vendor review.
- 78-9 approval rules: versions are immutable, `rules-changed` 409, presets, fixed rules cannot be sent.
- 78-10 numbering: `next_document_no()` (1, 2, 3; rollback gives the number back; concurrent transactions differ; fiscal year switch at 00:00 IST on 1 April; errors 22023, 42501, P0002), prefix and 16-character rules, reason lists, print templates.

## 10. Results of this run (18 Nov 2026)

Backend integration tests of this package (run on `sahaj_test` from an isolated copy of the classes, because another session was running the full gate in `backend/target`):

| Class | Tests | Pass | Fail |
| --- | --- | --- | --- |
| PharmacyModuleOffIT | 10 | 9 | 1 |
| PharmacyMenuAndContextIT | 18 | 18 | 0 |
| PharmacyUnitSettingsIT | 15 | 14 | 1 |
| PharmacyStoresIT | 24 | 23 | 1 |
| PharmacyStaffMappingIT | 21 | 20 | 1 |
| PharmacyStoreGuardIT | 7 | 7 | 0 |
| PharmacyStoreStaffRoleIT | 4 | 4 | 0 |
| PharmacyTablesIT | 14 | 14 | 0 |
| RowLevelSecurityCatalogIT (14 existing + 1 new) | 15 | 15 | 0 |
| Total | 128 | 124 | 4 |

The four failures are application defects against the contract (section 11), not test faults:
1. `PharmacyModuleOffIT.aMalformedPathIdWithTheModuleOffAnswersModuleOffFirstAsTheContractOrderSays`: 400 instead of 403 `module-off`.
2. `PharmacyStaffMappingIT.aNonOwnerWithoutAStoreIdGets403BeforeAnyValidationAsTheContractOrderSays`: 400 instead of 403.
3. `PharmacyUnitSettingsIT.aSaveThatOmitsAFieldTheContractRequiresIs400AndDoesNotSilentlyClearTheSavedValues`: 200 instead of 400.
4. `PharmacyStoresIT.aChangeThatOmitsFieldsTheContractRequiresIs400AndDoesNotSilentlyClearTheFlagsOrTheHod`: 200 instead of 400.

Playwright (`frontend/e2e/pharmacy-setup.spec.ts`, built app of 18 Nov 01:30 served by `serve.mjs`, projects phone-360 and desktop-1440): 19 tests x 2 projects = 38 runs: 26 pass, 12 fail. All 12 failures are the six R-1 landing checks in both projects ("R-1 landing", section 6). The 12 other specs and the Store staff landing check pass in both projects.


Gate (`node scripts/validate.mjs backend`, run once on a quiet tree, 18 Nov 2026 03:06 to 04:22 IST; the first attempt at 02:57 stopped in the unit phase because my test-only probe sat in a package that `StoreStaffRoleGatesTest` scans; the probe was moved to `backend/src/test/java/com/sahaj/pharmacy/it/`):
- Unit phase: 5,736 tests, 0 failures, 0 errors, 0 skipped.
- Integration phase: 3,118 tests, 4 failures, 0 errors, 25 skipped (the 25 skips are older tests, none in SAH-78). The same 4 failures as above and no others; the per-class counts above are identical in the gate.
- IpdJourneyIT (1 test) and MedicineRoundIT (21 tests) passed on the quiet tree this time; they are SAH-76 tests, not SAH-78.
- `node scripts/validate.mjs frontend`: lint passes; exit 1 at the test step on `--browsers=ChromeHeadless` (DEC-002, human-owned script). Not run again by me beyond that.
- Note for the other session: between 02:25 and 02:35 I ran my ITs from an isolated copy of the classes against `sahaj_test` while its full gate was running. One of my runs hit "remaining connection slots are reserved" (PostgreSQL out of connections). Its failsafe summary of 02:54 shows 3,004 completed, 940 errors, 24 failures; some of those errors may come from my extra connections. Treat that summary as unreliable. I should have waited for the quiet tree before starting.

## 10a. Re-check after the fixes (18 Nov 2026, 05:00 to 07:00 IST)

Fixture corrections (QA-owned; the app was right, the two fixtures sent bodies the contract does not allow):
- `PharmacyTablesIT.world()`: the unit PUT now sends `"drugLicenceNo":null,"drugLicenceValidTill":null` (contract `UnitSettingsInput` requires all five fields). Affected 8 tests.
- `PharmacyStoreGuardIT.theCapabilitiesFollowTheStoreRole...`: the store PUT now sends `hodMembershipId` and `delegate` as explicit nulls (contract `StoreUpdate` requires all eight fields). Built with a `LinkedHashMap`, because `Map.of` rejects null values.
- `PharmacyUnitSettingsIT` (the "not a date" and "impossible date" bodies): added `gstin` and `drugLicenceNo` as null so the 400 is for the date alone, not for a missing field. No test result changed.
- Checked and left as they are: all other partial PUT bodies in `PharmacyMenuAndContextIT`, `PharmacyModuleOffIT`, `PharmacyStaffMappingIT` (gate or 400 tests, answered before the body is read, or expected 400) and `Sah78Support` (`storeBody`, `mapBody` are complete).

Playwright, `pharmacy-setup.spec.ts`, fresh `npm --prefix frontend run build`, msedge, phone-360 and desktop-1440: **38 of 38 pass** (51 s). All 6 R-1 checks, in both projects, are green.

Backend, the gate run that the java-developer's finish hook started at 05:05 (`validate backend`, full `mvn verify`, ended 06:35): unit phase reports were overwritten by a second hook run, so QA does not quote them; failsafe summary: **3,118 completed, 0 errors, 9 failures, 25 skipped**. The only failing tests were the 9 fixture tests above (8 in `PharmacyTablesIT`, 1 in `PharmacyStoreGuardIT`); no other class failed. That build was compiled before the fixture fix.

NOT DONE (blocked): the targeted run with the fixed fixtures and the QA `validate backend`. At 06:39 `mvn verify` stopped at test compile on another session's file, `Sah76ReviewRoundIT.java` line 121 (`"IPD_%"`: illegal escape character, modified 06:33, still broken at 07:01). That file is SAH-76, not SAH-78, and is not edited by this run. See the handoff.

| Defect | Status |
| --- | --- |
| QA-78-1 | Fixed (java-developer fix round 1). `PharmacyUnitSettingsIT.aSaveThatOmits...` passes. |
| QA-78-2 | Fixed. `PharmacyStoresIT.aChangeThatOmits...` passes. |
| QA-78-3 | Fixed. `PharmacyModuleOffIT.aMalformedPathId...` and `PharmacyStaffMappingIT.aNonOwnerWithoutAStoreId...` pass. |
| R-1 | Fixed (angular-developer R-1 retry 1). 6 "lands on Today" checks pass in both projects. |

## 11. Defects found by this run (all fixed, see 10a; app code is not changed by QA)

| ID | Where | Test | Expected (contract) | Actual | Severity |
| --- | --- | --- | --- | --- | --- |
| QA-78-1 | `backend/src/main/java/com/sahaj/pharmacy/application/UnitService.java` `check(...)` and `web/UnitSettingsController.java` | `PharmacyUnitSettingsIT.aSaveThatOmits...` | `UnitSettingsInput` requires `version, gstin, drugLicenceNo, drugLicenceValidTill, integrationMode`; a body without `gstin` is 400 `validation` | 200; a missing field is read as null, so the saved GSTIN, licence number and date are cleared (the body records have no way to tell a missing field from null) | Medium: silent loss of legal data from a partial request |
| QA-78-2 | `application/StoreService.java` `update(...)` (`Boolean.TRUE.equals(input.saleAllowed())`, missing `hodMembershipId` read as null) | `PharmacyStoresIT.aChangeThatOmits...` | `StoreUpdate` requires `saleAllowed, consumptionAllowed, hodMembershipId, delegate`; a body without them is 400 | 200; Sale and Consumption silently become No, and the HOD and delegate are demoted to Store user | Medium: silent change of store flags and approvers |
| QA-78-3 | `web/StoreController.java`, `web/StaffController.java` (`@PathVariable UUID`, `QueryParams.requiredUuid` run before the service gate) | `PharmacyModuleOffIT.aMalformedPathId...`, `PharmacyStaffMappingIT.aNonOwnerWithoutAStoreId...` | General rule 2: module off, then access level (403), then path id and parameter validation (400) | 400 `bad-request` or `validation` before the 403 | Low: wrong status for a caller who is refused anyway; no data is exposed |

Observation, not a defect: `GET /context`, `/stores` and the other reads answer an unmapped PHARMACIST with 200, as the contract says; story 78-1 AC 4 says 403 (section 8).
Observation: the global-table `truncate` triggers cannot be reached for `catalogue_product` and `reason_default` (PostgreSQL refuses first, 0A000, because other tables reference them); the application is also refused by privilege. Only `catalogue_barcode` reaches the trigger.

## 12. WP-A re-check after review fix round 1 and F-11 (18 Nov 2026)

Changes of behaviour that the tests now follow (all on purpose, docs/handoffs/SAH-78.md, java fix round 1, 10:04):
- partial bodies are refused (400, one field error each, nothing written): the `PUT /unit-settings` and `PUT /stores/{id}` fixtures send the full lists of the contract (`UnitSettingsInput`, `StoreUpdate`);
- the gate runs before the body is read (module off 403, access level 403, then 400);
- the delegate range is at most 90 calendar days counting both ends (section 8, item 6) and migration 1230 holds the same rule in the database;
- the denial audit row of the store guard is written in its own transaction (REQUIRES_NEW); CHANGED again in fix round 2 (N-1): the problem handler writes it after the transaction, see section 18.2;
- the `afterMigrate` sample-catalogue guard exists; migrations 1230, 1240 and 1250 exist.

| # | Check | Test | Type |
| --- | --- | --- | --- |
| T78-RC-01 | Delegate 90 days counting both ends: `from + 89` accepted, `from + 90` and `from + 91` refused (API: mapping and store create) | `PharmacyStaffMappingIT.aDelegateNeedsBothDatesInOrderWithinNinetyDaysAndMustNotBeTheHod`, `PharmacyStoresIT.aDelegateNeedsDatesInOrderAtMostNinetyDaysNotInThePastAndMustBeADifferentPersonFromTheHod` | IT |
| T78-RC-02 | The same rule in the database: 2026-11-18 to 2027-02-15 accepted, to 2027-02-16 and 2027-02-17 refused (`ck_store_staff_delegate_dates`) | `PharmacyTablesIT.theStoreStaffTableKeepsOneRowPerPersonAndOneActiveHodAndDelegateAndChecksTheDelegateDates...` | IT |
| T78-RC-03 | 11 pharmacy migrations, 1250 last, checksums of 1000 to 1240 unchanged, `ix_catalogue_barcode_lower` in `pg_indexes` | `PharmacyTablesIT.theElevenPharmacyMigrationsAreAppliedInOrderWith1250Last...`, `.migration1250AddsTheLowerCaseBarcodeIndexAndTheTenantProductBarcodeKeyIsStillThere` | IT |
| T78-RC-04 | A malformed body from a caller whose module is off: 403 `module-off`, never 400 (12 body-reading operations x 6 bodies x 3 callers) | `PharmacyModuleOffIT.aMalformedBodySentByACallerWhoseModuleIsOffGets403ModuleOffNotA400` | IT |
| T78-RC-05 | A malformed body from a non-owner or an unmapped person: 403 `forbidden`, never 400 (12 operations x 6 bodies x 4 callers, and the pharmacist on every owner-only operation) | `PharmacyModuleOffIT.aMalformedBodySentByAPersonWithoutTheAccessLevelGets403ForbiddenNotA400` | IT |
| T78-RC-06 | Every one of the 33 operations answers 403 `module-off` with the module off | `PharmacyModuleOffIT.theOperationListCoversEveryOperationOfWpAAndWpB`, `.everyPharmacyEndpointAnswers403ModuleOff...` | IT |
| T78-RC-07 | The sample-catalogue guard: passes in `sahaj_dev` and `sahaj_test` with the 30 sample rows; stops (P0001, database name and row count, hand fix in the hint, no row value) in any other database name (13 names, including `sahaj_dev_staging`, `SAHAJ_TEST`, `sahaj_dev `); passes when no sample row is left and when the table does not exist; changes nothing | `PharmacySampleGuardIT` (5 tests) | IT |
| T78-RC-08 | The `PHARMACY_STORE_ACCESS_DENIED` audit row survives when the request's own transaction rolls back, also for a read-only transaction; the caller's own marker write is gone; a 404 writes no denial | `PharmacyStoreGuardIT.theDenialAuditRowSurvivesWhenTheCallersTransactionRollsBack...`, `.theDenialAuditRowIsKeptAlsoForACallerInAReadOnlyTransaction...`, `.aStoreThatIsNotFoundWritesNoDenialRow...` (probe `/rolled-back/...` and `/read-only/...`) | IT |
| T78-RC-09 | e2e: the top "Inventory setup" is a link in the navigation "Pharmacy and inventory"; the owner has three setup tabs (Stores & staff, Products, Opening stock) | `pharmacy-setup.spec.ts` "owner: one sidebar entry opens Inventory setup..." | e2e |
| T78-RC-10 | e2e: `add-store` ("Add store") and `map-staff` ("Map staff to this store") are found by test id; names are checked by the unit tests of the screen | `pharmacy-setup.spec.ts` (unchanged ids) | e2e |

### F-11 (contract 0.1.1) · `GET /staff-candidates` without `storeId` (`PharmacyCandidatesWithoutStoreIT`, e2e)

| # | Check | Test | Type |
| --- | --- | --- | --- |
| T78-F11-01 | Owner gets 200 without `storeId`, also for a clinic with no store; each team role lists its full store roles (Owner, Doctor, Nurse, Pharmacist four, Store staff and Billing two, the others Store user); Central Store user is included where the team role allows it | `theOwnerGets200WithoutAStoreAndEachTeamRoleListsItsFullStoreRolesCentralStoreUserIncluded` | IT |
| T78-F11-02 | `alreadyMappedHere` is false for everyone, also for a member who is mapped (control: true with a store) | `alreadyMappedHereIsFalseForEveryoneEvenForAMemberWhoIsMappedToAStore` | IT |
| T78-F11-03 | `storeRole=APPROVER` and `DELEGATE` list only the members who may hold the role | `theApproverAndDelegateFiltersListTheMembersWhoMayHoldThatRole...` | IT |
| T78-F11-04 | `storeId=` (empty) is the same answer as no `storeId`; `storeRole=` empty is no filter | `anEmptyStoreIdIsTheSameAsNoStoreIdAndAnEmptyStoreRoleIsNoFilter` | IT |
| T78-F11-05 | The form without `storeId` never answers 404; an unknown or another clinic's `storeId` is still 404 | `withoutAStoreIdTheAnswerIsNeverNotFoundAndAnUnknownOrForeignStoreIdIsStill404` | IT |
| T78-F11-06 | Bad `storeId` 400 `validation` "Choose a store" (value not echoed); bad `storeRole` 400; 400 before 404 | `aBadStoreIdOrStoreRoleIs400WithAFieldMessageAndNothingIsEchoed` | IT |
| T78-F11-07 | Non-owner 403 with or without `storeId`, before validation; module off 403 `module-off` | `aNonOwnerGets403WithOrWithoutAStoreIdAndBeforeAnyValidationAndTheModuleOffGets403ModuleOff` | IT |
| T78-F11-08 | Another clinic's team is never listed | `anotherClinicsTeamIsNeverListed` | IT |
| T78-F11-09 | e2e: at the first Central Store the store form shows Approver (HOD) and Delegate approver, the `no-approver-yet` note is gone, candidates are read without `storeId` and with `storeRole=APPROVER` and `DELEGATE`; Store staff is not offered as approver | `pharmacy-setup.spec.ts` "owner: one sidebar entry opens Inventory setup..." | e2e |

## 13. Story 78-5 · Product search, adopt, local settings (`PharmacyProductCatalogueIT`, e2e)

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-5-01 | Product master list: global and own together, legend, search box, columns, Source chip, sample note, "Box of 10 → vial", HSN and GST | e2e "owner: the list shows global and own products together..." | e2e |
| T78-5-02 | Search ignores capital letters; name, salt, alias and code match as part of the text; percent, underscore and backslash are plain characters | `theSearchIsCaseInsensitiveOnNameSaltAndCodeAndPercentOrUnderscoreAreLiteral` | IT |
| T78-5-03 | Under 2 characters the first page is unfiltered (1 character, spaces only, empty); pages hold 25 (default) and total, size and page are answered; size 1 to 50, page 0 to 2000 | `underTwoCharactersTheFirstPageIsUnfilteredAndPagesHold25ByDefault`, `aBadQueryValueIs400WithAFieldMessage...`; e2e (no `q` sent for 1 character) | IT, e2e |
| T78-5-04 | An exact code or barcode match is first, then by name; a barcode matches exactly ignoring case, a part of it does not; a local barcode and alias are searchable | `anExactCodeOrBarcodeMatchComesFirstThenTheOthersByName`, `theSourceFilterAndTheLocalBarcodeAndAliasAreSearchable...` | IT |
| T78-5-05 | `q` over 60 characters, bad `source`, `page` or `size`: 400 with the field message; `source` GLOBAL and OWN filters | `aBadQueryValueIs400...`, `theSourceFilter...` | IT |
| T78-5-06 | Readers (pharmacist, Central Store user) read the same list with no buttons; others 403 | `aBadQueryValueIs400WithAFieldMessageAndAnyReaderOfSetupMaySearch`; e2e "a reader (pharmacist) sees the list and search but no Adopt..." | IT, e2e |
| T78-5-07 | 30 sample products, "Sample · not clinically validated" note | `underTwoCharacters...` (30 rows, `sample` true); e2e (`sample-note`); `PharmacyTablesIT.withTheTestProfilePlaceholderTrue...` | IT, e2e |
| T78-5-08 | Adopt: 201 with the unit product; the row holds no global field; catalogue barcodes shown; audit; the repeat answers 200 and writes nothing | `adoptCreatesThe201ProductAndARepeatIs200WithTheSameProductAndWritesNothing`; e2e adopt | IT, e2e |
| T78-5-09 | Adopt: owner only (403), unknown id 404, malformed id 400 `bad-request`, two clinics adopt the same product separately | `adoptNeedsTheOwnerAnExistingCatalogueIdAndAWellFormedId` | IT |
| T78-5-10 | A withdrawn product shows WITHDRAWN to who adopted it, is hidden from others and cannot be adopted (409 `product-withdrawn`); the adopter's repeat is 200 | `aWithdrawnCatalogueProductIsShownWithdrawnToWhoAdoptedIt...` (the seeded row G-000130 is withdrawn and put back in `finally`) | IT |
| T78-5-11 | Local settings: reorder level and rack per store saved, 0 is a value, audited before and after, an identical save writes nothing | `localSettingsSaveReorderLevelAndRackPerStoreAndAuditTheChange`; e2e "local settings of an adopted product are saved" | IT, e2e |
| T78-5-12 | 0.1.2: stores not listed stay unchanged; both null clears that store; only the level cleared keeps the rack; an empty list changes no store | `storesThatAreNotListedKeepTheirSettingsAndAnEntryWithBothValuesNullClearsThatStore` | IT |
| T78-5-13 | Bad values: level -1, 1000000 or 2.5, rack over 30, alias over 40, barcode over 40 or with other characters, switched-off, unknown, foreign or repeated store; all errors together; a global field in the body and each missing required field are 400; nothing changes; boundaries (999999, 30, 40) accepted | `localSettingsRefuseBadValuesAllTogetherAndChangeNothing` | IT |
| T78-5-14 | Barcode used by another ACTIVE product of this unit: 409 `barcode-in-use` "That barcode is already used by <product>"; any case; another clinic may use it; a stopped product does not hold it; switching one on again while another holds it is refused; a catalogue barcode is not a local barcode | `aBarcodeUsedByAnotherActiveProductIs409AndAStoppedProductDoesNotHoldItsBarcode`; e2e error path | IT, e2e |
| T78-5-15 | Stop using: audited (`PHARMACY_PRODUCT_SWITCHED_OFF` and `_ON`); adopt does not switch it on again; with stock 409 `product-has-stock`; an already stopped product saves again | `stopUsingIsAuditedAdoptDoesNotSwitchItOnAgainAndAProductWithStockCannotBeStopped` | IT |
| T78-5-16 | Preferred vendor (0.1.2): a changed value must be ACTIVE and not Blocked (400 "Choose an active vendor": blocked, inactive, another clinic's, unknown); a licence valid today is not blocked; an unchanged vendor that became Blocked or Inactive stays savable with `needsReview`; a change to another blocked one is 400; null clears | `thePreferredVendorMustBeActiveAndNotBlockedWhenChanged...` | IT |
| T78-5-17 | Two owners: stale version 409 `settings-changed` with `current`; a missing or negative version is a field error | `aStaleVersionIs409SettingsChangedWithTheCurrentProductAndNothingIsOverwritten`; e2e error path | IT, e2e |
| T78-5-18 | Tenants and roles: another clinic's product 404 on GET, PUT and local settings; malformed id 400 before the body; pharmacist reads, nurse 403, non-owner change 403; local settings of one clinic never show in another's | `anotherClinicsProductIs404AndAnUnknownOrMalformedIdIsHandledInTheContractOrder`, `theLocalSettingsOfOneClinicNeverShowInAnotherClinicsSameCatalogueProduct` | IT |
| T78-5-19 | Errors on the screen: the list cannot be read (Try again), a failed save keeps the form, narrow screen has no sideways scroll | e2e "error path: the list cannot be read...", "...a barcode already used...", "the Products screen does not scroll the page sideways" | e2e |
| T78-5-20 | Not tested: the 300 ms search target (performance test of the architect); the empty-catalogue sentence "No shared products yet. Create your own product." is not built (DEC-070 Q4 open) | n/a | n/a |

## 14. Story 78-6 · Own product, duplicate check, units (`PharmacyOwnProductIT`, e2e)

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-6-01 | Create: 201, code `U-<unit>-0001` then `0002`, own source, `ndps` false, ACTIVE, text trimmed, manufacturer or made in-house | `anOwnProductIsCreatedWithTheNextCodeOfTheUnitAndReadsBackAsOwn`; e2e "create an own product..." (body sent) | IT, e2e |
| T78-6-02 | Numbering per unit (each clinic starts at 1); concurrent creates get different codes with no gap; refused creates take no number | `theNumberingIsPerUnit...`, `twoOrMoreCreatesAtTheSameMomentGetDifferentCodesWithoutGapsOrErrors`, `aFailedCreateGivesTheNumberBackNoGapIsLeft` | IT |
| T78-6-03 | Empty form: every field message together, nothing saved; schedule null "Choose the schedule"; manufacturer empty while not made in-house | `anEmptyFormIsRefusedWithEveryFieldMessageTogetherAndNothingIsSaved`; e2e error path | IT, e2e |
| T78-6-04 | Boundaries: name, salt, strength 80; form 30; units 20; manufacturer 80; barcode 40; conversion 1 to 10000 and whole; HSN 4 to 8 digits; GST 0 to 100 with at most 2 decimals; four schedules | `theLimitsOfEachFieldAreExactAtTheBoundary` | IT |
| T78-6-05 | Closed body (no NDPS, code, tenant, unit, source or version from the client), every required field and `clientKey` needed, unreadable body `malformed-request` | `theBodyIsClosedAndNeedsEveryRequiredFieldAndAClientKey` | IT |
| T78-6-06 | Duplicate 409 `possible-duplicate` with candidates (global first, manufacturer, `inUnit`), nothing saved; "Use global product" adopts; "Create anyway" (`ignoreDuplicates`) saves and records `duplicateOfCodes` | `theSameSaltStrengthAndFormAsAGlobalProductIs409WithCandidates...`; e2e (warning text, use, create anyway) | IT, e2e |
| T78-6-07 | "1 g" equals "1000 mg" (also "1g", "1.0 g", "1 G", "1000mg"), capitals and spaces do not matter; "0.5 g" matches the 500 mg product only; another form is no duplicate | `oneGramAndOneThousandMilligramsAreTheSameStrengthAndCaseAndSpacesDoNotMatter` | IT |
| T78-6-08 | An own product is a candidate too, after the global ones; another clinic's own product is not; `excludeProductId`; a switched-off own product is not | `anOwnProductOfTheUnitIsACandidateTooAfterTheGlobalOnes...` | IT |
| T78-6-09 | The check endpoint: owner only, three fields required, `excludeProductId` a UUID | `theDuplicateCheckEndpointNeedsTheOwnerAndAllThreeFieldsAndAGoodExcludeId` | IT |
| T78-6-10 | `clientKey` replay by the same caller: 200, same product, nothing written, answered before the body and the duplicate check; the number is not used up | `theSameClientKeyFromTheSameCallerAnswersTheFirstProductAgain...`; e2e (the same key on a retry) | IT, e2e |
| T78-6-11 | The same key from another caller: 409 `client-key-reused`; another clinic's same key is a separate record | `theSameClientKeyFromAnotherCallerIs409ClientKeyReusedAndWritesNothing` | IT |
| T78-6-12 | Non-owner create 403; a barcode used by another active product (own or adopted) 409; another clinic may use it | `onlyTheOwnerCreatesAndABarcodeUsedByAnotherActiveProductIs409` | IT |
| T78-6-13 | Edit: version, audit before and after, the same values write nothing, stale version 409 with `current`, same field rules (all errors together), code and source not in the body | `anOwnProductIsEditedWithItsVersionAuditedAndItsCodeNeverChanges` | IT |
| T78-6-14 | 0.1.2: the duplicate check on edit runs only when the normalised key changes ("1 g" to "1000 mg" is no change; an edit that keeps the key needs no "Create anyway" again); the codes are recorded when saved anyway | `theDuplicateCheckOnEditRunsOnlyWhenTheNormalisedKeyChanges` | IT |
| T78-6-15 | An adopted product's global fields cannot be edited: 400 `global-fields-read-only` "Global fields cannot be changed. Use Local settings.", also for a body that changes nothing; owner only; 400 for a bad id; 404 for another clinic | `anAdoptedProductsGlobalFieldsCannotBeEditedAndTheAnswerIs400WithTheSentence` | IT |
| T78-6-16 | Units and conversion locked once the product has stock: 409 `units-locked` "Locked because this product has stock."; other fields change; the database refuses it too (23514) | `theUnitsAndConversionAreLockedOnceTheProductHasStock...` | IT |
| T78-6-17 | Options: starter values, what the unit used, each once ignoring case, sorted A to Z; another clinic's values not suggested | `theOptionsListStartsWithTheStarterValues...` | IT |
| T78-6-18 | Not tested at API level: the schedule-change confirmation is a client step (unit test of the screen); the Create button disabled while the request runs (unit test) | n/a | n/a |

## 15. Story 78-7 · Opening stock (`PharmacyOpeningStockIT`, `PharmacyProductTablesIT`, e2e)

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-7-01 | Template: fixed UTF-8 CSV, header and one `#EXAMPLE` row, `Content-Disposition` attachment with the file name, served as `text/csv` also when the client asks for JSON; owner, pharmacist and mapped people read it, an unmapped nurse gets 403 | `theTemplateIsTheFixedCsvWithTheDocumentedHeadersForAnyoneWithAccessAndIsNotOfferedToOthers`; e2e download | IT, e2e |
| T78-7-02 | Start (201 DRAFT, `uploadedBy` is the membership id, `posted` false, `approveLockReason` null outside WAITING), rows (200), submit (WAITING_APPROVAL, totals fixed: 143 units, 3,82,530 paise); the owner may approve, the uploader may not; audit rows; no file text, batch numbers or rates in the log | `startThenRowsThenSubmitMakesTheImportWaitForApprovalWithTheTotalsFixedAndNoFileTextInTheLogs`; e2e upload | IT, e2e |
| T78-7-03 | Up to 200 rows per request (201 refused), a row sent again replaces it (count unchanged, old issue superseded), lines paging (size 200 at most) | `upTo200RowsGoInOneRequestARowSentAgainReplacesItAndTheLimitsAreExact` | IT |
| T78-7-04 | Request body errors: empty rows, `rowNo` outside 1 to rowCount or repeated, a null cell, a value over its length, an unknown field: 400 and nothing stored | `aBadRequestBodyIsRefusedWithFieldErrorsAndStoresNothing` | IT |
| T78-7-05 | Every row check: store-mismatch, product-unknown, batch-invalid (0, 20 and 21 characters), expiry-invalid and expiry-past (today refused, tomorrow accepted), five expiry formats, mrp-invalid, rate-invalid, quantity-invalid (limits 999999 and 9999999.99), rate-above-mrp is a WARNING; codes trimmed and case-insensitive; the raw text is kept; the live counts while DRAFT count rows | `everyRowCheckAnswersItsOwnIssueWithTheDocumentedFieldAndCodeAndWarningsAreNotErrors` | IT |
| T78-7-06 | A file with an error: 422 `opening-stock-invalid` "2 rows have problems...", `errorCount` counts rows, `rowIssues` lists ERROR issues only, ordered by row and field; NOT_ACCEPTED stays after the 422 (also in the database), final; the warning stays readable in the lines; the uploader starts again | `aFileWithAnErrorBecomesNotAcceptedAndThatStaysAfterThe422...`; e2e "error path: a file with problems is Not accepted..." | IT, e2e |
| T78-7-07 | More than 100 error rows: the first 100 issues are listed, `errorCount` 105; one bad row: "1 row has a problem." | `oneBadRowGives1RowMessageAndOnlyTheFirst100IssuesAreListed...` | IT |
| T78-7-08 | Same product and batch twice: both rows are errors, ignoring case and spaces; another product with the same batch is fine | `theSameProductAndBatchTwiceIsAnErrorOnBothRowsAtSubmitIgnoringCaseAndSpaces...` | IT |
| T78-7-09 | Submit before every row arrived: 409 `import-incomplete`, stays DRAFT | `submitBeforeEveryRowArrivedIs409ImportIncompleteAndTheImportStaysADraft` | IT |
| T78-7-10 | Start: header compared trimmed, ignoring case, BOM tolerated; wrong header 400 "Use the Sahaj template. Save the file as CSV."; `rowCount` 1 to 5000 (0, 5001, 2.5 refused); `clientKey` needed; closed body | `startNeedsTheTemplateHeaderAndOneToFiveThousandRowsAndAClientKey`; e2e "error path: the wrong template is refused in the browser..." | IT, e2e |
| T78-7-11 | `clientKey` replay 200; another caller or another store 409 `client-key-reused`; another clinic separate | `theSameClientKeyAnswersTheSameImportAgainAndAnotherCallerOrAnotherStoreGets409ClientKeyReused` | IT |
| T78-7-12 | Start refused: another person's import waiting (409 `import-waiting` "...by Pia Pharmacist."); store already approved (409 `opening-stock-already-approved`); my own waiting import does not block a new start | `startIsRefusedWhenTheStoreAlreadyHasAnApprovedImportOrAnotherPersonsImportIsWaiting` | IT |
| T78-7-13 | REPLACED: a new start replaces my DRAFT; a submit replaces my WAITING import; only one waits; a replaced import cannot be changed, approved or rejected; another person's draft is not replaced | `aNewStartReplacesMyDraftAndASubmitReplacesMyWaitingImportSoOnlyOneWaitsAtATime`, `anotherPersonsDraftIsNotReplacedByMyStart` | IT |
| T78-7-14 | Approve by another owner: APPROVED, `decidedBy`, final; the store and the products named on a line get `hasStock`; `unitsLocked == hasStock`; a product not on a line does not; store switch-off, product stop and own-product unit change are refused afterwards; audit with uploader and approver ids | `anotherOwnerApprovesAndTheStoreAndTheProductsOnTheLinesGetStockAndLockTheirUnits`; e2e "a second owner approves..." (confirmation first) | IT, e2e |
| T78-7-15 | Self-approval: 403 `self-approval` "Another owner must approve this import.", import unchanged; `approveLockReason` SELF_UPLOADED for the uploader-owner; the database check `ck_opening_stock_import_approver_differs` (23514) refuses it too | `theUploaderCannotApproveHisOrHerOwnImportAndTheDatabaseRefusesItToo`; e2e (no Approve and the lock text; the server refusal shown) | IT, e2e |
| T78-7-16 | Only an owner approves or rejects: pharmacist, approver nurse, doctor, front desk get 403 `forbidden`; the owner's list is the owner's alone; list filters validated | `onlyAnOwnerApprovesOrRejectsWhoeverElseGets403ForbiddenAndTheImportIsUnchanged`; e2e (the uploader pharmacist sees no Approve, reason "Only the owner can approve this import.") | IT, e2e |
| T78-7-17 | One WAITING and one APPROVED import per store in the database (23505 `uq_opening_stock_import_one_waiting`, `_one_approved`) | `anImportOnlyOneStoreHasWaitingAndOnlyOneApprovedIsAlsoEnforcedByTheDatabase` | IT |
| T78-7-18 | Reject: Wrong file, Wrong quantities, Wrong store, Other; the note is kept; "Other" needs 4 characters (3 refused, 4 accepted), at most 300; the uploader-owner may reject; a DRAFT is 409; a bad body is 400 before the 409; the uploader starts again | `anOwnerRejectsWithAReasonTheUploaderMayStartAgainAndNothingIsApproved`, `theOwnerWhoUploadedMayRejectHisOwnImportButNeverApproveIt`, `theReasonIsRequiredAndOtherNeedsANoteOfAtLeastFourCharactersAndAtMost300`; e2e reject (two tests) | IT, e2e |
| T78-7-19 | Store guard: unmapped store 404 (nothing audited); mapped without the capability 403 `store-permission-denied` with one audit row of ids only, on six operations; unknown, foreign and malformed ids; an import of one store addressed through another is 404 | `anUnmappedStoreIs404AMappedStoreWithoutTheCapabilityIs403WithTheAuditRowAndAnUnknownIdIsHandledInOrder` | IT |
| T78-7-20 | A second pharmacist, and the owner, who did not start the import may read it but not write rows or submit: 403 `store-permission-denied` with an audit row each | `aSecondPharmacistOrTheOwnerWritingToSomeoneElsesImportIs403StorePermissionDeniedAndAudited` | IT |
| T78-7-21 | Who may upload: a pharmacist mapped at a store (only that store), a Central Store user (not a pharmacist), the owner; a Store user who is not a pharmacist may not | `aPharmacistMappedAtAStoreMayUploadThereButOnlyForThatStore` | IT |
| T78-7-22 | Tenants: another clinic's import is 404 on every operation; the lists, rows and issues are only the clinic's own | `anotherClinicNeverSeesOrChangesMyImportsAndTheListsHoldOnlyMyOwn`, `PharmacyProductTablesIT.anotherTenantsRowsOfTheFiveNewTables...` | IT |
| T78-7-23 | The trigger refuses every line and issue write after DRAFT (WAITING, REPLACED, APPROVED), a row beyond the row count, a change of an issue other than superseded; an approved import is final; the totals of a waiting import are fixed; only the listed transitions | `theDatabaseRefusesEveryWriteToLinesAndIssuesOnceTheImportIsNoLongerADraft...`, `aWaitingImportKeepsItsTotalsAndOnlyMovesToApprovedRejectedOrReplaced...` | IT |
| T78-7-24 | e2e: a clean file is read in the browser (example row skipped, 1 row), sent in the documented calls, the review shows "Waiting for approval" and "₹ 12,50,000.00"; the file text is not kept in the address or in storage | `pharmacy-products-opening-stock.spec.ts` "uploader: a clean file is checked and sent..." | e2e |
| T78-7-25 | e2e: a file with problems is Not accepted with the row and the field named; the wrong template (header, not CSV, empty) is refused in the browser with the one sentence and nothing is sent; the template cannot be downloaded | same file, three tests | e2e |
| T78-7-26 | e2e: the Opening stock screen does not scroll sideways | same file | e2e |

### WP-B database checks (`PharmacyProductTablesIT`, `RowLevelSecurityCatalogIT`)

| # | Check | Test |
| --- | --- | --- |
| T78-DB-16 | The 5 new tables (`product`, `product_store_setting`, `opening_stock_import`, `opening_stock_line`, `opening_stock_line_issue`): tenant A's rows are invisible to B (by tenant and by id), B cannot update them, nothing is visible with no tenant bound | `PharmacyProductTablesIT.anotherTenantsRowsOfTheFiveNewTables...` |
| T78-DB-17 | Insert with another tenant's id refused by row level security; insert pointing at another tenant's unit, product, store or import refused (composite foreign keys, the draft guard) | `PharmacyProductTablesIT.anInsertForAnotherTenantIsRefused...` |
| T78-DB-18 | Forced row level security, exactly the select, insert and update policies, NOT NULL `tenant_id` defaulting to the bound tenant on all 20 tenant tables (the 5 new ones included); no delete, truncate, references or trigger grant on any of the 23 tables | `RowLevelSecurityCatalogIT.everyPharmacyTenantTable...`, `PharmacyTablesIT.theApplicationCanNeverDeleteOrTruncate...` |
| T78-DB-19 | `product` kind exclusivity (`ck_product_kind_fields`): adopted rows hold no global field, own rows hold all; code, HSN, GST, schedule and conversion checks; code unique | `PharmacyProductTablesIT.anAdoptedRowHoldsNoGlobalField...` |
| T78-DB-20 | Adopt twice refused (`uq_product_adopted`); one ACTIVE product per barcode ignoring case, an INACTIVE one does not hold it | `PharmacyProductTablesIT.adoptingTheSameCatalogueProductTwiceIsRefused...` |
| T78-DB-21 | Product guard trigger: kind, link, code and unit never change; `has_stock` never goes back; units and conversion locked with stock; other fields still change | `PharmacyProductTablesIT.theProductGuardKeeps...` |
| T78-DB-22 | One setting per product and store; reorder 0 to 999999; rack 1 to 30 | `PharmacyProductTablesIT.thereIsOneSettingPerProductAndStore...` |
| T78-DB-23 | Import checks: row count 1 to 5000, uploader equals creator, one client key per uploader, REPLACED needs `replaced_by`, rejection reason and note rules, decision columns, `submitted_at`, totals not negative | `PharmacyProductTablesIT.theImportTableChecks...` |
| T78-DB-24 | Line and issue checks: row number, amounts, batch lengths, one line per row, the issue vocabulary, "superseded" is the only change | `PharmacyProductTablesIT.theLineAndIssueTablesCheck...` |
| T78-DB-25 | Migration 1250 applies last, no checksum change on 1000 to 1240, `ix_catalogue_barcode_lower` listed in `pg_indexes` (T78-RC-03) | `PharmacyTablesIT` |

### WP-B under concurrency (`PharmacyConcurrencyIT`, added after the first targeted run)

| # | Check | Test |
| --- | --- | --- |
| T78-CC-01 | Three adopts of the same product at the same moment: one 201, two 200, one row, one audit row | `twoAdoptsOfTheSameProductAtTheSameMomentGiveOneCreatedAndOneAlreadyThere` |
| T78-CC-02 | Two saves of the local settings with the same version: one 200, one 409 `settings-changed`, version 1, one audit row | `twoSavesOfTheLocalSettingsWithTheSameVersionGiveOneWinnerAndOneSettingsChanged` |
| T78-CC-03 | Two owners approve the same import at once: one 200, one 409 `import-state`, one audit row | `twoOwnersApprovingTheSameImportAtTheSameMomentGiveOneApprovalAndOneImportState` |
| T78-CC-04 | Approve and reject at once: one wins, the other 409; the store has stock exactly when the file was approved | `anApproveAndARejectAtTheSameMomentLeaveOneFinalStateAndTheOtherGets409` |
| T78-CC-05 | Two people submit clean files for one store at once: one WAITING, the other 409 `import-waiting` (not a 500 from the unique index) | `twoPeopleSubmittingCleanFilesForTheSameStoreAtTheSameMomentLeaveOneWaiting...` |
| T78-CC-06 | Two starts with the same `clientKey` at once: one import | `twoStartsWithTheSameClientKeyAtTheSameMomentGiveOneImport` |

## 16. Results of this run (18 Nov 2026, WP-A re-check, F-11, WP-B)

### Backend integration tests (real `sahaj_test`)

| Class | Tests | Pass | Fail |
| --- | --- | --- | --- |
| PharmacyModuleOffIT (10 before, +3) | 13 | 13 | 0 |
| PharmacyMenuAndContextIT | 18 | 18 | 0 |
| PharmacyUnitSettingsIT | 15 | 15 | 0 |
| PharmacyStoresIT | 24 | 24 | 0 |
| PharmacyStaffMappingIT | 21 | 21 | 0 |
| PharmacyStoreGuardIT (7 before, +3) | 10 | 10 | 0 |
| PharmacyStoreStaffRoleIT | 4 | 4 | 0 |
| PharmacyTablesIT (14 before, +1) | 15 | 15 | 0 |
| PharmacyCandidatesWithoutStoreIT (new, F-11) | 8 | 8 | 0 |
| PharmacySampleGuardIT (new) | 5 | 5 | 0 |
| PharmacyProductCatalogueIT (new, 78-5) | 17 | 17 | 0 |
| PharmacyOwnProductIT (new, 78-6) | 19 | 19 | 0 |
| PharmacyOpeningStockIT (new, 78-7) | 27 | 27 | 0 |
| PharmacyProductTablesIT (new, WP-B tables) | 8 | 8 | 0 |
| RowLevelSecurityCatalogIT | 15 | 15 | 0 |
| **Targeted total** | **219** | **219** | **0** |
| PharmacyConcurrencyIT (new, added after the gate was compiled) | 6 | 6 | 0 |

How the 219 got green: the first targeted run (16:00 to 16:25) had 4 failures, all test-side and all fixed in test code only: `PharmacyModuleOffIT` (the fixture itself created the unit row and audit rows that the test then called "nothing written"; the test now compares with the counts before), `PharmacyOpeningStockIT` (the template answer is CSV, not JSON, so the helper that parses JSON threw), `PharmacyProductCatalogueIT` (a local-settings body was sent to `PUT /products/{id}`, which is a closed body of another shape, so it answered 400 before 404), `PharmacySampleGuardIT` (the check "no write statement" counted the hand-fix text that the guard's own hint contains). No application defect was found by any backend test.

### Gate (`node scripts/validate.mjs db`, 18 Nov 2026 15:41 to 17:22 IST, 1 h 41 min, shared tree checked first: only PID 12408 and the known PID 6964 were running; `exit 0`)
- `validate db` = the static migration checks (file names, row level security on every tenant table) and then `backend()`, which is exactly `mvn -B -q -f backend/pom.xml verify`. `validate backend` runs the same command, so it was executed once and the result counts for both.
- Unit phase (surefire): **5,974 tests, 0 failures, 0 errors, 0 skipped** (415 classes).
- Integration phase (failsafe): **3,226 completed, 0 failures, 0 errors, 25 skipped** (older tests, none in SAH-78).
- No IPD test (Dose*, Mar*, Sah76*, IpdJourney*, MedicineRound*) failed in this run, so nothing is listed as outside SAH-78.
- `PharmacyConcurrencyIT` was written while the gate ran, so it is not in these numbers; it ran alone afterwards (6 of 6, with `PharmacySampleGuardIT` 5 of 5): 3,226 + 6 = 3,232 integration tests have run green for this story's tree.
- `validate frontend`: not run (not asked; it stops at the known DEC-002 flag).

### Playwright (msedge, `serve.mjs` on a fresh `npm --prefix frontend run build` of 18 Nov 14:50, which already holds the inline "Create own product" card of DEC-070)

| Spec | Runs | Pass | Fail |
| --- | --- | --- | --- |
| `pharmacy-setup.spec.ts` (19 tests x 2 projects) | 38 | 38 | 0 |
| `pharmacy-products-opening-stock.spec.ts` (21 tests x 2 projects) | 42 | 40 | 2 |

The last full run (both specs, 80 runs, workers 2): **78 pass, 2 fail**. The 2 failures are the tests of defect QA-78-4 (`QA-78-4: the Opening stock tab shows its content...`, both projects); they fail on purpose until the screen is fixed. Over the nine runs of the day the tab content (Products or Opening stock) sometimes did not appear in 15 to 20 s and a test that had nothing to do with the defect failed with it; earlier runs under load from the Maven gate showed 14 to 24 failures of that kind. The tests now ask the browser for idle periods until the tab content shows (`showTab`), which made the other tests stable; the two QA-78-4 tests do not, and fail about 1 run in 2. After the tab shows, every check of the specs passes.

## 17. Defects found by this run (app code is not changed by QA)

| ID | Where | Test | Expected | Actual | Severity | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| QA-78-4 | `frontend/src/app/features/pharmacy/setup/setup.page.ts` lines 108 and 115: `@defer { <app-products /> }` and `@defer { <app-opening-stock /> }` with no trigger (so `on idle`) and a `@loading` block but no `@placeholder` | `pharmacy-products-opening-stock.spec.ts` "QA-78-4: the Products tab shows its content without waiting for the browser to be idle" and the Opening stock twin | The content of a tab that the person has just chosen appears at once | In headless Edge the browser reports no idle period for 15 s or more in about 3 runs of 4 when the page has nothing else to draw: the tab shows nothing (before the chunk starts to load) or "Loading products". Asking the browser for an idle period from the test makes the content come, so the code waits for the idle callback. The risk for a real person (a background tab, a slow device) is not proven | Low to Medium | angular-developer: use `@defer (on immediate)` (the person has chosen the tab, there is nothing to wait for) or a normal lazy route; add a `@placeholder` |

Observations (not defects):
- DEC-070 (inline "Create own product" card) had landed in `frontend/src` before this run; the specs find the form by test id through `openCreateForm`, which works for the card and for the old sheet. The sheet layout (Edit) is unchanged.
- Local settings dialog and inline card both use `data-testid="f-barcode"`; the spec scopes by `getByRole('dialog')`. A unique id for one of them would make selectors simpler.
- The 300 ms search target and the empty-catalogue sentence are not tested (section 13, T78-5-20).
- `frontend/e2e` is still in `.gitignore` (line 8, `frontend/e2e`): both pharmacy specs are written but not tracked. DEC-069 says to track them; the human edits `.gitignore`.

## 18. Review fix round 2 (DEC-071, contract 0.1.3, ADR-028 revision 5): tests added and changed (18 Nov 2026, from 20:00)

Sources: `docs/decisions/DEC-071-sah78-review-round-2-fixes.md`, `docs/reviews/SAH-78-code.md` and `SAH-78-security.md` (Round 2), `docs/api/pharmacy.yaml` 0.1.3, `docs/handoffs/SAH-78.md` (18:15 to 18:55). Texts, codes and numbers are copied from the contract, never read from the application.

### 18.1 Where the new tests are

| File | What it covers |
| --- | --- |
| `backend/src/test/java/com/sahaj/pharmacy/it/PharmacyStoreProbe.java` (changed) | now `implements PharmacyApi`, so `PharmacyProblemHandler` applies and writes the denial audit; the three shapes (noRollbackFor, plain rolled back, read-only) are kept; the comments no longer mention REQUIRES_NEW |
| `PharmacyStoreDenialPoolIT` (new, own Spring context, request pool of 3) | N-1 / L7 |
| `PharmacyOpenImportIT` (new) | N-2 / DEC-071 Q1 option A |
| `PharmacyOpeningStockRound2IT` (new) | L8(a), L8(b), I4, N-13 |
| `PharmacySeedDupKeyIT` (new) | N-8 |
| `PharmacyStoreGuardIT`, `PharmacyOpeningStockIT`, `PharmacyTablesIT` (changed on purpose) | comments of the denial audit; the template placeholder (N-13); 12 migrations with 1260 last |
| `frontend/e2e/pharmacy-products-opening-stock.spec.ts`, `pharmacy-setup.spec.ts` (changed) | QA-78-4 without the idle workaround, the N-2 sentence on four screens, N-3, N-4, N-5, N-6, N-7, I2, the new texts |

Six existing tests that the round changed on purpose failed in the full gate that the java-developer side ran from 18:55 to 20:24 (failsafe summary 3,232 run, 6 failures, 0 errors; no other test failed): `PharmacyStoreGuardIT` x4 (the probe did not implement `PharmacyApi`, so the handler wrote no audit row for it), `PharmacyOpeningStockIT.theTemplateIs...` (placeholder `PRODUCT-CODE`), `PharmacyTablesIT.theElevenPharmacyMigrations...` (now twelve, 1260 last). All six were corrected in the test code (the application was right).

### 18.2 N-1 / L7 · the denial audit never needs a second connection (`PharmacyStoreDenialPoolIT`)

| # | Criterion | Test | Type |
| --- | --- | --- | --- |
| T78-N1-01 | The pool of this class really has 3 connections | `thePoolOfThisClassHasThreeConnections` | IT |
| T78-N1-02 | 24 parallel denials (12 mapped nurses x 2 operations, a read and a write) on 3 connections: no connection timeout, every call 403 `store-permission-denied` with the fixed sentence; 24 audit rows, each with this tenant (counted by `tenant_id`), entity = the store id, no before values, `after` = `membershipId` and `capability` only; each of the 12 people twice; another clinic sees none; the refused calls wrote nothing; the pool is idle afterwards | `manyMoreParallelDenialsThanThePoolHasConnectionsAllAnswer403AndWriteOneAuditRowEach` | IT |
| T78-N1-03 | The three probe shapes under 18 parallel denials: the audit row exists in all three (3 per person); the rolled-back shape's own marker write is gone | `theThreeTransactionShapesOfTheProbeEachKeepTheirAuditRowUnderParallelDenials` | IT |
| T78-N1-04 | The 404 paths (unmapped store, another clinic's store, unknown store, and the rolled-back probe on an unmapped store) write no audit row, also when mixed with denials in the same parallel wave (6 denials, 24 not-found answers, 6 rows) | `notFoundStoresWriteNoAuditRowEvenMixedWithDenialsInTheSameParallelWave` | IT |
| T78-N1-05 | The owner, refused a pharmacist-only capability, 8 times in parallel: 8 rows | `theOwnerDeniedAScheduleXCapabilityInParallelIsAuditedToo` | IT |
| T78-N1-06 | The existing guard tests with the probe now a `PharmacyApi` controller: one row per denial, ids only, survives a roll back, read-only, a 404 writes none | `PharmacyStoreGuardIT` (10 tests, names unchanged) | IT |

### 18.3 N-2 · 409 `open-import` (`PharmacyOpenImportIT`)

Exact sentence everywhere: "Finish or reject the opening stock file of this store first." Nothing changes: the GET of the store or the product is equal before and after, and the total audit count of the clinic is unchanged.

| # | Criterion | Test | Type |
| --- | --- | --- | --- |
| T78-N2-01 | Switch-off of a store with a DRAFT or a WAITING_APPROVAL import: 409, store still ACTIVE, no `PHARMACY_STORE_SWITCHED_OFF`; the file is still readable; another store of the clinic (no file) can be switched off | `aStoreWithADraftOrWaitingImportCannotBeSwitchedOffAndNothingChanges` | IT |
| T78-N2-02 | Store update with another code: 409, nothing changed. The same code, a rename and the code in another letter case: 200. Another store may change its code | `aStoreCodeCannotChangeWhileTheImportIsOpenButARenameAndAnUnchangedSaveAreAllowed` | IT |
| T78-N2-03 | Product (on a line) with another purchase unit, issue unit or conversion: 409 each; refused although the Central Store has no file; a rename with unchanged units, and a product on no line: 200 | `aProductOnALineOfAnOpenImportKeepsItsUnitsAndConversionButOtherFieldsAndUnchangedUnitsAreAllowed` | IT |
| T78-N2-04 | Local settings `active:false` on an adopted and on an own product on a line: 409; `active:true` with another alias: 200; a product on no line: 200 and audited | `aProductOnALineOfAnOpenImportCannotBeSwitchedOffButSwitchingOnAndOtherProductsAreAllowed` | IT |
| T78-N2-05 | Order: `units-locked` and `product-has-stock` before `open-import` for the product; `store-has-stock` before `open-import` on switch-off and on a code change | `unitsLockedAndHasStockAnswerBeforeOpenImportForTheProductAndTheStore` | IT |
| T78-N2-06 | After REJECTED the store (code, switch-off) and the products (units, stop using) change again. A DRAFT cannot be rejected (409 `import-state`; OQ-78-42) | `afterRejectTheStoreAndTheProductsMayChangeAgain` | IT |
| T78-N2-07 | After NOT_ACCEPTED the same; while it was a DRAFT the product on a good row and the store were held | `afterTheFileIsNotAcceptedTheStoreAndTheProductsMayChangeAgain` | IT |
| T78-N2-08 | After REPLACED the products on the replaced file are free; the store is held by the new DRAFT | `aReplacedImportNoLongerHoldsItsProductsWhileTheNewDraftHoldsTheStore` | IT |
| T78-N2-09 | After APPROVED the has-stock rules answer (`store-has-stock`, `units-locked`, `product-has-stock`), never `open-import`; a rename still works | `afterApprovalTheHasStockRulesApplyAndOpenImportIsNoLongerAnswered` | IT |
| T78-N2-10 | Another clinic's open import does not block (same store codes, same catalogue product); the clinic that has the file is still blocked (control) | `anotherClinicsOpenImportNeverBlocksThisClinic` | IT |
| T78-N2-11 | Another clinic's store or product: still 404 while a file is open here | `anotherClinicsStoreOrProductIsStill404EvenWhenAFileIsOpenHere` | IT |
| T78-N2-12 | A non-owner gets 403 `forbidden` before the rule is reached | `aNonOwnerIsRefusedBeforeTheOpenImportRuleIsReached` | IT |

### 18.4 L8 · unchanged resend and the line-request cap (`PharmacyOpeningStockRound2IT`; migration 1260 in `PharmacyTablesIT`)

| # | Criterion | Test | Type |
| --- | --- | --- | --- |
| T78-L8-01 | A row sent again with the same seven cells writes nothing: line version and `updated_at` unchanged, issue rows identical (count, superseded count and ids), the same issues answered, `receivedRows` unchanged; the request still counts | `anUnchangedResendWritesNothingAndAnswersTheSameIssuesWhileAChangedCellIsWritten` | IT |
| T78-L8-02 | A request with one changed and one unchanged row writes only the changed one (version 1, `updated_at` set, old error superseded); a different letter case of the product code is a changed cell | same test | IT |
| T78-L8-03 | rowCount 412: 6 answered-200 requests (three batches, then three unchanged resends), the 7th and the 8th are 409 `import-state` "This import can no longer be changed.", nothing written (lines and issues identical), the counter stays 6; refused requests (400 twice, 403, 404) are not counted; the import stays DRAFT and submit still works; the counter stays 6 | `rowCount412AllowsSixAnsweredRequestsTheSeventhIs409ImportStateAndWritesNothingAndRefusedRequestsDoNotCount` | IT |
| T78-L8-04 | The cap follows 2 x ceil(rowCount / 200): rowCount 1 and 200 give 2, 201 and 400 give 4, 401 gives 6 | `theCapFollowsTwiceTheNumberOfBatchesOfTheFileAtTheBoundaries` | IT |
| T78-L8-05 | 8 parallel requests of one uploader on a cap of 2: exactly 2 answer 200 and 6 answer 409, counter 2, one line | `parallelRequestsCannotPassTheCapTogether` | IT |
| T78-L8-06 | The counter is frozen once WAITING: the trigger refuses a change together with an approve or a reject (23514 "totals are fixed once submitted"); a DRAFT may change it; never negative (`ck_opening_stock_import_line_request_count`); an approve leaves it as it was | `theCounterIsFrozenOnceTheImportWaitsAndIsOnlyEverNonNegative` | IT |
| T78-L8-07 | A new import starts at 0 (a used-up draft is replaced by starting again, the way the contract names) | `theCounterStartsAtZeroForEveryNewImportAndANewStartGivesANewBudget` | IT |
| T78-L8-08 | The cap and the counter are per import and per clinic; another clinic gets 404 on the import | `theCapAndTheCounterBelongToTheClinicNotToAnotherOne` | IT |
| T78-L8-09 | Migration 1260 applies last of the 12 pharmacy migrations, none failed, and the Flyway checksum of each applied migration 1000 to 1260 equals the CRC-32 of its file (no checksum change on 1000 to 1250) | `PharmacyTablesIT.theTwelvePharmacyMigrationsAreAppliedInOrderWith1260LastNoneFailedAndTheChecksumsOf1000To1250AreTheFilesAsTheyAre` | IT |
| T78-I4-01 | `uploaded_by`, `created_by` and `decided_by` hold the caller's membership id and never the user id: after start, submit, approve (second owner), reject (second owner), and the owner who uploaded rejecting his own file; every id written exists in `platform.membership` | `uploadedByAndDecidedByAreTheMembershipIdOfTheCallerNeverTheUserId` | IT |
| T78-N13-01 | The template example row is `#EXAMPLE,PRODUCT-CODE,...`, no `G-0001` text anywhere, served as `text/csv` also for an `Accept: application/json` client | `PharmacyOpeningStockRound2IT.theTemplateExampleRowUsesThePlaceholderNotASampleCatalogueCode`, `PharmacyOpeningStockIT.theTemplateIsTheFixedCsv...` | IT |

Note for the owner of the code: the cap is checked before the rows of the request are validated, so after the cap a request with a bad body also answers 409, not 400. The contract does not say which comes first; the tests do not assert it.

### 18.5 N-8 · the seed and Java agree (`PharmacySeedDupKeyIT`)

| # | Criterion | Test | Type |
| --- | --- | --- | --- |
| T78-N8-01 | For every one of the 30 seeded catalogue rows `ProductKeys.dupKey(salt, strength, form)` equals the stored `dup_key` | `theJavaKeyEqualsTheStoredKeyOfEverySeededCatalogueRow` | IT |
| T78-N8-02 | G-000110 (`amoxicillin + clavulanic acid|500mg+125mg|tablet`), G-000120 (21 g, `oral rehydration salts|21000mg|powder`), G-000130 (500 g, `absorbent cotton|500000mg|roll`) have the documented keys in the database and in Java | `thePlusAndTheGramRowsHaveTheDocumentedKeys` | IT |
| T78-N8-03 | Unit level, all 30 rows: every space of salt, strength and form replaced by a no-break space (U+00A0), padded and doubled, gives the stored key | `aNonBreakingSpaceInAnyPartGivesTheStoredKeyOfEverySeededRow` | IT |
| T78-N8-04 | API level: the duplicate check with no-break spaces in salt, strength and form finds G-000110 and G-000120; creating an own product typed with no-break spaces is 409 `possible-duplicate` and saves nothing | `aTypedProductWithNonBreakingSpacesFindsItsCatalogueTwinThroughTheDuplicateCheck` | IT |

### 18.6 e2e added or changed (API mocked in the browser, as before)

| # | Criterion | Test title (start) |
| --- | --- | --- |
| T78-E-01 | QA-78-4: the Products and Opening stock tabs show their content or the loading text at once (2 s) and then the content; the idle helper is removed | "QA-78-4: the Products tab ...", "QA-78-4: the Opening stock tab ..." (also the intro sentence) |
| T78-E-02 | 2 MB, column 8 and 5,001 rows are refused, nothing sent (an empty 8th cell, and 5,000 rows plus a blank row, are accepted) | "error path: a file over 2 MB, a value in column 8 and 5,001 rows ..." |
| T78-E-03 | `columns` of the start request = the file's header, trimmed | "the start request carries the columns of the file ..." |
| T78-E-04 | Line requests at most 200 rows and 60 KB, every row once in order; a clean 450-row file goes 200, 200, 50 | "line requests have at most 200 rows ...", "a clean file of 450 short rows ..." |
| T78-E-05 | Lost submit answer, retry: the review opens with no lines request and no second submit; "File sent for approval" only for WAITING_APPROVAL | "error path: the answer to the submit is lost ...", "error path: a lost answer for a file that was not accepted ..." |
| T78-E-06 | Leaving the tab while sending stops the upload, no submit, the draft stays | "leaving the Opening stock tab while a file is being sent ..." |
| T78-E-07 | The `open-import` sentence on store switch-off, the store form, local settings and own-product edit; the forms stay open and keep what was typed | pharmacy-setup "error path: a store with an open opening-stock file ...", products "error path: the open-import sentence ..." |
| T78-E-08 | Text matchers: intro, approved note, "Only an owner can approve this import.", the not-accepted note, the caption, `note-hint` "Do not add patient details." | the existing upload, not-accepted, pharmacist, approve and reject tests |
| T78-E-09 | N-14, one real full-stack happy path | NOT DONE, see section 19 |

## 19. Results of fix round 2 (18 Nov 2026, 20:30 to 22:40 IST)

### 19.1 Quiet tree and start conditions

- Cap present: `JdbcOpeningStockRepository.java` holds `line_request_count` (edited 20:18); `OpeningStockService.addLines` checks it under the import row lock.
- Other Maven runs: a full `verify` of another session ran 18:44 to 20:24 (3,232 ITs, the six expected failures of section 18.1) and a second one that started at 20:19 and was gone at 20:30 (its surefire reports show no failure; I do not know why it ended). I started at 20:30:34, when `wmic` showed only PID 12408 (VS Code) and the known 6964. Nothing was run from a copied build. After the gate ended, a `mvn spring-boot:run` of someone else (PID 3456, port 8080) was running; it was not touched.

### 19.2 Targeted run (`mvn verify` with `-Dit.test=Pharmacy*IT,RowLevelSecurityCatalogIT`, 20:30 to 20:42, BUILD SUCCESS)

| Class | Tests | Pass | Fail |
| --- | --- | --- | --- |
| PharmacyCandidatesWithoutStoreIT | 8 | 8 | 0 |
| PharmacyConcurrencyIT | 6 | 6 | 0 |
| PharmacyMenuAndContextIT | 18 | 18 | 0 |
| PharmacyModuleOffIT | 13 | 13 | 0 |
| PharmacyOpenImportIT (new) | 12 | 12 | 0 |
| PharmacyOpeningStockIT (template line changed) | 27 | 27 | 0 |
| PharmacyOpeningStockRound2IT (new) | 9 | 9 | 0 |
| PharmacyOwnProductIT | 19 | 19 | 0 |
| PharmacyProductCatalogueIT | 17 | 17 | 0 |
| PharmacyProductTablesIT | 8 | 8 | 0 |
| PharmacySampleGuardIT | 5 | 5 | 0 |
| PharmacySeedDupKeyIT (new) | 4 | 4 | 0 |
| PharmacyStaffMappingIT | 21 | 21 | 0 |
| PharmacyStoreDenialPoolIT (new, pool of 3) | 5 | 5 | 0 |
| PharmacyStoreGuardIT (probe is a `PharmacyApi`) | 10 | 10 | 0 |
| PharmacyStoresIT | 24 | 24 | 0 |
| PharmacyStoreStaffRoleIT | 4 | 4 | 0 |
| PharmacyTablesIT (12 migrations) | 15 | 15 | 0 |
| PharmacyUnitSettingsIT | 15 | 15 | 0 |
| RowLevelSecurityCatalogIT | 15 | 15 | 0 |
| **Total** | **255** | **255** | **0** |

Before this round the same list was 219 + 6 (`PharmacyConcurrencyIT`) = 225; now 255, that is 30 new tests (12 + 9 + 5 + 4). The same 255 passed again inside the gate below.

All new classes passed at the first run. No application defect was found by any backend test of this round, and no test was weakened to get there.

### 19.3 Gate (`node scripts/validate.mjs db`, 20:42:35 to 22:20, 1 h 38 min, exit 0, "validate db: PASSED")

- It is the static migration checks and then `mvn -B -q -f backend/pom.xml verify`, the same command as `validate backend`, so it counts for both.
- Unit phase (surefire): **5,999 tests in 417 classes, 0 failures, 0 errors, 0 skipped** (was 5,974 at the last gate; 25 more from the java-developer, the angular side has no Java).
- Integration phase (failsafe): **3,262 completed, 0 failures, 0 errors, 25 skipped** (the same 25 older skips, none in SAH-78; was 3,226 + 6; now 30 more of mine and the 6 changed ones are green).
- No IPD test failed, so nothing is listed as outside SAH-78.
- `validate frontend`: not run (not asked; it stops at the known DEC-002 flag).

### 19.4 Playwright (msedge, fresh `npm --prefix frontend run build` of 22:21, `serve.mjs`, workers 2, phone-360 and desktop-1440)

| Spec | Runs | Pass | Fail |
| --- | --- | --- | --- |
| `pharmacy-setup.spec.ts` (20 tests x 2) | 40 | 39 | 1 |
| `pharmacy-products-opening-stock.spec.ts` (29 tests x 2) | 58 | 57 | 1 |
| **Total** | **98** | **96** | **2** |

- The 2 QA-78-4 tests now pass in both projects (4 of 4). The defect is closed; the idle workaround is gone from the spec.
- All new tests of section 18.6 pass in both projects, except the one in defect QA-78-5 on phone-360 (it passes at desktop-1440).
- Failure 1 is a defect: **QA-78-5** (section 20). Failure 2 is intermittent: `pharmacy-setup.spec.ts` "a person with two stores is asked to pick a working store ... a lost store shows the server sentence" (line 596). It failed once on phone-360 in the big run, then failed 2 of 8 solo runs (1 of 3 on phone-360, 1 of 5 on desktop-1440) and passed the next 16 solo runs (8 on each project): 3 failures in 27 runs. In each failure the second click on the working-store select (line 614) did not show the option "Main Pharmacy" within 15 s. The test is from the first WP-A run and was not changed in this round. Cause not found (no failing trace kept: the next passing run cleared it). Left as it is; to be watched.

### N-14 · one real full-stack happy path: WRITTEN, NOT RUN

`frontend/e2e/pharmacy-full-stack.spec.ts` (opt-in with `E2E_BACKEND` and `E2E_BACKEND_LOG`; it is listed and compiles, and it skips itself in a normal run): a real sign-up and set-up wizard of a new clinic, the Pharmacy switch (as `sahaj_app` with the clinic bound, the way the design note says), a Central Store, an adopted catalogue product, a real pharmacist who signs in and uploads a CSV in the browser, and the owner who approves it in a second browser; the real backend is checked at the end (APPROVED, `decidedBy` differs from `uploadedBy`, store has stock, total ₹ 12,50,500.00).

Why it was not run: I built the real backend jar (`target/sahaj-backend-0.1.0-SNAPSHOT.jar`, profile test, port 8081, started 22:33 and stopped again by me) and then the permission system refused the run command (reason given: "Undermining Oversight"). The spec reads the one-time sign-in codes of the test profile out of the backend log, as the integration tests do, and writes the module switch with `psql` using the application database password from the environment. I did not try another way round the refusal. What the human can do: run the two commands in the header of the spec by hand (start the jar, then `E2E_BACKEND=http://127.0.0.1:8081 E2E_BACKEND_LOG=<log> npx playwright test pharmacy-full-stack --project=desktop-1440`), or allow that command in the permission rules.

Things that remain true without N-14: the CSV is read in the browser, the server checks every row, the template, the approval and the limits are covered by the 255 backend tests and by mocked e2e. What N-14 would add: the browser and the real server agree on the request shapes of start, lines and submit.

## 20. Defects of fix round 2 (app code is not changed by QA)

| ID | Where | Test | Expected | Actual | Severity | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| QA-78-4 | `setup.page.ts` `@defer` of Products and Opening stock | the 2 "QA-78-4" tests | the tab shows content or loading text at once | FIXED (`@defer (on immediate)` plus `@placeholder`): 4 of 4 runs pass | closed | angular-developer |
| QA-78-5 | `frontend/src/app/features/pharmacy/products/product-form.dialog.ts` (`PRODUCT_FORM_CONFIG`, panel `sheet-panel`) with `own-product-form.component.ts` in `layout="sheet"`; found by "error path: the open-import sentence shows in Local settings ... and in the own-product edit" on phone-360 | same test, project phone-360 only | the Edit own product sheet can be saved and cancelled on a 360 x 740 screen (every other sheet, e.g. Local settings, can) | `mat-dialog-surface` is 900 px high inside a pane limited to 740 px and does not scroll (scroll height equals client height, overflow of the pane is visible): "Save" (`save-product`) sits at y 760 to 795, and after scrolling it into view at y 837 to 881 of a 740 px window, so Playwright reports "element is outside of the viewport" and a person cannot reach Save or Cancel. At desktop-1440 the test passes. | Medium (an owner cannot save an own-product edit on a phone-sized screen; the data is safe) | angular-developer: let the sheet body scroll (the form body gets `min-height: 0; overflow: auto` inside a column whose height is capped by the pane, as the Local settings sheet does) |

Observations (not defects):
- Contract note: the line-request cap is checked before the rows are validated (a bad body after the cap answers 409, not 400). Not in the contract; not asserted.
- OQ-78-42 (a draft whose starter never returns holds the store): the tests record today's rule, that a DRAFT cannot be rejected (409 `import-state`, `afterRejectTheStoreAndTheProductsMayChangeAgain`). If the human chooses option (a) at the merge ask, that line changes.
- Known limits recorded, not tested: N-10 (search timing on the real catalogue), N-12 (2^53 totals), F-18 (hi/mr native check), I3 (export prefixes, SAH-79).
- `frontend/e2e` is still in `.gitignore` (line 8): the specs, including the new full-stack one, are written but not tracked (human).
- Open from the earlier list: the 300 ms search target is not tested.

## 21. Story 78-8 · Vendors (package C; contract 0.2.0, switch-on 0.2.1) · `PharmacyVendorsIT`, `PharmacyHardeningMastersIT`, e2e `pharmacy-package-c.spec.ts`

Author: qa-engineer · Date: 19 Nov 2026. Sections 21 to 26 cover SAH-78 package C (78-8, 78-9, 78-10) and the hardening round (DEC-076, DEC-077, DEC-078, contract 0.2.1). Test files are in `backend/src/test/java/com/sahaj/platform/it/` and `frontend/e2e/`. Shared set-up: `Sah78CSupport` (vendor, rule, series, reason and template bodies, `issueNumber` through `pharmacy.next_document_no`, the non-owner caller table) and `Sah79Support` (rolled-back probes of the tables). All names, GSTINs and licence numbers are invented.

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-8-01 | A new clinic has no vendor; the list writes nothing; default page 0, size 25 | `PharmacyVendorsIT.aNewClinicHasNoVendorAndTheListWritesNothing`; e2e "table shows each vendor" | IT, e2e |
| T78-8-02 | Owner adds a vendor with the form fields; empty "Supplies to" means the Central Store; version 1; audit `PHARMACY_VENDOR_CREATED` | `anOwnerAddsAVendorWithTheFieldsOfTheFormAndItSuppliesTheCentralStore...`; e2e "owner adds a vendor" | IT, e2e |
| T78-8-03 | `clientKey` replay by the same caller 200 with the same vendor; another caller 409 `client-key-reused` | `theSameClientKeyFromTheSameOwnerAnswers200...` | IT |
| T78-8-04 | Name unique ignoring capitals and any status: 409 `vendor-name-in-use` "That vendor is already in the list"; another clinic may use the name | `aRepeatedNameIgnoringCapitalsIs409AgainstAnyStatus...`; e2e "error path: an empty name ... a repeated name" | IT, e2e |
| T78-8-05 | Every field error together, with the story sentences (name, GSTIN, licence number and date, phone, credit days, return days, percent, stores) | `everyFieldErrorIsReportedTogetherWithItsMessageAndNothingIsSaved` | IT |
| T78-8-06 | Boundaries: credit days 0 and 365, return days 0, percent 0 and 100.00 accepted; 366, 100.01, -1, 1.234, 2.5 days refused | `theBoundariesOfTheNumbersAreAcceptedAndTheOnesBesideThemRefused` | IT |
| T78-8-07 | Unknown field 400 `validation` on `body`; missing required field 400; unreadable body 400 `malformed-request` | `aRequestWithAnUnknownFieldOrAMissingRequiredField...` | IT |
| T78-8-08 | Licence not required: licence values ignored and stored null, state NOT_REQUIRED | `aLicenceNotRequiredIgnoresTheLicenceValues...` | IT |
| T78-8-09 | Supplies to takes only ACTIVE stores of this clinic ("Choose active stores") | `suppliesToTakesOnlyActiveStoresOfThisClinic...` | IT |
| T78-8-10 | Blocked is computed in IST: valid through the licence date, blocked from the next day; never stored | `aVendorIsBlockedFromTheDayAfterTheLicenceDate...` (boundary today and yesterday, the row stays ACTIVE) | IT |
| T78-8-11 | A Blocked vendor can be edited; a date moved to today or later makes it Active at once | `aBlockedVendorCanBeEditedAndALicenceDateMovedToTodayOrLater...` | IT |
| T78-8-12 | Default list Active and Blocked; filter ACTIVE, BLOCKED, INACTIVE, ALL; name search (a % is literal); paging; bad filter, page, size or a 61-character search 400, boundaries accepted | `theDefaultFilterShowsActiveAndBlocked...`, `aBadFilterOrPageOrSizeOrALongSearchIs400`; e2e "the status filter" | IT, e2e |
| T78-8-13 | Products count = active products naming the vendor as preferred | `theProductsCountIsTheActiveProductsThatNameTheVendorAsPreferred` | IT |
| T78-8-14 | Edit changes fields, bumps the version, audits before and after; a save that changes nothing writes nothing | `anEditChangesTheFieldsBumpsTheVersion...`; e2e "owner edits a vendor" | IT, e2e |
| T78-8-15 | Stale version 409 `settings-changed` with the current vendor; rename to another vendor's name 409 | `aStaleVersionIs409SettingsChangedWithTheCurrentVendor...` | IT |
| T78-8-16 | Missing or negative version 400; malformed path id 400 `bad-request`; unknown vendor 404 | `aMissingOrNegativeVersionOrMalformedPathIdIs400...` | IT |
| T78-8-17 | Switch off: INACTIVE, never deleted, repeat writes nothing, an inactive vendor may be edited and stays inactive; confirmation before it is sent | `switchingOffMakesTheVendorInactive...`; e2e "owner switches a vendor off with a confirmation" | IT, e2e |
| T78-8-18 | Owner only for every write (6 non-owner kinds); pharmacist and Central Store user read; doctor, nurse, front desk, billing 403 on read | `everyWriteByANonOwnerIs403...`; e2e "a pharmacist sees the table and no button" | IT, e2e |
| T78-8-19 | Another clinic's vendor 404 on GET, PUT, switch-off; list empty; `clientKey` does not cross the wall; row level security | `anotherClinicNeverSeesAVendorAndEveryOperationOnItIs404`, `theVendorTableRefusesATenantOtherThanTheBound...` | IT |
| T78-8-20 | Two creates of one name at once: one 201, one 409 | `twoCreatesOfTheSameNameAtTheSameMoment...` | IT |
| T78-8-21 | Vendor switch-on (0.2.1, DEC-078 item 7): INACTIVE to ACTIVE, or BLOCKED when the licence date has passed; idempotent; one audit `PHARMACY_VENDOR_SWITCHED_ON`; 403 non-owner; 404 other clinic and unknown id; 400 malformed id; module off 403 | `PharmacyHardeningMastersIT.switchOnMakesAnInactiveVendorActiveAgain...`, `aVendorWithAnExpiredLicenceComesBackBlocked...`, `switchOnIsOwnerOnlyAnotherClinicsVendorIs404...`, `withTheModuleOffSwitchOnAnswers403ModuleOff...`; e2e switch-on of a vendor with an expired licence | IT, e2e |
| T78-8-22 | Screen: Blocked chip "Blocked · licence expired", licence text, "Not required (consumables)", list error with Try again, no sideways scroll | e2e: five vendor tests at phone-360 and desktop-1440 | e2e |

Not tested (not built yet): the two vendor credit levels `damagedCreditPercent` and `expiredCreditPercent` (0.2.1, DEC-078 item 5; they belong to SAH-79 WP-E). The angular form already carries the fields (`f-vendor-damaged-credit`, `f-vendor-expired-credit`); the java request body does not accept them yet (closed body, 400). Listed for the java-developer in the handoff.

## 22. Story 78-9 · Approval rules (package C; 0.2.0 and 0.2.1) · `PharmacyApprovalRulesIT`, `PharmacyHardeningMastersIT`, e2e

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-9-01 | Before the first save: STANDARD with no amounts, version 0, review needed, nine rows in order, escalation defaults, three fixed rules with their sentences, no delegate; the read writes no row | `beforeTheFirstSaveTheRulesAreStandard...`; e2e "before the first save the screen says Review these rules before use." | IT, e2e |
| T78-9-02 | Stores without an approver listed while a rule uses STORE_APPROVER; the current delegate shown; none when no rule uses it | `storesWithNoApproverAreListed...` | IT |
| T78-9-03 | Save of all nine rows: version 1, preset CUSTOM, review banner gone, savedBy, limits in paise, audit | `aSaveOfAllNineRowsMakesVersionOne...`; e2e "owner changes the purchase order limit and saves" | IT, e2e |
| T78-9-04 | A save identical to the current version writes nothing; a change makes the next version; version 1 stays as it was; versions newest first | `aSaveIdenticalToTheCurrentVersionWritesNothing...` | IT |
| T78-9-05 | Stale `baseVersionNo` 409 `rules-changed` with the current rules, for save and preset; two owners at once: one wins | `aStaleBaseVersionIs409RulesChanged...`, `twoOwnersSavingAtTheSameMoment...`; e2e "error path: someone else saved first" | IT, e2e |
| T78-9-06 | Validation (all together, field paths): type missing or repeated, more than 2 levels, level 2 without level 1, amount missing or negative, `ABOVE_PERCENT` only for the discount (and the rate rise), `ABOVE_AMOUNT` never for the discount, percent range, HIGH_VALUE_ITEM needs the high-value amount, STORE_TYPE needs types, URGENT_PRIORITY only for indent and transfer, escalation 1 to 168 hours, urgent 5 to 1440 minutes and indent only, fixed rules not accepted | `aMissingOrRepeatedTransactionType...`, `levelsMustBeAtMostTwo...`, `escalationOutsideItsRange...`, `theHighValueAmountMustBeInRange...` | IT |
| T78-9-07 | Allowed kinds and boundaries accepted (168 h, 1,440 min, 100 %, store types) | `theKindsThatAreAllowedAreAccepted` | IT |
| T78-9-08 | Presets: LEAN (purchase order only), STANDARD, STRICT; "Enter the limit" on `limits.<TYPE>`; "no amount condition" makes the owner step ALWAYS; both given 400; an entry the preset does not need 400; escalation defaults; high-value amount kept; same preset again writes nothing | `leanTakesTheOwnerLimitForThePurchaseOrderOnly...`, `aMissingLimitIs400...`, `standardPutsTheStoreApproverFirst...`, `strictPutsTheStoreApproverThenTheOwner...`, `aPresetKeepsTheHighValueAmount...`, `anUnknownPresetOrCustomOrMissingLimits...` | IT |
| T78-9-09 | Versions: list newest first, one version immutable, unknown version 404, not a whole number 400, paging checked | `theVersionEndpointsAnswer404...` | IT |
| T78-9-10 | Owner only for save and preset (6 non-owner kinds); pharmacist and Central Store user read; doctor, nurse, front desk, billing 403 on read | `everyWriteByANonOwnerIs403...`; e2e "a reader sees the rules as text with no control" | IT, e2e |
| T78-9-11 | Another clinic has its own versions and defaults | `anotherClinicHasItsOwnVersionsAndItsOwnDefaults...` | IT |
| T78-9-12 | Preset sheet on the screen: the confirmation text, the limits the server lists (one for Lean, nine for Strict, a percentage for the rise and the discount), "Enter the limit" shown, nothing sent until Apply, "No amount condition" | e2e "the preset sheet asks for exactly the limits the server lists", "the Strict sheet asks for a percentage..." | e2e |
| T78-9-13 | Hardening (0.2.1, DEC-078 items 1 to 3): `presets[].limitTypes` per preset in TransactionType order on every answer; each preset applied with exactly the listed limits and each missing one 400 on its own field; the rate rise limit is ALWAYS or a percent (an amount or another kind 400 on the level's condition); a preset limit for the rise is a percent; the largest amount limit is ₹ 9,99,99,999 = 9,999,999,900 paise (one paise more, 99,999,999,900 and 100,000,000,000 refused) for a rule and for a preset limit | `PharmacyHardeningMastersIT.theRulesAnswerOneEntryPerPreset...`, `eachPresetIsAppliedWithExactlyTheLimits...`, `theRateRiseLimitIsAlwaysOrAPercentage...`, `aPresetLimitForARateRiseIsAPercent...`, `theLargestAmountLimitIs9_99_99_999Rupees...`, `theSameLargestAmountHoldsForAPresetLimit`; e2e "an amount over ₹ 9,99,99,999 is refused on the field" | IT, e2e |

The preset tests of 22 read `presets[].limitTypes` when the server answers it (0.2.1) and fall back to the 0.2.0 sets (LEAN: purchase order; STANDARD: five types; STRICT: all nine), so they pass before and after the hardening. The role matrix of mockup 05 is not built (DEC-078 item 8) and not tested.

## 23. Story 78-10 · Numbering, reasons, print templates (package C) · `PharmacyNumberingIT`, `PharmacyReasonsTemplatesIT`, `PharmacyHardeningMastersIT`, e2e

| # | Acceptance criterion | Test | Type |
| --- | --- | --- | --- |
| T78-10-01 | Twelve series with the defaults (PO-, GRN-, IND-, TRF-, SB/ with the financial year and 6 digits, DN-, AUD-, RET-, DMG-, ADJ-, and from V2026_12_17_1800 CREDIT_NOTE `CN/` with the financial year, 6 digits, restarts each year, example CN/<FY>/000001, and IPD_ISSUE_SLIP `IS-`, 4 digits, example IS-0001), examples, nothing written | `PharmacyNumberingIT.theListHasTenRowsWithTheDocumentedDefaultsAndWritesNothing` (the name still says Ten; it asserts all twelve rows); e2e "
| T78-10-02 | A change: example and rendered length follow, saved true, version bumps, audit; unchanged save writes nothing | `anOwnerChangesTheSeries...`, `aSaveThatChangesNothing...`; e2e "owner changes a prefix" | IT, e2e |
| T78-10-03 | Prefix 1 to 6 of letters, digits, - or /; length 4 to 8; rendered length 16 accepted and 17 or more refused; restart needs the financial year | `thePrefixAndTheLengthAreChecked`, `aRenderedNumberOverSixteenCharacters...`, `restartingEachFinancialYearNeedsTheFinancialYear...`; e2e "a prefix of 7 characters is refused" | IT, e2e |
| T78-10-04 | One prefix per type ignoring case, also against another type's default and the five SAH-79 defaults: 409 `prefix-in-use` | `twoDocumentTypesCannotUseTheSamePrefix...`; e2e "error path: a prefix another type already uses" | IT, e2e |
| T78-10-05 | Stale version 409 `settings-changed` with the current series; unknown docType 400 `bad-request`; missing field 400 | `aStaleVersionIs409SettingsChanged...` | IT |
| T78-10-06 | `numbersIssued` true after the counter issued a number; `next_document_no` 1, 2 for each new type | `aNumberTakenByTheCounterShowsAsIssued...` | IT |
| T78-10-07 | Owner only; readers; clinic wall and per-clinic prefix rule | `everyWriteByANonOwnerIs403...`, `aSeriesOfAnotherClinicIsNeverSeen...` | IT |
| T78-10-08 | Series locking (0.2.1, DEC-078 item 4): before any number the prefix and format change freely; once a number of the current financial year is issued 409 `series-locked` "Numbers have already been issued this year." and nothing is written; a stale version is `settings-changed` first; a 400 is still a 400; a type never saved is locked by its first number (also the SAH-79 types and the bill); an old counter does not lock; the lock holds for every person and not across clinics; screen shows the series locked with no field | `PharmacyHardeningMastersIT.onceANumberOfTheTypeIsIssued...`, `aTypeThatWasNeverSavedIsLocked...`, `theLockCountsNumbersOfTheCurrentFinancialYearOnly...`, `aLockedSeriesIsLockedForEveryone...`; e2e "a series whose numbers were issued this year is shown locked" | IT, e2e |
| T78-10-09 | Reason lists: eleven in order, 36 seeded reasons with their labels, the list labels, nothing written by a read | `PharmacyReasonsTemplatesIT.theElevenListsComeInOrder...`; e2e "reasons: eleven lists" | IT, e2e |
| T78-10-10 | Add a reason (trim, 1 to 40 characters, appended at the end, audited, in every one of the eleven lists, not seen by another clinic); equal label ignoring case in the same list 409 `reason-exists` (also a switched-off one); unknown list 400 | `anOwnerAddsAReasonAtTheEnd...`, `aLabelIsOneToFortyCharacters...`; e2e "a reason that is already in the list" | IT, e2e |
| T78-10-11 | Switch off and on (seeded and own), idempotent, one audit each, per clinic; last active damage reason 409 "Keep at least one damage reason."; the fixed `receipt-reject.short` 409 `last-active-reason` "This reason is fixed."; unknown reason 404, unknown list 400 | `switchOffAndOnWorkOnSeededAndOwnReasons...`, `theLastActiveDamageReasonCannotBeSwitchedOff...`, `anUnknownReasonIs404...`; e2e reasons test | IT, e2e |
| T78-10-12 | `fixed` (0.2.1, DEC-078 item 6): `fixed: true` on `receipt-reject.short` only, `false` on every other reason including an own one in the same list; the screen shows no switch for it | `PharmacyHardeningMastersIT.theReasonItemCarriesFixedTrue...`; e2e "the fixed reason has no switch" | IT, e2e |
| T78-10-13 | Print templates: three rows, defaults, fixed fields, invoice `printable` false with `missing` GSTIN and DRUG_LICENCE_NO until Unit settings are set | `theThreeTemplatesHaveTheDefaults...`; e2e "print templates: three cards" | IT, e2e |
| T78-10-14 | Save: trim, header at most 3 lines of 1 to 80, footer at most 200 (empty is none), paper A4 or A5, unchanged save writes nothing, audit; stale version 409; unknown docType 400; fixed fields refused | `aSaveChangesTheHeaderFooterAndPaper...`, `theHeaderFooterAndPaperLimitsAreChecked`, `aStaleVersionIs409SettingsChanged...`; e2e "a header line over 80 characters" | IT, e2e |
| T78-10-15 | Owner only; readers; clinic wall | `everyTemplateWriteByANonOwnerIs403...` | IT |

## 24. Package C cross-cutting checks · `PharmacyPackageCCrossIT`

| # | Check | Test |
| --- | --- | --- |
| T78-C-01 | 18 operations: vendors 5, approval rules 5, numbering 2, reasons 4, templates 2 (10 of them write) | `thereAreEighteenOperationsAndTenOfThemWrite` |
| T78-C-02 | Module Off: all 18 answer 403 `module-off` for owner, pharmacist, store staff and nurse (72 calls); no row in 12 tables, no audit | `withTheModuleOffEveryOneOfTheEighteenOperations...` |
| T78-C-03 | The switch is per clinic and survives a switch Off and On with the data kept | `theModuleSwitchIsPerClinic...` |
| T78-C-04 | Unreadable body after the gate: module Off 403 `module-off`, a non-owner 403 `forbidden` (7 body operations x 4 malformed bodies x 6 kinds), never a 400; the owner past the gate gets 400 `malformed-request`; a bad path id or docType is read after the gate | `aMalformedBodyIsNeverReadBeforeTheGate...` |
| T78-C-05 | Non-owner 403 on every one of the 10 writes x 6 kinds of person; nothing written, no audit | `everyWriteOfThePackageByEverySixKindsOfNonOwner...` |
| T78-C-06 | Unauthenticated 401 on all 18 | `anUnauthenticatedCallIs401OnEveryOperation` |
| T78-C-07 | RLS: eleven new tenant tables show a clinic's rows to that clinic only; the other cannot delete (42501) or update; an insert naming another tenant is refused; with no tenant bound nothing is visible | `everyNewTenantTableShowsOneClinicsRows...`, `aRowCannotBeInsertedForAnotherTenant...` |

## 25. SAH-78 hardening round (DEC-076, DEC-077, DEC-078; contract 0.2.1) · `PharmacyHardeningImportIT`, `PharmacyHardeningMastersIT`

Written before the code. Both classes fail until the java-developer, the db-engineer and the architect's 0.2.1 behaviour are all in; a failure after that is a defect.

| # | Acceptance criterion | Test |
| --- | --- | --- |
| T78-H-01 | DEC-076: an owner (also the uploader) rejects a DRAFT with a reason; REJECTED, no `submittedAt`, store and products free at once, audit; OTHER needs a note; the uploader may still replace their draft by starting again | `anOwnerRejectsADraftWithAReason...`, `theUploaderMayStillReplaceTheirDraft...` |
| T78-H-02 | A pharmacist, nurse or doctor gets 403 rejecting a draft and nothing changes | `aPharmacistAndAnyoneWhoIsNotAnOwner...` |
| T78-H-03 | `import-state` for NOT_ACCEPTED, REPLACED, REJECTED and APPROVED on reject; one audit only | `anyOtherStateThanDraftOrWaiting...` |
| T78-H-04 | Database: DRAFT to REJECTED allowed (no `submitted_at`, with a reason and decider), DRAFT to APPROVED still refused, REJECTED final, no reason no rejection | `theDatabaseAllowsDraftToRejected...` |
| T78-H-05 | Cap by rows written, at most 2 x rowCount, counted in `line_rows_written`; a request over the cap refused whole; one changed row that still fits is accepted; after the cap any request including an empty or unreadable body or a row beyond the row count is 409 `import-state`; the import stays DRAFT and submit works | `theCapIsTwiceTheRowCount...`, `oneRequestThatWouldPassTheCap...`, `theCapFollowsTwiceTheRowCountAtTheBoundaries` (rowCount 1, 2, 5; 3 and 4 in the other two) |
| T78-H-06 | An unchanged resend is not counted (12 resends); only the changed row counts after a fix | `anUnchangedResendNeverCounts...`; also `PharmacyOpeningStockRound2IT.anUnchangedResendWritesNothing...` |
| T78-H-07 | A refused request (400, 403, 404) counts nothing; parallel requests cannot pass the cap (8 at once: 2 pass, 6 are 409) | `aBadRequestAnotherPharmacistOrAnUnknownImportCountsNothing`, `parallelRequestsCannotPassTheRowCapTogether` |
| T78-H-08 | Database: `line_rows_written` is never negative and never goes down in a DRAFT (-1, 1, 0 below 2 refused), is frozen with every transition out of WAITING_APPROVAL; the 0.1.3 counter `line_request_count` stays and is no longer written; a new import starts at 0; counter per clinic | `theRowCounterOnlyEverGoesUp...`, `theCounterStartsAtZero...` |
| T78-H-09 | Starts limit: 10 per person per store per IST day, the 11th 409 `import-limit` "Too many files today. Try again tomorrow."; nothing written and the current draft is not replaced; a `clientKey` replay answers 200 and is not a new start; another person and another store are not limited; the owner's starts count; 6 parallel starts after 8: 2 pass, 4 are 409 | `theEleventhStartOf...`, `theTenthStartIsStillFine...`, `parallelStartsOfOnePersonCannotPassTheLimit...` (not testable: the next IST day; no clock control in the IT) |
| T78-H-10 | A store switched off meanwhile: start, submit and approve answer 404 `not-found` and write nothing (the draft stays DRAFT, the waiting import stays waiting, no stock, no product has-stock); the database refuses an import for a store that is not ACTIVE | `startSubmitAndApproveAnswer404...`, `theDatabaseRefusesAnImportForAStoreThatIsNotActive` |
| T78-H-11 | A switch-off and a start at the same moment (6 rounds): one winner (200 and 404, or 409 `open-import` and 201), never an import on a switched-off store | `aSwitchOffAndAStartAtTheSameMoment...` |
| T78-H-12 | A product edit and a line write at the same moment (5 rounds): both 200 or the edit 409 `open-import`; the product is unchanged when refused | `aProductEditAndALineWriteAtTheSameMoment...` (the order of the two cannot be forced from outside: a smoke test of the lock) |
| T78-H-13 | `submit` flags a line whose product is no longer ACTIVE as an ERROR `product-unknown` (422, NOT_ACCEPTED) | `submitFlagsALineWhoseProductIsNoLongerActive...` |
| T78-H-14 | The clinic wall holds for reject and start | `anImportForAStoreOfAnotherClinicIsStill404...` |
| T78-H-15 | DEC-078 items 1 to 4, 6, 7: sections 21 (switch-on), 22 (preset `limitTypes`, rate rise percentage, ₹ 9,99,99,999), 23 (series locking, `fixed`) | `PharmacyHardeningMastersIT` |

Changed existing tests (the behaviour they pinned was replaced by a decision; not a failing test removed): `PharmacyOpenImportIT.afterRejectTheStoreAndTheProductsMayChangeAgain` and `PharmacyOpeningStockIT.theReasonIsRequired...` (a draft is now rejected by an owner, DEC-076); `PharmacyOpeningStockRound2IT` (the request-count cap tests, section 18, are replaced by the rows-written cap tests of T78-H-05 to T78-H-08, and the counters in `anUnchangedResendWritesNothing...` now read `line_rows_written`: 3, 3, 4, 5); the migration list test of `PharmacyTablesIT` (the twelve SAH-78 migrations are the first twelve; 1300 to 1390 are tested in `PharmacyWpDPostingDocsIT`); `PharmacyTablesIT` reason seed 13 becomes 36; `RowLevelSecurityCatalogIT` (see SAH-79.md).

## 26. e2e of package C and the two review items · `frontend/e2e/pharmacy-package-c.spec.ts`, `pharmacy-products-opening-stock.spec.ts`

`frontend/e2e` is still in `.gitignore` (human); the specs are written anyway. API mocked in the browser as in the other pharmacy specs. Run at phone-360 and desktop-1440. 29 tests: Vendors 9, Approval rules 9, Numbering and documents 11 (counts of titles; each runs in both projects: 58 runs).

| # | Test | Notes |
| --- | --- | --- |
| T78-C-E01 to E09 | Vendors: table and Blocked chip; add; empty name and repeated name; edit; switch off, filter, switch on; filter views; a pharmacist sees no button; list error with Try again; no sideways scroll | |
| T78-C-E10 to E18 | Approval rules: reader read-out; review banner; save with the version and paise; `rules-changed` error; amount over ₹ 9,99,99,999; Lean sheet; Strict sheet; saved set has no banner; no sideways scroll | |
| T78-C-E19 to E29 | Numbering and documents: twelve series; change a prefix; `prefix-in-use`; a prefix with a bad character and the field that takes 6 characters; locked series; a `series-locked` answer while the page is open; eleven reason lists, add, switch, fixed reason; duplicate reason; print template save and the missing GSTIN note; the 80 and 200 character boundaries of the header and footer; reader; no sideways scroll | |
| R3-5b | Code review round 3: `waitForTimeout(500)` in "leaving the Opening stock tab while a file is being sent stops the upload (N-5)" is replaced by polling until the number of line requests has not changed for four reads | done in `pharmacy-products-opening-stock.spec.ts` |
| QA-78-5 | "QA-78-5: the Edit own product sheet scrolls on a 360 x 740 phone, so Save and Cancel can be reached": sets 360 x 740 in both projects, checks that some part of the sheet has `overflow-y` auto or scroll with more content than space, scrolls with the mouse wheel, expects Save and Cancel in the viewport, presses Cancel, no sideways scroll | written before the angular fix: fails until QA-78-5 is fixed |

The 11 reason lists and the 12 series render what the server returns (the mock sends 11 and 12).

## 27. Results of this run (19 Nov 2026, 01:38 to 03:20 IST)

**Gate.** `node scripts/validate.mjs db` (full `mvn verify` on `sahaj_test`): the first run, started 01:38, ended `exit=1` at test-compile (the shared `target/classes` was being rebuilt by a second Maven that another session started at 01:45; see the observation below); the tests that count are those of that second `mvn verify` (it compiled all test classes of the shared tree, mine included, at 01:40 and ran from 01:45 to 03:00). Unit: **6,127 run, 0 failures**. Integration: **3,414 run, 17 failures, 1 error, 25 skipped**; every failing class is a pharmacy class of this work, no IPD or other class failed. After the test-side fixes below, a targeted `failsafe:integration-test` of the 8 touched classes ran 114 tests: **107 pass, 7 fail, and the 7 are product or migration defects** (section 28).

**First result: the migrations apply.** Flyway applied, on a database cleaned and migrated from scratch, `V2026_12_17_1270` to `1290` (the hardening round), `1300` to `1390` (WP-D) and also `1400` to `1700` (WP-E to WP-H, written by the db-engineer in parallel, 00:57 to 01:15): no failed row, in version order, checksums equal to the files (`PharmacyWpDPostingDocsIT.migrations1000To1390ApplyInOrder...` passes).

New and changed integration classes (tests run, pass):
| Class | Tests | Result |
| --- | --- | --- |
| `PharmacyVendorsIT` | 22 | 22 pass (after 2 test fixes: the vendor version starts at 0, not 1) |
| `PharmacyApprovalRulesIT` | 20 | 20 pass |
| `PharmacyNumberingIT` | 11 | 11 pass |
| `PharmacyReasonsTemplatesIT` | 12 | 12 pass |
| `PharmacyPackageCCrossIT` | 8 | 8 pass (after a test fix: the fixture saved no rule level) |
| `PharmacyHardeningMastersIT` | 14 | 14 pass (one test removed, see below) |
| `PharmacyHardeningImportIT` | 23 | 16 pass, **7 fail: defects D-1, D-2, D-3** |
| `PharmacyOpeningStockRound2IT` (edited) | 3 | 3 pass |
| `PharmacyOpenImportIT`, `PharmacyOpeningStockIT` (edited for DEC-076) | 12, 27 | failed on the 500 of D-1 in the full run; the reject of a draft is the only changed line |
| `PharmacyTablesIT`, `RowLevelSecurityCatalogIT` (edited) | 15, 15 | pass after the edits below |
| `PharmacyWpDTablesIT`, `PharmacyWpDRulesIT`, `PharmacyLedgerPartitionsIT`, `PharmacyWpDPostingDocsIT` | 11, 20, 6, 11 | all pass (see SAH-79.md) |

Pass or fail on the first run and what changed: the 17 failures of the full run were 8 `PharmacyHardeningImportIT` (D-1 three times, D-1 in the counter test, D-1 in the DB test, D-2 twice, D-3 once), 3 of reject of a draft in `PharmacyOpenImportIT` and `PharmacyOpeningStockIT` (D-1), and 6 faults of mine, fixed: `PharmacyLedgerPartitionsIT` (read `tenant_id` of a partition through `information_schema`, which hides tables `sahaj_app` has no privilege on; now `pg_attribute`), `PharmacyPackageCCrossIT` (the fixture saved rules with no level, so `approval_rule_level` was empty), `PharmacyVendorsIT` twice (the vendor version starts at 0; the contract example shows 1), `PharmacyWpDPostingDocsIT` (the application cannot insert a `reason_default` row at all, 42501), `PharmacyTablesIT` (the "23 tables in the pharmacy schema" test now sees the 98 tables of SAH-79 WP-D to WP-H and the 41 partitions; it keeps the exact rule for the 23 and the universal rule for the rest), `RowLevelSecurityCatalogIT` (WP-E to WP-H added 25 tenant tables, 9 of them insert-only: they are listed as the catalogue shows them, not yet reviewed by a WP-E to WP-H plan). `PharmacyHardeningMastersIT.theLockCountsNumbersOfTheCurrentFinancialYearOnly...` is removed: it cannot be written. The counter guard (migration 1240) refuses a counter whose time goes back ("document counter can only go up"), so a test cannot make a counter old, and the financial year cannot be moved in an integration test. It is left as a comment in the class and belongs to the unit test of `NumberingService`.

**e2e (msedge).** `pharmacy-package-c.spec.ts` 29 tests x 2 projects = 58, `pharmacy-setup.spec.ts` and `pharmacy-products-opening-stock.spec.ts`: final run of the three specs at 3 workers, msedge, both projects: **158 of 160 pass**; the 2 failures are timeouts that pass alone (the known intermittent "a person with two stores is asked to pick a working store" of `pharmacy-setup.spec.ts`, now at line 599, one failure in about 10 runs, cause still not found; and "owner: local settings of an adopted product are saved" once, which passed in 3 of 3 re-runs). Package C alone: 58 of 58 in two of three full runs. QA-78-5 passed at the phone size in this build (the angular fix is already in the tree); R3-5b is in. One older test changed: `pharmacy-setup` "owner: one sidebar entry opens Inventory setup ..." asserted three setup tabs, the owner now has six (the order is asserted). One intermittent: package C "the status filter" failed once in 60 at 3 workers and passed 3 of 3 alone.

## 28. Defects found by this run (app code is not changed by QA)

| ID | Where | Test | Expected | Actual | Severity | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| D-1 | `backend/src/main/resources/db/migration/V2026_12_17_1350__pharmacy_opening_stock_import_posted.sql` lines 34 to 86 (`create or replace function pharmacy.guard_opening_stock_import_update()`), which runs after `V2026_12_17_1270__..._hardening.sql` lines 53 to 120 | `PharmacyHardeningImportIT` (`theDatabaseAllowsDraftToRejected...`, `anOwnerRejectsADraft...`, `theUploaderMayStillReplaceTheirDraft...`, `anyOtherStateThanDraftOrWaiting...`, `theRowCounterOnlyEverGoesUp...`), `PharmacyOpenImportIT.afterRejectTheStoreAndTheProductsMayChangeAgain`, `PharmacyOpeningStockIT.theReasonIsRequired...` | After 1270, an owner can reject a DRAFT (DEC-076), the counters never go down in a DRAFT and `line_rows_written` is frozen in WAITING_APPROVAL | 1350 replaces the guard with a body written before 1270: its DRAFT branch lists `('DRAFT', 'NOT_ACCEPTED', 'WAITING_APPROVAL', 'REPLACED')` (no `REJECTED`) and it has no counter rule. The final function in the database refuses DRAFT to REJECTED ("opening stock import cannot go from draft to REJECTED"), so `POST .../reject` on a draft answers **500 internal-error** (the repository's update at `JdbcOpeningStockRepository:309` raises `DataIntegrityViolationException`), and a counter can go down. | High (DEC-076 and DEC-077 item 2 do not work; a 500 for a normal owner action) | db-engineer: a forward migration after 1350 (or 1350 carries the 1270 body) so the last `guard_opening_stock_import_update` has all three: posting, DRAFT to REJECTED, counters; plus a check that the order of `create or replace` between 1270 and 1350 is right |
| D-2 | `JdbcStoreRepository` line 92 (store switch-off), `JdbcProductRepository` line 211 (product edit), against `JdbcOpeningStockRepository` (start, lines) | `PharmacyHardeningImportIT.aSwitchOffAndAStartAtTheSameMoment...` (1 of 3 runs), `aProductEditAndALineWriteAtTheSameMoment...` (2 of 3 runs) | One winner each time (DEC-077 items 1: the store lock first, the product row locked before the open-import check), and a deadlock or lock timeout is **503 database-busy**, nothing saved (design note section 6) | Both pairs **deadlock** (SQLSTATE 40P01, "deadlock detected") now and then, and the answer is **500 internal-error** (the log says `Unhandled error type=PessimisticLockingFailureException`): nothing in `PharmacyProblemHandler` or `ProblemExceptionHandler` maps 40P01 or `PessimisticLockingFailureException` to 503 | High for the 500 and Medium for the deadlock (integrity is safe: PostgreSQL ends one of the two; the person sees an error and the other call wins) | java-developer: take the locks in one order everywhere (store, then import; product, then line) and map 40P01, 55P03 and 57014 to 503 `database-busy` in the pharmacy handler |
| D-3 | `OpeningStockService` lines request (the cap check is after the body is read) | `PharmacyHardeningImportIT.afterTheCapABodyThatReadsAsJsonButBreaksAFieldRuleAnswers409AndABodyThatCannotBeReadAnswers400` | pharmacy.yaml 0.2.1: "once the cap is used up, any further request, a bad body included, answers 409 import-state" | A body that is not JSON (`{"rows":`, `not json`, `[]`) answers 400 `malformed-request`; an empty `rows` list, a good row or a row beyond the row count already answer 409 | Low (the wording "a bad body" may mean a body that parses; the sentence of the contract is the reason for the test) | architect to say which, then java-developer; the test is kept as written |
| O-1 | Test environment | `validate db` first run | one `mvn verify` at a time on the shared `target/` | A second `mvn -B -q -f backend/pom.xml verify` started at 01:45 while the first was compiling; the first ended with a compile error (the class files of `target/classes` were missing for a while) and its output is lost | Observation | tech lead: one gate at a time |

Observations (not defects): (1) the vendor `version` of a new vendor is 0 (the contract example shows 1: the schema minimum is 0); (2) the vendor credit levels `damagedCreditPercent` and `expiredCreditPercent` are not accepted by the java body yet (not tested, section 21); (3) the `highValueThresholdPaise` maximum stays 99,999,999,900 in the schema of `ApprovalRulesInput` while the condition limit is 9,999,999,900 (0.2.1); the tests follow the schema; (4) `GET /document-series` answers ten rows (0.2.0); `CREDIT_NOTE` and `IPD_ISSUE_SLIP` of 0.3.0 are not built and not tested; (5) `frontend/e2e` is still in `.gitignore` (human).

## 29. Round 5 and 6 additions to SAH-78 (19 Nov 2026, qa-engineer)

| # | Rule | Test | Result |
| --- | --- | --- | --- |
| T78-R5-01 | D-3 settled by contract 0.3.1: after the row cap is used up, a body that reads as JSON but breaks a field rule (a rowNo outside 1..rowCount, `{"rows":[]}`, a batch of 200 characters) answers 409 import-state; a body that cannot be read (`{"rows":`, `not json`, `[]`, an unknown field, rows as text, a rowNo as text) answers 400 malformed-request; rows written stay at 2. The earlier test (409 for every bad body) was outdated and is renamed and rewritten. | `PharmacyHardeningImportIT.afterTheCapABodyThatReadsAsJsonButBreaksAFieldRuleAnswers409AndABodyThatCannotBeReadAnswers400` | SAH-79.md section 8 |
| T78-R5-02 | The high-value threshold maximum is 9,999,999,900 paise (200) and 9,999,999,901 is 400 on `highValueThresholdPaise`; the same limit on a rule level amount | `PharmacyRound5ContractIT` (2 tests) | SAH-79.md section 8 |
| T78-R5-03 | A new vendor answers version 0; the first update makes it 1 | `PharmacyRound5ContractIT.aNewVendorAnswersVersionZeroAndTheFirstUpdateMakesItOne` | SAH-79.md section 8 |
| T78-R5-04 | `numbersIssued` is false before a number is issued, true after (for that type only); the series is then locked (409 series-locked) and another type is not. The financial-year reading (a fixed clock around 1 April) is the java-developer's unit test `NumberingServiceTest`, not an integration test. | `PharmacyRound5ContractIT.aSeriesWithNoNumberIssuedSaysSoAndOneWithANumberIssuedIsLocked...` | SAH-79.md section 8 |
