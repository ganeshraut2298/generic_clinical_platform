# SAH-14 · Follow-up lists
Mockup screen: Follow-ups (sidebar → Follow-ups, `vFollowups`)
Module: opd

Numbering note: SAH-13 is skipped on purpose. Its ids are taken by docs from an unapproved "appointments workspace" attempt that the human chose to discard (3 Nov); appointments were built as SAH-11.

## 1. Story
As front-desk staff, I want a list of patients who are due for a follow-up today, who missed one, who are coming up, and who came back, so that I can call them and the clinic does not lose patients who need a review.

## Acceptance criteria
1. Given a visit moves forward out of With doctor (consultation done, SAH-9) and the visit's own workflow version (SAH-12) has the Follow-up set step on, then one follow-up is created for that patient and doctor, due on the visit day plus the clinic's follow-up window in days (SAH-7 fee settings `followUpDays`), with the reason "Review after treatment" (the mockup's own default). If the step is off, none is created. Never more than one follow-up per visit.
2. Given the Follow-ups screen, then it has four tabs with counts, matching the mockup: Due today, Missed, Upcoming, Returned. Status is computed, not stored: Returned when the patient has come back (see AC3); otherwise Due today when the due date is today (Asia/Kolkata), Missed when it is before today, Upcoming when it is after today.
3. Given an open (not yet returned) follow-up and a new token is issued for the same patient with the same doctor (SAH-9, or "Create token" from an appointment, SAH-11) on or after the source visit's day, then that follow-up becomes Returned, linked to the new visit. Only the oldest open follow-up for that patient and doctor is closed per new visit.
4. Given each list row, then it shows the patient's name, reason, doctor's short name, mobile number, and a pill: "Today", "N days late" or the due date (24 Oct 2026 style); Returned rows show "Came back". Lists are sorted by due date, oldest first.
5. Given the mockup's "Remind" and "Remind all due today" WhatsApp buttons and the WhatsApp allowance meter, then they are shown but disabled with "Needs WhatsApp (not in this phase)", the same rule as SAH-11 (no notifications service in Phase 1). The allowance meter is not shown (there is no allowance data yet).
6. Given any clinic role that has Follow-ups in its menu, then it can read the lists; there are no writes from this screen in Phase 1. Tenant isolation (RLS) applies as in prior stories.
7. Given a follow-up is created or closed, then it is audited with ids only, never the patient's name or mobile.

## 3. Technical design
- `opd.follow_up`: `id`, `tenant_id`, `patient_id` (FK `patient.patient`), `doctor_membership_id` (FK `platform.membership`), `source_visit_id` (FK `opd.visit`, unique: one follow-up per visit), `due_date date not null`, `reason text not null` (check length <= 200), `returned_visit_id` (nullable FK `opd.visit`), `returned_at timestamptz` (nullable), `created_at/by`, `updated_at/by`, `version`. FORCE RLS, index `(tenant_id, due_date)`, index `(tenant_id, patient_id, doctor_membership_id) where returned_visit_id is null` for the return lookup. Never deleted.
- Creation happens inside `VisitService.moveStage` in the same transaction, when the move is forward from `WITH_DOCTOR` and the visit's own workflow version has `followup` on. `due_date` = the source visit's Asia/Kolkata calendar day + `followUpDays`. Insert with `on conflict (source_visit_id) do nothing` so a retried move never makes a second row.
- Closing happens inside `VisitService.issueToken` in the same transaction: after the new visit is inserted, set `returned_visit_id`/`returned_at` on the oldest open follow-up for (tenant, patient, doctor) whose source visit is not the new visit.
- The list needs the patient's mobile. `patient.api.PatientLookupPort.PatientSummary` has no mobile today; add it there (read-only, already in `patient.patient`). The mobile is shown on screen only; never logged or put in an audit row.
- Status (`DUE`, `MISSED`, `UPCOMING`, `RETURNED`) is computed in the service from today's IST date, so it never goes stale.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/opd/follow-ups?status=DUE\|MISSED\|UPCOMING\|RETURNED` | any clinic role | none | `FollowUpList` (`counts` for all four tabs, plus `items` for the requested status) | 400 (bad status), 401 |

All paths under `/api/v1`. Contract: `docs/api/opd.yaml` (extend the existing file).

## 5. Tasks
### Tasks — Backend
- Migration for `opd.follow_up`.
- `FollowUpRepository`/`FollowUpService`/`FollowUpController`; hooks in `VisitService.moveStage` (create) and `VisitService.issueToken` (close); `PatientSummary.mobile`.
- Tests: created on consult-done when the step is on and not when off; due date uses `followUpDays`; one per visit even on a retried move; status computation for all four cases around midnight IST; a new token for the same patient and doctor closes the oldest open one, a different doctor does not; counts match items; cross-tenant isolation.

### Tasks — Frontend
- Replace the `/app/followups` placeholder with the real page: page head with the mockup's lead text, disabled "Remind all due today", four tabs with counts, list rows per AC4, disabled per-row "Remind" with the not-in-this-phase note, empty state "No follow-ups in this list."
- Unit tests for the tab switch, the pill text (today / N days late / date), and the disabled WhatsApp actions (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: sending WhatsApp reminders and the allowance meter (notifications service is Later), the doctor choosing a custom next-visit date or reason (no consultation screen in Phase 1; the default window is used), editing or cancelling a follow-up, follow-ups created by IPD discharge (IPD not built).
Open questions: none blocking; proceeding on the defaults above per DEC-008.

## 7. Status log
