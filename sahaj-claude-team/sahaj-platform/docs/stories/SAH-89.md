# SAH-89 · Stories: clinic sign-up to setup, validation, address, look, completion, header mobile search, doctor fee, owner as doctor
Mockup screen: Start screen and sign-in (opening screen of the mockup); also App shell (sidebar, header, Simple/Advanced, light/dark, language), Clinic setup → Clinic profile, Team and access, Fees and billing
Module: platform
Requirement: `docs/requirements/SAH-89.md` (rules 1 to 31, open questions OQ-89-1 to OQ-89-11).

Roles: new owner, owner, doctor, visiting doctor, nurse, front desk, billing, counsellor, pharmacist.
Stories are written to the recommended default of each open question and say "(default)". If the human chooses another option, the story changes, not the code. Nine stories, because the Product Owner asked for one per item (the skill suggests at most six; OQ-89-11).
Suggested order: 89-2, 89-1, 89-3, 89-6, 89-4, 89-5, 89-7, 89-8, 89-9.

---

### SAH-89-1 · As a person with a new mobile number, I want to create my clinic first and only then use the features, so that every clinic starts complete and nobody lands in an empty app
Priority: Must
Screen: sign-in (`pLogin`, toggle "Log in" / "New clinic"), Setup wizard.

## Acceptance criteria
- Given the sign-in screen in "New clinic", when I enter a mobile number with no clinic and the correct 6-digit code, then my account and clinic are created and the Setup wizard opens at step 1. (rule 1)
- Given I have not pressed "Create my clinic", when I try to open Today, Queue, Patients or any other screen or API address, then I am sent back to my setup step and no clinic data is returned. (rule 2)
- Given I leave at step 3 and log in again with the same number, then I return to step 3 with my answers kept. (rule 2)
- Given a number that already has a clinic or a staff membership, when I choose "New clinic" and verify, then I am logged in with "This number already has a clinic. Logging you in." and no second clinic is created. (rule 3)
- Given "Log in" with a number not registered anywhere, when I enter the correct code, then I see "This number is not registered. Ask your clinic owner to add you, or sign up to create a new clinic." and a button "Create a clinic with this number" that continues to the wizard in New clinic mode without asking for another code. (default, rule 4, OQ-89-1)
- Given I chose that button, then Terms and Privacy consent is recorded with time and version, and the clinic gets the Free plan for 12 months, as for any sign-up (SAH-3).
- Given a staff member added by an owner, when they log in with their mobile, then they open the app for their role and are never offered the wizard.
- Given a non-owner membership, when it calls a setup API, then the answer is 403 (SAH-4 AC14).
- Given a wrong code five times, then the number is locked for 15 minutes with the message "Too many wrong codes. Try again in 15 minutes." (SAH-3).
- Given the slow or lost network at "Verify and continue", then the button shows it is working, a failure shows "Could not reach Sahaj. Try again." and no clinic is created twice if I try again.
- Given another clinic's owner is logged in, when they try to read or change my setup, then the answer is 404 or 403 and my data is untouched (row-level security).

## Notes for design
- Most of this exists (SAH-3, SAH-4). The new work is the button on the not-registered message, the guard check on every API, and the tests. Option B of OQ-89-1 (open the wizard directly from Log in, as the mockup does) would replace the message and the button.
- The guard that sends `next=SETUP` users to the wizard exists in the app; the server-side refusal for clinic APIs before setup is complete must be checked by the architect.

---

### SAH-89-2 · As a new owner, I want every field in the setup wizard checked with clear messages, so that my clinic details are correct from day one
Priority: Must
Screen: Setup wizard steps 1 to 5 (`suClinic`, `suSpec`, `suTeam`, `suOpd`, `suLook`).

