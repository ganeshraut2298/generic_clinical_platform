# SAH-53, SAH-45, SAH-54, SAH-46, SAH-47, SAH-52 (backend) and the SAH-43 changes · Test plan: Doctor EMR by department

Stories: `docs/stories/SAH-53.md` (53-1 to 53-5), `SAH-45.md` (45-1 to 45-6), `SAH-54.md` (54-1 to 54-4), `SAH-46.md` (46-1 to 46-4), `SAH-47.md` (47-1 to 47-6), `SAH-52.md` (52-1 to 52-4). Requirement: `docs/requirements/SAH-45.md`.
Contract: `docs/api/opd.yaml` 0.3.0. Decision: ADR-020 (replaces ADR-019 D4 to D6; ADR-019 masters and ranking are kept). Stored shape and cases S-50 to S-71: `docs/database/SAH-53.md`. Approval: the human pre-approved the stories in chat (DEC-039 and its addenda); no hook blocked this work.
Replaces: the SAH-44 part of `docs/test-plans/SAH-43-44.md` (templates, overrides, settings; withdrawn). The SAH-43 parts (masters, ranking per doctor, doctor details) stay in force and are re-run here.
Module: opd (reads ipd through `EmrEncounterSource`, platform through its `api`).

**Status of this document: written and compiled, not yet run.** The tests compile (`mvn -f backend/pom.xml test-compile` passes). `sahaj_test` still holds the old Flyway checksums, so no test, `verify` or `validate` has been run. Every "Result" below is therefore **not run**; the coverage table is filled in again after the human recreates `sahaj_test` and the gate runs.

**Screens.** SAH-53 has no screen. SAH-45, 54 (screens), SAH-46-1, 47 (frame) and 52 are Angular work that does not exist yet (the mockup EMR pages are the design; the Queue card button and the EMR frame are not built). The criteria that only a screen can show (words on the page, switches, warnings on leaving, the busy state) are marked **Screen** and are covered by the angular-developer's unit tests and by Playwright tests in `frontend/e2e/` once the pages exist. **No Playwright test is written in this pass** (nothing can be driven). The backend half of each of those criteria (the data and the rule behind the switch) is covered below.

## How the tests are built

Every integration test goes through the real application (profile `test`, the whole Spring context, MockMvc through the real security chain) against the real `sahaj_test` PostgreSQL database as `sahaj_app`. Nothing is mocked. Clinics, doctors, patients, visits, admissions and Emergency cases are made through the real endpoints (sign-up, the setup wizard, Team, Patient, Queue, IPD). Direct SQL is used only for what no endpoint can do: a doctor's specialty text, the clinic's specialty code, a removed membership, signing a record (SAH-51 does not exist yet: `status = 'SIGNED'` is set by an `update` that the column grant allows), an earlier day's IPD note, and reading rows back to prove what was stored. Throwaway data only: made-up names and mobile numbers.

| Class (all in `backend/src/test/java/com/sahaj/platform/it/`) | Tests | What it covers |
| --- | --- | --- |
| `EmrSupport` | - | Shared set-up: staff of every role, department bodies, record bodies, SQL helpers. Reworked: the template and settings helpers are gone. |
| `EmrGlobalTablesIT` (rewritten) | 27 | S-50 to S-57 and the masters: seed counts, starting values of the story lists, off-at-start lists, order, None option, option sources, grants, immutable starter layout, layout documents against the tables, aliases, masters hints, comments. |
| `EmrClinicTablesIT` (rewritten) | 19 | S-60 to S-67 and the usage table: RLS on five tables and every partition (the 24 of the migration plus any the DEC-049 upkeep added), grants (column level), two clinics, department, version, doctor and record checks and keys, partitioning, helper function. |
| `EmrLibraryIT` (new) | 13 | 53-1 through the API: library and starters, roles, 401, 403 on every write, 404, counts, order, field types, hide locks. |
| `EmrDepartmentsIT` (new) | 29 | 45-1 to 45-5, 53-2 to 53-5: list, read, Save draft, Publish, versions, reset target, published layout, rules, 400/403/404/409/413, concurrency, audit, two clinics. |
| `EmrAddDepartmentIT` (new) | 17 | 54-1 to 54-4: blank, copy, starter, review, name, initials, colour, rooms, doctors, limit of 50, audit, draft until published. |
| `EmrRecordsIT` (new) | 28 | 46-1 to 46-3, 47-1 to 47-5, 52-1, 52-4 (OPD): open, idempotent, concurrency, stage, own patient, layout reference, Save draft, Done rule, still needed, value rules, 409, signed, switch, admission advice, header, copy from last visit. |
| `EmrIpdRecordsIT` (new, extends `ScopeSupport`) | 12 | 46-4, 52-1: admission and Emergency case, per-day note, concurrency, drafts off, roles, ward scope and break-glass, discharged, closed, module off, no patient name in logs. |
| `EmrVisitTypeIT` (new) | 6 | 52-2 and the Visit changes: visit-type PATCH, `emrRecordId` and `emrStatus`, audit, roles, database check (S-70). |
| `EmrDoctorContextIT` (rewritten) | 17 | 43-3 and 46-3: `/emr/me` with the new `department`, alias list, clinic default, assigned departments, port. The SAH-44 resolver and snapshot tests are removed (moved to `EmrRecordsIT`). |
| `EmrMastersIT` (edited) | 25 | 43-1: specialty is now one of the 15 starter keys (gp and homeo are 400), write paths are the library and starters, retired paths are 404, advice `nameMr` and `nameHi`. |
| `EmrQuickOptionsIT` (unchanged) | 21 | 43-2: ranking per doctor. Still valid (hints dental and paeds are starter keys). |
| `EmrLogPrivacyIT` (rewritten) | 3 | BR45-5: no log line (as configured and at DEBUG), no refusal body and no audit entry holds the doctor's details, the patient's name or allergy, clinical text, a referral line, a search text or an unknown field name. |
| `EmrPartitionUpkeepIT` (new, DEC-049) | 13 | S-72 to S-79: upkeep, count function, callback, second migrate, monitor on a real database. |
| `EmrPartitionUpkeepJarIT` (new, DEC-049, failsafe) | 3 | S-78: callback, migration and monitor in the packaged jar. |
| `EmrPartitionUpkeepMonitorWiringTest` (new, DEC-049, no database) | 1 | S-79: Spring can create the monitor bean (fails today, F-DEC49-1). |
| `RowLevelSecurityCatalogIT` (edited) | 13 | `NO_RLS_BY_DESIGN` now has the ten global EMR tables; new test for `opd.emr_record` and its partitions (at least 24 monthly, no fixed count since DEC-049). |
| `VitalsCatalogIT` (edited) | 6 | The four layout immutability triggers by name and enabled; FORCE RLS on the five clinic EMR tables. |

Deleted (withdrawn SAH-44 model): `EmrTemplatesReadIT` (11), `EmrOverridesIT` (21), `EmrSettingsIT` (12).
New or rewritten in this pass: 171 tests in 10 classes (`EmrGlobalTablesIT`, `EmrClinicTablesIT`, `EmrLibraryIT`, `EmrDepartmentsIT`, `EmrAddDepartmentIT`, `EmrRecordsIT`, `EmrIpdRecordsIT`, `EmrVisitTypeIT`, `EmrDoctorContextIT`, `EmrLogPrivacyIT`); edited: `EmrMastersIT` (25 tests in all), `RowLevelSecurityCatalogIT` (+1), `VitalsCatalogIT` (2 reworked); unchanged: `EmrQuickOptionsIT` (21). The EMR group is 217 tests plus the 3 catalog tests.

Roles used in every access test: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist, counsellor. "Claim" tests use a token whose role says doctor or owner while the membership says otherwise (the service re-reads the membership).

