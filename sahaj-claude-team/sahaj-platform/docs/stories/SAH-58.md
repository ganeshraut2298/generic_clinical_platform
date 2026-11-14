# SAH-58 · Stories: Multi-visit treatment course and payments (OPD and IPD)
Requirement: `docs/requirements/SAH-45.md` (rules BR45-27, BR45-4, BR45-5, BR45-1; decided sign-offs SO-45-1, SO-45-23, SO-45-32; billing rules B-1 to B-4; answered, default accepted (DEC-039 addendum): OQ-45-28, OQ-45-30; carried over from SAH-43 and the built billing stories: SAH-10 billing counter, SAH-23 admission and deposits, SAH-25 running bill). Module: opd for the course; billing (through its `api`) for estimates, advances, part payments, deposits and receipts; ipd (through its `api`) for admissions and day care. Phase 3. Depends on SAH-47 (frame), SAH-10, SAH-23, SAH-25; SAH-51 (a sitting is done when its visit is signed; phase 5). The specialty screens that start a course are SAH-49, SAH-61, SAH-63, SAH-64 and SAH-66 (phase 4).
Mockup screen: Queue (sidebar → Queue) (EMR mockup #TreatmentCourse, 1000 px wide; opened from the specialty screens "Treatment course", "Pregnancy care", "Chemotherapy course", "Surgery episode", "Rehab package").
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Doctor, visiting doctor and owner with doctor details start a course, plan and change its sittings. Payment is collected only by the roles that may collect at the billing counter today (SAH-10: billing, and owner; front desk where SAH-10 allows it); a doctor sees the money columns but cannot collect. Nurse and pharmacist see no course (403 on a direct call). "Not signed in" gets 401. The clinic always comes from the logged-in user, never from the request body. Amounts show as ₹ 1,25,000.
Rule tag: where a story says "Rule tag", the rule is "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed" (BR45-4). Every payment rule here is of this kind (B-1). Sample rates of the mockup are never used as prices (B-2); real prices come from the clinic tariff (SAH-7, SAH-18).

### SAH-58-1 · As a doctor and as billing, I want one running course for a multi-visit treatment with an estimate and a timeline or table of its visits, so that each visit and payment is tied to it
Priority: Must
Screen: EMR mockup #TreatmentCourse, header, views "Visits" (timeline) and "Table".
## Acceptance criteria
- Given a course exists, then the page shows its name (for example "Dental treatment course", "Pregnancy care · ANC course", "Chemotherapy course", "Surgery episode", "Rehab package · 10 sessions"), its id (for example TC-0412), a sub line (start date, doctor, notes such as "estimate signed by patient"), the unit word (Sitting, Visit, Cycle, Step or Session) and the one-line note "one running account: advances and part payments carry across visits". (BR45-27)
- Given the course, then the page shows the course estimate (the sum of planned and done charges), the amount paid, the credit from advances and deposits, and the balance, all in ₹ with Indian digit grouping. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-1). (BR45-27)
- Given the view "Visits", then each sitting is a card with its number (for example S3), date, encounter chip (OPD, IPD or Day care) and encounter id (for example OPD-10231, DC-3310), a status chip (Done, Today or Planned), the items done or planned, the charge and a note; given the view "Table", then the same data shows as rows with the columns "#", "Date", "Encounter", "Work", "Charge" and "Status". (BR45-27)
- Given a doctor starts a course from a specialty screen, then it opens with the kind, the name, the planned sittings and their charges taken from that screen (dental estimate SAH-69-1 from the plan SAH-49-2, ANC booking SAH-61, package SAH-63, surgery plan and estimate SAH-64-3 and SAH-71-1, chemo protocol SAH-66 and SAH-70-1); given the doctor starts one from the section "Treatment course" without a plan, then it opens with the kind and name only and sittings are added by SAH-58-2. (BR45-27)
- Given a planned charge, then it is the clinic's tariff price for the item (SAH-7, SAH-18) or the package price the doctor sets; sample rates are not used. (BR45-27)
- Given a patient has more than one course, then the page lists them and opens one at a time; a course belongs to one patient and one clinic. (BR45-27)
- Given a nurse or pharmacist (or counsellor), then the course cannot be read (403); given front desk or billing, the course is read-only except for payment (SAH-58-3); given another clinic's patient, 404. (BR45-5)
- Given a slow network, then a loading state shows; given a failure, an error with a retry. (BR45-27)
- Given any call, then no log line holds the patient's name or the clinical items of a sitting. (BR45-5)
## Notes for design
Whether a course is a new concept in billing or in opd (and what the billing `api` offers) is the architect's decision; the screen shows one running account either way. The page can be opened by a doctor from the EMR and by billing from the billing counter.

