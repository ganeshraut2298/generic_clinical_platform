# ADR-021 · WhatsApp sending, ABHA / ABDM sharing and the case-paper PDF in phase 1

Stories: SAH-68-4 (ABHA link, consent, share), SAH-68-5 (WhatsApp sending), SAH-68-6 (ABDM OP consultation record), and the
"Waits for DEC-040" criteria in SAH-55-3, SAH-48-3, SAH-56-2, SAH-58-5, SAH-59-4, SAH-47-1, SAH-45-6 and the outlines SAH-51-1,
SAH-62-2, SAH-63-2, SAH-66-1 (list: `docs/requirements/SAH-45.md`, "What waits for DEC-040").
Mockup screens: Clinic setup → WhatsApp messages (`cfgWa`, marked Later); Clinic setup → Privacy, ABDM and backup (`cfgData`,
ABDM part marked Later); EMR mockup #CasePaper ("Share · WhatsApp / ABHA"); Billing → Receipt ("Sent on WhatsApp"); Follow-ups.
Module: **not decided** (this ADR gives the options; the human decides, D1).
Status: **PROPOSED (12 Nov 2026). Options only. Nothing here is decided until the human answers DEC-040 and edits CLAUDE.md.**

> **Status note (13 Nov 2026, DEC-049 answer 1, DEC-040 update): ACCEPTED as the design for a later story; WhatsApp sending
> and ABDM sharing are moved back to Later.** DEC-040 (12 Nov 2026) accepted H1-H16 below; DEC-049 then moved WhatsApp
> sending and ABDM sharing back to "Later", as a new story for when the Meta WhatsApp Business account, the ABDM sandbox
> registration and the legal opinion exist. Until then: CLAUDE.md keeps six modules and its "Not in phase 1" line
> unchanged; the modules `notifications` and `integration`, their schemas, the consent kinds `WHATSAPP_DOCUMENTS` and
> `ABDM_LINK`, ABHA number storage, the outbox and dispatcher, the PDF renderer and the `.env.example` variables (D2/D3)
> are not built; the screen-map rows stay Later (H14 is not applied now). The in-app parts already in the EMR
> (ADR-025 D16: the sign body's share flag, `share.available: false` with its reasons, advice sendBy other than PRINT)
> stay built and switched off, answering 400 `needs-whatsapp-service`. No behaviour change. Marathi/Hindi labels
> (DEC-039 answer 4) stay in. Wherever this ADR says "in phase 1" for WhatsApp, ABDM or the PDF, read "in the later
> story".
Deciders: human (product owner); recorded by solution-architect.
Related: DEC-036 (answer 2: WhatsApp and ABHA were Later), DEC-039 (answer 4: include now), DEC-040 (open), ADR-002 (D2 tenant
binding, D4 threads other than the request thread, D5 `OtpSender` port pattern, D7 audit port), BR45-5 and BR45-29 in
`docs/requirements/SAH-45.md`.

---

## Context

DEC-039 answer 4 brings WhatsApp sending, ABHA / ABDM sharing and (by consequence) a server-made PDF case paper into the EMR
programme. CLAUDE.md, which only the human edits, says today:

> Modules with hard boundaries: `platform`, `patient`, `opd`, `ipd`, `hra`, `billing`.
> **Not in phase 1:** integration gateway (ABDM, HL7, payments), notifications (WhatsApp/SMS), documents service, analytics.

So these stories cannot be built on the current rules. They need a home (a module), external accounts (a WhatsApp provider and
ABDM registration), credentials (environment variables, human-owned `.env.example`), a PDF library (a new dependency), and
consent rules beyond what is built.

### What already exists (checked 12 Nov 2026)

| Need | Exists | Where |
| --- | --- | --- |
| Patient consent | `patient.patient_consent`, append-only, kinds `RECORDS`, `PROMOTIONAL`; latest row per kind is current | SAH-17, `V2026_11_05_1000__privacy_and_consent.sql`, `patient.application.ConsentService` |
| Clinic privacy switches | `platform.privacy_settings`: `consent_required`, `promo_consent`, `record_copy`, `hfr_id` (≤ 32 chars) | SAH-17, `platform.application.PrivacyService` |
| Patient message language, ABHA flag | `patient.patient.language` (`mr`, `hi`, `en`, default `mr`), `patient.patient.abha_linked boolean` (no ABHA number column) | SAH-8 migration `V2026_11_01_1039` |
| Clinic default language | `platform.clinic_profile.default_language` (`en`, `mr`, `hi`) | SAH-5 |
| Case paper print settings | `opd.case_paper_settings` (size, second language) | SAH-7 |
| A port for an outbound channel | `platform.application.OtpSender` with only a dev/test logging implementation; no bean in other profiles, so production fails closed | ADR-002 D5 |
| Audit | `platform.api.AuditPort`, `access_audit_event` actions `VIEW … EXPORT` (no `SHARE`) | ADR-002 D7 |

### What the mockup draws (the design authority)

- **Clinic setup → WhatsApp messages** (`cfgWa`): eight message kinds with an on/off switch and a monthly estimate each: Token
  confirmation, Appointment reminder, Receipt after payment ("with the bill as a PDF"), Follow-up reminder, Missed follow-up
  nudge, Doctor delayed or on leave, Case paper copy ("PDF after the consultation"), Birthday greeting ("Promotional · only to
  patients who agreed"). A preview in English, मराठी, हिंदी; "Language for new patients"; "Each patient gets messages in the
  language chosen at registration". "This month": *n* sent of 1,000 included, resets on the 1st; over the allowance "messages open
  in the clinic's own WhatsApp to send by hand". "WhatsApp approves message templates before they are used. A later version will
  let you change the wording." The preview header shows the **clinic's** name as the business account; Clinic profile has a
  "WhatsApp number" field; booking rules say "From the clinic's WhatsApp number".
- **Privacy, ABDM and backup** (`cfgData`): the consent line read at the first visit, "I agree that <clinic> may keep my health
  records to treat me **and send me visit reminders on WhatsApp**" (English and Marathi); a separate yes for promotional
  messages; "Patients can ask for a copy of their records · Sent on WhatsApp after an OTP to the patient's mobile". ABDM panel:
  HFR ID, an HPR ID per doctor, "Scan and Share at reception · After linking", "ABDM lets patients share past records with your
  clinic using their ABHA number".
- **EMR case paper**: "Share · WhatsApp / ABHA", footer "saved as an OP consultation record and can be shared to the patient's
  ABHA".
- Template texts carry the patient's first name ("Namaste Sunita ji"), token numbers, amounts, dates and the doctor's name; none
  carries a diagnosis. Clinical content travels only as the PDF attachment (receipt, case paper).

Not covered by DEC-040 but drawn in the mockup (listed so nobody builds them by accident): inbound replies ("Reply 1 to confirm
or 2 to cancel"), booking on WhatsApp, SMS reminders, "Send WhatsApp reminder" as a rule action, owner OTP for support access.

---

## D1 · Module placement (human decides)

### The dependency problem that decides the shape

- Sends are requested by `opd`, `billing`, `ipd` and later `patient` (record copy). Whatever sends must be callable by them.
- An ABDM health-information request arrives **days after** the visit, from the ABDM gateway, and must pull the visit from `opd`
  (and the patient from `patient`). Whatever answers ABDM must depend on `opd.api`.
- Spring Modulith fails the build on a dependency cycle between modules. A single module that `opd` calls (to send) and that also
  calls `opd` (to build the FHIR record) is a cycle unless one direction is turned into events.
- `platform` is the foundation every module depends on. It cannot call `opd` or `patient` without a cycle.

### Options

| | Option | Boundaries | For | Against |
| --- | --- | --- | --- | --- |
| A | **One new module `integration`** (WhatsApp, ABDM) | `integration` depends on `platform.api`, `patient.api`, `opd.api`, `billing.api`. Callers never depend on it: they publish domain events (`CasePaperShareRequested`, `ReceiptSendRequested`, `VisitSigned`), and `integration` listens. | One schema, one package, one CLAUDE.md line. Matches the "integration gateway" wording. | Every send is an after-commit async event, so "queued" is not atomic with the caller's action unless the Spring Modulith event publication registry is used (another table and a replay story, the same cost ADR-002 D7 rejected for audit). Preview and consent check before send need a synchronous answer, which an event cannot give, so callers still need a sync port, which brings the cycle back. |
| **B** | **Two new modules: `notifications` (WhatsApp, later SMS) and `integration` (ABDM, later HL7, payments)** | `notifications` is a **leaf**: it depends on `platform.api` and `patient.api` only. `opd`, `billing`, `ipd` call `notifications.api` synchronously to preview and enqueue, inside their own transaction (the outbox row commits with the action). `integration` is a **top** module: it depends on `platform.api`, `patient.api`, `opd.api`; nothing depends on it; it reacts to `opd` events (`VisitSigned`) and to ABDM callbacks. `patient` (below `notifications`) asks for a send by event only. | No cycle by construction. Matches the screen map's own words ("notifications service", "integration gateway"). Enqueue is atomic with the action. Each module has one reason to change and its own credentials. | Two schemas, two packages, two schema creations in `scripts/setup/create-database.mjs` (human-owned), more CLAUDE.md lines. |
| C | Inside `platform` (`com.sahaj.platform.messaging`, `…abdm`) | Callers use `platform.api`. | No CLAUDE.md module change. | ABDM cannot read `opd` from `platform` (cycle). WhatsApp cannot resolve the patient's mobile or consent from `patient` (cycle), so callers would pass mobile numbers into the `platform` schema, putting patient data in the foundation module. Mixes clinical-data egress with tenancy and identity code. Not viable for ABDM; weak for WhatsApp. |
| D | Inside each caller (`opd` sends its own, `billing` its own) | none new | No new module. | Provider credentials, templates, allowance, retry and webhook duplicated in four modules; one allowance per clinic cannot be counted. Rejected. |

**Recommendation: B.** Name them `notifications` and `integration`, as the screen map already does.

### The PDF renderer's home (separate from D1)

The PDF is made by the module that owns the content (case paper: `opd`; receipt: `billing`), from that module's own HTML
template. The renderer itself is a stateless technical capability (HTML and fonts in, bytes out, nothing stored, no patient
semantics). Options: (a) `platform.api.PdfRenderer`, usable by every module without a cycle; (b) inside `notifications` (then
`integration`, which also needs a PDF for the ABDM bundle, depends on `notifications`: allowed, no cycle); (c) a new `documents`
module (CLAUDE.md lists the documents service as not in phase 1). **Recommendation: (a)**, and keep "documents service (storage,
upload)" out of phase 1. `notifications` gets the bytes at dispatch time through an attachment port it defines in
`notifications.api` (`AttachmentSource`) and that `opd` and `billing` implement (dependency inversion: the dependency stays
`opd → notifications`).

### Rules that hold whichever option is chosen

1. **Outbox, not a direct call.** A user action writes one outbound message row in the caller's transaction (option B) and
   returns `202`. A dispatcher sends later. The provider is never called inside a request transaction.
2. **Tenant scoping.** Every message, consent artefact and link row has `tenant_id` with RLS and `FORCE`. The dispatcher runs off
   the request thread, so (ADR-002 D4) it re-binds `app.tenant_id` per message from the row it claimed, never from ambient state.
   Claiming due work across tenants needs a narrow cross-tenant view: recommended a `dispatch_ticket (message_id, tenant_id,
   due_at, provider_message_id)` table holding **ids only**, readable by a dispatcher actor kind policy, with the full message row
   read only after the tenant is bound. The db-engineer designs it; no `COALESCE` or bypass role.
3. **Inbound callbacks never take the tenant from the body.** WhatsApp status webhooks resolve the tenant from
   `provider_message_id` via the ticket; ABDM callbacks resolve it from the HIP id (the clinic's HFR ID, which must become globally
   unique) through a `platform.api` lookup.
4. **Idempotency.** Each send request carries an `Idempotency-Key` header; the outbox has `unique (tenant_id, idempotency_key)`; a
   repeat returns the first result. The domain key is `<module>:<entity>:<id>:<kind>[:n]` so a double tap on "Share" sends once.
   Providers do not dedupe, so delivery is at-least-once: before calling the provider the row moves to `SENDING`; a timeout with an
   unknown outcome moves it to `UNKNOWN`, reconciled by the status webhook for 15 minutes before a retry. A rare duplicate message is
   the accepted cost.
5. **Retries.** Back-off 1 min, 5 min, 30 min, 2 h, 6 h; then `FAILED`, shown to staff with "Send again". Each kind has a
   time-to-live (for example a token confirmation older than 2 hours is `EXPIRED`, never sent late). Provider errors that cannot
   succeed (invalid number, template paused, opt-out) fail at once without retry.
6. **Switches, fail closed.** Three layers: a global environment switch (`SAHAJ_WHATSAPP_ENABLED`, `SAHAJ_ABDM_ENABLED`, default
   `false`); the clinic's per-kind switch from the mockup; the patient's consent. With the global switch off, a send request is
   stored as `HELD` and the UI shows "Needs the WhatsApp service" (as the stories say). As for `OtpSender` today, no real adapter
   bean exists in `dev`/`test`; a logging adapter masks the mobile to its last 4 digits.
7. **Audit.** The request to send or share is audited synchronously through `AuditPort` in the caller's transaction: who, when,
   which record (`target_module`, `target_entity_type`, `target_entity_id`), channel and kind. **Never** the mobile number, ABHA
   number, template parameters or PDF text. Recommended: a new access action `SHARE` (today's list has none). ABDM-initiated data
   transfers (no staff actor) need a new actor kind, for example `SYSTEM` with `actor_display_name = 'ABDM consent'`.
8. **Logging.** No mobile number, ABHA number, ABHA address, OTP, template parameter, PDF byte or provider request/response body in
   any log, in any profile. HTTP client wire logging is off everywhere. Provider error text is not logged (it can echo the number);
   only the provider error code, message id, tenant id and status are.
9. **Consent is checked twice:** at enqueue (to show "consent missing" before anything is queued) and again at dispatch (a
   withdrawal between the two stops the send, status `BLOCKED_NO_CONSENT`).

### CLAUDE.md lines the human must change (only if they choose A or B)

- "Modules with hard boundaries: `platform`, `patient`, `opd`, `ipd`, `hra`, `billing`" → add `notifications` and `integration`
  (option B) or `integration` (option A).
- "Not in phase 1: integration gateway (ABDM, HL7, payments), notifications (WhatsApp/SMS), documents service, analytics" →
  for example "Not in phase 1: HL7 and payment integrations, SMS, documents storage service, analytics. WhatsApp (module
  `notifications`) and ABDM (module `integration`) are in phase 1, switched off until credentials exist. Server-side PDF
  rendering for sharing is allowed (no document storage)."
- Same change in `.claude/rules/postgresql.md` ("One schema per module (…)"), the schema list in
  `scripts/setup/create-database.mjs` and any module list in `scripts/validate.mjs`, the rows of `docs/design/screen-map.md`
  (WhatsApp messages; Privacy, ABDM and backup) and `.env.example`. All human-owned.

---

## D2 · WhatsApp (human decides provider and sender model)

### Provider options in India

| Option | What it is | Data residency (rule: all patient data stays in India) | Notes |
| --- | --- | --- | --- |
| W1 | **Meta WhatsApp Cloud API, direct** (Sahaj as a Meta Tech Provider) | Meta hosts the API. Meta offers a Cloud API **local storage** setting that keeps message data at rest in a chosen country, India among them. Processing and transit are not guaranteed to stay in India. **Unknown: whether that meets Sahaj's rule; must be confirmed in writing with Meta.** | Lowest cost, no middleman holding content, embedded signup for per-clinic numbers. Support is self-serve; billing by Meta. The older On-Premises API is retired; it is not an option. |
| W2 | **Indian BSP on top of the Cloud API** (for example Gupshup, Karix / Tanla, Route Mobile, Tata Communications Kaleyra; small-clinic oriented: Interakt, AiSensy, WATI) | Most BSPs relay to Meta's Cloud API, so W1's limits still apply, **plus** the BSP stores logs and media on its own infrastructure. Several say they host in India. **Unknown per vendor until a signed data processing agreement names the region.** | INR billing with GST invoices, local support, onboarding help, template tooling. One more processor of patient data. Vendor lock-in limited by our adapter port. |
| W3 | **No provider: click-to-chat link** (`wa.me/<number>?text=…`) opened on the clinic's own phone or WhatsApp Web | No data leaves Sahaj's servers; the text goes from the clinic's device. The clinic's WhatsApp account is the processor. | This is the mockup's over-allowance fallback ("messages open in the clinic's own WhatsApp to send by hand"). Text only, no PDF attachment, no delivery status, a person must tap Send. Needs no credentials. |

**No WhatsApp option keeps the message itself inside India end to end:** WhatsApp is a global service and the message ends on
the patient's phone. The human must decide what "all patient data stays in India" means for WhatsApp (H5).

### Sender model

- **S1 · One Sahaj number** for every clinic; the clinic's name is a template parameter. One Meta business verification, one
  template set. The mockup's header ("<clinic> · Business account") and "the clinic's WhatsApp number" are not met.
- **S2 · Each clinic's own number** (clinic-owned WhatsApp Business Account connected through Sahaj's Tech Provider app or a BSP's
  embedded signup). Matches the mockup. Per clinic: Meta business verification, display-name approval, template copies created
  in each account by our code, per-account messaging limits. At 10,000 clinics, onboarding is the cost.
- Design either way: the sender (phone number id, account id) is **per-tenant data**, not an environment variable; only the app
  credentials are environment variables.

### Templates and approval

- Business-initiated messages must use pre-approved templates (category Utility for token, appointment, receipt, follow-up,
  missed, delay, case paper, advice, vaccine, exercise, chemo calendar, form link; Marketing for birthday). Meta can re-categorise
  a Utility template as Marketing and can pause a template with poor feedback; the adapter must handle "template paused".
- One template per kind per language (`en`, `mr`, `hi`; all three are supported template languages). Wording is fixed by Sahaj
  (mockup: "A later version will let you change the wording"), stored as a global catalogue with versions; clinics only switch
  kinds on or off.
- PDF attachments go as a document header; the provider uploads the media and holds it for its retention period.
- The template text is the patient-facing wording and is mockup-driven; any EMR kind not in `cfgWa` (advice, vaccine, exercise,
  chemo calendar, form link, prescription) needs its wording drafted by the business analyst from the EMR mockup and approved by
  the human, then by Meta.

### Opt-in and consent

- Meta requires opt-in for business-initiated messages that names the business and WhatsApp. The mockup's first-visit line
  ("… and send me visit reminders on WhatsApp") is such an opt-in for **reminders**; `PROMOTIONAL` covers birthday greetings.
- Gaps the mockup does not answer: (1) does "visit reminders" cover sending the **case paper, prescription and receipt PDFs**
  (clinical documents)? (2) may sends happen when the clinic has switched "Ask for consent at the first visit" off (the mockup
  only warns it is "harder to defend")? (3) a patient's "STOP" reply. See H6.
- Consent stays in `patient.patient_consent` (append-only). New kinds, if the human agrees: `WHATSAPP_DOCUMENTS`, `ABDM_LINK`.
  `notifications` reads current consent through `patient.api` at enqueue and at dispatch; it never stores its own copy.

### Message content rules

- Template parameters may hold the first name, token, date, time, amount, bill number, clinic and doctor name. **Never** a
  diagnosis, medicine, complaint or note in the template body: clinical content only in the PDF.
- Parameters are stored in the outbox (tenant table, RLS) because the dispatcher cannot call back into the caller module; they are
  purged (set to null) 30 days after a final status; the row keeps kind, status and times.
- The mobile number is not stored in `notifications`; it is read from `patient.api` at dispatch, so a corrected number or a
  withdrawal applies.
- Allowance: the mockup's 1,000 included messages a month is plan data (`platform`, admin portal plans). When used up, the send
  becomes a W3 click-to-chat link for text kinds, as drawn; PDF kinds show "Allowance used up".

### Environment variables to add to `.env.example` (human edits)

```
SAHAJ_WHATSAPP_ENABLED=false            # global kill switch; false = every send is HELD
SAHAJ_WHATSAPP_PROVIDER=log             # log | meta-cloud | <bsp-name>; no real adapter in dev/test
SAHAJ_WHATSAPP_API_BASE_URL=            # provider base URL
SAHAJ_WHATSAPP_ACCESS_TOKEN=            # system-user or BSP API token (secret)
SAHAJ_WHATSAPP_APP_SECRET=              # verifies webhook signatures (secret)
SAHAJ_WHATSAPP_WEBHOOK_VERIFY_TOKEN=    # webhook subscription handshake (secret)
SAHAJ_WHATSAPP_DEFAULT_PHONE_NUMBER_ID= # only for sender model S1 or a pilot number
SAHAJ_WHATSAPP_BUSINESS_ACCOUNT_ID=     # only for S1 or a pilot
SAHAJ_PUBLIC_BASE_URL=                  # public HTTPS origin for webhooks and ABDM callbacks
```

---

## D3 · ABDM (human decides registration and scope)

### What ABDM asks of Sahaj and of each clinic

| Who | Needs |
| --- | --- |
| Sahaj (the company) | Registration on the ABDM sandbox as an integrator, giving a sandbox client id and secret; a registered bridge (callback) URL on a public HTTPS domain; sandbox functional testing signed off by NHA; a **WASA (web application security audit) certificate from a CERT-In empanelled auditor** for production; production approval and production client id and secret; compliance with the ABDM Health Data Management Policy and DPDP Act (privacy policy, grievance officer); hosting in India. |
| Each clinic | An HFR ID (field exists in `platform.privacy_settings.hfr_id`), linked to Sahaj's bridge as its HIP; an HPR ID per doctor (mockup shows the field; no column exists). The clinic registers on HFR / HPR itself; Sahaj cannot do it for them. |
| Each patient | An ABHA number or ABHA address, verified by an OTP that ABDM sends (Aadhaar OTP or ABHA-registered mobile OTP). Sahaj needs no SMS channel for this. |

### Milestones and scope

- **M1 · ABHA verification and linking** (front desk captures ABHA, ABDM sends OTP, Sahaj verifies, stores ABHA number and
  address, sets "ABHA linked"). Drives the header tag in SAH-47-1 and SAH-45-6.
- **M2 · Sahaj as HIP**: care contexts (one per signed OPD visit), HIP-initiated linking to the patient's ABHA ("Share to ABHA"
  on the case paper), patient-initiated discovery, consent notification, health-information request, encrypted push. This is
  SAH-68-4 and SAH-68-6.
