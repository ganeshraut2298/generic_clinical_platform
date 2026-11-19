# DEC-074 · SAH-80 open questions: researched and decided by the tech lead

Date: 18 Nov 2026 · Story: SAH-80 (Phase 3 Pharmacy) · Authority: DEC-073 (standing delegation), DEC-072 (IPD and billing held back)
Status: DECIDED (auto). Every billing, GST, legal and clinical item is also a go-live check (G-22 to G-30 of docs/requirements/SAH-80.md). Nothing here is cleared for real billing, a real H1 register or a real Schedule X register.

## Basis of the decisions
- The mockup, the BRD, ADR-028, the SAH-78 and SAH-79 decisions, and the BA's recommended answer for each question (docs/requirements/SAH-80.md).
- Indian practice, as the tech lead understands it, and not as legal advice:
  - GST tax invoice (CGST Rule 46): consecutive serial numbers of at most 16 characters, unique in a financial year, and the taxable value and tax rate shown. More than one series per registration is allowed if each is consecutive.
  - Retail price: the MRP printed on the pack is the ceiling (Legal Metrology and the drug price control rules), so a sale above MRP is never offered.
  - Schedule H and H1 items are sold on a prescription under a registered pharmacist.
  - The H1 register records the patient, the prescriber, the drug, the batch and the quantity, and is kept for at least 3 years.
- Where the BA's recommendation and this basis agree, the recommendation stands. The differences are listed below.

