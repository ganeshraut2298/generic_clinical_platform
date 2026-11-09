# DEC-032 · Release audit of SAH-37 (the human's "audit, fix and prove" prompt)

Date: 9 Nov 2026. Status: QUEUED. It starts when SAH-37's current QA re-run ends, because both need the backend and frontend locks.
Source: the human's chat prompt of 9 Nov 2026 ("after done the current job do this prompt job"): phases 0–5 (discover, input validation, UI glitches, unit tests, integration tests, regression and sign-off).

## Reading of the prompt
- **The story is not named.** The prompt still says `<STORY ID / NAME>` and "discover from git log". Default applied: **SAH-37, IPD patient card setup**, the newest finished story, which has the most inputs (labels, layout, switches, drafts). The human can redirect it to another story. The same audit can then run on SAH-41, SAH-29 and SAH-34 as each is built.
- **How the prompt maps to this project (the project rules win where they differ):**
  - **The Angular and Spring versions** are as in CLAUDE.md. Tests use the real `sahaj_test` PostgreSQL database, with Flyway run by `sahaj_migrator`, and not Testcontainers. Playwright already exists.
  - **The error body** is RFC 9457 problem+json with `fieldErrors`, as the project already does. Do not replace it with a new format.
  - **Tenancy** is enforced by RLS and the tenant comes from the signed-in user. The cross-tenant IDs and IDOR checks are part of Phase 1.
  - **Ownership.** Each agent writes only in its own folders. The QA report goes to docs/reviews/QA_REPORT_SAH-37.md, as for the earlier audit (DEC-025).
  - **Migrations.** An applied migration is never edited. A new versioned one is added by the db-engineer.
  - **The mockup is the design.** If a fix would differ from the mockup, or the mockup and the acceptance criteria conflict, the audit stops and lists it for the human.
- **Commits.** The prompt asks for small commits. Under the standing rule, **no agent and not the tech lead commits, pushes or merges** without the human's explicit go-ahead. The audit proposes the commit messages (`fix(validation)`, `fix(ui)`, `test(unit)`, `test(integration)`, `docs(qa)`), as the earlier audit did, and the human commits.
- **Scope limits.** No new features. No refactor beyond what a fix needs. Out-of-scope breakage goes to a "Deferred" list with a reproduction. Sev-1 and Sev-2 deferrals are not allowed.

## Plan (agents and phases)
| Phase | Agent | Work |
|---|---|---|
| 0 | business-analyst ∥ solution-architect (docs) | Map the slice and build the field table: every input, its rules, and where it is validated (FE, BE, DB). List the gaps. |
| 1 | java-developer ∥ angular-developer, db-engineer for a new migration | Close the gaps at all three layers. The mockup decides. |
| 2 | angular-developer, with qa-engineer measuring | Check each screen at 1920, 1366, 1024, 768 and 375 px and at 125% zoom: layout, states, keyboard, contrast, console. Fix. |
| 3–4 | java-developer ∥ angular-developer (unit), qa-engineer (integration and e2e) | Tests with boundary values, tenant isolation, cross-tenant access, DB constraints and audit columns. Never weaken an existing test. |
| 5 | qa-engineer, then code-reviewer ∥ security-reviewer | Full gate: lint, production build, the whole unit, integration and e2e suites. Walk every acceptance criterion. Write the QA report with the validation matrix, defects (fixed and deferred), the test inventory, the screen notes and the risks. State READY or NOT READY. |

## Human answer
(none yet; defaults applied under DEC-023: the story is SAH-37, and the human commits)

## Human answers (AskUserQuestion, 9 Nov 2026), after the Phase 0 report (docs/test-plans/SAH-37-audit-validation.md)
- **G-1 and G-2:** "Yes, reuse the mockup error style". An inline error line under the label box in the mockup's existing error style (`.ferr`), plus a client check for control, invisible and direction characters and the 40-character count. It is shown on blur and on submit. It adds a state the Patient card mockup does not draw, and the human has approved it.
- **G-4:** "Yes, add V15". A new server rule: a label may not equal another field's standard or custom label in the same card. It is stricter than the mockup and not in the stories, and the human has approved it. The architect adds it to the validation table and the contract, and the BA adds an acceptance criterion.
- **Phase 0 result:** 47 fields, 41 rules, 11 gaps, none Sev-1 or Sev-2. Sev-3: G-1, G-2, G-4, G-5. Sev-4: G-3 and G-6 to G-11. The server layer is sound: tenant isolation, forced RLS and the single error body.
- **Owners:**
  - G-3, G-1 and G-2: angular-developer.
  - G-4 (V15): architect first, then java-developer, then angular-developer.
  - G-5 (`schema_version` on the two jsonb tables, required by `.claude/rules/postgresql.md`): db-engineer.
  - G-6 to G-11: see the document.

## Phase 2 result and human answers (9 Nov 2026)
- **UI measure pass** (docs/test-plans/SAH-37-audit-ui.md, 52 screenshots): 16 defects, none Sev-1. Sev-2: 4 (D-1, D-2, D-3, D-4). Sev-3: 8. Sev-4: 4. The console, the network, loading and error states, the leave dialog, keyboard order, the theme and the formats were clean.
- **D-1 and D-2** (the Fields tab label box at about 1100–1300 px and at 375 px): the human answered "Yes, fix it responsively". The mockup has the same breakpoint (1180 px) and no phone rule for the rows, so this is a deviation approved by the human. Stack the editor above the preview sooner, give the label box a minimum width, and stack each row on phones.
- **D-9** (contrast): the human answered "I'll change the tokens". Only the human may edit docs/design/tokens.md. The developer applies the same values in the app after the human edits it. The audit report lists the exact pairs and the values that pass AA.
- **To restore from the mockup (no decision needed):** D-3 (the status bar is static on phones), D-4 (the BP unit rule `.vt.k-bp`) and D-15 (the view labels on phones).
- **Not defects:** the ₹ format follows the mockup (`₹1,25,000`) and not CLAUDE.md's "₹ 1,25,000" (a note for the human); the fourth tab, Workflow stages, is not built (OQ-37-3).

## Contrast pairs for the human to change in docs/design/tokens.md (D-9)
The developer computed these from `_tokens.scss`. Nothing was changed in the app.
| Pair | Today | Value that passes 4.5:1 |
|---|---|---|
| Light `--ink-3 #66778f` on tile and muted-soft surfaces (`#eff0f4`) and on the page background (`#ebf1f8`) | 4.01 | `#5E6E84` gives 4.57 |
| Light `--sec #0e8a7e` on `--sec-soft` (teal pill) | 3.58 | `#0C786E` gives 4.52 |
| Light `--good #15804c` on `--good-soft` | 4.17 | `#147A49` gives 4.50 |
| Dark `--ink-3 #8496b1` on the dark tile (`#21304c`) | 4.38 | `#8799B4` gives 4.55 |
Other pairs pass today: `--ink-2` on tile 6.74, `--pri-text` on `--pri-soft` 6.68, `--warn` on `--warn-soft` 4.74. The "Required" and "Always on" pills use the `p-wait` pair, which was not computed.

## Inconsistency in the approved text (found by the developer)
AC 69 says a label equal to the field's own standard name "(any case)" counts as unchanged. The design note (section 4.2) and V15 say the exact match (case-sensitive) is stored as empty, and a case or spacing variant such as "bed" is a real change on the server. The developer followed the design note. **Fix: the BA changes AC 69 to say "exactly".**
