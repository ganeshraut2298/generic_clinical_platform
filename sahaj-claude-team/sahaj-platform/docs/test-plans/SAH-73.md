# Test plan · SAH-73 (optional patient date of birth: registration, edit, age everywhere, Paediatrics, dental Child tab)

Owner: qa-engineer · Date: 13 Nov 2026 · Branch: feature/SAH-39-home-kinetic-background (tech lead names the story branch)
Sources: `docs/stories/SAH-73.md` (SAH-73-1 to SAH-73-5), `docs/requirements/SAH-73.md` (BR73-1 to BR73-18, SO-73-1 to SO-73-12), ADR-024, DEC-045 and its addendum, `docs/api/patient.yaml` 0.2.0, `docs/api/opd.yaml` 0.5.3, `docs/database/SAH-73.md` (9 cases), the java-developer and angular-developer sections of `docs/handoffs/SAH-43.md`.
Status of this pass: **COMPILE ONLY.** The tests were written against the contract and the code as read and compiled with `mvn -f backend/pom.xml test-compile`. Nothing was run: `sahaj_test` is not touched in this pass, and Playwright was not started (no backend database). Every "Result" below is therefore "not run" until the full run.

## Files (all under QA ownership)
| File | Tests | What it proves |
| --- | --- | --- |
| `backend/src/test/java/com/sahaj/platform/it/PatientDateOfBirthIT.java` | 47 | Create and PATCH (missing, date, null), the four refusal messages, age worked out on every read, unit edges, merge, who sees the date, chips, duplicate check, queue and bill ages, audit without the value, no date in any log |
| `.../PatientDateOfBirthTableIT.java` | 9 | The nine database cases of `docs/database/SAH-73.md` by SQL as `sahaj_app` and as the owner |
| `.../PatientDateOfBirthEmrIT.java` | 19 | Paediatrics (header, months and days, bands, vaccine states, add or refuse a vaccine, growth point age, "not recorded", card date, case paper) and the dental Child tab, through the EMR endpoints |
| `.../PatientsIT.java` (changed) | 25 (2 changed) | `kids` chip under 13 (13 and 17 are not children), the merge answer always has `datesOfBirthDiffered` |
| `frontend/e2e/patient-dob.spec.ts` | 12 (x 2 viewports) | Registration with a date, Age read-only, clear the date, future date, server refusal, nurse; Edit details (add, clear, failed save with Retry, server refusal, roles); Paediatrics header and the "not recorded" line. API mocked in the browser, as the other specs |

New tests: **75 backend, 12 e2e**. Existing tests that asserted the old behaviour: `PatientsIT` (the Children chip: now also asserts 13, 17 not in "Children"; the merge answer shape). `MedicineReviewFixIT.thePatientAuditRowsOf...` was read and still holds (undated patients keep `age`, `allergyRecorded`, `chronicRecorded`; the snapshots never held the texts). No existing IT asserted audit values with name, mobile or area, or `PATIENT_UPDATED` on a date change; the new ITs pin the new rule.

## Fixtures and conventions
- The application clock cannot move in an integration test (one shared Spring context; a second context would add a connection pool, see `MarSupport`). Every date is therefore worked out from today in India (`LocalDate.now(Asia/Kolkata)`), and the expected age is worked out by plain calendar arithmetic in the test (not `Period`). The fixed-day vectors (29 Feb 2020 on 28 Feb and 1 Mar 2027, 2 years, 3 months, 2 weeks at both edges) belong to the unit tests of `PatientAge` (java-developer); the ITs prove the same edges relative to today, and 29 Feb by the formula for the day the test runs. The e2e specs fix the browser clock to 13 Nov 2026.
- A test run across IST midnight can shift a boundary by one day (the same exposure as `BackdateSupport`); not mitigated.
- The stored `age` of a dated patient is made stale by SQL (`update patient.patient set age = 99`) to prove it is never read (ADR-024 D4).
- Audit rows are read by SQL as `sahaj_app` with the clinic bound. Marker words (`Zxq...`) make leaks easy to see. A "date" check uses the ISO text of the date and its year.
- Throwaway data only. No real patient data.

