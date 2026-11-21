Reserved story IDs: SAH-87 (IPD clinical/admin gap analysis A) / SAH-88 (IPD money/supply gap analysis B). Status: PROPOSED, not approved.
# IPD-flow-gap-analysis-B · IPD money and supply flow (pharmacy link, bill lines, payer split)
Module: ipd and pharmacy (the link between them); billing reads
Mockup screen: Transfer, Discharge, Case paper (card actions) for the IPD bill and discharge; IPD → Admitted for the running bill and deposit alert. The pharmacy-to-bill flow exists only in the Pharmacy and Inventory (P&I) mockup, not in the main mockup. Proposed flows with no screen are marked "NOT IN MOCKUP: needs human decision".
Source: Programme DEC-061 / IPD, gap analysis B by business analyst, 21 Nov 2026. Written from `docs/handoffs/IPD-gap-B-draft.md` and re-checked against the repo.
Status: ANALYSIS. Every proposed story (PB-1..PB-12) is PROPOSED. Nothing is approved or built from it. Existing stories are not edited.

## Problem
The pharmacy module and the IPD module each hold half of an ordering-to-billing link, and neither half is connected. A doctor's medicine order in IPD never reaches the pharmacy queue, a ward issue never reaches the patient's bill, and the final bill cannot contain medicines. Turning on an existing switch would not fix this: the service that uses the ports is a stub and IPD has no place to hold a pharmacy line.

## Who uses it (roles) and in which setting
Doctor (orders), nurse (ward, returns), pharmacist (pick, issue, substitution, clear), owner (approvals, refund, price decisions), front desk (collect at discharge), billing (reads the IPD bill: no IPD access today, SAH-27 OQ-27-3). Setting: hospital ward, hospital pharmacy, front desk.

## In scope / Out of scope
In scope: connecting the three pharmacy ports (TP-4, TP-5, TP-6), pharmacy lines and credits on the IPD running and final bill, the rules around them.
Out of scope: TPA claim and settlement (needs the integration gateway, Later), indent suggestion and ward par levels, offline use, API and table design.

## Re-check against the repo (corrections and confirmations)
| Draft statement | Result |
|---|---|
| Pharmacy has `NoOpIpdOrderSource` (TP-4), `NoOpIpdChargePort` (TP-5/6), `NoOpAdmittedPatientsReader`. | CONFIRMED. Files: `backend/src/main/java/com/sahaj/pharmacy/integration/NoOpIpdOrderSource.java`, `NoOpIpdChargePort.java`; `application/NoOpAdmittedPatientsReader.java` (this one sits in `application`, not `integration`). |
| `ipd_order_supply`, `ipd_issue`, `ipd_issue_line` exist, migrations 2200, 2210, 2220. | CONFIRMED with detail: `V2026_12_17_2200__pharmacy_ipd_order_supply_issue.sql` holds order supply and issue; `V2026_12_17_2210__pharmacy_ward_return_discharge_clearance.sql` holds ward return and discharge clearance; `V2026_12_17_2220__pharmacy_consumption_charge_columns.sql` adds charge columns for consumption (TP-6). |
| `IpdDispensingService` is a stub: 409 not-connected / not-issued; a port reporting connected() = true is refused too. | CONFIRMED in its header comment (409 `not-connected` for order set, line pick, dispense, post charges, mark cleared; 409 `not-issued` for a ward return; a self-reported connected port is refused). |
| Charge port returns POSTED, BILL_CLOSED, ADMISSION_NOT_FOUND. | CONFIRMED (`IpdChargePort.Result`). The interface has `charge` and `credit` with `ChargeLine(sourceKey, admissionId, productId, productName, quantity, amountPaise)`; credit is "never above what was charged minus what was credited for that source line". It is idempotent by `sourceKey` and is meant to run inside the pharmacy transaction. |
| `ChargeServices.ALL` is a closed list of 10 service keys. | CONFIRMED (10 entries in `backend/src/main/java/com/sahaj/ipd/application/ChargeServices.java`). |
| RunningBill multiplies service charges by the bed-category multiplier. | CONFIRMED (`RunningBill` applies the category multiplier, rounded half-up to whole paise per unit price). Medicines at MRP must not use it (rule 5). |
| `ipd.admission_charge` append-only, qty = 1 per row, no credit line, no source key. | NOT RE-VERIFIED in migrations by BA (only the Java side was read); the architect and db-engineer must confirm before design. |
| TP-4/5/6 are named in the docs. | CONFIRMED: `docs/requirements/SAH-80.md`, `docs/stories/SAH-80.md`, `docs/api/pharmacy.yaml`, `docs/api/pharmacy-sales.yaml`, ADR-030, DEC-074 and DEC-079. The code comment in `IpdChargePort` says a real port needs an IPD-owned port, an IPD migration (HIGH-risk money change) and human approval first (DEC-072 Q2). |
| SAH-81 (IPD money in day close) is "story only". | CONFIRMED: `docs/stories/SAH-81.md` and requirement exist; it reads IPD data via an `ipd.api` port. It does not include pharmacy lines. |
| Housekeeping (from draft A) | `docs/stories/IPD-open-questions.md` is cited by several stories but lives in `docs/decisions/`. |
| The draft's "17 more" open questions | The draft named only a few topics (take-home list source, GST, TPA rules, late charges, balance due, wastage, price rule, kits, others). The wording of OQ-B-4..OQ-B-20 below is derived by the BA from the draft's topic list and the Should/Must lists; it is not a verbatim copy. Mark it for the human to check. |

