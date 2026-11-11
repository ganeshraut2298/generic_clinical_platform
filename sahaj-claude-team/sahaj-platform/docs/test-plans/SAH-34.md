# Test plan SAH-34 · IPD ward scoping and break-glass emergency access (stories 34B, 34C, 34E, 34F; the data part of 34A)

Story: `docs/stories/SAH-34.md` (77 acceptance criteria). Decisions: DEC-031 and its addendum of 10 Nov 2026, ADR-016 (options), ADR-017 (decided build). Design: `docs/architecture/SAH-34-ward-scoping-and-break-glass.md` (section 9 is the test plan this file answers), `docs/database/SAH-34.md`, `docs/api/ipd.yaml` (the SAH-34 parts). Mockup parts: `pctog`, `pcscope`, `sfbg`, `sfbgok`, `sfFlags`, `ipSafety`, `bgRow`, `sfbgrev`, the `bg` entries of `kic`.
Run date: 10 Nov 2026. Branch `feature/SAH-39-home-kinetic-background` (mixed tree). Database `sahaj_test` (PostgreSQL 16, profile `test`, the real `sahaj_app` role with row level security).

Not built, so not tested: the 34A owner screen (not in the mockup), 34D (visiting doctor keeps 403 on every IPD endpoint, which IS tested), 34G. The 34A **data part** (three owner endpoints) is tested the way the screen will call it.

## 1. Test classes

All integration tests are in `backend/src/test/java/com/sahaj/platform/it/`. They use real users (own user id, own membership, token role and membership role agree), the real owner endpoints to assign wards and switch the scope, and the real `sahaj_test` database. The texts and URLs of the assertions are copied from the contract and the stories, not read from the application.

| Class | Tests | What |
| --- | --- | --- |
| `ScopeSupport` | - | shared set-up: a hospital with two wards, a nurse per ward, a patient per ward, the switch on; the aged-grant insert (table owner, stamping trigger off for one insert, the technique of `docs/database/SAH-34.md` section 7); the "nothing was written" snapshot of 17 tables and the version of every admission and escalation |
| `WardAssignmentIT` | 13 | 34A data: GET, PUT of the whole set, ended nurses stay as history, same set writes nothing, audit with ids only, non-nurse / unknown / duplicate / 101 ids -> 400 `Choose nurses from the list.`, 404 and 400 path ids, owner only (all other roles, a nurse, an OWNER token with a non-owner membership), switch refused while an active nurse has no ward **on every request** (also a repeated `on: true`), `on: false` never refused, `malformed-request` for an unknown field on all three new bodies without echoing the name, another clinic invisible, Read-only 409 |
| `WardScopeEnforcementIT` | 15 | 34B: list, `scope` object for every role, `mine`, OWNER/DOCTOR never narrowed even with an escalation in a ward nobody is assigned to, a nurse with no ward sees nothing and every by-id route is 403 `outside-scope`, every guarded read and write of another ward's patient is 403 with the fixed body and writes nothing (raw JSON holds no name, UHID, mobile, bed, ward word; only `type,title,status,detail`), role answer comes first, own ward still works, unknown / other clinic id is 404, transfer out then next request refused, switch off = as before for NURSE, switching applies at the next request of the same token, inactive membership 403, Safety board / summary / change token / banner / events narrowed, `otherWardsPastTarget` a number only, bed board masked, Medicine round scoped |
| `EmergencyAccessIT` | 20 (4 added 10 Nov after the review: picker audit row, count 0, refused reads write nothing, read-only and IPD Off order, audit failure gives 500) | 34E: picker (identity only, three reasons, writes nothing, ordered by bed code, empty when she sees everyone), every non-nurse role 403 with the fixed text, token/membership mismatch, open writes one grant + one `bg` entry with `grant_id` + one record-access row + one audit row, 30 minutes from the database clock, after opening only her role's endpoints for that one patient, validation texts and field order, 409 already-in-scope, 404 (unknown, other clinic, discharged), **two taps = one grant**, rollback of all four writes on a failure, Read-only allowed, **real expiry** (4-second grant, then 403 and the list drops, a new grant is a new row), expired grant, ends with the stay, review row keeps the bed of the grant |
| `EmergencyAccessReviewIT` | 11 | 34F: tile + count + first page only for the owner, other roles never get them, owner only, keyset paging by 25 over 61 rows with ties (no gap, no repeat), bad limit/cursor 400, review once (fields, `bg` entry, audit ids only, `grantStaffName`), second review 409 `already-reviewed`, grantee herself 403 (also as an owner now), unknown / other clinic 404, bad id 400, **two owners at once = one 200 and one 409** (3 rounds), expired / discharged / inactive grantee stay, Read-only allowed, panel holds no diagnosis/note/bill |
| `EmergencyAccessTableIT` | 9 | the database rules: the app cannot write a time, the trigger stamps 30 minutes, `ck_..._expiry` holds for the owner too, update of `expires_at` and every fixed column refused (app 42501, owner 23514), second review, self review, review that names nobody, delete / truncate refused for every role, RLS and composite keys keep other clinics out, `grant_id` only on `bg` and only of the same clinic, `ward_assignment` end-once / no delete / one live row per nurse and ward |
| `ScopeRaceIT` | 6 | ADR-017 D2 / design 1.6 on the real database: a transfer that wins the admission row refuses the queued vitals save (nothing written), a vitals save that wins is saved and the transfer still succeeds, a colleague's save behind a nurse's own transfer, **a grant that ends while a dose waits for the row** is refused when the row is free (nothing written), the same dose with a live grant is recorded, an assignment removed while a save waits |
| `ScopeEdgeCasesIT` | 11 | IPD Off (`module-off` on the new endpoints), a clinic never switched on, two nurses one ward / one nurse two wards / ended assignment / inactive member, a grant stops when the membership ends and works again, a grant never opens a second patient, the visiting doctor keeps 403, record-access rows of a scoped read = the owner's and a refusal writes none, **scope cannot be built -> 500 + WARN line (tenant id, request id), no data, no fallback to all** (the select on `ward_assignment` is revoked for the test and granted back), what the application log holds, no query parameter can widen the scope, role change |
| `SafetyChangeTokenIT` | 9 (changed) | OWNER counts have 7 keys including `emergencyAccessWaiting`; DOCTOR and NURSE 6, no such key |
| `ScopePlansIT` | 1 (rewritten 10 Nov) | the real query plans (section 4 and 6): the repositories' own statements read by reflection (`RealSql`), real JDBC parameters, work-count budgets |
| `ScopeReviewFixIT` | 7 (new 10 Nov) | AC 79 owner token `bg:` line (4 tests), AC 81 identical rows for a nurse with no ward and no grant, AC 14 guard before the state check, AC 80 discharged reads |
| `RealSql`, `UniqueSlugs` | - | test helpers (statements by reflection; unique clinic web addresses, SAH-34 follow-up item 5) |
| `IpdScopeCoverageTest` (java-developer, unit) | - | no endpoint without `@ScopeGuarded` / `@ScopeExempt`, contract cross-check, one named refusal test per by-id endpoint (AC 22). Not rewritten here; its result is part of `validate backend` |

