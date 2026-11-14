# ADR-026 · EMR design alignment (SAH-75): per-profile frame chrome, embedded treatment course and its actions, starter defaults by forward seed, body map where drawn, Dental assessment cards, Oncology cancer journey and watch chips

Stories: SAH-75-1 to SAH-75-11 (`docs/stories/SAH-75.md`, requirement `docs/requirements/SAH-75.md`, BR75-1 to BR75-12, OQ-75-1 to OQ-75-16).
Decision: DEC-052 (human approval 14 Nov 2026, and the addendum answering OQ-75-6, OQ-75-7, OQ-75-12, OQ-75-16). Audit: `docs/reviews/EMR-design-audit-2026-11-14.md`.
Design package: `docs/design/emr-config/emr-config-design-handoff/emr-config/` (SCREENS.md, `source/*.dc.html`; its full HTML is the EMR mockup).
Module: **opd** (owner of everything here). billing and ipd only read opd through ports that already exist (`TreatmentCoursePort`, `CourseAdmissionSource`); patient is written only through the existing `PatientClinicalUpdatePort` (ADR-025 D13). **No new module, no new library, no external service, no new module edge, no new permission.**
Status: PROPOSED (14 Nov 2026). The `contract` stage (this ADR, `docs/api/opd.yaml` 0.7.0, `docs/api/billing.yaml` 0.4.0, `docs/api/ipd.yaml` 0.3.2) needs the human's approval. Items under "Stopped for the human" are defaults written into the contract, not choices.
Addendum (14 Nov 2026): D1–D11 approved in DEC-053. **D12–D14 and H19–H23** close three gaps the angular-developer found (`docs/handoffs/SAH-75.md`, last two entries) and come with `docs/api/opd.yaml` 0.7.1; they are additive and need the human's approval as a contract follow-up.
Builds on: ADR-020 (layout model D6, starter layouts D4, record snapshot D8), ADR-022 (component binding D2, body map D8, case paper D9, course D12, ports D1), ADR-023 (profiles D1, storage D2, anchors D3, rules D4, visit-mode hiding D13.3, chemo order D6), ADR-025 (sign D1 step 9, patient write-back D13, admission requests D10).

Rule tag used below: **"value from the EMR mockup, not clinically reviewed"** (BR75-5; same meaning as the DEC-039 tag) — written "tagged".

---

## Context

Facts checked in the code, the seed and the contracts on 14 Nov 2026:

