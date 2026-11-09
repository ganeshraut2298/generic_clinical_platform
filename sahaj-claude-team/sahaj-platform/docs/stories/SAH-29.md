# SAH-29 · Packages and package billing (IPD, part 8)
Mockup screen: Clinic setup → Patient card, Roles and access, Wards/rooms/beds, Bed categories, Admission and deposits, Emergency and triage, Discharge — the panel "Packages" of Admission and deposits (`cfgIpAdm`, `pkgs`) and the package lines of the running bill (`ipLines`, `a.pkg`) in the patient sheet (`openIp`)
Module: ipd

## 1. Story
As the owner of a hospital, I want fixed-price packages (for example Appendicectomy, 3 days) listed in setup and billed as one line for the admitted patient, with extra days and exclusions at normal rates, so that the family knows the price in advance and the running bill matches it.

Scope note: Decided 9 Nov 2026: read-only slice (DEC-030, `docs/decisions/DEC-030-sah29-read-only-packages.md`). Design: `docs/architecture/SAH-29-ipd-packages.md`. The mockup shows packages in only two places: a read-only list in Clinic setup and the way the running bill treats an admission that has a package. It has no screen or button to create, edit or remove a package, and none to attach a package to an admission: the Admit sheet has no package field, and the three example packages and the four admissions that carry them exist only as seed data. This slice therefore builds only what the mockup shows (the list, the bill). Creating, editing and attaching (OQ-29-1, OQ-29-2) are future work (section 6). Packages are set by hand in the database in `sahaj_dev` and `sahaj_test` only, as SAH-24 did for the Cleaning state. SAH-29 is not for a live clinic until create and attach exist (P1): without them a real clinic could get a package only through an unaudited hand edit of billing data. The three example packages are demo data; their prices and day counts are not clinical or commercial defaults for any clinic.

## Acceptance criteria
1. Given Clinic setup → **Admission and deposits** (visible when IPD is Trial/On/Read-only), then under **Bed transfer rules** (SAH-26) a panel **Packages** lists the clinic's packages in the order they were created. Each row shows a chip with "3 days" (the days included; "Day-care" when the days are 0), the package name (bold), the small text "Bed, nursing and surgeon included · extra days and exclusions at normal rates", and the price at the right, for example "₹45,000". Readable by OWNER, FRONT_DESK, NURSE, DOCTOR. Only active packages are listed (P2). The panel is read-only: it has no add, edit or remove control. Empty state: the heading with an empty list and no new text, because the mockup has none (OQ-29-3, DEC-030).
2. Given an Admitted patient with a package, then the admission keeps its own copy of the package name, price and included days taken when it was attached; later changes to the package list never change an attached bill.
3. Given the running bill (SAH-25/26) of an admission with a package, then the bill lines are, in this order: **"Package · <package name> (bed, nursing and surgeon for <N> days)"**, quantity 1, at the package price; **"Bed · <current category> beyond the package"** at the daily bed charge of the category of the bed the patient is in, for the bill days beyond the included days, where beyond = max(0, bill days − max(1, included days)) (a day-care package includes 1 bed day) and the line is dropped when 0; then the extra charges of SAH-25, each named "<service> · not in package", priced as before (base price × the category multiplier, rounded half-up, × quantity). There is no "Doctor visits" line and no "Nursing care" line for a package admission, and bed-transfer segments (SAH-26) are never added. "Bill days" is the SAH-25 rule for "Bed day counted".
4. Given an admission with a package, then the sheet, the Admitted list, the Deposit low pill, the Deposit alerts tile and the board flag all use that bill (the package price counts from the first day), the heading note of the Running bill panel is unchanged, and the Summary strip shows no new field.
5. Given a package admission that is transferred (SAH-26), then the transfer works as in SAH-26, no bed-transfer segment line appears on the bill and the bed difference is not billed (as the mockup; OQ-26-4, OQ-29-5). Days beyond the package are billed at the bed the patient is in now, and extra charges are re-priced at that bed's multiplier. The stored transfer facts (such as `segment_days`) are still recorded as in SAH-26; the package bill ignores them. Given a discharge (SAH-27), the summary template defaults to "Surgical" unless the admission type is Maternity or Day-care.
6. Given roles and tenants: reading the package list and an admission's package — OWNER, FRONT_DESK, NURSE, DOCTOR (403 otherwise); package tables have `tenant_id` and RLS; another clinic's packages and admissions answer 404; module Off answers 403 `module-off`; Read-only keeps everything readable. Money is integer paise. The app cannot create, change or clear a package or an admission's package copy (no write grant for the app role). Audit of package changes: future work, with OQ-29-1/2; no app change exists in this slice, so the app writes no audit row. When it is built, the audit holds ids, package name and price only, never patient data. Changes made directly in the database are outside the app audit trail (P1).

