# Test plan SAH-24 · Bed board (IPD, part 3)

Story: docs/stories/SAH-24.md. Contract: docs/api/ipd.yaml (`GET /board`, `POST /beds/{id}/ready`, `POST /beds/{id}/block`).
Integration tests: `backend/src/test/java/com/sahaj/platform/it/BedBoardIT.java` (real `sahaj_test`, MockMvc, committed tenant SQL puts beds into clean/res/blk).
No migration, so no new catalog tests. Angular unit tests are owned by the angular-developer; Playwright e2e: `frontend/e2e/app-shell.spec.ts` (bed board tile Day N and Deposit low flag with paise rate, API mocked).

| AC | Test (BedBoardIT) | Kind |
| --- | --- | --- |
| AC1/AC7 module gate | ipdOffTheThreeEndpointsAre403ModuleOffAndNothingIsWritten | negative |
| AC1/AC7 read-only | readonlyBoardReadsWithStateReadonlyAndNoActionsAndReadyBlockAre409IpdReadonly | state |
| AC7 roles, 401 | boardIsOpenToFourRolesAndOtherFourGet403AndAnonymousGets401 | role |
| AC2 empty page | emptyClinicHasNoWardsAndZeroCounts | boundary |
| AC2 legend counts | countsPerStatusMatchTheBedsAcrossWards | happy |
| AC2 order and groups | wardsInCreationOrderRoomsGroupedBedsByRoomThenCodeNoRoomGroupLastAndEmptyRoomsHidden | happy |
| AC2 rooms off, empty ward | roomsOffGivesOneGroupWithEmptyNameAndAWardWithNoBedsIsStillListed | boundary |
| AC2 default rooms on | withNoSettingsRowTheBoardUsesTheDefaultRoomsOn (enabled; the bug is fixed, the test guards it. All tests now start from a clinic with no settings row, no workaround) | regression |
| AC3 occupied tile | occupiedBedShowsAdmissionPatientDayOneAndDoctorShortNameAndAFreeBedShowsNone | happy |
| AC3 discharged | aDischargedAdmissionOnACleaningBedShowsNoPatient | negative |
| AC3 no N+1 | threeAdmittedPatientsAllGetTheirOwnNamesAndOccupiedCount | sanity |
| AC3 rate | rateEqualsTheCategoryRateAlsoAfterARateChange | happy |
| AC6 first free bed | firstFreeBedIsTheFirstFreeInRoomThenCodeOrderAndHasFreeBedFollows | happy |
| AC7 ready | readyFromCleaningAndFromBlockedMakesTheBedFreeBumpsVersionAuditsAndReturnsTheBoard | happy, audit |
| AC7 ready 409 | readyFromFreeOccupiedOrReservedIs409BedStateWithTheExactMessage | negative |
| AC7 block | blockFromCleaningMakesItBlockedAuditsAndEveryOtherStateIs409 | happy, negative |
| AC7 404/400 | unknownAndMalformedBedIdsGet404And400ProblemBodies | negative |
| AC7 roles | ownerFrontDeskAndNurseMayChangeBedsOtherRolesGet403AndNothingChanges | role |
| AC4/AC5 offering | afterReadyTheBedIsOfferedAndAdmittingWorksAndABlockedBedIsNotOfferedAndAdmitIs409BedNotFree | integration |
| concurrency | twoSimultaneousReadyCallsOnTheSameCleanBedGiveOne200AndOne409BedState | race |
| concurrency | readyRacingAnAdmitOnTheSameBedLeavesAConsistentStateWithNo500 | race |
| AC8 privacy | patientNamesAreOnTheBoardButNotInAuditRowsLogsOrTheRecordAccessLog | privacy |
| AC9 RLS | anotherClinicSeesOnlyItsOwnBoardAndCannotChangeOrSeeTheOthersBeds | tenant isolation |

Notes
- Concurrent `ready` on one clean bed: the service takes the clinic IPD lock and re-reads the bed, so the result is always one 200 and one 409 `bed-state` ("... from Available."); it is not idempotent.
- Ready racing admit: ready is always 200; admit is 201 (ready first) or 409 `bed-not-free` (admit first); state and admission count agree.
- - The roomLevel default bug (BedBoardService defaulted to false with no `ipd.config` row) is fixed: board and layout both use IpdService.DEFAULT_CONFIG (rooms on). The test is enabled and the earlier `ipdClinic()` workaround (write the setting off then on) was removed from BedBoardIT, with its stale comments.
- Day number above 1 on the tile is covered in RunningBillIT (see SAH-25 plan): `oneDayNumberEverywhere...` checks tile, list and sheet equal `bill.billDays` under both Bed day cuts.
- Test helper `IpdBillingSupport.backdate` takes an ACCESS EXCLUSIVE lock on `ipd.admission` (it runs ALTER TABLE NO FORCE / FORCE row level security inside one transaction, with a 30 s lock_timeout). Any other test touching `ipd.admission` at the same moment waits until the helper commits (milliseconds) and can in theory time out after 30 s. Test-only; nothing in src/main does this. Do not run the IPD ITs in parallel inside one JVM.

Browser (Playwright, API mocked): see the table at the end of SAH-25.md (bed board tile and the shell checks in `frontend/e2e/app-shell.spec.ts`). The app shell checks (collapsed rail without group chevrons, profile chip shows the role once for the placeholder name Owner, no horizontal overflow at 1280 px) are in the same file.
