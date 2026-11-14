# Full-app sweep: real-database integration tests for SAH-4 to SAH-10

Branch: feature/SAH-13-appointments-workspace (working tree). Owner of this file: qa-engineer.
Scope: every endpoint of SAH-4 (setup), SAH-5 (shell), SAH-6 (team, permissions), SAH-7 (clinic masters), SAH-8 (patients), SAH-9 (queue), SAH-10 (billing) that had no real-database test. All tests call the real API (MockMvc through the real security filter chain) against `sahaj_test` as `sahaj_app`, with a real sign-up, the real five-step wizard, and team members and patients created through the real endpoints. Several tests also use real OTP logins and real Bearer tokens.

## Test classes added (all under `backend/src/test/java/com/sahaj/platform/it/`)

| Class | Stories | Tests | Highlights |
| --- | --- | --- | --- |
| `SweepSupport` | helper | - | raw calls, problem+json check, real team member + real login |
| `SetupAndShellIT` | SAH-4, SAH-5 | 29 (2 disabled) | all 5 steps, validation, plan limit, slug availability and slug-taken, complete twice, non-owner 403, re-login after setup reaches APP with a working token, mid-setup login resumes at the saved step, /me per role, menu per role, preferences |
| `TeamAndPermissionsIT` | SAH-6 | 33 | add every role, duplicate mobile, plan limit, edit/role change, last-owner guard (deactivate, demote, remove), deactivate/reactivate/remove, deactivated member cannot log in, old token refused within 60 s, tenant isolation, audit with masked mobile, permission grid read/write/validate |
| `ClinicMastersIT` | SAH-7 | 39 (1 disabled) | services CRUD + duplicates + active filter, fee settings with discount limits, medicines (search, paging, wildcard text, favourites per clinic), diagnoses, case paper, holidays, specialty templates, clinic profile, setup overview, audit events |
| `PatientsIT` | SAH-8 | 25 (1 disabled) | create, UHID sequence per clinic, search by name/mobile/UHID, filter chips, paging, family, duplicate flag, partial update, merge, roles, isolation |
| `QueueIT` | SAH-9 | 22 | token issue (number, stage, fee, follow-up fee rules), concurrent tokens, today, `mine` per role, stage moves forward/back/owner-only, ownership by stage, workflow version per visit |
| `BillingIT` | SAH-10 | 31 | counter lists, bill read, add/remove item, price snapshot, discount over role limit (400), partial and full payment (visit becomes DONE), refund owner-only, day closing and 409 on a second close |
| `RealSessionJourneyIT` | SAH-5..10 | 6 | a whole clinic day with real tokens for front desk, nurse, doctor and owner; read/forbidden matrix per real role; cross-clinic token; tampered token; very large money values |
| `LifecycleEdgeCasesIT` | SAH-6..10 | 10 | doctor switched off or removed mid-day, patient merged away, template applied twice, holiday on a working day |

Total new: about 195 tests (4 disabled with a bug javadoc each).

## Final state (after java-developer's fixes)

`mvn -f backend/pom.xml verify` on `sahaj_test`: 333 tests, 0 failures, 0 errors, 1 skipped. Bugs 1, 2 and 3 below are fixed and their tests are enabled and pass. The only skipped test was `PatientsIT.everyClinicRoleMayReadPatientsAsTheContractSays` (bug 4). **Update 13 Nov 2026: bug 4 is closed by DEC-049 answer 2** (BILLING and PHARMACIST may read, COUNSELLOR may not, patient.yaml 0.2.1); the skipped test is replaced by three enabled tests in `PatientsIT` (see docs/test-plans/SAH-53.md, section DEC-049 items 2 and 3).

Test changes made after the fixes:
- `LifecycleEdgeCasesIT.applyingATemplateWhenTheOwnerAlreadyAddedASameNamedServiceDoesNotFail`: the wizard now seeds starter services, so the test picks a dental template service the second clinic does not have yet, adds it by hand in upper case at Rs 77, applies the dental template, and checks there is still exactly one such service and it keeps Rs 77.
- `ClinicMastersIT.colourExampleGivenInTheErrorMessageIsAccepted`: extracts the `#` plus six hex digits example from the 400 message and checks it is accepted when sent back.
- `SetupAndShellIT`: stale "BUG" javadoc removed from the two re-enabled tests.
- No other test in `it/` assumed an empty price list or used a clashing fixed service name (services created in `BillingIT` carry a random suffix).

