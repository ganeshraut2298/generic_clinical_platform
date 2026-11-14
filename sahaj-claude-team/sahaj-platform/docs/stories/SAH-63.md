# SAH-63 · Stories: Physiotherapy screen (first-visit assessment, session treatment and goals, complete session and book next, exercise programme and progress report)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-4, BR45-5, BR45-6, BR45-20, BR45-27, BR45-29; clinical rule C-6; billing rule B-1; decided sign-offs SO-45-1, SO-45-24; answered questions OQ-45-6; DEC-039 answer 4 for Marathi and Hindi; answered, default accepted (DEC-039 addendum): OQ-45-27, OQ-45-28, OQ-45-30, OQ-45-32). Module: opd (session booking through Appointments; package payment through SAH-58 and the billing `api`). Phase 4 (BRD out of scope, in scope by DEC-039 answer 1). Depends on SAH-47 (frame), SAH-56 (EMR list), SAH-58 (Rehab package course), SAH-51 (finish), SAH-11 (Appointments). Department: Physiotherapy (starter department, 27 sections, SAH-53-1). **Added 12 Nov 2026 at the end of the block (no renumbering): SAH-63-3 (complete session and book next) and SAH-63-4 (exercise programme and progress report), split from the oversized SAH-63-2.**
Mockup screen: Queue (sidebar → Queue) (EMR mockup #DoctorPhysio; the mockup calls the user "Therapist").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). The "Therapist" of the mockup is a doctor or visiting doctor of the Physiotherapy department; Sahaj has no therapist role (SO-45-24). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) write the screen, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor get 403 on a direct call; front desk and billing see the booked session and the package money on their own screens. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). It is repeated on each clinical rule below.
Prescription starts off for Physiotherapy as seeded (SAH-53-1) and the owner can switch it on (OQ-45-6: only Allergies cannot be hidden); so this screen does not assume a prescription.

### SAH-63-1 · As a physiotherapist, I want a first-visit assessment with pain, range of motion, strength, special tests, posture and gait, so that I record a baseline
Priority: Should
Screen: EMR mockup #DoctorPhysio, mode "First visit: assessment", "Pain today", "Range of motion & strength · knee", "Special tests · knee", posture and gait.
## Acceptance criteria
- Given a patient with no earlier Physiotherapy visit in this clinic, then the screen opens in "First visit: assessment"; given an open package course (SAH-58), then it opens in "Session n" (SAH-63-2). The doctor can switch the mode; the mode is stored with the visit. (BR45-6)
- Given the mode, then the sections of the other mode are hidden as drawn: First visit hides Treatment modalities and Session note; Session hides Special tests and Posture and gait. A hidden section keeps its stored values, is never listed in the footer and never blocks Complete, whatever its Required setting; "treatment given today" is needed only in Session mode (SAH-63-2). The server returns the hidden set with the screen. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-6). (BR45-19, BR45-20)
- Given the section Outcome scales (library section "Outcome scales"), then the doctor records one total score for each scale: ODI, NDI, WOMAC and LEFS (the mockup draws the score as "Calculated", but no questionnaire is drawn, so the score is typed by the doctor, H20 applied by default; the human can change it); the section is Done with one score; Copy from last visit copies nothing; it prints under Examination (4) on the case paper. Rule tag as above (C-6). (BR45-4, BR45-20)
- Given a scale has a score today and an earlier signed Physiotherapy visit of this patient has a score for the same scale, then a chip reads "<scale> <previous> → <today>" (for example the mockup's "WOMAC 48 → 41" in the range-of-motion card, which is example data); with no earlier signed score the chip shows no previous value. A score outside what the scale allows is refused with an error (400 on a direct call). (BR45-4)
- Given the section Pain assessment (required at start), then "Pain today" is a 0 to 10 scale (NPRS) of eleven buttons with the words "no pain" at 0 and "worst pain" at 10, one selected, and a text "Pain site" (for example the joint and side). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-6). (BR45-4)
- Given the section Range of motion, then the title reads "Range of motion & strength · <joint>" with the knee drawn: rows Flexion, Extension and Flexion (passive) with the columns Movement, Right, Left, Normal and a bar; the doctor types or steps the degree for right and left; the normal values are those of the mockup (flexion 0–135°, extension 0°, passive flexion 0–135°). The bar shows right value divided by the normal maximum as a percent (flexion 105° of 135° shows 78%; for extension with normal 0° the bar is 100% at 0° and 90% otherwise). Rule tag as above (C-6). (BR45-4)
- Given a joint other than the knee, then the mockup draws no rows; the section shows the library fields of Range of motion and Special tests with the generic renderer (SAH-47-3) until the human supplies joint tables; the joint picker lists the joints the doctor types, and no normal range is invented. This is shown to the human at approval as a gap. (BR45-4)
- Given muscle strength, then manual muscle testing 0 to 5 is entered for right and left for each muscle group listed in the mockup; values outside 0 to 5 are refused with an error. Rule tag as above (C-6). (BR45-4)
- Given "Special tests · knee", then the tests McMurray (medial), Lachman, Patellar grind, Anterior drawer, Valgus stress and Apley compression each cycle on tap: Not done → Positive → Negative → Not done, with text and colour (Positive red, Negative green). Rule tag as above (C-6). (BR45-4)
- Given posture and gait, then the choices Normal, Antalgic, With stick and With walker are single choice. (BR45-6)
- Given Copy from last visit is switched on by the owner for a section of this screen (it is off for the Physiotherapy sections at start, SAH-53-1), then values of the last signed Physiotherapy visit are shown as "Copied from <date>" and need the doctor's confirm (OQ-45-26). (BR45-20)
- Given a signed visit, then the assessment is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then no pain score, angle or test result is written to a log line or analytics event together with a patient. (BR45-5)
## Notes for design
Normal ranges and the tests for each joint are product data (C-6). The same Range of motion and Special tests sections are used by Orthopaedics (shared Specialty sections); this story renders them for Physiotherapy only.

