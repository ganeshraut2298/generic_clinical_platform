# SAH-78 · Stories: Pharmacy & Inventory, Phase 1: Masters (configuration)
Module: pharmacy
Mockup screen: Pharmacy, Lab, Tele (sidebar, when enabled) → Pharmacy & Inventory → Inventory setup (P&I mockup file `16-setup-stores-staff-products-vendors.html`)
Also uses: P&I mockup files `05-compliance-roles-and-audit.html`, `17-end-to-end-flow.html`, `04-sales-return.html`, `11-store-stock-and-ward-consumption.html`, `13-physical-stock-audit.html` (reason lists only). Screen-map rows for the P&I mockup are pending from the human (OQ-78-1); the line above uses the existing parent row so that `validate docs` can find it.
Requirement: docs/requirements/SAH-78.md (business rules BR-1 to BR-31, open questions OQ-78-1 to OQ-78-38).
Source: DEC-061 (human, 17 Nov 2026, Q1 A, Q2 A, Q3 A, Q4 A); BRD sections 1, 2, 3, 6, 7, 8, 10 (`docs/handoffs/PHI-input/brd.txt`).

Reading guide.
- Slicing: ten sub-stories (more than the usual six) because Masters is the first phase of a new module with three developer lanes. Each fits one sprint; 78-10 may split if the architect sees fit.
- Texts marked NEW, and the four pieces that have no mockup screen (stories 78-2, 78-7, 78-9, 78-10, label "NEW screen"), are proposals for the human's sign-off (OQ-78-2, OQ-78-3). Rule 1: they are not built until the human accepts the proposal or supplies screens. The stories describe behaviour only, not layout.
- Every AC that follows a recommended default of an open question says so.
- The Sahaj shell is not changed (BRD section 7): use the existing sidebar, top bar, theme and components; do not copy the mockup's sidebar, colours, "End-to-end flow", "USP vs LocalWell" or "USP" chips.
- Inventory and Pharmacy tabs, stock, ledger, POS, dispensing, OPD or IPD changes, WhatsApp, OCR and Excel are out of scope (requirement, "Out of scope").

Common rules for every story (each AC below assumes them; they are tested once per endpoint):
- The unit (clinic) comes from the signed-in user, never from the request. Another unit's record answers 404 (row-level security). The global catalogue has no unit and is read-only to every unit.
- Roles are checked on the server on every call from the clinic membership (not only the token). Unless a story says otherwise: owner changes; pharmacist and Central Store user read stores, products and vendors; everyone else gets 403 on the setup APIs. Module Off: 403 `module-off`.
- Store-bound calls check that the caller is mapped to the store in use (403 `outside-store` otherwise), whatever the screen sent.
- A button is disabled while its request runs; nothing is shown as saved until the server has answered; a failed save keeps what was typed and says "Could not save. Check your connection and try again." (NEW). No offline queue.
- Every create, change and switch-off is audited (who, when, before and after) and never hard-deleted. Audit and log text holds no patient name, mobile number or ABHA number.
- Empty states say what to do next in one line. Lists load in pages; a list never shows another unit's rows.
- Plain English, sentence case; money as ₹ 1,25,000; dates as 24 Oct 2026; times as 11:05 AM IST.

Work packages (three developer lanes; each lane is a Java plus Angular pair):

| Package | Sub-stories | Depends on | Main tables / screens it owns (behaviour level) |
|---|---|---|---|
| WP-A Foundation: unit, stores, staff, shell | 78-1, 78-2, 78-3, 78-4 | Nothing. Needs the platform handoffs for the module entitlement (OQ-78-10) and, if accepted, a "Store staff" role (OQ-78-7); both have fall-backs. | Module entry, tab shell, unit settings, stores, store-staff mapping, working store, the P&I permission check used by every other package |
| WP-B Products and opening stock | 78-5, 78-6, 78-7 | WP-A: 78-1 (shell), 78-3 (stores, for local settings per store and for opening stock), 78-4 (who is mapped to a store). Vendors (78-8) only for the "preferred vendor" select: until then that select is empty. | Global catalogue, adopt, own product, duplicate check, units and conversion, local settings, opening-stock import |
| WP-C Vendors, approval rules, documents | 78-8, 78-9, 78-10 | WP-A: 78-1, 78-2 (GSTIN, licence for print templates), 78-3 (store list and types), 78-4 (HOD names in rule warnings). | Vendors, approval rules and presets, numbering, reason lists, print templates |

Overlap rules: WP-A owns the shell routes, the Inventory setup section list, the permission check and the store-list API. WP-B and WP-C add their sections and APIs but do not edit those. One `docs/api/pharmacy.yaml` (architect) and one set of Flyway migrations in the order A, B, C. Stories 78-2, 78-7, 78-9 and 78-10 wait for OQ-78-2.

---

