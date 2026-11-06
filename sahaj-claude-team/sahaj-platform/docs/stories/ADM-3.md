# ADM-3 · Modules and plan management by the Sahaj admin, and the end of the owner-started IPD trial
Mockup screen: Sahaj admin portal (platform admin view) — organization detail Modules tab (`aModules`, actions `amod`, `amodbeds`), Plan and billing tab and the sheet "Change plan or price" (`ADM_KIND.plan`, `aActHTML`, `aago`), Activity log tab (`aAuditRows`); and the owner side Clinic setup → Plan and modules (`cfgModules`, key `modules`)
Module: platform (admin packages in `com.sahaj.platform`); the admitted-patients check reads `ipd` through `platform.api`
Parent design: `docs/architecture/adr/ADR-003-sahaj-admin-portal.md` (rev 2, D6 and section ADM-3), contract `docs/api/admin.yaml` v0.2.0, DEC-010 (owner IPD trial), DEC-011, DEC-012, `docs/stories/SAH-19.md` (plan and modules, owner side). Depends on ADM-1 (sign-in) and ADM-2b (organization detail).

Split for building (each part is one build):
- **ADM-3a** Modules: IPD state, HRA, bed limit.
- **ADM-3b** Change plan.
- **ADM-3c** Activity log tab.
- **ADM-3d** End of the owner-started IPD trial, and what the clinic owner sees.

ADM-3a must be released before or together with ADM-3d: the owner switch is locked only once the Sahaj admin can switch IPD on.

## 1. Stories

### ADM-3a · As a Sahaj admin, I want to set a clinic's IPD state (Off, Trial, On, Read-only), switch its HRA add-on and set its bed limit, so that the clinic gets exactly the modules it has agreed to, the product way
Priority: Must

### ADM-3b · As a Sahaj admin, I want to change a clinic's plan with a written reason, so that every plan change is explained in the audit log
Priority: Must

### ADM-3c · As a Sahaj admin, I want to see the log of admin actions for one clinic, so that I and my colleagues can see who changed what and why
Priority: Should

### ADM-3d · As the Sahaj team, we want the clinic owner's self-started IPD trial to end once admins can switch IPD on, and the owner's Plan and modules page to show what we set, so that IPD is switched on only by Sahaj
Priority: Must

Roles: only the Sahaj platform admin (`PLATFORM_ADMIN`) changes anything in ADM-3a to ADM-3c. The clinic roles keep what SAH-19 gave them: every clinic role reads Plan and modules; only the **owner** may request IPD and switch HRA; doctor, visiting doctor, nurse, front desk, billing and pharmacist cannot change anything. No clinic role can reach `/api/v1/admin/**`.

## Acceptance criteria

### ADM-3a · Modules

