# ADR-016 · IPD patient scope (nurse wards, visiting doctor's own patients, "My patients") and break-glass emergency access: OPTIONS for the human

Story: SAH-34 (break-glass emergency access) together with the open questions OQ-33-2 and OQ-30-3 (ward scoping), OQ-31-8 and OQ-33-1(b) (visiting doctor)
Mockup screens: IPD → Admitted (`ipList` toolbar: "My patients" `pctog data-k="mine"`, the scope notice / `pcscope` chip "As <role> you see …", the button "Emergency access" `sfbg`); IPD → Safety (tile "Your patients · In your wards", tile "Emergency access · Waiting for your review", panel "Emergency access to review", `bgRow`, "Mark reviewed" `sfbgrev`, `bg` entries of the Clinical audit trail); card flag "Emergency access" (`sfFlags`, `sfBgOn`); sheet "Emergency access" (`sfbg`, `sfbgok`); Clinic setup → Roles and access, panel "Which patients each role sees" (`cfgIpAcl`, `pcScopeTxt`, `pcDefault().acl.scope`, `pcVisiblePts`, `PC_NURSE`)
Module: ipd (owner of every new table), reading `platform.api` (membership, names) and writing `patient.api.PatientAccessPort` (record-access log). No new module, port to a new module, service, library or external call in any recommended option.
Status: **OPTIONS ONLY (9 Nov 2026). Nothing in this file is decided.** The human chooses one option per decision (section 10) before any story, contract, migration or code. Human instruction (AskUserQuestion, 9 Nov 2026): design-only step, ADR with options, the human chooses.
Deciders: Ganesh (human). Written by solution-architect.
Related: DEC-015 (OQ-33-2 still open, SAH-34 split out), DEC-021 (no record-access row for the list; go-live condition "revisit when ward scoping arrives"), DEC-022 (nurse sees every admitted patient; no Emergency access tile), `docs/decisions/IPD-open-questions.md` (OQ-30-3, OQ-26-6, OQ-31-8), `docs/stories/SAH-33.md` (AC 4, AC 16, OQ-33-2), `docs/stories/SAH-36.md` (AC 13, out of scope), `docs/stories/SAH-37.md` (Roles and access out of scope), ADR-013 (D3 membership re-read, D5 list-level abilities "rows gain an optional override when ward scoping arrives", D10 record access), ADR-015 (card setup draft/publish, `CardAccessRule`), SAH-17 (`patient.record_access`), ADR-007 (Safety board, D12 record access), ADR-009 (D7 pharmacist list rows).

---

## 1. Context: what exists today

1. **No patient scope at all.** Every IPD read and write checks role (token and membership re-read) and tenant (RLS). Inside a clinic, OWNER, DOCTOR and NURSE see every admitted patient; FRONT_DESK sees every patient without clinical data (ADR-013 D3/D15). The Safety tile "Your patients" reads "All admitted patients" because "In your wards" would be false (OQ-33-2).
2. **VISITING_DOCTOR has no IPD access.** The role exists in `platform.membership` but every IPD endpoint answers 403 for it. So "own-patient scoping for visiting doctors" is a **widening** (from nothing to own patients), not a narrowing. This matters for the security review.
3. **Data that already exists:** `ipd.admission.doctor_membership_id` (admitting doctor, not null, SAH-23); `ipd.ward`, `ipd.bed` (bed → ward); `ipd.clinical_event` with kind `bg` already allowed by its check constraint (V2026_11_23); `patient.record_access` written through `patient.api.PatientAccessPort.recordOpened` (ids only); `hra.roster_shift` (membership, date, shift M/E/N/X, **no ward**, HRA is an add-on that may be off).
4. **Data that does not exist:** any link between a nurse and a ward; an assigned nurse per admission (the mockup card field "Assigned nurse" is not built, DEC-021); any per-clinic scope setting; any access grant.
5. Every IPD list is built **set-based in one transaction** with a membership re-read first (ADR-013 D2/D3, ADR-012, ADR-007). A scope filter can be added to those set reads as one more predicate, so no list needs per-patient calls.

## 2. Mockup coverage (shown / not shown)

Rule: what is shown is built as shown; what is not shown is a question (Q-n in section 10), never invented.