## 2. Coverage: acceptance criterion -> test -> result

"IT" = the integration test class above; E = `frontend/e2e/ward-scope.spec.ts` (API mocked, see section 3). Results are those of the runs recorded in section 5.

### 34B Scope enforcement (server)
| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 1 | scope per role; NURSE all while off, wards when on; VISITING_DOCTOR 403; no request field widens it | WSE `theScopeObjectIsPresentForEveryRoleAndMineFollowsTheRoleRules`, WSE `aScopedNurseSees...`, SE `theVisitingDoctorKeepsA403...`, SE `noQueryParameterOrHeader...` | pass |
| 2 | switch off = identical answers (also NURSE) | WSE `withTheSwitchOffTheNurseSeesEveryone...`, SE `aClinicThatWasNeverSwitchedOn...`; the whole existing IPD suite is run as it was (`validate backend`) | pass |
| 3 | nurse sees a patient iff the bed is in her wards; two nurses one ward; one nurse two wards; inactive membership counts for nothing | WSE `aScopedNurseSees...`, SE `twoNursesOfOneWardBothSeeIt...` | pass |
| 4 | moved patient: gone at the next request, no grace | WSE `aWriteThatStartsInScope...`, SR `aTransferOutOf...` | pass |
| 5 | no ward -> empty list, 403 `outside-scope` on by-id, Safety board holds no patient, never "all wards" | WSE `aNurseWithNoWardSeesNothing...` | pass |
| 6 | scope cannot be built -> error, nothing returned or written, WARN with ids; no stored setting = off; inactive/missing membership | SE `whenTheScopeCannotBeBuilt...`, WSE `aNurseWhoseMembershipIsNotActive...`, SE `aClinicThatWasNeverSwitchedOn...` | pass |
| 7 | role / assignment / membership / switch change applies at the next request | WSE `switchingOnAndOffAppliesAtTheNextRequest...`, SE `aMemberWhoseRoleChanged...`, SE `aGrantStopsGivingAccess...` | pass |
| 8 | list filtered; `mine`; N and M as numbers; wards filter lists every ward; FRONT_DESK no clinical data | WSE `aScopedNurseSees...`, `theScopeObjectIsPresentForEveryRole...` | pass |
| 9 | bed board: status of every bed stays, name and details absent (not hidden) | WSE `theBedBoardKeepsEveryBed...`; E bed board test | pass |
| 10 | detail reads (`/admissions/{id}`, nursing, emr, mar, discharge) 403 with no patient data; in scope: same answer and record-access rows | WSE `everyGuardedReadAndWrite...`, SE `aScopedReadWritesTheSameRecordAccessRows...` | pass |
| 11 | clinical events: OWNER/DOCTOR all, NURSE own + in scope (incl. grants) | WSE `theSafetyBoardTheSummary...`, EA `afterOpeningThePatientIsInHerList...` | pass |
| 12 | writes refused after the lock, nothing written (vitals, deposit, charge, transfer, dose; order, stop, discharge, sign-off are role 403 for a nurse) | WSE `everyGuardedReadAndWrite...` (17 endpoints, snapshot of 17 tables), SR (race, locked data) | pass |
| 13 | by-id order / escalation routes; other clinic id 404 | WSE `everyGuardedReadAndWrite...` (dose, call senior), `theNursesOwnWardPatientStillWorks...` (404); unit `IpdScopeCoverageTest` for the 17 by-id endpoints | pass |
| 14 | answer order: module-off, forbidden, 404, outside-scope | WSE `everyGuardedReadAndWrite...` (role answer first for order, stop, discharge, sign-off, acknowledge, respond); SE `ipdOff...` | pass |
| 15 | Safety board, summary, tiles, tab number, round agree per caller | WSE `theSafetyBoardTheSummary...`, `theMedicineRoundOfAScopedNurse...` | pass |
| 16 | tile "Your patients" N; text "In your wards" / "All admitted patients" | WSE `theSafetyBoard...` (`yourPatients`), E Safety scope note test | pass |
| 17 | acknowledge / respond / call-senior of another ward: 403 `outside-scope` | WSE (call senior; acknowledge / respond are role 403 for a nurse, the route to act is Emergency access) | pass |
| 18 | OWNER and DOCTOR never narrowed (escalation in an unassigned ward) | WSE `theOwnerAndTheDoctorSeeAllWards...` | pass |
| 19 | Safety answer carries N, M, hasWard; no name outside the scope | WSE `theSafetyBoardTheSummary...`, `theOwnerAndTheDoctor...` (raw JSON) | pass |
| 20 | `otherWardsPastTarget` a number, not in tile / tab / rows; 0; absent for OWNER, DOCTOR, unscoped nurse | WSE `theSafetyBoardTheSummary...`, `theOwnerAndTheDoctor...`, `withTheSwitchOff...` | pass |
| 21 | exempt endpoints unchanged; witness nurse not scoped | existing suites unchanged (`validate backend`); unit coverage test lists the exemptions | pass (gate) |
| 22 | endpoint coverage test | `IpdScopeCoverageTest` (unit, java-developer) | pass (gate) |
| 23 | same record-access rows; list writes none; refusal writes none and no `deny` event | SE `aScopedReadWritesTheSameRecordAccessRows...`, WSE (snapshot includes record_access and clinical_event) | pass |
| 24 | logs: ids only | SE `theApplicationLogOfARefusalAndOfAGrant...`, SE `whenTheScopeCannotBeBuilt...` | pass |
| 25 | another clinic never read | WSE `anAssignmentToAWardIsAboutTheMembership...`, WA `anotherClinicsAssignments...`, EAR `unknownAnotherClinics...`, EAT `anotherClinicsGrants...` | pass |

