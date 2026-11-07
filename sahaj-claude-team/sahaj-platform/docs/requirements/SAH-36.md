# SAH-36 · Admitted patients as uniform cards (Cards/Table view, search, ward filter, flags, card actions)
Module: ipd
Mockup screen: IPD → Admitted — the toolbar (`pctools`: search `pcq`, ward select, view switch Cards/Table, cards-per-row 2/3/4 `pcColsSeg`), the uniform card (`pcCard`, `pcgrid cols-N uni`, `pcf` flags, `pcacts`), "Showing N of M patients" with "Show 24 more" / "Show all" (`pcmore`), and the empty text. The Standard card as the mockup ships it (`pcDefault`).
Source: Human through the tech lead, 7 Nov 2026 (parallel lane 2). Remaining part of SAH-24 in `docs/process/playbook.md` ("Admitted list and configurable patient card"); rows 7, 8, 9 of the IPD inventory in `docs/stories/SAH-35.md` section 0.

## Problem
Beds and admissions → Admitted shows a plain seven-column table with an "Open" button. The mockup shows each admitted patient as a card that carries the things a nurse or doctor scans on a round: who and where, how long, which doctor, and small coloured flags that say what needs attention (an early-warning escalation nobody has seen, a score, an allergy, vitals due, a low deposit, going home today), with the daily actions one tap away (Nursing, EMR, Transfer, Discharge). Today a nurse must open each patient's admission sheet to find Nursing or EMR (OQ-30-1, OQ-31-7), and nothing on the list warns about a patient who is getting worse. A 60-bed ward also cannot be searched or narrowed to one ward.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- Nurse (ward, nurses' station, bedside tablet): scans cards, sees clinical flags, opens Nursing and EMR, may move a patient.
- Doctor (ward round, doctor's room): scans cards, sees clinical flags, opens Nursing (read) and EMR, starts discharge.
- Owner: sees everything on the card and every action.
- Front desk (IPD desk): sees who is where, Going home today and Deposit low; may move a patient; sees no vitals, score, allergy or escalation flags.
- Billing, pharmacist, visiting doctor: do not have the Admitted list today (list roles are OWNER, FRONT_DESK, NURSE, DOCTOR in `docs/api/ipd.yaml`); unchanged by this story.
- Setting: IPD page, Admitted tab, on a desktop, a nurses' station screen or a tablet (cards per row fall to 2, then 1 on narrow screens as the mockup does).

## In scope / Out of scope
In scope (the smallest slice that needs no human design decision):
1. Cards/Table switch (Cards is the default; Table is today's table, unchanged) and "Cards per row" 2, 3 or 4 (default 3).
2. The Sahaj Standard card with fixed content (not customisable): header (initials, name, UHID, age and sex, bed), flags line, Admitted on, Length of stay, Ward, Room, Doctor, and the action row.
3. Search ("Name, MRN or bed" in the mockup) and the ward filter, on both views.
4. "Showing N of M patients", "Show 24 more", "Show all", and the empty text "No patients match these filters."
5. Card flags whose data already exists in the system: Deposit low and Going home today (already in the list), and, read from existing records, Not seen N min, Doctor reviewing (SAH-33), EWS (SAH-30), Vitals due (SAH-30), Allergy (patient record). Each flag follows the mockup's role rules for the default card.
6. Card actions that open what already exists: Nursing (SAH-30/32 sheet), EMR (SAH-31 sheet), Transfer and Discharge (SAH-26/27, inside the admission sheet), and the patient's name, which opens the admission sheet ("Open bill and bed details").
7. Refresh of the cards every 60 seconds while the Admitted tab is on screen, so "Not seen N min" does not go stale.

Out of scope (each is a separate story or a human decision):
- **Customize card / Clinic setup → Patient card** (layout Compact/Standard/Expanded, sections, fields and labels, quick actions, workflow stages, draft and publish, versions, preview, locked safety fields, presets). Candidate story **SAH-37**. This story ships the Standard card as the mockup's default and nothing is editable. The owner-only "Customize card" button is not shown (no dead button).
- **My patients, the scope notice ("As Nurse you see patients in their wards ...") and Clinic setup → Roles and access.** Blocked on the ward-scoping design (OQ-33-2, OQ-30-3): phase 1 has no ward or patient assignment. Every role that can read the list sees every admitted patient, as today. The "My patients" button is not shown.
- **Workflow stage**: the stage chip on the card, the stage filter chips and the "Update stage" picker. No stage exists in the data or in the contract. The default stages are clinical workflow names (Under treatment, Fit for discharge ...) that need a decision (OQ-36-2). Candidate for a later story.
- **Dose overdue flag**: depends on SAH-35 (Medicine round), which supplies late doses per patient. Not built here; the card keeps no gap for it. If SAH-35 is merged first, adding the flag is a small follow-up (nurse and owner only, as the mockup).
- **Emergency access flag** (SAH-34, blocked).
- Card sections not named above: the vitals tiles and last-vitals line, Diagnosis and Allergies lines, Assigned nurse (no nurse assignment exists), Running bill, Deposit and Payer lines, Compact and Expanded layouts, the last nursing note.
- **Case paper** action and **Update stage** action (print is "soon" in the mockup itself; stage as above).
- **Deposit** quick action on the card: off in the mockup's default card (OQ-36-3). The deposit is still reached by the patient's name.
- A discharged-patients list, sorting, saved filters, server-side search.

## Business rules (numbered)
1. **Views.** Admitted shows a toolbar and then either Cards or Table. Cards is the default. The choice and the cards-per-row choice are kept for the visit only (in memory); they are reset when the page is reloaded. Table is the existing table; it is not redesigned.
2. **Cards per row.** 2, 3 or 4 (default 3). The mockup falls to 2 per row at 1100 px width or less and to 1 per row at 700 px or less, whatever is chosen. All cards in a row have the same height.
3. **Who sees which patients.** Exactly the admitted patients of the clinic that the Admitted list returns today, to OWNER, FRONT_DESK, NURSE, DOCTOR. No role is narrowed to "own" patients or wards in this story (OQ-33-2). A discharged patient is not shown.
4. **The card (Standard).** Top to bottom: header (round initials, patient name as a button, then "MRN <id> · <age> · <sex in full>" and the bed block "Bed <code>"); the flags line; "Admitted on", "Length of stay" ("Day 1 (today)" on the first day, otherwise "<N> days"), "Ward", "Room" ("—" when the ward has no rooms); "Doctor"; the action row. A missing age or sex shows "—". The patient name is always shown. Anything the role may not see is not sent by the server, not merely hidden.
5. **Search.** Matches, without regard to case, part of the patient name, the UHID, the bed code or the IP number. It filters the loaded admitted patients on the device; the typed text is never sent to the server, written to a log, a URL or the browser's storage.
6. **Ward filter.** "All wards" and the clinic's wards; shows only patients whose bed is in that ward. Search and ward filter work together (both must match).
7. **Paging.** At most 24 cards are drawn; "Showing N of M patients" with "Show 24 more" and "Show all" appears when there are more. A change of search, ward or view starts again at 24.
8. **Empty states.** Nobody admitted: "No one is admitted right now." (as today). Filters match nobody: "No patients match these filters."
9. **Flags, in this order**, shown only when true and only to the roles named: "Not seen · N min" (red) or "Doctor reviewing" (amber) for an open or acknowledged early-warning escalation (OWNER, DOCTOR, NURSE); "Allergy: <text>" (red) when an allergy is recorded (OWNER, DOCTOR, NURSE); "EWS <score> · <Medium|High>" (amber for Medium, red for High) when the latest score is not Low (OWNER, DOCTOR, NURSE); "Vitals due" (amber) when vitals are due by the SAH-30 rule (OWNER, DOCTOR, NURSE); "Going home today" (blue) (all four roles); "Deposit low" (red) (OWNER and FRONT_DESK only, the default card hides billing from doctors and nurses). Every flag has an icon and words: colour is never the only signal.
10. **Overflow.** The mockup shows one flag and "+N" for the rest. Safety flags (Not seen, Doctor reviewing, Allergy, EWS) are never folded into "+N"; the other flags may be (OQ-36-1).
11. **Critical card.** A patient whose latest score is High has a red outline on the card (mockup `pc-crit`), in addition to the flag text.
12. **Actions.** The row shows only the actions the role may use and the server allows (see story 5): Nursing, EMR, Transfer, Discharge. Case paper, Update stage and Deposit are not on the card (out of scope list). Actions follow the clinic's IPD state (Trial, On, Read-only) as the admission sheet already does.
13. **Refresh.** While the Admitted tab and the browser tab are on screen, the cards reload every 60 seconds and after any action that changed something. A failed reload keeps the last data with "Could not refresh. Showing data from <time>." (the SAH-33 text). All minute counts come from the server clock, never the device.
14. **Tenant and role.** The clinic is taken from the signed-in user, never from the request. The role is read again from the clinic membership on the server. Another clinic's patients are never returned.
15. **No new clinical rule.** Every flag is an existing rule shown again (SAH-27 going home, SAH-25 deposit low, SAH-30 score and vitals due, SAH-33 escalation, patient allergy record). The list does not decide a risk or a due time itself.

## Patient-safety and privacy points
- The list is a quick-look screen: a flag that is missing is as dangerous as a wrong one. The flags come from the server's own rules; the browser does not compute a score, a due time or an allergy match (the SAH-31 allergy matching stays on the server).
- Safety flags are never hidden behind "+N" (rule 10) and not by colour alone. An allergy flag shows the recorded text, cut by width with the full text in the accessible name and tooltip; it is never cut in the data.
- "Allergy not recorded" and "None known" show no flag, as the mockup. A patient whose allergy is not recorded is therefore not flagged (OQ-36-6).
- The card puts clinical data (escalation, score, allergy) for many patients on one screen, left on a shared nurses' station screen. The list does not write a record-access row per patient per refresh (same rule as the Admitted list, the Safety board DEC-015 and the pharmacist list note). Opening a patient's sheet writes the rows it writes today. Human to confirm (OQ-36-7).
- Front desk never receives vitals, score, escalation or allergy data (not just hidden): the mockup's default card gives that role none.
- No patient name, mobile, UHID, search text or clinical text is written to application logs or audit events by this story. No patient data is put in the URL or in `localStorage`.
- Nothing says a doctor or nurse was alerted: the screen only shows state (no notifications in phase 1).
- Wrong-patient risk: cards of different patients sit side by side. Each action's accessible name and each sheet that opens carry the patient's name; the sheets keep their own identity bar.

## Plan or entitlement (which plan/module unlocks it)
IPD module. Trial and On: the whole screen. Read-only: the cards and flags can be read; Nursing and EMR open as today; actions that change data (Transfer, Discharge) are not offered, as in the admission sheet. Off: the existing lock page ("In-patient care (IPD) is not on for your clinic"; API 403 `module-off`).

## Dependencies
- Reuses SAH-23 (list), SAH-25 (deposit low), SAH-26 (transfer), SAH-27 (discharge, going home), SAH-30 (vitals, score, vitals due), SAH-31 (EMR sheet, allergy status), SAH-33 (escalations, banner).
- **SAH-35** (Medicine round) is a dependency only for the "Dose overdue" flag, which is out of scope here. The two stories can merge in either order; SAH-36 must not break the Safety tab or its number.
- The Admitted tab banner "Open safety board" (SAH-33) stays above the toolbar in both views, unchanged.

## Open questions for the human
Numbered OQ-36-n, full text, proposed safer answer and "needs human" marks are in `docs/stories/SAH-36.md` section 3. Development proceeds on the proposed answer unless marked **needs human**.