## 1. Acceptance criteria to tests
### SAH-73-1 Registration (and family member)
| Criterion | Test (class: method) | Result |
| --- | --- | --- |
| Dialog shows Full name, Age, Date of birth, Sex in that order with the hint; the old hint stays | e2e patient-dob: "front desk registers a child with a date..." (order of the first three labels and the new hint). The old hint "Name, age and sex are enough. ABHA can be added later." is **not in the Angular dialog** (pre-existing gap, see finding F-73-3) | not run |
| Date 17 months old: registered with that date, Age shows "17 months" read-only before saving and after | PatientDateOfBirthIT: aChildOfSeventeenMonthsReadsInMonthsAfterSaving; e2e: front desk registers a child with a date | not run |
| Age 34, no date: registered as today, no date | PatientDateOfBirthIT: registeringWithAnAgeOnlyKeepsTheAgeAndHasNoDate, aDateOfNullWithAnAgeIsTheSameAsNoDate; e2e: clearing the date ... an age alone is sent | not run |
| Date filled: Age is read-only text; cleared: Age typeable, no stale value | e2e: front desk registers a child with a date; clearing the date makes Age typeable again | not run |
| Neither date nor age: refused on age, nothing saved | PatientDateOfBirthIT: registeringWithNeitherADateNorAnAgeIsRefusedOnAgeAndSavesNothing | not run |
| Future date refused "Date of birth cannot be in the future." | PatientDateOfBirthIT: aDateInTheFutureIsRefusedButTodayIsAcceptedAsANewborn; e2e: error path, a date in the future | not run |
| More than 120 years: "Date of birth must be within the last 120 years." | PatientDateOfBirthIT: aDateMoreThanOneHundredTwentyYearsBackIsRefusedAndTheLastDayThatStillCountsIsAccepted | not run |
| Not a date or does not exist: "Enter a valid date of birth.", 400 with field error on `dateOfBirth` | PatientDateOfBirthIT: aDateThatDoesNotExistOrIsNotADateIsRefusedWithoutEchoingIt; e2e: the server refuses the date | not run |
| Today accepted | PatientDateOfBirthIT: aDateInTheFutureIsRefusedButTodayIsAcceptedAsANewborn; e2e: today reads "0 days" | not run |
| Date and a typed age that disagree: 400 on `age` "Age does not match the date of birth." | PatientDateOfBirthIT: aMatchingDateAndAgeAreBothAcceptedAndADifferentAgeIsRefusedOnAge | not run |
| "Records" consent recorded as today; no new question | PatientDateOfBirthIT: aRecordsConsentAnswerWithADateIsRecordedAndTheDateAddsNoConsentQuestion | not run |
| Family member with a date saved under the same mobile | PatientDateOfBirthIT: aFamilyMemberRegisteredWithADateJoinsTheSameMobile | not run |
| Other roles 403, no login 401 | PatientDateOfBirthIT: onlyOwnerAndFrontDeskMayWriteTheDateAndAnotherClinicsPatientIs404; e2e: the registration controls are hidden for a nurse | not run |
| Slow or failed save: typed values stay, plain error, Retry (SAH-8) | e2e: the server refuses the date ... typed values stay (registration); Edit details: a failed save keeps the sheet open with Retry | not run |
| Audit holds who, when, id; no date, name or mobile in any log or audit value | PatientDateOfBirthIT: theRegistrationAuditHoldsNoNameMobileAreaAllergyOrDateAndSaysWhetherADateWasRecorded, noLogLineHoldsADateOfBirthOrAnUnreadableValueAcrossEveryKindOfCall | not run |