### SAH-78-1 · As an owner, pharmacist or mapped store user, I want one Pharmacy & Inventory entry in the Sahaj menu that opens the right tabs and sections for my role, so that I reach my work without a second menu or a different look
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html` (page header, tabs "Stores & staff", "Products", "Vendors", and the left panel's three tabs Inventory setup, Inventory, Pharmacy). Package WP-A.

## Acceptance criteria
- Given the Pharmacy & Inventory module is on for the clinic and the signed-in person is the owner, or a pharmacist, or is mapped to at least one store, when the Sahaj sidebar loads, then it shows one entry "Pharmacy & Inventory" in the existing sidebar style (no mockup sidebar, colours or logo).
- Given the module is off for the clinic, when any user loads the app, then the entry is hidden (not greyed) and any P&I API call answers 403 `module-off`.
- Given a signed-in person who is not the owner, not a pharmacist and mapped to no store (for example front desk, counsellor, visiting doctor), when they open a P&I address directly, then they see "You do not have access to Pharmacy & Inventory. Ask your owner." and the API answers 403.
- Given the entry opens, then three tabs can exist in the order Inventory setup, Inventory, Pharmacy, and only tabs with at least one released screen are shown (OQ-78-4: in this release only Inventory setup). A tab never shows a screen of another tab.
- Given the Inventory setup tab, when the owner opens it, then it shows the header "Setup" with the line "Unit: <clinic name from the clinic profile>" and the section tabs "Stores & staff", "Products" and "Vendors" (as in mockup 16) followed by the sections of stories 78-2, 78-7, 78-9, 78-10 as they are accepted (OQ-78-2). The mockup's "Organization:" line is not shown (OQ-78-9).
- Given the section tabs, then each tab shows its live subtitle: stores and distinct mapped staff ("7 stores · 24 staff mapped"), the product source ("Global catalogue + own products"), and active vendors ("12 active suppliers"); the numbers are counted by the server for this unit.
- Given a pharmacist or Central Store user, when they open Inventory setup, then they see Stores & staff, Products and Vendors read-only (no "+ Store", "+ Map staff", "+ Vendor", "Adopt", "Edit" or save buttons are shown) with the pill "View only"; the API answers 403 to any change.
- Given any other mapped user (store user, approver), when they open Pharmacy & Inventory, then no Inventory setup tab is shown and the working-store switch of 78-4 is the only setup item (OQ-78-8).
- Given the signed-in person is the owner and the unit has no stores yet, when Stores & staff opens, then it shows the empty state "No stores yet. Create your Central Store to start." with the one button "+ Store" (78-3).
- Given the page is loading, then a loading state shows; given the server is slow or unreachable, then "Could not load. Try again." with a Try again button, and nothing stale is shown as current.
- Given a narrow screen (phone or tablet), when the entry opens, then the tabs and the working-store switch are usable; setup tables may scroll sideways.
- Given the mockup's left-panel entries "End-to-end flow" and "USP vs LocalWell (internal)", then they are not built (requirement, out of scope).

## Notes for design
- BRD section 7: one sidebar entry, three tabs, existing shell. The navigation policy (role to menu) needs a rule "owner, pharmacist, or any store mapping" (platform `NavigationPolicy`; handoff).
- The entitlement comes from `platform.api` (OQ-78-10). Until a platform story adds the module flag, the backend uses a configuration value.
- Section list and routes belong to WP-A; the other work packages add children.

---

### SAH-78-2 · As an owner, I want to keep my unit's pharmacy settings (GSTIN, drug licence, Integration mode) in one place, so that documents carry the right legal details and every screen knows whether OPD and IPD are connected
Priority: Must
Screen: NEW screen, not in mockup (BRD M1 says "Setup → Stores & staff"; mockup 16 draws no settings). Proposal in OQ-78-2: a Unit settings card at the top of Stores & staff. Header line of mockup 16 ("Unit: ...") is the only drawn element. Package WP-A. Blocked until OQ-78-2 is answered.

## Acceptance criteria
- Given the owner opens Unit settings, then it shows: Unit name (from the clinic profile, read-only here), GSTIN, Drug licence number, Drug licence valid till, Integration mode (Integrated or Standalone), FEFO (on, locked) and Negative stock blocked (on, locked) (OQ-78-11).
- Given a first visit, then Integration mode is Integrated (OQ-78-14) and the other fields are empty.
- Given the owner enters a GSTIN that is not 15 characters of the GSTIN pattern, when saved, then the answer is 400 and the field says "Enter a 15-character GSTIN" (NEW text; format only, OQ-78-23); an empty GSTIN is allowed and shown as "Not set".
- Given a licence number longer than 40 characters, or a licence date that is not a date, when saved, then 400 with a field message; a licence date in the past is allowed and shown "expired" (the owner may be correcting records).
- Given the owner changes Integration mode, when they save, then a confirmation says what changes ("Standalone hides OPD and IPD links. Existing records are kept." and the opposite for Integrated), and nothing changes until they confirm; then the new mode is saved, audited with before and after, and every P&I screen read after that sees the new mode.
- Given Integration mode is Standalone, then any P&I screen that exists in this release shows no OPD or IPD queue or patient-bill link (hidden, not greyed); given Integrated and the clinic's IPD module is Off, then IPD parts stay hidden.
- Given any non-owner, when they call the change API, then 403; a pharmacist or Central Store user may read the unit settings (GSTIN and licence) because documents need them; others get 403.
- Given another unit, then its settings are never visible (404 on its id; the API has no id and always reads the caller's unit).
- Given two owners save at the same moment, then the second gets 409 `settings-changed` with the current values and nothing is overwritten silently.
- Given a save, then the audit event holds the changed fields with before and after; GSTIN and licence number are business data and may appear in audit.

## Notes for design
- Integration mode is a flag read through one P&I adapter switch (BRD section 6); no screen code reads OPD or IPD directly. In Phase 1 nothing is hidden yet except what the shell and these screens would show, so the setting must be readable by the front end from one place.
- The unit name is not duplicated from `platform.clinic_profile`; read it through `platform.api`.
- Decision needed: FEFO and negative stock are shown but locked (OQ-78-11).

---

### SAH-78-3 · As an owner, I want to define the stores in my unit (type, where each issues from, who approves, whether it sells or records consumption), so that every later purchase, indent and sale has a place to happen
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Stores & staff": card "Stores in this unit" (tree, "+ Store") and the detail card (fields Store type, Issues from, Approver (HOD), Delegate approver, Sale to patients, Consumption entry). Package WP-A. BRD M2, AC-M1.

## Acceptance criteria
- Given the owner opens Stores & staff, then the card "Stores in this unit" shows the Central Store at the top and every other store below it, each with name, a type chip (Central, Sale for a Pharmacy, Sub-store, "Ward · consumption" for a Ward store), the line "Issues from <store>" and the number of mapped staff ("· 5 staff"); the Central Store line reads "Buys from vendors · issues to all stores".
- Given a store is selected (marked), then the detail card shows its name as the heading and the fields in this order: Store type, Issues from, Approver (HOD), Delegate approver, Sale to patients (Yes or No), Consumption entry (Yes or No), then the staff section of 78-4.
- Given the owner taps "+ Store", then a form asks Name and Code first (NEW, OQ-78-3), then the fields above; Store type offers Pharmacy, Sub-store and Ward store (and Central Store only while the unit has none); Issues from offers the Central Store only (OQ-78-12).
- Given the unit has no Central Store, when the owner saves any other store, then the answer is 409 and the message says "Create the Central Store first."; given the unit already has a Central Store, when a second store of type Central Store is tried, then 409 "This unit already has a Central Store."
- Given a new store with valid fields, when saved, then it appears in the tree under the Central Store; Sale to patients defaults to Yes for a Pharmacy and No for others, and Consumption entry defaults to Yes for a Ward store and No for others (NEW defaults, for sign-off); all can be changed.
- Given Name or Code is empty, or a Name or Code already used by a store of this unit (ignoring capital letters), when saved, then 400 for empty ("Enter the store name") and 409 for a repeat ("That store name is already used"), and nothing is saved.
- Given Name longer than 60 characters, Code longer than 10 characters or with characters other than letters, digits and hyphen, then 400 with a field message.
- Given the owner chooses an Approver (HOD) or Delegate approver, then the select lists only active team members allowed by OQ-78-6 (owner, doctor, nurse, pharmacist); choosing someone who is not yet mapped to the store maps them with the role Approver or Delegate (78-4).
- Given a Delegate approver, then the owner must give the end date (and start date, OQ-78-15); an end date before the start date, or a delegate who is the same person as the HOD, is refused with 400 and a field message; after the end date the delegate no longer approves and the store row says "Delegate ended 30 Nov" (NEW text).
- Given the selected store has no active HOD (none chosen, or the person's access was switched off), then the detail shows the pill "No approver set" and a note that approval rules using an approver need one (78-9).
- Given a store's type, when the store already has an approved opening-stock import (78-7), then type, Issues from and code are locked and a note says why; name, HOD, delegate, Sale and Consumption can still be changed.
- Given the owner switches a store off (Switch off, NEW, with a confirmation), then it disappears from the choices for new work, keeps its history, and its mappings stop counting; given it has approved opening stock, the answer is 409 "This store has stock. Clear it in Inventory first." (OQ-78-38). The Central Store cannot be switched off while another store is active.
- Given a pharmacist or Central Store user, then stores are read-only (78-1); given another unit's store id, 404.
- Given a change, then an audit event holds the before and after values (store, field).

## Notes for design
- Hierarchy: a store issues from the Central Store; tree depth is one level in this release.
- Store flags are what later phases read: sale allowed, consumption allowed, type.
- Name and code are not on the mockup detail card (OQ-78-3).
- Several Central Stores per unit is not built (OQ-78-12).

---

### SAH-78-4 · As an owner, I want to map staff to stores with a role in each and let each person pick a working store after sign-in, so that people act only in their own stores
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Stores & staff": section "Staff mapped to this store" (button "+ Map staff", table columns Staff, Store role, Raise indent, Approve, Receive, Consume / return, Other stores, and the note "Every store user can receive, not only the HOD. A user mapped to several stores picks the working store after login."); also the "Working store" select shown in the left panel footer of every P&I screen (file 16 and 05). Package WP-A. BRD M3, AC-M2.

## Acceptance criteria
Mapping
- Given the owner selects a store and taps "+ Map staff", then the form asks Person (active team members of this unit), Store role (Store user, Central Store user (only at the Central Store), Approver, Delegate approver with its dates) and saves the mapping.
- Given a mapped person, then the table row shows name and, below it, the Sahaj role label (for example "Pharmacist", "Nurse") and, for the HOD, "HOD, <store>"; a chip with the store role (Approver, "Delegate · till 30 Nov", Store user, Central Store user); the four access columns Raise indent, Approve, Receive, Consume / return as Yes or No; and "Other stores" listing the other stores the person is mapped to, or "—".
- Given the four access columns, then they are read-only and worked out by the server: Approver and a current Delegate show Yes in all four; a Store user shows Raise indent Yes, Approve No, Receive Yes and Consume / return Yes only when the store's Consumption entry is Yes; a pharmacist shows "Yes · incl. Schedule X" for Receive; a Central Store user's columns follow the Central Store (Raise indent No, Receive Yes) (NEW, OQ-78-6, for sign-off). The owner cannot tick them.
- Given a Delegate whose end date has passed, then the row says "Delegate · ended 30 Nov", Approve shows No, and the person remains mapped as a Store user (NEW).
- Given the person is already mapped to this store, when mapped again, then 409 "That person is already in this store" and nothing changes; mapping the same person to another store is allowed with a different role (AC-M2: one user, many stores).
- Given a store role not allowed for the person's Sahaj role (an Approver or Delegate who is front desk, billing, counsellor or visiting doctor; a Central Store role at a store that is not the Central Store), when saved, then 400 with a field message (OQ-78-6).
- Given the owner removes a mapping (Remove from store, NEW, confirmation), then the person loses that store at once, the row stays in the history, and if they were the HOD or delegate the store shows "No approver set"; the audit shows who removed whom.
- Given a team member is switched off in Team and access (SAH-6), then all their mappings stop counting at once, the row says "Cannot sign in" (NEW), and they cannot choose a working store.
- Given a pharmacist or Central Store user opens the table, then it is read-only; a non-owner calling the mapping API gets 403; another unit's person or store, 404.

Working store
- Given a person mapped to one store, when they sign in and open Pharmacy & Inventory, then that store is their working store and the select shows it.
- Given a person mapped to several stores, when they open Pharmacy & Inventory for the first time after sign-in, then they are asked to pick a working store; the select lists only their stores, and the choice is remembered for their next sign-in.
- Given a working store, when the person switches to another of their stores, then every P&I screen shows that store's data from then on and the choice is saved.
- Given a person mapped to no store (an owner or pharmacist with no mapping), then the select is replaced by "You are not mapped to a store. Ask your owner." and store-bound screens stay empty; the owner can still open setup.
- Given a request that names a store the person is not mapped to (changed address, edited request), then the server answers 403 `outside-store` and the working store does not change.
- Given a mapping is removed or a store is switched off while the person has it as working store, when their next call arrives, then they are asked to pick again; no screen shows data of a store they lost.
- Given a phone or tablet, then the working-store select is reachable and usable (BRD section 7).
- Given the owner sets someone as HOD in 78-3, then the mapping table shows them as Approver without a second step.

## Notes for design
- The working store is a per-request value verified against the mapping, never trusted from the client; where it is stored (session, header, preference) is the architect's call.
- Sahaj has no "store assistant" or "store keeper" role (OQ-78-7); if no role is added, the owner maps people with an existing team role.
- Access columns are derived, not stored per person.
- Phase 2 will use these mappings for "any user mapped to the receiving store can receive" (BRD indent rules).

---

### SAH-78-5 · As an owner, I want to search the shared product catalogue together with my own products, adopt a global product and set my unit's local settings, so that my stock list is correct without retyping drug details
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Products": card "Product master" (legend chips "Global · shared by all units" and "Own · this unit only", the search box "Searches global + own products", table columns Source, Product, Salt · strength · form, UOM (buy → issue), HSN · GST, Flags, In this unit, action button "Adopt" or "Local settings" or "Edit", and the note about read-only global fields). Package WP-B. BRD M4, M5, AC-M3.

## Acceptance criteria
Search and list
- Given the owner, pharmacist or Central Store user opens Products, then the Product master card shows the legend chips, the search box and a table with the columns of the mockup: Source (Global or Own chip), Product (name, and below it the code and the manufacturer, or "made in-house" for an own product), "Salt · strength · form", "UOM (buy → issue)" (for example "Box of 10 → vial"), "HSN · GST" (for example "3004 · 12%"), Flags (schedule chip H, H1 or X; "—" when None), "In this unit" (Adopted, Not adopted, or Active for an own product, Inactive when switched off) and an action button.
- Given the owner types in the search box, then global and own products are searched together, ignoring capital letters, by name, salt, alias, barcode and code; fewer than 2 characters shows the first page unfiltered; results load in pages of 25 and show "No products match. Try the salt name." when none match (NEW).
- Given the search runs, then it answers within 300 ms for the sample catalogue in the performance test (BRD target; the architect confirms the real catalogue size, OQ-78-16).
- Given a pharmacist or Central Store user, then the list is the same but no Adopt, Local settings (edit) or Edit button shows.

Adopt
- Given a global product "Not adopted", when the owner taps "Adopt", then the product becomes "Adopted" for this unit, the button becomes "Local settings", and an audit event is written; adopting again changes nothing and answers success (idempotent).
- Given an adopted global product, then its name, salt, strength, form, manufacturer, HSN, GST %, schedule, units and conversion are read-only for the unit; any attempt to change them through the API is refused with 400 and nothing changes (AC-M3: "global fields stay read-only").
- Given a global product, when it is changed or removed in the global catalogue, then no unit's local settings are lost (adoption is a link plus local fields).
- Given a global product that is no longer in the catalogue, then it stays visible to units that adopted it, marked "Withdrawn", and cannot be adopted by others (NEW).

Local settings
- Given an adopted or own product, when the owner taps "Local settings", then a form shows (order NEW, OQ-78-3): Reorder level per store (one number for each active store, in issue units, whole number 0 or more), Rack per store (up to 30 characters), Preferred vendor (active vendors of this unit, or none), Alias (up to 40 characters), Barcode, Returnable (Yes or No), and "Stop using this product" (a switch).
- Given the owner saves, then the values are saved for this unit only and appear on the next open; a reorder level of -1 or a decimal, a rack longer than 30 characters, or a preferred vendor that is another unit's, is refused with 400 and a field message.
- Given a barcode already used by another active product of this unit, when saved, then 409 "That barcode is already used by <product>" and nothing is saved; the same barcode in another unit is allowed.
- Given the owner switches off a product that has approved opening stock, then 409 "This product has stock. Clear it in Inventory first." (OQ-78-38); a product with no stock is set Inactive, disappears from choices for new work, keeps its history.
- Given a product with no vendor yet in this unit (78-8 not built or no vendors), then Preferred vendor offers only "None".

Roles, tenants, errors
- Given a non-owner calls adopt or local-settings APIs, then 403; another unit's product or setting, 404; the global catalogue answers the same list to every unit (apart from the unit's own adoption marks).
- Given a failed adopt or save, then nothing is shown as saved and the error text is shown with what was typed kept.
- Given two owners edit the same local settings at once, then the second gets 409 `settings-changed` and the current values.
- Given the empty states, then "No products yet" shows when neither global nor own products exist, with the sentence "Adopt a global product or create your own." (NEW).

## Notes for design
- Global catalogue data source is open (OQ-78-16). Recommended: a small clearly marked sample set for development, test and demo only. No real HSN, GST or schedule values are invented by the BA; the sample values are sample.
- The mockup's "Own products can be sent for review" note and button are not built (OQ-78-18).
- No selling rule or MRP field (OQ-78-20).
- The product link to `opd.medicine` and `ipd.drug` is Phase 3 (DEC-061 Q3).
- Search target 300 ms is the BRD's POS figure; applying it to setup search is the BA's proposal.

---

### SAH-78-6 · As an owner, I want to create my own product with its units and conversion and be warned when it already exists in the global catalogue, so that I do not split stock across duplicate products
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Products": card "Create own product" (fields Product name, Salt / generic, Strength, Form, Purchase unit, Issue unit; the warning "Possible duplicate: global product ... Use it instead of creating a new one?" with buttons "Use global product" and "Create anyway"; and the "Edit" button on an own product row). Package WP-B. BRD M4, AC-M3, AC-M4.

## Acceptance criteria
Create and duplicate check
- Given the owner opens "Create own product", then the form shows in this order: Product name, Salt / generic, Strength, Form, Purchase unit, Issue unit, then the fields the BRD needs and the mockup lacks (NEW, OQ-78-3): Issue units per purchase unit, Manufacturer (or "Made in-house"), HSN, GST %, Schedule, Barcode.
- Given Product name, Salt, Strength, Form, Purchase unit, Issue unit, Issue units per purchase unit, HSN, GST % and Schedule are required (Manufacturer unless "Made in-house"), when any is empty or invalid, then the save answers 400 with a field message for each and nothing is saved; Schedule has no default and shows "Choose the schedule" until chosen (BR-16).
- Given Issue units per purchase unit is not a whole number of 1 or more, then 400 "Enter a whole number, 1 or more"; Strength, Name and Salt over 80 characters, HSN not 4 to 8 digits, GST % not between 0 and 100 with at most 2 decimals, then 400 with a field message.
- Given the owner has entered salt, strength and form (and leaves the field), when a global product with the same salt, strength and form exists (OQ-78-17), then the warning "Possible duplicate: global product "<name>" (<code>) has the same salt, strength and form. Use it instead of creating a new one?" shows with the buttons "Use global product" and "Create anyway"; the manufacturers of the matches are listed with them.
- Given the warning, when the owner taps "Use global product", then nothing is created, the global product is adopted for this unit (78-5) and shown as Adopted; given "Create anyway", then the own product is created, an own product shown with an Own chip, code `U-<unit code>-nnnn` and "made in-house" or its manufacturer, and the audit records that it was created despite a possible duplicate of <code>.
- Given no global product matches but an own active product of this unit has the same salt, strength and form, then the same warning names the own product and offers "Use <own product>" and "Create anyway".
- Given the owner saves a new own product with the same name, salt, strength and form as an existing own product and chooses "Create anyway" again, then it is created (the warning is advice, not a block) and both appear in the list.
- Given a barcode already used by another active product of this unit, then 409 as in 78-5.

Units and conversion
- Given the purchase unit, issue unit and conversion, then the list shows "Box of 10 → vial" built from them (unit names from the owner's list or typed, OQ-78-21), and "Kit → kit" when the conversion is 1 and the names match.
- Given an own product with no approved opening stock, when the owner edits Purchase unit, Issue unit or Issue units per purchase unit, then the change is saved and audited; given the product has approved opening stock (or any later stock), then these three fields are read-only with the note "Locked because this product has stock." and an API change answers 409 (BR-15).
- Given any field the owner edits on an own product (name, strength, schedule, HSN, GST %), then it is saved and audited with before and after; a change of schedule shows a confirmation "This changes how the product is sold and recorded. Check the schedule." (NEW).

Roles and tenants
- Given a non-owner calls create or edit, then 403; another unit's own product, 404; two units may use the same product name and barcode.
- Given a slow or failed save, then the form keeps its values, shows the error, and the Create button is disabled while the request runs; a double tap creates one product only (idempotent create).
- Given the owner opens "Edit" on an own product, then the fields shown are the same as at creation; the code and source cannot be changed.

## Notes for design
- Duplicate rule differs between mockup (salt, strength, form) and BRD (adds manufacturer): OQ-78-17, recommended mockup wording.
- Product code format for own products: `U-<unit code>-nnnn`; the unit code is a short code the architect derives (the clinic's code); the mockup shows `U-BAN-0042`.
- The unit lists and HSN/GST/schedule rules are legal and pricing choices of the owner and are not validated beyond format (OQ-78-19).
- "Send for review" to the global catalogue is not built (OQ-78-18).

---

### SAH-78-7 · As a pharmacist or Central Store user, I want to upload my starting stock from a CSV file and as an owner I want to check and approve it, so that the first stock is loaded once, correctly and with a second pair of eyes
Priority: Must
Screen: NEW screen, not in mockup (BRD "Opening stock": "A one-time import (Excel template) loads store, product, batch, expiry, MRP, purchase rate and quantity. It posts as an opening-stock line in the ledger and needs owner approval."; DEC-061: CSV instead of Excel). Proposal in OQ-78-2: a section "Opening stock" in Inventory setup. Package WP-B. Blocked until OQ-78-2, OQ-78-34 (ledger) and OQ-78-35 to OQ-78-37 are answered.

## Acceptance criteria
Template and upload
- Given a Central Store user or pharmacist mapped to a store, or an owner, when they open Opening stock, then they can download the CSV template (header row: store code, product code, batch, expiry date, MRP, purchase rate, quantity; with one example row marked as an example) and upload a filled file for one store.
- Given the file is not CSV (for example an Excel file), is empty, is larger than the row limit (5,000 rows, OQ-78-35), or has a header that does not match the template, then it is refused with a plain message ("Use the Sahaj template. Save the file as CSV.") and nothing is kept.
- Given the owner uploads, then the same checks apply and a Central Store user or pharmacist may only upload for a store they are mapped to (403 `outside-store` otherwise).

Checks (all-or-nothing)
- Given a file, when it is uploaded, then every row is checked and the screen lists every problem with its row number and field, and the file stays "Not accepted" until every problem is fixed and the file is uploaded again; nothing is partly imported.
- Given a row whose store code is not the store chosen for the import, or whose product code is not an adopted or own active product of this unit, or whose batch is empty or over 20 characters, then that row is an error.
- Given a row whose expiry date is not a date, or is in the past, then that row is an error "Expiry date must be in the future" (OQ-78-35).
- Given a row whose MRP or purchase rate is not an amount above 0 with at most 2 decimals, or whose quantity is not a whole number above 0, then that row is an error; rates are per issue unit.
- Given the same store, product and batch twice in one file, then both rows are errors; given the store already has an approved opening-stock import, then the upload is refused ("Opening stock was already approved for this store.", OQ-78-37).
- Given a row whose purchase rate is higher than MRP, then it is a warning (shown at approval), not an error.

Approval
- Given a file without errors, then it becomes "Waiting for approval" with a review showing the store, the number of lines, total quantity, total value at purchase rate (₹ with Indian grouping), the warnings, and every line.
- Given the owner opens a file waiting for approval who did not upload it, when they tap Approve, then the file becomes "Approved", it can no longer be edited or cancelled, and it is posted to the ledger as opening stock (or kept as approved opening stock until the ledger exists, OQ-78-34); the audit records uploader, approver, time and totals.
- Given the owner who uploaded the file tries to approve it, then 403 `self-approval` "Another owner must approve this import." (fixed rule, OQ-78-36); given a clinic with one owner and a file uploaded by store staff, then the owner can approve.
- Given the owner taps Reject, then a reason from a short list (Wrong file, Wrong quantities, Wrong store, Other with a note of at least 4 characters) is required, the file becomes "Rejected" and nothing is posted; the uploader can upload a corrected file.
- Given an Approved file, then it cannot be deleted; a correction is made later in Inventory with stock adjustments (reversing lines).
- Given a Waiting file, when the same uploader uploads a corrected file for the same store, then the old file becomes "Replaced" and cannot be approved.

Roles, tenants and safety
- Given any other role (nurse, doctor, front desk, billing), then 403 on every opening-stock API; a pharmacist or Central Store user mapped to the store may upload and read, but cannot approve; another unit's file or store, 404.
- Given an upload in progress on a slow network, then the page shows progress, the buttons are disabled, and a failed upload says "Could not upload. Try again." with nothing kept.
- Given an approved import, then the products in it are locked for unit and conversion changes (78-6) and cannot be switched off (78-5, OQ-78-38).
- Given the log, then file contents, batch numbers and quantities are not written to application logs (only counts and ids).
- Given stock is posted, then until posting is complete no screen shows it as usable stock (BR-30).

## Notes for design
- No Excel library (DEC-061). CSV is read as UTF-8 text; the template is generated by the server or shipped as a static file.
- Posting to the ledger depends on the ledger existing (OQ-78-34): architect decides whether the ledger is created in this story or in SAH-79.
- Fixed rule "nobody approves their own request" is applied (OQ-78-36).
- Rates per issue unit, one import per store and the row limit are proposals (OQ-78-35, OQ-78-37) and need human confirmation (pricing).

---

### SAH-78-8 · As an owner, I want to keep a list of my vendors with their GSTIN, drug licence, credit days and return terms, and have a vendor with an expired licence blocked automatically, so that I never order from an unlicensed supplier by mistake
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Vendors": card "Vendors / suppliers" (button "+ Vendor", table columns Vendor, GSTIN, Drug licence, Credit, Products, Supplies to, Status chips Active and "Blocked · licence expired"; note "A vendor with an expired drug licence is blocked from new purchase orders automatically"; the "USP" chip is not built). Package WP-C. BRD M6, AC-M5.

## Acceptance criteria
List
- Given the owner, pharmacist or Central Store user opens Vendors, then the table shows one row per vendor with: Vendor (name), GSTIN, Drug licence (number and "· till 31 Mar 2028", or "Not required (consumables)"; an expired licence shows "· expired 31 Oct 2026" in the warning colour), Credit ("30 days"), Products (a count, OQ-78-22), Supplies to (store names) and Status (Active, or "Blocked · licence expired").
- Given the screen is read-only for pharmacist and Central Store user (no "+ Vendor" or edit), and another unit's vendors are never shown (404 by id).
- Given no vendors yet, then "No vendors yet. Add the suppliers you buy from." with "+ Vendor" for the owner.

Add and edit
- Given the owner taps "+ Vendor", then the form asks Name, GSTIN, "Drug licence required" (Yes or No), Drug licence number, Drug licence valid till, Contact (name and phone), Credit days, Return policy (days before expiry that returns are accepted, and credit % given), Supplies to (stores, default the Central Store) in this order (field order NEW, OQ-78-3).
- Given Name empty, or the name already used by another vendor of this unit (ignoring capital letters), then 400 "Enter the vendor name" or 409 "That vendor is already in the list".
- Given "Drug licence required" is Yes and the licence number or date is empty, then 400 with a field message; given No, the licence fields are hidden and the list shows "Not required (consumables)".
- Given a GSTIN that is not 15 characters of the GSTIN pattern, then 400 "Enter a 15-character GSTIN" (format only; empty is allowed for a vendor without GST, shown "Not set"); licence number over 40 characters, credit days not a whole number between 0 and 365, return days not a whole number between 0 and 365, credit % not between 0 and 100 with at most 2 decimals, or a contact phone that is not a 10-digit number, then 400 with a field message (OQ-78-23).
- Given the owner saves a valid vendor, then it appears in the list with Status Active and an audit event; editing is allowed for every field and audited with before and after.
- Given "Supplies to", then only active stores of this unit can be chosen; a store that is switched off later is dropped from the list for new work.

Licence blocking
- Given a vendor whose licence valid-till date is before today (Asia/Kolkata), then the Status shows "Blocked · licence expired", and the vendor API marks it `blocked` so that purchase-order screens in SAH-79 can refuse a new PO; on the valid-till date itself it is still Active and from the next day it is Blocked (OQ-78-23).
- Given the owner edits the licence valid-till date to a future date, then the vendor is Active again at once and the audit records the change.
- Given a vendor with "Drug licence required" No, then it is never blocked for licence.
- Given a Blocked vendor, then it stays in the list, can be edited, and existing records of it are unchanged.
- Given the owner switches a vendor off (Switch off, NEW, with confirmation), then its Status is Inactive, it is not offered for new work or as a preferred vendor, and it is not deleted.
- Given a product has a preferred vendor that becomes Blocked or Inactive, then the product's Local settings show "Preferred vendor needs review" (NEW).

Roles and tenants
- Given a non-owner calls a vendor change API, then 403; given another unit's vendor id, 404; two units may have the same vendor name.
- Given a slow or failed save, then the form keeps what was typed and shows the error; a double tap creates one vendor.

## Notes for design
- Drug licence, GSTIN and contact are business data: never logged. They may be in audit.
- "Products" count and "Supplies to" are the BA's reading of the mockup (OQ-78-22).
- The blocked flag is computed from the date on read, so no daily job is needed; the PO story (SAH-79) reads it.
- The "return policy" is stored here and used by the vendor-return story in Phase 2.

---

### SAH-78-9 · As an owner, I want to set who approves what, up to which amount, and pick a ready preset, so that approval levels and limits follow my hospital's policy without any code change
Priority: Must
Screen: NEW screen, not in mockup (BRD M7: "New screen, same Setup tab"; BRD section 2). Context drawn in `17-end-to-end-flow.html` (column "Approval" per transaction), `05-compliance-roles-and-audit.html` (table "Role-based permissions & approvals": discount above 10%, stock adjustment up to ₹1,000) and `04-sales-return.html` (returns above ₹1,000). Proposal in OQ-78-2: section "Approval rules" in Inventory setup. Package WP-C. Blocked until OQ-78-2, OQ-78-25 to OQ-78-30 are answered.

## Acceptance criteria
Rules per transaction type
- Given the owner opens Approval rules, then one row per transaction type shows (list per OQ-78-25: Purchase order, Receive stock (rate rise), Vendor return, Indent, Stock transfer, Write-off, Stock adjustment, Discount at sale, Sales return): the number of levels (0, 1 or 2), the role of each level (store approver or owner), the condition (always, above an amount, Schedule X item, high-value item, urgent priority, store type) and the ₹ limit where one applies.
- Given a level count of 0, then the transaction needs no approval; 1, then one approval by the Level 1 role; 2, then Level 1 and then Level 2; a rule with Level 2 filled but Level 1 empty is refused with 400 "Fill level 1 first".
- Given a limit, then amounts are ₹ with Indian grouping (₹ 1,25,000) and a discount limit is a percentage (OQ-78-27); a limit that is negative, over 9,99,99,999 or not a number is refused with 400; an empty limit means "no amount condition".
- Given a condition "above ₹ limit" with no limit set, when saved, then 400 "Enter the limit".
- Given escalation, then each row has "Escalate after N hours" (whole number 1 to 168 or empty) and, for Indent, the urgent time (30 minutes) as a separate value (defaults per OQ-78-29: indent 2 hours, urgent 30 minutes, transfer 24 hours); a note says "Escalation starts working when the approvals inbox is released" (NEW).
- Given the owner saves, then the rule is stored, audited with before and after, and applies to transactions created after the save only; a transaction already waiting keeps the rule it started with.
- Given the rule's role is "store approver", then the approver is the HOD (or active delegate) of the transaction's store; given a store with no HOD while a rule names the store approver, then the page shows "<store> has no approver" next to the row (NEW) and the save is still allowed.
- Given the delegate, then dates are set on the store (78-3); this page shows the stores that currently have a delegate and their end dates, read-only.

Presets
- Given the owner opens the preset selector, then Lean, Standard and Strict are offered with one-line descriptions (OQ-78-28): Lean (no HOD step), Standard (one level, HOD), Strict (HOD, then owner above a limit).
- Given the owner chooses a preset, then a confirmation says "This replaces all rows below. You can change them afterwards."; on confirm every row is set to the preset values; a preset that needs a ₹ limit (for example the owner step above a limit) asks the owner to enter it before it can be applied, and the owner may choose "no amount condition"; Sahaj supplies no default amounts (OQ-78-26).
- Given the rules are changed by hand after a preset, then the selector shows "Custom".
- Given a new unit, then the rules start as the Standard preset with empty limits (NEW default, OQ-78-28), and a banner says to review them before use.

Fixed rules
- Given any state of the page, then three fixed rules are listed and cannot be edited: "Nobody approves their own request.", "Schedule X needs a pharmacist and a second signature.", "The stock ledger cannot be edited; corrections are reversing lines." (BR-24). No setting, preset or API call switches them off (an attempt to send a field for them answers 400).
- Given the "Schedule X item" condition, then it can add a level but cannot remove the pharmacist-and-second-signature rule.

Roles, tenants, errors
- Given a non-owner, then the page is read-only for pharmacist and Central Store user (OQ-78-8) and 403 on change for everyone else; another unit's rules, 404.
- Given two owners save at once, then the second gets 409 `rules-changed` with the current rules.
- Given a failed save, then what was typed is kept and the error is shown; a rule is never half-saved (one save for the page's changed rows).

## Notes for design
- The page stores configuration only; enforcement comes with each transaction in SAH-79 and SAH-80, and the enforcement reads this data (never code constants).
- The list of transaction types and the role choices are proposals (OQ-78-25). Roles for a level: store approver, owner. Pharmacist-in-charge as a role is open (OQ-78-25).
- The "Strict" and "Standard" meanings are compliance choices for the human (OQ-78-28).
- Escalation values are stored but not acted on until a later timer story (BRD I9 lists escalation timers as Later).

---

### SAH-78-10 · As an owner, I want to set how documents are numbered, which reasons staff can choose, and how printed documents look, so that numbers are orderly, reasons are the same everywhere and printouts carry the right details
Priority: Must
Screen: NEW screen, not in mockup (BRD, "Also in Phase 1: document numbering ..., reason lists ... and print templates ..."). Reason wording is taken from `04-sales-return.html` (sales return reasons), `11-store-stock-and-ward-consumption.html` (return and damage reasons) and `13-physical-stock-audit.html` (adjustment reasons); bill number style from `05-compliance-roles-and-audit.html` ("SB/018422"). Proposal in OQ-78-2: section "Numbering, reasons and print templates" in Inventory setup. Package WP-C. Blocked until OQ-78-2, OQ-78-31 to OQ-78-33 are answered.

## Acceptance criteria
Numbering
- Given the owner opens Numbering, then one series is shown for each of PO, GRN, indent, transfer and bill, each with Prefix, Financial year part (on or off), Sequence length, "Restarts every 1 April" (Yes or No) and a live example (for example "SB/26-27/000001").
- Given the owner edits a series, then Prefix may be 1 to 6 letters, digits, hyphen or slash, Sequence length 4 to 8, and the full example must be 16 characters or fewer (OQ-78-31); anything else is refused with 400 and a field message.
- Given a series is saved, then the change applies to numbers created after it; numbers already issued are never changed, reused or skipped by a format change; the audit holds before and after.
- Given the first number of a series, then it is issued only when a document is saved in Phase 2 or 3; this story issues no document numbers, but stores the series and the next number, and exposes a way to take the next number safely (unique even when two users save at the same moment).
- Given two series with the same prefix and no financial-year part for different document types, then 409 "Two document types cannot use the same prefix" (NEW).
- Given a non-owner, then the series are read-only for pharmacist and Central Store user and 403 on change; another unit's series, 404.

Reason lists
- Given the owner opens Reasons, then four lists show as chips (OQ-78-32): Store return (Excess stock, Near expiry, Not needed (order stopped)), Sales return (Doctor changed medicine, Excess bought, Wrong item given, Adverse reaction), Damage (Damaged / leaking, Expired, Cold-chain break) and Stock adjustment (Consumption not entered, Breakage, Unknown); the seeded reasons are in every new unit.
- Given the owner adds a reason (1 to 40 characters, not already in that list ignoring capital letters), then it appears as a chip at the end of that list; a blank or repeated reason is refused with 400 or 409.
- Given the owner switches a reason off, then it is not offered for new work but stays on old records; it can be switched on again; no reason is deleted and seeded reasons cannot be renamed.
- Given the Damage list, then at least one active reason must remain; switching off the last one is refused with 409.
- Given another unit's lists, then they are independent; a non-owner gets 403 on change.

Print templates
- Given the owner opens Print templates, then three templates are shown: Invoice, Issue slip and Debit note, each with Header lines (the unit name from the clinic profile, the address, the phone), a Footer note (up to 200 characters), Paper size (A4 or A5) and a preview.
- Given the Invoice template, then the fields GSTIN, drug licence number, HSN, batch, expiry, MRP and GST amounts are shown on the preview as fixed and cannot be hidden (OQ-78-33); GSTIN and licence number are taken from Unit settings (78-2); if either is empty the preview says "Set GSTIN in Unit settings" and the owner is told that invoices cannot be printed until it is set (NEW).
- Given the owner taps Print preview, then the browser print view opens with the template filled with sample values marked "Sample"; no file is generated by the server (DEC-061 default).
- Given a header line or footer longer than its limit, or a paper size other than A4 and A5, then 400 with a field message.
- Given a saved template change, then it applies to documents printed afterwards; documents already printed are not changed; the audit holds before and after.
- Given the Debit note and Issue slip templates, then they carry the unit name, the document number (when issued), the date as "24 Oct 2026" and the footer; the mapped legal fields for each are set by the human (OQ-78-33).

## Notes for design
- Bill numbering and invoice fields are a legal matter (GST); the human or accountant confirms before the bill series or invoice template is used for real billing (OQ-78-31, OQ-78-33).
- The next-number take must be race-safe and gapless in the sense the human specifies (OQ-78-31); the architect designs it.
- Reason lists are used later in Phase 2 and 3 as chips; the indent rejection reasons and consumption purposes are not here (OQ-78-32).
- Print uses the browser print view; no PDF library, WhatsApp or e-mail sending.
