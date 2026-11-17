# SAH-78 · Pharmacy & Inventory Phase 1 — Masters

Programme: DEC-061 (P&I: SAH-78 Masters, SAH-79 Inventory, SAH-80 Pharmacy). Inputs: docs/handoffs/PHI-input/ (brd.txt, screens/01–18).
Branch: `feature/SAH-39-home-kinetic-background` (human, 17 Nov, DEC-061 Answer 2). Additive-only rules in DEC-061.

## Stage log
| Stage | Agent | Started | State |
|---|---|---|---|
| Requirement + stories (Masters) | business-analyst (BA 1) | 17 Nov 2026 | running |
| Feasibility report + ADR-028 | solution-architect | 17 Nov 2026 | running |
| Stories (Inventory, SAH-79) | business-analyst (BA 2) | after BA 1 (requirements area lock) | waiting |
| Contract `pharmacy.yaml` | solution-architect | after SAH-78 stories | waiting |
| DB, 3 dev lanes, QA, reviews | — | after the branch and contract | waiting |

## Handoff · SAH-78 · business-analyst (BA 1) → solution-architect · 17 Nov 2026
Status: NEEDS DECISION (stories written; four parts have no mockup screen). Files: docs/requirements/SAH-78.md (BR-1..31, OQ-78-1..38), docs/stories/SAH-78.md (SAH-78-1..10).
Validation (tech lead ran it): `validate docs` fails only on the old docs/stories/ADM-2.md naming; SAH-78 files pass.
| WP | Sub-stories | Screen |
|---|---|---|
| WP-A | 78-1 module entry and tabs, 78-2 unit settings (NEW), 78-3 stores, 78-4 staff-store mapping and working store | 16 |
| WP-B | 78-5 product search/adopt/local settings, 78-6 own product + duplicate check + conversion, 78-7 opening stock CSV (NEW) | 16 |
| WP-C | 78-8 vendors + licence block, 78-9 approval rules (NEW), 78-10 numbering, reasons, print templates (NEW) | 16 |
Order A → B → C (B and C need stores, mapping and the shell). Blocking questions raised to the human in DEC-062. Platform requests: P&I module flag (SAH-19 plan and modules), "Store staff" role, nav rule.
Note: the requirements area lock is still held by this finished agent (`.claude/locks/requirements.lock`), so BA 2 (SAH-79) waits until it goes stale (about 2 h) or the human deletes it.

## Handoff · SAH-78 · solution-architect → tech-lead · 17 Nov 2026
Status: NEEDS DECISION. Files: docs/architecture/PHI-feasibility.md (BRD §9 points 1–8 with file paths; estimate Phase 1 18–20 dev-weeks, Phase 2 41–45, Phase 3 38–42, offline +14–18), docs/architecture/adr/ADR-028-pharmacy-inventory-module.md (D1–D12, OQ-PHI-1..21). No contract yet.
Key choices: pharmacy on top of the module graph (consumer-declared ports); tenant unchanged, unit = column, one unit per tenant; global catalogue select-only like ipd.drug; store-scoped access via StoreAccessGuard with `{storeId}` in path (404 unmapped, 403 no capability); ClinicalLink adapter Integrated/Standalone with `GET /context`; append-only partitioned ledger (Phase 2); versioned approval rules, escalation computed on read; numbering per tenant+unit+type+FY in the document transaction; money as paise.
Conflict found by tech lead: D4 "no new platform roles" vs DEC-062 Q3 (add Store staff role). Human answer governs; architect revises D4.
Human-only actions from this handoff: `scripts/db/schemas.sql` line 8 add 'pharmacy' and re-run create-database (needed before any P&I migration); `.claude/rules/postgresql.md` money-in-paise and schema; CLAUDE.md module list; screen-map rows.
Validation (tech lead): `validate docs` only fails on the old ADM-2.md.
Locks: requirements and architecture locks stay held by the finished agents for 2 h (to about 23:50 IST), blocking BA rework, BA 2 and the contract unless the human deletes them.
