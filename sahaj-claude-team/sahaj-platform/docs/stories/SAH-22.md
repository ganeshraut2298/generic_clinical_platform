# SAH-22 · Wards, rooms, beds and bed charges (IPD setup, part 1)
Mockup screens: Clinic setup → Wards, rooms and beds (`cfgIpBeds`, key `ipbeds`) and Bed categories and charges (`cfgIpRates`, key `iprates`)
Module: ipd (reads entitlements through `platform.api`)

## 1. Story
As the clinic owner of a hospital, I want to describe my wards, rooms and beds, give each bed a category, and set the daily charges, so that admissions, the bed board and billing can use them.

Scope note: this story is setup only. No patient can be admitted yet (SAH-23 onwards). Bed status is always "free" here; later stories use the other states (occupied, being cleaned, blocked, reserved).

## Acceptance criteria
1. Given IPD is not on for the clinic (SAH-19: state Off), then the two sections are not in Clinic setup and every `/ipd` endpoint answers 403 problem type `module-off`. When the state is Trial, On or Read-only, the sections appear in a group "In-patients (IPD)" of Clinic setup. In Read-only state all data can be read and every change answers 409 problem type `ipd-readonly` ("IPD is read-only. Nothing can be changed.").
2. Given the page **Wards, rooms and beds**, then the header shows "N of M beds on your plan" (M is the clinic's bed limit from SAH-19, default 25; the pill turns to warning at the limit), a switch **Use rooms** ("Switch off for a small nursing home: beds sit straight under each ward", default on), then each ward with its nursing station ("Nursing station 1") and "N beds", a "+ Room" button (or "+ Bed" when rooms are off), its rooms each with bed chips and a "+ Bed" button, and below an input "New ward, for example General ward · Female" with **Add ward**. With no wards: "No wards yet. Add your first ward below." Footnote: "Stored as one location tree (Branch → Ward → Room → Bed), so a hospital can add buildings and floors later without changing anything here."
3. Given **Add ward** with a name (1 to 80 characters, unique in the clinic, case-insensitive; empty gives the message "Type a ward name first"), then the ward is created with station "Nursing station 1"; when rooms are on, a first room is also created, named "Room " + (100 × the number of wards after adding + 1) (so the first ward gets Room 101). Toast: "<name> added. Now add its beds."
4. Given **+ Room**, then a room is added to the ward named "Room " + (highest number among the ward's room names + 1), or 100 × (the ward's position, 1-based) + 1 when the ward has no numbered rooms.
5. Given **+ Bed** (in a room, or on a ward when rooms are off), then if the clinic already has M beds the answer is 409 problem type `bed-limit` with the message "Your plan allows M beds. Ask Sahaj to raise the limit."; otherwise a bed is created in that room (when rooms are on and no room is named, in the ward's first room, creating one named as the ward if none exists) with a code made from the room's number (or, without a number, the first up to three capital letters of the ward name, or "B") followed by "-" and the first free letter A, B, C, … not used by any bed in the clinic; category General, except ICU for a ward whose name contains "icu" and Private for one containing "private" (case-insensitive); status free.
6. Given a bed chip, then its category can be changed from a list (General, Semi-private, Private, ICU) and the bed can be removed with × (disabled with the hint "A patient is in this bed" when occupied; the API answers 409 problem type `bed-occupied`). Every change is saved at once.
7. Given **Use rooms** is switched off, then beds are shown straight under each ward (existing rooms and their beds stay stored; beds keep their room link, shown flat); switching it on again shows the rooms again. No data is lost.
8. Given the page **Bed categories and charges**, then a table lists the four categories with columns Category, Bed charge a day (₹), Rate on services (a multiplier shown "× 1.25") and Beds (count of beds in it). Defaults: General ₹800 × 1, Semi-private ₹1,500 × 1.25, Private ₹2,500 × 1.5, ICU ₹5,000 × 2. The owner can edit the charge (whole rupees ≥ 0, ≤ 1,00,000) and the multiplier (0.5 to 5, up to two decimals). Hint under the table: ""Rate on services" multiplies doctor visits and services for patients in that category. ×1.5 means a ₹500 visit is billed ₹750 in a private room."
9. Given the same page, then three more settings: **Bed day counted** (At midnight / Every 24 hours, default At midnight; hint "When a new bed day is added to the bill"), **Nursing charge** (per day, every category, default ₹300) and **Doctor visit** (per day, before the category rate, default ₹500). All saved at once, owner only; other roles see the pages read-only.
10. Only the OWNER may change anything (403 otherwise); every clinic role may read. Every change is audited (ids, names of wards/rooms/beds and settings only, no patient data). Tenant isolation (RLS) applies to every new table. Money is integer paise in the API.

## 3. Technical design
- Schema `ipd` (exists). Tables, all with `tenant_id`, FORCE RLS:
  - `ipd.ward`: `id`, `name` (<= 80, unique per tenant on lower(name)), `station` (<= 60, default 'Nursing station 1'), `created_at/by`.
  - `ipd.room`: `id`, `ward_id` (FK ipd.ward), `name` (<= 40), `created_at/by`.
  - `ipd.bed`: `id`, `ward_id` (FK), `room_id` (FK, null when none), `code` (<= 20, unique per tenant), `category_key` (check in gen/semi/pvt/icu), `status` (check in free/occ/clean/blk/res, default free), `created_at/by`, `version`.
  - `ipd.bed_category`: PK `(tenant_id, key)`, `rate_paise` (>= 0), `multiplier numeric(4,2)` (0.5 to 5), updated_*; lazy: absent rows are synthesized with the defaults above.
  - `ipd.config`: `tenant_id` PK, `room_level` default true, `bed_day_cut` check in ('midnight','24h') default 'midnight', `nursing_paise` default 30000, `visit_paise` default 50000, updated_*; lazy.
  - Deleting a bed is a real delete (no patient data yet); grant delete on `ipd.bed` only. Later stories must replace delete by a guard when beds have history.
- The bed limit comes from `ModuleEntitlementPort` (`bedLimit`); the IPD state from the same port. Occupied check reads `ipd.bed.status`.
- The four categories are fixed in this phase (names and keys); adding categories is out of scope.

## 4. API (`/api/v1/ipd`, new contract `docs/api/ipd.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/layout` | any clinic role | none | `IpdLayout` | 401, 403 (module-off) |
| POST | `/wards` | OWNER | `{name}` | `IpdLayout` (201) | 400, 403, 409 |
| POST | `/wards/{id}/rooms` | OWNER | none | `IpdLayout` (201) | 403, 404, 409 |
| POST | `/beds` | OWNER | `{wardId, roomId?}` | `IpdLayout` (201) | 400, 403, 404, 409 (bed-limit) |
| PUT | `/beds/{id}` | OWNER | `{categoryKey}` | `IpdLayout` | 400, 403, 404, 409 |
| DELETE | `/beds/{id}` | OWNER | none | `IpdLayout` | 403, 404, 409 (bed-occupied) |
| PUT | `/room-level` | OWNER | `{on}` | `IpdLayout` | 400, 403, 409 |
| PUT | `/categories/{key}` | OWNER | `{ratePaise, multiplier}` | `IpdLayout` | 400, 403, 404, 409 |
| PUT | `/charges` | OWNER | `{bedDayCut, nursingPaise, visitPaise}` | `IpdLayout` | 400, 403, 409 |

(409 `ipd-readonly` applies to every write when IPD is read-only.)

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); ipd module services/controllers/audit, entitlement checks; unit tests. Real-DB tests by qa-engineer.
### Tasks — Frontend
Two config pages (keys `ipbeds`, `iprates`) in a new "In-patients (IPD)" group shown only when IPD is not Off; unit tests.

## 6. Out of scope, Open questions
Out of scope: admitting patients, bed board, bed states other than free, adding or renaming categories, renaming/removing wards and rooms, buildings and floors, nursing stations editor (fixed text), multi-branch, the 26-ward hospital template of the newer mockup.
Open questions: none blocking. Proceeding per DEC-008 and DEC-010.

## 7. Status log
