# DEC-014 · IPD stories SAH-26 to SAH-32: development continues on the business-analyst's proposals

Status: DECIDED by the tech lead on 5 Nov under the human's standing instruction ("go ahead for all development and make sure work will not stop"). Every item stays OPEN for the human to confirm or overrule; each build follows the mockup, and where the mockup is silent it follows the proposal written in the story and `docs/stories/IPD-open-questions.md`.

## Rule used
1. The mockup decides. Where the mockup shows behaviour, it is built as shown.
2. Where the mockup is silent and the story gives a proposal, the proposal is built, marked in code and tests as an assumption (`OQ-<story>-<n>`), and listed here.
3. Anything that would put clinical, legal or money data at risk is built in the safer direction (the narrower role, the append-only record, the server computes money, no document is generated).
4. No new module, external service or library is added. Cross-module needs go through `*.api` ports.

## Proposals now being built
- OQ-26-1 extra charges are re-priced at the current category's multiplier after a transfer (as the mockup); OQ-26-2 only OWNER, FRONT_DESK, NURSE may move a patient; OQ-26-3 "Owner approves" means only the owner can do it (no approval queue); OQ-26-4 package admissions get no extra segment (SAH-29 decides); OQ-26-5/6 no reason list in the inline row, no nurse handover.
- OQ-27-1 sign DOCTOR/OWNER, complete OWNER/FRONT_DESK; OQ-27-2 billing clearance and nursing handover are labels (one action completes them); OQ-27-3 refund OWNER only; OQ-27-4 the final bill and settlement are stored in `ipd` only for now, they do NOT yet appear in the Billing counter, day close or revenue (blocking for go-live: a later billing-link story); OQ-27-5 "Going home today" = summary signed today and not yet discharged; OQ-27-6 no discharged-patients list; OQ-27-7 no LAMA/death extra records and no deceased flag on the patient; OQ-27-8 the follow-up goes to the admitting doctor; OQ-27-9 Insurance/Corporate show the full balance like a Self patient (insurer share is not modelled); OQ-27-10 the follow-up reason includes the reason for admission as the mockup shows, cut to 200 characters.
- Later stories (SAH-28 to SAH-32): same rule; SAH-29 (packages) is built only after the human answers because it changes the bill rules; if a story needs a human answer that cannot be defaulted safely it is marked NEEDS DECISION and the pipeline moves to the next story.

## What the human should check before go-live
OQ-27-4 (billing link) and OQ-27-7 (clinical/legal records for LAMA and death) are the two that can block a real clinic.