## Acceptance criteria
- Given step 1, when I leave Clinic name empty, then "Enter your clinic name" shows under the field (mockup text); when it is 1 character or over 160, then "Clinic name must be 2 to 160 characters". (rule 9)
- Given step 1, when Address is empty or under 5 characters, then "Enter the clinic address" shows. (default, rule 9, OQ-89-3)
- Given step 1, when City is empty, then "Enter the city" shows (mockup text); when it has digits or symbols other than space, dot, hyphen and apostrophe, then "Enter a valid city name".
- Given step 1, when the PIN code is not exactly 6 digits or starts with 0, then "Enter a 6-digit PIN code" shows.
- Given step 1, when Clinic phone is not a 10-digit mobile starting 6 to 9 (or a landline with STD code, default), then "Enter a valid phone number" shows.
- Given step 1, Registration number is marked "(optional)", accepts up to 40 characters and no other check; 41 characters show "Use 40 characters or fewer". (rule 9)
- Given step 2, when no specialty is chosen, then Continue shows "Choose your specialty".
- Given step 3, when the owner doctor's name is empty, then "Enter the owner doctor's name" shows (mockup text); a fee that is not a whole number from 0 to 50,000 shows "Enter the fee in rupees, from 0 to 50,000" (mockup text "Enter fees as numbers" for non-numbers).
- Given step 3, when a staff row has a name but no mobile, a mobile that is not 10 digits starting 6 to 9, or a mobile already used by another row or by the owner, then the row shows "Enter a 10-digit mobile number" or "This number is already added".
- Given step 3 on the Free plan, when I add a third doctor or a sixth user, then Add is disabled and the counter "N of 5 users on the free year" stays correct; if the call is forced, the server answers 409 plan-limit. (SAH-4 AC4, AC9)
- Given step 4, when follow-up days are below 1 or above 30, then the value stays at the limit and the stepper does not pass it; the three choice questions always have a value.
- Given step 5, when the web address is not 3 to 24 lowercase letters, numbers or hyphens, starts with a hyphen, is reserved, or is taken, then the message of the mockup shows ("Use 3 to 24 lowercase letters, numbers or hyphens", "Already taken. Try ...") and Create my clinic is blocked with "Choose an available web address". (SAH-4 AC3, AC11)
- Given any step with an invalid field, when I press Continue, then I do not move on, the first invalid field takes focus, its message is read by a screen reader, and every message is next to its field, not only in a toast. (rule 10)
- Given I leave an optional field empty, then no message shows and I can continue.
- Given I press "Create my clinic", then all five steps are validated again by the server; if one fails, I return to that step with the message and my answers kept. (rule 11)
- Given a forged request that skips the screen checks, then the server returns 400 with the field named and nothing is saved. Invalid data is never stored.
- Given a non-owner calls the setup API, then 403.
- Given the user pastes text with spaces around a number or name, then the value is trimmed before checking.
- Given a slow network, when a step is being saved, then Continue is disabled and shows it is working; a failed save shows "Could not save. Try again." and the answers stay on screen.
- Given names, mobiles and addresses entered, then none of them appears in a log or an error message sent to a monitoring tool.

## Notes for design
- Shared limits must be in the API contract so the screen and the server use the same numbers (the architect owns the contract). The numbers here are proposals until OQ-89-3 is answered.
- The mobile pattern is the one in SAH-2: first digit 6 to 9, 10 digits.

---

### SAH-89-3 · As a new owner, I want the setup completion screen to show each step marked complete, so that I know my clinic is really ready before it opens
Priority: Should
Screen: Setup progress screen (`pBuilding`), then the app with the Getting started panel.

## Acceptance criteria
- Given I press "Create my clinic" with all steps valid, then a screen shows the clinic logo or its initials, "Setting up <clinic name>", "This takes a few seconds.", a progress bar and the final address line with a lock icon. (mockup, rule 15)
- Given the screen is shown, then it lists, in this order: "Creating your clinic space", "Loading the <specialty> template", "Setting up your OPD workflow and fee rules", "Preparing case paper and bill layouts", "Setting up staff logins" (default text, OQ-89-6), "Securing https://<address>". (rule 15)
- Given the server finishes a part of the work, then that line changes from working to a tick; a line is never ticked before its work is done. (default, OQ-89-6)
- Given all lines are ticked, then the app opens by itself with the clinic's colour, the toast "Your clinic is live at <address>" and the Getting started panel with the setup progress pill. (rule 16, SAH-4 AC12)
- Given a line fails, then the screen shows which step failed with "We could not finish this step. Try again." and a "Try again" button; the lines already ticked stay ticked; nothing half-created shows as complete. (rule 15)
- Given I press "Try again" or reload during setup, then the work continues without creating a second clinic, staff login or duplicate record (idempotent). (SAH-4)
- Given a staff member was added, then after completion they can log in with their mobile and see only their role's menu (SAH-4 AC5).
- Given the screen, then the progress is readable by a screen reader ("Step 3 of 6 done") and motion respects reduced-motion settings (ticks appear without animation).
- Given a phone 360 px wide, then the list does not overflow.
- Given I close the browser during completion and log in again, then I see the same screen or the app, never the wizard with lost answers.

