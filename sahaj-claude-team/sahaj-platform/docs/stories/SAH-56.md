# SAH-56 · Stories: EMR list (complaints, diagnosis, tests, advice, procedures and services)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-22, BR45-5, BR45-6; decided sign-offs SO-45-22; answered questions OQ-45-17 "answered: default accepted (DEC-039 addendum)"; Marathi and Hindi advice language: DEC-039 answer 4; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30; carried over from SAH-43: OQ-43-6, OQ-43-7, OQ-43-8, OQ-43-9, OQ-43-10). Module: opd. Phase 3. Depends on SAH-47 (frame and generic renderer), SAH-43 (masters and ranking). Used by every specialty screen and by the generic renderer for Search-list fields.
Mockup screen: Queue (sidebar → Queue) (EMR mockup #EmrList, 900 px wide; it is embedded in the EMR sections Chief complaints, Diagnosis, Investigations, Advice and Procedures & services of every doctor screen).
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only doctor, visiting doctor and owner with doctor details (`clinic.consultation.write`) add, change or remove list items, on their own patients (owner: all). Nurse, front desk, billing, pharmacist and counsellor never use the list (403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body.
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). The starter items of every list are of this kind (C-10, OQ-43-8 clinician review still advised before real use).

The five kinds and their two attributes (from the mockup), used by the stories below:

| Kind | Name label | Attribute 1 (default) | Attribute 2 (default) | Empty text |
| --- | --- | --- | --- | --- |
| Complaints | Complaint | Duration: 1 day, 2 days, 3 days, 5 days, 1 week, 2 weeks, 1 month, Over 1 month (3 days) | Severity: Mild, Moderate, Severe (Moderate) | "No complaint added yet. Tap a most-used one below or + Add." |
| Diagnosis | Diagnosis (with ICD-10 code) | Type: Provisional, Final, Differential, Comorbidity (Provisional) | Status: Active, Chronic, Resolved (Active) | "No diagnosis yet. It is needed to complete the visit." |
| Investigations | Test | Priority: Routine, Urgent, Before next visit (Routine) | Where: Clinic lab, Outside lab (Clinic lab) | "No tests ordered." |
| Advice | Advice | Language: English, Marathi, Hindi (the clinic's Case paper language) | Send by: Print, WhatsApp, Print + WhatsApp (Print) | "No advice yet. Tap a most-used one below." |
| Procedures and services | Procedure / service | Qty: 1, 2, 3, 4 (1) | Billing: Bill today, No charge, In package (Bill today) | "Nothing added. Consultation fee is billed by the front desk." |

### SAH-56-1 · As a doctor, I want my most-used items as one-tap chips and a search over the master list, so that routine entries take few taps
Priority: Must
Screen: EMR mockup #EmrList, header, search box and "Your most used" chips.
## Acceptance criteria
- Given an EMR section that uses the list, when it shows, then it shows the section title (with a red star when the section is required), a count (for example "3 active", or "3 active · 1 disabled" when some are switched off), the two views "Chips" and "Table", a search box with the placeholder of the kind (for example "Type a complaint, e.g. fever", "Type a diagnosis or ICD-10 code", "Type a test, e.g. CBC", "Type advice, e.g. drink plenty of water", "Type a procedure or service") and a "+ Add" action. (BR45-22)
- Given the doctor has used items before, when the list shows, then "Your most used:" lists up to 7 items of the master that are not yet added to this visit, ranked by how often and then how recently this doctor used them (SAH-43-2); tapping a chip adds it at once and the chip leaves the row; the hint reads "Showing your most used first. Tap to add; keep tapping to add more." (BR45-22)
- Given the doctor has no usage yet, then the chips come from the starter list of the visit's department in the master's own order (OQ-43-9). The starter content per department is clinical list content: Rule tag. (BR45-22)
- Given the doctor types in the search box, when the text matches the start or any part of an item's name, or a diagnosis code (any letter case), then the matching master items show as chips to tap, ranked as above; given nothing matches, then the hint reads "Not in the list? Add it as new. It stays on this patient's record." (see SAH-56-4). (BR45-22)
- Given the doctor taps an item, then the message reads "<name> added" with an Undo; given Undo is tapped while the message shows, then the item is removed again. (BR45-22)
- Given an item with the same name (any letter case) is already on the visit, then it is not offered in the chips and typing it does not add it a second time. (BR45-22)
- Given an item is added, then the master item's use is counted for ranking only when the visit is signed (SAH-43-2, SAH-51), not at the tap. (BR45-22)
- Given a nurse, front desk, billing, pharmacist or counsellor user, or a doctor who is not the visit's doctor, when they call the add, change or remove action directly, then the answer is 403 and nothing changes; given no login, 401. (BR45-16)
- Given a visit of another clinic, then the answer is 404 and no item or master row is returned (tenant check; row-level security). (BR45-5)
- Given the master list cannot be loaded (slow or lost network), then the section shows an error with a retry, typing and "+ Add" still work for this patient, and nothing typed is lost. (BR45-22)
- Given any call, then no log line, audit value or analytics event holds an item's text, because it is clinical data. (BR45-5)
## Notes for design
Ranking, search and the master are SAH-43 (not rebuilt here). The mockup keys its starter lists by department (`spec`); the architect decides whether SAH-43 masters get a department tag (see SAH-45.md, "SAH-43 and SAH-44 after DEC-039"). Tap targets 44 px (OQ-45-30). Chips are buttons with an accessible name "Add <name>".

### SAH-56-2 · As a doctor, I want each item to carry its two attributes with sensible defaults, so that duration, severity, type, priority or quantity are recorded without extra typing
Priority: Must
Screen: EMR mockup #EmrList, row attributes by kind (table above).
## Acceptance criteria
- Given an item is added, then it carries the two attributes of its kind set to the defaults in the table above; the default Language of advice is the clinic's Case paper and Rx language (SAH-7), because the mockup's Marathi default is example data. (BR45-22)
- Given the doctor taps an attribute value, then the choices of that attribute show (only the values in the table) and the choice is saved with the row; given a value outside the table is sent, then the answer is 400 and nothing changes. (BR45-22)
- Given a Diagnosis item from the master, then its ICD-10 code shows beside the name and cannot be edited; given a diagnosis typed by the doctor, then it has no code and none is invented (SAH-56-4). (BR45-22)
- Given a kind with an empty list, then the empty text of the table shows; for Diagnosis the text "No diagnosis yet. It is needed to complete the visit." shows even when the section is optional, as drawn. (BR45-22)
- Given an Advice item, when the doctor picks Marathi or Hindi, then the item shows the Marathi or Hindi text of that advice in the Case paper (SAH-55) and on the printed advice (SAH-48-3); given the master has no text in that language, then the English text is used and the row shows a short note "No Marathi text, English used" (Marathi and Hindi advice text is an additive column of the SAH-43 master; see SAH-45.md). (BR45-22)
- Given an Advice item, when the doctor picks Send by "WhatsApp" or "Print + WhatsApp", then the choice is shown disabled with the note "Needs the WhatsApp service" and the default stays Print. **Stays as built, switched off. Delivery on WhatsApp is Later (DEC-049).** (BR45-29)
- Given a Procedures and services item with Billing "Bill today", then it becomes a bill line through the billing `api` (SAH-48-4); given "No charge" or "In package", then no bill line is created. (BR45-23)
- Given an attribute change, then the case paper and the prescription outputs read the new value at once. (BR45-22)
- Given the Duration choices of a complaint, then they include "Over 1 month" and the other seven values, in that order. (BR45-22)
## Notes for design
Attribute names and value sets come from the mockup and are fixed lists of the product (not clinic-editable). Whether a complaint's duration pre-fills the Case paper line "Headache · 2 days" is SAH-55-1.

### SAH-56-3 · As a doctor, I want a table view with Added on, Added by and an Active switch, so that I can review, disable or remove what I added
Priority: Should
Screen: EMR mockup #EmrList, view "Table".
## Acceptance criteria
- Given the doctor taps "Table", then the items show as rows with the columns "#", the kind's name label, Attribute 1, Attribute 2, code (diagnosis only), "Added on", "Added by", "Active" and a remove action; tapping "Chips" shows the chips again with the same data. (BR45-22)
- Given a row, then "Added on" shows the date and time in IST (for example 11 Nov 2026, 11:04 AM) and "Added by" shows the doctor's name as saved in Team and access (read through `platform.api`, not copied). (BR45-22)
- Given the doctor switches a row's Active off, then the row stays but is muted, it no longer counts, and it is left out of the case paper, the prescription outputs, the bill lines and the "Done" check of its section; the count reads "n active · m disabled". Switching it on restores all of these. (BR45-22)
- Given the doctor removes a row, then the message reads "<name> removed" with an Undo; given Undo is tapped while it shows, then the row returns with its attributes. (BR45-22)
- Given a narrow screen (tablet), then the table scrolls sideways and keeps the "#" and name columns in view. (BR45-22)
- Given a visit that is signed, then the table is read-only: no Active switch, no remove, no attribute change (corrections are an addendum, SAH-51). (BR45-18)
- Given a nurse, front desk, billing, pharmacist or counsellor, then the table cannot be opened (403); given another clinic's visit, 404. (BR45-5)
## Notes for design
The mockup's grid is at least 760 px wide. Row order is the order of adding; reordering is not drawn.

### SAH-56-4 · As a doctor, I want to type an item that is not in the list and keep it on this patient only, so that I can record what is true without changing the clinic's lists
Priority: Must
Screen: EMR mockup #EmrList, "+ Add" with the tag "New to list".
## Acceptance criteria
- Given the doctor types a name that is not in the master and taps "+ Add" (or Enter), then the item is added to this visit with the first letter capitalised, the default attributes of its kind and the tag "New to list"; the message reads "<name> added". (BR45-22)
- Given a typed item, then it stays on this patient's visit record only (decided, OQ-45-17): it is not added to the master, not offered to other doctors or other patients, not ranked and not counted for "Your most used". The mockup's hint "It is saved to your list for next time" is therefore not shown. (BR45-22)
- Given the tag text, then it is "New to list" as drawn. The list does not grow, so the human may later rename the tag; this is a note for design, not a held rule. (BR45-22)
- Given a typed diagnosis, then it carries no ICD-10 code; a code is shown only for items from the master. (BR45-22)
- Given the text is empty after trimming, then nothing is added and no error is shown; given it is longer than the allowed length, then the answer is 400 with a reason and nothing is stored. (BR45-22)
- Given the same text (any letter case) is already on the visit, then it is not added twice. (BR45-22)
- Given a typed item, then it is clinical data: tenant data with `tenant_id` and row-level security, and never in a log line, audit value or analytics event. (BR45-5)
- Given a nurse, front desk, billing, pharmacist or counsellor, or a doctor who is not the visit's doctor, then the add is refused with 403; another clinic's visit gives 404. (BR45-16)
## Notes for design
Clinic-added master rows ("organisation-level rows") are not built (OQ-45-17 decided: no). If the human later wants the list to grow, that is a new story with a review step.

## Out of scope for SAH-56
Editing the master lists (a screen to maintain them); clinic-added master items; reordering rows; the prescription medicine rows (SAH-48-1) and the medicine master; the choice of ICD-10 source and licence (SAH-43-4); the Case paper lines (SAH-55); delivery of advice on WhatsApp (Later, DEC-049).

## Traceability
BR45-5 -> SAH-56-1, -3, -4. BR45-6 -> SAH-56-1. BR45-16 -> SAH-56-1, -4. BR45-18 -> SAH-56-3. BR45-22 -> SAH-56-1 to SAH-56-4. BR45-23 -> SAH-56-2. BR45-29 -> SAH-56-2.

## Open questions
In `docs/requirements/SAH-45.md`: OQ-45-17 (answered: free text stays on the patient's record), OQ-45-28 (documents and other services; not used here), OQ-45-30 (44 px). In SAH-43 (still open, with defaults): OQ-43-7 (ICD-10 source), OQ-43-8 (clinician review of lists), OQ-43-9 (what counts as a use), OQ-43-10 (clinic-added items; consistent with OQ-45-17).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #EmrList (five kinds, attribute sets, defaults, empty texts, Added on / by, Active switch, "New to list"), DEC-039 and DEC-040. Replaces the outline. The mockup has five kinds (the outline said four). Awaiting human approval of `stories`.
