# DEC-079 · SAH-80 contract and ADR-030: open points decided by the tech lead

Date: 19 Nov 2026 · Story: SAH-80 (Phase 3 Pharmacy) · Authority: DEC-073 (standing delegation), DEC-072 Q2, DEC-074
Status: DECIDED (auto). Billing, GST, legal and clinical items remain go-live checks (G-22 to G-30).

## What was delivered
- docs/api/pharmacy.yaml 0.2.1 (DEC-076 to DEC-078 wording and additions) and then 0.3.0 (the SAH-80 touch-ups on existing components: the CASHIER store role, 13 sale capabilities, the new document types and reason lists, return window and refund modes on the unit, `ndps` on own products, and more).
- docs/api/pharmacy-sales.yaml 0.3.0 (new): 70 operations in WP-I to WP-N, sharing the components of pharmacy.yaml. A sibling file because pharmacy.yaml would pass 10,000 lines.
- ADR-030 (PROPOSED) and ADR-028 Revision 7.
- The SAH-80 contract and ADR-030 are treated as approved on the strength of DEC-073 (chat), and `docs/approvals.md` has no line for them (the hooks run merge-only, DEC-001). The human confirms.

## Decisions on the architect's open points
- **O-1:** the IPD issue slip series defaults to "IS-", 4 digits, no financial-year part.
- **O-2:** GST is worked per line and summed per rate (accountant, G-22).
- **O-3:** the udhar payment slip has no number (it records money received, not a supply). Go-live check for the accountant.
- **O-4:** a bill-level discount is spread over the lines by the largest remainder, so the lines add up to the paisa.
- **O-5:** refunds are to the paisa, and the bill's round-off is not refunded (accountant).
- **O-6:** a stuck OPD claim can be taken over after 30 minutes.
- **O-7:** a ward return that is not accepted uses the `approval-reject` list.
- **O-8:** a cashier's return above the limit: the pharmacist accepts the goods first, then the approval engine runs with the starter as the raiser.
- **O-9:** the owner reads IPD dispensing and does not dispense. IPD dispensing is a pharmacist job. This follows stories 80-13 and 80-14.
- **O-10:** udhar ageing applies payments oldest first.
- **O-11:** a bill without a buyer name is allowed for a non-scheduled sale only. A bill with a Schedule H, H1 or X line needs a named buyer.
- **O-13:** the `return-condition` reason keys are fixed and cannot be switched off.
- **O-15:** the H1 address is typed, even for a registered patient, because the patient port has no address. Lawyer check G-23.
- **O-18:** no new extension is needed. `pg_trgm` is already installed and used (patient search and the SAH-78 catalogue). If POS search misses the 300 ms target, add a trigram index in a migration.

## The architect's deviations: accepted
1. A sibling contract file (ADR-030 S1).
2. `series-locked`, not `series-in-use` (DEC-078 is the later decision).
3. The bill number is taken last in the sale transaction. The BILL and CN counters are taken only by sale transactions, so no deadlock cycle is possible. A convention test is added.
4. The owner does not dispense to IPD (O-9).
5. Buyer `NONE` for an anonymous non-scheduled sale (O-11).
6. New 503 problems for unreachable ports: `patients-unreachable`, `prescriptions-unreachable`, `orders-unreachable`.
7. The two vendor credit levels are in the contract now (0.2.1), and the columns come with the WP-E migration.
8. At submit, an opening-stock line whose product is no longer ACTIVE is an ERROR `product-unknown`. A null damaged or expired credit level falls back to the standard credit.

## Ports (DEC-072 Q2): unchanged
`OpdPrescriptionSource` (TP-2), `IpdOrderSource` (TP-4), `IpdChargePort` (TP-5 and TP-6, with SAH-79's `AdmittedPatientsReader`) are pharmacy-owned interfaces with no-op beans. No code goes into the OPD, IPD or billing modules. Each real adapter needs the human and the IPD session to approve it first (TP-5 is HIGH risk). Standalone mode is complete. In Integrated mode with the ports unconnected the queues answer "Not connected", and issues and returns are saved as Charge pending or Credit pending.

## Work
- **DB-engineer (lock frees about 00:51), in this order:**
  1. A forward migration in 1270 to 1299: `line_rows_written`, DRAFT to REJECTED, the active-store insert trigger, `document_counter.last_issued_at` for series locking, and a counter that never goes down (DEC-077, DEC-078, ADR-028 R7).
  2. SAH-79 WP-E in 14xx, with the vendor credit columns, then WP-F in 15xx, WP-G in 16xx and WP-H in 17xx (ADR-029).
  3. SAH-80 tables from 18xx (ADR-030 "Data needed") only after SAH-79 WP-D to WP-H are built.
- Java and angular: the hardening round, then SAH-79 WP-D (nothing else of SAH-79 starts before WP-D is reviewed), then the later packages.

## Blocked
Nothing.
