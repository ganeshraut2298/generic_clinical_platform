# Test plan SAH-35 / SAH-36 / SAH-38 · Release audit DEC-025 (backend)

Scope: the backend fixes of the release audit (`docs/reviews/QA_REPORT_SAH-35-36-38.md`, "Phase 1-2 Fixes made"). The browser part (G-2, G-3, G-6, G-9, G-10, G-15) is the browser QA lane's and is not in this plan. Contract: `docs/api/platform.yaml`, `docs/api/ipd.yaml` (G-16 texts). Integration tests run on `sahaj_test`, profile `test`, MockMvc; `RequestBodyLimitIT` uses a real embedded server and `java.net.http.HttpClient`, because MockMvc cannot send a chunked body.

| Class | Tests | Gaps |
| --- | --- | --- |
| `MedicineRoundIT` (changed, 1 test strengthened) | 21 | G-14 |
| `RequestBodyLimitIT` (new, 3 tests added in the fix pass) | 12 | G-1, security #3, code #4 |
| `ApiAccessLogIT` (new, 1 test tightened in the fix pass) | 11 | G-4, code #5 |
| `AdminChainLogAndLimitIT` (new, fix pass) | 9 | security #1 |
| `ReleaseAuditHardeningIT` (new, G-13 test widened in the fix pass) | 10 | G-12, G-13, G-16, code #1, security #4 |

## Gap to test to result

