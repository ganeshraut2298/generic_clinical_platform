# SAH-59 · Stories: Form builder (clinic-defined forms: field types, show-if logic, scores and bands, versions and approval, print, starter forms)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-28, BR45-3, BR45-4, BR45-5, BR45-6, BR45-15, BR45-29; decided sign-offs SO-45-1 (form builder is in scope), SO-45-9, SO-45-24, SO-45-25; answered questions OQ-45-13 (forms approved by the owner), OQ-45-14, OQ-45-16 "answered: default accepted (DEC-039 addendum)"; rules C-9 form scales and L-1 MLC; answered, default accepted (DEC-039 addendum): OQ-45-27 (handwriting pad), OQ-45-28 (photo, file, signature), OQ-45-30). Module: opd. Phase 3. Depends on SAH-45 (the page and the custom-section list), SAH-47 (renderer), SAH-57 (Body map block), SAH-48 (Medicine block), SAH-56 (Diagnosis block), SAH-49-1 (Tooth chart block, phase 4; the block shows disabled until it exists), SAH-55 (print language headings). Large: six stories, about three to four sprints; build in the order 59-1, 59-4, 59-5, 59-2, 59-3, 59-6.
Mockup screen: Clinic setup → Specialty template, Case paper and Rx (EMR mockup #FormBuilder, 1440 px wide; opened from "+ Form builder" and "Open form builder" on EMR mockup #Main).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only the owner builds, saves, sends for approval, approves and publishes forms (a new permission name, decided by the architect; "Medical director" and "admin" in the mockup and the brief mean owner, OQ-45-13, OQ-45-12). Every other clinic role may open the list of forms and the Preview read-only; they cannot change anything (403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. Forms and their versions are tenant data (`tenant_id`, row-level security); the six starter forms are global reference data (BR45-3).
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). All scales, cut-offs, bands, normal ranges and starter-form content are of this kind (C-9).
Field-editing note: the brief asks to "edit fields" in the configurator; in #Main the fields list is read-only and "+ Add field" has no action (SO-45-9). Field editing is done here, for clinic forms only; library sections stay read-only.

### SAH-59-1 · As the owner, I want to build a form from field types and field settings, so that we can capture our own chart
Priority: Must
Screen: EMR mockup #FormBuilder, modes Build / Preview / Print, left palette "Fields", form name, form canvas, settings panel for the selected field.
## Acceptance criteria
- Given the owner opens the Form builder, then the page shows "Form builder — custom clinical forms", the path "Settings / EMR configuration / Form builder", a field for "Form name", the buttons "Save draft" and the publish button (SAH-59-4), the three modes Build, Preview and Print, a palette "Fields" and the canvas with the hint "Drag a field onto the form, or tap to add it below the selected field." (BR45-28)
- Given the palette, then it offers 23 field types in three groups. Basic fields: Short text, Long text, Number, Date, Time, Yes / No, Single choice, Multiple choice, Dropdown. Clinical: Vitals block (BP, pulse, SpO2, temperature, weight, height), Body map (uses the shared body map: pins, findings, what happened), Tooth chart (uses the shared FDI tooth chart), Medicine list (medicine, dose, frequency, days; allergy check), Lab tests (pick tests; results come back here), Diagnosis (ICD-10), Score (Σ), Score band. Layout: Section heading, Info note, Signature, Photo / image, File upload, Handwriting pad. (BR45-28)
- Given the owner drags a field type onto the form, or taps it, then the field is added at the drop place or below the selected field, selected, and its settings show; a keyboard way to add and to move a field up or down is provided (accessibility, not drawn). (BR45-28)
- Given a selected field, then the settings panel offers: label; help text; unit; "min" and "max" (hard limits) and the normal range "from" and "to"; options with their scores ("+ Add option", "score is used by Σ fields"); "Width on the form" (the form is a six-column grid: choices from one to six columns as drawn); "Print on paper"; "Copy from last visit"; "Who can fill it" (Doctor, Nurse, Reception, Patient); "Standard code" (free text such as "LOINC 32443-7" or a SNOMED CT code, stored for reports; exchange with ABDM is Later, DEC-049); the show-if rule (SAH-59-2); and, for Score and Score band fields, their own settings (SAH-59-3). Only the settings that fit the type are shown. (BR45-28)
- Given the owner taps Copy or Delete on a field, then the field is duplicated below or removed; Delete shows an Undo message; a field that other fields use (a score source or a show-if source) cannot be deleted until those uses are removed, and the message says which. (BR45-28)
- Given the form, then a line shows the field count (for example "12 fields · 2 with logic · 3 scored") and fields keep their order; the label of each field is required, 1 to 200 characters. (BR45-28)
- Given the types Photo / image, File upload, Signature and Handwriting pad, then they are placed on the form as drawn but their controls are shown disabled with a "Later" note in Preview and in the EMR, and no file or ink is stored until the documents service exists (OQ-45-28, OQ-45-27). (BR45-28)
- Given the types Body map, Tooth chart, Medicine list, Lab tests and Diagnosis, then they render the shared components of SAH-57, SAH-49-1 (when built), SAH-48 and SAH-56 and store their values with the form; Lab tests list tests but sends nothing to a lab (OQ-45-28). (BR45-6)
- Given a form with no field, then it cannot be sent for approval or published (message "Add at least one field"); Save draft is always allowed. (BR45-28)
- Given a field label, option text or help text, then it is stored as text only (no markup is run), and an over-long value gives 400 on a direct call. (BR45-28)
- Given a doctor, visiting doctor, nurse, front desk, billing, pharmacist or counsellor, then the builder is read-only and every change call is refused with 403; given no login, 401; given another clinic's form, 404. (BR45-15)
## Notes for design
Field model in the mockup: id, type, label, help, required, width (1 to 6), options with scores, unit, min, max, normal range, show-if, score sources, rules, bands, source, print, copy, who can fill, standard code. The architect fixes the type names and the limits (field count per form, label length).

