# Pharmacy & Inventory (P&I): feasibility report

Story: SAH-78 (Phase 1 Masters). Also covers SAH-79 (Phase 2 Inventory) and SAH-80 (Phase 3 Pharmacy) at feasibility level.
Mockup screens: P&I mockup, accepted as a design addition by DEC-061 (split copies in `docs/handoffs/PHI-input/screens/01..18`). Phase 1 uses screen 16 "Setup: stores, staff, products, vendors".
Module: `pharmacy` (new, DEC-061 Q2). Design decisions: `docs/architecture/adr/ADR-028-pharmacy-inventory-module.md`.
Author: solution-architect, 17 Nov 2026. Status: for the human's review. No code, contract or migration is written until the stories and `contract` gates are approved.

This answers the 8 points of BRD section 9 (`docs/handoffs/PHI-input/brd.txt`, lines 470-556). The BRD asked for `docs/pharmacy-inventory-feasibility.md`. It is written here instead, because `docs/` root is not an agent folder and `docs/architecture/` is the architect's.

---

## 0. Verdict in one paragraph

P&I is feasible on the current stack with **no new library, framework or external service** for Phases 1 and 2. Phase 3 is feasible too, except **offline billing**, which needs its own design and very likely a new frontend package (escalated, OQ-PHI-11). The codebase already has the patterns P&I needs: RLS with `FORCE`, global read-only reference tables, race-free per-tenant counters, append-only tables with deny triggers, a synchronous audit port, a single scope-guard class, a personal staff PIN plus a colleague PIN for two-person checks, and consumer-declared ports that keep the module graph acyclic. Three areas carry real risk:

1. **Tenancy wording.** The BRD says "organization → unit". Today the tenant is one clinic or hospital. ADR-028 D2 maps a unit onto that without changing RLS. This needs the human's sign-off.
2. **The BRD contradicts itself, and the mockup and code, on OPD/IPD.** It wants one shared product master and posting into IPD screens, but also "never change OPD/IPD screens or workflows". DEC-061 settled the master. T5 and T7 still collide (section 7.1).
3. **The mockup does not draw six of the Phase 1 masters.** Unit settings, approval rules, numbering, reasons, print templates and opening stock appear only in the BRD text (section 7.2). Under CLAUDE.md rule 1, the human must accept the BRD text as the design for these, or have them drawn.

---

## 1. Stack and structure