### 34C Admitted list (browser, mocked API)
| AC | Case | Test (E) | Result |
| --- | --- | --- | --- |
| 26 | chip text, tooltip and accessible name, icon | `a scoped nurse sees the chip ...` | pass |
| 27 | no chip for all-scope roles; none when nobody admitted | `no chip, no notice ...`, `nobody admitted ...` | pass |
| 28 | tooltip ends with "The owner sets your wards in Clinic setup." | `a scoped nurse sees the chip ...` | pass |
| 29 | zero-ward chip and text, polite status, Emergency access available | `a nurse with no ward ...` | pass |
| 30 | wards but nobody: new text; filters keep SAH-36 text | `a nurse with wards but nobody in them ...` | pass |
| 31 | Admitted tab number is N | chip test, `a nurse with no ward ...`, sheet happy path | pass |
| 32 | "My patients" visibility | `"My patients" is shown to OWNER, DOCTOR ...` | pass |
| 33 | filter only: narrows, combines with search, in memory, nothing sent or stored, Table view | `pressing "My patients" narrows ...`, `for a nurse "My patients" ...` | pass |
| 34 | Ward select lists every ward | `the Ward select lists every ward ...` | pass |
| 35 | search runs on the scoped list; no hint; text not sent / stored | `a search for a patient outside the scope ...` | pass |
| 36 | banner, Safety tab number, Table follow the scoped set | `pressing "My patients"` (Table), tab number tests | pass |
| 37 | Safety notes (wards only, no ward), red notice (plural, singular, none), none for OWNER / DOCTOR | `Safety tab: scope notes` (5 tests) | pass |
| 38 | 403 `outside-scope` on a sheet: closes, message (grant variant), row removed, reload, page not locked | `403 outside-scope on a patient sheet` (read, grant, name click) | pass |
| 39 | refused save: "Nothing was saved." | `a save refused after the patient left ...` (Nursing vitals) | pass |
| 40 | 60-second reload follows the server; filters kept | `the 60-second reload follows the server ...` | pass |
| 41 | no chip before the first answer; failed first load | covered by the existing SAH-36 tests and the frontend unit tests (`admitted-panel.scope.spec.ts`); not repeated | frontend unit (pass) |
| 42 | keyboard / screen reader / 44 px | `Layouts and keyboard` (375, 620, 1440; keyboard path; role="note"/aria-label; role="status") | pass |
| 43 | nothing stored; sign-out then another user sees nothing of the previous | storage / URL / network checks in the "My patients" and search tests; sign-out case: frontend unit | pass |

