# DEC-006 · Approvals given in chat

Date: 1 Nov 2026. Decided by: Ganesh.

Decision: "I give it approval from here, for further via command will give you approval." This approves starting Phase 1 development from the briefs, in place of the `phase-1-brief` line. Later approvals, including `merge`, are given by the human as commands in chat, one at a time.

Update, same day (Ganesh): "Everything written in this branch, further approval I will give via command. Don't depend on docs approval, change this rule. First we target to complete the development."
- `docs/approvals.md` is no longer a gate. Agents and the tech lead do not wait for lines in it.
- All Phase 1 work stays on `feature/SAH-2-home-page`, across stories. Handoffs still name the story ID.
- The tech lead moves to the next story without waiting for a merge, and records assumptions in the story instead of stopping on UI or scope details.
- Still needs a command from the human: commit, merge. Agents never push.
- `CLAUDE.md`, `.claude/agents/tech-lead.md` and `docs/process/` still describe the old rule. `.claude/settings.json` denies agents from editing the first two, so the human must change them. This decision overrides them until then. The ownership hook already has no approval gate (`NEED` is empty).

Notes:
- `docs/approvals.md` is human-only, so this file is the record until the human copies the lines there.
- This does not cover the password clean-up in DEC-003, which is still open.

Working arrangement while the human's clean-up and scaffold merge are pending:
- SAH-1 backend and SAH-2 work share the checkout on `feature/SAH-2-home-page`, because a branch switch would disturb agents running in the same folder. Backend and frontend paths do not overlap, so commits can still be split by story.
- The ownership hook still gives migrations to db-engineer and integration and e2e tests to qa-engineer, so those agents are used for that work.