## Notes for design
- Built today: heading, "This takes a few seconds." and a fixed 70% bar. The list, ticks and address line are new work against the mockup.
- The mockup uses a timer; real progress needs the complete call to report steps or the screen to show them in order after it answers. The architect decides; the human sets the minimum display time (OQ-89-6).

---

### SAH-89-4 · As a front desk user (or any role that opens patients), I want to type a mobile number in the header and have the patient open when I reach 10 digits, so that I find a patient in seconds
Priority: Must
Screen: App shell header (input "Search mobile number"), Patients record.

## Acceptance criteria
- Given I am signed in with a role that has the Patients menu, then the header shows the field with the placeholder "Search mobile number" and a numeric keyboard on phones and tablets. (mockup)
- Given I type or paste digits, spaces, "+91" or a leading 0, then only the digits are used and at most 10. (rule 17)
- Given exactly 10 valid digits (first digit 6 to 9) with exactly one patient in my clinic on that number, then that patient's record opens (default, OQ-89-7) and the field clears. (rule 18)
- Given 10 valid digits with several family members on that number, then the Patients list opens showing them so I can pick one; the number is not shown in the address bar. (default, rule 18, 19)
- Given 10 valid digits and no patient in my clinic on that number, then I see "No patient with this number" and a button "Register patient" that opens registration with the number filled. (rule 18)
- Given fewer than 10 digits, then nothing opens and nothing is searched; when I press Enter, then "Enter a 10-digit mobile number" shows. (mockup toast text)
- Given 10 digits that do not start with 6 to 9, then "Enter a valid mobile number" shows and nothing opens.
- Given an 11th digit is typed after the record opened, then the field has already been cleared and starts again.
- Given I open a record this way, then the mobile number is not in the address bar, browser history, local storage or a log, and the opening is written to the patient access log like any other view. (rule 19)
- Given a patient of another clinic with the same mobile, then I never see them (row-level security); the answer is the same as "no patient".
- Given a role without the Patients menu (default: hidden field), when it calls the patient search API directly, then 403. (rule 20)
- Given a slow or lost network, then the field shows that it is searching, a failure shows "Could not search. Try again." and typing again retries; no double navigation happens.
- Given I type quickly, then only the result for the last 10-digit number is used (older answers are ignored).
- Given the keyboard, then the field is reachable with Tab, has a visible focus ring and an accessible label.
- Given the front desk is on the phone-size layout, then the field is still reachable (it does not disappear under 980 px without another way to search).

## Notes for design
- Built today: the header input exists but does nothing. The target page `/app/patients/:id` and the list exist (SAH-8).
- The mockup behaviour (Enter opens the New walk-in token sheet with the family on that number) is kept as option B in OQ-89-7; the human decides. Under the default, Enter is also accepted at 10 digits as a no-op shortcut (nothing extra).
- No patient data in the URL: the architect picks in-memory navigation state or a short-lived search result id.

---

### SAH-89-5 · As an owner, I want to enter a doctor's consultation fee when I add staff of type Doctor, so that billing and accounting use the right amount from the first day
Priority: Must
Screen: Clinic setup → Team and access (Add person), Clinic setup → Fees and billing ("Consultation fees", New visit fee).