### SAH-58-2 · As a doctor, I want each sitting to open its own OPD visit and link IPD and day-care admissions to the same course, so that all work and money of one treatment sit together
Priority: Must
Screen: EMR mockup #TreatmentCourse, sitting cards, "Add" options, "Admit now", "Admission request".
## Acceptance criteria
- Given a sitting is planned for a date, when the patient arrives, then the sitting opens its own OPD visit (Queue and EMR as usual, SAH-46) linked to the course; the sitting's status becomes Today, and Done when that visit is signed (SAH-51). (BR45-27)
- Given the doctor taps "Add" on the course, then the choices are those of the course kind (dental: OPD sitting, Day-care admission, IPD admission; ANC: ANC visit, Emergency admission, Delivery admission; the other kinds have their own list as drawn), and choosing one adds a planned sitting with the message "<choice> added to the course". An OPD choice copies the last sitting's charge; an IPD choice has charge 0 and the note "Admission request. Rate set at admission." (BR45-27)
- Given a planned sitting is changed to day care or IPD, then the message reads "Will be done as a day-care admission. Admission request created." and an admission request is created in IPD through the ipd `api` (SAH-23) with the course id; given the clinic's IPD is off, then the choices IPD and Day care are not offered. (BR45-27)
- Given a planned IPD sitting, when the doctor taps "Admit now", then the patient is admitted through the existing admission sheet (SAH-23), the sitting shows the admission id (for example IP-2232), and the message reads "Admitted · <id>. Advance from this course is carried to the IPD bill." (BR45-27)
- Given an admission linked to a course, then the IPD running bill (SAH-25) shows the advance carried from the course and the course shows the admission's charges when the admission is billed. (BR45-27)
- Given a sitting is Done, then its items and charge can no longer be changed except by an addendum on its visit (SAH-51); given it is Planned, the doctor can change the date, items and charge. (BR45-18)
- Given two sittings are added at the same moment, then both are kept and numbered in order; given the same add is tapped twice quickly, then one sitting results. (BR45-27)
- Given a nurse, billing, pharmacist or counsellor tries to add or change a sitting, then the answer is 403; given a doctor who is not the course's doctor and not the owner, 403. (BR45-16)
- Given another clinic's course, 404. (BR45-5)
## Notes for design
Admission, deposits and running bill are existing IPD and billing features reused through their `api` packages, not rebuilt (SO-45-32, BR45-1). The mockup's IPD ids (IP-n, DC-n) are examples; the real ids come from IPD.

### SAH-58-3 · As billing, I want to collect a payment against the course as a visit payment, full balance, part payment, advance or IPD deposit, using advance credit first, so that money is tied to the right course
Priority: Must
Screen: EMR mockup #TreatmentCourse, panel "Collect payment" with payment types, "Use advance balance first", amount and mode.
## Acceptance criteria
- Given the payment panel, then the payment types offered are: "Today's <unit>" (today's charge plus any earlier due, less advance credit when "Use advance balance first" is ticked), "Full course balance" ("Clears the whole estimate"), "Part payment" ("Any amount now, rest later"), and "Advance" (or "Advance / IPD deposit" when the next planned sitting is an admission); the payment is recorded as Visit payment, Full balance, Part payment, Advance or IPD deposit. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-1). (BR45-27)
- Given the checkbox "Use advance balance first (₹ x credit)" is ticked, then credit from advances and deposits is used before cash is asked, and the amount due shows after credit; given it is unticked, the full amount is asked and the credit stays. Rule tag as above. (BR45-27)
- Given "Part payment" is chosen, then the suggested amount is half of today's amount rounded to ₹100, at least ₹500 and at most the balance; given "Advance / IPD deposit" for a planned admission, then the suggested amount is 40% of that admission's charge rounded to ₹500. The doctor or billing can type any other amount. Rule tag: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (B-1). (BR45-27)
- Given an amount, then it must be a positive amount in rupees; given zero, negative or more than the course balance for a Visit payment or Full balance, then the answer is 400 with a reason and nothing is recorded; an Advance or IPD deposit may exceed the balance because it stays as credit. (BR45-27)
- Given a mode UPI, Cash or Card is chosen (UPI first), then it is recorded with the payment; the payment gateway and card machine are not part of phase 1 (CLAUDE.md), so the mode is a record of how the money was received. (BR45-27)
- Given billing taps "Collect payment", then the payment is recorded once through the billing `api` (SAH-10) with a receipt number from billing's own numbering, the balance and credit update at once, and the message reads like "Payment collected · Receipt RC-<n>". (BR45-27)
- Given the same payment is tapped twice quickly or retried after a lost network, then exactly one payment is recorded. (BR45-27)
- Given the note "Charges are added when a <unit> is marked done. Advances and IPD deposits stay as credit and are used first. Balance on the course: ₹ x.", then it shows below the panel with the real balance. (BR45-27)
- Given an IPD deposit with a due week (for example "Admission deposit is due by 36 weeks."), then the note is stored on the sitting as text and shown; the product does not remind or block on it. Rule tag as above. (BR45-27)
- Given a doctor, nurse, pharmacist or counsellor, then the payment panel is hidden and a direct call is refused with 403; given no login, 401; given another clinic's course, 404. (BR45-16)
- Given any payment, then the audit holds who, when, course id, type, mode and amount, and never the patient's name, mobile number or clinical items. (BR45-5)
## Notes for design
Schemes (MJPJAY, PM-JAY) and TPA pre-authorisation are compliance sections of the specialty screens (SAH-71-1, SAH-66-2) and are not collected here. Refunds follow SAH-10 rules and are not drawn. The mockup's modes UPI, Cash and Card are labels only until the integration gateway exists.

### SAH-58-4 · As billing and as a doctor, I want a list of receipts and adjustments for the course, so that every rupee can be traced
Priority: Must
Screen: EMR mockup #TreatmentCourse, "Receipts and adjustments" table.
## Acceptance criteria
- Given payments exist, then the table "Receipts and adjustments" lists them newest last with the columns "Date", "Type" (Visit payment, Full balance, Part payment, Advance, IPD deposit), "Mode", "Against" (for example "Course advance", "Sitting 2", "Visits 2–3"), "Amount" and "Receipt" (the receipt number). (BR45-27)
- Given an advance or deposit is used against a sitting, then an adjustment line shows with the sitting in "Against" and a negative effect on the credit; the amounts of payments and adjustments always add up to paid, credit and balance shown on the page. (BR45-27)
- Given there is no payment yet, then the table shows an empty text and the balance equals the estimate less done charges. (BR45-27)
- Given a payment line, then it cannot be changed or deleted here; a correction is made in the billing counter by its own rules (SAH-10) and shows as a new adjustment line. (BR45-27)
- Given a doctor, then the table is read-only; given nurse, pharmacist or counsellor, 403; given another clinic's course, 404. (BR45-16, BR45-5)
- Given the amounts, then paid + credit − charges done = balance holds to the rupee; a test compares the totals with the billing counter. (BR45-27)
## Notes for design
Receipt numbers are billing's own (the mockup's RC-2201 is an example).

