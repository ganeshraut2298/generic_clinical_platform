# SAH-80 · Stories: Pharmacy & Inventory, Phase 3: Pharmacy (counter sale, OPD and IPD dispensing, sales return, registers and compliance, command centre, reports)
Module: pharmacy
Mockup screen: Pharmacy, Lab, Tele (sidebar, when enabled) → Pharmacy & Inventory → Pharmacy (P&I mockup files `01-pharmacy-command-center.html`, `02-rx-linked-pos-billing.html`, `03-hospital-pharmacy-opd-and-ipd.html`, `04-sales-return.html`, `05-compliance-roles-and-audit.html`)
Also uses: P&I mockup files `15-reports.html` (group "Sales & compliance"), `17-end-to-end-flow.html` (stock movement and approval per transaction), `14-approvals-inbox.html` (the "Sales" filter and the direct "Approve" for a discount). Screen-map rows for the P&I mockup are pending from the human (OQ-80-1, still open); the line above uses the existing parent row so that `validate docs` can find it, as SAH-78 and SAH-79 do.
Requirement: docs/requirements/SAH-80.md (business rules BR-1 to BR-38, touch points TP-1 to TP-11, open questions OQ-80-1 to OQ-80-42, go-live items G-22 to G-30).
Source: DEC-061 (human, 17 Nov 2026, Q1 A to Q4 A and Answer 2); DEC-062; DEC-063 (Q1 A, Q2 A); DEC-064 (R2-12, R2-13); ADR-028 (accepted, revisions 1 to 5); `docs/architecture/PHI-feasibility.md`; BRD sections 1, 2, 5 to 8 and 10 (`docs/handoffs/PHI-input/brd.txt`); SAH-78 and SAH-79 requirement and stories.
Branch: `feature/SAH-39-home-kinetic-background` (DEC-061 Answer 2), with the additive-only rules. Written ahead; Phase 3 gets its own approvals later (DEC-061 Q1 A).

Reading guide.
- Slicing: twenty-three sub-stories (the usual limit is six) because Phase 3 covers five screens, three registers, five touch points and a counter that must be fast. Each fits one sprint. They are grouped into seven work packages built in sequence.
- Where a story needs something the mockup does not show, the AC states the recommended answer, marks it NEW and names the open question (OQ-80-n). Decided questions say DECIDED with the DEC reference. Nothing is invented silently. Tax, legal, clinical and pricing rules are never decided here: each is an OPEN question with a Recommended answer, and the story builds on the recommendation only until the human answers.
- The Sahaj shell is not changed (BRD section 7). The mockup's left panel, tabs, colours, "USP" chips, "Rakesh S." and the "Counter 1" label are not built. Items marked Later in the BRD are not built (see the requirement, "Out of scope").
- Touch-point stories (80-11, 80-12 in part, 80-13 to 80-17, 80-23) are each built only after the human approves their touch point (TP-2, TP-4, TP-5, TP-6, TP-7, TP-8). Until then the feature is hidden and the other stories work (C-9).
- Roles are the SAH-78 and SAH-79 roles plus the NEW store role Cashier (80-1). "Pharmacist" is the team role pharmacist mapped to the store; "owner" never does pharmacist-only actions.
- In each story, "Integrated" and "Standalone" say what changes in each Integration mode (BR-3). A story with no such line works the same in both modes.

Common rules for every story (each AC assumes them; they are tested once per endpoint or screen):
- C-1 Tenancy and access. The clinic (unit) comes from the signed-in user, never from the request. Another clinic's record answers 404. Roles and store capabilities are checked on the server on every call. Module Off answers 403 `module-off`. Every store-bound call has `{storeId}` in its path and never takes a "current store" from the client; another clinic's store answers 404; a store of this clinic to which the caller is not mapped answers 404 (the owner is never unmapped); a mapped person without the capability gets 403 `store-permission-denied`, audited; bad input is 400; the store check runs before any filter, sort or paging. A store whose "Sale allowed" is No answers 409 `sale-not-allowed` for sale, hold, return and dispensing calls. An Integrated-only action in Standalone mode answers 409 `integration-off`.
- C-2 Ledger and stock (SAH-79 C-2). Every sale, issue, return and cancellation writes ledger lines in the same step as its document, through the one ledger and balance service of SAH-79-1; all or nothing. A line holds date and time, transaction, reference (bill number, credit note number or admission number; never a name), store, state, product, batch, quantity in, quantity out, balance after, user and reason. The ledger is never edited; a correction is a new line pointing to the one it corrects. A movement that would make a balance negative is refused with 409 `insufficient-stock` and the available quantity, also under two users at once. Issues pick Available, unexpired batches by FEFO; the user cannot choose a batch. Quantities are whole issue units.
- C-3 Approvals (SAH-79 C-3). A document covered by an approval rule is submitted to the engine of SAH-79-2 (types SALE_DISCOUNT and SALES_RETURN). Level 0: it proceeds at once. Nobody approves their own request (403 `self-approval`, also a database check). A rejection needs a reason chip. Decisions are saved with who, capacity, time and reason.
- C-4 Documents. A number from the unit's series is taken in the same step that saves the document; a held bill and a draft have no number; a cancelled document keeps its number; nothing is hard-deleted. Every document has a History card (who, store, time, before and after, reason). Two people acting on the same document at once: the second gets 409 `changed` and the screen reloads, keeping what was typed where possible.
- C-5 Reasons. Reasons are chips from the reason lists (SAH-78-10 and the lists requested in the requirement), not free text; an optional note may be added; "Other" needs a note of at least 4 characters. No clinical text goes into audit or logs (BR-34).
- C-6 UI. Plain English, sentence case. Money ₹ 1,25,000 or ₹ 1,25,000.50; dates 24 Oct 2026; times 11:05 AM IST; batch expiry as month and year ("Feb 2027"). A button is disabled while its request runs; nothing is shown as saved until the server has answered; a failed save keeps what was typed and says "Could not save. Check your connection and try again." (NEW). No offline queue (OQ-80-39). Empty states say what to do next in one line. Lists load in pages. What a person has no capability for, and every touch point not approved, is hidden, not greyed. The counter and approvals work on a tablet; the sale screen stacks on a phone (OQ-80-3). Shortcuts: F2 search, F4 patient, F6 substitute, F8 hold, F9 save (browser defaults that clash, such as F6, are for the developer to check).
- C-7 Printing and export. Printing uses the browser print view with the template settings of SAH-78-10; export is CSV (OQ-79-4, OQ-80-2). No WhatsApp, SMS, e-mail, PDF generation, Excel or QR.
- C-8 Audit and logs (BR-34, BR-36). Every create, change, decision and posting is audited (who, when, before and after). The ledger, logs and audit text hold ids, document numbers, admission numbers, counts and outcome codes only: never a patient name, mobile number, ABHA number, prescription text, allergy text or clinical note, and not batch quantities in application logs. Patient-linked reads write a record-access entry.
- C-9 Touch points. A story marked "Touch point" needs the human's approval of that touch point (requirement, "Touch points"). Until then its screen, tab or action is absent from the capability list of `GET /context` and the server answers 409 `integration-off` or 404; the rest of Phase 3 still works. The minimum additive change is in the requirement.
- C-10 Performance. POS search under 300 ms; bill save under 1 s (BRD section 8). QA proves both on a seeded data set.

Work packages (built in sequence WP-I, WP-J, WP-K, WP-L, WP-M, WP-N, WP-O; DEC-063 Q2 A: inside each, the Java and Angular developers work in parallel). The letters continue SAH-79's WP-D to WP-H. Numbers of sub-stories are not in the order of the packages for WP-L and WP-M.

| Package | Sub-stories | Depends on | Screens it owns (behaviour level) |
| --- | --- | --- | --- |
| WP-I Sale core | 80-1, 80-2, 80-3, 80-4, 80-5 | SAH-79 WP-D (79-1 posting, 79-3 FEFO and expiry, 79-4 states); SAH-78 WP-A to WP-C (stores, store roles, products, MRP rule, numbering, print templates) | Setup touch-ups (Cashier role, bill series per store, unit sale settings, mode guard), patient and customer pick, POS billing, udhar, hold and cancel; the Pharmacy tab shell |
| WP-J Sale checks and registers | 80-6, 80-7, 80-8, 80-9, 80-10 | WP-I; SAH-79-2 (engine, inbox), 79-7 (Schedule X second-signature mechanism), 79-17 (physical count, soft) | Discount approval, substitution, allergy block, Schedule H1 at sale and the H1 register, Schedule X sale and the balance register; the "Registers & compliance" screen shell |
| WP-K OPD dispensing | 80-11, 80-12 | WP-I, WP-J (80-8, 80-9); TP-2 approved | OPD queue, picking, Rx-linked bill, typed Rx, part supply |
| WP-L Sales return and compliance | 80-18, 80-19 | WP-I, WP-J; SAH-79-2, 79-3, 79-4, 79-17 | Sales return, licences and renewals, role read-out, audit log |
| WP-M IPD dispensing | 80-13, 80-14, 80-15, 80-16, 80-17 | WP-I, WP-J, WP-L (80-18 for counter returns in 80-15); SAH-79 WP-G (79-16 for 80-16); TP-4, TP-5, TP-6, TP-7 approved | IPD queue, pick list and issue to ward, ward returns, IPD charge and credit, discharge clearance |
| WP-N Overview and reports | 80-20, 80-21, 80-22 | WP-I to WP-L; SAH-79 WP-F (indents), WP-H (reports 79-18, 79-19) | Command centre, Sales & compliance reports, day close |
| WP-O Billing touch point | 80-23 | WP-I; TP-8 approved (may never be built) | "Add pharmacy bill to the OPD bill" |

Overlap rules: WP-I owns the sale (bill, lines, payments), the Pharmacy tab shell, the customer record and the number series use; later packages add to them and do not edit them. WP-J owns the "Registers & compliance" screen shell; WP-L adds cards to it. One `docs/api/pharmacy.yaml` (architect) and one set of Flyway migrations in the order I, J, K, L, M, N, O. Packages run in sequence; WP-K and WP-L touch different screens and could run in two worktrees if the human decides (OQ-80-41).

---

### SAH-80-1 · As an owner, I want to set up what selling needs (the Cashier role, a bill series per store, the return window and refund modes, the Schedule X licence), so that the counter opens with the right people, numbers and rules
Priority: Must
Screen: NEW fields on the SAH-78 setup screens, with the labels of mockup `05-compliance-roles-and-audit.html` (columns "Cashier"; card "Licences & renewals": "Schedule X licence (20F / 21F)"), `04-sales-return.html` (chip "Within 30-day return window", "Refund to" choices) and `02-rx-linked-pos-billing.html` (bill number "SB/26-27/018422"). Built inside Inventory setup (SAH-78-1 unit settings, SAH-78-3 and 78-4 stores and staff, SAH-78-10 numbering). Touch-up of built SAH-78 code inside the pharmacy module (TP-10); no OPD, IPD or billing change. Package WP-I. Carries over R2-12 (DEC-064), OQ-78-24, OQ-78-41 and the mode-switch guard of ADR-028 D5.

## Acceptance criteria
Cashier role
- Given the owner opens the staff mapping of a store whose "Sale allowed" is Yes, when they map a person with the team role billing or Store staff, then the store roles offered include "Cashier" (OQ-80-36); the choice is not offered for the Central Store, for a store with "Sale allowed" No, or for other team roles (the owner may map a pharmacist as Store user, who can sell without being a Cashier).
- Given a Cashier mapping, when the server works out the person's capabilities, then a Cashier can sell, hold a bill, start a sales return, close their shift and see the counter figures of the command centre; a Cashier cannot approve, adjust stock, change rates, read the registers, override a block, or act on Phase 2 documents (stock, indents, receiving); a person with no other mapping gets no Phase 2 screen.
- Given a Cashier is mapped and the store's "Sale allowed" is later switched to No, then the mapping shows "Needs review" and the person loses the sale capability at once (as SAH-78 BR-11); nothing is deleted.
- Given a team role that is not allowed to hold Cashier (for example a visiting doctor), when the owner tries, then 400 "This person cannot be a Cashier." and nothing changes.

Bill series per store
- Given the owner opens Numbering, then the row "Bill" has a "Store" choice: "All stores (unit series)" and each store with "Sale allowed" Yes; choosing a store shows that store's own prefix, financial-year part and sequence length with the same checks as SAH-78-10 (prefix up to 6 characters, whole number at most 16 characters, no two series of the unit share a prefix; OQ-80-7).
- Given a store has its own bill series, when a bill is saved there, then the number comes from that store's series; given it has none, then from the unit's Bill series; the numbers of one series have no gaps for saved bills and restart on 1 April only when the series says so (SAH-78 OQ-78-31).
- Given a new row "Credit note (sales return)" in Numbering (prefix up to 6 characters, same rules), when a sales return is completed (80-18), then its number comes from this series; the example shows "CN/26-27/000001" (NEW; OQ-80-7).
- Given a row "IPD issue slip" in Numbering (NEW; OQ-80-7, used by 80-14), then it has the same rules; hidden when the unit is Standalone.
- Given a series has issued a number, when the owner tries to change its prefix or financial-year part, then 409 `series-in-use` "A number has already been used. You can only change the layout of new series." (recommended; OQ-80-7); a series with no number issued can be changed freely.
- Given the owner switches a store off, then its series is kept and never reused.

Unit sale settings
- Given the owner opens Unit settings, then, after the fields of SAH-78-1, three NEW fields appear, marked NEW: "Return window (days)" (whole number 1 to 365; empty means not set; no default), "Refund modes allowed" (checkboxes: Original mode, Cash, Credit note for next purchase, Adjust in udhar), and "Schedule X licence number" (up to 40 characters) with "Schedule X licence valid till" (a date) (OQ-80-12, OQ-78-41, OQ-80-18).
- Given the return window is empty, then 80-18 says "Return window is not set. Ask your owner." and allows no return; the Command centre does not change.
- Given the owner enters a window of 0 or 400, or a licence number over 40 characters, then 400 with the field named and nothing is saved; Schedule X licence number without a date, or a date without a number, is accepted (both are optional, as SAH-78 licence fields).
- Given each save, then an audit event records who, when and before and after values.