### 34E Break-glass
| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 44 | button only for scoped; server refuses all-scope callers | EA `everyRoleButANurseWhoSeesSomeWards...`, WSE `withTheSwitchOff...`; E no-button test | pass |
| 45 | sheet content; picker has three reasons | EA `thePickerListsTheAdmittedPatients...`; E `the sheet has the title ...` | pass |
| 46 | nothing outside: text and only Cancel | EA `thePickerIsOrderedByBedCode...`; E `nothing outside her wards` | pass |
| 47 | no reason / box not ticked: messages and nothing sent; server 400 | EA `theValidationErrorsAreCollected...`; E `"Open for 30 minutes" with nothing chosen` | pass |
| 48 | roles | EA `everyRoleButANurse...`, `aNurseTokenWhoseMembership...`; SE visiting doctor | pass |
| 49 | admitted patient of the clinic outside scope only; 404 discharged / unknown / other clinic; 409 already in scope | EA `anUnknownAnotherClinics...`, `aPatientAlreadyInHerWards...` | pass |
| 50 | one grant, database clock, 30 minutes, no duration in the body | EA `openingWritesOneGrant...`, EAT `theApplicationInsertStamps...`, WA `anInvalidOn...` (`durationMinutes`, `expiresAt` -> `malformed-request`) | pass |
| 51 | sheet closes, search cleared, flag, toast after the answer | E `one tap, one grant ...` | pass |
| 52 | only her role's actions on that one patient; no other patient / role / module | EA `afterOpeningThePatient...`, SE `aGrantOfOneNurseNeverOpens...` | pass |
| 53 | checked on every request on the database clock; 403 after the end | EA `aGrantEndsAtItsTimeForReal...`, `anAlreadyExpiredGrant...`, SR `aGrantThatEndsWhileTheDoseWaits...` | pass |
| 54 | no extension; second request writes nothing; double tap = one grant | EA `aGrantEndsAtItsTime...` (new row), `aPatientAlreadyInHerWards...` (409), `twoTapsAtTheSameMoment...`; E double tap | pass |
| 55 | discharge / membership inactive / role change stop access; grant stays in review | EA `aGrantEndsWithTheStay...`, SE `aGrantStopsGivingAccess...`, SE `aMemberWhoseRoleChanged...`, EAR `expiredDischargedAndInactive...` | pass |
| 56 | second user's grant is its own row; a grant never shows to another user | EA `afterOpening...` (nurse B), `anAlreadyExpiredGrant...` | pass |
| 57 | picker: identity only; no record-access row; not logged | EA `thePickerListsTheAdmittedPatients...`, SE log test | pass |
| 58 | same transaction: grant, `bg` entry, record-access row, audit; all or nothing | EA `openingWritesOneGrant...`, `aFailureAfterTheGrantRollsBack...` | pass |
| 59 | no edit / shorten / back-date / delete / re-assign | EAT `anUpdateOfTheExpiry...`, `theReviewCanBeMadeOnce...`, `aGrantCanNeverBeDeleted...` | pass |
| 60 | Trial / On / Read-only allowed; Off 403 `module-off` | EA `emergencyAccessIsAllowedWhenIpdIsReadOnly`, SE `ipdOffTheNewEndpoints...` | pass |
| 61 | failed network: sheet stays, choices kept, no claim | E `a 5xx keeps the sheet open ...`, `a network failure ...` | pass |
| 62 | other clinic never listed / granted | EA `anUnknownAnotherClinics...`, EAT `anotherClinicsGrants...` | pass |
| 63 | labels, errors announced and focused, focus returns, 44 px, flag accessible text | E `Layouts and keyboard`, `Cancel and Esc ...`, flag test | pass |

