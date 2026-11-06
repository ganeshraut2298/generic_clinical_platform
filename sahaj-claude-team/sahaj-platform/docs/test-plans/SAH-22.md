# Test plan SAH-22 · Wards, rooms, beds and bed charges

Story: `docs/stories/SAH-22.md`. Contract: `docs/api/ipd.yaml`. Design: `docs/database/SAH-22.md` (migration `V2026_11_11_1000__ipd_wards_rooms_beds.sql`).
Integration tests: `backend/src/test/java/com/sahaj/platform/it/IpdSetupIT.java` (real `sahaj_test` database, MockMvc through the full filter chain; IPD switched on with `POST /platform/modules/ipd-trial`; bed limit lowered and trial expiry set with committed tenant updates on `platform.module_entitlement`). The developers' mock-based unit tests are separate.
Frontend e2e (Playwright): not part of this pass; no Playwright suite exists for the config pages yet (the UI owner's unit tests cover them).

## Acceptance criteria to tests (all in IpdSetupIT)
| AC | Case | Test |
|---|---|---|
| 1 | IPD Off: GET and all 8 writes 403 `module-off`, no rows, no audit; after trial GET works, state trial | ipdOffByDefaultAllNineEndpointsAre403ModuleOffThenTrialOpensTheLayout |
| 1 | Expired trial: GET works for all 8 roles with state readonly; every write 409 `ipd-readonly` with the contract message; nothing changed | expiredTrialIsReadonlyReadsWorkAndEveryWriteIs409IpdReadonlyWithNothingChanged |
| 2, 8, 9, 10 | Fresh layout: no wards, roomLevel true, bedLimit 25, bedCount 0, four categories with default paise and multipliers, charges midnight/30000/50000; GET writes no row (5 tables, audit); 8 roles read; 401 | freshTenantLayoutHasDefaultsAndGetWritesNoRowAllRolesReadAnd401WithoutToken |
| contract | JSON field names of IpdLayout, ward, room, bed, category, charges | layoutJsonKeepsTheContractFieldNames |
| 3 | Name trimmed, station default, Room 101 / 201 / 301 auto-created | wardNameIsTrimmedStationDefaultsAndRoomsOnCreatesRoom101Then201Then301 |
| 3, 7 | Rooms off: no room created | roomsOffCreatesNoRoomWithTheWard |
| 3 | Duplicate (case, spaces) 409 `ward-exists`; same name in another clinic fine | duplicateWardNameIsRefusedCaseInsensitivelyAfterTrimming |
| 3 | Empty / blank / missing / null / 81 chars 400 with fieldErrors ("Type a ward name first"); 80 chars accepted; bad JSON 400 | invalidWardNamesGet400WithFieldErrorsAndBoundaryOf80IsAccepted |
| 4 | Room number = highest + 1; 100 x position + 1 for a ward with none | roomNumberIsHighestPlusOneAndFirstRoomForAWardWithNoneIs100TimesPositionPlusOne |
| 4, 5 | Only room is unnumbered (named as the ward): next room is 100 x position + 1 | aWardWhoseOnlyRoomHasNoNumberGetsPositionBasedRoomNumber |
| 4 | Unknown / other tenant ward 404 | roomOnUnknownOrOtherTenantWardIs404 |
| 5 | Codes 101-A, 101-B, 102-C, 201-D; first room used when none named; flat list vs room list | bedCodesFollowRoomNumberThenFreeLetterUniqueAcrossTheClinic |
| 5, 7 | Rooms off: null room, code from capitals (CB-A, ABC-B, B-C) | bedOnAWardWithRoomsOffHasNoRoomAndTakesCapitalLettersOfTheWardName |
| 5 | Default category icu / pvt / gen from ward name; category counts | defaultCategoryComesFromTheWardName |
| 5 | Unknown ward, room of another ward, unknown room, other tenant's ward 404; missing wardId 400, bad UUID 400 | bedWithUnknownWardOrForeignRoomOrOtherTenantWardIs404AndBadBodyIs400 |
| 5 | Limit 5: sixth bed 409 `bed-limit`, exact message, nothing written; delete frees a place | bedLimitGivesConflictWithTheExactMessageAndCreatesNothing |
| 5 | Refused bed leaves no room behind | bedLimitCheckComesBeforeAnyRoomIsCreated |
| 5 | 6 parallel bed creates all 201, distinct codes, one room | sixParallelBedCreatesAllSucceedWithDistinctCodes |
| 5 | 8 parallel creates at limit 5: exactly 5 x 201, 3 x 409 | parallelBedCreatesNeverExceedTheLimit |
| 3 | 6 parallel ward creates with the same name: exactly one 201 | parallelWardCreatesWithTheSameNameGiveExactlyOneCreated |
| 6, 8 | Category change persists, counts follow, same category writes nothing (no audit, version same); bad key 400; unknown bed 404 | changingACategoryPersistsAndCountsFollowASameCategoryChangeWritesNothing |
| 6 | Delete removes the row; unknown 404; occupied bed 409 `bed-occupied`; letter reusable | deletingABedRemovesTheRowAnUnknownIdIs404AndAnOccupiedBedIs409 |
| 7 | Room level off/on persists in one config row; rooms and links kept; flat beds unchanged | roomLevelSwitchPersistsKeepsRoomsAndLinksAndFlatBedsStayTheSame |
| 7 | Invalid body (missing, null, string, array) 400 | invalidRoomLevelBodiesGet400 |
| 8 | Category upsert: one row, second PUT updates it; no-op writes nothing | categoryUpdateUpsertsOneRowAndASecondUpdateChangesIt |
| 8 | Boundaries 0 / 10000000 paise, 0.5 / 5 accepted; over max, negative, non-whole rupee, missing; multiplier 0.4, 5.01, 3 decimals, missing 400; unknown key 404 | categoryBoundariesAreAcceptedAndBadValuesGet400 |
| 9 | Charges persist, keep room level; boundaries; bad cut, negative, over max, missing 400 | chargesPersistAndBadValuesGet400 |
| 8, 9 | Setting the defaults again writes no row and no audit | settingTheDefaultsAgainWritesNoRowAndNoAudit |
| 10 | 7 non-owner roles x 8 writes 403; tables and audit unchanged | theSevenNonOwnerRolesGet403OnEveryWriteAndNothingChanges |
| 10 | The eight IPD_* actions audited once each with ids, names, settings, entity types; no patient data | allEightIpdActionsAreAuditedWithIdsNamesAndSettingsButNoPatientData |
| 10 | No-op changes write no audit | noOpChangesWriteNoAudit |
| 10 | RLS: tenant B sees none via API and SQL; cannot touch A's ids (404); SQL update touches nothing, insert for A refused | anotherTenantSeesNoneOfThisClinicsIpdDataThroughApiOrSql |
| 10 | No tenant bound: 0 rows on all five tables | withNoTenantBoundTheAppRoleSeesNoRowsOnAllFiveTables |
| 10 | Composite FK: bed or room cannot point at another tenant's ward; own ward with null room works | aBedCannotPointAtAnotherTenantsWardButOwnWardWithNullRoomWorks |
| 10 | sahaj_app cannot DELETE ward, room, config, bed_category (or truncate bed); can delete bed | theAppRoleCannotDeleteWardRoomConfigOrCategoryRowsButCanDeleteABed |
| 10 | Table checks (category, status, code length, case-insensitive ward name, rates, multiplier, cut); bed code unique per clinic only | tableChecksRejectBadRowsAndBedCodesAreUniquePerClinicOnly |

## Result
36 tests, 36 pass, 0 disabled. No application or migration bug found.
