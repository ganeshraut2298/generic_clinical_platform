# IPD stories SAH-26 to SAH-32 · open questions for the human

Written by the business analyst. The IDs match the "Open questions" of each story file. Nothing here was guessed: where the mockup (`docs/design/mockup/sahaj-mockup.html`) is silent or unclear, the story builds only what the mockup shows and the "Proposed" line is only a suggestion for you to confirm or change. "Blocks" says what cannot be finished without an answer.

Note on this file: `node scripts/validate.mjs docs` expects every file in `docs/stories/` to be named `SAH-<n>.md` and to carry a "Mockup screen:" line and an "Acceptance criteria" section. This file has none of those, so the check will fail on it until you either move it out of `docs/stories/` or relax that rule in `scripts/validate.mjs` (humans only). The business analyst cannot write elsewhere.

## A. Decide before development of the story can start or be tested

| ID | Story | Question | Proposed | Blocks |
|---|---|---|---|---|
| OQ-29-1 | SAH-29 | Who creates and changes packages, and where? The mockup shows only a read-only list in Clinic setup → Admission and deposits. | OWNER on the same panel: name, price, included days, switch off | Package setup |
| OQ-29-2 | SAH-29 | How is a package attached to an admission and by whom? There is no field in the Admit sheet or the patient sheet. | A "Package" select in the Admit sheet, and a one-time "Apply package" on the admission sheet (OWNER, FRONT_DESK) before any extra charge | Package billing cannot be used end to end |
| OQ-31-3 | SAH-31, SAH-32 | What is the "PIN" for the pharmacist check and the second-nurse check? The mockup accepts any 4 digits. A format-only check gives no safety. | A 4-digit PIN each person sets once in their profile (a screen not in the mockup), stored hashed, locked after repeated wrong tries; or re-entry of the sign-in password | Verify order, high-alert doses |
| OQ-30-2 | SAH-30 | An escalation raised by a medium or high score cannot be acknowledged or answered by anyone until the Safety board story exists, and no message reaches the doctor (no notifications in phase 1). | Build the Safety board right after SAH-30 and do not use SAH-30 on a live ward without it | Value of SAH-30 |
| OQ-28-3 | SAH-28 | "Treat and release" in the mockup creates an OPD visit and a ₹600 "Emergency consultation" bill in Billing. Where does the price come from, and to which patient record does the bill belong (a released patient may have no registered record)? | No bill in SAH-28 (the case is only closed); decide the price source and identity rule, then add it | Emergency treatments can go unbilled |
| OQ-27-4 | SAH-27 | Must the final bill, the balance collected and the refund appear in the Billing counter, day close and revenue lists (SAH-19/20)? Deposits (SAH-23) do not today. | Decide with the billing owner; build a link in a later billing story. Until then cash collected at discharge is not in day close | Day-close totals |

## B. Clinical, legal and security sign-off needed before real use (development can go ahead with the mockup content)