## Trace
- Covered: doctor order, IPD pharmacist check, advance deposit, final bill (total minus deposits, owner-only refund), ward indent and approval, ward stock and expiry, general ward consumption, IPD money in day close (SAH-81, story only).
- Partial or not connected (stories and contract exist, code returns 409 or empty): IPD queue, pick list, issue to ward, Charge pending, ward return, patient-wise consumption, discharge lane and Mark cleared.
- Missing: IPD charge and credit line, substitution on IPD issue, reversal of a wrong issue, take-home medicines, kits and consumables, late charges after discharge, payer split, GST, package rules for pharmacy.

## Findings (kept from the draft)
1. The link is not connected on either side (see re-check). Turning on the port alone connects nothing: the service must be built in the same change.
2. IPD has nowhere to put a pharmacy line. `admission_charge` is append-only, closed list of 10 keys, one row one unit, no credit, no source key; `sahaj_app` cannot insert qty. The running bill, deposit-low alert, Admitted list, board and final bill all read it, so one change reaches all (TP-5 is HIGH risk). `DischargeService` takes the clinic IPD lock before freezing the final bill; the charge port must take the same lock, or a charge can land after the expected-total check or after the freeze.
3. Mockup: the main mockup has no pharmacy in the IPD bill. "Print interim bill" is "coming soon". "Billing clearance" and "Nursing handover" are labels completed by the one Discharge button (DEC-014). No TPA split, co-pay, GST, estimate, balance-due outcome or mid-stay refund.

## Missing flows, rated (insurance and corporate only for hospitals that take them)
Must: connect TP-4/5/6 and build the service (PB-1, PB-2, PB-3); pharmacy lines on the bill with credits and MRP pricing (PB-4, PB-5); reverse a wrong issue (PB-6); IPD substitution with ward notice (PB-7); take-home supply and billing (PB-8); rule for late charges after discharge (PB-9); rule for pharmacy inside a package (PB-10); decision on GST (PB-11); insurer share, co-pay, non-payable items (PB-12).
Should: pharmacy clearance as information; a clearance gate (human choice); TPA pre-authorisation record; part refund of deposit; interim bill as browser print view; balance due with owner approval; estimate; IPD discount; kits and consumables; batch number on the medication chart; ward-level controlled-drug register; Billing role reading the IPD bill; issued, returned and given reconciliation.
Later: TPA claim and settlement (needs gateway); indent suggestion and ward par levels; offline.