| Item | Finding | Evidence |
|---|---|---|
| Backend | One Spring Boot 3.5.16 app, Java 17, Spring Modulith 1.4.14 | `backend/pom.xml` lines 9-10, 23 |
| Modules | Six packages `com.sahaj.{platform,patient,opd,ipd,hra,billing}`. Each exposes only its `api` sub-package | `backend/src/main/java/com/sahaj/*/package-info.java`; ADR-001 D1/D2 |
| Module graph (enforced) | platform ← patient ← opd ← billing ← ipd. `billing` never imports `ipd`. `opd` never imports `billing` or `ipd`. `ipd` reaches `billing` only through `billing.api` | `backend/src/test/java/com/sahaj/ModularityTests.java` |
| Cross-module style | Synchronous ports in `<module>.api`, run in the caller's transaction. When the "lower" module needs the "higher", the **consumer declares the port and the provider implements it**, e.g. `opd.api.EmrIpdOrderPort` (implemented by ipd), `opd.api.EmrVisitChargePort` (implemented by billing), the admin `IpdOccupancyPort` (platform.api, implemented by ipd) | `backend/src/main/java/com/sahaj/opd/api/EmrIpdOrderPort.java`, `EmrVisitChargePort.java`; ADR-016 D3 |
| Messaging | No broker. One in-process domain event exists (`opd.api.events.EmrRecordSigned`) with no listener. **No event publication registry** (no `spring-modulith-events-*`), so events are not durable | `backend/src/main/java/com/sahaj/opd/api/events/EmrRecordSigned.java`; `backend/pom.xml` (no events artifact) |
| Database | One PostgreSQL 16 database, one schema per module, created by `scripts/db/schemas.sql` (human-owned; `pharmacy` is not in the list yet). Flyway runs as `sahaj_migrator` and does not create schemas | `scripts/db/schemas.sql` line 8; `backend/src/main/resources/application.yml` lines 49-58 |
| Tenancy | `tenant_id = platform.current_tenant()` default, `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy per command for `sahaj_app`, bound by `SET LOCAL` per transaction from the session. The tenant is one clinic or hospital. Branch was designed as a column, never as an RLS key, and **no branch table was ever built** | ADR-002 D1-D4; `V2026_11_01_1001__platform_create_tenant.sql`; `V2026_11_01_1003__platform_create_membership.sql` |
| Global reference data | No `tenant_id`; RLS on with `FOR SELECT TO sahaj_app USING (true)` plus a migrator policy; `SELECT`-only grant; changed by migration only | `V2026_11_28_1000__ipd_medicine_orders.sql` (ipd.drug, section 3b); `V2026_12_15_1000__ipd_nursing_orders_to_execution.sql` (ipd.drug_code) |
| Counters | One row per tenant, `INSERT … ON CONFLICT DO UPDATE … RETURNING` in the caller's transaction (row lock, race-free, gapless for committed rows) | `V2026_11_01_1038__patient_create_uhid_sequence.sql`; `billing.receipt_counter` in `V2026_12_10_1200__billing_course_account_and_emr_bill_lines.sql` |
| Money | `bigint` paise everywhere in built code (billing, ipd). `.claude/rules/postgresql.md` says `numeric(12,2)` | `V2026_11_01_1042__billing_create_billing_counter.sql` header; `V2026_11_14_1000__ipd_admission_charge.sql` |
| Limits | Request body 64 KB signed-in (256 KB for form saves); statement timeout 30 s; lock timeout → 503 `database-busy` (DEC-054) | `RequestBodySizeLimitFilter.java` lines 50-57; `application.yml` lines 39-42, 82 |
| Search | `pg_trgm` extension already installed (patient name search) | `V2026_11_01_1039__patient_create_patient.sql` line 50 |
| Frontend | Angular 22.2 with Angular Material 22.2; standalone components; lazy feature routes under `/app` | `frontend/package.json`; `frontend/src/app/app.routes.ts`; `frontend/src/app/features/shell/shell.routes.ts` |

---

## 2. Shell, sidebar, routing: how a module is registered

| Piece | Where | How P&I plugs in |
|---|---|---|
| Menu keys (server, source of truth) | `backend/src/main/java/com/sahaj/platform/application/NavigationPolicy.java` builds `/me.menu` per role and module flags (IPD, HRA, Second checks) | Add key `pharmacy`. Platform cannot read pharmacy tables, so it asks a new port **declared in `platform.api` and implemented by `pharmacy`** ("does this member have any store access?"), the same pattern as `IpdOccupancyPort`. The module switch comes from `platform.module_entitlement` (new column). See ADR-028 D11 and OQ-PHI-4 |
| Menu items (client: order, icon, route) | `frontend/src/app/core/shell/nav.config.ts` (`NAV_ITEMS`) | One entry: key `pharmacy`, label "Pharmacy & Inventory", route `pharmacy` |
| Route guard | `frontend/src/app/core/shell/menu.guard.ts` (`menuGuard(key)`) | `canActivate: [menuGuard('pharmacy')]` |
| Lazy route | `frontend/src/app/features/shell/shell.routes.ts` (e.g. `billing`, `ipd`) | `path: 'pharmacy'` → `features/pharmacy/pharmacy.routes.ts`, with three child tabs |
| Shared UI | `frontend/src/app/shared/ui/page-head.component.ts`, `empty-state.component.ts`, `sheet-toast.ts`; formats `shared/format/inr.ts`, `ist-date.ts`, `ist-clock.ts`; icons `shared/icon/icon.component.ts` | Reused as is. The mockup's own rail and panel (`sx-rail`, `sx-panel`) are **not** copied (BRD section 7) |
| Existing placeholder | `frontend/src/app/features/config/modules/modules.page.html` lines 72-77: "Pharmacy and Lab · Phase 2" pill | To be updated by the angular-developer when the module ships (wording comes from the stories) |

Inside the module, the mockup's secondary panel (tabs, screen list, working-store picker in its footer) becomes a module header in the main content area: tab bar, then the screen, then the working-store picker. The tab order conflicts between mockup and BRD (OQ-PHI-7).

---

## 3. OPD/IPD data P&I can reuse

Status: **exists** = usable through an `api` port today; **partial** = data exists but no port, or the port lacks fields; **missing** = no data.

| Data | Table (owner) | API / event today | Status | Evidence |
|---|---|---|---|---|
| Patient identity (UHID, name, age, sex) | `patient.patient` | `patient.api.PatientLookupPort.find / findAllCurrent` | exists | `backend/src/main/java/com/sahaj/patient/api/PatientLookupPort.java` |
| Patient mobile | `patient.patient` | `PatientLookupPort.mobilesOf` (explicit call; never logged) | exists | same file, lines 55-63 |
| ABHA number | `patient.patient.abha_linked` (boolean only) | none | **missing** (no ABHA number stored; ABDM is Later) | `V2026_11_01_1039__patient_create_patient.sql` line 30 |
| Allergy list | `patient.patient` allergy text (free text, not coded) | `patient.api.PatientClinicalPort.find` (`allergyText`, `allergyStatus()`) | partial: free text, not salt-coded. Salt matching needs a keyword or class rule like IPD's | `backend/src/main/java/com/sahaj/patient/api/PatientClinicalPort.java`; `AllergyStatus.java`; DEC-017 review S1/S4 |
| Patient read permission for PHARMACIST | platform/patient | `PatientRoles.READ` includes PHARMACIST and BILLING | exists | DEC-049 answer 2 |
| OPD prescription (signed e-Rx) | `opd.emr_record` (section `rx`: drug from `opd.medicine`, dose pattern, duration, timing, instructions) | `opd.api.SignedEmrRecordReader.read(recordId)` (sections JSON, no caller yet); event `EmrRecordSigned` (not durable) | partial: no "signed prescriptions waiting for pharmacy" list, **no quantity to dispense**, drug is `opd.medicine` (no product link) | `SignedEmrRecordReader.java`; `V2026_12_08_1020__opd_emr_seed_library.sql` lines 36, 163-167 |
| Prescriber name and registration no. | `platform.doctor_profile` | `platform.api.TeamMemberPort.doctorDetails` | exists (needed for the H1 register) | `TeamMemberPort.java` lines 41-56 |
| OPD drug list | `opd.medicine` (global: name, form, schedule, usual dose) | read inside opd only | exists, but has no salt, strength, HSN, GST, Schedule or UOM | `V2026_11_01_1033__opd_create_medicine.sql` |
| IPD admission, ward, bed | `ipd.admission`, `ipd.ward`, `ipd.bed` | none in `ipd.api` (package-info only) | partial: data exists, no port | `V2026_11_11_1000__ipd_wards_rooms_beds.sql`; `V2026_11_12_1000__ipd_admission_and_deposits.sql`; `backend/src/main/java/com/sahaj/ipd/api/package-info.java` |
| IPD medication orders (new, changed, stopped; priority) | `ipd.medicine_order` + `ipd.medicine_order_version` (priority ROUTINE / URGENT / STAT; versions; hold; stop) | none for other modules (`EmrIpdOrderPort` goes the other way: opd → ipd) | partial: data and priorities exist; no reader port; drug is `ipd.drug` key; "Discharge" is not an order priority | `V2026_11_28_1000__ipd_medicine_orders.sql`; ADR-027 D1 |
| IPD drug list | `ipd.drug` (14 global rows, mockup demo content), `ipd.drug_code` (barcodes, empty) | inside ipd only | exists, no product link | same migrations |
| IPD running bill | `ipd.admission_charge` (append-only; `service_key` is a closed list of 10 values; qty 1-50; no credit line) | none for other modules | partial: no port; no pharmacy service key; **no credit or reversal line** ("Reversal comes with discharge/billing", table comment) | `V2026_11_14_1000__ipd_admission_charge.sql` |
| IPD discharge and final bill | `ipd.discharge`, `ipd.discharge_signoff`, `ipd.final_bill(_line)` | none | partial: no clearance step, no take-home medicine list | `V2026_11_18_1000__ipd_discharge.sql` |
| OPD bill | `billing.bill` (one per visit), `billing.bill_item`, `billing.payment` (append-only) | `opd.api.EmrVisitChargePort` (opd → billing); `billing.api.CourseCreditPort` (ipd → billing) | partial: no port for another module to add lines to a visit bill | `V2026_11_01_1042__billing_create_billing_counter.sql`; `EmrVisitChargePort.java` |
| Team members, roles, names | `platform.membership` (role list includes PHARMACIST and BILLING) | `TeamMemberPort`, `ActorDirectoryPort` | exists | `V2026_11_01_1003__platform_create_membership.sql`; `TeamMemberPort.java`; `ActorDirectoryPort.java` |
| Personal PIN, colleague PIN (two-person check) | `platform.staff_pin`, `platform.staff_pin_colleague_event` | `platform.api.StaffPinPort`, `ColleaguePinCheck`, `PinCheck` | exists (reusable for the Schedule X second signature) | ADR-010, ADR-014; files in `backend/src/main/java/com/sahaj/platform/api/` |
| Audit | `platform.audit_event` (append-only) | `platform.api.AuditLog.record(...)` (synchronous, same transaction) | exists | `AuditLog.java`; ADR-002 D7 |
| Module switches | `platform.module_entitlement` (IPD state, HRA, bed limit) | `platform.api.ModuleEntitlementPort` | exists (needs a pharmacy column) | `V2026_11_08_1000__platform_module_entitlement.sql`; `ModuleEntitlementPort.java` |
| Clinic letterhead (print) | platform clinic profile | `platform.api.ClinicLetterheadPort` | exists (reusable for invoice header) | `ClinicLetterheadPort.java` |

---

## 4. Touch points T1-T8

Effort: S ≤ 3 developer-days, M ≤ 2 weeks, L > 2 weeks (backend plus frontend, excluding QA). "Other side" = what the OPD/IPD/billing module must add in its own `api` package. That code is owned by the java-developer, and it is new code in another module's `api` package, so it needs its own story and review.

| # | Touch point | How to connect | Effort | Risk | Missing on the other side |
|---|---|---|---|---|---|
| T1 | Patient | Read through `PatientLookupPort` / `PatientClinicalPort` from the pharmacy integration adapter. Standalone: pharmacy-owned customer list. Bill keeps a nullable `patient_id` (FK to `patient.patient` is allowed by the rules) plus a typed walk-in name and phone | S | Low. Privacy: a pharmacy copy of name and phone for walk-ins is patient data in the `pharmacy` schema (RLS, never logged) | ABHA number is not stored (Later with ABDM). Nothing else |
| T2 | OPD e-Rx queue | New port in `opd.api` (declared and implemented by opd): `PrescriptionQueueReader` listing signed records with an `rx` section for a day, and the lines (opd medicine id or name, dose pattern, duration). Pull at read time, no event (the event is not durable). Map `opd.medicine` → pharmacy product through a pharmacy-owned link table (Phase 3, DEC-061 Q3) | M | Medium: dispense quantity is not in the Rx (it must be derived from pattern × duration, or typed by the pharmacist); mapping gaps show as "not mapped" lines | Reader port; a "dispensed" status per Rx line (written back only through a port opd owns). Opd must decide whether it shows that status. If it does, that is an OPD screen change (OQ-PHI-10) |
| T3 | Allergy | `PatientClinicalPort.find` at sale; match the product's salt against allergy text with a keyword rule (same approach as IPD SAH-31) | S | Medium: free-text allergy gives false negatives and positives. A block plus pharmacist override with reason (BRD) is the safe direction | Coded allergies would be better; not required |
| T4 | IPD order queue | New port in `ipd.api` (declared and implemented by ipd): `PharmacyOrderFeed` giving live orders since a change token, with admission, ward, bed, `ipd.drug` key, version, priority and status. Pharmacy polls (no push in phase 1). Map `ipd.drug` → product (Phase 3 link table) | M | Medium: "Discharge" priority and take-home medicines do not exist in ipd | Feed port; take-home medicine list on discharge |
| T5 | IPD issue and return | New port in `ipd.api` implemented by ipd: `PharmacyChargePort.post(admissionId, lines)` and `.credit(...)`, in pharmacy's transaction, so stock and charge commit together. Needs a pharmacy service key and a credit or reversal line in `ipd.admission_charge` (today: closed key list, qty 50 max, no reversal) | M-L | High: changes an append-only IPD money table and the running-bill and final-bill totals. The mockup also shows items "appear in the nursing medication chart with the batch number" (screen 03, line 188), which is an IPD MAR screen change | Port, migration (new key, credit line, qty range), final-bill totals. Human must allow the IPD change (OQ-PHI-10) |
| T6 | Ward indent and consumption | Wards are pharmacy stores of type WARD. In Integrated mode a ward store carries the opaque `ipd.ward` id (no FK). Patient-wise consumption picks an admitted patient through an `ipd.api` reader `AdmittedPatients.inWard(wardId)` | S-M | Low | Reader port only |
| T7 | Discharge clearance | Port **declared in `ipd.api`, implemented by pharmacy** (`DischargePharmacyClearance.status(admissionId)`), so ipd never imports pharmacy. Ipd would call it before the final bill | M | High: adds a gate to the built IPD discharge workflow (SAH-27), which BRD section 6 says must not change | Discharge step change and screen text. Needs the human (OQ-PHI-10). Recommended default: information only, no gate |
| T8 | OPD billing | Default: the pharmacy bill stands alone (pharmacy owns bills, BRD section 6). Option "add to OPD bill": new port in `billing.api` implemented by billing (`ExternalBillLinePort.addLines(visitId, sourceKey, lines)`), same shape as `EmrVisitChargePort.addPricedLines` | S (alone) / M (added) | Medium for "added": two places collect money for the same goods; refunds must stay consistent | Port in billing; `bill_item.qty` max 20 and no source column for pharmacy lines |

Allowed dependency edges after P&I (ADR-028 D1): `pharmacy → platform.api, patient.api, opd.api, billing.api, ipd.api`. **Nothing depends on `pharmacy`**. The two places where an older module must ask pharmacy something (menu, discharge clearance) use ports that the older module declares.

---

## 5. Standalone mode: one switch, no screen code change

Design: **one adapter interface + one resolver + a capability list sent to the client.** ADR-028 D5 has the full decision.

- `com.sahaj.pharmacy.integration.ClinicalLink` (internal interface): `findPatient`, `allergyFor`, `opdPrescriptions`, `ipdOrders`, `admittedInWard`, `postCharge`, `postCredit`, `dischargeStatus`, `addToOpdBill`.
- Two implementations: `IntegratedClinicalLink` (calls the ports in section 4) and `StandaloneClinicalLink` (answers "not used": empty lists, `NOT_APPLICABLE`; never throws).
- `ClinicalLinkResolver` picks one per request from `pharmacy.unit.integration_mode` **and** the module entitlements (`ModuleEntitlementPort`: IPD off or read-only switches T4-T7 off even in Integrated mode). Application services only ever see `ClinicalLink`. No `if (integrated)` anywhere else (a review rule).
- `GET /api/v1/pharmacy/context` returns `integrationMode` and a capability list (`opdQueue`, `ipdQueue`, `ipdCharges`, `patientLink`, `allergyCheck`, `dischargeClearance`, `addToOpdBill`). Angular renders from the capabilities: a feature that is off is **not rendered** (BRD: "hidden, not greyed out"). Screen code has no mode check, only capability checks.
- The server enforces the same list: an Integrated-only endpoint called in Standalone mode answers `409 integration-off`, so the UI is never the only control.
- "OPD/IPD unreachable": in a monolith that means a port call failed (exception, 503 `database-busy`, IPD switched to read-only). The resolver marks that call as **degraded**: the sale continues in standalone style, the bill keeps `link_state = PENDING_LINK`, and a "link to patient" action completes it later. No retry daemon or queue (none exists).
- **No domain events for writes.** There is no durable event registry, and adding one is a new dependency (`spring-modulith-events-jpa`). Charges must commit with the stock movement, which is the reasoning ADR-002 D7 used for audit. Events may be used later for derived signals only.
- Changing the mode is a unit-admin action, audited. Phase 3 adds a guard: no switch to Standalone while integrated documents are open (unposted IPD issues, unlinked bills).

---

## 6. Existing pharmacy, store, item or inventory code

| Found | Verdict |
|---|---|
| No pharmacy, stock, batch, vendor, store or ledger code in backend or frontend (search of `backend/src/main` and `frontend/src` for pharmacy/inventory/stock/vendor/batch finds only IPD MAR and EMR texts) | Build new in `com.sahaj.pharmacy` |
| `opd.medicine` (global drug list for OPD prescribing) | **Keep unchanged** (DEC-061 Q3). Phase 3 adds a pharmacy-owned link table `product ↔ opd.medicine id` (opaque id, no FK) |
| `ipd.drug`, `ipd.drug_code`, `ipd.drug_interaction` | **Keep unchanged.** Phase 3 adds a link `product ↔ ipd.drug key`. Reuse the barcode idea (`drug_code`), not the table |
| `platform.membership` role PHARMACIST, PHARMACIST on the IPD menu (DEC-017 Q2) and in patient read (DEC-049) | **Reuse.** The store role "Pharmacist" requires membership role PHARMACIST (ADR-028 D4) |
| IPD pharmacist check (SAH-31, SAH-76: verify, reject, query, review) | **Reuse as is.** It is clinical verification, not dispensing; P&I's IPD queue (T4) reads orders after verification |
| `platform.api` `StaffPinPort`, `ColleaguePinCheck` | **Reuse** for Schedule X second signature and stock-audit two signatures |
| `platform.api.AuditLog` | **Reuse** for every master and approval change |
| Counter pattern (`patient.next_uhid()`, `billing.receipt_counter`) | **Reuse the pattern** for document numbering (ADR-028 D8) |
| `ScopeGuard` (ipd, ADR-017) | **Reuse the pattern**: one `StoreAccessGuard` class decides store access (ADR-028 D4) |
| `billing.day_closing`, `billing.payment` | Pattern only. Pharmacy owns its own bills and day close (BRD section 6), so no shared table |
| Clinic config page "Pharmacy and Lab · Phase 2" | Refactor text later (angular-developer, from stories) |

---

## 7. Gaps and risks

### 7.1 Conflicts between BRD, mockup and built code (human decisions)

| # | Conflict | Recommendation | OQ |
|---|---|---|---|
| C1 | BRD: one product master feeds OPD/IPD prescribing. BRD: never change OPD/IPD. Built: `opd.medicine`, `ipd.drug` | Decided by DEC-061 Q3: P&I owns its master; link tables in Phase 3 | closed |
| C2 | BRD T5 and mockup screen 03 (charges show in the nursing medication chart with batch) vs "never change IPD screens" | Phase 3: post the charge only; no MAR change unless the human approves an IPD story | OQ-PHI-10 |
| C3 | BRD T7 "pharmacy clearance before final bill" adds a gate to the built IPD discharge | Information only by default; a gate only with an approved IPD story | OQ-PHI-10 |
| C4 | BRD M2 "FEFO on/off" and "negative stock blocked" settings vs BRD section 8 non-negotiables "FEFO everywhere; stock never goes negative" | Both fixed On; shown as facts, no switch | OQ-PHI-8 |
| C5 | Tab order: mockup "Pharmacy · Inventory · Inventory setup"; BRD section 7 "Inventory setup · Inventory · Pharmacy" | BRD order (newer; matches build order); unbuilt tabs hidden | OQ-PHI-7 |
| C6 | Approvals: BRD per transaction type (levels, conditions, ₹ limits, delegates, escalation). Mockup screen 05 draws a role × action matrix (Allow / Needs approval / Deny / "Up to ₹1,000" / "Same day only"), and screen 14 says limits are set there | BRD model for the engine (ADR-028 D7); the screen-05 matrix becomes a Phase 3 read-out of the same rules | OQ-PHI-6 |
| C7 | Opening stock is Phase 1 (BRD section 3) but posts to the ledger and needs batches, which are Phase 2 | Phase 1: template, upload, validation, staging, owner approval. Posting is the first SAH-79 story | OQ-PHI-9 |
| C8 | Mockup screens 01 and 12 show multi-branch counters and branch-to-branch transfer ("Aundh → Baner"); BRD lists multi-branch HO as out of scope | Later. Store-to-store transfers within one unit only | OQ-PHI-17 |

### 7.2 Mockup gaps for Phase 1

Screen 16 draws only **Stores & staff**, **Products** (global + own, adopt, duplicate warning, local settings button) and **Vendors**. The BRD's M1 unit settings (GSTIN, drug licence and expiry, Integration mode), M7 approval rules ("New screen, same Setup tab"), document numbering, reason lists, print templates and the opening-stock import have **no drawn screen**. Under CLAUDE.md rule 1 the BA cannot write those stories against a screen until the human either accepts BRD sections 2-3 as their design or asks for screens (OQ-PHI-6). `validate docs` will also fail any SAH-78 story until `docs/design/screen-map.md` has P&I rows (DEC-061 human to-do).

### 7.3 Multi-tenancy and RLS

- **Unit vs tenant** (ADR-028 D2, OQ-PHI-1, needs sign-off). Recommended: tenant stays the organization row we have, i.e. the clinic or hospital. A unit is a pharmacy row and a `unit_id` column, **not** an RLS key, with exactly one unit per tenant in this release. Isolation between tenants stays database-enforced. Isolation inside a tenant is by store mapping, enforced in code.
- **Global catalogue under RLS**: follow `ipd.drug` exactly (RLS on and forced, `USING (true)` select policy for `sahaj_app`, migrator policy, `SELECT`-only grant). This also passes the repository gates (`validate db` and `RowLevelSecurityCatalogIT`) without an allow-list. Put the global tables in their own migration file, so the "file mentions tenant_id → RLS on every table" check stays clean.
- **No views without `security_invoker = true`.** A view owned by `sahaj_migrator` would run with the owner's rights. Join global and unit rows in the repository instead.
- **No cross-module FKs** except `platform.tenant` and `patient.patient`. Membership ids, IPD ward ids, `opd.medicine` ids and `ipd.drug` keys are opaque columns checked through ports.
- **Store scope is code, not RLS** (same choice as ward scope, ADR-002 D6): one `StoreAccessGuard`, applied before any filter or paging parameter, so no parameter can widen it.

### 7.4 Offline billing (Phase 3)

Not feasible inside phase-1 rules without escalation. It needs: an installable offline shell (`@angular/service-worker`, a new package), a local store (IndexedDB, browser built-in), idempotent replay keyed by client keys (the pattern exists, `shared/client-key.ts`), conflict review for the pharmacist, and **per-counter number blocks**. That last item breaks a single gapless GST invoice series and needs a GST-compliant series per counter. Recommendation: remove it from SAH-80 and give it its own design story after Phase 3 (OQ-PHI-11).

### 7.5 Performance (POS search < 300 ms, bill save < 1 s)

- **Search**: the POS searches only the unit's own and adopted products (thousands of rows, not the whole global catalogue), joined to `stock_balance` for the working store. Indexes: `pg_trgm` GIN on name and salt (extension already installed), b-tree unique on `(tenant_id, barcode)`, `(tenant_id, store_id, product_id)` on balances, `(tenant_id, product_id, expiry)` on batches for FEFO. The Setup screen also searches the global catalogue (trigram GIN). Its size depends on the data source (OQ-PHI-5), and the 300 ms target applies at the POS, not the setup screen.
- **Bill save**: one transaction: lock the counter row, lock the balance rows in a fixed order (store, product, batch) to avoid deadlocks, insert bill, lines, ledger lines and register rows, update balances, write one audit row. About 10 + 4 × lines statements; well under 1 s on PostgreSQL 16. The counter row serialises bills of one series; at counter speed (a bill every few seconds) that is fine.
- **Proof**: QA adds a timed integration test on a seeded dataset (no new tool) per target. The contract states the targets as non-functional notes.
- **Growth**: the stock ledger is the biggest table (every movement, every line). Range-partition by month from the first migration (rule: plan before 50 M rows), reusing the automated partition upkeep from DEC-049 answer 3.

### 7.6 Audit

- Masters, settings, rule changes, mode switch: `platform.api.AuditLog` in the same transaction (before/after snapshots).
- Stock: the ledger itself is the audit trail (append-only by grants, policies and a deny trigger; corrections are reversing lines).
- Approvals: append-only decision rows (who, acting as HOD or delegate, decision, reason, time).
- Patient-linked reads at dispensing: a record-access row through the patient module's existing access port, as ipd does (Phase 3).
- The mockup's "tamper-evident" label (screen 05) would need hash-chaining. Not proposed: append-only enforcement is the built standard. This is listed as a possible later USP, not a commitment.
- Logs never carry patient names, phone numbers, Rx text or allergy text (CLAUDE.md).

### 7.7 Schedule H1 / X compliance

- **H1 register**: written as append-only `pharmacy` rows **in the sale transaction** from bill lines whose product schedule is H1. It snapshots the prescriber name and registration (`TeamMemberPort.doctorDetails`, or typed in Standalone), the patient name, drug, batch and quantity. Missing prescriber data blocks the sale line, with a pharmacist override and reason (BRD).
- **Schedule X**: separate stock state is not needed. A Schedule X product requires a store mapping with the Pharmacist role (membership role PHARMACIST) for receive and issue, plus a **second signature by a different person through `ColleaguePinCheck`**. Both enforced in code and by a database check (`second_signer <> first_signer`).
- **NDPS**: the BRD merges "Schedule X/NDPS". NDPS registers follow separate law and state rules. The compliance owner must confirm that one balance register satisfies both.
- **Legal facts the human must confirm before Phase 3** (not invented here): register retention periods; the GST invoice serial rules per financial year (the architecture assumes a unique series per financial year, at most 16 characters); the drug licence types to record (screen 05 shows 20B/21B and 20F/21F). Recorded as OQ-PHI-19.
- **Missing data**: pharmacist registration number and validity (screen 05 "Registered pharmacist · [REG NO.] · valid till") is not stored anywhere today (`doctor_profile` is doctors only). Proposed: pharmacy-owned (Phase 3).

### 7.8 Other risks

- **Team throughput.** One writer per area per working tree (`.claude/locks`) means the "3 full-stack lanes" run one after another inside `backend/` and `frontend/` (section 8.3).
- **Catalogue data source and licence** (OQ-PHI-5). A real Indian drug catalogue is large and usually licensed. Phase 1 can ship a small demo seed only, marked "not clinically validated" like `ipd.drug`. Go-live needs the human to choose the source.
- **Human-owned files** must change before any P&I migration can run (section 10).

---

## 8. Estimate per phase

Developer-weeks are human-equivalent effort for a developer who knows this codebase, including unit tests. QA covers the test plan, integration tests (RLS as `sahaj_app`, cross-tenant, 403/404/400, store scope) and e2e. "Other" covers the architecture, contract, DB design and review passes.

| Phase | Scope (basic column only) | Backend | Frontend | DB | QA | Other | Total |
|---|---|---|---|---|---|---|---|
| 1 Masters (SAH-78) | Unit settings, stores, staff-store mapping and working store, product master (global, adopt, own, duplicate check, UOM conversion, local settings), vendors, approval rules (presets), numbering, reasons, print-template settings, opening-stock CSV staging, module entry and tabs, adapter skeleton | 6-7 | 5-6 | 1.5 | 3 | 2 | **18-20** |
| 2 Inventory (SAH-79) | Opening-stock posting, ledger and balances, PO, GRN, vendor return and debit note, batch stock and expiry buckets, indents (approve, process, dispatch, receive, return), transfers, ward consumption, damage and quarantine, stock audit, approvals inbox and engine runtime, reports (CSV/browser print) | 15-17 | 12-14 | 3 | 8 | 3 | **41-45** |
| 3 Pharmacy (SAH-80), no offline | Command center, POS (search, FEFO, GST invoice, discount limits, payment modes record-only, hold), OPD/IPD dispensing with T1-T8 ports in opd/ipd/billing, sales return, H1 and Schedule X registers, licence list, day close | 14-16 | 11-13 | 2.5 | 8 | 3 | **38-42** |
| Offline billing (if approved) | Separate design; service worker, local queue, per-counter series, sync conflicts | 4-6 | 5-7 | 1 | 3 | 1 | **14-18** |

**Total without offline: about 97-107 developer-weeks.** About 60 % of Phase 3's backend effort is the T1-T8 ports and their changes in opd, ipd and billing. That part carries the most review risk.

### 8.1 Phase 1 work packages (the "3 full-stack lanes" of DEC-061)

| WP | Contents | Depends on |
|---|---|---|
| WP-0 (db-engineer, once) | All Phase 1 tables in three migrations: global catalogue (own file), unit and stores, masters. Plus the `platform.module_entitlement` column | contract approved; human adds schema `pharmacy` |
| WP-A Stores and staff | Unit settings, stores, staff mapping, `StoreAccessGuard`, working store, `GET /context`, menu port, adapter skeleton (both implementations, everything "not used" in Phase 1), module shell and tabs | WP-0 |
| WP-B Products | Global catalogue read, adopt, own product with duplicate check, UOM conversion, local settings per store, opening-stock CSV staging and owner approval | WP-A (stores, guard) |
| WP-C Vendors and rules | Vendors (licence expiry → blocked), approval rule sets with three presets and fixed rules, numbering series, reason lists, print-template settings | WP-A |

### 8.2 Team breakdown that fits the lock rule

One working tree allows one writer per area at a time. Phase 1 therefore runs as:

1. db-engineer: WP-0 (one pass).
2. java-developer and angular-developer in parallel on WP-A (backend and frontend are different areas).
3. Then WP-B, then WP-C, each as a Java + Angular pair. WP-B and WP-C cannot overlap in the same area.
4. qa-engineer after each WP (one backend and one frontend test run at a time, DEC-061).
5. code-reviewer and security-reviewer once per WP, or once for Phase 1.

So "3 full-stack lanes" means **3 sequential work packages with Java and Angular in parallel**, not 3 simultaneous lanes. True parallel lanes need either the human changing the lock rule in `.claude/hooks/guard-ownership.mjs` or separate worktrees per WP (OQ-PHI-18). Recommended for Phase 1: sequential (small, low risk); revisit for Phase 2.

Phase-level split of developer effort (backend : frontend : QA): Phase 1 ≈ 6.5 : 5.5 : 3; Phase 2 ≈ 16 : 13 : 8; Phase 3 ≈ 15 : 12 : 8.

---

## 9. BRD section 10 open questions: proposed answers

| BRD question | Proposed answer | OQ |
|---|---|---|
| Pharmacy bill separate from or added to the OPD bill by default? | Separate by default; "add to OPD bill" as a unit setting in Phase 3 through a `billing.api` port | OQ-PHI-12 |
| One receipt per dispatch, or several partial receipts per indent? | One receipt per dispatch; an indent may have several dispatches (partial dispatch), each received once | OQ-PHI-16 |
| Escalation times 2 h (urgent 30 min) and 24 h? | Yes as defaults, configurable per rule; escalation computed when the inbox is read (no scheduler, no notifications) | OQ-PHI-14 |
| More than one Central Store per unit? | One in this release; the schema allows more later | OQ-PHI-13 |
| Who approves promoting a unit's own product to the global master? | The Sahaj team, through a reviewed migration in this release; an admin-portal screen is Later | OQ-PHI-5 |
| Sales return window and refund modes per unit or per organization? | Per unit (a unit is one tenant in this release) | OQ-PHI-15 |
| Does the product master already exist in OPD prescribing, and can pharmacy share it? | Two lists exist (`opd.medicine`, `ipd.drug`). Neither has salt, strength, HSN, GST, Schedule or UOM. P&I owns its own master and links it in Phase 3 (DEC-061 Q3) | closed |

---

## 10. Changes needed outside `docs/architecture/`

**Human (owned files):**
- `CLAUDE.md`: add `pharmacy` to the module list and to "Not in phase 1" exceptions as the human sees fit (DEC-061 to-do).
- `scripts/db/schemas.sql` line 8: add `'pharmacy'` to the schema array. Then re-run `node scripts/setup/create-database.mjs` (or create the schema with the same grants) on `sahaj_dev` and `sahaj_test`.
- `.claude/rules/postgresql.md`: add `pharmacy` to the schema list. Note the money convention actually used (`bigint` paise) and the opaque-id rule for pharmacy links (OQ-PHI-21).
- `docs/design/screen-map.md`: rows for the P&I screens (Inventory setup: Stores & staff, Products, Vendors; plus whatever the human accepts for unit settings, approval rules, numbering, reasons, print templates, opening stock).
- `docs/approvals.md`: SAH-78 gates.

**java-developer (later, after `contract`):** `ModularityTests` rules (nothing imports `com.sahaj.pharmacy`; pharmacy reaches other modules only through `api`); `NavigationPolicy` + the new `platform.api` menu port; `com.sahaj.pharmacy` package.

**angular-developer (later):** `nav.config.ts` entry, `shell.routes.ts` lazy route, `features/pharmacy/`.

**db-engineer (later):** Phase 1 migrations as listed in ADR-028 "Data needed", including the `platform.module_entitlement` column.