### SAH-73-2 Edit details (add, correct, clear)
| Criterion | Test | Result |
| --- | --- | --- |
| Sheet has Date of birth and Age and Save and Cancel; no other field | e2e: front desk adds a date: only Date of birth and Age are on the sheet | not run |
| A record made before this change has no date, shows its age; nothing guessed | PatientDateOfBirthIT: registeringWithAnAgeOnlyKeepsTheAgeAndHasNoDate, anUndatedPatientKeepsTheStoredAgeAsIs; PatientDateOfBirthTableIT: aLegacyInsertWithoutTheColumnSucceedsAndReadsNull | not run |
| Add 4 Mar 2020 to a patient of 5: the age follows the date | PatientDateOfBirthIT: addingADateToAnOlderRecordMakesTheAgeFollowTheDateAndAuditsOneSetEvent; e2e: front desk adds a date | not run |
| Change the date: age, vaccine states, weight band, milestone band follow; given vaccines keep dates | PatientDateOfBirthIT: correctingTheDateChanges...; PatientDateOfBirthEmrIT: aCorrectedDateRecomputesTheStatesOnTheNextReadAndGivenVaccinesStayGiven, theDentalTabFollowsACorrectedDateOnTheNextRead | not run |
| Clear: the age stays as worked out that day, never no age | PatientDateOfBirthIT: clearingTheDateWithAnExplicitNullKeepsTheWorkedOutAgeAndAuditsOneClearedEvent, clearingWithATypedAgeKeepsThatAgeAndClearingAnUndatedRecordIsANoOp; e2e: clearing the date sends null | not run |
| One audit event per set, change or clear, never the value | PatientDateOfBirthIT: theDateEventHoldsTheFieldNameAndTheKindOfChangeNeverTheDateOrAnAge, aDateOnlyChangeWritesNoUpdatedEventButANameChangeDoes | not run |
| Future, over 120, invalid, conflicting age: same refusals, nothing saved | PatientDateOfBirthIT: aPatchWithAnInvalidDateIsRefusedWithTheSameMessagesAndSavesNothing, anAgeSentWhileTheRecordKeepsItsDateMustMatchAndAMatchIsANoOp; e2e: the server refuses a date that conflicts | not run |
| Doctor with the EMR open sees the new age on the next read | PatientDateOfBirthEmrIT: aCorrectedDateRecomputesTheStatesOnTheNextRead... (re-read, nothing pushed) | not run |
| Other roles 403 and no Edit details; other clinic 404; no login 401 | PatientDateOfBirthIT: onlyOwnerAndFrontDeskMayWriteTheDateAndAnotherClinicsPatientIs404; e2e: a nurse sees the date but has no Edit details button | not run |
| Merge: kept has no date, merged has: copied; both differ: kept kept and the notice | PatientDateOfBirthIT: mergeCopiesTheDate..., mergeKeepsTheKeptDateAndSaysSoWhenTheTwoDatesDiffer, mergeAnswerAlwaysHasDatesOfBirthDifferedAndOtherAnswersNeverDo, mergeKeepsTheKeptDateWhenOnlyTheKeptRecordHasOneAndWhenBothHaveTheSameDate. The notice text on screen: angular unit tests (merge notice); no e2e (merge dialog not in this pass) | not run |
| Save fails or slow: sheet stays open with values and Retry | e2e: error path, a failed save keeps the sheet open with the typed date and a Retry button | not run |