| Gap | Acceptance point | Test | Result |
| --- | --- | --- | --- |
| G-14 | exactly one skipped-order warning across 3 reads (board, summary, list) of one clinic | `MedicineRoundIT.anOrderWhoseDrugIsNotInTheCatalogue...OneWarning...` (warning count == 1) | Pass |
| G-14 | another clinic's damaged order has its own warning (key = clinic + order), and no text of the first clinic | same test, second `LogCapture` | Pass |
| G-1 | Give POST: 65,537 bytes, Content-Length and chunked, answers 413 problem+json, type `.../payload-too-large`, title "Request too large", detail "The request body must be 64 KB or smaller.", `X-Request-Id` present | `RequestBodyLimitIT.theGiveCallRefusesABodyOf65537Bytes...` | Pass |
| G-1 | exactly 65,536 and 65,535 bytes are not 413 (the endpoint answers its own 4xx) | `theGiveCallReadsABodyOfExactly65536AndOf65535Bytes...` | Pass |
| G-1 | 512 KB refused (declared and chunked) | `aVeryLargeGiveBodyIsRefusedToo` | Pass |
| G-1 | `POST /me/pin/colleague-tries/seen` 65,537 -> 413, 65,536 -> the endpoint's own 400 (seenThrough, "Reload the page and try again."), both transfer modes | `theColleagueTriesSeenCallRefusesABodyOf65537BytesWith413AndReadsExactly65536` | Pass |
| G-1 | public `POST /public/sales-enquiries` still 413 above 4,096 bytes ("4 KB"), 4,096 read | `thePublicEnquiryEndpointStillRefusesAboveFourKb...` | Pass |
| G-1 | the two limits are independent (5,000 bytes: public 413, signed-in read) | `theTwoLimitsAreIndependent...` | Pass |
| G-1 | PUT is limited too; session usable after a 413 | `aPutBodyIsLimitedToo`, `afterA413TheSameSignedInSessionWorksAgain` | Pass |
| security #3 | no session, 65,537 bytes -> 413 "The request body must be 4 KB or smaller." (was "64 KB": intended change, not a weakening); a 100-byte body still 401 | `anOversizedBodyWithoutASessionIsRefusedWith413AtTheFourKbCapAndASmallOneStillGets401` | Pass |
| security #3 | no session, non-public path (Give, "Got it"): 5,000 bytes chunked and declared -> 413 "4 KB"; 512 KB chunked -> 413 "4 KB" | `withoutASessionANonPublicPathRefusesAbout5000BytesWith413WhetherChunkedOrDeclared` | Pass |
| security #3 | no session: 4,096 and 4,095 bytes (chunked and declared) -> 401 `unauthorized`; 4,097 -> 413 | `withoutASessionABodyOfExactly4096And4095BytesIsReadAndAnswered401...` | Pass |
| security #3 | signed-in caller keeps 64 KB on the same path (5,000 and 65,536 read, 65,537 -> 413 "64 KB") while an anonymous one gets 4 KB | `aSignedInCallerKeepsThe64KbCapOnTheSamePathWhereAnAnonymousOneGets4Kb` | Pass |
| G-4 | one INFO line on `sahaj.access` per signed-in call: method, template path (`{id}`, not the UUID), status, durationMs, tenantId and userId = caller, requestId = `X-Request-Id` | `ApiAccessLogIT.aSignedInReadWritesOneInfoLine...` | Pass |
| G-4 | each request has its own id, line matches its own answer's header | `everyRequestHasItsOwnRequestId...` | Pass |
| G-4 | `?name=Asha&mobile=9000000001` never in the line | `aQueryStringWithANameAndAMobileNumberNeverReachesTheLine` | Pass |
| G-4 | no body, PIN, wristband, second nurse, name, mobile (wrong PIN 403 and right PIN 201) | `aGiveCallWithAPinAWristband...` | Pass |
| G-4 | no session: `path=unmatched status=401 tenantId=- userId=-` | `aCallWithoutASessionIsLoggedAsUnmatched...` | Pass |
| G-4, code #5 | unknown path never echoed; signed-in 404 logs exactly `path=unmatched` (never `/**`, never the raw path); anonymous 401 too | `anUnknownPathIsLoggedAsUnmatched...` (tightened from `isIn("unmatched","/**")`) | Pass |
| G-4 | tenant isolation: another clinic's admission is 404, the line names only the caller's clinic | `anotherClinicsCallToThisClinicsPatientIs404...` | Pass |
| G-4 | malformed id: 400, template path, bad text not logged | `aMalformedIdIsLoggedWithTheTemplate...` | Pass |
| G-4 | 413 and 500 are still one line each; no secret of the exception; non-API path writes none | `anOversizedBodyIsLoggedAs413...`, `anUnexpectedServerErrorIsStillOneLine...`, `aPathOutsideApiWritesNoAccessLine` | Pass |
| G-12 | Give: unreadable `clientKey` (123, {}, [], true, 1.5, ...) -> 400 fieldError "Reload the page and try again.", nothing written, no parser text | `ReleaseAuditHardeningIT.anUnreadableClientKey...` | Pass |
| G-12 | Give: unreadable `slotAt`, `outcome` ({}, []) -> same text | `anUnreadableSlotAtOrOutcome...` | Pass |
| G-12 | other fields keep "Choose a valid option" (deferred D-1 pinned) | `anUnreadableValueOfAnyOtherFieldKeepsChooseAValidOption` | Pass |
| G-12 | same text for every role that can give; no session 401 | `theReloadTextOfTheGiveCallIsTheSame...` | Pass |
| G-12 | `seenThrough` ({}, 123, [], null, "", not a date, before 2020, future, 41 chars, missing) -> 400 "Reload the page and try again."; a good value -> 204 | `anUnreadableOrUnacceptableSeenThrough...` | Pass |
| G-12 | broken / empty body of "Got it" -> 400 (not 500); no session 401 | `aBrokenOrEmptyBodyOfTheSeenCall...` | Pass |
| G-13 | forced database faults quoting a value (3 forms): "Unhandled error" line has sqlState and the kind of fault, no value anywhere in the log or the response; transaction rolled back | `aDatabaseFaultThatQuotesAValueIsLoggedWith...` | Pass |
| code #1, security #4 | 4 more forms: identifiers after constraint / column + relation / table + constraint are KEPT in `dbError=` (`constraint "uq_it_audit_x"`, `column "mobile_x" of relation "staff_x"`), nothing else quoted; text after a colon cut; a quoted value after a non-identifier word cut; Detail / Key ( / Failing row values absent | same test (cases 4 to 7) | Pass |
| security #1 | admin chain: sign-in body 4,097 and 65,537 bytes -> 413 problem+json "4 KB"; 4,096 and 4,095 read (strict-JSON 400 `malformed-request`); enrol, verify, refresh the same at 5,000 | `AdminChainLogAndLimitIT.aSignInBodyOf4097Bytes...`, `everyOtherPublicSignInEndpointHasTheSameCap` | Pass |
| security #1 | a signed-in admin (live session) also has the 4 KB cap (documented behaviour) | `anAdminWithALiveSessionHasTheSame4KbCapOnASignedInAdminEndpoint` | Pass |
| security #1 | admin request writes one `sahaj.access` line: template path `/api/v1/admin/auth/login`, status, `tenantId=-`, `userId=-`, requestId = header, no email or password | `anAdminRequestWritesOneAccessLine...` | Pass |
| security #1 | admin-authenticated GET /admin/me: `userId=<adminId>`, `tenantId=-`, template path, no name, token or session id; next anonymous request logs `userId=-` | `anAdminAuthenticatedCallCarriesTheAdminIdAsUserId...` | Pass |
| security #1 | dead admin session -> 401 line without the admin id; no token -> 401; unknown admin path -> `unmatched`, caller text absent; outside the allowed network -> 403 still logged; 413 -> `unmatched`, no body text | `anAdminTokenWhoseSessionHasEnded...`, `anAdminCallWithoutATokenIsLogged...`, `aRequestFromOutsideTheAllowedNetworks...`, `a413OnTheAdminChainIsLoggedAsUnmatched...` | Pass |
| G-16 | 4 bad ids x 4 operations (MAR, doses, pin reset, colleague-tries) -> 400 `bad-request`, title "Bad Request", fixed detail, id not echoed; nothing written; well-formed unknown id still 404 | `aMalformedIdOnTheFourOperations...` | Pass |
| G-16 | another clinic's id is 404 on all four (tenant isolation) | `anIdOfAnotherClinicIs404...` | Pass |
| G-16 | non-owner gets 403 on the two owner-only operations | `theNonOwnerStillGets403...` | Pass |

