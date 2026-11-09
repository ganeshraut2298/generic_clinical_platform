# SAH-37 · IPD patient card setup (Clinic setup → Patient card, draft and publish, and the Admitted cards that use it)
Module: ipd
Mockup screen: Clinic setup → Patient card, Roles and access, Wards/rooms/beds, Bed categories, Admission and deposits, Emergency and triage, Discharge — only the "Patient card" section (`cfgIpCard`, `pcBar`, `pcPreview`, `pcDefault`, `PC_PRESET`, `PC_LAY`, `PC_F`, `PC_S`, `PC_A`, the "Published versions" panel, the locked safety fields block `PC_F[k].safe` / `PC_S[k].safe`), and, on IPD → Admitted, the owner-only "Customize card" button and the layouts Compact, Standard and Expanded (`pcCard`, `pcgrid lay-*`)
Source: Tech lead, from the human's go-ahead of 9 Nov 2026 ("continue the further stories"). SAH-36 (`docs/decisions/DEC-021-sah36-admitted-cards-open-points.md`) named this story as the "SAH-37 candidate". Recommended defaults are taken per DEC-023.

## Problem
SAH-36 ships one fixed card, the Sahaj standard card. The mockup lets the clinic owner shape that card: pick a layout (Compact, Standard, Expanded), switch sections and fields on or off, rename and re-order fields, choose the quick actions, check the result in a live preview for each role, and then publish it, so that every nurse, doctor and front desk user sees the new card. A small nursing home wants no billing on the card and a hospital wants every field. Today the owner cannot change anything, and there is not even a "Patient card" entry in Clinic setup (`docs/stories/SAH-35.md` section 0, row 11).

