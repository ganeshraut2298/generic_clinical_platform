# SAH-79 · Stories: Pharmacy & Inventory, Phase 2: Inventory (stock ledger, purchase, indents, transfers, consumption, audit, approvals, reports)
Module: pharmacy
Mockup screen: Pharmacy, Lab, Tele (sidebar, when enabled) → Pharmacy & Inventory → Inventory (P&I mockup files `06-batch-inventory-and-expiry.html`, `07-purchase-orders.html`, `08-purchase-ocr-and-grn.html`, `09-vendor-return-and-debit-note.html`, `10-ward-and-store-indent-workflow.html`, `11-store-stock-and-ward-consumption.html`, `12-stock-transfer.html`, `13-physical-stock-audit.html`, `14-approvals-inbox.html`, `15-reports.html`)
Also uses: P&I mockup files `17-end-to-end-flow.html` (stock movement and approval per transaction), `16-setup-stores-staff-products-vendors.html` and `05-compliance-roles-and-audit.html` (data from Masters). Screen-map rows for the P&I mockup are pending from the human (OQ-79-1, still open); the line above uses the existing parent row so that `validate docs` can find it, as SAH-78 does.
Requirement: docs/requirements/SAH-79.md (business rules BR-1 to BR-37, open questions OQ-79-1 to OQ-79-39, go-live items G-13 to G-22; OQ-79-37 is DECIDED).
Source: DEC-061 (human, 17 Nov 2026, Q1 A to Q4 A and Answer 2); DEC-062; DEC-063 (Q1 A, Q2 A); ADR-028 (accepted); BRD sections 4, 6, 7, 8, 9 and 10 (`docs/handoffs/PHI-input/brd.txt`); SAH-78 requirement and stories.
Branch: `feature/SAH-39-home-kinetic-background` (DEC-061 Answer 2), with the additive-only rules. Written ahead; Phase 2 gets its own approvals later (DEC-061 Q1 A).

Reading guide.
- Slicing: nineteen sub-stories (the usual limit is six) because Phase 2 covers ten screens and a stock ledger. Each fits one sprint. They are grouped into five work packages that are built in sequence.
- Where a story needs something the mockup does not show, the AC states the recommended answer and names the open question (OQ-79-n). Decided questions say DECIDED with the DEC reference. Nothing is invented silently.
- The Sahaj shell is not changed (BRD section 7). The mockup's left panel, tabs, colours, "USP" chips, "AC" chips (such as AC08), "Rakesh S." and the demo-step buttons are not built. Items marked Later in the BRD are not built.
- "Central Store user", "HOD", "pharmacist" and "store user" are as in the requirement table: people with that store role at that store. "Owner" can do everything a Central Store user does and every store capability except pharmacist-only actions.
- All Inventory screens work in Standalone and Integrated mode, except patient-wise ward consumption (79-16).

Common rules for every story (each AC assumes them; they are tested once per endpoint or screen):
- C-1 Tenancy and access. The clinic (unit) comes from the signed-in user, never from the request. Another clinic's record answers 404. Roles are checked on the server on every call from the clinic membership. Module Off answers 403 `module-off`. Every store-bound call has `{storeId}` in its path and never takes a "current store" from the client; another clinic's store answers 404; a store of this clinic to which the caller is not mapped answers 404 (the owner is never unmapped); a mapped person without the capability gets 403 `store-permission-denied`, audited; bad input is 400; the store check runs before any filter, sort or paging. An Integrated-only action in Standalone mode answers 409 `integration-off`.
- C-2 Ledger and stock (BR-4 to BR-8). Stock is per store, product, batch and state (Available, In transit, Pending return, Quarantine); only Available is usable. Every movement writes ledger lines (date and time, transaction, reference, store, state, product, batch, in, out, balance after, user, reason) in the same step as the document; all or nothing. The ledger is never edited or deleted; a correction is a new line pointing to the one it corrects. A movement that would make a balance negative is refused with 409 `insufficient-stock` and the available quantity, also under two users at once. Issues pick by FEFO and never from an expired batch. Quantities are whole issue units.
- C-3 Approvals (BR-12 to BR-17). A document covered by an approval rule is submitted to the engine of 79-2. Level 0: it proceeds at once. Otherwise it waits with the status "Waiting for approval". The rule version is kept at submission. Nobody approves their own request. A rejection needs a reason chip.
- C-4 Documents. A number from the unit's series (SAH-78-10) is taken in the same step that first submits or posts a document; a draft has no number and can be discarded; a cancelled document keeps its number; nothing is hard-deleted. Every document has a History card: each step with user, store, time, before and after quantity and reason (BR-26). Two people acting on the same document at once: the second gets 409 `changed` and the screen reloads.
- C-5 Reasons. Reasons are chips from the reason lists (SAH-78-10 and OQ-79-15), not free text; an optional note may be added; "Other" needs a note of at least 4 characters.
- C-6 UI. Plain English, sentence case; "Receive stock", not "GRN" alone (the one exception is the receipt button, which reads "Post GRN & update stock" as the mockup `08` does, 79-7). Money ₹ 1,25,000 or ₹ 1,25,000.50; dates 24 Oct 2026; times 11:05 AM IST; batch expiry as month and year (OQ-79-7). A button is disabled while its request runs; nothing is shown as saved until the server has answered; a failed save keeps what was typed and says "Could not save. Check your connection and try again." (NEW). No offline queue. Empty states say what to do next in one line. Lists load in pages and never show another clinic's rows. What a person has no capability for is hidden, not greyed. Approvals, receiving, stock counting and ward consumption work on a tablet or phone; other tables may scroll sideways.
- C-7 Printing and export. Printing uses the browser print view with the template settings of SAH-78-10; export is CSV (OQ-79-4). No WhatsApp, e-mail, PDF generation or Excel.
- C-8 Audit and logs. Every create, change, decision and posting is audited (who, when, before and after). Application logs hold ids, counts and outcome codes only: never a patient name, mobile number, ABHA number, clinical note or batch quantity.
- C-9 Blind count (BR-30; DEC-080 L12). While a store has an open stock audit (79-17, until the audit is Closed), a person who has the capability to count but not the capability to review the count gets no quantity for the products in that audit, from any stock read of that store: stock screens, lists, reports, the stock ledger, and any error that would name an available quantity (the quantity fields are empty, not zero). Where the quantity would be, the screen shows "Counting in progress" (NEW; the mockup has no wording for this). Figures worked from those quantities (stock value, totals, the "Below reorder" status) leave out those products for that person (OQ-79-38). A person who may review the count sees the quantities. Other products, other stores and other people are not affected. Each story that reads stock repeats this in one criterion, and QA checks that no quantity reaches such a person through any endpoint.
- C-10 Schedule X movement by a person (BR-32; DEC-080 L14). A stock movement of a Schedule X product made by a person is not accepted by the server without a second signer, who is a different active person; the stock ledger refuses it as well, so a fault in the application cannot skip the second signature. The second signer is chosen from the other active pharmacists and owners of the unit; the list comes from the server and never contains the person signing first (79-5 states it in full). Movements made by the system (for example expired stock moved to Quarantine, user "System") need no signer. The architect lists which transaction types count as person-driven (ADR-029 P7); posting approved opening stock is not one of them (79-1).

Work packages (built in sequence WP-D, then WP-E, then WP-F, then WP-G, then WP-H; DEC-063 Q2 A: inside each, the Java and Angular developers work in parallel). The letters continue SAH-78's WP-A to WP-C.

| Package | Sub-stories | Depends on | Screens it owns (behaviour level) |
| --- | --- | --- | --- |
| WP-D Stock core and approvals | 79-1, 79-2, 79-3, 79-4, 79-5 | SAH-78 WP-A (stores, staff mapping, HOD and delegate, store guard, `GET /context`), WP-B (approved opening stock 78-7, products and conversion 78-5/78-6, reorder level and rack), WP-C (approval rules 78-9, numbering 78-10) | Stock ledger and balances, batches, opening-stock posting, approval engine and inbox, batch inventory and expiry, store stock by state, stock adjustment; the Inventory tab shell |
| WP-E Buy | 79-6, 79-7, 79-8 | WP-D (ledger, batches, engine, inbox); SAH-78 78-8 (vendors, licence block, return policy), 78-5 (preferred vendor), 78-9 (PO, rate-rise, vendor-return rules), 78-10 (PO, GRN, DN series) | Purchase orders, receive stock (GRN), vendor payable record, vendor return, debit and credit note |
| WP-F Indents | 79-9, 79-10, 79-11, 79-12, 79-13 | WP-D; SAH-78 78-3 and 78-4 (store issues-from, HOD, delegate, mapping, receive capability), 78-9 (indent rule), 78-10 (indent series, reasons). WP-E is not needed, because stock can come from opening stock | Ward and store indents: raise, HOD approval, Central Store dispatch, receive, Central Store accepts return or settles shortage |
| WP-G Store use | 79-14, 79-15, 79-16 | WP-F (the receive and Central-Store-accept flow is reused); WP-D; SAH-78 78-3 (flags, linked ward), 78-9 (write-off, transfer rules); IPD reader port for patient-wise consumption (OQ-79-23) | Return to Central Store, report damaged or expired, stock transfer, ward consumption |
| WP-H Control and reports | 79-17, 79-18, 79-19 | WP-D to WP-G (the movements counted and reported); WP-E for purchase reports | Stock audit, stock and movement reports with the stock ledger, purchase reports |

Overlap rules: WP-D owns the ledger, balances, batch, approval engine, inbox, the Inventory tab shell and its section list, and the shared "Waiting for action" list; later packages add their sections, their transaction types to the inbox and their movements, and do not edit those. One `docs/api/pharmacy.yaml` (architect) and one set of Flyway migrations in the order D, E, F, G, H. Packages run in sequence; WP-E and WP-F could run in two worktrees if the human decides (OQ-79-34).

---

### SAH-79-1 · As an owner or a store keeper, I want to post my approved opening stock to the stock ledger, so that the starting stock becomes real, usable stock exactly once and with a full trail
Priority: Must
Screen: NEW section "Opening stock" of Inventory setup (built in SAH-78-7), which gets the "Post to stock" step; the result is visible in `06-batch-inventory-and-expiry.html` (batch table and status chips). First SAH-79 story (OQ-78-34 and ADR-028 OQ-PHI-9, decided). Package WP-D.

## Acceptance criteria
- Given an opening-stock import with status "Approved" (SAH-78-7) and not yet posted, when the owner, or a Central Store user or pharmacist mapped to the import's store, opens it, then it shows store, number of lines, total quantity, total value at purchase rate (₹ with Indian grouping and paise), the names of uploader and approver, and the button "Post to stock" (OQ-79-30).
- Given the person taps "Post to stock", when the server posts, then every line becomes ledger lines of the transaction "Opening stock" in the store, state Available, with the line's product, batch, quantity in, balance after, the user who posted, and the import as reference; the batch (product, batch number, expiry, MRP, purchase rate per issue unit) is created; the import shows "Posted" with the date and time; all lines are posted or none (C-2).
- Given the import has lines for a batch that already exists with the same product, batch number and expiry, when posted, then the quantity is added to that batch if the MRP is the same; a different MRP for the same batch number and expiry stops the posting with 409 and names the lines (BR-10).
- Given a batch that already exists, then its MRP is fixed (BR-10): it cannot be edited. A wrongly typed MRP is corrected by emptying the batch by a stock adjustment (79-5) and receiving a new batch (another batch number or expiry). Go-live check for the pharmacist (OQ-D-2, DB design note `docs/database/SAH-79-inventory.md`); if a correction path is wanted, a later forward migration adds one for an owner-approved change.
- Given an approved import that contains Schedule X items, when a person who may post it posts it, then it posts without a second signature: posting needs the owner's approval of the import first (SAH-78-7, by another owner), and the poster is a person who may post (the owner, or a Central Store user or pharmacist mapped to the store); no second signature is asked at posting, because posting records existing stock and is not a receipt (A-8 of ADR-029, DECIDED, DEC-075 item 4; go-live check G-15). The ledger rule of C-10 does not apply to the "Opening stock" lines. This is the less strict reading and is a go-live check for the pharmacist (G-15); a stricter rule can be added later without changing data.
- Given an import that has no owner's approval yet, or a poster who is not a person who may post, then the posting is refused (409 `not-approved`, or 403) and nothing changes.
- Given a line whose expiry date has passed between approval and posting, when posted, then that line goes to Quarantine (never Available) with the reason "Expired", and the confirmation lists such lines before posting (OQ-79-30, BR-8).
- Given an import that is not Approved (Waiting for approval, Rejected, Replaced or Not accepted), when someone tries to post, then 409 `not-approved` and nothing changes.
- Given an import already posted, when the button is tapped again or the request is repeated, then nothing is posted twice (409 `already-posted` with the posted time) and the button is not shown on a posted import.
- Given the import is posted, then the stock is usable from that moment (until then it was never shown as usable, BR-11), the Batch inventory screen (79-3) and Store stock screen (79-4) show it, and the products in it stay locked for units and conversion (SAH-78 BR-15).
- Given a person who is not the owner and is not mapped to the import's store with store role Central Store user or Pharmacist, when they call the posting, then 403; a store of this clinic they are not mapped to answers 404; another clinic's import answers 404.
- Given a posted import, then it cannot be edited, cancelled or un-posted; a correction is a stock adjustment (79-5), which writes new ledger lines.
- Given a failure part way (server error, lost connection), when the person retries, then either everything was posted or nothing was; the screen shows the current status, never a half-posted state.
- Given a very large import (up to the file limit of SAH-78-7), when posted, then it runs as one step and the page shows progress; a slow network shows "Posting. Do not close this page." and the button is disabled (NEW text).
- Given a post, then the audit event records who posted, when, the import, line count and totals; the application log carries counts and ids only, not batch numbers or quantities (C-8).
- Given the posted lines, then each ledger line can be read later in the stock ledger report (79-18) with the user who posted.