## Business rules
1. Every proposed flow is PROPOSED. The mockup is the design; the pharmacy-to-IPD-bill flow is not in the main mockup, so the human decides first (OQ-B-1).
2. Tenant isolation: every record is limited to the clinic; another clinic's record answers 404.
3. In IPD Read-only state, writes to IPD answer 409 `ipd-readonly`.
4. Money is integer paise.
5. Medicines are billed at MRP; the bed-category multiplier must not apply to them (price rule is open: OQ-B-9).
6. A charge and a credit are idempotent by a source key: the same key posts once.
7. An issue is all-or-nothing: stock and charge commit together, or neither does.
8. When the IPD bill is already closed (final bill frozen), the charge is refused with 409 `ipd-bill-closed`, and nothing moves (stock, issue, charge).
9. When the port fails technically (not a business refusal), the issue is saved as CHARGE_PENDING and "Post waiting" posts each source key once; nothing posts by itself at switch-over.
10. The charge port takes the same clinic IPD lock as discharge.
11. The IPD module must not depend on the pharmacy module; the order feed and the charge port are interfaces in `ipd.api`, with the pharmacy adapters behind a per-clinic switch.
12. The order feed carries no patient name.
13. With the link off, every existing IPD behaviour is unchanged.
14. A credit can never exceed the amount charged less the amount already credited for that source line.
15. Pricing, tax, package, payer-split and late-charge rules are legal or commercial and are open questions, not assumptions.

## Patient-safety and privacy points
- The ward must not give a medicine that was substituted without a visible ward notice (PB-7).
- A wrong issue must be reversible and traced (PB-6); controlled drugs stay on the controlled-drug register (Should).
- Order feed and logs must not contain patient name, mobile or clinical text; admission id only.
- The pharmacist check state is read from IPD; pharmacy never edits the doctor's order.

## Plan or entitlement
Hospital plan with IPD On or Trial and Pharmacy module On; the link switch is per clinic and off by default. Read-only IPD blocks writes.

## Integration spec (full)
### IPD side (new interfaces in `ipd.api`, IPD owns them)
1. Order feed (TP-4), read only: for each open medicine order: order id and version, admission id, current ward and bed, priority, status, pharmacist-check state, medicine and dose as ordered, no patient name. A changed order has a new version; pharmacy never edits it.
2. Admitted-patients reader: admission id, IP number, ward, bed, status (for the pick list, patient-wise consumption and the discharge lane). No patient name unless a human approves it (OQ-B-4).
3. Charge and credit port (TP-5, TP-6): `charge` and `credit` with `sourceKey`, admission id, product id and name, quantity, amount in paise. Idempotent by `sourceKey`. Runs inside the pharmacy transaction, under the same clinic IPD lock as discharge. Returns POSTED, BILL_CLOSED or ADMISSION_NOT_FOUND. A credit is never above what was charged minus what was credited for that source line.
4. IPD must also own: a place for a pharmacy line (a source key, a credit line, a quantity), a pricing path that skips the bed-category multiplier, and the bill views that show it (running bill, deposit-low alert, Admitted list, board, final bill). This is an IPD migration and a HIGH-risk money change.
### Pharmacy side
1. Replace the no-ops by adapters behind a per-clinic switch; the no-ops stay as the default when the switch is off.
2. Build all operations of the dispensing service (WP-M): order set, line pick, dispense, post waiting charges, ward return, mark cleared, patient-wise consumption.
3. Issue is all-or-nothing. BILL_CLOSED gives 409 `ipd-bill-closed`, and nothing moves. A technical port failure saves the issue as CHARGE_PENDING. "Post waiting" posts each source key once. Nothing posts by itself at switch-over (existing CHARGE_PENDING rows need a human or owner action).
4. A port that reports connected() = true is only accepted when the service is also built.
### Tests QA must be able to run
Atomicity (stock and charge commit or fail together); charge versus discharge concurrency (a charge never lands after the freeze); idempotency (same source key twice); reconciliation (issued, returned, given, charged); tenant isolation (other clinic 404, row-level security); ModularityTests (ipd must not depend on pharmacy); existing IPD test suites unchanged with the link off; no PII in logs.

