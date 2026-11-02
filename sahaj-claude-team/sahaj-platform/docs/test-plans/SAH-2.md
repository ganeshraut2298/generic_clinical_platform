# Test plan · SAH-2 Home page (enquiry endpoint)
Mockup screen: Home page (landing: features, pricing, compare, Start free)
Module: platform · Author: qa-engineer · Date: 1 Nov 2026
Stories: `docs/stories/SAH-2.md` · Design: `docs/database/SAH-1.md` (table `platform.sales_enquiry`) · Contract: `docs/api/platform.yaml`

## Scope
This plan covers the backend part of criterion 4 and the technical design rules for `POST /api/v1/platform/public/sales-enquiries`: validation, insert-only table, no tenant, rate limit 5 per address per hour, mobile never logged. The page itself (criteria 1 to 3 and 5 to 12) and the Playwright tests in `frontend/e2e/` are a later task, after the frontend is finished; they are listed at the end as planned.

## Set-up
- Same as SAH-1: whole application on `sahaj_test`, pool as `sahaj_app`, Flyway as `sahaj_migrator`.
- `sahaj_app` has INSERT only on `platform.sales_enquiry`, so rows are read back through a `sahaj_migrator` connection (the table has no RLS, so the migrator sees all rows).
- MockMvc tests send each request from a different connection address, because the limiter keeps its counters for the life of the Spring context. The 429 tests use the real embedded server and `java.net.http.HttpClient`, each in a Spring context of its own.

## Coverage

| # | Criterion / rule | Test (class.method) | Type |
|---|---|---|---|
| 4 | valid name and 10-digit mobile is saved; response is 201 `{id}`; row has all fields | `SalesEnquiryIT.aValidEnquiryIsSavedAndTheResponseCarriesTheId` | Happy path |
| 4 | optional fields may be absent (stored NULL) | `SalesEnquiryIT.anEnquiryWithOnlyTheRequiredFieldsIsSavedWithNullOptionalColumns` | Boundary |
| 4 | text trimmed, blank city stored as NULL | `SalesEnquiryIT.textIsTrimmedBeforeItIsSavedAndBlankCityBecomesNull` | Boundary |
| 4 | every best-time option and both sources accepted | `SalesEnquiryIT.everyBestTimeOptionAndBothSourcesAreAccepted` | Happy path |
| 4 | missing or blank name: 400, field error `name`, nothing saved | `SalesEnquiryIT.aMissingOrBlankNameIsRejectedWithAFieldErrorAndNothingIsSaved` | Negative |
| 4 | invalid mobile (8 variants): 400, field error `mobile`, nothing saved | `SalesEnquiryIT.invalidMobileNumbersAreRejectedWithAFieldErrorOnMobile` | Negative |
| 4 | mobile boundaries 6000000000 and 9999999999 accepted | `SalesEnquiryIT.mobileNumbersAtTheEdgesOfTheValidRangeAreAccepted` | Boundary |
| 4 | all missing required fields reported together | `SalesEnquiryIT.everyMissingRequiredFieldIsReportedTogether` | Negative |
| 4 | unknown option rejected, value not echoed | `SalesEnquiryIT.anUnknownBestTimeOrSourceIsRejectedWithoutEchoingTheValue` | Negative |
| 4 | name 120 ok / 121 rejected; city 200 ok / 201 rejected | `SalesEnquiryIT.nameAndCityLengthLimitsAreEnforcedAtTheirBoundaries` | Boundary |
| 4 | broken JSON and empty body: 400 problem+json | `SalesEnquiryIT.brokenJsonAndAnEmptyBodyGet400AsProblemJson` | Negative |
| 4 | wrong content type: 415 problem+json | `SalesEnquiryIT.aWrongContentTypeGets415AsProblemJson` | Negative |
| 4 | quotes and Devanagari stored as sent; SQL text is data | `SalesEnquiryIT.theNameCanContainQuotes...`, `sqlTextInAFieldIsStoredAsPlainTextAndDoesNotRunAsSql` | Boundary / security |
| Design | no tenant column; tenant id in body, header, query changes nothing | `SalesEnquiryIT.theSavedRowHasNoTenantBecauseTheEndpointIsPublic`, `RowLevelSecurityCatalogIT.salesEnquiryHasNoTenantColumn` | Tenant |
| Design | no login needed; no read-back endpoint (GET is 405) | `SalesEnquiryIT.theEndpointNeedsNoLoginAndTheListIsNotExposed` | Role |
| Design | `sahaj_app` INSERT only: plain insert works; `RETURNING`, SELECT, UPDATE, DELETE refused; column-level too | `RowLevelSecurityCatalogIT.salesEnquiryAcceptsAPlainInsertButRefusesReturningAndAnyRead`, `salesEnquiryIsInsertOnlyAtColumnLevelToo`, `sahajAppHasExactlyTheDocumentedPrivilegesOnEachPlatformTable` | Grants |
| Design | 6th request from one address returns 429 (real HTTP client), problem+json, `Retry-After`, `retryAfterSeconds`; only 5 rows saved; 7th (invalid) also 429 | `SalesEnquiryRateLimitIT.theSixthRequestFromOneAddressInAnHourIs429AndIsNotSaved` | Rate limit |
| Design | mobile absent from logs (DEBUG capture) and from every body: success, 400 of four kinds, 429 | `SalesEnquiryIT.theMobileNumberIsAbsentFromLogsAndFromEveryResponseBody`; also inside `SalesEnquiryRateLimitIT` | Privacy |
| Risk | with `SAHAJ_REMOTE_IP_HEADER` unset, a forged `X-Forwarded-For` (and Forwarded, X-Real-IP, X-Client-IP) does not change the rate-limit key: 5 allowed, then 429 for 20 forged addresses | `ForwardedHeaderRateLimitIT.withNoRemoteIpHeaderConfiguredAForgedXForwardedForDoesNotChangeTheRateLimitKey` | Configuration risk |

