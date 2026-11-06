# SAH-15 · Today dashboard per role
Mockup screen: Today (sidebar → Today, `vToday`)
Module: opd (summary), billing (collected-today figure)

## 1. Story
As anyone signed in to the clinic, I want a Today screen that shows how the OPD day is going and what is waiting on me, so that I know what to do next without opening every screen.

## Acceptance criteria
1. Given any clinic role signs in, then Today (the landing page) shows a greeting "Good morning, <first name or clinic-given name>" (morning/afternoon/evening by Asia/Kolkata time), a lead line, and two buttons: "Book appointment" (goes to Appointments) and "New token" (opens the same new-token dialog SAH-9 already has). Both are hidden for roles that cannot book or issue tokens (same role rules as SAH-9/SAH-11).
2. Given the KPI row, then it shows four tiles with the mockup's labels: **Patients today** (total, with "N new · N follow-up"), **Waiting now** (visits in Waiting or Vitals, with "N in OPD · longest N min", where waiting minutes = now minus the token's creation time), **Collected** (net payments today in ₹ 1,25,000 style, with "N bills pending"), **Follow-ups due** (due today plus returned, with "N returned · N still to come"). "Today" is the Asia/Kolkata calendar day.
3. Given the Live OPD panel, then it shows one card per active doctor: name, "Now with doctor" token and patient (name, age, sex, New/Follow-up) or "Room free", and "Next in line · N" as token chips (visits in Waiting or Vitals for that doctor, oldest first) or "No one waiting". A link "Open queue" goes to Queue. The mockup's "Average wait today N min" line is not built (see Out of scope).
4. Given the My tasks panel, then it lists today's visits sitting in a stage the signed-in role owns, with token, patient, "stage · doctor · N min", and the one-click action SAH-9 already supports for that stage; roles with nothing show "Nothing waiting on <role> right now." Ownership uses the same rule as the Queue's "My tasks" filter (SAH-9/SAH-12).
5. Given the Alerts panel, then it shows only alerts that can be computed from data that exists: "N visits waiting for payment" (visits in Billing, with an "Open billing" action) and "N follow-ups missed" (with a "Review" action to Follow-ups, Missed tab), each only for roles that can act (owner and front desk; others see "No alerts for your role."). Rule-driven alerts (vitals not done in 10 min) and discount-approval alerts need features that are not built and are not shown.
6. Given an owner whose clinic setup is not complete, then the existing "Setup N% done" pill and the Getting started checklist from the setup overview (SAH-7's `/platform/setup/overview`) appear at the top, replacing the SAH-5 placeholder text.
7. Given the data, then the numbers are computed on the server in one call for opd (`GET /opd/today-summary`) and one for billing (`GET /billing/today-summary`); the frontend only formats. Tenant isolation (RLS) applies as before.
8. Given a patient's name appears in the response, then it is shown on screen only and never logged or written to an audit row (read-only endpoints write no audit rows).

## 3. Technical design
- No new tables. `opd` reads `opd.visit` (today's rows, already indexed by tenant and created_at), `opd.follow_up` (SAH-14) for the counts, and reuses `PatientLookupPort`/`TeamMemberPort` for names; the doctors list comes from `TeamMemberPort.findActiveDoctors` (added in SAH-11). `opd` must not call `billing` (billing depends on opd), so the money figures come from a separate billing endpoint and the frontend composes the two.
- `My tasks` reuses `VisitService.today(tenant, role, mine=true)` logic and its `ownedStages` rule, so there is one definition of "mine".
- Billing summary: `collectedPaise` = net of today's payments (positive minus refunds, Asia/Kolkata day, from `billing.payment`); `billsPending` = the number of visits currently in the Billing stage with a balance above zero (same definition as the counter's "Awaiting payment").
- Greeting name: from `/me` (existing). Time of day is the browser's IST-adjusted clock using the existing `shared/format/ist-date.ts` helpers.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/opd/today-summary` | any clinic role | none | `TodaySummary` | 401 |
| GET | `/billing/today-summary` | any clinic role | none | `BillingTodaySummary` | 401 |

All paths under `/api/v1`. Contracts: `docs/api/opd.yaml`, `docs/api/billing.yaml`.

## 5. Tasks
### Tasks — Backend
- `TodaySummaryService`/controller in opd (counts, longest wait, per-doctor now/next, my tasks, follow-up and missed counts); `BillingTodaySummaryService`/controller in billing. Unit tests for each figure (new vs follow-up split, longest wait, doctor with nobody / with one in consult / several waiting, role filtering of my tasks, net collected after a refund, midnight IST boundary).
### Tasks — Frontend
- Replace the Today stub (`frontend/src/app/features/shell/today/`) with the dashboard: header, KPI tiles, Live OPD, My tasks (with the existing stage actions), Alerts, and for incomplete setups the Getting started checklist. Two API calls composed on the page; loading and error states. Unit tests for the tile text, "Room free"/"No one waiting" states, role-based alerts and hidden buttons (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: "Average wait today" per doctor (needs per-stage timestamps that are not stored), rule-driven alerts such as "vitals pending over 10 min" and discount approval alerts (Alerts and escalation and approvals are not built), the fee-collection-timing text in the lead line (fee timing options are not built; the lead states only what is true today), the Doctor App link on the consult stage.
Open questions: none blocking; proceeding on the defaults above per DEC-008.

## 7. Status log
