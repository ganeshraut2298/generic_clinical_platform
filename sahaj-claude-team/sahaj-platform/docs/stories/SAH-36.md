# SAH-36 · Admitted patients as uniform cards (IPD, part 14)
Mockup screen: IPD → Admitted — toolbar (`pctools`: `pcq` search, ward select, Cards/Table switch, "Cards per row" 2/3/4), uniform card (`pcCard`, `pcgrid cols-N uni`, `pcf` flags, `pcacts`), "Showing N of M patients" (`pcmore`), empty text; the Standard card of `pcDefault`
Module: ipd

## 0. Scope in one view (7 Nov 2026, business analyst)
Reads against the code in `frontend/src/app/features/ipd` (the Admitted tab is the table in `ipd.page.html`), the list contract `GET /admissions` in `docs/api/ipd.yaml`, and the IPD inventory of `docs/stories/SAH-35.md` section 0 (rows 6 to 10).

| Part of the mockup | State today | In SAH-36? |
|---|---|---|
| Admitted as a table with Open, safety banner | BUILT | Kept as the "Table" view, unchanged |
| Cards/Table switch, Cards per row 2/3/4, uniform Standard card | NOT BUILT | Yes (stories 1, 2) |
| Search, ward filter, "No patients match these filters.", paging 24 | NOT BUILT | Yes (story 2) |
| Flags Deposit low, Going home today | In the list (SAH-25, SAH-27), shown only as table pills | Yes (story 3) |
| Flags Not seen N min, Doctor reviewing, EWS, Vitals due, Allergy | Data exists (SAH-33, SAH-30, patient record) but is not in the list answer; vitals due and score only in the one-patient sheet | Yes (story 4): list needs the existing facts added, no new table, no new rule |
| Actions Nursing, EMR | Sheets exist, opened from the admission sheet footer (OQ-30-1, OQ-31-7) | Yes (story 5): open the same sheets from the card |
| Actions Transfer, Discharge | Exist inside the admission sheet (Move row; discharge view) | Yes (story 5): the card opens the admission sheet at that part |
| Action Deposit | Exists inside the admission sheet | No: off in the mockup's default card; reached by the name (OQ-36-3) |
| Workflow stage chip, stage chips, Update stage | No stage anywhere (no data, no contract) | **No** (OQ-36-2) |
| Flag Dose overdue | Needs SAH-35 | **No**, dependency on SAH-35 |
| Customize card, Patient card setup, draft/publish | NOT BUILT | **No**, candidate **SAH-37** |
| My patients, scope notice, Roles and access | Needs ward scoping | **No**, blocked (OQ-33-2, OQ-30-3) |
| Case paper action, Print | Print is "soon" in the mockup | No |
| Vitals tiles, Diagnosis and Allergies lines, Assigned nurse, Running bill section, Compact/Expanded layouts | Not on the minimum card | No |

## 1. Stories

### SAH-36-1 · As a nurse, doctor, owner or front desk, I want the admitted patients shown as uniform cards with a Cards/Table switch, so that I can scan the ward at a glance and still use the table when I prefer it
Priority: Must

### SAH-36-2 · As a user of the Admitted list, I want to search by name, UHID or bed and filter by ward, so that I find one patient among many quickly
Priority: Must

### SAH-36-3 · As a front desk or owner, I want the card to show "Going home today" and "Deposit low", so that I see who needs billing attention without opening each patient
Priority: Should

### SAH-36-4 · As a nurse or doctor, I want the card to show an unseen escalation, the early warning score, due vitals and a recorded allergy, so that I notice the patient who needs me first
Priority: Must

### SAH-36-5 · As a nurse, doctor, owner or front desk, I want the daily actions on the card (Nursing, EMR, Transfer, Discharge) for the roles that may use them, so that I do not have to open the admission sheet first
Priority: Should

## Acceptance criteria

