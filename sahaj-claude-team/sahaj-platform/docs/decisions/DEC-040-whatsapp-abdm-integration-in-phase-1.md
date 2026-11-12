# DEC-040 · WhatsApp sending and ABHA/ABDM sharing brought into phase 1

Date: 12 Nov 2026. Status: OPEN (architecture work not started; human items listed below). Raised by: tech lead, from DEC-039 answer 4.
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

## Answer
(pending)
