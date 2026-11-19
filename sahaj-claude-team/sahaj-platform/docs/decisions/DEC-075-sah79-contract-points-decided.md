# DEC-075 · SAH-79 contract and ADR-029: open points decided by the tech lead

Date: 18 Nov 2026 · Story: SAH-79 (Phase 2 Inventory) · Authority: DEC-073 (standing delegation), DEC-072
Status: DECIDED (auto). Money, GST, legal and pharmacy-practice items stay as go-live checks (G-13 to G-21).

## What was delivered
- docs/api/pharmacy.yaml 0.2.0: 96 new operations, additive only, with `x-sub-story`, `x-work-package`, `x-required-role` and `x-store-access` on each.
- docs/architecture/adr/ADR-029-pharmacy-inventory-runtime.md (PROPOSED): batches, ledger, posting and lock order, the approval engine, Schedule X, documents, reports and partitions. ADR-028 Revision 6 points to it.
- The SAH-79 contract and ADR-029 are treated as approved on the strength of DEC-073 (chat). `docs/approvals.md` has no SAH-79 line, and the hooks run merge-only (DEC-001). The human confirms when they review.

## Decisions
1. **Q-1 (story 79-12, partial receipt of a dispatch with Schedule X lines):** the contract stands.
   - A person who is not a pharmacist may receive the other lines. The Schedule X lines stay in transit until a pharmacist receives them with a second signature, and the dispatch is PARTLY_RECEIVED until then.
   - Reason: refusing the whole receipt would hold up ordinary stock behind a controlled item. Schedule X units are never Available while they wait.
   - The BA changes the wording "received once, in full" in 79-12 to "each line is received once".
2. **IPD reader port `ipd.api.AdmittedPatients` (P14):** NOT approved or built (DEC-072 Q2). The pharmacy side uses `NoOpAdmittedPatientsReader`. Patient-wise consumption is hidden and answers 409 `integration-off`. General ward consumption works in every mode. The `wards()` addition is left out until the port is approved.
3. **OQ-79-37 (batch numbers that differ only in case):** the interim reading stands. They are one batch, ignoring case, as the built import does. Reversing it needs one forward migration and moves no data. Go-live G-15 (pharmacist).
4. **A-8 (posting opening stock that contains Schedule X items):** the architect's reading stands. The poster is a person who may post, and the import was approved by another owner (SAH-78-7). There is no second signature at posting, because it records existing stock, not a receipt. This is the less strict of the two readings, so it is a clear go-live check for the pharmacist (G-15). A stricter rule can be added later without changing data.
5. **A-1 to A-7:** accepted as written in ADR-029.
   - A-1: after a rate-rise approval, the receipt is APPROVED and the Central Store user posts it.
   - A-2: the amount checked is the value of the lines whose rate rose (accountant, G-13).
   - A-3: print headers for the purchase order and the count sheet.
   - A-4: cancellations use the `approval-reject` list.
   - A-5: two lists are seeded with "Other" only.
   - A-6: which payable a credit note reduces, and what happens to excess credit (accountant, G-19).
   - A-7: return items are accepted whole.
6. **SAH-78 touch-ups** (linked ward, the capability split, two vendor credit tiers, the percentage limit for rate rise, the new series and lists):
   - They are folded into the work of SAH-78 package C, where they belong to 78-3, 78-4, 78-8, 78-9 and 78-10.
   - Schema changes for them go to the db-engineer as additive migrations in the range 1270 to 1299, which stays reserved for SAH-78. SAH-79 uses 1300 and above.
7. **Angular:** the Reason lists screen and the series screen render what the server returns (11 lists and 10 series after SAH-79), and do not assume 4 and 5.

## Order of work
- SAH-78 package C (78-8 vendors, 78-9 approval rules, 78-10 numbering, reasons and templates), java ∥ angular. This starts when the backend lock frees (about 22:35).
- Then the db-engineer starts SAH-79 WP-D. It waits until QA has finished the gate for packages A and B, because a new migration file would break the migration-count test and change what QA is running.
- SAH-79 WP-D first, and nothing else of SAH-79 starts until WP-D is reviewed (ADR-029). Then E, F, G and H, with java ∥ angular inside each package.
- SAH-80 follows SAH-79 (DEC-074).

## Blocked
Nothing.