- **Scan and Share** at reception (mockup: "After linking"): the clinic's HFR QR lets a patient share ABHA profile details for
  registration. Small, needs M1 and the HFR link.
- **M3 · Sahaj as HIU** (pull a patient's past records from other facilities; mockup line "lets patients share past records with
  your clinic") is a separate, larger build: consent requests, decryption, storing or showing external records. Not asked by any
  EMR story.

### Flows (HIP side)

1. **ABHA link with OTP:** the front desk enters the ABHA number (or chooses Aadhaar OTP); Sahaj calls the ABHA APIs with the
   identifier encrypted using ABDM's public key; ABDM sends the OTP; the front desk enters it; Sahaj verifies, compares name, year
   of birth and sex with the patient record (mismatch goes to the front desk, never silently overwrites), stores the ABHA number
   and address. **The Aadhaar number is never stored or logged.**
2. **Care context on signing:** `opd` publishes `VisitSigned (tenantId, visitId, patientId)`. `integration` records a care
   context. If the patient has `ABDM_LINK` consent and a verified ABHA, the doctor's "Share to ABHA" links it (HIP-initiated
   linking); the patient sees it in their ABHA app.
3. **Consent artefact:** a requester (the patient's own app or another facility) asks the consent manager; the patient approves in
   their ABHA app; ABDM notifies Sahaj with the consent artefact (care contexts, purpose, date range, expiry). Sahaj stores it,
   acknowledges, and honours revocation and expiry. **Sahaj never sends health information without a granted, unexpired artefact
   that names the care context.**
4. **Health-information request:** ABDM asks for the data with the requester's public key; Sahaj builds one **OP consultation
   record FHIR R4 bundle** per care context (NRCES profile `OPConsultRecord`: Composition, Patient, Practitioner, Organization,
   Encounter, Condition (ICD-10 / SNOMED CT stored on fields, SAH-45), MedicationRequest, Observation (vitals, LOINC),
   AllergyIntolerance, ServiceRequest, follow-up Appointment, and optionally the case-paper PDF as a Binary / DocumentReference),
   encrypts it with ABDM's ECDH scheme (X25519 key agreement, AES-GCM), pushes it to the requester's data-push URL, and reports
   the transfer status. Every transfer is audited (actor ABDM consent, `SHARE`).
5. **Callbacks:** ABDM APIs are asynchronous; the gateway calls Sahaj's bridge URL. These endpoints have no staff session; they
   verify the gateway's signed token against ABDM's published keys, check `REQUEST-ID` and timestamp for replay, and resolve the
   tenant from the HIP id. They are the only unauthenticated-by-session data paths in the product; the security reviewer must see
   them.

New libraries this implies (each needs the human's go-ahead, DEC-039 addendum): HAPI FHIR structures R4 (Apache 2.0) for the
bundle and validation against the NRCES profiles; Bouncy Castle (MIT-style licence) for X25519 if the JDK 17 provider is not
enough for ABDM's key format.

### Environment variables to add to `.env.example` (human edits)

```
SAHAJ_ABDM_ENABLED=false             # global kill switch
SAHAJ_ABDM_ENVIRONMENT=sandbox       # sandbox | production
SAHAJ_ABDM_GATEWAY_BASE_URL=         # gateway / HIE-CM APIs for the chosen environment
SAHAJ_ABDM_ABHA_BASE_URL=            # ABHA APIs for the chosen environment
SAHAJ_ABDM_CLIENT_ID=                # from ABDM registration
SAHAJ_ABDM_CLIENT_SECRET=            # secret
SAHAJ_ABDM_CM_ID=                    # consent manager id for the environment (sandbox value differs from production)
# SAHAJ_PUBLIC_BASE_URL (shared with WhatsApp) is the bridge URL registered with ABDM
```

---

## D4 · PDF case paper (human decides library)

The case paper and the receipt are printed today with the browser's print (A4 / A5, English, Marathi or Hindi headings). A PDF is
needed only to **attach** to WhatsApp (and optionally inside the ABDM bundle). Marathi and Hindi are in Devanagari, which needs
OpenType shaping (conjuncts, matras, reph); Marathi also has its own letter forms (for example ल, श and the eyelash र) chosen by
the `mr` language tag. A renderer that does not shape Devanagari prints broken words, which on a medical document is not
acceptable.

| Option | Licence | Devanagari shaping | Notes |
| --- | --- | --- | --- |
| P1 · OpenHTMLtoPDF (community fork, on Apache PDFBox) | LGPL 2.1+ (PDFBox Apache 2.0) | **Uncertain.** PDFBox has limited GSUB support; Indic shaping must be proven by a spike. | Pure Java, same HTML/CSS approach as print; CSS 2.1 subset, so the print layout must be simplified. |
| P2 · Headless Chromium (Gotenberg sidecar container, or Playwright for Java) | Chromium BSD; Gotenberg Apache 2.0; Playwright Apache 2.0 | **Correct** (HarfBuzz), honours `lang="mr"`. | One HTML template serves print and PDF, exactly as the browser shows it. Costs a second process (a container or a bundled browser, a few hundred MB), its own patching, and network lock-down (no JavaScript from outside, no outbound fetch). Not a "service" in the business sense, but the human must accept it next to "one Spring Boot app". |
| P3 · iText 8 with pdfCalligraph | AGPL or **commercial** licence; pdfCalligraph is commercial only | Correct | AGPL is not usable for a closed SaaS without buying; licence cost per year. |
| P4 · Apache FOP (XSL-FO) | Apache 2.0 | Has complex-script support including Devanagari; quality to be proven | Separate XSL-FO templates, so the PDF and the print view drift apart. |
| P5 · OpenPDF with its layout processor (uses the JDK's own text layout, which shapes Indic scripts) | LGPL / MPL | Likely, spike needed | Low level: layout written in Java, not HTML. |
| P6 · No server PDF: browser print only; WhatsApp sends text templates only ("your case paper is ready, ask at the desk") | none | n/a | No library and no clinical PDF leaves Sahaj. Does not meet the mockup's "Case paper copy · PDF" and "Receipt · with the bill as a PDF". |

**Fonts:** Noto Sans Devanagari / Noto Serif Devanagari, Mukta, Hind (all SIL Open Font Licence 1.1: free to embed and bundle).
Bundle the font files in the application (no download at runtime), embed subsets in every PDF, check that the Latin font has ₹
(U+20B9), set `lang` per paper so Marathi forms are chosen. Digits stay Latin (UI rule ₹ 1,25,000).

**Storage:** no document storage in phase 1. A PDF is rendered on demand from the signed visit (immutable; changes are addenda)
or the paid bill, attached, and dropped. The outbox keeps the renderer version and a SHA-256 of the bytes so "what was sent" can
be shown to match a re-render.

**Recommendation:** a one-week spike rendering the mockup's case paper in English, Marathi and Hindi with P2 and P1 (and P5 if P1
fails), judged by a Marathi reader. Prefer P2 if the human accepts the sidecar; P1 only if the spike shows correct Devanagari.
Renderer behind `platform.api.PdfRenderer` so the choice can change without touching callers.

---

## D5 · Phased plan

| Phase | Needs from the human | Builds | Sends anything? |
| --- | --- | --- | --- |
| 0 · now | nothing | Already in the stories: buttons shown switched off with "Needs the WhatsApp service" / "Needs the WhatsApp and ABHA services"; browser print; HFR ID field (exists). | No |
| A · in-app parts | D1 module choice and CLAUDE.md edit; consent kinds (H6); schema creation in `create-database.mjs` | `notifications`: template catalogue (8 mockup kinds + EMR kinds) in 3 languages, Clinic setup → WhatsApp messages (switches, preview, language for new patients, allowance meter), outbox, dispatcher with the `log` adapter only, idempotency, retries, statuses, audit `SHARE`. Consent capture for the new kinds at the desk. `integration`: ABHA number and address columns (via `patient`), HPR ID per doctor (via `platform`), care contexts on `VisitSigned`, FHIR OP consultation bundle builder validated offline against the NRCES profiles. | No. Global switches stay `false`; every send is `HELD`. |
| B · PDF | D4 library after the spike | `PdfRenderer`, case paper and receipt PDF previews in the app (no sending). | No |
| C · WhatsApp live | Provider account (W1/W2), sender model, Meta business verification, approved templates, written residency confirmation, env values | Provider adapter, template sync, status webhook, allowance, W3 fallback; pilot one tenant by a per-tenant flag, then widen. | Yes, pilot first |
| D · ABDM sandbox | Sandbox registration, client id and secret, public callback domain | M1 ABHA verify, M2 linking, consent artefacts, health-information push with encryption, callbacks; NRCES validation; NHA sandbox test. | Sandbox only |
| E · ABDM production | WASA certificate, production approval and credentials; clinics' HFR and doctors' HPR IDs | Production switch per clinic once its HFR is linked. | Yes |

Phases A and B need no external account and can run while the company sets up C and D, which is DEC-040's recommendation.

---

## D6 · Contract sketch (not a contract)

Base path depends on D1; shown for option B. Not written into `docs/api/` because the module names are not decided and
`docs/api/opd.yaml` is being revised by another architect. A real contract (`notifications.yaml`, `integration.yaml`, plus
additions to `opd.yaml` and `billing.yaml`) follows the human's answer.

| Operation | Path | Role / permission (proposed new keys in brackets) |
| --- | --- | --- |
| Send the case paper | `POST /api/v1/opd/visits/{visitId}/shares` `{channel: WHATSAPP\|ABHA}` + `Idempotency-Key` → `202` with message status | Doctor (`clinic.consultation.write`); the owning module checks visit scope, signed state and consent, then calls `notifications.api` / raises the ABDM link |
| Send a receipt | `POST /api/v1/billing/receipts/{receiptId}/shares` | Billing (`clinic.billing.collect`) |
| Preview a message | `POST /api/v1/notifications/messages/preview` `{kind, source {module, entityType, entityId}, patientId, language}` → text, attachment name, consent state, sending on/off | the caller's permission on the source |
| Message status for a record | `GET /api/v1/notifications/messages?sourceModule=&sourceId=&page=&size=` (mobile never returned) | (`clinic.messages.view`) |
| Send again | `POST /api/v1/notifications/messages/{id}/retry` | (`clinic.messages.send`) |
| WhatsApp settings | `GET`, `PUT /api/v1/notifications/whatsapp/settings` (`If-Match` version) | Owner (`clinic.settings.view` / `.edit`) |
| Template preview with sample data | `GET /api/v1/notifications/whatsapp/templates/{kind}/preview?lang=en\|mr\|hi` | Owner |
| Provider webhook | `GET`, `POST /api/v1/notifications/webhooks/whatsapp` (public, signature verified) | none (public endpoint) |
| ABHA verify | `POST /api/v1/integration/abdm/abha-verifications` → txn id; `POST …/{txnId}/confirm {otp}` | Front desk (`clinic.patients.edit`) |
| ABDM status | `GET /api/v1/integration/abdm/status` (environment, HFR linked, HPR per doctor) | Owner |
| Consent artefacts of a patient | `GET /api/v1/integration/abdm/consents?patientId=&page=&size=` | (`clinic.abdm.view`) |
| ABDM callbacks | paths fixed by ABDM's bridge specification, public, gateway token verified | none |

Problem types (RFC 9457): `whatsapp-sending-off` (409, HELD), `consent-missing` (409), `allowance-used-up` (409, offers the
click-to-chat link), `visit-not-signed` (409), `abha-not-verified` (409), `abdm-not-configured` (503), `idempotency-conflict` (409,
same key with a different body), `otp-invalid` (400), plus the standard 400 / 403 / 404 of ADR-002 D6.

---

## Consequences

**If option B with the phased plan is chosen**

- No cycle between modules; sends are atomic with the user's action; the provider can change behind one port.
- The in-app parts are built and tested before any account exists, and production cannot send by accident (three switches, no
  adapter bean, `HELD` status).
- Patient data leaves Sahaj only through two audited, consent-checked doors, each with its own kill switch.
- Costs: two new schemas and packages, human edits to CLAUDE.md and scripts, a dispatcher and webhook to operate, public
  endpoints to secure, new libraries (FHIR, crypto, PDF), recurring costs (WhatsApp per message, WASA audit, possibly a PDF licence
  or sidecar), and onboarding work per clinic (HFR, HPR, possibly WhatsApp business verification).
- WhatsApp cannot fully meet "all patient data stays in India"; the human's interpretation (H5) is a lasting product rule.

---

## Human decides

| # | Decision | Recommendation |
| --- | --- | --- |
| H1 | Module placement (D1) | **B**: new modules `notifications` (WhatsApp) and `integration` (ABDM); edit CLAUDE.md, `.claude/rules/postgresql.md`, `create-database.mjs`, `validate.mjs` lists. |
| H2 | PDF renderer home | `platform.api.PdfRenderer` (stateless, no storage); documents storage stays out of phase 1. |
| H3 | PDF library (D4) | Spike first; prefer **P2 headless Chromium** if a sidecar is acceptable, else P1 only if Devanagari passes. Not P3 (AGPL / cost), not P6 (misses the mockup). |
| H4 | WhatsApp provider | **W1 Meta Cloud API direct with India local storage**, behind an adapter so a BSP (W2) can replace it; choose W2 only if INR billing and support outweigh one more data processor. Get the residency statement in writing either way. |
| H5 | Meaning of "all patient data stays in India" for WhatsApp | Sahaj's own stores stay in India; WhatsApp is accepted as a patient-chosen channel for **non-clinical template text**; **clinical PDFs** (case paper, prescription) only with an explicit patient consent (H6) and a legal opinion. |
| H6 | Consent | Keep `RECORDS` (its wording names WhatsApp reminders) for reminders, token, receipt text; `PROMOTIONAL` for birthday; add **`WHATSAPP_DOCUMENTS`** for case paper / prescription / receipt PDFs and **`ABDM_LINK`** for linking to ABHA. Sends require recorded consent even when the clinic's "consent at first visit" switch is off. A "STOP" reply records a withdrawal. These are beyond the mockup, so they need the human. |
| H7 | Sender model | Store the sender per tenant from day one; pilot on one Sahaj number (S1); move to clinic-owned numbers (S2, as the mockup draws) when onboarding is ready. |
| H8 | ABDM registration and scope | Register Sahaj in the **sandbox now**; build **M1 + M2 (HIP) + Scan and Share**; M3 (HIU, pulling past records) later. Budget for the WASA audit before production. Confirm whether the company is already registered. |
| H9 | Credentials | Add the variables in D2 and D3 to `.env.example`; real values only in `.env`; per-tenant sender ids and HFR / HPR ids are data, not variables. |
| H10 | Click-to-chat fallback (W3) | Only where the mockup draws it (allowance used up), text kinds only, never PDFs; not as a substitute while the service is off. |
| H11 | Inbound WhatsApp (replies 1 / 2, booking on WhatsApp) | Out of DEC-040's first cut; only delivery-status webhooks and STOP. Separate story later. |
| H12 | Retention | Template parameters purged 30 days after a final status; message metadata, consent artefacts and ABDM transfer logs kept as long as the audit (still unresolved in ADR-002). |
| H13 | PDF storage | Do not store PDFs; store renderer version and SHA-256 per send. |
| H14 | Screen map | Move "Clinic setup → WhatsApp messages" and the ABDM half of "Privacy, ABDM and backup" from Later to phase 1 (module per H1), and add the EMR rows the analyst drafted. |
| H15 | New template wording for EMR kinds (advice, prescription, vaccine, exercise, chemo calendar, form link) | Business analyst drafts from the EMR mockup in three languages; human approves before submission to Meta. |
| H16 | ABHA number at rest | Store in `patient`, masked in the UI except the last 4, never logged; column encryption with a key from an environment variable if the security reviewer asks for it. |

---

## Data needed (provisional, for the db-engineer, only after H1 and H6)

Not to be built before the human answers. Shown for option B. Every table: tenant-scoped, `tenant_id` first in indexes,
`ENABLE` + `FORCE` RLS, no cross-module foreign keys except `platform.tenant` and `patient.patient`.

| Schema | Entity | Key fields | Retention |
| --- | --- | --- | --- |
| `notifications` | `whatsapp_settings` (one per tenant) | per-kind switches, default language, sender phone number id and account id (S2), `version` | kept |
| `notifications` | `message_template` (global, Sahaj-owned) | `kind`, `language`, `version`, provider template name, category (UTILITY / MARKETING), parameter list, status (DRAFT / SUBMITTED / APPROVED / PAUSED / REJECTED) | kept |
| `notifications` | `outbound_message` | `idempotency_key` (unique per tenant), `channel`, `kind`, `patient_id`, `source_module`, `source_entity_type`, `source_entity_id`, `language`, `template_version`, `params jsonb` (purged), attachment kind, renderer version, `attachment_sha256`, `status` (HELD / QUEUED / SENDING / UNKNOWN / SENT / DELIVERED / READ / FAILED / EXPIRED / BLOCKED_NO_CONSENT / MANUAL), `attempts`, `next_attempt_at`, `provider_message_id`, `last_error_code`, `created_by`, timestamps. No mobile number. | metadata as audit; params 30 days |
| `notifications` | `dispatch_ticket` | `message_id`, `tenant_id`, `due_at`, `provider_message_id`; ids only; dispatcher policy | deleted when final |
| `notifications` | `monthly_usage` | `tenant_id`, month, `sent_count` | kept |
| `patient` | `patient` additions | `abha_number` (14 digits), `abha_address`, `abha_verified_at`, `abha_verified_by`, verification method | kept |
| `patient` | `patient_consent.kind` | add `WHATSAPP_DOCUMENTS`, `ABDM_LINK` (H6) | append-only |
| `platform` | doctor HPR ID; `privacy_settings.hfr_id` globally unique | | kept |
| `platform` | `access_audit_event.action` add `SHARE`; actor kind `SYSTEM` | | per audit |
| `integration` | `care_context` | `patient_id`, `visit_id` (opaque, no FK into `opd`), reference, display text without diagnosis, linked_at, link status | kept |
| `integration` | `consent_artefact` | ABDM artefact id, status (GRANTED / REVOKED / EXPIRED / DENIED), purpose, requester, date range, expiry, care context ids, received_at, signed artefact jsonb | kept |
| `integration` | `health_info_transfer` | artefact id, care context, requested_at, pushed_at, status, bundle SHA-256 (no bundle stored) | kept |
| `integration` | `abdm_request_log` | `request_id` (replay check), direction, api, timestamp, status; no body | 90 days |