| Part | What the mockup shows | What the mockup does NOT show (question) |
|---|---|---|
| Scope per role | Clinic setup → Roles and access, panel "Which patients each role sees", subtitle "Applies to the patient list, bed board details and search.", per role a choice of **All patients / In their wards / Assigned to them**; "Owner / Admin · All patients · fixed". Defaults: Doctor all, Visiting doctor **mine**, Nurse **ward**, Front desk all, Billing all. Under the same draft/publish bar as Patient card (`pcBar('acl')`). Tip: "Visiting doctors usually see only their own patients." "Hiding a button is not the protection: the server checks these permissions on every request." | PHARMACIST and COUNSELLOR are not in the role list (Q4). Whether a scope change waits for Publish or applies at once (Q5). "Search" (Patients module search is OPD-wide; is it scoped?) (Q6). Emergency queue and Pharmacist check panel are not mentioned (Q6). |
| Nurse to ward link | Only demo data: `PC_NURSE` = one nurse per ward (`wm`, `wf`, `wp`, `wi`), one nurse may hold two wards. "In their wards" for a nurse = patients whose bed is in a ward mapped to her. | **No screen to assign nurses to wards anywhere** (not in Wards/rooms/beds, not in Team and access, not in Staff and leave) (Q1, blocker). Several nurses per ward, shifts, cover, float nurses (Q2). |
| "In their wards" for a non-nurse | The mockup returns **all** patients for any role except nurse set to "ward" (`role==='nurse' ? … : true`). | Whether a doctor or front desk set to "In their wards" should be scoped, and to which wards (Q3). |
| "Assigned to them" | Nurse: `a.nurse === me` (per-admission assigned nurse, defaulted from the ward's nurse when the patient is seeded). Doctor and visiting doctor: `a.doc === 'd1'` (the admitting/consultant doctor; hard-coded demo doctor). | How an admission gets or changes its assigned nurse (no field in the Admit sheet, Transfer only says the handover "moves to <nurse>'s colleague in the new ward") (Q7). Co-consultants, referrals, a covering doctor (Q8). |
| "My patients" toggle | Toolbar button for every role except Front desk (`aria-pressed`). Filters the visible list: nurse → assigned nurse; everyone else → admitting doctor. A filter, not a security scope. | What it means for a nurse when there is no assigned nurse; what it means for Billing; whether it is remembered (Q9). |
| Scope notice | When the role's scope is not "all": "As <role> you see **<scope>** (N of M). The owner sets this in Clinic setup → Roles and access." Later folded into a toolbar chip (`pcscope`) with the Emergency access button. "N of M" reveals the clinic's total admitted count. | — (shown fully). |
| Safety board scoping | Escalations, doses, tab count and the nurse's audit trail are filtered to the patients the user can see (`sfScope`). Nurse tile "Your patients · N · In your wards". | Whether a DOCTOR's Safety board is scoped (default "all", so no) — fine. Visiting doctor's Safety tab: the mockup shows the tab to owner and nurse only (Q10). |
| Break-glass entry | Button "Emergency access" (key icon) in the scope notice, so **only for a user whose scope is not "all"**. | Entry from anywhere else (search result, bed board, a 403 page) (Q11). |
| Break-glass sheet | Title "Emergency access"; subtitle "For a patient outside your wards. Access lasts 30 minutes, is logged, and the owner reviews it."; select **Patient** listing every admitted patient outside the user's scope as "<name> · bed <code> · <MRN>"; select **Reason**: "Patient deteriorating, rapid response", "Covering for a colleague on break", "Asked to help by the duty doctor" (placeholder "Choose a reason"); checkbox "I understand this access is recorded with my name and reviewed by the owner"; buttons **Cancel**, **Open for 30 minutes**. Empty: "You can already see every admitted patient." Errors: "Choose a reason for emergency access.", "Tick the box to confirm this access is recorded." | Free-text reason or "Other" (Q12). Ending early, extending, a second grant for the same patient, a cap on grants (Q13). Patients not admitted (Emergency queue, discharged) (Q14). |
| Effect of a grant | The patient joins the user's visible list for 30 minutes (`pcVisiblePts` adds it) with the grey card flag **"Emergency access"** (key icon); the user's role permissions are unchanged. List search is cleared. | What the countdown looks like; what happens to an open sheet at minute 30 (Q15). |
| Alert to owner | Toast "Emergency access to <name> for 30 minutes. **The owner has been told.**" | No notification exists in phase 1. The sentence would be false (Q16; same pattern as OQ-33-3). |
| Owner review | Safety board (owner only): tile **"Emergency access · N · Waiting for your review"** (amber when N > 0); panel **"Emergency access to review"** (key icon), subtitle "Staff opened patients outside their wards. Check each reason.", one row per grant "<staff name> opened <patient name> · bed <code>", small line "<reason> · <time> · outside their wards", button **Mark reviewed**; toast "Marked as reviewed"; panel hidden when nothing is waiting. | A reviewed list or history, a comment, "this was not OK" / follow-up action, a filter or date, review of grants of patients already discharged (the mockup only lists admitted patients, so a discharge would silently drop the grant from review) (Q17). Who reviews when the owner is absent (Q18). |
| Logging | Clinical audit trail entries, kind `bg` (key icon): "Emergency access opened · <reason>" (by the grantee) and "Reviewed emergency access by <staff name>" (by the owner). Roles and access notice: "every view of a patient record goes into the access log". | Record-access log rows for a grant (Q19). The review text holds a staff name, which conflicts with the rule "names read at display time, never stored in the entry" (SAH-33 AC 17) (Q20, wording). |
| Card flag "Assigned nurse" | Card field "Assigned nurse" (`nurse`, identity group) in section Care. | Not built (DEC-021); only possible with option A3 (section 3). |

## 3. Decision A: how a nurse is linked to the patients she may see

The common part of every option: the server builds a **`PatientScope`** once per request, right after the membership re-read (ADR-013 D3), from (role, membership, clinic scope setting, assignments, active grants). It is one of `ALL`, `WARDS(set of ward ids)`, `OWN(admitting doctor membership id)`, or `NONE`, plus a set of admission ids from active emergency grants. Every IPD read and write applies it (section 6). The browser never decides scope.

### A1 · Nurse-to-ward assignment table (standing assignment)

- **Data shape:** `ipd.ward_assignment (id, tenant_id, ward_id, membership_id, assigned_by, assigned_at, removed_by, removed_at, version)`; at most one live row per (tenant, ward, membership) (partial unique index `where removed_at is null`); tenant FK composite to `ipd.ward`; `membership_id` without cross-module FK (rule: no FK across modules), validated through `platform.api.TeamMemberPort` on write and ignored on read when the membership is inactive. FORCE RLS on `tenant_id`. History kept (rows are ended, not deleted). Many-to-many: a ward may have several nurses, a nurse several wards (the mockup's one-nurse-per-ward demo data is a special case).
- **Who edits:** OWNER only (Roles and access is owner-only in the mockup). Every change audited (`IPD_WARD_ASSIGNMENT_CHANGED`, ids only). **Where** is not in the mockup (Q1): candidates (a) Clinic setup → Wards, rooms and beds, a "Nurses" multi-select per ward; (b) Clinic setup → Team and access, a "Wards" field on a nurse; (c) Roles and access, under "Which patients each role sees".
- **Effect:** NURSE with scope "ward" sees admissions whose current bed is in her wards. A transfer to another ward removes the patient from her view at once (by design; Q2 for the handover gap).
- **Existing users:** see section 3.4.
- **Cost at 100 hospitals:** about 100 × 60 nurses × 1 to 3 wards ≈ 12,000 live rows platform-wide; one index probe `(tenant_id, membership_id) where removed_at is null` per request; the list filter is a `ward_id = any(:wards)` predicate on the existing R1 join bed → ward (no extra query). Owner effort: set once, change when staff move.

### A2 · Shift-based assignment (who is on duty in which ward, by date and shift)

- **Data shape:** either (a) a new `ipd.ward_shift_assignment (tenant_id, work_date, shift, ward_id, membership_id, …)`, or (b) a `ward_id` added to `hra.roster_shift` read through a new `hra.api` port. (b) makes IPD depend on HRA, an add-on that may be off, and adds a cross-module dependency; (a) duplicates the roster. Both need shift times in IST and a rule for overlaps and night shifts that cross midnight.
- **Who edits:** OWNER (or a nurse in charge, a role that does not exist) every week or day. The mockup's Staff and leave roster has **no ward** column and no link to IPD.
- **Effect:** a nurse sees her ward only during her shift. Off-shift (handover running late, charting after the shift) she sees nothing and must use break-glass: **many false break-glass uses**, which bury the real ones in the owner's review.
- **Existing users:** every nurse needs a roster entry every day or sees nothing; "sees all wards until assigned" would be true most of the time, which defeats the scope.
- **Cost:** about 100 × 60 × 30 ≈ 180,000 rows per month; one probe per request with a time predicate on the database clock; heavy owner workload every week; not in the mockup at all.

### A3 · Per-patient assigned nurse

- **Data shape:** `ipd.admission_nurse (id, tenant_id, admission_id, membership_id, from_at, to_at, assigned_by)` (history), or a column `assigned_nurse_membership_id` on `ipd.admission` plus history in the clinical event log.
- **Who edits:** not in the mockup (Q7): the Admit sheet has no nurse field; candidates are a select on the admission sheet, a bulk "assign" at shift change, or defaulting from A1 ("the ward's nurse", as the mockup seeds `a.nurse`).
- **Effect:** matches the mockup's "Assigned to them" and the nurse's "My patients", and the card field "Assigned nurse". Every shift change, transfer and absence needs a reassignment; a patient with nobody assigned is invisible to every nurse (patient-safety risk) unless a fallback applies.
- **Existing users:** every admitted patient needs a nurse at switch-on.
- **Cost:** about 100 × 300 = 30,000 open rows, high churn (each transfer and shift); one extra join per list read; heavy staff workload.

### A4 · Hybrid: A1 for scope, A3 only as an optional display label later

A1 decides who may see a patient. The "Assigned nurse" card field and the nurse's "My patients" come later from A3 if the human wants them, without changing security.

### 3.4 Switching on for existing clinics (applies to A1 to A4)

| Option | How it works | Risk |
|---|---|---|
| M1 · "Not assigned = all wards" per user (the default named in the brief) | A nurse with no live assignment sees every ward; once assigned she sees her wards only. | **Fails open**: ending a nurse's last assignment (by mistake, or when a ward is deleted) widens her access to the whole hospital. A new nurse sees everything until the owner remembers to assign her. |
| M2 · Per-clinic switch "Nurses see only their wards" (off by default) | Off: every nurse sees all (today's behaviour, no change at deploy). On: the owner turns it on after assigning; a nurse with no assignment sees **no** patient, a notice says so, and she can use Emergency access. Switching on is refused (400) while a NURSE member has no assignment, or allowed with a warning (Q21). | One more setting; the owner must act to get scoping. Fails closed once on. |
| M3 · Scope from the Roles and access setting only | The clinic's published scope for NURSE ("all" until the owner publishes "In their wards"); same fail-closed rule as M2 once "ward" is published. | Same as M2, but the switch is the mockup's own control (needs decision C2 or C3). |

## 4. Decision B: own-patient scope for doctors and visiting doctors

### B1 · Admitting doctor only (`ipd.admission.doctor_membership_id`)
- **Data:** none new. Already set on every admission (SAH-23).
- **Who edits:** whoever admits (today OWNER, FRONT_DESK). Changing the admitting doctor after admission is not in the mockup (Q8).
- **Effect:** VISITING_DOCTOR (and DOCTOR if the clinic sets "Assigned to them") sees admissions where `doctor_membership_id` = own membership. Same predicate on every set read.
- **Existing users:** VISITING_DOCTOR gains access to own patients only (a widening from 403; needs the role decisions OQ-31-8 and OQ-33-1(b): which sheets and actions a visiting doctor may use).
- **Cost:** zero new rows; predicate on an existing column; an index `(tenant_id, doctor_membership_id) where status = 'ADMITTED'` only if plans show a need (unlikely at ≤ 300 rows).

### B2 · Care team table (admitting doctor plus added doctors)
- **Data:** `ipd.admission_doctor (tenant_id, admission_id, membership_id, added_by, added_at, removed_at)`.
- **Who edits:** not in the mockup (Q8). The admitting doctor or OWNER adding a referred consultant.
- **Effect:** covers co-management and referrals; more screens and rules.
- **Cost:** few rows; one extra join.

### B3 · B1 plus "treated by" (any doctor who ordered, acknowledged or responded for the admission)
- **Data:** derived from `ipd.medicine_order.ordered_by`, `ipd.ews_escalation.acknowledged_by` (user ids, not membership ids: a mapping is needed).
- **Effect:** scope grows by acting, which turns a single break-glass action into permanent access. **Not suitable** as a security scope; listed for completeness.

"My patients" (a filter, not a scope) under every option: the server marks each row `mine: true|false` (admitting doctor = caller for OWNER, DOCTOR, VISITING_DOCTOR; for NURSE only with A3, else the button is hidden or means "my wards", Q9). No membership id is sent to the browser for this.

## 5. Decision C: is the scope per role configurable (Roles and access) or fixed?

| Option | Shape | Notes |
|---|---|---|
| C1 · Fixed in code | NURSE = ward (with the switch M2), VISITING_DOCTOR = own (B1), all others = all, OWNER all. | Smallest. The mockup's Roles and access panel is not built (deviation the human must accept). |
| C2 · In the card setup document (ADR-015 jsonb, draft and publish) | `acl.scope` inside `ipd.card_setup_version.setup`, as the mockup keeps it in the same draft. | Follows the mockup exactly, including draft/publish. But ADR-015 D3 **repairs** a stored setup on read (drops unknown keys, fills defaults): a security policy must never be "repaired" into a wider value. Needs a separate, non-repairing, fail-closed reader for the scope part. |
| C3 · Own small versioned table `ipd.patient_scope_setting` (one row per role per published version, check constraint on the three values, OWNER fixed `all`) | Same Roles and access UI and the same publish bar in the browser; stored and validated separately. | Clear checks in the database; no jsonb repair in a security path; one more table. |

## 6. Effect on every IPD endpoint (for any option)

Rule: **lists filter, details and writes refuse.** Scope = `PatientScope` ∪ active grants of the caller.

| Endpoint (docs/api/ipd.yaml) | Effect |
|---|---|
| `GET /admissions` (Admitted list, cards and table) | Rows filtered in R1. `wards` filter options: all wards (Q22: only the nurse's wards?). Scope notice data: scope kind, visible count, total admitted count ("N of M", as the mockup). `mine` per row. Card flag `emergencyAccess` (grant expiry) only for the grantee. Abilities stay list-level (ADR-013 D5) because every listed row is in scope. `safety` counts computed on the scoped set. |
| `GET /admissions/{id}`, `/nursing`, `/emr`, `/mar`, `/discharge` (read), `/deposits`, `/charges` (read) | Out of scope → refused (Decision E). In scope or granted → as today, same record-access rows. |
| Writes: `POST /vitals`, `/medicine-orders`, `/doses`, `/transfer`, `/discharge`, `/discharge/signoff`, `/deposits`, `/charges`, `POST /medicine-orders/{id}/stop` | Same check inside the transaction, after the admission row lock (ADR-006 D4), so a transfer or expiry racing with the write is decided on locked data. |
| `GET /safety`, `/safety/summary` (escalations, Medicine round, tiles, tab count, banner) | Every panel and count on the scoped set; the invariants of ADR-012 (doses) and SAH-33 AC 4 (tab = tile = rows) hold **per caller**. Nurse tile "Your patients · In your wards" becomes true. Owner tile and panel "Emergency access" added. |
| `POST /escalations/{id}/acknowledge`, `/respond`, `/call-senior` | Refused out of scope. Patient-safety question: may a nurse call the senior doctor for a patient she cannot see? With scoping she cannot see the row, so no (Q23). |
| `GET /clinical-events` | NURSE: own entries plus entries of admissions in scope (SAH-33 AC 16, now true). VISITING_DOCTOR: own entries plus own patients (if given the tab, Q10). OWNER, DOCTOR: all (unchanged). |
| `GET /pharmacist-checks`, `/summary`, `POST /medicine-orders/{id}/verify`, `/reject` | PHARMACIST is not in the mockup's scope list (Q4). Proposal space: pharmacist = all. |
| `GET /board` (bed board) | Mockup subtitle "Applies to the patient list, **bed board details** and search": names and details of out-of-scope occupied beds hidden, bed status kept (Q6). |
| `GET /emergency`, cases | Emergency queue patients are not admitted and have no ward; the mockup does not scope it (Q6). Unchanged unless the human says otherwise. |
| `patient` module (Patients search, patient file) | Not IPD. The mockup says "search"; scoping OPD patient search would change SAH-8 for every clinic (Q6). Recommended unchanged. |
| Config endpoints (`/wards`, `/card-setup`, settings) | Unchanged. |

### Decision E: answer for an out-of-scope admission
- **E1 · 404 `not-found`** (as another clinic's id): leaks nothing, but a nurse following a stale screen after a transfer gets "not found" for a patient she just saw.
- **E2 · 403 with a new problem type `outside-scope`**, no patient data in the body, so the browser can say "This patient is not in your wards." and offer Emergency access (text not in the mockup, Q11). Existence of an admission id inside the same clinic is already visible to that user through the break-glass picker.

## 7. Decision D: break-glass emergency access (SAH-34)

### D-1 · Who may use it
- **D-1a (mockup):** any user whose scope is not "all" (NURSE when ward-scoped, VISITING_DOCTOR, any role the clinic narrows). OWNER never needs it.
- **D-1b:** clinical roles only (NURSE, DOCTOR, VISITING_DOCTOR); a narrowed FRONT_DESK or BILLING may not.
- The grant never adds permissions: a nurse with a grant can do what a nurse can do, on that one admission.

### D-2 · Data shape (every option)
`ipd.emergency_access_grant (id, tenant_id, admission_id, user_id, membership_id, role, reason_key, opened_at, expires_at, reviewed_by, reviewed_at, version)`.
- Tenant composite FK to `ipd.admission`; FORCE RLS; no patient name, note or free text (reason is a key from a fixed list).
- `opened_at` and `expires_at` stamped by the database clock (`clock_timestamp()`, `+ interval '30 minutes'`), never from the browser.
- Append-only except the review columns, which a guard trigger lets be set **once**, never by the grantee, never cleared. No delete, no truncate.
- One row per grant: a second user's grant on the same patient never overwrites the first (the mockup's single `a.bg` would lose the first grant from review).
- Index `(tenant_id, user_id, expires_at desc)` for "my active grants" on every request; partial index `(tenant_id, opened_at) where reviewed_at is null` for the owner's panel.

### D-3 · Reason capture
- **D-3a (mockup):** select of the three reasons, required; confirmation checkbox required (server receives `confirmed: true`, refuses otherwise).
- **D-3b:** the three reasons plus "Other" with free text (up to 200 characters, IPD text rules). Free text can carry clinical or personal data into an audit table; not shown in the mockup (Q12).

### D-4 · Duration
- **D-4a (mockup):** fixed 30 minutes from the database clock; no extension; a new grant (new reason, new review row) after expiry. Optional "End now" is not in the mockup (Q13).
- **D-4b:** clinic-configurable 15 to 60 minutes (not in the mockup).
- Expiry is checked on **every** request against the database clock, so an open sheet's next save after minute 30 is refused (E rule) and the list drops the patient on the next 60-second reload (Q15: notice text).
- Ends early when: the admission is discharged; the grantee's membership becomes inactive or its role changes (grant bound to `membership_id` and `role`).

### D-5 · Alert to the owner (phase 1 has no notifications)
- **D-5a:** in-app only: the owner's Safety tab count and the tile/panel; the toast says, proposed, **"Emergency access to <name> for 30 minutes. The owner will see this on the Safety board."** (deviation from "The owner has been told.", Q16).
- **D-5b:** add the count to the owner's Today or header badge (not in the mockup).
- A WhatsApp/SMS alert needs the notifications service: **not in phase 1**, not proposed.

### D-6 · Owner review (only what the mockup shows)
- Safety board, OWNER only: tile "Emergency access · N · Waiting for your review" (N = unreviewed grants), panel "Emergency access to review" with the rows and "Mark reviewed" exactly as the mockup.
- **Deviation needed for safety (Q17):** unreviewed grants stay in the panel **after the patient is discharged and after expiry** (the mockup lists admitted patients only, so a quick grant followed by a discharge would never be reviewed). Row text for a discharged patient: bed shown as the bed at the time of the grant (`a.bg.bed` in the mockup).
- `POST /emergency-access/{id}/review` with `{version}`: OWNER only (token and membership), never the grantee, idempotent refusal 409 `already-reviewed`; allowed in IPD Read-only (it records oversight).
- No comment, no "not OK" outcome, no history list: not in the mockup (Q17).

### D-7 · What is written, and where
| Event | `ipd.clinical_event` (kind `bg`, fixed template, no names) | `patient.record_access` (via `PatientAccessPort`) | `platform` audit event |
|---|---|---|---|
| Grant opened | "Emergency access opened · <reason text from the fixed list>" (user = grantee, admission set) | **Option R1:** one row at grant time (it is an opening of the record outside normal access). **Option R2:** none at grant; only the usual rows when a sheet is opened. | `IPD_EMERGENCY_ACCESS_OPENED` (grant id, admission id, reason key, expiry) |
| Sheets opened during the grant | unchanged (EMR "view" event as today) | unchanged rows (admission sheet, EMR, nursing, MAR, as today) | — |
| Reviewed | "Reviewed emergency access" (user = owner); the grantee is shown at display time from the grant row, not stored in the text (Q20: the mockup's text "… by <staff name>" stores a name) | none | `IPD_EMERGENCY_ACCESS_REVIEWED` |
| Refused (out of scope, expired) | Option: `deny` "Access refused: outside scope" per refusal, or nothing (a stale tab can produce many) (Q24) | none | none |

Application logs: ids, `tenantId`, `requestId`, reason key only. Never names, UHID, bed or reason text.

### D-8 · Abuse cases and the control for each
| Abuse | Control in the options |
|---|---|
| A nurse opens many patients "just in case" | Each grant is one patient, one reason, one review row; optional cap of active grants per user (for example 3) answering 409 `grant-limit` (not in the mockup, Q13). The owner's tile shows the volume. |
| Grant then discharge to escape review | Unreviewed grants stay until reviewed (D-6 deviation). |
| Grantee reviews own grant | Review is OWNER only and never the grantee (server check plus trigger). |
| Extending access by re-granting forever | Each re-grant is a new reviewed row; the count is visible. |
| Using an old token after a role change or deactivation | Membership re-read per request; grant bound to membership and role. |
| Clock tricks | Database clock only. |
| Reading the picker as a patient directory | The picker lists every out-of-scope admitted patient with name, bed and UHID before any reason is given (mockup). **Option P1 (mockup):** keep, data are identity only, no clinical data. **Option P2:** type the bed code or UHID exactly, no list (deviation, Q11). |
| Break-glass used to see the OPD file or other modules | The grant is IPD-only (one admission); the Patients module is not scoped anyway (Q6). |
| Free text with patient data in the reason | Fixed reasons only (D-3a). |
| Owner never reviews | No technical control in phase 1 (no notifications); the tile stays amber. Go-live condition for the human. |
| Owner is the abuser | Not covered in phase 1 (only the owner reviews; no second reviewer, Q18). The append-only grant and audit rows stay for an inspector. |

## 8. Security, privacy (DPDP) and failure modes

- **Enforcement layer.** Option S1 (recommended family): one `PatientScope` builder in `ipd.application` and one guard used by every service, tested per endpoint; tenant isolation stays in RLS. Option S2: also enforce scope in PostgreSQL with RLS policies reading `app.user_id`, `app.scope` settings (defence in depth). S2 touches policies on about 15 ipd tables, and the scope depends on another module's data (membership), so the policy would need a copy of it; high cost and new failure modes. Not recommended for phase 1; listed for the human.
- **Fail closed.** (1) Scope cannot be built (assignment or grant read fails, membership missing): the request fails (500/403), never falls back to "all". (2) Unknown or corrupt scope setting (C2/C3): treated as the most narrow defined default for that role, logged as WARN with ids, never widened. (3) With M2/M3 on, a nurse with no live ward sees an empty list with a notice and can use break-glass. (4) Expired grant: refused on the next request. (5) A transfer out of the nurse's ward removes access at once (patient safety handled by the receiving ward and by break-glass; Q2).
- **Patient-safety tension (human).** Scoping can hide a deteriorating patient from a nurse covering another ward. Mitigations in the options: break-glass; DOCTOR default "all"; the Safety board for OWNER and DOCTOR unscoped. The clinical sign-off owner (OQ-30-6) should see this ADR.
- **DPDP.** Scoping is purpose limitation and data minimisation: a nurse processes only the patients she cares for. It **narrows** the DEC-021 exception (no record-access row for the ward list): the go-live condition "revisit when ward scoping arrives" can then be answered with "the list shows only the user's own wards". Break-glass is the documented exception, with reason, time limit, owner review and logs. The privacy register needs entries for: scope rules per role, break-glass purpose and review, retention of grants. "N of M" in the scope notice reveals the hospital's census to every scoped user (low sensitivity, as the mockup).
- **Audit trail.** Assignment and scope changes: `platform` config audit (ids, before/after ward ids or scope values). Grants and reviews: section D-7. Clinical event texts stay fixed templates without names.
- **Retention.** Assignments, scope versions and grants are never deleted in phase 1 (same as clinical events, ADR-006 Q10). Grants are part of the in-patient access trail.
- **Phase 1 held.** No notifications, no gateway, no documents, no analytics, no new library. A2(b) would add an ipd → hra dependency (not recommended).

## 9. Effect on the finished stories SAH-28 to SAH-40, and a proposed split

### 9.1 What changes in finished work
| Story | Changes under the recommended path |
|---|---|
| SAH-28 Emergency queue | None, unless the human scopes the queue (Q6). |
| SAH-29 Packages | None. |
| SAH-30 Nursing, vitals, EWS | `/nursing` read and `POST /vitals` gain the scope check (E). VISITING_DOCTOR vitals view if granted the role. |
| SAH-31 Medicine orders, pharmacist check | `/emr`, `/medicine-orders`, `/stop` scoped; visiting doctor ordering only after OQ-31-8; pharmacist list unscoped (Q4). |
| SAH-32 MAR | `/mar`, `/doses` scoped (inside the lock). |
| SAH-33 Safety board | Board, summary, banner, counts, escalation actions and the nurse's audit trail scoped; tile wording "In your wards" restored; owner Emergency access tile and panel added; `deny`/`bg` entries. |
| SAH-34 | This work (not built). |
| SAH-35 Medicine round | Round and dose counts on the scoped set; invariant per caller; tests per scope. |
| SAH-36 Admitted cards | R1 filter, `mine`, scope notice/chip, "My patients" toggle, "Emergency access" button and card flag; ADR-013 D5 unchanged (list-level abilities stay valid). |
| SAH-37 Card setup | Only with C2 (scope inside the setup document, non-repairing reader). With C1/C3 no change except the "View as" preview showing the scope note. |
| SAH-38 PIN hardening | None (the second-nurse check needs no read of the patient; a witness from another ward still works). Confirm with the human that the witness need not be in scope (Q25). |
| SAH-39 Home background | None. |
| SAH-40 Refused refresh clears clinical data | Extend the same rule to the new 403 `outside-scope` / 404 so a patient leaving scope (transfer, expiry) clears from screens; tests. |
| Earlier (SAH-23, 24, 26, 27) | Admission sheet, deposits, charges, bed board details, transfer and discharge reads and writes get the same check (FRONT_DESK stays "all" by default). |

### 9.2 Proposed story split (sizes S ≤ 2 days, M 3 to 5, L 6 to 10 of one developer pair)
1. **SAH-34A · Nurse ward assignment** (A1 + M2 switch): table, owner screen (placement per Q1), audit. **M.** Depends on: Q1, Q2, Q21. Blocker: no mockup screen.
2. **SAH-34B · Patient scope enforcement** (`PatientScope`, every endpoint of section 6, counts and invariants, E answer, fail-closed tests, SAH-40 extension). **L.** Depends on 34A (nurse) and B1.
3. **SAH-34C · Admitted list scope UI** (scope notice/chip "N of M", "My patients", ward options). **S.** Depends on 34B.
4. **SAH-34D · Visiting doctor in IPD** (role access per sheet and action, B1 own scope, Safety tab or not). **M.** Depends on 34B and the role answers OQ-31-8, OQ-33-1(b), Q10.
5. **SAH-34E · Break-glass grant** (table, sheet, picker, 30-minute grant, card flag, logs, refusal handling). **M.** Depends on 34B.
6. **SAH-34F · Owner review** (tile, panel, Mark reviewed, discharged-patient rule). **S.** Depends on 34E.
7. **SAH-34G · Roles and access "Which patients each role sees"** (C3 or C2). **M.** Optional; after 34B; replaces the M2 switch by the mockup control (M3).
Total about 5 to 7 weeks of one pair; 34A and 34D design can run in parallel; nothing can start before the human's answers.

## 10. Questions for the human and the recommendations

**Each "Recommended" below is the architect's recommendation for the human, not a decision.** Nothing is built on it until the human chooses.

### The decisions (pick one each)
| # | Decision | Options | Recommended (for the human) | Reasons |
|---|---|---|---|---|
| 1 | Nurse link (A) | A1 ward table · A2 shift · A3 per-patient nurse · A4 A1 now, A3 label later | **A1 (as A4)**, many nurses per ward | Matches the mockup's "In their wards"; set once; no daily work; no HRA dependency; ~12k rows and one index probe; A2 causes routine break-glass; A3 risks an unassigned patient being invisible to every nurse. |
| 2 | Switch-on (M) | M1 unassigned = all · M2 clinic switch · M3 via Roles and access | **M2 now, M3 when 34G is built** | M1 fails open; M2 changes nothing at deploy and fails closed once the owner turns it on. |
| 3 | Doctor own scope (B) | B1 admitting doctor · B2 care team · B3 treated-by | **B1** | Data exists; zero cost; B2 needs a screen not in the mockup; B3 turns one action into permanent access. |
| 4 | Scope configurable (C) | C1 fixed · C2 in card setup jsonb · C3 own table | **C1 first, C3 later (story 34G)** | Smallest safe step; C2 would put a security rule under ADR-015's repair-on-read. C1 is a mockup deviation (panel not built) the human must accept. |
| 5 | Out-of-scope answer (E) | E1 404 · E2 403 `outside-scope` | **E2** | Clear message after a transfer; lets the browser offer Emergency access; no patient data in the body. |
| 6 | Break-glass users (D-1) | D-1a anyone narrowed · D-1b clinical roles only | **D-1a** (as the mockup) | Same rule as the mockup; in phase 1 only NURSE and VISITING_DOCTOR are narrowed by default anyway. |
| 7 | Reason (D-3) | D-3a three fixed reasons · D-3b plus "Other" text | **D-3a** | No free text in an audit table; as the mockup. |
| 8 | Duration (D-4) | D-4a fixed 30 min · D-4b configurable | **D-4a**, no extension, no "End now" | As the mockup; simplest to audit. |
| 9 | Owner alert (D-5) | D-5a in-app with new toast · D-5b plus a header/Today badge | **D-5a** | Phase 1 has no notifications; never claim an alert that was not sent (DEC-015 rule). |
| 10 | Review list (D-6) | as mockup (admitted only) · keep until reviewed | **Keep until reviewed** (deviation) | Otherwise discharge hides a grant from review. |
| 11 | Record-access row at grant (D-7) | R1 one row · R2 none | **R1** | The grant is an opening outside normal access; one row per grant is cheap and answers an inspector. |
| 12 | Picker (D-8) | P1 list of out-of-scope patients · P2 type bed/UHID | **P1** (as the mockup) | Speed in an emergency; identity data only. |
| 13 | Enforcement (section 8) | S1 app guard · S2 app guard plus RLS | **S1** | One tested guard; S2 costs policies on ~15 tables and a copy of membership data. |

### Other questions (from section 2 and later)
- Q1 (blocker) Where does the owner assign nurses to wards? No screen in the mockup.
- Q2 Several nurses per ward, floats, and the handover gap after a transfer (OQ-26-6).
- Q3 What "In their wards" means for a doctor, front desk or billing set to it (the mockup gives them all patients).
- Q4 Scope for PHARMACIST and COUNSELLOR (not in the mockup's role list).
- Q5 Does a scope change wait for Publish (mockup bar) or apply at once?
- Q6 Is the bed board, the Emergency queue or the Patients search scoped ("bed board details and search")?
- Q7 How an admission gets its assigned nurse (only if A3).
- Q8 Changing the admitting doctor; co-consultants (B2).
- Q9 "My patients" for a nurse without A3, and for Billing.
- Q10 Does VISITING_DOCTOR get the Safety tab (the mockup shows it to owner and nurse only)?
- Q11 Other entries to Emergency access (from a refusal, the bed board); P2 instead of the picker.
- Q12 Free-text reason or "Other".
- Q13 "End now", extension, a cap on active grants.
- Q14 Break-glass for patients not admitted (Emergency queue, discharged).
- Q15 Text when a grant expires while a sheet is open.
- Q16 Toast wording "The owner will see this on the Safety board."
- Q17 Reviewed history, comment, "not OK" outcome, discharged patients in review.
- Q18 A second reviewer when the owner is absent or is the subject.
- Q19 / D-7 Record-access row at grant time.
- Q20 Review event text without the staff name ("Reviewed emergency access").
- Q21 May the switch be turned on while a nurse has no ward?
- Q22 Ward filter options: all wards or only the user's wards.
- Q23 May a nurse act (Call senior) on an escalation outside her wards without break-glass?
- Q24 Log refused out-of-scope requests as `deny` events?
- Q25 Must the second-nurse witness of a high-alert dose be in scope?

## Data needed (for db-engineer) — ONLY after the human chooses; nothing to build now

Under the recommended path (A1, M2, B1, C1, D-2):
1. `ipd.ward_assignment`: id, tenant_id (default `platform.current_tenant()`), ward_id (composite FK `(tenant_id, ward_id)` → `ipd.ward`), membership_id (no cross-module FK), assigned_by, assigned_at, removed_by, removed_at, version. Partial unique `(tenant_id, ward_id, membership_id) where removed_at is null`; index `(tenant_id, membership_id) where removed_at is null`. FORCE RLS. No delete; end by `removed_at`. Retention: life of the clinic.
2. A clinic setting "nurses see only their wards" (boolean, default false) on `ipd.config`, audited.
3. `ipd.emergency_access_grant` as D-2, with the guard trigger (review columns set once, never by the grantee; everything else immutable), indexes `(tenant_id, user_id, expires_at desc)` and `(tenant_id, opened_at) where reviewed_at is null`, no delete/truncate. Retention: never deleted in phase 1. Expected volume: about 10 grants a day per hospital, ~365,000 a year platform-wide.
4. Possibly `(tenant_id, doctor_membership_id) where status = 'ADMITTED'` on `ipd.admission`, only if plans show a need.
5. Plans to record: the scoped R1, escalation, dose and clinical-event reads with the ward and grant predicates, as `sahaj_app` with `app.tenant_id` set, at 300 admitted patients and 26 wards.
