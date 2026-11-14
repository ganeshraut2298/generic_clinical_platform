# DEC-040 · WhatsApp sending and ABHA/ABDM sharing brought into phase 1

Date: 12 Nov 2026. Status: ANSWERED 12 Nov 2026 (human, chat); human file edits and accounts still pending (below). Raised by: tech lead, from DEC-039 answer 4.
Blocked: the stories that send on WhatsApp or share to ABHA (case paper share, follow-up and vaccine reminders, home-exercise and chemo-calendar sends, receipts on WhatsApp, ABDM OP consultation record). Everything else in the EMR programme is not blocked by this decision.

## Why this is an architecture decision
- CLAUDE.md (human-owned) says: "Not in phase 1: integration gateway (ABDM, HL7, payments), notifications (WhatsApp/SMS), documents service". The module list is fixed (platform, patient, opd, ipd, hra, billing). Sending and sharing need a place to live: either a new module (for example `integration` or `notifications`) or a defined home inside an existing one. Both change the architecture rules, so the human edits CLAUDE.md.
- New external services: a WhatsApp Business API provider (BSP) and the ABDM gateway (HIP/HIU registration, sandbox then production, consent manager flows, FHIR OP consultation record bundles). Each needs accounts, credentials (environment variables only, never in code), data-residency confirmation (all patient data stays in India), and outbound network access from the server.
- Consent and privacy: ABHA sharing only with recorded patient consent (ABDM consent artefact); WhatsApp sends need patient opt-in and a template approval process with the provider; DPDP Act notice and purpose. The existing Clinic setup → WhatsApp messages and Privacy, ABDM and backup screens are marked "Later" in the screen map.
- PDF case paper for WhatsApp: the "documents service" is also listed as not in phase 1.

## What the human needs to decide or provide (the architect will turn this into an ADR with options)
1. Module placement: new module(s) `integration` / `notifications` (CLAUDE.md change by the human) or inside `platform`.
2. WhatsApp provider (BSP) and who owns the account; ABDM: is the company already registered as HIP/HIU, which environment first (sandbox).
3. Credentials and settings: names to add to `.env.example` (human-owned) and values in `.env`.
4. PDF generation for the case paper (library choice is an architecture change).
5. Update `docs/design/screen-map.md` rows marked "Later" (WhatsApp messages, ABDM) if they move to phase 1.

## Recommendation
Keep these stories last in the EMR phase order (phase 5, integrations) so the provider accounts can be set up in parallel; build the in-app parts (consent capture, message preview, "send" queue) first behind a switch that stays off until credentials exist.

## Answer (human, chat, 12 Nov 2026), on ADR-021 (docs/architecture/adr/ADR-021-whatsapp-abdm-pdf.md)
1. **All 16 recommendations H1-H16 accepted**: two new modules `notifications` (WhatsApp) and `integration` (ABDM); PDF renderer in `platform`, nothing stored; Meta Cloud API direct with India storage behind a swappable connector; consent kinds `WHATSAPP_DOCUMENTS` and `ABDM_LINK` added, every send needs recorded consent, STOP = withdrawal; sender number per clinic, pilot on one Sahaj number; ABDM M1/M2 and Scan and Share now, M3 later; credentials only in `.env`; click-to-chat fallback text only where drawn; patient replies/booking/SMS later; message details deleted 30 days after final status; PDFs not stored; screen-map rows move to phase 1; message wording drafted in en/mr/hi by the BA and approved by the human before submission to Meta; ABHA stored in patient, masked to last 4, never logged.
2. **Residency (H5): reminders only by default**; case papers and prescriptions only with explicit patient consent **and** after a legal opinion.
3. **ABDM: not yet registered; register in the sandbox** (needs a public callback URL; production later after the WASA audit).
4. **PDF: second process acceptable; one-week trial first** (headless Chromium via Gotenberg or Playwright vs OpenHTMLtoPDF on a Marathi/Hindi case paper). Each library still needs the human's go-ahead after the trial.
Human-owned steps still needed before the new modules can be built: edit CLAUDE.md (module list and "Not in phase 1"), .claude/rules/postgresql.md (new schemas), scripts/setup/create-database.mjs (schemas `notifications`, `integration` and grants), scripts/validate.mjs if it lists modules, docs/design/screen-map.md, .env.example (variable names in ADR-021 D2/D3); create the Meta WhatsApp Business account and start ABDM sandbox registration; arrange a legal opinion for documents on WhatsApp. Agents build the in-app parts with sending switched off once the module rules exist.

## Update (human, chat, 13 Nov 2026): moved back to Later (DEC-049)
WhatsApp sending and ABDM sharing go back to "Later" as a new story for when the accounts, sandbox registration and legal opinion exist. The in-app parts stay built and switched off. The human file edits listed above are not needed now. ADR-021 stays accepted as the design for that later story.