## Acceptance criteria
- Given I am the owner on Team and access → Add person, when I choose the type Doctor or Visiting doctor, then the field "Consultation fee (₹)" appears with the registration number field; for other types it does not appear. (rule 23, default)
- Given I enter a fee from 0 to 50,000 whole rupees and save, then the doctor is created with that fee and it shows in Fees and billing as "New visit fee" for that doctor. (rule 24)
- Given I leave the fee empty for a doctor, then the form shows "Enter the consultation fee" and does not save; ₹ 0 is accepted and shown as "Free". (default, OQ-89-9)
- Given a fee with letters, decimals or a negative number or over 50,000, then "Enter the fee in rupees, from 0 to 50,000" shows and nothing is saved.
- Given I change the fee in Fees and billing, then Team and access shows the same amount, and the other way round. (rule 24)
- Given a doctor already has bills, when the fee changes, then old bills keep their amount and new tokens and bills use the new fee. (rule 24)
- Given I change a fee, then an audit entry records who, when, old amount and new amount (no names of patients). (rule 26)
- Given a doctor logs in, then they see their own fee and cannot edit it; a call to change it answers 403. (rule 25)
- Given a front desk, nurse, billing, counsellor or pharmacist user, when they call the fee change API, then 403.
- Given the clinic is on the Free plan with 2 doctors, when I add a third doctor, then "Your plan allows 2 doctors" shows and nothing is saved (409 plan-limit). (SAH-4 AC4)
- Given another clinic's doctor, then I cannot read or change their fee (row-level security).
- Given the fee is saved, then it is stored in integer paise and shown as ₹ 1,200 with Indian grouping. (rule 26)
- Given a slow network, then Save is disabled while saving; a failure keeps the form values and shows "Could not save. Try again."
- Given the follow-up rule (Free, 50%, Full), then follow-up amounts are still computed from this fee as in Fees and billing; this story changes no follow-up rule.
- Given Tariff and services has a payer or branch rate for the same service, then the billing result is as built today; this story does not change tariff logic (default, OQ-89-9).

## Notes for design
- The API model already has `DoctorDetails.feeRupees`; the Add person form sends only the registration number today. The fee field is a form and contract addition.
- Whether Add person for a Doctor with a fee also appears in the setup wizard step 3 as it already does: yes, unchanged.
- No revenue share or doctor payout (out of scope).

---

### SAH-89-6 · As an owner who is also a doctor, I want to see and treat patients as a doctor, so that I do not need a second login or a second clinic membership
Priority: Must
Screen: Clinic setup → Team and access (Doctors list), Queue (Doctor App consultation), Setup wizard step 3 (first doctor marked "Owner").

## Acceptance criteria
- Given I am the owner and I added doctor details (specialty, qualification, registration number, fee) to my own row, then I keep the owner role and gain every doctor function. (default, rule 27, 28, OQ-89-10)
- Given I did that, then I appear in the doctor lists (token, appointments, queue, Fees and billing), patients can be assigned to me, and my Queue shows my patients with the Doctor App consultation, EMR and prescriptions. (rule 28)
- Given I write a note or a prescription, then it is saved and printed under my own name and registration number, and my user id is on the audit entry. (rule 28, 30)
- Given I have no registration number saved, when I try to start a consultation, then I am told "Add your registration number before you see patients" with a link to Team and access, and the consultation does not start. (default, OQ-89-10, needs legal confirmation)
- Given I ordered chemotherapy or any order that needs a second check, then I cannot be the second checker of my own order; another eligible person must do it. (rule 30, patient safety)
- Given I am a doctor and owner, then I still see every owner function (Revenue, Clinic setup, Team and access) and the menu shows both sets of items once, without duplicates.
- Given I switch off "I also see patients as a doctor" (default control in my own row), then I leave the doctor lists, no new patient is assigned to me, and my past records stay under my name. (rule 31)
- Given the plan is Free with 1 other doctor, then I count as the first of 2 doctors; adding another doctor is allowed once, then blocked with the plan message. (rule 29, default)
- Given a non-owner, when they try to turn doctor functions on for the owner or for themselves, then 403; only the owner edits their own doctor details.
- Given the clinic already has an owner with doctor details from the wizard, then after release the owner gets the doctor functions and sees a one-time notice "You can now see patients as a doctor". (default, OQ-89-10)
- Given another clinic's owner with the same mobile (a second membership), then each clinic keeps its own doctor details and records; no data crosses clinics.
- Given any doctor function used by the owner, then the server checks the permission on the doctor role rule, not only the menu.