## Notes for design
- Creates the first ledger, balance and batch tables and the first monthly ledger partitions (ADR-028 D6). The architect decides how the import reference is held on a ledger line (a document type and id).
- The posting is a new action on the SAH-78-7 section (a small additive change inside SAH-78's screen, owned by this story).
- SAH-78 OQ-78-37 (one approved import per store) stays as decided there.
- OQ-79-37: DECIDED (DEC-075 item 3 and DEC-072; auto). The interim reading stands: batch numbers that differ only in case ("AB1", "ab1") are one batch, as the SAH-78-7 import already treats them (ADR-028 R4), so the criterion above on "the same batch number and expiry" ignores capital letters. Reversing it needs one forward migration and moves no data. Go-live check for the pharmacist (G-15).
- A-8 (ADR-029) and the batch MRP rule are recorded in the two criteria above (DEC-075 item 4; OQ-D-2).

---

### SAH-79-2 · As an owner or an HOD, I want one inbox of everything waiting for my decision, with approvals that follow my unit's rules, so that nothing is stuck and nobody can approve their own request
Priority: Must
Screen: P&I mockup `14-approvals-inbox.html` (header "Approvals inbox", filter chips All, Indents, Purchase, Stock, Sales; card "Waiting for your decision" with columns Type, What, Raised by, Waiting, Rule and the buttons Reject and Review or Recount). It also fixes how every other story submits for approval (rules stored in SAH-78-9). Package WP-D. BRD I9, AC-I2, AC-I8.

## Acceptance criteria
Inbox
- Given the owner, or a person who is a store's HOD or the current delegate, opens "Approvals inbox" in the Inventory tab, then it shows the header "Approvals inbox" with "Signed in as <role>" and the card "Waiting for your decision" with the line "Sorted by urgency. Every decision is saved with your name, time and reason."
- Given the card, then the columns are Type (chip), What (document number, stores, number of items or the amount), Raised by (name and store), Waiting (age, for example "35 min", "2 h"), Rule (why it needs approval, for example "PO above ₹ 50,000", with the amount read from the unit's rule, never fixed) and the buttons "Review" and "Reject" (for an audit variance, "Recount" and "Review").
- Given the filter chips, then "All n" counts everything waiting for the person and the chips Indents, Purchase (purchase orders, rate increases, vendor returns) and Stock (write-offs, stock adjustments, audit variances, transfers) count their types; a chip with no type built yet (Sales, SAH-80) is not shown.
- Given a person's items, then the inbox lists only items for which the person is the current approver: the HOD or current delegate of the store the rule names; the owner for owner-level steps; the owner for an item whose store has no HOD and no current delegate (mockup "HOD absent → delegate"); and any item that has escalated to them (BR-14, BR-16).
- Given the order, then urgent items come first, then escalated items, then the longest waiting.
- Given nothing waits, then "Nothing is waiting for your decision." and no table.
- Given a person who is neither an owner nor an HOD or delegate of any store, then the Approvals inbox entry is not shown, and the API answers 403.

Decisions
- Given the person taps "Review", then the document opens at its approval step (the story of that document, for example 79-10 for an indent) where they can approve with changes or reject; the inbox does not approve without opening the document in this release (the mockup's direct "Approve" button is for discounts, SAH-80).
- Given the person taps "Reject", then a reason chip is required (the list of OQ-79-15, plus "Other" with a note of at least 4 characters); the document returns to its raiser with the status "Rejected" and the reason; no stock moves; the decision is saved with name, capacity, time and reason.
- Given a rule with two levels, when level 1 approves, then the item moves to level 2 and shows in the level-2 approver's inbox; final approval lets the document proceed; each decision is saved separately with its level.
- Given the person who raised a document, when they open the inbox or call the decision API for their own document, then the item is not listed and the call answers 403 `self-approval`; the same is held by a database check (BR-13). If no other eligible approver exists, the item shows "Needs another approver" to the next approver, or to the owner (OQ-79-28).
- Given the same person approved level 1 of a request, then they cannot also approve level 2 of it (OQ-79-28, proposal).
- Given a store's HOD is away and a delegate has current dates (IST), then the delegate sees and decides the items, and the decision is recorded as "Delegate"; after the end date the delegate no longer sees them; the history shows the capacity of every decision.
- Given two approvers decide the same item at the same moment, then the first decision stands and the second gets 409 `already-decided` with the current status.
- Given an item has waited longer than its rule's escalation time (defaults: indent 2 hours, urgent indent 30 minutes, transfer 24 hours, DECIDED OQ-79-3), when the inbox is read, then the item also shows to the next approver and is marked "Escalated"; this is worked out at read time with no timer and no notification (OQ-79-29).
- Given an owner changes an approval rule in SAH-78-9, then items already waiting keep the rule they started with and new items follow the new rule (BR-12).
- Given a rule of level 0, then no inbox item is created.
- Given a transaction type of this release (purchase order, rate increase on receipt, vendor return, indent, transfer, write-off, stock adjustment and audit variance), then it appears with its own chip, and the Rule column text is built from the rule that applied; discount and sales return are added in SAH-80.

Roles, tenants, errors
- Given a store of this clinic whose item the person is not an approver for, when they open its document, then 404; another clinic's item, 404; a person with a missing capability, 403 `store-permission-denied`.
- Given a slow or failed load, then "Could not load. Try again." with a Try again button; nothing stale is shown as current.
- Given a phone or tablet, then the inbox and the review step are usable (BRD section 7).

## Notes for design
- Phase 2 runtime of ADR-028 D7: an approval request that keeps the rule version, and append-only decisions with `acting_as` (HOD, delegate, owner, escalated). Each later story registers its transaction type.
- The inbox is the one place to approve; each document story describes its own "Review" step.
- Phase 1 stored escalation times only; this story reads them (BR-16).

---

### SAH-79-3 · As a Central Store user, pharmacist, HOD or owner, I want to see stock by product and batch with expiry, FEFO order and a low stock list, so that I can use the oldest stock first and act on expiry and shortage
Priority: Must
Screen: P&I mockup `06-batch-inventory-and-expiry.html` (header with SKU and batch counts, view pills, batch table for one product, low stock). Not built from this screen: ABC × XYZ matrix, non-moving stock card, cold chain and cycle count card, the pills Non-moving (90 d) and Cold chain, "Plan" action, "Branch transfer" button. Package WP-D. BRD I1, AC-I1.

## Acceptance criteria
View and header
- Given a person mapped to a store opens "Batch inventory & expiry", then it shows the working store (the owner may pick any store or "All stores", OQ-79-21), the header "Batch inventory & expiry" and the line "<n> SKUs · <n> batches · stock value ₹ <amount>" for that store, the amount as in OQ-79-6.
- Given the pills, then the views are All batches, Near expiry (90 d), Expired · quarantined, Low stock and Schedule H / H1 / X; under Near expiry the buckets 30, 60 and 90 days can be chosen (BRD I1); Non-moving and Cold chain pills are not built (Later).
- Given the search box, then a product is found by name, salt, alias, barcode or code, ignoring capital letters, global and own products together as in SAH-78-5; results load in pages of 25.
- Given a product is chosen, then a heading shows "<name> · <strength> · <form> · <pack>" and a line "Salt: <salt> · HSN <hsn> · GST <n>% · Rack <rack> · Reorder level <n> <unit>", the rack and reorder level being this store's local settings (SAH-78-5).

Batch table
- Given a product has batches in the store, then the table columns are FEFO #, Batch, Expiry (month and year), Qty (in the product's issue unit), MRP, Purchase rate, Landed, Margin, Supplier (the vendor of the receipt) and Status, with one action cell; the Quarantine and other-state quantities are shown by their own rows with the state in the Status chip.
- Given Available, unexpired batches, then FEFO # numbers them 1, 2, 3 by earliest expiry then earliest received, and the note reads "Stock lives at product + batch level. Every issue picks the top row first (FEFO)."; expired batches show "—" (BR-7).
- Given the Status chip, then it reads "Saleable" (Available, more than 90 days to expiry), "Expires in <n> d" (Available, 90 days or fewer), "Expired · quarantined" (expired, in Quarantine), "New MRP" (the MRP differs from the product's previous batch); a batch held in Pending return or In transit shows that state.
- Given an Available batch whose expiry date has passed (the date after its last valid day, IST), when this screen or any other action finds it, then it is moved to Quarantine with ledger lines (reason "Expired", user "System") and is shown as "Expired · quarantined"; it is never shown as Available and never picked (BR-8, no scheduled job).
- Given Landed and Margin, then they are worked as in OQ-79-6 and shown "—" where a batch has no purchase rate; Margin is a percentage with one decimal.
- Given the action cell, then a batch in Quarantine at the Central Store, or near expiry there, shows "Return" for a Central Store user (opens Vendor return, 79-8); for others the cell is empty (the mockup's "Plan" is Later).

Low stock and filters
- Given the Low stock pill, then the list shows products whose Available quantity in the working store is at or below the store's reorder level (SAH-78-5), with product, Available, reorder level and, for a non-central store, a link "Raise indent" (79-9) or, at the Central Store, "Add to purchase order" (79-6); a product with no reorder level set is not listed.
- Given the Schedule pill, then only products with schedule H, H1 or X (the product master's flag) are shown, with a schedule chip.
- Given the top buttons, then "Stock adjustment" (79-5) and "Start stock count" (79-17; the mockup says "Start cycle count") show only to people with the capability; "Branch transfer" is not built.

Roles, tenants, errors
- Given the owner, then every store is reachable; given anyone else, then only the stores they are mapped to; an unmapped store answers 404 (C-1).
- Given a store with no stock, then "No stock in this store yet. Post opening stock or receive stock." (NEW).
- Given another clinic's data, then never shown; a slow load shows "Could not load. Try again."
- Given a product from the global catalogue that the unit has not adopted, then it has no stock and is not offered.
- Given the store has an open stock audit (79-17) and the person can count but cannot review the count, when they open this screen, then for the products in that audit the Qty, the Low stock quantity and the stock value show "Counting in progress" (NEW) and no number is sent; the batch, expiry, MRP and status of those products are still shown, and other products show as usual (C-9, DEC-080 L12). A person who can review the count sees the quantities.

## Notes for design
- Stock value, landed and margin follow OQ-79-6 (open, accountant).
- Expiry is held as a date (last day of the month when typed as month and year) and shown as month and year (OQ-79-7).
- Detection and quarantine of expired stock happens in a read path without a job (ADR-028 D6); the architect decides how a read can write safely.

---

### SAH-79-4 · As a store user, HOD, Central Store user or owner, I want to see one product's stock across stores by state, so that I know what is usable, what is on the way and what is stuck
Priority: Must
Screen: P&I mockup `11-store-stock-and-ward-consumption.html`, tab "Current stock · By store, with central store view" (card "<product> · all stores", the buttons My store and All stores, table Store, Available, In transit (in), Pending return, Quarantine, Reorder level, Status, row "Unit total"). Package WP-D. BRD I6 (store-wise stock by bucket).

## Acceptance criteria
- Given a person opens "Store stock & consumption" and the tab "Current stock", then a product search is shown ("Pick a product to see its stock in every store.") and, once a product is chosen, the heading "<product> · all stores" or "<product> · <store>" and the segmented buttons "My store" and "All stores".
- Given "All stores", then one row per store the person can reach shows Available, In transit (in), Pending return, Quarantine, Reorder level (the store's setting) and Status; the owner reaches all stores; everyone else sees only mapped stores (OQ-79-21, recommended); the last row "Unit total" adds up the rows shown, a dash where nothing applies.
- Given the Status column, then it reads "OK", "Below reorder · raise indent" (Available below the store's reorder level) or "Indent in transit" (units on the way from a dispatch); a store with no reorder level set shows "OK".
- Given the note "Only Available stock can be issued, sold or consumed.", then it is shown under the table, and each store's name opens that store's batches and expiry (79-3, opened on that product), only for a store the person can reach.
- Given "My store", then only the working store's row is shown with its quantities by state.
- Given In transit and Pending return, then they are the quantities of the receiving store (the store that will receive or has been rejected to), and they are not added to any store's Available (BR-4).
- Given the quantities, then they come from the balances that the ledger lines keep, so the sum of the ledger equals the balance shown (reconciliation is a QA check).
- Given an unmapped store, then it is not listed and its id answers 404; another clinic's stores are never shown.
- Given no stock of the product anywhere, then "No stock of this product yet."
- Given a slow or failed load, then "Could not load. Try again."
- Given the store has an open stock audit (79-17) that includes the product, and the person can count but cannot review the count, then that store's row shows "Counting in progress" (NEW) in Available, In transit, Pending return, Quarantine and Status, no quantity is sent, and the "Unit total" leaves that store's quantities out; the same person sees the other stores' rows as usual (C-9, DEC-080 L12). A person who can review the count sees the quantities.
- Given a phone, then the table scrolls sideways and the product search is usable.

## Notes for design
- Read-only. The tabs "Ward consumption" and "Return / damaged" of the same screen are 79-16 and 79-14.
- The mockup draws the tab as one product view; a store-wide list (all products in one store) is the Batch inventory screen of 79-3.

---

### SAH-79-5 · As an HOD, pharmacist, Central Store user or owner, I want to correct a batch quantity with a reason and, where my rules say so, an approval, so that mistakes are fixed by new ledger lines and never by editing stock
Priority: Should
Screen: P&I mockup `06-batch-inventory-and-expiry.html`, card "Stock adjustment with audit trail" (fields Batch, Book → physical, Reason (required), Photo evidence; the note "Adjustments above ₹1,000 or on Schedule X items go to the owner for approval. Before/after values, user, device and time are stored permanently."). Not in the BRD I1 list: OQ-79-27 (recommended: build). The photo field is not built (OQ-79-25). Package WP-D.

## Acceptance criteria
- Given a person with the right (HOD, pharmacist, Central Store user or owner; OQ-79-27) opens "Stock adjustment" from Batch inventory, then a form shows Batch (an Available batch of the working store, as "<product> · <batch>"), "Book → physical" (the system quantity shown, and a field for the physical quantity), Reason (chips from the Stock adjustment list), an optional note, and the button "Submit adjustment"; there is no photo field.
- Given the physical quantity equals the book quantity, or is not a whole number of 0 or more, then 400 with a field message ("No change to post" for equal) and nothing is saved.
- Given a valid form, then the screen shows the difference as "+n" or "−n" and its value (difference times the batch's purchase rate per issue unit, without GST; BR-17).
- Given a reason is not chosen, then 400 "Choose a reason" (the reason is required).
- Given the unit's approval rule for stock adjustment has level 0, then the adjustment posts at once; otherwise it waits with "Waiting for approval" and goes through the inbox (79-2); the item flag Schedule X in the rule can add a level or send it to the owner, and an amount above the owner's limit goes to the owner (BR-12, BR-17).
- Given the adjustment is approved (or needs none), then it posts ledger lines of the transaction "Stock adjustment" on the batch in the Available state with the reason, the user who raised it and the approver, the reference of the adjustment document, and a link to the earlier line when the user picked one to correct; the balance changes by the difference only.
- Given a decrease that is larger than the Available quantity at posting time, then 409 `insufficient-stock` and nothing is posted (C-2).
- Given an increase, then the extra units are added to the same batch (they are found stock, not a new batch).
- Given the item is Schedule X, then the person raising must be a pharmacist and a second signature by a different person is required (BR-32, OQ-79-8); the owner cannot raise it; otherwise 403 `schedule-x-pharmacist`.
- Given a Schedule X adjustment, when the person opens the second-signer step, then the list of second signers comes from the server and holds the other active pharmacists and owners of the clinic (the person need not be mapped to this store), sorted by name, each shown with name and role ("Pharmacist" or "Owner") and nothing else about them (no mobile number); it never contains the person raising it; only a pharmacist mapped to the store can open it (the owner and other store users get 403 `store-permission-denied`); the chosen person confirms with their own PIN (colleague PIN check, ADR-028 D7); a name sent by the client that is not on the list, or is the caller, is refused; both names are stored (OQ-79-8, C-10).
- Given a Schedule X adjustment and no other active pharmacist or owner in the unit, then the list is empty, the screen says "No other pharmacist or owner is available to sign." (NEW) and nothing is saved.
- Given a stock movement of a Schedule X product by a person without a second signer, then the server refuses it, and the stock ledger refuses it too, so the stock does not change (C-10, DEC-080 L14).
- Given the store has an open stock audit (79-17) and the person can count but cannot review the count, then the system quantity ("Book") is not shown for the batches of the products in that audit: the form shows "Counting in progress" (NEW) in place of the book quantity, the "+n" or "−n" difference is not shown, and no quantity is sent, not even in an error message such as "No change to post" (C-9, DEC-080 L12; OQ-79-38 asks whether such a person may adjust these products at all).
- Given an adjustment is waiting for approval, then a second adjustment on the same batch is refused with 409 "An adjustment for this batch is already waiting."
- Given the person who raised it, then they cannot approve it (C-3).
- Given the adjustment is rejected, then no stock changes and the raiser sees the reason.
- Given the History card, then it shows raised, approved or rejected, and posted, each with user, store, time, book and physical quantity and reason (BR-26).
- Given an expired or Quarantine batch, then it is not offered here (only Available batches are adjusted); damaged or expired stock goes through 79-14.
- Given roles and tenants, then a store user without the right gets 403; another store of this clinic that the person is not mapped to, 404; another clinic's batch, 404.
- Given a slow or failed submit, then the form keeps what was typed and the button is disabled while the request runs; a double tap submits one adjustment.

## Notes for design
- Corrections are new ledger lines with `reverses_ledger_id` set where a specific earlier line is corrected (ADR-028 D6). This is also the correction path for opening stock and receipts, since no document is un-posted.
- The reason list is the Stock adjustment list of SAH-78-10, to which "Count mismatch" and "Theft or loss" are to be added (OQ-79-27).
- Adjustment documents need their own series (OQ-79-14).

---

### SAH-79-6 · As a Central Store user, I want to create purchase orders from the reorder list or by hand, get approval above my limit and print them for the vendor, so that I buy what is needed from licensed vendors
Priority: Must
Screen: P&I mockup `07-purchase-orders.html` (header "Purchase orders" with the Central Store line, the button "+ Manual PO", KPI tiles Draft, Waiting for approval, Sent to vendor, Partly received, Overdue delivery; card "Reorder suggestions · <n> items"; the PO detail card; card "Recent POs"). Not built from this screen: the "USP" formula (average daily use, lead time), "Avg use / day", "Best vendor" ranking, and "Send on WhatsApp / email". Package WP-E. BRD I2, AC-M5.

## Acceptance criteria
Reorder list and creating
- Given a Central Store user (or the owner) opens "Purchase orders" with the Central Store as working store, then they see the KPI tiles with counts, the card "Reorder suggestions · <n> items", the PO detail area and "Recent POs"; given any other working store, then "Purchase orders are raised at the Central Store." (NEW) and nothing else.
- Given the reorder list, then it lists products whose Central Store Available stock plus quantity on open purchase orders is at or below the product's reorder level at the Central Store (SAH-78-5, OQ-79-31), with columns Select, Item and purchase unit, CS stock, All stores (owner only, OQ-79-21), Open PO, Suggested (reorder level minus that sum, at least 1, whole purchase units, editable), Vendor (the product's preferred vendor, or a choice) and Rate (the last received rate from that vendor, or empty).
- Given a product that already has an open purchase order covering it, then the row shows the PO number and "0" suggested and is not selected.
- Given the Central Store has an open stock audit (79-17) and the person can count but cannot review the count, then for the products in that audit the columns CS stock and All stores show "Counting in progress" (NEW), and no quantity is sent (C-9, DEC-080 L12; OQ-79-38 asks how the Suggested column behaves for those rows).
- Given the person selects rows and taps "Create POs (split by vendor)", then one Draft purchase order is made for each vendor among the rows; a row without a vendor asks for one first; a vendor that is inactive, does not supply the Central Store or is blocked ("Blocked · licence expired") cannot be chosen: 409 `vendor-blocked` "This vendor's drug licence has expired. It cannot get a new purchase order." (AC-M5, SAH-78 BR-21).
- Given "+ Manual PO", then a form asks Vendor (active, unblocked, supplies the Central Store), Delivery expected (a date, today or later; NEW, OQ-79-32), Credit days (NEW; the field may be left empty, and the server then fills it from the vendor's credit days when the order is saved; the person may type another value) and lines (product, quantity in whole purchase units, rate per purchase unit); a product with no adopted or own active status cannot be added.
- Given a person who cannot see the vendor's credit terms (the reduced vendor view below) opens "+ Manual PO", then the Credit days field is shown empty with the hint "From the vendor" (NEW), and the saved purchase order shows the vendor's credit days; given the field is empty and the person can see the vendor's terms, then the same fill happens on save.
- Given a vendor that is blocked ("Blocked · licence expired"), then it is still listed on the Vendors screen with that mark (SAH-78-8) and the vendor list used for purchasing, and it cannot be chosen on the purchase order forms (reorder list rows and "+ Manual PO"), on the form for a direct receipt (79-7) or on the vendor return form (79-8); a receipt against an already sent purchase order of such a vendor is not affected (79-7). Wording of the mockup `16`: "A vendor with an expired drug licence is blocked from new purchase orders automatically"; blocking a direct receipt follows OQ-79-9d and blocking a vendor return follows the tech lead's decision of 19 Nov 2026 (OQ-79-39).
- Given a person who buys at the Central Store (a Central Store user) but cannot open Setup, when they choose a vendor for a purchase order, then the vendor list shows only the vendors that supply the Central Store, each with name, status and the mark "Blocked · licence expired" where it applies; the licence number and date, GSTIN, contact name and phone and credit terms are not shown to them (the owner, and anyone who can open Setup, see the full vendor list as in SAH-78-8; contract 0.3.3 and the tech lead's reduced view, DEC-073). Given the same person asks for the list of any other store, or without a store, then 403.
- Given a line, then quantity must be a whole number of 1 or more, the rate an amount above 0 with up to 4 decimals, and a document has at most 200 lines (OQ-79-35); otherwise 400 with a field message; the same product twice in one purchase order is refused.

Detail, approval and sending
- Given a purchase order, then the detail card shows "<PO number> · <vendor> <status chip>", the table Item, Qty, Rate, Amount, the line "Total incl. GST", the note of the approval rule ("POs above ₹ <limit> need owner approval", read from the unit's rule; nothing when the rule has no limit) and "Delivery expected <date> · credit <n> days".
- Given amounts, then each line amount is quantity times rate rounded half up to the paisa, GST is worked per line at the product's GST % on that amount, and the total is the sum (BR-9).
- Given the person submits a Draft, then the purchase order takes its number from the PO series (C-4) and the approval rule decides: level 0 gives "Approved"; otherwise "Waiting for approval" (the inbox, 79-2) with the button "Open in approvals"; the limit looks at the total including GST (BR-17).
- Given an approved purchase order, then "Print purchase order" opens the browser print view (header from the print template, vendor, lines, totals, delivery date, credit days) and "Mark as sent" sets "Sent to vendor" with the date; there is no WhatsApp or e-mail button (OQ-79-32).
- Given a purchase order that is Waiting, Approved or Sent, then it cannot be edited; the person may cancel it (reason chip; allowed only while nothing has been received) and it shows "Cancelled" and keeps its number; a Draft can be edited or discarded (OQ-79-19).
- Given a rejection, then the purchase order returns to its raiser as "Rejected" with the reason and can be copied into a new draft (NEW).
- Given receiving (79-7), then the status moves to "Partly received", "Received" or "Received · <n> short" when closed short, from the receipts; a purchase order with a delivery date in the past and goods outstanding shows "Overdue <n> days" and counts in the "Overdue delivery" tile.
- Given a vendor that becomes blocked after a purchase order was sent, then that purchase order is untouched and can still be received (79-7); only a new purchase order is refused.

Lists, roles, errors
- Given "Recent POs", then it lists PO, Vendor, Value and Status (chip), newest first in pages, and a row opens the purchase order; the tiles filter the list.
- Given no purchase orders, then "No purchase orders yet. Create one from the reorder list or choose + Manual PO."
- Given a non-Central-Store user (store user, HOD, pharmacist), when they call the purchase order APIs, then 403; the owner may; a store they are not mapped to, 404; another clinic's purchase order, 404.
- Given two users submit or edit the same purchase order, then the second gets 409 `changed`.
- Given a slow or failed save, then what was typed is kept; a double tap creates one purchase order.
- Given the History card, then each step (created, submitted, decided, sent, received, cancelled) shows user, time and reason.

## Notes for design
- The suggested quantity here is the basic rule of OQ-79-31 (the "Later" formula is not built).
- "Approved" and the delivery date and credit days fields are NEW (the mockup shows them only as text, OQ-79-32).
- The blocked flag comes from SAH-78-8 (computed from the licence date on read).
- Purchase order amounts are in purchase units; stock is posted in issue units at receiving (79-7).

---

### SAH-79-7 · As a Central Store user, I want to receive stock against a purchase order or directly, with batch, expiry, MRP, free quantity, discount and GST, flags for short supply and rate changes, so that the Central Store stock and the vendor payable are correct
Priority: Must
Screen: P&I mockup `08-purchase-ocr-and-grn.html` (header "Purchase invoice → GRN", step bar, invoice header, line grid with Check chips, the buttons Save draft, "Send for approval (rate ↑)" and "Post GRN & update stock", the "3-way match" card). Not built from this screen: invoice sources Photo / PDF → AI OCR, Distributor e-invoice and CSV / Excel with saved mapping; the "GSTIN verified" chip; the "Cold chain" check; "MRP changed · reprint labels" label printing (the chip "MRP changed" is shown without a reprint action); the "Best distributor for next order" card. Step 2 of the step bar "Invoice capture · Photo → OCR" is shown as "Enter invoice". Package WP-E. BRD I3, AC-I1.

## Acceptance criteria
Start and header
- Given a Central Store user (or the owner) opens "Receive stock (GRN)" at the Central Store, then they can start a receipt against a purchase order (a list of this unit's Sent or Partly received purchase orders, with the button "Purchase orders (<n> open)") or direct; the step bar shows "1 · PO", "2 · Enter invoice", "3 · Verify", "4 · Post to stock", "5 · Payable".
- Given the header, then it asks Vendor (from the purchase order, or chosen for a direct receipt), Vendor invoice number, Invoice date, Credit days (from the purchase order or vendor, editable) and Invoice total (as printed on the paper invoice, OQ-79-9b); it shows the vendor's GSTIN (format only, no "verified" mark) and "Matched to <PO number>" for a receipt against a purchase order.
- Given a person who buys at the Central Store but cannot open Setup chooses the vendor of a direct receipt, then the vendor list shows only the vendors that supply the Central Store, with name, status and "Blocked · licence expired"; licence number and date, GSTIN, contact and credit terms are not shown to them (same reduced view as 79-6).
- Given the vendor choice of a direct receipt, then a blocked vendor ("Blocked · licence expired") is not offered; given a direct receipt for a blocked vendor (expired licence) is sent anyway, then 409 `vendor-blocked` (OQ-79-9d); given a receipt against an already sent purchase order of a vendor that has since become blocked, then it is allowed with a warning "Drug licence expired".
- Given the same vendor invoice number already received for this vendor, when saved or posted, then 409 `duplicate-invoice` "That invoice was already received." and nothing is saved.

Lines
- Given a receipt against a purchase order, then the screen starts the lines from the remaining quantities of the order shown on the screen (product and quantity still to come), and the screen sends its own lines to the server, each with the batch, expiry and MRP that the person types, because the server cannot guess them; a receipt saved against a purchase order with no lines is a draft with no lines, not a draft filled by the server (tech lead decision, 19 Nov 2026; the contract text "filled from the remaining quantities" is corrected by the architect); for a direct receipt the person adds products (adopted or own active products of the unit).
- Given the grid, then each line has Item (with its pack), Batch, Expiry (month and year), Qty (received, in purchase units), Billed qty (NEW, default equals Qty, OQ-79-9a), Free (scheme units), PTR (rate per purchase unit), MRP, Disc (%), GST (% from the product master, read-only), Landed per unit, Margin and a Check chip; MRP is per issue unit as printed on the pack (OQ-79-6).
- Given validation, then Batch is 1 to 20 characters, Expiry a month and year that is not in the past (a past or invalid expiry is 400 "Expiry must be in the future"), quantities whole numbers (Free 0 or more), PTR and MRP above 0, Disc 0 to 100 with up to 2 decimals; a PTR above the MRP is a warning on the line, not an error.
- Given Landed per unit and Margin, then they are worked as in OQ-79-6 and shown as the line is typed, with the note "Landed cost spreads free quantity and discount across units, so margin is true margin." (wording follows OQ-79-6).
- Given a line whose product is Schedule X, then the person posting must be a pharmacist and a second signature by a different person is required (BR-32, OQ-79-8); otherwise 403 `schedule-x-pharmacist` and the line shows "Pharmacist and second signature needed".
- Given a receipt line of a Schedule X product posted by a person without a second signer, then the server refuses the post, and the stock ledger refuses the line too; the second signer is chosen from the list described in 79-5 (the server's list of the other active pharmacists and owners of the clinic, never the caller; C-10, DEC-080 L14).

Checks and approval
- Given the Check column, then each line shows "OK", "Rate ↑ <n.n>% vs PO" (or "vs last" for a direct receipt; OQ-79-10), "Short <n> vs PO" (Billed or received less than ordered), "MRP changed" (the MRP differs from the product's last batch) or a combination; the mockup's "OCR … confirm batch" and "Cold chain" checks are not built.
- Given a short line on a purchase order, then the person chooses per line "Balance to come" (the purchase order stays Partly received) or "Close short" (the line is closed and the purchase order shows "Received · <n> short"); the choice is kept.
- Given a rate above the purchase order rate by more than the unit's rate-rise limit (Approval rules, OQ-79-10; no limit means no check), then the button "Post GRN & update stock" is replaced by "Send for approval (rate ↑)"; the receipt waits with "Waiting for approval" in the inbox (79-2) with the line "<product> · ₹ 70.00 → ₹ 74.30 (+6.1%)"; nothing is posted until approved; on rejection the receipt returns to Draft with the reason so that the rate can be corrected.
- Given a receipt waiting for the rate-rise approval, then its status is "Waiting for approval" (it has no GRN number yet and no stock effect); given it is approved, then the approval does not post it: the receipt shows as approved and ready to post, and a Central Store user (or the owner) posts it with the normal button "Post GRN & update stock"; the approver of the rate rise does not need the purchase capability to read it. If the receipt is changed after approval, the approval no longer covers it and a rate rise needs a new approval at the next post. The button is chosen by the server's `needsApproval` flag (contract 0.3.3), not by the screen's own sum.
- Given the "3-way match" card, then it shows a small read-only list of lines that differ: PO quantity, billed quantity and received quantity, PO rate and billed rate (OQ-79-9a); it appears only when a line differs.
- Given Invoice total and the computed total, then the screen shows the difference; given the difference is more than ₹ 1 (rounding allowance, OQ-79-9b), then posting is refused with 400 "Invoice total does not match the lines." until the difference is explained or corrected.

Posting
- Given a receipt without open approval, when the person taps "Post GRN & update stock" (the button's wording is the mockup's, `08`), then in one step: the received plus free quantities, converted to issue units (purchase quantity times conversion, BR-9), are added to the Central Store as Available through ledger lines of the transaction "GRN" with the receipt number as reference; batches are created or topped up (same product, batch and expiry with the same MRP is added; a different MRP for the same batch and expiry is 409 "Same batch with a different MRP. Check the batch number."; BR-10); the purchase order is updated; the vendor payable entry is created (below); everything is saved or nothing.
- Given the posting, then the receipt takes its number from the GRN series (C-4) and the status is "Posted"; a posted receipt cannot be edited; a mistake is corrected with a stock adjustment (79-5) or a debit note (79-8).
- Given a draft, then "Save draft" keeps it without a number or stock effect; the person can return and continue.
- Given short billed lines (Billed above Received, OQ-79-9a), then after posting a draft debit note for the short supply is prepared for Vendor return (79-8); a rate difference is added only for lines where the person ticked "Claim rate difference" (OQ-79-9c); the screen says "Debit note prepared: <n> lines, ₹ <amount>".
- Given a line where the person ticked "Claim rate difference" (NEW, OQ-79-9c), then the claim is prepared as a line of the draft debit note, a "short supply" type line on that receipt line (NEW; the contract has no separate rate-difference line type yet): the quantity is the billed quantity converted to issue units, the amount per issue unit is the rise over the reference rate (the purchase order rate, or the last rate from this vendor), and the credit is 100%; it carries no stock movement and no pick, and the debit note shows it as one of its lines. A separate rate-difference line type is a later contract and migration change. New go-live check G-14: the accountant confirms how a rate difference is claimed (as a debit note line, at 100%, with the GST reversal).

Vendor payable
- Given a posted receipt, then a vendor payable entry is created in the pharmacy module for the invoice amount (the invoice total typed and checked above) with the due date = invoice date plus credit days; the receipt shows "Payable · due 16 Dec 2026" (OQ-79-5, BR-21); no payment can be recorded in this release.
- Given a debit note's credit note is received (79-8), then the payable is reduced by it.

Roles, tenants, errors
- Given a person who is not a Central Store user or owner, then 403; a store they are not mapped to, 404; another clinic's receipt, vendor or purchase order, 404.
- Given a product that is inactive, not adopted, or has no purchase unit or conversion, then it cannot be added (400).
- Given a failed or slow post, then the grid keeps what was typed, nothing is posted, and the button is disabled while the request runs; a double tap posts once.
- Given the History card, then it shows created, submitted for approval, decided, posted, with user, time and reason.
- Given a phone or tablet, then the receipt grid scrolls sideways and the line fields are usable for receiving at the dock.

## Notes for design
- The stock-ledger effect is in issue units (ADR-028 D6); amounts in paise, unit rates numeric(14,4) (D10).
- Billed qty, "Claim rate difference", "Close short" and the 3-way match list are NEW (OQ-79-9).
- Duplicate invoice check key: tenant, vendor, invoice number.
- GST on the invoice line comes from the product master (SAH-78-6). A different rate on the vendor's invoice is not handled (the owner corrects the master).

---

### SAH-79-8 · As a Central Store user, I want to return near-expiry or quarantined stock to a vendor with a debit note and track it to the vendor's credit note, so that stock leaves properly and credit is not forgotten
Priority: Must
Screen: P&I mockup `09-vendor-return-and-debit-note.html` (header "Vendor return & debit note", button "+ New return"; vendor line with the return policy; card "Items eligible to return to <vendor>"; the total "Debit note DN-… · <n> lines · ₹ …" and the button "Create debit note & pick list"; card "Track DN-…" with steps Created, Picked & packed, Handed to vendor rep, Credit note received, Adjusted in payable). Not built: the "Non-moving" source, "Signature + photo", the "30 days on the Command Center" flag (a report instead, 79-19). Package WP-E. BRD I4.

## Acceptance criteria
Choose and pick
- Given a Central Store user (or the owner) taps "+ New return" at the Central Store and chooses a vendor, then the vendor line shows the return policy from the vendor master ("return policy: up to <d> days before expiry, <c>% credit", SAH-78-8; extra levels OQ-79-11).
- Given a vendor that is blocked ("Blocked · licence expired"), then it is not offered when the person chooses a vendor for a new return (tech lead decision, 19 Nov 2026; the mockup `09` says nothing about blocked vendors and `16` says "blocked from new purchase orders"; OQ-79-39), and the same reduced vendor list as 79-6 applies to a Central Store user who cannot open Setup.
- Given the vendor's two credit levels (credit % for goods damaged on receipt, and credit % after expiry; the mockup policy line "damaged on receipt, 100%; after expiry, 50%"), then this story uses them. They already exist on the vendor: they are built in SAH-78-8 (contract 0.2.1, migration 1290), each 0 to 100 with at most 2 decimals and optional, and are not part of the WP-E migration. The vendor line shows them.
- Given a return line, then its Credit % starts from "Credit for damaged on receipt" when the line's reason is damaged on receipt, from "Credit after expiry" when the batch is expired, and otherwise from the vendor's standard credit ("Credit given on return, %"); a level that is empty means the standard credit applies (tech lead decision of 18 Nov 2026; go-live G-18, owner and accountant).
- Given a return line that is both damaged on receipt and expired, then its Credit % starts from the lower of the two levels, "Credit for damaged on receipt" and "Credit after expiry" (an empty level counts as the standard credit before the two are compared), because the vendor owes no more than its own policy (DEC-078 item 5, amended 19 Nov 2026; go-live check G-18, accountant). The mockup chip "Quarantine · damaged" means damaged on receipt; a line from that chip whose batch is also expired follows this rule. The person may still change the Credit % as in the criterion below.
- Given the vendor, then the card "Items eligible to return to <vendor>" lists, from that vendor's batches at the Central Store: units in Quarantine (any reason), Available units whose expiry is within the vendor's "days before expiry", and short supply from a posted receipt of this vendor that has no debit note yet; columns Select, Item · batch, Expiry, Source (chips "Quarantine · indent rejection", "Quarantine · damaged", "Quarantine · expired", "Near expiry · <n> days", "GRN short · <receipt>"), Qty, Rate, Credit %, Credit.
- Given opening-stock batches and batches with no vendor, then they are not offered (OQ-79-11).
- Given each line, then the person enters a quantity from 1 to the units available in that source state; more is refused with 400; units already held by another debit note are not offered.
- Given Credit %, then it starts from the level named in the criterion above (damaged on receipt, after expiry, or the vendor's standard credit; OQ-79-11), the person may change it, a changed value is marked "Changed from policy" and kept in the history, and Credit = quantity times rate times Credit % rounded half up to the paisa.
- Given short-supply lines, then they carry no stock movement (the units never entered stock) and no pick; only their value goes to the debit note (OQ-79-9a).

Debit note
- Given selected lines, then the footer shows "Debit note · <n> lines · ₹ <credit total> + GST reversal" with GST reversal worked per line at the product's GST % on the credit value, round half up, shown separately (OQ-79-13; accountant to confirm, G-14).
- Given the person taps "Create debit note & pick list", then the debit note takes its number from the debit note series (OQ-79-14, C-4) and the vendor-return rule decides: level 0 proceeds; otherwise it waits in the inbox (79-2); the approver is the Central Store HOD or the owner by rule.
- Given the debit note is approved (or needs none), then the selected units move from Quarantine or Available to Pending return at the Central Store, so nothing can use them (OQ-79-12); a browser-print pick list shows item, batch, quantity and rack.
- Given the tracking card, then the steps are Created (name and time), Picked & packed, Handed to vendor rep, Credit note received, Adjusted in payable; each step has a button for the next step and records who and when.
- Given "Handed to vendor rep", then the person records the representative's name and confirms; the units leave stock through ledger lines of the transaction "Vendor return" out of Pending return (photo and signature are not built, OQ-79-25).
- Given "Credit note received", then the person records the vendor's credit note number, date and amount; a difference from the debit note is shown; the vendor payable is reduced by the credit note amount and the step "Adjusted in payable" is done at once (OQ-79-5); no payment is recorded.
- Given a debit note with goods to hand over (at least one stock return line), then the vendor's credit note is recorded only after "Handed to vendor rep"; before that, 409 `doc-state`.
- Given a debit note for short supply only (every line is a short-supply or rate-difference line, so there are no goods to hand over), then the steps "Picked & packed" and "Handed to vendor rep" do not apply and the credit note can be recorded as soon as the note is "Created" or "Picked" (contract 0.3.3, pharmacyRecordCreditNote; tech lead decision, 19 Nov 2026), after which the payable is adjusted at once as above; it still follows the approval rule of the vendor return first (a note waiting for approval cannot get a credit note).
- Given a debit note not yet handed over, then it can be cancelled with a reason chip (from the approval-reject reasons): the held units are released and move back to the state they came from, and the debit note keeps its number as "Cancelled"; given the batch has expired in the meantime, then the released units go to Quarantine instead of Available; a waiting approval request is withdrawn; a note that has been handed over cannot be cancelled (409 `doc-state`).
- Given the draft debit note prepared by a receipt (79-7), then it appears here as "Draft" for the Central Store user to review, complete and create.

Roles and errors
- Given a store user, HOD or pharmacist who is not the owner, then 403; a store they are not mapped to, 404; another clinic's debit note or vendor, 404.
- Given units that are no longer there at creation (used or moved by someone else), then 409 `insufficient-stock` naming the line and nothing is held.
- Given the person who raised the debit note, then they cannot approve it (C-3).
- Given a Schedule X item, then the Schedule X rule applies to the hand-over (pharmacist and second signature, BR-32); a stock movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; the second signer comes from the server's list, 79-5).
- Given a slow or failed save, then the selections are kept and the button is disabled while the request runs.
- Given the History card, then every step shows user, time, quantities and reason.

## Notes for design
- Unmapped decisions: credit terms (OQ-79-11), the Pending-return hold (OQ-79-12), GST wording (OQ-79-13).
- The two vendor credit levels are read from the vendor master (SAH-78-8, migration 1290); nothing is added to the vendor in WP-E. The migration for WP-E (14xx) carries no vendor column.
- A debit note line has a type: stock return, or short supply (no stock effect).

---

### SAH-79-9 · As a store user, I want to raise an indent for what my ward or store needs and send it for approval, so that supplies come from the Central Store through a written, approved request
Priority: Must
Screen: P&I mockup `10-ward-and-store-indent-workflow.html`, step "1 · Raise" (header "<IND-…> · <from store> → <to store> · <priority> · raised <date and time>", card "Raise indent": From, To, Priority, table Item, <store> stock, Requested; the buttons "Save draft" and "Submit for HOD approval"; the "Stock impact" card and the "History" card). Not built: the columns "Avg use / day" and "Suggested" and the note about suggested quantity (Later). Package WP-F. BRD I5, AC-I2.

## Acceptance criteria
- Given a person mapped to a non-central store with "Raise indent" (store user, HOD, delegate or pharmacist) opens "Ward / store indents", then the list shows the indents of the working store (number, to store, items, priority, status, raised by and time) with a status filter and the button "+ New indent"; a Central Store user and the owner at the Central Store do not see "+ New indent" (the Central Store buys by purchase order); the owner may raise for a store.
- Given "+ New indent", then the card "Raise indent" shows the line "Acting as <name> · mapped to <store> · can raise indents for <store>", the fields From (requesting store, read-only, the working store), To (issuing store, read-only, the store's "Issues from", SAH-78-3) and Priority (Routine or Urgent), and a table with Item, "<store> stock" (this store's Available quantity of the product) and Requested.
- Given the form opens for a new indent, then "To (issuing store)" is shown at once with the name of the store that the indents of this working store go to (for example "Central Store", as the mockup `10` shows); the working-store context from the server carries that store, so the screen does not wait for a draft to be saved and the note "Shown after you save the draft" is not needed (tech lead decision, 19 Nov 2026; contract 0.3.5 adds the field to the context).
- Given a line, then the person adds a product (adopted or own active product) and a quantity that is a whole number of 1 or more in the issue unit; the same product twice is refused (400 "Already on this indent"); at most 200 lines (OQ-79-35); a product with a Schedule flag shows its chip.
- Given "Save draft", then the indent is kept without a number and without effect; the person can edit or discard it.
- Given the person taps "Submit for HOD approval", then the indent takes its number from the indent series (C-4) and the rule for Indent decides (C-3): level 0 sends it straight to the Central Store (the button then reads "Send to Central Store"); otherwise it shows "Waiting for HOD approval" and appears in the inbox of the store's HOD or current delegate (79-2); an Urgent indent shows first and uses the urgent escalation time (BR-16).
- Given the person who raised an indent is the store's HOD, then the item is shown to the next approver as "Needs another approver" (OQ-79-28).
- Given a submitted indent, then it cannot be edited; the raiser may cancel it before dispatch with a reason chip and it keeps its number as "Cancelled" (OQ-79-19).
- Given the right column "Stock impact · <product>", then selecting a line shows, for this indent's two stores, Central Store saleable, In transit to the requesting store, the requesting store's Available, Pending return and any Back-order, updating as the indent moves; it shows only the two stores of the indent (OQ-79-21).
- Given the History card, then before submission it says "Nothing yet. History starts when the indent is submitted." and afterwards lists every step with user, store, time, quantities and reason (BR-26).
- Given the indent status chip, then it reads Draft, Waiting for HOD approval, Approved · with Central Store, Dispatched · in transit, Received (with open items), Closed, Rejected or Cancelled, as the story of each step says.
- Given a store with no "Issues from" store (not set up), then the screen says "This store has no supplying store. Ask your owner." and the indent cannot be raised.
- Given the requesting store, or the issuing store, has an open stock audit (79-17) that includes the product and the person can count but cannot review the count, then the column "<store> stock" and the Stock impact card show "Counting in progress" (NEW) for that product and no quantity is sent; the indent can still be raised (C-9, DEC-080 L12).
- Given a person not mapped to the store, then 404; mapped without "Raise indent", 403 `store-permission-denied`; another clinic's indent, 404.
- Given a slow or failed save or submit, then what was typed is kept; a double tap submits one indent.
- Given a phone or tablet, then raising an indent is usable (BRD section 7).

## Notes for design
- Indent value for amount conditions: BR-17 (quantity times the latest purchase rate per issue unit; OQ-79-36).
- The requesting store is the working store in the path (`{storeId}`).
- "Cancel" and "Discard draft" are NEW (OQ-79-19).

---

### SAH-79-10 · As an HOD or delegate, I want to approve, change or reject an indent line by line, so that approval stays with the store's HOD and wards ask for the right quantity
Priority: Must
Screen: P&I mockup `10-ward-and-store-indent-workflow.html`, step "2 · HOD approval" (line "Acting as <name> · HOD, <store> · approval stays with HOD (or the delegate set by admin)"; table Item, Requested, Approved qty, Change, Line decision; field "Remark (required when rejecting or reducing)"; buttons "Reject indent" and "Approve & send to Central Store"). Package WP-F. BRD I5, AC-I2.

## Acceptance criteria
- Given an HOD or the current delegate of the requesting store opens an indent waiting for approval (from the inbox "Review", 79-2), then the card "Approve indent" shows the line "Acting as <name> · HOD, <store>" (or "Delegate" with the end date) and the table Item, Requested, Approved qty (editable, starting at the requested quantity), Change (for example "+4 by HOD", "−5 by HOD", or "—") and Line decision ("Approve", or "Reject" when the approved quantity is 0).
- Given the person changes an approved quantity, then it must be a whole number of 0 or more; increasing is allowed and shown as "+n by HOD"; reducing or rejecting a line needs the Remark (a reason chip and optional note, OQ-79-15; "Other" needs a note); an increase is recorded with before and after quantities.
- Given "Reject indent", then a Remark is required, the indent becomes "Rejected" with the reason shown to the raiser, and no stock moves.
- Given "Approve & send to Central Store", then at least one line must have an approved quantity above 0; when every approved quantity is 0, the approval is refused with 400 and the field message "Reject the indent with a reason instead." (NEW; the mockup `10` has no text for it; tech lead decision, 19 Nov 2026, replacing "all zero is treated as a rejection"), nothing is decided and the indent stays waiting; otherwise the indent becomes "Approved · with Central Store" and appears in the Central Store's dispatch list (79-11); the decision is saved with name, capacity (HOD or Delegate), time, the approved quantities and the remark.
- Given a rule with a second level (for example owner above a limit, BR-17), then after the HOD approves the indent waits for the owner as "Waiting for owner approval" and the Central Store sees it only after the final approval.
- Given the person is the one who raised the indent, then they cannot approve it (403 `self-approval`, C-3); given the HOD is away and the delegate's dates are current (IST), then the delegate acts; given a delegate whose dates ended, then 403 `store-permission-denied` and the indent shows to the owner if the store has no current HOD or delegate.
- Given a person who is an HOD of another store, then the indent answers 404 for them; a store user without the approve capability, 403.
- Given two people decide at once (HOD and delegate, or HOD and owner), then the second gets 409 `already-decided`.
- Given the indent was waiting longer than its escalation time (2 hours, 30 minutes if urgent, set in SAH-78-9), then it also shows to the next approver as "Escalated" (BR-16, OQ-79-29).
- Given a phone or tablet, then the approval card is usable (BRD section 7: approvals on tablet or phone).
- Given the History card, then the decision shows "Approved · IV NS changed 20 → 24" style entries with user, time and remark (BR-26).

## Notes for design
- Approval goes through the engine of 79-2; the rule type is Indent; the "store approver" is the requesting store's HOD (BR-14).
- The mockup's chips "AC08" and "AC09" are test references and are not shown.

---

### SAH-79-11 · As a Central Store user, I want to process an approved indent, change a product or quantity with a reason, dispatch by FEFO and keep or close any balance, so that wards get the right stock and every change is visible
Priority: Must
Screen: P&I mockup `10-ward-and-store-indent-workflow.html`, step "3 · CS dispatch" (line "Acting as <name> · Central Store · can change product and quantity"; table Approved item, Approved, CS stock, Dispatch item, Batch (FEFO), Dispatch qty, Balance; the note about changes and back-orders; buttons "Print issue slip" and "Dispatch to <store>"). Package WP-F. BRD I5, AC-I2, OQ-79-2 (DECIDED).

## Acceptance criteria
- Given a Central Store user (or the owner) opens "Ward / store indents" at the Central Store, then the list shows indents "Approved · with Central Store" and indents with a back-order open, each with requesting store, priority and age; an Urgent indent shows first.
- Given an indent is opened, then the card "Process & dispatch" shows "Acting as <name> · Central Store · can change product and quantity" and the table: Approved item, Approved (remaining quantity), CS stock (Available at the Central Store), Dispatch item ("Same" or a replacement), Batch (FEFO) (the batch or batches the server will pick, with expiry), Dispatch qty (editable) and Balance.
- Given Dispatch qty, then it must be a whole number from 0 up to the smaller of the remaining approved quantity and the Central Store's Available unexpired stock of that product; above the available quantity is 409 `insufficient-stock`; above the remaining approved quantity is 400.
- Given the dispatch quantity is below the remaining approved quantity, then the Balance cell asks "Back-order" or "Close balance" for that line; "Back-order" keeps the remainder open on the same indent for a follow-up dispatch; "Close balance" closes it with a reason chip (OQ-79-15); one of the two is required before dispatch (DECIDED OQ-79-2: several dispatches per indent).
- Given the person changes the product of a line (Dispatch item), then a replacement select offers active products of the unit that meet the rule of OQ-79-16 (recommended: same salt or generic name and same schedule class), and a reason chip is required (the Central Store change reasons, OQ-79-15; "Other" needs a note); the line then shows "replaced by Central Store" and the stock shown is that of the replacement.
- Given the person dispatches less than approved or replaces a product, then the reason is required and is stored with before and after quantities (BR-26).
- Given the batch column, then the server picks Available, unexpired batches by FEFO and the person cannot choose another batch; a quantity larger than the first batch is split across batches in FEFO order, each its own ledger pair; an expired batch is never picked (BR-7, BR-8).
- Given a line whose product has no usable stock and no replacement, then Dispatch qty is 0 and the Balance is "Back-order" or "Close balance"; given all lines 0, then there is nothing to dispatch and the button is disabled.
- Given the person taps "Dispatch to <store>", then in one step: for each batch the Central Store's Available is reduced and the requesting store's In transit is increased through ledger lines of the transaction "Indent dispatch" (out of the Central Store Available and in to the receiving store In transit, same reference and batch), the dispatch gets its reference "<indent number> / <dispatch count>", and the indent shows "Dispatched · in transit" (or "Approved · back-order open" if a balance is open) (C-2).
- Given a line whose product is Schedule X, then the dispatch needs a pharmacist and a second signature (BR-32, OQ-79-8), otherwise 403 `schedule-x-pharmacist` for that line; a dispatch movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; the second signer comes from the server's list, 79-5).
- Given the Central Store has an open stock audit (79-17) that includes the product and the person can count but cannot review the count, then "CS stock" shows "Counting in progress" (NEW), no quantity is sent, and a 409 `insufficient-stock` for that product does not name the available quantity (C-9, DEC-080 L12; OQ-79-38).
- Given "Print issue slip", then the browser print view shows the issue slip from the SAH-78-10 template: indent number, dispatch reference, stores, items, batches, expiry, quantities, replacements and reasons.
- Given the requesting store later opens the dispatch, then it sees every product or quantity change with its reason before it receives (79-12).
- Given two Central Store users dispatch the same indent line at once, then the first posts and the second gets 409 `changed`; no stock is dispatched twice.
- Given a person who is not a Central Store user (HOD of the requesting store, store user, pharmacist) or not the owner, then 403; another clinic's indent, 404.
- Given a slow or failed dispatch, then the quantities typed are kept, nothing is posted, and the button is disabled while the request runs.
- Given the Stock impact card, then it updates as the indent moves: Central Store saleable falls, In transit rises (OQ-79-21).

## Notes for design
- One indent can have several dispatches (DEC-063, OQ-79-2). The dispatch reference is derived from the indent number; no extra series (OQ-79-14).
- The replacement rule is OQ-79-16 (clinical, open).
- Priority and age come from the indent; urgent escalation times apply only to approval.

---

### SAH-79-12 · As any person mapped to the receiving store, I want to receive a dispatch by accepting or rejecting each line with a reason, so that stock enters my store at once, rejected stock is not lost and shortages are recorded
Priority: Must
Screen: P&I mockup `10-ward-and-store-indent-workflow.html`, step "4 · Receive" (line "Acting as <name> · … not the HOD. Any user assigned to the receiving store can accept."; table Item · batch, Dispatched, Accepted (− and +), Rejected / damaged, Reason (required); the "Check" line "Accepted + Rejected = Dispatched"; the note about Schedule X; button "Confirm receipt"). "Add photo of damage" is not built (OQ-79-25). Package WP-F. BRD I5, AC-I3, AC-I4, AC-I5 and the indent rules.

## Acceptance criteria
- Given a person mapped to the receiving store with the capability "Receive" (store user, HOD, delegate, pharmacist; not only the HOD) opens "Ward / store indents", then the dispatches "In transit" for the working store are listed and one can be opened; a store that is not the receiving store answers 404.
- Given a dispatch, then the card "Receive items" shows "Acting as <name> · <role> assigned to <store>" and the table: Item · batch, Dispatched, Accepted (a number with − and + buttons), Rejected / damaged (worked out), Reason (required when anything is rejected); a replaced product shows "replaced by Central Store" and a back-ordered line shows "<n> back-ordered".
- Given the table, then Accepted starts equal to Dispatched; the person lowers it for what is not accepted; Rejected = Dispatched − Accepted and cannot be negative; Accepted cannot be above Dispatched (400).
- Given Accepted plus Rejected, then the line "Check: Accepted <a> + Rejected <r> = Dispatched <d>" is shown for every line and confirmation is refused with 400 unless it holds for every line (AC-I4); accepted and rejected are saved as separate quantities.
- Given any rejected quantity on a line, then one reason chip is required from: Damaged in transit, Wrong item, Near expiry / expired, Excess quantity, Short / not received (OQ-79-15; one reason per line); without it, 400 "Choose a reason for the rejected quantity".
- Given the person taps "Confirm receipt", then in one step: Accepted units move from the store's In transit to its Available at once; rejected units for a physical reason (damaged, wrong item, near expiry or expired, excess) move from In transit to the store's Pending return and wait for the Central Store (BR-25); units marked "Short / not received" stay In transit as "Short · under investigation" and are never added to Available or Pending return (OQ-79-18); ledger lines of "Indent receipt · accepted" and "Indent receipt · rejected" are written with user, store, time and reason (C-2).
- Given a short or not received quantity, then the note reads "These <n> units are not added to <store> stock and cannot be returned. Central Store will investigate."; given a rejected quantity, then "The <n> rejected units do not enter <store> stock. They wait for Central Store to accept them back."
- Given the batch has expired by the time of receipt, then its quantity cannot be accepted: the person must reject it with "Near expiry / expired" (400 otherwise) (BR-8).
- Given a line whose product is Schedule X, then only a pharmacist mapped to the store can receive it and a second signature by a different person is required (BR-32, OQ-79-8); other people get 403 `schedule-x-pharmacist` for that line and can receive the other lines; the note "Schedule X / NDPS lines can only be received by a mapped pharmacist, with a second signature." is shown.
- Given the receipt is confirmed and every approved quantity of the indent is now dispatched and received (or closed), then the indent status is "Received", with the chip "· <n> rejected, waiting for CS" or "· <n> short, CS investigating" while a rejection or a shortage is open (<n> is the quantity that the Central Store has not yet accepted, or the shortage quantity not yet settled, 79-13); the status becomes "Received" at no other time (DEC-080 m-4, contract 0.3.3).
- Given the receipt is confirmed and a back-order is still open on the indent, then the indent status stays "Partly dispatched" and does not become "Received"; the label on the screen is the mockup's "Received · <product> back-order open" when every dispatch so far has been received, and it is only a label for that state, not the status "Received" (before that dispatch is received the label is "Approved · back-order open" or "Dispatched · in transit", 79-11); when a quantity is rejected and not yet accepted by the Central Store, or short and not yet settled, the chip "· <n> rejected, waiting for CS" or "· <n> short, CS investigating" is shown and wins over the back-order label, which is shown again only when nothing is rejected or short (mockup `10`; tech lead decision, 19 Nov 2026); a later dispatch of the balance has its own receipt (OQ-79-2) and when that is received, or the balance is closed, the status becomes "Received".
- Given the receipt is confirmed, then the dispatch shows "Partly received" while Schedule X lines of the dispatch wait for a pharmacist (DEC-075 Q-1); the indent has no status "Partly received" and stays "Partly dispatched" with the chip "· Schedule X lines waiting for a pharmacist" (NEW; the mockup has no Schedule X wait state; tech lead decision, 19 Nov 2026); and the History card shows the receipt with accepted and rejected quantities, reasons, user and time.
- Given a line already received, when the person confirms it again, then 409 `changed` and nothing is posted twice; lines not yet received (for example Schedule X lines waiting for a pharmacist) can still be received.
- Given a dispatch that is partly received, then the screen sends the confirmation again only for the lines not yet received (a receipt that repeats a line already received is the 409 above), and every line of the dispatch is received exactly once.
- Given a person who is not mapped to the receiving store, then 404; mapped without "Receive", 403 `store-permission-denied`; another clinic's dispatch, 404.
- Given a slow or failed confirmation, then the accepted and rejected entries are kept, nothing is posted, and the button is disabled while the request runs.
- Given a phone or tablet, then the − and + buttons and reason chips are large enough to use with one hand (BRD section 7: receiving works on tablet or phone).
- Given a dispatch, then each line is received once (DEC-075 Q-1; this replaces "received once, in full"); a back-ordered balance arrives as a later dispatch with its own receipt (OQ-79-2). A line that has been received cannot be received again, and nothing is posted twice.
- Given a dispatch with Schedule X lines and a person who is not a pharmacist, when they confirm the receipt, then the lines that are not Schedule X are received (accepted and rejected as above) and the Schedule X lines are skipped and stay In transit; the dispatch shows "Partly received", and the indent stays "Partly dispatched" with the chip "· Schedule X lines waiting for a pharmacist", until a pharmacist mapped to the store receives the Schedule X lines with a second signature by a different person (BR-32, OQ-79-8); then the dispatch shows "Received" and the chip goes (DEC-075 Q-1). A Schedule X line is received only by a pharmacist with a second signer.
- Given Schedule X units that are waiting for a pharmacist, then they are never Available in the receiving store while they wait: they stay In transit (DEC-075 Q-1).
- Given a dispatch that has only Schedule X lines and a person who is not a pharmacist, then no line can be received by that person (403 `schedule-x-pharmacist`) and the dispatch stays In transit.
- Given a Schedule X line received by a pharmacist without a second signer, then the server refuses it and the stock ledger refuses it too, and the units stay In transit; the second signer comes from the server's list of the other active pharmacists and owners of the clinic, never the caller (C-10, DEC-080 L14; 79-5).

## Notes for design
- States per BRD section 8: dispatch moved stock to In transit at the receiving store; the receipt moves it to Available or Pending return; shortages stay In transit with a shortage mark until settled (OQ-79-18).
- The "AC16-17" and "AC28" chips on the mockup are test references and are not shown.

---

### SAH-79-13 · As a Central Store user, I want to accept returned or rejected items into saleable stock or quarantine, dispute a rejection, or settle a shortage, so that rejected stock comes back only when I accept it and a shortage is investigated, never returned
Priority: Must
Screen: P&I mockup `10-ward-and-store-indent-workflow.html`, step "5 · CS accepts return" (line "Acting as <name> · Central Store · sees rejected quantity and reason"; the "Shortage, not a return" banner; table Item · batch, From, Rejected by, Qty, Reason, "Put back into" or "Settle as"; buttons "Dispute · ask <store> with reason" and "Accept <n> units back" or "Settle shortage of <n>"; the result panels) and the list "Waiting for action" of `11-store-stock-and-ward-consumption.html`. Package WP-F. BRD I5, AC-I5.

## Acceptance criteria
Return acceptance
- Given a Central Store user (or the owner) opens "Waiting for action" at the Central Store, then the list shows rejected items from indents (type "Indent rejection"), returns from stores (type "Return to CS", 79-14) and damaged items sent to the Central Store, with No., Type, Item · batch, Qty, Reason, Raised by and Status ("Waiting for Central Store", "Accepted by CS · quarantine", "Accepted by CS · saleable", "Disputed").
- Given a rejected item is opened, then the card "Accept returned / rejected items" shows Item · batch, From (the store), Rejected by (name and time), Qty, Reason (chip) and "Put back into" with the options "Saleable stock" and "Damaged / quarantine bin", the default depending on the reason: Damaged in transit and Near expiry / expired default to quarantine; Wrong item and Excess quantity default to saleable stock.
- Given the person taps "Accept <n> units back", then in one step the units leave the store's Pending return and enter the Central Store as Available or Quarantine as chosen, through ledger lines "Return accepted by CS", with the user and time; the panel shows "Return accepted. <n> units added to Central Store: <saleable stock or quarantine>." and the indent status becomes "Received" or "Closed" when nothing else is open (AC-I5).
- Given the indent chip "· <n> rejected, waiting for CS" (79-12), then <n> is the quantity that the Central Store has not yet accepted: it falls as items are accepted, a disputed item still counts because its units stay in Pending return, and the chip goes when the quantity is 0 (mockup `10`: "Rejected · waiting for CS"; tech lead decision, 19 Nov 2026; it is not the sum of all rejected quantities).
- Given nothing has been accepted yet, then no Central Store balance changes: rejected stock reaches the Central Store only after the Central Store accepts it (BR-25).
- Given a batch that has expired, when the person chooses "Saleable stock", then 409 and it can only go to quarantine (BR-8).
- Given a Schedule X item, then accepting it back needs a pharmacist and a second signature (BR-32); accepting it without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; 79-5 for the signer list).
- Given the person taps "Dispute · ask <store> with reason", then a reason chip (OQ-79-15) and an optional note are required; the item shows "Disputed" with the reason to the returning store and its HOD, who may add a reply note; the units stay Pending return in the returning store; the Central Store may still accept later (OQ-79-17).
- Given nothing was rejected on an indent, then the screen says "Nothing was rejected. All items were accepted, so nothing goes back to Central Store." and any back-order stays open on the indent.

Shortage
- Given a short or not received quantity, then the banner reads "Shortage, not a return. <n> units never reached <store>, so nothing comes back to the shelf. Central Store investigates: found in store, transit loss, or claim from carrier." and the column is "Settle as" with "Found in Central Store", "Transit loss · write off" and "Claim from carrier"; the button reads "Settle shortage of <n>".
- Given "Found in Central Store", then the units leave the receiving store's In transit and enter the Central Store as Available (Quarantine if the batch has expired), with a ledger line "Shortage found".
- Given "Transit loss · write off", then a write-off request goes through the approval rule for Write-off (level 0 writes off at once; otherwise it waits for the approver, 79-2); on approval the units leave stock with the reason "Transit loss"; the screen then reads "Shortage settled. <n> units written off as transit loss. No stock added back." (OQ-79-18).
- Given "Claim from carrier", then the units stay as "Claim open" and the shortage must later be settled as found or written off; no claim amount is tracked (OQ-79-18).
- Given the indent chip "· <n> short, CS investigating" (79-12), then <n> is the shortage quantity that is not yet settled: it falls as shortages are settled, a shortage with "Claim open" or with a write-off still waiting for approval is not yet settled and still counts, and the chip goes when the quantity is 0 (mockup `10`: "short, CS investigating"; tech lead decision, 19 Nov 2026).
- Given a shortage, then it can never be accepted as a physical return, never enters the receiving store's Available or Pending return, and is shown under "Short · under investigation" in the Stock impact card.

Roles and errors
- Given a person who is not a Central Store user or the owner, then 403; another store's item that the person is not mapped to, 404; another clinic's item, 404.
- Given two people act on the same item, then the second gets 409 `changed`.
- Given a slow or failed acceptance, then nothing is posted and the choices are kept.
- Given the History card, then each step is shown with user, store, time, quantity, reason and where the units went (BR-26).

## Notes for design
- Uses the state Pending return (BRD section 8) and the In transit state for shortages; no fifth state (OQ-79-18).
- The same acceptance step serves the returns of 79-14 and the transfer returns of 79-15 (acceptance by the sending store).

---

### SAH-79-14 · As a store user or HOD, I want to return surplus stock to the Central Store and report damaged or expired stock, so that unusable stock is blocked at once and the right person decides what happens to it
Priority: Must
Screen: P&I mockup `11-store-stock-and-ward-consumption.html`, tab "Return / damaged · <n> waiting for action" (cards "Return to Central Store" with Item · batch, Quantity, Reason, "Send to Central Store"; "Report damaged / expired" with Item · batch, Quantity, Reason, After approval, "Submit for HOD approval"; list "Waiting for action"). "Add photo" is not built (OQ-79-25). Package WP-G. BRD I6, AC-I5.

## Acceptance criteria
Return to the Central Store
- Given a person mapped to a store (not the Central Store) with the capability to return (OQ-79-22: recommended every store user, HOD, delegate, pharmacist) opens the tab, then the card "Return to Central Store" says "Excess, near-expiry or no longer needed. No indent needed." and has Item · batch (an Available batch of the working store), Quantity (whole number from 1 up to the Available quantity), Reason (chips of the Store return list: Excess stock, Near expiry, Not needed (order stopped)) and the button "Send to Central Store".
- Given the person sends, then the return takes its number from the return series (OQ-79-14) and the units move from the store's Available to its Pending return through ledger lines (reference the return number); the list shows "Waiting for Central Store"; nothing enters the Central Store until it accepts (79-13, BR-29).
- Given a quantity above the Available quantity, then 400 and nothing moves; a reason is required.
- Given a Schedule X item, then a pharmacist and a second signature are needed (BR-32); a movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger, for the return and for the damage report alike (C-10, DEC-080 L14; 79-5 for the signer list; OQ-79-38 asks whether the damage report is on the architect's person-driven list).
- Given the store has an open stock audit (79-17) that includes the product and the person can count but cannot review the count, then the Available quantity beside Item · batch shows "Counting in progress" (NEW), no quantity is sent, and the 400 for a quantity above Available does not name the quantity (C-9, DEC-080 L12; OQ-79-38).

Report damaged or expired
- Given the card "Report damaged / expired", then it says "Moved to quarantine at once so it cannot be used. HOD approves the next step." and has Item · batch (an Available or Quarantine batch of the working store), Quantity, Reason (chips of the Damage list: Damaged / leaking, Expired, Cold-chain break), After approval ("Write off at store" or "Send to Central Store"; at the Central Store itself only "Write off at store") and "Submit for HOD approval".
- Given the person submits, then the damage report takes its number (OQ-79-14) and in one step the quantity moves from Available to Quarantine of the same store through ledger lines, so it cannot be issued or sold; the status is "Waiting for HOD" (or applies at once for a Write-off rule of level 0).
- Given the HOD (or current delegate, or the owner per rule) approves, then for "Write off at store" the quarantined units leave stock through ledger lines "Write-off" with the reason and the approver; for "Send to Central Store" the units move from Quarantine to Pending return and wait for the Central Store (79-13), which puts them into quarantine or, if appropriate, saleable stock (BR-8).
- Given the HOD rejects, then a reason chip is required and the HOD chooses the outcome "Release to Available" (only when the batch has not expired, with the reason recorded) or "Keep in Quarantine" for the owner (OQ-79-19); no stock disappears.
- Given the person who reported it, then they cannot approve it (C-3).
- Given an expired batch found by the system (79-3), then it is already in Quarantine; the person can report it from Quarantine here to write it off (reason "Expired").
- Given Quarantine stock, then it counts in the store's Quarantine column (79-4) and is never picked by any issue.

Waiting for action
- Given the list "Waiting for action", then it shows No., Type (Return to CS, Indent rejection, Damaged), Item · batch, Qty, Reason, Raised by (name and time) and Status for the working store's items, newest first in pages; the note reads "Stock is added to Central Store only after Central Store accepts the return. Every step is recorded with user, store, time and reason."
- Given an item the person raised, then the status and any dispute reason are visible and a reply note can be added.

Roles and errors
- Given a person not mapped to the store, then 404; mapped without the capability, 403 `store-permission-denied`; another clinic's data, 404.
- Given a slow or failed submit, then what was typed is kept and the button is disabled while the request runs; a double tap makes one document.
- Given a phone or tablet, then the tab is usable (BRD section 7).
- Given the History card of each document, then every step shows user, store, time, quantity and reason.

## Notes for design
- The "Consume / return" capability split is OQ-79-22 (a SAH-78-4 touch-up). Until it is decided the recommended split is assumed.
- Write-off follows the approval rule for Write-off (BR-14: the HOD of the reporting store).
- The HOD's rejection outcome is a recommended proposal (OQ-79-19).

---

### SAH-79-15 · As a store user or HOD, I want to move stock from one store to another with approval from the sending store and a receipt like an indent, so that stores can balance stock without the Central Store
Priority: Must
Screen: P&I mockup `12-stock-transfer.html` (header "Stock transfer", button "+ New transfer"; card "TRF-… · <from> → <to>" with From store, To store, Reason, table Item · batch, Expiry, Available at <from>, Transfer qty, Value; step bar Requested, Approved, Dispatched, Received; the note about receiving like an indent; card "Open transfers" with No., From → to, Items, Status, Age). "Branch to branch" and the "Aundh branch" example are Later (OQ-PHI-17). Package WP-G. BRD I7.

## Acceptance criteria
- Given a person with "Raise indent" at either store, when they tap "+ New transfer", then a form asks From store (sending), To store (receiving), both active stores of this unit, different from each other, and Reason (chips: Non-moving here, needed there; Urgent shortage; Store closure, OQ-79-15) (OQ-79-20: the Central Store cannot be the receiving store; use Return to Central Store).
- Given the lines, then the person adds products (adopted or own, active) and a Transfer qty (whole number of 1 or more); the screen shows Available at the sending store and, after the server picks the FEFO batch, the Item · batch, Expiry and Value (quantity times the batch's purchase rate per issue unit, without GST); a quantity above the sending store's Available unexpired stock is refused with 400 and the available quantity (OQ-79-21: the requester sees only this figure for the sending store).
- Given the person submits, then the transfer takes its number from the transfer series (C-4), the step bar shows "Requested", and the rule for Stock transfer decides: level 0 goes straight to "Approved"; otherwise it waits for the sending store's HOD or current delegate (or the owner by rule) in the inbox (79-2; OQ-79-20a); the requester cannot approve their own transfer.
- Given the sending store's HOD approves, then the status is "Approved" and a person at the sending store with the capability to dispatch (store user, HOD, pharmacist; the Central Store user at the Central Store) can dispatch.
- Given the sending store dispatches, then Available at the sending store falls and In transit at the receiving store rises through ledger lines "Stock transfer · dispatch" by FEFO batches, all or nothing, and the status is "Dispatched · in transit" with the age; never negative, never from an expired batch (C-2).
- Given receiving, then it works exactly like an indent (79-12): any person mapped to the receiving store accepts or rejects each line, Accepted plus Rejected equals Dispatched, a reason chip is required for any rejected quantity (the same list), accepted units enter the receiving store's Available at once, rejected units wait as Pending return at the receiving store and go back to the sending store, whose staff accept them into saleable stock or quarantine (as 79-13); short or not received is a shortage that the sending store investigates and never a physical return (BR-25, BR-27).
- Given a Schedule X item, then dispatch and receipt each need a pharmacist and a second signature (BR-32); a movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; 79-5 for the signer list).
- Given the sending store has an open stock audit (79-17) that includes the product and the person can count but cannot review the count, then "Available at <from>" shows "Counting in progress" (NEW), no quantity is sent, and the 400 for a quantity above Available does not name the quantity (C-9, DEC-080 L12; OQ-79-38).
- Given "Open transfers", then the list shows No., From → to, Items, Status (Waiting for approval, Approved, In transit, Received, Cancelled) and Age for the transfers of the stores the person can reach.
- Given a transfer in transit longer than the transfer time (default 24 hours, DECIDED OQ-79-3), when a list is read, then its status shows "Not received · <n> h" and the item appears for both stores' HODs; there is no notification and no job (BR-16, OQ-79-29); the mockup note reads "Transfers not received in 24 hours are escalated to both store HODs." with the time taken from the unit's setting.
- Given a transfer not yet dispatched, then the requester or the sending store may cancel it with a reason chip; it keeps its number as "Cancelled" (OQ-79-19).
- Given a person not mapped to the sending or receiving store, then the transfer answers 404 (the owner reaches all); mapped without capability, 403 `store-permission-denied`; another clinic's transfer, 404.
- Given a transfer from a store to itself, an inactive store, or a store of another clinic, then 400.
- Given two people act at once, then the second gets 409 `changed`.
- Given a slow or failed save, then what was typed is kept and the button is disabled while the request runs.
- Given the History card, then every step shows user, store, time, quantity and reason; the receiving flow on a phone or tablet is usable.

## Notes for design
- The approval rule type is Stock transfer; the approver is the sending store's HOD (OQ-79-20a, mockups 12, 14 and 17); the BRD says "like an indent". Open for the human.
- Transfer lines are batch-level in the mockup only because FEFO produces them; the sender does not choose the batch (OQ-79-20d).

---

### SAH-79-16 · As a nurse or store user at a ward store, I want to record what the ward used, for a patient or for general ward use, so that the ward's stock stays right and patient use is traceable
Priority: Must
Screen: P&I mockup `11-store-stock-and-ward-consumption.html`, tab "Ward consumption · Patient-wise and ward general" (line "Acting as <name> · nurse mapped to <store>"; the segmented buttons "Patient-wise (billable)" and "Ward general (non-billable)"; patient fields; Purpose; table Item, Batch (FEFO, ward stock), Ward available, Qty used; "+ Scan item" and "Save consumption"; card "Today · <store>"). "Average daily use feeds the suggested quantity" is Later. Package WP-G. BRD I6, AC-I6.

## Acceptance criteria
General consumption (both modes)
- Given a person mapped to a store whose "Consumption entry" is Yes and who has the capability "Consume" opens the tab "Ward consumption", then the card "Record consumption" shows "Acting as <name> · <role> mapped to <store>", a choice of type, and (for "Ward general (non-billable)") Purpose (chips: Ward general use, Procedure (non-billable), Cleaning / infection control, OQ-79-15) and "Cost booked to" (the store name, read-only, OQ-79-24c); a store with "Consumption entry" No does not show the tab.
- Given the items table, then each row has Item, Batch (FEFO, store stock) (the batch the server will pick), Ward available and Qty used (whole number of 1 or more); the person adds items by product search or by typing or scanning a barcode into "+ Scan item" (no camera scanning, OQ-79-24a).
- Given the person taps "Save consumption", then in one step the Available stock of the store is reduced by FEFO through ledger lines "Ward consumption" (reference "Ward general"), batch by batch, the purpose is saved, and the entry appears in "Today · <store>" with Time, Item, Qty and Type (chip "General"); a quantity above the Available unexpired stock is 409 `insufficient-stock` and nothing is posted.
- Given the note "Not billed to a patient. Cost is booked to the ward for reports.", then it is shown for general use.
- Given only Available stock is used, then Quarantine, In transit and Pending return stock is never offered or consumed (BR-4); an expired batch is never picked (BR-8).
- Given a Schedule X item, then only a pharmacist can record it with a second signature (BR-32); a nurse gets 403 `schedule-x-pharmacist` for that line (OQ-79-8); a consumption movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; 79-5 for the signer list).
- Given the store has an open stock audit (79-17) that includes the product and the person can count but cannot review the count, then "Ward available" shows "Counting in progress" (NEW), no quantity is sent, and a 409 `insufficient-stock` does not name the available quantity; the entry can still be saved (C-9, DEC-080 L12; OQ-79-38).

Patient-wise consumption (Integrated mode, IPD on)
- Given Integration mode is Integrated and the clinic's IPD module is on and the store is linked to a ward (OQ-79-23, request to SAH-78-3), then the type "Patient-wise (billable)" is offered with "Patient (scan wristband)" and "Ordered by"; in Standalone mode or with IPD off it is hidden and the server answers 409 `integration-off` (BR-3).
- Given the Patient field, then the person types or scans an admission number or picks from the ward's admitted patients (read through the IPD reader port); the screen shows the patient's name, admission number and bed so the nurse can verify, and a patient not admitted to this ward or already discharged is refused with 400 (OQ-79-24a).
- Given "Ordered by", then a select of active doctors and visiting doctors of the unit, with "Other" and a typed name (OQ-79-24b).
- Given the person saves, then the Available stock of the store is reduced by FEFO through ledger lines "Ward consumption · patient" whose reference is the admission number (not the name); the entry shows in "Today" with the chip "Patient" and the status "Charge pending" (OQ-79-23); no charge is posted to the IPD bill and the medication chart is not changed in this release; the screen does not say the patient was charged.
- Given a patient-wise save, then a record-access entry is written for the read of the patient (ADR-028 D9), and no patient name, number or clinical text is written to logs (C-8).

Correction, roles, errors
- Given a saved entry, then it cannot be edited or deleted; a mistaken entry is corrected by a stock adjustment (79-5) or, in a count, by the variance reason "Consumption not entered" (79-17).
- Given a person not mapped to the store, then 404; mapped without "Consume", 403 `store-permission-denied`; another clinic's store or patient, 404.
- Given a slow or failed save, then the entries are kept, nothing is posted, and the button is disabled while the request runs; a double tap posts once.
- Given a phone or tablet, then recording consumption is usable with one hand (BRD section 7).
- Given the list "Today · <store>", then it shows the store's entries of the day (IST) in pages, and the History of each entry shows user, time and quantities.

## Notes for design
- Needs `ipd.api.AdmittedPatients` (T6, additive reader port owned by the ipd module; human approval of the IPD change) and a linked ward on the store (ADR-028 `ipd_ward_id`; SAH-78-3 touch-up).
- Posting the charge and the medication-chart view are touch point T5, SAH-80 (ADR-028 OQ-PHI-10). Entries in "Charge pending" are posted when T5 is approved.
- "Cost booked to" a department is not modelled (OQ-79-24c).

---

### SAH-79-17 · As an HOD, pharmacist or store keeper, I want to count a store blind, review the differences and post approved adjustments, so that the book stock matches the shelf and every difference has a reason and an approver
Priority: Must
Screen: P&I mockup `13-physical-stock-audit.html` (header "<store> · audit AUD-…", KPI tiles Items in this count, Counted, With variance, Variance value; card "Count sheet · <name>" with the "Blind count" line, Item · batch, Rack, Counted, buttons "Scan next item" and "Submit count"; card "Variance review · <store> HOD" with Item, System, Counted, Diff, Reason, buttons "Recount" and "Approve & post adjustment"). Not built: the card "Audit plan" and daily cycle counts (Later), and the "Start cycle count" wording of `06` (the button reads "Start stock count"). Package WP-H. BRD I8, AC-I7.

## Acceptance criteria
Start and count
- Given an HOD, delegate, pharmacist, Central Store user or the owner opens "Stock audit" and taps "Start stock count" for a store they can reach, then the scope is every batch with Available stock in that store, or products they choose (OQ-79-26b); the audit takes its number from the audit series (OQ-79-14) and shows the tiles "Items in this count", "Counted", "With variance" and "Variance value"; one open audit per store at a time (409 otherwise).
- Given a person with the right opens the count, then the card "Count sheet · <name>" shows "Blind count · the counter does not see the system quantity, so the count is honest" and a table Item · batch, Rack (the store's rack, SAH-78-5) and Counted (a whole number of 0 or more); the API answers the counter with the lines and never the system quantity or the differences (BR-30). The book quantity is not shown to the counter; a person who can review the count (has the capability to review) sees it in the variance review (BR-30).
- Given a store with an open stock audit, then a person who can count but cannot review the count gets no quantity for the products in the audit from any stock screen, list, report, ledger or error message of that store: the quantity fields are empty and the screen shows "Counting in progress" (NEW); the quantities come back when the audit is Closed; a person who can review the count is not affected (C-9, DEC-080 L12). QA asserts that no quantity reaches such a person through any endpoint.
- Given "Scan next item", then a barcode typed or read by a keyboard-style scanner jumps to the item's row (no camera, OQ-79-24a).
- Given "Submit count", then every line must have a counted value (or the person marks the line "Not counted" with a reason; the line is then left out); the book quantity of each line is fixed at that moment (OQ-79-26c) and the count moves to "Waiting for variance review".

Variance review
- Given the count is submitted, then the card "Variance review · <store> HOD" shows only lines with a difference: Item, System, Counted, Diff (red for negative), Reason (a chip required for each line from the Stock adjustment list, OQ-79-27), and the tile "Variance value" ("− ₹ 412" style: difference times the batch's purchase rate per issue unit, without GST).
- Given the HOD sends a line back with "Recount", then the line returns to the counter with no system quantity, is counted again and re-enters the review.
- Given "Approve & post adjustment", then the rule for Stock adjustment decides (C-3): level 0 posts at once; otherwise it waits for the HOD or the owner above the owner's limit or for a Schedule X item (the inbox row reads "AUD-… · <store> · − ₹ 412 · <rule>", 79-2); the person who submitted the count cannot approve it (C-3).
- Given the adjustment is approved, then each difference posts as ledger lines "Stock audit adjustment" (+ or −) on the batch in Available with the audit number as reference, the reason, the counter, the approver and a before and after quantity; zero-difference lines post nothing; the audit shows "Closed".
- Given a batch whose stock moved after the count was submitted (any ledger line since), then its line shows "Stock moved since the count. Recount." and cannot be posted until recounted (OQ-79-26c).
- Given a decrease larger than the Available quantity at posting time, then the line is refused with 409 `insufficient-stock` and goes to recount.
- Given the reason "Consumption not entered", then the review offers "Post as consumption instead" (a link to Record consumption, 79-16, filled with the product and quantity); if the person uses it, the difference is not posted as a loss (Should; the mockup note).
- Given a Schedule X item in the count, then the person counting and the person posting need a pharmacist and a second signature (BR-32, OQ-79-8); an audit adjustment movement of a Schedule X product by a person without the second signer is refused by the server and by the stock ledger (C-10, DEC-080 L14; 79-5 for the signer list).
- Given a rejection of the whole review, then a reason chip is required and the audit returns to counting; nothing is posted.

Roles and errors
- Given a store user who is mapped but not allowed to start counts, then they can count an open audit of their store but cannot start or review it (403 for those calls); a store they are not mapped to, 404; another clinic's audit, 404.
- Given a slow or failed submit, then the counted values are kept (also if the page reloads, the counts typed so far are saved as the person goes), nothing is posted, and the button is disabled while the request runs.
- Given a phone or tablet, then counting is usable (BRD section 7: stock count works on tablet or phone).
- Given the History card, then every step shows user, store, time, system and counted quantities and reason.

## Notes for design
- Blind count: no system quantity in the counter's API responses, and (DEC-080 L12) none from the stock endpoints of a store with an open audit for a person who can count but not review (a contract point for the architect: the quantity fields are null; additive).
- Amount basis: BR-17 (OQ-79-36). The thresholds in the mockup (₹250, ₹1,000) are not used; the rule decides (OQ-79-26a).
- The "Audit plan" card is Later.

---

### SAH-79-18 · As an owner, HOD, pharmacist or Central Store user, I want stock and movement reports including the stock ledger for a product, so that I can prove where every unit went
Priority: Should
Screen: P&I mockup `15-reports.html` (header "Reports", the report catalogue with groups Stock, Movement, Purchase, and the card "Stock ledger · <product> · <stores> · <date>" with the buttons Excel and PDF and the table Time, Transaction, Ref, Store · bucket, Batch, In, Out, Balance, User). Built from the catalogue: Store-wise stock, Batch and expiry, Below reorder level, Stock ledger (per product), Indent register, Transfer register, Ward consumption, Returns and write-offs. Not built: Stock valuation, Non-moving / dead stock (OQ-79-33) and the group "Sales & compliance" (SAH-80). Package WP-H. BRD I10.

## Acceptance criteria
- Given a person who may read reports (owner for all stores; HOD, pharmacist and Central Store user for their stores; store users see only store stock and below reorder, OQ-79-21) opens "Reports & stock ledger", then the line "Every report can be filtered by store, date and product, and downloaded as a CSV file or printed" and the catalogue show the report names above, grouped Stock, Movement, Purchase (79-19); nothing in "Sales & compliance" is shown.
- Given any report, then filters are Store (the stores the person can reach), Date range (at most 12 months a run, OQ-79-35) and Product; results are paged; an unreachable store answers 404.
- Given "Stock ledger", then the person picks a product and the report shows "Stock ledger · <product> · <store or all reachable stores> · <date or range>" and one line per movement, oldest first: Time, Transaction (for example GRN, Indent dispatch, Indent receipt · accepted, Indent receipt · rejected, Return accepted by CS, Vendor return, Ward consumption · patient, Stock adjustment, Opening stock), Ref (document number; the admission number for patient-wise consumption, never the name), Store · bucket (store and state), Batch, In, Out, Balance (after the line, per store, state and batch) and User.
- Given a movement between two stores or states, then both of its lines are shown (out of the source, in at the destination) as the mockup's dispatch lines are; a reversing or adjustment line shows the line it corrects.
- Given the ledger report, then the sum of In minus Out for a store, state and batch equals the balance shown in the stock screens (79-3, 79-4); a QA reconciliation checks this.
- Given "Store-wise stock" and "Batch and expiry", then they are the views of 79-3 and 79-4 as reports, with filters and CSV; "Below reorder level" lists products at or below the store's reorder level with Available and level.
- Given "Indent register", then each indent shows number, requesting store, issuing store, priority, raised by and time, status, approved, dispatched and received quantities, rejected and short quantities, and open back-orders; "Transfer register" shows the same for transfers.
- Given "Ward consumption", then it lists consumption by store, date and product with type (General or Patient), purpose, admission number for patient-wise, user and "Charge pending" where it applies; patient names are never in a report or an export (privacy).
- Given "Returns and write-offs", then it lists returns to the Central Store, damage reports, write-offs, shortage settlements and disputed items with reasons, quantities, values, approver and status.
- Given "Download CSV", then the filtered report is downloaded as UTF-8 CSV with the same columns and Indian-format money without grouping separators inside numbers; a result above the row cap (OQ-79-35) says "Narrow the date range." and nothing partial is downloaded; "Print" opens the browser print view, from which the browser can save a PDF (OQ-79-4).
- Given a report with no rows, then "No records for this filter."
- Given a store with an open stock audit (79-17) and a person who can count but cannot review the count, then in the stock reports and in the stock ledger of 79-18 the quantity columns (Qty, In, Out, Balance, Available, level comparison) for the products in that audit are empty with "Counting in progress" (NEW), also in the CSV and the print view, and the quantity totals leave those products out; the other columns (time, transaction, reference, user) are shown (C-9, DEC-080 L12; OQ-79-38).
- Given a person without the right, then 403; a store not reachable, 404; another clinic's data, never shown.
- Given a slow or failed load, then "Could not load. Try again."; a large ledger report responds from a paged query, never from loading all lines.
- Given the export and the print view, then no patient name, mobile number or ABHA number is ever included, and the access is audited as a report view (C-8).

## Notes for design
- Reads the ledger partitions by date range; the architect sets limits and indexes. Reconciliation of balances and the ledger is a QA test and a report; there is no scheduled job (ADR-028 D6).
- The report catalogue grows with SAH-80 (sales, registers).

---

### SAH-79-19 · As an owner or Central Store user, I want purchase reports (orders against receipts, the GST purchase register and open debit notes), so that I can check what was bought, what is owed and what credit is pending
Priority: Should
Screen: P&I mockup `15-reports.html`, catalogue group "Purchase" (PO vs GRN, Purchase register (GST), Debit notes pending). Not built: Vendor performance and Rate history (OQ-79-33). Package WP-H. BRD I10 ("purchase and GST registers").

## Acceptance criteria
- Given an owner or a Central Store user opens "Reports", then the Purchase group lists "PO vs GRN", "Purchase register (GST)" and "Debit notes pending", with the same filters (date range, vendor, product) and CSV and print as 79-18; other people see no Purchase group (403 on the APIs).
- Given "PO vs GRN", then each purchase order line shows the PO number, vendor, product, ordered quantity, received quantity, short quantity, status (including "Received · <n> short" and "Overdue") and delivery date, and the report can be filtered by status.
- Given "Purchase register (GST)", then each posted receipt line shows date, receipt number, vendor, vendor GSTIN, vendor invoice number and date, product, HSN, batch, quantity, taxable value, GST % and amount, discount and total, with totals per vendor and per GST % for the range; layout and legal fields are an accountant's confirmation (OQ-79-13, G-14).
- Given the register, then amounts are shown ₹ with Indian grouping and paise on screen and as plain numbers with two decimals in CSV; the total of a receipt equals the invoice total posted for the payable (79-7).
- Given "Debit notes pending", then it lists debit notes not yet at "Credit note received" with number, vendor, amount, current step, age in days, and marks those older than 30 days (replacing the Command Center flag of the mockup, SAH-80); a Cancelled debit note is not listed.
- Given the purchase reports, then a vendor name, GSTIN, invoice number and amount are business data and may be shown and exported; they are never written to application logs (C-8).
- Given no rows, then "No records for this filter."; given a failed load, then "Could not load. Try again."
- Given another clinic's data, then never shown; a store the person cannot reach answers 404.

## Notes for design
- Vendor payable figures are shown only as the receipt's due date and the debit note's effect; no payable report is built (OQ-79-5).
- The GST register layout is not decided (OQ-79-13).

---

## Change log
- 18 Nov 2026 (wording, no scope change): added open item OQ-79-37 to the notes of 79-1 and to the header count (batch numbers that differ only in case at posting; SAH-78-7 import already treats them as one batch, ADR-028 R4). Not decided; no acceptance criterion changed.
- 18 Nov 2026 (DEC-075, DEC-078; wording and criteria, no scope change beyond what is listed; decided automatically, the human confirms):
  - 79-12 (DEC-075 Q-1): "received once, in full" replaced by "each line is received once"; a person who is not a pharmacist can receive the lines that are not Schedule X; the Schedule X lines stay in transit until a pharmacist receives them with a second signature; the dispatch is "Partly received" until then; Schedule X units are never Available while they wait. Status text and the repeat-confirm criterion follow.
  - 79-1: OQ-79-37 is DECIDED (DEC-075 item 3, DEC-072): batch numbers that differ only in case are one batch, go-live check for the pharmacist (G-15). New criterion for A-8 (ADR-029, DEC-075 item 4): posting opening stock that contains Schedule X items needs no second signature; the poster is a person who may post and the file was approved by another owner; go-live check G-15. New criterion for the batch MRP (BR-10): the MRP of an existing batch is fixed; a wrong MRP is corrected by emptying the batch by adjustment and receiving a new one; go-live check for the pharmacist (OQ-D-2, new G-22).
  - 79-8 (DEC-078 item 5): the two vendor credit percentages (damaged on receipt, after expiry) are added to the vendor with the WP-E migration, not in SAH-78-8; which level a line starts from and the range are not stated, so the existing "starts from the vendor's credit %" criterion stands.
  - Not changed, flagged: OQ-79-2 ("one receipt per dispatch") now reads as one receipt per line for a dispatch with Schedule X lines; the tech lead confirms.
  - Requirement: `docs/requirements/SAH-79.md` (BR-10, BR-25, BR-32, OQ-79-11, OQ-79-37, request 3, G-15, G-22).
- 19 Nov 2026 (DEC-061 programme, touch-up after package C and the hardening round; wording and criteria, no scope change):
  - 79-8: the two vendor credit levels were done in SAH-78 migration 1290 (contract 0.2.1), not with the WP-E migration; the 18 Nov entry above that says "added with the WP-E migration" is history. A return line now starts from the damaged-on-receipt level when its reason is damaged on receipt, from the after-expiry level when the batch is expired, and otherwise from the standard credit; an empty level means the standard credit (tech lead decision, 18 Nov 2026). Replaces "the existing criterion stands". Two points not decided anywhere are flagged in the 79-8 criterion.
  - OQ-79-2 is reworded in the requirement to "each line of a dispatch is received once" (DEC-075 Q-1), as 79-12 already says; the flag of 18 Nov above is closed. Story text unchanged.
- 19 Nov 2026 (DEC-061 programme; DEC-078 amended, DEC-079, DEC-080; criteria added or reworded, no scope change; decided by the tech lead, the human confirms):
  - 79-8 (DEC-078 item 5, amended): a return line that is both damaged on receipt and expired starts from the lower of the two credit levels (an empty level counts as the standard credit); the chip "Quarantine · damaged" means damaged on receipt; go-live G-18 now also names the accountant. The "left open" sentence is removed. The vendor fields sit after the Return policy fields on the vendor sheet (SAH-78-8).
  - New common rules C-9 (blind count on the stock reads, DEC-080 L12) and C-10 (a Schedule X movement by a person needs the second signer, also in the ledger, DEC-080 L14). C-9 is repeated as one criterion in 79-3, 79-4, 79-5, 79-6, 79-9, 79-11, 79-14, 79-15, 79-16, 79-17 and 79-18. The wording "Counting in progress" is NEW (the mockup `13` has no text for it). C-10 is repeated in 79-5, 79-7, 79-8, 79-11, 79-12, 79-13, 79-14, 79-15, 79-16 and 79-17.
  - 79-17: the book quantity is not shown to the counter; a person who can review the count sees it (BR-30).
  - 79-5: the Schedule X second signer is chosen from the other active pharmacists and owners of the unit; the list comes from the server and never contains the caller; empty-list message NEW.
  - 79-1: posting needs the owner's approval of the import first and the poster is a person who may post; no second signature at posting (DEC-075 A-8, G-15); the ledger rule of C-10 does not apply to "Opening stock" lines.
  - New open question OQ-79-38 in the requirement (what the blind count hides besides quantities; the damage report and the person-driven list; a reviewer who also counts).
  - Requirement: `docs/requirements/SAH-79.md` (BR-30, BR-32, OQ-79-8, OQ-79-11, OQ-79-30, OQ-79-38, G-15, G-18).
- 19 Nov 2026 (DEC-061 programme; contract 0.3.3 and the java-developer's WP-E decisions, DEC-073; criteria reworded or added, no scope change; decided by the tech lead, the human confirms):
  - 79-12: the indent stays "Partly dispatched" while a back-order is open and becomes "Received" only when every approved quantity is dispatched and received (or closed); "Received · <product> back-order open" is a label for that state, not the status. One criterion split in three.
  - 79-6 and 79-7: new criterion for the vendor list of a Central Store user who cannot open Setup (only the vendors that supply the store; name, status and "Blocked · licence expired"; no licence number, GSTIN, contact or credit terms).
  - 79-7: the screen sends its own lines (batch, expiry and MRP) and starts them from the order's remaining quantities; a receipt waiting for the rate-rise approval is "Waiting for approval" and a Central Store user posts it after approval; a rate difference claimed on a receipt is prepared as a short-supply type line of the debit note (NEW; go-live check G-14 for the accountant).
  - 79-8: a debit note for short supply only can be recorded as credited from "Created" or "Picked"; for notes with goods the credit note is recorded after the hand-over; cancel releases the held units (to Quarantine if the batch has expired meanwhile).
  - 79-5 and 79-12: the second-signer list wording follows contract 0.3.3 (the clinic's other active pharmacists and owners, no mapping to the store needed, name and role only, only a mapped pharmacist can read it).
  - Requirement: `docs/requirements/SAH-79.md` (G-14).
- 19 Nov 2026 (DEC-061 programme; wording and criteria from the WP-F build, DEC-073 decisions of 19 Nov 2026 and the real mockup files `08` and `10`; no scope change; decided by the tech lead, the human confirms):
  - 79-7 and C-6: the receipt button reads "Post GRN & update stock" as the mockup `08` does (it replaces "Post receipt" and "Post receipt and update stock"); the rule "Receive stock, not GRN alone" keeps its wording everywhere else. The same exception is in BR-36 of the requirement.
  - 79-12: each line is received once and a re-send for a partly received dispatch carries only the lines not yet received (new criterion); a Schedule X line is received only by a pharmacist with a second signer, a non-pharmacist skips it, the dispatch shows "Partly received" and the indent has no such status: it stays "Partly dispatched" with the chip "· Schedule X lines waiting for a pharmacist" (NEW chip; replaces "the dispatch and its indent show Partly received"); the chips "· <n> rejected, waiting for CS" and "· <n> short, CS investigating" win over the back-order label when both apply.
  - 79-13: <n> in "rejected, waiting for CS" is the quantity the Central Store has not yet accepted (disputed items still count); <n> in "short, CS investigating" is the shortage quantity not yet settled (a claim open, or a write-off waiting for approval, still counts). Two new criteria.
  - 79-9: "To (issuing store)" is shown from the start from the store the indents of this store go to (the working-store context carries it); no "Shown after you save the draft" note.
  - 79-10: an approval with every approved quantity 0 is refused with "Reject the indent with a reason instead." (NEW text); replaces "all zero is treated as a rejection and asks for a Remark".
  - 79-6, 79-7 and 79-8: the credit days of a purchase order are filled from the vendor when the field is empty (hint "From the vendor" for a person who cannot see credit terms); blocked vendors stay on the vendor list with the mark and are not offered on the purchase order, direct receipt and vendor return forms. New open question OQ-79-39 (vendor return to a blocked vendor; the mockup is silent). The header count now reads OQ-79-1 to OQ-79-39.
  - Go-live items: no new G-n; G-15 now also names the Schedule X wait chip and "pharmacist with a second signer" at receipt.
  - Requirement: `docs/requirements/SAH-79.md` (BR-18, BR-23, BR-25, BR-36, OQ-79-39, G-15).
