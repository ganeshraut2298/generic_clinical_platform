# Sahaj development workflow with Claude Code agents

## Roles
- **Tech lead (your main Claude Code session):** runs the pipeline, calls one agent per stage, updates `docs/handoffs/BOARD.md`, saves review reports in `docs/reviews/`. Writes no feature code.
- **You (human):** approve at three gates in `docs/approvals.md`, merge, and own `CLAUDE.md`, `.claude/`, `scripts/`, `docs/design/` (mockup) and `.env`.

## Pipeline per story

| # | Stage | Agent | Output | Gate |
|---|---|---|---|---|
| 1 | Requirement | business-analyst | `docs/requirements/SAH-n.md` (names the mockup screen) | validate docs |
| 2 | User story | business-analyst | `docs/stories/SAH-n.md` | **You add `SAH-n · stories · APPROVED`** |
| 3 | Architecture | solution-architect | ADR if needed | — |
| 4 | API contract | solution-architect | `docs/api/<module>.yaml` | **You add `SAH-n · contract · APPROVED`** |
| 5 | Database design | db-engineer | `docs/database/SAH-n.md` + Flyway migration | validate db |
| 6 | Development | java-developer ∥ angular-developer | code + unit tests | validate backend / frontend |
| 7 | Testing | qa-engineer | test plan, IT and e2e tests | validate all |
| 8 | Review | code-reviewer ∥ security-reviewer | reports | no Blocker/Major/Critical/High |
| 9 | Approval | you | merge to `main` | **You add `SAH-n · merge · APPROVED`** |

∥ = may run in parallel (different folders). A failed gate goes back to the owner of the problem.

## What the hooks enforce
- **Ownership:** writing outside your folder is blocked.
- **Workflow:** the hook reads the story ID from the branch name (`feature/SAH-12-...`). The architect is blocked until `stories` is approved; DB, developers and QA are blocked until `contract` is approved.
- **Mockup:** `validate docs` fails if a requirement or story has no `Mockup screen:` line, names a screen not in `docs/design/screen-map.md`, or says it is not in the mockup.
- **One writer per area:** area locks in `.claude/locks/` (released when the agent finishes, stale after 2 hours).
- **Done = validated:** developer, DB and QA agents cannot finish while `node scripts/validate.mjs <area>` fails. After a second failure they must report BLOCKED.

## Handoff block (every agent ends with this)

```markdown
## Handoff · SAH-n · <agent> → <next agent>
Status: DONE | BLOCKED | NEEDS DECISION
Mockup screen: <name>
Files changed:
- path — what changed
Validation: node scripts/validate.mjs <area> → PASSED (last lines)
Decisions made:
Open questions / requests for other owners:
```

## Git (local only for now)
- `main` — always working. Only you merge into it.
- `feature/SAH-<n>-<slug>` — one per story, from `main`. Bug fixes: `fix/SAH-<n>-<slug>`.
- Commit messages: `feat(opd): SAH-12 book appointment API`. Agents may stage and propose commits (you are asked first). Pushing, merging, rebasing and hard resets are denied to agents.
- After `merge · APPROVED`: `git switch main` → `git merge --no-ff feature/SAH-12-...` → `git tag vYYYY.MM.N` when releasing.
- When GitHub is set up later: add the remote, protect `main`, and run `node scripts/validate.mjs all` in CI on every pull request.

## Testing and review
- Developers: unit and slice tests for everything they write.
- QA: integration tests on the local `sahaj_test` database (cleaned and migrated per run), Playwright e2e, and always: other tenant's data invisible, wrong role gets 403, bad input gets 400.
- Review: code-reviewer checks story, mockup match, contract, rules; security-reviewer checks access control, tenant isolation, health-data handling, dependencies.