## Notes for design
- Built today: the owner membership can carry doctor details and the backend adds "Second checks" for such an owner. The wider doctor functions for the owner are not verified in every module; the architect lists them (Queue, EMR, IPD doctor actions, appointments).
- Option B of OQ-89-10 (a second DOCTOR membership for the same person) is not recommended.
- The mockup's role switcher is demo only and is replaced by real login; no switcher.

---

### SAH-89-7 · As an owner, I want the "Second checks" sidebar entry removed without losing the second check on chemotherapy orders, so that the menu is simple and the safety step stays
Priority: Should
Screen: App shell (sidebar); the entry "Second checks" has no place in the mockup sidebar.

## Acceptance criteria
- Given the human confirmed the target and the new place of the check (OQ-89-8), when any role opens the sidebar, in simple or advanced mode, then the entry "Second checks" does not show. (rule 21)
- Given I am a nurse, pharmacist, doctor, visiting doctor or an owner with doctor details, when a chemotherapy order waits for my second check, then I can reach it from Queue (a tab or banner "N second checks waiting", default) with the same list and the same count as before. (rule 22)
- Given no order is waiting, then the Queue place shows "No second checks waiting for you." (existing text, SAH-66)
- Given the user who wrote an order, then they cannot do its second check, and the server answers 403 as built. (rule 22)
- Given a front desk, billing or counsellor user, or an owner without doctor details, then the new place is not shown and a direct call to the list answers 403 as today.
- Given a bookmarked address `/app/emr/second-checks`, then it still works for the allowed roles, or redirects to the new place; it never shows a blank page. (default)
- Given the menu key `secondchecks` is no longer sent by the server, then the shell shows no empty gap and no badge call is made for the entry. (rule 21)
- Given a patient-safety review, then no chemotherapy order can be given while its second check is waiting, and removing the menu changes none of that. (rule 22)
- Given the tests of the menu (SAH-66) , then they are updated for the new place and still prove the role rules.

## Notes for design
- Built today: `NAV_ITEMS`, `SIMPLE_MENU_KEYS`, `NavigationPolicy`, the badge store and the shell tests all name `secondchecks`. This story is blocked until OQ-89-8 is answered; if the human chooses to keep it (option C) or delete the check (option B, not recommended), this story changes or closes.
- The "Safety" tab inside IPD is a different thing and is not touched here.

---

### SAH-89-8 · As an owner setting up my clinic, I want a custom colour and an optional logo in the "Choose your colour and web address" step, so that my clinic looks like mine from the first screen
Priority: Should
Screen: Setup wizard step 5 (`suLook`), Clinic setup → Clinic profile ("Logo and colour", "Brand colour" with Custom colour).