## TP-4/5/6 connection checklist
Use this as the go/no-go list for turning the link on. Each item is a yes/no for QA and the tech lead.
| # | Item | TP |
|---|---|---|
| 1 | Human approves TP-4, TP-5 and TP-6 separately and approves the IPD session review (OQ-B-1) | all |
| 2 | Architect writes the IPD-owned interfaces in `ipd.api` and the contract; human approves the contract | all |
| 3 | DB engineer designs the IPD pharmacy-line storage (source key, credit, quantity); migration reviewed as HIGH risk | 5 |
| 4 | Order feed returns version, ward, bed, priority, status, pharmacist-check state and no patient name | 4 |
| 5 | Admitted-patients reader returns admission id, IP number, ward, bed, status | 4, 6 |
| 6 | Charge and credit take the same clinic IPD lock as discharge; test proves no charge lands after freeze | 5 |
| 7 | Same sourceKey twice posts once; credit never exceeds charged minus credited | 5, 6 |
| 8 | Medicines price at MRP, without the bed-category multiplier; test proves it | 5 |
| 9 | Running bill, deposit-low alert, Admitted list, board and final bill all show the new lines and agree to the paise | 5 |
| 10 | The dispensing service is built (no 409 not-connected); a self-reported connected port is only accepted with it | 4, 5, 6 |
| 11 | BILL_CLOSED gives 409 `ipd-bill-closed` and nothing moves | 5 |
| 12 | A technical failure saves CHARGE_PENDING; Post waiting posts each key once; no auto-post at switch-over | 5 |
| 13 | Patient-wise consumption (TP-6) charges and credits through the same port and matches the issue lines | 6 |
| 14 | Per-clinic switch is off by default; with it off, existing IPD suites pass unchanged | all |
| 15 | ModularityTests pass: ipd does not depend on pharmacy | all |
| 16 | Tenant isolation proven for feed, reader and port (other clinic 404) | all |
| 17 | No PII in logs or audit for any of the three | all |
| 18 | Late-charge, package, GST and payer-split rules decided (OQ-B-3, OQ-B-5, OQ-B-6, OQ-B-7) or switch stays off for those cases | 5 |

## Open questions for the human
Draft items OQ-B-1 to OQ-B-3 are verbatim in meaning; the rest are derived from the topics the draft listed (see re-check).
| Id | Question |
|---|---|
| OQ-B-1 | Approve TP-4, TP-5 and TP-6 separately, plus the IPD session review of the HIGH-risk money change? |
| OQ-B-2 | What does "discharge started" mean for the pharmacy (summary signed, Start discharge opened, bill frozen)? It decides what the pharmacy lane may issue. |
| OQ-B-3 | Pharmacy inside a package: included, excluded, capped, or billed on top? |
| OQ-B-4 | May the order feed or the pick list show the patient name to the pharmacist (draft: no name)? |
| OQ-B-5 | Late charges after discharge (issue or return after the bill is frozen): refuse, a supplementary bill, or write off? |
| OQ-B-6 | GST on medicines in the IPD bill: shown, included in MRP, or none? (tax rule: do not assume) |
| OQ-B-7 | Insurer share, co-pay and non-payable items: who sets the list, and how does the bill show it? |
| OQ-B-8 | Take-home medicines: where does the list come from (discharge summary, separate order) and who may issue? |
| OQ-B-9 | Price rule: MRP always, or the tariff or a payer rate? Discount allowed in IPD? |
| OQ-B-10 | Balance due at discharge: may the patient leave with an unpaid balance, and who approves? |
| OQ-B-11 | Part refund of deposit mid-stay: allowed, and by whom? |
| OQ-B-12 | Wastage and partly used items on return: credit in full, in part, or none? |
| OQ-B-13 | Kits and consumables: billed per item, per kit, or inside the bed charge? |
| OQ-B-14 | Should pharmacy clearance at discharge only inform, or gate the discharge (human choice)? |
| OQ-B-15 | Estimate and interim bill: browser print only? (see IPD-A-7) |
| OQ-B-16 | Billing role: may it read the IPD bill, and refund? (SAH-27 OQ-27-3) |
| OQ-B-17 | Batch number on the medication chart: needed for traceability? |
| OQ-B-18 | Ward-level controlled-drug register: in phase 1? |
| OQ-B-19 | Substitution rule on IPD issue: which substitutions are allowed, and who is told? |
| OQ-B-20 | Switch-over: what happens to existing rows already saved as CHARGE_PENDING? |
Existing questions stay open: SAH-80 and SAH-81 questions, OQ-27-4 (IPD money in billing and day close), OQ-27-9 (insurer share).

