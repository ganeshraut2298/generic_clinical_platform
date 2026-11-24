# SAH-89 · Clinic sign-up to setup: validation, address, look, completion, header mobile search, doctor fee, owner as doctor
Module: platform (patient for the header search target; opd and billing read the doctor fee and the doctor role)
Mockup screen: Start screen and sign-in (opening screen of the mockup); also App shell (sidebar, header, Simple/Advanced, light/dark, language), Clinic setup → Clinic profile, Team and access, Fees and billing
Source: human (Product Owner), priority request, 22 Nov 2026. Nine points, analysed one by one below. Stories: `docs/stories/SAH-89.md`.
Number check: SAH-89 was free (no file or mention in `docs/`; SAH-87 and SAH-88 are IPD analyses). It is used as asked.

## Problem
The Product Owner asked for nine changes around getting a clinic started and the first daily use:

1. A new mobile number must first create its clinic, then use features.
2. The clinic address should be filled with Google Maps so it is well defined and reusable.
3. Every field of the setup process needs proper validation.
4. "Choose your colour and web address" needs a custom colour (not only presets) and an optional logo upload.
5. When all is filled, the setup completion screen shows each step marked complete.
6. Top header: search by mobile number; at 10 digits, open the existing patient view.
7. Remove the second "checks" menu.
8. Adding staff of type Doctor lets the owner enter the consultation fee, so accounting is simple.
9. The owner can also act as a doctor, with all doctor functions.

Several points are already in the mockup or already built. Some are different from the mockup, and some are new. Rule 1 of CLAUDE.md says anything not in the mockup, or different from it, stops and goes to a human. The open questions at the end list every such point with a recommended default. The stories are written to the recommended defaults and say so.

## Who uses it (roles) and in which setting
| Role | What changes for this role |
|---|---|
| New clinic owner (not yet logged in) | Items 1 to 5: sign-up, setup wizard, completion screen. Owner-only (setup API 403 for others, SAH-4 AC14). |
| Owner | Items 4, 6, 7, 8, 9 in the running clinic: Clinic profile (colour, logo), Team and access (doctor fee), working as a doctor. |
| Doctor, visiting doctor | Item 8 (their fee is set by the owner; they see it, not change it), item 9 (the owner now does what they do), item 7 (menu). |
| Front desk, nurse, billing, counsellor | Item 6 (header search, when they may open patients). Item 7 for nurse (menu). |
| Pharmacist | Item 7 (Second checks entry), item 6 only if they have the Patients menu. |

Setting: new clinic setup on the owner's phone or computer; front desk at the counter (header search); owner's office (team, fees, profile).

## In scope / Out of scope
In scope:
- Item 1: sign-up path to the wizard, the rule that no feature opens before setup is complete, the Log in with an unregistered number message.
- Item 2: a proper, structured address in step 1 and in Clinic profile, with the Google Maps part decided first (OQ-89-2).
- Item 3: validation rules, messages and behaviour for every field of the five steps.
- Item 4: custom colour and optional logo inside step 5, reusing what the mockup shows in Clinic profile.
- Item 5: the completion screen with each step ticked.
- Item 6: header mobile search behaviour at 10 digits.
- Item 7: removing the "Second checks" sidebar entry, with the safety consequence made explicit.
- Item 8: consultation fee on the Add person form when the type is Doctor or Visiting doctor.
- Item 9: the owner working as a doctor.

Out of scope (each could be a later story):
- Subdomain serving (SAH-4 out of scope), sample patients, WhatsApp logins (not Phase 1).
- Printing the logo on case paper, bills and booking page (the mockup shows previews; printing belongs to the paper and billing stories). Only upload, store and show in the app are here.
- A revenue share or commission per doctor, doctor payouts, tariff and payer rates (Clinic setup → Tariff and services).
- Maps on the public booking page, directions, "near me" search, branch maps.
- ABDM facility registration (needs the integration gateway, Later).
- Changing the 5-step order or labels of the wizard.