Integration-mode guard
- Given the unit is Integrated and the owner switches to Standalone, when any IPD issue is "Charge pending", any ward return is "Credit pending", any bill is "Link to patient pending" or any prescription is "Part supplied", then the switch is refused with 409 `open-integrated-documents` and the text "Finish or link these first: <n> IPD issues without a charge, <n> returns without a credit, <n> bills without a patient link, <n> part-supplied prescriptions." (ADR-028 D5, OQ-80-42); nothing changes. When none are open, the switch works as in SAH-78-2.
- Given the owner switches Standalone to Integrated, then it works as in SAH-78-2.

Roles, tenants, errors
- Given a person who is not the owner, when they change any of the above, then 403; a pharmacist or Central Store user may read the settings (SETUP_VIEW, as SAH-78); another clinic's record answers 404.
- Given the module Off, then 403 `module-off`; with the module Off no existing screen changes.
- Given a slow or failed save, then "Could not save. Check your connection and try again." and what was typed stays.

## Notes for design
- Additive within the pharmacy module: a nullable store column on the bill series, two new document types (credit note, IPD issue slip), three unit columns, one new store role and its capability rows, the guard on the mode switch. The architect decides how the series and counter keys carry the store (ADR-028 D8 has the counter per unit and document type).
- OQ-80-7 (series rules, accountant), OQ-80-12 (window and modes), OQ-80-18 (Schedule X licence), OQ-80-36 (who may be Cashier), OQ-80-42 (guard) are open; the stories build on the recommendations.
- The SAH-78 stories are not edited by SAH-80; the tech lead passes these touch-ups to the owners (requirement, "Requests to other owners").

---

### SAH-80-2 · As a pharmacist or cashier, I want to pick the patient or customer for a bill, so that the bill, the registers and any credit belong to the right person
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (card "Patient and prescription": initials, name, age and sex, UHID, masked mobile, "ABHA linked"; shortcut F4 "Patient") and `03-hospital-pharmacy-opd-and-ipd.html` (button "Paper Rx / walk-in"). Touch point T1 (TP-1): reads existing ports only; no change to patient. Package WP-I. BRD T1.
Integrated: pick a registered patient. Standalone: walk-in name and phone, or the pharmacy's own customer list.

## Acceptance criteria
Integrated
- Given the unit is Integrated and a person with the sale capability presses F4 or "Patient", then a search by UHID, name or mobile number opens ("Scan or type UHID, name or mobile"); a keyboard-style scanner typing a UHID works; results show name, age, sex and UHID, with the mobile masked ("+91 98XXX XX214"); the search never lists patients of another clinic.
- Given the person picks a patient, then the card shows "<name> · <age> <sex>" and "UHID <n> · <masked mobile>"; the chip "ABHA linked" is shown only if the patient port returns that fact (OQ-80-28); the button "Patient history" is not shown; the read writes a record-access entry (BR-34) and nothing is written to logs.
- Given a patient is picked, then the allergy line and checks of 80-8 apply, and the patient is saved on the bill as the patient id; the bill and registers show the name read at that time.
- Given the unit is Integrated and the person chooses "Walk-in" (no registration), then the sale continues as a walk-in with a typed name and phone as in Standalone.
- Given the patient port fails (for example IPD or patient read is unreachable), when the person searches, then "Patient records are not reachable. You can continue as a walk-in and link the patient later." and the sale continues as a walk-in; the saved bill shows "Link to patient pending" (BR-3, feasibility 5).
- Given a bill with "Link to patient pending", when a pharmacist chooses "Link to patient" and picks the patient, then the bill gets the patient id, the pending mark goes, an audit event records who and when; the register rows keep what was typed at the sale; a bill can be linked once.

Standalone
- Given the unit is Standalone, then the card offers "Walk-in" with name (required, up to 80 characters) and phone (optional, 10 digits), and "Customers": a search by name or phone over the pharmacy's own customer list.
- Given a new walk-in name and phone, when the person ticks "Save to customers" or the bill is on credit (80-4), then a customer record is created (tenant isolated); the same phone for another name shows "A customer with this phone already exists. Use them?" and does not block.
- Given a customer card, then it shows name, phone (masked in lists), an allergy note field (up to 200 characters; "Not checked by Sahaj. Shown for the pharmacist." (NEW)), and the credit fields of 80-4 for those who may see them.
- Given a customer is switched off by an owner or pharmacist, then they no longer appear in the search; their bills and credit stay; nothing is deleted.

Roles, tenants, errors
- Given a person without the sale capability, when they open the search, then 403 `store-permission-denied`; another clinic's patient or customer answers 404.
- Given no match, then "No patient found. Check the number or sell to a walk-in." (Integrated) or "No customer found. Add a new one." (Standalone).
- Given invalid input (a phone that is not 10 digits, a name over 80 characters), then 400 with the field named and nothing is saved.
- Given a slow search, then a spinner and "Could not load. Try again." on failure.
- Given the mobile number, then it is read only through the explicit call of the patient port (never logged) and is shown masked everywhere except on the customer card of a walk-in for the owner and pharmacist.

## Notes for design
- Reads `patient.api.PatientLookupPort` and `PatientClinicalPort` (exist; PHARMACIST and BILLING can read patients, DEC-049). A Cashier mapped as Store staff has no patient read of their own: the architect decides how pharmacy reads on behalf of a mapped cashier (TP-1, OQ-80-28).
- The customer record is patient data in the `pharmacy` schema (feasibility T1; OQ-80-21).
- The "Link to patient pending" state is also a blocker of the mode switch (80-1).

---

