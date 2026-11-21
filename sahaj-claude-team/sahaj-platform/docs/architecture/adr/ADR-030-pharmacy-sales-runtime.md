# ADR-030 · Pharmacy sales and dispensing runtime: the sale transaction, GST maths, held bills, udhar, returns and credit notes, H1 and Schedule X registers, allergy check, activity feed, touch-point ports

Story: SAH-80 (Pharmacy & Inventory, Phase 3: Pharmacy; sub-stories 80-1 to 80-23, work packages WP-I to WP-O).
Mockup screens: P&I files `01-pharmacy-command-center.html`, `02-rx-linked-pos-billing.html`, `03-hospital-pharmacy-opd-and-ipd.html`, `04-sales-return.html`, `05-compliance-roles-and-audit.html`, `15-reports.html` (group "Sales & compliance"), `17-end-to-end-flow.html` in `docs/handoffs/PHI-input/screens/` (accepted design addition, DEC-061 Q1). Screen-map rows are pending (OQ-80-1).
Module: `pharmacy` (ADR-028 D1). Reads `platform.api` (team, entitlement, audit, colleague PIN) and `patient.api` (`PatientLookupPort`, `PatientClinicalPort`, `PatientAccessPort`, all existing). Every OPD, IPD and billing touch point is a pharmacy-owned port with a no-op implementation (S12).
Status: **PROPOSED** (18 Nov 2026), with `docs/api/pharmacy-sales.yaml` 0.3.0 and `docs/api/pharmacy.yaml` 0.3.0. Decisions relied on: **DEC-074** (all 42 SAH-80 open questions, binding), **DEC-072 Q2** (touch points as ports), DEC-073 (standing delegation), DEC-076 to DEC-078 (masters hardening; series locking binds the bill series).
Relation: builds on ADR-028 (module, tenancy, store guard, D7 approvals, D8 numbering, D10 money) and ADR-029 (batches, `StockPosting`, FEFO, P5 lock order, P6 engine, P7 Schedule X signature, P9 documents and `document_event`, P13 reports). Nothing in them is reversed; S3 records one narrow, documented exception to the P5 step order for the sale series.
Deciders: the human (contract approval; each port separately, with the IPD session), tech lead; recorded by solution-architect.
Revision 0.3.8 (21 Nov 2026, review round 7; DEC-091 items 2 and 3, DEC-092 items 1 to 6; reports `docs/reviews/SAH-80-code.md`, `docs/reviews/SAH-80-security.md`): the contract files move to 0.3.8 together (additive or wording only). Changed here: S2 (CASHIER capabilities, role read-out "No limit set"), S3 (discount lock and backstop), S5 (Schedule X cancel), S6 (customer edit audit), S7 (export record access), S10 (activity feed for blind callers), S14 (discount approval validity), new S15 (OQ-80-37 reading) and S16 (blind count on sale-side reads), the go-live checklist, "Data needed", open points O-19 and O-20.

**Order of work for a first-time reader.** SAH-78 (masters, built) → SAH-79 WP-D (batches, ledger, `StockPosting`, approvals, documents and numbering) → SAH-79 WP-E to WP-H → SAH-80 in the order **WP-I, WP-J, WP-L, WP-K, WP-M, WP-N** (DEC-074 OQ-80-41); WP-O is not built. DEC-074: SAH-80's database and code start only after SAH-79's. Read S3, S4 and S12 first.

---

## Context

1. Phase 3 sells and dispenses from the stock that SAH-79 posts. A sale is a ledger movement (AVAILABLE out), a GST invoice with a gapless number, money recorded by mode, and, for Schedule H1 and X, a legal register row, all in one step (BR-5).
2. Built facts that constrain the design: Spring JDBC with hand-written SQL (ADR-028 R3-2); `StockPosting` is the only writer of `stock_balance` and `stock_ledger` (ADR-029 P5, a convention test); `StaffPinPort.checkColleague` must run outside any transaction (ADR-029 P7); the store-denial audit is written after the transaction (ADR-028 R5-1); no scheduler, no event registry, no new library or extension (DEC-072 standing rules).
3. `patient.api` exists and is enough for TP-1 and TP-3 (lookup, clinical with allergy text and `AllergyStatus`, access log). Nothing in `opd.api`, `ipd.api` or `billing.api` serves TP-2, TP-4, TP-5, TP-6, TP-7 or TP-8 (requirement, "Touch points").
4. DEC-074 decides the business, tax and legal answers; its four changes from the BA's recommendations are binding: (a) OQ-80-6 the bill also shows the taxable value and the tax per GST rate, no IGST; (b) OQ-80-17 the H1 register adds the patient's age, address and prescription date, asked only on a bill with an H1 line and stored only with that register row, with reversing rows, kept until a period is set; (c) OQ-80-22 keyword allergy match only, no drug classes, the screen says so; (d) OQ-80-35 a pharmacy-owned activity feed from document histories, no platform read method. Also OQ-80-18b: an NDPS yes or no on own products.
5. DEC-072 Q2 and DEC-074: the IPD, OPD and billing touch points are not built in those modules. Standalone mode must work completely; in Integrated mode the OPD and IPD queues show "Not connected" and IPD issues and returns save as "Charge pending" and "Credit pending".

---

## Decisions

### S1 · Contract: a sibling file, same API

- **`docs/api/pharmacy-sales.yaml` 0.3.0** holds the 70 SAH-80 operations; **`docs/api/pharmacy.yaml` 0.3.0** holds only the additive SAH-78 touch-ups of TP-10 on existing components (StoreRole `CASHIER`, `StoreAccess` sale capabilities, context capabilities, `DocType` `CREDIT_NOTE` and `IPD_ISSUE_SLIP`, nine reason lists, unit sale fields, the mode guard 409 `open-integrated-documents`, `ndps` on own products, `LedgerTransaction` sale values, inbox `SALES` chip and `APPROVE` action, six report keys).
- **Why a second file.** ADR-029 P1 chose one file for SAH-79. After 0.2.1 `pharmacy.yaml` is about 6,800 lines; 70 more operations would pass 10,000, and each edit would risk text that the SAH-78 and SAH-79 code is built against. No generator reads the contract (checked: no OpenAPI plugin in `backend/pom.xml`; the Angular client is hand-written and only cites the file), so a cross-file `$ref` costs nothing at build time. The sibling file copies no component: it references `./pharmacy.yaml#/components/...`, keeps base path `/api/v1/pharmacy` and states that general rules 1 to 23 apply unchanged; it adds rules 24 to 31. Both files carry version 0.3.0 and move together.
- Packages (ADR-029 P1 style): `com.sahaj.pharmacy.sale` (bill, quote, held bill, cancel, refund, discount request), `customer` (customer, account), `register` (H1, Schedule X), `salesreturn`, `dispense` (OPD queue, IPD issue, ward return, discharge, charges), `compliance` (licences, pharmacist registration, role read-out, activity), `dayclose`; `integration` gains the ports of S12. Nothing outside `com.sahaj.pharmacy` depends on them.

### S2 · Roles and capabilities