## Item by item: mockup, built, new or different
### 1. Unregistered mobile creates the clinic first
- Mockup shows: sign-in screen with toggle "Log in" / "New clinic", mobile field (+91), "Send OTP", 6-digit code, "Verify and continue". In either mode, a mobile that is not a known owner goes to the Setup wizard (function `pLogin`, action `verify`). In Log in mode it first shows "No clinic on this number yet. Let's set one up." Nothing else can be opened before the wizard ends.
- Built: login page with both modes; `AuthNext` has SETUP, APP, CHOOSE_CLINIC, NOT_REGISTERED (SAH-3). New clinic with a new number goes to the wizard; Log in with an unknown number shows "This number is not registered. Ask your clinic owner to add you, or sign up to create a new clinic." (SAH-3 AC4, a version the human accepted over the mockup).
- Different from the mockup: in Log in mode the built app does not open setup. The request says an unregistered number "first creates his clinic". Decision OQ-89-1.

### 2. Address from Google Maps
- Mockup shows: wizard step 1 field "Address" (placeholder "Building, road, area"), "City", "PIN code". Clinic profile has "Address" and "Google Maps link" ("Paste the share link from Google Maps"). No map, no autocomplete, no pin.
- Built: wizard step 1 free-text Address (no validation), City (required), PIN (6 digits, optional). Clinic profile has the Google Maps link field.
- New: address lookup with Google Maps and a structured stored address. This is a new external service (key, cost, data leaving India). Decision OQ-89-2, architecture decision.

### 3. Validation on every field
- Mockup shows only: "Enter your clinic name", "Enter the city" (SAH-4 AC7), "Enter the owner doctor's name", "Enter fees as numbers", "Choose an available web address", the slug rule "Use 3 to 24 lowercase letters, numbers or hyphens", the colour rule "Enter a colour code with 6 characters, like 1F5BC8", logo rule messages (type, 2 MB), "Enter a 10-digit mobile number".
- Built: name required (max 160), city required, PIN 6 digits optional, phone none, address none, doctor fee at least 0 only, staff mobile pattern optional, slug pattern, step 4 ranges. Messages come as toasts.
- New: a full rule per field, shown inline under the field. Texts for fields where the mockup has no message are proposals (OQ-89-3).

### 4. Custom colour and optional logo in "Choose your colour and web address"
- Mockup shows: step 5 has presets only (Colour swatches), web address, default staff language, preview. A custom colour (picker, 6-character code, colours taken from the logo, readability checks: button text 4.5 : 1, text on white, dark mode shade) and a logo upload (PNG, SVG, JPG, WEBP, up to 2 MB, checks, previews) exist in Clinic setup → Clinic profile ("Logo and colour"), not in the wizard step.
- Built: wizard step 5 presets only. Clinic profile has the Brand colour swatches with a "Custom colour" button; API already has brand `custom` and `brandHex`. No logo upload was found in `frontend/src/app/features/config` (only a hint text mentions the logo).
- Different from the mockup: custom colour and logo in the wizard step. Logo storage needs a decision (documents service is not in Phase 1). Decisions OQ-89-4 and OQ-89-5.

### 5. Completion screen with every step marked complete
- Mockup shows: after "Create my clinic" a progress screen (`pBuilding`): logo or initials, "Setting up <clinic name>", "This takes a few seconds.", a progress bar, a list of six lines that turn to a tick one by one: "Creating your clinic space", "Loading the <specialty> template", "Setting up your OPD workflow and fee rules", "Preparing case paper and bill layouts", "Sending staff logins on WhatsApp", "Securing https://<slug>.<domain>", and the final address line. Then the app opens with the toast "Your clinic is live at <address>" and the Getting started panel (SAH-4 AC12).
- Built: heading "Setting up your clinic", "This takes a few seconds." and a bar fixed at 70%. No step list, no ticks, no final address.
- Different: the built screen is missing what the mockup shows; plus the line "Sending staff logins on WhatsApp" is not true in Phase 1. Decision OQ-89-6.

### 6. Header search by mobile number
- Mockup shows: header input, placeholder "Search mobile number" (numeric keyboard). Pressing Enter opens the "New walk-in token" sheet (`openWalkin`) with the number filled; the sheet lists the family on that mobile ("A whole family can share one number") and lets the user pick or add a person. It does not open on 10 digits and it is not the Patients record.
- Built: the input is drawn in the header (`shell.searchMobile`), but nothing happens when typing or pressing Enter. The Patients feature has a list and a record page `/app/patients/:id` (SAH-8).
- Different: auto-open at 10 digits (mockup: on Enter) and "existing patient view" (mockup: walk-in sheet). Decision OQ-89-7.