Statuses in the tables below that say DISABLED or "pass except bug 1..3" are superseded by this section.

## Coverage by acceptance area

| Area | Test (class.method) | Result |
| --- | --- | --- |
| SAH-4 AC1 complete creates clinic, team, profile | SetupAndShellIT.completeCreatesTheClinicTeamAndIsRepeatable | pass |
| SAH-4 AC3/AC11 slug availability, reserved, taken | slugAvailabilityReportsFreeTakenReservedAndInvalid, takingAnotherClinicsSlug... | pass |
| SAH-4 AC4 plan limit 409 | tooManyPeopleInTheTeamStepIsAPlanLimit | pass |
| SAH-4 AC14 owner only | everySetupRouteRefusesNonOwners | pass |
| SAH-4 re-login after setup | ownerLogsInAgainAfterSetupAndLandsOnAppWithAWorkingToken | pass |
| SAH-4 AC9 first doctor named in step 3 | wizardFirstDoctorNameIsKeptOnTheOwnersTeamEntry | DISABLED, bug 1 |
| SAH-4 AC1/AC8 template saved (starter services) | finishingSetupSeedsTheStarterPriceListOfTheChosenSpecialty | DISABLED, bug 2 |
| SAH-5 /me, menu per role, preferences | meReturns..., menuDiffersPerRole..., preferences* | pass |
| SAH-6 AC1..AC13 | TeamAndPermissionsIT (whole class) | pass |
| SAH-7 services, fees, medicines, case paper, holidays, specialty, profile | ClinicMastersIT | pass except bug 3 |
| SAH-8 AC1..AC10 | PatientsIT | pass except bug 4 |
| SAH-9 AC1..AC9 | QueueIT | pass |
| SAH-10 AC1..AC9 | BillingIT | pass |

## Bugs found (most severe first)

None of these is a 500 or a data-leak. The real-database classic failure shapes (`.single()` on NULL, untyped null parameters, UUID/Instant/jsonb binding, RLS blocking an insert, missing grants) were checked in every `Jdbc*` repository by reading and by exercising each one through the API; no further instance of the `lastVisitAt` bug exists. `.single()` appears only on aggregate queries that always return a non-null value (`count`, `exists`, `coalesce(sum)`, `next_token_no()`).

### Bug 1 (medium) - the first doctor's name typed in the wizard is thrown away
- Symptom: step 3 asks for the first doctor ("the first is marked Owner", SAH-4 AC9). After setup the team list, the queue board, bills and appointments show that doctor as "Owner".
- Root cause: `SetupService.saveDoctors`, branch `i == 0` (backend/src/main/java/com/sahaj/platform/application/SetupService.java line 229-231) reuses the owner's membership and never stores `doctor.name()`.
- Fix: write the typed name into the owner membership's `display_name` when `i == 0`.
- Owner: java-developer.
- Test: `SetupAndShellIT.wizardFirstDoctorNameIsKeptOnTheOwnersTeamEntry` (disabled).

### Bug 2 (medium) - finishing setup does not seed the price list of the chosen specialty
- Symptom: after the wizard with "General practice", `GET /opd/services` is empty. The Fees page and the billing "add service" picker have nothing until the owner opens Clinic setup and applies the template by hand (which works).
- Root cause: `SetupService.complete` (SetupService.java line 178-221) stores the specialty code but never calls `SpecialtyServicesPort.addMissingServices`, which `SpecialtyService.apply` does call.
- Fix: in `complete`, apply the template services in the same transaction. Note the homeopathy template has no services by design (seed comment).
- Owner: java-developer.
- Test: `SetupAndShellIT.finishingSetupSeedsTheStarterPriceListOfTheChosenSpecialty` (disabled). Applying the template by hand is covered and passes (`LifecycleEdgeCasesIT`, `ClinicMastersIT`).

