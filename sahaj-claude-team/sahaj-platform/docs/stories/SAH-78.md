# SAH-78 · Stories: Pharmacy & Inventory, Phase 1: Masters (configuration)
Module: pharmacy
Mockup screen: Pharmacy, Lab, Tele (sidebar, when enabled) → Pharmacy & Inventory → Inventory setup (P&I mockup file `16-setup-stores-staff-products-vendors.html`)
Also uses: P&I mockup files `05-compliance-roles-and-audit.html`, `17-end-to-end-flow.html`, `04-sales-return.html`, `11-store-stock-and-ward-consumption.html`, `13-physical-stock-audit.html` (reason lists only). Screen-map rows for the P&I mockup are pending from the human (OQ-78-1, still open); the line above uses the existing parent row so that `validate docs` can find it.
Requirement: docs/requirements/SAH-78.md (business rules BR-1 to BR-33, open questions OQ-78-1 to OQ-78-42, of which OQ-78-42 is DECIDED (DEC-076), go-live items G-1 to G-12).
Source: DEC-061 (human, 17 Nov 2026, Q1 A, Q2 A, Q3 A, Q4 A and Answer 2); DEC-062 (Q1 A to Q4 A); DEC-063 (Q1 A, Q2 A); ADR-028 (accepted, D4 revised for the Store staff team role); BRD sections 1, 2, 3, 6, 7, 8, 10 (`docs/handoffs/PHI-input/brd.txt`).
Branch: `feature/SAH-39-home-kinetic-background` (DEC-061 Answer 2), with the additive-only rules.
Touch-up, 17 Nov 2026: answers of DEC-062 and DEC-063 folded in; stories made consistent with ADR-028.
Touch-up 18 Nov 2026: stories aligned with pharmacy.yaml 0.1.1 (DEC-064), DEC-068, code review F-11/F-12. Where a story and `docs/api/pharmacy.yaml` differ, the contract wins.

Reading guide.
- Slicing: ten sub-stories (more than the usual six) because Masters is the first phase of a new module with three work packages. Each fits one sprint; 78-10 may split if the architect sees fit.
- Stories 78-2, 78-7, 78-9 and 78-10 (label "NEW screen") have no mockup screen. The human accepted the section layout and the NEW fields as the design (OQ-78-2, OQ-78-3: DEC-062 Q1 A). They are built from existing Sahaj components; the stories describe behaviour only, not layout. They no longer wait for a screen. Values that are still open questions are built on the recommended answer and named in the story.
- Every AC that follows a recommended default of an open question says so. Decided questions say DECIDED with the DEC reference.
- The Sahaj shell is not changed (BRD section 7): use the existing sidebar, top bar, theme and components; do not copy the mockup's sidebar, colours, "End-to-end flow", "USP vs LocalWell" or "USP" chips.
- Inventory and Pharmacy tabs, stock, ledger, POS, dispensing, OPD or IPD changes, WhatsApp, OCR and Excel are out of scope (requirement, "Out of scope").
- Who reads masters (contract 0.1.2, DEC-064, DEC-068 Q2 A): in these stories "a reader of setup" means a pharmacist (team role pharmacist, mapped to a store or not) or a Central Store user (mapped with that store role). They see the Pharmacy & Inventory entry and read the masters (SETUP_VIEW). A pharmacist with no store mapping is a reader too: the API answers 200 with `stores: []`, and the screen says "You are not mapped to a store. Ask your owner." Wider read access for other mapped people is OQ-78-8 (what is built is SETUP_VIEW as described here).
- There is no Pharmacist store role. The store roles are Store user, Central Store user, Approver (HOD) and Delegate. The pharmacist-only actions (Schedule X receive and issue) follow the team role PHARMACIST only.
- No Phase 1 operation is Integrated-only, so the `integration-off` answer exists in the contract but is not reachable in this release; it is tested when the first Integrated-only operation arrives (SAH-79).
- Store lists and the staff table are master reads: they are gated by SETUP_VIEW (read) and SETUP_EDIT (change), not by the working store.

Common rules for every story (each AC below assumes them; they are tested once per endpoint):
- The clinic (unit) comes from the signed-in user, never from the request. Another clinic's record answers 404 (row-level security). The global catalogue has no unit and is read-only to every unit.
- Roles are checked on the server on every call from the clinic membership (not only the token). Unless a story says otherwise: owner changes; a pharmacist or a Central Store user (a reader of setup) reads stores, products and vendors; everyone else gets 403 on the setup APIs. Module Off: 403 `module-off`.
- Store access (ADR-028 D4, BR-12): every store-bound call has `{storeId}` in its path and the server never takes a "current store" from the client. Another clinic's store: 404. A store of this clinic to which the caller is not mapped: 404 (a non-owner is not told the store exists). Mapped but missing the capability: 403 `store-permission-denied`, audited. The owner is mapped to nothing and reaches all stores for read and masters and has every store capability except pharmacist-only actions (Schedule X receive and issue, allergy or H1 override). The store check runs before any filter, sort or paging. Master reads (the store list, the staff table, products, vendors) are not store-bound: they follow SETUP_VIEW, so a reader of setup sees every store of the unit.
- Store roles are Store user, Central Store user, Approver (HOD) and Delegate. There is no Pharmacist store role; the pharmacist-only actions follow the team role PHARMACIST, checked on every call.
- Order of answers (contract general rule 2; code review round 2, N-9): module off 403, then the access level 403, then a path id that is not valid 400, then a request body that cannot be read (broken JSON, wrong shape) 400, then 404 for a record or store the person cannot use, then the store check, then field checks 400 (all field errors together), then conflicts 409, then content checks 422. The unreadable-body 400 comes right after the id check and before the 404, so that a malformed request is never answered with "not found"; it tells the person nothing about whether the record exists. Field checks stay after the 404. Every AC below that names a 400 and a 404 for the same call follows this order. After the line-send cap of an opening stock file is reached, a request with a bad body answers 409, not 400, because the state check comes first (DEC-077; see 78-7).
- One capability answer, `GET /context` (ADR-028 D5): integration mode, capabilities, unit, and the caller's stores and store roles. The screens show only what the capabilities list (hidden, not greyed). The server refuses an Integrated-only action in Standalone mode with 409 `integration-off`; no Phase 1 operation is Integrated-only.
- FEFO and negative-stock block are fixed On, shown as facts, with no switch and no API to change them (OQ-78-11, DECIDED, DEC-062 Q4 A).
- Document numbers are kept per unit, document type and financial year (April to March, IST).
- Money is ₹ with Indian grouping and paise when present (₹ 1,25,000 or ₹ 1,25,000.50); amounts are held to the paisa. Dates 24 Oct 2026; times 11:05 AM IST. Plain English, sentence case.
- A button is disabled while its request runs; nothing is shown as saved until the server has answered; a failed save keeps what was typed and says "Could not save. Check your connection and try again." (NEW). No offline queue.
- Every create, change and switch-off is audited (who, when, before and after) and never hard-deleted. Audit and log text holds no patient name, mobile number or ABHA number.
- Empty states say what to do next in one line. Lists load in pages; a list never shows another clinic's rows.
- Additive only (DEC-061 Answer 2): with the module switch Off, nothing existing changes (see 78-1).

Work packages (built in sequence WP-A, then WP-B, then WP-C: DEC-063 Q2 A; inside each, the Java and Angular developers work in parallel):

| Package | Sub-stories | Depends on | Main tables / screens it owns (behaviour level) |
|---|---|---|---|
| WP-A Foundation: unit, stores, staff, shell | 78-1, 78-2, 78-3, 78-4 | Nothing built before it. Includes the additive platform plug-ins decided by the human: module flag `pharmacy_on` (Off by default) and its navigation key, the Store staff team role (OQ-78-7), and the menu port `PharmacyAccessPort` (ADR-028 D1, D11). The admin screen to switch the module on is a separate small story and is not needed to build (fixtures switch it on in dev and test). | Module entry, tab shell, `GET /context`, unit settings, stores, store-staff mapping, working store, the store guard used by every other package |
| WP-B Products and opening stock | 78-5, 78-6, 78-7 | WP-A: 78-1 (shell), 78-3 (stores, for local settings per store and for opening stock), 78-4 (who is mapped to a store; the store guard). Vendors (78-8) are built later, so until WP-C the "Preferred vendor" select shows only "None". Posting of approved opening stock waits for the ledger in the first SAH-79 story (ADR-028 OQ-PHI-9). | Global (sample) catalogue, adopt, own product, duplicate check, units and conversion, local settings, opening-stock upload, checks, review and owner approval |
| WP-C Vendors, approval rules, documents | 78-8, 78-9, 78-10 | WP-A: 78-1, 78-2 (GSTIN, licence for print templates), 78-3 (store list and types), 78-4 (HOD names in rule warnings). WP-B: 78-5 and 78-6 (the vendor "Products" count and the "Preferred vendor needs review" note read products; the preferred-vendor select gets its vendors). | Vendors, approval rules and presets, numbering, reason lists, print templates |

Overlap rules: WP-A owns the shell routes, the Inventory setup section list, the store guard, `GET /context` and the store-list API. WP-B and WP-C add their sections and APIs but do not edit those. One `docs/api/pharmacy.yaml` (architect) and one set of Flyway migrations in the order A, B, C. Because the packages run in sequence there is no overlap in time; WP-C starts only after WP-B is finished and tested.

---

