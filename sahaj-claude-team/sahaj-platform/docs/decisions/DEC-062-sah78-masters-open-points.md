# DEC-062 · SAH-78 Masters: screens not in the mockup, catalogue, roles, legal and pricing defaults

Date: 17 Nov 2026. Status: ANSWERED (A, A, A, A). Raised by: tech lead from the BA 1 hand-back (docs/requirements/SAH-78.md, OQ-78-1..38).
Blocked: SAH-78-2, 78-7, 78-9, 78-10 (no screen); the contract for the role and flag parts.

## Q1 Parts with no mockup screen (OQ-78-2, OQ-78-3)
Unit settings, approval rules, numbering/reasons/print templates, opening stock. The BRD names them, and says M7 is a "new screen, same Setup tab", but the P&I mockup has no screen for them. Store, own-product and vendor forms also lack BRD fields (code, manufacturer, conversion, HSN, GST %, schedule, barcode, vendor contact, return policy).
- A (recommended): accept the BA's section layout on the Inventory setup tab, built from existing Sahaj components, with extra fields placed after the mockup fields and marked NEW in the stories. Pro: Phase 1 is complete. Con: no visual design check before build.
- B: build only what screen 16 shows now; the four parts wait for screens. Con: no approval rules, so Phase 2 cannot start.
- C: the human supplies screens first.

## Q2 Global product catalogue (OQ-78-16)
It does not exist anywhere.
- A (recommended): seed about 30 clearly marked sample products by migration for dev, test and demo only, never in production. The real catalogue is a later story once you name a licensed source and a pharmacist reviews it.
- B: own products only for now ("adopt" waits).

## Q3 Roles and module switch (OQ-78-6, -7, -10)
Sahaj has no store assistant or store keeper role and no P&I module flag in Plan and modules (SAH-19). This changes auth.
- A (recommended): add a "Store staff" team role and a "Pharmacy & Inventory" module flag (Off by default) via the platform owner. Approver, Delegate and Central Store user are store roles in the mapping, not team roles. Unit admin = owner, Cashier = billing, Pharmacist = existing PHARMACIST.
- B: no new role; nurses and other existing roles get store roles through the mapping only.

## Q4 Legal, pricing and safety defaults (OQ-78-11, -19, -23, -26, -28, -30, -31, -33, -36)
- A (recommended): accept the BA's proposals as written: FEFO and negative-stock block On and locked. Schedule has no default and the owner sets it. GST % is typed, with no hard-coded slabs. Licence numbers are free text, with blocking from the day after expiry. No shipped ₹ limits (presets ask the owner). The Schedule X second signer is left to SAH-80. Bill numbers are prefix + financial year, restart on 1 April, never reused. Invoice fields as listed. When the owner uploads opening stock, a different owner approves. All of these are go-live items for an accountant and pharmacist to confirm before real billing.
- B: hold these stories until an accountant and pharmacist confirm.

## Recorded without a question (BA recommendation, DEC-023 style)
One Central Store per unit (OQ-78-12); one clinic account = one unit (OQ-78-9); duplicate check on salt + strength + form (OQ-78-17); no MRP/selling rule in Phase 1 (OQ-78-20); escalation times stored only (OQ-78-29); opening stock is approved now and posted when the Phase 2 ledger exists, unless ADR-028 creates the ledger now (OQ-78-34).

## Human-only actions
Screen-map rows (OQ-78-1); `pharmacy` in CLAUDE.md; optionally delete `.claude/locks/requirements.lock` so BA 2 can start now.

## Answer (human, AskUserQuestion, 17 Nov 2026): Q1 A, Q2 A, Q3 A, Q4 A
1. The BA's section layout for unit settings, approval rules, numbering/reasons/print templates and opening stock is accepted as the design; extra fields go after the mockup fields, marked NEW.
2. About 30 sample catalogue products seeded for dev, test and demo only, marked "not clinically validated", never in production.
3. Add a "Store staff" team role and a "Pharmacy & Inventory" module flag (Off by default). Approver, Delegate and Central Store user are store roles. Unit admin = owner, Cashier = billing, Pharmacist = PHARMACIST.
4. Legal, pricing and safety defaults accepted as written; all listed as go-live items for an accountant and pharmacist.
Effect on ADR-028: D4 said "no new platform roles"; the human's answer adds the Store staff role, so ADR-028 D4 is revised by the architect. ADR-028 OQ-PHI-5, -6, -8 are answered by Q2, Q1, Q4.