### 34F Owner review
| AC | Case | Test | Result |
| --- | --- | --- | --- |
| 64 | tile OWNER only, amber above 0 | EAR `theOwnersTileCountAndFirstPage...`; E `the fourth tile ...`, `DOCTOR and NURSE never get the tile` | pass |
| 65 | panel below the Medicine round, above the audit trail; not shown at 0 | E `the panel ... has the subtitle ...`, tile test | pass |
| 66 | row sentence, small line, time format, names at display time, bed of the grant | EAR `theOwnersTile...`, EA `theReviewRowKeepsTheBedOfTheGrant...`; E panel test | pass |
| 67 | expired / discharged / inactive stay until reviewed | EAR `expiredDischargedAndInactive...`, EA `aGrantEndsWithTheStay...`; E `expired grants, a discharged patient ...` | pass |
| 68 | Mark reviewed: once, row leaves, N drops, toast, button off while running | EAR `reviewingOnceWrites...`; E `Mark reviewed: one request ...` | pass |
| 69 | OWNER only; never the grantee; second review 409; others 403; unknown 404 | EAR `onlyTheOwnerListsAndReviews...`, `theGranteeHerself...`, `unknownAnotherClinics...`, `reviewingOnce...` (409), `twoOwnersWhoPress...`; E 409 test | pass |
| 70 | `bg` entry "Reviewed emergency access" without names; audit with ids | EAR `reviewingOnceWrites...` (incl. `grantStaffName`) | pass |
| 71 | owner's tab number includes the grants waiting; the three figures agree | SCT, EAR `theOwnersTile...`; E `the Safety tab number of the owner ...` | pass |
| 72 | 0 not amber; more than 25: oldest first, 25, "Show 25 more" | EAR `theListPagesBy25...`; E `more than 25 waiting ...`, `"Show 25 more" failing ...` | pass |
| 73 | Read-only: read and review allowed; Off 403 | EAR `reviewingIsAllowedWhenIpdIsReadOnly...`, SE `ipdOff...` | pass |
| 74 | follows the server; a failed review keeps the row | E `a failed Mark reviewed keeps the row ...` | pass |
| 75 | other clinic never listed or reviewed | EAR `unknownAnotherClinics...` | pass |
| 76 | names never logged or audited; board writes no record-access row; reason one of three | EAR `thePanelAndTheListHoldNo...`, `reviewingOnceWrites...`, SE log test | pass |
| 77 | not built: comment, outcome, history, filter, export | EAR `thePanelAndTheListHold...` (answer keys are exactly `asOf, waiting, items, nextCursor`); the row has exactly 10 fields | pass |

### 34A data part (no story; DEC-031, ADR-017)
| Case | Test | Result |
| --- | --- | --- |
| owner reads wards, active nurses, switch (off by default) | WA `theOwnerReadsEveryWard...` | pass |
| PUT replaces the whole set; ended rows stay; same set writes nothing | WA `putSetsTheWholeSet...` | pass |
| audit holds ids only | WA `theAuditHoldsIdsOnly...` | pass |
| switch refused while a nurse has no ward, on every request | WA `theSwitchIsRefusedWhileAnActiveNurseHasNoWard...` | pass |
| `malformed-request` on the three new bodies, name not echoed | WA `anInvalidOnIsA400...` | pass |
| tenant isolation of assignments and switch | WA `anotherClinicsAssignments...`, EAT `anAssignmentCanOnlyBeEnded...` | pass |

## 3. Browser tests

`frontend/e2e/sah34-world.ts` (the mock world; routes registered after the older worlds, `route.fallback()` for everything else) and `frontend/e2e/ward-scope.spec.ts` (49 tests, 98 runs at 360 and 1440 px by the two projects; the layout tests set 375, 620 and 1440 themselves). The mock adds `scope` on `GET /admissions` and the new endpoints. **What the browser tests do not prove:** who is in whose scope, the 30 minutes, once-only review, audit, record access and RLS (those are the integration tests above). Mutation switches in the world (`SAH34_MUTATE=drop-scope|wrong-n|hide-waiting|refuse-ok|unmask-bed`) make the mock wrong on purpose; a test that stays green under its mutation proves nothing.

## 4. Real query plans (run 10 Nov 2026, `ScopePlansIT`)

Method: `sahaj_app` with `app.tenant_id` set, `sahaj_test`, a hospital of **26 wards, 300 admitted patients** (the bed limit), **36,000 clinical events** (120 per patient over 30 days), **3,655 grants of which 5 unreviewed**, one nurse with two live wards and one live grant, tables analysed. The statements are those the repositories send. Full output with both planner settings: `backend/target/sah34-explain.txt` (rewritten by each run; a copy is kept in the QA report folder of the run). Times are `Execution Time`.

