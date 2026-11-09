# DEC-031 · SAH-34 ward scoping and break-glass: the human chooses

Date: 9 Nov 2026. Status: DECIDED (recommended path). 34A waits for the human's mockup screen. Q1–Q25 not individually answered: defaults per ADR-016 (DEC-023).
Source: docs/architecture/adr/ADR-016-ipd-ward-scoping-and-break-glass.md (13 decisions, 25 questions, 7-story split, about 5 to 7 weeks for one developer pair).

## What the human should know first
- VISITING_DOCTOR has no IPD access today (403 on every IPD endpoint). "Own patients" for visiting doctors therefore widens access.
- No mockup screen assigns nurses to wards. Every model needs a screen the mockup lacks, so the human must add one to the mockup or approve a deviation.
- "Not assigned means all wards" fails open: a nurse with no ward sees the whole hospital again.
- Four mockup behaviours would need deviations: the toast "The owner has been told." would be false (no notifications in phase 1); the review panel lists only admitted patients, so a discharge would hide a grant; the review log stores a staff name; and one grant per patient means a second user's grant replaces the first.
- A clinical risk: hiding a patient from a covering nurse can hide a deteriorating patient. The clinical sign-off owner (OQ-30-6) reads ADR-016 section 8.

## Recommended path (the architect's recommendation, not a decision)
A1 ward table with several nurses per ward · M2 a per-clinic switch (M3 later) · B1 admitting doctor · C1 scope fixed in code (the Roles and access panel is not built: a mockup deviation) · E2 403 `outside-scope` · break-glass as the mockup with three safety deviations (fixed 30 minutes, in-app owner alert with a changed toast, grants stay in review after discharge) · S1 one app-layer guard.
Stories: 34A ward assignment (M), 34B scope enforcement (L), 34C list UI (S), 34E break-glass (M), 34F owner review (S), 34D visiting doctor in IPD (M, after its role answers), 34G Roles and access panel (M, optional, later).

## Human answer
9 Nov 2026, AskUserQuestion: (1) "Accept recommended": A1 + M2 + B1 + C1 + E2 + S1. (2) Nurse-to-ward screen: "I'll add it to the mockup" - the human adds it to docs/design/mockup and the screen map; story 34A waits for it. (3) Break-glass: "Accept the deviations" (fixed 30 minutes; toast "The owner will see this on the Safety board"; grants stay in review after discharge).