Two dangers make this more than a settings screen. First, a card that can be reshaped could be reshaped so that the allergy, the early-warning score or the alerts disappear from the ward round. The mockup therefore "locks" some fields and sections; this story builds those locks on the server, not only in the screen. Second, the owner must be able to try changes without staff seeing half-finished work, so edits go to a draft and only Publish changes what staff see.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- **Owner** (clinic office or nurses' station, desktop or tablet): opens Clinic setup → Patient card, edits a draft, previews it as each role, saves, discards, resets, publishes. Also sees a "Customize card" button on IPD → Admitted.
- **Doctor, nurse, front desk** (ward, doctor's room, IPD desk): see the published card on IPD → Admitted. They may open Clinic setup → Patient card to read what is live (disabled controls, as the mockup shows for any non-owner); they never see an unpublished draft and cannot change anything.
- Billing, pharmacist, visiting doctor: do not have the Admitted list in phase 1 (`docs/api/ipd.yaml`); nothing changes for them. They are not offered as "View as" roles (OQ-37-4).
- Sahaj platform admin: the mockup's admin portal shows which card each clinic uses; out of scope here (see below).
- Setting: Clinic setup on a desktop or tablet; Admitted page on a desktop, nurses' station or tablet.

## In scope / Out of scope
In scope (everything the mockup's Patient card section shows, except what is listed as out):
1. Clinic setup menu entry "Patient card" (IPD group, after Discharge), its description, "Affects" chips and tips, and its line on the Overview tile ("Standard · Sahaj default · v1", with "· draft" when a draft exists).
2. The status bar: "Sahaj standard card · v1 live" or "Customized · v<n> live"; "Draft · N changes not published · saved <time>" or "No unpublished changes"; buttons Reset to default, Discard draft, Save draft, Publish.
3. Four tabs in the mockup. Built here: **Layout and sections**, **Fields**, **Quick actions**. The fourth tab, **Workflow stages**, is not built (OQ-37-3).
4. Layout and sections: three templates (Small clinic, Nursing home, Hospital), the layout choice (Compact, Standard, Expanded), "Cards per row" default (2, 3, 4), sections with on/off switch and order.
5. Fields: order, move to another section, on/off, own label per field.
6. Quick actions: order and on/off for the actions that exist today (Nursing, EMR, Transfer, Discharge, Deposit) with the "who can use it" line.
7. The live preview: "View as" role, patient, Desktop/Tablet, the "Hidden for <role>" line.
8. Draft, Save draft, Discard draft, Reset to default, Publish, version history ("Published versions", v1 "Sahaj standard patient card (default)"), and the Clinic setup change history lines.
9. The locked safety fields and sections of the mockup (rule 9), enforced by the server.
10. The Admitted cards using the published setup: layouts Compact, Standard and Expanded, the sections, fields, labels and actions chosen; the default "Cards per row"; the owner-only "Customize card" button.

Out of scope (each is a separate story or a human decision):
- **Clinic setup → Roles and access and ward scoping** (OQ-33-2, OQ-30-3). The mockup's patient-scope switches (All / In their wards / Assigned to them), the data matrix (Hidden, View, Edit per role) and the action matrix are not built. Who sees which data and which action is the **fixed rule** of rule 11, which only narrows what the server already allows. The button "Change who can use each action" and the scope notice are not shown. Nothing here changes access control.
- **Break-glass / Emergency access** (SAH-34) and its card flag.
- **Workflow stages**: the Workflow stages tab (names, colours, on/off, "Add stage"), the "Workflow stage" field, the stage chip, the stage filter chips on Admitted, the "Update stage" action. No stage exists in data or contract (OQ-36-2, OQ-37-3).
- **Assigned nurse** field (no nurse assignment exists) and the **Case paper** action (print is "soon" in the mockup). They are not listed in the setup, so there is no dead switch.
- The **"My patients"** button and the Sahaj admin portal's per-clinic line "IPD patient card" (platform admin portal).
- Restoring an older version (the mockup lists versions but offers no restore), per-person card settings, saved filters, sorting.
- Editing the Table view (the mockup's Table is the old table; setup does not change it).
- Printing, WhatsApp or any notification when a card is published.

## Business rules (numbered)
1. **Entry.** Clinic setup has the section "Patient card" in the group "In-patients (IPD)", after "Discharge", icon cards, text "What each admitted patient's card shows: layout, sections, fields, quick actions and workflow stages. Changes go live when you publish." (the last two words of the field list are changed to match what is built: OQ-37-3), Affects "Doctor, Nurse, Front desk", and the three tips of the mockup. It is shown only when IPD is Trial, On or Read-only (as the other IPD setup sections).
2. **One setup per clinic.** A clinic has one published (live) setup with a version number, and at most one draft. A clinic that never published has the **Sahaj standard card, v1**. The setup belongs to the clinic (tenant); the clinic comes from the signed-in user, never from the request.
3. **Who.** Only OWNER may save a draft, discard, reset or publish. OWNER, FRONT_DESK, NURSE and DOCTOR may read the **published** setup (the Admitted page needs it). Only OWNER may read the draft. The server decides; hidden or disabled buttons are not the protection.
4. **Draft and publish.** Every edit changes the draft only. Staff see the live card until Publish. Publish is possible only when the draft differs from the live setup. A publish creates the next version (v2, v3 ...) and keeps the old versions. Discard draft removes the draft. Reset to default replaces the draft by the Sahaj standard setup (not the live setup; the live setup changes only on Publish). Reset is not offered when the draft already equals the default.
5. **Layout and cards per row.** Layouts: Compact (key facts on one line, icon actions, fits 4 or 5 cards a row), Standard (sections with labels and vital tiles, the Sahaj default), Expanded (every field, section titles; the last-nursing-note line is not built: OQ-37-5). "Cards per row" 2, 3 or 4 is the **default** shown on the Admitted page; each person may still switch it for the visit (SAH-36). Tablets show 2 and phones 1 whatever is chosen (SAH-36).
6. **Templates.** "Small clinic" (Compact, billing section off), "Nursing home" (Standard, Deposit action on) and "Hospital" (Expanded, billing section on, all quick actions on, IP number and Admission type on). A template changes the **draft** only (layout, sections, fields, actions), not "Cards per row", and it asks nothing else. New clinics get the Sahaj standard card automatically; a template is optional.
7. **Sections.** The sections are Card header, Workflow status, Admission and bed, Care team, Vitals, Clinical, Billing, in the order of the draft. A section can be moved and switched off, except the locked ones (rule 9). The Card header is always first. A section that is off is hidden from every card, and so are its fields.
8. **Fields and labels.** Each field can be switched on or off, moved up or down inside its section or to another section, and given an own label (empty keeps the standard name). Only fields that have data in phase 1 are listed (OQ-37-3). The patient's name is required and fixed in the Card header.
9. **Locked safety fields and sections (patient safety).** The following cannot be switched off, cannot be moved into a section that is switched off, and cannot be removed by Reset, a template or an edit sent straight to the server: fields **Patient name**, **UHID** (the mockup's "MRN"), **Alerts** (the flags line), **Early warning** and **Allergies**; sections **Card header**, **Workflow status**, **Vitals** and **Clinical** (the last three hold a safety field). A section that holds a safety field cannot be switched off. The labels of the safety fields cannot be changed (OQ-37-1). Roles that may see clinical and vitals data always keep at least that (rule 11). Why locked: an allergy, an unseen escalation or a high score missing from the card is as dangerous as a wrong one.
10. **Quick actions.** The actions are Nursing, EMR, Transfer, Discharge, Deposit, in the order of the draft; each can be switched on or off; the default is Nursing, EMR, Transfer, Discharge on and Deposit off. The setup only chooses what is **offered**; it never grants. An action shows to a person only when the clinic switched it on **and** the fixed role rule and the clinic's IPD state allow it (SAH-36 abilities: Nursing and EMR for OWNER, NURSE, DOCTOR; Transfer for OWNER, NURSE, FRONT_DESK; Discharge for OWNER, DOCTOR; Deposit for OWNER, FRONT_DESK; Transfer, Discharge and Deposit not when IPD is Read-only). The server refuses a call the role may not make whatever the setup says.
11. **Fixed access rule (no Roles and access screen yet).** Data groups per role on the card: Patient and admission (name, UHID, age and sex, bed, IP number, admitted on, length of stay, ward, room, admission type, doctor): OWNER, FRONT_DESK, NURSE, DOCTOR. Contact (mobile): OWNER and FRONT_DESK. Clinical (diagnosis, allergies) and Vitals (readings, early warning, last recorded): OWNER, DOCTOR, NURSE. Billing (running bill, deposit, payer, and the Deposit low flag): OWNER and FRONT_DESK. A field the role may not see is **not sent** by the server and not drawn (not merely hidden). This is the mockup's default matrix, narrowed where the server already narrows it (mobile: OQ-37-4). It is not editable.
12. **Preview.** The preview draws the draft (or the live card when there is no draft) as the chosen role would see it, for a chosen admitted patient, on Desktop or Tablet width. It uses the same rules as the real card. It never changes data, opens nothing and shows fictional data when nobody is admitted.
13. **Admitted uses the published setup.** In the Cards view the cards follow the published layout, sections (order, on or off), fields (order, labels), quick actions and default cards per row. The flags, the 60-second reload, search, ward filter, paging and the card actions of SAH-36 are unchanged. The Table view is unchanged. If the published setup cannot be read or is not valid, the page shows the Sahaj standard card (never an empty card) with the notice "Could not load the card setup. Showing the standard card.".
14. **Publish takes effect** for each person at the next reload of the Admitted list (within 60 seconds) or when the page is opened; nobody is logged out or forced to reload (OQ-37-7).
15. **Customize card.** On IPD → Admitted, OWNER sees the button "Customize card" (sliders icon) in the toolbar, which opens Clinic setup → Patient card. Other roles do not see it.
16. **Server checks the setup.** Save and Publish are validated on the server: only known sections, fields and actions; every locked item present, on and in an allowed place; no duplicates; labels trimmed, at most 40 characters, plain text; a field only in a section the catalogue allows. A bad setup is refused with a field-level message and nothing is stored.
17. **No lost work.** Two owners editing at once never overwrite each other silently: Save draft and Publish refuse when the draft or the live version changed after the editor loaded it (OQ-37-6).
18. **History.** Saving a draft and publishing add a line to the Clinic setup change history (who, when, which parts changed: layout, sections, fields, quick actions); every publish is a stored version with number, person, time and a "what changed" line. No patient data is in any of them.
19. **Tenant and privacy.** One clinic never reads or changes another clinic's setup (row-level security). The setup holds no patient data. The preview and the cards write no record-access rows beyond what SAH-36 already writes (none for the list, DEC-021).
20. **Vital tile marks.** A tile outside the normal range carries the word "Check" or "Critical" as well as colour. The server decides the mark from the approved SAH-30 rule, never from a new limit and never in the browser (OQ-37-11).
21. **Wording and format.** Plain English, sentence case; dates as 24 Oct 2026, times as 11:05 AM IST; texts from the mockup are kept; changed or new texts are listed in the story and OQ-37-8.

## Patient-safety and privacy points
- **Locked safety fields** (rule 9) are enforced by the server on every save and publish, and again when the card is read: if a stored setup is missing a locked item, the server adds it back. The screen shows them with the pill "Safety · always on" (green, lock icon); the name shows "Required".
- **Allergies always on the card** for OWNER, DOCTOR and NURSE: the Clinical section is locked on and holds the locked Allergies field. In the Standard and Expanded layouts the line shows the status, "None known", "Not recorded" or the recorded text (red). This answers the concern of OQ-36-6 (a never-asked patient looked like a safe one). In the mockup's Compact layout the line drops "None known" and "Not recorded" (OQ-37-2, needs human).
- **Early warning always on the card**: the Vitals section is locked on and holds the locked Early warning field; with no vitals the card says "No vitals recorded yet" (amber "Not recorded" for last recorded).
- A role never receives a field it may not see (rule 11); front desk never gets vitals, early warning, allergies or diagnosis, whatever the setup says. Switching on a field in the setup can never widen access.
- Cards put clinical data for many patients on one screen (SAH-36, OQ-36-7, DEC-021): showing diagnosis and the five vitals extends that to more values. The human already accepted "no access row for the list" for the SAH-36 card; this story adds diagnosis and vital readings to the same list (OQ-37-1 asks to confirm).
- The preview with a real patient shows clinical data on the owner's screen; it is the data the owner can already read on the Admitted page. Only the owner may open the setup editor. The "View as" role never re-requests data under another role's name; it applies the fixed rule on the owner's own answer (architect decides how; no extra patient data leaves the server).
- Free text: field labels are typed by the owner and shown to all staff. They are plain text, 40 characters at most, never run as markup (no script in a label). They are not patient data and not secret.
- No patient name, mobile, UHID, allergy, diagnosis or vitals value is written to logs, the setup change history or a version. The change history holds only the parts changed.
- A wrong setup must not make a patient disappear: the patient name and bed are always on the card; a failed load shows the Sahaj standard card.
- Wrong-patient risk is unchanged from SAH-36: every action's accessible name carries the patient's name.

## Plan or entitlement (which plan/module unlocks it)
IPD module. Trial and On: the whole setup (OWNER edits). Read-only: the section can be opened and read; Save draft, Discard, Reset and Publish are not offered, with the existing IPD Read-only message (OQ-37-10 asks to confirm; the architect matches the other IPD setup sections). Off: the section is not in the menu and the API answers 403 `module-off`; the Admitted page shows the existing lock page.

## Dependencies
- SAH-36 (the Admitted cards, flags, actions, abilities, 60-second reload) is built; SAH-37 changes how its card is drawn, not what its flags mean. SAH-30 (vitals, early warning), SAH-31 (allergy status), SAH-33 (escalations), SAH-25 (deposit low), SAH-26/27 (transfer, discharge) are reused.
- The list answer (`GET /admissions`) needs the extra facts for fields now switchable (diagnosis, the five vitals and last recorded, payer, admission type, IP number, mobile) for the roles in rule 11 only. The architect decides the shape and the cost for 100 hospitals polling every 60 seconds (one batch read, no per-patient calls).
- **Not** dependent on SAH-35 (Medicine round): the Dose overdue flag, when it arrives, is part of the locked Alerts field.
- Blocked and unrelated: Roles and access / ward scoping (OQ-33-2), SAH-34.

## Open questions for the human
Full text, Recommended option and reason are in `docs/stories/SAH-37.md` section "Open questions". Per DEC-023 the Recommended option is taken unless you answer. Those that touch a clinical safety field are marked **SAFETY**.
- OQ-37-1 **SAFETY, needs human**: the default card (the mockup's full Standard card against the smaller SAH-36 card), and whether the labels of the locked safety fields may be renamed.
- OQ-37-2 **SAFETY, needs human**: the Compact layout hides "None known" and "Not recorded" allergy.
- OQ-37-3 (proceed): what the setup lists when data does not exist (stages, nurse, case paper); the fourth tab is not built.
- OQ-37-4 (proceed, sign-off): the fixed access rule, mobile on the card, "View as" roles, non-owners read the live card only.
- OQ-37-5 **SAFETY / privacy, needs human**: the Expanded "Last nursing note" line.
- OQ-37-6 (proceed): concurrent editing.
- OQ-37-7 (proceed): when a published change reaches staff.
- OQ-37-8 (proceed): label limits and new or changed wording.
- OQ-37-9 (proceed): what the history records; the Overview tile and setup search; the admin-portal line.
- OQ-37-10 (proceed): behaviour when IPD is Read-only.
- OQ-37-11 **SAFETY, needs human**: the "Check" and "Critical" marks on vital tiles (the mockup's own limits are not the SAH-30 bands).