| Id | Statement | Real plan | Time | Verdict |
| --- | --- | --- | --- | --- |
| S1 | scope build, wards | `Index Only Scan using ix_ward_assignment_member_live` (Index Cond tenant, membership), Heap Fetches 2 | 0.10 ms | as expected |
| S2 | scope build, grants (nurse with 5 grants) | `Nested Loop`: `Index Only Scan using ix_emergency_access_grant_member_expires` (Index Cond tenant + membership only; **`expires_at > clock_timestamp()` and `role` are a Filter**, 4 rows removed), then `Index Scan using uq_admission_tenant_id` | 0.22 ms | works; see finding F-1 |
| S2b | same, for a nurse with 3,650 old grants | `Bitmap Index Scan` of **3,650 index entries**, 92 heap blocks, Filter removes all 3,650 | 2.3 ms | **finding F-1** |
| N | narrow admitted-set read (`ScopeGuard.visibleAdmissionIds`, 300 rows) | `Hash Join`: `Bitmap Heap Scan on admission` via `ux_admission_tenant_bed_admitted` (300 rows, 8 blocks) joined to `Bitmap Heap Scan on bed` via `ix_bed_tenant_category` (300 rows) | 1.2 ms (1.6 ms with seqscan off) | acceptable; the index is not the `ix_admission_tenant_status_admitted_at` the design note names |
| C1 | `admitted` count | `Index Only Scan using ux_admission_tenant_bed_admitted` (Heap Fetches 300: table not vacuumed in the test) | 0.4 to 0.9 ms | as expected |
| R4 | clinical events, nurse with 2 wards + 1 grant, limit 51 | `Index Scan using ix_clinical_event_tenant_at` (no sort), nested loops to admission and bed by primary key, grant by primary key; **639 events read for 51 rows** | 13 ms | within the SAH-33 accepted pattern |
| R4b | same, nurse with no ward and no grant (own entries only) | same index scan, **951 events read, 142,308 join-filter removals** | 28 to 34 ms | **finding F-2** |
| R4c | same, OWNER (no predicate; as before SAH-34) | `Index Scan using ix_clinical_event_tenant_at`, 51 rows read | 2.8 to 3.9 ms | baseline |
| U1 | unreviewed count | `Index Only Scan using ix_emergency_access_grant_unreviewed` | 0.10 ms | as expected |
| U2 | first page, limit 26 | `Index Scan using ix_emergency_access_grant_unreviewed`, no Sort; bed and admission by key | 0.31 ms | as expected |
| U2b | keyset page | same with the row comparison as Index Cond | 0.3 to 0.45 ms | as expected |
| U2c | unreviewed ids (change token) | partial index scan | 0.2 ms | as expected |
| U3 | review | `Index Scan using uq_emergency_access_grant_tenant_id` (+ Filter `reviewed_at is null`); the guard trigger costs 2.0 to 2.6 ms of 3.2 to 3.9 | 3.2 ms | as expected (the plan names `uq_...tenant_id`, not the primary key) |

Request times in the same hospital (MockMvc, warm JVM, best of 5) are in `backend/target/sah34-explain.txt` under "requests".

**For the db-engineer** (they own `docs/database/SAH-34.md` section 10; QA does not edit it):
- Replace the expected plans with the table above. R1, R2, R3, C2 do not apply (design 1.5).
- **F-1 (S2, `HAS_LIVE_GRANT`):** the liveness test is a Filter, so the probe reads every grant the member ever opened (S2b: 3,650 entries, 2.3 ms; a grant per nurse per day is 365 a year). Suggest adding a stable, implied bound to both statements: `and g.expires_at > now() - interval '1 minute'` (any row that is live on `clock_timestamp()` also satisfies `expires_at > now()`, because `clock_timestamp() >= now()`), so the descending index range stops at the live entries; re-run S2/S2b. The Java statement belongs to the java-developer (`JdbcWardScopeRepository.LIVE_GRANTS`, `HAS_LIVE_GRANT`).
- **F-2 (R4):** the nurse with no ward and no grant reads most of the events to find her own. The note already names the revisit trigger (more than about 1,000 rows filtered per page) and the cure (`(tenant_id, actor, at desc, seq desc)` index in its own `create index concurrently` migration). The scoped nurse is the case that hits it first; decide before a hospital with a million events.
- The design note says the narrow read uses `ix_admission_tenant_status_admitted_at`; the planner uses `ux_admission_tenant_bed_admitted`. No action needed; correct the text.

## 5. Results (10 Nov 2026)

- `node scripts/validate.mjs backend`: **PASSED**. Unit 3,744 run, 0 failed. Integration 1,900 run in 140 classes, 0 failed, 0 errors, 26 skipped (not SAH-34 tests). The 82 new SAH-34 integration tests and the changed `SafetyChangeTokenIT` all pass; `ScopePlansIT` wrote the plans of section 4.
- Browser: `ward-scope.spec.ts` 49 tests, 98 runs, 98 pass; `--repeat-each=3` 294 of 294; five mutations of the mock all detected (26, 7, 1, 2, 1 tests fail). Whole e2e folder once: 2,558 passed, 45 skipped by design, 9 failed; 7 passed again on `--last-failed` (load: network suspended, timeouts), 2 were the SAH-29 Packages test that still expected a Try again button the angular-developer removed on purpose; QA updated that test, it passes.
- Lint passes; frontend unit tests 2,761 of 2,761; build passes. `validate frontend` and `validate docs` keep their known failures (the `--browsers` flag; `ADM-2.md`).
- Defects: none in the product. Findings F-1 and F-2 (performance) and F-3 (plan text) in section 4 and in the QA report.
- The criteria marked "see section 5" (AC 21 and 22) are covered by the unit `IpdScopeCoverageTest` and the unchanged existing IPD suites, which are part of the passing backend gate: **pass**.

