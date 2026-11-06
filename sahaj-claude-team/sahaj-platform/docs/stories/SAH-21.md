# SAH-21 · Leave requests and balances (HRA add-on, part 2)
Mockup screen: Staff and leave → Leave tab (`hrLeave`), plus Leave in the roster, Attendance and the tiles
Module: hra (implements a port defined in `opd.api`)

## 1. Story
As a team member I want to ask for leave and see how many days I have left, and as the owner I want to approve or decline requests, so that leave is agreed in one place and a doctor's approved leave stops online booking on those days.

## Acceptance criteria
1. Given HRA is on, then the Staff and leave page gets a fourth tab **Leave** with a count badge of pending requests (the badge is visible to the owner only), a page-header button "Apply for leave" that opens the tab, and the fourth tile **Leave requests** ("Waiting for the owner"). When HRA is off nothing changes (lock page).
2. Given the Leave tab, then two columns: **Leave requests** ("You approve leave." for the owner, "The owner approves leave." otherwise; "Approved doctor leave closes booking for those days.") and, at right, **Apply for leave** and **Balance this year**.
3. Given the request list, then the owner sees every request and other roles only their own, newest first. Each row shows the person's name and leave type ("Dr. Rohan Deshpande · Casual leave"), the dates ("Today to 3 Nov 2026" style: "2 Nov 2026" for one day, "13 Nov 2026 to 15 Nov 2026" for a range), the reason, "asked <date and time IST>", and for a pending request on a doctor, a warning line "N booked appointment(s) on those days" when the doctor has booked appointments on any of those days. Pending rows show **Decline** and **Approve** buttons to the owner and the pill "Waiting for owner" to others; decided rows show the pill Approved or Declined. Empty text: "No leave requests."
4. Given "Apply for leave", then: **For** (a select of all active team members for the owner; a fixed line with their own name for everyone else), **Type** (Casual leave · N left, Sick leave · N left, Earned leave · N left, from the chosen person's balance), **From** and **To** (dates, from today up to 60 days ahead; To not before From), **Reason** (optional short text, up to 200 characters), and **Send request**. A request that overlaps another pending or approved request of the same person is refused with "That person already has leave on some of these days." A request is created as pending; applying more days than are left is allowed (the owner decides) and the balance may show a negative number in the owner's view only as 0.
5. Given **Balance this year**, then a table of Staff × CL, SL, EL remaining (owner sees every active member, others only themselves), with the footnote "CL casual leave (8 a year) · SL sick leave (7 a year) · EL earned leave (15 a year)". The year runs 1 Jan to 31 Dec (IST); remaining = allowance − days of approved requests whose dates fall in that year (calendar days, From to To inclusive; a request across New Year counts each day in its own year); never below 0.
6. Given the owner approves a request, then its status becomes Approved and the decision time and approver are recorded; Decline likewise. A decided request cannot be changed (409 "This request was already decided."). Approving or declining is audited. No WhatsApp or other message is sent (not in this phase); the page does not claim one was.
7. Given an approved request, then for every day in its range: the person's cell in the Weekly roster shows **Leave** (also for doctors, cell kind LEAVE, not editable), Attendance shows the pill **On leave** and no Present/Late/Absent buttons for today, and for a doctor the online booking and slot lists treat those days as closed (the doctor has no slots; existing appointments are left as they are and are not cancelled).
8. Given the booking side, then it works without opd depending on hra: opd defines a port in `opd.api` (doctor blocked dates for a range) and hra implements it; opd's slot computation asks all implementations. When HRA is off, nothing is blocked (hra's implementation returns no dates), but approved leave data is kept.
9. Roles: any clinic role may apply for themselves and read their own requests and balance; only the OWNER may apply for another person, see all, approve or decline (403 otherwise). Every endpoint answers 403 `module-off` when HRA is off. Tenant isolation (RLS) applies. The reason text is free text typed by staff: it is never logged and never put in the audit row (audit holds ids, dates, type and status only).

## 3. Technical design
- `hra.leave_request`: `id`, `tenant_id`, `membership_id`, `leave_type text check in ('CL','SL','EL')`, `from_date`, `to_date` (check `to_date >= from_date`, span <= 60 days), `reason text null (<= 200)`, `status text check in ('pending','approved','declined')`, `created_at`, `created_by`, `decided_at`, `decided_by`, `version`. FORCE RLS; grants select, insert, update; no delete. Index `(tenant_id, membership_id, from_date)` and `(tenant_id, status)`.
- Allowances CL 8, SL 7, EL 15 are constants in code this phase (an editor for leave types is out of scope).
- Overlap check inside the insert transaction (advisory lock per member or `exclusion` is not available across statuses, so lock then check).
- Appointment counts for the warning come from a new method on an `opd.api` port (booked appointments for a doctor between two dates).

## 4. API (`/api/v1/hra`, contract `docs/api/hra.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/leave` | any clinic role | none | `LeaveView` | 401, 403 |
| POST | `/leave` | any clinic role (owner may name another person) | `LeaveInput` | `LeaveView` (201) | 400, 403, 404, 409 |
| POST | `/leave/{id}/approve` | OWNER | none | `LeaveView` | 403, 404, 409 |
| POST | `/leave/{id}/decline` | OWNER | none | `LeaveView` | 403, 404, 409 |

Also changed: `StaffSummary.leaveRequests` (pending count; owner only, 0 for others), `RosterCell.kind` gains `LEAVE`, `AttendanceRow.onLeave` boolean.

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); leave service/controller/audit; opd.api port + opd slot computation wiring + appointment count port; roster and attendance changes; unit tests. Real-DB tests by qa-engineer including a doctor on approved leave getting no slots.
### Tasks — Frontend
Leave tab, apply form, balance table, approve/decline, tile and badge, roster Leave cells, attendance On leave; unit tests.

## 6. Out of scope, Open questions
Out of scope: WhatsApp messages and reschedule links, editing leave types or allowances, half days, cancelling or editing a request, public-holiday interaction, payroll.
Open questions: none blocking. Proceeding per DEC-008.

## 7. Status log
