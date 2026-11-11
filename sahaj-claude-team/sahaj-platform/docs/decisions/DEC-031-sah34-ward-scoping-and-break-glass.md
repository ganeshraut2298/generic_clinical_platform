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

## Addendum, frontend readings and contract alignment (10 Nov 2026, defaults applied under DEC-023)
| # | Point | Default applied |
|---|---|---|
| FE-1 | Safety tab gate: the contract wording (`items` not empty) would hide the board and the AC 37 notes from a nurse with no ward. | Use the admitted count M for scoped users, `items.length` otherwise. The architect updates the contract text. |
| FE-2 | New state texts not in the stories: "We could not load the patients. Try again.", "Loading the patients", "We could not mark that as reviewed. Try again.", "We could not load more. Try again.", and "Already reviewed." as a toast (AC 69 says a notice). | Accept as plain state text. **Human to sign off the wording.** |
| FE-3 | The Emergency access card flag is a safety flag and never folds into "+N". | Accept. |
| FE-4 | Tooltip role word "Nurse" is fixed until 34D. | Accept. |
| FE-5 | OQ-34-15: typed values are not kept after a refused save. | Default applied; the human still chooses. |
| FE-6 | OQ-34-26: the red notice "N escalations past the response target in other wards" is built and removable. | Keep; remove if the human says no. |
| AR-1..9 | Architect alignment (docs/handoffs/SAH-34.md): in-memory list filter, `malformed-request`, every-request switch check, locks, audit entity types, membership rule. | Accepted by the architect; none needs the human. |

## Addendum, security review (10 Nov 2026)
| # | Point | Default applied | Human |
|---|---|---|---|
| SEC-1 | The picker is a directory of admitted patients (name, UHID, bed) for scoped nurses, with no trace. | One audit event per picker read (user id and count only) and a privacy-register entry. | Confirm. |
| SEC-2 | Break-glass has no cap, rate limit or alert (OQ-34-13). | None added (a cap could block a real emergency); recorded as a risk. | Choose: (a) soft per-user limit (for example more than 5 open grants in 8 hours gives a 429 with fixed text), (b) per-staff count in the owner panel, (c) accept. Security recommends (a) plus (c). |
| SEC-3 | Scoped nurses see colleagues' bg entries on their own ward; discharged admissions stay readable to the last ward's nurses. | Accept and note in the register. | Confirm. |
