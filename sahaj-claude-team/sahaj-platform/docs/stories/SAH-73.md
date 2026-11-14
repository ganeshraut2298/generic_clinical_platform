# SAH-73 · Stories: Optional patient date of birth
Requirement: `docs/requirements/SAH-73.md` (rules BR73-1 to BR73-18; sign-off items SO-73-1 to SO-73-12). Module: patient (read by opd through `patient.api`). Source: DEC-045.
Mockup screen: Patients (sidebar → Patients)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Writing a patient: owner and front desk only (as SAH-8); every other role gets 403. "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request; another clinic's patient is 404.
**Registration screen differences from the repo mockup (needs human sign-off before the screen is built):** the New patient dialog has no date of birth field (SO-73-1) and "Edit details" is a "soon" button (SO-73-2). The age display for small children (SO-73-3) and the message texts (SO-73-7) are also not in the mockup. Defaults are stated in each story. Story 4 and 5 serve the EMR (SAH-62, SAH-49) and follow DEC-045.

### SAH-73-1 · As front desk, I want to enter an optional date of birth when I register a patient or add a family member, so that a child's exact age is known from day one
Priority: Must
Screen: Patients, "New patient" and "Add family member" dialog (mockup fields "Full name", "Age" with placeholder "Years", "Sex"; hint "Name, age and sex are enough. ABHA can be added later.").
## Acceptance criteria
- Given the New patient dialog (from "New patient" or "Add family member"), then it shows "Full name", "Age", the new optional "Date of birth", and "Sex", in that order, with the hint "Optional. If you know it, the age is worked out for you."; the existing hint "Name, age and sex are enough. ABHA can be added later." stays. (BR73-1, SO-73-1; default, to be signed off)
- Given a valid name, sex and mobile and a date of birth of 10 Jun 2025 (and today 13 Nov 2026), when I choose Save, then the patient is registered with that date, the Age shows "17 months" read-only before saving, and the saved record shows "17 months". (BR73-1, BR73-4, BR73-7)
- Given I type an age of 34 and leave the date empty, when I save, then the patient is registered with age 34 exactly as today and has no date of birth. (BR73-1, BR73-5)
- Given I fill in the date, then the Age box shows the worked-out age as read-only text and cannot be typed; given I clear the date, then Age can be typed again and keeps no stale value. (BR73-6, SO-73-1)
- Given neither a date of birth nor an age, when I save, then it is refused with the existing inline message for age and nothing is saved. (BR73-1, SAH-8 criterion 10)
- Given a date of birth later than today (IST), when I save, then it is refused with "Date of birth cannot be in the future." under the field, the other values stay, and nothing is saved. (BR73-3, SO-73-7)
- Given a date more than 120 years ago, when I save, then it is refused with "Date of birth must be within the last 120 years." (BR73-3, SO-73-7)
- Given a date that does not exist (for example 31 Feb 2026) or text that is not a date, then the field shows "Enter a valid date of birth." and the API answers 400 with the field error on `dateOfBirth`. (BR73-2, SO-73-7)
- Given today's date, then it is accepted (a baby born today). (BR73-3)
- Given an API request that carries a date of birth and a typed age that does not match the completed years from the date, then it answers 400 with a field error on `age`: "Age does not match the date of birth."; the form itself never sends both once a date is filled. (BR73-6, SO-73-4)
- Given a "records" consent answer in the dialog (SAH-17), then it is recorded as today; the date of birth adds no new consent question. (SO-73-10, default)
- Given a mobile number that already has patients, when I add a family member with a date of birth, then they are saved under the same mobile as a family member, as in SAH-8. (BR73-1)
- Given a nurse, billing, pharmacist, doctor, visiting doctor or counsellor, then the registration controls are hidden and a direct call to the API returns 403; given no login, 401. (BR73-18)
- Given the save is slow or the connection drops, then Save is disabled while it runs (no double registration), the typed values stay on the form, and a plain error is shown with Retry. (SAH-8)
- Given a registration, then the audit records who, when and the patient id; **no date of birth value, name or mobile is written to any log or audit value**. (BR73-9, BR73-18)
## Notes for design
New optional field `dateOfBirth` (ISO date, no time) on the patient create request and response; `age` required only when `dateOfBirth` is absent. Contract change in `docs/api/patient.yaml` (architect). Today in IST. The registration dialog is the mockup's shared walk-in dialog (`resultsHTML`); the Patients "New patient" button and "Add family member" open it. The architect decides how the age text for small children is produced (server or screen) and whether the stored `age` column is kept only for patients with no date.