### SAH-80-3 · As a pharmacist or cashier, I want to sell medicines at the counter by scanning or searching, with FEFO batches, a GST invoice and split payment, so that a patient is billed correctly and quickly from stock that exists
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (header "New sale", shortcuts F2 to F9, search "Scan barcode, or type brand / salt / composition…", cart table #, Medicine, Batch (FEFO), Expiry, Qty, Rate, GST, Amount, card "Summary", "Split payment", buttons Save, Print, Hold, Cancel). Package WP-I. BRD P2, AC-P1.
Depends on SAH-79-1 (stock exists), 79-3 (FEFO order, expiry quarantine), 79-4 (states). Not built from this screen: "Safe substitute" (80-7), "Clinical safety checks" (80-8, 80-9), "Refill auto-scheduled", "Save & share on WhatsApp", the UPI "QR", the "View Rx image" and "Patient history" buttons, "Counter 1" and "Cashier: <name>" labels.
Integrated and Standalone: the same screen; only the patient pick (80-2) and the allergy check (80-8) differ.

## Acceptance criteria
Opening and search
- Given a pharmacist, a cashier or the owner mapped to a store with "Sale allowed" Yes opens "POS billing", then the header shows "New sale", the line "Bill number is given when you save." (NEW; the mockup shows a number before saving, which would leave gaps in a GST series, OQ-80-7) with the seller's name and role, and the shortcut hints "F2 Search, F4 Patient, F6 Substitute, F8 Hold, F9 Save and print".
- Given the search box "Scan barcode, or type brand / salt / composition…", when the person types or a keyboard-style scanner enters text, then the products of the unit (adopted and own) are found by name, salt, alias, code or barcode, ignoring capital letters; a barcode matches exactly; results show name, strength, form, pack and the Available quantity in this store; the chip "Salt search" shows when the match is on salt; results return in under 300 ms (C-10).
- Given a product with no Available stock in this store, then it shows "Out of stock" and cannot be added; a product the unit has not adopted is never offered.
- Given an unknown barcode, then "No product has this barcode." and nothing is added.

Cart
- Given the person adds a product and a quantity (a whole number of the issue unit, 1 or more), then the server picks the Available, unexpired batches by FEFO (BR-5) and the cart shows one line per batch with: Medicine (name, then "salt strength · form · pack" in small text), Batch with the chip "FEFO", Expiry (month and year), Qty, Rate (the batch MRP per issue unit, BR-6), GST % and Amount (quantity times rate, to the paisa); the person cannot choose another batch.
- Given the quantity is more than the first batch holds, then the line splits across the next batches, each its own line; given it is more than all Available stock, then "Only <n> in stock." and the quantity is not accepted.
- Given an earlier batch of the product is expired, then it is never picked and the line shows "<batch> expired · blocked" in small text under the batch (as the mockup); an expired batch cannot be forced in by any request (AC-P1).
- Given a line has 90 days or fewer to expiry, then it shows the chip "Expires in <n> d" (OQ-80-27) and is sold normally.
- Given a product with schedule H, H1 or X, then the line shows the chip "Sch. H", "Sch. H1" or "Sch. X" and the row is highlighted (as the mockup); the rules of 80-9 and 80-10 apply.
- Given the person changes a quantity or removes a line, then the batches are picked again and the totals update; a bill with no lines cannot be saved ("Add at least one item.").
- Given an empty cart, then "No items yet. Scan or search a medicine." (NEW).

Summary and payment
- Given lines in the cart, then the card "Summary" shows "Items / units", "MRP total", "Discount" (80-6), "CGST <x>% (incl.)", "SGST <x>% (incl.)", "Round off" and "Net payable" in large type, worked as in BR-6 (OQ-80-6, the accountant's confirmation).
- Given the "Split payment" fields UPI, Cash and Card (and Udhar, 80-4), then each takes an amount in rupees and paise; the screen shows what is still to be paid; "Save and print" stays disabled until the payments add up to the net payable; a total above or below shows "Payments must add up to ₹532.00." (NEW). No gateway, no QR, no payment reference is captured (OQ-80-10).
- Given the person has chosen only one mode, then entering the net payable in that field is one tap ("Pay full by Cash" is not built; the field is filled by tapping the mode name, NEW, optional).

Save and print
- Given the person presses "Save and print" (F9), then in one all-or-nothing step the bill gets its number from the store's series (or the unit's), the bill, lines, payments and ledger lines "Counter sale" are saved, stock reduces, the register rows of 80-9 and 80-10 are written, an audit event is recorded, and the browser print view opens with the invoice: unit name, drug licence number, GSTIN, patient or customer, bill number and date, HSN, batch, expiry, MRP, GST and amount per line, totals, and a pharmacist signature line with the pharmacist's name and registration number where the bill has a pharmacist (BR-9, BR-28); the screen then shows "Bill <number> saved." and a fresh sale.
- Given a saved bill, then "Print" reprints it; the saved bill cannot be edited ("A saved bill cannot be edited. Cancel it and make a new bill." where 80-5 allows).
- Given two counters sell the last units at the same moment, then the first bill saves and the second gets 409 `insufficient-stock` with the available quantity; nothing is saved for the second, the cart stays, and the screen reloads availability (BR-5).
- Given a batch expired or a rate changed between adding the line and saving, then the server picks again and, if the total changes, the screen shows "Prices or batches changed. Check the bill and save again." (NEW) and saves nothing.
- Given a double tap or a repeated request, then one bill is saved, not two (client key); the same click is ignored while the request runs.
- Given the connection is lost or the server fails, then "Could not save. Check your connection and try again." and the cart and what was typed stay; no number is used (OQ-80-39).
- Given the person presses "Cancel" with items in the cart, then "Discard this sale?" with Yes and No; Yes clears the cart; nothing was saved.
- Given a cashier saves a bill that has a line of schedule H, H1 or X, then 403 `pharmacist-required` "A pharmacist must complete this bill." and the bill stays in the cart (BR-18, OQ-80-20); a pharmacist can take it over from "Hold" (80-5).

Roles, tenants, errors
- Given a person who is not mapped to the store, then 404; a mapped person without the sale capability, then 403 `store-permission-denied` (audited); a store with "Sale allowed" No, then 409 `sale-not-allowed` and the screen is not shown; another clinic's product or batch answers 404.
- Given a cashier, then no purchase rate, landed cost, margin or other store's stock is shown anywhere on the sale screen.
- Given invalid input (a negative or fractional quantity, a payment with more than 2 decimals, more lines than the limit of OQ-80-40), then 400 with the field named and nothing is saved.
- Given the module Off, then 403 `module-off`; the Pharmacy tab is not shown.
- Given a tablet, then the cart and the summary are usable; given a phone, then the summary stacks below the cart (OQ-80-3).

## Notes for design
- A sale writes through the ledger and balance service of SAH-79-1 in the fixed lock order; the architect designs the bill, line and payment tables, the client key and the transaction; QA adds timed tests for 300 ms and 1 s.
- Worked example for the architect (mockup `02`, OQ-80-6, to be confirmed by the accountant): four lines 126.00 + 195.00 + 71.40 + 168.00 = MRP total ₹560.40; discount 5% = ₹28.02; payable 532.38; GST inside it at 12% = ₹57.04, shown as CGST ₹28.52 and SGST ₹28.52; round off − ₹0.38; net ₹532.00.
- A line has the batch MRP per issue unit; the "15/strip" pack text is the product's purchase and issue units (SAH-78-5), not a pack-selling feature.
- The mockup's "Print" before saving is not built; Print appears after the bill is saved.

---

### SAH-80-4 · As a pharmacist or cashier, I want to sell on credit (udhar) to a known customer within their limit and to receive their payments, so that dues are tracked and not forgotten
Priority: Should
Screen: P&I mockup `02-rx-linked-pos-billing.html` (field "Udhar (credit)", line "Credit limit ₹5,000 · current due ₹0"), `04-sales-return.html` (refund to "Credit note for next purchase", "Adjust in udhar khata") and `01-pharmacy-command-center.html` (card "UDHAR OUTSTANDING"). NEW screens: the customer's credit card and "Receive payment" (not drawn). Package WP-I. BRD P1 "udhar due", P2 "udhar". Open: OQ-80-11 (billing and accounting).

## Acceptance criteria
- Given a sale has a named patient or customer (80-2) whose credit limit is set, then the payment panel shows the field "Udhar (credit)" and the line "Credit limit ₹<limit> · current due ₹<due>"; with no named customer, or no limit set, the field is not shown (hidden, not greyed).
- Given the person enters an udhar amount, when it plus the current due is above the limit, then 400 `credit-limit-exceeded` "This is above the credit limit. Reduce the udhar amount or ask the owner." and nothing is saved; within the limit, the bill saves, the due goes up by the udhar amount, and the invoice shows the amount "On credit".
- Given the owner or a pharmacist opens a customer card, then "Credit limit (₹)" can be set or changed (0 or empty means no credit); a cashier cannot (403); every change is audited with before and after (OQ-80-11).
- Given a customer with a due, when a pharmacist or cashier chooses "Receive payment", then they enter an amount (not above the due), a mode (Cash, UPI or Card) and save; the due goes down; a "Payment received" slip prints (browser print view; whether it takes a numbered series is OQ-80-7); a payment above the due is refused with "The payment is more than the due." and nothing is saved.
- Given a customer card, then it shows a list of credit bills and payments received with date, number, amount and running due, and, for the owner and pharmacist, the part of the due older than 60 days ("over 60 days", the mockup's figure, OQ-80-4).
- Given a sales return with the refund to "Adjust in udhar khata" (80-18), then the due goes down by the refund (not below zero; any excess is refused: use another mode); given "Credit note for next purchase", then the customer's credit note balance goes up and the credit note shows on the card.
- Given a customer has a credit note balance, then the payment panel offers "Credit note" as a payment mode up to the balance; the balance goes down when the bill is saved; with no balance the mode is not shown.
- Given a bill with udhar is cancelled (80-5), then the due goes down by the udhar part; a bill with a payment received against its due can still be cancelled; the customer's due never goes below zero.
- Given Integrated mode, then the credit account belongs to the patient (by patient id) in the pharmacy; nothing is written to the OPD, IPD or billing modules; given Standalone, then it belongs to the customer record.
- Given a person without the sale capability, then 403; a cashier opening the limit field, then it is read-only; another clinic's customer, 404; a limit that is negative or has more than 2 decimals, 400.
- Given two cashiers sell on credit to the same customer at the same time, then the due is worked from the saved bills in order and the second may be refused for the limit; nothing is saved twice.
- Given the customer is switched off, then no new bill can use udhar; the due stays and payments can still be received.
- Given a failed save, then "Could not save. Check your connection and try again." and the amount typed stays.

## Notes for design
- The billing module has no customer credit (feasibility 7.2; OQ-80-11), so pharmacy owns a customer account: limit, entries (credit bill, payment, adjustment, credit note), due. The ageing figure feeds the command centre (80-20).
- No interest, no reminders, no write-off and no accounts export are built. The accountant confirms before real use (G-27).
- If the human removes udhar, the field, the refund choices and the card are hidden and nothing else changes.

---

### SAH-80-5 · As a pharmacist, cashier or owner, I want to hold a sale for later and to cancel a saved bill the right way, so that a queue keeps moving and a mistake is corrected without editing a GST invoice
Priority: Should
Screen: P&I mockup `02-rx-linked-pos-billing.html` (button "Hold", F8 "Hold") and `05-compliance-roles-and-audit.html` (matrix row "Cancel / edit saved bill": Owner Allow, Pharmacist "Same day only", Cashier Deny). The list of held bills and the cancel action are NEW (not drawn). Package WP-I. BRD P2 "hold bill". Open: OQ-80-13.

## Acceptance criteria
Hold
- Given a cart with at least one line, when the person presses "Hold" (F8), then the cart is saved as a held bill without a number, without stock movement and without any payment; the screen shows a fresh sale and the note "Held: <n> items, <time>" (NEW); nothing is reserved (BR-11).
- Given held bills exist for the store, then a "Held bills" list on the sale screen shows each with the holder, time, number of items and age; the person can "Resume" or "Discard"; a cashier sees every held bill of the store (a pharmacist needs to take over a bill with a scheduled line, 80-3).
- Given a held bill is resumed, then the batches, rates and availability are checked again when the person saves; the cart changes are shown before saving ("Prices or batches changed. Check the bill and save again.").
- Given "Discard", then "Discard this held bill?" with Yes and No; Yes removes it from the list; an audit event records who and when; nothing was saved to stock.
- Given the number of held bills for the store is at the limit (OQ-80-40), then "Too many held bills. Finish or discard one." and the hold is refused.
- Given a held bill belongs to a patient, then it holds the patient id only; the held list shows the name masked as in 80-2 or "Walk-in".
- Given another clinic's or another store's held bill, then 404.

Cancel a saved bill
- Given the owner, or a pharmacist on the day the bill was saved (IST), opens a saved bill (found by number or from "Today's bills" in the sale screen), then the action "Cancel bill" is shown; for a pharmacist on a later day, a cashier, or anyone else, it is not shown and the API answers 403 `cancel-not-allowed` ("Only the owner can cancel a bill from an earlier day.") (matrix of mockup 05).
- Given the person cancels, then a reason chip is required (a NEW list "Cancel bill reason": Wrong item, Wrong quantity, Customer changed mind, Other; request to SAH-78-10) and the question "Are the medicines back on the shelf?" with Yes (units return to Available) and No (units go to Quarantine); the bill then shows "Cancelled" with who, when and the reason; its number is kept; the original ledger lines stay and new reversing lines "Counter sale · cancelled" point to them (BR-12).
- Given the bill had Schedule H1 or Schedule X lines, then reversing register rows are written (80-9, 80-10); for a Schedule X line a pharmacist and a second signer confirm the cancellation (80-10).
- Given the bill had udhar, then the customer's due goes down by that amount (80-4); given it was paid in cash, UPI or card, then the bill shows "Refund due ₹<amount>" until a pharmacist or cashier records the refund as in 80-18 (refund mode, credit note); the accountant confirms the GST treatment of a cancelled invoice (OQ-80-13, G-22).
- Given a bill that has a sales return, or is already cancelled, when someone tries to cancel it, then 409 `has-returns` or 409 `already-cancelled` and nothing changes.
- Given the person tries to edit a saved bill, then there is no edit action; the screen says "A saved bill cannot be edited. Cancel it and make a new bill."
- Given two people cancel the same bill at once, then the second gets 409 `changed` with the current status.
- Given the bill was added to an OPD bill (80-23), then it cannot be cancelled here ("Cancel it from the OPD bill." only when 80-23 is built).

Roles, errors
- Given a person not mapped to the store, then 404; mapped without the capability, 403 `store-permission-denied`.
- Given a slow or failed request, then "Could not save. Check your connection and try again."; the person sees the current status after reload.
- Given an audit, then every hold discard, cancel and refund-due is recorded with ids and outcome only (C-8).

## Notes for design
- A cancel never changes old ledger lines or register rows; it adds reversing ones (BR-12).
- The same-day rule uses the IST date of the bill. The cancel reason list is a request to SAH-78-10 (requirement, "Requests to other owners").
- Whether cancel also needs the approval engine is not decided; none is assumed (OQ-80-13, OQ-80-38).

### SAH-80-6 · As a seller, I want to give a discount within the unit's limit and to ask for approval above it, so that discounts are controlled and every exception has a name on it
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (summary line "Discount (chronic plan 5%) − ₹28.02"), `05-compliance-roles-and-audit.html` (matrix row "Discount above 10%"; audit tile "requested 15% discount · sent for approval · approved by Owner on mobile"), `14-approvals-inbox.html` (filter chip "Sales"; direct "Approve" button, left to SAH-80 by SAH-79-2) and `17-end-to-end-flow.html` (row "Counter sale · Approval: Discount above limit"). Package WP-J. BRD P2 "discount within limit", section 2 (₹ limit for discount), SAH-78 OQ-78-27. Depends on SAH-79-2 (engine and inbox).

## Acceptance criteria
- Given a cart, when the seller enters a discount percent in the "Discount" line (0 to 100, up to 2 decimals), then the summary shows "Discount" and the amount, the discount lowers each line's price in proportion before the GST is worked (BR-7, OQ-80-6), and the net payable updates. The label does not say "chronic plan"; no discount plan exists (OQ-80-8).
- Given the unit's SALE_DISCOUNT rule has a percent limit and the discount is at or below it, then it applies at once and nothing waits.
- Given the discount is above the limit, then the screen shows "This discount needs approval." and a button "Send for approval"; after sending, the bill shows "Waiting for approval" with the approver's role and the age; the cart cannot be saved while it waits; the request is also kept with the held cart if the seller holds the bill (80-5).
- Given the rule names the store's HOD (or current delegate) or the owner, when the request is sent, then it appears in that person's Approvals inbox under the new chip "Sales" with the Rule text "Discount above <limit>%" (the limit read from the unit's rule, never fixed) and the buttons "Approve" and "Reject" directly in the row (the mockup's direct buttons) as well as "Review" (SAH-79-2, BR-38).
- Given the approver approves, then the seller sees "Approved by <name>" and can save; the approval is for that discount percent and that cart; if the seller changes the lines or raises the percent, the approval is void and a new request is needed when the discount is still above the limit.
- Given the approver rejects, then a reason chip is required (the list of SAH-79 OQ-79-15 plus "Other" with a note of at least 4 characters); the seller sees the reason and can lower the discount or send a new request.
- Given the seller is the one who would approve (the owner asking for their own discount, or a HOD selling), when the request is sent, then the item is not listed for them (403 `self-approval` on a direct call; also a database check) and shows "Needs another approver" to the next approver, or to another owner (SAH-79 OQ-79-28); in a one-owner clinic the owner is told "Ask another approver, or set the discount rule to Lean." (OQ-80-38).
- Given the unit has not entered a percent limit (Sahaj ships none; SAH-78 G-4), then the rule default applies: every discount above 0% waits for the owner, and the screen says "The discount limit is not set. Ask your owner." (NEW; OQ-80-8). Given the preset Lean (level 0 for discounts), then any discount applies at once; the owner's rule screen shows a banner "Lean: discounts are not checked." (NEW; OQ-80-8f).
- Given a waiting item has passed its escalation time (SAH-79 BR-16), then it also shows to the next approver marked "Escalated" (worked out when the inbox is read; no timer).
- Given an approval or rejection, then the decision is saved with who, in what capacity (HOD, delegate, owner, escalated), when, the percent, the amount and the reason, and appears in the audit log of 80-19.
- Given a discount, then "Discount approved by <name>" is kept on the bill record; the printed invoice shows the discount and the net amount.
- Given two approvers decide at the same moment, then the first stands and the second gets 409 `already-decided`.
- Given a person who is not an approver, when they call the decision, then 403; a store not mapped, 404; another clinic's request, 404; a discount below 0 or above 100, 400.
- Given the approver is on a phone, then the row and the buttons are usable (BRD section 7).
- Given a slow or failed save, then "Could not save. Check your connection and try again." and the request is not lost.

## Notes for design
- Registers the transaction types SALE_DISCOUNT in the SAH-79-2 engine; the engine already keeps the rule version at submission, escalation at read time and append-only decisions.
- OQ-80-38 (matrix cells: cashier "Deny", owner "Allow", pharmacist "Needs approval") and OQ-80-8 are open: the recommended behaviour is that any seller may ask, the rule decides, nobody approves their own request.
- The percent limit is the owner's entry (SAH-78-9, OQ-78-27). Money in whole paise; percent `numeric(5,2)` (ADR-028 D10).

---

### SAH-80-7 · As a pharmacist, I want to offer and record an exact substitute when a medicine is out of stock or the patient asks, so that only the same salt, strength and form is ever supplied and the reason and the patient's agreement are on the bill
Priority: Should
Screen: P&I mockup `02-rx-linked-pos-billing.html` (card "Safe substitute": chips "Salt ✓", "Strength 40 mg ✓", "Form: EC tab ✓", "Release ✓"; table Option, MRP/tab, Stock, Margin; note "Substitution reason + patient consent stored on the bill"; cart chip "Substituted"; shortcut F6 "Substitute") and `03-hospital-pharmacy-opd-and-ipd.html` (chip "for Pantocid 40", queue chip "Substitute OK?"). Package WP-J. BRD Pharmacy rule "Substitution: only same salt, strength and dosage form; reason saved on the bill". Open: OQ-80-23.

## Acceptance criteria
- Given a pharmacist selects a line (or the product asked for is out of stock) and presses F6 or "Substitute", then a card "Safe substitute" opens with the sentence "<product> is out of stock. Only exact equivalents are offered." (or "Only exact equivalents are offered." when it is in stock).
- Given the card, then the options are the unit's active products with the same salt, the same strength and the same dosage form as the product (the normalised key of SAH-78 D3, manufacturer and brand ignored), with Available stock in this store, and with the same schedule as the product (NEW safety rule: a Schedule H1 or X product is replaced only by a product of the same schedule, OQ-80-23); the check chips "Salt ✓", "Strength <n> ✓" and "Form: <form> ✓" show for the chosen option; the chip "Release ✓" is not built (the form carries it).
- Given the options table, then each row shows Option (name, "selected" on the chosen one), "MRP/<unit>", Stock (Available in this store) and, for the owner and the pharmacist only, Margin (₹ per unit on landed cost, OQ-79-6, OQ-80-23d); a generic ("Jan Aushadhi type") appears like any product with the same salt, strength and form.
- Given no product qualifies, then "No exact equivalent in stock." and nothing is offered.
- Given the pharmacist picks an option, then a reason chip is required (the NEW list "Substitution reason": Out of stock, Patient preference, Cheaper equivalent, Prescriber advice; Other with a note of at least 4 characters; request to SAH-78-10) and a tick "Patient agrees" (NEW; both stored on the bill line); without them "Choose a reason and confirm the patient agrees." and nothing changes.
- Given the substitution, then the line shows the chip "Substituted" and the small text "Rx: <product asked for>"; the batch is picked by FEFO for the supplied product (BR-5); the bill line keeps the product asked for, the product supplied, the reason, the agreement and the pharmacist's name.
- Given the line is Schedule H1, then the register row names the drug supplied and the product prescribed (80-9).
- Given the person is a cashier, when they press F6, then the action is not shown and a direct call answers 403 `pharmacist-required` "A pharmacist must substitute." (OQ-80-23).
- Given Integrated mode and a prescription line (80-11, 80-12), then the substitution is recorded on that Rx line and the queue shows the chip "Substitute OK?" while a substitute is proposed and the patient has not agreed; Standalone: the product asked for is the first product added.
- Given the owner, then they cannot substitute (pharmacist-only), or can if the human allows (OQ-80-37); recommended: cannot.
- Given a product or schedule not in this clinic, then 404; stock of another store is never listed; a store the person cannot reach, 404.
- Given a slow list, then "Could not load. Try again."; the cart is unchanged.
- Given each substitution, then an audit event records ids, the reason key and the agreement, and no clinical text.

## Notes for design
- The match uses the same normalised key as the product duplicate check (SAH-78 D3); the architect decides how it is queried for speed.
- SAH-79 OQ-79-16 allows a looser replacement at the Central Store (a supply decision); this counter rule is exact. Recorded in the requirement, conflict 8.
- Whether a prescriber's consent is also needed, and the legal wording of the agreement, are for the clinical lead and the lawyer (OQ-80-23, G-25).

---

### SAH-80-8 · As a pharmacist, I want a sale or an issue to be stopped when the patient has a recorded allergy to the medicine, and to override it only with a reason, so that no patient is handed a medicine they are allergic to by mistake
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (patient line "Allergy on EMR: Penicillin"; card "Clinical safety checks": "Blocked: Amoxicillin + Clavulanic acid — patient has a Penicillin allergy recorded in EMR. Pharmacist override needs a reason and is logged.") and `05-compliance-roles-and-audit.html` (row "Override allergy / H1 block": Owner Deny, Pharmacist "With reason · logged"; audit tile "overrode allergy block on bill"). Not built: "Interaction & duplicate-therapy check" and "Dose vs Rx" (Later). Touch point T3 (TP-3): reads an existing port, no OPD or IPD change. Package WP-J. BRD Pharmacy rule "Allergy (Integrated mode only)". Open: OQ-80-22 (clinical).
Integrated: checked. Standalone: not checked; the customer card has an allergy note, shown only.

## Acceptance criteria
- Given Integrated mode and a patient picked (80-2), then the patient card shows "Allergy on EMR: <text as recorded>" in red when there is one, "No allergy recorded" when the record says none, and "Allergy not checked" when the record cannot be read (OQ-80-22); the text is read through the patient port and is never logged.
- Given a product is added whose salt or generic name matches the allergy text (keyword match, ignoring capital letters, OQ-80-22), then the line is not added and a red notice shows "Blocked: <product> — patient has a <allergy as recorded> allergy recorded in EMR." with, for a pharmacist, the action "Override with a reason"; for anyone else the text "A pharmacist must decide." and no action.
- Given the pharmacist chooses "Override with a reason", then a reason chip is required (NEW list "Allergy override reason": Prescriber confirmed, Patient has taken it safely before, Allergy entry is wrong, Other with a note of at least 4 characters; OQ-80-22) and the line is added with the chip "Allergy override"; the reason chip, the optional note and the pharmacist's name are saved on the bill line; the audit event holds ids and the reason key only, not the allergy or the note (BR-34; the mockup's free-text reason is a deviation, OQ-80-22).
- Given the owner or a cashier, when they call the override, then 403 `pharmacist-required`; the owner has "Deny" in the mockup matrix.
- Given the patient's allergy was recorded or changed after the line was added, when the bill is saved, then the server checks again and blocks the save with the same notice if a line now matches.
- Given the patient record cannot be read (the port fails), then "Allergy not checked." shows on the card and the sale continues, and the bill carries the mark "Allergy not checked" (BRD section 6: pharmacy keeps working when OPD or IPD is unreachable; feasibility 5); a pharmacist sees the mark on the saved bill and in the day's list.
- Given a walk-in (no patient), then the card says "No patient selected. Allergy not checked." and nothing is blocked.
- Given Standalone mode, then no allergy check runs and no allergy block appears; the customer card's allergy note (80-2) shows on the sale screen as "Allergy note (not checked): <text>".
- Given a prescription line or an IPD order line (80-11, 80-13), then the same check runs when the patient is loaded and at the issue, and a blocked line shows the same notice in the queue ("Needs action").
- Given a drug class cannot be matched by keyword (for example penicillin and amoxicillin), then it is not blocked unless the human supplies the class list (OQ-80-22); the recommended answer is stated, not assumed, and QA tests the keyword rule only.
- Given an override is used, then the day's list for the owner and pharmacist (80-19 audit log) shows it with the bill number, the store and the reason chip.
- Given a person who is not mapped to the store, then 404; another clinic's patient, 404; a slow patient read, "Could not load. Try again." and the sale is not blocked.

## Notes for design
- Reads `patient.api.PatientClinicalPort.find` (exists; free text allergy and a status; feasibility 3). A false negative is possible with free text; the block is the safe direction (patient-safety points).
- The override reason list is a request to SAH-78-10. OQ-80-22 is open (clinical lead); G-24.

---

### SAH-80-9 · As a pharmacist, I want every Schedule H1 sale to write its register entry by itself, with the prescriber and the patient, so that nobody keeps a paper register and a missing prescriber is stopped at the counter
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (chip "Sch. H1" and the note "Schedule H1: prescriber, patient and quantity auto-posted to the H1 register. Rx copy attached."), `05-compliance-roles-and-audit.html` (card "Schedule H1 register · auto-generated from bills", chip "23 entries today · 0 missing prescriber", columns Date, Bill no., Patient, Prescriber & reg. no., Drug, Batch, Qty, Rx copy) and `01-pharmacy-command-center.html` (card "Compliance health: Schedule H1 register"). This story also builds the shell of the screen "Registers & compliance" (cards are added by 80-10 and 80-19). Package WP-J. BRD P5, AC-P5. Open: OQ-80-17 (legal).

## Acceptance criteria
At sale
- Given a cart line of a product with schedule H1, then the line shows "Sch. H1" and a card "Prescription" opens on the sale screen asking for: prescriber name (up to 80 characters), prescriber registration number (up to 30), prescription date, and the patient's name and age (read from the patient in Integrated mode, typed in Standalone or for a walk-in) (OQ-80-17; the mockup's mask "MMC-[NO.]" is a placeholder, not a format).
- Given an e-Rx is loaded (80-12), then the prescriber name and registration number come from the prescriber's profile through the team port (read, not typed) and the Rx date and time from the e-Rx; Standalone or a paper Rx: typed on the card.
- Given a line is H1 and the prescriber name or registration number is missing, when the person saves, then the line is blocked: "Prescriber name and registration number are needed for Schedule H1."; a pharmacist may "Override with a reason" (chip list as 80-8, NEW "H1 override reason"; the owner and a cashier may not, 403 `pharmacist-required`); the override is saved on the line and the register row.
- Given the bill is saved, then in the same step one append-only register row per H1 line is written: date and time, bill number, patient name and age, prescriber and registration number, drug (the product supplied, and the product asked for when substituted), batch, quantity, the prescription (the e-Rx reference, or "Typed"), the pharmacist and the store; no manual entry is needed; if the save fails no row is written.
- Given a bill with an H1 line is cancelled (80-5) or the line is returned (80-18), then a new reversing row is added that points to the original and says "Cancelled" or "Returned"; the original row stays (BR-16, OQ-80-17).

