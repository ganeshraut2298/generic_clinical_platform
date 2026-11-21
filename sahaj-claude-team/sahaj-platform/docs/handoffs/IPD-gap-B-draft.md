# IPD flow gap analysis B (draft from BA #2, not yet written to docs/requirements: requirements lock held by a finished BA)

Date: 21 Nov 2026 · Scope: IPD money and supply flows. All proposals PROPOSED.

## 1. The pharmacy link is not connected on either side
Pharmacy has NoOpIpdOrderSource (TP-4), NoOpIpdChargePort (TP-5/6), NoOpAdmittedPatientsReader. Tables ipd_order_supply, ipd_issue, ipd_issue_line (charge_status), ward return and discharge clearance exist (migrations 2200, 2210, 2220). IpdDispensingService is a stub (lists empty, writes 409 not-connected/not-issued; a port reporting connected() = true is also refused). Turning the port on alone connects nothing: the service must be built in the same change.

## 2. IPD has nowhere to put a pharmacy line
ipd.admission_charge is append-only with a closed list of 10 service keys (ChargeServices.ALL), one row = one unit (qty = 1; sahaj_app cannot insert qty), no credit line, no source key. RunningBill multiplies service charges by the bed-category multiplier: must not apply to medicines at MRP. IpdBilling and RunningBill feed the running bill, deposit-low alert, Admitted list, board and final bill: one change reaches all (TP-5 is HIGH risk). DischargeService takes the clinic IPD lock before freezing the final bill: the charge port must take the same lock, otherwise a charge can land after the expected-total check or the freeze.

## 3. Mockup
The main mockup has no pharmacy in the IPD bill (pharmacy-to-bill exists only in the P&I mockup). "Print interim bill" is "coming soon"; "Billing clearance" and "Nursing handover" are labels completed by the one Discharge button (DEC-014); no TPA split, co-pay, GST, estimate, balance-due outcome, or mid-stay refund.

## 4. Trace
Covered: doctor order, IPD pharmacist check, advance deposit, final bill (total minus deposits, owner-only refund), ward indent and approval, ward stock and expiry, general ward consumption, IPD money in day close (SAH-81, story only).
Partial/not connected (stories and contract exist, code returns 409 or empty): IPD queue, pick list, issue to ward, Charge pending, ward return, patient-wise consumption, discharge lane and Mark cleared.
Missing: IPD charge and credit line, substitution on IPD issue, reversal of a wrong issue, take-home medicines, kits and consumables, late charges after discharge, payer split, GST, package rules for pharmacy.

## 5. Missing flows rated (insurance/corporate only for hospitals that take them)
Must: connect TP-4/5/6 and build the service; pharmacy lines on the bill with credits and MRP pricing; reverse a wrong issue; IPD substitution with ward notice; take-home supply and billing; rule for late charges after discharge; rule for pharmacy inside a package; decision on GST; insurer share, co-pay, non-payable items.
Should: pharmacy clearance as information; a gate (human choice); TPA pre-authorisation record; part refund of deposit; interim bill as browser print view; balance due with owner approval; estimate; IPD discount; kits and consumables; batch number on the medication chart; ward-level controlled-drug register; Billing role reading the IPD bill; issued/returned/given reconciliation.
Later: TPA claim and settlement (needs gateway), indent suggestion and ward par levels, offline.

## 6. Integration spec
IPD side (new interfaces in ipd.api): read-only order feed (order version, current ward and bed, priority, status, pharmacist-check state, no patient name); admitted-patients reader; charge and credit port: idempotent by sourceKey, runs in the pharmacy transaction under the same clinic IPD lock as discharge, returns POSTED, BILL_CLOSED or ADMISSION_NOT_FOUND.
Pharmacy side: replace the no-ops with adapters behind a per-clinic switch; build all WP-M operations; issue is all-or-nothing; BILL_CLOSED gives 409 ipd-bill-closed and nothing moves; a technical port failure saves the issue as CHARGE_PENDING; Post waiting posts each key once, nothing posts by itself at switch-over.
Tests QA must be able to run: atomicity, charge vs discharge concurrency, idempotency, reconciliation, tenant isolation, ModularityTests (ipd must not depend on pharmacy), existing IPD suites unchanged with the link off, no PII in logs.

## 7. Open questions for the human
OQ-B-1 approve TP-4/5/6 separately plus the IPD session review; OQ-B-2 what "discharge started" means; OQ-B-3 pharmacy inside a package; plus 17 more (take-home list source, GST, TPA rules, late charges, balance due, wastage, price rule, kits, others). Proposed stories PB-1 to PB-12 to be written with acceptance criteria in the file.