1. Given the organization detail, tab **Modules** (ADM-2b), then the IPD control **Off · Trial · On · Read-only**, the HRA control **Off · On** and the Bed limit stepper (buttons "−" and "+", aria labels "Fewer beds" and "More beds") are enabled for the signed-in platform admin. Before the result of a change returns, the changed control is disabled (so a second click cannot send a second request).
2. Given an IPD choice that differs from the clinic's current effective state, then the server applies it and the panel shows the new state from the server's answer. A choice equal to the current state does nothing: no request is needed, no change, no toast, no audit entry. Any state may be chosen from any other, except the refusal in AC 5.
3. Given **Trial**, then the trial end is set to 30 days from that moment (the mockup labels the state "Trial · 30 days"). Given **On**, **Off** or **Read-only**, then the trial end is cleared. Given a trial that has ended (the clinic reads Read-only) and the admin chooses **Trial**, then a new 30 days starts. A custom end date and extending a trial that is still running are not in the mockup (OQ-3-1).
4. Given an applied IPD change, then the toast reads "IPD {state in lower case} for {clinic name}" with " · menus and setup appear for the clinic right away" added for On and Trial. The state names come from the mockup: Off, Trial · 30 days, On, Read-only. For example "IPD on for Shree Sai Family Clinic · menus and setup appear for the clinic right away" and "IPD trial · 30 days for Shree Sai Family Clinic · menus and setup appear for the clinic right away" (wording question: OQ-3-2; the clinic does not see the change live: OQ-3-8).
5. Given **Off** or **Read-only** while one or more patients are admitted in that clinic, then nothing changes (also nothing else sent in the same request), the control stays on the current state, and the panel shows the critical notice "{n} patients are admitted in {clinic name}. IPD stays {current state in lower case} until they are discharged." (the API answers 409 `ipd-patients-admitted` with the count). The refusal is written to the audit log (AC 15). The notice goes away when the admin makes another module change or leaves the tab. For "admitted" see OQ-3-5 and OQ-3-6 (patient safety).
6. Given the clinic has no admitted patients, then Off and Read-only are allowed from any state. Switching IPD Off or Read-only never deletes data (the existing rule: records are kept; Off hides IPD, Read-only keeps past records readable).
7. Given the HRA control, then a change applies at once, the toast reads "HRA on for {clinic name}" or "HRA off for {clinic name}", and there is no guard (the mockup has none). Choosing the current value does nothing. HRA can also be switched by the clinic owner (SAH-19); the last change wins and each is audited in its own log.
8. Given the Bed limit stepper, then each click changes the limit by 5, within 5 to 300; "−" is disabled at 5 and "+" at 300; there is no toast (as the mockup). The new number shows from the server's answer. A limit lower than the beds the clinic already has is allowed: the existing beds stay, new beds are refused by the existing rule "Your plan allows N beds. Ask Sahaj to raise the limit." (OQ-3-7).
9. Given a request with an unknown field, a bed limit that is not a multiple of 5, outside 5 to 300, or an IPD state that is not one of off, trial, on, readonly, then the API answers 400 and changes nothing. A request that changes nothing returns the clinic unchanged and writes no audit entry.
10. Given a request that changes more than one module, then it is all or nothing in one transaction, and a refused IPD change (AC 5) leaves HRA and bed limit unchanged too.
11. Given a slow or failed network, then the control returns to its previous value and an error notice shows (text not in the mockup, OQ-3-10); a lost answer is safe to repeat because choosing the same value again changes nothing.
12. Given an unknown or malformed clinic id, then the API answers 404 or 400 and nothing is audited as a change.
13. Given the clinic's own app on its next request, then its menus, `/ipd/*` access (403 `module-off` when Off; 409 `ipd-readonly` when Read-only), the HRA menu and the bed limit follow the new values without the clinic signing in again. No notification is sent to the clinic (notifications are not in phase 1).
14. Given two admins changing different modules of the same clinic at the same moment, then both changes are kept. Given the admin switches IPD Off or Read-only at the moment a nurse or front desk admits a patient in that clinic, then both cannot succeed: either the admission is counted and the switch is refused, or the switch happens first and the admission is refused with `module-off` or `ipd-readonly`. This holds once the `ipd` admit transaction reads the entitlement with a shared lock (ADR-003 D6, request to the IPD developers). Until then a one-second race exists (OQ-3-6).
15. Given every applied change, then one `ACTION` / `MODULES_CHANGED` entry per changed field is written to `platform_admin_audit` in the same transaction: the admin (from the signed-in session, never the request), the clinic, the text "IPD set to {On | Off | Trial · 30 days | Read-only}", "HRA switched on" / "HRA switched off" or "Bed limit set to {n}", reason "Module entitlement", before and after values, outcome APPLIED, request id, address. Given a refusal, then one entry with outcome REFUSED and the refusal code is written. If the change is rolled back, no entry remains. Entries cannot be updated or deleted by any application role (proved as `sahaj_app` and `sahaj_migrator`).
16. Given access: a request with no token, a clinic token, a pre-auth token or an ended admin session answers 401, changes nothing and writes no audit entry; a request from a network that is not allowed answers 403. A clinic user (any role) cannot call these endpoints.

### ADM-3b · Change plan