### SAH-73-2 · As front desk, I want to add, correct or clear the date of birth on an existing patient, so that records made before this change can be completed and a wrong date can be fixed
Priority: Must
Screen: Patients, record page header button "Edit details" (drawn as a "soon" placeholder in the mockup).
## Acceptance criteria
- Given a patient record, when the owner or front desk chooses "Edit details", then a sheet opens with "Date of birth" and "Age" (same behaviour as SAH-73-1) and Save and Cancel; no other field is editable in this story. (SO-73-2, default, to be signed off)
- Given an existing patient registered before this change, then the record has no date of birth and shows its age exactly as before; nothing is changed or guessed for anyone. (BR73-8)
- Given I add a date of birth of 4 Mar 2020 to a patient whose age was 5, when I save, then the record shows the age worked out from the date from then on, and the old whole-number age is no longer used. (BR73-4)
- Given a patient who has a date of birth, when I change it, then the age, the vaccine states, the weight band and the milestone band follow the new date from the next time they are read; vaccines already recorded as given keep their dates; nothing recorded earlier is changed. (BR73-16)
- Given I clear the date, then the age stays as the whole number worked out on that day, and the record is never left with no age. (BR73-9)
- Given any set, change or clear of the date, then one audit event is written with who, when, the patient id and the field name, and **never the date value**. (BR73-9)
- Given a date in the future, over 120 years ago, invalid, or conflicting with a typed age, then the same refusals and messages as SAH-73-1 apply, and nothing is saved. (BR73-3, BR73-6)
- Given the sheet is saved while a doctor has the child's EMR open, then the doctor sees the new age the next time the EMR is read; nothing is pushed or overwritten on screen. (BR73-16)
- Given a nurse, doctor, visiting doctor, billing, pharmacist or counsellor, then "Edit details" is hidden and the API returns 403; given another clinic's patient id, 404; given no login, 401. (BR73-18)
- Given two records are merged (SAH-8) and the kept record has no date while the merged one has, then the date is copied to the kept record; given both have different dates, then the kept record's date stays and the notice "The two records have different dates of birth. The date on the kept record was kept." is shown. (BR73-11, SO-73-8)
- Given the save fails or is slow, then the sheet stays open with the entered values and a plain error with Retry. (SAH-8)
## Notes for design
SAH-8 already has a PATCH for patient fields; the date of birth is one more optional field in it, with clearing as an explicit null. Optimistic locking follows SAH-8 (the record already has a `version`). The "Edit details" sheet is a new screen shape not drawn in the mockup: keep it as small as the dialog of SAH-73-1.