### SAH-59-2 · As the owner, I want a field to show only when an earlier answer says so, so that forms stay short
Priority: Should
Screen: EMR mockup #FormBuilder, "Extra rules" · "Show this field" · "Always" / "if <field> is <value>", badge "Shows if <field> = <value>".
## Acceptance criteria
- Given a field that comes after a Yes / No, Single choice, Multiple choice or Dropdown field, then "Show this field" offers "Always" or "if <earlier field> is <value>", where the earlier field is any of those four types above it on the form and the value is one of its options. (BR45-28)
- Given a field with a rule, then its card on the canvas shows the badge "Shows if <field label> = <value>" in Build mode and the field is shown dimmed. (BR45-28)
- Given Preview or the EMR section, when the earlier field has the value, then the field shows; when it does not (or is empty), then the field is hidden. For a Multiple choice source the field shows when the value is among those chosen. (BR45-28)
- Given a field is hidden by its rule, then it is treated as empty: it is not required, it is not counted in "required fields filled", it adds no score, it is not printed, and any value typed earlier is not saved. (analyst decision, flagged for the human; the mockup does not say) (BR45-28)
- Given the source of a rule is itself hidden by a rule, then the dependent field is hidden too (chains are allowed and evaluated top to bottom). (analyst decision, flagged) (BR45-28)
- Given the owner deletes or moves the source field below the dependent field, then the rule is removed or refused with a message and the form is never left with a rule that points to a later or missing field. (BR45-28)
- Given a field with a rule, then the Print mode prints it only when shown. (BR45-28)
- Given a non-owner, 403 on any change. (BR45-15)
## Notes for design
Example from the mockup: "Ulcer present now" (Yes / No) shows "Ulcer site, size and depth" and "Photo of the ulcer" when Yes; "Fasting 6 hours" = No shows the note "Not fasting: postpone unless emergency."

