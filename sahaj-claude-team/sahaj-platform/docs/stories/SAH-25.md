# SAH-25 · Running bill, extra charges and deposit alerts (IPD, part 4)
Mockup screens: IPD → Admitted list (Running bill column, "Deposit low" pill), KPI tile "Deposit alerts", Bed board "Deposit low" flag, the patient sheet (`openIp`: summary, alert notice, running bill, "Add a charge"), `ipLines`, `ipDays`, `ipLow`, `IP_SERV`
Module: ipd

## 1. Story
As the front desk or nurse of a hospital, I want every admitted patient's bill to build up by itself (bed, doctor visits, nursing) and to add extra charges to it, and to be warned when the bill reaches the deposit, so that the family is asked for a further deposit before the money runs out.

Scope note: package pricing, bed transfer segments (bed charges before a transfer), discharge and the final bill are later stories; the bill here is the current bed all along. The extra-charge list is the fixed list of the mockup (`IP_SERV`), not a clinic price list (a clinic tariff for IPD comes with the billing story). Interim bill printing stays disabled ("Needs printing, not in this phase"). Insurance pre-authorisation is not built, so the Insurance notice only repeats what the mockup says.

## Acceptance criteria
1. Given an admitted patient, then the running bill is computed on read (nothing is posted by a timer) from: **Bed · <category name>** = the category's daily bed charge × bill days; **Doctor visits · <doctor short name>** = the visit charge × the category multiplier (rounded to whole paise) × bill days; **Nursing care** = the nursing charge × bill days; and one line per extra charge name = base price × the category multiplier (rounded) × quantity. Lines with zero quantity are dropped. Rates are those currently set in Clinic setup (SAH-22) and the category of the bed the patient is in.
2. Given "Bed day counted" (SAH-22): At midnight → bill days = the number of Asia/Kolkata calendar days from the admission day to today, inclusive (day 1 on the admission day); Every 24 hours → the number of completed 24-hour periods since admission, at least 1.
3. Given the Admitted list, then it has the column **Running bill** (₹, right-aligned) before Deposit, and the status pill **Deposit low** (critical colour, alert icon) when the payer is Self and the running bill is at or above the alert share of the deposit: `total × 100 >= deposit × alertPercent` (alert percent from Admission and deposits, default 80; with no deposit the pill shows as soon as the bill is above ₹0, which is always). Order of the status pill: Insurance / Corporate pill if payer is not Self, else Deposit low, else Deposit OK (this replaces the deposit-minimum pill of SAH-23; the "Deposit due" pill when below the admission minimum is dropped because the running bill supersedes it).
4. Given the tiles above the tabs, then a third tile **Deposit alerts** shows the number of admitted patients with Deposit low and the text "Running bill over N% of deposit" (N = the alert percent).
5. Given the Bed board, then an occupied bed tile with Deposit low shows the flag "Deposit low" with the alert icon under the patient line.
6. Given the admission sheet (SAH-23), then it gets the summary strip **Bed** (code, "<category> · <ward>"), **Day** (bill days and the admission type), **Running bill** (₹ and "N% of deposit" or "No deposit yet") and **Deposit** (₹ and "₹X left" or the critical "₹X over"); when Deposit low, the notice "The bill has reached N% of the deposit (alert at M%). Ask the relatives for a further deposit." (N = bill ÷ deposit, 100 when no deposit); for an Insurance patient the notice "Insurance patient · pre-authorisation pending with the insurer. Deposit alerts are paused."; the **Running bill** panel with its heading note ("Bed day counted at midnight" / "Bed day counted every 24 hours", plus " · <category> rate ×<multiplier> on services" when the multiplier is not 1), the lines (name, "q × ₹unit" when q > 1, amount), the "Total so far", and the deposit lines ("Deposit · UPI · 24 Oct 2026" with minus amounts); and **Add a charge** (OWNER, FRONT_DESK, NURSE; a select of the ten services with their price after the multiplier, "Extra doctor visit · ₹750", and an Add button; toast "<service> added to <patient>'s bill"). Buttons "Print interim bill" (disabled, with the hint above). The other actions of the mockup's sheet (Move to another bed, Start discharge) are not shown yet.
7. Given Add a charge, then: services are Extra doctor visit ₹500, IV fluids (per bottle) ₹350, Injection (IV/IM) ₹150, CBC ₹300, Chest X-ray ₹450, Oxygen (per day) ₹800, Major dressing ₹400, ECG ₹300, Nebulisation ₹200, Physiotherapy session ₹400 (base prices). Each add inserts a row (append-only: a mistaken charge cannot be removed in this story; a reversal comes with discharge/billing). Quantity per add is 1 and at most 50 rows of the same service per admission. Only for admissions in the Admitted state (409 `not-admitted` otherwise), IPD not read-only.
8. Roles: reading the bill follows SAH-23 detail (OWNER, FRONT_DESK, NURSE, DOCTOR); adding charges OWNER, FRONT_DESK, NURSE. Charges are audited (ids, service name, base price, admission id only). Tenant isolation (RLS) applies. Money is integer paise; multiplier rounding is half-up to whole paise per line.

## 3. Technical design
- New table `ipd.admission_charge`: `id`, `tenant_id`, `admission_id` (composite FK), `service_key text` (check in the ten keys), `service_name text`, `unit_paise bigint` (base price at the time), `qty int default 1 check between 1 and 50`, `added_at` (db default), `added_by`. Append-only like `ipd.deposit` (grants select + column insert; trigger denies update/delete/truncate). Index `(tenant_id, admission_id)`.
- The bill is computed in the service from admission, bed, category, config and charges; no stored totals. Money math lives in one small class (`RunningBill`) used by the list, board, tiles and detail so the numbers always agree.
- Contract changes: `AdmissionRow` gains `runningBillPaise` and `depositLow`; `AdmissionList` gains `depositAlerts` and `alertPercent`; `BoardBed` gains `depositLow`; `AdmissionDetail` gains `bill`; new `GET /ipd/charge-services` and `POST /ipd/admissions/{id}/charges`.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/charge-services` | OWNER, FRONT_DESK, NURSE, DOCTOR | none | `ChargeServices` | 403 |
| POST | `/admissions/{id}/charges` | OWNER, FRONT_DESK, NURSE | `{serviceKey}` | `AdmissionDetail` | 400, 403, 404, 409 |

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); RunningBill; changes to list/detail/board; charges endpoints; audit; unit tests. Real-DB tests by qa-engineer (admitted_at cannot be moved by `sahaj_app`: use the migrator/superuser path or a committed update if granted; for bill days > 1 tests may need a clock or a test hook — qa decides).
### Tasks — Frontend
Running bill column, Deposit low pill, Deposit alerts tile, board flag, sheet summary + bill + Add a charge; unit tests.

## 6. Out of scope, Open questions
Out of scope: packages, bed transfer segments, discharge/final bill, refunds and charge reversal, clinic-specific IPD price list, printing, WhatsApp alerts, insurance claims.
Open questions: none blocking. Proceeding per DEC-008.

## 7. Status log