### 7. Remove the second "checks" menu
- The entry is **"Second checks"** (menu key `secondchecks`, route `/app/emr/second-checks`, right after Queue, in simple and advanced menus). It is not in the mockup: the mockup's sidebar has no such item. It came from SAH-66 and ADR-023 D13.5: chemotherapy orders waiting for a second person's check. It shows for nurse, pharmacist, doctor, visiting doctor, and owner with doctor details; a badge shows the waiting count.
- The only other "check" wording in the mockup is the IPD tab **"Safety"** inside IPD (EWS, pharmacist check, medicine round) and the allergy, duplicate and interaction checks in EMR. Those are not a sidebar menu. If the Product Owner meant another entry, OQ-89-8 asks.
- Built: `nav.config.ts`, `NavigationPolicy` (backend), shell badge store. Removing only the menu key hides the way to reach the waiting list, and the second check is a safety control (the writer of a chemotherapy order cannot be the only person to approve it). Decision OQ-89-8.

### 8. Doctor consultation fee when adding staff of type Doctor
- Mockup shows: wizard step 3 doctors have name, specialty and a "₹" consultation fee; the first is marked Owner. Clinic setup → Fees and billing → "Consultation fees" lists each doctor with a "New visit fee" field and the follow-up rule beside it. Clinic setup → Team and access: "Add visiting doctor" is a placeholder action ("soon") and "Add staff" has name, mobile and role (Front desk, Nurse, Counsellor, Attendant, Pharmacist), no doctor type and no fee. The Sahaj admin portal user form has a "Consultation fee (₹)" field (New-visit fee).
- Built: Team and access has an Add person form with the doctor role and the registration number only (no fee); `DoctorDetails.feeRupees` exists in the API model; Fees and billing edits the doctor's fee.
- New: the fee field on the Add person form for Doctor and Visiting doctor. It must be the same single value as the "New visit fee" of Fees and billing (no second fee). How it relates to Tariff and services: OQ-89-9, a billing rule.