### SAH-59-3 · As the owner, I want scores that add up fields and bands that classify the total, and ranges that flag odd numbers, so that scales like the diabetic foot risk score work
Priority: Should
Screen: EMR mockup #FormBuilder, Score (Σ) "Adds up the scores of", Score band, "Values outside the normal range are flagged in red on the form."
## Acceptance criteria
- Given a choice field, then each option can carry a score (0 if none), and a "Scored" badge shows on the field in Build mode. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-9). (BR45-28)
- Given a Score (Σ) field, then "Adds up the scores of" lets the owner pick the fields to add (choice fields with scores, and rule points); the total is worked out live in Preview and in the EMR from the chosen options (for a Multiple choice field the scores of all chosen options add up). A field with no answer adds 0 and the total shows as blank until at least one source is answered. (BR45-28)
- Given a Score (Σ) field with points rules, then the owner can add rules that give points when a number field is below a value (for example monofilament sites felt below 8 gives 1 point) or when the number of chosen options in a choice field is below a value (for example fewer than 4 pulses felt gives 1 point), and these points add to the total. Rule tag as above. (BR45-28)
- Given a Score band field, then it takes a source (a Score field), and a list of bands, each with a "from" number and a label (for example "from 0: Low risk: check every year", "from 1: Moderate: check every 6 months", "from 3: High: check every 1–3 months, refer"); the band shown is the one with the highest "from" not above the total. Rule tag as above. (BR45-28)
- Given the band, then it is shown with a colour that moves from green through amber to red as the band rises, and the label text, so colour is not the only signal. (BR45-28)
- Given a Number field with "min" and "max", then a value outside shows the error "Outside <min>–<max>" and is not accepted on save; given a value inside the limits but outside the normal range "from" and "to", then it is accepted and flagged in red with the word "Abnormal" in Preview and in the EMR, and the Preview line reads "n value(s) outside the normal range". Rule tag as above. (BR45-28)
- Given the bands or ranges are missing or overlap in a way that gives no band for a total, then the form can still be saved as a draft but cannot be published; the message names the field. (BR45-28)
- Given a non-owner, 403 on any change. (BR45-15)
## Notes for design
The band cut-offs, scores and normal ranges are those of the mockup's starter forms and are not clinical sources. Standard codes (SNOMED CT, LOINC) are stored text only.

### SAH-59-4 · As the owner, I want form settings, versions and an approval step, so that records keep the version they were filled in and a form reaches doctors only when it is approved
Priority: Must
Screen: EMR mockup #FormBuilder, right panel "Form settings" and "Versions", header chip "v3 · draft", "Save draft", "Send for approval" / "Publish".
## Acceptance criteria
- Given Form settings, then the owner sets: "Use in departments" (the clinic's departments; plus the places Emergency and IPD wards, read as the Emergency assessment and the IPD daily progress note of SAH-52), "EMR part" (Assessment or Treatment), "Visit types" (New consult, Follow-up, Procedure visit, Teleconsult, IPD daily round), "Who can fill" (Doctor, Nurse, Reception, Patient), "Print language" (English, English + Marathi, English + Hindi) and "Approval before publishing" (On or Off; On by default). (BR45-28)
- Given the header, then a chip shows the version and state: "v3 · draft", "v3 · waiting approval" or "v3 · live". Save draft stores the draft and the message reads "Draft saved". (BR45-28)
- Given approval is On, when the owner taps "Send for approval", then the state becomes "waiting approval" and the message says the version was sent for approval and in which departments it will appear; the owner (the approver, OQ-45-13) then approves or sends it back with a reason, and after approval the state becomes "live". A clinic with one owner approves its own forms. (BR45-28)
- Given approval is Off, then the button reads "Publish" and the state becomes "live" at once. (BR45-28)
- Given no department or place is chosen, when the owner taps "Send for approval" or "Publish", then nothing happens and the message reads "Pick at least one department in Form settings first." (BR45-28)
- Given a form is live, then it appears as a custom section (tag "Custom") in the sections list of the chosen departments in Clinic setup (SAH-45-2), where the owner can show or hide, require, move and reorder it like any section; given the owner removes a department from the settings and publishes, then the section leaves that department for visits opened afterwards. (BR45-28)
- Given a live form is edited, then a new draft version (v4) is made and the live v3 stays in use until v4 is live; the "Versions" list shows every version with its state and the text "Records already filled keep the version they were filled in." A visit keeps the form version it was opened with (SAH-46-3), and a signed record never changes. (BR45-18)
- Given the choice "Patient" in "Who can fill", then it is stored and shown; the product has no patient screen, so a patient fills the form only with help at the desk (a doctor or front desk types it). Sending a link to the patient on WhatsApp is **Later (DEC-049)**. (BR45-29)
- Given "Who can fill" lists Nurse or Reception, then it is stored and shown; only the doctor writes the EMR in phase 2; nurse vitals pre-fill is the separate future story SAH-72 (OQ-45-14, decided; SO-45-43). (BR45-28)
- Given Teleconsult in "Visit types", then it can be ticked, but no call is started (OQ-45-27). (BR45-28)
- Given two owners edit the same form, when the second saves with an older revision, then the answer is 409 and nothing is applied. (BR45-15)
- Given each save draft, send for approval, approval, publish or retire, then one audit entry is written with who, when, form id, version and the before and after of the form settings (no patient data). (BR45-15)
- Given a non-owner, then these controls are hidden and a direct call is refused with 403; another clinic's form, 404. (BR45-15)
## Notes for design
Forms are part of OPD with no plan flag (OQ-45-16, decided). A retired form keeps all records. Whether "Use in departments" accepts all 15 starter departments or only the clinic's own is for the architect (default: the clinic's own departments, as in SAH-45).