## Proposed stories, priority summary
Must for go-live (if the hospital uses IPD with pharmacy): PB-1, PB-2, PB-3, PB-4, PB-5, PB-6, PB-7, PB-8, PB-9, PB-10, PB-11, PB-12. PB-11 and PB-12 are decision stories: Must to decide, but PB-12 is needed only by hospitals that take insurance or corporate patients. Should/Could: none of the PB stories; the Should items are listed above and have no story yet. If the human decides pharmacy stays outside the IPD bill at go-live (OQ-B-1 no), the whole set drops to Later and the IPD bill stays as in the main mockup.

# Stories (PROPOSED)

## Common acceptance criteria (CAC), applied to every story
- CAC-1 Given a user of another clinic, when they open or change any record of these stories by id, then 404.
- CAC-2 Given IPD in Read-only state, when anything tries to write to IPD (a charge, a credit), then 409 `ipd-readonly`; nothing moves in pharmacy either.
- CAC-3 Given IPD or Pharmacy Off, then 403 `module-off`.
- CAC-4 Given a slow or lost network, then the action button is disabled while the request runs, a retry or double tap never creates a second movement or a second charge (same key), and if the answer is lost the user reopens the record and sees the real state.
- CAC-5 Given invalid input (unknown product, quantity zero or negative, quantity above what was issued), then 422 with the field named and nothing moves.
- CAC-6 Every write is audited without patient name, mobile or clinical text.

### PB-1 · As a pharmacist, I want the IPD medicine orders to reach my queue, so that I can prepare what the doctors ordered
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision (P&I mockup has the pharmacy IPD queue; the main mockup does not). EMR sheet (card → EMR) shows the doctor side.
Roles: pharmacist, owner read the queue; doctor orders (unchanged).
## Acceptance criteria
- Given the link is On for a clinic, when a doctor saves a medicine order, then it appears in the pharmacy IPD queue with ward, bed, priority, status and pharmacist-check state and no patient name.
- Given the doctor changes the order, then the queue shows the new version and the earlier pick, if any, is flagged.
- Given the link is Off, then the queue is empty and a notice says the link is not connected (as today).
- Given a nurse or front desk, when they open the pharmacy queue, then 403.
- Given the order feed is unavailable, then the queue shows an error with retry and never shows stale data as current.
- Given another clinic's order id, then 404 (CAC-1); Read-only (CAC-2); slow network (CAC-4).
## Notes for design
TP-4. The IPD side exposes a read-only feed in `ipd.api`; ipd must not depend on pharmacy.

### PB-2 · As a pharmacist, I want to see which patients are admitted, so that I can pick, issue to the right ward and bed and clear at discharge
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision.
Roles: pharmacist, owner.
## Acceptance criteria
- Given admitted patients, when the pharmacist opens the pick list or patient-wise consumption, then the list shows admission, IP number, ward and bed.
- Given a patient is transferred, then the new ward and bed show at once.
- Given a discharged patient, then the patient moves to the discharge lane.
- Given no admitted patients, then an empty state.
- Given a wrong role (doctor, nurse for pharmacy screens), then 403.
- Given another clinic, 404 (CAC-1); Read-only (reads allowed, CAC-2); slow network (CAC-4).
## Notes for design
Admitted-patients reader replaces `NoOpAdmittedPatientsReader`.