## Decisions
- **Accepted as recommended:**
  - OQ-80-2 (unbuilt channels: WhatsApp, SMS, PDF, QR and Rx image are not built);
  - OQ-80-3 (the mockup parts listed as not built stay unbuilt);
  - OQ-80-4 (command centre figures);
  - OQ-80-5 (the pharmacy screens show IPD orders and the store's own indents; a ward does not indent from the pharmacy store);
  - OQ-80-7 (bill series per selling store, a credit note series with prefix CN, no gaps);
  - OQ-80-8 (bill-level discount; any seller may ask; the rule decides who approves; the inbox Approve button is built);
  - OQ-80-9 (no "Change MRP": the sale is at the batch MRP);
  - OQ-80-10 (payment modes are recorded only; a part-paid bill needs a named udhar customer);
  - OQ-80-11 (a pharmacy-owned customer account for udhar and credit notes);
  - OQ-80-12 (no shipped return window: "Return window is not set. Ask your owner."; refund modes the unit allows);
  - OQ-80-13 (held bills reserve no stock; a cancelled bill reverses stock and the register rows; "Edit" does not exist);
  - OQ-80-14 (a pharmacy day close with its own drawer, separate from billing's);
  - OQ-80-15 (an IPD charge is the batch MRP per issue unit; a credit equals the charge);
  - OQ-80-16 (T8, add to the OPD bill: not built);
  - OQ-80-18 (the second signer is a different active person who is a pharmacist or an owner; the first person must be a pharmacist; one combined balance register labelled as the mockup);
  - OQ-80-19 (licences shown with a 60-day warning; no blocking);
  - OQ-80-20 (a cashier bills non-scheduled items only; any H, H1 or X line needs a pharmacist);
  - OQ-80-21 (names only in the H1 and X registers, shown to the owner and the pharmacist, with an access entry; nothing is deleted before the legal periods are set);
  - OQ-80-23 (substitution by exact salt, strength and form; "Patient agrees" recorded; the margin is shown to the owner and the pharmacist only);
  - OQ-80-24 (the pharmacist types the quantity; "Part supplied"; the pharmacy numbers its own queue tokens);
  - OQ-80-25 (the step is "Dispense to ward" after the IPD check; the mockup's "Verify & dispense" label is a deviation, approved);
  - OQ-80-26 (ward returns: same batch only; Sealed goes to Available, Opened goes to Quarantine);
  - OQ-80-27 (an "Expires in n d" chip, no block);
  - OQ-80-28 (a masked mobile; the ABHA chip only if a port gives it; the patient history button hidden);
  - OQ-80-33 (information only at discharge: no gate in IPD);
  - OQ-80-34 (pharmacy-owned link tables from OPD and IPD items to pharmacy products);
  - OQ-80-36 (the Cashier store role for the BILLING and STORE_STAFF team roles, at a store where Sale is allowed);
  - OQ-80-37 (the owner may bill non-scheduled items and cancel any bill, never override an allergy or H1 check, and never sell Schedule X);
  - OQ-80-38 (the roles matrix of mockup 05 is a read-out of what the rules engine enforces, not an editable matrix);
  - OQ-80-40 (limits: 100 lines per bill, 20 held bills per store, 100 IPD issue lines, 25 rows per queue page, reports within 12 months);
  - OQ-80-41 (build order: WP-I, WP-J, WP-L, WP-K, WP-M, WP-N; WP-O is not built);
  - OQ-80-42 (the switch to Standalone is refused while integrated documents are open; a failed port leaves "Link to patient pending").
- **Decided with a change (this basis against the recommendation):**
  - **OQ-80-6, GST on the invoice:** the recommendation stands (price is the MRP per issue unit including GST; the discount lowers each line before GST; CGST and SGST are halves; rounded to the rupee). I add that **the bill also shows the taxable value and the tax per GST rate** (Rule 46), which the mockup's inclusive figures give without new data. IGST is not built: counter sales are to consumers within the state. Go-live G-22.
  - **OQ-80-17, H1 register:** the mockup columns, plus the patient's **age, address and prescription date**, because the register has to identify the patient; reversing rows; kept for ever until the retention period is set. The address and age are asked only on a bill that has an H1 line, and are stored only with that register row. This is NEW and marked so. Go-live G-23 (compliance adviser), also under DPDP.
  - **OQ-80-18(b), NDPS:** an NDPS yes/no on own products is added, as an additive change inside the pharmacy module (SAH-78 touch-up TP-10). It needs an additive migration and a contract note.
  - **OQ-80-22, allergy match:** the keyword match on salt and generic name stands (case-insensitive, a block with an override reason chip). Drug classes (penicillin against amoxicillin) are **not** checked in this release. The sale screen says so where it shows "No allergy recorded" or the match result, and the limit is a clinical go-live check. A class list is a separate later story.
  - **OQ-80-35, audit log screen:** a **pharmacy-owned activity feed built from document histories**, not a new method in `platform.api`. This avoids a platform change.
- **Not decided by me, and not built (DEC-072 Q2):** each change to code that is already built stays behind a port with a no-op implementation until the human approves it and the IPD session has reviewed it.
  - OQ-80-29 (TP-2, an OPD prescription reader);
  - OQ-80-30 (TP-4, the IPD order feed);
  - OQ-80-31 (TP-5, the IPD charge and credit; HIGH risk);
  - OQ-80-32 (TP-6, the patient-wise consumption charge).
  - **What this means:**
    - The pharmacy side defines the ports and is built against fakes.
    - **Standalone mode works completely** (typed prescriptions and typed customers).
    - In Integrated mode the OPD and IPD queues show "Not connected" until the ports are implemented, and IPD issues are saved as "Charge pending" and "Credit pending".
- **Process items (human, not blocking):**
  - OQ-80-1: the five screen-map rows in docs/design/screen-map.md. That file is human-owned. Until they are added, the Mockup screen line uses the parent row.
  - The SAH-78 touch-ups (TP-10): the Cashier store role, bill series per store, unit fields, the NDPS yes/no on own products, and the reason lists. They go to the architect and the db-engineer with the SAH-80 contract.

## Story count
23 sub-stories against the usual limit of six. This is accepted, as SAH-79 has 19. They are grouped in work packages WP-I to WP-O.

## What this means for the build
- **Stories:** docs/stories/SAH-80.md is treated as approved on the strength of DEC-073. The human is asked to confirm this when they review the work. `docs/approvals.md` has no SAH-80 line, and the hooks run merge-only (DEC-001).
- **Dependencies:** SAH-80 builds on SAH-79's batches and ledger (WP-D to WP-H), which are not built yet. SAH-80's contract can be written next, but its database and code start only after SAH-79's.

## Blocked
Nothing.