### SAH-73-3 · As clinic staff, I want the age shown from the date of birth, in years or months for small children, so that the age is always right without retyping it
Priority: Must
Screen: Patients: list rows, patient record header, family list, filter chips, duplicate notice.
## Acceptance criteria
- Given a patient with a date of birth, then every place that shows the age (list row "`{age}` F · SSC-00012", record header "`{age}` · Female", family row, and the age that other screens read from the patient: queue cards, bills, the IPD admission pick) shows the age worked out as of today in IST, not a stored number. (BR73-4, BR73-17)
- Given a patient of 34 years, then the list row reads "34 F" and the record header "34 yrs · Female", unchanged from the mockup. (BR73-7)
- Given a child of 18 months, then the list row reads "18 months F" and the record header "18 months · Female"; given 10 weeks, "10 weeks"; given 9 days, "9 days". (BR73-7, SO-73-3)
- Given a patient born 29 Feb 2020, then on 28 Feb 2027 they are 6 years and on 1 Mar 2027 they are 7 years. (BR73-4, SO-73-9)
- Given a patient with no date of birth, then the age is the stored whole number, shown as before, and does not change over time. (BR73-5, BR73-8)
- Given the filter chip "Children" (under 13) and "60+" (60 or over), then each counts and lists by the worked-out age for patients with a date and by the stored age for the others; a child who turns 13 today moves out of "Children" that day. (BR73-10)
- Given the duplicate check at registration (same mobile, age within 2 years, similar name) and the notice "have the same mobile number, age and a similar name", then it works on the same worked-out age and its behaviour and text are unchanged; it never compares dates of birth directly. (BR73-10)
- Given the search box "Search by name, mobile or patient ID", then typing a date of birth finds nothing by date; search works as before. (BR73-10)
- Given the patient list or any list response, then it carries no date of birth. (BR73-12, BR73-17)
- Given the patient record, then the owner, doctor, visiting doctor, nurse and front desk see the date of birth as "13 Jun 2025" in the header details; billing and pharmacist do not see it and see the age text only. (BR73-12, SO-73-5)
- Given a patient of another clinic, then it is never listed or counted (404 by id). (BR73-18)
- Given a date of birth, then it never appears in a URL, a query string, a log line, a browser tab title or an error message. (Privacy)
## Notes for design
The architect decides whether the age is computed in the database, the service or on the screen, as long as all modules see the same number on the same day. The "Children" chip and the duplicate check keep their thresholds. UI text: plain English, sentence case, "24 Oct 2026".

### SAH-73-4 · As a doctor, I want Paediatrics to use the child's date of birth, so that due and overdue vaccines, the growth age, the milestones and the printed vaccination card are right
Priority: Must
Screen: EMR mockup #DoctorPaeds (header "18 months · Boy · UHID [ID] · Mother: [NAME]", Growth chart, Milestones · 18 months, Immunization · IAP schedule, Print vaccination card). This story completes SAH-62; the Paediatrics screens themselves are SAH-62's.
## Acceptance criteria
- Given a child with a date of birth, when the doctor, visiting doctor or owner with doctor details opens the EMR, then the header shows the child's age in months ("18 months · Boy"), and "Growth today" shows the age in months and days worked out from the date. (BR73-13, SAH-62-1)
- Given the weight bands, then the weight note uses the band whose age range holds the child's age; for an age with no band it reads "Centile not available for this age" and the point is still plotted (SAH-62-1 unchanged). (BR73-13)
- Given the milestone card, then it uses the age band of the child; for an age with no band it reads "Milestones for this age are not set up" (SAH-62-2 unchanged). (BR73-13)
- Given each vaccine of the schedule, then it shows Given (with its date), Due today or Overdue, worked out from the date of birth with the window rule of SAH-62-2: a vaccine whose group's age window holds the child's age today and is not given is Due today; one whose group's age ends before the child's age and is not given is Overdue. (BR73-13, SO-73-12)
- Given a Due today or Overdue vaccine, then the doctor can add it as given today as in SAH-62-2; given a vaccine that is not due, then it cannot be added (400). (SAH-62-2, BR73-13)
- Given the button "Print vaccination card", then the printed card shows the child's name and date of birth ("13 Jun 2025"), every vaccine with its state, and the next due vaccine with its age. (BR73-13, SAH-62-2)
- Given the child's date of birth is corrected after vaccines were given, then given vaccines stay given with their dates and the due and overdue states are worked out again from the new date. (BR73-16)
- Given a child with no date of birth, then no vaccine can be added; the weight note reads "Centile not available for this age"; the milestone card reads "Milestones for this age are not set up"; the header shows the whole-number age; and the screen shows "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age." (BR73-14, SO-73-6)
- Given the dose-by-weight card (SAH-62-3), then it is unchanged: it uses today's weight only, never the age, and the date of birth adds no estimate. (BR73-13)
- Given the case paper of a Paediatrics visit, then the meta line shows the age text of BR73-7 ("18 months · Boy"); given another department, the age shows as before. (BR73-13, BR73-17)
- Given a nurse, front desk, billing, pharmacist or counsellor, then the EMR is not open to them (403 on the EMR calls), as in SAH-62; the nurse sees the age only on the patient screens. (BR73-12)
- Given another clinic's visit or patient, then 404; given no login, 401. (BR73-18)
- Given the EMR calls, the printed card and every log line, then the date of birth is shown only in the printed card and the Patients screens and is never logged. (Privacy)
## Notes for design
`PatientClinicalPort` (patient.api) gains a nullable `dateOfBirth` (ADR-023 decision D11); the opd module never reads the patient table directly. The architect updates `docs/api/opd.yaml` where the header age or the card need it, and decides how the age in months and days is carried. The Immunization reference data, milestone bands and weight bands are unchanged (SAH-62, tagged C-5, accepted in DEC-039/DEC-044, not clinically reviewed). QA's blocked tests (F-3, F-6 in `docs/test-plans/SAH-49-71.md`) become possible once this story is built.