1. **The frame is already "profile chrome in code, sections from data".** ADR-023 D1 put the screen chrome (mode switch, summary strip, finish label, footer texts) in one client registry keyed by profile; sections, order, part, Show, Required come from the record's snapshot. The audit confirms it ("Configuration rule: mostly met").
2. **Department section set** (`EmrLayouts.base`): the latest published version's sections, else the **current** starter layout, else blank (derived from the GM starter's COMMON sections plus `abha`, `consent`, `rxformat`). `EmrLayoutRules.validate` demands the saved set equal that base exactly (400 `section-set-mismatch`). So today a starter that gains a section breaks every stored draft of a never-published department of that starter, and a published clinic cannot add it even by "Reset to template".
3. **Starter layouts are immutable** (`emr_starter_layout`, trigger); a change is a new `layout_version` and `emr_starter_department.current_layout_version`. Records keep the version they opened with (ADR-020 D8).
4. **Starter colours** in `emr_starter_department.colour` are exactly the BR75-10 accents for the seven drawn departments; the other eight are `#3E4C5E`.
5. **Library:** 100 sections. A field of type BODY_MAP is already a shared BODY_MAP component (ADR-022 D2), so a new library section with one BODY_MAP field needs no new code for storage, MLC, register or case paper. `pain.site` (Physiotherapy) is covered by the PAIN_SCORE editor and stores a text `site` only. `lesion_map` is a derm section; there is no "Local examination" body-map section; `ortho_local` is a different card ("Look, feel and move").
6. **Course model** (ADR-022 D12): status is computed (PLANNED, TODAY when a linked OPD visit is today's or a linked admission is open, DONE when the linked visit is signed or the admission discharged); the sign sets `done_at` (ADR-025 D1 step 9). No missed or removed state. Money is **derived on read** by billing from the sittings' status through `TreatmentCoursePort` (estimate, charges done, due today); there is no stored charge row per sitting.
7. **A DAY_CARE or IPD sitting with `admissionRequested` already is the admission request**: ipd lists it through `TreatmentCoursePort.openAdmissionSittings` (ADR-025 D10). opd cannot call ipd (module cycle).
8. **Lifetime doxorubicin** is already computed from SENT chemo orders plus the outside dose (`EmrSpecialtyView.onco.cumulativeDoxorubicinMgM2`, ADR-023 D4). **No scheme amount exists anywhere**: billing course payment types are VISIT_PAYMENT, FULL_BALANCE, PART_PAYMENT, ADVANCE, IPD_DEPOSIT; the chemo order holds only a scheme *state* (APPROVED, PREAUTH_RAISED, NOT_IN_PACKAGE, SELF_PAY); there is no scheme master or scheme ledger.
9. **The patient module holds no clinical store** (ADR-022 H9) and must never depend on opd. It has no husband name and no blood group.
10. The allergy drug-class check knows only `SULFA` and `PENICILLIN` (ADR-022 D5); the patient write-back writes the visit's allergy list at sign (ADR-025 D13, H25 "None" tokens).

---

## Decisions

### D1 · 75-1 / 75-2 frame chrome: what is data and what is code

**Rule (kept from ADR-023 D1, made explicit):** *what* is on the screen is data; *how a drawn department frames it* is code in the profile registry.

| Data (layout snapshot and library; owner changes it in the configurator) | Code (profile registry, keyed by the record's `starterKey` profile; generic for the other eight starters and clinic-made departments) |
| --- | --- |
| Which sections, their order, part, Show, Required, Print, Copy, collapsed, who can fill; section and field names, field options; the new `presentation` (chips) and option `alert` flags (D6) | Layout mode: 50/50 split from 1280 px, stacked below 1280 px with sticky tabs (OQ-75-11) — every profile including generic |
| Required counts and Done (`parts[].requiredDone/requiredTotal`, `stillNeeded`, `stillNeededItems`, server) | Left section nav: **gm only** among the drawn profiles; generic keeps it (OQ-75-10) |
| Section hidden by visit mode (`specialty.hiddenSectionKeys`, server) | Part tabs: gm, dental, surgery, onco; **og, paeds, physio one scrolling page** in published order, no tabs, no badges (OQ-75-9) |
| | Quick tools tab and which card moves into it: gm (visit templates, Trend · last 5 visits), paeds (Dose by weight, Feeding), physio (Home exercises, Goals), og (ANC: Due now, PCPNDT Form F, Supplements; Gynae: Quick orders). A moved card is still the section of the layout: hidden in the layout → not in Quick tools |
| | Part subtitles, "n of m required" / "n of m done" badge words, assessment-bar and ready-footer sentence templates, print button labels, card-title overrides (gm "General examination", "Systemic examination", "Local examination · body map"; onco "Other complaints (optional)"; surgery "Local examination · body map") |
| | Accent colour (BR75-10): gm #1D5FA8, dental #0B6E69, og #A3366B, paeds #B4471F, physio #6D3FC0, surgery #1E40AF, onco #9D174D; generic uses the app colour. On the course page the accent follows the course kind (DENTAL → dental, ANC → og, REHAB → physio, SURGERY → surgery, CHEMO → onco) |

- **Sentences are templates filled from data.** Every "Still needed …" names the layout's section names (lower case) from `stillNeededItems`; the drawn example names are never hard-coded (SAH-75-2 note). Values come from the record view: og warnings = `specialty.og.alerts`, next ANC = `ANC_CHECK.nextAncWeeks` and the gestational age; paeds vaccines given today and medicine count from the record; physio session numbers from `specialty.physio.sessionNo`; dental "n procedure(s) done today" from `specialty.dental.doneTodayRowIds`; gm print language from `printPreferences`.
- **Options considered.** (a) Chrome in code (chosen): the chrome is not drawn as configurable anywhere, its texts are bound to logic, and ADR-023 already put it there. (b) Chrome as data on `emr_starter_department` (a JSON "frame" per starter): rejected — nothing in the design lets an owner edit it, it would need a validator and versioning for no user, and a clinic-made department would still have no drawn chrome. (c) Accent from the department's own `colour` (data): rejected because a clinic-made department would get a palette colour, against BR75-10 ("other departments keep the app colour"); the seven values equal the starter seed, so code and data agree today.
- **Contract effect:** none for the chrome. The only server facts the sentences need already exist (D10 lists two small additions for 75-11).

### D2 · 75-4 starter defaults by forward seed migration; the department section set becomes "version ∪ current starter"

**Seed (global data only, new migration files, `V2026_12_08_*` untouched):**
1. New library sections (D5, D6): `local_exam` "Local examination" (SPECIALTY; one field `body_map` BODY_MAP, meta "Front / back", not required) and the six Dental cards `dental_medhx`, `dental_hx`, `dental_pain`, `dental_extraoral`, `dental_intraoral`, `dental_xray`. Library keys never start with `f_` or `d_` (`dental_` is allowed).
2. Two additive library columns: `emr_section_field.presentation` (DEFAULT | CHIPS, default DEFAULT) and `emr_section_field_option.is_alert` (default false). Existing rows keep the defaults.
3. Starter placements (`emr_starter_department_section`), exact results after the migration (the doctor screen wins, DEC-052 item 6):

| Starter | Required after | Other changes |
| --- | --- | --- |
| gm | complaints, **vitals** ("General examination"), diagnosis, rx | `local_exam` added, ASSESSMENT, right after `exam`, shown, optional, print on, copy off, who: doctor; `procedures` shown (T1); Treatment order diagnosis, rx, investigations, procedures, advice, followup (T2) |
| dental | complaints, **dental_medhx**, diagnosis, **tx_plan** (dental_chart and rx no longer required, as SAH-75-4 states; the db-engineer reports which were required before) | the six cards added in ASSESSMENT right after `complaints`, in the drawn order, shown, optional except `dental_medhx`; `intraoral` and `extraoral` (generic) placements kept but **shown = false** (never removed, see the set rule below); Treatment order diagnosis, dental_chart, tx_plan, (course panel is not a section), rx, advice (D5 of 75-11) |
| og | complaints, diagnosis, rx, anc, pcpndt, **menstrual** | none |
| surgery | complaints, **sx_fitness** ("Pre-op work-up"), diagnosis, **sx_plan**, **sx_consent**; rx **optional** (OQ-75-2) | `local_exam` added, ASSESSMENT, after `sx_findings` and before `sx_fitness`, shown, optional |
| onco | diagnosis, rx (complaints **optional**) | none |
| every starter (15) | `rxformat` **not required** (G4); stays shown | none |

4. One new immutable starter layout per starter (`layout_version` = current + 1, built in SQL from the rows above with the same builder as version 1, copied section definitions including `presentation` and `is_alert`), and `emr_starter_department.current_layout_version` moved to it. All 15 change because of `rxformat`.
5. **Blank layout** follows: it is derived in code from the GM starter's COMMON sections plus `abha`, `consent`, `rxformat`, so a blank department now starts with "Vitals" required and "Procedures" shown, and `rxformat` not required (flagged H14).

**Never touched:** any tenant row — `emr_department` (drafts), `emr_department_version`, `emr_record` and its snapshot reference, forms, courses, patients. A record keeps the starter version it opened with (BR45-18). A TEMPLATE department (no row) shows the new starter at once, which is the intended effect (SAH-75-4 criterion 2).

**Department section set (ADR-020 D6, changed additively):** the set the configurator works on and the save checks is now **the latest published version's sections ∪ the current starter layout's sections (for a department with a `starterKey`) ∪ live forms**. When the base has a section that the working copy (stored draft or latest version) lacks, the department read **appends it at its starter position** with:
- for a department that has a published version: the starter's settings but **shown = false, required = false** (offered for adding; doctors see nothing new until the owner shows it and publishes — BR75-4: "the owner adds or resets them");
- for a department never published (doctors already see the current starter layout): the starter's own settings.

Nothing is written on read; the next Save draft or Publish carries the full set as today. "Reset to template" returns the current starter settings (new defaults) and its save now passes the set check. A section is never removed from the set (a starter that stops using a section keeps the placement hidden), so no stored draft or version can fall out of the set. This answers the SAH-75-4 note "the architect confirms the configurator offers the new sections for adding": **yes, as hidden rows of the department, switched on with Show.** Options considered: (a) union with hidden rows (chosen); (b) an "Add section from library" control — not drawn (SO-45-9 family), rejected; (c) leave published clinics without the sections until they reset — impossible today (reset fails the set check), rejected.

**Proof that only global data changed (SAH-75-4 criterion 1):** three layers, all required.
1. *Static IT* (`EmrSeedAlignmentIT`, QA): every INSERT, UPDATE or DELETE statement of the SAH-75 seed files targets only `opd.emr_section`, `opd.emr_section_field`, `opd.emr_section_field_option`, `opd.emr_starter_department`, `opd.emr_starter_department_section`, `opd.emr_starter_layout`; any other table name fails the test.
2. *Catalogue IT*: no foreign key from a tenant table to those tables has ON UPDATE / ON DELETE actions other than NO ACTION or RESTRICT, and no trigger on them writes to another table.
3. *Before/after checksum* (db-engineer writes `docs/database/SAH-75-seed-checksum.sql`, read-only, run as the superuser so RLS does not hide rows): per tenant table of opd, patient, billing, ipd: row count and `md5(string_agg(t::text, '|' order by <pk>))`; run on `sahaj_dev` before and after the migration, outputs identical. The human runs it (agents do not touch `sahaj_dev`); a fully automated before/after on fixture data needs a scratch database that `scripts/setup/create-database.mjs` (human-owned) does not create (H10).
4. *Same flags on a fresh and an upgraded database* (criterion 12): an IT reads `GET /emr/starter-departments/{key}` for the seven drawn starters and compares required/shown/order with the table above; the same query run on `sahaj_dev` after the upgrade gives the same answer.

### D3 · 75-3 embedded course panel and the start entry

- **Reuse, not a new resource.** The panel is the SAH-58 course component hosted in the doctor screen; it reads `GET /emr/courses/{courseId}` and `GET /api/v1/billing/courses/{courseId}/account` as the page does. Which course: `GET /emr/courses?patientId=&kind=` (new optional `kind` filter), newest ACTIVE first; the existing switcher picks another.
- **Where, per profile (code, D1):** dental after `tx_plan` and before `rx` (kind DENTAL, replaces the old button and link); og after the ANC section in ANC mode only (kind ANC; none in Gynae mode); physio after the examination cards, before advice (REHAB); surgery after `sx_consent` and `sx_preop`, before advice (SURGERY); onco Treatment part after "F · Next cycle and patient calendar", before referrals (CHEMO). "Open full course" links to `/app/courses/:courseId`.
- **Empty state (OQ-75-12, human):** title, "No course started yet." and "Start course". It opens the SAH-58-1 start flow with kind and default name filled, calling `POST /emr/courses` with `fromRecordId`. Dental keeps "Start course from plan" (`fromPlan`). **Kind SURGERY is now accepted** by `POST /emr/courses` (the start entry); the OT booking (ADR-023 D8) then uses, in order, the ACTIVE SURGERY course started from this record, else **the patient's most recent ACTIVE SURGERY course of the same doctor (any for OWNER)**, else creates one — so an episode started at the first consult carries to the pre-op visit (H11).
- **Sub line (G15-4):** `TreatmentCourseView.estimateSigned` (new column `estimate_signed`, set by `fromPlan` when the estimate is signed); the server stops writing the text "estimate signed by patient" into `note` for new courses; for old rows the read treats a note equal to that text (ignoring case) as signed (no rewrite). "sample rates" never exists server-side.
- **Accent, money, roles:** as SAH-58-3; the panel never computes money; `canCollect` decides the collect panel (doctors see money, never collect). IPD-off hides DAY_CARE/IPD choices (`addOptions` already filtered).

### D4 · 75-10 course actions: states, endpoints, charges, the sign rule, audit

**States.** A sitting is in exactly one state, computed on read from stored columns in this order:
1. **REMOVED** — `removed_at` set. Not returned in `sittings`, not counted anywhere (estimate, counts, steps), not listed for admission. Never deleted.
2. **DONE** — `done_at` set, or the linked visit's record is SIGNED, or the linked admission is DISCHARGED (unchanged).
3. **MISSED** — `missed_at` set.
4. **TODAY** — a linked OPD visit created today, or a linked admission not discharged (unchanged), **or (new) an unlinked OPD sitting whose `plannedDate` is today** (Asia/Kolkata). DAY_CARE and IPD sittings without a link stay PLANNED (their card offers "Admit now").
5. **PLANNED** — otherwise.

**Display numbers (SAH-75-10 "later sittings are renumbered"):** `sittingNo` stays the stable, never-reused key (audit, links, idempotency); the new `displayNo` is the 1-based rank of the sitting among non-removed sittings by `sittingNo`, used for "S<n>", "<Unit> n", physio "Session n of m" (m = non-removed sittings) and the oncology cycle number. Receipt "against" texts already printed keep their words (H1).

**Endpoints (one per drawn action, like `link-visit`; all take the course `revision`; all return `TreatmentCourseView`).** Who: `clinic.consultation.write`, the course's doctor or OWNER with doctor details (403 `not-own-patient`); FRONT_DESK and BILLING 403 (they collect only); NURSE, PHARMACIST, COUNSELLOR 403; another clinic 404. Stale revision or a second press → 409 `course-changed` with detail "This sitting changed. Reload." Wrong state → 409 `sitting-not-available` (or `sitting-done`), nothing changes.

| Action (drawn label) | Path under `/emr/courses/{courseId}/sittings/{sittingId}` | From → to | Rules |
| --- | --- | --- | --- |
| "Mark <unit> done" | `POST …/mark-done` `{revision, visitId?}` | TODAY → DONE | Sets `done_at`, `done_source = MARK_DONE`, `done_by_membership_id`. Optional `visitId` (the hosting EMR's OPD visit): when the sitting has no link, links that visit first in the same transaction (same checks as `link-visit`). |
| "Patient did not come" | `POST …/missed` `{revision}` | TODAY without a link → MISSED | A sitting with a linked visit or admission: 409 `sitting-not-available` (the patient came). No charge. Toast "Marked missed." (OQ-75-4: no reminder claimed). |
| "Rebook" | `POST …/rebook` `{revision, plannedDate}` | MISSED → PLANNED (or TODAY when the date is today) | Clears `missed_at`; date today or later (400 validation "Choose today or a later date."). |
| "Reschedule: <date> ▾" | `POST …/reschedule` `{revision, plannedDate}` | PLANNED → PLANNED / TODAY | Date only; today or later. Booking a chair or appointment stays the "next sitting" flow (SAH-69-3). |
| "Remove" | `POST …/remove` `{revision}` | PLANNED → REMOVED | Not for TODAY, MISSED or DONE (409). No receipt or advance is touched; an IPD deposit earmarked to it stays course credit. |
| "Move to day care / IPD" (OPD sitting) and "Request admission" (a doctor on a planned DAY_CARE / IPD sitting, OQ-45-31) | `POST …/admission-request` `{revision, encounterKind?}` | PLANNED OPD → PLANNED DAY_CARE (default; IPD when sent) with `admissionRequested`; or PLANNED DAY_CARE/IPD → `admissionRequested` true | IPD OFF → 403 `module-off`. The charge is kept. **The request reaches ipd through the existing port** (ipd's `GET /ipd/admission-requests` reads `TreatmentCoursePort.openAdmissionSittings`); opd never calls ipd. Repeat: 200, nothing written. |
| "Admit now" (OWNER, FRONT_DESK; `canAdmit`) | none new | — | Opens the existing SAH-23 sheet with `treatmentCourseSittingId` (ADR-022 D12, unchanged). |
| "+ <choice>" ("Add to this course:", "Extra session") | existing `POST …/sittings` | → PLANNED | Unchanged (SAH-58-2). |

`CourseSitting.allowedActions` (new) lists, for the caller, the actions the server would accept now; the client draws buttons from it and the server still checks.

**Charges and the SAH-58-2 rule "signing marks a sitting Done" — one state, one charge either way.** No charge row exists: billing derives "charges done" from the DONE state on every read (fact 6). Mark done and the sign both only set `done_at` **when it is null** (the sign's step 9 of ADR-025 D1 becomes "if `done_at` is null"), so whichever comes first makes it DONE and the second is a no-op; the charge is counted once by construction. A DONE sitting refuses every action and `PUT` (409 `sitting-done`); corrections are addenda on its visit. A sitting settled in IPD (linked to an admission) can be marked done, its charge stays on the IPD bill and out of the course account (ADR-022 H1); the toast then cannot say "added to the account" (H2).

**Billing interplay** (no billing code path changes beyond reading the new states): `TreatmentCoursePort` stops returning REMOVED sittings and returns MISSED as a status; billing's sums already count DONE only for charges and treat anything else as not done; "today's charge" becomes the **sum** of TODAY sittings not settled in IPD (there can now be two: a linked one and an unlinked one planned today). `CourseAccount.nextPlannedAdmission` gains `displayNo`. `linkAdmission` refuses a REMOVED, MISSED or DONE sitting (409 `course-sitting-not-available`, ipd.yaml 0.3.2 note).

**Other existing operations:** `PUT …/sittings/{id}` refuses MISSED (409 `sitting-not-available`, "Rebook it first"); `link-visit` accepts PLANNED or TODAY-unlinked OPD sittings; the sign never meets a MISSED linked sitting (a linked sitting cannot be missed).

**Audit** (`platform.api.AuditLog`, same transaction; entity TREATMENT_COURSE / course id; ids, numbers, status words and dates only — never items, notes or names): `COURSE_SITTING_MARKED_DONE`, `COURSE_SITTING_MISSED`, `COURSE_SITTING_REBOOKED`, `COURSE_SITTING_RESCHEDULED`, `COURSE_SITTING_REMOVED`, `COURSE_SITTING_ADMISSION_REQUESTED`, each `{sittingId, sittingNo, displayNo, statusBefore, statusAfter, plannedDateBefore, plannedDateAfter, encounterKindBefore, encounterKindAfter}` (unused keys null). A `mark-done` with `visitId` also writes `COURSE_SITTING_VISIT_LINKED`.

Options considered for the actions: (a) one endpoint per action (chosen; per-action schemas and checks, like `link-visit`); (b) one `…/actions` endpoint with a discriminated body (fewer paths, but one schema with conditional fields and mixed rules); (c) reuse `PUT` (needs the full sitting body for a date change; cannot express Missed or Remove).

### D5 · 75-5 body map where drawn

| Place | How | Storage |
| --- | --- | --- |
| General Medicine "Local examination · body map" | New library section `local_exam` (one BODY_MAP field) placed in the gm starter (D2). Generic renderer + the existing BODY_MAP component; the card title override is chrome | `sections.local_exam.values.body_map` = `EmrBodyMapValue` (ADR-022 D8): pins, MLC rule L-1, `EMR_MLC_FLAG_CHANGED`, `header.mlc`, case paper 4 Examination |
| General Surgery "Local examination · body map" | Same section placed in the surgery starter after `sx_findings` | Same |
| Medical Oncology exam tab "Body map" | Inside the ONC_EXAM editor (the whole "2 · Examination" card is `onc_bsa`, ADR-023 H1) | New optional `SpOncExam.bodyMap` = `EmrBodyMapValue`; the server applies the same pin rules, MLC rule and audit to it and counts it in `header.mlc`; ONC_EXAM Done unchanged (the body map is optional) |
| Physiotherapy "Pain site" (OQ-75-3) | Single marker on the shared body figure, front view, R and L as drawn, plus a one-line note; no register, no MLC | `SpPainScore.siteMarker {side FRONT, x, y, region}` (nullable) and `site` (the note, now ≤ 120) |

Pins and the marker use the body-map region keys fixed in code (ADR-022 D8). Clinics that published GM or Surgery before see `local_exam` as a hidden row they can switch on (D2). Case paper: `local_exam` → 4 Examination; the finish dialog's "Local findings" row is composed in the client from the BODY_MAP value (ADR-025 D2).

### D6 · 75-6 Dental assessment cards: a chip-group renderer for five cards, one specialty editor for "Medical history and alerts"

**Options.** (a) Six specialty editors with their lists in code (ADR-023 D4 style). (b) Six library sections rendered by a generic **chip-group** presentation driven by seed data. (c) **Hybrid (chosen):** (b) for the five cards that are pure chip groups, plus one editor for the card that has rules.

Why: five cards are a title, a second line and groups of chips — exactly library sections with CHIPS (many) and SINGLE_CHOICE (one) fields; making them data keeps "rendered from configuration" (the owner can hide, require, reorder, print them) and the story asks for the content as starter seed data (BR75-4). "Medical history and alerts" has a review stamp, alert rules, an allergy-class link and the patient write-back: logic that ADR-023 D4 says lives in code.

**Library additions (data).** `EmrFieldDefinition.presentation` CHIPS renders a CHOICE field as a single-choice chip row (pressing the chosen chip again clears it) and a MULTI_CHOICE field as a many-choice chip row; an option with `isNone` clears the others of the field and is cleared by any other (existing `is_none`); `alert` options show red when chosen. Chips stay 44 px high (OQ-45-30). Sections, fields and options exactly as SAH-75-6 lists them (tagged; option keys follow the library rule, for example `o6_12_months`, `plus_2`):
- `dental_hx` "Dental history and habits" / "Shapes the treatment plan and advice": `last_dental_visit` (one), `brushing` (one), `habits` (many; every habit `alert`).
- `dental_pain` "Pain" / "For the main complaint": `type` (many), `score` (one, `o0`…`o10`), `since` (one).
- `dental_extraoral` "Extra-oral examination": `face`, `tmj` "Jaw joint (TMJ)", `lymph_nodes`, `mouth_opening` (one each).
- `dental_intraoral` "Intra-oral examination" / "Tooth-by-tooth findings go on the chart in Treatment": `oral_hygiene`, `gingiva`, `calculus` "Calculus / stains", `occlusion` (one each), `soft_tissue` (many; Ulcer, White patch, Red patch, Tongue coating `alert`).
- `dental_xray` "Radiographs and photos" / "Tap to order; attached images open in the viewer": `images` (many: IOPA, OPG, Bitewing, CBCT, Intra-oral photos). Choosing is an order in this visit; "(attached)" and the viewer are Later (documents service), nothing is sent. Case paper heading 6 Investigations advised.
- `dental_medhx` "Medical history and alerts" / "Ask before any injection, extraction or surgery": library fields `conditions`, `medicines`, `allergies` (CHIPS, with "None") for the configurator's field list; on a dental OPD record they are COVERED by the editor.
Alert flags: as the design draws red chips (`DoctorDental.dc.html:468-475`); the db-engineer copies them from that line.

**Editor DENTAL_MED_HISTORY** (profile dental, anchor `dental_medhx`). Value `SpDentalMedHistory {conditions[], medicines[], allergies[], reviewedWithPatient, review (stamp, read-only)}`, enums as drawn; NONE with another chip of the same group → 400 validation. Done = `reviewedWithPatient` true ("Mark as reviewed" → "✓ Reviewed with patient"; pressing again takes it back and clears the stamp). Audit `EMR_DENTAL_MEDHX_REVIEWED` `{reviewed}` (record id; no chips).
- **Alert banners** are computed by the server and returned as `specialty.dental.medicalAlerts [{key, title, text}]` in the drawn order and words (tagged): PENICILLIN_ALLERGY, BLEEDING_RISK (Blood thinners, Aspirin or Bleeding disorder), DIABETES, BP_HEART (High BP or Heart disease), PREGNANT, BISPHOSPHONATES, TOBACCO_USE (habits `tobacco_chewing`, `gutkha`, `paan_betel`, `smoking` of `dental_hx`, read from the same record). They warn; they never block.
- **Drug-class check (SAH-48, ADR-022 D5) reused:** the visit's allergy classes = the Allergies section's class items ∪ the classes of the chosen `dental_medhx` allergies (PENICILLIN → `PENICILLIN`; the others have no class, H15). The existing rules follow unchanged: alerts on prescription rows, `completeBlockers ALLERGY_ALERT`, reviews cleared when the class set changes in a save.
- **Write-back at signing (OQ-75-16, human):** ADR-025 D13 is extended, not copied: the visit's confirmed allergy list = active Allergies section items ∪ the chosen `dental_medhx` allergy labels ("Penicillin", "Local anaesthetic", "Latex", "Painkillers (NSAIDs)"), de-duplicated ignoring case. "None" in `dental_medhx` counts as an explicit None (writes "No known allergy") only when no allergy is recorded in either place; over an existing patient allergy it needs `confirmAllergyRemoval` (H25 rule kept). Fingerprint conflict, removal rule, 400-character limit and the finish-check `patientRecord` rows are unchanged; the patient module writes and audits `PATIENT_CLINICAL_FACTS_UPDATED` as today. Conditions and medicines chips are not written to the patient record (not asked).
- Header tags "Allergy: <chips>" and "Medical: <chips>" are composed by the client from the record; `header.allergyText` stays the patient record.
- **Case paper:** `dental_medhx` and `dental_hx` → 3 Medical history; `dental_xray` → 6 Investigations advised; the others → 4 Examination; the client formats "Extra-oral: face <v>, TMJ <v>, nodes <v>" and "Intra-oral: oral hygiene <v>, gingiva <v>, occlusion <v>" (one formatter per section key, empty values left out).
- Clinics with a published Dental layout keep it; the six cards appear as hidden rows they can switch on (D2).

### D7 · 75-7 Oncology journey strip and watch chips

**Module placement.** The journey record is clinical data written from the EMR by the treating doctor: **opd**, table `opd.emr_cancer_journey` (FK `patient.patient`). Rejected: the patient module (it has no clinical store, would need EMR access rules of its own, and patient must not depend on opd for the doctor checks); the record document (the strip needs the patient's latest values across visits, and a stage edit is not a fact of one visit's signature).

**Shape (OQ-75-7: a simple per-patient form, layout ours).** Append-only versions: each save inserts version n + 1 (history kept, never updated or deleted); the current record is the highest version. Fields (all optional; the form layout is not drawn and is the human's to approve, H6): `stageGroup` (0, I, IA, IB, IC, II, IIA, IIB, IIC, III, IIIA, IIIB, IIIC, IV, IVA, IVB, IVC; anything else 400), `tnmText` ≤ 40 ("cT2 cN2 M0"), `markersText` ≤ 80 ("ER+ PR+ HER2−"), `ki67Pct` 0–100 (above 100 → 400), `geneticsText` ≤ 60 ("BRCA pending"), `intent` CURATIVE | PALLIATIVE, `setting` NEOADJUVANT | ADJUVANT | DEFINITIVE | ADVANCED, `lineNo` 1–10 ("1st line"), `boardDate` (not more than 365 days ahead). Lists are ours, not clinically reviewed (H6).

**Write:** `PUT /emr/records/{recordId}/cancer-journey` `{expectedVersion, …fields}` — the record's access (own patient, `clinic.consultation.write`), OPD_VISIT of the onco profile (else 400 `not-applicable`), record DRAFT (SIGNED → 409 `emr-record-signed`); stale `expectedVersion` → 409 `journey-changed`. Audit `ONC_JOURNEY_UPDATED` (entity ONC_JOURNEY / patient-scoped row id; `{version, changedFields}` names only).

**Read:** the strip is one server block `specialty.onco.journey` on every record read and save (never a half-filled strip):
- `record` (the current journey or null → cells "Not recorded");
- `diagnosisText` = the first active diagnosis item of this record, else of the patient's latest SIGNED onco record;
- `plan` (null → "No chemotherapy plan") from the patient's newest ACTIVE CHEMO course: `courseId`, `title` ("AC ×4 → Paclitaxel ×4": runs of consecutive regimens of the cycle sittings), `cycleNo` / `cycleCount` (display numbers among non-removed `chemo_cycle` sittings), `cycles[{sittingId, label, state GIVEN | TODAY | PLANNED | DELAYED | MISSED}]`, DELAYED when today's sitting and the recorded decision is DELAY_1_WEEK;
- chip labels: the sitting's `regimenKey` short label + its ordinal within that regimen ("AC1", "P1"; short labels AC → AC, PACLI_3W and PACLI_WEEKLY → P / "Paclitaxel", TC → TC, in code, tagged), "C<n>" when no regimen is set. **New sitting column `regimen_key`** (CHEMO only, FK `opd.emr_chemo_protocol`): settable in the sitting plan/add/change inputs; a new `chemo_cycle` sitting copies the previous cycle's regimen; sending a chemo order sets the linked sitting's regimen when it is null;
- response cell from the existing `onco.response` and `onco.sums.pctFromBaseline` ("Not measured" without lesions).

**Watch chips:** `specialty.onco.watch.doxorubicinGivenMgM2` = the outside dose entered on the latest SENT order + Σ doxorubicin of the patient's SENT orders in this clinic, **excluding this record's own order** (the drawn "180 / 450" is before today's cycle), null (chip hidden) when there is none; `doxorubicinLimitMgM2` 450 (tagged C-8). The order sheet's own cumulative check is unchanged. **Scheme balance: not available (OQ-75-8).** No scheme amount exists in billing, the course ledger or the chemo order (fact 8); the chip is hidden in every case and the contract carries no field for it. Building it needs a scheme payment type or a scheme ledger in billing — new scope for the human (H4). "No known drug allergy" uses the existing header rule (OQ-45-23).

**Also for 75-11 (O2-2):** `specialty.onco.dayCareBed` (bed label of the admission linked to today's CHEMO sitting, through `CourseAdmissionSource`, which ipd extends with the bed label; null shows nothing) and the intent from the journey.

### D8 · 75-9 Paediatrics overdue vaccines

No shape change: `SpImmunization.givenToday` already accepts DUE_TODAY and OVERDUE vaccines (and refuses LATER and already given ones, 400). "Mark all due as given" adds every DUE_TODAY and OVERDUE vaccine of `specialty.paeds.vaccines` in one draft save; the toast counts them ("<n> vaccines recorded"; batch numbers Later, OQ-75-13). ADR-023 D4 "marks due today only" is superseded (tagged clinical rule from the mockup). Two quick presses: the client sends one save (set semantics by `vaccineKey`, server de-duplicates).

### D9 · Visit-mode hiding (BR75-9) and required flags

ADR-023 D13.3 gains one key: **og ANC hides `menstrual`** (GYNAE already hides `anc`). So the newly required Menstrual history counts only in Gynae mode and Today's ANC check only in ANC mode; a hidden section never appears in `stillNeeded`.

### D10 · 75-8 / 75-11 minor gaps: contract changes only where data is missing

Everything else in 75-8 and 75-11 is client text, layout or behaviour on data already returned. Additive contract items:
- `EmrPastVisitDetail.medicinesHeading` MEDICINES | DISCHARGE_MEDICINES (IPD, IPD_ROUND rows) | TAKE_HOME_MEDICINES (an OPD record of the onco profile with a SENT chemo order) (G16-1).
- `EmrSpecialtyHistory.dentalToothHistory [{tooth, date, text}]` from the patient's SIGNED dental records' plan rows done (D6 "History of <tooth>"; empty → "No earlier work on this tooth.").
- `EmrSpecialtyHistory.physioProgress.painBySession [{sessionNo, score}]` (P-3 "Sessions 1–3: 7, 6, 5").
- `EmrSpecialtyHistory.oncologyCycles[].lesions [{name, cm}]` (O-6 trend line).
- `specialty.onco.dayCareBed` (O2-2, D7).
- `SpPreauth.sent` is described as "pre-authorisation requested by the clinic" (G-S7; nothing reaches a payer).
- G-S8 OT date chips: **no OT days setting exists** (OQ-45-28); the date is a free choice. No contract change.
- O7 Gynae header "Husband: <name> · Blood group <group>": **no source exists** (fact 9). Left out until the human decides (H5).

### D11 · Permissions and privacy

No new permission. Course actions: `clinic.consultation.write` + course doctor or OWNER with doctor details. Journey: the record's access. Configuration seed: no endpoint. NURSE, FRONT_DESK, BILLING, PHARMACIST, COUNSELLOR never read or write EMR data (403); course reads keep ADR-022 D14. Tenant from the signed-in user only. No log line, audit value or problem detail holds a patient name, chip value, journey value, finding, sitting item, scheme or allergy text.

### D12 · "No known allergy" chips: a three-state allergy status on the patient facts (0.7.1)

**Gap.** Paediatrics draws a grey "No known allergy" chip (`DoctorPaeds.dc.html:44`) and Oncology a grey "No known drug allergy" watch chip (`DoctorOncology.dc.html:773`), shown only when "none" was recorded explicitly (OQ-45-23). The header has only `allergyText`, so the client cannot tell "none recorded" from "nothing recorded"; and today a recorded "No known allergy" (written by the sign, ADR-025 D13, DEC-048 H25) comes back as `allergyText` and draws a red "Allergy: No known allergy" tag.

**Decision.** The patient record's allergy text stays the only store (no new column, no new module, no new write path). `patient.api.PatientClinicalPort.PatientClinical` gains `allergyStatus` (enum `AllergyStatus` RECORDED | NONE_RECORDED | NOT_RECORDED in `patient.api`), worked out by the patient module from the text it returns:
- NOT_RECORDED: text null or blank.
- NONE_RECORDED: the whole text, trimmed, lower case, trailing full stops removed, is one of `none`, `nil`, `nka`, `nkda`, `none known`, `no known allergy`, `no known allergies`, `no known drug allergy`, `no known drug allergies`.
- RECORDED: anything else ("Penicillin; None" is RECORDED).

The token set moves to `patient.api` as the single rule; `EmrSignService.isNoneToken` uses it (it gains `none known`, the mockup's sample word), so what the sign writes and what the header reads cannot drift. The writes are unchanged: an explicit None at sign (Allergies section or the Dental `dental_medhx` "None" chip, D6) writes "No known allergy" through `PatientClinicalUpdatePort`; the front desk can type a none word in the patient form.

**Contract.** `EmrRecordHeader.allergyStatus` (required) and, narrower, `allergyText` null unless RECORDED. Client: red tag for RECORDED in every department; Paediatrics grey chip "No known allergy" and Oncology grey watch chip "No known drug allergy" for NONE_RECORDED; no tag for NOT_RECORDED, and none for NONE_RECORDED in the other departments (not drawn there). Emergency case without a patient: NOT_RECORDED. Key facts (SAH-50) and the case paper are unchanged.

**Options considered.** (a) Status on the patient facts, from the existing text (chosen): no schema change, one rule, the existing write-back is reused. (b) A boolean column `allergy_none_recorded` on `patient.patient`: a second fact that the front-desk text edit and the sign must keep in step; rejected. (c) Work it out in opd only: ipd and opd would each keep a token list (ipd already has its own, see H20); rejected.

### D13 · Gynae menstrual history and contraception as drawn: library data, tiles from option hints (0.7.1)

**Gap.** `DoctorGynae.dc.html:189-212, 372-378` draws Menstrual history as four single-choice rows (Cycle, Cycle length, Flow with "With clots", Pain) and Contraception as six method tiles with a second line. The library has `menstrual.cycle` as free text, `menstrual.dysmenorrhoea` as a Yes/No toggle, `flow` without "With clots", no cycle length, and Contraception only the generic fallback fields (Findings, Template, Attachments). Both sections render with the generic renderer (ADR-023 D2), so this is library data plus one renderer rule.

**Decision (global seed data, forward migration; tagged).**
- `menstrual` (fields in this order): `lmp` unchanged (DATE, required, read for EDD); `cycle` becomes SINGLE_CHOICE, presentation CHIPS, options `regular` "Regular", `irregular` "Irregular", meta "" (was PATTERN "Regular / irregular, days"); **new** `cycle_length` "Cycle length" SINGLE_CHOICE CHIPS, options `under_21_d` "< 21 d", `o21_27_d` "21–27 d", `o28_30_d` "28–30 d", `o31_35_d` "31–35 d", `over_35_d` "> 35 d"; `flow` becomes SINGLE_CHOICE CHIPS (was DROPDOWN; same CHOICE value kind, stored keys unchanged), meta "", adds option `with_clots` "With clots" after `heavy`; `dysmenorrhoea` keeps its key, label "Pain", becomes SINGLE_CHOICE CHIPS, options `none` "None", `mild` "Mild", `severe` "Severe", meta "" (was TOGGLE); `age_at_menarche` unchanged, last. None is required except `lmp` (as before); no option is `is_none` or `is_alert` (single-choice rows, same rule as the Dental cards).
- `contraception`: **new** field `method` "Method" at position 1 (the existing three fields move down one), SINGLE_CHOICE CHIPS, options with hints: `none` "None" / "Not using", `condoms` "Condoms" / "Barrier", `ocp` "OCP" / "Daily pill", `cu_t` "Cu-T" / "IUCD, 5–10 years", `injectable` "Injectable" / "Every 3 months", `sterilisation` "Sterilisation" / "Permanent".
- New option column `hint` (≤ 60, null) on `emr_section_field_option`; `EmrFieldOption.hint`. **Renderer rule:** a CHIPS field with at least one option hint draws as tiles (three columns, label bold, hint below, at least 56 px; chosen tile accent border and tint). No new `presentation` value.
- **Keys kept, types changed in place** for `cycle` and `dysmenorrhoea`. Safe because every record validates against its own snapshot and versions and starter layouts hold copied definitions (ADR-020 D6, D8): records opened before keep the old types. The one place an old value meets a new definition is copy from last visit (`menstrual` has copy on in the og starter), so **copy now leaves out a value that does not fit the new record's field definition** (different value kind, or a choice key that is not an option of the new definition); a section where nothing fits is not copied. Without this rule a returning patient's old text "Regular, 28 days" would be copied into a choice field and the next save would answer 400.
- og starter: one new starter layout version (definitions copied, including `hint`); placements unchanged.

**Options considered.** (a) Retype in place plus the copy-fit rule (chosen): the card shows the drawn rows, no duplicate "Cycle" row. (b) New keys beside the old fields: the card would show both "Cycle" (text) and "Cycle" (chips), and a Yes/No dysmenorrhoea toggle beside "Pain"; fields cannot be hidden per department; rejected. (c) A Gynae specialty editor in code: logic-free chip rows do not need code (D6 reasoning); rejected. For the tiles: (a) option `hint` + rule (chosen); (b) a new presentation TILES (one more enum and check value for the same chip behaviour); rejected.

**Reach.** A TEMPLATE or never-published og department shows the new rows at once (new current starter version). A published department keeps its version's definitions until the owner's next publish that changes a setting; a publish with no change stays NOTHING_TO_PUBLISH (ADR-020 D6, unchanged; H22).

### D14 · Oncology Assessment card order as drawn (0.7.1)

`DoctorOncology.dc.html:84-233` numbers the cards 1 Side effects (`onc_tox`), 2 Examination (`onc_bsa`), 3 Pre-chemo labs (`onc_labs`), 4 Performance status (`onc_ecog`); the decision card is drawn after ECOG. The onco starter has `onc_ecog` 17, `onc_tox` 18, `onc_bsa` 19, `onc_labs` 20. **Starter placement change only:** `onc_tox` 17, `onc_bsa` 18, `onc_labs` 19, `onc_ecog` 20 (part ASSESSMENT and every other flag unchanged; no other onco row moves). The decision card stays chrome (ADR-023: decision in `screen_state`), drawn by the client right after the `onc_ecog` card. One new onco starter layout version. Published departments keep their order until the owner changes it (Up/Down) or resets to template (H23).

---

## Consequences

Positive: the doctor screens match the design without moving layout rules out of data; new starter defaults reach only new or reset layouts, and the configurator can now offer starter sections to published clinics; the course becomes runnable from its card with one state machine and no second charge path; the Dental cards and the GM/Surgery body map are seed data the owner controls; the journey strip has an honest source and an edit form.
Negative / accepted: the department section set is now a union (a configurator change the angular- and java-developer must test with old drafts); `TODAY` widens to unlinked sittings planned today (changes "Due today" for them); display numbers can differ from numbers already printed on receipts; the scheme chip never shows in phase 1; the before/after checksum on real data is a human-run script, not an automated test.
0.7.1 (D12–D14): the allergy none words become one rule in `patient.api` (the header can no longer show a recorded "none" as a red allergy); two library fields change type under the same key, which is safe only with the new copy-fit rule (java-developer) and is the first in-place retype of a library field; option tiles come from data (`hint`) rather than a new presentation value.

---

## Stopped for the human (defaults written into the contract)

| # | Item | Default |
| --- | --- | --- |
| H1 | "Remove" renumbers later sittings (SAH-75-10) while ADR-022 kept numbers never reused; receipts already printed name the old number | Stable `sittingNo` kept as key; `displayNo` renumbers on screen; printed receipt texts unchanged |
| H2 | "Mark done" on a sitting billed in IPD: the drawn toast "₹ x added to the account" would be false | Allowed; toast "<Unit> n done · billed in IPD" (wording not drawn) |
| H3 | TODAY now also means "unlinked OPD sitting planned for today" (the design's Today card) | As written; "Due today" includes it |
| H4 | Scheme balance chip "MJPJAY · ₹ 38,000 left" (OQ-75-8) has no data source | Hidden always; a scheme payment type or ledger in billing is a new story |
| H5 | Gynae header husband name and blood group (O7) need new patient fields (personal data) | Left out of the header until the human decides |
| H6 | Cancer journey form (OQ-75-7): fields and value lists are ours (stage groups, intent, setting, line 1–10, board date ≤ 365 days ahead) | As D7; not clinically reviewed |
| H7 | Plan chip labels need a regimen per cycle sitting (new `regimen_key`) and short labels (AC, P, TC) in code | As D7 |
| H8 | Physiotherapy pain marker on the back (low back pain) is not drawn (front view only) | FRONT only |
| H9 | Card titles that differ from the library names are profile overrides in code, not library renames (a rename would change every department) | As D1 |
| H10 | Automated before/after checksum on fixture data needs a scratch database that `scripts/setup/create-database.mjs` does not create | Static and catalogue ITs + the read-only checksum script run by the human on `sahaj_dev` |
| H11 | OT booking reuses the patient's latest ACTIVE SURGERY course of the same doctor when not started from this record | As D3 |
| H12 | A missed chemo cycle chip is not drawn | State MISSED, red, hover "Missed" |
| H13 | Published departments get new starter sections as hidden rows in the configurator (union rule) | As D2 |
| H14 | Blank departments inherit the GM COMMON defaults: Vitals now required and Procedures shown | As D2 |
| H15 | Local anaesthetic, latex and NSAID allergy chips are written to the patient record but have no drug-class check (only Penicillin and Sulfa classes exist) | As D6; new classes are a clinical decision |
| H16 | Surgery Treatment badge counts 3 required (diagnosis, plan, consent) while the design draws "of 2" | Count from the layout (3) |
| H17 | "IOPA 36 (attached)": the tooth number is sample data and attachment is Later | Option "IOPA" without a tooth |
| H18 | Dental starter: dental chart and prescription stop being required (SAH-75-4 lists four required sections) | As the story |
| H19 | D12: a none word typed at the front desk ("nil", "NKDA") also counts as "none recorded", not only the doctor's None at sign | Counts (it is an explicit recording); the doctor-only alternative would use `allergy_recorded_at` |
| H20 | D12: ipd's medicine chip (`MedicineSafetyCheck.allergyStatus`) only knows "none known", so the text the sign writes, "No known allergy", shows there as a recorded allergy | Not changed in this follow-up (ipd contract untouched); tech lead to schedule ipd using `PatientClinical.allergyStatus` |
| H21 | D13: Menstrual history keeps LMP (required, needed for EDD) and Age at menarche, and Contraception keeps the generic Findings, Template and Attachments fields, none of which the Gynae design draws on these cards | Kept (library fields are never removed); the label "Method" over the tiles is ours (not drawn) |
| H22 | D13: published og departments get the new menstrual and contraception rows only at their next publish that changes a setting | As ADR-020 D6; no forced re-publish |
| H23 | D14: published onco departments keep ECOG before Side effects until the owner reorders or resets | As D2 (tenant rows never touched) |

Library check: none needed (CSS and SVG as before). **No new module, library or external service.**

---

## Data needed (for the db-engineer)

New migration files only; never edit a merged migration. Tenant tables: `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`, ENABLE + FORCE RLS, tenant-first indexes, `created_at/by`, `updated_at/by`. No FK across modules except `platform.tenant` and `patient.patient`. Clinical rows never deleted.

**opd, global (seed; SELECT only for `sahaj_app`; comment "value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052)")**
1. `emr_section_field` add `presentation text not null default 'DEFAULT'` check DEFAULT/CHIPS; `emr_section_field_option` add `is_alert boolean not null default false`.
2. `emr_section` + 7 rows (`local_exam`, `dental_medhx`, `dental_hx`, `dental_pain`, `dental_extraoral`, `dental_intraoral`, `dental_xray`; kind SPECIALTY; positions 101–107) with their fields and options (D5, D6; contents from `DoctorDental.dc.html:468-475` and SAH-75-6; `presentation` CHIPS; `is_none` for the "None" chips of `dental_medhx`; `is_alert` as drawn). Confirm no field key `specialty` (ADR-023 reserved key).
3. `emr_starter_department_section`: updates and inserts exactly as D2 table (positions renumbered per starter; `shown or not required` holds; `intraoral`, `extraoral` kept in dental with `shown = false`). Report in the design note which dental sections were required before.
4. `emr_starter_layout`: one new row per starter (15), `layout_version` = previous + 1, built from the rows above with copied definitions including `presentation`, `is_alert`; `emr_starter_department.current_layout_version` updated. Versions 1 untouched (immutable trigger).
5. `docs/database/SAH-75-seed-checksum.sql` (D2 proof 3) and the list of tables for the static allow-list test (D2 proof 1).

**opd, tenant**
6. `treatment_course` add `estimate_signed boolean not null default false` (column update grant).
7. `treatment_course_sitting` add `missed_at timestamptz` null, `missed_by_membership_id uuid` null, `removed_at timestamptz` null, `removed_by_membership_id uuid` null, `done_source text` null check MARK_DONE/SIGN, `done_by_membership_id uuid` null, `regimen_key text` null FK `opd.emr_chemo_protocol(protocol_key)`. Checks: missed pair both null or both set; removed pair both null or both set; `done_source is null or done_at is not null` (existing rows keep `done_source` null and are read as SIGN; no tenant row is backfilled); `removed_at is null or (done_at is null)`; `missed_at is null or (done_at is null and removed_at is null)`; `regimen_key` only for sittings of CHEMO courses (application rule; no cross-row check). Column update grants for the new columns. Index (`tenant_id`, `course_id`) where `removed_at is null` if the planner needs it.
8. `emr_cancer_journey` (append-only: select, insert grants; deny update, delete, truncate trigger): `id`, `patient_id` (FK `patient.patient` composite), `version int ≥ 1`, `stage_group text` null (check list D7), `tnm_text` ≤ 40, `markers_text` ≤ 80, `ki67_pct smallint` null 0–100, `genetics_text` ≤ 60, `intent` null CURATIVE/PALLIATIVE, `setting` null NEOADJUVANT/ADJUVANT/DEFINITIVE/ADVANCED, `line_no smallint` null 1–10, `board_date date` null, `source_record_id uuid` null (no FK; partitioned record), `recorded_by_membership_id`, `recorded_by_user_id`, `recorded_at` (database time). Unique (`tenant_id`, `patient_id`, `version`); index (`tenant_id`, `patient_id`, `version desc`). Comment "Clinical data, never log". Volume: a few rows per oncology patient. Retention: life of the clinic.

**billing, ipd, patient:** no table change. ipd extends the `CourseAdmissionSource` read with the bed label (code only).

**0.7.1 follow-up (D12–D14), opd global seed only; new migration files after `V2026_12_14_1030`; same allow-list (D2 proof 1), same tag comment; no tenant row, no patient table.**
9. `emr_section_field_option` add `hint text null`, check `hint is null or char_length(hint) between 1 and 60` (SELECT grant unchanged).
10. `menstrual` fields (UPDATE in place, keys kept; final positions 1–6): `lmp` 1 unchanged; `cycle` 2 → `field_type` SINGLE_CHOICE, `options_source` FIELD, `allow_free_text` false, `meta` '', `presentation` CHIPS; INSERT `cycle_length` 3 "Cycle length" SINGLE_CHOICE, required false, FIELD, no free text, meta '', CHIPS; `flow` 4 → SINGLE_CHOICE, meta '', CHIPS; `dysmenorrhoea` 5 → label "Pain", SINGLE_CHOICE, FIELD, no free text, meta '', CHIPS; `age_at_menarche` 6 unchanged. Options (is_none false, is_alert false, hint null): `cycle` regular 1 "Regular", irregular 2 "Irregular"; `cycle_length` under_21_d 1 "< 21 d", o21_27_d 2 "21–27 d", o28_30_d 3 "28–30 d", o31_35_d 4 "31–35 d", over_35_d 5 "> 35 d"; `flow` keeps scanty 1, normal 2, heavy 3 and adds with_clots 4 "With clots"; `dysmenorrhoea` none 1 "None", mild 2 "Mild", severe 3 "Severe". Library section description stays.
11. `contraception`: INSERT `method` 1 "Method" SINGLE_CHOICE, required false, FIELD, no free text, meta '', CHIPS; `findings`, `template`, `attachments` move to 2, 3, 4. Options (is_none false, is_alert false): none 1 "None" hint "Not using"; condoms 2 "Condoms" "Barrier"; ocp 3 "OCP" "Daily pill"; cu_t 4 "Cu-T" "IUCD, 5–10 years"; injectable 5 "Injectable" "Every 3 months"; sterilisation 6 "Sterilisation" "Permanent".
12. `emr_starter_department_section` onco: `onc_tox` 17, `onc_bsa` 18, `onc_labs` 19, `onc_ecog` 20 (temporary-position pattern of `V2026_12_14_1010`); nothing else moves.
13. `emr_starter_layout`: version 3 for **og and onco only** (same builder, option JSON now carries `hint`); `current_layout_version` 3 for them; the other 13 stay at 2. Checks at the end: og v3 menstrual fields in the order above; onco v3 order 17–20 as item 12; 15 + 15 + 2 layout rows.
14. Report the library counts after the migration (sections unchanged at 107; fields + 2; options + 17), using temporary positions if field positions are unique per section and update the checksum note if needed. Integration tests to list: option `hint` length check; og/onco current version 3; a copy-forward from a signed record with old free text in `menstrual.cycle` leaves it out and the draft save passes (java-developer rule, D13).

Integration tests the design note should list: static DML allow-list (D2.1); catalogue (D2.2); starter v2 flags per the D2 table; a draft-only and a published department read after the migration show the appended sections (D2 set rule) and save; sitting state checks (missed and removed pairs, missed only without done); journey append-only (update and delete refused) and cross-tenant invisibility; `regimen_key` FK.
