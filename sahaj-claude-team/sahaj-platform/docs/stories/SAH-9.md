# SAH-9 · Queue and tokens
Mockup screen: Queue (sidebar → Queue)
Module: opd

## 1. Story
As clinic staff, I want to give a walk-in patient a token and track them through the clinic's stages on a board, so that everyone knows who is waiting, who is with the doctor, and who is done.

Scope note: the mockup's Queue screen also opens "Doctor App" (the consultation itself: vitals entry, diagnosis, prescription, case paper) when a card is in the consult stage, and lets a billing stage collect payment. Neither consultation content nor payment collection exists yet (no prior story built them). This story builds the token queue and stage board only; moving a card into consult or billing just changes its stage and shows a placeholder ("Opens in a later update"). SAH-10 (Doctor App / consultation) and SAH-11 (Billing) will fill those in without changing this story's data model.

## Acceptance criteria
1. Given staff issue a walk-in token (mobile number, pick an existing family member or register new via the SAH-8 form, choose doctor), then a visit is created with the next token number for today, stage = first on-stage per the clinic's workflow (from SAH-4's opd_settings: vitals stage only if vitals_on, otherwise straight to waiting-for-doctor), and it appears on the board.
2. Given the clinic's workflow settings (queue_on/vitals_on from SAH-4), then the board's columns reflect them: Waiting, Vitals (if on), With doctor, Done. Counsel/Billing columns are placeholders for later stories and always show "Opens in a later update" if a card reaches them.
3. Given a card on the board, then staff can move it to the next stage with a button (no drag-and-drop required for Phase 1; the mockup's drag is a progressive enhancement, not required for correctness). Moving backward is not allowed except by an owner correcting a mistake (PATCH with an explicit earlier stage, owner-only).
4. Given "My tasks" vs "Everyone" filter, then it filters to stages the signed-in role owns (NURSE owns vitals, DOCTOR/VISITING_DOCTOR owns with-doctor; OWNER/FRONT_DESK see everyone by default).
5. Given a token is issued, then it shows token number, patient name/age/sex, doctor, visit type (New or Follow-up, computed the same way SAH-7's fee settings follow-up window decides it: last visit with this doctor within follow-up_days), minutes waited (computed from created_at), and the consultation fee (shown, not collected — collection is SAH-11).
6. Given the free plan's daily token volume is unbounded in Phase 1 (no mockup limit found), then no cap is enforced; note this as an assumption.
7. Given any write, then it is audited and tenant isolation applies (RLS) exactly as prior stories.
8. Given a non-owner role without the right to create tokens (map onto SAH-6 roles: OWNER, FRONT_DESK, NURSE can create a token; all clinic roles can view and move cards for stages they own; only OWNER/FRONT_DESK can move any card), then the API returns 403 and the UI hides the action.
9. Given the end of the day, then visits stay on the board (no auto-archive in Phase 1); "Done" column shows today's closed visits, filtered by created_at = today server-side.

## 3. Technical design
- Schema `opd`, new table `opd.visit`: `id`, `tenant_id`, `patient_id` (FK to `patient.patient` — cross-module FK, exception allowed per postgresql rules: "no foreign keys across modules except to platform.tenant and patient.patient"), `token_no` (int, per-tenant per-day sequence, like SAH-8's UHID approach but reset daily — use a function keyed on (tenant_id, date)), `doctor_membership_id` (FK to `platform.membership`), `visit_type` (NEW/FOLLOW_UP), `stage` (check: WAITING, VITALS, WITH_DOCTOR, COUNSEL, BILLING, DONE), `fee_paise`, `created_at`, `created_by`, `updated_at`, `updated_by`, `version`. FORCE RLS, fail-closed, least-privilege grants (no delete — a visit is never deleted, only reaches DONE).
- `opd` reads patient name/age/sex through `patient.api` (new port there) rather than joining `patient.patient` directly from a query — keep the module boundary the same way `platform`/`opd` already do.
- `opd` reads doctor list/fee and workflow settings (vitals_on etc.) through existing `opd.opd_settings`/`platform.team` data already available to opd's own code or via platform.api, matching the pattern already used by SetupService/OpdDefaultsPort.
- Stage transitions are a small fixed state machine in the service layer: WAITING → VITALS (if on) → WITH_DOCTOR → COUNSEL (if configured, else skip) → BILLING → DONE. "Next" always moves exactly one step forward through the stages actually enabled for this clinic.
- Token numbering resets each day: a function similar to SAH-8's `next_uhid()` but keyed on `(tenant_id, current_date)`.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/opd/visits/today?mine=` | any clinic role | none | `Visit[]` | 401 |
| POST | `/opd/visits` | OWNER, FRONT_DESK, NURSE | `VisitCreate` (patientId, doctorMembershipId) | `Visit` | 400, 403, 404 |
| PATCH | `/opd/visits/{id}/stage` | any (own stage) / OWNER, FRONT_DESK (any stage) | `{stage}` | `Visit` | 400, 403, 404 |

All paths under `/api/v1`. Contract: `docs/api/opd.yaml` (extend the existing file).

## 5. Tasks
### Tasks — Backend
- Migration, `patient.api` read port for name/age/sex, visit service with the stage state machine, controller, role gate, audit, daily token numbering.
- Tests: create, stage advance, backward-move owner-only, my-tasks filter, cross-tenant 404, non-owner 403.

### Tasks — Frontend
- Route `/app/queue` behind the shell (menu key `queue` should already exist from SAH-5; add if missing).
- Board: columns per enabled stages, cards per the mockup's layout (token, type pill, wait time, name/age/sex, doctor, a "Move to <next stage>" button instead of drag), Everyone/My tasks toggle, "New token" button opening a simple dialog: mobile → pick family member or register new (reuse SAH-8's patient search/registration) → choose doctor → issue.
- Counsel/Billing/With-doctor-detail: clicking into them shows "Opens in a later update" rather than a broken screen.
- Unit tests for the board, the new-token dialog, and stage-move permissions.

## 6. Out of scope, Open questions
Out of scope: drag-and-drop (nice-to-have, not required), consultation content (SAH-10), payment collection (SAH-11), appointments-to-token conversion (needs the Appointments story), WhatsApp token notification, "late" alerts, workflow version history.
Open questions: none blocking; proceeding on the defaults above per DEC-008.

## 7. Status log