### SAH-73-5 · As a doctor in the Dental department, I want the chart to open on the Child tab for a patient under 13, using the exact age, so that I start on the right teeth
Priority: Should
Screen: EMR mockup dental chart, tabs "Adult" (32 teeth) and "Child" (20 teeth) (SAH-49-1).
## Acceptance criteria
- Given a patient with a date of birth who is under 13 today (for example 12 years and 11 months), when the doctor opens the dental chart, then the Child tab is open; given exactly 13 years today or more, then the Adult tab is open. (BR73-15)
- Given a patient with no date of birth, then the tab opens by the whole-number age as before (under 13 opens on Child). (BR73-15, BR73-5)
- Given the doctor switches the tab, then the chart shows the other set and keeps everything recorded; the open tab is not a stored clinical fact. (SAH-49-1, BR73-15)
- Given a patient of another clinic or a role without access, then 404 or 403 as in SAH-49; given no login, 401. (BR73-18)
- Given a child chart, then only the 20 primary teeth show and the adult teeth stay in the record, unchanged. (SAH-49-1)
## Notes for design
The under-13 rule is the analyst's default flagged in SAH-49-1 (SO-73-12); this story only makes the age exact. The field `childTabDefault` already exists in `docs/api/opd.yaml` ("Patient under 13 (needs date of birth)").

## Out of scope for SAH-73
Date of birth from ABHA; year-only or approximate flags; changing search, filters or duplicate rules; editing any other patient field in the "Edit details" sheet; changing vaccine, milestone, growth or dose reference data; the IPD admission form's own age field (it picks a patient from Patients); patient-facing display; bulk import; WhatsApp reminders (Later, DEC-049).

## Traceability
BR73-1 -> 73-1. BR73-2 -> 73-1. BR73-3 -> 73-1, 73-2. BR73-4 -> 73-1, 73-2, 73-3. BR73-5 -> 73-1, 73-3, 73-5. BR73-6 -> 73-1, 73-2. BR73-7 -> 73-1, 73-3, 73-4. BR73-8 -> 73-2, 73-3. BR73-9 -> 73-1, 73-2. BR73-10 -> 73-3. BR73-11 -> 73-2. BR73-12 -> 73-3, 73-4. BR73-13 -> 73-4. BR73-14 -> 73-4. BR73-15 -> 73-5. BR73-16 -> 73-2, 73-4. BR73-17 -> 73-3, 73-4. BR73-18 -> 73-1 to 73-5.

## Open questions
SO-73-1 to SO-73-12 in `docs/requirements/SAH-73.md`. Needing the human before the screens are built: SO-73-1 (date field in the New patient dialog) and SO-73-2 (Edit details sheet), both registration changes that the repo mockup does not show. Needing a legal answer: SO-73-10. All others have defaults.

## Status log
- 13 Nov 2026 · requirement and stories written by the business-analyst from DEC-045; not yet approved.
