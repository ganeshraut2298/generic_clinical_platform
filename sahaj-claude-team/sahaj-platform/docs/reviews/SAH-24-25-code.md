# Code review · SAH-24 (bed board, bed ready/block) and SAH-25 (running bill, extra charges, deposit alert)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Branch feature/SAH-13-appointments-workspace, uncommitted. Saved by the tech lead.

**Verdict: CHANGES REQUIRED** (2 Major, no Blocker). Security is reviewed separately (SAH-24-25-security.md).

## Findings

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| 1 | Major | frontend/src/app/shared/format/inr.ts:5-19; used in admission-dialog, bed-board, bed-sheet-dialog, ipd.page templates | Bill amounts go through `formatInr(paise / 100)` which rounds to whole rupees. The server bills in whole paise (Semi-private x1.25: injection 18750 paise = ₹187.50). Screen shows ₹188; "3 × ₹438" shows ₹1,313 (true ₹1,312.50); lines do not add to the total; "left/over" off by up to ₹1. | Add `formatInrPaise(paise)`: Indian grouping, no decimals when paise % 100 === 0, else two. Use it for all bill and deposit amounts, charge options, bed rates, list. Specs with a 1.25 multiplier and a half-rupee line. |
| 2 | Major | frontend/src/styles/_primitives.scss (missing) | `.p-crit`, `.p-blue`, `.notice.crit` not defined globally (the last exists only in login.page.scss, encapsulated). The "Deposit low" pill (AC3), Insurance/Corporate pill and Deposit low notice (AC6) render unstyled. Same gap in follow-ups and appointments. | Define them in shared styles with tokens (`--crit-soft`, `--crit`; `.p-blue` as alias of `.p-sec`). Check computed colour is not transparent. |
| 3 | Minor | AdmissionService:413-414, BedBoardService:203-204, ipd.page, bed-board html vs admission-dialog html | List and board "Day N" count IST calendar days; sheet uses `bill.billDays`. Under 24h cut they differ. Mockup `openIp` shows calendar day, story AC6 differs from the mockup. | HUMAN DECISION. Preferred: one number everywhere (`dayNumber = bill.billDays()`), update ipd.yaml descriptions and AC3/AC6. Otherwise label the sheet "Bed days". |
| 4 | Minor | admission-dialog.component.spec.ts:32,43 | Fixture has dayNumber 2 = billDays 2; a regression binding the strip to dayNumber would pass. | Spec with dayNumber 3, billDays 2, 24h cut: strip reads "Day 2". |
| 5 | Minor | AdmissionService:469-473 | `reason.trim()` runs before the control/bidi check, silently dropping leading/trailing control characters; U+2028/2029, zero-width not rejected; U+00A0-only reason stored. | Check raw value first, use `strip()`, extend the check (U+2028, U+2029, U+200B-200D, U+FEFF); test it. |
| 6 | Minor | BedBoardService:113,141,145; AdmissionService.list | `catalog.config()`/`categories()` read twice per board read; `team.find` per doctor in list. | Load once and pass into `IpdBilling.forAdmitted`; use `team.findAll`. |
| 7 | Minor | AdmissionService:380-382, RunningBill:26, BedBoardService:45, AdmissionService:66 | `IST` defined 3 times; calendar-day maths copied; `depositRules()` duplicates `IpdCatalog.depositRules()`. | `RunningBill.calendarDay(...)`, one shared IST, remove duplicate. |
| 8 | Minor | RunningBillIT ~213-226 | `assumeTrue` after the assertions; test plan claims a list-vs-sheet dayNumber assertion that does not exist. | Move guard before assertions; add dayNumber assertion after finding 3. |
| 9 | Minor | docs/test-plans/SAH-24.md, BedBoardIT | Plan still lists the roomLevel test as DISABLED; bug is fixed; stale comments and workaround remain. | qa-engineer: update plan, drop workaround. |
| 10 | Minor | IpdBillingSupport:367-387 | `backdate` takes ACCESS EXCLUSIVE lock via ALTER TABLE; may block other ITs up to 30 s. | Acceptable; note it in the plan. |
| 11 | Minor | admission-dialog.component.html:76-77 | `role="table"` with a non-row `.ipbh` heading; no column headers. | Move heading out or `role="presentation"`; add hidden columnheaders. |
| 12 | Minor | admission-dialog html/ts | Charge select not a typed form control; `$any($event.target).value`. | `FormControl<ChargeServiceKey \| null>`. |
| 13 | Nit | RunningBill:59-69 | Parallel maps and `(int)` casts. | Small private record in one LinkedHashMap. |
| 14 | Nit | admission-dialog.component.ts | `loading` signal never read. | Remove. |
| 15 | Nit | ipd.page.html | `<h2>Admitted</h2>` not in mockup; stale class comment "two tiles". | Drop and fix. |
| 16 | Nit | admission-dialog html ~21 | Mockup omits ward name when equal to category name. | Optional parity. |
| 17 | Nit | BedBoardService:217-230 | Unknown status silently Blocked. | Throw IllegalStateException. |
| 18 | Nit | bed-board.component.ts tileClick | Non-actionable tiles are buttons that do nothing. | `aria-disabled` or non-interactive tile. |

## Checks that passed
Money math (integer paise, half-up per unit price, bed and nursing unscaled), bed-day cuts, deposit-low rule with boundary tests, contract match (paths, roles, shapes, 400/403/404/409), bed state rules and lock order, module boundaries, no per-row N+1, problem types, toString hides names, audit rows ids only, migration V2026_11_14_1000 correct, no disabled tests, OnPush/signals/a11y tab pattern.

## Done well
One `RunningBill` feeds list, board, tile and detail. `IpdGate`/`IpdCatalog`/`IpdBilling` removed earlier duplication. Strong real-DB tests (committed-SQL set-up, backdating helper with rollback, concurrency, RLS, audit/log privacy). Review fixes have regression tests. Frontend specs assert exact rendered text.

## Required before approval
Findings 1 and 2. Finding 3 needs a human decision. Findings 4 and 5 in the same pass. The rest can follow in a tidy-up.