Register view
- Given the owner, a pharmacist or the HOD of the store opens "Registers & compliance", then the card "Schedule H1 register · auto-generated from bills" shows the chip "<n> entries today · <n> missing prescriber" (the second number counts overrides) and a table with the columns above, newest first, 25 rows a page; the patient name is shown to these roles only and the read writes a record-access entry (OQ-80-21).
- Given the filters (date range up to 12 months, drug, prescriber), then the table narrows; "Download CSV" exports the filtered rows including the patient name; each export is audited (OQ-80-21); the browser print view prints the register.
- Given the "Rx copy" column, then "e-Rx" opens the prescription lines read-only through the OPD port (Integrated) and "Typed" shows the typed details; no scanned image is stored or shown (no documents service, OQ-80-2).
- Given no rows, then "No Schedule H1 sales in this range."
- Given no row can be changed or deleted, then there is no edit or delete action and the database refuses an update or delete; a mistake is a new reversing row.
- Given a cashier, then the card is not shown (403 on the API); another store of the clinic that the person is not mapped to answers 404; another clinic's rows, never shown.
- Given the Command centre (80-20), then its card "Schedule H1 register" says "Up to date" with "Auto-filled from <n> bills today" and, if any override exists today, "<n> overrides to review".
- Given a slow or failed load, then "Could not load. Try again."

## Notes for design
- The register is not a separate document; it is a set of rows written in the sale transaction (feasibility 7.7). The architect decides the table and the append-only protection.
- Retention, columns and reversing rows are the lawyer's and pharmacist's decision (OQ-80-17; G-23); nothing is deleted until the human sets a period (G-11).
- The patient name is held in the register (law) but never in the ledger, logs or audit text (BR-34).

---

### SAH-80-10 · As a pharmacist, I want to sell or issue Schedule X medicines only with a second signature and to see the Schedule X balance register, so that controlled stock is never handled by one person alone and the book matches the shelf
Priority: Must
Screen: P&I mockup `05-compliance-roles-and-audit.html` (card "Schedule X / NDPS balance register": Drug, Opening, Received, Issued, Book, Physical, chips "✓" and "101 · review"; the note "two-person sign-off on issue and on empty-ampoule return"), `03-hospital-pharmacy-opd-and-ipd.html` (note "Cold-chain and Schedule X items need the pharmacist's approval") and `01-pharmacy-command-center.html` (card "Schedule X / NDPS stock: Balanced · Physical = book · double-signed"). Package WP-J. BRD section 2 fixed rule 2, AC-P5. Carries over G-6 and OQ-79-8 (the second signer), R2-13 (NDPS). Open: OQ-80-18 (legal and clinical). Depends on SAH-79-7 (the second-signature mechanism built for receiving Schedule X), SAH-79-17 (physical count).