## 6. Follow-up after the review fix pass (10 Nov 2026, late)

Inputs: `docs/reviews/SAH-34-code.md`, `docs/reviews/SAH-34-security.md`, design note section 9 "Added 10 Nov 2026", AC 14 (reworded), AC 78 to 81 (added). Interim notes: `docs/test-plans/SAH-34-followup.md`.

| AC / finding | Test | Result |
| --- | --- | --- |
| AC 78 one audit row per picker read: action `IPD_EMERGENCY_ACCESS_PICKER_VIEWED`, entity type `IPD_EMERGENCY_ACCESS`, no entity id, no before, actor her user id, after exactly `{"count": 1}`; the raw row holds no admission id, patient id, name, mobile, bed code or UHID; no record-access row and no clinical event | `EmergencyAccessIT.thePickerListsTheAdmittedPatientsOutsideHerWardsWithIdentityOnlyThreeReasonsAndWritesNothing` (updated: the snapshot differs only by the one audit row) | pass |
| AC 78 a list of 0 writes a row with count 0; two reads write two rows; opening a grant still writes only `IPD_EMERGENCY_ACCESS_OPENED` | `EmergencyAccessIT.anEmptyPickerStillWritesOneAuditRowWithCountZeroAndTwoReadsWriteTwoRows` | pass |
| AC 78 401, 403 `forbidden` (seven roles, token/membership mismatch, inactive member, switch off) write nothing | `EmergencyAccessIT.aRefusedOrUnauthenticatedPickerReadWritesNoAuditRowAndNothingElse` | pass |
| AC 78 / AC 60 / AC 14 as reworded: IPD read-only still answers and writes the row; IPD Off: OWNER gets 403 `forbidden`, NURSE 403 `module-off` on the picker and on Open (role first); no row | `EmergencyAccessIT.aReadOnlyIpdStillAnswersThePickerAndWritesTheRowWhileIpdOffIsRoleFirstThenModuleOffAndWritesNothing` | pass |
| AC 78 the audit write fails: 500, fixed body, no list, no row kept, nothing else written; the next read works. Forced with a trigger on `platform.audit_event` limited to this test's tenant and to this action, created and dropped by the table owner in `finally` (the technique of `MedicineDbErrorsIT`) | `EmergencyAccessIT.whenTheAuditWriteFailsThePickerAnswers500WithNoListAndNothingIsKept` | pass |
| AC 79 owner token: no line at 0 (equals the DOCTOR token and the old rule), `bg:<count>:<newest id>` at 1 and 2, back to none after the last review; DOCTOR, the nurse who did not open it and the grantee never carry a line; board, summary and banner agree; the token is worked out here from the rule | `ScopeReviewFixIT.theOwnerTokenGainsOneBgLineWithTheCountAndTheNewestIdWhileAGrantIsUnreviewedAndHasNoneAtZero` | pass |
| AC 79 a review plus a new grant in one interval (count 1 both times) changes the token: the newest id is in it | `ScopeReviewFixIT.aReviewTogetherWithANewGrantInOneIntervalChangesTheTokenBecauseTheNewestIdIsInIt` | pass |
| AC 79 the token holds no id, name or count as plain text; another clinic's grant never moves this clinic's token | `ScopeReviewFixIT.theTokenHoldsNoIdNameOrCountAsPlainText`, `...theTokenOfAnotherClinicsGrantIsNeverInThisClinicsToken` | pass |
| AC 79 DOCTOR and NURSE tokens unchanged; OWNER counts have 7 keys, others 6 | `SafetyChangeTokenIT` (unchanged, 9 tests) | pass |
| AC 81 / F-2 part 1: a scoped nurse with no ward and no grant gets exactly her own 65 entries (admitted, discharged and other wards), in the order and paging of any nurse (first page 14, then 50), equal to the rows of the old statement (`SCOPED_NURSE` with empty lists, run through `RealSql`); none of the grant or others' entries; no own entries gives an empty page; a nurse with a ward keeps AC 11 | `ScopeReviewFixIT.aScopedNurseWithNoWardAndNoGrantGetsExactlyHerOwnEntriesAndTheSameRowsAsTheOldStatementWithTheSamePaging` | pass |
| AC 14 / code review finding 3: an order or an escalation of a DISCHARGED admission, a nurse outside the scope: 403 `outside-scope` (fixed four fields, nothing written), never 409 or 404; the nurse of the last ward gets the endpoint's own 4xx that is not 403; an unknown id is 404 | `ScopeReviewFixIT.forAnOrderOrAnEscalationOfADischargedAdmission...` | pass |
| AC 80 discharged admission: the nurses of the ward of the last bed read detail, nursing, EMR, MAR and the discharge sheet (same status as the owner); another ward and the nurse who had an emergency access within the 30 minutes get 403 `outside-scope`; the grant stays in the owner's list; a write by an outside nurse is `outside-scope` first, by a nurse of the last ward `not-admitted` | `ScopeReviewFixIT.aDischargedAdmissionIsReadableOnlyByTheNursesOfTheWardOfItsLastBed...` | pass |
| Code review finding 5: expiry-vs-dose margin 30 s (was 10 s); the lock-waiter match is `%ipd.admission%`; a new precondition fails loudly if the dose queued after the grant ended | `ScopeRaceIT` (6 tests) | pass |
| Code review finding 6 and F-1: S2b asserts the live-range scan as a work count (0 rows read, was 3,650); the other plan assertions are work-count budgets (rows read), not plan words; the plan output stays as evidence | `ScopePlansIT` | pass |
| SAH-34 follow-up item 5: slug flake | `UniqueSlugs` used by `OpdJourneySupport` and `WalkInTokenJourneyIT`; `ClinicMastersIT` 39, `SeniorCallIT` 10, `PackageTableIT` 15, `WalkInTokenJourneyIT` 1 pass | pass |
| SAH-29 module-off text | `packages.spec.ts`: "Packages are not available because the module is off." and no Try again; the 500 and offline tests keep "We could not load the packages. Try again." | pass |
| Code review finding 9: the focus lands on `#ipd-panel` after a refusal (the row that opened the sheet is gone) | `ward-scope.spec.ts`: two new tests (refused open, refused save) | pass |