## Frontend smoke tests (Playwright, `frontend/e2e/home.spec.ts`)
Run at 360 px (project `phone-360`) and 1440 px (project `desktop-1440`) against the production build served by `frontend/e2e/serve.mjs`. Run with `npm --prefix frontend/e2e test` (builds first) or `npx playwright test` in `frontend/e2e`.
**The real backend is not running (no database credentials). Every enquiry-sheet test mocks `POST /api/v1/platform/public/sales-enquiries` in the browser (`page.route`).** They prove what the page does with 201, 429, 500, 200 and a network failure; the real endpoint is covered by the backend tests above.

| Criterion | Test (describe › name) | Notes |
|---|---|---|
| 1 | Home page › page loads with the headline, no script errors and no horizontal page overflow; no section is wider than the viewport; section headings match the mockup labels | both widths |
| 5 | header shows brand, Admin portal, Log in and Start Free; section links show only above 980 px; desktop: a section link scrolls to its section; header stays at the top (sticky, 10 px as in the mockup) | links hidden at 360 px |
| 6 | sections appear in mockup order | 12 sections, DOM order and drawn order |
| 10 | footer says patient data is hosted in India and prices exclude GST; pricing note says 18% GST | |
| 2 | Navigation › header, hero and final call to action Start Free open `/login?mode=signup` | |
| (stub) | Log in opens `/login?mode=login`; Admin portal shows Coming soon | assumptions in the story |
| 7 | Billing period toggle › Professional ₹1,999 per year / ₹299 per month; Free, Multi-Doctor, Custom | |
| 9 | Enquiry sheet › asks for name, clinic name and city, mobile (+91), best time (Morning, Afternoon, After OPD hours); fits 360 px; Contact sales opens "Talk to sales" | |
| 4 (error) | invalid input shows field errors and sends nothing | empty, 5 digits, starts with 5, blank name |
| 4 (happy, mocked) | API MOCKED 201: request carries typed values, thank-you shows, sheet closes | body checked; no tenant id sent |
| 4 (error, mocked) | API MOCKED 429 and 500: error shows, values stay, no thank-you, retry possible | |
| 4 (error, mocked) | API MOCKED network failure; API MOCKED 200 (not 201): no thank-you | |
| 12 | Keyboard › Tab reaches skip link, Admin portal, Log in, Start Free, each with a visible focus ring; desktop: five section links; Enter on Start Free; enquiry sheet focus and Escape | ring checked by computed style and by eye |
| 11 | Reduced motion › no CSS animation running; hero, Clinic setup, Roles, Workflow builder, Pricing show final state; control test shows the hero does play without reduced motion | `reducedMotion: 'reduce'` |
| 3, 8 | not covered by Playwright (calculator and Compare are covered by the frontend unit tests); a later task | |

## Known limits
- Each rate-limit class needs the loopback address to be the connection address; run it on a machine that does not sit behind a proxy that rewrites it.
- If the operator sets `SAHAJ_ENQUIRY_LIMIT_MAX`, `SalesEnquiryRateLimitIT` fails at its first assertion by design.
- Rows from these tests stay in `sahaj_test` (the app cannot delete them); names are unique per run.