## Acceptance criteria
- Given step 5, then the colour presets show first as in the mockup and a "Custom colour" option sits next to them. (default, OQ-89-4)
- Given I choose Custom colour, then I can pick a colour or type a 6-character code (with or without #), and the preview updates at once.
- Given a code that is not 6 hexadecimal characters, then "Enter a colour code with 6 characters, like 1F5BC8" shows (mockup text) and the colour is not applied.
- Given a colour whose button text would be under 4.5 : 1, then the screen warns "Button text is hard to read" and offers "Use the readable shade" as in the mockup; saving is still allowed. (default: warn, not block, OQ-89-4)
- Given a colour too light for text on white, then links and labels use the darker shade the mockup computes, and dark mode uses its own lighter shade; the three readability messages show as in the mockup.
- Given I save, then the brand is stored as `custom` with the hex code and the app opens in that colour; a preset stores its name. (rule 13)
- Given I upload a logo (optional), then PNG, JPG and WEBP up to 2 MB are accepted; SVG only if the architect confirms safe handling (default, OQ-89-5); other types show "Use a PNG, SVG, JPG or WEBP file for the logo" and files over 2 MB show "This file is <size>. Keep the logo under 2 MB." (mockup texts)
- Given an image that cannot be read, then "This image could not be read. Save it again as PNG and retry." shows (mockup text).
- Given a file whose real content is not an image although it is named .png, then the server refuses it with 400 and stores nothing. (rule 14)
- Given I skip the logo, then setup completes and the clinic's initials are used in the preview and in the app. (rule 14)
- Given a logo is saved, then it shows in the wizard preview and in the app sidebar; "Replace" and "Remove" work and Remove brings back the initials. Only the owner may do these.
- Given a logo with colours, then up to 4 colours from the logo are offered as quick picks for the custom colour (mockup "From your logo"), and each is checked the same way.
- Given another clinic, then its logo and colour are never visible to me (row-level security), and the logo is served only to signed-in users of my clinic.
- Given a non-owner, when it calls the colour or logo API, then 403.
- Given a slow network, then upload shows progress, can be cancelled, and a failure keeps the colour choice and shows "Could not upload the logo. Try again."
- Given a keyboard, then the swatches, the picker, the code field and the upload area can be used without a mouse, each with a label and a visible focus ring; colour is never the only signal (the selected swatch also shows a tick and its name).

## Notes for design
- Built: presets in wizard step 5; custom colour and a Google Maps link field on Clinic profile; no logo upload anywhere. The API already has brand `custom` and `brandHex`.
- Logo storage is the escalation of OQ-89-5 (documents service not in Phase 1); default is a bounded database column per clinic.
- Printing the logo on case papers and bills, and the booking page, are out of scope here.

---

### SAH-89-9 · As an owner, I want to fill my clinic address with Google Maps, so that the address is correct and ready for later use
Priority: Could (blocked until the human decides OQ-89-2)
Screen: Setup wizard step 1 (`suClinic`), Clinic setup → Clinic profile (Address, "Google Maps link").

## Acceptance criteria
- Given the default (option B, OQ-89-2), when I fill step 1 or Clinic profile, then the address is saved as four parts: address line, city, state, PIN code, and each part is validated by SAH-89-2. (rule 5, 9)
- Given the default, then Clinic profile keeps the optional "Google Maps link" with the hint "Paste the share link from Google Maps", and a link that is not a Google Maps address shows "Paste a link from Google Maps". (mockup, rule 6)
- Given option A is approved (Google lookup), when I type 3 or more letters in the address box, then suggestions appear; choosing one fills address line, city, state and PIN code for me to check. (rule 5, 7)
- Given option A, then my typed text is never overwritten without my choosing a suggestion, and I can edit every part after. (rule 7)
- Given option A and Google is slow or unavailable, then the form works by typing and shows "Address suggestions are not available now. Type your address." (rule 6)
- Given option A, then the Google key is read from the environment, not from code, is limited to our web address, and no patient data or patient mobile number is ever sent to Google. (rule 6)
- Given option A, then the first use asks nothing about patients and states in plain words that Google receives the text typed in this box (text agreed by the human).
- Given the address is saved, then the saved parts are read later by other screens (for example the case paper header and the booking page) and do not change unless the owner edits them; the change is audited. (rule 5)
- Given a non-owner, when it edits the address, then 403.
- Given another clinic, then its address is never visible (row-level security).
- Given the address contains a PIN that does not match 6 digits, or the state is missing, then saving is refused with the field message.
- Given an old clinic with a single free-text address, then the first edit asks the owner to split it into the four parts; the old text is not lost until they confirm.

## Notes for design
- This story is blocked on a human decision and, for option A, on a new ADR by the solution-architect (key handling, cost cap, data flow outside India, fallback, content-security rules).
- Default B needs no external service and is the part of this story that could ship now.
- Latitude and longitude are not stored by default.

---

## Traceability: rules to acceptance criteria
| Rule | Covered by |
|---|---|
| 1, 3 sign-up creates clinic, existing number | 89-1 |
| 2 no feature before setup ends | 89-1 |
| 4 unregistered on Log in | 89-1 |
| 5, 6, 7 address, no Google without approval | 89-9 |
| 8, 9, 10, 11 validation | 89-2 |
| 12, 13 custom colour | 89-8 |
| 14 logo | 89-8 |
| 15, 16 completion screen | 89-3 |
| 17, 18, 19, 20 header search | 89-4 |
| 21, 22 Second checks | 89-7 |
| 23 to 26 doctor fee | 89-5 (and 89-2 for the wizard step) |
| 27 to 31 owner as doctor | 89-6 |
