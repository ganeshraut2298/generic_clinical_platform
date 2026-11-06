# SAH-20 · Staff, roster, attendance and holidays (HRA add-on, part 1)
Mockup screen: Staff and leave (sidebar → Staff and leave, `vStaff`), tabs Staff and shifts, Attendance, Holidays
Module: hra (reads the team through `platform.api`, doctor OPD days through `opd.api`)

## 1. Story
As the clinic owner, I want a weekly roster, a daily attendance sheet and the clinic holiday list in one place, so that I know who works when and who came in today.

Scope note: the mockup's fourth tab, Leave (apply, approve, balances, closing booking on approved doctor leave), is the next story, SAH-21. In this story the Leave tab and the "Apply for leave" button and the "Leave requests" tile are not shown. Staff are the clinic's team members (people with a login). The mockup also lists staff without logins (ward attendant, housekeeping); that needs a staff list outside Team and access and is left for a later decision (noted in the handoff).

## Acceptance criteria
1. Given the HRA add-on is off (SAH-19), then "Staff and leave" is not in the menu, and opening `/staff` shows the lock page "Staff, timings and leave is an add-on" with the text "Doctor timings and holidays already work for appointment booking without it. Switch it on when you want shifts, leave approval and attendance." and, for the owner, a "See plan and modules" button. The API answers 403 with problem type `module-off`. When HRA is on, the menu item "Staff and leave" appears for every clinic role.
2. Given HRA is on, then the page shows the title "Staff and leave", the lead "Shifts, leave, attendance and holidays. Payroll is not part of Sahaj; attendance exports to your payroll tool." and four tiles: **Staff** (count, "N doctors · M staff"), **Present today** (present or late, "N late"), **Next holiday** (date and name, or "—" and "None set"). (The fourth tile of the mockup, Leave requests, comes with SAH-21.) Tabs: Staff and shifts, Attendance, Holidays.
3. Given the Staff and shifts tab, then "Weekly roster" shows 7 days starting today (Today, then weekday and date) for every active team member (name, role label). Staff cells show the shift: Morning 08:00 – 14:00, Evening 14:00 – 20:00, Night 20:00 – 08:00, or Off. A doctor's cell is not editable and shows OPD (the doctor has OPD slots that weekday) or Off (from Appointments and slots, so booking and the roster always match); approved leave is added in SAH-21. The owner taps a staff cell to cycle Morning → Evening → Night → Off; other roles see the cells disabled. A legend shows the three shifts with their times. New staff cells default to Off.
4. Given the roster, then the owner sees "Publish roster" until published, then the pill "Published to staff". Publishing is one switch for the clinic (it records who and when); changing a cell after publishing keeps it published. Non-owners see the button disabled.
5. Given the Attendance tab, then "Today's attendance" lists every active team member with a status pill (Present, Late, Absent, or Not marked) and, if marked, "in at 8:52 AM"; the subtitle reads "Marked at the front desk · late after 15 min". The owner and the front desk see Present / Late / Absent buttons per person and can change them the same day; Present and Late record the time of marking (IST), Absent records none. Nurses and all other roles see the list only (the buttons are not rendered). Attendance is per IST calendar day; the page always shows today.
6. Given the Holidays tab, then two panels: **Clinic holidays** (the existing list from Clinic profile, `GET /platform/clinic/holidays`, newest upcoming first, with "Add in Clinic profile" link) and **Public holidays** (a fixed list for India · Maharashtra: 25 Dec 2026 Christmas, 26 Jan 2027 Republic Day, 1 May 2027 Maharashtra Day, 15 Aug 2027 Independence Day, 2 Oct 2027 Gandhi Jayanti), each with a switch "Clinic closed on <name>" that adds or removes the clinic holiday of that date through the existing holiday endpoints; owner only (others see the switches disabled). Subtitle texts as in the mockup. The next holiday tile uses the same clinic holiday list.
7. Given any roster cell change, publish or attendance mark, then it is audited (who, member id, date, before and after), without any patient data. Tenant isolation (RLS) applies to every new table.

## 3. Technical design
- Schema `hra` (already created). Tables, all with `tenant_id`, FORCE RLS:
  - `hra.roster_shift`: PK `(tenant_id, membership_id, work_date)`, `shift char(1) check in ('M','E','N','X')`, `updated_at`, `updated_by`. Row absent means Off.
  - `hra.roster_state`: `tenant_id` PK, `published_at timestamptz`, `published_by uuid` (lazy; absent = not published).
  - `hra.attendance`: PK `(tenant_id, membership_id, work_date)`, `status text check in ('in','late','abs')`, `marked_time time null` (IST wall clock), `marked_by uuid`, `marked_at timestamptz`, `version`. Updates allowed the same day; no deletes.
- `membership_id` references `platform.membership` logically (no cross-schema FK unless existing modules already do; db-engineer follows the project convention).
- Team members come from `platform.api` (TeamMemberPort or the closest existing listing port; add a listing method there if none exists). Doctor OPD days from a new small port in `opd.api` (does the doctor have OPD slots on this date's weekday and is the date not a clinic holiday). The `hra` module never calls `platform` or `opd` internals.
- Menu: `NavigationPolicy` gets a module-aware overload that adds the `staff` key for all roles when `ModuleEntitlementPort` says HRA is on (SAH-19). `/me` menu then contains it. The existing counts in `menuCounts` are for the base menu.
- IST dates everywhere (Asia/Kolkata).

## 4. API (`/api/v1/hra`, new contract `docs/api/hra.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/summary` | any clinic role | none | `StaffSummary` | 401, 403 (module-off) |
| GET | `/roster` | any clinic role | none | `Roster` | 401, 403 |
| PUT | `/roster/cells` | OWNER | `{membershipId, date, shift}` | `Roster` | 400 (doctor, past date, bad shift), 403, 404 |
| POST | `/roster/publish` | OWNER | none | `Roster` | 403 |
| GET | `/attendance` | any clinic role | none | `Attendance` | 401, 403 |
| PUT | `/attendance/{membershipId}` | OWNER, FRONT_DESK | `{status}` | `Attendance` | 400, 403, 404 |

Public-holiday switches reuse `POST/DELETE /platform/clinic/holidays` (SAH-5).

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); platform/opd port additions if missing, module-aware menu, hra service, controller, audit; unit tests. Real-DB tests by qa-engineer.
### Tasks — Frontend
Page `/staff` (menu key `staff`) with lock page, tiles, three tabs, roster table, attendance list, holidays; guard by menu; unit tests (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: Leave tab (SAH-21), staff without logins, phone check-in attendance mode (only "Marked at the front desk"), late-after setting editor, roster for past/future weeks beyond the 7 days, payroll export, notifications.
Open questions: staff without logins (ward attendant, housekeeping) shown in the mockup roster: a later human decision. Proceeding per DEC-008.

## 7. Status log