### Bug 3 (low) - custom colour error message suggests a value the server refuses
- Symptom: a wrong colour gives "Enter 6 characters, like 1F5BC8", but `1F5BC8` is refused (400); only `#1F5BC8` is accepted.
- Root cause: `ClinicProfileService` line 29 (`HEX` needs `#`, matches docs/api/platform.yaml) versus the message at line 174.
- Fix: change the message to "like #1F5BC8" (or accept both and add the `#`). The frontend should check what it sends.
- Owner: java-developer.
- Test: `ClinicMastersIT.colourExampleGivenInTheErrorMessageIsAccepted` (disabled).

### Bug 4 (low, contract mismatch) - billing, pharmacist and counsellor roles cannot read patients
- Symptom: `GET /patient/patients`, `/patients/{id}` and `/family` return 403 for BILLING, PHARMACIST and COUNSELLOR. `docs/api/patient.yaml` says "Reads are for any clinic role". Story SAH-8 AC8 is ambiguous (it names OWNER, FRONT_DESK, NURSE for view).
- Root cause: `PatientRoles.READ` (backend/src/main/java/com/sahaj/patient/web/PatientRoles.java line 8).
- Fix: needs a human decision on the intended rule, then either widen `READ` or correct the contract. COUNSELLOR and BILLING staff look patients up in the mockup.
- Owner: java-developer (and solution-architect if the contract changes).
- **Closed 13 Nov 2026 (DEC-049 answer 2):** BILLING and PHARMACIST read, COUNSELLOR 403; tests `PatientsIT.billingAndPharmacistReadTheListDetailAndFamilyWithAgeAndNoDateOfBirth`, `counsellorGets403OnTheListTheDetailAndTheFamily`, `billingAndPharmacistStillCannotWritePatientsAndCannotReadAnotherClinicsPatient` (the disabled test is removed).

### Observations, not bugs (no test disabled)
- A bill with a zero balance (for example a free follow-up) cannot take a payment (amount must be at most the balance and at least 1 paisa), so the billing counter has no "close" action for it. The visit can still be moved to DONE with the stage PATCH (tested in `BillingIT.aFreeFollowUpWithNothingToPayCanStillBeClosedByMovingItToDone`). Check with the human whether the counter needs a "Mark done" action.
- `PATCH /patients/{id}` with `"area": null` keeps the old area (null means "not sent"); sending `""` clears it. The contract lists `area` as nullable. Low.
- `GET /me` has no `permissions` array and `GET /setup/overview` checks have no `section` and `anchor`, although both are in platform.yaml. They are optional properties, so clients that need them (the Fix links) will not get them. Raise with java-developer if the frontend uses them.
- `GET /patients` summary `lastVisitAt` is documented as "always null until SAH-9 exists"; SAH-9 now exists. Not checked further.
- `PatientsIT` shows UHIDs are `<prefix>-00001` per clinic and restart at 00001 for each clinic (correct).

## Test-side findings (fixed in the tests, not application bugs)
- `clinic.as(role)` keeps the owner's user id, and several services re-read the actor's membership (`OwnerGuard`, `TeamService`). Tests that need a real non-owner at service level (backward stage move by front desk, second owner) therefore log in a real member and use a Bearer token.
- MockMvc encodes a URL template again, so `?q=%25` arrives as the text `%25`. `SweepSupport.call` builds a `URI` so encoded query strings are sent as written.

## Environment notes for the tech lead
- The working tree is being edited under this run: `backend/src/main/resources/db/migration/V2026_11_02_1000__opd_appointment_tables.sql` shows as deleted (a stale copy `V2026_11_02_1000__opd_sah13_appointment_slots.sql` in `backend/target/classes` made Flyway fail with a syntax error; it was removed from `target/classes` only). Main sources (`MeService`, `RefreshTokenCookie`, `AuthController`) were also modified by someone else while this sweep ran, and the build was briefly uncompilable. Nothing under `backend/src/main` was edited by qa-engineer.
- `TeamAndPermissionsIT.deactivatedMembersExistingAccessTokenIsRefusedByTheMembershipCheck` polls up to 70 seconds because the active-membership check may cache for 60 seconds (SAH-6 AC2); it usually ends sooner. It makes the suite about one minute slower.