### 9. The owner can also act as a doctor
- Mockup shows: in the wizard the first doctor is marked "Owner" (owner doctor's name, specialty, fee), so a clinic owner who is a doctor is in the mockup. Doctor App is the consultation step of the Queue. The mockup has no screen to switch an owner who did not start as a doctor into a doctor later.
- Built: the owner membership can carry doctor details (the wizard saves them). The backend gives the owner the Second checks entry only when doctor details exist. Team and access lists any member with doctor details among Doctors. No other doctor function is tied to the owner's doctor details (not verified in every module).
- New: a roles and authorisation change, the full set of doctor functions for the owner. Decision OQ-89-10.

## Business rules (numbered)
Sign-up (item 1)
1. A mobile number with no clinic and no staff membership, verified by OTP in "New clinic", creates the user, the clinic (tenant), the owner membership and the Free plan, then opens step 1 of the wizard (SAH-3).
2. Until "Create my clinic" has completed, a new owner can open only the Setup wizard. Every other screen and API answers with the setup step, never with clinic data. If the owner leaves, the progress stays (SAH-4 AC2, AC13).
3. A number that already has a clinic or membership is logged in, not asked to create another clinic (SAH-3 AC11).
4. Log in with an unregistered number: recommended behaviour in OQ-89-1.

Address (item 2)
5. The clinic address is stored as separate parts: address line (building, road, area), city, state, PIN code. Free-text lines remain editable by the owner whatever a map suggests. Further fields (latitude, longitude, map link) depend on OQ-89-2.
6. No Google service is called until the human approves it and the architect records the decision (ADR). Without that approval the address is typed by hand with the validation of rule 9 and the optional "Google Maps link" paste field of the mockup.
7. The address is shown to the owner for confirmation before it is saved; a map suggestion never overwrites typed text silently.

Validation (item 3)
8. Every field is validated on the screen and again by the server. The server's answer wins; messages are inline under the field, in plain English, sentence case.
9. Proposed rules (all to be confirmed in OQ-89-3):
   - Clinic name: required, 2 to 160 characters after trimming.
   - Address: required, 5 to 200 characters.
   - City: required, 2 to 80 characters, letters, spaces, dots, hyphens, apostrophes.
   - PIN code: required, exactly 6 digits, not starting with 0.
   - Clinic phone: required, an Indian mobile (10 digits, first digit 6 to 9) or a landline with STD code (OQ-89-3).
   - Registration number: optional, up to 40 characters; no format check.
   - Specialty: one template must be chosen.
   - Doctor name: required for the owner doctor (existing rule), 2 to 100 characters; specialty from the list; fee a whole number of rupees from 0 to 50,000 (OQ-89-9).
   - Staff: name required 2 to 100 characters, role from the list, mobile required, 10 digits, first digit 6 to 9 (the SAH-2 mobile pattern), no duplicate of another person in the setup or of the owner's own number.
   - Staff and doctor count within the plan limit (2 doctors, 5 users on Free), checked on screen and by the server (409 plan-limit).
   - Step 4: follow-up days 1 to 30; the three segment questions have a value.
   - Step 5: web address 3 to 24 lowercase letters, numbers or hyphens, starting with a letter or number, not reserved, not taken; default language one of the three; colour a preset or a valid custom 6-character code; logo as in rule 14.
10. "Continue" and "Create my clinic" do not move on while a field of the step is invalid; the first invalid field gets focus and its message is announced. Optional fields are marked "(optional)" as in the mockup.
11. Only the current step's fields are validated when moving forward, and all five steps again at "Create my clinic"; a failure sends the owner back to the first step with an error.

Colour and logo (item 4)
12. Custom colour: any 6-character hex code (with or without #), picker or typed. The readability rules of the mockup apply as shown there: button text needs 4.5 : 1; if the colour is too light for text on white, a darker shade is used for links and labels; dark mode gets its own lighter shade. The mockup warns and offers "Use the readable shade", it does not block (OQ-89-4).
13. The colour is saved as brand `custom` with the hex code. Presets stay.
14. Logo is optional and can be skipped. Accepted types and size follow the mockup (PNG, SVG, JPG, WEBP, up to 2 MB); the server checks the real content type, not the file name or the browser's claim. Where the file is kept is OQ-89-5. Replace and Remove are owner only. Until a logo exists, the clinic's initials are used.

Completion (item 5)
15. After "Create my clinic" the screen shows the mockup's list; a line is ticked only when that work has really finished on the server (OQ-89-6). If it fails, the screen says which step failed and offers "Try again"; the progress and answers stay; nothing half-created is shown as complete. The call is safe to repeat (SAH-4 idempotent complete).
16. When all lines are ticked the app opens with the clinic's colour and the Getting started panel (SAH-4 AC12).

Header search (item 6)
17. The header search accepts digits only; spaces, "+91" and a leading 0 are ignored; at most 10 digits are used.
18. When exactly 10 valid digits are present (first digit 6 to 9) the existing patient view of this clinic opens for that mobile (OQ-89-7). If the number matches no patient, the user is told so and offered registration, as the Patients screen does today.
19. The mobile number is never put in the address bar, the browser history, a log or an analytics call (project rule: no patient data in URLs or logs). Opening a record is written to the access log as any other view of a patient record.
20. The search works only for roles that may see patients. Other roles do not get the field, or it says they cannot open patients (OQ-89-7). The server decides by role and clinic; the tenant comes from the logged-in user.

Second checks (item 7)
21. The sidebar entry "Second checks" is removed for every role, only after the human says where the chemotherapy second check is done instead (OQ-89-8). Until then it stays.
22. Whatever is decided, an order waiting for a second check must remain reachable to every person who may check it, and the writer of an order must still be unable to check it themselves. The API rule does not change.

Doctor fee (item 8)
23. On Team and access → Add person, when the type is Doctor or Visiting doctor, a field "Consultation fee (₹)" appears, as in the wizard and Fees and billing. The fee is saved with the doctor's details.
24. There is one fee per doctor: the one shown in Fees and billing as "New visit fee" and used at the token, the walk-in sheet and the bill. Changing it in either place changes both. Bills already made keep their amount.
25. Only the owner can enter or change a fee; the doctor sees theirs without editing; other roles do not see the amount except where they already bill it.
26. Fee changes are audited with old and new amount (amounts and ids only). Money is integer paise in the API and database; shown as ₹ 1,200.

Owner as doctor (item 9)
27. The owner can mark themselves as a doctor: they add doctor details (specialty, qualification, registration number, fee) to their own membership. They keep the owner role.
28. An owner who is a doctor has every doctor function: appears in the doctor lists (appointments, token, queue, Fees and billing), receives and sees their own patients in the Queue, uses the consultation (Doctor App, EMR, prescriptions) and signs with their own name and registration number. They also keep every owner function.
29. A person is counted once. The owner as doctor counts as one doctor against the plan limit (OQ-89-10).
30. Every clinical entry made by the owner as a doctor is recorded under their own user, so the audit trail and the "writer cannot check own order" rule work as for any doctor.
31. The owner can remove the doctor details; patients already seen keep their records under the owner's name.

## Patient-safety and privacy points
- Second checks (chemotherapy double check) is a patient-safety control; its menu entry may not be removed unless another way to do the check exists (rules 21 and 22).
- Owner as doctor: prescriptions and notes need the doctor's registration number to print, as medical council rules require (mockup text on Team and access). Not having one must be visible and resolved by the human (OQ-89-10). The same person cannot be both the writer and the second checker of the same order.
- Header search: a mobile number is personal data. It never goes into a URL, a log or an analytics event; opening a record is access-logged; a number that matches nothing reveals nothing about other clinics (tenant isolation by row-level security).
- Google Maps: the clinic address is business information, not patient data, but a browser call to Google sends the typed text and the user's address to a third party, possibly outside India. No patient data may ever be sent. Decision OQ-89-2.
- Logo upload: an uploaded file is untrusted. Type and size are checked on the server, SVG could carry script, and the file is served only with a safe content type. Stored per tenant, with row-level security.
- Mobile numbers of staff and owner are never logged; audit rows hold masked mobiles.
- Fees are not personal data but changes are audited.

## Plan or entitlement (which plan/module unlocks it)
All items are in every plan (Free, Professional, Multi-Doctor, Custom). The plan limits still apply: Free and Professional allow 2 doctors and 5 users (SAH-4); the owner as doctor counts as a doctor. Second checks has no module condition today (SAH-66).

## Open questions for the human
Every point below is NOT IN MOCKUP: needs human decision. Each has a recommended default; the stories are written to the recommended default and marked "(default)".

- **OQ-89-1 · Unregistered number on Log in (item 1). NOT IN MOCKUP: needs human decision.** The mockup opens the setup wizard from Log in too ("No clinic on this number yet. Let's set one up."). SAH-3 chose a message that sends the person to the owner or to "New clinic", to stop a staff member with a mistyped number creating a stray clinic. The request says an unregistered number first creates a clinic. Options: (A, recommended) keep SAH-3 and add a button "Create a clinic with this number" on the message that continues in New clinic mode without a second OTP, and keep the rule that nothing opens before the wizard ends; (B) follow the mockup exactly: Log in with an unknown number opens the wizard.
- **OQ-89-2 · Google Maps for the address (item 2). NOT IN MOCKUP: needs human decision. Escalation to the solution-architect, a new external service.** The mockup has typed address fields and a pasted "Google Maps link", no lookup. A Google Places lookup needs: a Google Cloud project and API key (kept as an environment variable, restricted to our web address), a billing account with a cost per lookup, and it sends the typed text and the user's IP address to Google, which may process it outside India. The project rule is that all patient data stays in India; the clinic address is not patient data, but the human must accept the data flow, and an ADR must record key handling, cost cap, fallback when Google is down, and the content-security settings. Options: (A) Google Places autocomplete, new ADR first; (B, recommended for Phase 1) typed structured address (line, city, state, PIN) plus the mockup's optional "Google Maps link" paste field, no Google call from our pages; (C) an India-hosted map or address service. Recommended: B now, and A as a follow-up story after the ADR and a cost approval. Also decide whether to store latitude and longitude (recommended: no in Phase 1) and what "listed for future use" means (recommended: the structured address is stored on the clinic profile and read by later screens such as the booking page; no public directory in Phase 1).
- **OQ-89-3 · Validation rules and texts (item 3). NOT IN MOCKUP: needs human decision.** Confirm or change the rules in rule 9. Specific questions: (a) is the address required (the mockup does not mark it optional, SAH-4 required only name and city)? Recommended: required. (b) Clinic phone: mobile only, or also landlines with an STD code? Recommended: both, 10 digits mobile (6 to 9 first) or STD plus number 10 to 11 digits in total. (c) Registration number (clinical establishment): a legal format depends on the state; the BA does not guess. Recommended: optional free text, up to 40 characters, no format check. (d) Doctor fee range. Recommended: whole rupees 0 to 50,000. (e) New texts for every message; recommended pattern "Enter the PIN code (6 digits)", "Enter a 10-digit mobile number" (mockup text), "Enter the clinic address". (f) Validate on blur and on Continue, or only on Continue? Recommended: on blur after the first visit and on Continue.
- **OQ-89-4 · Custom colour in the wizard and its contrast rule (item 4). NOT IN MOCKUP: needs human decision.** The mockup has custom colour in Clinic profile, not in wizard step 5. Recommended: add the same control to step 5, using the mockup's rules (picker, 6-character code, colours from logo if a logo was added, three readability messages, button text at least 4.5 : 1, automatic darker shade for text on white, lighter shade in dark mode). Contrast rule question: warn like the mockup (recommended, because the app already adjusts the text shade so the app stays readable) or block colours whose button text is under 4.5 : 1 (WCAG AA)? Also confirm that the 4.5 : 1 rule is the accessibility standard to use.
- **OQ-89-5 · Logo upload and file storage (item 4). NOT IN MOCKUP: needs human decision. Escalation: file storage, documents service not in Phase 1.** The mockup has a logo upload in Clinic profile (drop zone, checks, previews) but not in the wizard, and SAH-4 listed it as out of scope (SAH-7). No file storage exists. Options: (A, recommended) store the logo, at most 2 MB, one per clinic, as a binary column in the `platform` schema with row-level security, served only to logged-in users of that clinic through an authenticated endpoint (and later by the documents service); (B) wait for the documents service and keep the logo out of the wizard (only initials). Also: accept SVG? Recommended: accept PNG, JPG, WEBP always; accept SVG only if the architect can sanitise it, otherwise drop SVG from Phase 1 and say so in the screen text. Also: does the wizard get the full mockup checks and previews (large) or only upload, preview and Remove? Recommended: upload, preview, Remove, and the one-line size and type messages; the full checklist stays in Clinic profile.
- **OQ-89-6 · Completion screen (item 5). NOT IN MOCKUP: needs human decision (the built screen differs from the mockup; two texts change).** Recommended: build the mockup's screen with the six lines and final address. (a) The line "Sending staff logins on WhatsApp" is not true in Phase 1 (notifications are Later). Recommended text: "Setting up staff logins". (b) Mockup ticks on a timer; recommended: tick from real progress (the complete call), minimum 4 seconds so it is readable. (c) Does the owner need a final "Open my clinic" button, or does the app open by itself like the mockup? Recommended: opens by itself, as the mockup, with the toast "Your clinic is live at <address>". (d) Do they mean this screen or the wizard rail with all five steps ticked? Recommended: this screen, and the rail's five steps also show ticked just before it.
- **OQ-89-7 · Header mobile search (item 6). NOT IN MOCKUP: needs human decision.** Different from the mockup in two ways. (a) Trigger: the mockup acts on Enter; the request acts at 10 digits. Recommended: at 10 digits, and Enter works too. (b) Target: the mockup opens the "New walk-in token" sheet with the family on that number. "The existing patient view" is read as the Patients record page. Options: (A, recommended) one match opens that patient's record; several family members open the Patients list showing them, with the mobile held in memory, not in the address; none shows "No patient with this number" and a "Register patient" button; (B) keep the mockup: open the walk-in token sheet. Also: which roles get the field. Recommended: every role that has the Patients menu; for others the field is hidden. Also: should a paste of "+91 98220 00001" work? Recommended: yes. Also: the mockup's family-on-one-number rule means one number can match several people; confirm (A).
- **OQ-89-8 · "Second checks" menu (item 7). NOT IN MOCKUP: needs human decision. Escalation: patient safety.** First confirm the target: the entry "Second checks" (shown after Queue). If another menu was meant (for example the IPD "Safety" tab), say so. Second, the entry is not in the mockup, so removing it moves toward the mockup, but the chemotherapy second check (ADR-023, SAH-66) lives behind it and has no other door. Options: (A, recommended) remove the sidebar entry and show the waiting orders inside Queue (a tab or a banner "N second checks waiting", visible to the same roles, badge kept) so the safety step is never lost; (B) remove the entry and the whole second-check screen; this removes a safety control and needs a clinical sign-off; (C) keep it. The BA does not recommend B. A needs the architect and a short note from the clinical owner on where the check belongs.
- **OQ-89-9 · Doctor fee and billing rules (item 8). NOT IN MOCKUP: needs human decision. Escalation: billing rule.** Questions: (a) is it one fee per doctor (the "New visit fee" of Fees and billing and the wizard)? Recommended: yes, one fee, two screens. (b) How does it meet Clinic setup → Tariff and services (branch and payer rates)? Recommended: the doctor fee is the self-pay new-visit fee; payer or branch tariffs, if set, still override at billing as built; this story changes no pricing logic. (c) Follow-up fee: stays the clinic-wide rule (Free, 50%, Full of this fee). (d) Visiting doctors: same fee field, no revenue share or payout in this story. (e) "So accounting is simple": confirm that the only wanted outcome is the fee being set at the time of adding the doctor, not a report; recommended: yes, reports are SAH-16 as built. (f) Fee changes apply to new bills only; old bills stay. (g) Is a fee of 0 allowed (free consultation)? Recommended: yes, shown as "Free".
- **OQ-89-10 · Owner as doctor (item 9). NOT IN MOCKUP: needs human decision. Escalation: roles and authorisation change.** The mockup shows the owner as the first doctor in setup, nothing more. Options: (A, recommended) one owner membership with doctor details; doctor functions are available when doctor details exist; the owner can turn this on or off in their own row in Team and access ("I also see patients as a doctor"); (B) a second DOCTOR membership for the same person; more work and two logins; not recommended. Questions: (a) does the owner who is a doctor count against the 2-doctor Free limit? Recommended: yes, as in the mockup wizard "Doctors · 1 of 2". (b) Registration number required before the owner can consult? Recommended: yes (case paper prints it by medical council rule); needs a legal confirmation. (c) Can the owner act as second checker of their own order? Recommended: never. (d) Is "all doctor functionality" every function of the doctor role, including IPD doctor actions? Recommended: yes, those the doctor role has now. (e) The role switcher of the mockup (demo role switcher) is replaced by real login; owner as doctor does not need a switcher. Recommended: no switcher. (f) Existing clinics where the owner already has doctor details get the doctor functions on release, with a notice. Recommended: yes, with a one-time notice.
- **OQ-89-11 · Story count and sequence. NOT IN MOCKUP: needs human decision.** The skill suggests up to 6 stories per requirement; this request has nine items and the PO asked for one story per item, so there are nine stories (89-1 to 89-9), each small. Recommended order: 89-2, 89-1, 89-3, 89-6, 89-4, 89-5, 89-7, 89-8, 89-9 (the last two wait for OQ-89-8 and OQ-89-2).

## Dependencies on existing stories
SAH-2 (home, Start free), SAH-3 (sign-up and login), SAH-4 (setup wizard), SAH-5 (shell and menu), SAH-6 (team and access), SAH-8 (patients), SAH-66 (Second checks), ADR-023 D13.5, Fees and billing story (consultation fees), SAH-19 (plan limits).

## Status log
- 22 Nov 2026: requirement and stories written by the business-analyst (docs only, branch `feature/SAH-39-home-kinetic-background`).