- Store role **`CASHIER`** (DEC-074 OQ-80-36): held only by team roles BILLING and STORE_STAFF, only at an ACTIVE store with `saleAllowed` that is not the Central Store. A store switched to sale-not-allowed makes the mapping "Needs review" (as SAH-78 BR-11), never deleted.
- New `StoreAccess` fields, derived on every request by the same `StoreCapabilities` code that the guard uses (never stored): `sell`, `sellScheduled` (= `substitute` = `overrideBlock`: a PHARMACIST member mapped; never OWNER or CASHIER, OQ-80-20, OQ-80-37), `cancelBill` (OWNER any day; PHARMACIST member the bill's IST day, checked per bill; never CASHIER; 0.3.8: a bill with a Schedule X line only by a PHARMACIST member with a second signer, never the OWNER, S15), `salesReturn`, `acceptSalesReturn` (PHARMACIST member, APPROVER or current DELEGATE, OWNER), `dispense` = `acceptWardReturn` (PHARMACIST member; the OWNER may read the IPD screens but not dispense, per stories 80-13 and 80-14; BR-4 says otherwise, see open point O-9), `recordWardReturn` (PHARMACIST member or STORE_USER), `readRegisters` (OWNER, PHARMACIST member, APPROVER or current DELEGATE), `dayClose`. All need `saleAllowed`.
- **CASHIER capabilities, explicit** (0.3.8, DEC-092 item 4, security L25). Before 0.3.8 the code let a CASHIER fall into the Store user branch of the SAH-79 table and so gave it inventory capabilities nobody decided. A CASHIER gets exactly this (an ACTIVE mapping at a sale store that is not the Central Store):

  | StoreAccess field | CASHIER |
  |---|---|
  | `readStock` | true |
  | `sell`, `salesReturn`, `dayClose` | true (the sale columns) |
  | `raiseIndent`, `approve`, `receive`, `receiveScheduleX`, `consumeReturn`, `uploadOpeningStock`, `postOpeningStock`, `adjustStock`, `purchase`, `dispatchTransfer`, `acceptReturn`, `consume`, `returnDamage`, `startCount`, `count`, `reviewCount`, `handleScheduleX` | false |
  | `sellScheduled`, `substitute`, `overrideBlock`, `cancelBill`, `acceptSalesReturn`, `dispense`, `recordWardReturn`, `acceptWardReturn`, `readRegisters` | false |

  Because a CASHIER has `readStock` but not `count`, the blind-count test changes from "count and not reviewCount" to "readStock and not reviewCount" (S16); for every SAH-79 role `readStock` and `count` are the same set, so nobody else changes. The role read-out CASHIER column is computed from this row.
- **Role read-out** (`/compliance/role-readout`, OQ-80-38): computed from `StoreCapabilities` and the current approval-rule version, so the screen cannot disagree with the server. Cells the rules cannot express are not shown as settings. 0.3.8 (DEC-092 item 5, security L26): with no `SALE_DISCOUNT` rule, any seller may give any discount with no approval (built behaviour; no default rule is seeded, it is the owner's pricing decision), so the DISCOUNT_ABOVE_LIMIT cell of every selling column other than OWNER reads ALLOW with ruleText "No limit set"; a Lean rule the owner chose stays ALLOW with no text. The owner must set a limit before selling (go-live checklist).
- The convention test learns the new handlers; every `/stores/{storeId}/**` handler declares OPERATION, MASTER or SCOPED and calls the guard; rule 24 (`sale-not-allowed`) is checked right after the guard.

### S3 · The sale transaction (bill number at save, gapless series per store)

**Quote, then save.** The screen calls `POST …/sale/quote` after each cart change: FEFO picks without locks, totals by S4, checks (allergy, H1, Schedule X, pharmacist, discount), and a `quoteVersion` digest (lines, batches, MRPs, percent). Nothing is written except the ADR-029 P4 expiry sweep.

**Save (`POST …/bills`)**, in this order:
1. Outside any transaction: if any line is Schedule X, `ScheduleXSignature.verify` (ADR-029 P7; first person a PHARMACIST member, second a different active pharmacist or owner; 403 `second-signer-same` when it is the caller).
2. One transaction, `SET LOCAL statement_timeout` as the default (no raise):
   - `client_request` replay check (ADR-029 P5).
   - Lock order (ADR-029 P5, steps used): (2) the discount `approval_request` row `FOR SHARE` (APPROVED and valid for this save by S14: cart digest, raiser, held bill, unused, IST day; two saves can both hold it in share mode, so single use is enforced by the unique partial index on `bill.discount_request_id`, whose violation answers 409 `discount-approval-void` and rolls the number back); (3) document rows `FOR UPDATE`: the held bill, the OPD dispense row (claimed by the caller), the customer account row (udhar, credit note); (5)-(7) batches and **every** balance row of each `(store, product)` in canonical order; FEFO in memory from the locked rows; expired AVAILABLE rows swept first.
   - Re-price with S4 and compare with `quoteVersion` and `expectedNetPaise`: any difference 409 `sale-changed` with the new quote; short stock 409 `insufficient-stock`. Re-read the allergy (Integrated, patient picked) and re-check every rule of the contract.
   - **Number last** (the one exception to the P5 step order, recorded here): `pharmacy.next_store_bill_no(store)` or `next_document_no('BILL')` is called after the balance locks, immediately before the inserts. Reason: the counter row lock is held until commit, so taking it last keeps the serialised part of every sale of the series as short as possible (C-10, 1 s). No cycle is possible: the BILL and CREDIT_NOTE counters are taken only by sale-side transactions (bill save, cancel refund, sales return), which all take them after their balance locks; no Phase 2 transaction takes them. Gapless: the counter update rolls back with the transaction, so a failed save uses no number (BR-5).
   - Inserts: `bill`, `bill_line` (one per batch), `bill_payment`, ledger lines `COUNTER_SALE` through `StockPosting` (reference = bill number), `customer_account_entry`, `h1_register_row` and `schedule_x_register_row`, Rx line supply state, held bill closed, `document_event`, audit `PHARMACY_BILL_SAVED` (ids, number, counts, total).
3. Answer 201 with the bill and its print data.

**Series per store** (DEC-074 OQ-80-7, R2-12). SAH-78's `document_series` / `document_counter` stay as they are. New `store_bill_series` and `store_bill_counter` with `next_store_bill_no(store_id)`, the same rules (prefix ≤ 6, rendered ≤ 16, restart needs the FY part) and the series lock of DEC-078 item 4 (`series-locked` replaces the story's `series-in-use`). A store without its own series uses the unit's BILL series. Prefix uniqueness across the unit series and all store series is a service check under the lock of the unit's BILL `document_series` row. A switched-off store keeps its series; it is never reused. `CREDIT_NOTE` ("CN/", FY part, 6 digits, restart: CN/26-27/000001) and `IPD_ISSUE_SLIP` ("IS-", 4 digits, open point O-1) are unit series.

**Concurrency.** Two counters selling the last unit: the second waits on the balance lock and gets 409 `insufficient-stock` with `available`. Double tap: `clientKey`. Two people billing the same OPD prescription: the dispense row lock and `rx-claimed`.

### S4 · GST maths (DEC-074 OQ-80-6; go-live G-22)

For a bill with lines i (quantity qᵢ, batch MRP mᵢ in rupees incl. GST, GST rate rᵢ %):
1. Line amount aᵢ = round_half_up(qᵢ × mᵢ × 100) paise. MRP total A = Σ aᵢ.
2. Discount D = round_half_up(A × p / 100) for the bill percent p; spread over the lines in proportion to aᵢ by the largest-remainder method so that Σ dᵢ = D exactly (open point O-4).
3. Line net nᵢ = aᵢ − dᵢ (GST included). Taxable tᵢ = round_half_up(nᵢ × 100 / (100 + rᵢ)); tax xᵢ = nᵢ − tᵢ. Both are stored on the line (returns and the GST register use them; open point O-2).
4. Per GST rate: Σ tᵢ, Σ xᵢ; CGST = round_half_up(Σ xᵢ / 2) per rate, SGST = the rest (`taxByRate`, Rule 46). The bill's CGST and SGST are the sums over rates. No IGST (counter sales are intra-state).
5. Net N = Σ nᵢ; net payable = round_half_up(N / 100) × 100; round off = net payable − N (−50 to +49 paise).
Worked example (mockup 02): A = 56,040; D = 2,802; N = 53,238; one rate 12%: t = 47,534, x = 5,704, CGST 2,852, SGST 2,852; round off −38; net ₹ 532.00. Selling above MRP is impossible (the price is the batch MRP); there is no price change at the counter (OQ-80-9).

### S5 · Held bills, the customer account and udhar, cancellation, returns and credit notes

- **Held bill** (BR-11, OQ-80-13): `held_bill` with the cart as ids (product, quantity, substitution and override choices, buyer as patient id, customer id or the typed walk-in name), the discount request id, the holder, OPEN / RESUMED / REPLACED / DISCARDED, version. No number, no stock, no payment. At most 20 OPEN per store, counted under the store row lock (409 `held-limit`). Resume = read, quote again, save with `heldBillId`.
- **Customer account** (OQ-80-11, G-27): `customer` (WALK_IN with name, phone, allergy note; or PATIENT with `patient_id` only, the name read through the port) carries `credit_limit_paise`, `due_paise`, `credit_note_balance_paise`, version. `customer_account_entry` is append-only (CREDIT_BILL, PAYMENT, CANCELLED_BILL, RETURN_TO_UDHAR, CREDIT_NOTE_ISSUED, CREDIT_NOTE_USED) with the running due and balance after each. Every write locks the customer row first (step 3), so two counters on credit are ordered and the second may get `credit-limit-exceeded`. Over 60 days (owner and pharmacist only) = max(0, due − Σ credit bills of the last 60 IST days): payments are applied oldest first (FIFO, open point O-10). No interest, reminders, write-off or export.
- **Cancel** (BR-12, OQ-80-13): reversing ledger lines `COUNTER_SALE_CANCELLED` (to AVAILABLE when "back on the shelf", else QUARANTINE; an expired batch always to QUARANTINE), reversing H1 and Schedule X rows, the udhar part off the due, the credit-note part back to the balance, `refund_due_paise` for cash, UPI and card. The refund is settled later with `…/refund`, which takes a CREDIT_NOTE number and records `refund` rows (OQ-80-13: "a sales-return style refund, with a credit note"). No approval engine for cancel.
- **Cancel of a bill with a Schedule X line** (0.3.8, DEC-092 items 2 and 6, security M6 and I27). Only a PHARMACIST member cancels it, on the bill's IST day, with a second signer verified by `ScheduleXSignature.verify` outside the transaction and rechecked inside (as the bill save, S3 step 1). The OWNER is refused with 403 `pharmacist-required` (fails closed, as built; S15). The reversal records the people who did it, as the sales-return path already does: the reversing `schedule_x_register_row` rows carry the canceller as first signer and the verified signer as second signer (never the original sale's signers, which the built code copied); the reversing `COUNTER_SALE_CANCELLED` ledger lines of Schedule X lines carry the signer; a `document_event` SECOND_SIGNATURE (bill number, canceller, signer ids) is appended next to BILL_CANCELLED; the audit `PHARMACY_BILL_CANCELLED` names the signer's membership id. Must be fixed before go-live (DEC-092 item 2). Consequence: a bill with a Schedule X line from an earlier IST day cannot be cancelled by anyone (the owner never handles Schedule X; a pharmacist cancels only on the bill's day); its units come back by sales return (open point O-19).
- **Sales return** (BR-26, OQ-80-12): only against the bill and batch; within `returnWindowDays` (not set: no return; over: no override); returnable flag; condition from `return-condition` (Sealed → AVAILABLE; Strip cut or opened, Damaged → "Pharmacist decides": QUARANTINE or refused). Refund of q units of a line = round_half_up(nᵢ × q / qᵢ), and the last return of the line gets the remainder so that a full return refunds nᵢ exactly; the bill's round off is not refunded (mockup 04 "Refund ₹30.70"; open point O-5). Taxable and tax of a return line are worked the same way from tᵢ and xᵢ. Flow (OQ-80-12c): a CASHIER's return, or a doubtful line started by someone who cannot accept it, waits for a pharmacist, HOD or owner (`WAITING_PHARMACIST`); then, or at once for a pharmacist, a refund total above the SALES_RETURN rule's amount goes to the engine (`SALES_RETURN` subject, raiser = the starter, open point O-8); otherwise it completes. Completing: ledger `SALES_RETURN` (reference = credit note number), the CREDIT_NOTE number, refund rows (mode rules of the contract), account entries, register reversals, `returned_qty`. The credit note is printed from the return's GET; its legal layout is G-22.

