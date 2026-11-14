# SAH-73 · Optional patient date of birth
Module: patient (the opd module reads it through `patient.api`; the contract and the `PatientClinicalPort` addition are the architect's)
Mockup screen: Patients (sidebar → Patients)
Source: DEC-045 (human, chat, 12 Nov 2026; recorded 13 Nov 2026): "yes, a new patient story, optional date of birth at registration and on the patient record; age is worked out from it when present". Raised by the java-developer and QA (EMR phase 4, test plan `docs/test-plans/SAH-49-71.md`, finding F-3 and F-6). Written by the business-analyst, 13 Nov 2026.

## Problem
A patient is stored with a whole-number age only (SAH-8, `docs/api/patient.yaml`: `age` 0 to 120, required). That is enough for a list, but not for a child:
- the Paediatrics screen (SAH-62) needs the child's exact age in months and days, because the due and overdue vaccines, the weight band note and the milestone age band all depend on it; the schedule in the EMR mockup is in weeks and months ("6 weeks", "10 weeks", "9 months", "16–18 months");
- the mockup's Immunization section says "Due and overdue vaccines from date of birth";
- the dental Child tab opens for a patient under 13 (SAH-49-1), and a whole-number age is wrong for half of a year;
- a stored age goes stale: a child registered at "2" is still "2" years later.
Today a child registered with an age only cannot have a vaccine added in the EMR at all, and the vaccine states Due today and Overdue cannot be reached (QA finding F-6).

## What the mockup shows (quoted; this is the design)
Repo mockup `docs/design/mockup/sahaj-mockup.html`, screen Patients:
- The patient dialog ("New patient", "Add family member", the same dialog as the walk-in token dialog `resultsHTML`) has three fields: "Full name", "Age" (placeholder "Years") and "Sex" (F, M, O). Hint: "Name, age and sex are enough. ABHA can be added later." **There is no date of birth field.**
- The list row shows `{age} {sex} · {UHID} · {long-term condition}`, for example "34 F · SSC-00012". The record header shows "`{age}` yrs · Female". Family rows show "`{age}` {sex} · n visits".
- The filter chips "Children" (age under 13) and "60+" (age 60 or over) count by age.
- The duplicate notice reads "have the same mobile number, age and a similar name".
- The record page button "Edit details" is drawn as a "soon" placeholder: there is no edit form in the mockup.
- Nothing on the Patients screen shows a date of birth. The Data requests list (privacy screen) mentions "Patient asks to correct date of birth", so a correction is an expected event.

EMR mockup `docs/design/mockup/EMR-Configuration-Smart-Clinic.html`, window `DoctorPaeds`:
- Header: "[CHILD NAME]" then "18 months · Boy · UHID [ID] · Mother: [NAME] · Term, 2.9 kg at birth".
- "Milestones · 18 months". Immunization (IAP schedule): "Due and overdue vaccines from date of birth, with batch numbers and reminders", with age groups Birth, 6 weeks, 10 weeks, 14 weeks, 6–9 months, 9 months, 12 months, 15 months, 16–18 months.
- Case paper: meta "18 months · Boy"; the vaccination card prints the child's name and date of birth (SAH-62-2).
- The mockup has no screen anywhere that types a date of birth.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- **Front desk** and **owner** enter and correct it at the front desk (registration, patient record). These two roles write patients today (SAH-8).
- **Doctor**, **visiting doctor** and **owner with doctor details** see the age worked out from it in the Paediatrics screen and the dental chart, and print the vaccination card.
- **Nurse** reads the patient (as in SAH-8) and sees the derived age; the nurse never opens the EMR.
- **Billing** and **pharmacist** see the age only (a number as today), not the date.
- Same clinic only; the clinic always comes from the logged-in user.

## In scope / Out of scope
In scope: an optional date of birth on the patient (new registration, new family member, and the existing patient record); age worked out from it, shown as years, or as months and weeks or days for a small child; the whole-number age still accepted when the date is not known; validation and the conflict rule; existing patients unchanged; the privacy rules; the consumers (Paediatrics, dental Child tab, EMR header and case paper age).
Out of scope: date of birth from ABHA (integration is not in phase 1); year-only or "approximate" date flags; a patient-facing view; changing how Patients search, filters or duplicate checks work; any new screen; the IPD admission form's own age field (it picks a patient from Patients); bulk import of dates for existing patients; changing the vaccine, milestone, growth or dose reference data (SAH-62, accepted tagged values).

## Business rules (numbered)
Each rule maps to an acceptance criterion in `docs/stories/SAH-73.md` (traceability at its end).
- BR73-1. Date of birth is optional. A patient can be registered with a date of birth, with an age only, or with both (see BR73-6). Name, sex and mobile stay required as in SAH-8; age stays required **only when no date of birth is given**.
- BR73-2. The date of birth is a calendar date (day, month, year), no time. "Today" is always the date in India (IST).
- BR73-3. A date of birth must be today or in the past (a newborn registered on the day of birth is allowed), and must not make the patient older than 120 years (the same upper age limit as SAH-8). Anything else is refused with a field message.
- BR73-4. When a date of birth is present, the patient's age is **always worked out from it as of today**, never from a stored number. A child therefore ages on its own, and no one retypes it. Completed years: a patient born on 29 Feb has the birthday on 1 Mar in other years (default, SO-73-9).
- BR73-5. When no date of birth is present, the whole-number age as typed is used and does not change by itself, exactly as today (SAH-8, age 0 to 120).
- BR73-6. If a request carries both a date of birth and a typed age and they do not agree (the typed age is not the completed years worked out from the date), it is refused. The date of birth is the source of truth; the form never sends a typed age once a date is filled in (it shows the worked-out age, read-only) (SO-73-4).
- BR73-7. Age text: from 2 years, whole years ("34 yrs" on the record header, "34" in list rows, as today). Under 2 years the text is in months ("18 months", as the EMR header shows); under 3 months in weeks; under 2 weeks in days (SO-73-3). Paediatrics also needs months **and days** for the growth chart (SAH-62-1): the exact months and days come from the same date.
- BR73-8. Existing patients are not changed: they have no date of birth and keep their age and every screen they have today. No age is invented or guessed for anyone. No migration may guess a date from an age.
- BR73-9. The front desk or owner can add a date of birth to an existing patient, correct it, or clear it. Clearing leaves the whole-number age as it was worked out on that day (so the record is never left with no age). Every set, change and clear is audited (who, when, which patient id, which field). The audit record never holds the date itself.
- BR73-10. Search, filters and the duplicate check are unchanged in how they work. "Search by name, mobile or patient ID" does not search by date of birth. The duplicate check (same mobile, age within 2 years, similar name; SAH-8) and the chips "Children" (under 13) and "60+" use the age worked out by BR73-4 or BR73-5, so a patient with a date of birth is counted by the exact age.
- BR73-11. Merging two patients (SAH-8) keeps the surviving record's date of birth; if the surviving record has none and the merged-away record has one, it is copied over. If both have different dates, the surviving record's date is kept and the merge shows the notice "The two records have different dates of birth. The date on the kept record was kept." (text proposed, SO-73-8).
- BR73-12. Who sees the date itself: owner, doctor, visiting doctor, nurse and front desk (patient record, registration and edit form, the Paediatrics header, the vaccination card). Billing and pharmacist get only the age (SO-73-5). The patient list rows never carry the date; they carry the age only.
- BR73-13. Paediatrics (SAH-62) uses the date of birth for: the age in the header ("18 months · Boy"), the child's age in months and days in "Growth today", the weight note's age band, the Milestones age band, the vaccine states Given, Due today and Overdue, and the date printed on the vaccination card. **It does not change the dose-by-weight maths**: that uses today's recorded weight only and never an estimate from age (SAH-62-3, OQ-43-23).
- BR73-14. When a child has no date of birth, Paediatrics keeps its current "not recorded" behaviour (SAH-62): no vaccine can be added; the weight note and the milestone card say "not available for this age" and "Milestones for this age are not set up". The screen shows a plain line "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age." (text proposed, SO-73-6). The age shown in the header is the whole-number age, never a made-up month count.
- BR73-15. The dental chart Adult and Child tab (SAH-49-1, "a patient under 13 opens on Child", an analyst default flagged for the human) uses the exact age worked out from the date of birth when present, and the whole-number age otherwise. The doctor can still switch the tab.
- BR73-16. Correcting a date of birth after clinical use re-works the age, vaccine due and overdue states, weight band and milestone band from then on. A vaccine already recorded as given stays given with its date; nothing recorded earlier is changed or deleted.
- BR73-17. The Patients screen shows the date of birth only where a person must read it (registration and edit form, patient record). The mockup's other age displays (list rows, family rows, queue cards, billing) show the age text only.
- BR73-18. All writes follow SAH-8: owner and front desk only (403 otherwise), tenant isolation by the logged-in user (another clinic's patient is 404), invalid input 400 with field errors.

## Patient-safety and privacy points
- A wrong date of birth gives a wrong vaccine schedule (a vaccine shown Due today that is not due, or a missed one). That is why a future date, a date over 120 years, and a typed age that disagrees with the date are all refused, not corrected silently, and why a correction is audited.
- Date of birth with name and mobile identifies a person. It is personal data: **never written to logs, error messages, audit values, URLs, query strings or exports to other tenants**. The audit holds "date of birth changed" only. The list endpoint and the Patients search never return or accept it. Filters and the duplicate check use the age.
- The date is returned only to the roles in BR73-12. It travels over the normal authenticated API only, and is stored in India with the rest of the patient record.
- No age is guessed for existing patients, and no vaccine or centile is shown for a child whose date is unknown (never an estimate from the age number).
- The vaccination card prints the child's name and date of birth, as already approved in SAH-62-2; nothing else new is printed.
- No real patient data in tests or seed data.

## Plan or entitlement (which plan/module unlocks it)
Patients is a core module in every plan (SAH-8). The date of birth field is available on every plan. The Paediatrics and dental screens that use it follow their own entitlement (SAH-45 EMR configuration), unchanged.

## Open questions for the human
Numbered sign-off items. Each has a default the team will use unless the human says otherwise. Items 1 and 2 are the registration screen changes that the repo mockup does not show, so they need the human's sign-off (DEC-045).
- **SO-73-1 · New "Date of birth" field in the New patient dialog (registration and add family member).** The mockup shows only "Full name", "Age" (placeholder "Years"), "Sex". Default: add one optional field "Date of birth" after Age (a day, month, year date control; typed or picked), hint "Optional. If you know it, the age is worked out for you." When a date is chosen, "Age" shows the worked-out age as read-only text ("2 yrs", "18 months") and is not typed. Clearing the date makes Age typeable again. The hint "Name, age and sex are enough. ABHA can be added later." stays. Field order: Full name, Age, Date of birth, Sex.
- **SO-73-2 · Adding or correcting a date of birth on an existing patient.** The mockup's record button "Edit details" is a "soon" placeholder (no edit form drawn); DEC-045 asks for the date "on the patient record". Default: the button "Edit details" on the record header opens a small sheet with only "Date of birth" and "Age" (same behaviour as SO-73-1) and Save and Cancel; no other field becomes editable in this story. The API for the other fields (SAH-8 PATCH) is unchanged.
- **SO-73-3 · Age text for small children on the Patients screens.** The mockup shows "{age} yrs" or "{age}" only, and the EMR shows "18 months". Default: BR73-7 (months under 2 years, weeks under 3 months, days under 2 weeks, whole years from 2). List and family rows show "18 months F", record header "18 months · Female".
- **SO-73-4 · Typed age and date of birth disagree.** Default: the date of birth wins and the form shows the worked-out age (read-only); if a client still sends both and they disagree, the API answers 400 on the field `age`: "Age does not match the date of birth." The alternative (silently ignore the typed age) hides an error; not chosen.
- **SO-73-5 · Who sees the date itself.** Default BR73-12 (owner, doctor, visiting doctor, nurse, front desk; billing and pharmacist see the age only). The alternative is every clinic role, as for the other patient fields in SAH-8.
- **SO-73-6 · Text in Paediatrics when there is no date of birth.** "Date of birth not recorded. Add it in Patients to see due vaccines and the child's age." (not drawn in the mockup). Default as written; no link or edit from inside the EMR.
- **SO-73-7 · Registration message texts, not in the mockup.** Default: "Date of birth cannot be in the future." and "Date of birth must be within the last 120 years." and "Enter a valid date of birth." and "Age does not match the date of birth."
- **SO-73-8 · Merge with different dates of birth.** Default BR73-11, notice text as written.
- **SO-73-9 · Birthdays on 29 February.** Default: the birthday in a non-leap year is 1 March.
- **SO-73-10 · Legal.** Is a date of birth covered by the patient's existing "records" consent (SAH-17), or is a separate consent or notice needed under the Digital Personal Data Protection Act and the clinic's own policy? Default: covered by the existing records consent; the analyst does not decide this legal point, the human confirms.
- **SO-73-11 · Approximate dates.** Many families know only a year or a rough age. Default: no "year only" or "approximate" flag in this story; those patients keep a whole-number age and Paediatrics shows "not recorded". A flag is a later story if the human wants it.
- **SO-73-12 · Carried over, already flagged in SAH-49 and SAH-62, unchanged here:** the dental Child tab under 13 rule, and the mapping from age to vaccine window. Both stay the analyst readings accepted in DEC-039 until the human changes them; the date only makes the age exact.
