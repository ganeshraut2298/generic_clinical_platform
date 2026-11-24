# ADR-031 · Onboarding guard, phased setup completion, clinic logo in the database, owner as doctor, header mobile lookup

Story: SAH-89 (SAH-89-1 to SAH-89-9) · Clinic sign-up to setup, validation, address, look, completion, header mobile search, doctor fee, owner as doctor
Mockup screen: Start screen and sign-in; App shell (sidebar, header); Clinic setup → Clinic profile, Team and access, Fees and billing; Setup wizard and its progress screen (`pBuilding`)
Modules: **platform** (setup, guard, logo, team, doctor capability, menu), **patient** (mobile lookup), **opd** (second-check count, owner-as-doctor checks, registration number gate). **billing**: no behaviour change (description note only).
Status: PROPOSED (22 Nov 2026), awaiting `SAH-89 · contract · APPROVED`
Source: DEC-096 (human, 22 Nov 2026: every recommended option; BA defaults for OQ-89-1 A, 3, 4, 6, 7 A, 11)
Builds on: ADR-002 (tenancy, RLS, audit), ADR-010/ADR-014 (staff data in audit), ADR-019 D8 (`clinic.consultation.write`), ADR-023 D6, D13.5 (chemotherapy second check), ADR-024 D3 (data minimisation per role)
Contracts: `docs/api/platform.yaml` 0.2.0 → **0.3.0**, `docs/api/patient.yaml` 0.2.2 → **0.3.0**, `docs/api/opd.yaml` 0.7.3 → **0.8.0**, `docs/api/billing.yaml` 0.4.1 → **0.4.2** (description only)

---

## Context

The nine SAH-89 items touch five cross-cutting points that need one recorded decision each:

1. **No feature before setup ends (rule 2).** Today only the Angular route guard sends a `next=SETUP` user to the wizard. A clinic API called directly by a mid-setup owner is not refused by the server (read in code: no filter checks `platform.setup_progress.completed_at`; `setup-incomplete` is only answered by `POST /setup/complete`).
2. **Real progress on the completion screen (rule 15).** `SetupService.complete` writes everything in one transaction, so there is nothing to tick one by one, and a failure gives no step.
3. **Logo storage (OQ-89-5 A).** No file storage exists in phase 1 (documents service is "Later"). An uploaded image is untrusted; SVG can carry script.
4. **Owner as doctor (OQ-89-10 A).** "OWNER with doctor details" is checked in many places by `TeamMemberPort.doctorDetails(...).isPresent()` (opd `EmrAccess`, `ChemoCheckService`, `ChemoOrderService`, `TreatmentCourseService`, `EmrDoctorContextService`, `EmrDepartmentResolver`, `EmrDepartmentService`, `DoctorSlotSettingsService`, `TodaySummaryService`, ipd `AdmissionService`, pharmacy `ConsumptionService`, platform `NavigationPolicy`). `TeamMemberPort.findActiveDoctors` returns only DOCTOR and VISITING_DOCTOR, so the owner never appears in doctor lists. `TeamService.toView` sends `doctor` only for doctor roles, so the owner's own fee cannot be seen or edited in Team and access or Fees and billing. There is no switch to turn the doctor part off, and doctor rows are never deleted (history).
5. **Header search by mobile (OQ-89-7 A).** The existing patient search `GET /patients?q=` puts the typed text, possibly a mobile number, in the query string. The header search must never put the number in a URL (rule 19).

The Google Maps question (OQ-89-2) was answered B: no Google call in phase 1. It is recorded here (D4) so the content security policy and the data flow stay unchanged.

---

## Decisions

### D1 · The server refuses clinic APIs until setup is complete (setup guard)

