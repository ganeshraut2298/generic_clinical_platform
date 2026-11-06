# SAH-29 · Packages and package billing (IPD, part 8)
Mockup screen: Clinic setup → Patient card, Roles and access, Wards/rooms/beds, Bed categories, Admission and deposits, Emergency and triage, Discharge — the panel "Packages" of Admission and deposits (`cfgIpAdm`, `pkgs`) and the package lines of the running bill (`ipLines`, `a.pkg`) in the patient sheet (`openIp`)
Module: ipd

## 1. Story
As the owner of a hospital, I want fixed-price packages (for example Appendicectomy, 3 days) listed in setup and billed as one line for the admitted patient, with extra days and exclusions at normal rates, so that the family knows the price in advance and the running bill matches it.

Scope note: STATUS NEEDS DECISION BEFORE DEVELOPMENT. The mockup shows packages in only two places: a read-only list in Clinic setup and the way the running bill treats an admission that has a package. It has no screen or button to create, edit or remove a package, and none to attach a package to an admission: the Admit sheet has no package field, and the three example packages and the four admissions that carry them exist only as seed data. The story therefore builds what the mockup shows (the list, the bill) and leaves creating and attaching to open questions OQ-29-1 and OQ-29-2. Until they are answered, developers and testers set packages and attachments through the database, as SAH-24 did for the Cleaning state. The three example packages are demo data; their prices and day counts are not clinical or commercial defaults for any clinic.

## Acceptance criteria
1. Given Clinic setup → **Admission and deposits** (visible when IPD is Trial/On/Read-only), then under **Bed transfer rules** (SAH-26) a panel **Packages** lists the clinic's packages in the order they were created. Each row shows a chip with "3 days" (the days included; "Day-care" when the days are 0), the package name (bold), the small text "Bed, nursing and surgeon included · extra days and exclusions at normal rates", and the price at the right, for example "₹45,000". Readable by OWNER, FRONT_DESK, NURSE, DOCTOR. The empty state is not in the mockup (OQ-29-3).
2. Given an Admitted patient with a package, then the admission keeps its own copy of the package name, price and included days taken when it was attached; later changes to the package list never change an attached bill.
3. Given the running bill (SAH-25/26) of an admission with a package, then the bill lines are, in this order: **"Package · <package name> (bed, nursing and surgeon for <N> days)"**, quantity 1, at the package price; **"Bed · <current category> beyond the package"** at the daily bed charge of the category of the bed the patient is in, for the bill days beyond the included days, where beyond = max(0, bill days − max(1, included days)) (a day-care package includes 1 bed day) and the line is dropped when 0; then the extra charges of SAH-25, each named "<service> · not in package", priced as before (base price × the category multiplier, rounded half-up, × quantity). There is no "Doctor visits" line and no "Nursing care" line for a package admission, and bed-transfer segments (SAH-26) are never added. "Bill days" is the SAH-25 rule for "Bed day counted".
4. Given an admission with a package, then the sheet, the Admitted list, the Deposit low pill, the Deposit alerts tile and the board flag all use that bill (the package price counts from the first day), the heading note of the Running bill panel is unchanged, and the Summary strip shows no new field.
5. Given a package admission that is transferred (SAH-26), then no bed segment is created and the bed difference is not billed (as the mockup; OQ-26-4). Given a discharge (SAH-27), the summary template defaults to "Surgical" unless the admission type is Maternity or Day-care.
6. Given roles and tenants: reading the package list and an admission's package — OWNER, FRONT_DESK, NURSE, DOCTOR (403 otherwise); package tables have `tenant_id` and RLS; another clinic's packages and admissions answer 404; module Off answers 403 `module-off`; Read-only keeps everything readable. Money is integer paise. Any change to packages or attachments (once OQ-29-1/2 are answered) is audited with ids, package name and price only, never patient data.

## 3. Technical design
- New migration (db-engineer): `ipd.package` (tenant_id, FORCE RLS): `id`, `name text` (<= 80, unique per tenant on lower(name)), `price_paise bigint` (>= 0, <= 10,00,00,000), `included_days smallint` (0 to 60; 0 means day-care), `created_at/by`, `active boolean` (never deleted). The migration seeds nothing (the mockup examples are demo data; OQ-29-3).
- `ipd.admission` gets nullable `package_name text`, `package_price_paise bigint`, `package_days smallint` (the copy of AC 2), all or none set (check). Grants for these columns follow OQ-29-2.
- `RunningBill` (SAH-25) takes the package copy as input and builds the lines of AC 3; list, board and tiles keep one code path so the numbers agree.
- Contract changes: `AdmissionDetail` gains `package`; `BillLine` names follow AC 3; new `PackageList`.

## 4. API (`/api/v1/ipd`, contract `docs/api/ipd.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/packages` | OWNER, FRONT_DESK, NURSE, DOCTOR | none | `PackageList` | 403 |
| POST | `/packages` | OWNER | `{name, pricePaise, includedDays}` | `PackageList` (201) | 400, 403, 409 — only if OQ-29-1 is answered yes |
| PUT | `/packages/{id}` | OWNER | `{name, pricePaise, includedDays, active}` | `PackageList` | 400, 403, 404, 409 — only if OQ-29-1 is answered yes |
| POST | `/admissions/{id}/package` | per OQ-29-2 | `{packageId}` | `AdmissionDetail` | 400, 403, 404, 409 (`not-admitted`, `package-already-set`, `ipd-readonly`) — only after OQ-29-2 |

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); package list read endpoint; `RunningBill` package branch; unit tests for AC 3 (included days 3 and 0, bill days below, equal and above the included days, extra charges "not in package", transfer with a package, both cuts of "Bed day counted"). Package write and attach endpoints only after the open questions are answered.
### Tasks — Frontend
Packages panel in Admission and deposits; package lines in the Running bill panel; unit tests.

## 6. Out of scope, Open questions
Out of scope: creating, editing, deactivating and attaching packages until answered; package inclusions and exclusions lists; package price by bed category; package for insurance or corporate rate cards; surgeon fee split; package change after attach; package refund or conversion to a normal bill; Packages in the Billing tariff (SAH-18).
Open questions (also in `docs/stories/IPD-open-questions.md`):
- OQ-29-1 (blocking): who creates and changes packages, and where? The mockup shows a read-only list. Proposed: OWNER on the same panel (name, price, included days, switch off).
- OQ-29-2 (blocking): how is a package attached to an admission, and by whom? Not in the mockup. Proposed: a "Package" select in the Admit sheet and a one-time "Apply package" on the admission sheet for OWNER and FRONT_DESK, only before any extra charge is added.
- OQ-29-3: do new clinics start with example packages or an empty list (and what does the empty panel say)? Package prices are commercial data of the clinic. Proposed: empty list.
- OQ-29-4 (billing): the line text says "bed, nursing and surgeon for 0 days" for a day-care package in the mockup. Proposed wording for 0 days: "for the day". Confirm.
- OQ-29-5 (billing): extra charges made before a transfer or to a dearer bed are re-priced at the current category's multiplier (same as SAH-26 OQ-26-1). Confirm for packages.

## 7. Status log