### SAH-36-1 · Cards, Table and the Standard card
1. Given IPD is Trial, On or Read-only, someone is admitted and the user is OWNER, FRONT_DESK, NURSE or DOCTOR, when the Admitted tab opens, then the page shows (top to bottom) the existing red safety banner when it applies (SAH-33), a toolbar, and the patients as **Cards**. The toolbar has a switch "View" with **Cards** and **Table** (`aria-pressed`, icons plus words), and, in Cards view, a switch "Cards per row" with **2**, **3**, **4**. Cards and 3 per row are the defaults.
2. Given the user chooses **Table**, then the toolbar stays and the table of today (columns Patient, Bed, Doctor, Running bill, Deposit, Status, Open; the pills; "Open <name>") is shown unchanged; the "Cards per row" switch is not shown. Given the user chooses **Cards** again, the cards return. The choices are kept only until the page is reloaded; nothing is stored in the browser or sent to the server.
3. Given a card, then it shows, in this order: round initials (decorative); the patient name as a button (tooltip "Open bill and bed details") which opens the admission sheet of SAH-23; under it "MRN <UHID> · <age> · <sex in full: Male, Female, Other>"; the bed block "Bed" and the bed code; the flags line (stories 3 and 4); "Admitted on" (date and time, for example 24 Oct 2026, 9:20 AM IST); "Length of stay" ("Day 1 (today)" on the day of admission, otherwise "<N> days", N being the billed day number of the list); "Ward"; "Room" (the room name, or "—" when the ward has no rooms); "Doctor" (the admitting doctor's name); then the action row (story 5).
4. Given a missing age or sex, then the card shows "—" in its place and never "null" or "undefined". Given a long name, ward or room, then it is cut with an ellipsis and the full text is in the tooltip and the accessible name; nothing is cut in the data.
5. Given several cards, then all cards in one row have the same height and the action row sits at the bottom of each card.
6. Given a screen 1100 px wide or narrower, then 3 or 4 per row become 2 per row; given 700 px or narrower, then every setting becomes 1 per row (as the mockup). Given a tablet, then every button and chip on the card is at least 44 px high.
7. Given the card, then it is an article named "<patient name>, bed <code>"; the name button, every action and every switch can be reached and used with the keyboard; the focus outline is visible; the Cards/Table and Cards-per-row switches announce their pressed state.
8. Given 24 or fewer matching patients, then all are drawn. Given more than 24, then 24 are drawn, followed by "Showing 24 of 61 patients" (the true numbers) with the buttons **Show 24 more** and **Show all**. Given the user taps **Show 24 more**, then 24 more cards are added and the line updates; the keyboard focus stays on the button (or moves to the first new card when the button is gone), and the new count is announced politely. Given the user changes the search, ward or view, then the list starts again at 24.
9. Given nobody is admitted, then the panel shows "No one is admitted right now." (as today) and no toolbar results. Given a slow network, the existing "Loading beds and admissions" shows; given a failed load, the existing message "We could not load beds and admissions. Try again." with **Try again** shows.
10. Given the card list is on screen, then the "Admitted" tab number is still the count of all admitted patients (not the filtered count).
11. Given IPD Off, then the existing lock page shows and the list call answers 403 `module-off`. Given a role without the list (BILLING, PHARMACIST, VISITING_DOCTOR), then the endpoint answers 403 as today; nothing in this story widens who can read the list.
12. Given a patient of another clinic, then the list never contains them (RLS; the clinic comes from the signed-in user).
13. Given the page is used by a nurse and a front desk user, then both see every admitted patient of the clinic (no ward or "own patients" narrowing in this story; OQ-33-2).

Notes for design
- The Table view is today's table; no new behaviour. Cards, toolbar and paging are new frontend pieces; the Cards/Table and Cards-per-row choices live in the page's memory only (OQ-36-5).
- Card field "Room" and the ward needed by the filter are not in the list answer today (only `wardName`): the architect decides how the list carries room and a ward id (names can repeat).
- "MRN" in the mockup is the product's UHID (as in SAH-31/32); the label is OQ-36-8.

### SAH-36-2 · Search and ward filter
14. Given the toolbar, then it has a search box with the accessible name "Search admitted patients" and the placeholder "Name, MRN or bed", and a select named "Ward" with the options "All wards" followed by the clinic's wards. The ward select is a native control or Material select with a visible label for screen readers.
15. Given the user types in the search box, then the cards (or the table rows) narrow as they type to the patients whose name, UHID, bed code or IP number contains the text, ignoring upper and lower case and leading or trailing spaces. Given the text is cleared, all return.
16. Given a ward is chosen, then only patients whose bed is in that ward show. Given a search text and a ward, then both must match.
17. Given the filters match nobody, then the card area shows "No patients match these filters." and the buttons for paging are not shown.
18. Given a filter or search is active, then it applies to both views (Cards and Table) and the chosen text and ward stay when the user switches view (OQ-36-9: the mockup's Table ignores the filters; that is treated as a mockup flaw).
19. Given the typed text, then it stays on the device: it is not sent to the server, not written to a log, not put in the URL and not saved in the browser's storage. Given the 60-second reload (story 4), then the search text, ward, view and number of cards shown are kept.
20. Given a very large hospital (many wards), then the ward select lists every ward in the order of the Beds setup; no ward is dropped.
21. Given a ward with no patient, then it is still listed in the select and choosing it shows "No patients match these filters.".
22. Given a slow network or an offline browser, then the filters keep working on the last loaded data (they are on the device); the reload follows story 4.

Notes for design
- Filtering is on the device over the loaded list (the list is already fully loaded today). If the architect prefers a server search, ask the human first: it changes what is sent about patients.

### SAH-36-3 · Flags already in the list: Going home today and Deposit low
23. Given an admitted patient with `goingHomeToday` (summary signed today, SAH-27), then the card shows the flag **Going home today** (blue, door icon) to OWNER, FRONT_DESK, NURSE and DOCTOR.
24. Given an admitted patient with the deposit low rule true (SAH-25: payer Self, running bill at or over the alert share of the deposit), then the card shows the flag **Deposit low** (red, deposit icon) to OWNER and FRONT_DESK only. Given NURSE or DOCTOR, then no Deposit low flag is shown (the default card of the mockup hides billing from them; OQ-36-4).
25. Given a patient with both, then both flags show (on the table they replace each other; on the card they do not, as the mockup).
26. Given the flags line has more flags than fit, then the ones that are not safety flags (Going home today, Deposit low, Vitals due) may be folded into a chip "+N" whose tooltip and accessible name list them, for example "2 more: Vitals due, Deposit low"; safety flags are never folded (story 4, OQ-36-1).
27. Given a patient with no flag, then the flags line is empty and takes no visible text (the card keeps its height).
28. Given the table, then its existing Status pills are unchanged.

Notes for design
- Nothing new to compute: both values are already in the list answer.

### SAH-36-4 · Safety flags: Not seen, Doctor reviewing, Allergy, EWS, Vitals due
29. Given an admitted patient with an open early-warning escalation (SAH-33 status Open) and the user is OWNER, DOCTOR or NURSE, then the card shows the red flag **Not seen · N min**, N being the whole minutes since it was raised on the server clock; the tooltip reads "Early warning escalation raised N min ago, past the T min target, not seen by a doctor" or "... within the T min target ..." (T = 15 or 60 by the SAH-33 target).
30. Given the escalation has been acknowledged by a doctor (status Acknowledged), then the flag is the amber **Doctor reviewing**. Given it is closed, no flag.
31. Given the latest vitals of the patient give a score that is Medium or High (SAH-30) and the user is OWNER, DOCTOR or NURSE, then the card shows **EWS <score> · Medium** (amber) or **EWS <score> · High** (red). Given a Low score or no vitals, no EWS flag. Given a High score, the whole card also has a red outline (the flag text still carries the meaning).
32. Given the SAH-30 rule says the vitals are due (no vitals yet, or older than the due time, computed by the server), and the user is OWNER, DOCTOR or NURSE, then the card shows the amber flag **Vitals due**.
33. Given the patient has a recorded allergy (the allergy status is Recorded, not "None known" and not "Not recorded"; the server decides, the browser does not match words), and the user is OWNER, DOCTOR or NURSE, then the card shows the red flag **Allergy: <recorded text>** cut by width; the full text is in the tooltip and the accessible name. Given "None known" or "Not recorded", no allergy flag (OQ-36-6).
34. Given the user is FRONT_DESK, then the server sends no escalation, score, vitals-due or allergy data in the list and the card shows none of these flags. Given the browser's developer tools, nothing about them is present in the response for that role.
35. Given the order of flags, then it is: Not seen or Doctor reviewing, Allergy, EWS, Vitals due, Going home today, Deposit low. Safety flags (the first four, excluding Vitals due) always show in full and are never folded into "+N".
36. Given the Admitted tab is on screen and the browser tab is visible, then the list reloads every 60 seconds and after any action that changed something (a sheet closed with a change, an admission, a move); it does not reload while the browser tab is hidden. Given a flag changes on the server (an escalation is acknowledged, a score changes), then the card shows it after the next reload without any action. Given a failed reload, then the last data stays with the notice "Could not refresh. Showing data from <time>." and nothing is shown as newer than it is.
37. Given the minute counts, then they come from the server answer (its clock, IST), never from the device clock; between reloads the number is not counted up on the device (OQ-36-10).
38. Given a patient is discharged or moved during the minute, then the card is gone or changed at the next reload; the server rejects stale actions as it does today.
39. Given privacy, then reading the list writes no record-access row per patient per reload (OQ-36-7); the answer carries no data for patients the user's role may not read; patient names, UHIDs, allergy text and escalation data are not written to application logs or audit events.
40. Given the Dose overdue flag of the mockup, then it is not built in this story and no placeholder is shown; it follows SAH-35 (dependency).
41. Given the role is changed on the server (for example NURSE to FRONT_DESK), then the next reload applies it at once; the role is read again from the clinic membership, not only from the token.
42. Given the list is large (a 100-bed hospital), then the list is one read of all admitted patients with their flags, not one call per patient per reload (architect to confirm the cost).

Notes for design
- The list needs, per admitted patient and only for the roles above: the escalation status and minutes (with target), the latest score and risk, vitals due, and the allergy status and text. All exist: SAH-33 escalations, SAH-30 vitals (`vitalsDue` and `latestEws` already exist on the one-patient detail), the patient record's allergy (read through the patient module's port, as the pharmacist list does). Who decides batch versus per-patient reads, and whether `GET /admissions` or a new read carries it, is the architect's.
- The 15 and 60 minute targets and the score bands are the SAH-30/33 rules; this story adds no rule (clinical sign-off OQ-30-6 still applies before a live ward).

### SAH-36-5 · Card actions: Nursing, EMR, Transfer, Discharge
43. Given the action row, then each action is a button with its icon and word and the accessible name "<Action> for <patient name>": **Nursing** (primary), **EMR**, **Transfer**, **Discharge**, in that order, in the same tablet-friendly size (44 px). Case paper, Update stage and Deposit are not on the card.
44. Given the role and the clinic state, then an action is shown only when both the mockup's default card rules and the server allow it: Nursing for OWNER, NURSE, DOCTOR; EMR for OWNER, NURSE, DOCTOR; Transfer for OWNER, NURSE, FRONT_DESK and only when IPD is not Read-only; Discharge for OWNER and DOCTOR and only when IPD is not Read-only. FRONT_DESK sees only Transfer. Given a role that may not use an action, it is not shown (not a disabled grey button) and the server refuses the call (403) if it is made (OQ-36-4: the mockup lets DOCTOR transfer and the server does not; the mockup hides Discharge from FRONT_DESK and the server lets FRONT_DESK complete one; the narrower side is built).
45. Given **Nursing**, then the Nursing sheet of SAH-30/32 opens for that patient (title "Nursing · <patient name>"), with the Vitals and notes and Medicines (MAR) tabs as today, over the Admitted tab; closing it returns focus to the Nursing button of that card; the sheet's own rules, checks and texts are unchanged.
46. Given **EMR**, then the EMR sheet of SAH-31 opens for that patient, unchanged; closing it returns focus to the EMR button of that card.
47. Given **Transfer**, then the admission sheet of SAH-23 opens for that patient with the "Move to another bed" part of SAH-26 open and focused, unchanged (there is no separate Transfer sheet and no reason field yet: OQ-26-5, OQ-36-11). Given **Discharge**, then the admission sheet opens straight on the discharge view of SAH-27 (the summary, final bill and bed release), unchanged.
48. Given the patient's **name** on the card, then it opens the admission sheet ("Open bill and bed details"), where the running bill and Deposit stay reachable for the roles that may collect one.
49. Given a sheet closed with a change (a patient moved, discharged, a deposit taken, vitals saved), then the cards reload; a moved patient shows the new bed, a discharged patient leaves the list.
50. Given another user discharged or moved the patient while the sheet was open, then the sheet shows its existing message (409 `not-admitted` / `ipd-readonly`), and the cards reload.
51. Given IPD Read-only, then Nursing and EMR are shown as today (reading), and Transfer and Discharge are not shown.
52. Given a screen reader, then buttons of different patients can be told apart by their accessible names; given a keyboard, the Tab order inside a card is name, flags (not focusable), Nursing, EMR, Transfer, Discharge.
53. Given the nursing and EMR sheets write a record-access row when opened (SAH-30, SAH-31), then opening them from the card writes the same row; the card itself writes none.

Notes for design
- Nursing and EMR sheets take only an admission id, so they can open from a card directly. Transfer, Discharge and the name open the admission sheet; the developer needs a way to open it at a given part (Move row, discharge view).
- Whether the list answer carries the role-and-state ability per action once for the whole list (the abilities depend on role and clinic state, not on the patient, since every listed patient is admitted) or per row is the architect's.

## 2. Out of scope, dependency, further stories
See `docs/requirements/SAH-36.md`. In short: **SAH-37 candidate** "Patient card setup" (Customize card; Clinic setup → Patient card with layout, sections, fields, actions, stages, draft and publish, versions, locked safety fields) is a separate story; My patients, scope notice and Roles and access are **blocked** by the ward-scoping design; the workflow stage (chip, chips, Update stage) waits for OQ-36-2; Dose overdue waits for **SAH-35**; Case paper waits for printing.

## 3. Open questions
"Needs human" marks those the team cannot decide. For the others, development proceeds on the proposed safer answer (the rule of DEC-014 and DEC-015) and the human confirms later.

- **OQ-36-1 (needs human; patient safety and design).** In the mockup's uniform card the flags line shows one flag and "+N" for the rest, with the rest only in a tooltip. A nurse could miss an allergy or an unseen escalation behind "+2". Proposed: safety flags (Not seen, Doctor reviewing, Allergy, EWS) always show in full; only Vitals due, Going home today and Deposit low may fold into "+N". Cards stay equal height; the flags line may take two lines.
- **OQ-36-2 (needs human; clinical workflow).** There is no workflow stage in the data or the contract, so the stage chip on the card, the stage filter chips (All 9, Under treatment 4 ...) and "Update stage" cannot be built from existing data. The mockup's default stages (Admitted, Under treatment, Investigations pending, Fit for discharge, Discharge initiated ...) are clinical wording, need an owner of who may change a stage, and every change is logged. Proposed: leave all three out of SAH-36 and make a small story after the human accepts the eight default stages and who may update them. A derived stage (only "Admitted" and "Discharge initiated" from the signed summary) is possible but is a guess; not proposed.
- **OQ-36-3 (proceed).** The mockup's default card has the **Deposit** action off (the "Nursing home" and "Hospital" presets turn it on, and they need the setup screen of SAH-37). Proposed: do not show Deposit on the card; the deposit stays one tap away through the patient's name and the admission sheet. If the human wants it on the card for OWNER and FRONT_DESK now, it is one more button; say so.
- **OQ-36-4 (proceed; sign-off).** Role differences between the mockup's default card and what the server already enforces: Transfer (mockup: doctor yes; server: OWNER, FRONT_DESK, NURSE only, OQ-26-2), Discharge (mockup: front desk no; server: front desk completes), and Deposit low for doctor and nurse (mockup: hidden; the table already shows bill and deposit to them). Proposed: build the narrower of the two for each case, as stated in AC 24 and AC 44. The table is not changed.
- **OQ-36-5 (proceed).** The Cards/Table and Cards-per-row choices are not remembered between visits (mockup: session only). Proposed: in memory only; remembering a per-user choice is a later story (not patient data, but a preference store does not exist yet).
- **OQ-36-6 (proceed; patient safety).** "Allergy: Not recorded" shows no flag in the mockup, so a patient whose allergy was never asked looks the same as a safe one on the card. Proposed: as the mockup (no flag); the EMR and Give sheets keep their identity-bar allergy chip. Human may prefer an amber "Allergy not recorded" flag.
- **OQ-36-7 (needs human; privacy, DPDP).** The card puts allergy, score and escalation of many patients on one screen, and the list writes no record-access row (as the SAH-23 list and the Safety board, DEC-015). Proposed: no row for the list; the sheets keep theirs; the human confirms this satisfies the access-log policy, and whether a shared nurses' station screen needs an idle lock (a platform topic, HARD-2).
- **OQ-36-8 (proceed).** The mockup labels the identifier "MRN"; the rest of IPD calls it UHID (SAH-31, SAH-32). Proposed: show "UHID" on the card and in the search placeholder ("Name, UHID or bed"); this changes one word of mockup text; human confirms.
- **OQ-36-9 (proceed; sign-off).** In the mockup the search, ward filter and stage chips do nothing in Table view (the table is the old list). Proposed: search and ward filter apply to both views.
- **OQ-36-10 (proceed).** "Not seen · N min" is counted by the server at load. Between 60-second reloads the number is not counted up on the device; so it may be up to 59 seconds behind. Accepted for phase 1 (as OQ-33-10); notifications are Later.
- **OQ-36-11 (proceed).** The mockup's Transfer action opens its own sheet with a Reason; the built system has the Move row inside the admission sheet and no reason (OQ-26-5). Proposed: the card opens the admission sheet at that row; the reason waits for its own change.
- **OQ-36-12 (proceed).** Texts not in the mockup: "Could not refresh. Showing data from <time>." (reused from SAH-33), the accessible names of the switches, flags and actions, the polite "Showing N of M" announcement. The date format "24 Oct 2026, 9:20 AM" follows the project rule (the mockup omits the year). Human confirms wording.
- **OQ-36-13 (proceed).** Order of the cards: the order of the list today (as the table). The mockup has no sort. A card with an unseen escalation does not move up. Human may want the sickest first; that is a safety decision and a separate change.
- Shared and still open (not caused by this story): OQ-33-2 and OQ-30-3 (ward scoping: every nurse sees every admitted patient), OQ-30-1 (card as the place of Nursing, now closed for Nursing and EMR by this story), OQ-30-6 (clinical sign-off of score bands and response times before a live ward), OQ-35-2 and the go-live conditions of DEC-017/DEC-018.

## 4. Technical notes (for the architect; not a design)
- The change is mostly a screen. Contract: the admitted-list answer needs, for the roles and fields above, the extra facts of story 4, the room and ward id, the UHID, and either per-list or per-row action abilities. All come from existing tables and ports; no new table and no new rule is expected. The db-engineer checks whether the escalation and latest-vitals reads need an index for "all admitted patients of a clinic" (100 hospitals polling every 60 seconds).
- Frontend: Cards/Table view, toolbar, card component, flags, paging; the existing table, banner and sheets are reused. A card must not call the server per patient.
- Tests: role by role field presence (FRONT_DESK gets no clinical data), 24/48/all paging, filter combinations, flag order and the "+N" rule, 60-second reload keeping the filters, action visibility by role and state, focus return, narrow-screen columns.

## 5. API (`/api/v1/ipd`; proposals for the architect, details are theirs)
| Method | Path | Role | Change |
|---|---|---|---|
| GET | `/admissions` | OWNER, FRONT_DESK, NURSE, DOCTOR | The answer gains the per-patient facts for the card and the action abilities; role-gated fields are absent, not null-filled, for the roles that may not read them |
| GET | `/admissions/{id}`, nursing, EMR, discharge reads | as today | none |

## 6. Tasks
### Tasks — Backend
Add the card facts to the list read for the roles that may see them; role re-read twice; no names in logs; unit tests for each role's fields; real-DB tests by qa-engineer (other clinic invisible; FRONT_DESK response holds no clinical field; a 100-bed list in one read).
### Tasks — Frontend
Cards/Table switch, cards per row, search and ward filter, card with flags and actions, paging, 60-second reload with kept filters, focus handling, narrow screens; unit tests; the existing table and banner unchanged.

## 7. Status log
- 7 Nov 2026 · drafted by the business-analyst from the mockup (`ipList`, `pcCard`, `pcFiltered`, `sfFlags`, `pcColsSeg`), SAH-35 inventory, DEC-013 to DEC-018 and the IPD contract. Awaiting human approval of `stories`.