- A servlet filter in `com.sahaj.platform.infrastructure.security` (after authentication and tenant binding, before any controller) checks whether the signed-in user's clinic has finished setup (`platform.setup_progress.completed_at is not null`).
- When it has not, every request under `/api/v1/**` answers **403, problem type `setup-required`**, title "Finish setting up your clinic", detail "Finish setting up your clinic first.", extension `currentStep` (1 to 5). No clinic data, no counts, nothing written.
- **Allow-list while setup is open** (exact paths, all methods listed in the contract): `/api/v1/platform/health`, `/api/v1/platform/public/**`, `/api/v1/platform/auth/**`, `/api/v1/platform/setup/**`, `GET /api/v1/platform/me`, `PATCH /api/v1/platform/me/preferences`, `GET /api/v1/platform/specialties`, `GET|PUT|DELETE /api/v1/platform/clinic/logo`. The Sahaj admin portal (`/api/v1/admin/**`) has its own authentication and is not affected.
- Performance: once a clinic is complete it never goes back, so the filter caches "complete" per tenant id in memory (bounded, e.g. 20,000 entries). "Not complete" is never cached; it is read each time (one primary-key read under RLS).
- Staff logins created during completion (D2 step 5) cannot be used before step 6 commits: for a non-owner membership of a clinic whose setup is not complete, `POST /auth/otp/verify` and `/auth/select-clinic` leave that clinic out; if it is the only one, the answer is `next: NOT_REGISTERED` as for any unknown number. In practice the gap is seconds.
- The client keeps its route guard; on any `setup-required` answer the HTTP interceptor opens `/setup` at `currentStep`.

Options: A · client guard only (today): rejected, rule 2 says "every other screen and API". B · check in every controller: rejected, many modules, easy to miss one. **C · one filter with an allow-list (chosen).**

### D2 · Setup completion runs in six committed, repeatable steps and reports real progress

`POST /setup/complete` runs six steps **in order, each in its own transaction, each safe to repeat**. A step that is already DONE is skipped. The clinic opens (the D1 guard lets features through) only when step 6 commits.

| # | key | Line on screen | Work |
|---|---|---|---|
| 1 | `CLINIC_SPACE` | Creating your clinic space | validate all five steps again; claim the web address and brand on `platform.tenant`; upsert `platform.clinic_profile` (structured address, brand, brand hex) |
| 2 | `TEMPLATE` | Loading the <specialty> template | seed the specialty's starter services (idempotent: nothing added when already seeded) |
| 3 | `OPD_RULES` | Setting up your OPD workflow and fee rules | owner and second-doctor `doctor_profile` rows with fees; `OpdDefaultsPort.saveDefaults` (upsert) |
| 4 | `PAPERS` | Preparing case paper and bill layouts | make sure the clinic's case paper and bill layout defaults exist (an idempotent "ensure defaults" through an opd.api port; if the defaults are read-time only, the step confirms they can be read) |
| 5 | `STAFF_LOGINS` | Setting up staff logins | staff and second-doctor users and memberships (insert when absent, matched by mobile) |
| 6 | `WEB_ADDRESS` | Securing https://<slug>.<domain> | final check that the web address is still this clinic's; set `completed_at`; audit `SETUP_COMPLETED` |

- Line 5 text is "Setting up staff logins" (OQ-89-6 a default): no WhatsApp in phase 1.
- Step state is stored per tenant in `platform.setup_completion_step` (see Data needed). States: `PENDING`, `RUNNING`, `DONE`, `FAILED`.
- **Read the progress:** `GET /setup/completion` (owner only). The client starts `POST /setup/complete` and, while it waits, reads `GET /setup/completion` about once a second to tick the lines as they become DONE. Every line shows for at least 4 seconds in total (OQ-89-6 b default), done on the client.
- **Failure:** the failing step is marked FAILED with a fixed error code (never a stack trace or a value), its transaction is rolled back, earlier DONE steps stay DONE (they really are done) and the clinic stays closed. The answer is **500-free**: 409 `setup-step-failed` with `failedStep`, or 409 `slug-taken` (step 1), 409 `plan-limit` (step 1), 400 `validation` with `step` and `fieldErrors` (step 1 re-validation), or 503 `database-busy`. "Try again" calls `POST /setup/complete` again and continues from the failed step.
- **Two calls at once** (two tabs, a retry after a lost answer): the first sets the run's `RUNNING` marker under a row lock on `platform.setup_progress`; a second call while a run is less than 2 minutes old answers 409 `setup-running` (retryable; the client keeps reading `GET /setup/completion`). A run older than 2 minutes is treated as stopped and may be resumed.
- Reload or a new sign-in during completion: `AuthResult.next` stays `SETUP`; the client reads `GET /setup/completion` and, when any step is not PENDING, shows the progress screen, not the wizard.

