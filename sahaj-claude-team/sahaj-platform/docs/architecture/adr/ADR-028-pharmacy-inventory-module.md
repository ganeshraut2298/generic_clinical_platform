# ADR-028 · Pharmacy & Inventory module: boundary, tenancy, global catalogue, store-scoped access, integration adapter, ledger direction, approval engine, numbering

Story: SAH-78 (Phase 1 Masters). Sets direction for SAH-79 (Inventory) and SAH-80 (Pharmacy).
Mockup screens: P&I mockup accepted as a design addition (DEC-061 Q1); split copies `docs/handoffs/PHI-input/screens/01..18`. Phase 1: screen 16 "Setup: stores, staff, products, vendors", plus the BA section layout for unit settings, approval rules, numbering / reasons / print templates and opening stock (DEC-062 Q1, NEW).
Module: `pharmacy` (new). Reads platform, patient, opd, ipd and billing only through their `api` packages.
Status: **ACCEPTED** (17 Nov 2026, DEC-063 Q1: all OQ-PHI recommendations accepted as written, with D4 revised for the Store staff team role of DEC-062 Q3). This is **revision 2** (17 Nov 2026), written with the contract `docs/api/pharmacy.yaml` 0.1.0. Revision 2 changes: D4 (Store staff role, store roles, capability table, two access kinds), D5 (default mode), D7 (rule shape), D8 (doc types), D11 (menu rule), D12 (opening-stock flow), a new section "Additive-only changes to existing files", the final Phase 1 data list, and the open-question table (answered). **Revision 3** (18 Nov 2026, after the WP-A code and security reviews, DEC-068) is additive: it records decisions taken during WP-A and the review fix round. It is in the section "Revision 3" before Consequences. Contract `pharmacy.yaml` 0.1.1. **Revision 4** (18 Nov 2026, after the WP-B build) is wording only: it records ten WP-B choices the tech lead accepted as defaults. It is in the section "Revision 4". Contract `pharmacy.yaml` 0.1.2. **Revision 5** (18 Nov 2026, after the round-2 code and security reviews, DEC-071) amends R3-3 (the denial audit is written after the caller's transaction ends) and adds the open-import rule, the line-request cap and two recorded limits. It is in the section "Revision 5". Contract `pharmacy.yaml` 0.1.3 (additive). **Revision 6** (18 Nov 2026, SAH-79) is a pointer: the Phase 2 runtime is decided in ADR-029 (proposed), contract `pharmacy.yaml` 0.2.0 (additive). **Revision 7** (18 Nov 2026, after the round-3 reviews; DEC-076, DEC-077, DEC-078) amends R5-2 and R5-3 to what is built (store lock first, product locks, the cap by rows written, the starts limit), lets an owner reject a draft and records the masters contract gaps. It is in the section "Revision 7". Contract `pharmacy.yaml` 0.2.1 (additive or wording only). SAH-80 (Phase 3, Pharmacy sales) is decided in ADR-030.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect.
Related: DEC-061 (programme, Answer 2 additive-only rules), DEC-062 (masters open points), DEC-063 (sign-off), DEC-017 and DEC-049 (PHARMACIST role), ADR-002 (tenancy, audit, D1 branch as a column, D6 scope vs RLS, D7 audit port), ADR-010 and ADR-014 (staff PIN, colleague PIN), ADR-016 D3 (consumer-declared port), ADR-017 (single scope guard), ADR-022 D1 (module graph), ADR-027 (IPD orders), `docs/architecture/PHI-feasibility.md` (evidence, estimates).

---

## Context

1. DEC-061 accepted the P&I mockup and BRD as a design addition, outside the original phase-1 screen map. It also fixed the module shape (one module `pharmacy`) and the product master (P&I owns it; `opd.medicine` and `ipd.drug` unchanged; link in Phase 3). DEC-061 Answer 2: the work happens on the current branch `feature/SAH-39-home-kinetic-background`, and every change to existing code is additive only.
2. The BRD asks for: "organization → unit" isolation; a global product catalogue shared read-only; access per store, not per user, with a working-store switch; four stock states and an append-only ledger; configurable approvals with fixed rules; numbering per unit; one adapter that a unit-level Integration mode switches off.
3. Built facts that constrain the answer (evidence in the feasibility report, sections 1 and 3):
   - The tenant is one clinic or hospital (`platform.tenant`), bound per transaction with `SET LOCAL`, with fail-closed RLS.
   - Branch was designed as a column, never an RLS key, and was never built.
   - Global reference data follows the `ipd.drug` pattern.
   - The module graph is a strict DAG checked by `ModularityTests`.
   - There is no durable event registry.
   - Counters use `INSERT … ON CONFLICT … RETURNING`.
   - Money is `bigint` paise.
   - `PHARMACIST` already exists as a membership role. New membership roles have been added before by replacing the check constraint in a new migration (`V2026_11_01_1019__platform_membership_add_counsellor_role.sql`).
4. Phase 1 limits still hold: no gateway, notifications, documents service or analytics. No new library, framework or service without the human.

---

## Decisions

### D1 · Module boundary and dependency direction **[BOUNDARY]** (accepted)

- New Spring Modulith module `com.sahaj.pharmacy`: schema `pharmacy`, base path `/api/v1/pharmacy`. Sub-packages: `api` (empty in Phase 1), `web`, `application`, `domain`, `infrastructure`, and `integration` (the adapter of D5, internal).
- Inside the module, features are grouped by package (`masters`, `catalogue`, `approval`, `numbering`, `openingstock`; Phase 2 adds `stock`, `purchase`, `indent`; Phase 3 adds `sale`, `dispense`, `register`).
- **Edges allowed:** `pharmacy → platform.api, patient.api, opd.api, billing.api, ipd.api`. **No module depends on `pharmacy`.** Phase 1 uses `platform.api` only (`TeamMemberPort`, `ModuleEntitlementPort`, `AuditLog`, clinic profile read).
- When an older module must ask pharmacy something, the older module declares the port in its own `api` package and pharmacy implements it (precedent `IpdOccupancyPort`, ADR-016 D3; `EmrIpdOrderPort`, ADR-025 D8). Phase 1 has one: `platform.api.PharmacyAccessPort` (menu, D11). Phase 3 may add `ipd.api.DischargePharmacyClearance` (T7, only if the human allows the IPD change).
- New ports the other modules provide (Phase 3, each with its own story): `opd.api.PrescriptionQueueReader` (T2), `ipd.api.PharmacyOrderFeed` (T4), `ipd.api.PharmacyChargePort` (T5), `ipd.api.AdmittedPatients` (T6), `billing.api.ExternalBillLinePort` (T8).
- Build checks (java-developer, `ModularityTests`): (a) no class outside `com.sahaj.pharmacy..` depends on `com.sahaj.pharmacy..`; (b) `com.sahaj.pharmacy..` depends on other modules only through `..api..`; (c) every `@Table` in `com.sahaj.pharmacy` declares `schema = "pharmacy"`.
- Options considered: pharmacy below ipd/billing (rejected: reverses the DAG, makes IPD and billing depend on an optional module); events in both directions (rejected: not durable without a new library; writes must be atomic, D5).

### D2 · Tenancy: the tenant stays the organization row; a unit is a pharmacy row, not an RLS key **[TENANCY]** (accepted)

- RLS key unchanged: `tenant_id = platform.current_tenant()`, `NOT NULL DEFAULT`, `ENABLE` + `FORCE`, per-command policies for `sahaj_app`, verified as `sahaj_app` (ADR-002 D3).
- `pharmacy.unit` holds the unit settings. **Exactly one unit per tenant**, enforced by `unique (tenant_id)`. Created lazily in the transaction of the first master write; defaults are served until then (as `platform.module_entitlement`).
- Every unit-owned table carries `unit_id` as well as `tenant_id`, so a later multi-unit release is a constraint drop plus an application filter. `tenant_id` stays first in every index. `unit_id` is in no RLS policy.
- Organization → unit isolation: between tenants by RLS (unchanged); inside a tenant by the store guard (D4).
- The unit code (three characters, used in own-product codes `U-<code>-nnnn`, mockup `U-BAN-0042`) is derived once from the clinic web address (first three letters or digits, upper case, padded with `X`) when the unit row is created, and is read-only in Phase 1. Uniqueness across tenants is not needed (codes are per unit).
- If platform later builds branches (ADR-002 D1), `pharmacy.unit` gains `branch_id` 1:1.

| Option | Verdict |
|---|---|
| A · Unit = tenant (each unit its own tenant row) | Rejected long-term: multi-branch command center and branch transfer (screens 01, 12) become cross-tenant operations, which RLS forbids |
| **B · Tenant = organization row; unit = pharmacy row + column; one unit per tenant now (chosen)** | No change to RLS, auth or platform. Additive path to several units |
| C · Second RLS key `app.unit_id` | Rejected: second trusted session setting, contradicts ADR-002 D1 |

### D3 · Global product catalogue: shared, read-only, outside tenant RLS (accepted)

- `pharmacy.catalogue_product` and `pharmacy.catalogue_barcode` are global reference data on the `ipd.drug` pattern: no `tenant_id`; RLS `ENABLE` + `FORCE` with `FOR SELECT TO sahaj_app USING (true)` and a `sahaj_migrator` policy; `REVOKE ALL` from `sahaj_app` and `PUBLIC`, then `GRANT SELECT` to `sahaj_app`; deny trigger on `DELETE` and `TRUNCATE`; a withdrawn product is `active = false`, set by migration. **The global tables sit in their own migration file.**
- Global fields: code (`G-nnnnnn`), name, salt, strength, form, manufacturer, HSN, GST rate, schedule (`NONE`, `H`, `H1`, `X`), NDPS flag, purchase unit, issue unit, conversion, barcodes, active, `sample`.
- **Sample seed (DEC-062 Q2):** about 30 products from the mockup rows with fake manufacturer names, `sample = true`, commented "not clinically validated", **never in production**. The seed is a separate migration whose inserts are guarded by a Flyway placeholder (`${sahajSampleData}`, default `false`; set `true` only in the `dev` and `test` profiles by java-developer). No new library. The real catalogue is a later story once the human names a licensed source and a pharmacist reviews it.
- A unit's product list is one tenant table, `pharmacy.product`: **adopted** rows (`catalogue_product_id` set, global fields null) and **own** rows (`catalogue_product_id` null, own copy of the global field set); a check constraint makes the kinds exclusive; `unique (tenant_id, unit_id, catalogue_product_id)` prevents adopting twice. Global fields are never copied, so a catalogue correction reaches every adopter at once.
- Reads join global and unit rows in the repository. No database view unless `WITH (security_invoker = true)`.
- Duplicate check (OQ-78-17, decided): normalised key of **salt + strength + form** (trimmed, lower case, spaces collapsed, strength units normalised, for example "1 g" = "1000 mg"), computed in Java, stored as `dup_key` in both tables and indexed. Catalogue matches first, then the unit's own products. The answer is advice: `409 possible-duplicate` with candidates unless the request says `ignoreDuplicates`; the audit records the candidate codes.
- Promotion of an own product to the catalogue: not built in Phase 1 (OQ-78-18); the mockup line "Own products can be sent for review" is not shown. `review_state` is dropped from the Phase 1 data.

### D4 · Identity and store-scoped access **[AUTH]** (revision 2: Store staff team role)

**What changed in revision 2.** Revision 1 said "no new platform role". DEC-062 Q3 adds one team role, **Store staff** (`STORE_STAFF`), for store assistants and store keepers (mockup 16 "Alok K. · Store assistant"), so that they need not be given a clinical role and its menus. Approver, Delegate and Central Store user stay **store roles** in the mapping, not team roles. Unit admin = OWNER; Cashier = BILLING (Phase 3); Pharmacist = PHARMACIST.

**Store staff team role.**
- New value `STORE_STAFF` in `MembershipRole`, the `ck_membership_role` check and `platform.yaml` `Role` (label "Store staff"). No existing role changes meaning; no permission key or session field is added.
- By itself it grants nothing. Every existing role gate in every module is an allow-list that does not name it, so OPD, IPD, billing, HRA and clinic setup answer 403 as for any role they do not name. QA adds a test that a Store staff session gets 403 on a sample of existing endpoints and an empty menu while `pharmacy_on` is false.
- Its menu has no base keys; it gets `pharmacy` only when the module is on and it has an active mapping (D11). An unmapped Store staff member signs in to an empty menu; the shell must show a "no access" page (angular-developer, handoff).
- It may be mapped only as `STORE_USER` or `CENTRAL_STORE_USER`, never `APPROVER` or `DELEGATE` (OQ-78-6 accepted), and never gets Schedule X.
- It counts against the plan user limit like every member.
- `role_permission` and `role_permission_default` are not changed: `GET /role-permissions` never lists it; a `PUT` row for it is refused with 400 by the service.

**Three layers, checked in this order on every request** (inside the transaction; the membership is re-read through `TeamMemberPort`, so a switched-off member loses access at once):

1. Module entitlement `pharmacy_on` (D11). Off: `403 module-off`.
2. Access level of the operation, from the team role plus the caller's active mappings:

| Level | Who | Used by |
|---|---|---|
| `SETUP_EDIT` | OWNER | every master write; opening-stock approve and reject |
| `SETUP_VIEW` | OWNER, any PHARMACIST member, any member with an ACTIVE `CENTRAL_STORE_USER` mapping (OQ-78-8) | master reads (stores, staff, products, vendors, unit settings, rules, series, reasons, templates) |
| `PHARMACY_ACCESS` | OWNER, any PHARMACIST member, any member with at least one ACTIVE mapping to an ACTIVE store | `/context`, working store, opening-stock template, store operations |
| none | everyone else (front desk, counsellor, visiting doctor, nurse, billing or Store staff without a mapping) | `403 forbidden` "You do not have access to Pharmacy & Inventory. Ask your owner." |

3. **`StoreAccessGuard`** (one class, pattern of ipd `ScopeGuard`, ADR-017), for operations marked `x-store-access: OPERATION`, which act inside the store in the `{storeId}` path:
   - unknown id, other tenant's store, switched-off store, or a store the caller is not mapped to (OWNER excepted): `404 not-found` (does not confirm the store; DEC-063 Q1);
   - mapped but the needed capability is missing: `403 store-permission-denied`, audited `PHARMACY_STORE_ACCESS_DENIED` (ids only);
   - the guard runs before any filter, sort or paging parameter; the server never trusts a "current store" from the client.

   Operations marked `x-store-access: MASTER` (store detail, staff mapping) read or change the store as a master record: gated by `SETUP_VIEW` or `SETUP_EDIT`, never by a mapping. A convention test checks that every handler under `/stores/{storeId}/**` declares one of the two kinds and that OPERATION handlers call the guard.

**Store roles (Phase 1):** `STORE_USER`, `CENTRAL_STORE_USER` (Central Store only), `APPROVER` (the store's HOD, at most one active per store), `DELEGATE` (with `delegate_from` and `delegate_to`, IST dates, at most 90 calendar days counting both ends (revision 3, R3-6), not the HOD, at most one active per store). Revision 1's store roles `PHARMACIST` and `CASHIER` are dropped from Phase 1:
- pharmacist powers come from the team role PHARMACIST plus any mapping;
- `CASHIER` is added additively in SAH-80.

The store's HOD and delegate shown on the store card are these mapping rows. Setting a new HOD or delegate on the store turns the previous one into `STORE_USER` (kept, audited). A delegate outside its dates counts as `STORE_USER` ("Delegate · ended 30 Nov").

**Who may hold which store role** (checked at mapping time and again by the guard; a mapping that no longer fits the member's current team role is flagged `needsReview` and counts as `STORE_USER`):

| Team role | Store roles allowed |
|---|---|
| OWNER, DOCTOR, NURSE, PHARMACIST | STORE_USER, CENTRAL_STORE_USER, APPROVER, DELEGATE |
| BILLING, STORE_STAFF | STORE_USER, CENTRAL_STORE_USER |
| VISITING_DOCTOR, FRONT_DESK, COUNSELLOR | STORE_USER |

**Capabilities**, derived on every read (never stored per person, never sent by a client; mockup 16 columns):

| Effective store role | Raise indent | Approve | Receive | Consume / return | Upload opening stock |
|---|---|---|---|---|---|
| APPROVER, current DELEGATE | Yes | Yes | Yes | Yes | only if PHARMACIST team role |
| STORE_USER | Yes | No | Yes | store's Consumption entry | only if PHARMACIST team role |
| CENTRAL_STORE_USER | No | No | Yes | store's Consumption entry | Yes |
| OWNER (no mapping needed, every store) | Yes | Yes | Yes | Yes | Yes |

The table has three modifiers:
- **Receive Schedule X** ("Yes · incl. Schedule X") = Receive **and** team role PHARMACIST. Never for OWNER (pharmacist-only actions stay out of the owner's reach, as screen 05) and never for STORE_STAFF.
- **Approve** for opening stock is not a store capability. It is the fixed rule "a different OWNER approves" (D7).
- A member whose team access is switched off has no capabilities. The row shows "Cannot sign in".

**Working store.** The client keeps the selected store. The server remembers the last one per member in `pharmacy.staff_preference` for convenience only. `GET /context` lists the caller's mapped stores (the owner's select also lists mapped stores only). `PUT /context/working-store` saves the preference and answers 404 for an unmapped store. Every store operation still checks its path store.

Fixed rule "nobody approves their own request": enforced in the service **and** by a check constraint on the decision (for opening stock: `approved_by <> uploaded_by`).

Options considered: a platform role per store role (rejected: one membership has one role, and it changes auth for all modules); store scope in RLS (rejected for the same reason as ward scope, ADR-002 D6); no new role, existing roles only (DEC-062 Q3 B, not chosen by the human).

### D5 · Integration adapter and the Integration mode switch (accepted; default revised)

- Internal interface `com.sahaj.pharmacy.integration.ClinicalLink` with `IntegratedClinicalLink` (calls the ports of D1) and `StandaloneClinicalLink` (returns "not used", never throws).
- `ClinicalLinkResolver` chooses per request from `pharmacy.unit.integration_mode` (`INTEGRATED` | `STANDALONE`) and `ModuleEntitlementPort`. IPD off or read-only removes T4-T7 even when Integrated.
- **Default `INTEGRATED`** (revision 2, following OQ-78-14 and story 78-2; revision 1 said STANDALONE). In Phase 1 the two are equivalent because no integrated feature exists. Listed for the human's confirmation in the open points below.
- Application services depend only on `ClinicalLink` and a `Capabilities` value. Review rule: no mode check outside the resolver.
- `GET /api/v1/pharmacy/context` returns the mode, `capabilities[]`, the unit and the caller's stores. Angular shows only what is in `capabilities` (hidden, never greyed).
- An Integrated-only operation in Standalone answers `409 integration-off` (`components/responses/IntegrationOff`). No 0.1.0 operation is Integrated-only; the response is defined for reuse.
- Writes back (charge, credit, dispense status) are synchronous port calls in pharmacy's transaction. No events for writes.
- Port failure: the operation continues in standalone style when safe, fails cleanly otherwise. A bill keeps `link_state` (Phase 3).
- Mode change: OWNER only, audited `PHARMACY_INTEGRATION_MODE_CHANGED`. Phase 3 adds "refused while integrated documents are open".

### D6 · Stock ledger direction (built in Phase 2; fixed now so Phase 1 keys fit) (accepted)

- `pharmacy.batch` (tenant, unit, product, batch no., expiry, MRP, purchase rate per issue unit, vendor, received at). Unique `(tenant_id, product_id, batch_no, expiry)`.
- `pharmacy.stock_ledger`, append-only:
  - columns: occurred_at, transaction type, document type and id, line id, store, state, batch, product, `qty_in`, `qty_out` (issue units, integer), balance after, actor, reason key, `reverses_ledger_id`;
  - `GRANT SELECT, INSERT` only; deny triggers on update, delete and truncate;
  - range-partitioned by month;
  - corrections are reversing lines.
- States `AVAILABLE`, `IN_TRANSIT`, `PENDING_RETURN`, `QUARANTINE`; only `AVAILABLE` is usable.
- `pharmacy.stock_balance` is a projection:
  - updated in the same transaction as the ledger line;
  - rows are locked in a fixed order;
  - `check (qty >= 0)`.
- FEFO at pick time; expired batches are blocked and quarantined by the reader that finds them.
- Quantities are whole issue units; purchase quantities are converted at GRN.
- **Opening stock approved in Phase 1 is posted by the first SAH-79 story** (OQ-PHI-9, accepted). The Phase 1 lines carry everything the posting needs (store, product, batch, expiry, MRP, rate, quantity).

### D7 · Approval engine shape (rules in Phase 1; runtime in Phase 2) (accepted; shape revised)

- Transaction types (OQ-78-25): `PURCHASE_ORDER`, `GRN_RATE_RISE`, `VENDOR_RETURN`, `INDENT`, `STOCK_TRANSFER`, `WRITE_OFF`, `STOCK_ADJUSTMENT`, `SALE_DISCOUNT`, `SALES_RETURN`. Opening stock is not a configurable type: it has the fixed rule "a different OWNER approves" (OQ-78-36).
- Data per unit:
  - `approval_rule_set`: one row per unit, pointing to the current version;
  - `approval_rule_version`: immutable, numbered 1, 2, …; holds preset `LEAN` | `STANDARD` | `STRICT` | `CUSTOM` and the high-value item amount in paise;
  - `approval_rule`: one per transaction type per version; escalate after N hours (1 to 168); urgent minutes (indent only);
  - `approval_rule_level`: level 1 or 2; approver `STORE_APPROVER` (HOD or current delegate) or `OWNER`; one condition, either `ALWAYS`, `ABOVE_AMOUNT` (paise), `ABOVE_PERCENT` (discount only, OQ-78-27), `SCHEDULE_X_ITEM`, `HIGH_VALUE_ITEM`, `URGENT_PRIORITY` or `STORE_TYPE` (list).
- Revision 1's `approval_limit` table is replaced by the per-level amount, which carries the same information.
- Presets are data written by the service (meanings in `pharmacy.yaml`, POST /approval-rules/preset). A preset that needs a limit asks for it. Sahaj ships no ₹ amounts (DEC-062 Q4).
- Defaults before the first save: STANDARD with no amounts (owner levels with condition ALWAYS) and a "review these rules" banner.
- Every save writes a new version. A save identical to the current version writes nothing.
- Phase 2 runtime (unchanged):
  - `approval_request` snapshots the version;
  - `approval_decision` is append-only;
  - escalation is computed at read time;
  - the fixed rules are enforced in code and the database: no self-approval; Schedule X needs a pharmacist plus a second signer; the ledger cannot be edited.

### D8 · Document numbering per unit: gapless for committed documents, race-free (accepted; doc types revised)

- `pharmacy.document_series (tenant_id, unit_id, doc_type, prefix, financial_year_part, sequence_length, restarts_each_fy)`, one row per doc type: `PURCHASE_ORDER`, `GRN`, `INDENT`, `TRANSFER`, `BILL` (story 78-10; debit note and audit numbers come with SAH-79). Defaults until saved: `PO-0001`, `GRN-0001`, `IND-0001`, `TRF-0001`, `SB/26-27/000001`.
- Rendering:
  - number = prefix + (`26-27/` when the financial-year part is on) + the sequence padded to its length;
  - at most 16 characters (OQ-78-31, DEC-062 Q4);
  - restarting on 1 April needs the financial-year part, so numbers stay unique.
- `pharmacy.document_counter (tenant_id, unit_id, doc_type, fiscal_year) → next_value`:
  - fiscal year `0` when the series never restarts;
  - a number is taken with `INSERT … ON CONFLICT … DO UPDATE … RETURNING` in the transaction that inserts the document (function `pharmacy.next_document_no(doc_type)`, SECURITY INVOKER);
  - result: no race, no gap on rollback, cancelled documents keep their number.
- Phase 1 takes no number. The function exists, is tested by QA, and has no HTTP operation.
- Fiscal year April-March, IST.
- Once a number is taken, editing a series is a SAH-79 rule (open point below).

### D9 · Audit (accepted)

- Every master, setting, mapping, rule, series, reason, template, mode and opening-stock change: `platform.api.AuditLog.record` in the same transaction, with before and after values (ids and field values, never patient text). Action names are on each operation in `pharmacy.yaml`.
- A denied store operation writes `PHARMACY_STORE_ACCESS_DENIED` (ids only).
- Logs carry tenant id, request id, ids, counts and outcome codes only. Never: contact phone numbers, file contents, batch numbers or quantities.

### D10 · Money and quantity conventions (accepted, DEC-063 OQ-PHI-21)

- Document amounts, totals and limits are `bigint` paise (JSON integer).
- Unit rates (MRP and purchase rate per issue unit) are `numeric(14,4)` rupees, sent as decimal strings. Phase 1 input accepts at most 2 decimals.
- Line value = rate × quantity, rounded half-up to the paisa.
- Percentages are `numeric(5,2)`, sent as decimal strings.
- Deviates from `.claude/rules/postgresql.md` ("`numeric(12,2)` for money") as the built modules already do. The human edits that rule file.

### D11 · Sidebar entry and module switch **[AUTH]** (accepted; menu rule revised)

- `platform.module_entitlement` gains `pharmacy_on boolean not null default false`. Read through a new method on `ModuleEntitlementPort` (`boolean pharmacyOn(UUID tenantId)`), so the existing `ModuleEntitlement` record is unchanged.
- `NavigationPolicy` adds key `pharmacy` ("Pharmacy & Inventory", `/app/pharmacy`) when `pharmacy_on` and the member is OWNER, or PHARMACIST (story 78-1; revision 1 said OWNER or mapped only), or `PharmacyAccessPort.hasActiveMapping(tenantId, membershipId)` is true. The port is declared in `platform.api` and implemented by pharmacy. In both menu modes, before `staff` and `config`.
- Who switches `pharmacy_on`: the Sahaj admin (DEC-063 OQ-PHI-4) through `PATCH /api/v1/admin/clinics/{id}/modules` field `pharmacyOn` (admin.yaml 0.6.0). That implementation touches files outside the DEC-061 Answer 2 list, so it waits for the tech lead or the human (open point). Until then test fixtures switch it on.
- One route `/app/pharmacy`. Tabs in BRD order (Inventory setup, Inventory, Pharmacy). Only tabs with a released screen are shown: Inventory setup in Phase 1.

### D12 · Phase 1 scope edges (accepted; flow detailed)

- Opening stock (CSV, no Excel library; the file is never stored):
  - The browser reads the file and checks file type, header and row count (at most 5,000).
  - It starts an import (`DRAFT`) and sends the rows as raw text in requests of at most 200 rows (inside the 64 KB cap). The server parses and checks each row.
  - Submit runs the file-wide checks. Any error makes the import `NOT_ACCEPTED`: final, nothing partly kept, `422 opening-stock-invalid`. Otherwise it becomes `WAITING_APPROVAL`.
  - A different OWNER approves (`APPROVED`, final) or rejects with a reason (`REJECTED`).
  - At most one `WAITING_APPROVAL` and one `APPROVED` import per store.
  - Approval sets `has_stock` on the store and on its products, which locks store type and code and product units and conversion, and blocks switch-off.
  - Posting is SAH-79.
- Print templates are settings only. The preview is the browser's print view. No PDF library, no file storage.
- WhatsApp, OCR, ABDM: out.
- Not built in Phase 1 (no story): the IPD ward link on a store; switching a store or vendor back on; promotion of own products; the MRP or selling rule (OQ-78-20).

---

## Additive-only changes to existing files and tables (DEC-061 Answer 2)

Each change below is additive. Nothing existing is renamed or removed. No existing row changes. With `pharmacy_on` false, every existing screen, menu, API answer and test behaves as before.

| # | Existing file or table | Change | Owner | In DEC-061 Answer 2 list? |
|---|---|---|---|---|
| 1 | `platform.module_entitlement` | New column `pharmacy_on boolean not null default false`, in a new migration | db-engineer | Yes |
| 2 | `platform.membership` check `ck_membership_role` | Replaced in a new migration by the same list plus `'STORE_STAFF'` (precedent: migration 1019, COUNSELLOR). The old migration is not edited. Existing rows unchanged | db-engineer | Yes (role value) |
| 3 | `com.sahaj.platform.domain.MembershipRole` | New enum value `STORE_STAFF` | java-developer | Yes |
| 4 | `com.sahaj.platform.application.NavigationPolicy` | Key `pharmacy`; `STORE_STAFF` gets no base keys. `ModuleFlags` gains `pharmacyOn` through a new constructor or overload, so existing callers compile unchanged | java-developer | Yes |
| 5 | `com.sahaj.platform.api.ModuleEntitlementPort` and its implementation | New method `pharmacyOn(UUID)`. The record is unchanged | java-developer | Yes (module switch) |
| 6 | `com.sahaj.platform.api.PharmacyAccessPort` | **New file** in `platform.api`. A no-op default (false) is used while the pharmacy module is absent | java-developer | Yes (menu, ADR D11) |
| 7 | `GET /me` assembler (where `NavigationPolicy` is called) | Passes `pharmacyOn` and the port answer | java-developer | Yes (menu) |
| 8 | `ModularityTests` | Pharmacy rules (a)-(c) of D1 | java-developer | Yes |
| 9 | `nav.config.ts`, `shell.routes.ts` | Entry `pharmacy` and lazy route `/app/pharmacy` | angular-developer | Yes |
| 10 | Frontend role label map and the Team role select | Label "Store staff" for `STORE_STAFF`. Where the shell lands a user with an empty menu, a "no access" page | angular-developer | **Partly**: the role value is listed, the label and landing page are implied. Tech lead to confirm |
| 11 | `docs/api/platform.yaml` 0.1.2 → 0.2.0 | Role enum value, menu key text, info note | solution-architect | Contract |
| 12 | `docs/api/admin.yaml` 0.5.0 → 0.6.0 | Optional `pharmacyOn` in `ModulesChange` and `ClinicModules` | solution-architect | Contract |
| 13 | Admin modules service, `platform.admin_set_modules` (new `CREATE OR REPLACE` in a new migration with an extra optional parameter), admin portal Modules tab | Implements admin.yaml 0.6.0 | java-developer, db-engineer, angular-developer | **No**. Needs the tech lead or the human before it is built |
| 14 | `application-dev` and `application-test` profile config | Flyway placeholder `sahajSampleData=true` (D3) | java-developer | **No** (configuration). Tech lead to confirm |
| 15 | `scripts/db/schemas.sql` line 8, `create-database.mjs` re-run | Add `'pharmacy'` | human | Human-only |
| 16 | `com.sahaj.platform.api.ClinicWebAddressPort` and `platform.infrastructure.persistence.JdbcClinicWebAddressPort` (revision 3, R3-1) | **New files.** Read-only port: the clinic web address, from which pharmacy derives the unit code | java-developer | Accepted by the tech lead (handoff SAH-78, decision 2); recorded here |
| 17 | `NavigationPolicy` (revision 3, R3-5) | `STORE_STAFF` never gets a non-pharmacy key, including HRA `staff` (DEC-068 Q2 A) | java-developer | Yes (same file as row 4) |

Not touched: `role_permission`, `role_permission_default`, any OPD, IPD, billing, patient or HRA table, file or API.

---

## Data needed (for the db-engineer): Phase 1, final

**Conventions for every pharmacy tenant table:**
- `id uuid pk default gen_random_uuid()`.
- `tenant_id uuid not null default platform.current_tenant()`, FK to `platform.tenant`.
- `unit_id uuid not null`, with a same-tenant composite FK to `pharmacy.unit (tenant_id, id)`. The unit has `unique (tenant_id, id)`.
- `created_at`, `created_by`, `updated_at`, `updated_by`, `version bigint`.
- `ENABLE` + `FORCE` RLS, per-command policies for `sahaj_app` on `platform.current_tenant()`, **no delete grant** anywhere.
- `tenant_id` first in every index. `timestamptz`. Text with `check` constraints.
- Same-tenant composite FKs inside the schema (`(tenant_id, x_id)` → `(tenant_id, id)`).
- No cross-module FK except `platform.tenant`. Membership ids are opaque `uuid`s checked through `TeamMemberPort`.
- Table names follow the existing singular precedent (`ipd.drug`, `platform.membership`).

**Migration order** (timestamps keep the A, B, C package order):

| Migration | Contents |
|---|---|
| A1 platform | `module_entitlement.pharmacy_on`, `ck_membership_role` plus `STORE_STAFF` |
| A2 pharmacy | `unit`, `store`, `store_staff`, `staff_preference` |
| B1 pharmacy catalogue (own file) | `catalogue_product`, `catalogue_barcode` |
| B2 pharmacy sample seed (own file) | about 30 rows, placeholder-guarded, `sample = true` |
| B3 pharmacy | `product`, `product_store_setting`, `opening_stock_import`, `opening_stock_line`, `opening_stock_line_issue` |
| C1 pharmacy | `vendor`, `vendor_store`; FK `product.preferred_vendor_id` added here |
| C2 pharmacy | `approval_rule_set`, `approval_rule_version`, `approval_rule`, `approval_rule_level` |
| C3 pharmacy | `document_series`, `document_counter`, `next_document_no()`, `reason_default` (global, seeded), `reason`, `reason_default_state`, `print_template_setting` |

| Table | Key fields | Keys, uniques, checks | RLS / grants / notes |
|---|---|---|---|
| `platform.module_entitlement` (existing) | + `pharmacy_on boolean not null default false` | none | Existing policy covers it. No data change |
| `platform.membership` (existing) | `ck_membership_role` plus `'STORE_STAFF'` | Drop and add the constraint in one migration | No data change |
| `pharmacy.unit` | `code char(3)` `^[A-Z0-9]{3}$`; `gstin` (15, GSTIN pattern, nullable); `drug_licence_no` (≤ 40, nullable); `drug_licence_valid_till date`; `integration_mode` (`INTEGRATED`/`STANDALONE`, default `INTEGRATED`); `own_product_next_no integer not null default 1` | `unique (tenant_id)` (one unit, D2); `unique (tenant_id, id)` | Select, insert, update. Lazy row. FEFO and negative-stock block are constants (not stored) |
| `pharmacy.store` | `name` (≤ 60); `code` (≤ 10, `^[A-Z0-9-]+$`); `type` (`CENTRAL`/`PHARMACY`/`SUB_STORE`/`WARD`); `issues_from_store_id`; `sale_allowed`; `consumption_allowed`; `has_stock boolean default false`; `status` (`ACTIVE`/`INACTIVE`) | Unique `(tenant_id, unit_id, lower(name))` and `(tenant_id, unit_id, lower(code))`; partial unique `(tenant_id, unit_id) where type = 'CENTRAL' and status = 'ACTIVE'`; check: `issues_from_store_id is null` exactly when `type = 'CENTRAL'`; self FK same tenant | Depth one: non-central stores issue from the central store (service check) |
| `pharmacy.store_staff` | `store_id`; `membership_id`; `store_role` (`STORE_USER`/`CENTRAL_STORE_USER`/`APPROVER`/`DELEGATE`); `delegate_from`; `delegate_to`; `status` (`ACTIVE`/`REMOVED`) | `unique (tenant_id, store_id, membership_id)` (a re-map re-activates the row); partial unique `(tenant_id, store_id) where store_role = 'APPROVER' and status = 'ACTIVE'`, same for `DELEGATE`; check: dates not null, `to >= from` and `to - from <= 90` exactly when `DELEGATE`, else both null (superseded by revision 3, R3-6: `to - from <= 89`, migration `V2026_12_17_1230`); index `(tenant_id, membership_id) where status = 'ACTIVE'` (guard, menu port, context) | Allowed store role per team role and Central-Store-only for `CENTRAL_STORE_USER` are service checks (cross-module and cross-row) |
| `pharmacy.staff_preference` | `membership_id`; `last_store_id` | `unique (tenant_id, membership_id)` | Convenience. Not audited |
| `pharmacy.catalogue_product` (global) | `code` (`^G-[0-9]{6}$`, unique); `name`; `salt`; `strength`; `form`; `manufacturer`; `hsn` (4-8 digits); `gst_rate numeric(5,2)`; `schedule` (`NONE`/`H`/`H1`/`X`); `ndps`; `purchase_unit`; `issue_unit`; `conversion integer >= 1`; `active`; `sample boolean`; `dup_key` | Unique `code`; trigram GIN on `lower(name)`, `lower(salt)`; index on `dup_key` | No `tenant_id`; `ipd.drug` RLS pattern; select-only for `sahaj_app`; delete and truncate deny trigger; own migration file. `pg_trgm` is already in use (patient migration 1039) |
| `pharmacy.catalogue_barcode` (global) | `catalogue_product_id`; `barcode` (≤ 40) | Unique `barcode` | Same pattern |
| `pharmacy.product` | `kind` (`ADOPTED`/`OWN`); `catalogue_product_id`; own fields as the catalogue (null when adopted); `made_in_house`; `code` (own: `U-XXX-nnnn`); `alias` (≤ 40); `barcode` (≤ 40); `returnable`; `preferred_vendor_id`; `has_stock`; `status` (`ACTIVE`/`INACTIVE`); `dup_key` (own only) | Exclusive-kind check (adopted: link set, own fields null; own: link null, own fields and `code` not null, schedule not null); `unique (tenant_id, unit_id, catalogue_product_id)`; `unique (tenant_id, unit_id, code)`; partial unique `(tenant_id, unit_id, lower(barcode)) where status = 'ACTIVE' and barcode is not null`; trigram GIN on `lower(name)`, `lower(salt)`, `lower(alias)`; index `(tenant_id, dup_key)` | FK `preferred_vendor_id` (same tenant) added in C1. The own-product number comes from `unit.own_product_next_no` (`UPDATE … RETURNING` in the same transaction) |
| `pharmacy.product_store_setting` | `product_id`; `store_id`; `reorder_level integer` 0-999999 (nullable); `rack` (≤ 30) | `unique (tenant_id, product_id, store_id)` | none |
| `pharmacy.opening_stock_import` | `store_id`; `status` (`DRAFT`/`NOT_ACCEPTED`/`WAITING_APPROVAL`/`APPROVED`/`REJECTED`/`REPLACED`); `row_count` (1-5000); `uploaded_by`; `client_key`; `submitted_at`; `decided_by`; `decided_at`; `reject_reason_key` (`WRONG_FILE`/`WRONG_QUANTITIES`/`WRONG_STORE`/`OTHER`); `reject_note` (≤ 300); totals (`line_count`, `total_quantity bigint`, `total_value_paise bigint`, `error_count`, `warning_count`); `replaced_by_import_id` | Check `decided_by <> uploaded_by` when `APPROVED` (fixed rule); partial unique `(tenant_id, store_id) where status = 'APPROVED'` and `… where status = 'WAITING_APPROVAL'`; `unique (tenant_id, uploaded_by, client_key)` | Trigger: `APPROVED`, `REJECTED`, `NOT_ACCEPTED` and `REPLACED` rows are final (no update) |
| `pharmacy.opening_stock_line` | `import_id`; `row_no`; raw text of the 7 columns (each ≤ 40); parsed `product_id`, `batch_no` (≤ 20), `expiry_date`, `mrp numeric(14,4)`, `purchase_rate numeric(14,4)`, `qty integer`, `value_paise bigint` | `unique (tenant_id, import_id, row_no)`; index `(tenant_id, import_id, product_id, batch_no)` | Update allowed only while the parent is `DRAFT` (trigger). Kept as evidence and as the SAH-79 posting source |
| `pharmacy.opening_stock_line_issue` | `line_id`; `field`; `code`; `severity` (`ERROR`/`WARNING`) | Index `(tenant_id, line_id)` | Rewritten while `DRAFT` (service). Delete is not granted, so a replaced row's issues are marked superseded, or the line id is new per send. db-engineer chooses and notes it |
| `pharmacy.vendor` | `name` (≤ 120); `gstin`; `licence_required`; `licence_no` (≤ 40); `licence_valid_till`; `contact_name` (≤ 80); `contact_phone` (`^[0-9]{10}$`); `credit_days` 0-365; `return_days_before_expiry` 0-365; `return_credit_pct numeric(5,2)`; `status` (`ACTIVE`/`INACTIVE`); `client_key` | Unique `(tenant_id, unit_id, lower(name))`; `unique (tenant_id, created_by, client_key)`; check: licence number and date not null when `licence_required` | Blocked is computed from the date (IST), never stored |
| `pharmacy.vendor_store` | `vendor_id`; `store_id` | `unique (tenant_id, vendor_id, store_id)` | Rows replaced on edit, so needs delete; or use an `active` flag. db-engineer chooses (prefer the flag: no delete grant) |
| `pharmacy.approval_rule_set` | `current_version_id` | `unique (tenant_id, unit_id)` | Lazy |
| `pharmacy.approval_rule_version` | `version_no`; `preset` (`LEAN`/`STANDARD`/`STRICT`/`CUSTOM`); `high_value_threshold_paise bigint` | `unique (tenant_id, unit_id, version_no)` | **Select and insert only** (immutable) |
| `pharmacy.approval_rule` | `version_id`; `transaction_type` (9 values, D7); `escalate_after_hours` 1-168; `urgent_escalate_after_minutes` 5-1440 (only `INDENT`) | `unique (tenant_id, version_id, transaction_type)` | Select and insert only |
| `pharmacy.approval_rule_level` | `rule_id`; `level_no` (1, 2); `approver_kind` (`STORE_APPROVER`/`OWNER`); `condition_kind` (7 values); `amount_paise bigint` (0-99999999900); `percent numeric(5,2)`; `store_types text[]` (checked subset) | `unique (tenant_id, rule_id, level_no)`; check: amount exactly for `ABOVE_AMOUNT`, percent exactly for `ABOVE_PERCENT`, store types exactly for `STORE_TYPE` | Select and insert only |
| `pharmacy.document_series` | `doc_type` (`PURCHASE_ORDER`/`GRN`/`INDENT`/`TRANSFER`/`BILL`); `prefix` (`^[A-Za-z0-9/-]{1,6}$`); `financial_year_part`; `sequence_length` 4-8; `restarts_each_fy` | `unique (tenant_id, unit_id, doc_type)`; unique `(tenant_id, unit_id, lower(prefix))`; check `restarts_each_fy` implies `financial_year_part`; check rendered length ≤ 16 | Lazy |
| `pharmacy.document_counter` | `doc_type`; `fiscal_year` (0 = never restarts); `next_value bigint` | pk `(tenant_id, unit_id, doc_type, fiscal_year)` | Select, insert, update; function `pharmacy.next_document_no(doc_type text) returns bigint` SECURITY INVOKER |
| `pharmacy.reason_default` (global) | `reason_key` (`^[a-z0-9.-]+$`); `list_key` (`store-return`/`sales-return`/`damage`/`stock-adjustment`); `label`; `sort` | Unique `reason_key` | Global reference (`ipd.drug` pattern), seeded with the 13 mockup reasons (story 78-10) in every environment |
| `pharmacy.reason` | `list_key`; `reason_key` (`c-<uuid>`); `label` (≤ 40); `active`; `sort` | Unique `(tenant_id, unit_id, list_key, lower(label))`; unique `(tenant_id, unit_id, reason_key)` | The label must not equal a default label of the same list (service check) |
| `pharmacy.reason_default_state` | `reason_key`; `active` | `unique (tenant_id, unit_id, reason_key)` | Lazy. A missing row means active |
| `pharmacy.print_template_setting` | `doc_type` (`INVOICE`/`ISSUE_SLIP`/`DEBIT_NOTE`); `header_line_1..3` (≤ 80); `footer_note` (≤ 200); `paper_size` (`A4`/`A5`) | `unique (tenant_id, unit_id, doc_type)` | Lazy |

**Retention:**
- Masters are never deleted; they are switched off by status.
- Opening-stock imports and lines are kept.
- Approval versions are kept for good.
- Ledger, registers and approvals (Phase 2 and 3) are kept until the human sets legal periods (OQ-PHI-19). They are removed only by partition drop, never by `DELETE`.

**Tenant scope:**
- Every table above is tenant-scoped, except the three global tables `catalogue_product`, `catalogue_barcode` and `reason_default`.
- QA verifies cross-tenant invisibility as `sahaj_app` for every tenant table.

---

## Open questions (OQ-PHI table answered by DEC-063 Q1 and DEC-062)

| ID | Flag | Answer |
|---|---|---|
| OQ-PHI-1 | [TENANCY] | **Answered** (DEC-063): D2 option B |
| OQ-PHI-2 | [BOUNDARY] | **Answered** (DEC-063): D1 as written |
| OQ-PHI-3 | [AUTH] | **Answered** (DEC-063), revised for Store staff (DEC-062 Q3): D4 revision 2 |
| OQ-PHI-4 | [AUTH] (menu) | **Answered** (DEC-063): Sahaj admin switches `pharmacy_on`; entry for OWNER, PHARMACIST and mapped staff |
| OQ-PHI-5 | | **Answered** (DEC-062 Q2): about 30 sample products, dev, test and demo only, "not clinically validated"; real source later |
| OQ-PHI-6 | | **Answered** (DEC-062 Q1): BA section layout accepted; extra fields after the mockup fields, marked NEW |
| OQ-PHI-7 | | **Answered** (DEC-063): BRD tab order; unbuilt tabs not rendered |
| OQ-PHI-8 | | **Answered** (DEC-062 Q4): FEFO and negative-stock block On and locked |
| OQ-PHI-9 | | **Answered** (DEC-063): Phase 1 stages and approves; posting is the first SAH-79 story |
| OQ-PHI-10 | [BOUNDARY] | **Answered** (DEC-063): each Phase 3 touch point gets its own story |
| OQ-PHI-11 | | **Answered** (DEC-063): offline billing is its own design story |
| OQ-PHI-12..16 | | **Answered** (DEC-063): BRD section 10 recommendations as written |
| OQ-PHI-17 | | **Answered** (DEC-063): multi-branch Later |
| OQ-PHI-18 | | **Answered** (DEC-063 Q2): WP-A → WP-B → WP-C in sequence, Java and Angular in parallel |
| OQ-PHI-19 | | **Answered** (DEC-063): compliance check before Phase 3; nothing deleted meanwhile |
| OQ-PHI-20 | | **Answered** (DEC-063): pharmacist registration number in Phase 3 |
| OQ-PHI-21 | | **Answered** (DEC-063): paise accepted; human edits `.claude/rules/postgresql.md` |

### Open points raised by revision 2 (each with the contract's current choice)

| ID | Point | Contract choice (recommendation) |
|---|---|---|
| R2-1 | Stories 78-4 and 78-7 answer **403 `outside-store`** for an unmapped store; DEC-063 accepted ADR D4: **404** | 404 (the decision). BA to update the stories |
| R2-2 | Integration mode default: ADR revision 1 said STANDALONE; story 78-2 and OQ-78-14 say INTEGRATED | INTEGRATED. No effect in Phase 1. Human to confirm |
| R2-3 | May a STORE_STAFF member be an Approver or Delegate (a store keeper as HOD of the Central Store)? OQ-78-6 lists owner, doctor, nurse and pharmacist only | Not allowed (follows OQ-78-6). Human to confirm |
| R2-4 | Admin portal toggle for `pharmacy_on` (admin.yaml 0.6.0) touches files outside the DEC-061 Answer 2 list | Contract written. Build after the tech lead or human allows it; until then, fixtures |
| R2-5 | Story 78-1 shows the entry to an unmapped PHARMACIST and lets PHARMACIST read masters; ADR revision 1 showed it only to OWNER or mapped staff | Follows the story (D4 and D11 revised) |
| R2-6 | Mockup own product "Kit · OT use" has no strength; BR-16 requires strength | Strength required (story). The owner types "1 kit". Human may relax it |
| R2-7 | Approval presets for `GRN_RATE_RISE` and `VENDOR_RETURN` are not defined by OQ-78-28 | Architect proposal in `pharmacy.yaml` (Lean: none; Standard: owner above limit, and HOD; Strict: HOD then owner above limit). Human to confirm |
| R2-8 | OQ-78-25 (transaction list, pharmacist-in-charge as a role) and OQ-78-27 (discount limit in %) are not in the DEC-062 answers | Contract follows the BA recommendations. Pharmacist-in-charge is not a role yet |
| R2-9 | Delegate at most 90 days (OQ-78-15) is a BA proposal, not answered | 90 days. Human to confirm. **Answered** (DEC-068 Q1 A): at most 90 calendar days counting both ends, revision 3 R3-6 |
| R2-10 | Prefix uniqueness: story 78-10 refuses equal prefixes only without the financial-year part | Refuses equal prefixes always (simpler; avoids identical numbers across types) |
| R2-11 | Opening-stock expiry formats (DD-MM-YYYY, DD/MM/YYYY, YYYY-MM-DD, MM/YYYY, MM-YYYY = last day of the month) | NEW; BA to add to story 78-7 |
| R2-12 | Bill series per store (OQ-78-31 accepted "bills per store if the owner chooses") | One series per unit in 0.1.0; a store-level bill series is added with SAH-80 (additive column `store_id`) |
| R2-13 | Own products have no NDPS flag input (catalogue has one) | False for own products in Phase 1; pharmacist to confirm before Phase 3 |
| R2-14 | Editing a series after numbers are taken in the current fiscal year | SAH-79 rule; Phase 1 takes no numbers |
| R2-15 | `pg_trgm` for search | Already used by `patient.patient` (migration 1039); reuse it, no new extension |
| R2-16 | Flyway placeholder for sample data needs profile config (java-developer) outside the DEC-061 list | Tech lead to confirm |

---

## Revision 3 (18 Nov 2026): WP-A review record (additive)

Sources: `docs/reviews/SAH-78-code.md` (F-2, F-3, F-10, F-11, F-15, F-16), `docs/reviews/SAH-78-security.md` (L3, L4, L6), `docs/decisions/DEC-068-sah78-wpa-review-points.md` (human answers Q1 A, Q2 A; tech lead decisions), and the tech lead decisions in `docs/handoffs/SAH-78.md` (java WP-A review, decisions 1 and 2). No decision D1 to D12 is reversed. Each point adds to, or makes exact, the decision it names.

| ID | Adds to | Decision |
|---|---|---|
| R3-1 | D1, D2 | **`platform.api.ClinicWebAddressPort`** (read-only): `Optional<String> webAddress(UUID tenantId)` returns the clinic web address (`platform.tenant.slug`). Pharmacy calls it once, when it creates the unit row, to derive the three-character unit code of D2 (for example `sunrise-baner` gives `SUN`). It is **declared by the producer**: platform owns it in its own `api` package, the same as `TeamMemberPort`. This is allowed because the edge `pharmacy → platform.api` already exists (D1); it is not a consumer-declared port, since platform never calls pharmacy through it. Platform implements it with JDBC (`JdbcClinicWebAddressPort`): `@Transactional(propagation = MANDATORY)` in the caller's transaction, under RLS (policy `p_tenant_self`), so another clinic's id simply returns nothing. It reads no personal data. No other module may write through it. Phase 1 platform ports used by pharmacy: `TeamMemberPort`, `ModuleEntitlementPort`, `AuditLog`, `ClinicWebAddressPort` and the clinic profile read |
| R3-2 | D1, Data needed | **Persistence is Spring JDBC (`JdbcClient`) with hand-written, parameterised SQL**, as in every built module (no `@Entity` anywhere in the backend). JPA is not used in `com.sahaj.pharmacy`. The tech lead confirmed this. Consequences: (a) D1 build check (c) ("every `@Table` declares `schema = "pharmacy"`") passes without checking anything (code review F-15). The java-developer may replace it with a rule that every SQL text in `com.sahaj.pharmacy` names only `pharmacy.` tables, or drop it. Tenant safety is pinned by `PharmacySqlGuardrailsTest` (bind parameters only; every statement names `tenant_id`). (b) Read the database design note's JPA words (`@Table`, `@Version`, `saveAndFlush`) as: `version` is checked in `UPDATE … WHERE version = :version`, and write order is statement order. (c) `.claude/rules/java-spring.md` still says "Spring Data JPA". The human aligns it (tech lead to-do; human-owned file) |
| R3-3 | D4, D9 | **Amended by revision 5, R5-1: the audit is no longer written in REQUIRES_NEW inside the caller's transaction, but by the exception handler after that transaction ends. The text below is kept as history.** **The `PHARMACY_STORE_ACCESS_DENIED` audit row survives the caller's rollback.** `StoreAccessGuard` writes it through `AuditLog.record` in its own transaction (`Propagation.REQUIRES_NEW`), in a separate Spring bean so that the proxy applies, and then throws `StoreAccessDeniedException`. It replaces the comment-only rule "callers declare `noRollbackFor`" (code review F-2, security L4). `TenantBindingTransactionManager` binds `app.tenant_id` at every transaction begin, so the inner transaction runs under RLS for the same tenant (QA proves this with the guard probe: the row is there after the 403, and is invisible to another tenant). The inner transaction only inserts one `platform.audit_event` row (ids and the capability name, nothing else), so it shares no locks with the outer one. Cost: a second pooled connection for a few milliseconds on a refused request, which is rare. A convention test checks that every OPERATION handler calls the guard; it is kept. The java-developer is building this in fix round 1 |
| R3-4 | D5 | **`IntegrationOffException` → 409 `integration-off`** is the one shared server error for every Integrated-only operation. It lives inside the module (`com.sahaj.pharmacy.application`, not `api`: no other module throws it). `PharmacyProblemHandler` maps it to `components/responses/IntegrationOff` of `pharmacy.yaml` (type `https://sahaj.in/problems/integration-off`, fixed detail). Only the capability check that follows `ClinicalLinkResolver` throws it. This keeps the D5 review rule: no other mode check in services, and no hand-built 409 for this case. No 0.1.x operation needs it. It is built now with a unit test (code review F-10), and SAH-79 and SAH-80 reuse it unchanged |
| R3-5 | D4, D11 | **Store staff never gets a non-pharmacy menu key** (DEC-068 Q2 A, security L6). For `STORE_STAFF`, `NavigationPolicy` returns exactly `[pharmacy]` (module on and an active mapping) or `[]`. The HRA `staff` key is no longer added for this role. Rule for later modules: a new menu key is never given to `STORE_STAFF` unless its story names the role. Landing (tech lead decision on code review F-1): a `STORE_STAFF` member with an empty menu is routed to `/app/pharmacy`, which shows the server's 403 sentence "You do not have access to Pharmacy & Inventory. Ask your owner." This is the "no access" page of D4 |
| R3-6 | D4, Data needed | **Delegate period: at most 90 calendar days, counting both the first and the last day** (DEC-068 Q1 A, security L3): `delegate_to - delegate_from <= 89`. One rule in four places: the service (`DelegateRules`), the database check `ck_store_staff_delegate_dates` (forward migration `V2026_12_17_1230`, added `NOT VALID` and then validated), the contract (`DelegateInput` and `POST /stores/{storeId}/staff`, `pharmacy.yaml` 0.1.1), and the Angular pre-check. Example: from 17 Nov 2026 the last allowed end date is 14 Feb 2027. The user text stays "A delegation can be at most 90 days". This closes open point R2-9 |
| R3-7 | Data needed (migration order) | **Migration versions** (code review F-16). SAH-78 has used `V2026_12_17_1000` to `V2026_12_17_1240`. Later SAH-78 files (WP-B, WP-C fixes, SAH-78 follow-ups) take `V2026_12_17_1250` or higher, below `V2026_12_18_0000`. The IPD story SAH-77 (other session) uses `V2026_12_18_*` and later. Flyway runs with out-of-order off. So a file may never take a version below one already applied in `sahaj_dev` or `sahaj_test`, and an applied file is never edited (tech lead decision on M1). If the `V2026_12_17` slot runs out, or another session takes a `V2026_12_17_*` number, the tech lead settles the order before the next file is written. The dates in these versions are labels, not calendar dates |
| R3-8 | D4 (contract) | **`GET /staff-candidates` without `storeId`** (code review F-11, `pharmacy.yaml` 0.1.1, additive and backward compatible). Without a store, the answer lists the ACTIVE members who can be mapped at all, by team role only. `allowedStoreRoles` is the full role table of D4, and `alreadyMappedHere` is always false. Access level (SETUP_EDIT) and check order are unchanged, and there is no 404 without a store. The store form uses it for the Approver (HOD) and Delegate approver selects before the store exists, so the first Central Store can be given an HOD at creation (mockup 16 detail card). `POST /stores` checks every chosen person again, as before |
| R3-9 | Contract rule 2 | **Body validation runs after the gate** (code review F-3, security L1). The approved check order of `pharmacy.yaml` general rule 2 stays as written: 401, module-off 403, access-level 403, 400 path, 404, store guard, then 400 body validation. The java-developer binds the body after the gate (as a tree or text, converted after `gate.require`). No contract change |

**Consequences of revision 3**
- Positive: the denial audit no longer depends on each caller's annotation. Store staff gets nothing outside P&I. The delegate limit has one meaning in the UI, API and database. The first Central Store matches mockup 16 at creation. Migration order across the two sessions is written down.
- Cost: one extra connection on a refused store operation; one more platform port (read-only, producer-declared); D1 check (c) needs replacing or dropping.

---

## Revision 4 (18 Nov 2026): WP-B choices recorded (wording only)

The java-developer made these choices while building WP-B (stories 78-5, 78-6, 78-7), where contract 0.1.1 was silent or loose. The tech lead accepted them as defaults. `pharmacy.yaml` 0.1.2 records them in the descriptions of the operations and schemas named. No path, status code, required list, field name, type or enum changed. No decision D1 to D12 changes, and no table or migration changes.

| ID | Adds to | Choice |
|---|---|---|
| R4-1 | D3 | `PUT /products/{productId}` runs the duplicate check only when the normalised salt, strength and form key changes. Otherwise every later edit of an own product created with "Create anyway" would ask for "Create anyway" again |
| R4-2 | Local settings | A changed preferred vendor must be ACTIVE and not Blocked (400 `preferredVendorId` "Choose an active vendor"). An unchanged one that has since become Blocked or Inactive stays savable and shows "needs review". Blocked = licence required and valid-till before today (IST), as in story 78-8 |
| R4-3 | Local settings | `storeSettings` changes only the stores it lists; both values null clears a store. `active = false` gets 409 `product-has-stock` only when the product is ACTIVE now and has stock. The barcode clash is checked only when the product will be ACTIVE after the save (matches the partial unique index on ACTIVE barcodes) |
| R4-4 | D12 | `errorCount` = rows with at least one ERROR. The 422 `rowIssues` lists the first 100 ERROR issues by row number; warnings come from `GET .../lines`. Counts and totals are live while DRAFT and fixed at submit |
| R4-5 | D12 | The batch repeat check ignores case. Store and product codes in rows match ignoring case and leading or trailing spaces |
| R4-6 | D4, D12 | `approveLockReason` is set only while WAITING_APPROVAL (`NOT_OWNER` or `SELF_UPLOADED`), otherwise null |
| R4-7 | D12 | `unitsLocked` equals `hasStock`. Approval sets `has_stock` on the store and on every product named on a line of the import |
| R4-8 | D3, R2-13 | Own products: `ndps` is false (no input field in Phase 1) |
| R4-9 | D3 | Product search: barcode matches exactly, ignoring case. Name, salt, alias and code match as a substring, ignoring case |
| R4-10 | D9, Data needed | Uploader and decider of an import are membership ids, as everywhere else in the module |

Consequence for SAH-79: batch numbers that differ only in case are one batch at import time. The posting story keeps that reading, or raises it before it writes `pharmacy.batch`.

---

## Revision 5 (18 Nov 2026): review round 2 (R3-3 amended; additive otherwise)

Sources: `docs/reviews/SAH-78-code.md` Round 2 (N-1, N-2, N-9, N-12, N-13), `docs/reviews/SAH-78-security.md` Round 2 (L7, L8, I3), `docs/decisions/DEC-071-sah78-review-round-2-fixes.md` (Q1 option A, decided by the tech lead; the human confirms at the merge ask). Contract `pharmacy.yaml` 0.1.3: one new 409 code, the line-request cap, the body-order sentence, the template placeholder and two known limits. No path, status code, required list, field name, type, enum or schema shape changed. R3-3 is the only earlier text this revision replaces; D1 to D12 stand.

**Context.** R3-3 wrote the `PHARMACY_STORE_ACCESS_DENIED` audit in `REQUIRES_NEW` inside the caller's transaction. That needs a second pooled connection while the request already holds one (and, in `addLines` and `submit`, row locks). With a pool of 10, about 10 concurrent refused requests wait on each other for the 10 s connection timeout: the whole node stalls for every clinic, the audit fails, the caller gets 500 and the denial row is lost (code review N-1, Major; security L7). Separately, an open opening-stock file could be orphaned by a store switch-off, or point at a product whose units changed (N-2), and a script could grow line and issue rows without limit (L8).

| ID | Amends or adds to | Decision |
|---|---|---|
| R5-1 | D4, D9; **replaces R3-3** | **The denial audit is written after the caller's transaction has ended, in one short transaction of its own.** `StoreAccessGuard` no longer writes anything and no longer uses `REQUIRES_NEW`. It throws `StoreAccessDeniedException` carrying the ids the audit needs (tenant id, actor user and membership ids, store id, capability name; nothing else). The caller's transaction rolls back and returns its connection. `PharmacyProblemHandler` (the `@RestControllerAdvice` for the module) then writes the audit through `AuditLog.record` in one new, short transaction (a `TransactionTemplate` or a separate `@Transactional` bean; the tenant is bound by `TenantBindingTransactionManager` from the ids on the exception, as before, so RLS applies), and answers 403 `store-permission-denied`. So a request never holds two connections. If the audit write itself fails, the handler still answers 403 (never 500) and logs the failure with ids only; the denial is then lost, which is the same outcome as a crash, and is rare. Kept from R3-3: the row survives the caller's rollback; ids and the capability name only; the convention test that every OPERATION handler calls the guard. Test (java-developer, QA): more parallel denials than the pool size (test pool 3) all answer 403 within the normal time, with one audit row each and no connection timeout. DEC-068's REQUIRES_NEW text is superseded by this point (tech lead updates DEC-068) |
| R5-2 | D12, D3 | **Open opening-stock file (DEC-071 Q1 option A).** An import is open while DRAFT or WAITING_APPROVAL. While a store has an open import, `pharmacyUpdateStore` refuses a code change and `pharmacySwitchOffStore` refuses the switch-off. While a product is on a line of an open import of any store (a line whose product code matched it), `pharmacyUpdateOwnProduct` refuses a change of purchase unit, issue unit or conversion, and `pharmacyPutLocalSettings` refuses active true to false. Each answers 409 `open-import` "Finish or reject the opening stock file of this store first." and writes nothing. The code name `open-import` is fixed (the java-developer and Angular use it). Order: after the existing `store-has-stock`, `units-locked` and `product-has-stock` checks, so a record with stock keeps its existing answer. **Concurrency:** the store checks run while holding the store row lock (`SELECT … FOR UPDATE`), the lock that import start and submit take first (lock order store, then import, unchanged), so an import cannot open between the check and the change. For products, the product row is locked (`FOR UPDATE`) before the check, and the line write reads the products it matches with `FOR SHARE`, so a units change or switch-off and a concurrent line send are ordered; the lock order stays store, then import, then products, and product edits take no store or import lock, so no cycle is added. If the java-developer finds the product-side lock too costly, the residual race (a line written in the same instant as the change) is recorded as a known limit in the handoff rather than silently left. Approve rechecks nothing (option B was not chosen) |
| R5-3 | D12 | **Line-request cap and unchanged resends (security L8).** Each import accepts at most 2 × ceil(rowCount / 200) line requests answered 200; the next one answers 409 `import-state` (existing code and sentence; no new code), writes nothing and leaves the import DRAFT. Twice the needed count lets a full resend of every batch fit. The count is kept and checked under the import row lock. A row sent again with its seven raw cells unchanged writes nothing (no row update, no issue rows) and its stored issues are answered again. Together these bound the rows one import can create to about 2 × 5,000 lines and their issues. Unlimited starts per person (each leaves a REPLACED draft) stay possible; a per-person daily cap on starts is not added in Phase 1 (security L8 marked it optional) and is a candidate for SAH-79 if the audit shows abuse |
| R5-4 | Contract rule 2 | **Body order (code review N-9).** R3-9 stands, with one exact point: a body that cannot be read (missing, not JSON, malformed, an unknown field, a value of the wrong JSON type) is answered 400 (`malformed-request`, or `validation` with one field) right after the path-id check and before the 404 and the store guard; field rules of a readable body stay after them. It runs after module-off and the access level and answers the same whether the record exists, so it leaks nothing (accepted by the code reviewer). `malformed-request` is added to the contract's list of problem types (it was already built) |
| R5-5 | D12 | **Template placeholder (N-13).** The template's example row uses the product code `PRODUCT-CODE`, never a sample catalogue code, because sample rows exist only in `sahaj_dev` and `sahaj_test` (M1 guard) |
| R5-6 | D10 | **Known limit (N-12).** `totalValuePaise` is int64; the worst case is about 5e18 paise per file, above 2^53 (about ₹ 90 lakh crore), where a JavaScript number loses precision. Realistic opening stock is far below it, so the client reads it as a number. Revisit (send as a string, or add a ceiling) only if SAH-79 sums across files or stores at a scale that could approach it |
| R5-7 | D12, for SAH-79 | **CSV export escapes formula prefixes (security I3).** Opening-stock lines keep the raw cells, which may start with `=`, `+`, `-` or `@`. Any CSV or spreadsheet export of lines, batches or products (SAH-79 and later) must prefix such a cell, and one starting with a tab or carriage return, with a single quote before writing it. A shared export helper in `com.sahaj.pharmacy` and a unit test with each prefix are the expected shape. 0.1.x has no export |

**Options considered for R5-1** (code review N-1, security L7): (a) write the audit in the exception handler after the rollback, in one short transaction (chosen: one connection per request, no new pool, the convention test stays); (b) give the audit its own small connection pool (keeps REQUIRES_NEW semantics but adds a second pool to size, configure and monitor, and still holds the caller's locks while it writes); (c) go back to `noRollbackFor` on each caller (rejected in revision 3: it depends on every caller's annotation).

**Options for R5-2** are in DEC-071 Q1: A refuse while open (chosen), B recheck at approve, C known limit.

**Consequences of revision 5**
- Positive: a burst of refused requests can no longer stall the node. An open file can no longer be orphaned or point at a product whose units moved under it. Line and issue rows per import are bounded. The body order and the CSV export rule are written down.
- Cost: one new 409 code that the store and product screens must show (Angular shows the detail sentence). The owner must finish or reject an open file before switching off its store or changing a product on it. A rare audit-write failure after the rollback loses that one denial row (the 403 still goes out). One counter of accepted line requests per import (see Data needed below).

**Data needed (db-engineer), revision 5**
- `pharmacy.opening_stock_import`: a count of accepted line requests (for example `line_request_count integer not null default 0`, check `>= 0`), tenant-scoped like the row (RLS unchanged), updated under the import row lock; kept with the row (no separate retention). The java-developer may instead derive it, in which case no migration is needed; the java-developer and db-engineer settle which in the handoff. A forward migration only (`V2026_12_17_1250` or higher, R3-7).
- Lookups for R5-2, both tenant-scoped and under RLS, are served by indexes that already exist in `V2026_12_17_1100`: "open imports of a store" by `ix_opening_stock_import_store (tenant_id, store_id, created_at desc)` filtered on status, and "open imports with a line for a product" by `ix_opening_stock_line_product (tenant_id, product_id) where product_id is not null` joined to the import on status. No new index unless the db-engineer's EXPLAIN on a realistic file says otherwise. Note for the java-developer: the FK from `opening_stock_line.product_id` takes only `FOR KEY SHARE` on the product, which does not conflict with an ordinary product `UPDATE`; that is why R5-2 asks for an explicit `FOR SHARE` in the line write.
- No new table, no new tenant table, no change to RLS policies or to retention. No cross-module table access.

---

## Revision 6 (18 Nov 2026): Phase 2 runtime moved to ADR-029 (pointer, additive)

SAH-79 (Phase 2, Inventory) needs more than a revision table: the batch and ledger design, FEFO and expiry quarantine, the posting service and its lock order, the approval engine runtime, the Schedule X second signature, the capability extension, partition upkeep, the vendor payable and the IPD reader port. They are recorded in **`docs/architecture/adr/ADR-029-pharmacy-inventory-runtime.md`** (status PROPOSED, with `pharmacy.yaml` 0.2.0). That ADR makes D6, D7 and D8 exact and adds the D1 port `ipd.api.AdmittedPatients` (T6), which is a **change to the IPD module for the human to approve**; pharmacy works with a no-op reader until then. Nothing in D1 to D12 or R3 to R5 is reversed. Points of this ADR that ADR-029 answers: R2-14 (editing a series after numbers are taken: allowed, issued numbers never change, ADR-029 P9); R4 consequence for SAH-79 (batch case: interim reading kept, OQ-79-37 open, ADR-029 P2); R5-6 (no cross-file sums in 0.2.0 approach the limit; report totals are per page and per export); R5-7 (CSV escaping, ADR-029 P13).

---

## Revision 7 (18 Nov 2026): hardening after review round 3 (R5-2 and R5-3 amended to what is built; additive otherwise)

Sources: `docs/reviews/SAH-78-code.md` round 3 (R3-3, R3-4), `docs/reviews/SAH-78-security.md` round 3 (L8, L9, L10, I7 to I10), DEC-076, DEC-077, DEC-078 (decided by the tech lead under DEC-073). Contract `pharmacy.yaml` 0.2.1 (additive or wording only). Only R5-2 and R5-3 are amended; D1 to D12 and the other revision points stand. The SAH-79 runtime side of the same points is note N1 in ADR-029.

**Context.** Code review R3-3 found that R5-2 promised two product locks that were not built: the product edit did not lock the product row before its open-import check, and the line write did not read the products `FOR SHARE`. R5-2 had said such a gap would be recorded rather than silently left. Security round 3 found two integrity races of the same kind (L9 store, L10 product), a request cap that clashed with the 60 KB batches (R3-4, I7), unlimited starts (L8), and a draft that nobody finishes holding the store for good (OQ-78-42).

| ID | Amends or adds to | Decision |
|---|---|---|
| R7-1 | **amends R5-2** (concurrency part) | **As built after DEC-077 item 1.** The store row lock (`SELECT … FOR UPDATE` on `pharmacy.store`) is always taken **first**, before the import row: by `pharmacyUpdateStore` and `pharmacySwitchOffStore` before their open-import check, and by `start`, `submit` and `approve`, which then **re-check that the store is ACTIVE** (a store switched off in between answers 404 `not-found`, nothing written). A database insert trigger on `opening_stock_import` refuses a row for a store that is not ACTIVE (backstop). On the product side, `pharmacyUpdateOwnProduct` and `pharmacyPutLocalSettings` lock the product row (`FOR UPDATE`) **before** the open-import check, and the line write (`pharmacyAddOpeningStockLines`) reads every product it matches with `FOR SHARE`; `submit` marks a line whose product is no longer ACTIVE as ERROR `product-unknown`. Lock order: store, then import, then products; product edits take only the product row, so no cycle is added (ADR-029 P5 is amended the same way). `start` also locks the caller's own DRAFT imports of the store before it replaces them (I10) |
| R7-2 | R5-2 | **The residual race that R7-1 closes.** Before DEC-077: (a) a units change or switch-off of a product could commit between the line write's product check and its insert, leaving an open file whose line points at a product with changed units or an inactive product (R3-3, L10); the FK on `opening_stock_line.product_id` takes only `FOR KEY SHARE`, which does not conflict with a plain product `UPDATE`; (b) a store could be switched off between the guard's check and `start`, `submit` or `approve`, leaving an open file on an inactive store that the guard then hides (404) for good (L9). With R7-1 both orders are serialised by the row locks, and a product switched off before submit is caught as a line error. No residual race of this kind is known |
| R7-3 | **replaces the cap of R5-3** | **The cap counts changed rows written, not requests** (DEC-077 item 2). At most 2 × `rowCount` changed rows written per import, counter `line_rows_written` (new additive column, default 0). A row counts when it is inserted or replaced; a row resent with its seven cells unchanged writes nothing and does not count, so an all-unchanged request does not count and a retry after an error resumes without a 409. The over-cap request is refused whole (409 `import-state`, nothing written); after the cap any request, a bad body included, answers 409 (the state check runs before the field check, accepted in round 3). Counted under the import row lock; the guard trigger's DRAFT branch never lets a counter go down (I8). `line_request_count` stays in the table, unused (no rename, no drop). This also settles R3-4: the cap no longer depends on the batch size |
| R7-4 | R5-3 | **Starts per day** (DEC-077 item 3; R5-3 had left it optional). A person can start at most 10 new imports per store per IST day; the 11th answers 409 `import-limit` "Too many files today. Try again tomorrow." The count is taken under the store row lock in `start`; a `clientKey` replay is not a new start |
| R7-5 | D12 | **An owner may reject a DRAFT** (DEC-076). An OWNER (SETUP_EDIT; the OWNER holds the approve capability at every store) may reject an import in DRAFT as well as WAITING_APPROVAL, with the same reasons; any other state answers 409 `import-state`. The uploader keeps the right to replace their own draft. A rejected draft frees the store and its products at once (rule 14) and needs no `submitted_at`. The transition trigger allows DRAFT → REJECTED |
| R7-6 | D7, D8, masters | **Masters contract gaps** (DEC-078): the amount limit of an approval condition and of a preset limit is at most ₹ 9,99,99,999 (9,999,999,900 paise; the 0.1.x schema maximum was ten times the text); `GRN_RATE_RISE` takes a percentage only (ALWAYS or ABOVE_PERCENT); `GET /approval-rules` answers `presets[].limitTypes` (the server says which types need a limit); a series is locked (409 `series-locked`) once a number of the current financial year has been issued, so a new prefix starts with a financial year and every series stays consecutive (this narrows ADR-029 P9 "editing a series later is allowed"); reasons carry `fixed` and `receipt-reject.short` cannot be switched off; `POST /vendors/{vendorId}/switch-on`; vendors gain `damagedCreditPercent` and `expiredCreditPercent` (optional, nullable, used by SAH-79 WP-E vendor returns) |

**Consequences of revision 7**
- Positive: the open-file rule now holds under concurrency as R5-2 intended; the cap fits every batch size; a stuck draft can always be cleared by an owner; starts are bounded.
- Cost: one more product lock per line write (a share lock on a handful of rows) and one more store re-read in start, submit and approve; one new counter column and one insert trigger; two new 409 codes (`import-limit`, `series-locked`) that the client shows by their detail sentence.

**Data needed (db-engineer), revision 7** (one forward migration in `V2026_12_17_1270` to `1299`, after the SAH-79 WP-D agent's lock, DEC-077 Order)
- `pharmacy.opening_stock_import.line_rows_written integer not null default 0 check (line_rows_written >= 0)`; the DRAFT branch of the guard trigger refuses a decrease of `line_rows_written` and of `line_request_count`.
- Transition trigger: DRAFT → REJECTED allowed; `ck_opening_stock_import_submitted` relaxed only so that a REJECTED row that was never submitted needs no `submitted_at` (db-engineer's form; `ck_…_decision` kept).
- Insert trigger on `opening_stock_import`: the store must be ACTIVE.
- Starts limit: served by `ix_opening_stock_import_store (tenant_id, store_id, created_at desc)` filtered by `uploaded_by`; no new index unless EXPLAIN says so.
- Series lock: for a series that restarts each financial year, the counter row `(doc_type, fiscal_year = current)` exists exactly when a number of this year was issued, so no column is needed. A series that never restarts has one row with `fiscal_year = 0`, which does not say when its last number was taken; the recommended form is an additive column `document_counter.last_issued_at timestamptz null`, set by `pharmacy.next_document_no()` on every issue (the guard trigger allows it to move forward only), and the service locks the series row and compares `last_issued_at` with the start of the current financial year (1 April, 00:00 IST). The db-engineer may choose another form that gives the same answer without reading document tables of other features. Tenant scope, RLS and retention unchanged. No cross-module table access.

---

## Consequences

**Positive**
- No change to RLS, sessions or the existing module graph. P&I sits on top and nothing depends on it.
- Existing platform changes are limited to one role value, one column, one port method, one new port and the menu.
- The global catalogue reuses a reviewed pattern; sample data cannot reach production.
- Store access has one enforcement point with two clearly marked kinds (MASTER, OPERATION). Tenant isolation stays in the database.
- Standalone mode costs one resolver.
- Numbering, approvals and opening stock carry database checks behind every fixed rule.

**Negative / accepted costs**
- One unit per tenant in this release.
- Store scope is code; a missed guard call is an intra-tenant leak RLS will not catch. Mitigations: the guard class, the convention test and QA's store-scope matrix.
- A new team role touches every existing role gate in principle. Mitigation: gates are allow-lists, and QA runs a 403 sample for STORE_STAFF.
- Counter rows serialise documents of one series.
- Phase 3 needs new ports and migrations in opd, ipd and billing.
- Approved opening stock is not usable until SAH-79 posts it.