| ID | Story | Question | Proposed |
|---|---|---|---|
| OQ-30-6 | SAH-30 | A doctor must approve the early warning score bands and risk rule (taken from the mockup `pcEws`: SpO₂, RR, pulse, temperature, systolic BP; High at 7 or more; Medium at 5 or more or any single 3), the response times (High 15 minutes, Medium 1 hour) and the monitoring advice (30 minutes, 1 hour, 4 hours). Note the score has no consciousness or oxygen item. | Build as in the mockup; clinical review before go-live |
| OQ-31-1 | SAH-31 | The 14 medicines, their classes, the three high-alert medicines, the three interaction rules and the four override reasons are demo content. Who approves the real list; is it the shared `opd.medicine` list extended with classes and flags? | A doctor and a pharmacist approve; extend the shared list |
| OQ-31-4 | SAH-31 | Allergies are matched by keywords in free text (penicillin, amoxic, sulfa, sulpha). Other allergies are not caught. | Keep as the mockup for now; structured allergies in the patient file later |
| OQ-31-6 | SAH-31 | A patient with no allergy recorded only gets a warning. Should it be a hard stop? | Warning, as the mockup |
| OQ-28-2 | SAH-28 | Confirm triage level names and descriptions, default target times (3 colours 5/30/120 min; 5 levels Now/10/30/60/120 min) and the mapping when the scale is changed (3 to 5: Red to Level 2, Yellow to Level 3, Green to Level 5; 5 to 3: Level 1 and 2 to Red, Level 3 to Yellow, Level 4 and 5 to Green). | As the mockup |
| OQ-27-7 | SAH-27 | "On request (LAMA)" and "Death" need extra records in practice (LAMA consent, cause of death, medico-legal case, death certificate). Should a deceased patient be marked in the patient file so reminders and follow-ups stop? | Not built; decide the legal content |
| OQ-27-1 | SAH-27 | Who may sign the discharge summary (any doctor, only the admitting doctor, a visiting doctor) and who may complete a discharge? The mockup default matrix and its sheet disagree. | Sign: DOCTOR, OWNER. Complete: OWNER, FRONT_DESK |
| OQ-27-3 | SAH-27 | Who may refund unused deposit? The mockup tip says "the owner or the accounts person". | OWNER only (BILLING has no IPD access yet) |
| OQ-27-9 | SAH-27 | For Insurance and Corporate patients the mockup collects "total − deposit" from the family like a Self patient. What should the final bill show for the insurer's share? | As the mockup |
| OQ-32-1 | SAH-32 | The mockup's "Scan wristband" button copies the patient's own UHID into the box, so the identity check would pass by itself. Confirm it is not built, and how wristbands are made and read in phase 1. | Not built; keyboard-type scanner or typing the UHID |
| OQ-32-2 | SAH-32 | When is a dose late for an order made or verified during the day, and what happens to a dose nobody recorded by midnight? | No late status before verification; missed doses handled in the Safety board story |
| OQ-26-1 | SAH-26, SAH-29 | Billing rule check: with "Same day", a transfer on admission day bills the whole first day at the new category, and extra charges are re-priced at the current category's multiplier even when added earlier. Intended for upgrades and downgrades? | As the mockup |
| OQ-26-4 | SAH-26, SAH-29 | A package admission that moves to a dearer bed gets no bed segment, so the difference is not billed. Confirm. | As the mockup |

## C. Not blocking: proposed default is built unless you say otherwise

| ID | Story | Question | Proposed |
|---|---|---|---|
| OQ-26-2 | SAH-26 | May DOCTOR transfer a patient? (Mockup default matrix: doctor yes, visiting doctor no; the Roles and access page is not built.) | OWNER, FRONT_DESK, NURSE only |
| OQ-26-3 | SAH-26 | Is "Owner approves" only "the owner does it", or an approval request the owner answers later? | Only the owner does it, as the mockup |
| OQ-26-5 | SAH-26 | Inline "Move" row has no reason; the card Transfer sheet has one. | Accept and store an optional reason; show the field with the patient card |
| OQ-26-6 | SAH-26 | Nurse handover to the new ward's nurse needs ward-wise nurse assignment. | Not in phase 1 |
| OQ-27-2 | SAH-27 | Are "Billing clearance" and "Nursing handover" real sign-offs by other staff, or only labels? | Labels, completed by the one discharge button |
| OQ-27-5 | SAH-27 | How does a patient become "Going home today" before discharge? The mockup only seeds it. | Built as an assumption: summary signed today and not yet discharged |
| OQ-27-6 | SAH-27 | Where do staff see discharged patients, summaries and final bills? No screen exists in the mockup. | Counter only; read via the admission detail |
| OQ-27-8 | SAH-27 | Which doctor gets the follow-up (the mockup hard-codes one), and what if the admitting doctor is inactive? | The admitting doctor; ask if inactive |
| OQ-27-10 | SAH-27 | Should the follow-up reason carry the diagnosis (front desk, who cannot see clinical details, reads that list)? | Keep the mockup text, cut to 200 characters; review with security |
| OQ-28-1 | SAH-28 | When is an emergency patient registered in Patients, including "Unknown male, about 40", and which fields does Patients require? | Only when admitting, through the existing registration dialog with the placeholder name |
| OQ-28-4 | SAH-28 | Should DOCTOR see the Emergency menu item and act (mark seen)? | DOCTOR can read and act; menu item decided with roles |
| OQ-28-5 | SAH-28 | How is the duty doctor alerted on a red arrival or a breached target? Not possible without notifications. | Screen only |
| OQ-28-6 | SAH-28 | Bays are given automatically to the most urgent level and cannot be changed by hand. | As the mockup |
| OQ-29-3 | SAH-29 | Do new clinics start with example packages or an empty list? Prices are commercial data. | Empty list |
| OQ-29-4 | SAH-29 | Day-care package line says "for 0 days" in the mockup. | "for the day" |
| OQ-29-5 | SAH-29 | Re-pricing of earlier extra charges on a package admission (see OQ-26-1). | As the mockup |
| OQ-30-1, OQ-31-7 | SAH-30, SAH-31 | The Nursing and EMR sheets are opened from the patient card, which is not built. | Buttons on the admission sheet until the card story |
| OQ-30-3 | SAH-30 | Who records vitals and who sees them; does a nurse see all patients or only her ward? | Record: NURSE, OWNER. View: DOCTOR. No ward scoping yet |
| OQ-30-4 | SAH-30 | Allowed ranges for each vital and the note length. | SpO₂ 1 to 100; temperature 25.0 to 45.0; pulse and RR 1 to 300; systolic 40 to 300; diastolic 20 to 200; note 1,000 characters. A doctor confirms |
| OQ-30-5 | SAH-30 | Correcting a wrong vitals entry. | New entry only |
| OQ-30-7 | SAH-30, SAH-32 | Should opening the Nursing sheet or the MAR write to the patient record-access log (the mockup logs only opening the EMR)? | Not logged; security review decides |
| OQ-30-8 | SAH-30 | Vitals fields are prefilled with the last values (mockup). Risk of saving old readings by mistake. | As the mockup; consider empty fields |
| OQ-31-2 | SAH-31 | Who sees and uses the Pharmacist check: the mockup shows it only to the owner. | OWNER and PHARMACIST; a doctor may verify only another doctor's order. The IPD menu needs PHARMACIST |
| OQ-31-5 | SAH-31 | Where does the doctor see why an order was rejected? | In the EMR list under the order |
| OQ-31-8 | SAH-31 | VISITING_DOCTOR may order in the mockup but has no access to the admission sheet (SAH-23). | Decide with the roles |
| OQ-32-3 | SAH-32 | Can DOCTOR give a dose? | NURSE and OWNER only |
| OQ-32-4 | SAH-32 | Where does the doctor see "Not given" and "Refused"? | Last outcome of each order in the EMR list |
| OQ-32-5 | SAH-32 | Correcting a wrongly recorded dose. | No correction; a note and the doctor |