### SAH-63-2 · As a physiotherapist, I want to record the treatment given in a session, a session note and the goals, so that each session is on the record
Priority: Should
Screen: EMR mockup #DoctorPhysio, mode "Session 4", "Treatment given today" chips, "Session note", "Goals · tap when achieved".
## Acceptance criteria
- Given the mode Session, then the header reads "Session n of m" and the package name (for example "Session 4 of 10" and "Knee package") taken from the open Rehab package course (SAH-58-1); a patient with no package shows "Session n" with no total. (BR45-27)
- Given "Treatment given today", then the chips offered are IFT (15 min · knee, 4-pole), Hot pack (10 min · before exercise), Ultrasound (1 MHz · 5 min), TENS (20 min · conventional), Exercise therapy (Supervised, 20 min) and Taping (Patella, unloading); tapping toggles a chip (a tick shows) and the parameter text beside it can be edited for this session. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-6). (BR45-4)
- Given the section Treatment modalities, then a modality the clinic adds beyond the six (typed) stays on this patient's record only, as for other typed list items (OQ-45-17). (BR45-22)
- Given a "Session note" (free text, limited in length), then it is saved with the visit and shown under Examination or Procedures on the case paper (SAH-55-1). (BR45-25)
- Given "Goals · tap when achieved", then the list of goals set at the first visit shows (the mockup's examples: "Short term: pain below 4 by session 5", "Knee bend to 120° by session 10", "Climb one floor of stairs without support"); each toggles to achieved with a tick and the date; the doctor can add and edit goals in the Treatment goals section. (BR45-6)
- Given a goal is achieved, then it stays shown as achieved in later sessions. (BR45-6)
- Given the footer, then it reads "Select at least one treatment given today." in red until a chip is on, and "Completing marks session n as used and books session n+1." in green after. (BR45-20)
- Given a signed visit, then the session is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given any call, then the note, goals and treatment given are not written to a log line or analytics event. (BR45-5)
## Notes for design
Modality parameters are free text defaults. The session count is read from the course, not typed.

### SAH-63-3 · As a physiotherapist, I want "Complete session & book next" to confirm the summary, use up the session and book the next one, so that the package stays on track
Priority: Must
Screen: EMR mockup #DoctorPhysio, "Complete session & book next", dialog "Complete session 4?", "Book session 5", slots.
## Acceptance criteria
- Given no treatment is ticked, when the doctor taps "Complete session & book next", then nothing is completed and the message reads "Pick the treatments given today first". (BR45-20)
- Given at least one treatment is ticked and the other required sections of the published layout are done (SAH-47-4, SAH-51-1), when the doctor taps the button, then the dialog "Complete session n?" opens with a short summary (for example pain score, treatment list, goals achieved) and "Book session n+1" with the slots offered. (BR45-27)
- Given the slots, then they are the doctor's next free appointment slots read from Appointments (SAH-11); the mockup's examples are Wed 17:00, Thu 10:00, Thu 18:30 and Fri 11:00. One slot is chosen. (BR45-27)
- Given the doctor confirms, then in one action: the visit is signed (SAH-51-2), session n is marked used in the package (the course sitting becomes Done, SAH-58-2), session n+1 is added as a planned sitting and an appointment is booked in the chosen slot through Appointments, and the message reads "Session n done · session n+1 booked <slot>". (BR45-27)
- Given the package has m sessions and session m is completed, then no next session is booked, the package shows "All sessions used" and the message reads "Session m done · package complete"; adding more sessions is a new course or a change of the package by the doctor (SAH-58-2). Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-6: package of 10 sessions). (BR45-27)
- Given a payment per session or a package advance, then the money follows SAH-58-3 and is collected by billing; this dialog collects nothing. (BR45-27)
- Given no free slot, then the dialog says "No free slot. Pick a date from Appointments." and the doctor can confirm with the session booked as a date only (analyst default, flagged for the human). (BR45-27)
- Given a failure part-way, then nothing is half done: the visit stays unsigned, the session count unchanged and the error shows with a retry. (BR45-27)
- Given the same confirm is tapped twice or retried after a lost network, then one signature, one used session and one appointment result. (BR45-27)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; a doctor who is not the visit's doctor (not owner), 403; another clinic's course, 404; no login, 401. (BR45-16, BR45-5)
- Given the audit, then one entry records who, when, visit id, course id and session number (no clinical text). (BR45-5)
## Notes for design
"Complete session & book next" is the Physiotherapy name of the Complete button (SAH-51). Slots are read through the Appointments `api`.