### SAH-59-5 · As a doctor and as the owner, I want to preview, fill and print a form in English with Marathi or Hindi, and save what I fill to the patient's record, so that the form is useful in the visit
Priority: Must
Screen: EMR mockup #FormBuilder, modes Preview and Print, "Save to patient record", "Values come from the Preview tab.", print language.
## Acceptance criteria
- Given the owner taps Preview, then the form shows as a doctor will fill it, with example data only (no patient), live scores and bands, the show-if rules at work and the status line: "n required field(s) missing: <names>" after a save is tried, or "n value(s) outside the normal range", or "x of y required fields filled" (only shown required fields count); "Clear" empties the example values. (BR45-28)
- Given a published form, when a doctor opens the EMR of a patient in a department where it is used, then it appears as a custom section (SAH-47-3) that renders the same way, is filled by the doctor, and is saved with the visit and its Save draft (SAH-47-5). (BR45-28)
- Given the doctor fills the section, then "Save to patient record" writes the values as a form entry (form id, version, values, time, doctor) to the patient's record through the patient `api`, and the entry shows in the patient's history (SAH-50-4); in the builder's own Preview the button is shown disabled with the note "Fill the form from a patient's EMR to save it", because the builder has no patient. (BR45-28)
- Given the owner taps Print, then the printed form shows the clinic header (name, address, phone), "Patient", "Age / sex", "UHID", the fields that have "Print on paper" on and are shown, and the line "Signature · Dr. <name> · Reg. no. <number>", at the chosen paper size. (BR45-28)
- Given the Print language is "English + Marathi" or "English + Hindi", then the standard words (Patient, Age / sex, UHID, Signature, Reg. no.) print in both languages, and each field label prints in English and, when the owner has typed a Marathi or Hindi label for it, in the second language too (the label fields appear in the field settings only for those languages; analyst addition, since the mockup shows the language choice but not where the translations are typed). (BR45-28)
- Given a field with "Copy from last visit" on, then its value is pre-filled from the patient's last signed entry of the same form and marked "Copied from <date>" as in SAH-47-3. (BR45-20)
- Given a signed visit, then the form section is read-only. (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, then they cannot fill or save a form entry (403); given another clinic's patient, 404. (BR45-16, BR45-5)
- Given the values filled, then they are clinical data: never in a log line, audit value or analytics event. (BR45-5)
## Notes for design
Handwriting, photo and file fields are disabled (OQ-45-27, OQ-45-28). The printed form is a browser print like the case paper (SAH-55); a PDF for WhatsApp is Later (DEC-049).

### SAH-59-6 · As the owner, I want to start from a form clinics in India already use, so that I do not build from nothing
Priority: Should
Screen: EMR mockup #FormBuilder, "Start from a form clinics in India already use. Everything stays editable." and the starter list.
## Acceptance criteria
- Given the owner opens the starter list, then it offers six forms with a name, a one-line description and the departments they suit: Diabetic foot examination ("Risk score, ulcer follow-up, advice"; General Medicine, Diabetology), Glasgow Coma Scale ("Eye, verbal, motor · total and severity"; General Medicine, Emergency), Morse fall risk scale ("IPD and day care · nursing"; IPD wards), MLC injury report ("Medico-legal case · body map · police details"; General Medicine, Emergency, Orthopaedics), Pre-anaesthetic check-up ("ASA grade, airway, fasting, consent"; Surgery, Dental, Obstetrics & Gynaecology) and Untitled form ("Start with an empty page"). Each starts in the part Assessment. (BR45-28)
- Given the owner picks one, then a copy opens in Build mode as the clinic's own draft v1 with all its fields, options, scores, show-if rules, bands and ranges, fully editable; the starter itself never changes. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-9, L-1 for the MLC form). (BR45-28)
- Given the Glasgow Coma Scale starter, then it holds the eye, verbal and motor choices with scores, a "GCS total" and a "Severity" band with Severe (3–8), Moderate (9–12) and Mild (13–15). Rule tag as above. (BR45-28)
- Given the Diabetic foot starter, then it holds monofilament sites felt (of 10, normal range 8 to 10, LOINC 32443-7), vibration sense, pulses (DP and PT, right and left), "Ulcer present now" with the show-if fields, a risk score with points rules and the three-band "Risk category". Rule tag as above. (BR45-28)
- Given the MLC injury report starter, then it uses the Body map block and the police details fields and carries the same MLC wording as SAH-57-3. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (L-1). (BR45-28)
- Given the Pre-anaesthetic starter, then it holds ASA grade I to IV, fasting 6 hours (Yes / No, with the show-if note), vitals, tests needed and "Consent signed". Rule tag as above. (BR45-28)
- Given the starter list is global reference data, then no clinic can change or delete it (403) and it holds no patient data; another clinic's copies are never visible. (BR45-3)
- Given a non-owner, the list is readable but "Use this starter" is refused (403). (BR45-15)
## Notes for design
The starters are the mockup's content; the architect and db-engineer seed them from the file. They are examples a clinic must review before real use (OQ-43-8 advice).

## Out of scope for SAH-59
Sending a form link to a patient on WhatsApp (Later, DEC-049); a patient-facing screen; handwriting recognition, photo, file and signature capture (OQ-45-27 and OQ-45-28 defaults); a lab or radiology order from the Lab tests block; editing library sections of the 15 departments; ABDM exchange of standard codes (Later, DEC-049); importing or exporting forms; per-visit-type configuration of forms beyond the "Visit types" tick list.

## Traceability
BR45-3 -> SAH-59-6. BR45-4 -> SAH-59-3, -6. BR45-5 -> SAH-59-5. BR45-6 -> SAH-59-1. BR45-15 -> SAH-59-1 to SAH-59-6. BR45-16 -> SAH-59-5. BR45-18 -> SAH-59-4, -5. BR45-20 -> SAH-59-5. BR45-28 -> SAH-59-1 to SAH-59-6. BR45-29 -> SAH-59-4.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-9 (field editing lives here), SO-45-24 (roles), OQ-45-13 (answered: owner approves), OQ-45-14 (who can fill: stored and shown), OQ-45-16 (no plan flag), OQ-45-27 (handwriting pad Later), OQ-45-28 (photo, file, signature Later), OQ-45-30 (44 px). Later (DEC-049): the patient link on WhatsApp (SAH-59-4). Analyst decisions flagged inside the criteria for the human's review: how hidden show-if fields count (59-2), where Marathi and Hindi labels are typed (59-5), the meaning of "Emergency" and "IPD wards" in "Use in" (59-4).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #FormBuilder (23 types, field settings, show-if, scores, bands, ranges, form settings, versions, approval, preview, print, six starter forms), DEC-039 and DEC-040. Replaces the outline; no longer held for the BRD or the clinical owner (tagged instead). Awaiting human approval of `stories`.