## Coverage: SAH-53-1 (library and 15 starters)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | 100 sections: 14 Common, 78 Specialty, 8 Compliance, unique stable key, name 1 to 200, kind, description, field list | `EmrGlobalTablesIT.theSeedHasTheCountsOfTheDesignNote`; `EmrLibraryIT.theLibraryHoldsHundredSections...` | not run |
| 2 | A section with no field list carries Findings (rich text), Template (dropdown), Attachments (file) | `EmrGlobalTablesIT.everyFieldHasALabelATypeOfTheFixedListAMetaTextAndARequiredFlagAndEverySectionHasAField` (structure and count; the row-by-row comparison with the mockup file is the db-engineer's parse-back, see gaps) | not run |
| 3 | Each field has label, type from the fixed list, meta, required; another type cannot be stored | same test (`FREE_TEXT` refused 23514); `EmrLibraryIT.everyFieldHasALabelAFixedType...` | not run |
| 4 | The 15 starters, initials, colour, ordered section list | `EmrGlobalTablesIT.theFifteenStartersAreTheMockupDepartments...`; `EmrLibraryIT.theFifteenStartersComeInMockupOrder...` | not run |
| 5 | Per department 19, 24, 25, 23, 27, 22, 22, 22, 20, 22, 21, 21, 27, 26, 20 = 341; every library section used | same tests; `EmrGlobalTablesIT.theDefaultOrderIsTheFourteenCommonSections...` | not run |
| 6 | Starting values (Required 12, Print 12, Copy 10, Who can fill, Part 39, Off at start) as drawn | `EmrGlobalTablesIT.theStartingValuesAreSeededAsDrawnInTheStoryLists`, `theSectionsOffAtStartAreTheListedOnes...`; `EmrLibraryIT.aStarterIsReadWithEverySection...` | not run |
| 7 | Any role including the owner: create, change, delete of a library section or starter gets 403, nothing changes | `EmrLibraryIT.everyWriteToTheLibraryOrTheStartersIs403ForEveryRole...`; `EmrGlobalTablesIT.theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable` | not run |
| 8 | Any signed-in role reads, same for every clinic; no login is 401; no patient data | `EmrLibraryIT.everyClinicRoleReads...`, `theLibraryAndTheStartersAreTheSameForEveryClinic`, `thereIsNoHomeopathyStarterAndNoSectionOrStarterTextHoldsPatientData` | not run |
| 9 | The seed holds no patient data | `EmrGlobalTablesIT.noSeededTextLooksLikeAPatientNameOrMobileNumber` | not run |
| 10 | A later library change does not change a published department (own copy) | `EmrLibraryIT.aPublishedVersionKeepsItsOwnCopyOfEverySectionDefinition...` (the version holds the definition and fields; a real migration cannot be run in a test) | not run |
| 11 | Unknown starter or section key is 404, not an empty template (gp, homeo included) | `EmrLibraryIT.anUnknownSectionKeyIs404...`, `aStarterKeyThatIsNotOneOfTheFifteenIs404StarterNotFound...` | not run |
| 12 | `dental` and `paeds` valid again at the new paths, 404 at the removed `/emr/templates/**` | `EmrLibraryIT.aStarterKeyThatIsNotOneOfTheFifteen...`; `EmrMastersIT.theRetiredTemplateAndSettingsPathsNoLongerExist...` | not run |
| 13 | A required choice list includes None or Nothing abnormal | `EmrGlobalTablesIT.everyRequiredMultiSelectOrChecklistFieldWithOptionsHasOneNoneOption...`; `EmrLibraryIT.aRequiredChoiceListWithNoneOrNothingAbnormal...` | not run |
| 14 | No Homeopathy department; a Homeopathy clinic gets the default | `EmrDoctorContextIT.thereIsNoHomeopathyDepartmentAndAHomeopathDoctorGetsTheClinicDefault`; `EmrLibraryIT.thereIsNoHomeopathyStarter...` | not run |

## Coverage: SAH-53-2 (the six settings, part, order) and SAH-45-2, 45-3 (backend half)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Draft read: every section with the six settings, part, position, from the starter values | `EmrDepartmentsIT.theDepartmentIsReadWithEverySectionHiddenOnesTooTheSixSettings...` | not run |
| 2 | Hidden saved as hidden; hidden and required is 400 with a reason; hiding a required section clears Required in the same change | `EmrDepartmentsIT.aHiddenSectionCannotBeRequiredAndHidingARequiredSection...` | not run |
| 3 | "x of y on" and "required" count shown sections only | same test; `aNewClinicSeesTheFifteenStarters...` (oracle read from the seed tables) | not run |
| 4 | Move to the other part keeps overall position; Up and Down swap; top or bottom of a part is a success | `EmrDepartmentsIT.movingASectionToTheOtherPartChangesOnlyItsPart...` (the server stores the order the page sends) | not run |
| 5 | Only Allergies cannot be hidden (any department); other sections including Rx can | `EmrDepartmentsIT.onlyAllergiesAndPcpndtFormFInObstetricsCannotBeHidden...` | not run |
| 6 | PCPNDT Form F cannot be hidden in Obstetrics & Gynaecology; other Compliance sections can | same test (also a department added from og) | not run |
| 7 | Every section hidden or nothing required is accepted | `EmrDepartmentsIT.aDepartmentWithEverySectionHiddenExceptTheLockedOneOrNothingRequired...` | not run |
| 8 | A key not in the department is 404, nothing stored; a setting value outside the list is 400 | `EmrDepartmentsIT.theSectionSetMustBeExactlyTheDepartments...`, `aValueOutsideTheListedOnesOrAnExtraFieldIsA400...` | not run |
| 9 | A label, kind, description or field in the request is 400 | `EmrDepartmentsIT.aValueOutsideTheListedOnesOrAnExtraFieldIsA400AndNothingIsStored` | not run |
| 10 | A clinic id in the body is ignored | `EmrDepartmentsIT.aBodyOverSixtyFourKilobytesIs413AndAClinicIdInTheBodyIsIgnored`; `EmrAddDepartmentIT.theClinicIdInTheBodyIsIgnored...` | not run |
| 11 | A change that fails half way stores nothing | `EmrDepartmentsIT.aChangeThatFailsHalfWayStoresNothing...` | not run |
| 12 | Who can fill is stored and shown only (no role but the doctor writes the EMR) | `EmrDepartmentsIT.saveDraftStoresTheChangeAsADraft...` (whoCanFill stored); `EmrRecordsIT.onlyADoctorAVisitingDoctorOrAnOwner...` (nurse and others cannot write) | not run |
| 13 | Words, switches, "Section settings" panel, 44 px targets, disabled switches for other roles | **Screen** | - |

## Coverage: SAH-53-3 (Save draft and Publish)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Save draft: new revision, published version unchanged, doctors still see the published layout | `EmrDepartmentsIT.saveDraftStoresTheChangeAsADraftOfThatDepartmentAndDoctorsStillSeeThePublishedLayout`, `whatDoctorsReadIsTheShownSectionsOfTheLatestVersion...` | not run |
| 2 | No change: success "Nothing new to save" / "Nothing to publish", no audit, no version | `EmrDepartmentsIT.aSaveWithNoChangeIsNothingNew...`, `publishWithNothingNewIsNothingToPublish...` | not run |
| 3 | Publish: next number (v6 to v7), Live, draft cleared, message data | `EmrDepartmentsIT.theFirstPublishIsVersionOneTheStatusBecomesLive...` (v1 to v7) | not run |
| 4 | Never published: first version is v1 | same test | not run |
| 5 | A visit opened with n still reads n after n+1 | `EmrRecordsIT.aRecordKeepsTheLayoutItOpenedWith...`, `aRecordOpenedOnTheStarterLayoutKeepsIt...`, `theRecordRowPointsAtTheStarterLayoutOrTheDepartmentVersion...` | not run |
| 6 | A published version cannot be changed | `EmrClinicTablesIT.aPublishedVersionIsInsertOnly...` (privilege, policy, trigger by name in `VitalsCatalogIT`); `EmrDepartmentsIT.aPublishedVersionIsReadExactlyAsPublished...` (version 1 unchanged after v2, v3) | not run |
| 7 | Two owners, older revision: 409, nothing applied | `EmrDepartmentsIT.aSaveOrPublishWithAnOlderRevisionIs409...` | not run |
| 8 | Publish twice at the same moment: one version | `EmrDepartmentsIT.twoPublishesAtTheSameMomentMakeOneVersion...` (also two saves) | not run |
| 9 | Another clinic's drafts and versions never visible or changeable | `EmrDepartmentsIT.anotherClinicsOwnerNeverSees...`, `theDepartmentRowsAndVersionsCannotBeReachedAcrossClinicsInTheDatabaseEither`; `EmrClinicTablesIT.oneClinicCannotRead...` | not run |
| 10 | Doctor, visiting doctor, nurse, front desk, billing, pharmacist: 403; no login 401 | `EmrDepartmentsIT.everyRoleButTheOwnerIs403OnSaveDraftPublishAndAddDepartment...` (incl. a token that claims OWNER) | not run |
| 11 | One audit entry: who, when, department, version or revision, before and after of each changed setting, no patient data | `EmrDepartmentsIT.theAuditEntriesCarryWhoWhatAndTheBeforeAndAfter...` | not run |

## Coverage: SAH-53-4 (reset and undo) and SAH-45-5

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Reset: draft takes the starter values, the count of changes rises, published unchanged | `EmrDepartmentsIT.resettingPutsTheStarterValuesInTheDraftCountsTheChange...`, `theResetTargetIsTheStarterSettingsOfExactlyThisDepartmentsSections...` | not run |
| 2 | Undo returns the draft exactly as before; later change removes Undo | `resettingPuts...` (saving the earlier settings back restores them; Undo itself is a page action, **Screen** for the offer) | not run |
| 3 | Already equal to the starter: success, nothing changes, no audit | `EmrDepartmentsIT.aDepartmentAlreadyEqualToItsStarterValuesResets...` | not run |
| 4 | Covers only the selected department | `EmrDepartmentsIT.aResetCoversOnlyTheSelectedDepartment` | not run |
| 5 | Saved and published: new version from starter values, earlier versions kept | `resettingPuts...` (v2 from the reset, v1 kept) | not run |
| 6 | Wrong role 403; another clinic's department 404 | `everyRoleButTheOwnerIs403...`, `anotherClinicsOwnerNeverSees...` (reset-target 404) | not run |
| 7 | One audit entry with before and after counts of shown and required sections | `resettingPuts...` (`EMR_DEPARTMENT_RESET`, `sectionsOn`, `requiredOn`) | not run |
| 8 | 409 when another owner changed it | `aSaveOrPublishWithAnOlderRevisionIs409...` (the reset is saved with origin RESET through the same call) | not run |

## Coverage: SAH-53-5 (layout doctors read)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Published version: shown sections only, in order, with name, kind, part, settings, fields | `EmrDepartmentsIT.whatDoctorsReadIsTheShownSectionsOfTheLatestVersion...` | not run |
| 2 | No published version: starter values, answer says starter | same test (`layoutSource STARTER`) | not run |
| 3 | A draft is not in what doctors read | same test | not run |
| 4 | A given version number is returned as published | `EmrDepartmentsIT.aPublishedVersionIsReadExactlyAsPublished...` | not run |
| 5 | Another clinic's department is 404 | `anotherClinicsOwnerNeverSees...` | not run |
| 6 | Nurse, front desk, billing, pharmacist can read it; no login 401 | `whatDoctorsRead...` (nurse, pharmacist, doctor), `everyClinicRoleReadsTheListAndTheDepartment...` | not run |
| 7 | No log line holds patient data | `EmrLogPrivacyIT` (three tests) | not run |

## Coverage: SAH-45 (the page, backend half)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 45-1.1 to 45-1.3 | Title, line, clinic name; cards with initials, name, status, version, "x of y"; new clinic lists 15 as Template v0 until first save | **Screen** for the words; data: `EmrDepartmentsIT.aNewClinicSeesTheFifteenStarters...` | not run |
| 45-1.4 | Old "Start from your specialty" cards keep working | not part of this change (SAH-7 tests `ClinicMastersIT` re-run as regression) | not run |
| 45-1.5, 45-1.6 | Search on the page; tap selects first Specialty section | **Screen** (search is on the page, ADR-020 D5) | - |
| 45-1.7 | Departments the clinic added are listed with their own initials and colour; status Template or Draft | `EmrAddDepartmentIT.aStarterGivesADraftDepartment...` (list after creation) | not run |
| 45-1.8 | Other roles see the same list, no change possible, direct calls 403 | `EmrDepartmentsIT.everyClinicRoleReadsTheListAndTheDepartmentButOnlyTheOwnerCanEdit` (`canEdit`), `everyRoleButTheOwnerIs403...` | not run |
| 45-1.9 | Another clinic's owner never sees these departments | `EmrDepartmentsIT.anotherClinicsOwnerNeverSees...` | not run |
| 45-1.10 | Loading and error states | **Screen** | - |
| 45-2.x | Groups, rows, switches, panel, chips, read-only fields, locks | **Screen**; rules: see 53-2 table; field list read-only: `theDepartmentIsReadWithEverySection...` | not run |
| 45-3.x | Move and order; accessible names | **Screen**; server half: `movingASectionToTheOtherPart...` | not run |
| 45-4.x | Chip "n unsaved change(s)", warning on leaving, double tap | **Screen**; server half: 53-3 table (the baseline is the working layout, `layoutSource`) | not run |
| 45-5.x | Reset and Undo | 53-4 table; **Screen** for the message | not run |
| 45-6.x | Preview, visit type selector, links | **Screen**; the preview reads `GET /emr/departments/{key}` (draft, never writes) | - |

## Coverage: SAH-54 (add department)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 54-1.1 to 54-1.2 | Page, stepper, Start blank, Copy, Specialty templates | **Screen**; data: `EmrLibraryIT.theFifteenStartersComeInMockupOrder...` (counts as drawn: Physiotherapy 10, Dental 7) | not run |
| 54-1.3 | All 15 starters offered | same test | not run |
| 54-1.4 | Template preview: name, description, sections, "Turned off by default" | `EmrLibraryIT.theFifteenStarters...` (`description`, `offAtStart`), `aStarterIsReadWithEverySection...` | not run |
| 54-1.5 | Matching print template: Print flags of the starter, no separate template | `EmrAddDepartmentIT.withoutTheMatchingPrintTemplateEveryPrintFlagIsCleared...` | not run |
| 54-1.6 | Copy lists the clinic's own departments; none disables | `EmrAddDepartmentIT.aCopyEqualsTheSourceDepartmentsSavedDraft...` (a never-saved starter cannot be copied: 404); disabling is **Screen** | not run |
| 54-1.7 | Cancel creates nothing | **Screen** (no call is made) | - |
| 54-1.8 | Other roles 403 | `EmrDepartmentsIT.everyRoleButTheOwnerIs403...` | not run |
| 54-1.9 | Another clinic's departments never in Copy | `EmrAddDepartmentIT.theClinicIdInTheBodyIsIgnoredAndAnotherClinicsDepartmentCannotBeCopied` | not run |
| 54-2.x | Review sections: changes replace the source; unchanged equals the source; Start blank offers 14 + 3 | `EmrAddDepartmentIT.reviewedSectionsReplaceTheSourceSettingsAndFollowTheDraftRules`, `aBlankDepartmentHasTheFourteenCommon...`, `aStarterGivesADraftDepartment...` | not run |
| 54-3.x | Doctors and rooms: only active doctors and visiting doctors of this clinic; several departments per doctor; no doctor allowed; room length 400 | `EmrAddDepartmentIT.theDoctorListOffersOnlyActiveDoctors...`, `doctorsAreAssignedWhenTheyAreActiveDoctors...`, `roomsAreTextLabelsOf...` | not run |
| 54-3.6 | The assignment is used by SAH-46 | `EmrDoctorContextIT.whenTheSpecialtyIsNotInTheListTheDoctorsPublishedDepartmentIsUsed...`, `withSeveralDepartmentsTheOneOfTheClinicsSpecialtyIsUsed...` | not run |
| 54-4.1 | Created Draft with chosen name, initials, colour, sections | `EmrAddDepartmentIT.aStarterGives...`, `initialsAndColourAreDerived...` | not run |
| 54-4.2 | Name exists (any case) 400; empty or too long 400 | `EmrAddDepartmentIT.theNameIsUniqueInTheClinic...`, `anEmptyOrTooLongNameIs400...` | not run |
| 54-4.3 | Two creates with the same name at the same moment: one wins | `EmrAddDepartmentIT.twoRequestsWithTheSameNameAtTheSameMoment...` (see finding F-3) | not run |
| 54-4.4 | From a starter equals the starter; a copy equals the source's draft, not its history | `aStarterGives...`, `aCopyEqualsTheSourceDepartmentsSavedDraft...` | not run |
| 54-4.5 | Doctors do not see it until published | `creatingOneAuditEntry...AndDoctorsDoNotSeeItUntilPublished`, `EmrDoctorContextIT.whenTheSpecialtyIsNotInTheList...` | not run |
| 54-4.6 | One audit entry: who, when, id, name, source | `EmrAddDepartmentIT.creatingOneAuditEntry...` | not run |
| 54-4.7 | Wrong role 403; another clinic never read; clinic id ignored | `everyRoleButTheOwnerIs403...`, `theClinicIdInTheBodyIsIgnored...` | not run |
| 54-4.8 | Busy state, failure keeps the wizard | **Screen** | - |
| limit | At most 50 added departments, the 51st is 400 `department-limit` | `EmrAddDepartmentIT.aClinicCanAddFiftyDepartmentsAndTheFiftyFirstIs400DepartmentLimit` | not run |
| contract | Source must be complete (STARTER needs starterKey, COPY needs copy key, BLANK neither) | `EmrAddDepartmentIT.theSourceMustBeCompleteAndOfTheRightKind` (see finding F-2) | not run |

## Coverage: SAH-46 (open the EMR, choose the department, keep the layout)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 46-1.1 | Button on OPD, IPD, Emergency cards for doctor, visiting doctor, owner with details | **Screen**; the card shows it when `GET /emr/me` answers 200: `EmrDoctorContextIT.aDoctorGetsTheirOwnDetails...`, `anOwnerWithDoctorDetailsGetsThem...` | not run |
| 46-1.2 | Other roles: no button and a direct open is 403 | `EmrRecordsIT.onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsOpens...`; `EmrIpdRecordsIT.anOwnerWithDoctorDetailsAndADoctorOpenAnyAdmission...` | not run |
| 46-1.3 | Another doctor's patient: 403; owner with details can open any | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 46-1.4 | IPD off: no IPD cards, open is 403 module-off | `EmrIpdRecordsIT.anotherClinicsAdmissionOrCaseIs404AndAnIpdThatIsOffIs403ModuleOff` | not run |
| 46-1.5 | "Consult done" unchanged | not touched by the code under test (queue tests `QueueIT` re-run) | not run |
| 46-1.6 | A signed visit opens read-only | `EmrRecordsIT.draftsAreForOpdVisitsOnlyAndASignedRecordIsRefused...` | not run |
| 46-1.7 | Clinic B opening clinic A's visit: 404, no data | `EmrRecordsIT.anotherClinicsVisitAndRecordAreNotFound...`; `EmrIpdRecordsIT.anotherClinicsAdmissionOrCase...` | not run |
| 46-2.1 | Waiting or Vitals moves to With doctor through the workflow | `EmrRecordsIT.openingMovesAWaitingVisitToWithDoctor...` | not run |
| 46-2.2 | Already With doctor: nothing changes | same test; `openingAgainIsSafeToRepeat...` (no duplicate stage entry) | not run |
| 46-2.3 | Move fails: EMR still opens, message that the queue was already updated | `EmrRecordsIT.openingMovesAWaitingVisit...` covers MOVED, ALREADY_WITH_DOCTOR and NOT_APPLICABLE (a Done visit); ALREADY_UPDATED needs a race that cannot be forced (unit test `VisitServiceStartConsultationTest`) | not run |
| 46-2.4 | Back arrow keeps filter and draft | **Screen** (the draft is kept: `saveDraftStoresTheValues...`) | - |
| 46-2.5 | Two doctors can both open | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` covers the owner and the visit's doctor; "two doctors on one visit" cannot happen by the own-patient rule | not run |
| 46-2.6 | IPD and Emergency change no OPD stage | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...` (audit of stage moves unchanged) | not run |
| 46-2.7 | One audit entry per open: who, when, ids, no clinical text | `EmrRecordsIT.openingAgainIsSafeToRepeat...`, `EmrIpdRecordsIT.noLogLineOrAuditEntry...` | not run |
| 46-3.1 | Specialty in the alias list (any case, extra spaces) chooses the department | `EmrDoctorContextIT.theSpecialtyNamesOfTheAliasList...`; `EmrRecordsIT.theDepartmentIsChosenOnceWhenTheRecordIsOpened...` | not run |
| 46-3.2 | Else the doctor's assigned departments: clinic's specialty match, else first by name | `EmrDoctorContextIT.withSeveralDepartmentsTheOneOfTheClinicsSpecialtyIsUsed...` | not run |
| 46-3.3 | Else the clinic default, and the answer says so | `EmrDoctorContextIT.aSpecialtyNotInTheListFallsBackToTheClinicDefaultAndSaysSo`, `thereIsNoHomeopathyDepartment...` | not run |
| 46-3.4 | Published version used, else starter layout | `EmrRecordsIT.theRecordRowPointsAtTheStarterLayoutOrTheDepartmentVersion...` | not run |
| 46-3.5 | First open stores the snapshot (department, version or starter, shown sections with settings and fields) | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...`, `theRecordRowHoldsReferencesAndOneDocument...` (stored as a reference to the immutable layout, ADR-020 D8) | not run |
| 46-3.6 | Later publish or reset does not change the visit; required judged by it | `EmrRecordsIT.aRecordKeepsTheLayoutItOpenedWith...` | not run |
| 46-3.7 | Signed visit: snapshot cannot be changed (409) | `EmrRecordsIT.draftsAreForOpdVisitsOnlyAndASignedRecordIsRefused...` (switch is 409; there is no other way to change it) | not run |
| 46-3.8 | Switch department for a draft: new snapshot, no data deleted, one audit entry | `EmrRecordsIT.theDoctorSwitchesTheDepartmentOfADraftRecord...`, `switchingToTheSameDepartmentChangesNothing...` | not run |
| 46-3.9 | The switch is not saved as the doctor's default | `theDoctorSwitchesTheDepartmentOfADraftRecord...` (`/emr/me` unchanged, next visit uses the doctor's department) | not run |
| 46-3.10 | Nurse, front desk, billing, pharmacist cannot read a snapshot (403) | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` (get, draft, switch, advice) | not run |
| 46-3.11 | No log line holds the doctor's name, the patient's name or clinical text | `EmrLogPrivacyIT` | not run |
| 46-4.1, 46-4.2 | Admitted patient and Emergency case open the same frame with the encounter set | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...`, `aDoctorOpensTheEmergencyAssessmentOfACase...` | not run |
| 46-4.3 | Ward scope and break-glass of IPD: a doctor who may not see a ward cannot open (403); break-glass audited by the IPD path | `EmrIpdRecordsIT.withWardScopeOnADoctorStillOpensAnyAdmission...` (see finding F-1: the outside-scope answer cannot be reached through the API) | not run |
| 46-4.4 | Frame opens and the snapshot is stored; saving a draft per encounter is switched off (409) | `EmrIpdRecordsIT.savingADraftIsSwitchedOffForAnAdmission...`, `aDoctorOpensTheEmergencyAssessment...` | not run |

## Coverage: SAH-47 (frame, parts, sections, Done, Save draft), backend half

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 47-1.1 | Header: initials, name, age, sex, token, UHID, doctor, room, visit line | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (header), `theHeaderShowsARecordedAllergy...`; **Screen** for the strip | not run |
| 47-1.2, 47-1.3 | Allergy and known tags; never "No known allergy" unless recorded; last visit; visit count | `EmrRecordsIT.theHeaderShowsARecordedAllergyAndKnownConditions...` ("None known" gives no tag) | not run |
| 47-1.4 | Ticking an allergy updates the tag at once | **Screen** | - |
| 47-1.5 | "ABHA linked" not shown until DEC-040 | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (not returned) | not run |
| 47-1.6 to 47-1.8 | Slim bar, tags Admission advised / Emergency, no name in logs | **Screen**; `adviseAdmissionMarksTheRecord...`; `EmrLogPrivacyIT` | not run |
| 47-1.9 | Clinic B never gets clinic A's patient (404) | `EmrRecordsIT.anotherClinicsVisitAndRecordAreNotFound...` | not run |
| 47-2.x | Part tabs with "n of m required", tick, 0 of 0, Continue buttons, left list | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (parts 1 of 1 and 3 of 3 required, 9 and 7 sections), `whenEveryRequiredSectionIsDone...`, `aHiddenSectionNeverAppearsInStillNeeded...`; **Screen** for tabs and buttons | not run |
| 47-3.1 | Only Show-on sections appear, in the snapshot order | `openingAnOpdVisitCreatesTheRecord...` (16 of 19) | not run |
| 47-3.2, 47-3.3 | Collapsed by default; generic renderer by field type | **Screen**; `EmrLibraryIT.everyFieldHasALabel...` gives the `valueKind` the renderer uses | not run |
| 47-3.6 | Copy from last visit: pre-filled, "Copied from", Done only after confirm; last signed record of the same department only | `EmrRecordsIT.copyFromLastVisitPrefillsFromThePatientsLastSignedRecord...` | not run |
| 47-3.7 | Who can fill stored and shown; the doctor edits all | `saveDraftStoresTheValues...`; `EmrDepartmentsIT.saveDraftStoresTheChange...` | not run |
| 47-3.8 | Vitals start empty, nurse vitals not pre-filled | `openingAnOpdVisitCreatesTheRecord...` (every section starts with no values) | not run |
| 47-3.10 | Vitals limits coloured and labelled | **Screen** (client rule) | - |
| 47-3.11 | Photo, file, signature: disabled with "Later", nothing stored | `EmrRecordsIT.eachValueMustMatchItsField...` (a value for `attachments.files` is 400) | not run |
| 47-3.12 | A typed value is stored with the visit and never logged | `saveDraftStoresTheValues...`, `theRecordRowHoldsReferences...`, `EmrLogPrivacyIT` | not run |
| 47-3.13 | An invalid value (range, length) shows an error and is not saved | `EmrRecordsIT.eachValueMustMatchItsFieldAndABadOneIs400...`, `aSectionThatIsNotInTheRecordsLayout...`, `aBodyOverSixtyFourKilobytesIs413...` (the 32 KB per-section limit is a unit test of the java-developer; one section cannot reach 32 KB within the field limits in the seed) | not run |
| 47-4.1 | Done: every required field filled; no required field: one field filled; Optional never blocks | `EmrRecordsIT.aSectionIsDoneWhenEveryRequiredFieldIsFilled...` | not run |
| 47-4.2 | Moving between parts is allowed while required sections are missing | `whenEveryRequiredSectionIsDone...` (Save draft moves the position, never refuses) | not run |
| 47-4.3, 47-4.4 | Footer "Still needed" in red, "Ready to complete" in green | `stillNeeded` and `readyToComplete`: `openingAnOpdVisitCreatesTheRecord...`, `whenEveryRequiredSectionIsDone...`; **Screen** for colour and wording | not run |
| 47-4.5, 47-4.6 | Complete blocked, first missing section opens; Complete does not sign in phase 2 | **Screen**; `whenEveryRequiredSectionIsDone...` (status stays DRAFT) | not run |
| 47-4.7, 47-4.8 | Hidden section never in "Still needed"; follows the snapshot | `aHiddenSectionNeverAppearsInStillNeededAndADepartmentWithNothingRequired...`, `aRecordKeepsTheLayoutItOpenedWith...` | not run |
| 47-5.1 | Save draft stores everything without checking required sections | `EmrRecordsIT.saveDraftStoresTheValuesThePositionAndANewRevision...` | not run |
| 47-5.2 | Reopen shows the same part, values, position | same test (`uiState`, values) | not run |
| 47-5.3, 47-5.4 | Save before leaving, busy and error state | **Screen** | - |
| 47-5.5 | A draft of another doctor's visit cannot be written (403) | `aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 47-5.6 | A draft of clinic A: clinic B gets 404 | `anotherClinicsVisitAndRecordAreNotFound...` | not run |
| 47-5.7 | One audit entry: who, when, visit id, no clinical text | `saveDraftStoresTheValues...` (section keys only, no value) | not run |
| 47-5 (409) | Stale revision 409; two saves, one wins | `EmrRecordsIT.aStaleRevisionIs409EmrRecordChanged...` | not run |
| 47-6.x | Right panel tabs | **Screen** | - |

## Coverage: SAH-52 (OPD, IPD, Emergency differences), backend half

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 52-1.1 | Titles Consultation / Daily progress note / Emergency assessment | **Screen** (the server sends `encounterType`); `EmrRecordsIT`, `EmrIpdRecordsIT` | not run |
| 52-1.2 | IPD and Emergency header facts read from ipd, not typed | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...`, `aDoctorOpensTheEmergencyAssessment...` | not run |
| 52-1.3 | The same patient's OPD visit and admission never mix | `EmrRecordsIT` (OPD record has no admission), `EmrIpdRecordsIT` (admission record has no visit) | not run |
| 52-1.4 | Discharged admission or closed case opens read-only | `EmrIpdRecordsIT.aDischargedAdmissionOpensReadOnly...`, `aClosedCaseOpensReadOnly...` | not run |
| 52-1.5 | No log line holds the patient's name or clinical text | `EmrIpdRecordsIT.noLogLineOrAuditEntryOfAnIpdOrEmergencyOpenHolds...`, `EmrLogPrivacyIT` | not run |
| 52-2.1 to 52-2.3 | Doctor and owner change the type; front desk only at registration; card, record and audit show the new type | `EmrVisitTypeIT` (all six tests) | not run |
| 52-2.4 | Emergency option disabled with "Coming in a later step"; server answers 400 until SAH-67 | `EmrVisitTypeIT.emergencyIsNotAvailableYetAndAnyOtherValueOrBodyIs400` | not run |
| 52-2.5 | IPD shows "IPD - day n", Emergency the triage level, read-only | **Screen**; data in the header: `EmrIpdRecordsIT` | not run |
| 52-2.7 | The meaning of Revisit is OQ-43-26 | the value is stored and shown only: `EmrVisitTypeIT.theDoctorChangesTheTypeOfTheirOwnVisit...` | not run |
| 52-3.x | Past visits content | not built (SAH-50); nothing to test | - |
| 52-4.1 | Admit to IPD button for OPD and Emergency, hidden for IPD | `EmrIpdRecordsIT.savingADraftIsSwitchedOff...` (advice on an admission is 400 `not-applicable`), `aDoctorOpensTheEmergencyAssessment...` (advice on a case works); **Screen** for the button | not run |
| 52-4.2, 52-4.3 | IPD on: admission sheet; IPD off: sheet with "Advise admission + referral"; "Switch on IPD" for the owner only | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (`ipd.state`, `canSwitchOn` false for a doctor), `onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsOpens...` (true for the owner); **Screen** | not run |
| 52-4.5 | One audit entry, no clinical text | `EmrRecordsIT.adviseAdmissionMarksTheRecordStoresTheReferralLineOnlyOnce...` (`withReferral` only, never the text) | not run |
| 52-4.6 | Nurse, front desk, billing, pharmacist: 403 | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 52-4 (limits) | Referral line 1 to 300 characters; stale revision 409; signed 409 | `EmrRecordsIT.aReferralLineIsOneToThreeHundredCharacters...`, `draftsAreForOpdVisitsOnly...` | not run |

## Coverage: the SAH-43 changes

| # | Change | Test | Result |
| --- | --- | --- | --- |
| 43-a | Master hint is one of the 15 starter keys (gp and homeo refused, 3 former homeopathy complaints have no hint) | `EmrGlobalTablesIT.theNameHintAndOrderChecksOfTheMasters...`, `theThreeFormerHomeopathyComplaintsNowHaveNoHint...`; `EmrMastersIT.aSpecialtyPutsItsTaggedItemsFirst...`, `aSpecialtyOutsideTheFifteenStarterKeysIsAValidationProblem`, `diagnosesPagesWalkAllFiftyThree...` (starter key maps to the SAH-7 diagnosis tag) | not run |
| 43-b | `emr_advice.name_mr` and `name_hi` (null in the seed, 1 to 200) and in the API | `EmrGlobalTablesIT.theAdviceMastersMarathiAndHindiNames...`; `EmrMastersIT.theAdviceMasterCarriesNullMarathiAndHindiNames...` | not run |
| 43-c | `/emr/me` has `department` (and `departments`) and no `template` | `EmrDoctorContextIT.aDoctorGetsTheirOwnDetails...` and the choice tests | not run |
| 43-d | Writes to master, quick-option, library and starter paths are 403; the retired template and settings paths are 404 | `EmrMastersIT.everyWriteToAMasterOrTemplatePathIsRefused...`, `theRetiredTemplateAndSettingsPaths...` | not run |
| 43-e | Ranking per doctor, quick options, recorder | `EmrQuickOptionsIT` (21 tests, unchanged) | not run |

## Database cases S-50 to S-71 (docs/database/SAH-53.md)

| Case | Test | Result |
| --- | --- | --- |
| S-50 counts | `EmrGlobalTablesIT.theSeedHasTheCountsOfTheDesignNote`, `theFifteenStartersAreTheMockupDepartments...` | not run |
| S-51 library against the mockup, FALLBACK fields, 30 types, `FREE_TEXT` refused | `EmrGlobalTablesIT.theStartingValuesAreSeededAsDrawn...`, `theSectionsOffAtStart...`, `everyFieldHasALabel...` (lists of the story compared; the full row-by-row mockup comparison is **not** repeated in Java, see gaps) | not run |
| S-52 grants, no tenant column | `theAppCanSelectEveryGlobalTableWithNoClinicBound`, `theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable`, `noGlobalTableHasATenantColumnOrRowLevelSecurity` | not run |
| S-53 None option | `everyRequiredMultiSelectOrChecklistFieldWithOptionsHasOneNoneOption...` | not run |
| S-54 option sources, allergy always shown, one placement lock, no hidden and required | `theOptionsSourceRulesHoldInTheStoredData`, `theSectionsOffAtStart...` | not run |
| S-55 starter layout immutable, version 2 works, deferred key, documents equal the tables | `aStarterLayoutCannotBeUpdatedDeletedOrTruncatedByAnyRole...`, `theFifteenLayoutDocumentsMatchWhatAQueryOverTheTablesProduces` | not run |
| S-56 alias | `onlyTheNormalisedFormOfAnAliasCanBeStored...` | not run |
| S-57 masters hints and advice languages | see 43-a, 43-b | not run |
| S-60 RLS everywhere, no clinic bound, two clinics, foreign `tenant_id` | `EmrClinicTablesIT.everyClinicTableHasEnabledAndForced...`, `everyMonthlyPartitionOfTheRecordTable...`, `withNoClinicBound...`, `oneClinicCannotReadChangeDeleteOrInsert...`; `RowLevelSecurityCatalogIT.theEmrRecordTableAndEveryOneOfItsPartitions...`; `VitalsCatalogIT.theFiveEmrClinicTablesHave...` | not run |
| S-61 department checks and grants | `aDepartmentIsUniquePerClinic...`, `aDepartmentRefusesBadInitials...`, `theAppCanChangeOnlyTheDraft...`, `twoFirstInsertsOfTheSameDepartment...` | not run |
| S-62 version | `aPublishedVersionIsInsertOnly...`; triggers by name in `VitalsCatalogIT.theEmrLayoutImmutabilityTriggersAreThereByNameAndEnabled` | not run |
| S-63 doctor | `aDepartmentDoctorIsInsertOnly...` | not run |
| S-64 record encounter ids and layout reference | `eachEncounterTypeAcceptsOnlyItsOwnIds...` | not run |
| S-65 record keys across clinics | `aRecordCannotPointAtAnotherClinicsPatientVisit...` | not run |
| S-66 record grants | `theAppChangesOnlyTheEditableColumnsOfARecord...` | not run |
| S-67 partitions (far month now worked out from today, DEC-049) | `aRecordLandsInThePartitionOfItsMonth...`, `thePartitionHelperIsIdempotent...` | not run |
| S-72 to S-78 partition upkeep, S-79 monitor | see section "DEC-049 items 2 and 3" below (`EmrPartitionUpkeepIT`, `EmrPartitionUpkeepJarIT`, `EmrPartitionUpkeepMonitorWiringTest`) | not run (S-79 wiring: fail) |
| S-68 concurrency | `EmrRecordsIT.sixOpensAtTheSameMoment...`, `aStaleRevisionIs409...`; `EmrIpdRecordsIT.fiveOpens...` (two); `EmrDepartmentsIT.twoPublishes...`, `twoSaves...`; `EmrAddDepartmentIT.twoRequestsWithTheSameName...` | not run |
| S-69 query plans (find by visit, by admission and round, by case, patient signed read, patient created read) | **not written** (see gaps) | - |
| S-70 `opd.visit` REVISIT accepted, WALKIN refused, appointment still refuses REVISIT | `EmrVisitTypeIT.theDatabaseAcceptsRevisitOnAVisit...` | not run |
| S-71 backward compatibility | the existing suites (`QueueIT`, `WalkInTokenJourneyIT`, `AppointmentDoubleBookingIT`, the IPD classes, `ClinicMastersIT`, `TenantIsolationIT`, `PooledConnectionIT`) are run by `verify`; no new test | not run |

## DEC-049 items 2 and 3 (13 Nov 2026): patient read roles and partition upkeep

Decision: `docs/decisions/DEC-049-closing-older-open-items.md` (answers 2 and 3). Contract: `docs/api/patient.yaml` 0.2.1. Design: this story's database note, "Partition upkeep", cases S-72 to S-78. **The database tests have not run**: they compile (whole-project `mvn -o test`, no database) and run once `sahaj_test` is recreated (`mvn verify`). The one database-free wiring test (`EmrPartitionUpkeepMonitorWiringTest`) ran and **fails**, see finding F-DEC49-1.

### Item 2: who may read patient details (closes DEC-013 item 14)

| Criterion (patient.yaml 0.2.1) | Test | Result |
| --- | --- | --- |
| BILLING and PHARMACIST get 200 on `GET /patients`, `/patients/{id}`, `/patients/{id}/family`; no `dateOfBirth` property (absent, not null); `age` and `ageDetail` present; the date is not in the body text | `PatientsIT.billingAndPharmacistReadTheListDetailAndFamilyWithAgeAndNoDateOfBirth` (dated patient plus a family member on the same mobile; list, search `q`, detail, family) | not run |
| Same, for a dated and an undated patient, strict (replaces the old "200 or 403" test) | `PatientDateOfBirthIT.billingAndPharmacistReadWithTheDateAbsentAndCounsellorGets403` | not run |
| COUNSELLOR gets 403 (problem details) on the list, a search, the detail and the family | `PatientsIT.counsellorGets403OnTheListTheDetailAndTheFamily`; `PatientDateOfBirthIT.billingAndPharmacistReadWithTheDateAbsentAndCounsellorGets403` (last part: no date in the 403 body) | not run |
| Role: reading does not give write: BILLING and PHARMACIST still get 403 on create and update | `PatientsIT.billingAndPharmacistStillCannotWritePatientsAndCannotReadAnotherClinicsPatient`; `PatientsIT.onlyOwnerAndFrontDeskMayWritePatients` (unchanged) | not run |
| Tenant isolation: another clinic's patient is 404 for these roles, and their list shows no patient of another clinic | `PatientsIT.billingAndPharmacistStillCannotWritePatientsAndCannotReadAnotherClinicsPatient` | not run |
| The five roles of `DateOfBirthAccess` still get the date | `PatientDateOfBirthIT.theDateIsPresentForTheFiveRolesAndNullWhenNotRecorded` (unchanged) | not run |
| Unauthenticated 401 (unchanged) | `PatientsIT.unauthenticatedIs401` | not run |

The skipped test `PatientsIT.everyClinicRoleMayReadPatientsAsTheContractSays` (`@Disabled`) is removed; no `@Disabled` is left in `PatientsIT` or `PatientDateOfBirthIT`.

### Item 3: partition upkeep of `opd.emr_record` (cases S-72 to S-78 of the database note, plus S-79 the monitor)

New classes in `backend/src/test/java/com/sahaj/platform/it/`: `EmrPartitionUpkeepIT` (Spring context on `sahaj_test`, 13 tests), `EmrPartitionUpkeepJarIT` (failsafe, opens the packaged jar, 3 tests) and `EmrPartitionUpkeepMonitorWiringTest` (no database, 1 test). **How the data is kept safe:** every case that makes, hides or fills a partition runs as `sahaj_migrator` in one transaction that `DbAccess.asMigrator` rolls back (PostgreSQL DDL is transactional), so no partition made by a test stays, and **no partition is ever dropped**. The skip case writes its row in that same transaction (a temporary policy for the migrator, rolled back with the rest), so no committed row of a throw-away month is left and nothing has to be cleaned up.

| Case | Test | Result |
| --- | --- | --- |
| S-72 current UTC month and next 12 exist after the context start, each with RLS enabled and forced, one policy `p_emr_record_YYYY_MM_tenant`, no privilege for `sahaj_app` or PUBLIC; count function at least 12 | `EmrPartitionUpkeepIT.afterTheContextStartedTheCurrentMonthAndTheNextTwelveExist...` | not run |
| S-73 upkeep(12) as `sahaj_migrator` returns no rows, twice; upkeep(0) none; `ensure_partitions` untouched (24 months returns 0, a new month 1, then 0) | `theUpkeepOfTwelveMonthsReturnsNoRowsTwiceAndTheEnsureHelperIsUntouched` | not run |
| S-74 upkeep(24) returns `created` for exactly the missing months (worked out from the catalog first), each safe, count function rises by that number, second call empty | `theUpkeepOfTwentyFourMonthsCreatesExactlyTheMissingMonthsSafelyAndIsThenIdempotent` | not run |
| S-74 boundaries: 60 accepted; 61, -1, null, 1000 refused with SQLSTATE 22023 and the text "between 0 and 60" | `sixtyMonthsAreAccepted`, `sixtyOneMinusOneAndNullAreRefusedWithInvalidParameterValue` | not run |
| S-75 a month whose rows sit in the default partition (+50 months) is skipped: result row `skipped_default_partition_has_rows`, no exception, every other missing month to +60 created, no partition for that month, warning text names the partition and holds no clinic id or row text, second call returns only the skip row; nothing stays after the rollback | `aMonthWhoseRowsAreInTheDefaultPartitionIsSkippedWithAWarning...` | not run |
| S-76 `sahaj_app` has no EXECUTE on the upkeep function and a call answers 42501; PUBLIC has no EXECUTE on either function (explicit ACL) | `theUpkeepFunctionCannotBeRunByTheAppOrPublic...` | not run |
| S-77 count function as `sahaj_app` with and without a clinic bound equals the catalog count of monthly partitions after the current month (default not counted, matches the name pattern only), at least 12; `prosecdef`, `proconfig` = `search_path=pg_catalog, pg_temp`, returns integer; EXECUTE for `sahaj_app` and the owner only | `theCountFunctionEqualsTheMonthlyPartitionsAfterTheCurrentMonth...` | not run |
| S-78 callback file in main resources (runs `opd.emr_record_upkeep_partitions(12)` only when the function exists; no drop, grant or delete), migration file there, Flyway locations in the test context still list `classpath:db/migration` and `classpath:db/guard` | `theCallbackFileIsInMainResourcesRunsTheUpkeepOfTwelveMonths...` | not run |
| S-78 a second `migrate` with nothing pending executes 0 migrations, Flyway logs the SQL callback "opd emr record partition upkeep", and months 0 to +12 all exist afterwards | `aSecondMigrateWithNothingPendingRunsTheCallbackAgain...` | not run |
| S-78 the packaged jar holds the callback, the migration and `EmrPartitionUpkeepMonitor.class`; the callback in the jar is byte for byte the class path copy; it is not in `db/migration`; the dev-tools guard callback is still there | `EmrPartitionUpkeepJarIT` (3 tests) | not run (failsafe, `mvn verify`) |
| S-79 Spring can create the monitor bean from the real class (database-free) | `EmrPartitionUpkeepMonitorWiringTest.springCanCreateTheMonitorBeanFromItsJdbcClientConstructor` | **fail** (F-DEC49-1) |
| S-79 the real monitor bean on the real database logs nothing with 12 or more future months | `theRealMonitorBeanOnTheRealDatabaseReadsTheCountAndLogsNothing...` | not run |
| S-79 WARN with fewer than 3 future months on a real database: exactly 3 is quiet, 2 gives one WARN with "fewer than 3" and "count: 2" and no partition name, 0 gives a second WARN (boundary 3 / 2) | `theMonitorWarnsOnARealDatabaseWithUnderThreeFutureMonthsAndNotWithThreeOrMore` | not run |
| S-79 a failing call (the database answers with an error; the connection is refused) gives one WARN per call with the exception class only (no database text, no password, no URL, no stack trace) and never throws | `aFailingCallOnARealDatabaseLogsOneWarnWithTheClassOnlyAndNeverStopsTheCaller` | not run |

**How S-79 is done without disturbing other tests.** The future months are hidden by *renaming* partitions inside one open `sahaj_migrator` transaction (the count function matches the name pattern), and the monitor under test reads through that same connection (`SingleConnectionDataSource`), so no other connection, test or context ever sees fewer months; the transaction is rolled back at the end. The failing call is made by renaming the count function in a rolled-back transaction; the refused connection uses a wrong password against the real server. The pure threshold logic (under 3, count only in the message, exception class only) is also in the unit test `EmrPartitionUpkeepMonitorTest`.

**Not done, on purpose (instructions).** The test of the callback re-making a *dropped* month cannot be done within the rule "drop nothing but a far-future month made by the test": the callback only reaches 12 months ahead, and every month in that range already exists. S-78 therefore proves the callback is picked up by running a real second `migrate` and reading Flyway's log line, and S-74 and S-75 prove what the function does when months are missing, in a rolled-back transaction. Also, `sahaj_migrator` cannot insert into `opd.emr_record` by itself (forced row level security and no policy for it), which is why S-75 adds temporary, rolled-back policies in its own transaction.

### Tests made independent of the clock and of the number of partitions

- `RowLevelSecurityCatalogIT.theEmrRecordTableAndEveryOneOfItsPartitionsAreProtected...`: no fixed 26. It now asserts at least the 24 monthly partitions of the migration (Nov 2026 to Oct 2028), the default partition and the parent, every other name is a month, all with RLS enabled and forced and one policy. The catalog query was also made exact (`^emr_record(_default|_[0-9]{4}_[0-9]{2})?# SAH-53, SAH-45, SAH-54, SAH-46, SAH-47, SAH-52 (backend) and the SAH-43 changes · Test plan: Doctor EMR by department

Stories: `docs/stories/SAH-53.md` (53-1 to 53-5), `SAH-45.md` (45-1 to 45-6), `SAH-54.md` (54-1 to 54-4), `SAH-46.md` (46-1 to 46-4), `SAH-47.md` (47-1 to 47-6), `SAH-52.md` (52-1 to 52-4). Requirement: `docs/requirements/SAH-45.md`.
Contract: `docs/api/opd.yaml` 0.3.0. Decision: ADR-020 (replaces ADR-019 D4 to D6; ADR-019 masters and ranking are kept). Stored shape and cases S-50 to S-71: `docs/database/SAH-53.md`. Approval: the human pre-approved the stories in chat (DEC-039 and its addenda); no hook blocked this work.
Replaces: the SAH-44 part of `docs/test-plans/SAH-43-44.md` (templates, overrides, settings; withdrawn). The SAH-43 parts (masters, ranking per doctor, doctor details) stay in force and are re-run here.
Module: opd (reads ipd through `EmrEncounterSource`, platform through its `api`).

**Status of this document: written and compiled, not yet run.** The tests compile (`mvn -f backend/pom.xml test-compile` passes). `sahaj_test` still holds the old Flyway checksums, so no test, `verify` or `validate` has been run. Every "Result" below is therefore **not run**; the coverage table is filled in again after the human recreates `sahaj_test` and the gate runs.

**Screens.** SAH-53 has no screen. SAH-45, 54 (screens), SAH-46-1, 47 (frame) and 52 are Angular work that does not exist yet (the mockup EMR pages are the design; the Queue card button and the EMR frame are not built). The criteria that only a screen can show (words on the page, switches, warnings on leaving, the busy state) are marked **Screen** and are covered by the angular-developer's unit tests and by Playwright tests in `frontend/e2e/` once the pages exist. **No Playwright test is written in this pass** (nothing can be driven). The backend half of each of those criteria (the data and the rule behind the switch) is covered below.

## How the tests are built

Every integration test goes through the real application (profile `test`, the whole Spring context, MockMvc through the real security chain) against the real `sahaj_test` PostgreSQL database as `sahaj_app`. Nothing is mocked. Clinics, doctors, patients, visits, admissions and Emergency cases are made through the real endpoints (sign-up, the setup wizard, Team, Patient, Queue, IPD). Direct SQL is used only for what no endpoint can do: a doctor's specialty text, the clinic's specialty code, a removed membership, signing a record (SAH-51 does not exist yet: `status = 'SIGNED'` is set by an `update` that the column grant allows), an earlier day's IPD note, and reading rows back to prove what was stored. Throwaway data only: made-up names and mobile numbers.

| Class (all in `backend/src/test/java/com/sahaj/platform/it/`) | Tests | What it covers |
| --- | --- | --- |
| `EmrSupport` | - | Shared set-up: staff of every role, department bodies, record bodies, SQL helpers. Reworked: the template and settings helpers are gone. |
| `EmrGlobalTablesIT` (rewritten) | 27 | S-50 to S-57 and the masters: seed counts, starting values of the story lists, off-at-start lists, order, None option, option sources, grants, immutable starter layout, layout documents against the tables, aliases, masters hints, comments. |
| `EmrClinicTablesIT` (rewritten) | 19 | S-60 to S-67 and the usage table: RLS on five tables and 25 partitions, grants (column level), two clinics, department, version, doctor and record checks and keys, partitioning, helper function. |
| `EmrLibraryIT` (new) | 13 | 53-1 through the API: library and starters, roles, 401, 403 on every write, 404, counts, order, field types, hide locks. |
| `EmrDepartmentsIT` (new) | 29 | 45-1 to 45-5, 53-2 to 53-5: list, read, Save draft, Publish, versions, reset target, published layout, rules, 400/403/404/409/413, concurrency, audit, two clinics. |
| `EmrAddDepartmentIT` (new) | 17 | 54-1 to 54-4: blank, copy, starter, review, name, initials, colour, rooms, doctors, limit of 50, audit, draft until published. |
| `EmrRecordsIT` (new) | 28 | 46-1 to 46-3, 47-1 to 47-5, 52-1, 52-4 (OPD): open, idempotent, concurrency, stage, own patient, layout reference, Save draft, Done rule, still needed, value rules, 409, signed, switch, admission advice, header, copy from last visit. |
| `EmrIpdRecordsIT` (new, extends `ScopeSupport`) | 12 | 46-4, 52-1: admission and Emergency case, per-day note, concurrency, drafts off, roles, ward scope and break-glass, discharged, closed, module off, no patient name in logs. |
| `EmrVisitTypeIT` (new) | 6 | 52-2 and the Visit changes: visit-type PATCH, `emrRecordId` and `emrStatus`, audit, roles, database check (S-70). |
| `EmrDoctorContextIT` (rewritten) | 17 | 43-3 and 46-3: `/emr/me` with the new `department`, alias list, clinic default, assigned departments, port. The SAH-44 resolver and snapshot tests are removed (moved to `EmrRecordsIT`). |
| `EmrMastersIT` (edited) | 25 | 43-1: specialty is now one of the 15 starter keys (gp and homeo are 400), write paths are the library and starters, retired paths are 404, advice `nameMr` and `nameHi`. |
| `EmrQuickOptionsIT` (unchanged) | 21 | 43-2: ranking per doctor. Still valid (hints dental and paeds are starter keys). |
| `EmrLogPrivacyIT` (rewritten) | 3 | BR45-5: no log line (as configured and at DEBUG), no refusal body and no audit entry holds the doctor's details, the patient's name or allergy, clinical text, a referral line, a search text or an unknown field name. |
| `RowLevelSecurityCatalogIT` (edited) | 13 | `NO_RLS_BY_DESIGN` now has the ten global EMR tables; new test for `opd.emr_record` and its 25 partitions. |
| `VitalsCatalogIT` (edited) | 6 | The four layout immutability triggers by name and enabled; FORCE RLS on the five clinic EMR tables. |

Deleted (withdrawn SAH-44 model): `EmrTemplatesReadIT` (11), `EmrOverridesIT` (21), `EmrSettingsIT` (12).
New or rewritten in this pass: 171 tests in 10 classes (`EmrGlobalTablesIT`, `EmrClinicTablesIT`, `EmrLibraryIT`, `EmrDepartmentsIT`, `EmrAddDepartmentIT`, `EmrRecordsIT`, `EmrIpdRecordsIT`, `EmrVisitTypeIT`, `EmrDoctorContextIT`, `EmrLogPrivacyIT`); edited: `EmrMastersIT` (25 tests in all), `RowLevelSecurityCatalogIT` (+1), `VitalsCatalogIT` (2 reworked); unchanged: `EmrQuickOptionsIT` (21). The EMR group is 217 tests plus the 3 catalog tests.

Roles used in every access test: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist, counsellor. "Claim" tests use a token whose role says doctor or owner while the membership says otherwise (the service re-reads the membership).

## Coverage: SAH-53-1 (library and 15 starters)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | 100 sections: 14 Common, 78 Specialty, 8 Compliance, unique stable key, name 1 to 200, kind, description, field list | `EmrGlobalTablesIT.theSeedHasTheCountsOfTheDesignNote`; `EmrLibraryIT.theLibraryHoldsHundredSections...` | not run |
| 2 | A section with no field list carries Findings (rich text), Template (dropdown), Attachments (file) | `EmrGlobalTablesIT.everyFieldHasALabelATypeOfTheFixedListAMetaTextAndARequiredFlagAndEverySectionHasAField` (structure and count; the row-by-row comparison with the mockup file is the db-engineer's parse-back, see gaps) | not run |
| 3 | Each field has label, type from the fixed list, meta, required; another type cannot be stored | same test (`FREE_TEXT` refused 23514); `EmrLibraryIT.everyFieldHasALabelAFixedType...` | not run |
| 4 | The 15 starters, initials, colour, ordered section list | `EmrGlobalTablesIT.theFifteenStartersAreTheMockupDepartments...`; `EmrLibraryIT.theFifteenStartersComeInMockupOrder...` | not run |
| 5 | Per department 19, 24, 25, 23, 27, 22, 22, 22, 20, 22, 21, 21, 27, 26, 20 = 341; every library section used | same tests; `EmrGlobalTablesIT.theDefaultOrderIsTheFourteenCommonSections...` | not run |
| 6 | Starting values (Required 12, Print 12, Copy 10, Who can fill, Part 39, Off at start) as drawn | `EmrGlobalTablesIT.theStartingValuesAreSeededAsDrawnInTheStoryLists`, `theSectionsOffAtStartAreTheListedOnes...`; `EmrLibraryIT.aStarterIsReadWithEverySection...` | not run |
| 7 | Any role including the owner: create, change, delete of a library section or starter gets 403, nothing changes | `EmrLibraryIT.everyWriteToTheLibraryOrTheStartersIs403ForEveryRole...`; `EmrGlobalTablesIT.theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable` | not run |
| 8 | Any signed-in role reads, same for every clinic; no login is 401; no patient data | `EmrLibraryIT.everyClinicRoleReads...`, `theLibraryAndTheStartersAreTheSameForEveryClinic`, `thereIsNoHomeopathyStarterAndNoSectionOrStarterTextHoldsPatientData` | not run |
| 9 | The seed holds no patient data | `EmrGlobalTablesIT.noSeededTextLooksLikeAPatientNameOrMobileNumber` | not run |
| 10 | A later library change does not change a published department (own copy) | `EmrLibraryIT.aPublishedVersionKeepsItsOwnCopyOfEverySectionDefinition...` (the version holds the definition and fields; a real migration cannot be run in a test) | not run |
| 11 | Unknown starter or section key is 404, not an empty template (gp, homeo included) | `EmrLibraryIT.anUnknownSectionKeyIs404...`, `aStarterKeyThatIsNotOneOfTheFifteenIs404StarterNotFound...` | not run |
| 12 | `dental` and `paeds` valid again at the new paths, 404 at the removed `/emr/templates/**` | `EmrLibraryIT.aStarterKeyThatIsNotOneOfTheFifteen...`; `EmrMastersIT.theRetiredTemplateAndSettingsPathsNoLongerExist...` | not run |
| 13 | A required choice list includes None or Nothing abnormal | `EmrGlobalTablesIT.everyRequiredMultiSelectOrChecklistFieldWithOptionsHasOneNoneOption...`; `EmrLibraryIT.aRequiredChoiceListWithNoneOrNothingAbnormal...` | not run |
| 14 | No Homeopathy department; a Homeopathy clinic gets the default | `EmrDoctorContextIT.thereIsNoHomeopathyDepartmentAndAHomeopathDoctorGetsTheClinicDefault`; `EmrLibraryIT.thereIsNoHomeopathyStarter...` | not run |

## Coverage: SAH-53-2 (the six settings, part, order) and SAH-45-2, 45-3 (backend half)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Draft read: every section with the six settings, part, position, from the starter values | `EmrDepartmentsIT.theDepartmentIsReadWithEverySectionHiddenOnesTooTheSixSettings...` | not run |
| 2 | Hidden saved as hidden; hidden and required is 400 with a reason; hiding a required section clears Required in the same change | `EmrDepartmentsIT.aHiddenSectionCannotBeRequiredAndHidingARequiredSection...` | not run |
| 3 | "x of y on" and "required" count shown sections only | same test; `aNewClinicSeesTheFifteenStarters...` (oracle read from the seed tables) | not run |
| 4 | Move to the other part keeps overall position; Up and Down swap; top or bottom of a part is a success | `EmrDepartmentsIT.movingASectionToTheOtherPartChangesOnlyItsPart...` (the server stores the order the page sends) | not run |
| 5 | Only Allergies cannot be hidden (any department); other sections including Rx can | `EmrDepartmentsIT.onlyAllergiesAndPcpndtFormFInObstetricsCannotBeHidden...` | not run |
| 6 | PCPNDT Form F cannot be hidden in Obstetrics & Gynaecology; other Compliance sections can | same test (also a department added from og) | not run |
| 7 | Every section hidden or nothing required is accepted | `EmrDepartmentsIT.aDepartmentWithEverySectionHiddenExceptTheLockedOneOrNothingRequired...` | not run |
| 8 | A key not in the department is 404, nothing stored; a setting value outside the list is 400 | `EmrDepartmentsIT.theSectionSetMustBeExactlyTheDepartments...`, `aValueOutsideTheListedOnesOrAnExtraFieldIsA400...` | not run |
| 9 | A label, kind, description or field in the request is 400 | `EmrDepartmentsIT.aValueOutsideTheListedOnesOrAnExtraFieldIsA400AndNothingIsStored` | not run |
| 10 | A clinic id in the body is ignored | `EmrDepartmentsIT.aBodyOverSixtyFourKilobytesIs413AndAClinicIdInTheBodyIsIgnored`; `EmrAddDepartmentIT.theClinicIdInTheBodyIsIgnored...` | not run |
| 11 | A change that fails half way stores nothing | `EmrDepartmentsIT.aChangeThatFailsHalfWayStoresNothing...` | not run |
| 12 | Who can fill is stored and shown only (no role but the doctor writes the EMR) | `EmrDepartmentsIT.saveDraftStoresTheChangeAsADraft...` (whoCanFill stored); `EmrRecordsIT.onlyADoctorAVisitingDoctorOrAnOwner...` (nurse and others cannot write) | not run |
| 13 | Words, switches, "Section settings" panel, 44 px targets, disabled switches for other roles | **Screen** | - |

## Coverage: SAH-53-3 (Save draft and Publish)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Save draft: new revision, published version unchanged, doctors still see the published layout | `EmrDepartmentsIT.saveDraftStoresTheChangeAsADraftOfThatDepartmentAndDoctorsStillSeeThePublishedLayout`, `whatDoctorsReadIsTheShownSectionsOfTheLatestVersion...` | not run |
| 2 | No change: success "Nothing new to save" / "Nothing to publish", no audit, no version | `EmrDepartmentsIT.aSaveWithNoChangeIsNothingNew...`, `publishWithNothingNewIsNothingToPublish...` | not run |
| 3 | Publish: next number (v6 to v7), Live, draft cleared, message data | `EmrDepartmentsIT.theFirstPublishIsVersionOneTheStatusBecomesLive...` (v1 to v7) | not run |
| 4 | Never published: first version is v1 | same test | not run |
| 5 | A visit opened with n still reads n after n+1 | `EmrRecordsIT.aRecordKeepsTheLayoutItOpenedWith...`, `aRecordOpenedOnTheStarterLayoutKeepsIt...`, `theRecordRowPointsAtTheStarterLayoutOrTheDepartmentVersion...` | not run |
| 6 | A published version cannot be changed | `EmrClinicTablesIT.aPublishedVersionIsInsertOnly...` (privilege, policy, trigger by name in `VitalsCatalogIT`); `EmrDepartmentsIT.aPublishedVersionIsReadExactlyAsPublished...` (version 1 unchanged after v2, v3) | not run |
| 7 | Two owners, older revision: 409, nothing applied | `EmrDepartmentsIT.aSaveOrPublishWithAnOlderRevisionIs409...` | not run |
| 8 | Publish twice at the same moment: one version | `EmrDepartmentsIT.twoPublishesAtTheSameMomentMakeOneVersion...` (also two saves) | not run |
| 9 | Another clinic's drafts and versions never visible or changeable | `EmrDepartmentsIT.anotherClinicsOwnerNeverSees...`, `theDepartmentRowsAndVersionsCannotBeReachedAcrossClinicsInTheDatabaseEither`; `EmrClinicTablesIT.oneClinicCannotRead...` | not run |
| 10 | Doctor, visiting doctor, nurse, front desk, billing, pharmacist: 403; no login 401 | `EmrDepartmentsIT.everyRoleButTheOwnerIs403OnSaveDraftPublishAndAddDepartment...` (incl. a token that claims OWNER) | not run |
| 11 | One audit entry: who, when, department, version or revision, before and after of each changed setting, no patient data | `EmrDepartmentsIT.theAuditEntriesCarryWhoWhatAndTheBeforeAndAfter...` | not run |

## Coverage: SAH-53-4 (reset and undo) and SAH-45-5

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Reset: draft takes the starter values, the count of changes rises, published unchanged | `EmrDepartmentsIT.resettingPutsTheStarterValuesInTheDraftCountsTheChange...`, `theResetTargetIsTheStarterSettingsOfExactlyThisDepartmentsSections...` | not run |
| 2 | Undo returns the draft exactly as before; later change removes Undo | `resettingPuts...` (saving the earlier settings back restores them; Undo itself is a page action, **Screen** for the offer) | not run |
| 3 | Already equal to the starter: success, nothing changes, no audit | `EmrDepartmentsIT.aDepartmentAlreadyEqualToItsStarterValuesResets...` | not run |
| 4 | Covers only the selected department | `EmrDepartmentsIT.aResetCoversOnlyTheSelectedDepartment` | not run |
| 5 | Saved and published: new version from starter values, earlier versions kept | `resettingPuts...` (v2 from the reset, v1 kept) | not run |
| 6 | Wrong role 403; another clinic's department 404 | `everyRoleButTheOwnerIs403...`, `anotherClinicsOwnerNeverSees...` (reset-target 404) | not run |
| 7 | One audit entry with before and after counts of shown and required sections | `resettingPuts...` (`EMR_DEPARTMENT_RESET`, `sectionsOn`, `requiredOn`) | not run |
| 8 | 409 when another owner changed it | `aSaveOrPublishWithAnOlderRevisionIs409...` (the reset is saved with origin RESET through the same call) | not run |

## Coverage: SAH-53-5 (layout doctors read)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 1 | Published version: shown sections only, in order, with name, kind, part, settings, fields | `EmrDepartmentsIT.whatDoctorsReadIsTheShownSectionsOfTheLatestVersion...` | not run |
| 2 | No published version: starter values, answer says starter | same test (`layoutSource STARTER`) | not run |
| 3 | A draft is not in what doctors read | same test | not run |
| 4 | A given version number is returned as published | `EmrDepartmentsIT.aPublishedVersionIsReadExactlyAsPublished...` | not run |
| 5 | Another clinic's department is 404 | `anotherClinicsOwnerNeverSees...` | not run |
| 6 | Nurse, front desk, billing, pharmacist can read it; no login 401 | `whatDoctorsRead...` (nurse, pharmacist, doctor), `everyClinicRoleReadsTheListAndTheDepartment...` | not run |
| 7 | No log line holds patient data | `EmrLogPrivacyIT` (three tests) | not run |

## Coverage: SAH-45 (the page, backend half)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 45-1.1 to 45-1.3 | Title, line, clinic name; cards with initials, name, status, version, "x of y"; new clinic lists 15 as Template v0 until first save | **Screen** for the words; data: `EmrDepartmentsIT.aNewClinicSeesTheFifteenStarters...` | not run |
| 45-1.4 | Old "Start from your specialty" cards keep working | not part of this change (SAH-7 tests `ClinicMastersIT` re-run as regression) | not run |
| 45-1.5, 45-1.6 | Search on the page; tap selects first Specialty section | **Screen** (search is on the page, ADR-020 D5) | - |
| 45-1.7 | Departments the clinic added are listed with their own initials and colour; status Template or Draft | `EmrAddDepartmentIT.aStarterGivesADraftDepartment...` (list after creation) | not run |
| 45-1.8 | Other roles see the same list, no change possible, direct calls 403 | `EmrDepartmentsIT.everyClinicRoleReadsTheListAndTheDepartmentButOnlyTheOwnerCanEdit` (`canEdit`), `everyRoleButTheOwnerIs403...` | not run |
| 45-1.9 | Another clinic's owner never sees these departments | `EmrDepartmentsIT.anotherClinicsOwnerNeverSees...` | not run |
| 45-1.10 | Loading and error states | **Screen** | - |
| 45-2.x | Groups, rows, switches, panel, chips, read-only fields, locks | **Screen**; rules: see 53-2 table; field list read-only: `theDepartmentIsReadWithEverySection...` | not run |
| 45-3.x | Move and order; accessible names | **Screen**; server half: `movingASectionToTheOtherPart...` | not run |
| 45-4.x | Chip "n unsaved change(s)", warning on leaving, double tap | **Screen**; server half: 53-3 table (the baseline is the working layout, `layoutSource`) | not run |
| 45-5.x | Reset and Undo | 53-4 table; **Screen** for the message | not run |
| 45-6.x | Preview, visit type selector, links | **Screen**; the preview reads `GET /emr/departments/{key}` (draft, never writes) | - |

## Coverage: SAH-54 (add department)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 54-1.1 to 54-1.2 | Page, stepper, Start blank, Copy, Specialty templates | **Screen**; data: `EmrLibraryIT.theFifteenStartersComeInMockupOrder...` (counts as drawn: Physiotherapy 10, Dental 7) | not run |
| 54-1.3 | All 15 starters offered | same test | not run |
| 54-1.4 | Template preview: name, description, sections, "Turned off by default" | `EmrLibraryIT.theFifteenStarters...` (`description`, `offAtStart`), `aStarterIsReadWithEverySection...` | not run |
| 54-1.5 | Matching print template: Print flags of the starter, no separate template | `EmrAddDepartmentIT.withoutTheMatchingPrintTemplateEveryPrintFlagIsCleared...` | not run |
| 54-1.6 | Copy lists the clinic's own departments; none disables | `EmrAddDepartmentIT.aCopyEqualsTheSourceDepartmentsSavedDraft...` (a never-saved starter cannot be copied: 404); disabling is **Screen** | not run |
| 54-1.7 | Cancel creates nothing | **Screen** (no call is made) | - |
| 54-1.8 | Other roles 403 | `EmrDepartmentsIT.everyRoleButTheOwnerIs403...` | not run |
| 54-1.9 | Another clinic's departments never in Copy | `EmrAddDepartmentIT.theClinicIdInTheBodyIsIgnoredAndAnotherClinicsDepartmentCannotBeCopied` | not run |
| 54-2.x | Review sections: changes replace the source; unchanged equals the source; Start blank offers 14 + 3 | `EmrAddDepartmentIT.reviewedSectionsReplaceTheSourceSettingsAndFollowTheDraftRules`, `aBlankDepartmentHasTheFourteenCommon...`, `aStarterGivesADraftDepartment...` | not run |
| 54-3.x | Doctors and rooms: only active doctors and visiting doctors of this clinic; several departments per doctor; no doctor allowed; room length 400 | `EmrAddDepartmentIT.theDoctorListOffersOnlyActiveDoctors...`, `doctorsAreAssignedWhenTheyAreActiveDoctors...`, `roomsAreTextLabelsOf...` | not run |
| 54-3.6 | The assignment is used by SAH-46 | `EmrDoctorContextIT.whenTheSpecialtyIsNotInTheListTheDoctorsPublishedDepartmentIsUsed...`, `withSeveralDepartmentsTheOneOfTheClinicsSpecialtyIsUsed...` | not run |
| 54-4.1 | Created Draft with chosen name, initials, colour, sections | `EmrAddDepartmentIT.aStarterGives...`, `initialsAndColourAreDerived...` | not run |
| 54-4.2 | Name exists (any case) 400; empty or too long 400 | `EmrAddDepartmentIT.theNameIsUniqueInTheClinic...`, `anEmptyOrTooLongNameIs400...` | not run |
| 54-4.3 | Two creates with the same name at the same moment: one wins | `EmrAddDepartmentIT.twoRequestsWithTheSameNameAtTheSameMoment...` (see finding F-3) | not run |
| 54-4.4 | From a starter equals the starter; a copy equals the source's draft, not its history | `aStarterGives...`, `aCopyEqualsTheSourceDepartmentsSavedDraft...` | not run |
| 54-4.5 | Doctors do not see it until published | `creatingOneAuditEntry...AndDoctorsDoNotSeeItUntilPublished`, `EmrDoctorContextIT.whenTheSpecialtyIsNotInTheList...` | not run |
| 54-4.6 | One audit entry: who, when, id, name, source | `EmrAddDepartmentIT.creatingOneAuditEntry...` | not run |
| 54-4.7 | Wrong role 403; another clinic never read; clinic id ignored | `everyRoleButTheOwnerIs403...`, `theClinicIdInTheBodyIsIgnored...` | not run |
| 54-4.8 | Busy state, failure keeps the wizard | **Screen** | - |
| limit | At most 50 added departments, the 51st is 400 `department-limit` | `EmrAddDepartmentIT.aClinicCanAddFiftyDepartmentsAndTheFiftyFirstIs400DepartmentLimit` | not run |
| contract | Source must be complete (STARTER needs starterKey, COPY needs copy key, BLANK neither) | `EmrAddDepartmentIT.theSourceMustBeCompleteAndOfTheRightKind` (see finding F-2) | not run |

## Coverage: SAH-46 (open the EMR, choose the department, keep the layout)

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 46-1.1 | Button on OPD, IPD, Emergency cards for doctor, visiting doctor, owner with details | **Screen**; the card shows it when `GET /emr/me` answers 200: `EmrDoctorContextIT.aDoctorGetsTheirOwnDetails...`, `anOwnerWithDoctorDetailsGetsThem...` | not run |
| 46-1.2 | Other roles: no button and a direct open is 403 | `EmrRecordsIT.onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsOpens...`; `EmrIpdRecordsIT.anOwnerWithDoctorDetailsAndADoctorOpenAnyAdmission...` | not run |
| 46-1.3 | Another doctor's patient: 403; owner with details can open any | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 46-1.4 | IPD off: no IPD cards, open is 403 module-off | `EmrIpdRecordsIT.anotherClinicsAdmissionOrCaseIs404AndAnIpdThatIsOffIs403ModuleOff` | not run |
| 46-1.5 | "Consult done" unchanged | not touched by the code under test (queue tests `QueueIT` re-run) | not run |
| 46-1.6 | A signed visit opens read-only | `EmrRecordsIT.draftsAreForOpdVisitsOnlyAndASignedRecordIsRefused...` | not run |
| 46-1.7 | Clinic B opening clinic A's visit: 404, no data | `EmrRecordsIT.anotherClinicsVisitAndRecordAreNotFound...`; `EmrIpdRecordsIT.anotherClinicsAdmissionOrCase...` | not run |
| 46-2.1 | Waiting or Vitals moves to With doctor through the workflow | `EmrRecordsIT.openingMovesAWaitingVisitToWithDoctor...` | not run |
| 46-2.2 | Already With doctor: nothing changes | same test; `openingAgainIsSafeToRepeat...` (no duplicate stage entry) | not run |
| 46-2.3 | Move fails: EMR still opens, message that the queue was already updated | `EmrRecordsIT.openingMovesAWaitingVisit...` covers MOVED, ALREADY_WITH_DOCTOR and NOT_APPLICABLE (a Done visit); ALREADY_UPDATED needs a race that cannot be forced (unit test `VisitServiceStartConsultationTest`) | not run |
| 46-2.4 | Back arrow keeps filter and draft | **Screen** (the draft is kept: `saveDraftStoresTheValues...`) | - |
| 46-2.5 | Two doctors can both open | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` covers the owner and the visit's doctor; "two doctors on one visit" cannot happen by the own-patient rule | not run |
| 46-2.6 | IPD and Emergency change no OPD stage | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...` (audit of stage moves unchanged) | not run |
| 46-2.7 | One audit entry per open: who, when, ids, no clinical text | `EmrRecordsIT.openingAgainIsSafeToRepeat...`, `EmrIpdRecordsIT.noLogLineOrAuditEntry...` | not run |
| 46-3.1 | Specialty in the alias list (any case, extra spaces) chooses the department | `EmrDoctorContextIT.theSpecialtyNamesOfTheAliasList...`; `EmrRecordsIT.theDepartmentIsChosenOnceWhenTheRecordIsOpened...` | not run |
| 46-3.2 | Else the doctor's assigned departments: clinic's specialty match, else first by name | `EmrDoctorContextIT.withSeveralDepartmentsTheOneOfTheClinicsSpecialtyIsUsed...` | not run |
| 46-3.3 | Else the clinic default, and the answer says so | `EmrDoctorContextIT.aSpecialtyNotInTheListFallsBackToTheClinicDefaultAndSaysSo`, `thereIsNoHomeopathyDepartment...` | not run |
| 46-3.4 | Published version used, else starter layout | `EmrRecordsIT.theRecordRowPointsAtTheStarterLayoutOrTheDepartmentVersion...` | not run |
| 46-3.5 | First open stores the snapshot (department, version or starter, shown sections with settings and fields) | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...`, `theRecordRowHoldsReferencesAndOneDocument...` (stored as a reference to the immutable layout, ADR-020 D8) | not run |
| 46-3.6 | Later publish or reset does not change the visit; required judged by it | `EmrRecordsIT.aRecordKeepsTheLayoutItOpenedWith...` | not run |
| 46-3.7 | Signed visit: snapshot cannot be changed (409) | `EmrRecordsIT.draftsAreForOpdVisitsOnlyAndASignedRecordIsRefused...` (switch is 409; there is no other way to change it) | not run |
| 46-3.8 | Switch department for a draft: new snapshot, no data deleted, one audit entry | `EmrRecordsIT.theDoctorSwitchesTheDepartmentOfADraftRecord...`, `switchingToTheSameDepartmentChangesNothing...` | not run |
| 46-3.9 | The switch is not saved as the doctor's default | `theDoctorSwitchesTheDepartmentOfADraftRecord...` (`/emr/me` unchanged, next visit uses the doctor's department) | not run |
| 46-3.10 | Nurse, front desk, billing, pharmacist cannot read a snapshot (403) | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` (get, draft, switch, advice) | not run |
| 46-3.11 | No log line holds the doctor's name, the patient's name or clinical text | `EmrLogPrivacyIT` | not run |
| 46-4.1, 46-4.2 | Admitted patient and Emergency case open the same frame with the encounter set | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...`, `aDoctorOpensTheEmergencyAssessmentOfACase...` | not run |
| 46-4.3 | Ward scope and break-glass of IPD: a doctor who may not see a ward cannot open (403); break-glass audited by the IPD path | `EmrIpdRecordsIT.withWardScopeOnADoctorStillOpensAnyAdmission...` (see finding F-1: the outside-scope answer cannot be reached through the API) | not run |
| 46-4.4 | Frame opens and the snapshot is stored; saving a draft per encounter is switched off (409) | `EmrIpdRecordsIT.savingADraftIsSwitchedOffForAnAdmission...`, `aDoctorOpensTheEmergencyAssessment...` | not run |

## Coverage: SAH-47 (frame, parts, sections, Done, Save draft), backend half

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 47-1.1 | Header: initials, name, age, sex, token, UHID, doctor, room, visit line | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (header), `theHeaderShowsARecordedAllergy...`; **Screen** for the strip | not run |
| 47-1.2, 47-1.3 | Allergy and known tags; never "No known allergy" unless recorded; last visit; visit count | `EmrRecordsIT.theHeaderShowsARecordedAllergyAndKnownConditions...` ("None known" gives no tag) | not run |
| 47-1.4 | Ticking an allergy updates the tag at once | **Screen** | - |
| 47-1.5 | "ABHA linked" not shown until DEC-040 | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (not returned) | not run |
| 47-1.6 to 47-1.8 | Slim bar, tags Admission advised / Emergency, no name in logs | **Screen**; `adviseAdmissionMarksTheRecord...`; `EmrLogPrivacyIT` | not run |
| 47-1.9 | Clinic B never gets clinic A's patient (404) | `EmrRecordsIT.anotherClinicsVisitAndRecordAreNotFound...` | not run |
| 47-2.x | Part tabs with "n of m required", tick, 0 of 0, Continue buttons, left list | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (parts 1 of 1 and 3 of 3 required, 9 and 7 sections), `whenEveryRequiredSectionIsDone...`, `aHiddenSectionNeverAppearsInStillNeeded...`; **Screen** for tabs and buttons | not run |
| 47-3.1 | Only Show-on sections appear, in the snapshot order | `openingAnOpdVisitCreatesTheRecord...` (16 of 19) | not run |
| 47-3.2, 47-3.3 | Collapsed by default; generic renderer by field type | **Screen**; `EmrLibraryIT.everyFieldHasALabel...` gives the `valueKind` the renderer uses | not run |
| 47-3.6 | Copy from last visit: pre-filled, "Copied from", Done only after confirm; last signed record of the same department only | `EmrRecordsIT.copyFromLastVisitPrefillsFromThePatientsLastSignedRecord...` | not run |
| 47-3.7 | Who can fill stored and shown; the doctor edits all | `saveDraftStoresTheValues...`; `EmrDepartmentsIT.saveDraftStoresTheChange...` | not run |
| 47-3.8 | Vitals start empty, nurse vitals not pre-filled | `openingAnOpdVisitCreatesTheRecord...` (every section starts with no values) | not run |
| 47-3.10 | Vitals limits coloured and labelled | **Screen** (client rule) | - |
| 47-3.11 | Photo, file, signature: disabled with "Later", nothing stored | `EmrRecordsIT.eachValueMustMatchItsField...` (a value for `attachments.files` is 400) | not run |
| 47-3.12 | A typed value is stored with the visit and never logged | `saveDraftStoresTheValues...`, `theRecordRowHoldsReferences...`, `EmrLogPrivacyIT` | not run |
| 47-3.13 | An invalid value (range, length) shows an error and is not saved | `EmrRecordsIT.eachValueMustMatchItsFieldAndABadOneIs400...`, `aSectionThatIsNotInTheRecordsLayout...`, `aBodyOverSixtyFourKilobytesIs413...` (the 32 KB per-section limit is a unit test of the java-developer; one section cannot reach 32 KB within the field limits in the seed) | not run |
| 47-4.1 | Done: every required field filled; no required field: one field filled; Optional never blocks | `EmrRecordsIT.aSectionIsDoneWhenEveryRequiredFieldIsFilled...` | not run |
| 47-4.2 | Moving between parts is allowed while required sections are missing | `whenEveryRequiredSectionIsDone...` (Save draft moves the position, never refuses) | not run |
| 47-4.3, 47-4.4 | Footer "Still needed" in red, "Ready to complete" in green | `stillNeeded` and `readyToComplete`: `openingAnOpdVisitCreatesTheRecord...`, `whenEveryRequiredSectionIsDone...`; **Screen** for colour and wording | not run |
| 47-4.5, 47-4.6 | Complete blocked, first missing section opens; Complete does not sign in phase 2 | **Screen**; `whenEveryRequiredSectionIsDone...` (status stays DRAFT) | not run |
| 47-4.7, 47-4.8 | Hidden section never in "Still needed"; follows the snapshot | `aHiddenSectionNeverAppearsInStillNeededAndADepartmentWithNothingRequired...`, `aRecordKeepsTheLayoutItOpenedWith...` | not run |
| 47-5.1 | Save draft stores everything without checking required sections | `EmrRecordsIT.saveDraftStoresTheValuesThePositionAndANewRevision...` | not run |
| 47-5.2 | Reopen shows the same part, values, position | same test (`uiState`, values) | not run |
| 47-5.3, 47-5.4 | Save before leaving, busy and error state | **Screen** | - |
| 47-5.5 | A draft of another doctor's visit cannot be written (403) | `aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 47-5.6 | A draft of clinic A: clinic B gets 404 | `anotherClinicsVisitAndRecordAreNotFound...` | not run |
| 47-5.7 | One audit entry: who, when, visit id, no clinical text | `saveDraftStoresTheValues...` (section keys only, no value) | not run |
| 47-5 (409) | Stale revision 409; two saves, one wins | `EmrRecordsIT.aStaleRevisionIs409EmrRecordChanged...` | not run |
| 47-6.x | Right panel tabs | **Screen** | - |

## Coverage: SAH-52 (OPD, IPD, Emergency differences), backend half

| # | Acceptance criterion | Test | Result |
| --- | --- | --- | --- |
| 52-1.1 | Titles Consultation / Daily progress note / Emergency assessment | **Screen** (the server sends `encounterType`); `EmrRecordsIT`, `EmrIpdRecordsIT` | not run |
| 52-1.2 | IPD and Emergency header facts read from ipd, not typed | `EmrIpdRecordsIT.aDoctorOpensTheDailyProgressNote...`, `aDoctorOpensTheEmergencyAssessment...` | not run |
| 52-1.3 | The same patient's OPD visit and admission never mix | `EmrRecordsIT` (OPD record has no admission), `EmrIpdRecordsIT` (admission record has no visit) | not run |
| 52-1.4 | Discharged admission or closed case opens read-only | `EmrIpdRecordsIT.aDischargedAdmissionOpensReadOnly...`, `aClosedCaseOpensReadOnly...` | not run |
| 52-1.5 | No log line holds the patient's name or clinical text | `EmrIpdRecordsIT.noLogLineOrAuditEntryOfAnIpdOrEmergencyOpenHolds...`, `EmrLogPrivacyIT` | not run |
| 52-2.1 to 52-2.3 | Doctor and owner change the type; front desk only at registration; card, record and audit show the new type | `EmrVisitTypeIT` (all six tests) | not run |
| 52-2.4 | Emergency option disabled with "Coming in a later step"; server answers 400 until SAH-67 | `EmrVisitTypeIT.emergencyIsNotAvailableYetAndAnyOtherValueOrBodyIs400` | not run |
| 52-2.5 | IPD shows "IPD - day n", Emergency the triage level, read-only | **Screen**; data in the header: `EmrIpdRecordsIT` | not run |
| 52-2.7 | The meaning of Revisit is OQ-43-26 | the value is stored and shown only: `EmrVisitTypeIT.theDoctorChangesTheTypeOfTheirOwnVisit...` | not run |
| 52-3.x | Past visits content | not built (SAH-50); nothing to test | - |
| 52-4.1 | Admit to IPD button for OPD and Emergency, hidden for IPD | `EmrIpdRecordsIT.savingADraftIsSwitchedOff...` (advice on an admission is 400 `not-applicable`), `aDoctorOpensTheEmergencyAssessment...` (advice on a case works); **Screen** for the button | not run |
| 52-4.2, 52-4.3 | IPD on: admission sheet; IPD off: sheet with "Advise admission + referral"; "Switch on IPD" for the owner only | `EmrRecordsIT.openingAnOpdVisitCreatesTheRecord...` (`ipd.state`, `canSwitchOn` false for a doctor), `onlyADoctorAVisitingDoctorOrAnOwnerWithDoctorDetailsOpens...` (true for the owner); **Screen** | not run |
| 52-4.5 | One audit entry, no clinical text | `EmrRecordsIT.adviseAdmissionMarksTheRecordStoresTheReferralLineOnlyOnce...` (`withReferral` only, never the text) | not run |
| 52-4.6 | Nurse, front desk, billing, pharmacist: 403 | `EmrRecordsIT.aDoctorOrVisitingDoctorOpensOnlyTheirOwnPatients...` | not run |
| 52-4 (limits) | Referral line 1 to 300 characters; stale revision 409; signed 409 | `EmrRecordsIT.aReferralLineIsOneToThreeHundredCharacters...`, `draftsAreForOpdVisitsOnly...` | not run |

## Coverage: the SAH-43 changes

| # | Change | Test | Result |
| --- | --- | --- | --- |
| 43-a | Master hint is one of the 15 starter keys (gp and homeo refused, 3 former homeopathy complaints have no hint) | `EmrGlobalTablesIT.theNameHintAndOrderChecksOfTheMasters...`, `theThreeFormerHomeopathyComplaintsNowHaveNoHint...`; `EmrMastersIT.aSpecialtyPutsItsTaggedItemsFirst...`, `aSpecialtyOutsideTheFifteenStarterKeysIsAValidationProblem`, `diagnosesPagesWalkAllFiftyThree...` (starter key maps to the SAH-7 diagnosis tag) | not run |
| 43-b | `emr_advice.name_mr` and `name_hi` (null in the seed, 1 to 200) and in the API | `EmrGlobalTablesIT.theAdviceMastersMarathiAndHindiNames...`; `EmrMastersIT.theAdviceMasterCarriesNullMarathiAndHindiNames...` | not run |
| 43-c | `/emr/me` has `department` (and `departments`) and no `template` | `EmrDoctorContextIT.aDoctorGetsTheirOwnDetails...` and the choice tests | not run |
| 43-d | Writes to master, quick-option, library and starter paths are 403; the retired template and settings paths are 404 | `EmrMastersIT.everyWriteToAMasterOrTemplatePathIsRefused...`, `theRetiredTemplateAndSettingsPaths...` | not run |
| 43-e | Ranking per doctor, quick options, recorder | `EmrQuickOptionsIT` (21 tests, unchanged) | not run |

## Database cases S-50 to S-71 (docs/database/SAH-53.md)

| Case | Test | Result |
| --- | --- | --- |
| S-50 counts | `EmrGlobalTablesIT.theSeedHasTheCountsOfTheDesignNote`, `theFifteenStartersAreTheMockupDepartments...` | not run |
| S-51 library against the mockup, FALLBACK fields, 30 types, `FREE_TEXT` refused | `EmrGlobalTablesIT.theStartingValuesAreSeededAsDrawn...`, `theSectionsOffAtStart...`, `everyFieldHasALabel...` (lists of the story compared; the full row-by-row mockup comparison is **not** repeated in Java, see gaps) | not run |
| S-52 grants, no tenant column | `theAppCanSelectEveryGlobalTableWithNoClinicBound`, `theAppCannotInsertUpdateDeleteOrTruncateAnyGlobalTable`, `noGlobalTableHasATenantColumnOrRowLevelSecurity` | not run |
| S-53 None option | `everyRequiredMultiSelectOrChecklistFieldWithOptionsHasOneNoneOption...` | not run |
| S-54 option sources, allergy always shown, one placement lock, no hidden and required | `theOptionsSourceRulesHoldInTheStoredData`, `theSectionsOffAtStart...` | not run |
| S-55 starter layout immutable, version 2 works, deferred key, documents equal the tables | `aStarterLayoutCannotBeUpdatedDeletedOrTruncatedByAnyRole...`, `theFifteenLayoutDocumentsMatchWhatAQueryOverTheTablesProduces` | not run |
| S-56 alias | `onlyTheNormalisedFormOfAnAliasCanBeStored...` | not run |
| S-57 masters hints and advice languages | see 43-a, 43-b | not run |
| S-60 RLS everywhere, no clinic bound, two clinics, foreign `tenant_id` | `EmrClinicTablesIT.everyClinicTableHasEnabledAndForced...`, `everyMonthlyPartitionOfTheRecordTable...`, `withNoClinicBound...`, `oneClinicCannotReadChangeDeleteOrInsert...`; `RowLevelSecurityCatalogIT.theEmrRecordTableAndEveryOneOfItsPartitions...`; `VitalsCatalogIT.theFiveEmrClinicTablesHave...` | not run |
| S-61 department checks and grants | `aDepartmentIsUniquePerClinic...`, `aDepartmentRefusesBadInitials...`, `theAppCanChangeOnlyTheDraft...`, `twoFirstInsertsOfTheSameDepartment...` | not run |
| S-62 version | `aPublishedVersionIsInsertOnly...`; triggers by name in `VitalsCatalogIT.theEmrLayoutImmutabilityTriggersAreThereByNameAndEnabled` | not run |
| S-63 doctor | `aDepartmentDoctorIsInsertOnly...` | not run |
| S-64 record encounter ids and layout reference | `eachEncounterTypeAcceptsOnlyItsOwnIds...` | not run |
| S-65 record keys across clinics | `aRecordCannotPointAtAnotherClinicsPatientVisit...` | not run |
| S-66 record grants | `theAppChangesOnlyTheEditableColumnsOfARecord...` | not run |
| S-67 partitions | `aRecordLandsInThePartitionOfItsMonth...`, `thePartitionHelperIsIdempotent...` | not run |
| S-68 concurrency | `EmrRecordsIT.sixOpensAtTheSameMoment...`, `aStaleRevisionIs409...`; `EmrIpdRecordsIT.fiveOpens...` (two); `EmrDepartmentsIT.twoPublishes...`, `twoSaves...`; `EmrAddDepartmentIT.twoRequestsWithTheSameName...` | not run |
| S-69 query plans (find by visit, by admission and round, by case, patient signed read, patient created read) | **not written** (see gaps) | - |
| S-70 `opd.visit` REVISIT accepted, WALKIN refused, appointment still refuses REVISIT | `EmrVisitTypeIT.theDatabaseAcceptsRevisitOnAVisit...` | not run |
| S-71 backward compatibility | the existing suites (`QueueIT`, `WalkInTokenJourneyIT`, `AppointmentDoubleBookingIT`, the IPD classes, `ClinicMastersIT`, `TenantIsolationIT`, `PooledConnectionIT`) are run by `verify`; no new test | not run |

): the old `like 'emr\_record%'` also matched `opd.emr_record_addendum` (SAH-51, two policies), which the old check would have reported as weak and counted as a 27th table.
- `EmrClinicTablesIT.everyMonthlyPartitionOfTheRecordTableAndTheDefaultOneHave...`: no fixed 25; checks the 24 of the migration are there and every partition is the default or a month, then the RLS and privilege rules for every one found.
- `EmrClinicTablesIT` S-67 (`aRecordLandsInThePartitionOfItsMonth...`, `thePartitionHelperIsIdempotent...`): the "far month" was the fixed 2031-06 (default partition) and 2030-01 (a new month). Once the callback runs in 2029 both would be wrong. They now use today + 70 months (past the 60 the function can reach).

### Finding for the java-developer (found while writing, not fixed: main code)

- **F-DEC49-1 (high, confirmed by a database-free run).** `EmrPartitionUpkeepMonitor` has two constructors (public `(JdbcClient)` and package-private `(Supplier<Integer>)`) and no `@Autowired`. Spring takes a constructor by itself only when the class has exactly one; with two unannotated constructors it asks for a no-argument one and fails with "No default constructor found". `EmrPartitionUpkeepMonitorWiringTest` reproduces it with a bare Spring context and a mock `JdbcClient`. Every full context therefore fails to start: the application (dev, test, prod) and every `@OnTestDatabase` test. It did not show in the unit run because the three `SpringBootTest`s that boot a context set `sahaj.opd.partition-check.enabled=false`. Fix: `@Autowired` on the public constructor.

## Tenant isolation, roles, negative and boundary cases (summary)

- Tenant: every tenant table (usage, department, version, doctor, record and its partitions) is read, updated, deleted and inserted into as a second clinic and with no clinic bound (`EmrClinicTablesIT`); through the API for departments, versions, layouts, reset targets, add and copy (`EmrDepartmentsIT`, `EmrAddDepartmentIT`), and for records, open, draft, switch, advice (`EmrRecordsIT`, `EmrIpdRecordsIT`).
- Roles: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist, counsellor, not signed in, a token whose claim differs from the membership, an owner without doctor details, a removed doctor.
- Boundary values: names 60 and 61 characters, rooms 10 and 11, 40 and 41 characters, initials, colour, 50 added departments, referral 300 and 301, text 2,000 and 2,001, rich text 10,000 and 10,001, numbers at ±1,000,000, version 0 and 100,001, body 64 KB.
- Negative: unknown fields in every closed body, wrong types, empty and broken bodies, stale revisions, duplicate and missing sections, unknown keys.

## Findings while writing (for the owners; nothing was changed outside `it/` and `docs/test-plans/`)

- **F-1 (java-developer / solution-architect).** `EmrEncounterSourceAdapter` allows only OWNER and DOCTOR, and the IPD scope of both is "all patients". The `OUTSIDE_SCOPE` answer and the `outside-scope` problem of `EmrProblemException` therefore cannot be produced through the API, and a break-glass grant can never be used by the EMR (a nurse is refused earlier by `clinic.consultation.write`, a visiting doctor by the adapter). `EmrIpdRecordsIT` proves what does happen: nurse, visiting doctor and the other roles get 403 `forbidden`, a doctor opens any ward's patient, and a nurse's grant is neither used nor changed. If the human wants visiting doctors in the EMR (IPD does the same, story 34D), the contract and the adapter must change; until then the path is dead code.
- **F-2 (java-developer).** The contract says `source` "neither for BLANK (400 validation otherwise)". `EmrDepartmentService.sourceLayout` ignores a starter key sent with kind BLANK. `EmrAddDepartmentIT.theSourceMustBeCompleteAndOfTheRightKind` asserts 400 and will fail if the code ignores it; the java-developer or architect decides which of the two is right.
- **F-3 (java-developer).** `EmrDepartmentService.create` retries after `DuplicateKeyException` and then calls `nameTaken`. In PostgreSQL a unique violation aborts the transaction, so the second statement may fail with "current transaction is aborted" (500) instead of 400 `department-name-taken`. `EmrAddDepartmentIT.twoRequestsWithTheSameNameAtTheSameMoment...` will show it if so (a savepoint or `on conflict` is the usual fix).
- **F-4 (java-developer).** `RecordSectionInput` and the department inputs are closed by `EmrStrictJsonModule`; a boolean sent as the number 1 is not tested (Jackson may coerce it). Not asserted either way.

## Gaps (stated, not hidden)

- **S-69 query plans** are not written. They need rows in the partitions or `enable_seqscan = off`; to be added in a follow-up once the database is recreated and the first run is green.
- **Row-by-row comparison of the 100 sections and 313 fields with the mockup file** is not repeated in Java: `EmrGlobalTablesIT` checks counts, kinds, the story lists, off-at-start lists, order, locks, option rules and that the stored layout documents equal the tables. The db-engineer's parse-back script (docs/database/SAH-53.md "Static checks") is the reference for the text itself; the human clinical review (OQ-43-8) is still advised.
- **IPD read-only state** (`ipd-read-only`, READ_ONLY entitlement) and **a second IPD day by clock** are not driven through the API (no way to put the clinic in READ_ONLY or move the clock); the second day is covered with an inserted earlier note.
- **A real migration that changes the library** (53-1.10) is covered by the copy stored in each version, not by running a migration.
- **Playwright** (`frontend/e2e/`): none yet, no screen exists. Planned when the pages arrive: Specialty template page (open, switch Show and Required, Save draft, Publish, Reset and Undo, 409 reload) and Add department (starter, preview, create); EMR frame (open from the Queue card, fill the required sections, "Still needed", Save draft, reload). One error path each: stale revision on Publish; open as a nurse.
- The Angular unit tests and the angular-developer's `frontend` changes are outside this plan.

## What runs once the database is recreated

1. The human drops and recreates `sahaj_test` (`node scripts/setup/create-database.mjs`); a clean build so no old migration file stays in `backend/target/classes/db/migration`.
2. `mvn -f backend/pom.xml verify` (or `node scripts/validate.mjs backend`) runs everything above on `sahaj_test`: the 10 new or rewritten classes, the 3 edited classes, `EmrQuickOptionsIT`, and every older class as the regression for S-71.
3. Then `node scripts/validate.mjs all`. Failures are reported in the coverage tables above, not deleted or skipped; a failure that points at the code goes back to its owner (java-developer for the application, db-engineer for the migrations).

## Handoff

Story: SAH-53 (with SAH-45, SAH-54, SAH-46, SAH-47, SAH-52 backend and the SAH-43 changes). Next agents: code-reviewer and security-reviewer, after the first green run.