### SAH-78-1 · As an owner or a person mapped to a store, I want one Pharmacy & Inventory entry in the Sahaj menu that opens the right tabs and sections for my role, so that I reach my work without a second menu or a different look
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html` (page header, tabs "Stores & staff", "Products", "Vendors", and the left panel's three tabs Inventory setup, Inventory, Pharmacy). Package WP-A.

## Acceptance criteria
- Given the Pharmacy & Inventory module is on for the clinic and the signed-in person is the owner, any pharmacist (mapped or not), or anyone with an active store mapping (any store role, including a Store staff team member), when the Sahaj sidebar loads, then it shows one entry "Pharmacy & Inventory" in the existing sidebar style (no mockup sidebar, colours or logo).
- Given the module is off for the clinic (the default for every clinic until the Sahaj admin switches it on, DECIDED OQ-78-10), when any user loads the app, then the entry is hidden (not greyed) and any P&I API call answers 403 `module-off`.
- Given the module switch is Off (the default), when any user of any role, including the owner, uses Sahaj, then the sidebar, every menu, the Team and access role list, every existing screen, and every existing API and its JSON behave exactly as before this release; no existing test result changes (DEC-061 Answer 2 rule 4).
- Given the module is on and a signed-in person is not the owner, is not a pharmacist and is mapped to no store (for example front desk, counsellor, visiting doctor, or a Store staff member with no mapping), when they open a P&I address directly, then they see "You do not have access to Pharmacy & Inventory. Ask your owner." and the API answers 403.
- Given the module is on and a signed-in pharmacist is mapped to no store, when they open Pharmacy & Inventory, then the API answers 200 with `stores: []` and the capability SETUP_VIEW, the Inventory setup tab opens read-only, and the working-store area says "You are not mapped to a store. Ask your owner." (contract 0.1.2, DEC-064).
- Given a Store staff team member with no mapped store signs in, then they land on /app/pharmacy (not on Today) and see "You do not have access to Pharmacy & Inventory. Ask your owner." (code review F-1).
- Given a Store staff team member mapped to a store, when they sign in, then Pharmacy & Inventory is the only menu item in their sidebar: no other module entry and no HRA `staff` item, even when HRA is on (see 78-4 for the role; DEC-068 Q2 A).
- Given a person with an entry to Pharmacy & Inventory but without the SETUP_VIEW capability (for example a mapped store user or approver who is not a pharmacist), when they open an Inventory setup address directly, then they see "You do not have access to Inventory setup. Ask your owner." (NEW text) and the API answers 403.
- Given the entry opens, then three tabs can exist in the order Inventory setup, Inventory, Pharmacy (BRD order), and only tabs with at least one released screen are shown: in this release only Inventory setup (OQ-78-4, DECIDED, DEC-063 Q1 A). No empty tab and no "coming soon" page. A tab never shows a screen of another tab.
- Given the shell loads, then it reads one `GET /context` answer (integration mode, capabilities, unit, the caller's stores and store roles) and shows only the tabs, sections and buttons that the capabilities list; anything not listed is hidden, not greyed.
- Given the Inventory setup tab, when the owner opens it, then it shows the header "Setup" with the line "Unit: <clinic name from the clinic profile>" and the section tabs "Stores & staff", "Products" and "Vendors" (as in mockup 16) followed by the accepted sections of stories 78-2, 78-7, 78-9, 78-10 in the order: Stores and staff (with unit settings as a card at the top), Products, Vendors, Approval rules, Numbering and documents, Opening stock (OQ-78-2, DECIDED, DEC-062 Q1 A). The mockup's "Organization:" line is not shown (OQ-78-9, DECIDED).
- Given the section tabs, then each tab shows its live subtitle: stores and distinct mapped staff ("7 stores · 24 staff mapped"), the product source ("Global catalogue + own products"), and active vendors ("12 active suppliers"); the numbers are counted by the server for this unit.
- Given a pharmacist or a Central Store user (a reader of setup), when they open Inventory setup, then they see the Stores & staff, Products and Vendors sections read-only (no "+ Store", "+ Map staff", "+ Vendor", "Adopt", "Edit" or save buttons are shown) with the pill "View only"; the API answers 403 to any change. (OQ-78-8: SETUP_VIEW is what is built; a wider read group needs a human decision.)
- Given any other mapped user (store user, approver) who is not a pharmacist, when they open Pharmacy & Inventory, then no Inventory setup tab is shown and the working-store switch of 78-4 is the only setup item.
- Given the signed-in person is the owner and the unit has no stores yet, when Stores & staff opens, then it shows the empty state "No stores yet. Create your Central Store to start." with the one button "+ Store" (78-3).
- Given the page is loading, then a loading state shows; given the server is slow or unreachable, then "Could not load. Try again." with a Try again button, and nothing stale is shown as current.
- Given a narrow screen (phone or tablet), when the entry opens, then the tabs and the working-store switch are usable; setup tables may scroll sideways.
- Given the mockup's left-panel entries "End-to-end flow" and "USP vs LocalWell (internal)", then they are not built (requirement, out of scope).

## Notes for design
- BRD section 7: one sidebar entry, three tabs, existing shell. Navigation: the entry shows for the owner, any pharmacist, or any person with a store mapping (ADR-028 D11, revised by DEC-064): `NavigationPolicy` key `pharmacy`, using a port `PharmacyAccessPort.hasAnyStore` declared in `platform.api` and implemented by pharmacy.
- A Store staff team member sees no other menu item, not even HRA `staff` (DEC-068 Q2 A); the landing for Store staff is /app/pharmacy (F-1).
- The entitlement is `platform.module_entitlement.pharmacy_on`, a new column with default false, read through the module-entitlement port (ADR-028 D11). Test fixtures switch it on. A Sahaj-admin switch screen is a separate small story.
- Section list and routes belong to WP-A; the other work packages add children.
- Every touch to existing code is additive (DEC-061 Answer 2 rule 2): the sidebar entry and route, the module column, the role value, and the modularity test rules. Anything else goes back to the tech lead.

---

### SAH-78-2 · As an owner, I want to keep my unit's pharmacy settings (GSTIN, drug licence, Integration mode) in one place, so that documents carry the right legal details and every screen knows whether OPD and IPD are connected
Priority: Must
Screen: NEW screen, not in mockup (BRD M1 says "Setup → Stores & staff"; mockup 16 draws no settings). Accepted design (OQ-78-2, DEC-062 Q1 A): a Unit settings card at the top of Stores & staff. Header line of mockup 16 ("Unit: ...") is the only drawn element. Package WP-A.

## Acceptance criteria
- Given the owner opens Unit settings, then it shows: Unit name (from the clinic profile, read-only here), GSTIN, Drug licence number, Drug licence valid till, Integration mode (Integrated or Standalone), and two fixed facts, "FEFO: On" and "Negative stock: Blocked", each shown as a fact with no switch (OQ-78-11, DECIDED, DEC-062 Q4 A).
- Given a first visit, then Integration mode is Integrated (OQ-78-14, DECIDED, DEC-064 R2-2) and the other fields are empty.
- Given a request that tries to change FEFO or negative stock, then the answer is 400 and nothing changes; no screen or API offers a switch.
- Given the owner enters a GSTIN that is not 15 characters of the GSTIN pattern, when saved, then the answer is 400 and the field says "Enter a 15-character GSTIN" (NEW text; format only, OQ-78-23, DECIDED); an empty GSTIN is allowed and shown as "Not set".
- Given a licence number longer than 40 characters, or a licence date that is not a date, when saved, then 400 with a field message; a licence date in the past is allowed and shown "expired" (the owner may be correcting records). Sahaj does not check the number with any authority.
- Given the owner changes Integration mode, when they save, then a confirmation says what changes ("Standalone hides OPD and IPD links. Existing records are kept." and the opposite for Integrated), and nothing changes until they confirm; then the new mode is saved, audited with before and after, and the `GET /context` answer of every person shows the new mode from then on.
- Given Integration mode is Standalone, then the capability answer lists no OPD or IPD capability and any screen of this release shows no OPD or IPD queue or patient-bill link (hidden, not greyed); given Integrated and the clinic's IPD module is Off, then IPD parts stay hidden. In Phase 1 every integrated capability answers "not used", so only the switch and the capability answer are live and tested (Phase 1 reading of BRD AC-M7, accepted with ADR-028). No Phase 1 operation is Integrated-only.
- Given Standalone mode and a request for an Integrated-only action, then the server answers 409 `integration-off` even if the screen was changed. No Phase 1 operation uses it; this AC is tested when the first Integrated-only operation arrives (SAH-79).
- Given any non-owner, when they call the change API, then 403; a pharmacist or a Central Store user (a reader of setup) may read the unit settings (GSTIN and licence) because documents need them; others get 403.
- Given another clinic, then its settings are never visible (the API has no id and always reads the caller's unit; 404 on another clinic's id).
- Given two owners save at the same moment, then the second gets 409 `settings-changed` with the current values and nothing is overwritten silently.
- Given a save, then the audit event holds the changed fields with before and after; GSTIN and licence number are business data and may appear in audit.

## Notes for design
- Integration mode is read through one adapter switch (BRD section 6; ADR-028 D5: `ClinicalLinkResolver`); no screen code reads OPD or IPD directly, and no mode check lives outside the resolver. The front end reads it from `GET /context` only.
- The unit name is not duplicated from `platform.clinic_profile`; read it through `platform.api`.
- ADR-028 data lists a Schedule X licence on the unit; the stories do not build it (OQ-78-41, open).

---

### SAH-78-3 · As an owner, I want to define the stores in my unit (type, where each issues from, who approves, whether it sells or records consumption), so that every later purchase, indent and sale has a place to happen
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Stores & staff": card "Stores in this unit" (tree, "+ Store") and the detail card (fields Store type, Issues from, Approver (HOD), Delegate approver, Sale to patients, Consumption entry). Package WP-A. BRD M2, AC-M1.

## Acceptance criteria
- Given the owner opens Stores & staff, then the card "Stores in this unit" shows the Central Store at the top and every other store below it, each with name, a type chip (Central, Sale for a Pharmacy, Sub-store, "Ward · consumption" for a Ward store), the line "Issues from <store>" and the number of mapped staff ("· 5 staff"); the Central Store line reads "Buys from vendors · issues to all stores".
- Given a store is selected (marked), then the detail card shows its name as the heading and the fields in this order: Store type, Issues from, Approver (HOD), Delegate approver, Sale to patients (Yes or No), Consumption entry (Yes or No), then the staff section of 78-4.
- Given the owner taps "+ Store", then a form asks Name and Code first (NEW, OQ-78-3, DECIDED), then the fields above; Store type offers Pharmacy, Sub-store and Ward store (and Central Store only while the unit has none); Issues from offers the Central Store only (OQ-78-12, DECIDED: one Central Store per unit).
- Given the unit has no Central Store, when the owner saves any other store, then the answer is 409 and the message says "Create the Central Store first."; given the unit already has a Central Store, when a second store of type Central Store is tried, then 409 "This unit already has a Central Store."
- Given a new store with valid fields, when saved, then it appears in the tree under the Central Store; Sale to patients defaults to Yes for a Pharmacy and No for others, and Consumption entry defaults to Yes for a Ward store and No for others (NEW defaults, for sign-off); all can be changed.
- Given Name or Code is empty, or a Name or Code already used by a store of this unit (ignoring capital letters), when saved, then 400 for empty ("Enter the store name") and 409 for a repeat ("That store name is already used" for a name; "That store code is already used" for a code), and nothing is saved.
- Given Name longer than 60 characters, Code longer than 10 characters or with characters other than letters, digits and hyphen, then 400 with a field message.
- Given the owner chooses an Approver (HOD) or Delegate approver, then the select lists only active team members allowed by OQ-78-6 (DECIDED: owner, doctor, nurse, pharmacist; not Store staff); choosing someone who is not yet mapped to the store maps them with the role Approver or Delegate (78-4).
- Given the owner creates the very first Central Store (the unit has no store yet), then the form already offers the Approver (HOD) and Delegate approver selects, and the owner may choose them at create time (F-11, pharmacy.yaml 0.1.2: the candidate list needs no store yet; it is filtered by team role only and nobody is shown as already mapped). The earlier note that the approver can be set only after saving is removed.
- Given a Delegate approver, then the owner must give the start date and the end date (OQ-78-15, DECIDED, DEC-064 R2-9); an end date before the start date, or a delegate who is the same person as the HOD, is refused with 400 and a field message; the delegation may last at most 90 calendar days counting both the first and the last day (90 days allowed, 91 days refused with 400 and the message "A delegation can be at most 90 days"; DEC-068 Q1 A); after the end date the delegate no longer approves and the store row says "Delegate ended 30 Nov" (NEW text).
- Given the selected store has no active HOD (none chosen, or the person's access was switched off), then the detail shows the pill "No approver set" and a note that approval rules using an approver need one (78-9).
- Given a store's type, when the store already has an approved opening-stock import (78-7), then type, Issues from and code are locked and a note says why; name, HOD, delegate, Sale and Consumption can still be changed.
- Given the owner switches a store off (Switch off, NEW, with a confirmation), then it disappears from the choices for new work, keeps its history, and its mappings stop counting; given it has approved opening stock, the answer is 409 "This store has stock. Clear it in Inventory first." (OQ-78-38, open). The Central Store cannot be switched off while another store is active.
- Given a store has an opening stock file (78-7) that is a draft (rows still being sent) or waiting for approval, when the owner switches the store off, or changes its code, then the answer is 409 `open-import` with the sentence "Finish or reject the opening stock file of this store first." (NEW text), and nothing changes; once the file is approved, rejected or replaced, the store is free of this check (an approved file has its own locks above and OQ-78-38). Decided by DEC-071 Q1 A (auto, human away; the human confirms at the merge ask). Reason: switching the store off while a file is open would leave a file nobody can approve or reject (code review N-2).
- Given a store has been switched off, including in the meantime while a person was starting an opening stock file, when a person starts, submits or approves a file for it, then the answer is 404 and no file is created for that store (DEC-077, security L9; the database also refuses a file for a store that is not active). See 78-7.
- Given a pharmacist or a Central Store user (a reader of setup), then stores are read-only (78-1). `GET /stores` lists all stores of the unit for anyone with SETUP_VIEW, mapped or not; the store list is a master read, not a store-bound call (contract 0.1.2; code review F-12). A pharmacist therefore sees every store name, the staff names in the staff table, and the unit's GSTIN and licence. This is what the contract builds (OQ-78-8).
- Given an unknown store id, or another clinic's store id, then 404.
- Given a change, then an audit event holds the before and after values (store, field).

## Notes for design
- Hierarchy: a store issues from the Central Store; tree depth is one level in this release.
- Store flags are what later phases read: sale allowed, consumption allowed, type.
- Name and code are not on the mockup detail card (OQ-78-3, DECIDED).
- Several Central Stores per unit is not built (OQ-78-12, DECIDED).
- ADR-028 D4 keeps the HOD and delegate (with dates) as fields of the store; the behaviour above is the same (OQ-78-39, open).
- Delegate length: the contract says "at most 90 calendar days counting both ends" (`to - from > 89` is refused).
- Create-time approver (F-11): the staff-candidates list works without a store id; the screen always shows Approver and Delegate at create.

---

### SAH-78-4 · As an owner, I want to map staff to stores with a role in each, give the Store staff team role to store keepers, and let each person pick a working store after sign-in, so that people act only in their own stores
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Stores & staff": section "Staff mapped to this store" (button "+ Map staff", table columns Staff, Store role, Raise indent, Approve, Receive, Consume / return, Other stores, and the note "Every store user can receive, not only the HOD. A user mapped to several stores picks the working store after login."); also the "Working store" select shown in the left panel footer of every P&I screen (file 16 and 05). Package WP-A. BRD M3, AC-M2.

## Acceptance criteria
Store staff team role (DECIDED, OQ-78-7, DEC-062 Q3 A)
- Given the module is on and the owner adds or edits a team member in Team and access (SAH-6), then the role list also offers "Store staff"; given the module is off, then the list on the screen is exactly as before: the screen hides the option (OQ-78-40(b), settled by the tech lead, see the design note).
- Given a Store staff team member signs in, then their menu is limited to Pharmacy & Inventory (no other module entry and no HRA `staff` item, DEC-068 Q2 A), and any other module's screens and APIs answer 403 for them as for any role without permission.
- Given a Store staff team member, when the owner maps them, then only the store roles Store user and Central Store user are offered; Approver or Delegate approver for a Store staff member is refused with 400 and a field message.
- Given a mapping whose stored store role (Approver or Delegate) no longer fits the person's team role (for example the person's team role was changed after mapping), then the row shows "Needs review" and the person counts as Store user until the owner fixes it.
- Given the new role exists, then no existing role changes its name, menu or permissions, and existing team members keep their roles.

Mapping
- Given the owner selects a store and taps "+ Map staff", then the form asks Person (active team members of this unit), Store role (Store user, Central Store user (only at the Central Store), Approver, Delegate approver with its dates) and saves the mapping. There is no Pharmacist store role.
- Given a mapped person, then the table row shows name and, below it, the Sahaj role label (for example "Pharmacist", "Nurse", "Store staff") and, for the HOD, "HOD, <store>"; a chip with the store role (Approver, "Delegate · till 30 Nov", Store user, Central Store user); the four access columns Raise indent, Approve, Receive, Consume / return as Yes or No; and "Other stores" listing the other stores the person is mapped to, or "—".
- Given the four access columns, then they are read-only and worked out by the server: Approver and a current Delegate show Yes in all four; a Store user shows Raise indent Yes, Approve No, Receive Yes and Consume / return Yes only when the store's Consumption entry is Yes; a person whose team role is pharmacist shows "Yes · incl. Schedule X" for Receive and no other person does (it follows the team role PHARMACIST only, not a store role); a Central Store user's columns follow the Central Store (Raise indent No, Receive Yes) (NEW, OQ-78-6, DECIDED). The owner cannot tick them.
- Given a Delegate whose end date has passed, then the row says "Delegate · ended 30 Nov", Approve shows No, and the person remains mapped as a Store user (NEW).
- Given a Delegate approver mapping, then the start and end dates are required, the end date is not before the start date, and the delegation lasts at most 90 calendar days counting both the first and the last day (90 allowed, 91 refused with 400 "A delegation can be at most 90 days"; DEC-068 Q1 A).
- Given a mapping change request (owner only) whose body cannot be read, then 400 right after the id check and before the 404 for a store or person that does not exist or belongs to another clinic; field checks (role fit, dates) stay after the 404 (common rule "Order of answers", N-9).
- Given the person is already mapped to this store, when mapped again, then 409 "That person is already in this store" and nothing changes; mapping the same person to another store is allowed with a different role (AC-M2: one user, many stores).
- Given a store role not allowed for the person's Sahaj role (an Approver or Delegate who is front desk, billing, counsellor, visiting doctor or Store staff; a Central Store role at a store that is not the Central Store), when saved, then 400 with a field message (OQ-78-6, DECIDED).
- Given the owner removes a mapping (Remove from store, NEW, confirmation), then the person loses that store at once, the row stays in the history, and if they were the HOD or delegate the store shows "No approver set"; the audit shows who removed whom.
- Given a team member is switched off in Team and access (SAH-6), then all their mappings stop counting at once, the row says "Cannot sign in" (NEW), and they cannot choose a working store.
- Given a pharmacist or a Central Store user (a reader of setup) opens the table, then it is read-only (the staff table is a master read gated by SETUP_VIEW); a non-owner calling a mapping change API gets 403; an unknown store or another clinic's person or store, 404.

Working store
- Given a person mapped to one store, when they sign in and open Pharmacy & Inventory, then that store is their working store and the select shows it.
- Given a person mapped to several stores, when they open Pharmacy & Inventory for the first time after sign-in, then they are asked to pick a working store; the select lists only their stores (from `GET /context`), and the choice is remembered for their next sign-in.
- Given a working store, when the person switches to another of their stores, then every P&I screen shows that store's data from then on and the choice is saved.
- Given a person mapped to no store (an owner or a pharmacist with no mapping), then the select is replaced by "You are not mapped to a store. Ask your owner." and store-bound screens stay empty; the owner can still open setup and reach every store, and a pharmacist can still open setup read-only.
- Given a request that names a store of this clinic that the person is not mapped to (changed address, edited request), or a store that does not exist or belongs to another clinic, then the server answers 404 and the working store does not change; given a mapped store where the person lacks the capability, then 403 `store-permission-denied` (audited).
- Given a mapping is removed or a store is switched off while the person has it as working store, when their next call arrives, then they are asked to pick again; no screen shows data of a store they lost.
- Given a phone or tablet, then the working-store select is reachable and usable (BRD section 7).
- Given the owner sets someone as HOD in 78-3, then the mapping table shows them as Approver without a second step.

## Notes for design
- The working store is a path value (`{storeId}`) verified against the mapping on every call, never trusted from the client; the screen keeps the selection and the server remembers the last one per person for convenience (ADR-028 D4).
- The store check is one guard class (ADR-028 D4). There is no Pharmacist store role; pharmacist-only actions are checked against the team role PHARMACIST by the guard.
- The Store staff role is an additive platform change (SAH-6 handoff to the platform owner). Tech lead decision (recorded in `docs/handoffs/SAH-78.md`, 18 Nov 2026): `POST /team` accepts STORE_STAFF while the module is off, and the screen hides the option while `pharmacy_on` is false. "The list is exactly as before" refers to the screen. This settles OQ-78-40(b).
- Access columns are derived, not stored per person.
- A pharmacist who is also the HOD: OQ-78-39 (open).
- Phase 2 will use these mappings for "any user mapped to the receiving store can receive" (BRD indent rules).

---

### SAH-78-5 · As an owner, I want to search the shared product catalogue together with my own products, adopt a global product and set my unit's local settings, so that my stock list is correct without retyping drug details
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Products": card "Product master" (legend chips "Global · shared by all units" and "Own · this unit only", the search box "Searches global + own products", table columns Source, Product, Salt · strength · form, UOM (buy → issue), HSN · GST, Flags, In this unit, action button "Adopt" or "Local settings" or "Edit", and the note about read-only global fields). Package WP-B. BRD M4, M5, AC-M3.

## Acceptance criteria
Search and list
- Given a person with access to Products (the owner, or a pharmacist or a Central Store user, a reader of setup) opens Products, then the Product master card shows the legend chips, the search box and a table with the columns of the mockup: Source (Global or Own chip), Product (name, and below it the code and the manufacturer, or "made in-house" for an own product), "Salt · strength · form", "UOM (buy → issue)" (for example "Box of 10 → vial"), "HSN · GST" (for example "3004 · 12%"), Flags (schedule chip H, H1 or X; "—" when None), "In this unit" (Adopted, Not adopted, or Active for an own product, Inactive when switched off) and an action button.
- Given the owner types in the search box, then global and own products are searched together, ignoring capital letters; a barcode must match exactly (ignoring capital letters), and name, salt, alias and code match as a part of the text; fewer than 2 characters shows the first page unfiltered; results load in pages of 25 and show "No products match. Try the salt name." when none match (NEW).
- Given the search runs, then it answers within 300 ms for the sample catalogue in the performance test (BRD target; the architect confirms the real catalogue size, OQ-78-16).
- Given a pharmacist or a Central Store user (a reader of setup), then the list is the same but no Adopt, Local settings (edit) or Edit button shows.

Sample catalogue (DECIDED, OQ-78-16, DEC-062 Q2 A)
- Given the development, test or demo environment, then the global catalogue holds about 30 sample products, and every one is marked "Sample · not clinically validated" where the product is shown; manufacturer names are made up and HSN, GST % and schedule values are sample values.
- Given a production environment, then no sample product is loaded: the global catalogue is empty, the list shows own products only, and an empty catalogue says "No shared products yet. Create your own product." (NEW) until a real, pharmacist-reviewed catalogue is loaded by a separate story (go-live item G-10).

Adopt
- Given a global product "Not adopted", when the owner taps "Adopt", then the product becomes "Adopted" for this unit, the button becomes "Local settings", and an audit event is written; adopting again changes nothing and answers success (idempotent).
- Given an adopted global product, then its name, salt, strength, form, manufacturer, HSN, GST %, schedule, units and conversion are read-only for the unit; any attempt to change them through the API is refused with 400 and nothing changes (AC-M3: "global fields stay read-only").
- Given a global product, when it is changed or removed in the global catalogue, then no unit's local settings are lost (adoption is a link plus local fields).
- Given a global product that is no longer in the catalogue, then it stays visible to units that adopted it, marked "Withdrawn", and cannot be adopted by others (NEW).

Local settings
- Given an adopted or own product, when the owner taps "Local settings", then a form shows (order NEW, OQ-78-3, DECIDED): Reorder level per store (one number for each active store, in issue units, whole number 0 or more), Rack per store (up to 30 characters), Preferred vendor (active vendors of this unit, or none), Alias (up to 40 characters), Barcode, Returnable (Yes or No), and "Stop using this product" (a switch).
- Given the owner saves, then the values are saved for this unit only and appear on the next open; a reorder level of -1 or a decimal, a rack longer than 30 characters, or a preferred vendor that is another unit's, is refused with 400 and a field message.
- Given a barcode already used by another active product of this unit, when saved, then 409 "That barcode is already used by <product>" and nothing is saved; the same barcode in another unit is allowed.
- Given the owner switches off a product that has approved opening stock, then 409 "This product has stock. Clear it in Inventory first." (OQ-78-38, open); a product with no stock is set Inactive, disappears from choices for new work, keeps its history.
- Given a product is on a line of an opening stock file (78-7) that is a draft or waiting for approval, when the owner switches the product off ("Stop using this product"), then the answer is 409 `open-import` with the sentence "Finish or reject the opening stock file of this store first." (NEW text), and the product stays active. Once the file is approved, rejected or replaced this check no longer applies (an approved file has the rule above). Decided by DEC-071 Q1 A (auto, to be confirmed by the human at the merge ask). The units and conversion of an adopted product are read-only already (above), so only own products can reach the units rule in 78-6.
- Given a product with no vendor yet in this unit (78-8 not built yet or no vendors), then Preferred vendor offers only "None".

Roles, tenants, errors
- Given a non-owner calls adopt or local-settings APIs, then 403; another clinic's product or setting, 404; the global catalogue answers the same list to every unit (apart from the unit's own adoption marks).
- Given a failed adopt or save, then nothing is shown as saved and the error text is shown with what was typed kept.
- Given two owners edit the same local settings at once, then the second gets 409 `settings-changed` and the current values.
- Given the empty states, then "No products yet" shows when neither global nor own products exist, with the sentence "Adopt a global product or create your own." (NEW).

## Notes for design
- The global catalogue is read-only reference data without a clinic id, and the application role has read access only. The ~30 sample products load by a development, test and demo seed only, never in production (the architect and DB engineer decide how); no real HSN, GST or schedule values are invented by the BA.
- The mockup's "Own products can be sent for review" note and button are not built (OQ-78-18, open). The product "MRP rule" is not built (OQ-78-20, DECIDED).
- The product link to `opd.medicine` and `ipd.drug` is Phase 3 (DEC-061 Q3).
- Search target 300 ms is the BRD's POS figure; applying it to setup search is the BA's proposal.

---

### SAH-78-6 · As an owner, I want to create my own product with its units and conversion and be warned when it already exists in the global catalogue, so that I do not split stock across duplicate products
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Products": card "Create own product" (fields Product name, Salt / generic, Strength, Form, Purchase unit, Issue unit; the warning "Possible duplicate: global product ... Use it instead of creating a new one?" with buttons "Use global product" and "Create anyway"; and the "Edit" button on an own product row). Package WP-B. BRD M4, AC-M3, AC-M4.

## Acceptance criteria
Create and duplicate check
- Given the owner opens "Create own product", then the form shows in this order: Product name, Salt / generic, Strength, Form, Purchase unit, Issue unit, then the fields the BRD needs and the mockup lacks (NEW, OQ-78-3, DECIDED): Issue units per purchase unit, Manufacturer (or "Made in-house"), HSN, GST %, Schedule, Barcode.
- Given Product name, Salt, Strength, Form, Purchase unit, Issue unit, Issue units per purchase unit, HSN, GST % and Schedule are required (Manufacturer unless "Made in-house"), when any is empty or invalid, then the save answers 400 with a field message for each and nothing is saved; Schedule has no default and shows "Choose the schedule" until chosen (BR-16); a note says "Check the drug schedule before saving" (OQ-78-19, DECIDED).
- Given Issue units per purchase unit is not a whole number of 1 or more, then 400 "Enter a whole number, 1 or more"; Strength, Name and Salt over 80 characters, HSN not 4 to 8 digits, GST % not between 0 and 100 with at most 2 decimals, then 400 with a field message. GST % is typed, with no fixed list of slabs.
- Given the owner has entered salt, strength and form (and leaves the field), when a global product with the same salt, strength and form exists (OQ-78-17, DECIDED), then the warning "Possible duplicate: global product "<name>" (<code>) has the same salt, strength and form. Use it instead of creating a new one?" shows with the buttons "Use global product" and "Create anyway"; the manufacturers of the matches are listed with them.
- Given the warning, when the owner taps "Use global product", then nothing is created, the global product is adopted for this unit (78-5) and shown as Adopted; given "Create anyway", then the own product is created, an own product shown with an Own chip, code `U-<unit code>-nnnn` and "made in-house" or its manufacturer, and the audit records that it was created despite a possible duplicate of <code>.
- Given no global product matches but an own active product of this unit has the same salt, strength and form, then the same warning names the own product and offers "Use <own product>" and "Create anyway".
- Given the owner saves a new own product with the same name, salt, strength and form as an existing own product and chooses "Create anyway" again, then it is created (the warning is advice, not a block) and both appear in the list.
- Given a barcode already used by another active product of this unit, then 409 as in 78-5.

Units and conversion
- Given the purchase unit, issue unit and conversion, then the list shows "Box of 10 → vial" built from them (unit names from the owner's list or typed, OQ-78-21, open), and "Kit → kit" when the conversion is 1 and the names match. Quantities after receiving are whole issue units.
- Given an own product with no approved opening stock, when the owner edits Purchase unit, Issue unit or Issue units per purchase unit, then the change is saved and audited; given the product has approved opening stock (or any later stock), then these three fields are read-only with the note "Locked because this product has stock." and an API change answers 409 (BR-15).
- Given an own product is on a line of an opening stock file (78-7) that is a draft or waiting for approval, when the owner changes its Purchase unit, Issue unit or Issue units per purchase unit, or switches the product off, then the answer is 409 `open-import` with the sentence "Finish or reject the opening stock file of this store first." (NEW text), and nothing changes; the screen shows the same sentence as the reason. Name, strength, schedule, HSN and GST % can still be edited. Once the file is approved, rejected or replaced this check no longer applies (an approved file locks the units under BR-15 and the rule above). Decided by DEC-071 Q1 A (auto, to be confirmed by the human at the merge ask).
- Given any field the owner edits on an own product (name, strength, schedule, HSN, GST %), then it is saved and audited with before and after; a change of schedule shows a confirmation "This changes how the product is sold and recorded. Check the schedule." (NEW).

Roles and tenants
- Given a non-owner calls create or edit, then 403; another clinic's own product, 404; two units may use the same product name and barcode.
- Given a slow or failed save, then the form keeps its values, shows the error, and the Create button is disabled while the request runs; a double tap creates one product only (idempotent create).
- Given the owner opens "Edit" on an own product, then the fields shown are the same as at creation; the code and source cannot be changed.

## UI text (NEW, wording check of 18 Nov 2026, DEC-070 item 10)
- "Save anyway": the label of the "Create anyway" button when the owner is editing an own product (NEW; the mockup shows "Create anyway" on create only, and the create text is unchanged). It saves the edit and keeps the audit entry of the possible duplicate, as "Create anyway" does.
- Open point: this story states the duplicate warning on create only. The contract (0.1.2) also runs the check on edit, when salt, strength or form changes, and the warning then shows. The human confirms that rule; the story criteria are not changed here.
- Text check of 18 Nov 2026 (round 2, DEC-071), inline card on the Products tab: "A product only this unit uses." Verdict: OK, no replacement. Reason: it says in plain words what the mockup legend chip "Own · this unit only" already says; the mockup draws no sentence under "Create own product", so it is NEW; it adds no rule. It sits next to the schedule note "Check the drug schedule before saving" (OQ-78-19, DECIDED, above). The two stay separate lines: the first describes, the second is the safety warning and stays beside the Schedule field. Use the same punctuation for both (a full stop, as the other sentences in this list).
- Text check, same date: "Finish or reject the opening stock file of this store first." is the 409 `open-import` sentence (DEC-071). Verdict: OK, NEW. It is shown as it is when a product is switched off or its units are changed while it is on an open opening stock file (see the criterion above). The word "store" is correct for the product case too, because the file belongs to a store.

## Notes for design
- Duplicate rule is salt, strength and form (OQ-78-17, DECIDED). ADR-028 D3 builds the duplicate key with the manufacturer as well; the architect aligns it to this decision and answers the matches with the "create anyway" choice in the contract.
- Product code format for own products: `U-<unit code>-nnnn`; the unit code is a short code the architect derives (the clinic's code); the mockup shows `U-BAN-0042`.
- HSN, GST % and schedule are legal and pricing choices of the owner and are not validated beyond format (OQ-78-19, DECIDED; go-live item G-2).
- "Send for review" to the global catalogue is not built (OQ-78-18, open).

---

### SAH-78-7 · As a pharmacist or Central Store user, I want to upload my starting stock from a CSV file and as an owner I want to check and approve it, so that the first stock is loaded once, correctly and with a second pair of eyes
Priority: Must
Screen: NEW screen, not in mockup (BRD "Opening stock": "A one-time import (Excel template) loads store, product, batch, expiry, MRP, purchase rate and quantity. It posts as an opening-stock line in the ledger and needs owner approval."; DEC-061: CSV instead of Excel). Accepted design (OQ-78-2, DEC-062 Q1 A): a section "Opening stock" in Inventory setup. Package WP-B. Posting to the ledger is not in this story: it is the first SAH-79 story (OQ-78-34, DECIDED, ADR-028 OQ-PHI-9). The file rules (OQ-78-35) and one import per store (OQ-78-37) are still open and are built on the recommended values below.

## Acceptance criteria
Template and upload
- Given a Central Store user or a pharmacist who is mapped to the store, or an owner, when they open Opening stock, then they can download the CSV template (header row: store code, product code, batch, expiry date, MRP, purchase rate, quantity; with one example row marked as an example) and upload a filled file for one store.
- Given the file is not CSV (for example an Excel file), is empty, is larger than the row limit (5,000 rows, OQ-78-35, open), or has a header that does not match the template, then it is refused with a plain message ("Use the Sahaj template. Save the file as CSV.") and nothing is kept.
- Given the owner uploads, then the same checks apply and the owner may upload for any store of the unit; a Central Store user or pharmacist may only upload for a store they are mapped to (404 for a store of this clinic they are not mapped to, and 404 for another clinic's store or an unknown id, ADR-028 D4).

Checks (all-or-nothing)
- Given a file, when it is uploaded, then every row is checked and the screen lists every problem with its row number and field, and the file stays "Not accepted" until every problem is fixed and the file is uploaded again; nothing is partly imported.
- Given a row whose store code is not the store chosen for the import, or whose product code is not an adopted or own active product of this unit, or whose batch is empty or over 20 characters, then that row is an error.
- Given a row whose expiry date is not a date, or is in the past, then that row is an error "Expiry date must be in the future" (OQ-78-35).
- Given a row whose MRP or purchase rate is not an amount above 0 with at most 2 decimals, or whose quantity is not a whole number above 0, then that row is an error; rates are per issue unit and shown as ₹ (OQ-78-35: 2 or 4 decimals still to confirm).
- Given the same store, product and batch twice in one file, then both rows are errors; batch numbers are compared ignoring capital letters ("AB1" and "ab1" are the same batch), and the store code and product code of a row match ignoring capital letters and leading or trailing spaces; given the store already has an approved opening-stock import, then the upload is refused ("Opening stock was already approved for this store.", OQ-78-37, open).
- Given a row whose purchase rate is higher than MRP, then it is a warning (shown at approval), not an error.

Approval
- Given a file without errors, then it becomes "Waiting for approval" with a review showing the store, the number of lines, total quantity, total value at purchase rate (₹ with Indian grouping and paise), the warnings, and every line.
- Given the owner opens a file waiting for approval who did not upload it, when they tap Approve, then the file becomes "Approved", it can no longer be edited or cancelled, and the lines are kept as approved opening stock; they are posted to the ledger by the first SAH-79 story, and until then no screen shows them as usable stock; the audit records uploader, approver, time and totals.
- Given the owner who uploaded the file tries to approve it, then 403 `self-approval` "Another owner must approve this import." (fixed rule, OQ-78-36, DECIDED, DEC-062 Q4 A; go-live item G-9); given a clinic with one owner and a file uploaded by store staff, then the owner can approve.
- Given the owner taps Reject, then a reason from a short list (Wrong file, Wrong quantities, Wrong store, Other with a note of at least 4 characters) is required, the file becomes "Rejected" and nothing is kept as stock; the uploader can upload a corrected file.
- Given an owner who may approve at the store, when they reject a file that is a DRAFT (rows still being sent) as well as one that is waiting for approval, with a reason from the list, then the file becomes "Rejected", and the store and its products are free of the `open-import` block at once; the audit holds ids, the reason key and the note only. A file in any other state (Approved, Rejected, Replaced, Not accepted) cannot be rejected and answers 409 `import-state`. Decided by DEC-076 (auto; the human confirms at the merge ask); this settles OQ-78-42.
- Given a draft whose starter never comes back (for example the person left), when an owner rejects it, then the store and its products are no longer held by it; a person who may only upload (a pharmacist or Central Store user) cannot reject (403, as for approve).
- Given the uploader has a draft, then the uploader can still replace their own draft by starting a new file (the old draft becomes "Replaced"); nobody else can replace it.
- Given an Approved file, then it cannot be deleted; a correction is made later in Inventory with stock adjustments (reversing lines).
- Given a Waiting file, when the same uploader uploads a corrected file for the same store, then the old file becomes "Replaced" and cannot be approved.

Limits on sending (DEC-077; decided auto, the human confirms at the merge ask)
- Given a file being sent in batches, then the cap on line sends counts the changed rows written, at most twice the row count of the file; a request whose rows are all unchanged writes nothing and does not count towards the cap (so a retry after an error resumes without a 409).
- Given the cap has been reached, when another request that would write rows arrives, then the answer is 409 and nothing is written; given the cap has been reached and the request has a bad body, then the answer is 409, not 400, because the state check comes first (common rule "Order of answers"; accepted by code review round 3).
- Given a person has started 10 new files for a store today (Asia/Kolkata day), when they start an 11th for that store, then the answer is 409 "Too many files today. Try again tomorrow." (NEW text) and no file is created; the count is per person and per store, so another person, or the same person at another store, is not affected, and the next IST day starts at zero.
- Given a store that has been switched off, when a person starts, submits or approves a file for it, then the answer is 404 (also when the store was switched off a moment after the request began), and no file is created or changed.

Roles, tenants and safety
- Given any other role (nurse, doctor, front desk, billing, or a person who is neither a Central Store user nor a pharmacist), then 403 on every opening-stock API; a pharmacist or Central Store user mapped to the store may upload and read, but cannot approve; a store of this clinic to which the caller is not mapped, and another clinic's file or store, answer 404; a mapped caller who lacks the capability answers 403 `store-permission-denied` (audited). There is no 403 `outside-store`.
- Given an upload in progress on a slow network, then the page shows progress, the buttons are disabled, and a failed upload says "Could not upload. Try again." with nothing kept.
- Given an opening stock file that is a draft (rows still being sent) or waiting for approval, then its store cannot be switched off or have its code changed (78-3), and a product on its lines cannot be switched off or have its units or conversion changed (78-5, 78-6); each is refused with 409 `open-import` and "Finish or reject the opening stock file of this store first." The block ends when the file is approved, rejected or replaced (cross-reference to the rule in those stories; DEC-071 Q1 A, auto, to be confirmed by the human at the merge ask).
- Given a request to any opening-stock call whose body cannot be read (broken JSON or the wrong shape), then 400 right after the id check and before the 404 for a store the caller cannot use; field checks on the rows stay after the 404 (common rule "Order of answers", N-9).
- Given an approved import, then the products in it are locked for unit and conversion changes (78-6) and cannot be switched off (78-5, OQ-78-38, open).
- Given the log, then file contents, batch numbers and quantities are not written to application logs (only counts and ids).
- Given stock is approved but not yet posted, then no screen shows it as usable stock (BR-30).

## UI text (NEW, no mockup; wording check of 18 Nov 2026, DEC-070 item 10)
The screen is NEW (OQ-78-2), so these texts are NEW. They add no rule: each repeats a rule above. Terms: "file" is the CSV a person uploads; "import" is the record the owner approves (as in "Another owner must approve this import."); the next step after approval is "post to stock" (SAH-79-1 button "Post to stock", status "Posted").
- Intro (top of the section): "Load your starting stock from a CSV file. An owner checks and approves it. Approved stock cannot be used until it is posted to stock."
- Approved note (review of an Approved file): "Approved. The lines are kept as opening stock. They cannot be used until they are posted to stock."
- Owner list filter: label "Show"; options "All files" and "Waiting for approval" ("Waiting for approval" is the status name above).
- Review checkbox: "Show only rows with a problem or warning" (a view filter only; the checks and what is kept do not change).
- Approve lock note, for the owner who uploaded the file: "Another owner must approve this import." (the same sentence as the 403 `self-approval` message).
- Approve lock note, for anyone else who cannot approve: "Only an owner can approve this import."
- Row-number caption (above the problem rows): "Row numbers count the data rows only. The header row and the example row are not counted."
- Open points on these texts: (a) the Intro no longer says "once", because a Rejected or Replaced file can be uploaded again and "one import per store" is still open (OQ-78-37); (b) until SAH-79-1 is built there is no "Post to stock" button, so the two texts name a step the person cannot yet do; (c) if OQ-79-30 changes the name of the posting action, the words "posted to stock" change with it; (d) the story had no list of files or filter; the list and the filter are display only and need no new rule; (e) the template's example row ("marked as an example") is skipped on upload and is not counted in row numbers (contract 0.1.2, DEC-070 Q5 a); this rule is not stated in the criteria above and the human confirms it with OQ-78-35.

Text check of 18 Nov 2026 (round 2, DEC-071; the angular-developer is already using these texts). Verdict for each: OK, no replacement, so there is no follow-up list.
- (a) N-11, review of a file that is Not accepted: "Not accepted. Fix every problem below and upload the file again." Verdict: OK. It replaces "Nothing was kept.", which was untrue (the list of problems is kept for the person to read). It repeats the criterion "the file stays Not accepted until every problem is fixed and the file is uploaded again; nothing is partly imported", and "Not accepted" is the status name. Limit: "below" is true only when the review lists problem rows. A file refused before its rows are read (not CSV, empty, over 5,000 rows, wrong header) keeps the sentence "Use the Sahaj template. Save the file as CSV.", with no list.
- (b) I5, hint under the reject note: "Do not add patient details." Verdict: OK. The note goes into the audit text, and audit holds no patient name, mobile number or ABHA number (common rules). It is a hint only: it adds no check and does not change the rule that "Other" needs a note of at least 4 characters.
- (d) N-4, shown when a retry finds the file already sent: "File sent for approval". Verdict: OK for one case only, the file is Waiting for approval (the first submit worked and only its answer was lost). It is true for that status and matches the status name. It must not be shown for Approved, Rejected, Replaced or Not accepted: there the review opens with the status chip and no extra sentence (no new text is invented here). Flag for the angular-developer: show it only for Waiting for approval.
- New sentence, 409 `open-import` (DEC-071): "Finish or reject the opening stock file of this store first." Verdict: OK, NEW. It is shown wherever a store or product change is refused because a file is open (78-3, 78-5, 78-6). "Finish" covers a draft that is still being sent or approval that is waiting; "reject" is the owner's action.
- (c) of the text check is the inline card sentence of 78-6, recorded in its UI text section.

## Notes for design
- No Excel library (DEC-061). CSV is read as UTF-8 text in the browser and sent in batches (ADR-028 D12: at most 200 rows a request); the server checks every row. The file itself is never stored (no documents service). The import is staged: draft, submitted, approved.
- Owner approval of opening stock is the fixed rule "owner approves", not the approval-rule engine (ADR-028 D7).
- Posting to the ledger is the first SAH-79 story (OQ-78-34, DECIDED).
- Rates per issue unit, one import per store and the row limit are the recommended values (OQ-78-35, OQ-78-37, open; pricing: go-live item G-12).

---

### SAH-78-8 · As an owner, I want to keep a list of my vendors with their GSTIN, drug licence, credit days and return terms, and have a vendor with an expired licence blocked automatically, so that I never order from an unlicensed supplier by mistake
Priority: Must
Screen: P&I mockup `16-setup-stores-staff-products-vendors.html`, tab "Vendors": card "Vendors / suppliers" (button "+ Vendor", table columns Vendor, GSTIN, Drug licence, Credit, Products, Supplies to, Status chips Active and "Blocked · licence expired"; note "A vendor with an expired drug licence is blocked from new purchase orders automatically"; the "USP" chip is not built). Package WP-C. BRD M6, AC-M5.

## Acceptance criteria
List
- Given a person with access to Vendors (the owner, or a pharmacist or a Central Store user, a reader of setup) opens Vendors, then the table shows one row per vendor with: Vendor (name), GSTIN, Drug licence (number and "· till 31 Mar 2028", or "Not required (consumables)"; an expired licence shows "· expired 31 Oct 2026" in the warning colour), Credit ("30 days"), Products (a count, OQ-78-22, open), Supplies to (store names) and Status (Active, or "Blocked · licence expired").
- Given the screen is read-only for a pharmacist or a Central Store user, a reader of setup (no "+ Vendor" or edit), and another clinic's vendors are never shown (404 by id).
- Given no vendors yet, then "No vendors yet. Add the suppliers you buy from." with "+ Vendor" for the owner.

Add and edit
- Given the owner taps "+ Vendor", then the form asks Name, GSTIN, "Drug licence required" (Yes or No), Drug licence number, Drug licence valid till, Contact (name and phone), Credit days, Return policy (days before expiry that returns are accepted, and "Credit given on return, %"), "Credit for damaged on receipt" and "Credit after expiry" (both optional and NEW; they sit after the Return policy fields, DECIDED 19 Nov 2026; the mockup does not show them), Supplies to (stores, default the Central Store) in this order (field order NEW, OQ-78-3, DECIDED).
- Given Name empty, or the name already used by another vendor of this unit (ignoring capital letters), then 400 "Enter the vendor name" or 409 "That vendor is already in the list".
- Given "Drug licence required" is Yes and the licence number or date is empty, then 400 with a field message; given No, the licence fields are hidden and the list shows "Not required (consumables)".
- Given a GSTIN that is not 15 characters of the GSTIN pattern, then 400 "Enter a 15-character GSTIN" (format only; empty is allowed for a vendor without GST, shown "Not set"); licence number over 40 characters, credit days not a whole number between 0 and 365, return days not a whole number between 0 and 365, credit % not between 0 and 100 with at most 2 decimals, or a contact phone that is not a 10-digit number, then 400 with a field message (OQ-78-23, DECIDED).
- Given the owner saves a valid vendor, then it appears in the list with Status Active and an audit event; editing is allowed for every field and audited with before and after.
- Given "Supplies to", then only active stores of this unit can be chosen; a store that is switched off later is dropped from the list for new work.

Licence blocking
- Given a vendor whose licence valid-till date is before today (Asia/Kolkata), then the Status shows "Blocked · licence expired", and the vendor API marks it `blocked` so that purchase-order screens in SAH-79 can refuse a new PO; on the valid-till date itself it is still Active and from the next day it is Blocked, with no grace days (OQ-78-23, DECIDED; go-live item G-3).
- Given the owner edits the licence valid-till date to a future date, then the vendor is Active again at once and the audit records the change.
- Given a vendor with "Drug licence required" No, then it is never blocked for licence.
- Given a Blocked vendor, then it stays in the list, can be edited, and existing records of it are unchanged.
- Given the owner switches a vendor off (Switch off, NEW, with confirmation), then its Status is Inactive, it is not offered for new work or as a preferred vendor, and it is not deleted.
- Given a switched-off vendor (Status Inactive), when the owner switches it on again (Switch on, NEW, owner only; DEC-078 item 7), then its Status is Active again (or Blocked if its licence has expired), it is offered for new work and as a preferred vendor again, and the audit records the change; a non-owner gets 403 and another clinic's vendor 404. Reason: the vendor name is unique in the unit, so a switched-off vendor could otherwise not be re-added or reused. Switching on a vendor that is already active changes nothing.
- Given a product has a preferred vendor that becomes Blocked or Inactive, then the product's Local settings show "Preferred vendor needs review" (NEW).
- Given this story is built after 78-5, then the "Preferred vendor" select of Local settings now lists the active vendors of the unit.

Vendor credit levels (built now in SAH-78: contract 0.2.1, migration 1290; they are used by the vendor return of SAH-79-8)
- Given the vendor sheet, then besides "Credit given on return, %" (the standard credit) it has two optional fields, "Credit for damaged on receipt" and "Credit after expiry", each a percentage from 0 to 100 with at most 2 decimals (for example 100 or 37.5).
- Given a credit level that is below 0, above 100, has more than 2 decimals or is not a number, then 400 "Enter a % from 0 to 100" on that field, and nothing is saved.
- Given a credit level that is left empty, then no level is stored for it and the standard credit ("Credit given on return, %") applies to that kind of return line; a vendor created with neither level behaves as before.
- Given the owner edits a vendor, then the two levels can be set, changed or cleared like any other field, and the audit records before and after; a save that does not mention a level leaves the stored value unchanged.
- Given the vendor-return story (SAH-79-8), then a return line whose reason is damaged on receipt starts from "Credit for damaged on receipt", a line whose batch is expired starts from "Credit after expiry", and any other line starts from the standard credit; an empty level means the standard credit (DEC-078 item 5 and the tech lead decision of 18 Nov 2026; this story only stores and shows the levels).
- Given a return line that is both damaged on receipt and expired, then it starts from the lower of the two levels (DEC-078 item 5, amended 19 Nov 2026; go-live G-18, accountant); the mockup chip "Quarantine · damaged" means damaged on receipt. This is worked in SAH-79-8; this story only stores and shows the levels.
- Given a non-owner changes a level through the API, then 403; another clinic's vendor, 404 (as for every vendor field).

Roles and tenants
- Given a non-owner calls a vendor change API, then 403; given another clinic's vendor id, 404; two units may have the same vendor name.
- Given a slow or failed save, then the form keeps what was typed and shows the error; a double tap creates one vendor.

## Notes for design
- Drug licence, GSTIN and contact are business data: never logged. They may be in audit.
- "Products" count and "Supplies to" are the BA's reading of the mockup (OQ-78-22, open).
- The blocked flag is computed from the date on read, so no daily job is needed; the PO story (SAH-79) reads it.
- The "return policy" is stored here and used by the vendor-return story in Phase 2. The two credit levels are built in this story (not in SAH-79 WP-E): contract 0.2.1 fields `damagedCreditPercent` and `expiredCreditPercent` (optional, null means the standard credit applies), migration 1290 (columns `damaged_credit_pct` and `expired_credit_pct`, null when empty). This replaces the earlier note that moved them to SAH-79 WP-E (DEC-078 item 5, DEC-079 deviation 7); the build went ahead of that note.
- DECIDED 19 Nov 2026 (DEC-078 item 5, amended): a return line that is both damaged on receipt and expired starts from the lower of the two levels; the position of the two fields after the Return policy fields is decided. Go-live check G-18 (accountant).

---

### SAH-78-9 · As an owner, I want to set who approves what, up to which amount, and pick a ready preset, so that approval levels and limits follow my hospital's policy without any code change
Priority: Must
Screen: NEW screen, not in mockup (BRD M7: "New screen, same Setup tab"; BRD section 2). Context drawn in `17-end-to-end-flow.html` (column "Approval" per transaction), `05-compliance-roles-and-audit.html` (table "Role-based permissions & approvals": discount above 10%, stock adjustment up to ₹1,000) and `04-sales-return.html` (returns above ₹1,000). Accepted design (OQ-78-2, DEC-062 Q1 A): section "Approval rules" in Inventory setup. Package WP-C. The transaction type list (OQ-78-25) and the discount unit (OQ-78-27) are still open and are built on the recommended values below.

## Acceptance criteria
Rules per transaction type
- Given the owner opens Approval rules, then one row per transaction type shows (list per OQ-78-25, open: Purchase order, Receive stock (rate rise), Vendor return, Indent, Stock transfer, Write-off, Stock adjustment, Discount at sale, Sales return): the number of levels (0, 1 or 2), the role of each level (store approver or owner), the condition (always, above an amount, Schedule X item, high-value item, urgent priority, store type) and the ₹ limit where one applies.
- Given a level count of 0, then the transaction needs no approval; 1, then one approval by the Level 1 role; 2, then Level 1 and then Level 2; a rule with Level 2 filled but Level 1 empty is refused with 400 "Fill level 1 first".
- Given a limit, then amounts are ₹ with Indian grouping and paise when present (₹ 1,25,000) and a discount limit is a percentage (OQ-78-27, open); a limit that is negative, over ₹ 9,99,99,999 (9,999,999,900 paise; the limit of an amount condition is at most this, DEC-078 item 1) or not a number is refused with 400; an empty limit means "no amount condition".
- Given the transaction type "Receive stock (rate rise)" (`GRN_RATE_RISE`), then its condition is "Always" or "Above a percentage" only, and its limit is a percentage, like the discount at sale; a rupee amount is not offered for it and is refused with 400 (DEC-078 item 2). Sahaj ships no limits and no high-value threshold (OQ-78-26, DECIDED, DEC-062 Q4 A; go-live item G-4).
- Given a condition "above ₹ limit" with no limit set, when saved, then 400 "Enter the limit".
- Given escalation, then each row has "Escalate after N hours" (whole number 1 to 168 or empty) and, for Indent, the urgent time (30 minutes) as a separate value (defaults, DECIDED, OQ-78-29: indent 2 hours, urgent 30 minutes, transfer 24 hours); a note says "Escalation starts working when the approvals inbox is released" (NEW).
- Given the owner saves, then the rule is stored, audited with before and after, and applies to transactions created after the save only; a transaction already waiting keeps the rule it started with.
- Given the rule's role is "store approver", then the approver is the HOD (or active delegate) of the transaction's store; given a store with no HOD while a rule names the store approver, then the page shows "<store> has no approver" next to the row (NEW) and the save is still allowed.
- Given the delegate, then dates are set on the store (78-3); this page shows the stores that currently have a delegate and their end dates, read-only.

Presets
- Given the owner opens the preset selector, then Lean, Standard and Strict are offered with one-line descriptions (OQ-78-28, DECIDED, DEC-062 Q4 A; go-live item G-5): Lean (no HOD step), Standard (one level, HOD), Strict (HOD, then owner above a limit).
- Given the owner chooses a preset, then a confirmation says "This replaces all rows below. You can change them afterwards."; on confirm every row is set to the preset values; a preset that needs a ₹ limit (for example the owner step above a limit) asks the owner to enter it before it can be applied, and the owner may choose "no amount condition"; Sahaj supplies no default amounts (OQ-78-26, DECIDED).
- Given the owner opens a preset, then the server says, for each preset, which transaction types need a limit (for example Strict means a limit for every type that has an amount), and the screen asks for exactly those limits; the screen does not work out the list itself (DEC-078 item 3). A limit missing for a type the server names is refused with 400 `limits.<TYPE>`.
- Given the rules are changed by hand after a preset, then the selector shows "Custom".
- Given a new unit, then the rules start as the Standard preset with empty limits, and a banner says to review them before use.

Fixed rules
- Given any state of the page, then three fixed rules are listed and cannot be edited: "Nobody approves their own request.", "Schedule X needs a pharmacist and a second signature.", "The stock ledger cannot be edited; corrections are reversing lines." (BR-24). No setting, preset or API call switches them off (an attempt to send a field for them answers 400). Who the second signer is, is left to SAH-80 (OQ-78-30, DECIDED; go-live item G-6).
- Given the "Schedule X item" condition, then it can add a level but cannot remove the pharmacist-and-second-signature rule.

Roles, tenants, errors
- Given a non-owner, then the page is read-only for a pharmacist or a Central Store user (a reader of setup) and 403 on change for everyone else; another clinic's rules, 404.
- Given two owners save at once, then the second gets 409 `rules-changed` with the current rules, and the screen re-reads the rules (DEC-078 item 8).
- Given the mockup 05 table "Role-based permissions & approvals" (the role matrix, a read-out of what the engine enforces; DEC-074 OQ-80-38), then it is not built in this release: it is a "USP" chip in the mockup and carries no contract data. It can be added with SAH-80 (DEC-078 item 9). This page keeps the rules, presets and fixed rules only.
- Given a failed save, then what was typed is kept and the error is shown; a rule is never half-saved (one save for the page's changed rows).

## Notes for design
- The page stores configuration only; enforcement comes with each transaction in SAH-79 and SAH-80, and the enforcement reads this data (never code constants). Limits are held to the paisa.
- The list of transaction types and the role choices are the recommended values (OQ-78-25, open). ADR-028 D7 differs slightly (it adds opening stock and a pharmacist-in-charge approver kind and omits "Receive stock" and "Vendor return"); the architect and human settle the list. Owner approval of opening stock is a fixed rule and is not set on this page.
- The "Strict" and "Standard" meanings are compliance choices accepted by the human (OQ-78-28).
- Escalation values are stored but not acted on until a later timer story (BRD I9 lists escalation timers as Later).

---

### SAH-78-10 · As an owner, I want to set how documents are numbered, which reasons staff can choose, and how printed documents look, so that numbers are orderly, reasons are the same everywhere and printouts carry the right details
Priority: Must
Screen: NEW screen, not in mockup (BRD, "Also in Phase 1: document numbering ..., reason lists ... and print templates ..."). Reason wording is taken from `04-sales-return.html` (sales return reasons), `11-store-stock-and-ward-consumption.html` (return and damage reasons) and `13-physical-stock-audit.html` (adjustment reasons); bill number style from `05-compliance-roles-and-audit.html` ("SB/018422"). Accepted design (OQ-78-2, DEC-062 Q1 A): section "Numbering and documents" in Inventory setup (numbering series, reason lists, print templates). Package WP-C. The reason list names (OQ-78-32) are still open and are built on the recommended values below.

## Acceptance criteria
Numbering
- Given the owner opens Numbering, then one series is shown for each of PO, GRN, indent, transfer and bill, each with Prefix, Financial year part (on or off), Sequence length, "Restarts every 1 April" (Yes or No) and a live example (for example "SB/26-27/000001"); there is one series per unit and document type, and no separate bill series per store (OQ-78-31, DECIDED, DEC-062 Q4 A).
- Given the owner edits a series, then Prefix may be 1 to 6 letters, digits, hyphen or slash, Sequence length 4 to 8, and the full example must be 16 characters or fewer (OQ-78-31); anything else is refused with 400 and a field message.
- Given a series is saved, then the change applies to numbers created after it; numbers already issued are never changed, reused or skipped by a format change; the audit holds before and after.
- Given any number of the current financial year has been issued from a series, when the owner tries to change the series, then the answer is 409 `series-locked` "Numbers have already been issued this year." and nothing changes; the screen shows the series read-only with that note (DEC-078 item 4; this replaces the earlier rule "only the prefix, and only from the next financial year", ADR-028 D8). The lock follows the financial year, which starts on 1 April 00:00 IST: a number issued before that moment belongs to the previous year and does not lock the series, and on 1 April the series is open again until its first number of the new year is issued.
- Given no number of the current financial year has been issued from a series, then the prefix and the format can be changed, so a new prefix starts at the first number of a new financial year, and each series stays consecutive (DEC-078 item 4: there is no "from next year" field).
- Given the first number of a series, then it is issued only when a document is saved in Phase 2 or 3; this story issues no document numbers, but stores the series and exposes a way to take the next number safely: the counter is kept per unit, document type and financial year (April to March, IST), the number is taken in the same step that saves the document, two users saving at the same moment get different numbers, a save that fails does not use up a number (no gaps for saved documents), and a cancelled document keeps its number.
- Given a financial year starts on 1 April, when the series restarts every year, then the first document of the new year gets the first number of the new counter; when it does not restart, the counter continues.
- Given two series with the same prefix and no financial-year part for different document types, then 409 "Two document types cannot use the same prefix" (NEW).
- Given a non-owner, then the series are read-only for a pharmacist or a Central Store user (a reader of setup) and 403 on change for everyone else; another clinic's series, 404.

Reason lists
- Given the owner opens Reasons, then the lists the server returns show as chips, and the screen does not assume how many there are (DEC-075 item 7). After SAH-79 there are 11 lists, with these labels: Receipt rejection, Central Store change, Approval rejection, Stock transfer, Consumption purpose, Dispute, Vendor return, and the four lists of this story (the next criterion). The seeds of the seven SAH-79 lists belong to SAH-79; this story only shows whatever lists exist.
- Given the owner opens Reasons in this release, then the four lists of this story show as chips (OQ-78-32, open): Store return (Excess stock, Near expiry, Not needed (order stopped)), Sales return (Doctor changed medicine, Excess bought, Wrong item given, Adverse reaction), Damage (Damaged / leaking, Expired, Cold-chain break) and Stock adjustment (Consumption not entered, Breakage, Unknown); the seeded reasons are in every new unit.
- Given the owner adds a reason (1 to 40 characters, not already in that list ignoring capital letters), then it appears as a chip at the end of that list; a blank or repeated reason is refused with 400 or 409.
- Given the owner switches a reason off, then it is not offered for new work but stays on old records; it can be switched on again; no reason is deleted and seeded reasons cannot be renamed.
- Given the Damage list, then at least one active reason must remain; switching off the last one is refused with 409.
- Given a reason that is fixed (the fixed reason `receipt-reject.short`), when the owner tries to switch it off, then it is refused (the status code is the contract's) and it stays active; the server marks which reasons are fixed (a `fixed` flag on the reason), the screen does not offer a switch-off for them and does not recognise this reason by its key (DEC-078 item 6).
- Given another clinic's lists, then they are independent; a non-owner gets 403 on change.

Print templates
- Given the owner opens Print templates, then three templates are shown: Invoice, Issue slip and Debit note, each with Header lines (the unit name from the clinic profile, the address, the phone), a Footer note (up to 200 characters), Paper size (A4 or A5) and a preview.
- Given the Invoice template, then the fields GSTIN, drug licence number, HSN, batch, expiry, MRP and GST amounts are shown on the preview as fixed and cannot be hidden (OQ-78-33, DECIDED, DEC-062 Q4 A; go-live item G-8); GSTIN and licence number are taken from Unit settings (78-2); if either is empty the preview says "Set GSTIN in Unit settings" and the owner is told that invoices cannot be printed until it is set (NEW).
- Given the owner taps Print preview, then the browser print view opens with the template filled with sample values marked "Sample"; no file is generated by the server (DEC-061 default).
- Given a header line or footer longer than its limit, or a paper size other than A4 and A5, then 400 with a field message.
- Given a saved template change, then it applies to documents printed afterwards; documents already printed are not changed; the audit holds before and after.
- Given the Debit note and Issue slip templates, then they carry the unit name, the document number (when issued), the date as "24 Oct 2026" and the footer; the mapped legal fields for each are set by the human (OQ-78-33, go-live item G-8).

## Notes for design
- Contract point for the architect (tech lead note of 19 Nov 2026): `numbersIssued` in `GET /document-series` is described as "a number of the type was taken" (any year), while the lock is about the current financial year; the description must say "issued this financial year" (or a new field). The criterion above is about the current financial year.
- Bill numbering and invoice fields are a legal matter (GST); the accountant confirms before the bill series or invoice template is used for real billing (OQ-78-31, OQ-78-33; go-live items G-7, G-8).
- The next-number take must be race-safe and gapless for saved documents (ADR-028 D8, `INSERT ... ON CONFLICT` counter in the document's own transaction); the architect designs it.
- Reason lists are used later in Phase 2 and 3 as chips; the indent rejection reasons and consumption purposes are not here (OQ-78-32). ADR-028 names the lists differently; the contract aligns them.
- ADR-028 data table has a footer of up to 300 characters and show-flags; this story keeps 200 characters and fixed required fields until the architect and human settle it.
- Print uses the browser print view; no PDF library, WhatsApp or e-mail sending.

---

## Change log
- 17 Nov 2026: first version; touch-up with the answers of DEC-062 and DEC-063.
- 18 Nov 2026: aligned with `docs/api/pharmacy.yaml` 0.1.1 (DEC-064), DEC-068 and code review F-1, F-11, F-12. No scope change.
  - Reader of setup: a pharmacist (mapped or not) or a Central Store user reads masters (SETUP_VIEW). An unmapped pharmacist gets 200 with `stores: []`. There is no Pharmacist store role; Schedule X receive follows team role PHARMACIST (78-1, 78-3, 78-4, 78-5, 78-7 to 78-10 and the common rules).
  - 78-1: entry for the owner, any pharmacist or anyone with a mapping; Store staff with no store lands on /app/pharmacy (F-1) and sees no other menu item, including HRA `staff` (DEC-068 Q2 A); new text "You do not have access to Inventory setup. Ask your owner."
  - 78-2: Integration mode default Integrated is DECIDED (DEC-064 R2-2); `integration-off` is kept, not reachable in Phase 1.
  - 78-3: `GET /stores` lists all stores for SETUP_VIEW (a pharmacist sees store names, staff names, GSTIN and licence); unknown or foreign id gives 404; delegate at most 90 days counting both ends (DEC-068 Q1 A); the first Central Store can choose Approver and Delegate at create (F-11); code-in-use 409 text.
  - 78-4: Pharmacist removed from the store roles; delegate 90-day rule; 404 for an unmapped or foreign store and 403 `store-permission-denied` for a mapped caller without the capability; unmapped owner or pharmacist message; "Needs review" counts as Store user; the POST /team versus screen decision (OQ-78-40(b)).
  - 78-7: 404 and 403 wording; `outside-store` dropped.
- 18 Nov 2026 (wording, no scope change): current-contract citations changed from `docs/api/pharmacy.yaml` 0.1.1 to 0.1.2 (wording-only release, ADR-028 revision 4); the 0.1.1 lines in the touch-up note and in the entry of 18 Nov above are history and are unchanged. 78-5: a barcode matches exactly (ignoring capital letters); name, salt, alias and code match as a part of the text. 78-7: batch numbers in "the same store, product and batch twice" compare ignoring capital letters; store and product codes ignore capital letters and leading or trailing spaces. Acceptance criteria otherwise unchanged.
- 18 Nov 2026 (wording check, no scope change, DEC-070 item 10): UI text of the NEW Opening stock screen added to 78-7 and the "Save anyway" label to 78-6, all marked NEW, with the open points listed under each. Acceptance criteria unchanged.
- 18 Nov 2026 (review round 2, DEC-071; code review N-9, N-2, N-11; security I5):
  - N-9: common rule "Order of answers" added (unreadable body 400 right after the id check, before the 404; field checks stay after the 404), with one AC each in 78-4 and 78-7.
  - N-2, DEC-071 Q1 A (decided automatically while the human was away; the human confirms at the merge ask): 409 `open-import` "Finish or reject the opening stock file of this store first." while a store or product is on an opening stock file that is a draft or waiting for approval. New AC in 78-3 (store switch-off, code), 78-5 (product switch-off), 78-6 (switch-off, units, conversion) and a cross-reference in 78-7. The code `open-import` is an additive contract change (pharmacy.yaml 0.1.3, architect).
  - Text check (UI text sections of 78-6 and 78-7): the four texts are all OK, none replaced; the Waiting-for-approval-only limit on "File sent for approval" is flagged.
  - Raised, not decided: OQ-78-42 (how an abandoned draft is closed).
- 18 Nov 2026 (DEC-075 to DEC-078, after security and code review round 3; wording and criteria, no scope change beyond what is listed; decided automatically, the human confirms at the merge ask):
  - 78-7 (DEC-076): an owner who may approve at the store may reject a DRAFT as well as a waiting file, with a reason from the list; the uploader can still replace their own draft; a rejected file frees the store and its products at once. OQ-78-42 is DECIDED (DEC-076, auto).
  - 78-7 (DEC-077): the cap on line sends counts changed rows written (at most twice the row count; unchanged requests do not count); at most 10 new files per person per store per IST day (the 11th answers 409 "Too many files today. Try again tomorrow."); `start`, `submit` and `approve` refuse (404) a switched-off store.
  - 78-3 (DEC-077): a store switched off in the meantime cannot get a new file (the DRAFT or WAITING block of DEC-071 is unchanged).
  - 78-8 (DEC-078): a switched-off vendor can be switched on again (owner only); the two vendor credit levels move to SAH-79 WP-E (OQ-79-11).
  - 78-9 (DEC-078): amount limit at most ₹ 9,99,99,999; `GRN_RATE_RISE` takes a percentage only; the server says which transaction types need a limit per preset; the role matrix of mockup 05 is not built (DEC-074 OQ-80-38 read-out postponed).
  - 78-10 (DEC-078): the series is locked once any number of the current financial year has been issued (409 "Numbers have already been issued this year."); before that the prefix and format can change (replaces "only the prefix, from the next year"); the fixed reason `receipt-reject.short` cannot be switched off and the server says which are fixed; the screen shows the lists the server returns (11 labels after SAH-79).
  - Common rules: after the cap a request with a bad body answers 409, not 400 (state check first); the "Order of answers" rule is otherwise kept.
  - Requirement: `docs/requirements/SAH-78.md` (BR-12, BR-20, BR-22, BR-25, BR-26, new BR-35, OQ-78-42).
- 19 Nov 2026 (DEC-061 programme, touch-up after package C and the hardening round; wording and criteria, no scope change):
  - 78-8: the two vendor credit levels are built now in SAH-78 (contract 0.2.1, migration 1290), not in SAH-79 WP-E. New fields "Credit for damaged on receipt" and "Credit after expiry" (each 0 to 100, 2 decimals, optional; an empty level means the standard credit "Credit given on return, %" applies) with criteria for range, empty, edit, audit and roles; the earlier criterion and the note that moved them to WP-E are replaced. The 18 Nov entry above that says they move to WP-E is history. Which level a return line starts from is stated for SAH-79-8.
  - 78-10: the lock is stated with its code `series-locked` and with the financial year that starts on 1 April IST; contract point on `numbersIssued` noted for the architect.
  - Header: open questions counted OQ-78-1 to OQ-78-42 (OQ-78-42 DECIDED, DEC-076).
- 19 Nov 2026 (DEC-078 item 5 amended; wording, no scope change): 78-8: a return line that is both damaged on receipt and expired starts from the lower of the two vendor credit levels (new criterion and the open note replaced); the two new fields sit after the Return policy fields on the vendor sheet (decided; they stay NEW because the mockup does not show them); the mockup chip "Quarantine · damaged" means damaged on receipt.