### SAH-58-5 · As billing, I want to print a receipt and send it on WhatsApp, so that the patient has proof of payment
Priority: Should
Screen: EMR mockup #TreatmentCourse, "Receipt" column of "Receipts and adjustments".
## Acceptance criteria
- Given a receipt number in the table, when billing taps it, then the receipt opens in the billing counter's existing receipt view for printing (SAH-10). (BR45-27)
- Given the services exist, when billing taps "Send on WhatsApp", then a preview of the message shows and the patient's WhatsApp consent is checked before sending. **Later (DEC-049)** (provider, template approval, PDF library, patient opt-in); until then the button is shown switched off with the note "Needs the WhatsApp service" (stays as built). (BR45-29)
- Given a send, then the audit holds who, when, receipt number and channel, never the patient's mobile number. **Later (DEC-049).** (BR45-5)
- Given a doctor, nurse, pharmacist or counsellor, then the print and send actions are not available (403). (BR45-16)
## Notes for design
The print part uses the existing receipt view and needs nothing from the Later (DEC-049) sending work.

## Out of scope for SAH-58
Scheme and TPA pre-authorisation, patient share and claim submission (SAH-71-1, SAH-66-2 compliance sections); refunds and write-offs; the payment gateway and card machine (integration gateway, not in phase 1); discount rules; the clinic tariff itself (SAH-7, SAH-18); GST invoices beyond the existing billing counter.

## Traceability
BR45-1 -> SAH-58-2. BR45-4 -> SAH-58-1, -3. BR45-5 -> SAH-58-1 to SAH-58-5. BR45-16 -> SAH-58-2 to SAH-58-5. BR45-18 -> SAH-58-2. BR45-27 -> SAH-58-1 to SAH-58-5. BR45-29 -> SAH-58-5.

## Open questions
In `docs/requirements/SAH-45.md`: SO-45-23 (decided: treatment course in scope, rules B-1 tagged), SO-45-32 (reuse repo screens), OQ-45-28 (gateway and Lab are Later), OQ-45-30 (44 px). Later (DEC-049): WhatsApp receipt (SAH-58-5). For the architect: whether a course lives in billing or opd; how an advance becomes an IPD deposit (SAH-23).

## Status log
- 12 Nov 2026 · full stories written by the business-analyst from the source of EMR mockup #TreatmentCourse (course kinds and units, timeline and table, add choices, Admit now, payment types, credit first, suggested amounts, ledger), DEC-039 and DEC-040. Replaces the outline; no longer held for the billing owner (rules tagged instead). Awaiting human approval of `stories`.