## Findings while writing the tests

- Closed in the fix pass: a signed-in request to an unknown `/api/**` path used to be logged as `path=/**`; AccessLogFilter now maps it to `unmatched` and the test requires exactly that.
- Admin realm in the IT profile: it CAN be enabled (`sahaj.admin.enabled=true`, the dummy admin secrets of the DEC-016 tests, `sahaj.admin.allowed-networks=10.0.0.0/8`) in a Spring context of its own. The admin tables are still not applied to sahaj_test (`db/pending/admin`, human prerequisite), so `AdminChainLogAndLimitIT` mocks `AdminAuthRepository` and `AdminAuthService`: it proves the filters of the chain (cap, access line, admin id), not the sign-in logic or the audited database functions.
- Body-size risk on the admin chain (docs/api/admin.yaml 0.3.1): no admin request body can legitimately exceed 4,096 bytes. Largest: login (email at most 254 + password at most 128 characters, about 2.4 KB if every character were written as a six-byte JSON unicode escape), PlanChange (reason at most 500 characters, ticket at most 20: about 3.1 KB in the all-escaped worst case, about 0.6 KB normally), ModulesChange (3 small fields), enrol and verify (a 43-character token plus a 6-digit or 19-character code). Refresh, sign-out, /me, GET lists and the dev sample-admin call carry no body. The limit sits ahead of the admin token filter, so a signed-in admin has the same 4 KB. Margin is thin only for PlanChange with escaped text; any future admin body (for example a bulk or free-text note) must be checked against 4 KB first.
- A freshly signed-up clinic has no IPD module: `POST /medicine-orders/{id}/doses` answers 403 `module-off` before it reads the fields, so the "65,536 bytes is read" proof for the Give call is "not 413, endpoint's own 4xx"; the field-level proof is on the `seen` endpoint.

## Run result

Fix pass (security #1/#3/#4, code #1/#4/#5), 8 Nov 2026.

- Touched ITs on sahaj_test: `RequestBodyLimitIT` 12, `ApiAccessLogIT` 11, `AdminChainLogAndLimitIT` 9, `ReleaseAuditHardeningIT` 10 = 42 tests, 0 failures, 0 skips.
- `node scripts/validate.mjs backend`: PASSED. Unit 3,311 run, 0 failures, 0 errors, 0 skips. Integration 1,708 run, 0 failures, 0 errors, 26 skips (prerequisite skips that were there before: DevSampleAdminFunctionIT, DevToolsCleanupScriptIT, DevToolsGuardCallbackIT need the admin tables and role; PatientsIT).
- Frontend e2e (after `npm --prefix frontend run build`, 4 workers): `mar.spec.ts`, `audit-sah35-36-38.spec.ts`, `layout-matrix.spec.ts` together 332 run, 330 passed, 2 failed. Both failures were `mar.spec.ts` "G-9: only the nurse is missing" (phone-360, desktop-1440): the test picked the nurse after the browser pre-check had cleared the PIN, so Given was refused again. That is the intended new behaviour (security optional item), so the test now asserts the PIN box is empty and types the nurse and the PIN together. `mar.spec.ts` rerun: 198 passed, 0 failed.
- E2E edits: `mar.spec.ts` (the "nurse then Given" step near line 1629 and the G-9 step near line 846: nurse and PIN are typed together after a refusal); `serve.mjs` (malformed percent-escape returns 400; the `E2E_NO_KINETIC` hook is kept and documented). The other PIN-then-nurse places in `mar.spec.ts` (lines 814, 827, 884, 916, 1431, 1489 to 1501) keep the nurse selected, so they were not affected. `medicine-round.spec.ts` and `audit-sah35-36-38.spec.ts` type no PIN without a nurse. The SAH-39 dark-theme assertion is not touched.
