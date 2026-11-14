# SAH-72 · Stories (placeholder, outline only): Nurse vitals shown in the doctor's EMR
Requirement: `docs/requirements/SAH-45.md` (rules BR45-1, BR45-5, BR45-16, BR45-19; decided sign-offs SO-45-11, SO-45-43; OQ-45-14 answered). Module: opd (reads the vitals step of the existing OPD workflow, the IPD nursing chart and Emergency triage only through their `api` packages). **Future story, not in phase 2.** Depends on SAH-47 (frame and Vitals section), SAH-60 (General examination vitals rows) and SAH-68-1 (vitals read for OPD, IPD and Emergency). **This id was added on 12 Nov 2026, at the end of the range, by the human's decision in the DEC-039 addendum "database path, remaining defaults, chemo second check": there is no nurse vitals value in phase 2; the nurse vitals pre-fill is a separate story later.** SAH-68-1 already outlines the same read for phase 5; when this story is scheduled the two are to be merged into one (the tech lead decides which id stays).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorGeneral and #GeneralTreatment, General examination vitals rows and the line "Recorded by <nurse> at <time>").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details open the EMR; the nurse records vitals in the existing vitals step and never opens the EMR. "Not signed in" gets 401. The clinic always comes from the logged-in user.

### SAH-72-1 · As a doctor, I want the vitals the nurse recorded for this visit shown in the EMR, with who took them and when, so that I confirm them quickly instead of retyping them
Priority: Could
Screen: EMR mockup #DoctorGeneral, General examination vitals rows and the "Recorded by" line.
## Acceptance criteria
Outline only. The full criteria are written when the human schedules this story; the points below are what it must cover (each to be expanded into Given/When/Then):
- Given the nurse recorded vitals in the vitals step of this visit, when the doctor opens the EMR, then the vitals rows show the values as a suggestion with "Recorded by <nurse name> at <time>. Record again to add a new entry.", and the doctor confirms or edits them; a value the nurse's step does not capture stays empty.
- Given no vitals were recorded for this visit, then the line reads "Last taken by <name>, <date and time>. Doctor's entry for this visit is still needed." and nothing is pre-filled.
- Given the nurse records vitals after the EMR is open, then the EMR offers "New vitals recorded. Use them?" and replaces values only when the doctor accepts.
- Given a nurse, front desk, billing, pharmacist or counsellor, then they cannot read or write the EMR (403); given another clinic's visit, 404; no login, 401.
- Given any call or log line, then no vitals value is logged with a patient name; the audit holds ids only.
- Out-of-range colours and labels stay as in SAH-47-3 (adult limits, tagged as accepted by the human in DEC-039, not clinically reviewed).
## Notes for design
Until this story is built the doctor types vitals in the EMR (SAH-47-3). The architect decides how the opd module reads vitals from the workflow, the IPD nursing chart and Emergency triage without a module cycle (see SAH-68-1). "Who can fill" stays a stored and shown setting; this story does not change what other roles may write in the EMR.

## Out of scope for SAH-72
Writing vitals by the nurse in the EMR (the nurse keeps the existing vitals step); the specialty vitals rows of the other departments (their own stories); IPD and Emergency reads beyond what SAH-68-1 specifies.

## Traceability
BR45-1 -> SAH-72-1. BR45-5 -> SAH-72-1. BR45-16 -> SAH-72-1. BR45-19 -> SAH-72-1. SO-45-43 -> SAH-72-1.

## Open questions
None new. Decided: no nurse vitals value in phase 2 (human, DEC-039 addendum, SO-45-43).

## Status log
- 12 Nov 2026 (follow-up pass) · placeholder created by the business-analyst; outline only. Not part of the pre-approved phase 2 work.
