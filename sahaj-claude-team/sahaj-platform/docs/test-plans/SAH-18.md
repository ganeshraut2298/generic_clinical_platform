# Test plan SAH-18 · Alerts and escalation rules

Screen: Clinic setup → Alerts and escalation (`cfgRules`). Module: opd.
Integration tests: `backend/src/test/java/com/sahaj/platform/it/AlertRulesIT.java` (real `sahaj_test`, MockMvc, nothing mocked).
Scope: storage only; the rule engine is a later release. No e2e added in this pass (UI unit tests belong to the angular-developer).

| AC | Test (AlertRulesIT) | Type |
|---|---|---|
| AC1 read for all roles | allEightClinicRolesCanRead | role |
| AC1 non-owner read-only (server side) | everyNonOwnerRoleIsRefusedOnEveryWriteAndNothingIsChanged, nonOwnerWritesOnAFreshTenantSaveNothing | role, negative |
| AC2 starter rules, order, 4 on / 1 off, id null, GET writes nothing | freshTenantSeesStarterRulesSuggestionsAndOptionsAndGetWritesNothing | happy |
| AC2 first change saves starter plus change | firstAddMaterialisesStarterPlusNewRuleInOrder | happy |
| AC2 seeded once, idempotent, no resurrection | starterEndpointSeedsOnceAndIsIdempotent, afterDeletingEveryRuleStarterRulesDoNotComeBack | boundary |
| AC3 builder validation, fieldErrors | unknownOrMissingFieldsAreRefusedWithFieldErrorsAndNothingIsWritten | negative |
| AC3 duplicate 409 "That rule already exists." | duplicateRuleGets409AndNoSecondRow | negative |
| AC4 toggle persists, missing enabled 400 | toggleOffOnPersistsAndMissingEnabledIs400 | happy, negative |
| AC4 delete, 404 unknown, malformed uuid 400 | deleteRemovesTheRuleAndASecondDeleteIs404, unknownIdIs404OnPutAndDelete, malformedUuidIs400 | happy, negative |
| AC5 suggestion add, hidden, 409, 404 | suggestionAddWorksHidesItAndRefusesAgain, unknownSuggestionIndexIs404 | happy, negative |
| AC5 hidden by same if+is; all five; texts outside builder lists | suggestionHiddenWhenSameWhenAndCondAlreadyAddedThroughBuilder, suggestionsOutsideTheBuilderListsAreAllowedAndAddingAllHidesThePanelContent | boundary |
| AC6 audit ADDED/TOGGLED/DELETED before/after, rule texts only | addToggleAndDeleteAreAuditedWithBeforeAndAfterAndOnlyRuleTexts, refusedWritesAreNotAudited | audit |
| AC7 owner-only writes, 401 unauthenticated | everyNonOwner..., withoutLoginIs401 | role |
| AC7 tenant isolation / RLS | tenantBSeesOnlyItsOwnRulesAndState, anotherTenantsRuleIdIs404OnPutAndDeleteAndStaysUntouched, appRoleCannotInsertARuleForAnotherTenant | isolation |
| Marker append-only for sahaj_app | appRoleCannotUpdateOrDeleteTheSeededMarker | security |
| Concurrent first write | twoSimultaneousFirstWritesSeedTheStarterRulesExactlyOnce, twoSimultaneousStarterCallsSeedOnce | concurrency |
