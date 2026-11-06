# SAH-24 · Bed board (IPD, part 3)
Mockup screens: IPD → Beds and admissions → Bed board tab (`ipBoard`, `bedTile`, `BSTAT`), the Bed sheet (`openBedSheet`, `ipclean`, `ipblock`), "Admit here" per ward
Module: ipd

## 1. Story
As the nurse or front desk of a hospital, I want to see every ward and bed at a glance with who is in each bed, and to mark a cleaned or blocked bed ready, so that I know where a new patient can go.

Scope note: the bed states are Available, Occupied, Cleaning, Reserved and Blocked. In this story Available and Occupied come from SAH-22/23. Cleaning arises after a discharge or a transfer, and Reserved after a planned-admission reservation; those events are later stories, so for now no bed enters Cleaning or Reserved through the app (the board and the sheet still handle them, and tests set them in the database). The mockup lets a user block a bed only from the Cleaning state, so that is what is built; blocking an available bed is not in the mockup and is not built. The ward "Configure" button and the ward filters of the newer 26-ward mockup are out of scope. The mockup's "Deposit low" and "Going home today" flags on a bed need the running bill and discharge and come later.

## Acceptance criteria
1. Given IPD is Trial/On/Read-only and the user is OWNER, FRONT_DESK, NURSE or DOCTOR, then the page Beds and admissions has two tabs, **Bed board** (default) and **Admitted** (with the pending count as in the mockup: the number of admitted patients), with the accessible tab pattern used on the follow-ups page. The tiles and the Admit patient button of SAH-23 stay above the tabs.
2. Given the Bed board tab, then a legend shows the five states with counts — Available, Occupied, Cleaning, Reserved, Blocked — each with its icon, and below it one panel per ward (in creation order): ward name, "<station> · N of M occupied", an occupancy bar ("N of M beds occupied" for screen readers), and the rooms as groups with the room name as heading (when "Use rooms" is on; rooms without beds are not shown; beds without a room are shown in a group without heading; when rooms are off all the ward's beds are shown in one group). Empty page when no wards: the SAH-23 panel "Add your wards and beds to start".
3. Given a bed tile, then it shows the code and the category (General, Semi-private, Private, ICU), the status label with its icon, and: for an Occupied bed the patient's name and "Day N · <doctor short name>" (N = 1 on the admission day, Asia/Kolkata calendar days; doctor short name = first two words of the doctor's name); for an Available bed "₹X a day" (the category's bed charge); for a Reserved bed nothing yet (reservations come later). The tile is a button with an accessible name like "Bed 101-A, General, Occupied, Sunil Jadhav". Tile colour follows the status.
4. Given a click on a tile: an Occupied bed opens the admission sheet of SAH-23; an Available bed opens the Admit-a-patient sheet with that bed preselected (only when the user may admit and IPD is not read-only; otherwise nothing opens); a Cleaning or Blocked bed opens the Bed sheet.
5. Given the Bed sheet, then: title "Bed <code>", subtitle "<category> · ₹X a day · <ward name>", the status pill, and a text — Cleaning: "Housekeeping is preparing this bed after a discharge or transfer. It is not offered for admission until it is marked ready."; Blocked: "This bed is out of use, for example for repair or isolation. It is not offered for admission." For OWNER, FRONT_DESK and NURSE when IPD is not read-only: a Cleaning bed offers **Block bed** and **Cleaned · ready**; a Blocked bed offers **Unblock · ready**. Both ready actions make the bed Available and toast "Bed <code> cleaned and ready for admission" or "Bed <code> unblocked and ready for admission". Block bed makes it Blocked (toast "Bed <code> blocked"). Other roles and read-only state only see Close.
6. Given the per-ward button **Admit here** (shown on a ward that has an Available bed, to users who may admit and when IPD is not read-only), then it opens the Admit sheet with the first Available bed of that ward preselected (first in room then code order).
7. API: `GET /ipd/board` (OWNER, FRONT_DESK, NURSE, DOCTOR) returns the data above in one call; `POST /ipd/beds/{id}/ready` and `POST /ipd/beds/{id}/block` (OWNER, FRONT_DESK, NURSE). `ready` is allowed from Cleaning or Blocked only, `block` from Cleaning only; any other state answers 409 problem type `bed-state` with the message "This bed cannot be changed from <status label>." Unknown or other-clinic bed 404. Every write is audited (IPD_BED_READY, IPD_BED_BLOCKED with bed id, code, from and to status) and answers 403 module-off / 409 ipd-readonly like the other IPD writes.
8. The board read does not write to the patient record-access log (it shows only name, day and doctor, like the Admitted list); the security review of SAH-23 decides whether admission reads need logging. Patient names are shown on screen only: never logged.
9. Tenant isolation (RLS) applies; no new tables.

## 3. Technical design
- No migration: `ipd.bed.status` (free/occ/clean/blk/res) and its update grant exist (SAH-22); occupied beds are joined to the ADMITTED admission of SAH-23 (patient name through `patient.api`, doctor name through `platform.api`, one batched lookup per request, no N+1).
- Bed status changes use `select ... for update` on the bed under the IPD tenant advisory lock, like SAH-23.
- `GET /ipd/board` returns wards (ordered by created_at, id), rooms, beds ordered by room then code, and counts per status.

## 4. API (`/api/v1/ipd`, contract `docs/api/ipd.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/board` | OWNER, FRONT_DESK, NURSE, DOCTOR | none | `BedBoard` | 403 |
| POST | `/beds/{id}/ready` | OWNER, FRONT_DESK, NURSE | none | `BedBoard` | 403, 404, 409 |
| POST | `/beds/{id}/block` | OWNER, FRONT_DESK, NURSE | none | `BedBoard` | 403, 404, 409 |

## 5. Tasks
### Tasks — Backend
Board query service and controller, two state-change endpoints with audit; unit tests. Real-DB tests by qa-engineer (set Cleaning/Reserved/Blocked by SQL).
### Tasks — Frontend
Tabs on the Beds and admissions page, Bed board tab, bed tiles, Bed sheet, Admit here; unit tests.

## 6. Out of scope, Open questions
Out of scope: entering Cleaning or Reserved through the app (discharge, transfer and reservation stories), blocking an available bed, ward Configure and filters, deposit-low and going-home flags, real-time refresh (the board reloads on action and with a Refresh button; no websockets).
Open questions: none blocking. Proceeding per DEC-008.

## 7. Status log