Options: A · one transaction plus client timer (today and mockup): rejected, a tick would not mean the work is done (rule 15). B · asynchronous job with 202 and polling: rejected for phase 1, no job runner exists and the work takes seconds. **C · synchronous call with committed steps and a progress read (chosen).**

Consequence: a clinic can be partly built (steps 1 to 4 done, 5 failed). That is safe because the guard keeps it closed, and every step is repeatable; staff cannot sign in to it (D1).

### D3 · "Create a clinic with this number" from Log in (OQ-89-1 A)

- `POST /auth/otp/verify` in mode LOGIN with a number that has no membership still answers `next: NOT_REGISTERED`, and now also `signupToken` (opaque, 32 random bytes, base64url) and `signupTokenExpiresInSeconds: 600`.
- `POST /auth/signup-continue` with `{ signupToken, consent }` creates the user, clinic, owner membership and Free plan exactly as `SIGNUP` verify does, and answers `next: SETUP` with a session. No second code.
- The token is single-use, bound to the verified mobile and challenge, valid 10 minutes, stored only as a SHA-256 hash on `platform.otp_challenge` (no new table, same no-tenant, no-RLS rule as the challenge). Wrong, expired or used token: 401 `signup-token-invalid` "This link has expired. Verify your number again.". Rate limit: the OTP limits of SAH-3 apply to the mobile; plus 10 continue calls per IP per 10 minutes, 429 `rate-limit`.
- A number that got a membership between verify and continue: answered as SIGNUP verify does today ("This number already has a clinic. Logging you in.", `next: APP` or `CHOOSE_CLINIC`); no second clinic.

### D4 · Address: typed and structured, no Google call in phase 1 (OQ-89-2 B)

- The clinic address is four required parts: `address` (line: building, road, area; 5 to 200), `city` (2 to 80), `state` (one of the 36 states and union territories, by name), `pin` (6 digits, not starting 0). `area` (optional, existing) stays.
- `mapsUrl` stays optional on Clinic profile only (not in the wizard; the mockup has it only there). It must be `https://` and the host one of `maps.app.goo.gl`, `goo.gl` (path starting `/maps`), `maps.google.com`, `www.google.com` or `google.com` (path starting `/maps`), `www.google.co.in` or `google.co.in` (path starting `/maps`), `maps.google.co.in`; at most 500 characters. The server never fetches it; the client opens it in a new tab with `rel="noopener noreferrer"`.
- No latitude or longitude is stored. No script, font, tile or API from Google is loaded; the content security policy is unchanged.
- Clinics saved before SAH-89 have `state` null. `GET /clinic/profile` returns `addressNeedsReview: true` for them; `PUT /clinic/profile` requires `state`, so the first save asks the owner to complete it. The old address text is kept as the line; nothing is split or lost by a migration.
- Google Places (option A) needs a new ADR (key handling, cost cap, data leaving India, fallback, CSP) and a cost approval before any story uses it.

### D5 · Clinic logo: one PNG per clinic in the platform schema, re-encoded on the server, served only to signed-in users; SVG not accepted