## D. Cross-cutting points for the tech lead and architect

1. Story numbers: the playbook (`docs/process/playbook.md`) lists different IPD stories under SAH-21 to SAH-29. The stories already built use SAH-22 to SAH-25 for different slices, and these new files use SAH-26 to SAH-32 as requested. The playbook rows for Nursing/EWS, Medicine orders/MAR, Transfer/Discharge, Emergency and triage and Safety board should be re-numbered or marked "split into SAH-26 to SAH-32".
2. Stories not written, needed to make the above useful: the **Safety board** (escalation Acknowledge / Record response / Call senior, banners and counters, Medicine round across patients, emergency (break-glass) access with owner review, clinical audit trail view) and the **Admitted patient card** (cards, stage chip, "Vitals due" and score flags, card settings with draft and publish, Roles and access matrix, ward scoping of nurses and own-patient scoping of visiting doctors). Both are in the mockup; neither was in the request.
3. Roles: IPD reads currently go to OWNER, FRONT_DESK, NURSE, DOCTOR. The new clinical screens need PHARMACIST (SAH-31) and possibly VISITING_DOCTOR and BILLING; the navigation policy must be extended once decided.
4. External services still not in phase 1 and replaced by on-screen text: WhatsApp (discharge summary, final bill, deposit receipts), printing (interim bill, death summary, referral letter, case paper), documents, notifications (duty doctor alerts, rejection notices), wristband printing and camera scanning.
5. Cross-module changes the architect must contract: `opd.api` follow-up port and a nullable visit on `opd.follow_up` (SAH-27); `patient.api` allergy text and record-access log call (SAH-31) and, perhaps, placeholder patients (SAH-28); `platform.api` team lookups for nurses and owner name (SAH-26, SAH-32); possibly a `billing.api` port for IPD receipts (OQ-27-4).
6. The IPD pages of the mockup are also designed for 26-ward hospitals (bed board rail, Emergency dock); those layouts are not in these stories.
7. Files here use "Mockup screen:" (singular) so that `validate docs` can read them; the earlier IPD story files SAH-22 to SAH-25 use "Mockup screens:" and would not match the validator's pattern.