### Real plans after the fixes (`ScopePlansIT`, 26 wards, 300 admitted, 36,010 events, 90,000 doses, 3,680 grants of which 30 unreviewed; as `sahaj_app`)

| Id | Rows read | Execution | Note |
| --- | --- | --- | --- |
| S1 wards | 2 | 0.13 ms | |
| S2 grants (5 of her own) | 2 | 0.21 ms | |
| S2b grants (3,650 old) | **0** | 0.12 ms | was 3,650 entries and 2.3 ms: F-1 fixed |
| S2c `HAS_LIVE_GRANT` (3,650 old) | 0 | 0.07 ms | |
| Narrow admitted read | 1,505 | 0.6 ms | |
| C1 admitted count | 300 | 0.3 ms | |
| R4 scoped nurse, 2 wards and 1 grant (real SCOPED_NURSE) | 2,393 | **10.5 ms** | |
| R4x the same with the old `= any(cast(...))` text | 98,381 | **231 ms** | the 200 ms of the review, reproduced |
| R4b scoped nurse, no ward, no grant, many own entries (ACTOR_ONLY) | 2,454 | 2.9 ms | |
| R4d scoped nurse, no ward, no grant, 10 own entries (ACTOR_ONLY) | 72,044 | **9.0 ms** | `Bitmap Heap Scan` of the clinic's whole log (36,012 entries, filter removes 36,002); about 1 ms per 4,000 events, so about 250 ms at a million (by proportion, not measured) |
| R4c OWNER | 1,005 | 2.0 ms | |
| U1 unreviewed count | 30 | 0.14 ms | |
| U2 `UNREVIEWED_MARK` (count and newest id) | 31 | 0.15 ms | |
| U3 review | 1 | 3.7 ms | |
| D1 `DOSES_BETWEEN`, 300 admissions, one day, 90,000 doses | 2,400 | 3.9 ms | `Bitmap Index Scan` with the cast as an Index Cond (evaluated once); no change needed |
| D2 `DOSES_BETWEEN`, 12 admissions | 48 | 0.31 ms | the `in (select unnest(...))` form (D2x) is the same, 0.33 ms |

Requests (MockMvc, warm JVM, best of 5): `GET /clinical-events?limit=50` scoped nurse **15 ms** (was 200 ms; the java-developer expected about 10 ms or less), scoped nurse with no ward and no grant 13 ms, owner 7 ms; `GET /admissions` scoped nurse 28 ms, owner 52 ms; `GET /safety` scoped nurse 8 ms, owner 14 ms; `GET /board` scoped nurse 17 ms. Full output: `backend/target/sah34-explain.txt`.

### Results of the follow-up (11 Nov 2026)

- `node scripts/validate.mjs backend`: **PASSED** (unit 3,763 run in 243 classes; integration 1,938 run in 142 classes; 0 failed, 0 errors, 26 skipped, none in SAH-34 classes). The first run of this gate failed on `SetupAndShellIT.slugAvailabilityReportsFreeTakenReservedAndInvalid` because the first `UniqueSlugs` produced 24-character addresses, which leave no room for the "-2" alternatives; the shape is now 22 characters.
- Browser: `ward-scope.spec.ts` + `packages.spec.ts` 172 of 172.
- Known and unchanged: `validate frontend` (the `--browsers` flag) and `validate docs` (`ADM-2.md`).