- **Storage:** `platform.clinic_logo`, one row per tenant, `bytea` content, RLS on `tenant_id`. Not a file system, not object storage; when the documents service exists the row can be moved there behind the same endpoints.
- **What the browser sends:** like the mockup's `logoLoad`, the client checks the chosen file (type PNG, JPG or WEBP; at most 2 MB), draws it on a canvas, scales the longest side down to 1024 px (never up) and uploads a **PNG**. If that PNG is over 2 MB the client scales to 512 px (at most about 1 MB). This makes WEBP work without a WebP decoder on the server.
- **What the server accepts:** `PUT /clinic/logo`, `multipart/form-data`, one part `file`, at most 2 MB (2,097,152 bytes). Content type is decided by the **bytes** (PNG signature `89 50 4E 47 0D 0A 1A 0A`, JPEG `FF D8 FF`), never by the file name or the declared part type. Anything else, including SVG, GIF, WEBP bytes and HTML, answers 400 `logo-type-not-allowed`. Width and height are read from the header **before** decoding and must each be 16 to 4,096 px (400 `logo-dimensions`), so a decompression bomb is refused without decoding. Then the image is decoded with the JDK's `ImageIO` (400 `logo-unreadable` when it fails) and **re-encoded as PNG** (longest side at most 1024 px, alpha kept). Re-encoding drops every metadata chunk (EXIF, GPS, text chunks, ICC) and any bytes appended to the file, so what is stored and served is a clean image. At most 2 decodes run at the same time per application instance; a third waits up to 5 seconds and then answers 429 `rate-limit` "Too many uploads at once. Try again." with `retryAfterSeconds` 5.
- **SVG is not accepted in phase 1** (DEC-096 "SVG only if sanitised, else dropped"). No vetted SVG sanitiser exists in our Java stack; the candidates (Batik, a hand-written allow-list) have a history of XXE and script bypasses, and serving SVG safely needs its own isolated origin. The texts change from the mockup's "Use a PNG, SVG, JPG or WEBP file for the logo" to **"Use a PNG, JPG or WEBP file for the logo."** (mockup difference: a human adds it to `docs/design`).
- **Serving:** `GET /clinic/logo` with the bearer token (any active member of the clinic, including Store staff, because the sidebar shows the logo). Always `Content-Type: image/png`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="clinic-logo.png"`, `Content-Security-Policy: default-src 'none'; sandbox`, `Cache-Control: private, no-cache`, `ETag` = the stored SHA-256; `If-None-Match` answers 304. The client reads it with `HttpClient` as a blob and shows it through an object URL (an `<img src>` cannot send the bearer token). No public or unauthenticated URL exists (booking page and print are out of scope).
- **Who writes:** OWNER only (re-read membership), also during setup (D1 allow-list). `DELETE /clinic/logo` removes the row (Remove brings back the initials). Audit `PLATFORM_CLINIC_LOGO_SET` (size, width, height, SHA-256, source type PNG or JPEG) and `PLATFORM_CLINIC_LOGO_REMOVED`; never image bytes. 20 uploads per clinic per hour, 429 `rate-limit`.
- The request-size filter (64 KB) gets one path exception: `PUT /api/v1/platform/clinic/logo` may carry up to 2 MB + 64 KB; above that it answers 413 `payload-too-large` "Keep the logo under 2 MB.".
- "From your logo" colour picks are worked out in the browser from the shown image, as in the mockup; the server stores no colours.

Options: A · **bytea in platform, re-encode (chosen)**; B · wait for the documents service (rejected by DEC-096); C · store the original bytes and serve them (rejected: polyglot files, metadata with GPS, SVG script).

### D6 · Custom colour in the wizard

- `SetupLook` gains `brandHex`. With `brand: custom` it is required, 6 hexadecimal characters with or without `#`; the server stores `#` plus upper case (`#1F5BC8`). With a preset it must be absent or null (a sent value is ignored, never stored). Step 1 of D2 copies it to `clinic_profile.brand_hex`; `POST /setup/complete` and `GET /me` return it.
- Readability (4.5 : 1 button text, darker shade for text on white, lighter shade in dark mode) is worked out in the browser and **warns, does not block** (OQ-89-4 default). The server checks only the format.

### D7 · Owner as doctor: one membership, one switch, one capability rule for every module

