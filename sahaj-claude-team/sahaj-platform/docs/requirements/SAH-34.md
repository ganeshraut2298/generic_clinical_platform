# SAH-34 · IPD ward scoping and break-glass emergency access (nurse sees her wards; Emergency access; owner review)
Module: ipd
Mockup screen: IPD → Admitted (toolbar: "My patients", the scope chip / notice "As <role> you see …", the button "Emergency access", the card flag "Emergency access", the sheet "Emergency access") and IPD → Safety (tiles "Your patients" and "Emergency access", panel "Emergency access to review", "Mark reviewed", `bg` entries of the Clinical audit trail). Mockup parts: `ipList`, `pctog` `data-k="mine"`, `pcscope`, `pcVisiblePts`, `pcScopeTxt`, `sfBgOn`, `sfFlags`, `sfScope`, `sfCount`, `ipSafety`, `bgRow`, `sfbg`, `sfbgok`, `sfbgrev`, `kic`
Source: Tech lead, 9 Nov 2026, from DEC-031 (the human accepted the architect's recommended path of ADR-016). Open questions Q1 to Q25 of ADR-016 take the ADR's recommendation per DEC-023.

## Problem
Today every nurse sees every admitted patient of the clinic, on every IPD screen and API call (OQ-30-3, OQ-33-2). The mockup shows a nurse only the patients of her own wards, with a notice saying so, and gives her a documented way past the limit when a patient outside her wards needs her now: "Emergency access", which lasts 30 minutes, is logged, and is reviewed by the owner. Neither exists. This is a privacy gap (a nurse reads records of patients she does not care for; DPDP purpose limitation) and a missing safety valve.

There is also a clinical-safety risk in doing the first half without care: hiding a patient from a covering nurse can hide a deteriorating patient. This story therefore builds the scope, the break-glass way past it, and the owner's review together, and it makes the limit visible on the screen instead of silent.

## Who uses it (roles) and in which setting (clinic, hospital ward, front desk)
- **Nurse** (ward, nurses' station, tablet): with the clinic switch on, sees and acts on the patients of her assigned wards only; opens Emergency access for one other patient when needed.
- **Owner** (office, nurses' station): sees everything as today; reviews every emergency access in the Safety board and marks it reviewed. Turns the clinic switch on (that screen is story 34A, waiting for the mockup).
- **Doctor**: sees all patients as today (scope fixed "all"); sees the audit entries of emergency access.
- **Front desk, billing, pharmacist**: scope fixed "all" on the endpoints they already have; no change. They have no Emergency access.
- **Visiting doctor**: has no IPD access in phase 1 (403 on every IPD endpoint). The own-patient rule (admitting doctor) is built into the guard but no visiting doctor can use it until story 34D (waiting for OQ-31-8 and OQ-33-1(b)).
- Setting: hospital wards and nurses' stations (IPD only). OPD clinics without IPD are not affected.

## In scope / Out of scope
In scope (stories 34B, 34C, 34E, 34F of ADR-016 section 9.2):
1. **34B Scope enforcement (L).** One server-side guard on every IPD endpoint of ADR-016 section 6: lists filter, details and writes refuse with 403 `outside-scope`. Scope fixed in code (C1). The Safety board, its counts and the escalation alerts follow the caller's scope and never silently drop a patient (rule 7).
2. **34C Admitted list (S).** Scope chip with "N of M", "My patients", the zero-ward and empty texts, the ward select, handling of `outside-scope` on screen.
3. **34E Break-glass (M).** Button, sheet, 30-minute grant, card flag, logs.
4. **34F Owner review (S).** Tile, panel "Emergency access to review", Mark reviewed, audit entries.

Out of scope:
- **34A Nurse ward assignment (M): waits for the human to add the nurse-to-ward screen to the mockup.** Not invented here. It holds the ward-assignment table (several nurses per ward), the per-clinic switch "Nurses see only their wards" and its screen, and the audit of changes. Placeholder in `docs/stories/SAH-34.md`.
- **34D Visiting doctor in IPD (M): waits for the role answers OQ-31-8 and OQ-33-1(b).** Placeholder only.
- **34G The Roles and access panel "Which patients each role sees" (M, optional, later): out of scope.** The scope is fixed in code (C1); the panel is a mockup deviation the human accepted. Nobody can change a role's scope in phase 1.
- Assigned nurse per patient (A3), shift-based scope (A2), care team of doctors (B2), "treated-by" scope (B3).
- Scoping of the Emergency queue and cases, of the Patients (OPD) search and patient file, of the pharmacist check list, and of the configuration endpoints (OQ-34-6, OQ-34-4).
- Notifications of any kind to the owner or anyone (WhatsApp, SMS, email, push): none in phase 1.
- Ending a grant early, extending it, a free-text or "Other" reason, a cap on grants per user, a second reviewer, a comment or "not OK" outcome on a review, a reviewed history, filters or export of grants.
- Break-glass for patients who are not admitted (Emergency queue, discharged).
- Database-level (RLS) enforcement of the scope (S2): the scope guard is in the application (S1); tenant isolation stays in RLS.

## Business rules (numbered)
1. **Scope by role (fixed in code).** OWNER, DOCTOR, FRONT_DESK, BILLING, PHARMACIST: all admitted patients, on the endpoints each role already has. NURSE: all while the clinic switch is off; only her wards when it is on. VISITING_DOCTOR: own patients (admitting doctor), built in the guard but unusable until 34D. The browser and the request body never choose or change a scope.
2. **Clinic switch.** "Nurses see only their wards" is a per-clinic setting, off by default. Existing clinics are unscoped at deploy and stay so until their owner switches it on (screen: 34A).
3. **Ward rule for a nurse.** With the switch on, a nurse sees an admitted patient when the patient's current bed is in a ward with a live assignment to her membership. A ward may have several nurses and a nurse several wards. Assignments of an inactive membership count for nothing. A transfer moves the patient in or out of her view at the next request.
4. **Fail closed.** With the switch on, a nurse with no live ward sees no patient, and the screen says why. If the scope cannot be built (assignment, setting or membership cannot be read), the request fails; it never falls back to "all".
5. **One guard.** One scope guard in the application is used by every IPD service. Lists filter in the same set-based read; details and writes refuse. Writes check the scope inside the transaction after the admission row lock.
6. **Out-of-scope answer.** 403 with the problem type `outside-scope`, no patient data in the body. Module off, wrong role, another clinic's id and unknown id keep their existing answers; the role is checked before the scope.
7. **The limit is never silent (patient safety).** (a) Counts, tiles, tab number, red notice, banner and audit trail are computed on the caller's scope and agree with each other (tab = tile = rows) per caller. (b) The OWNER and DOCTOR boards are never narrowed by this story. (c) A scoped user's Safety board and Admitted list say that they are scoped ("N of M"), and an empty or clean board never reads as a hospital-wide all-clear. (d) A scoped user sees a count, without names, of escalations past their target in other wards (BA safety addition, OQ-34-26). (e) A nurse cannot act on an escalation outside her wards without Emergency access (OQ-34-23).
8. **What stays unscoped.** Emergency queue and cases, Patients (OPD) search and file, pharmacist check, configuration endpoints, and the second-nurse witness of a high-alert dose (SAH-38). Bed board: occupied-bed details of out-of-scope patients are hidden, bed status kept.
9. **Admitted list.** For a user whose scope is not "all": a chip "<scope> · N of M" (N visible, M admitted in the clinic) with the full sentence as its tooltip. "My patients" is a filter (not a security scope) for every role with the list except FRONT_DESK. The Ward select lists every ward.
10. **Break-glass: who and how.** Any user whose scope is not "all" (in phase 1: a ward-scoped NURSE) may open Emergency access. The sheet picks one admitted patient outside her scope, one of three fixed reasons, and a confirmation tick. OWNER never needs it.
11. **The grant.** One patient, 30 minutes fixed, counted by the database clock, no extension, no early end; a new grant after expiry needs a new reason and creates a new review row. One row per grant: a second user's grant on the same patient never replaces the first. It adds no permission: the user can do what her role can do, on that one admission. It is bound to her membership and role, and stops giving access at discharge, role change or deactivation. It is checked on every request.
12. **Alert to the owner is in-app only.** Phase 1 has no notification. The toast says "The owner will see this on the Safety board." The owner sees the tile, the panel and the number on the Safety tab. Never a sentence that claims an alert was sent.
13. **Owner review.** Every grant waits in the owner's panel until reviewed, also after expiry and after the patient is discharged. Mark reviewed is OWNER only, never by the person who opened the grant, once per grant, and allowed in IPD Read-only.
14. **What is written.** Per grant: a clinical audit entry (kind `bg`, fixed text, no names), one patient record-access row (R1), and a platform audit event, all with ids only. Per review: a clinical audit entry with fixed text and a platform audit event. Application logs carry ids, tenant, request id and the reason key only.
15. **Tenant and privacy.** The clinic comes from the signed-in user; row-level security isolates clinics. The break-glass picker shows identity only (name, bed, UHID), no clinical data. The list writes no record-access row (DEC-021).
16. **Plan state.** IPD Off: 403 `module-off`. Trial, On and Read-only: scope applies; opening a grant and the owner review are allowed in Read-only (OQ-34-29).
17. **Wording.** Plain English, sentence case; 24 Oct 2026, 11:05 AM IST; UHID (not MRN, OQ-36-8). Mockup text is kept; changed and new texts are listed in `docs/stories/SAH-34.md` and OQ-34-27.

## Patient-safety and privacy points
- **Covering-nurse risk (clinical sign-off, OQ-30-6).** A nurse covering a colleague's ward, or a patient moved to another ward, can leave a deteriorating patient out of the nurse's view. Controls in this story: the switch is off by default (a clinic chooses to switch it on); Emergency access with the reason "Patient deteriorating, rapid response"; OWNER and DOCTOR unscoped; the board and list say they are scoped; the count-only line of rule 7(d). **The clinical sign-off owner (OQ-30-6) must read ADR-016 section 8 and this requirement before any clinic switches the scope on.** This is a go-live condition for the human; no technical gate exists beyond the switch.
- **A scoped nurse must never read a quiet board as a quiet hospital.** "All within target" and "No escalations" are only true for her wards; the scope note and the other-ward count make that visible.
- **Fail closed.** No path may widen access when something cannot be read. A nurse with no ward is shown why, and Emergency access is still available to her.
- **Break-glass abuse controls:** one patient and one reason per grant, fixed reasons (no free text in an audit table), database clock, no extension, review by the owner with unreviewed grants kept after discharge, the grantee cannot review. Not covered in phase 1 (human to accept as a go-live condition): the owner never reviews (no notification, the tile stays amber); the owner is the abuser (no second reviewer).
- **The picker is a directory.** Before any reason is given, the Emergency access sheet lists every admitted patient outside the user's scope with name, bed and UHID (as the mockup, option P1). It carries no clinical data and no record-access row. Security review and privacy register must list it.
- **DPDP.** Scope is purpose limitation and data minimisation. With the switch on, the DEC-021 exception "no access row for the ward list" shrinks to the user's own wards (the go-live condition "revisit when ward scoping arrives" can be answered). Break-glass is the documented exception: reason, time limit, owner review, logs. The privacy register needs entries for scope rules, break-glass purpose and review, and retention of grants (never deleted in phase 1). "N of M" shows the hospital's census to every scoped user (low sensitivity, as the mockup).
- **No patient name, mobile number, ABHA number, UHID value or clinical text in application logs.** Audit rows hold ids and fixed keys. The patient name in the owner's panel and the audit trail is read at display time and shown to OWNER (and, in the trail, DOCTOR and the nurse's own entries); it is never stored in an entry.
- **Role and membership are read again on every request** (ADR-013 D3), so a change of role, ward or switch applies at once. A transfer racing with a write is decided on locked data.

## Plan or entitlement (which plan/module unlocks it)
IPD module (Trial, On, Read-only). Off: 403 `module-off` on every endpoint, existing lock page on screen. No other module or add-on is needed. HRA is not used (shift-based scope was rejected).

## Dependencies
- **34A** supplies the ward-assignment table, the per-clinic switch and the owner screen. 34B reads them; until 34A has a screen no owner can switch the scope on, so clinics stay unscoped and 34E has no user (OQ-34-35 asks the tech lead whether the data part of 34A is built with 34B).
- 34C and 34E depend on 34B. 34F depends on 34E.
- Reuses: SAH-33 (Safety board, audit trail, kind `bg`), SAH-36 (Admitted list), SAH-35 (Medicine round counts), SAH-38 (witness), SAH-40 (refused refresh clears clinical data), SAH-17 (patient record-access port), ADR-013 (membership re-read, list abilities), ADR-006 D4 (admission row lock).

## Open questions for the human
Full text, Recommended option and basis are in `docs/stories/SAH-34.md` section 3. Per DEC-023 the Recommended option is taken unless you answer. Marked **SAFETY** when a clinician could lose sight of a patient.
- OQ-34-1 to OQ-34-25 are Q1 to Q25 of ADR-016 with the ADR's recommendation applied (default applied, DEC-023). Q1 is the blocker for 34A (the human adds the screen).
- **OQ-34-26 SAFETY, needs human and clinical sign-off (OQ-30-6):** the covering-nurse risk and the count-only line of other wards' overdue escalations (a BA addition not in the mockup or ADR).
- OQ-34-27 (proceed): new and changed wording (scope sentence, zero-ward and empty texts, board scope note, "outside scope" and "access ended" messages).
- OQ-34-28 (proceed): the Admitted tab number for a scoped user.
- OQ-34-29 (proceed): grant and review while IPD is Read-only.
- OQ-34-30 (proceed): the owner's Safety tab number includes unreviewed grants (the mockup's count does not).
- OQ-34-31 **needs human (policy):** go-live conditions (clinical sign-off, privacy register, owner never reviews, owner as abuser, retention of grants).
- OQ-34-32 (proceed): order and paging of the review panel.
- OQ-34-33 (proceed): tooltip with the end time on the card flag.
- OQ-34-34 (proceed): wording "outside their wards" for a visiting doctor's grant (34D).
- OQ-34-35 (tech lead): whether the data part of 34A (table and setting) is built with 34B while its screen waits.