### PB-3 · As a pharmacist, I want to issue, return and clear in the IPD lane with the stock and the bill moving together, so that stock and bill always agree
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision.
Roles: pharmacist, owner; nurse receives and returns at ward.
## Acceptance criteria
- Given a picked order line, when the pharmacist issues it, then stock moves out and the charge posts in one step; if the charge is refused, nothing moves.
- Given the IPD bill is closed, when the pharmacist issues, then 409 `ipd-bill-closed` and nothing moves.
- Given the charge port fails technically, then the issue is saved as CHARGE_PENDING and shown with that tag; Post waiting posts each key once.
- Given Post waiting is pressed twice, then each charge posts once.
- Given a ward return, when quantity is above what was issued, then 422; when the line was never issued, 409 `not-issued`.
- Given Mark cleared, then it is allowed only after every line is charged or credited (and, if OQ-B-14 says gate, only then).
- Given a wrong role (billing, front desk), then 403.
- Given slow network, the issue button is disabled while it runs and a retry does not issue twice (CAC-4).
- Given another clinic (404), Read-only (409 `ipd-readonly`).
## Notes for design
TP-4, TP-5, TP-6 together. Replaces the stub `IpdDispensingService`; remove the 409 not-connected only when the whole flow works.

### PB-4 · As an owner or nurse, I want issued medicines to show as lines on the IPD running and final bill, with credits for returns, so that the patient pays for exactly what was used
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions) for the final bill; IPD → Admitted for the running bill. The pharmacy line itself is NOT IN MOCKUP: needs human decision.
Roles: owner, front desk, doctor, nurse read; billing as OQ-B-16.
## Acceptance criteria
- Given a posted charge, when anyone opens the running bill, then a line with the medicine name, quantity and amount shows and the total, deposit-low alert and Admitted list agree.
- Given a credit for a return, then a credit line shows with a minus sign and the total goes down.
- Given a credit above the charged amount, then it is refused (422).
- Given discharge, then the frozen final bill contains the pharmacy lines and credits as at the freeze.
- Given the final bill is frozen, then no later change alters it (see PB-9).
- Given an empty bill, then the existing empty state.
- Given a wrong role, 403; another clinic, 404 (CAC-1); Read-only, reads allowed and writes 409 (CAC-2); slow network (CAC-4).
## Notes for design
HIGH risk; migration of `admission_charge` or a new table is for the architect and db-engineer.

### PB-5 · As an owner, I want medicines on the IPD bill priced at MRP without the bed-category multiplier, so that the bill is correct
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-9 for the price rule).
Roles: owner decides; pharmacist, front desk see.
## Acceptance criteria
- Given a medicine charge in a bed category with a multiplier above 1, then the line shows the MRP rate unchanged.
- Given a service charge (visit, injection) in the same bill, then it still uses the multiplier.
- Given the transfer to another category, then the medicine lines do not change.
- Given an amount in the request that differs from the server's price, then the server's price is used or the request is refused (422), as the architect decides; the amount is never trusted blindly.
- Given a price of zero or negative, then 422.
- Given other clinic (404), Read-only (409), slow network (CAC-4).
## Notes for design
Rounded half-up to whole paise per unit; tax per OQ-B-6.

### PB-6 · As a pharmacist, I want to reverse a wrong issue, so that stock and bill are corrected and traced
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision.
Roles: pharmacist records; owner approves if the human decides.
## Acceptance criteria
- Given an issued line, when the pharmacist reverses it with a reason, then stock returns, a credit posts for the full charged amount and the issue shows "Reversed".
- Given a line already reversed, then 409.
- Given the IPD bill closed, then 409 `ipd-bill-closed` and nothing moves.
- Given a missing reason, then 422.
- Given a wrong role, 403.
- Given a controlled drug, then the reversal is also on the register (OQ-B-18).
- Given other clinic (404), Read-only (409), slow network (CAC-4).
## Notes for design
Idempotent by a reversal key.

