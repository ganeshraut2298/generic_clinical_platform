# Test plan · SAH-17 Privacy and consent

Mockup screen: Clinic setup → Privacy, ABDM and backup. Tests: `backend/src/test/java/com/sahaj/platform/it/PrivacyConsentIT.java` (real `sahaj_test` database, MockMvc, real sign-up and setup wizard). Unit tests (mock-based) are owned by the developers. Frontend page and dialog: covered by the Angular unit tests; Playwright e2e for the page is not part of this pass.

| AC | Case | Test (PrivacyConsentIT) |
| --- | --- | --- |
| 2 | Defaults on a fresh tenant, GET inserts no row | freshTenantGetsDefaultsAndGetInsertsNothing |
| 2 | Owner saves; second PUT updates the one row, version rises | ownerSavesAndSecondPutUpdatesTheSingleRow |
| 5 | HFR ID trimmed, blank to null, 32 ok, 33 gives 400 | hfrIdIsTrimmedBlankBecomesNullAndThirtyThreeCharsIsRefused |
| 2 | Missing switch gives 400, nothing saved | missingSwitchIsRefusedAndNothingIsSaved |
| 1, 5 | Non-owner PUT 403 (7 roles); all 8 roles GET 200 | everyNonOwnerRoleIsRefusedOnPutAndEveryClinicRoleCanGet |
| 9 | Audit PRIVACY_SETTINGS_CHANGED with before/after, no patient data | savingIsAuditedWithBeforeAndAfterAndNoPatientData |
| 10 | Tenant B has own defaults, cannot see A's row (API and SQL under RLS) | anotherTenantHasItsOwnDefaultsAndCannotSeeTheRow |
| 3 | Create with consents, both visible with recordedByName | createWithConsentsRecordsBothWithStaffName |
| 3 | No consent recorded gives empty state, no rows | patientWithoutConsentsHasNoConsentState |
| 4 | Withdrawal adds a new row; latest per kind wins; old row kept | withdrawalIsANewRowAndLatestPerKindWins |
| 3 | Duplicate kind gives 400, patient not created | duplicateKindInOneCreateIsRefusedAndPatientIsNotCreated |
| 3, 4 | Unknown kind / missing fields give 400 | unknownKindAndMissingGivenAreRefused |
| 4 | POST allowed OWNER, FRONT_DESK, NURSE; 403 for others | onlyOwnerFrontDeskAndNurseMayRecordConsent |
| 10 | Unknown / other-tenant patient gives 404 | unknownAndOtherTenantPatientIsNotFound |
| 4 | sahaj_app cannot UPDATE, DELETE, TRUNCATE consent | consentRowsCannotBeUpdatedDeletedOrTruncatedByTheApp |
| 9 | Consent writes create no audit_event rows | consentWritesDoNotCreateAuditEvents |
| 7 | One access row per patient detail GET | openingAPatientFileWritesExactlyOneRowPerCall |
| 7 | List, search, family, consents write none | listSearchFamilyAndConsentEndpointsWriteNoAccessRows |
| 7 | 404 patient writes no row | notFoundPatientWritesNoAccessRow |
| 8 | Newest first, names resolved, default 5, limits 1 and 50 ok, 0, 51, abc give 400 | ownerListsNewestFirstWithNamesAndDefaultLimitFive |
| 8 | Non-owner roles get 403 | nonOwnerRolesCannotReadTheAccessList |
| 8 | Row has only ids and timestamp | accessRowStoresOnlyIdsAndATimestamp |
| 7 | sahaj_app cannot UPDATE, DELETE, TRUNCATE access log | accessRowsCannotBeUpdatedDeletedOrTruncatedByTheApp |
| 10 | Tenant B sees none of A's rows | anotherTenantSeesNoAccessRows |

Not covered here: AC6 (static facts, frontend only); AC5 disabled ABDM placeholders (frontend only).