### S6 · Patient and customer (TP-1, TP-3)

- Pharmacy calls the existing `patient.api` ports as a module, gated by its own `sell` capability, so a CASHIER mapped as Store staff can pick a patient without a clinical role (the requirement's TP-1 check; the architect decides). Search by UHID, mobile or name runs over `PatientLookupPort` (find by id) and a pharmacy-side search on what the port answers; if `patient.api` has no search method usable for this, the java-developer uses the existing patient search port the front desk uses, read-only, and records which in the handoff (no new patient code). The mobile is read only with `mobilesOf` and shown masked.
- Picking a patient, opening a bill, a register page or a queue item of a registered patient writes `PatientAccessPort.recordOpened` (ids only) in the request's transaction.
- **Customer edits** (0.3.8, DEC-092 item 3, security L27): `PHARMACY_CUSTOMER_CHANGED` names every changed field (name, phone, allergyNote, creditLimitPaise) besides the credit limit before and after; never the old or new name, phone or allergy note.
- A port failure answers 503 `patients-unreachable`; the sale continues as a walk-in with `linkPending` (BR-3); "Link to patient" (pharmacist, once) sets the id later. A `linkPending` bill blocks the switch to Standalone (DEC-074 OQ-80-42).

### S7 · Schedule H1 register (DEC-074 OQ-80-17, binding)

- `h1_register_row`, append-only (select and insert to `sahaj_app`; deny triggers on update, delete and truncate), written in the sale transaction, one per H1 bill line: time, store, bill id and number, patient id (if any), **patient name, age and address** (address and age asked only on a bill with an H1 line, NEW, stored only here), prescriber name and registration number, **prescription date**, Rx source (E_RX with the record id, or TYPED), drug supplied, product prescribed when substituted, batch, quantity, pharmacist, override reason (missing prescriber, pharmacist only).
- **Reversing rows**: a cancel or a sales return adds a row of kind REVERSAL with `reverses_row_id`, negative quantity and the reason CANCELLED or RETURNED (the credit note number). The original stays.
- **Access**: OWNER, PHARMACIST member and the store's HOD read it (`readRegisters`); a cashier never. Each page writes `PHARMACY_REGISTER_VIEWED` (ids, filter, row count) and a record-access entry per registered patient on the page; the CSV (OWNER and pharmacists) writes `PHARMACY_REGISTER_EXPORTED`. 0.3.8 (DEC-092 item 3, security L24): a bulk export that carries patient rows (the H1 register export and the `h1-register` report export; a later Schedule X sale-entries export if one is added) also writes one record-access entry per distinct registered patient id among the exported rows, in the export's transaction before any CSV byte is sent; if they cannot be written there is no CSV (record access fails closed). The Schedule X balance export has no patient and writes none. Names never reach the ledger, logs or audit text. Kept until the human sets a period (at least 3 years per the DEC-074 basis; G-11, G-23).

### S8 · Schedule X / NDPS register

- **Sale rows**: `schedule_x_register_row`, append-only like S7, one per Schedule X line of a bill, cancel, sales return, IPD issue and ward return: patient name and age, prescriber, prescription date, drug, batch, quantity, first signer and second signer (`check (second_signer <> first_signer)`), kind ENTRY or REVERSAL. The prescription card is required; there is no override for Schedule X (OQ-80-18).
- **Balance register**: a query, not stored rows: for each product with schedule X or `ndps` true, Opening = Σ ledger AVAILABLE in/out before `from`; Received and Issued = Σ by transaction type in the range (the mapping in the contract); Book = Opening + Received − Issued (equal to the ledger balance at `to`); Physical = the counted quantity of the latest posted SAH-79 stock audit line for that store and product. It reads `stock_ledger` by `(tenant_id, product_id, occurred_at)` (partition pruning) filtered by store. NDPS: catalogue flag for adopted products, the new own-product yes or no (DEC-074 OQ-80-18b). One register titled "Schedule X / NDPS balance register"; the lawyer confirms (G-23).

### S9 · Allergy check (DEC-074 OQ-80-22, binding)

- Integrated and a patient picked: `PatientClinicalPort.find` gives the free text and `AllergyStatus` (RECORDED, NONE_RECORDED, NOT_RECORDED). Match = case-insensitive keyword match of the product's salt and of its generic name against the allergy text: the text is split on commas, semicolons, "and" and line breaks into terms; a term (trimmed, at least 3 letters) that appears as a whole word in the salt or generic name, or the salt's first word that appears as a whole word in the text, blocks the line. No drug classes: a penicillin allergy does not block amoxicillin; the sale screen says "Drug classes are not checked." next to the result (`classesNotChecked`). The none-words of `AllergyStatus` never match. QA tests the keyword rule only.
- Block → the line is refused unless a pharmacist overrides with a chip of `allergy-override` (Prescriber confirmed, Patient has taken it safely before, Allergy entry is wrong, Other with a note). The chip, note and pharmacist are on the bill line; the audit and the activity feed hold the reason key only, never the allergy text or the note.
- The allergy is read again at save. A failed read: "Allergy not checked" on the card and `allergy_not_checked` on the bill; the sale continues (BRD section 6). Standalone: no check; the customer's allergy note is shown "Allergy note (not checked)".

### S10 · Activity feed, history and reason lists

- DEC-074 OQ-80-35: no platform read method. Every sale-side write appends `pharmacy.document_event` (ADR-029 P9) with a structured event type (ALLERGY_OVERRIDE, H1_OVERRIDE, DISCOUNT_REQUESTED, DISCOUNT_DECIDED, STOCK_ADJUSTED from SAH-79, BILL_CANCELLED, SALES_RETURN, SECOND_SIGNATURE), the store, the actor, the document number and the reason key. `GET /activity` and the report `activity-log` build the sentence from those fields and reason labels only. The platform audit (`AuditLog.record`) is still written for compliance (C-8); the feed is the screen's source. The card is titled "Audit log"; "tamper-evident" is not claimed (the table is append-only). 0.3.8 (DEC-092 item 3, security L22): the sentence is built with the blind-count mask of the row's store (S16): for a caller blind there now, a row about an audited product leaves every quantity out ("adjusted <batch> · Breakage"); the same in the `activity-log` report.
- Nine reason lists seeded by the WP-I migration as global editable rows (contract `ListKeyValue`). `return-condition` keys `.sealed`, `.opened`, `.damaged` are fixed (they route stock; `fixed: true`, as `receipt-reject.short`), open point O-13.

### S11 · Reports (80-21)

Six report keys on the SAH-79 framework (ADR-029 P13; same filters, 12-month range, paging, 50,000-row cap, CSV escaping): `sales-margin` (cost and margin to owner and pharmacists only), `sales-returns`, `gst-sales-register` (per bill line and credit-note line: date, number, HSN, GST %, taxable, CGST, SGST, total; totals per rate and per day; no buyer name), `h1-register` (the only export with patient name and address; owner and pharmacists; audited), `schedule-x-register`, `activity-log`. A cashier has no report.

### S12 · Touch-point ports, no-op implementations, and what works in each mode

Each port is an interface **owned by the pharmacy module** in `com.sahaj.pharmacy.integration`, with a default `NoOp…` bean (`connected() = false`). A real implementation is one adapter bean in pharmacy that calls a new interface in the other module's `api` package (the dependency direction of ADR-028 D1: pharmacy → `opd.api` / `ipd.api`). **Each real implementation, and the other module's interface it needs, is for the human and the IPD session to approve** (DEC-072 Q2); until then nothing in `com.sahaj.opd`, `com.sahaj.ipd` or `com.sahaj.billing` is touched.

```java
package com.sahaj.pharmacy.integration;

/** TP-2 (80-11, 80-12): signed OPD prescriptions, pulled at read time. */
public interface OpdPrescriptionSource {
    boolean connected();
    /** Signed records of the day that have a prescription section, oldest first. Caller's transaction (RLS bound). Never logged. */
    List<RxHeader> signedOn(UUID tenantId, LocalDate istDay);
    Optional<Rx> read(UUID tenantId, UUID recordId);
    record RxHeader(UUID recordId, UUID patientId, UUID prescriberMembershipId, String doctorName, Instant signedAt, int lineCount) {}
    record Rx(RxHeader header, List<RxLine> lines) {}
    record RxLine(String lineKey, UUID medicineId, String medicineText, String dosePattern, String duration, String timing, String instructions) {
        @Override public String toString() { return "RxLine[lineKey=" + lineKey + "]"; }
    }
}

/** TP-4 (80-13, 80-14, 80-17 read): IPD medicine orders verified by the IPD pharmacist check, and discharges started. */
public interface IpdOrderSource {
    boolean connected();
    boolean ipdUsable(UUID tenantId);                     // IPD on and not read-only
    List<OrderRow> openOrders(UUID tenantId);             // ACTIVE, STOPPED since the last supply, ON_HOLD
    List<OrderRow> ordersOf(UUID tenantId, UUID admissionId);
    Map<UUID, Integer> currentVersions(UUID tenantId, Collection<UUID> orderIds);
    List<Discharge> dischargesStarted(UUID tenantId);     // empty when the feed does not report them (TP-7 information only)
    record OrderRow(UUID orderId, int version, UUID admissionId, String admissionNo, UUID patientId, UUID wardId, String wardName,
                    String bedLabel, UUID drugId, String drugText, String orderText, String priority, String status,
                    String pharmacistCheck, String verifiedBy, Instant orderedAt, String prescriberName) {
        @Override public String toString() { return "OrderRow[orderId=" + orderId + ", version=" + version + "]"; }
    }
    record Discharge(UUID admissionId, Instant startedAt, Instant expectedAt) {}
}

/** TP-5 (80-14, 80-15, 80-16) and TP-6 (patient-wise consumption, SAH-79-16): charges and credits on the IPD running bill.
 *  Called inside the pharmacy transaction, so stock and charge commit together. Idempotent by sourceKey. */
public interface IpdChargePort {
    boolean connected();
    Outcome charge(UUID tenantId, ChargeLine line);
    Outcome credit(UUID tenantId, ChargeLine line);       // never above charged minus credited for that source line
    record ChargeLine(String sourceKey, UUID admissionId, UUID productId, String productName, int quantity, long amountPaise) {}
    enum Result { POSTED, BILL_CLOSED, ADMISSION_NOT_FOUND }
    record Outcome(Result result, String chargeRef) {}
}
```

TP-6 also uses SAH-79's `AdmittedPatientsReader` (ADR-029 P14) unchanged; the consumption charge is `IpdChargePort.charge` with `sourceKey = "consumption:<lineId>"` and the OQ-80-15 price.

**What the real implementations need from the other modules** (each a separate human approval):

| Port | Needs in the other module | Changes built behaviour? |
|---|---|---|
| TP-2 `OpdPrescriptionSource` | New read-only `opd.api.PrescriptionQueueReader` implemented by OPD over signed EMR records with a prescription section (medicine id or text, dose pattern, duration, timing, instructions, prescriber, patient, signed time). No write-back ("dispensed" is pharmacy's own state, OQ-80-29). | No (a new read port) |
| TP-4 `IpdOrderSource` | New read-only `ipd.api.PharmacyOrderFeed` over `ipd.medicine_order` and versions with priority, status, the IPD pharmacist-check state and who verified; discharges started (TP-7 information only). | No (a new read port) |
| TP-5 `IpdChargePort` | New `ipd.api.PharmacyChargePort` implemented by IPD, running in the caller's transaction; an IPD-owned migration adding a pharmacy service key to the closed list of `ipd.admission_charge`, a credit (negative) line kind, a quantity range above 1 to 50, an idempotency key; running-bill and final-bill totals include the lines; returns BILL_CLOSED after the final bill. The medication chart is not changed (OQ-80-31b). **HIGH risk** (an IPD money table). | **Yes** |
| TP-6 | `ipd.api.AdmittedPatients` (ADR-029 P14) plus the TP-5 port. | Yes (through TP-5) |
| TP-7 gate, TP-8, TP-9 | Not built: TP-7 stays information only (OQ-80-33), TP-8 is WP-O (OQ-80-16), TP-9 replaced by S10. | — |

**What works in each mode** (with every port unconnected):

| Feature | Standalone | Integrated, ports not connected | Integrated, ports connected |
|---|---|---|---|
| POS, held bills, udhar, cancel, discount approval, substitution, H1 and X registers, sales return, licences, role read-out, activity, command centre (non-Integrated cards), reports, day close | Works | Works | Works |
| Patient pick (TP-1), allergy check (TP-3) | Walk-in or customer; allergy note shown only | Works (existing `patient.api`) | Works |
| OPD queue, e-Rx billing (TP-2) | Hidden (409 `integration-off`); typed Rx on the sale screen | Tab shows "Not connected"; typed Rx works | Works |
| IPD queue, dispense to ward (TP-4) | Hidden | Tab shows "Not connected"; no IPD issue can be made (orders come only from the port) | Works |
| IPD charge, ward-return credit, patient-wise consumption charge (TP-5, TP-6) | Hidden | Issues and accepted returns save "Charge pending" / "Credit pending"; "Post waiting charges" answers 409 `not-connected` | Posted in the same transaction; "Post waiting charges" posts the waiting ones once each |
| Discharge lane (TP-7) | Hidden | Hidden | Shown when the feed reports discharges |
| Add to OPD bill (TP-8) | Not built | Not built | Not built |

The Integration-mode guard (pharmacy.yaml `pharmacyPutUnitSettings`) refuses Standalone while Charge pending, Credit pending, Link to patient pending or Part supplied items exist (DEC-074 OQ-80-42).

### S13 · Performance (C-10, BR-37)

- **Search under 300 ms**: one query over the unit's ACTIVE products with a generated lower-case search text (name, salt, alias, code) and an exact barcode index, joined to `stock_balance` by `(tenant_id, store_id, product_id, state)`; at most 20 rows. A unit holds thousands of products, so a filtered scan fits; if EXPLAIN on the QA data set misses the target, a trigram index needs `pg_trgm`, a new extension, which is the human's decision (open point O-18).
- **Save under 1 s**: one transaction, the locks of S3, batched inserts, the number taken last, the PIN check outside the transaction. QA measures both on the seeded data set.
- Command centre: queries per card over `bill (tenant_id, store_id, saved_at)`, `sales_return (tenant_id, store_id, completed_at)`, the balance and batch indexes of SAH-79, each card isolated.

### S14 · Discount approval, day close, OPD and IPD dispense state

- **Discount** (OQ-80-8): `SALE_DISCOUNT` engine request (ADR-029 P6; subject `SALE_DISCOUNT`, rule store = the selling store, ABOVE_PERCENT on the percent, amount = the discount). A `sale_discount_request` row keeps the cart digest, percent and held bill; a changed cart or higher percent makes it VOID (computed), and the save needs an APPROVED request whose digest matches. The inbox row carries the direct APPROVE action (BR-38). Withdrawal on discard or a new request (ADR-029 P6 WITHDRAWN).
- **Discount approval validity** (0.3.8, DEC-091 item 2, DEC-092 item 1; code review Major 2, security M5). Before 0.3.8 an APPROVED request was never consumed or expired, so one approval could discount every later bill with the same lines. An APPROVED request lifts the approval need only when all of these hold, checked by one shared component (`DiscountApprovals`) for the quote, the bill save and the seller's read:
  1. **Single use**: no `bill` row, SAVED or CANCELLED, carries the request (a cancelled bill does not give it back).
  2. **Bound to the raising seller**: the caller is the `approval_request` raiser. A pharmacist who resumes a cashier's held cart sends a new request.
  3. **Bound to the held bill**: a request raised with a held bill is good only for a save whose `heldBillId` is that held bill; a request raised on a live cart is good for a save with no `heldBillId`, or with the held bill that carries this request (the cart was held after the request). Checked at save only (the quote takes no held bill).
  4. **Same cart**: digest and percent as before.
  5. **Same IST day**: the IST date of the approval time equals today's IST date. The approval time is `max(approval_decision.decided_at)` over the request's APPROVED decision rows (the last level's approval; `decided_at` is `clock_timestamp()`, ADR-029). No scheduler: the age is computed when read.
  Failing any of them reads VOID (the existing computed status): the quote answers `discount.approval.status` VOID and keeps the DISCOUNT_APPROVAL blocker; the save answers 409 `discount-approval-void`, nothing saved; the seller's read answers VOID once used, out of its day, or for another seller. Backstop: a unique partial index on `bill (tenant_id, discount_request_id) where discount_request_id is not null` (db-engineer, a new forward migration); its violation, from two saves racing under the `FOR SHARE` lock of S3, maps to the same 409. The OWNER exemption (DEC-090) is unchanged: an owner's bill carries no request.
- **Day close** (OQ-80-14, NEW screen): per store and person, append-only `day_close`; expected from `bill_payment`, `customer_account_entry` (payments) and `refund` rows of that person in the period; the pharmacy drawer is separate from billing's (G-28).
- **OPD dispense state** (OQ-80-24, OQ-80-29): `rx_dispense` (per store and OPD record: token of the IST day, status, claim, version) and `rx_dispense_line` (typed quantity, picked, supplied, remaining, closed reason). The queue read creates the row and token for a newly seen record (the only write of that read). **IPD**: `ipd_order_supply` (per order and version: picked, typed and supplied quantity) is the row the dispense locks, so two pharmacists cannot dispense the same order version (409 `changed`).

### S15 · The owner and Schedule X: reading of DEC-074 OQ-80-37 (0.3.8, DEC-092 item 6)

DEC-074 OQ-80-37 says the owner "may bill non-scheduled items and cancel any bill, never override an allergy or H1 check, and never sell Schedule X". Security I27 found that the built code refuses the owner on a Schedule X cancel, against the words "any bill". DEC-092 keeps the code (it fails closed) and corrects the wording: **the owner never handles Schedule X**, so "cancel any bill" means **any bill without a Schedule X line, on any day**. A bill with a Schedule X line is cancelled only by a PHARMACIST member with a second signer (S5). Contract rule 25 and `pharmacyCancelBill` state this. The SAH-78 handoff note that took `COUNTER_SALE_CANCELLED` off the Schedule X signer trigger list ("an owner may cancel any bill") is no longer needed for that reason; whether to put it back is open point O-19 (the reversal now carries a signer, S5).

### S16 · Blind count on sale-side reads (0.3.8, DEC-091 item 3, DEC-092 item 3)

Contract rule 32 (pharmacy-sales.yaml) and rule 24 (pharmacy.yaml) apply the SAH-79 blind count (ADR-029 P12) to sale-side reads. Review round 7 found four leaks by comparison or by count. Decisions:
- **Who is blind**: `readStock` and not `reviewCount`, never the OWNER (was `count` and not `reviewCount`; identical for the SAH-79 roles, and a CASHIER, which has no `count` after S2, stays blind).
- **FEFO pick** (`pharmacyFefoPick`) and **quote** (`pharmacyQuoteSale`): the number of batches FEFO takes for a quantity tells how many units the first batch holds. For a blind caller on an audited product, the pick names only the first batch with no quantity; the quote answers each cart line of that product as one line naming only the first batch, with the quantity asked, no stock numbers, and the money summed over the real pick so the bill total is right. A SHORT line keeps its state and is priced at the first batch's MRP. `quoteVersion` still digests the real pick, so the save compares like with like; the saved bill (the caller's own) shows its real batch lines. Residual, accepted: when the batches carry different MRPs, the line amount differs from quantity × the shown MRP; and the SHORT state itself (kept by rule 32 since 0.3.1 so a counter can sell) can be probed by changing the quantity. Both are left to the human with O-20.
- **Statuses**: `StoreStateRow.status` is null and the overview `lowStock` pill leaves out audited products (they were worked from hidden quantities). `statusChip` and expiry stay.
- **Activity feed and `activity-log` report**: quantities left out of rows about audited products (S10).
- **Command Center**: the reorder card and the Schedule X / NDPS review count leave out audited products of a store where the caller is blind; batch counts stay.
- Unchanged by the decisions and left as open point O-20: the low-stock list (`pharmacyListLowStock`, which still lists an audited product at or below its level) and `pharmacyReorderSuggestions`. They give the same one bit the pill and the reorder card no longer give, and the pill count can now be lower than the list length for a blind caller.

---

## Options considered

| Point | Options | Chosen and why |
|---|---|---|
| Contract file | (a) more of `pharmacy.yaml`; (b) a sibling file referencing its components | (b): see S1; no generator, no copy, one API |
| Bill number | (a) at save; (b) at the start of a sale (mockup) | (a): (b) leaves gaps in a GST series (DEC-074 OQ-80-7a, approved deviation) |
| Number lock position | (a) P5 step 4 (before balances); (b) last, before the inserts | (b): shortest hold of the one row every sale of a series waits on; no cycle (S3) |
| Per-store series | (a) a store column on `document_series` / `document_counter`; (b) new store tables and function | (b): additive, the SAH-78 primary keys and the counter function stay untouched |
| GST | (a) tax per line, summed per rate; (b) tax on the rate group total | (a): returns and the register reconcile line by line; paisa differences are the accountant's (O-2) |
| Schedule X balance | (a) a query over the ledger and audits; (b) stored balance rows | (a): one source of truth, no drift; sale rows are stored because they carry names and signers |
| Activity feed | (a) `platform.api` audit read; (b) pharmacy `document_event` | (b): DEC-074 OQ-80-35 |
| Touch points | (a) code in OPD, IPD, billing now; (b) pharmacy-owned ports with no-op beans | (b): DEC-072 Q2 |
| Cashier patient read | (a) give STORE_STAFF a patient role; (b) pharmacy reads through `patient.api` under its `sell` capability | (b): no platform role change; every read is logged in the patient access log |
| Discount approval life (0.3.8) | (a) as built: reusable while the cart matches; (b) single use only; (c) single use, bound to seller and held bill, valid for the IST day of approval; (d) a fixed time window (for example 30 minutes) | (c): DEC-091 item 2 and DEC-092 item 1. (d) needs a time setting nobody has decided and the mockup does not show; the IST day matches how bills, day close and the cancel rule already count days |
| Single-use enforcement (0.3.8) | (a) take the request row `FOR UPDATE`; (b) keep `FOR SHARE` plus a unique partial index on `bill.discount_request_id` | (b): no change to the lock order of S3 or to the engine's locks; the index is the backstop DEC-091 names and turns a race into a clean 409 |
| Blind quote (0.3.8) | (a) all FEFO batches without quantities; (b) only the first batch | (b): DEC-091 item 3; (a) still shows how many batches a quantity takes |

---

## Additive-only changes to existing files (DEC-061 Answer 2)

| # | Existing file or object | Change | Owner | Needs |
|---|---|---|---|---|
| 1 | `docs/api/pharmacy.yaml` 0.2.1 → 0.3.0 | TP-10 touch-ups on existing components (S1) | solution-architect | Contract approval |
| 2 | `docs/api/pharmacy-sales.yaml` (new) | 70 operations | solution-architect | Contract approval |
| 3 | `pharmacy.store_staff` role check; `pharmacy.unit` (4 columns); `pharmacy.product` (`ndps` on own products, if not already a column); `document_series` doc type check; `reason_default` / `reason` list checks; `approval_request` subject type check; `consumption` (charge columns) | Wider checks and nullable columns | db-engineer | Contract approval |
| 4 | SAH-78 and SAH-79 services: `StoreCapabilities`, `StoreAccessGuard`, `ContextService`, unit settings (guard), staff mapping (CASHIER rule), series and reasons services, `ApprovalEngine` handlers, report catalogue | Additive code in `com.sahaj.pharmacy` | java-developer | Contract approval |
| 5 | `OpdPrescriptionSource`, `IpdOrderSource`, `IpdChargePort` real adapters and the `opd.api` / `ipd.api` interfaces they need | **Not built.** For the human and the IPD session | — | Separate approvals (S12) |

Not touched: platform, patient (read through existing ports), OPD, IPD, billing, HRA.

---

## Data needed (for the db-engineer)

Conventions of ADR-028 and ADR-029 "Data needed" apply to every table (uuid pk, `tenant_id` default `platform.current_tenant()`, RLS `ENABLE` + `FORCE`, `unit_id` with a same-tenant FK, `tenant_id` first in every index, no delete grant, `created_*`, `version` on changeable rows, paise `bigint`, rates `numeric(14,4)`, percentages `numeric(5,2)`, quantities `integer`, membership ids opaque, no cross-module FK). Append-only tables: select and insert only, deny triggers on update, delete and truncate.

### Sequencing note: migrations and tables per work package

SAH-80 migrations start **after SAH-79 WP-D to WP-H** (`13xx` to `17xx`) and use **`V2026_12_17_1800` and up**, in build order: **`18xx` WP-I, `19xx` WP-J, `20xx` WP-L, `21xx` WP-K, `22xx` WP-M, `23xx` WP-N**. If another session has taken numbers in that range, the tech lead settles the order first. Forward migrations only.

**WP-I (80-1 to 80-5). Depends on SAH-79 WP-D** (`batch`, `stock_balance`, `stock_ledger`, `client_request`, `document_event`, engine) **and SAH-78.**
- Touch-ups: `store_staff.store_role` check + `CASHIER`; `unit.return_window_days int null check (between 1 and 365)`, `unit.refund_modes text[] not null default '{}'` (check: subset of the four values), `unit.schedule_x_licence_no varchar(40)`, `unit.schedule_x_licence_valid_till date`; `product.ndps` writable for own products; `document_series.doc_type` check + `CREDIT_NOTE`, `IPD_ISSUE_SLIP`; `reason_default` and `reason` list checks + the nine lists and their seed rows (`return-condition` keys fixed); `stock_ledger.txn_type` check + the six sale values.
- `store_bill_series` (unique `(tenant_id, store_id)`), `store_bill_counter` (pk `(tenant_id, unit_id, store_id, fiscal_year)`, `next_value`, `last_issued_at`; guard trigger as `document_counter`), function `pharmacy.next_store_bill_no(store_id)` (SECURITY INVOKER, same contract as `next_document_no`).
- `customer` (kind, `patient_id` unique per unit where not null, `name varchar(80)`, `phone char(10)`, `allergy_note varchar(200)`, `credit_limit_paise`, `due_paise >= 0`, `credit_note_balance_paise >= 0`, status, version; indexes `(tenant_id, unit_id, phone)`, `(tenant_id, unit_id, lower(name))`); `customer_account_entry` (append-only; kind, amount, `due_after >= 0`, `cn_balance_after >= 0`, bill, return, mode, store, actor, at; index `(tenant_id, customer_id, at desc)`, `(tenant_id, store_id, actor_membership_id, at)`).
- `bill` (store, `bill_no`, series kind and fiscal year, status SAVED / CANCELLED, buyer kind, `patient_id`, `customer_id`, `walk_in_name`, `walk_in_phone`, `link_pending`, `allergy_not_checked`, `rx_record_id`, prescription fields, discount percent and paise, discount request id, totals of S4, `udhar_paise`, `saved_by`, `pharmacist_membership_id`, cancel fields, `refund_due_paise`, `refund_credit_note_no`, version; unique `(tenant_id, unit_id, lower(bill_no))`; index `(tenant_id, store_id, saved_at desc)`); `bill_line` (append-only except `returned_qty` through a guarded update: the db-engineer chooses a guard trigger allowing only `returned_qty` to grow up to `qty`; product, batch, qty, MRP, GST rate, HSN, a, d, n, t, x of S4, prescribed product, substitution key and note, `patient_agreed`, override keys and notes, decided by, `actor_membership_id`, `second_signer_membership_id` with `check (second_signer_membership_id <> actor_membership_id)`, `rx_line_key`); `bill_payment` (append-only; unique `(tenant_id, bill_id, mode)`); `held_bill` (cart `jsonb` of ids and the typed walk-in name, status, holder, discount request id, held net, version; index `(tenant_id, store_id, status)`); `refund` (append-only; source CANCELLED_BILL or SALES_RETURN, mode, amount, credit note number, store, actor, at).

**WP-J (80-6 to 80-10). Depends on WP-I.**
- `approval_request.subject_type` check + `SALE_DISCOUNT`, `SALES_RETURN`; `sale_discount_request` (request id, store, cart digest, percent, discount paise, held bill id).
- `h1_register_row` (S7; append-only; patient name, age, address; index `(tenant_id, store_id, occurred_at desc)`, `(tenant_id, product_id, occurred_at)`); `schedule_x_register_row` (S8; append-only; signer check).
- No table for the balance register (a query, S8).

**WP-L (80-18, 80-19). Depends on WP-I and WP-J.**
- `sales_return` (store, bill, status, credit note number, reason, note, started, accepted, completed by and at, approval request id, refund total, version; partial unique `(tenant_id, credit_note_no) where credit_note_no is not null`); `sales_return_line` (bill line, qty, condition key, decision, destination, refund, t, x, second signer).
- `pharmacist_registration` (unique `(tenant_id, unit_id, membership_id)`, `registration_no varchar(40)`, `valid_till date`, version).
- `document_event.event` values widened if it has a check.

**WP-K (80-11, 80-12). Depends on WP-I and WP-J.**
- `rx_dispense` (store, OPD record id, `token_day date`, `token int`, status, claimed by and at, patient id, version; unique `(tenant_id, store_id, rx_record_id)`, unique `(tenant_id, store_id, token_day, token)`); `rx_dispense_line` (line key, source item id, qty, picked by and at, supplied, remaining, closed reason).
- `product_link` (unique `(tenant_id, unit_id, source_kind, source_item_id)`, product, version) and `product_link_history` (append-only).

**WP-M (80-13 to 80-17). Depends on WP-I, WP-J, WP-L and SAH-79 WP-G.**
- `ipd_order_supply` (store, admission id, order id, order version, typed qty, picked by and at, supplied qty; unique `(tenant_id, store_id, order_id, order_version)`); `ipd_issue` (slip number, store, admission id, admission number, patient name snapshot for the slip, ward and bed text, take-home, sent by and at); `ipd_issue_line` (order id and version, product, batch, qty, price `numeric(14,4)`, amount, `charge_status`, `charge_ref`, `charge_failure`, second signer, `returned_qty`; index `(tenant_id, charge_status) where charge_status = 'CHARGE_PENDING'`); `ward_return` (issue line, qty, reason, condition, status, credit paise, `credit_status`, `credit_ref`, recorded and decided by, version); `discharge_clearance` (unique `(tenant_id, admission_id)`, cleared by and at, reason).
- SAH-79 `consumption` (or its line): `charge_ref`, `charged_at`, `charge_failure` (nullable, additive).

**WP-N (80-20 to 80-22). Depends on WP-I to WP-L and SAH-79 WP-F and WP-H.**
- `day_close` (append-only; store, person, closed by, period from and to, expected, counted, difference per mode, reasons; unique `(tenant_id, store_id, person_membership_id, period_to)`).
- Report and command-centre indexes only after EXPLAIN on realistic data.

**0.3.8 (review round 7; a new forward migration, additive, after the SAH-80 ones already written; the db-engineer picks the next free number).**
- `bill`: unique partial index `(tenant_id, discount_request_id) where discount_request_id is not null` (single use of a discount approval, S14; SAVED and CANCELLED bills both count). Before creating it, the migration checks that no two existing bills share a request id (dev and test data only; nothing is live) and fails with a clear message if they do.
- `approval_decision`: the approval time of S14 is `max(decided_at)` of a request's APPROVED rows; confirm an index starting `(tenant_id, request_id)` exists (ADR-029 D4 tables); add none if it does.
- No column is added for the canceller and signer of a Schedule X cancel: `schedule_x_register_row` already has first and second signer, and the ledger line already has a signer column (S5). Optional, open point O-19: put `COUNTER_SALE_CANCELLED` back on the Schedule X signer trigger list now that the owner never cancels a Schedule X bill.

**Tenant scope and retention.** Every table is tenant-scoped (RLS); reason seed rows stay global. Patient data lives only in `customer`, `bill` (walk-in name and phone, patient id), `held_bill` (cart), `h1_register_row`, `schedule_x_register_row` and `ipd_issue` (name snapshot): tenant isolated, never logged, never in the ledger or audit text. Nothing is deleted until the human sets legal periods (G-11, G-29).

---

## Build order (java-developer and angular-developer)

Sequential by package (DEC-074 OQ-80-41), Java and Angular in parallel inside each; each package after its migration.
1. **WP-I** · Java: capabilities and CASHIER rule, unit fields and the mode guard, store bill series and `next_store_bill_no`, customers and the account, product search, quote (S4 maths with the worked example as a unit test), `BillService.save` (S3, through `StockPosting`), held bills, cancel and refund, link to patient, `NoOp` ports registered. Angular: Pharmacy tab shell, POS screen (F2, F4, F8, F9), patient and customer card, split payment and udhar, held bills, today's bills and cancel, setup touch-ups (Cashier, bill series per store, unit sale fields).
2. **WP-J** · discount request and inbox handler with APPROVE, substitutes (F6), allergy override, H1 and Schedule X capture at save and both registers, the "Registers & compliance" shell.
3. **WP-L** · sales return (find, lines, refund, accept, finish, credit note), licences and pharmacist registration, role read-out, activity feed.
4. **WP-K** · OPD queue against `OpdPrescriptionSource` (no-op: "Not connected"; a fake bean in tests), pick, claim, part supply, product links.
5. **WP-M** · IPD queue, pick list, dispense, ward returns, charges waiting and posting, discharges, all against the no-op and fake ports.
6. **WP-N** · command centre, the six report keys, day close.

Java rules: every stock change through `StockPosting`; the PIN check outside the transaction; the number taken last (S3); no OPD, IPD or billing class touched; ports only from `com.sahaj.pharmacy.integration`; logs with ids and counts only. Tests: a fake connected bean for each port proves the Integrated path; the no-op proves Standalone and "Not connected".

---

## Open points (OPEN, each with the recommended answer; not decided by DEC-074)

| ID | Point | Recommended answer (used by the contract) | Who |
|---|---|---|---|
| O-1 | Default prefix of the IPD issue slip series | "IS-", 4 digits, no financial-year part, no restart | Tech lead; accountant (G-22) |
| O-2 | GST per line then summed per rate, or on the rate group total (a paisa may differ) | Per line, summed per rate (S4) | Accountant (G-22) |
| O-3 | Does the udhar "Payment received" slip take a number (OQ-80-7c had no answer) | No number; the slip shows date, time, customer, amount, mode, person and the new due | Accountant (G-27) |
| O-4 | Spreading the bill discount over lines | Proportional, largest remainder, so the line discounts add up exactly | Accountant (G-22) |
| O-5 | Rounding of a refund and the credit note | To the paisa, no round off; the bill's round off is not refunded (mockup 04 "₹30.70") | Accountant (G-22) |
| O-6 | A stuck OPD claim | The claimant, or anyone after 30 minutes, may take it over | Tech lead |
| O-7 | Reason list for a ward return "Not accepted" (none named) | `approval-reject` | Tech lead |
| O-8 | A cashier's return above the ₹ limit: goods check and approval | Pharmacist accepts the goods first; then the engine with the starter as raiser | Tech lead; owner |
| O-9 | Owner and IPD dispensing: BR-4 "owner for non-scheduled" vs stories 80-13 and 80-14 "owner looks, does not dispense" | Stories: owner reads only | Human |
| O-10 | Udhar ageing "over 60 days" | Payments applied oldest first (FIFO) | Accountant (G-27) |
| O-11 | A bill with no name at all (buyer `NONE`) for a non-scheduled cash sale; 80-2 makes the walk-in name required | Allowed for non-scheduled bills without udhar, labelled "Walk-in"; the human may require a name | Human |
| O-13 | `return-condition` keys fixed | Fixed (they route stock) | Tech lead |
| O-15 | H1 patient address for a registered patient (the patient port has no address) | Typed on the card for every H1 bill | Human; lawyer (G-23) |
| O-18 | 300 ms search if a plain scan is too slow | Ask the human for `pg_trgm` (a new extension) | Human |
| O-19 | (0.3.8) A bill with a Schedule X line from an earlier IST day cannot be cancelled by anyone (owner never handles Schedule X; a pharmacist cancels only on the bill's day). Also: put `COUNTER_SALE_CANCELLED` back on the Schedule X signer trigger list? | Accept: the sales return is the way back for those units. Trigger: yes, once the java fix of S5 sets the signer on those lines (db-engineer, forward migration) | Human; tech lead |
| O-20 | (0.3.8) Blind count residuals not covered by DEC-091: the low-stock list and reorder suggestions still list an audited product at or below its level (the pill and the reorder card no longer count it); the quote's SHORT state and a mixed-MRP line amount can still be probed | Leave audited products out of the low-stock list and reorder suggestions for a blind caller, as the pill; accept the SHORT state (a counter must know it can sell) and the mixed-MRP amount | Human; tech lead |

Deviations recorded in the contract: the sibling file (S1); `series-locked` (DEC-078) replaces the story's `series-in-use`; IPD dispense by owner per the stories (O-9); `NONE` buyer (O-11); 503 problems `patients-unreachable`, `prescriptions-unreachable`, `orders-unreachable` for port failures.

---

## Go-live checklist (0.3.8 additions; DEC-091 header, DEC-092 items 5 and 6)

These are setup and legal steps, not code. They add to G-29 and G-30 of `docs/requirements/SAH-80.md` (the business-analyst may copy them there). Nothing below is cleared for real selling until each is done.

| # | Item | Why | Who |
|---|---|---|---|
| GL-1 | The owner sets a `SALE_DISCOUNT` approval rule with a percent limit (Inventory setup, approval rules) **before the first sale** of each unit | With no rule any seller may give any discount with no approval (DEC-092 item 5, security L26); no default is seeded because the limit is the owner's pricing decision. The role read-out shows "No limit set" until it is done | Owner; accountant |
| GL-2 | A DPDP consent notice is shown and recorded before a walk-in buyer is saved as a customer ("Save to customers": name, phone, allergy note) | Personal data kept beyond the bill (DEC-092 item 6, security I30; G-29). Wording, where it is shown and how consent is kept are for the lawyer and the BA; the mockup has no such text yet, so it goes to the human before any screen change | Lawyer; human; business-analyst |
| GL-3 | The owner ticks the unit's refund modes (Inventory setup, unit sale fields) | With none ticked, every refund of a cancelled bill or a sales return is refused and bills stay "Refund due" (contract 0.3.7, DEC-088 item 3; G-30) | Owner |
| GL-4 | The Schedule X cancel fix of S5 (canceller and signer on the reversal, SECOND_SIGNATURE event) is built and tested | DEC-092 item 2: must be fixed before go-live | java-developer; qa-engineer |

## Consequences

**Positive**
- One sale transaction with a gapless, short-held number; stock, register, money and audit commit together or not at all.
- Every fixed rule has a database backstop: `qty >= 0`, append-only ledger and registers, `second_signer <> actor`, `decided_by <> raised_by`.
- Standalone mode is complete with every port unconnected; each Integrated feature switches on with one adapter bean after its approval, with no contract change.
- No platform, patient, OPD, IPD or billing change in SAH-80.

**Negative / accepted costs**
- Two contract files to keep in step (both 0.3.0).
- The number-last rule is an exception to ADR-029 P5 that every sale-side transaction must follow (a convention test: BILL and CREDIT_NOTE counters are taken only after `StockPosting.lockBalances`).
- Queue reads may write (token and dispense rows), as the P4 sweep does.
- Patient data in the pharmacy schema grows (customers, registers); retention waits for the human (G-29).
- Many tax and legal points stay go-live checks (G-22 to G-30, GL-1 to GL-4) and open points O-1 to O-20.
- 0.3.8: a discount approval is good for one bill, one seller, one held bill and one IST day; a seller who resumes another person's held cart, or sells after midnight IST, asks again. A Schedule X bill from an earlier day cannot be cancelled (O-19). Blind callers see less on the quote and stock screens (S16).
