# ADR-028 · Pharmacy & Inventory module: boundary, tenancy, global catalogue, store-scoped access, integration adapter, ledger direction, approval engine, numbering

Story: SAH-78 (Phase 1 Masters). Sets direction for SAH-79 (Inventory) and SAH-80 (Pharmacy).
Mockup screens: P&I mockup accepted as a design addition (DEC-061 Q1); split copies `docs/handoffs/PHI-input/screens/01..18`. Phase 1: screen 16 "Setup: stores, staff, products, vendors".
Module: `pharmacy` (new). Reads platform, patient, opd, ipd and billing only through their `api` packages.
Status: PROPOSED (17 Nov 2026). Needs the human's sign-off on every point marked **[TENANCY]**, **[AUTH]** or **[BOUNDARY]** before `docs/api/pharmacy.yaml` is written. The SAH-78 stories (BA, in progress) may add or narrow points. This ADR is revised, not replaced, if they do.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect.
Related: DEC-061 (programme answers), DEC-017 and DEC-049 (PHARMACIST role), ADR-002 (tenancy, audit, D1 branch as a column, D6 scope vs RLS, D7 audit port), ADR-010 and ADR-014 (staff PIN, colleague PIN), ADR-016 D3 (consumer-declared port), ADR-017 (single scope guard), ADR-022 D1 (module graph), ADR-027 (IPD orders), `docs/architecture/PHI-feasibility.md` (evidence, estimates).

---

## Context

1. DEC-061 accepted the P&I mockup and BRD as a design addition, outside the original phase-1 screen map. It also fixed the module shape (one module `pharmacy`) and the product master (P&I owns it; `opd.medicine` and `ipd.drug` unchanged; link in Phase 3).
2. The BRD asks for: "organization → unit" isolation; a global product catalogue shared read-only; access per store, not per user, with a working-store switch; four stock states and an append-only ledger; configurable approvals with fixed rules; numbering per unit; one adapter that a unit-level Integration mode switches off.
3. Built facts that constrain the answer (evidence in the feasibility report, sections 1 and 3):
   - The tenant is one clinic or hospital (`platform.tenant`), bound per transaction with `SET LOCAL`, with fail-closed RLS.
   - Branch was designed as a column, never an RLS key, and was never built.
   - Global reference data follows the `ipd.drug` pattern.
   - The module graph is a strict DAG checked by `ModularityTests`.
   - There is no durable event registry.
   - Counters use `INSERT … ON CONFLICT … RETURNING`.
   - Money is `bigint` paise.
   - `PHARMACIST` already exists as a membership role.
4. Phase 1 limits still hold: no gateway, notifications, documents service or analytics. No new library, framework or service without the human.

---

## Decisions

### D1 · Module boundary and dependency direction **[BOUNDARY]**

- New Spring Modulith module `com.sahaj.pharmacy`: schema `pharmacy`, base path `/api/v1/pharmacy`. Sub-packages: `api` (empty in Phase 1), `web`, `application`, `domain`, `infrastructure`, and `integration` (the adapter of D5, internal).
- Inside the module, features are grouped by package (`masters`, `catalogue`, `approval`, `numbering`; Phase 2 adds `stock`, `purchase`, `indent`; Phase 3 adds `sale`, `dispense`, `register`). They are one module because the ledger, sales and indents share stock rows (DEC-061 Q2).
- **Edges allowed:** `pharmacy → platform.api, patient.api, opd.api, billing.api, ipd.api`. **No module depends on `pharmacy`.**
- When an older module must ask pharmacy something, **the older module declares the port in its own `api` package and pharmacy implements it.** That is the precedent of `IpdOccupancyPort` (ADR-016 D3) and `EmrIpdOrderPort` (ADR-025 D8). Two such ports are foreseen:
  - `platform.api.PharmacyAccessPort`, for the menu (D11, Phase 1).
  - `ipd.api.DischargePharmacyClearance` (T7, Phase 3, only if the human allows the IPD change, OQ-PHI-10).
