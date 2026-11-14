# DEC-045 · Patient date of birth

Date: 13 Nov 2026. Status: ANSWERED (human, chat, 12 Nov 2026). Raised by: tech lead, from the java-developer and QA (EMR phase 4).
Patients are stored with an integer age only. Paediatrics needs exact age (vaccine due/overdue, dose by weight checks, growth bands, milestones, dental Child tab).
**Answer: yes, a new patient story** — optional date of birth at registration and on the patient record; age is worked out from it when present. The analyst writes the story (next free id), then contract, migration, backend and screens; Paediatrics uses it as soon as it exists. Covered by the human's pre-approval for EMR-programme stories only in so far as it serves the EMR; registration screen changes that differ from the repo mockup go to the human.

## Addendum (human, chat, 13 Nov 2026): SAH-73 sign-offs
All defaults SO-73-1 to SO-73-12 in docs/requirements/SAH-73.md accepted, including the two Patients-screen changes the repo mockup does not show (optional "Date of birth" after Age in the New patient / Add family member dialog, Age read-only when a date is typed; "Edit details" opens a sheet with only Date of birth and Age). SO-73-10: date of birth is **covered by the existing records consent** (no new consent wording). Treated as the human's chat approval of the SAH-73 stories; not lines in docs/approvals.md.