## Acceptance criteria
Sale or issue
- Given a cart or an issue line (80-3, 80-14) has a product of schedule X, then only a person with the team role pharmacist mapped to the store may complete it; the owner and a cashier get 403 `pharmacist-required` "Only a pharmacist can sell a Schedule X medicine." (BR-17).
- Given the pharmacist saves, then a step "Second signature" asks a different active person of the unit to pick their name and enter their colleague PIN (the platform's colleague PIN check); a wrong PIN shows "The PIN is not correct."; the same person as the first signer gets 403 `second-signer-same` "The second signature must be a different person."; no sale is saved without it.
- Given who may be the second signer, then recommended: a different active person of the unit who is a pharmacist or an owner (OQ-80-18, G-6; the first person must still be a pharmacist); a person who is not active, or who cannot sign in, cannot sign.
- Given both signatures, then both names are stored on the bill line and on the register row, with the time; a database check holds `second signer ≠ first signer` (BR-17).
- Given a Schedule X line, then the prescription card of 80-9 is required in the same way (prescriber, registration number, date, patient and age); a missing one blocks the line; there is no override for Schedule X (NEW; OQ-80-18).
- Given a sales return, a cancel or a ward return of a Schedule X item (80-5, 80-15, 80-18), then the same pharmacist and second signature are required and reversing register rows are written.
- Given the SAH-79 consumption (79-16), receipt (79-7), count (79-17) or adjustment (79-5) of a Schedule X item, then they already follow the same rule; this story adds nothing to them but reads them for the register.

Balance register
- Given the owner, a pharmacist or the store's HOD opens "Registers & compliance", then the card "Schedule X / NDPS balance register" lists, per product and store, Opening (balance at the start of the date range), Received (receipts, transfers in, accepted returns, positive adjustments), Issued (sales, issues, consumption, transfers out, negative adjustments), Book (Opening plus Received minus Issued, equal to the ledger's Available balance at the end of the range) and Physical (the quantity of the latest stock count of that store, SAH-79-17, with its date).
- Given Physical equals Book, then the cell shows "<n> ✓"; given it differs, then "<n> · review" with the difference; given no count exists, then "Not counted".
- Given the date range (default the current month, at most 12 months), the store and the product filters, then the table narrows; "Download CSV" exports it (audited; names of products and quantities only; patients are not in this table).
- Given the NDPS flag (OQ-80-18), then adopted products show the catalogue's flag and own products the yes or no of the SAH-78 touch-up (TP-10); the register is titled "Schedule X / NDPS balance register" as the mockup, with the lawyer to confirm that one register serves both.
- Given the note under the table, then it reads "Two-person sign-off on issue. The register is kept as per applicable state rules." (the mockup's "separate locked stock" is a physical practice and is not a feature; "empty-ampoule return" is in 80-15).
- Given no Schedule X product has stock or movement, then "No Schedule X movement in this range."
- Given a cashier, then the card is not shown; another store the person is not mapped to, 404; another clinic, never shown.
- Given a slow or failed load, then "Could not load. Try again."
- Given the Command centre (80-20), then its card "Schedule X / NDPS stock" says "Balanced" with "Physical = book · double-signed" when every counted row matches, or "<n> to review".

## Notes for design
- The register is computed from the ledger and the latest count (no separate stock); the architect decides whether it is a query or stored rows.
- OQ-80-18 (second signer, NDPS, empty ampoule) is open and is the lawyer's and pharmacist's; until it is answered the recommended rule protects Schedule X lines. G-23.
- A nurse cannot consume a Schedule X item at a ward store (SAH-79 OQ-79-8): the human may need to decide for ward stock; not changed here.

### SAH-80-11 · As a pharmacist, I want to see the signed prescriptions from OPD waiting at the pharmacy and to pick them by rack before the patient arrives, so that the patient is served faster and nothing is typed twice
Priority: Must
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (tab "Outpatient (OPD) · 5 · Prescriptions from doctor consultations"; queue with status chips New, Picking, Ready, Needs action; detail card with steps "1 · Received 2 · Checked 3 · Picked & scanned 4 · Bill & hand over"; table Medicine, How to take (printed on label), Rack, Batch, Qty, Pick check; buttons "Print labels", "Give part now, rest later", "Bill & hand over F9"; search "Scan token / UHID / wristband") and `01-pharmacy-command-center.html` (card "Live prescriptions from Sahaj OPD / IPD"). Touch point T2 (TP-2): new port in `opd.api`; needs the human's approval (C-9, OQ-80-29). Package WP-K. BRD P3, AC-P2.
Integrated only: hidden in Standalone (the typed Rx of 80-12 replaces it).

## Acceptance criteria
- Given the unit is Integrated, TP-2 is approved and the person is a pharmacist, a cashier or an owner mapped to a store with "Sale allowed" Yes, then "OPD / IPD dispensing" shows the tab "Outpatient (OPD) · <n>" with the line "Prescriptions from doctor consultations"; Standalone or TP-2 not approved: the tab is not shown.
- Given a doctor signs a prescription that has a prescription section, then it appears in the queue when the queue is next read (pulled through the OPD port, no push; the screen refreshes on its own at an interval the architect sets and has a "Refresh" button; OQ-80-29); only signed records of this clinic appear.
- Given the queue, then each row shows "<token> · <patient name>" (the token is a pharmacy number per day, "T-041", OQ-80-24c), a status chip (New, "Picking · <person>", Picked, "Substitute OK?", "Needs action") and the line "<doctor> · <n> items · waiting <n> min"; rows are oldest first; the count chips above the list (New, Picking, Ready, Needs action) show how many; a prescription "Ready" for a long time shows "ready <n> min ago" only (no "Not collected" status, no SMS, OQ-80-3).
- Given the search "Scan token / UHID / wristband", then typing or a keyboard-style scanner entry of a token or a UHID finds the row (no camera scan).
- Given a row is selected, then the detail shows "<token> · <name> · <age> <sex>", "UHID <n> · <doctor> · e-Rx <time> · Allergy: <text>", the chip "All checks passed" or "<n> to check", and the four steps with who and when ("Received 10:42 from OPD", "Checked: allergy and products", "Picked <n> of <m> · <person>", "Bill and hand over"); the diagnosis is not shown (OQ-80-24).
- Given the lines, then the table shows Medicine (the prescribed medicine and, when mapped, the pharmacy product), "How to take (printed on label)" (the dose pattern, duration, timing and instructions as written by the doctor), Rack (this store's rack, SAH-78-5), Batch (the FEFO batch, shown for the pick), Qty (typed by the pharmacist; empty at the start, OQ-80-24) and "Picked" (who and when); the column "Pick check" with "Scanned ✓" is not built (barcode pick check is Later); a Schedule H1 or X line shows its chip.
- Given a line has no link to a pharmacy product, then it shows "Not mapped" and the action "Choose product"; the pharmacist searches the unit's products, picks one and the link is saved for the unit (BR-25, OQ-80-34) and audited; until mapped the line cannot be picked.
- Given the quantity is typed, then the line shows "In stock", "<n> short" or "Out of stock" for this store; the quantity is a whole number of the issue unit; stock is not reserved by picking (stock moves only when the bill is saved, 80-12).
- Given a person mapped to the store as pharmacist, cashier or store user presses "Mark picked" on a line, then the line shows "Picked · <person>, <time>"; unmarking is allowed until billing; when every line is picked the step "Picked" is done.
- Given the pharmacist presses "Print labels", then the browser print view opens one label per line with the patient name, medicine, how to take (as written), batch, expiry and the pharmacy name, in English (labels in Marathi or Hindi are Later); the name is on the label only and in no log.
- Given the patient's allergy matches a line (80-8), then the row shows "Needs action" and the line the block notice; an owner or cashier cannot override.
- Given two people open the same prescription, then the first to start billing owns it and the second sees "Already being billed by <name>." (409 `changed`).
- Given the person opens a prescription, then a record-access entry is written (BR-34); the port is read at open and the lines are not copied into a log.
- Given the OPD port fails or IPD or OPD is switched to read-only, then "Prescriptions could not be loaded. Try again." and nothing stale is shown; the counter still sells (80-3).
- Given the queue is empty, then "No prescriptions waiting."
- Given a cashier, then they see the queue and mark picking; a line of schedule H, H1 or X is completed by a pharmacist (80-3).
- Given a person not mapped to the store, then 404; mapped without the capability, 403 `store-permission-denied`; another clinic's prescription, 404; a prescription already billed in full, no longer listed.

## Notes for design
- Needs the new `opd.api.PrescriptionQueueReader` (TP-2): signed records with a prescription section for a day with medicine id or name, dose pattern, duration, timing, instructions, prescriber id, patient id and signed time (feasibility T2). The Rx has no quantity (feasibility 3). Written back to OPD: nothing (OQ-80-29).
- The pharmacy keeps its own state per prescription line (new, picking, picked, billed, part supplied) and the link table of BR-25. The "Speed today" band (OPD wait, targets) is not built (OQ-80-3).
- Until TP-2 is approved this story is not built and the tab is not shown.

---

### SAH-80-12 · As a pharmacist or cashier, I want to bill a prescription from the queue, or a typed or paper prescription, and to supply part now and the rest later, so that the bill follows the doctor's prescription without retyping
Priority: Must
Screen: P&I mockup `02-rx-linked-pos-billing.html` (card "Patient and prescription": "e-Rx loaded from OPD · Dr. A. Mehta (Reg. MMC-[NO.]) · 17 Nov, 10:42", buttons "View Rx image", "Patient history"; cart lines with "Rx: Pantocid 40") and `03-hospital-pharmacy-opd-and-ipd.html` (button "Bill & hand over F9", "Give part now, rest later", "Paper Rx / walk-in"). Touch point T2 for the e-Rx part (TP-2, approved first); the typed part needs none. Package WP-K. BRD AC-P2: an OPD e-Rx loads into the bill in Integrated mode; a typed Rx in Standalone.
Integrated: from the queue. Standalone: typed or paper Rx on the sale screen.

## Acceptance criteria
From the queue (Integrated, TP-2 approved)
- Given the pharmacist or cashier presses "Bill & hand over" (F9) on a prescription, then the sale screen of 80-3 opens with the patient, the prescriber, the Rx date and time and the lines already in the cart, with the quantities typed in the queue and the batches picked by FEFO now; the card says "e-Rx loaded from OPD · <doctor> (Reg. <number>) · <date, time>" (the number read from the doctor's profile, OQ-80-17).
- Given the cart, then a line has the chip "Rx" and, when the supplied product differs from the prescribed one, "Substituted" and "Rx: <prescribed>" (80-7); the person may also add a medicine not on the prescription; it shows "Not on Rx".
- Given the button "View prescription" (the mockup's "View Rx image", OQ-80-2), then the prescription lines open read-only; no image is shown.
- Given the person saves, then the bill is saved as in 80-3, the H1 and X rows are written with the prescription as the e-Rx reference (80-9, 80-10), the Rx lines get the status "Supplied <n> of <m>", and the prescription leaves the queue as "Handed over" when every line is fully supplied.
- Given the person supplies less than prescribed on a line, or leaves a line out ("Give part now, rest later"), then on save the prescription stays in the queue as "Part supplied" with the remaining quantity per line; a later bill for it loads only the remaining lines; the pharmacist may close the rest with a reason chip (NEW list "Rx closed reason": Patient declined, Out of stock, Doctor changed the order, Other) so that it leaves the queue; no timer closes it (OQ-80-24b).
- Given another person has billed the same prescription meanwhile, then 409 `changed` "This prescription was already billed." and the cart is not saved.
- Given the allergy check blocks a line (80-8), the prescriber is missing for an H1 line (80-9) or the line is Schedule X (80-10), then those rules apply as on the sale screen.
- Given the bill is saved, then the pharmacy bill stands alone and nothing is written to the OPD bill (default, DECIDED); if the unit uses 80-23, that story applies.
- Given nothing is written back to OPD, then no OPD screen shows "dispensed" (OQ-80-29).

Typed or paper Rx (both modes)
- Given the person presses "Paper Rx / walk-in" (or, in Standalone, adds a medicine that needs a prescription), then the sale screen shows a card "Prescription" with prescriber name (up to 80 characters), prescriber registration number (up to 30), prescription date, and notes none; the card is required only when the cart has a line of schedule H1 or X (80-9, 80-10); for others it is optional.
- Given a typed Rx, then the register rows say "Typed"; no image or scan is stored; "Rx copy: Scan" is not built.
- Given the person is a cashier and the cart has a scheduled line, then 403 `pharmacist-required` (80-3).

Mode, errors
- Given Standalone mode, then the queue, the "e-Rx loaded" card and the "Patient history" button are not shown; given Integrated with the OPD port failing, then the sale continues with the typed Rx and the bill shows "Link to patient pending" if the patient could not be read (80-2).
- Given a prescription of another clinic, then 404; of a store the person cannot reach, 404; a quantity not a whole number, 400.
- Given a slow or failed save, then "Could not save. Check your connection and try again." and the cart stays.

## Notes for design
- The link between the prescribed `opd.medicine` and the pharmacy product is the pharmacy-owned link table (BR-25, OQ-80-34). The "dispense status" is pharmacy-owned (OQ-80-29).
- The quantity is typed (auto supply quantity is Later); the prescribed text is shown beside it. The "Dose vs Rx" card is not built.
- Needs 80-3, 80-8, 80-9 and, for the e-Rx part, 80-11.

---

### SAH-80-13 · As a pharmacist, I want to see the IPD medicine orders that wards have raised, by priority, with what changed since the last supply, so that STAT and urgent orders are supplied first and nothing new, changed or stopped is missed
Priority: Must
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (tab "Inpatient (IPD) · 9 · 1 STAT · 2 urgent · 2 discharges"; priority lanes STAT, Urgent, "2 PM ward round", Discharge; queue rows; detail "<patient> · <age> <sex> · IPD-7781", "Ward 3B, Bed 12 · Dr. A. Mehta · Allergy: none recorded · Payer: TPA"; table "What changed since the last supply": Change (New, Changed, Stopped), Medicine and order, Supply until next round, Batch, Check) and `01-pharmacy-command-center.html` (rows "Urgent · stat order"). Touch point T4 (TP-4): new port in `ipd.api`; needs the human's approval (C-9, OQ-80-30). Package WP-M. BRD P3 "IPD orders by priority (STAT, urgent, routine, discharge)", T4.
Integrated only, IPD On and not read-only; hidden otherwise.

## Acceptance criteria
- Given the unit is Integrated, IPD is On, TP-4 is approved and the person is a pharmacist mapped to the store (or the owner, who may look but not dispense), then the tab "Inpatient (IPD) · <n>" shows with a line such as "<n> STAT · <n> urgent · <n> discharges"; Standalone, IPD off or read-only, or TP-4 not approved: the tab is not shown.
- Given the lanes above the queue, then four lanes show the counts: STAT, Urgent, Routine (grouped by ward; one tote per ward) and Discharge; each STAT row shows how long it has waited ("waiting 12 min"); the mockup's "due in 12 min · target 15" and "within 1 hour" need target settings that no master holds and are not built (OQ-80-25b); the mockup's "2 PM ward round" is shown as "Routine · <ward> · <n> patients · <n> items" (round time is not modelled).
- Given the queue, then rows are ordered STAT, Urgent, Routine, Discharge, then oldest first; a row shows "<ward> · Bed <n> · <patient name>" with the priority chip and a line such as "Inj. Noradrenaline · ordered 10:44 · Dr. S. Kulkarni", "3 order changes after morning round", or "New admission · 6 items · first doses".
- Given only orders that the IPD pharmacist check has verified are listed for supply (SAH-31, SAH-76, read from IPD, OQ-80-25a), then an order not yet verified shows "Not verified in IPD yet" with no supply action; an order on hold in IPD shows "On hold" with no supply action.
- Given a row is selected, then the detail shows "<patient> · <age> <sex> · <admission number>", "<ward>, Bed <n> · <doctor> · Allergy: <text>" (or "None recorded" or "Allergy not checked", 80-8); the "Payer: TPA" and "TPA drug limit" are not built (OQ-80-3).
- Given the table "What changed since the last supply", then each line shows Change (chip New, Changed or Stopped), Medicine and order ("1 g IV every 12 h × 5 days"; "IV once daily → tablet once daily" for a change), "Quantity to supply" (typed by the pharmacist; the mockup's "calculated from dose × frequency" is Later, OQ-80-24), Batch (FEFO, shown when stock exists) and Check (OK, "Return pending", "Allergy block", "Not mapped", "Short").
- Given an order is Stopped, then the line shows "Stopped <time>" and, when units are with the ward, "Return <n> from ward" linking to the ward returns of 80-15.
- Given a drug on an order has no link to a pharmacy product, then it shows "Not mapped" with "Choose product" and the link is saved as in 80-11 (BR-25).
- Given the queue is refreshed, then the new orders appear without a reload at an interval the architect sets; a "Refresh" button exists; the port is polled, not pushed.
- Given a record-access entry is written when a patient's orders are opened (BR-34); names are on screen only.
- Given the port fails, then "Orders could not be loaded. Try again." and nothing stale is shown; given no orders, "No orders waiting."
- Given a person not mapped to the store, then 404; a cashier, 403 (no IPD dispensing); another clinic's order, 404.
- Given the Discharge lane, then its rows come from 80-17 (T7) when approved; otherwise the lane is not shown.

## Notes for design
- Needs `ipd.api.PharmacyOrderFeed` (TP-4): orders since a change token with admission, ward, bed, drug key, version, priority, status and the IPD pharmacist-check state (feasibility T4). No IPD screen changes. "Discharge" is not an order priority in IPD (feasibility 3); the lane is fed by 80-17.
- Whether the IPD pharmacist check is repeated at the pharmacy is OQ-80-25a (recommended: not repeated; read).
- Until TP-4 is approved this story is not built.

---

### SAH-80-14 · As a pharmacist, I want a pick list by rack and a single action to dispense the picked IPD items to the ward, so that the right medicines leave the store, the stock goes down by FEFO and the ward is supplied
Priority: Must
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (steps "Verified · Picked & scanned · Sent to ward · Received by nurse · Charged"; buttons "Ask doctor (message)", "Print patient labels", "Verify & dispense to ward"; the note "When the nurse scans the bag at the ward, the items post to the IPD running bill") and `17-end-to-end-flow.html` (row "IPD issue: Pharmacy · Available to Patient (IPD bill); approval: Pharmacist verify"). Touch point T4 (TP-4). Package WP-M. BRD P3 "pick list by rack; issue to patient". Depends on 80-13, SAH-79-1, 79-3; the charge comes from 80-16.

## Acceptance criteria
- Given a pharmacist opens an order set (a patient, or a ward's routine round), then the action "Print pick list" opens the browser print view listing lines sorted by rack (this store's rack, SAH-78-5), with medicine, quantity, batch (FEFO) and ward and bed, grouped by ward for routine rounds (BRD P3 pick list by rack).
- Given a line, then "Mark picked" records who and when; the step "Picked" shows "<person>, <time>"; scanning ("Picked & scanned") is not built (OQ-80-3).
- Given the pharmacist presses the primary button, then its label is "Dispense to ward" (the mockup reads "Verify & dispense to ward"; the IPD pharmacist check is read from IPD and is not repeated here, so the word "Verify" is dropped as a deviation to approve, OQ-80-25a); a confirmation lists each line with quantity and batch.
- Given the pharmacist confirms, then in one all-or-nothing step the Available stock of the store drops by FEFO (BR-5), the ledger lines "IPD issue" are written with the admission number as reference (never a name), an issue slip number is taken from the series "IPD issue slip" (NEW; 80-1, OQ-80-7), the issue record is saved ("Sent to ward" with who and when), and the charge is posted through 80-16 or saved as "Charge pending" when TP-5 is not approved.
- Given the issue slip, then "Print patient labels" and the slip print through the browser print view (template of SAH-78-10, issue slip) with patient name, admission number, ward and bed, medicine, quantity, batch and expiry; English only.
- Given the stock is short for a line, then the line shows "<n> short"; the pharmacist may "Issue available now": the available quantity is issued and the rest stays on the queue as the remaining quantity (no automatic back-order); an out-of-stock line is left out and the order stays open.
- Given a line is Schedule X, then the second signature of 80-10 is required; given an allergy block, 80-8 applies.
- Given the order changed in IPD after the pharmacist opened it (a new version), when they press dispense, then 409 `order-changed` "This order changed. Reload it." and nothing is issued.
- Given two pharmacists dispense the same order, then the second gets 409 `changed` "Already dispensed by <name>."
- Given an expired or Quarantine batch, then it is never issued; the line is picked from the next batch.
- Given the last step "Charged", then it shows "Charged to the IPD bill" once 80-16 has posted, or "Charge pending" before; the step "Received by nurse: scan at ward" is not shown (no ward-side scan exists, OQ-80-25d); the button "Ask doctor (message)" is not shown (no messaging in phase 1).
- Given a person who is not a pharmacist (a cashier, a store user), then no dispense action (403 `pharmacist-required`); a store with "Sale allowed" No, 409 `sale-not-allowed`; not mapped, 404.
- Given a quantity that is not a whole number or above the limit (OQ-80-40), then 400.
- Given the issue is saved, then an audit event records the admission id, count of lines and outcome, no clinical text (C-8).
- Given a slow or failed save, then "Could not save. Check your connection and try again."; the pharmacist reloads and sees whether the issue was saved.

## Notes for design
- A direct issue to the patient does not move stock to a ward store (feasibility and `17-end-to-end-flow.html`: Pharmacy Available to Patient); ward stock by indent is SAH-79.
- OQ-80-25 (verify versus dispense, rounds, ward confirmation) is open.
- Until TP-4 is approved this story is not built.

---

### SAH-80-15 · As a pharmacist, I want to record and accept returns of unused medicines from wards, and to accept counter returns waiting for me, so that good stock goes back on the shelf, doubtful stock is quarantined and the patient is credited
Priority: Must
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (tab "Returns · 4 · From wards and OPD counter"; card "Returns waiting to be accepted" with columns From, Patient, Medicine, Qty, Batch, Reason, Condition, Credit and the buttons "Accept" and "Record wastage"; note "Only the same batch that was issued can be returned. Cold-chain and Schedule X items need the pharmacist's approval") and `17-end-to-end-flow.html` (row "Ward return"). Touch point T5 for the credit (TP-5, 80-16). Package WP-M. BRD P3 "ward returns", AC-P3. Depends on 80-14, 80-18 (counter returns), SAH-79-3, 79-4, 79-14. Open: OQ-80-26.
Integrated: ward returns and the credit. Standalone: only counter returns waiting (the tab is the list of 80-18 there).

## Acceptance criteria
Recording a ward return (NEW: the mockup draws only the waiting list; OQ-80-26e)
- Given a person mapped to the pharmacy store (a pharmacist or a store user) presses "Record ward return", then a form asks for the admission (search by admission number or patient name among patients that were issued items), the issued item and batch (a list of this admission's issues, so only the same batch can be chosen), the quantity (a whole number, not above issued minus already returned), a reason chip (the NEW list "Ward return reason": Medicine stopped, Discharge, Not used; request to SAH-78-10) and a condition chip (Sealed, Opened); saving adds a row to "Returns waiting to be accepted" and moves nothing.
- Given the quantity is above issued minus returned, then 400 "You can return at most <n>." and nothing is saved.

Waiting list
- Given the pharmacist opens the tab "Returns · <n>", then the card "Returns waiting to be accepted" shows the line "Scan each item: good stock goes back to the shelf, the patient's bill is credited automatically" (the credit part only when TP-5 is approved) and the table: From (ward or "OPD counter"), Patient (name and admission number, or customer and bill number), Medicine, Qty, Batch, Reason, Condition chip (Sealed, "Opened · wastage"), Credit (₹ per the price of OQ-80-15, or "—") and the actions.
- Given a sealed row, when the pharmacist presses "Accept", then in one step the units go to Available in this store (ledger lines "Ward return · accepted", reference the admission number), the return shows "Accepted" with who and when, and the credit is posted through 80-16 (or saved as "Credit pending" when TP-5 is not approved).
- Given an opened row, then "Record wastage" moves the units to Quarantine with the reason "Wastage" (ledger lines), posts no credit, and leaves the units for the write-off flow of SAH-79-14; the pharmacist may also "Accept" an opened item into Quarantine (doubtful stock never goes to Available); the choice is saved with the reason (OQ-80-26).
- Given a row the pharmacist does not accept, then "Not accepted" with a reason chip; nothing moves and the ward is told on the row's history.
- Given a Schedule X item, then only a pharmacist accepts it and the second signature of 80-10 is required, also for the return of an empty ampoule ("Empty ampoule returned", a NEW condition chip for Schedule X; OQ-80-18c); the register rows of 80-10 get a reversing line; a cold-chain item needs the pharmacist (there is no cold-chain flag; the product's "returnable" flag decides, SAH-78).
- Given a counter return waiting (80-18), then its row shows From "OPD counter", the customer and the bill number, the amount to refund, and "Accept"; accepting finishes the refund and stock movement of 80-18.
- Given the same batch rule, then a row for an item or batch that was not issued to that admission cannot exist; a direct call answers 409 `not-issued`.
- Given two people accept the same row at once, then the second gets 409 `changed`.
- Given a store user (not a pharmacist), then they may record but not accept (403 `pharmacist-required`); a cashier sees nothing here except counter returns they started; not mapped, 404.
- Given the count in the tab, then it counts rows waiting; with none, "No returns waiting."
- Given each step, then an audit event records ids and outcome (no names).

## Notes for design
- The mockup does not say who creates a ward return; the ward has no screen for it in this mockup. The recommended answer is that the pharmacy records it on arrival (OQ-80-26e). A ward-side request would be a nursing or IPD change (not proposed).
- Credit posts only through TP-5; the credit is never above what was charged (80-16).

---

### SAH-80-16 · As a pharmacist or the owner, I want pharmacy issues, ward returns and patient-wise ward consumption to post their charges and credits to the patient's IPD running bill together with the stock movement, so that no medicine given to an admitted patient goes unbilled
Priority: Must
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (step "Charged · IPD running bill"; "the items post to the IPD running bill and appear in the nursing medication chart with the batch number"; Returns: "the patient's bill is credited automatically"), `17-end-to-end-flow.html` (rows "IPD issue", "Ward consumption") and `11-store-stock-and-ward-consumption.html` (consumption note "Charged to the IPD running bill and shown in the nursing medication chart"). Touch point T5 (TP-5, high risk) and T6 (TP-6): the human approves the IPD change first (C-9, OQ-80-31, OQ-80-32). Package WP-M. BRD T5, AC-P3. Carries over OQ-79-23 and the "Charge pending" entries of SAH-79-16. Depends on 80-14, 80-15, SAH-79-16.
Integrated only, IPD On; hidden otherwise.

## Acceptance criteria
- Given TP-5 is approved, when a pharmacist saves an IPD issue (80-14), then in the same transaction a charge line per issued line is posted to the admission's IPD running bill through the port (product name, quantity, price per OQ-80-15, the admission id), so that stock and charge commit together or not at all; the issue shows "Charged".
- Given the port refuses (the admission is discharged and its final bill is frozen, or IPD is read-only), then the whole issue fails with 409 `ipd-bill-closed` "The patient's bill is closed. Ask the ward or the owner." (NEW text); nothing is issued and nothing is posted.
- Given a ward return is accepted (80-15), then a credit line is posted to the same bill for the units accepted, at the price that was charged, never more than charged minus already credited (409 `credit-above-charge` otherwise); wastage and not-accepted rows post no credit.
- Given TP-5 is not approved or the port is unavailable, then issues save as "Charge pending" and returns as "Credit pending"; nothing is posted; the owner and pharmacist see a list "Waiting to post" with the count; the Integration-mode guard of 80-1 counts them.
- Given the action "Post waiting charges" (pharmacist or owner), then every waiting charge and credit is posted in the order it was saved, each exactly once (keyed by its issue or return line), all or nothing for one line; lines that still cannot post stay waiting with their reason ("Bill closed", "Admission not found"); nothing is written off by itself.
- Given patient-wise ward consumption was saved by SAH-79-16 with "Charge pending", then "Post waiting charges" posts each entry (admission, product, quantity, price) through the same port, and the consumption entry then shows "Charged"; an entry for a discharged admission stays waiting ("Bill closed"); the ward nurses' own list shows the status text but is otherwise unchanged (OQ-79-23, OQ-80-32).
- Given a running bill, then the pharmacy lines show as "Pharmacy" lines with product, quantity and amount; the IPD final bill totals include them (this is the IPD change the human approves); the medication chart is not changed and shows no batch number unless the human approves that change separately (OQ-80-31b).
- Given the price (OQ-80-15), then recommended: the batch MRP per issue unit including GST, no discount, to the paisa; a line's amount is quantity times price, rounded half up; the credit uses the same price.
- Given a reconciliation, then QA can show that for an admission the pharmacy charges minus credits equal the pharmacy lines on its running bill; a difference is reported, not corrected silently.
- Given Standalone mode or IPD off, then none of this exists: no charge, no credit, no waiting list.
- Given a person who is not a pharmacist or an owner, then 403 on "Post waiting charges"; not mapped, 404; another clinic's admission, 404.
- Given no patient name, mobile number, ABHA number or clinical text is sent to a log, then only ids, quantities and amounts are written (C-8); the port carries admission id and product, not clinical text.
- Given no cash is taken, then SAH-81 (IPD money in day close) is not affected (OQ-80-31e).
- Given a slow or failed call, then "Could not post. Try again." and the line stays waiting.

## Notes for design
- The IPD change (TP-5) is the largest in Phase 3: `ipd.api.PharmacyChargePort` (post, credit) owned and implemented by ipd, a pharmacy service key and a credit line in `ipd.admission_charge` (today a closed list of 10 keys, quantity 1 to 50, append-only, no credit), and the running-bill and final-bill totals (feasibility T5). The BA's minimum: the port, the key, the credit kind, the totals; the chart unchanged.
- The reader port `ipd.api.AdmittedPatients` of TP-6 is SAH-79's; this story uses its results.
- Until the human approves TP-5, this story is not built and entries wait.

---

### SAH-80-17 · As a pharmacist, I want to see which patients are about to be discharged and whether the pharmacy side is clear, so that unused medicines are returned and take-home medicines are supplied before the final bill
Priority: Could
Screen: P&I mockup `03-hospital-pharmacy-opd-and-ipd.html` (lane "Discharge · 2 · take-home meds + clearance"; rows "Ward 3B · Bed 8 · Shalini Gore · Discharge 1 PM · 5 take-home medicines · 2 unused to return" and "Ward 2A · Bed 2 · Prakash Jadhav · Pharmacy clearance pending for final bill"; metric "Discharge meds ready, avg 22 min · target 30"). Touch point T7 (TP-7): information only unless the human approves a gate (C-9, OQ-80-33). Package WP-M. BRD T7. Depends on 80-13, 80-14, 80-15.
Integrated only, IPD On.

## Acceptance criteria
- Given TP-7 (information only) is approved and an admission's discharge has started in IPD, then the IPD tab's lane "Discharge" lists it as "<ward> · Bed <n> · <patient>" with the chip "Discharge" and the discharge time if IPD gives one (the list comes from the same port as 80-13; no new IPD screen); with TP-7 not approved the lane is not shown.
- Given a discharge row, then it shows "Pharmacy clearance: Pending" when any ward return of that admission waits to be accepted (80-15) or a discharge supply is open, and "Cleared" otherwise; nothing else is claimed (the mockup's "2 unused to return" would need the ward's count of unused items, which pharmacy does not hold; not built).
- Given the pharmacist presses "Mark cleared", then the row shows "Cleared by <name>, <time>"; the mark is pharmacy's own and is written to no IPD table; marking cleared with returns still waiting needs a reason chip (NEW list "Clearance reason": Returns will come later, Nothing to return, Other with a note of at least 4 characters).
- Given take-home medicines, then they are supplied as an IPD issue marked "Take-home" (80-14, charge to the IPD bill) or as a counter sale (80-3) according to the human's choice (OQ-80-33); until then the pharmacist uses either screen; the lane's row shows the supplies saved for that admission today.
- Given the IPD final bill, then it is not delayed, blocked or changed by this story: "Pharmacy clearance pending for final bill" is information for the pharmacist only (recommended default); a gate in the discharge workflow is a separate IPD story the human may ask for (OQ-80-33, feasibility C3).
- Given the "Discharge meds ready, avg ... target 30" metric, then it is not built (no target setting).
- Given Standalone, or IPD off or read-only, then the lane is not shown; the API answers 409 `integration-off`.
- Given a person who is not a pharmacist, then no "Mark cleared" (403); not mapped, 404; another clinic's admission, 404.
- Given each mark, then an audit event records the admission id, who and when, without clinical text.
- Given the port fails, then "Discharges could not be loaded. Try again."

## Notes for design
- The only IPD need is a read of "discharge started" in the T4 port (TP-7 minimum). A real clearance gate would need `ipd.api.DischargePharmacyClearance` declared in `ipd.api` and implemented by pharmacy, which changes the built discharge workflow (SAH-27): not built.
- Priority is Could because the BRD calls it a touch point but gives no behaviour beyond "Pharmacy clearance before final bill; take-home medicines".

### SAH-80-18 · As a cashier or pharmacist, I want to take back medicines against the original bill and the same batch, within the return window, and refund the patient, so that stock returns correctly and no refund is made without a bill
Priority: Must
Screen: P&I mockup `04-sales-return.html` (header "Sales return", search "Scan the bill QR or type the bill number", card "<name> · bill of 14 Nov 2026 · ₹642.00" with the chip "Within 30-day return window", table Item · batch, Sold, Already returned, Return now, Condition, Allowed?, Refund; selects "Reason" and "Refund to"; the line "Stock goes back to Main Pharmacy · Available (sealed) or Quarantine (doubtful). Returns above ₹1,000 need pharmacist-in-charge approval."; button "Refund ₹30.70 & print credit note") and `17-end-to-end-flow.html` (row "Sales return: Patient to Pharmacy Available or Quarantine; approval above ₹ limit"). Package WP-L. BRD P4, AC-P4. Depends on 80-1 (return window, refund modes, credit note series), 80-3, SAH-79-2, 79-3, 79-4. Open: OQ-80-12.

## Acceptance criteria
Find the bill
- Given a person with the sales return capability (a cashier, a pharmacist, the owner) opens "Sales return", then the header shows "Sales return" and the store's name ("Counter 1" is not built), and the search "Type the bill number, or scan it" (a typed number or a keyboard-style scanner; the mockup's bill QR needs a camera and is not built, OQ-80-2); the number is found ignoring capital letters in the store's series or the unit's.
- Given the person also searches by customer name or phone (NEW; "Find bill" in BRD P4, OQ-80-12), then the store's bills of that customer in the last <window> days are listed; a patient's name is shown to these roles only.
- Given no bill matches, then "No bill found. Check the number." and nothing else.
- Given the unit has not entered a return window (80-1), then "Return window is not set. Ask your owner." and no return can be made.

Return lines
- Given a bill is found, then the card shows "<customer or patient> · bill of <date> · ₹<total>" and a chip "Within <n>-day return window" (green) or "Return window is over" (red; the lines cannot be returned and no one can override it, OQ-80-12); a cancelled bill shows "This bill was cancelled." and cannot be returned.
- Given the table, then each bill line (one per batch) shows Item and batch, Sold, Already returned, "Return now" (a whole number input, empty means none), Condition (chips Sealed, "Strip cut or opened", Damaged; NEW list "Return condition"), "Allowed?" (Yes, "Not returnable", "Pharmacist decides") and Refund (the money for the units, worked from the line's amount after its share of the discount, to the paisa); only the batch that was sold can be returned (AC-P4).
- Given "Return now" is more than Sold minus Already returned, then 400 "You can return at most <n>." and nothing is saved.
- Given the product's "returnable" flag is No (SAH-78 BR-17), then "Allowed?" shows "Not returnable" and the quantity cannot be entered; the mockup's "Cold chain" chip is not built (no cold-chain flag).
- Given the condition "Strip cut or opened" or "Damaged", then "Allowed?" shows "Pharmacist decides": a cashier's return waits for a pharmacist (below); a pharmacist chooses Accept (units go to Quarantine) or Refuse; given Sealed, then "Yes".
- Given a Schedule X line, then only a pharmacist completes it with the second signature of 80-10; the cashier's return waits.

Reason, refund, approval
- Given the selects, then "Reason" offers the sales-return list (Doctor changed medicine, Excess bought, Wrong item given, Adverse reaction, and the owner's own, SAH-78-10) and "Refund to" offers only the modes the unit allows (Original mode (<mode of the bill>), Cash, Credit note for next purchase, Adjust in udhar; 80-1); "Credit note" and "Adjust in udhar" need a named customer (80-4); with a split payment the person picks the mode and the amount per mode cannot exceed what that mode paid (OQ-80-12d).
- Given the footer, then it reads "Stock goes back to <store> · Available (sealed) or Quarantine (doubtful)." and, when the unit's SALES_RETURN rule has a ₹ limit, "Returns above ₹ <limit> need approval." (the limit read from the rule, never fixed; the mockup's ₹1,000 and "pharmacist-in-charge" are examples, OQ-80-12c).
- Given the refund total is at or below the rule's limit (or level 0) and the person is a pharmacist or the owner, then pressing "Refund ₹<amount> & print credit note" completes the return at once: ledger lines "Sales return" (reference the credit note number; units to Available if Sealed, to Quarantine if doubtful), the credit note number from the credit note series (80-1), the refund recorded in the chosen mode, the H1 reversing rows (80-9), and the credit note printed through the browser print view.
- Given the refund is above the limit, then the button reads "Send for approval"; the return waits ("Waiting for approval") in the inbox of the approver the rule names under the chip "Sales" with the Rule text "Sales return above ₹ <limit>"; nobody approves their own request; on approval the person who started finishes it; on rejection the reason is shown (SAH-79-2, BR-38).
- Given a cashier starts a return, then it always waits for a pharmacist, the store's HOD or the owner ("Waiting for the pharmacist"), appears for them in "Returns waiting to be accepted" (80-15, and as a list on this screen in Standalone), and no money is refunded until they accept the goods; this follows the matrix cell "Cashier: Needs approval" (OQ-80-12c, OQ-80-38).
- Given two people return the same line at once, then the second gets 409 `changed` and sees the remaining quantity.
- Given a refund is recorded, then no payment is made by Sahaj; the screen says "Refund by <mode> from the counter." and the amount counts in the day close (80-22).
- Given an amount with more than 2 decimals, a quantity that is not a whole number, or a refund mode the unit does not allow, then 400.
- Given the bill was added to the OPD bill (80-23), then the return posts a credit there through the same port.

Roles, tenants, errors
- Given a person not mapped to the store, then 404; mapped without the capability, 403 `store-permission-denied`; another clinic's bill, 404; a bill of another store of the clinic that the person cannot reach, 404.
- Given a failed save, then "Could not save. Check your connection and try again." and the entries stay; nothing is half saved.
- Given the return is saved, then an audit event records the bill id, credit note number, count and outcome, never the patient's name.

## Notes for design
- The refund per line is worked from the bill line's paid share (discount in proportion, OQ-80-6); GST on the credit note is the accountant's layout (G-22).
- "Returns above ₹1,000" in the mockup is a rule value; Sahaj ships none (SAH-78 G-4). The window, modes and limit are the owner's entries.
- Cold chain is Later; the returnable flag is what exists.

---

### SAH-80-19 · As an owner or a pharmacist, I want one screen of licences, registered pharmacists, what each role may do and the audit log, so that I can show an inspector that the pharmacy is licensed, staffed and controlled
Priority: Should
Screen: P&I mockup `05-compliance-roles-and-audit.html` (cards "Licences & renewals", "Role-based permissions & approvals", "Audit log · tamper-evident", "Data protection & trust"; header buttons "Export GST (GSTR-1 / 3B)" and "Download inspection pack (PDF)") and `01-pharmacy-command-center.html` (card "Compliance health: Drug licence 20B / 21B renewal"). The screen shell is built in 80-9; this story adds the cards. Package WP-L. BRD P5 "licence expiry list; audit log". Depends on 80-1 (Schedule X licence), 80-9 (shell), SAH-78-1. Open: OQ-80-19, OQ-80-35, OQ-80-38.

## Acceptance criteria
Licences and renewals
- Given the owner or a pharmacist opens "Registers & compliance", then the card "Licences & renewals" lists in a table Document, Number, Valid till, Status: the retail drug licence (the number and expiry of the unit, SAH-78-1), the Schedule X licence (80-1; shown only when entered), the GST registration (the unit's GSTIN; Valid till "—"; no status chip, because Sahaj verifies nothing) and one row per registered pharmacist ("Registered pharmacist", name and registration number, valid till).
- Given a valid-till date, then the Status chip shows "Valid" when it is later than 60 days away, "<n> days" (warning) when it is within 60 days, and "Expired" (red) after the date; the 60 days is a recommendation (OQ-80-19); dates show as a full date ("28 Dec 2026"); licence numbers are free text up to 40 characters (SAH-78 BR-21).
- Given the owner presses "Add pharmacist registration", then they choose a member with the team role pharmacist, enter the registration number (up to 40 characters) and the valid-till date (a date) and save; the same fields can be edited; a pharmacist sees their own row and the unit's licences but cannot edit; every change is audited with before and after (ADR-028 OQ-PHI-20).
- Given a licence or a pharmacist registration has expired, then the owner and pharmacists see a notice at the top of the screen ("<document> has expired.") and on the Command centre (80-20); nothing is blocked and no message is sent (OQ-80-19).
- Given the person has no right, then the card is not shown (403 on the API).

Role read-out
- Given the owner or a pharmacist opens the card "Role-based permissions & approvals", then a read-only table shows the rows Discount above the limit, Cancel saved bill, Refund / sales return, Stock adjustment, Override allergy / H1 block, Raise ward indent and the columns Owner, Pharmacist, Cashier, Store keeper, Ward nurse, with cells Allow, "Needs approval" (with the rule text, for example "Needs approval above 10%"), Deny, and "Same day only" for the pharmacist's cancel; the cells are worked from the unit's approval rules, the fixed rules and the capabilities the server enforces (BR-29).
- Given a row the mockup draws that Sahaj does not build, then it is not shown: "Change MRP / sale rate" is not built (OQ-80-9); "+ Custom role" is not built; the card says "These are the rules in force. The owner changes them in Inventory setup." and has no editor.
- Given a cell the approval rules cannot express (a limit that depends on the person, for example "Up to ₹1,000" for a pharmacist, OQ-80-38), then it shows what the server enforces (the rule's limit for everyone) and nothing else.

Audit log
- Given the owner or a pharmacist opens the card "Audit log", then it lists pharmacy events newest first with time, person (and role), what and the store: overrides of allergy or H1 blocks ("overrode allergy block on bill <number> · reason: <chip> · <store>"), discount requests and decisions ("requested 15% discount · sent for approval · approved by <name>"), stock adjustments ("adjusted <batch> 60 to 58 · Breakage"), cancelled bills, sales returns and second signatures; no photo is shown ("photo attached" is not built), no patient name, no free text and no allergy text; the card is titled "Audit log" (the mockup's "tamper-evident" is not claimed: the log is append-only, OQ-80-3).
- Given filters (date range up to 12 months, person, type), then the list narrows; 25 rows a page; no export here (the report "User activity & audit log" of 80-21 exports).
- Given the owner, then every store; a pharmacist, only the stores they are mapped to; a cashier and others, 403; another store not reachable, 404.
- Given a slow or failed load, then "Could not load. Try again."
- Given the mockup tiles of "Data protection & trust" (Hosted in India, Encrypted, DPDP consent, ABDM-ready, Daily backup, Your data, exportable) and the buttons "Export GST (GSTR-1 / 3B)" and "Download inspection pack (PDF)", then none is built: they are claims or features that this phase does not deliver (consent, ABDM, backup are platform matters; the pack is Later; OQ-80-2, OQ-80-3).

## Notes for design
- The audit log reads the platform audit; `platform.api.AuditLog` has only a write method today (TP-9). The architect chooses a read method in `platform.api` (additive, human approves) or a pharmacy-owned activity feed (OQ-80-35).
- The read-out must come from the same code that enforces the rules, so that the screen cannot disagree with the server.

---

### SAH-80-20 · As an owner, pharmacist or HOD, I want a command centre of today's sales, expiry, reorder, waiting prescriptions, credit and compliance, so that I can see what needs attention at the start of the shift
Priority: Should
Screen: P&I mockup `01-pharmacy-command-center.html` (header "Pharmacy Command Center", search, buttons "+ New bill" and "+ Purchase entry"; cards TODAY'S SALES, GROSS MARGIN, NEAR-EXPIRY (90 D), REORDER DRAFTS, RX FROM OPD / IPD, UDHAR OUTSTANDING; "Expiry risk" bars Expired, 0–30, 31–60, 61–90 days; "Live prescriptions from Sahaj OPD / IPD"; "Compliance health"). Not built from this screen: "Action queue" and its buttons, the "Expiry-return calendar" note, the card "Branches today", the cold-chain card and chip, "Send reminders", "Create return", "Review POs", "Transfer stock". Package WP-N. BRD P1. Depends on SAH-79-3 (expiry and low stock), 79-9 to 79-13 (indents), 80-3, 80-4, 80-9, 80-10, 80-19. Open: OQ-80-4, OQ-80-5.

## Acceptance criteria
Header and actions
- Given an owner, pharmacist, HOD of a selling store or cashier opens "Command Center", then the header shows the date line "<weekday>, 24 Oct 2026 · <store name>" (the mockup's "Baner Branch" is the store), the title "Pharmacy Command Center", a search box "Medicine, salt, bill no…" (medicine and salt find stock in this store; a bill number opens the bill; searching by patient name is not built here, F4 on the sale screen does it, OQ-80-4), the button "+ New bill" (opens 80-3) and, only for a person who may receive stock (a Central Store user or the owner), "+ Purchase entry" (opens "Receive stock", SAH-79-7).
- Given the owner, then a store select offers "All selling stores" and each; others see their working store.
- Given figures, then they are worked when the page is read, for the current IST day, and the page says "As of <time> IST" with a "Refresh" button; nothing is estimated and no figure appears that built data cannot give (BR-30).

Cards
- Given "TODAY'S SALES", then the card shows the net payable of bills saved today minus the sales returns completed today, and "<n> bills · avg ₹<n>" (average per bill); a cancelled bill is not counted (OQ-80-4).
- Given "GROSS MARGIN", then it is shown only to the owner and pharmacists and only when the landed cost of OQ-79-6 is confirmed; the text says "On landed cost, not PTR" as the mockup; otherwise the card is not shown (OQ-80-4).
- Given "NEAR-EXPIRY (90 D)", then it shows the value of Available batches expiring within 90 days (at purchase rate, SAH-79 OQ-79-6) and "<n> batches"; the part "₹<n> returnable" is shown only if SAH-79-8's vendor return policy gives it; "Expired" stock in Quarantine is in the card below.
- Given "REORDER", then it shows "<n> SKUs" (products at or below the store's reorder level, SAH-79-3) and the line "below reorder level"; the mockup's "3 distributors auto-picked" and the reorder drafts are Later (best-vendor ranking).
- Given "RX FROM OPD / IPD" (Integrated only, hidden in Standalone), then it shows "<n> OPD prescriptions waiting" and "<n> IPD orders waiting" (80-11, 80-13) and, for the working store, "<n> indents waiting" counted from SAH-79 (indents this store must dispatch or receive; ward indents are not served by the pharmacy, OQ-80-5).
- Given "UDHAR OUTSTANDING", then it shows the total due of customers and "₹<n> over 60 days" (80-4); hidden when udhar is not used.
- Given "Expiry risk", then bars show Expired (blocked at the counter), 0–30 days, 31–60 days and 61–90 days with batch count and value, each linking to "Batch inventory & expiry" (SAH-79-3).
- Given "Live prescriptions from Sahaj OPD / IPD" (Integrated only), then it lists the four oldest waiting items (time, patient, source, number of items, availability chip "All in stock" or "<n> short") with the action "Bill" (80-12) or "Issue" (80-14); the "<n> substitute" chip is not worked; ward indents are not listed (OQ-80-5).
- Given "Compliance health", then four tiles show: Schedule H1 register ("Up to date · Auto-filled from <n> bills today", or "<n> overrides to review", 80-9), Schedule X / NDPS stock ("Balanced · Physical = book · double-signed", or "<n> to review", 80-10), the drug licence ("Due in <n> days" or "Expired", 80-19) and a link "Open registers →"; the cold-chain tile is not built.
- Given a cashier, then only "TODAY'S SALES" (bills and average) and "+ New bill" are shown.
- Given a card fails to load, then it shows "Could not load. Try again." and the others still show.
- Given no sale today, then "No sales yet today." Given no stock, "No stock in this store yet. Post opening stock or receive stock."
- Given a store the person is not mapped to, then 404; another clinic's data, never shown; Standalone, the Integrated cards are not shown.
- Given a phone, then the cards stack (OQ-80-3).

## Notes for design
- Figures are queries over bills, the ledger and batches (no store of totals, no job). The architect sets the indexes to keep the page fast.
- The mockup's line "debit notes older than 30 days" is not in screen 01 as supplied; SAH-79-19's report covers it (requirement, conflict 5).

---

### SAH-80-21 · As an owner or a pharmacist, I want sales, GST, returns and register reports, so that I can reconcile the counter, file returns and answer an inspection
Priority: Should
Screen: P&I mockup `15-reports.html` (catalogue group "Sales & compliance": Sales & margin, Sales returns, GST sales register, Schedule H1 / X registers, User activity & audit log; buttons Excel and PDF). Package WP-N. BRD P5 and section 4 I10 pattern; extends SAH-79-18 (report framework, filters, CSV, print). Open: OQ-80-6, OQ-80-17, OQ-80-21, OQ-80-35.

## Acceptance criteria
- Given the owner, or a pharmacist, HOD or cashier mapped to a selling store (cashier: none) opens "Reports", then the catalogue shows, besides the SAH-79 groups, the group "Sales & compliance" with the five reports above; a cashier sees no report; the owner sees every selling store.
- Given any of these reports, then the filters are Store (stores the person can reach), Date range (at most 12 months a run, OQ-79-35) and Product where it applies; results are paged; an unreachable store answers 404; the person can "Download CSV" (UTF-8) or "Print"; the mockup's Excel and PDF buttons are replaced as in SAH-79 OQ-79-4.
- Given "Sales & margin", then per day, product or user (the person picks) it shows bills, quantity, sales (net of discount and returns), discount, tax, cost at the purchase rate and margin as a percentage with one decimal; cost and margin are shown to the owner and pharmacists only; the margin follows OQ-79-6.
- Given "Sales returns", then each completed return shows credit note number, bill number, date, product, batch, quantity, reason, condition, refund mode, amount, approver and who finished it.
- Given "GST sales register", then each bill line shows date, bill number, HSN, GST %, taxable value, CGST, SGST and total, with returns as credit note rows, totals per GST % and per day; a cancelled bill shows as cancelled; the patient or customer name is not in this report; the layout and the legal fields are the accountant's (OQ-80-6, G-22); the mockup's "Export GST (GSTR-1 / 3B)" is not built.
- Given "Schedule H1 / X registers", then the H1 register (80-9) and the Schedule X balance register (80-10) are available as reports with the same columns; the CSV includes the patient name for the H1 register (the law's content) and each export is audited and limited to the owner and pharmacists (OQ-80-21).
- Given "User activity & audit log", then the log of 80-19 can be filtered by date, person and type and downloaded; it holds no patient name or clinical text.
- Given a report with a result above the row cap (OQ-79-35), then "Narrow the date range." and nothing partial is downloaded.
- Given no rows, then "No records for this filter."; a failed load, "Could not load. Try again."
- Given an export or a print, then no patient name, mobile number or ABHA number is included, except in the H1 register as above; the access is audited as a report view (C-8).
- Given a cashier or another role without a right, then 403; another clinic's data, never shown.
- Given a large range, then the query is paged and returns in a reasonable time; no report loads all rows into memory.

## Notes for design
- Reuses the report framework, filters and caps of SAH-79-18. The reports "Stock valuation", "Non-moving", "Vendor performance" and "Rate history" of the catalogue stay unbuilt (OQ-79-33).
- A quantity or amount in a report is never written to an application log.

---

### SAH-80-22 · As a cashier or pharmacist, I want to close my shift by counting cash, UPI and card against what the system expects, so that the day's tally is right and any difference has a reason
Priority: Should
Screen: NEW. The BRD says "Day close: cash tally by payment mode at shift end" (section 5); no mockup screen draws it. Marked NEW; layout follows the plain sheet pattern of the Sahaj billing day close (SAH-10) without copying its money (OQ-80-14). Package WP-N. BRD Pharmacy rules. Depends on 80-3, 80-4, 80-18.

## Acceptance criteria
- Given a cashier or a pharmacist at a store with "Sale allowed" Yes opens "Close shift" (NEW), then the sheet shows the store, the person, the period (since the person's last close at this store, or the start of today if none) and, per payment mode, the amount the system expects: Cash, UPI, Card, and "Credit note used" and "Udhar" as information; expected = payments of the bills saved in the period, plus payments received against udhar (80-4), minus refunds recorded in the period (80-18, 80-5).
- Given the person types the counted amount for Cash, UPI and Card, then the sheet shows the difference per mode; a difference other than ₹ 0 needs a reason chip (NEW list "Day close difference": Short change given, Entry error, Refund not recorded, Other with a note of at least 4 characters; request to SAH-78-10).
- Given the person presses "Close shift", then the closing is saved with who, when, the period, expected, counted and difference, and cannot be changed; sales after it start a new period; a second close for the same period gets 409 `already-closed`.
- Given a cancelled bill or a refund from an earlier period, then it counts in the period in which it was recorded.
- Given the closing is saved, then a summary prints through the browser print view; no bank reconciliation, no deposit slip and no accounts export is built.
- Given a person closes for another person, then only the owner may ("Close for <name>"); a cashier or pharmacist closes their own shift; a person with no sale capability, 403; not mapped, 404.
- Given nothing to close, then "Nothing to close. No bills in this shift."
- Given the unit also uses the billing day close (SAH-10, SAH-81), then the two are separate: pharmacy money is pharmacy-owned and is not in the billing drawer; the screen says "This is the pharmacy counter only." (NEW; OQ-80-14).
- Given the amounts, then they are whole paise; an amount with more than 2 decimals or negative, 400.
- Given each close, then an audit event records ids, totals and the difference.
- Given a slow or failed save, then "Could not save. Check your connection and try again." and what was typed stays.

## Notes for design
- A NEW screen with no mockup: the human confirms it (OQ-80-14) or drops it; nothing else depends on it.
- UPI and card are recorded only (no gateway), so "expected" is what the bills say, not a bank statement.

---

### SAH-80-23 · As an owner, I want the option to add the pharmacy bill to the patient's OPD bill, so that an OPD patient pays once at the OPD counter when the hospital prefers it
Priority: Could
Screen: NEW. BRD T8 ("Pharmacy bill stands alone, or is added to the OPD bill as configured"); no mockup screen draws it. Touch point T8 (TP-8): needs a port owned by `billing.api` and the human's approval (C-9, OQ-80-16). Package WP-O. Default decided: the pharmacy bill stands alone (DEC-063, BRD section 10 Q1). Depends on 80-3, 80-12, 80-18.
Integrated only, OPD patients only.

## Acceptance criteria
- Given TP-8 is approved, the unit is Integrated and the owner opens Unit settings, then a NEW setting "Add pharmacy bill to the OPD bill" (Off by default) is shown; with TP-8 not approved, or Standalone, the setting is not shown and the pharmacy bill always stands alone.
- Given the setting is On and a bill is for a registered OPD patient with an open visit bill, when the pharmacist saves, then the person may choose "Add to OPD bill": the bill is saved with payment mode "On OPD bill" only; stock, ledger, registers and numbering are as in 80-3; the lines are posted to the OPD bill through the billing port in the same transaction (product name, quantity, amount); if billing refuses (the visit bill is closed or paid), the whole save fails with 409 `opd-bill-closed` "The OPD bill is closed. Take payment here instead." and nothing is saved.
- Given a bill added to the OPD bill, then no payment is taken at the pharmacy (day close, 80-22, does not count it), and the invoice still prints with the pharmacy's GST details (OQ-80-16).
- Given a sales return or cancel of such a bill, then a credit is posted to the OPD bill through the same port; if that is impossible, the return is refused with 409 `opd-bill-closed` and nothing changes.
- Given the setting is turned Off, then new bills stand alone; bills already added stay as they are.
- Given the OPD billing table's limits (quantity per line up to 20 today, no source column), then the port carries what billing accepts and the story is not built until billing's change is approved (feasibility T8).
- Given a person who is not the owner, then 403 on the setting; the sale choice shows to a pharmacist or cashier only when On.
- Given another clinic's visit, then 404; an invalid input, 400.
- Given each posting, then an audit event records the visit id, bill number and amount.

## Notes for design
- Billing's change (a port such as `billing.api.ExternalBillLinePort.addLines`, a source column, a larger quantity range) is outside DEC-061's list and needs the human; the story may never be built.
- Two places collecting money for the same goods is the risk (feasibility T8); the accountant's view is G-22.

---

## Change log
- 18 Nov 2026: first version of SAH-80 stories (23 sub-stories, seven work packages WP-I to WP-O), written from the BRD sections 5 to 8, mockup screens 01 to 05, 15 and 17, ADR-028, the feasibility report and the SAH-78 and SAH-79 stories.