- **One membership** (OQ-89-10 A). The owner's doctor part is the existing `platform.doctor_profile` row of their OWNER membership, plus a new flag `sees_patients` (true by default). "Also a doctor" on = a row with `sees_patients = true`; off = the row stays (history, past prints keep the name and registration number) with `sees_patients = false`. DOCTOR and VISITING_DOCTOR rows are always `sees_patients = true`.
- **One rule in `platform.api`.** `TeamMemberPort` gains `boolean actsAsDoctor(UUID tenantId, UUID membershipId)`: true for an ACTIVE membership that is DOCTOR or VISITING_DOCTOR, or OWNER with a doctor profile whose `sees_patients` is true. `TeamMemberPort.DoctorDetails` gains `seesPatients`. `findActiveDoctors` returns DOCTOR, VISITING_DOCTOR **and** OWNER members who act as doctor (owners after the doctors, by name). **Every** call site listed in Context item 4 that today means "OWNER with doctor details" must use `actsAsDoctor` (or `findActiveDoctors`), not `doctorDetails(...).isPresent()`. `doctorDetails` keeps returning switched-off rows for printing history only.
- **Doctor functions.** An owner who acts as doctor has every function the DOCTOR role has now (OQ-89-10 d default): doctor lists (token, appointments, Queue, Fees and billing, department doctor options, doctor slots), own Queue (`/visits/today?mine=true`), Doctor App consultation, EMR write and sign (`clinic.consultation.write`), prescriptions, chemo orders and second checks as a checker, IPD doctor actions wherever ipd checks a doctor. The owner functions stay. `Me.menu` has each key once.
- **Registration number before consulting** (OQ-89-10 b default; legal confirmation still asked). For an OWNER acting as doctor with no registration number, the call that starts a consultation (`POST /api/v1/opd/emr/records/open`) and the sign (`POST …/sign`) answer **403 `registration-number-missing`** "Add your registration number before you see patients.". Reading records is not blocked. DOCTOR and VISITING_DOCTOR are not changed by this rule (today they print "Reg. no. not set"); extending it is an open question for the human.
- **Never second checker of own order** (OQ-89-10 c): unchanged and already enforced by `checker-is-prescriber` and the named-checker rule in opd (ADR-023 D6); it keys on the membership, so it covers the owner.
- **Switch:** `PUT /api/v1/platform/me/doctor` (own OWNER membership only; any other role 403). Turning on counts against the plan's doctor limit (D8). Turning off: the owner leaves the doctor lists at once; visits and appointments already assigned stay assigned (the front desk reassigns them, as for a doctor switched off today); past records keep the owner's name. Audit `PLATFORM_OWNER_DOCTOR_ON` / `PLATFORM_OWNER_DOCTOR_OFF` (membership id only).
- **One-time notice** (OQ-89-10 f): `doctor_profile.doctor_notice_seen_at`; null on an existing owner-doctor row shows "You can now see patients as a doctor" once (`Me.capabilities.ownerDoctorNotice`); `POST /me/doctor/notice-seen` sets it.
- **Owner's fee and details** are edited through `PUT /me/doctor` (Team and access, own row) and, for the fee only, `PATCH /team/{id}` on the owner's own row (Fees and billing). Another owner cannot change them (403).

Options: A · **one membership with a flag (chosen)**; B · second DOCTOR membership for the same user (rejected by DEC-096: two logins, double counting, tenant-scoped uniqueness of user per clinic breaks).

### D8 · One doctor fee, required on Add person, audited; billing unchanged

- The one fee is `platform.doctor_profile.fee_paise` (integer paise; API keeps whole rupees in `feeRupees`, as today). It is the "New visit fee" of Fees and billing and the "Consultation fee (₹)" of Add person and the wizard.
- Limits: whole rupees **0 to 50,000**; 0 is shown as "Free". Required on `POST /team` for DOCTOR and VISITING_DOCTOR and on `PUT /me/doctor` when switching on.
- **Plan doctor limit** (2 on Free and Professional, `PlanLimits.MAX_DOCTORS`) is now checked on the server: active DOCTOR + active VISITING_DOCTOR + active OWNER acting as doctor. Checked on `POST /team` with a doctor role, `PATCH /team/{id}` changing a role to a doctor role, `POST /team/{id}/reactivate` of a doctor, and `PUT /me/doctor` turning on: 409 `plan-limit` "Your plan allows 2 doctors.". Clinics already above the limit are not changed.
- Every fee change writes `PLATFORM_DOCTOR_FEE_CHANGED` (membership id, `beforePaise`, `afterPaise`; no names) in addition to the existing `TEAM_MEMBER_UPDATED`.
- Old bills keep their amount because opd copies the fee into `opd.visit.fee_paise` when the token is issued and billing derives the consultation line from the visit (existing). New tokens use the new fee. Follow-up rule, tariff and payer prices: unchanged.