### PB-7 · As a pharmacist, I want to substitute a medicine on an IPD issue and tell the ward, so that the patient gets the same therapy and the nurse knows
Status: PROPOSED · Priority: Must
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-19). EMR sheet is where the doctor order sits.
Roles: pharmacist substitutes; nurse sees the notice; doctor sees it.
## Acceptance criteria
- Given an ordered medicine, when the pharmacist issues a substitute allowed by the rule, then the issue records both and a ward notice shows on the nurse's round and the Nursing sheet.
- Given a substitute not allowed by the rule, then 422.
- Given the patient's recorded allergy matches the substitute, then the issue is blocked with the safety message.
- Given the nurse has not seen the notice, then the dose is flagged on the round until acknowledged.
- Given a wrong role (nurse substitutes), 403.
- Given other clinic (404), Read-only (409), slow network (CAC-4).
## Notes for design
Patient safety. The allowed substitutions are a clinical question (OQ-B-19).

### PB-8 · As a pharmacist or front desk user, I want to supply and bill take-home medicines at discharge, so that the patient leaves with medicines and the bill is complete
Status: PROPOSED · Priority: Must
Mockup screen: Transfer, Discharge, Case paper (card actions) (Discharge sheet). Take-home supply is NOT IN MOCKUP: needs human decision (OQ-B-8).
Roles: pharmacist supplies; doctor lists; front desk collects.
## Acceptance criteria
- Given a signed discharge summary with a take-home list, when the pharmacist supplies it, then stock moves and the lines are billed on the final bill before the freeze.
- Given the bill is already frozen, then take-home goes as a separate sale (OQ-B-5), not on the frozen bill.
- Given an item not in the list, then 422.
- Given no take-home list, then an empty state.
- Given a wrong role, 403.
- Given other clinic (404), Read-only (409), slow network (CAC-4).
## Notes for design
Where the list comes from is open (OQ-B-8).

### PB-9 · As an owner, I want a rule for charges that arrive after the bill is frozen, so that money is neither lost nor added to a closed bill
Status: PROPOSED · Priority: Must (decision)
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-5).
Roles: owner decides and approves exceptions.
## Acceptance criteria
- Given a charge or credit arrives after the final bill is frozen, then it is refused with 409 `ipd-bill-closed` and nothing moves.
- Given the human chooses a supplementary bill, then the late charge is recorded against the admission and collected separately; the frozen bill is unchanged.
- Given a concurrent charge and discharge, then exactly one wins (the same lock), and the loser gets a clear answer.
- Given a slow network on discharge while a pharmacist issues, then no charge lands between the expected-total check and the freeze.
- Given another clinic (404), Read-only (409), wrong role (403).
## Notes for design
Depends on the lock rule in the finding 2.

### PB-10 · As an owner, I want a rule for pharmacy items inside a package, so that the package price holds
Status: PROPOSED · Priority: Must (decision)
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-3). Package exists in SAH-29.
Roles: owner decides.
## Acceptance criteria
- Given an admission with a package and the rule "included", then pharmacy lines show as zero-amount lines marked "In package" and the total does not change.
- Given the rule "billed on top", then lines add to the package total.
- Given the rule "capped", then the amount above the cap is billed and the cap is shown.
- Given no rule set, then the link cannot be switched on for packages and the screen says so.
- Given other clinic (404), Read-only (409), invalid rule value (422), slow network (CAC-4).
## Notes for design
Package rules are commercial; not assumed.

### PB-11 · As an owner, I want a decision on GST for IPD medicine lines, so that bills are lawful
Status: PROPOSED · Priority: Must (decision)
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-6).
Roles: owner, billing.
## Acceptance criteria
- Given the human's decision (shown, included or none), then every pharmacy line on the bill follows it and the final bill shows the tax rows as decided.
- Given a product without a tax rate and the rule "shown", then the issue is refused with 422.
- Given a frozen bill, then tax figures do not change.
- Given other clinic (404), Read-only (409), wrong role (403), slow network (CAC-4).
## Notes for design
Tax law is not decided by the BA.