## 3. Technical design
The stored shape is owned by the architect's note (`docs/architecture/SAH-29-ipd-packages.md`, section 3) and the db-engineer; this list only states what the stories need.
- New migration (db-engineer): `ipd.package` (tenant_id, FORCE RLS, app role SELECT only): `id`, `tenant_id`, `name text` (1 to 80, unique per tenant on lower(name)), `price_paise bigint` (0 to 10,00,00,000, whole rupees: P3), `included_days smallint` (0 to 60; 0 means day-care), `active boolean` (never deleted; only active rows are listed: P2), `created_at`, `created_by` (nullable in this slice because packages are set through the database; required once create is built), `version`. No `updated_at` or `updated_by` in this slice: nothing updates a row. The migration seeds nothing (the mockup examples are demo data; OQ-29-3).
- `ipd.admission` gets nullable `package_name text`, `package_price_paise bigint`, `package_days smallint` (the copy of AC 2), all or none set (check). The app role has no INSERT or UPDATE grant on them. The architect recommends a guard so that, once set, the copy cannot be changed or cleared and cannot be set on a discharged admission.
- `RunningBill` (SAH-25) takes the package copy as input and builds the lines of AC 3; list, board and tiles keep one code path so the numbers agree.
- Contract changes: `AdmissionDetail` gains `package`; `BillLine` names follow AC 3; new `PackageList`.

## 4. API (`/api/v1/ipd`, contract `docs/api/ipd.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/packages` | OWNER, FRONT_DESK, NURSE, DOCTOR | none | `PackageList` | 401, 403 (`forbidden`, `module-off`) |
| POST | `/packages` | OWNER | `{name, pricePaise, includedDays}` | `PackageList` (201) | FUTURE WORK, not in this slice (OQ-29-1) |
| PUT | `/packages/{id}` | OWNER | `{name, pricePaise, includedDays, active}` | `PackageList` | FUTURE WORK, not in this slice (OQ-29-1) |
| POST | `/admissions/{id}/package` | per OQ-29-2 | `{packageId}` | `AdmissionDetail` | FUTURE WORK, not in this slice (OQ-29-2); would be 400, 403, 404, 409 |

In this slice the package copy is also returned on `AdmissionDetail` (`package`, or null) for the four roles that already see the bill. Nothing in this slice writes a package.

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); package list read endpoint; `RunningBill` package branch; unit tests for AC 3 (included days 3 and 0, bill days below, equal and above the included days, extra charges "not in package", transfer with a package, both cuts of "Bed day counted"). Package create, edit and attach endpoints are future work (not in this slice).
### Tasks — Frontend
Packages panel in Admission and deposits; package lines in the Running bill panel; unit tests.

## 6. Out of scope, Open questions
Out of scope: creating, editing, deactivating and attaching packages (future work, below); the audit of package changes (future work); package inclusions and exclusions lists; package price by bed category; package for insurance or corporate rate cards; surgeon fee split; package change after attach; package refund or conversion to a normal bill; Packages in the Billing tariff (SAH-18).

### Future work (not in this slice; each needs a mockup design first)
- OQ-29-1: who creates and changes packages, and where? The mockup shows a read-only list. Proposed for a later story: OWNER on the same panel (name, price, included days, switch off). `created_by` becomes required then, and the audit of package changes is added.
- OQ-29-2: how is a package attached to an admission, and by whom? Not in the mockup. Proposed for a later story: a "Package" select in the Admit sheet and a one-time "Apply package" on the admission sheet for OWNER and FRONT_DESK, only before any extra charge is added.

Both change the mockup or go beyond it, so a human must add them to the mockup before any story is written.

### Open points with defaults applied (DEC-030, 9 Nov 2026; reversible)
| ID | Point | Default applied |
| --- | --- | --- |
| P1 (go-live) | With no create or attach, a real clinic could get a package only through an unaudited hand edit of billing data. | Hand-set packages only in `sahaj_dev` and `sahaj_test`. Do not use SAH-29 in a live clinic until create and attach are built. |
| OQ-29-3 | Empty panel: nothing is seeded and the mockup has no empty-state text. | Show the empty list with no new text. |
| OQ-29-4 (billing) | The mockup prints "for 0 days" for a day-care package and "1 days" for one day. | Keep the mockup text literally. A one-line change if the human prefers "for the day". |
| OQ-29-5 (billing) and OQ-26-4 | After a transfer, extra charges are re-priced at the new bed's multiplier, and a dearer bed adds no bed-difference charge (as the mockup). | Built as the mockup. The human should confirm the billing behaviour before go-live. |
| P2 | Only active packages are listed. | Keep. |
| P3 | Package prices are whole rupees. | Keep, as for deposits. |

(Also recorded in `docs/stories/IPD-open-questions.md` for OQ-29-1 to OQ-29-5; the decision file DEC-030 holds the answers.)

## 7. Status log
- 9 Nov 2026: decided by the human, read-only slice (DEC-030). Story aligned: create, edit, attach and the audit of package changes moved to future work; P1, P2, P3 and OQ-29-3 to -5 recorded with defaults.