17. Given the organization detail, tab **Plan and billing**, then the button **Change plan** (and in the header the button **Change plan or price**) opens a sheet. Both buttons are enabled for a platform admin. (The mockup's header places this button among several; with the others out of scope, it is shown directly, without a "More" menu.)
18. Given the sheet, then it is titled "Change plan or price" with the sub-title "{clinic name} · {code} · {city}", a close button (aria label "Close"), the information notice "The new plan starts at the next renewal unless the owner asks to switch now.", **New plan** as a segmented choice **Free year · Pro**, **Reason (saved in the audit log)** (multi-line, 3 rows, placeholder "What happened, and who asked for it"), **Support ticket (optional)** (placeholder "e.g. T-2304") and the buttons **Cancel** and **Change plan or price**. The reason box has the focus when the sheet opens. Cancel and close discard everything. Four things differ from the mockup and are open questions: the plan choices, the notice text (phase 1 applies at once), the button name (there is no price) and the second-admin approval (OQ-3-3).
19. Given a reason shorter than 10 characters after trimming, then the sheet stays open and the message "Write a reason of at least 10 characters. It goes into the audit log." shows (the API answers 400 with the field `reason`). The reason is at most 500 characters and the ticket at most 20 (letters, digits and hyphen; the message for a wrong ticket is OQ-3-10).
20. Given a valid plan change, then the plan changes at once, the sheet closes, the toast reads "Plan changed to {Free year | Pro}", and the header text and the Plan row show the new plan. Given the same plan as now, then the sheet closes with no change and no audit entry.
21. Given the clinic's own Plan and modules page on its next request, then the plan pill shows "Free year" or "Pro". Nothing else changes with the plan in phase 1: there is no billing, renewal or invoice, and no feature depends on FREE or PRO (OQ-3-4).
22. Given an applied change, then one `ACTION` / `PLAN_CHANGED` entry is written in the same transaction: admin, clinic, "Plan changed to {plan}", the reason as typed and the ticket, before and after, outcome APPLIED. The reason is never written to an application log. Roles, tenants and access: as AC 16.

### ADM-3c · Activity log tab

23. Given the organization detail, then a third tab **Activity log** is shown after Modules. It has the heading "Activity", the sub-line "Admin actions and security events for this clinic", and a table with the columns **When · Admin · Action · Reason · Second admin / consent**, newest first. (The mockup's red export-alert notice and its buttons are not shown.)
24. Given a row, then **When** is the time in IST (OQ-3-11); **Admin** is the admin's name with the role "Platform admin" in small type; **Action** is the bold text of AC 15 and AC 22 with the clinic name and code in small type; **Reason** is the reason as typed, with " (ticket {T-…})" added when a ticket was given, or "Module entitlement" for module changes; the last column is "—" (no second admin in phase 1).
25. Given refused IPD changes, then they are listed too, with the refusal notice as the reason (wording not in the mockup, OQ-3-10). Sign-in rows and "clinic viewed" rows are not listed. The log shows only this clinic's rows.
26. Given no entry, then the text "No entries." shows. Given more than 20 entries, then 20 are shown per page with the pager "Showing {from}–{to} of {n}" and previous and next buttons.
27. Given each time the tab is opened or a page is loaded, then one `ACCESS` / `CLINIC_ACTIVITY_VIEWED` entry is written. Another clinic's id shows only that clinic's rows; an unknown id answers 404. Access rules as AC 16.

### ADM-3d · End of the owner-started IPD trial, and what the clinic owner sees

28. Given the release that contains ADM-3a, then the setting `sahaj.modules.owner-ipd-trial` is `false` in every profile that real clinics or demos use, including `dev` (it is already `false` in the base configuration). The `test` profile keeps it on only until the integration tests set the entitlement another way (OQ-3-12).
29. Given the setting is off, then `POST /api/v1/platform/modules/ipd-trial` answers 404 for every user, `ipdTrialAvailable` is `false` in `GET /modules`, and the owner's page no longer shows the teal notice "Try IPD: start a 30-day trial here. In the product the Sahaj team does this from the admin console." or its button **Start 30-day IPD trial**. The endpoint is marked deprecated in `platform.yaml`; removing the endpoint and the setting is a later clean-up.
30. Given a clinic whose owner already started a trial before the switch-off, then its trial is not cancelled: it runs to its end date, then reads Read-only (existing rule), and the Sahaj admin sees the same state in the Modules tab and may choose Trial again for a new 30 days.
31. Given the owner of a clinic where IPD is Off, then on Clinic setup → Plan and modules the In-patients (IPD) row still shows the pill "Not on your plan" and the button **Request IPD** (owner only; other roles see the pill only). After the owner presses it, the row shows "Request sent · Sahaj will call you". The request is stored for the Sahaj team and is idempotent (SAH-19). If an admin later switches IPD back to Off, the row shows "Request sent · Sahaj will call you" again, because the request time is kept as history (OQ-3-9).
32. Given the admin sets IPD to **Trial**, then the owner's IPD pill reads "Trial · ends {date, for example 24 Nov 2026}" and the IPD menus and IPD setup pages open as the IPD stories define; **On** shows the pill "On"; **Read-only** shows the pill "Read-only" and the notice "IPD is read-only. Past admissions, bills and summaries stay viewable for 90 days, then IPD is hidden. Nothing is deleted." (the 90-day hiding is not enforced anywhere, OQ-3-13); **Off** shows "Not on your plan" and hides the IPD menus, setup pages and bill heads, and IPD calls answer 403 `module-off`.
33. Given the admin switches HRA, then the owner's HRA switch (row "Staff, timings and leave (HRA)", label Add-on) shows the new position on the next load; the owner can still switch it, and non-owners see it disabled with the notice "Only the owner can request IPD or switch HRA. You can see the plan below." Given the admin changes the plan, then the pill beside "Modules on your plan" reads "Free year" or "Pro". Given the admin changes the bed limit, then the IPD setup page "Wards, rooms and beds" shows "{beds} of {limit} beds on your plan" (for example "30 of 25 beds on your plan" after a limit lowered below existing beds) and adding a bed beyond the limit is refused.
34. Given the owner of the clinic, then nothing on the owner's pages names the admin or shows the admin's reason: the clinic does not see Sahaj admin entries in its own Activity log in phase 1 (OQ-3-9). The "Open the clinic app as owner" button of the mockup is not built.

## 3. Technical design

The design is in ADR-003 (D4 functions, D6 module and plan rules, D7 audit); this story does not repeat it. Points the developers need from the stories:

- **State used for comparisons.** "Current state" is the effective state (an expired trial counts as Read-only); the rules are reused from the existing `ModuleEntitlements` (trial length, effective state), not copied.
- **One transaction.** Lock the clinic's module row, read the admitted count through the `platform.api` port (the same port as ADM-2b), set the values, write the audit row; a refusal writes its own `REFUSED` row.
- **Owner side needs no new API.** The owner's page reads `GET /api/v1/platform/modules` (SAH-19) per request, so it follows the admin's change on the next call. Only the setting `sahaj.modules.owner-ipd-trial` changes (java-developer, `backend/src/main/resources/application-dev.yml`).

### What `docs/api/admin.yaml` (v0.2.0) and the database already have, and what is missing

| Need | Contract (`admin.yaml` v0.2.0) | Database function (ADR-003 D4) | Status |
|---|---|---|---|
| Change IPD state, HRA, bed limit | `PATCH /clinics/{id}/modules` (`adminChangeModules`), schema `ModulesChange`, 409 `ipd-patients-admitted` with `admittedNow` | `platform.admin_lock_modules`, `platform.admin_set_modules`, `ipd.admin_admitted_count` | Contract: present. Functions: **not written** |
| Change plan | `PUT /clinics/{id}/plan` (`adminChangePlan`), schema `PlanChange` | `platform.admin_set_plan` (column grant `UPDATE (plan, updated_at, version)`) | Contract: present. Function: **not written** |
| Activity log | `GET /clinics/{id}/activity` (`adminClinicActivity`), schemas `ActivityPage`, `ActivityEntry` | `platform.admin_list_clinic_activity` | Contract: present. Function: **not written** |
| Audit table | — | `platform.platform_admin_audit` (append-only, trigger) | **Not written** (the ADM-1 migrations in `db/pending/admin/` hold the core tables and auth functions only; the audit table is part of the admin core and must be active first) |
| Port for the admitted count | `ClinicModules.admittedNow` | `IpdOccupancyPort` (`platform.api`) and its `ipd` implementation | **Not written** |
| Owner trial lock | `platform.yaml` `POST /modules/ipd-trial` (still described as available) | — | Setting exists (`OwnerIpdTrialGuard`, `sahaj.modules.owner-ipd-trial`); **deprecation note in `platform.yaml` missing**; dev profile still `true` |

Gaps between the mockup, the stories and the contract, for the architect:
1. **Extending a running trial or a custom end date**: no field, no endpoint (OQ-3-1).
2. **Refused changes in the activity list**: the contract has `outcome: REFUSED` but no text for the action of a refused row; the stories propose "IPD change to Off refused" style text and need the human's approval (OQ-3-10).
3. **`ActivityEntry` has no clinic name or code**; the screen uses the clinic it is already showing.
4. **Plan sheet**: the contract allows FREE and PRO; the mockup offers Pro monthly, Pro yearly and Custom only (OQ-3-3).
5. The contract's text for `reason` on module changes is fixed ("Module entitlement"); the mockup has no reason box for modules, so this is consistent.

## 4. API (`/api/v1/admin`, contract `docs/api/admin.yaml`)
| Method | Path | Role | Request | Response | Errors | In contract |
|---|---|---|---|---|---|---|
| PATCH | `/clinics/{id}/modules` | PLATFORM_ADMIN | `ModulesChange` (one or more of `ipdState`, `hraOn`, `bedLimit`) | `ClinicDetail` | 400, 401, 403, 404, 409 (`ipd-patients-admitted`) | Yes |
| PUT | `/clinics/{id}/plan` | PLATFORM_ADMIN | `PlanChange` `{plan, reason, ticket?}` | `ClinicDetail` | 400, 401, 403, 404 | Yes |
| GET | `/clinics/{id}/activity` | PLATFORM_ADMIN | `?page&size` | `ActivityPage` | 400, 401, 403, 404 | Yes |
| POST | `/api/v1/platform/modules/ipd-trial` | OWNER | none | `404` after ADM-3d | 404 | Existing (SAH-19); deprecation note to add |

## 5. Tasks
### Tasks — Backend
db-engineer: `admin_lock_modules`, `admin_set_modules`, `admin_set_plan`, `admin_list_clinic_activity` (and the ADM-2 functions they share), optional plan check constraint. java-developer: admin modules and plan service reusing `ModuleEntitlements`, admitted-count refusal through `IpdOccupancyPort`, all-or-nothing change, setting `owner-ipd-trial=false` in `application-dev.yml`; IPD developers: shared-lock read of the entitlement in the admit transaction. Unit tests. Real-database tests by qa-engineer: refusal with admitted patients, trial date, clinic sees the change without signing in again, audit atomicity (a rolled-back change leaves no entry; a refusal leaves a REFUSED entry), append-only proof, trial endpoint 404.
### Tasks — Frontend
Enable the Modules controls on the detail; refusal notice; toasts; Change plan sheet with the validation message; Activity log tab with pager and empty state; confirm that the owner's Plan and modules page hides the trial notice when `ipdTrialAvailable` is false (already coded); unit tests with mocked HTTP. Owner-side page text is unchanged.

## 6. Out of scope, Open questions
Out of scope: second-admin approval and the Approvals page; price, Custom plan, Pro monthly and Pro yearly, Grace period, Suspended, renewals, invoices, refunds, extend free period; suspend and reactivate; WhatsApp allowance; support sessions and reset owner sign-in; a custom trial end date; the "Open the clinic app as owner" button; showing admin changes in the clinic's own Activity log; notifying the owner; admin roles other than one; the other mockup tabs (Usage, Settings for this clinic, Support); removing the owner-trial endpoint and setting (later clean-up).

Open questions (for the human; defaults are what the stories above use until answered):
- **OQ-3-1 Extend or start a trial.** The brief asks for "extend/start trial". The mockup has only the choice **Trial** (30 days) and choosing it while already on Trial does nothing; there is no extension and no date. Default (ADR): Trial starts 30 days from now, a new 30 days only when the trial has ended; a running trial cannot be extended. Do you want an "extend by N days" action or a date? That needs a mockup design first.
- **OQ-3-2 Wording with "Trial · 30 days".** The mockup builds its texts from the state label in lower case. So the toast reads "IPD trial · 30 days for {clinic} · menus and setup appear…", the refusal reads "IPD stays trial · 30 days until they are discharged." and the log reads "IPD set to Trial · 30 days". Default: exactly as the mockup. Or use the plain word "trial" and "read-only" everywhere?
- **OQ-3-3 Plan sheet differences.** (a) The mockup's choices are Pro monthly, Pro yearly, Custom (no Free year); the product has Free year and Pro only, so both are offered (ADR H4). Can an admin move a clinic back from Pro to Free year? (b) Default selection: the plan the clinic does not have now. (c) The notice "The new plan starts at the next renewal unless the owner asks to switch now." is shown although phase 1 applies the change at once. (d) The button name "Change plan or price" is kept although there is no price. (e) The mockup sends plan changes to a second admin for approval by default; phase 1 has none. Please confirm or change each.
- **OQ-3-4 What a plan changes.** In phase 1 the plan only changes the pill and the label; no feature, limit or price depends on it. Confirm. If Free year has an end date (free year length), who sets it, and does moving to Free year restart it?
- **OQ-3-5 (patient safety) What counts as "admitted".** The refusal counts patients whose admission status is Admitted (a patient with a signed discharge summary who has not been discharged still counts). The mockup counts admissions only. Patients waiting in Emergency who are not yet admitted, and discharged patients with an unsettled bill, are not counted. Should Off or Read-only also be refused while Emergency patients are being treated, or while a discharge is incomplete?
- **OQ-3-6 (patient safety) The race.** Until the IPD admit transaction takes a shared lock on the entitlement, a patient can be admitted in the same second IPD is switched off; the data is kept and readable in Read-only, but the patient would be in a bed while IPD is Off. The ADR accepts this until the IPD developers' change lands. Accept the risk for go-live, or block ADM-3a until that change is in?
- **OQ-3-7 Bed limit lower than existing beds.** Allowed by default (existing beds stay, new beds are refused). Owners then see "30 of 25 beds on your plan". Confirm, or refuse a limit below the beds already built.
- **OQ-3-8 How fast the clinic sees it.** The toast says menus and setup appear "right away" (a mockup in one browser). In the product the clinic sees the change the next time its app loads the plan and modules or opens an IPD page; an open session is not pushed, and the owner is not notified. Confirm that this is enough, or say that the clinic must be told.
- **OQ-3-9 Transparency and the request flag.** (a) Should the clinic's own Activity log show "Sahaj changed IPD to On" (ADR H11; default no)? (b) If an admin switches IPD Off again after the owner had asked, the owner sees "Request sent · Sahaj will call you" again, because the request time is kept as history (the mockup also never clears it). Should an admin action clear the request?
- **OQ-3-10 Texts not in the mockup.** Error text when a module or plan change fails ("We could not save this change. Try again." proposed); the message for a ticket with other characters ("Use letters, numbers and hyphens, up to 20" proposed); the Activity row for a refused change (Action "IPD change to Off refused", Reason = the notice text, proposed); the activity load error. Please approve or change.
- **OQ-3-11 Date and time in the Activity log.** The mockup shows "Today 10:42 AM" for new entries and has no older example. Default: "Today 11:05 AM IST" for today and "24 Oct 2026, 11:05 AM IST" for older entries (the project format).
- **OQ-3-12 Test profile and running trials.** Keep `owner-ipd-trial=true` in the `test` profile until QA moves its tests to set the entitlement through the admin path or the database (ADR H8)? Confirm that clinics whose owner already started a trial keep it (AC 30).
- **OQ-3-13 (clinical, legal) Read-only for 90 days, then hidden.** The owner's notice (SAH-19) and the admin panel promise that past admissions, bills and summaries are viewable for 90 days after Read-only and then IPD is hidden. Nothing enforces the 90 days; today Read-only keeps records readable indefinitely. Should a timer be built (a later story), or the text changed? Hiding clinical records after 90 days may conflict with record-keeping duties; this needs a human or legal answer.
- **OQ-3-14 Pill words on the owner's page.** The mockup's pill for IPD Off is "Off" and for Trial "Trial · 30 days"; the page already built in SAH-19 shows "Not on your plan" and "Trial · ends {date}". ADM-3 does not change them. Confirm the built wording stays.

## 7. Status log
- 5 Nov 2026 · business-analyst · Stories written from ADR-003 rev 2, `admin.yaml` v0.2.0, DEC-010, DEC-011, DEC-012, SAH-19 and the mockup (`aModules`, `amod`, `amodbeds`, `aActHTML`, `aago`, `aAuditRows`, `cfgModules`). Waiting for human approval of `stories`.