### PB-12 · As a front desk or billing user, I want the insurer share, co-pay and non-payable items shown on the IPD bill, so that the family pays only its part
Status: PROPOSED · Priority: Must for hospitals that take insurance or corporate patients; otherwise Should
Mockup screen: NOT IN MOCKUP: needs human decision (OQ-B-7). Today the mockup collects total minus deposit like a Self patient (SAH-27 OQ-27-9).
Roles: front desk, billing, owner.
## Acceptance criteria
- Given an insured admission, when the final bill is shown, then it shows total, insurer share, co-pay, non-payable items and the patient's share.
- Given the non-payable list is empty, then all items are payable and the screen says so.
- Given a split that does not add up to the total, then 422 and the discharge is blocked.
- Given a wrong role, 403; the share is computed by the server, never taken from the request.
- Given other clinic (404), Read-only (409), slow network (CAC-4).
## Notes for design
TPA claim and settlement are Later (gateway).

# End-to-end IPD test scenario outline (admission to discharge, with pharmacy, for QA)
Setup: clinics T1 (hospital, IPD On, Pharmacy On, link switch per scenario) and T2 (another hospital); owner, doctor, nurse, pharmacist, front desk in T1; one ward and three beds; one package; fake patients and products; one batch with stock. Steps marked [PROPOSED] run only after approval and build.
1. Link off: admit with deposit, doctor orders medicine, run the whole IPD suites. Check: pharmacy queue empty with the not-connected notice; all existing IPD behaviour unchanged.
2. Link on: doctor orders a medicine. [PROPOSED PB-1] Check: it appears in the pharmacy queue with ward, bed, priority and no patient name; T2 pharmacist 404.
3. Doctor edits the order. [PB-1] Check: new version, earlier pick flagged.
4. Pharmacist picks and issues. [PB-3, PB-4, PB-5] Check: stock down; bill line at MRP without the multiplier; running bill, deposit alert and Admitted list agree to the paise.
5. Issue the same key twice (retry). Check: one charge.
6. Issue with the network failing after the stock step. Check: atomic, nothing half done.
7. Technical port failure. [PB-3] Check: CHARGE_PENDING; Post waiting posts each key once, twice pressed gives once.
8. Substitute a medicine. [PB-7] Check: ward notice, allergy block.
9. Ward return of part. [PB-3, PB-4] Check: credit line; return above issued 422; credit never above charged.
10. Reverse a wrong issue. [PB-6] Check: stock back, credit, "Reversed", second reverse 409.
11. Transfer to another category. Check: medicine lines unchanged, service lines re-rated.
12. Package patient. [PB-10] Check: the rule applied.
13. Start discharge. [OQ-B-2] Check: pharmacy lane reflects the decided meaning.
14. Take-home supply. [PB-8] Check: on the final bill before the freeze.
15. Concurrency: issue and discharge at the same moment. [PB-9] Check: exactly one wins; no charge after the freeze; loser gets 409 `ipd-bill-closed`.
16. Discharge with refund by non-owner 403; owner refunds. Check: frozen bill includes pharmacy lines and credits; second discharge 409 `not-admitted`.
17. Late return after discharge. [PB-9] Check: refused or supplementary bill, as decided; frozen bill unchanged.
18. Insured patient. [PB-12, PB-11] Check: split adds up, tax rows as decided.
19. Mark cleared in the discharge lane. Check: allowed only when every line is charged or credited.
20. Reconciliation: issued minus returned minus reversed equals billed quantity; given (MAR) is reported against it.
21. Read-only: switch IPD to Read-only. Check: issue, return and reversal give 409 `ipd-readonly`; bills readable.
22. Tenant isolation: T2 user tries every id above: 404; direct database check with row-level security.
23. Slow network on issue and on discharge with a double tap: one movement, one collection.
24. Day close (SAH-81): IPD money totals include the final bill, with pharmacy lines only if the human decides (OQ-27-4).
25. No PII: search logs, audit rows and the order feed for the test patient's name and mobile; expect none.
26. Modularity test: ipd has no dependency on pharmacy.