### D9 · Header mobile lookup: POST body in the patient module, never in a URL

- `POST /api/v1/patient/patients/lookup-by-mobile`, body `{ "mobile": "9822000001" }` (exactly `^[6-9][0-9]{9}$`; the client removes spaces, `+91`, a leading 0). Answer `{ outcome: NONE | ONE | MANY, total, patients[] }` (at most 20 `PatientSummary` rows, newest registration first). `Cache-Control: no-store`.
- Roles: OWNER, FRONT_DESK, NURSE, DOCTOR, VISITING_DOCTOR (the roles that have the Patients menu **and** may read patients). COUNSELLOR has the Patients menu item but may not read patients (DEC-049), BILLING and PHARMACIST have no Patients menu: all 403. `Me.capabilities.headerMobileSearch` tells the shell whether to draw the field; platform computes it from the same role list (static, no call to patient).
- The lookup writes no record-access row (it is a list, like `GET /patients`); opening the record (`GET /patients/{id}`) is logged as today. Rate limit 30 lookups per user per minute, 429 `rate-limit`. The mobile is never logged, never in a problem detail, never in an audit value.
- Client: ONE opens `/app/patients/<id>`; MANY opens the Patients list in a "family on this number" mode fed from memory (router state, not a query parameter); NONE shows "No patient with this number" and "Register patient", which opens registration with the number held in memory.
- Another clinic's patient is never returned (RLS); the answer is the same as "no patient".

### D10 · "Second checks" leaves the sidebar; Queue shows the count

- `Me.menu` no longer contains `secondchecks` for any role (platform 0.3.0). `Me.capabilities.secondChecks` is true for NURSE, PHARMACIST, DOCTOR, VISITING_DOCTOR and an OWNER acting as doctor (the same rule as the opd endpoints).
- New `GET /api/v1/opd/emr/chemo-checks/count` answers `{ waiting }` with the same roles and the same filter as `GET /emr/chemo-checks` (orders in DRAFT that name the caller as checker, not confirmed). The Queue shows "N second checks waiting" (banner or tab) opening the existing list; "No second checks waiting for you." when 0. The list, confirm, PIN and `checker-is-prescriber` rules are unchanged.
- The route `/app/emr/second-checks` stays for the allowed roles (bookmarks) and is reached from the Queue banner.

### D11 · Shared validation limits

The numbers in the contract schemas are the single source for the screen and the server (SAH-89-2). Every 400 is `validation` with `fieldErrors` whose `message` is the inline text; field paths use dots and brackets (`doctors[1].feeRupees`, `staff[0].mobile`). `POST /setup/complete` re-validates the five steps and adds the extension `step` to the 400 so the client returns to that step.

---

## Consequences

- Positive: rule 2 holds on the server; completion ticks are true; the logo is stored and served without a new service and without trusting the upload; the owner-doctor rule is one function instead of twelve copies; no mobile number reaches a URL from the header.
- Negative: a clinic can be partly built (closed by the guard, resumable). One more filter on every request (cached after completion). `bytea` up to about 2 MB per clinic in the main database (10,000 clinics × 2 MB worst case = 20 GB; typical logos are far smaller); the documents service will take it over later. Removing the menu key changes `Me.menu` (breaking for any client that expects `secondchecks`; only our Angular app reads it).
- Debt: `GET /patients?q=` can still carry a mobile number in the query string (SAH-8). Recommended follow-up: move the Patients list search to a POST body as well.
- Debt: whether the registration-number gate applies to DOCTOR and VISITING_DOCTOR too is a human decision.

## Hand-offs

- db-engineer: Data needed in the contract hand-off (tables, columns, RLS, grants, retention).
- java-developer: D1 filter, D2 steps, D3 token, D5 image pipeline, D7 port change and every call site, D8 plan check and audit, D9 endpoint, D10 count and menu.
- angular-developer: wizard inline validation and state field, custom colour and logo in step 5, progress screen, header search, Queue banner, Team and access fee and "Also a doctor", notices.
- security-reviewer: D1 allow-list, D3 token, D5 pipeline and headers, D9 rate limit and logs.