### SAH-73-3 The age everywhere
| Criterion | Test | Result |
| --- | --- | --- |
| Every place that shows age uses the worked-out age (list, header, family, queue, bills, IPD pick) | PatientDateOfBirthIT: aStaleStoredAgeIsNeverUsed..., theQueueCardAndTheBillShowTheEffectiveCompletedYearsAndNoDate, aFamilyRowOfAnInfantReadsInMonths. **IPD admission pick: not covered by an IT** (needs the hospital and bed set-up of `AdmissionDepositsIT`; covered by the java-developer's unit test only, see gap G-73-1) | not run |
| 34 years: list "34 F", header "34 yrs" | PatientDateOfBirthIT: registeringWithADateOnlyStoresTheDateAndWorksTheAgeOutFromIt (YEARS, 34); e2e: clearing the date ... ("34 yrs" preview); text is built by the screen (angular unit tests) | not run |
| 18 months, 10 weeks, 9 days | PatientDateOfBirthIT: theAgeUnitChangesAtTwoYearsThreeMonthsAndTwoWeeks (months, weeks, days values), aFamilyRowOfAnInfantReadsInMonths; e2e: nurse record header "18 months" | not run |
| Born 29 Feb 2020: 6 on 28 Feb 2027, 7 on 1 Mar 2027 | PatientDateOfBirthIT: aTwentyNinthOfFebruaryBirthdayIsOnTheFirstOfMarchInOtherYears (the formula for the run day); fixed vectors: `PatientAge` unit tests | not run |
| No date: the stored number, does not change | PatientDateOfBirthIT: anUndatedPatientKeepsTheStoredAgeAsIs | not run |
| Chips Children (under 13) and 60+ by the worked-out age; a child who turns 13 today leaves Children | PatientDateOfBirthIT: theChildrenChipIsUnderThirteen..., theChildrenChipFollowsACorrectedDateAndAChildWhoTurnsThirteenLeavesIt, theSixtyPlusChipUsesTheDateWhenThereIsOneAndTheStoredAgeOtherwise; PatientsIT: filterChipsKids... (13 and 17 not children) | not run |
| Duplicate check on effective years, never the dates | PatientDateOfBirthIT: theDuplicateCheckComparesTheEffectiveYearsNeverTheDates, theDuplicateCheckUsesTheWorkedOutYearsOfTheOlderRecordNotItsStaleStoredAge | not run |
| Search by a date finds nothing | PatientDateOfBirthIT: searchNeverMatchesADateOfBirthAndNoListOrFamilyRowCarriesIt | not run |
| Lists carry no date | same test (five roles, four list paths, family) | not run |
| Record shows the date to owner, doctor, visiting doctor, nurse, front desk; billing and pharmacist see the age only | PatientDateOfBirthIT: theDateIsPresentForTheFiveRolesAndNullWhenNotRecorded, billingAndPharmacistReadWithTheDateAbsentAndCounsellorGets403 (renamed and made strict 13 Nov 2026, DEC-049; **see finding F-73-1, now closed**), theQueueCardAndTheBillShow... (bill for BILLING and PHARMACIST has the age and no date); e2e: nurse sees the date; a role without the date sees the age text only | not run |
| Another clinic's patient never listed, counted, 404 by id | PatientDateOfBirthIT: onlyOwnerAndFrontDeskMayWrite...(404), theClinicComesFromTheLoggedInUser..., theNewIdsAreNotMixedUp..., auditEventsOfOneClinicAreInvisibleToAnotherClinic | not run |
| The date is never in a URL, a query string, a log line, a title, an error message | PatientDateOfBirthIT: noLogLineHolds..., aDateThatDoesNotExist...WithoutEchoingIt (refusals); URLs and titles: e2e asserts no ISO date on the page body; the contract has no date in any path or query | not run |

### SAH-73-4 Paediatrics
| Criterion | Test | Result |
| --- | --- | --- |
| Header shows the age in months; "Growth today" months and days | PatientDateOfBirthEmrIT: aChildOfEighteenMonthsHasTheHeaderAgeInMonths..., growthTodayShowsMonthsAndDaysWorkedOutFromTheDate; e2e: a child with a date | not run |
| Weight note by the age band; no band: "Centile not available for this age", point still plotted | theWeightNoteUsesTheBandOfTheChildsAgeAndSaysNotAvailable..., theGrowthPointsAgeInMonthsComesFromTheDateAndIsNullWithoutOne | not run |
| Milestone card by the age band; none: "Milestones for this age are not set up" | `milestonesSetUp` true at 18 months, false at 30 months and without a date (the text is the screen's; angular unit tests) | not run |
| Vaccine states Given, Due today, Overdue from the date and the H9 window | aNewbornHasBirthVaccinesDueToday..., aChildOfEightWeeksHasTheBirthVaccinesOverdue..., aWindowEndsOnItsLastDayExclusive (day 41 due, day 42 overdue), anEarlierSignedRecordGivenVaccineIsNotDueAgain | not run |
| Due or overdue vaccine can be added; a vaccine not due is 400 | aNewbornHas... (bcg added, dtwp_1 refused), aChildOfEightWeeks... (overdue bcg added, dtwp_2 refused) | not run |
| Printed card shows the name and date, every vaccine with its state, next due | The date is in `paeds.dateOfBirth` (once in the record) and not in the case paper: aChildOfEighteenMonths..., theCasePaperCarriesTheAgeDetailAndNeverTheDate. The printed layout itself: angular unit tests | not run |
| Date corrected after vaccines given: given stay given, states recomputed | aCorrectedDateRecomputesTheStatesOnTheNextReadAndGivenVaccinesStayGiven | not run |
| No date: no vaccine can be added; notes say not available; header whole-number age; the "Date of birth not recorded..." line | aChildWithNoDateHasNoMonths..., e2e: error path, a child with no date shows ... the line | not run |
| Dose by weight unchanged: uses today's weight only | theDoseByWeightDoesNotUseTheAgeOrTheDate (same dose weight with and without a date) | not run |
| Case paper meta shows the age text for Paediatrics; other departments as before | theCasePaperCarriesTheAgeDetailAndNeverTheDate; aDentalRecordNeverCarriesTheDateOfBirth (integer `age`) | not run |
| EMR closed to nurse, front desk, billing, pharmacist, counsellor (403); other clinic 404; no login 401 | theEmrIsClosedToNurseFrontDeskBillingPharmacistAndCounsellorAndToAnotherClinic; theVisitingDoctorAndTheOwnerWithDoctorDetailsReadTheSameFields (visiting doctor; the owner with doctor details is covered by `EmrDoctorContextIT`) | not run |
| The date only in the printed card and Patients screens; never logged | noLogLineAndNoAuditValueHoldsTheDateWhileTheEmrIsOpenedAndSaved; e2e: body has no ISO date | not run |

### SAH-73-5 Dental Child tab
| Criterion | Test | Result |
| --- | --- | --- |
| Dated under 13 (12 years 11 months): Child; exactly 13: Adult | theDentalChartOpensOnTheChildTabUnderThirteenByTheExactAge | not run |
| No date: by the whole-number age (under 13 Child) | withoutADateTheDentalTabOpensByTheWholeNumberAge | not run |
| Switching the tab keeps everything; the tab is not stored | Not an API fact: `childTabDefault` is computed per read (theDentalTabFollowsACorrectedDateOnTheNextRead); the switch is the screen's (angular unit tests) | not run |
| Other clinic or role without access: 404 or 403; no login 401 | Covered with the Paediatrics access test (same EMR guards); the dental-specific 403 list is `EmrSpecialtyScreensIT` | not run |
| Child chart shows 20 primary teeth, adult teeth stay in the record | Unchanged from SAH-49 (`EmrSpecialtyScreensIT` dental tests) | not run |

## 2. Cross-cutting cases
| Case | Test | Result |
| --- | --- | --- |
| Invalid input: 400 with a problem-details body | every refusal goes through `problem(...)` (content type `application/problem+json`, status and title) | not run |
| Tenant isolation (data, audit, merge, duplicates, family) | see the SAH-73-3 row "another clinic"; PatientDateOfBirthTableIT: anotherClinicCannotSeeOrChangeTheDateAndNoClinicBoundSeesNothing | not run |
| Role without permission 403 | PatientDateOfBirthIT: onlyOwnerAndFrontDeskMayWrite..., mergeIsForOwnerAndFrontDeskOnlyAndNotAcrossClinics | not run |
| Boundaries | 0 and 120 years, today, tomorrow, 121 years, 121 years minus a day; 2 years, 3 months, 2 weeks, 13 days; 12y11m, 13 today, 13 tomorrow; 60 today, 60 tomorrow; the 1900 floor (service refuses first; the database floor by SQL) | not run |
| The clinic comes from the session, never the body | theClinicComesFromTheLoggedInUserAndNeverFromTheRequestBody | not run |

## 3. Database cases (docs/database/SAH-73.md)
| Case | Test (PatientDateOfBirthTableIT) | Result |
| --- | --- | --- |
| 1 column exists, `date`, nullable, no default; migration applied | theColumnIsANullableDateWithNoDefaultAndTheMigrationIsApplied | not run |
| 2 legacy insert reads null | aLegacyInsertWithoutTheColumnSucceedsAndReadsNull | not run |
| 3 1900-01-01 accepted, 1899-12-31 fails `ck_patient_dob_floor` | the1900FloorAcceptsTheFirstOfJanuary1900AndRefusesTheDayBefore | not run |
| 4 null insert; date back to null | anInsertWithNullAndAnUpdateFromADateBackToNullBothSucceed | not run |
| 5 age check (121 fails) with and without a date | theAgeCheckStillRefusesAnAgeAbove120AndBelowZeroWithAndWithoutADate | not run |
| 6 RLS: other clinic sees nothing and cannot update; no clinic bound sees zero; cannot insert for another clinic | anotherClinicCannotSeeOrChangeTheDateAndNoClinicBoundSeesNothing | not run |
| 7 `sahaj_app` may insert and update the column, no delete | sahajAppMayInsertAndUpdateTheColumnAndHasNoDeleteOnThePatientTable | not run |
| 8 column comment says "never log" | theColumnCommentSaysNeverLog | not run |
| 9 service level: future, over 120, not in logs, audit values or list responses | PatientDateOfBirthIT (see sections 1 and 2); aStoredAgeThatDisagreesWithTheDateDoesNotViolateAnyConstraint (the database keeps only the fixed rules) | not run |

## 4. Findings and gaps
- **F-73-1 (contract mismatch, was human-open DEC-013 item 14; CLOSED 13 Nov 2026 by DEC-049 answer 2, patient.yaml 0.2.1).** BILLING and PHARMACIST now read the list, the detail and the family (200, `dateOfBirth` absent, `age` and `ageDetail` present); COUNSELLOR gets 403 on all three. `PatientDateOfBirthIT.billingAndPharmacistReadWithTheDateAbsentAndCounsellorGets403` is strict (no more "200 or 403") and covers the detail, the list, the family, a dated and an undated patient. The skipped `PatientsIT` test was replaced by three enabled tests (see docs/test-plans/SAH-53.md, section DEC-049 items 2 and 3). Not run until `sahaj_test` is recreated.
- **F-73-2 (QA risk, not a defect).** The application clock cannot move in an integration test, so the 29 Feb and fixed-day vectors of the stories are not pinned by day in the ITs. They are pinned by the java-developer's `PatientAge` unit tests and the angular-developer's `patient-age.ts` tests (shared vectors). Recommend the code-reviewer confirm both sides carry the same vector table.
- **F-73-3 (angular-developer note, already in the handoff).** The mockup hint "Name, age and sex are enough. ABHA can be added later." is missing from the Angular New patient dialog (pre-existing). Story criterion 1 of SAH-73-1 says it stays. Not tested as present. For the angular-developer or the human.
- **F-73-4 (angular-developer note).** A native date input cannot show the client message "Enter a valid date of birth." for a typed non-date (the browser blocks it); the server 400 shows it (e2e: the server refuses the date).
- **G-73-1 (not covered by an IT).** The IPD admission pick and IPD card age are not exercised end to end here (they need the hospital, ward, bed and deposit set-up). The java-developer's `AdmissionServiceTest` covers the effective years. Add an IT if the code-reviewer wants it.
- **G-73-2.** The merge notice screen and the vaccination card print are not in the e2e pass (merge dialog and print sheet need larger mocks); the angular unit tests cover them.
- The e2e specs are **not run**: they follow the Angular code as read (test ids `age-line`, `dob`, `age-preview`, `sp-note`, `specialty-strip`; labels; route `/app/patients`, `/app/emr/r1`). A failing e2e at the full run is reported, not skipped.

## 5. How to run (full run, later)
`node scripts/validate.mjs all` (backend `mvn verify` on `sahaj_test`, frontend lint, unit tests and build). The e2e: `npm --prefix frontend/e2e test` (builds the app, then Playwright; no backend needed, the API is mocked).
