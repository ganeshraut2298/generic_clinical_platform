# DEC-096 · SAH-89 onboarding, header search, doctor fee, owner as doctor: escalations

Date: 22 Nov 2026 · Story: SAH-89 (docs/requirements/SAH-89.md, docs/stories/SAH-89.md) · Status: DECIDED (human, 22 Nov)

## Context
The Product Owner asked for nine changes (sign-up creates the clinic, Google Maps address, wizard validation, custom colour and logo, completion screen, header mobile search, remove "Second checks" menu, doctor fee on Add person, owner acts as doctor). The BA found several are new or differ from the mockup, and five touch areas that CLAUDE.md says must go to a human: a new external service, file storage, a safety control, a roles/auth change and a billing rule.

## Escalations (BA recommendation first)
1. **Google Maps (OQ-89-2).** A: Google Places autocomplete (new external service, API key, per-lookup cost, typed address and IP sent to Google, possibly outside India; needs an ADR). B (recommended for phase 1): structured typed address (line, city, state, PIN) plus the mockup's pasted "Google Maps link"; Places autocomplete later after an ADR and cost approval.
2. **Logo upload (OQ-89-5).** A (recommended): one logo per clinic, at most 2 MB, PNG/JPG/WEBP (SVG only if sanitised), stored in the platform schema under RLS, served only to signed-in users. B: leave logo upload until the documents service.
3. **"Second checks" menu (OQ-89-8).** It holds the chemotherapy second check (SAH-66, ADR-023). A (recommended): remove the sidebar entry, show "N second checks waiting" inside Queue for the same roles; the safety rule stays. B: delete the feature (removes a safety control; needs clinical sign-off).
4. **Owner as doctor (OQ-89-10).** A (recommended): owner keeps one membership; a "Also a doctor" toggle with doctor details on the owner's own row; counts as one of the plan's doctors; registration number needed before consulting; never second checker of own order.
5. **Doctor fee (OQ-89-9).** One fee per doctor = the doctor's "New visit fee"; tariff/payer rules still override at billing; follow-up stays clinic-wide; 0 shown as "Free"; old bills unchanged; changes audited.

Other open questions (OQ-89-1, 3, 4, 6, 7, 11) have BA defaults in the requirement.

## Blocked
SAH-89 stories approval, contract and all build stages.

## Human answer
22 Nov 2026, the human chose the recommended option on every point:
1. Address: typed structured address + pasted Google Maps link; no Google call in phase 1 (autocomplete later, needs ADR + cost approval).
2. Logo: stored in the platform schema (max 2 MB, PNG/JPG/WEBP, SVG only if sanitised), RLS, served to signed-in users only.
3. Second checks: sidebar entry removed, "N second checks waiting" shown inside Queue for the same roles; the safety rule stays.
4. Owner as doctor and doctor fee: recommended rules accepted; all other SAH-89 open questions take the BA defaults (OQ-89-1 A, 3, 4, 6, 7 A, 11).
Next: human adds "SAH-89 · stories · APPROVED" to docs/approvals.md; mockup differences (completion line text, header search behaviour, colour and logo in the wizard) to be added to docs/design by a human.

## Second round (22 Nov, after ADR-031 and the contracts)
Human answers, recommended option each time:
5. Registration number before consulting: owner acting as doctor only, for now; doctors and visiting doctors keep today's rule (revisit after a legal check).
6. Visiting doctors count toward the Free plan's 2-doctor limit (as the contract says).
7. Counsellor: remove the Patients menu item. Doctor fees in GET /team: shown only to the owner and billing staff.
8. Branch: commit the pharmacy work on the current branch first (human commits), then create feature/SAH-89-onboarding from it and continue SAH-89 there.
Architect to apply 7 to platform.yaml before contract approval.