- New ports the other modules provide (Phase 3, each with its own story, owned by java-developer in that module's `api`):
  - `opd.api.PrescriptionQueueReader` (T2).
  - `ipd.api.PharmacyOrderFeed` (T4).
  - `ipd.api.PharmacyChargePort` (T5).
  - `ipd.api.AdmittedPatients` (T6).
  - `billing.api.ExternalBillLinePort` (T8).
- Build checks (java-developer, `ModularityTests`):
  - (a) no class outside `com.sahaj.pharmacy..` depends on `com.sahaj.pharmacy..`;
  - (b) `com.sahaj.pharmacy..` depends on other modules only through `..api..`;
  - (c) every `@Table` in `com.sahaj.pharmacy` declares `schema = "pharmacy"`.
- Options considered:
  - Pharmacy below ipd/billing (ipd and billing call pharmacy). Rejected: it reverses the built DAG and would make IPD and billing depend on an optional module.
  - Events in both directions. Rejected: not durable without a new library, and writes must be atomic (D5).

### D2 · Tenancy: the tenant stays the organization row; a unit is a pharmacy row, not an RLS key **[TENANCY]**

- RLS key unchanged: `tenant_id = platform.current_tenant()`, `NOT NULL DEFAULT`, `ENABLE` + `FORCE`, per-command policies for `sahaj_app`, verified as `sahaj_app` (ADR-002 D3).
- `pharmacy.unit` holds the BRD's unit settings (name, GSTIN, drug licences, Integration mode). In this release **exactly one unit per tenant**, enforced by a unique constraint on `tenant_id`. Lazily created on first write, with defaults served until then, like `platform.module_entitlement`.
- Every unit-owned pharmacy table carries `unit_id` as well as `tenant_id`, so a later multi-unit release is a constraint drop plus an application filter, not a backfill. `tenant_id` stays first in every index. `unit_id` is not part of any RLS policy.
- How "organization → unit isolation" is met:
  - Between tenants: database RLS, unchanged.
  - Inside a tenant: store mapping (D4). In this release a tenant has one unit, so a unit boundary has nothing to separate.
- If platform later builds branches (ADR-002 D1), `pharmacy.unit` gains `branch_id` 1:1. A unit is then a branch, and the mockup's "Organization: Sahaj Health · Unit: Sahaj Hospital, Baner" header maps to tenant name · unit name.
- Options:

| Option | Verdict |
|---|---|
| A · Unit = tenant (each unit its own tenant row) | Equal to the chosen option today, but the mockup's Later features (multi-branch command center, branch-to-branch transfer, screens 01 and 12) would become cross-tenant operations, which RLS forbids by design. Rejected as the long-term model |
| **B · Tenant = organization row as today; unit = pharmacy row + column; one unit per tenant now (chosen)** | No change to RLS, auth or platform. Additive path to several units per tenant |
| C · Second RLS key `app.unit_id` | Changes the tenancy model for one module, adds a second trusted session setting (ADR-002 consequence on `app.actor_kind`), and contradicts ADR-002 D1. Rejected |

Sign-off needed because the BRD says "unit isolation" and this ADR answers "not by RLS in this release" (OQ-PHI-1).

### D3 · Global product catalogue: shared, read-only, outside tenant RLS

- `pharmacy.catalogue_product` is global reference data:
  - no `tenant_id`;
  - RLS `ENABLE` + `FORCE`, with `FOR SELECT TO sahaj_app USING (true)` and a `sahaj_migrator` policy;
  - `REVOKE ALL` from `sahaj_app` and `PUBLIC`, then `GRANT SELECT` to `sahaj_app`;
  - no delete for any role (deny trigger on `DELETE` and `TRUNCATE`). A withdrawn product is `active = false`, set by migration.

  This is exactly the `ipd.drug` / `ipd.drug_code` pattern. The repository gates then pass without an allow-list. The global tables sit in **their own migration file**.
- Global fields: code (`G-nnnnnn`, stable), name, salt / generic, strength, dosage form, manufacturer, HSN, GST rate, schedule (`NONE`, `H`, `H1`, `X`; NDPS flag separate), purchase unit, issue unit, conversion factor, barcode(s), active. Read-only for every tenant.
- **A unit's product list is one tenant table, `pharmacy.product`:**
  - **Adopted** row: `catalogue_product_id` set; global fields null on the row. A check constraint makes the two kinds exclusive, and `unique (tenant_id, unit_id, catalogue_product_id)` prevents adopting twice.
  - **Own** row: `catalogue_product_id` null; the unit's own copy of the same field set.

  Local fields live on the same row or in a per-store child (D-data): alias, preferred vendor, MRP or selling rule, returnable Y/N, rack and reorder level per store. Global fields are never copied, so a catalogue correction reaches every adopter at once.
- Reads join global and unit rows **in the repository**. No database view, unless created `WITH (security_invoker = true)` (a migrator-owned view would run with the owner's rights).
- Duplicate check (AC-M5): a normalised key (salt, strength, form and manufacturer, each trimmed, lower-cased, spaces collapsed, strength units normalised such as "1 g" = "1000 mg") is computed in Java and stored as `dup_key`. Indexed in both tables. Before an own product is saved, the server answers the matching global products first (`409 possible-duplicate` with candidates, unless the request says "create anyway"; contract detail later).
- Promotion of an own product to the catalogue: Phase 1 stores only `review_state = SUBMITTED` ("Own products can be sent for review", screen 16). The Sahaj team promotes by a reviewed migration. An admin-portal screen is Later (OQ-PHI-5).
- Seed: a small demo seed taken from the mockup rows, commented "not clinically validated" like `ipd.drug`. The real catalogue source and licence are the human's choice before go-live (OQ-PHI-5).

### D4 · Identity and store-scoped access: platform role gate + store mapping, one guard class **[AUTH]**

- **No new platform role, permission key or session field.** Staff are existing memberships (`TeamMemberPort`). Store roles are pharmacy data.
- Layer 1, platform role gate (per operation in the contract):
  - Masters writes: `OWNER` (the BRD's Unit admin).
  - Masters reads: `OWNER`, plus any member with an active store mapping (D11 check).
  - The module entitlement must be on (D11), otherwise `403 module-off` (existing `ModuleOffException`).
- Layer 2, store mapping: `pharmacy.store_staff (tenant_id, unit_id, store_id, membership_id, store_role, capabilities…, active)`.
  - Store roles: `STORE_USER`, `APPROVER` (the store's HOD), `CENTRAL_STORE_USER`, `PHARMACIST`, `CASHIER`.
  - Delegate is not a role. It is the store's `delegate_membership_id` with `delegate_from`/`delegate_to` dates (IST), so "Delegate · till 30 Nov" is computed.
  - Capabilities as drawn on screen 16: raise indent, approve, receive, consume or return, Schedule X (pharmacist only). Phase 3 adds sell.
- **One class, `StoreAccessGuard`, decides store access** (pattern of ipd `ScopeGuard`, ADR-017):
  - Once per request, inside the transaction: re-read the membership through `TeamMemberPort` (inactive → `401/403` as today); load the caller's mapping for the store in the path.
  - Every store-scoped operation has `{storeId}` in its path. The server never trusts a "current store" from the client.
  - The guard runs before any filter, sort or paging parameter.
- Answers (consistent with ADR-002 D6):
  - Store id of another tenant: `404` (RLS).
  - Store of this tenant to which the caller has no mapping: `404 not-found` (does not confirm the store to an unmapped user).
  - Mapped but capability missing: `403 store-permission-denied`, audited.
  - Bad input: `400`.
- `OWNER`:
  - all stores for read and masters;
  - every store capability **except pharmacist-only actions** (Schedule X receive and issue, allergy or H1 override). The mockup's screen 05 matrix denies the owner the allergy override.
- Store role `PHARMACIST` may be given only to a membership whose platform role is `PHARMACIST` (registered pharmacist). Checked at mapping time and again by the guard.
- Working store:
  - The client keeps the selected store (component state, not the URL query or `localStorage`).
  - The server remembers the last one per member in `pharmacy.staff_preference` for convenience only.
  - `GET /context` returns the stores the caller is mapped to; switching is a client action plus an optional preference write. Every API still checks the path store.
- Fixed rule "nobody approves their own request" is enforced in the approval service **and** by a check constraint on the decision row (`decided_by <> requested_by`, denormalised) (D7).
- Options:
  - A new platform role per store role. Rejected: changes auth for all modules, and one membership has one role.
  - Store scope in RLS. Rejected for the same reason as ward scope (ADR-002 D6): the policy would need application facts, and a second trusted setting.

Sign-off needed: the 404-for-unmapped rule, OWNER's reach, and PHARMACIST-only mapping (OQ-PHI-3).

### D5 · Integration adapter and the Integration mode switch

- Internal interface `com.sahaj.pharmacy.integration.ClinicalLink` with two implementations, `IntegratedClinicalLink` (calls the ports of D1) and `StandaloneClinicalLink` (returns "not used", never throws).
- `ClinicalLinkResolver` chooses per request from `pharmacy.unit.integration_mode` (`INTEGRATED` | `STANDALONE`, default `STANDALONE` until set) and `ModuleEntitlementPort`. IPD off or read-only removes T4-T7 even when Integrated.
- Application services depend only on `ClinicalLink` and a `Capabilities` value. A review rule: no mode check outside the resolver.
- `GET /api/v1/pharmacy/context` returns `integrationMode`, `capabilities[]`, the unit, and the caller's stores and store roles. Angular shows only what is in `capabilities`: hidden, never greyed (BRD section 7).
- An Integrated-only operation in Standalone answers `409 integration-off`.
- **Writes back (charge, credit, dispense status) are synchronous port calls in pharmacy's transaction.** Stock and charge commit or roll back together (the reasoning of ADR-002 D7). No events for writes: Modulith events here are not durable, and a durable registry is a new dependency.
- Port failure (exception, `503 database-busy`, IPD read-only):
  - the operation that needed the link continues in standalone style when that is safe (a sale);
  - it fails cleanly when it is not (posting an IPD charge rolls back the IPD issue);
  - a bill keeps `link_state` (`NONE`, `LINKED`, `PENDING_LINK`) for "link to patient later".
- Mode change: OWNER only, audited (`PHARMACY_INTEGRATION_MODE_CHANGED`). Phase 3 adds "refused while integrated documents are open".
- In Phase 1 both implementations exist and **every integrated capability answers "not used"**. Only the switch and `/context` are live, which satisfies AC "Integration mode switch hides or shows OPD/IPD features" with nothing to hide yet. The BA should confirm that this is the Phase 1 reading of AC-M7.

### D6 · Stock ledger direction (built in Phase 2; fixed now so Phase 1 keys fit)

- `pharmacy.batch` (tenant, unit, product, batch no., expiry, MRP, purchase rate per issue unit, vendor, received at). Unique `(tenant_id, product_id, batch_no, expiry)`.
- `pharmacy.stock_ledger`: **append-only**.
  - Columns: occurred_at, transaction type, document type and id, line id, store, state, batch, product, `qty_in`, `qty_out` (issue units, integer), balance after, actor, reason key, `reverses_ledger_id`.
  - `GRANT SELECT, INSERT` only; select and insert policies only; `BEFORE UPDATE OR DELETE` and `TRUNCATE` deny triggers (`platform.deny_audit_mutation()`).
  - Range-partitioned by month from the first migration, with automated partition upkeep (DEC-049 answer 3).
  - Corrections are reversing lines.
- States: `AVAILABLE`, `IN_TRANSIT`, `PENDING_RETURN`, `QUARANTINE`. Only `AVAILABLE` is usable.
- `pharmacy.stock_balance (tenant_id, store_id, product_id, batch_id, state) → qty`:
  - a projection updated **in the same transaction** as the ledger line;
  - rows are locked in a fixed order (store, product, batch, state) to prevent deadlocks;
  - `check (qty >= 0)` makes "never negative" a database fact.

  A reconciliation check (a QA test and a report, no scheduled job) asserts balance = sum of ledger.
- FEFO: issue picks `AVAILABLE` balances of unexpired batches ordered by expiry, then received at. Expired batches are blocked at pick time and moved to `QUARANTINE` by the reader that finds them (no scheduler).
- Quantities are integers in the issue unit. Purchase quantities are converted at GRN (`purchase qty × conversion`). Fractional issue units are not supported (OQ-PHI-21 if needed).

### D7 · Approval engine shape (rules in Phase 1; runtime in Phase 2)

- Rule data per unit and transaction type (`PO`, `INDENT`, `TRANSFER`, `WRITE_OFF`, `STOCK_ADJUSTMENT`, `DISCOUNT`, `SALES_RETURN`, `OPENING_STOCK`):
  - `approval_rule_set` (current version, preset `LEAN` | `STANDARD` | `STRICT` | `CUSTOM`);
  - `approval_rule_version` (immutable once used);
  - `approval_rule_level` (level 1 or 2; approver kind `STORE_HOD` | `OWNER` | `PHARMACIST_IN_CHARGE`; conditions: store types, amount from/to in paise, item flags `SCHEDULE_X` / `HIGH_VALUE`, priority);
  - `escalate_after_minutes` per level and priority (defaults 120 / urgent 30; transfers 1440; OQ-PHI-14);
  - `approval_limit` (role × transaction type → max paise).

  Presets are data written by the service. Choosing a preset writes a new version.
- **Phase 2 runtime:**
  - `approval_request` snapshots the rule version at submission, so a rule change never moves in-flight items.
  - `approval_decision` is append-only (level, decided_by, acting_as `HOD` | `DELEGATE` | `OWNER` | `ESCALATED`, decision, reason key, time).
- Escalation is **computed when the inbox is read**: an item past its time shows for the next approver. No job, no notification (phase 1 rules).
- Delegation is active when today (IST) is within the store's delegate dates.
- Fixed rules (code + database; no setting can switch them off):
  - (1) `decided_by <> requested_by`;
  - (2) Schedule X: a `PHARMACIST` store role first, plus a second signature by a different person through `platform.api.ColleaguePinCheck` (`second_signer <> first_signer`, both stored);
  - (3) the ledger cannot be edited (D6).
- Phase 1 builds the rule editor and presets only. Opening-stock approval in Phase 1 is the fixed rule "OWNER approves", not the engine (OQ-PHI-9).

### D8 · Document numbering per unit: gapless for committed documents, race-free

- `pharmacy.document_series (tenant_id, unit_id, doc_type, prefix, separator, pad_width, reset YEARLY_FY | NEVER)`, one row per doc type. Defaults follow the mockup: `PO-1191`, `GRN-…`, `IND-6020`, `TRF-0440`, `DN-0219`, `AUD-0093`, `SB/018422`.
- `pharmacy.document_counter (tenant_id, unit_id, doc_type, fiscal_year) → next_value`. A number is taken with `INSERT … ON CONFLICT … DO UPDATE … RETURNING` **in the same transaction that inserts the document** (the `patient.next_uhid()` pattern, SECURITY INVOKER).
  - Concurrent saves serialise on the counter row: no race.
  - A rolled-back save rolls the counter back: no gap.
  - Cancelled documents keep their number (status, never deleted).
- Fiscal year is April-March (IST). The rendered number for a GST document (sale bill, debit note) must be at most 16 characters and unique per fiscal year. The series editor validates that. Legal confirmation is OQ-PHI-19.
- Editing a series after its first number is limited to the prefix for the next fiscal year.
- Offline counters would need per-counter blocks. That is out of scope (OQ-PHI-11).

### D9 · Audit

- Every master, setting, mapping, rule, series and mode change: `platform.api.AuditLog.record` in the same transaction, with before and after values (ids and field values, never patient text).
- Stock: the ledger. Approvals: `approval_decision`.
- Phase 3 dispensing reads of a patient write a record-access row through the patient module's access port, as ipd does.
- Logs carry tenant id, request id, ids and outcome codes only.

### D10 · Money and quantity conventions

- Document amounts (bill totals, line amounts, limits) are `bigint` paise, consistent with billing and ipd.
- Unit rates after conversion (purchase rate per issue unit, PTR, landed cost) are `numeric(14,4)` rupees, because a box price ÷ conversion is not whole paise.
- Line amount = round half-up to paise at line level. GST is computed per line on the taxable value.
- This deviates from `.claude/rules/postgresql.md` ("`numeric(12,2)` for money") exactly as the built modules already do. The human may update the rule (OQ-PHI-21).

### D11 · Sidebar entry and module switch **[AUTH]** (menu change)

- `platform.module_entitlement` gains `pharmacy_on boolean not null default false`, exposed through `ModuleEntitlementPort`.
- `NavigationPolicy` adds key `pharmacy` ("Pharmacy & Inventory") when `pharmacy_on` and the member is OWNER, or `PharmacyAccessPort.hasAnyStore(tenantId, membershipId)` is true. That port is declared in `platform.api` and implemented by pharmacy.
- Who switches `pharmacy_on`: recommended the Sahaj admin (a paid module, like IPD), through a small admin-portal change. Until then, test fixtures switch it on (OQ-PHI-4).
- One route `/app/pharmacy` with three tabs. Order per OQ-PHI-7. Only "Inventory setup" exists in Phase 1; the other tabs are not rendered until built.

### D12 · Phase 1 scope edges

- Opening stock: the BRD's Excel becomes a **CSV template** (DEC-061 default).
  - The browser reads the file and sends JSON batches (at most 200 rows per request, inside the 64 KB body cap).
  - The server validates every row (store, product, batch, expiry, MRP, rate, qty).
  - The import is staged (`DRAFT` → `SUBMITTED` → `APPROVED` by OWNER, fixed rule).
  - The file itself is never stored (no documents service).
  - Posting to the ledger is Phase 2 (OQ-PHI-9).
- Print templates: settings only (which fields and texts appear on invoice, issue slip, debit note). Printing is the browser's print view. No PDF library, no file storage.
- WhatsApp, OCR, ABDM: out (DEC-049, DEC-061).

---

## Consequences

**Positive**
- No change to RLS, sessions, platform roles or the existing module graph. P&I sits on top and nothing depends on it, so it can be switched off or removed without touching OPD/IPD.
- The global catalogue reuses a reviewed pattern and passes the existing gates.
- Store access has one enforcement point. Tenant isolation stays in the database.
- Standalone mode costs one resolver, not a code path per screen. The server enforces it as well as the UI.
- Ledger, balance and numbering are race-free and gapless by construction (transaction-scoped row locks), with database checks behind every rule ("never negative", "not your own request", "two different signers").

**Negative / accepted costs**
- One unit per tenant in this release. A hospital group with several premises needs several tenants until a multi-unit release.
- Store scope is code. A missed guard call is an intra-tenant leak that RLS will not catch. Mitigation: one guard class, a convention test that every `/stores/{storeId}/**` handler calls it, and QA's store-scope matrix.
- Counter rows serialise documents of one series. Fine at counter speed; a hot hospital pharmacy may need a series per counter later.
- Phase 3 needs new ports and migrations in opd, ipd and billing. Two of them (T5 credit lines, T7 clearance) change built IPD behaviour and need the human.
- Free-text allergy matching gives imperfect T3 checks.
- Six Phase 1 masters have no drawn screen. Until the human decides, the BA cannot write those stories (OQ-PHI-6).

---

## Data needed (for the db-engineer): Phase 1, provisional until the SAH-78 stories are approved

Conventions for every tenant table:
- `id uuid pk default gen_random_uuid()`;
- `tenant_id uuid not null default platform.current_tenant()` with FK to `platform.tenant`;
- `unit_id uuid not null` (same-tenant composite FK to `pharmacy.unit (tenant_id, id)`);
- `created_at/by`, `updated_at/by`, `version`;
- `ENABLE` + `FORCE` RLS, per-command policies for `sahaj_app`, **no delete grant**;
- `tenant_id` first in every index; `timestamptz`; text with `check` constraints.

No cross-module FK except `platform.tenant` (and `patient.patient` in Phase 3). Membership ids and IPD ward ids are opaque `uuid`s checked through ports.

| Entity | Key fields | Notes |
|---|---|---|
| `unit` | name, GSTIN (15 chars, format check), retail drug licence no. and expiry, Schedule X licence no. and expiry (nullable), integration_mode (`INTEGRATED`/`STANDALONE`), fefo (fixed true, OQ-PHI-8), block_negative (fixed true) | `unique (tenant_id)` in this release (D2). Lazy row |
| `store` | name, code (unique per unit), type (`CENTRAL`, `PHARMACY`, `SUB_STORE`, `WARD`), issues_from_store_id (same unit; null only for CENTRAL), sale_allowed, consumption_allowed, hod_membership_id, delegate_membership_id, delegate_from, delegate_to, ipd_ward_id (opaque, Integrated only), status (`ACTIVE`/`INACTIVE`) | One CENTRAL per unit (partial unique index, OQ-PHI-13). No cycles in issues_from (service check; depth 1 in this release since all issue from Central) |
| `store_staff` | store_id, membership_id, store_role, can_raise_indent, can_approve, can_receive, can_consume_return, can_schedule_x, status | `unique (tenant_id, store_id, membership_id)`. Index `(tenant_id, membership_id)` for the guard and the menu port. `can_schedule_x` only with role PHARMACIST |
| `staff_preference` | membership_id, last_store_id | Convenience only |
| `catalogue_product` (global) | code, name, salt, strength, form, manufacturer, hsn, gst_rate, schedule, ndps, purchase_unit, issue_unit, conversion, active, dup_key | No tenant_id; `ipd.drug` RLS pattern; own migration file; trigram GIN on name and salt; index on dup_key |
| `catalogue_barcode` (global) | code, catalogue_product_id | Same pattern |
| `product` | catalogue_product_id (null = own), own fields (as global, null when adopted), alias, preferred_vendor_id, mrp_rule, returnable, review_state (`NONE`/`SUBMITTED`), status, dup_key | Exclusive-kind check; `unique (tenant_id, unit_id, catalogue_product_id)`; trigram GIN on name, salt and alias; unique `(tenant_id, barcode)` for own barcodes |
| `product_store_setting` | product_id, store_id, reorder_level, rack | `unique (tenant_id, product_id, store_id)` |
| `vendor` | name, GSTIN, drug_licence_no, drug_licence_expiry, licence_not_required, contact name and phone, credit_days, return_days_before_expiry, return_credit_pct, status | Blocked state derived from expiry (IST date), never stored |
| `vendor_store` | vendor_id, store_id | "Supplies to" |
| `approval_rule_set`, `approval_rule_version`, `approval_rule_level`, `approval_limit` | as D7 | Versions immutable once referenced (trigger) |
| `document_series`, `document_counter` | as D8 | Counter: select, insert, update only; function `pharmacy.next_document_no(doc_type)` |
| `reason` | list (`RETURN`, `DAMAGE`, `ADJUSTMENT`, `REJECTION`, `SUBSTITUTION`, `OVERRIDE`), key, label, active, sort | Global default rows (reference) + tenant rows; clients get the merged list |
| `print_template_setting` | doc_type (`INVOICE`, `ISSUE_SLIP`, `DEBIT_NOTE`), show_* flags, footer text (≤ 300) | One row per doc type |
| `opening_stock_import`, `opening_stock_line` | import: status, submitted_by/at, approved_by/at (≠ submitted_by). Line: store_id, product_id, batch_no, expiry, mrp, purchase_rate, qty, row_no, errors | Kept after approval as evidence; posting in Phase 2 |
| `platform.module_entitlement.pharmacy_on` | boolean, default false | Platform schema, additive |

Retention: masters are never deleted (status). Import lines are kept. Ledger, registers and approvals are kept until the human sets legal periods (OQ-PHI-19), and are removed only by partition drop, never `DELETE`.

---

## Open questions for the human (each with a recommendation)

| ID | Flag | Question | Recommendation |
|---|---|---|---|
| OQ-PHI-1 | **[TENANCY]** | Is a unit the tenant, a column under it, or a second RLS key? | D2 option B: tenant stays the clinic or hospital row; unit is a pharmacy row and column, one per tenant now; no RLS change |
| OQ-PHI-2 | **[BOUNDARY]** | Accept pharmacy on top of the module graph, nothing depending on it, with consumer-declared ports for menu and discharge? | Yes (D1) |
| OQ-PHI-3 | **[AUTH]** | Store-scoped access: no new platform roles; 404 for an unmapped store, 403 for a missing capability; OWNER all stores except pharmacist-only actions; PHARMACIST store role only for PHARMACIST members | Accept D4 as written |
| OQ-PHI-4 | **[AUTH]** (menu) | Who sees "Pharmacy & Inventory" and who switches the module on? | `pharmacy_on` entitlement set by the Sahaj admin; entry for OWNER and mapped staff; admin-portal toggle as a small ADM story |
| OQ-PHI-5 | | Global catalogue: data source and licence; who promotes own products | Demo seed now ("not clinically validated"); human picks the real source before go-live; promotion by the Sahaj team via reviewed migration; admin screen Later |
| OQ-PHI-6 | | Screen 16 does not draw unit settings, approval rules, numbering, reasons, print templates or opening stock; screen 05 draws a different approval shape | Accept BRD sections 2-3 as the design for those six (BA writes them against "Inventory setup" with BRD references) and use the BRD approval model; screen-05 matrix becomes a Phase 3 read-out. Or have the screens drawn first |
| OQ-PHI-7 | | Tab order: mockup "Pharmacy · Inventory · Inventory setup" vs BRD "Inventory setup · Inventory · Pharmacy" | BRD order; unbuilt tabs not rendered |
| OQ-PHI-8 | | "FEFO on/off" and "negative stock blocked" settings vs non-negotiables | Fixed On, shown as facts, no switch |
| OQ-PHI-9 | | Opening stock needs batches and the ledger (Phase 2) | Phase 1 stages, validates and approves; posting is the first SAH-79 story |
| OQ-PHI-10 | **[BOUNDARY]** | T5 (charges in the IPD medication chart, credit lines in `ipd.admission_charge`), T7 (clearance gate before the final bill), T2 (dispensed status in OPD) change built OPD/IPD behaviour that the BRD says must not change | Phase 3 decides each with its own story. Default: post charges and credits through an IPD port (needs an IPD migration); no MAR change; clearance shown as information, not a gate |
| OQ-PHI-11 | | Offline billing | Out of SAH-80; own design story; expect a new frontend package (`@angular/service-worker`) and per-counter number series |
| OQ-PHI-12 | | Pharmacy bill separate or added to the OPD bill by default (BRD Q1) | Separate by default; "add to OPD bill" unit setting in Phase 3 |
| OQ-PHI-13 | | More than one Central Store per unit (BRD Q4) | One in this release |
| OQ-PHI-14 | | Escalation times 2 h / 30 min / 24 h (BRD Q3) | Defaults as stated, configurable per rule, computed at read |
| OQ-PHI-15 | | Sales return window and refund modes per unit or per organization (BRD Q6) | Per unit |
| OQ-PHI-16 | | One receipt per dispatch or several per indent (BRD Q2) | Several dispatches per indent; one receipt per dispatch |
| OQ-PHI-17 | | Multi-branch command center and branch-to-branch transfer (screens 01, 12) | Later |
| OQ-PHI-18 | | Three dev lanes vs one writer per area | Phase 1 as three sequential work packages with Java and Angular in parallel; decide worktrees or a lock change before Phase 2 |
| OQ-PHI-19 | | Legal: register retention, GST invoice series rules, licence types, NDPS vs Schedule X register | Confirm with a compliance adviser before Phase 3; nothing is deleted meanwhile |
| OQ-PHI-20 | | Pharmacist registration number and validity (screen 05) are not stored anywhere | Pharmacy-owned record on the PHARMACIST store mapping, Phase 3 |
| OQ-PHI-21 | | Money as `bigint` paise and unit rates as `numeric(14,4)` vs postgresql.md `numeric(12,2)`; integer issue units | Accept D10; update postgresql.md |
