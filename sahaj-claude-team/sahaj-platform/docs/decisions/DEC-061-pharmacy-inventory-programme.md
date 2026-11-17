# DEC-061 · Pharmacy & Inventory programme (new module, outside phase 1)

Date: 17 Nov 2026. Status: ANSWERED (A, A, A, A). Raised by: tech lead.
Request (human, chat): build the Pharmacy & Inventory BRD and mockup in `docs/inventory and pharmacy/` "ASAP" with dedicated agents: 1 product owner head, 2 business analysts, 3 full-stack developers, 1 QA. Other agents keep working on their own lanes.
(The BRD's own page 1 asks for a different team: 1 PO, 3 BA, 10 full-stack, 5 QA. The chat request is newer, so I use it.)

## Inputs (split by tech lead, because the files are large)
- BRD, 13 pages: text in `docs/handoffs/PHI-input/brd.txt`.
- Mockup, 8.4 MB bundle: 18 screens unpacked to `docs/handoffs/PHI-input/screens/NN-<slug>.html` (10–29 KB each). Screens 01–05 are the Pharmacy tab, 06–15 Inventory, 16 Inventory setup, 17 end-to-end flow, 18 internal USP comparison (not a product screen).
- BRD scope: Phase 1 Masters (M1–M7, numbering, reasons, print templates, opening stock); Phase 2 Inventory (I1–I10); Phase 3 Pharmacy (P1–P5); 8 OPD/IPD touch points (T1–T8); Integrated or Standalone mode per unit.

## Why this stops here (hard stops under DEC-023 and DEC-047)
1. **Outside phase 1.** `docs/design/screen-map.md` row "Pharmacy, Lab, Tele" is **Later**. The P&I mockup is not part of `sahaj-mockup.html`. You must accept it as a design addition and add screen-map rows (agents cannot edit `docs/design/`).
2. **New module.** `CLAUDE.md` lists six modules. P&I needs a new schema, package and Spring Modulith boundary, and the rule list in CLAUDE.md needs updating (yours).
3. **Size.** About 18 screens in 3 phases, each with migrations, APIs, screens and tests. That is many stories. The BRD itself says each phase is released and tested before the next starts, and its own prompt (section 9) asks for a feasibility report before any code.
4. **Conflict with built code.** Two drug lists already exist: `opd.medicine` (OPD prescribing) and `ipd.drug` (IPD orders, SAH-26/76). The BRD wants one shared product master that feeds OPD/IPD prescribing, and also says "never change OPD/IPD screens or workflows". Both cannot hold.
5. **Items that need a library, an external service, or are Later anyway:** Excel opening-stock import (no Excel library in the build; CSV needs none); WhatsApp sending of the PO and bill (Later under DEC-049); offline billing with sync (large and risky, needs its own design); UPI and card are recorded as payment modes only, with no payment gateway.
6. **Agents.** Agent definitions live in `.claude/agents/` (yours). I cannot create "inventory" or "pharmacy" agents. I can run the existing agent types as a dedicated P&I team (DEC-019 parallel lanes).
7. **Working tree.** 409 uncommitted files on `feature/SAH-39-home-kinetic-background`, with SAH-77 pilot work in flight.

## Proposed team (existing agent types)
| Requested role | How it runs |
|---|---|
| Product owner head | You (approvals, scope, merge), as in DEC-019. I coordinate. |
| BA 1 | business-analyst: Phase 1 Masters stories (screen 16, BRD §2–3) |
| BA 2 | business-analyst: Phase 2 Inventory stories (screens 06–15, BRD §4), written ahead while Masters is built |
| Full-stack 1–3 | three lanes, each a java-developer and angular-developer pair, split by master group (for example stores and staff, products, vendors and approval rules) |
| QA | qa-engineer, one backend and one frontend test run at a time (lock protocol) |
| Also needed (workflow) | solution-architect (ADR, feasibility, `pharmacy.yaml`), db-engineer (schema `pharmacy`, RLS), code-reviewer and security-reviewer |

## Questions and options
**Q1 Scope.**
- A (recommended): accept the P&I mockup as the design. Architect writes the feasibility report and module ADR while the two BAs write Masters and Inventory stories in parallel. Build Phase 1 Masters only; Phase 2 and Phase 3 get their own approvals. Pro: follows the BRD's own build order; first slice is reviewable quickly. Con: Pharmacy POS comes last.
- B: feasibility report only, then stop. Pro: cheapest. Con: slowest.
- C: all three phases at once. Pro: fastest on paper. Con: Pharmacy builds on unbuilt stock, very large review load, collides with SAH-77 test runs.

**Q2 Module shape.**
- A (recommended): one module `pharmacy` (schema `pharmacy`, package `com.sahaj.pharmacy`, base path `/api/v1/pharmacy`), covering inventory and pharmacy, as the BRD says ("one Pharmacy & Inventory module"). Reads patient, OPD and IPD only through their `api` packages; writes back through one adapter (BRD §6).
- B: two modules, `inventory` and `pharmacy`. Pro: smaller boundaries. Con: stock ledger and sales split across modules; more cross-module calls.

**Q3 Product master vs `opd.medicine` / `ipd.drug`.**
- A (recommended): P&I owns its product master. OPD and IPD prescribing stay unchanged. A link from product to `opd.medicine` and `ipd.drug` comes with the T2/T4 dispensing stories (Phase 3). Pro: no change to built EMR/IPD. Con: two lists until the link exists.
- B: replace both drug lists with the shared master now. Pro: one list. Con: rework of built and tested OPD/IPD prescribing; breaks the BRD rule "do not change OPD/IPD".

**Q4 Branch.**
- A (recommended): you commit or stash the current work; I branch `feature/SAH-78-pharmacy-masters` from `main`.
- B: continue on the current branch (as with SAH-76). Con: one merge then covers SAH-39, SAH-76, SAH-77 and P&I.

## Defaults I will apply unless you say otherwise
Excel import becomes a CSV template (no new library); WhatsApp send, OCR and every "Later" column item stay out; offline billing goes to Phase 3 as its own design question; printing uses the browser print view.

## Story ids
SAH-78 Phase 1 Masters, SAH-79 Phase 2 Inventory, SAH-80 Phase 3 Pharmacy (sub-stories SAH-78-n and so on).

## Blocked
All P&I work: BA, architect, DB, code, QA.

## Answer (human, AskUserQuestion, 17 Nov 2026): Q1 A, Q2 A, Q3 A, Q4 A
1. **Scope:** the P&I mockup is accepted as the design. Build Phase 1 Masters (SAH-78) first; Phase 2 (SAH-79) and Phase 3 (SAH-80) get their own approvals later.
2. **Module:** one module `pharmacy` (schema `pharmacy`, package `com.sahaj.pharmacy`, `/api/v1/pharmacy`).
3. **Product master:** P&I owns its own. `opd.medicine` and `ipd.drug` are unchanged; the link comes with Phase 3 dispensing.
4. **Branch:** the human commits or stashes current work; the tech lead then creates `feature/SAH-78-pharmacy-masters` from `main`.

## How the team runs (tech lead, 17 Nov)
- The ownership hook allows **one writer per area at a time** (`.claude/locks`, released when the agent stops). So the two BAs cannot write `docs/stories/` at the same moment, and three dev lanes cannot write `backend/` or `frontend/` at the same moment in one working tree. Plan: BA 1 (Masters) and the architect (feasibility report + ADR-028, different areas) run now in parallel; BA 2 (Inventory) starts when BA 1 stops. The 3 dev lanes are planned as 3 work packages; Java and Angular run in parallel, but each area takes one writer at a time unless the human changes the lock rule (`.claude/hooks/guard-ownership.mjs`) or allows separate worktrees.
- Until the branch exists, the docs-only stages write **new files only** (`docs/stories/SAH-78*.md`, `docs/requirements/SAH-78*.md`, `docs/architecture/PHI-feasibility.md`, `docs/architecture/adr/ADR-028-*.md`). They stay untracked and carry over to the new branch. **When committing current work, leave these files out** (or stash with `-u` and pop on the new branch).
- Code, the migration and `docs/api/pharmacy.yaml` wait for the SAH-78 branch.

## Human to-do (agents cannot)
- Commit or stash the 409 files on `feature/SAH-39-home-kinetic-background`, leaving out the P&I files above.
- `docs/design/screen-map.md`: add rows for the P&I screens (the mockup lives in `docs/inventory and pharmacy/`; split copies are in `docs/handoffs/PHI-input/screens/`).
- `CLAUDE.md`: add `pharmacy` to the module list (Architecture, phase 1).

## Answer 2 (human, chat, 17 Nov 2026): branch
"can you make it changes into this branch without affecting existing one". Read as: P&I work (SAH-78 onward) is done on the current branch `feature/SAH-39-home-kinetic-background`; no separate `feature/SAH-78` branch; existing features must not change. This replaces Q4 A. Same precedent as SAH-76 (DEC-055 Answer 3). Cost: the merge approval then covers SAH-39, SAH-76, SAH-77 and P&I together. The human no longer needs to commit or stash before DB and code start.

### Additive-only rules given to every P&I agent
1. New code only in `com.sahaj.pharmacy`, `frontend/src/app/features/pharmacy/`, schema `pharmacy`, `docs/api/pharmacy.yaml`, and new migration files. Never edit or rename an existing migration.
2. Touches to existing code are limited to what the module must plug into, and each is additive:
   - Sidebar entry and route: `nav.config.ts`, `shell.routes.ts`, `NavigationPolicy`.
   - Module switch: `platform.module_entitlement.pharmacy_on`, a new column with default `false` (DEC-062 Q3, ADR-028 D9).
   - Store staff team role: a new role value; no existing role changes meaning (DEC-062 Q3).
   - `ModularityTests`: add the pharmacy module rules.
   No other existing file is changed. Any other need goes back to the tech lead.
3. Existing APIs keep the same behaviour and JSON. Existing tables get no drops, renames or data changes on existing rows.
4. With `pharmacy_on` false (the default), every existing screen, menu and test behaves exactly as before.
5. Every stage re-runs the existing suites it touches (backend unit + IT, frontend unit, lint, build). A previously green test that fails is a Blocker for that stage.
6. Human-only file still needed: `scripts/db/schemas.sql` line 8 must list 'pharmacy' and `create-database.mjs` must be re-run before the first P&I migration runs.