### SAH-63-4 · As a physiotherapist, I want an exercise programme with sets and times, a progress report and printing for the patient, so that the patient continues at home and the referring doctor is informed
Priority: Could
Screen: EMR mockup #DoctorPhysio, "Exercise library" in the section Home exercise program, "Progress report", "Send to patient on WhatsApp".
## Acceptance criteria
- Given the section Home exercise program (prints on the prescription at start), then the doctor picks exercises from the clinic's exercise list with sets and times (for example sets, repetitions, hold time); the list the doctor builds is saved with the visit and the patient. (BR45-6)
- Given the exercise library as a list screen (top-bar link "Exercise library"), then it is shown disabled with a "Later" note because no module has it (OQ-45-28); the doctor types an exercise name, which stays on the patient's record. (BR45-6)
- Given "Print", then the home exercise programme prints with the exercises, sets and times and the clinic header, with headings in English, Marathi or Hindi as the language choice of SAH-55-2; the Marathi and Hindi words are those of the case paper where the word is the same, and English otherwise until reviewed wording is supplied (OQ-45-32 default). Pictures of exercises need the documents service and are not printed (OQ-45-28). (BR45-25)
- Given the button "Send to patient on WhatsApp" (the mockup shows "n exercises sent on WhatsApp with pictures"), then it is shown switched off with the note "Needs the WhatsApp service". **Stays as built, switched off. Sending is Later (DEC-049).** (BR45-29)
- Given the button "Progress report", then a one-page report prints for the doctor who referred the patient: patient, diagnosis, sessions done out of planned, pain score first and last, range of motion first and last, goals achieved; the mockup message reads "Progress report (sessions 1–n) ready to share with referring doctor". Sharing is by print; sending is later. (BR45-6)
- Given there is no earlier session, then the report says "Only the first visit is recorded." (BR45-6)
- Given a nurse, front desk, billing, pharmacist or counsellor, 403; another clinic's visit, 404; no login, 401. (BR45-16, BR45-5)
- Given a printed programme or report, then it holds patient data; it is never logged and no copy is stored by the print action. (BR45-5)
## Notes for design
Exercise pictures, the exercise library page and WhatsApp delivery are later. The referring doctor's name is the "Referred by" fact of the history box (SAH-50-2) and is typed on the first visit.

## Out of scope for SAH-63
Frame (SAH-47); package and payment screens (SAH-58); the exercise library page and exercise pictures (OQ-45-28); WhatsApp delivery (SAH-68-5); billing per session beyond SAH-58-3; the Orthopaedics screen (generic template).

## Traceability
BR45-4 -> SAH-63-1, -2, -3. BR45-5 -> SAH-63-1 to SAH-63-4. BR45-6 -> SAH-63-1, -2, -4. BR45-16 -> SAH-63-1 to SAH-63-4. BR45-18 -> SAH-63-1, -2. BR45-20 -> SAH-63-1, -2, -3. BR45-22 -> SAH-63-2. BR45-25 -> SAH-63-2, -4. BR45-27 -> SAH-63-2, -3. BR45-29 -> SAH-63-4.

## Open questions
In `docs/requirements/SAH-45.md`: C-6 and B-1 (tagged), SO-45-24 (decided), OQ-45-6 (answered), OQ-45-27, OQ-45-28, OQ-45-30 (defaults accepted, DEC-039 addendum), OQ-45-32 (Marathi and Hindi wording, default English, accepted). Later (DEC-049): sending the programme on WhatsApp. Gap shown to the human: joints other than the knee have no drawn table.

## Status log
- 12 Nov 2026 · outline by the business-analyst; updated after DEC-039.
- 12 Nov 2026 (ADR-023 D13, DEC-044 addendum) · aligned: hidden sections by visit mode; outcome scales ODI, NDI, WOMAC, LEFS with typed scores (H20) and the "<scale> previous → today" chip. Awaiting human approval of `stories`.
- 12 Nov 2026 (phase 4) · full stories written from the source of EMR mockup #DoctorPhysio (NPRS, range table, special tests, modalities, goals, footer texts, dialog, slots); SAH-63-3 and SAH-63-4 added at the end of the block. Awaiting human approval of `stories`.
