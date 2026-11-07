# DEC-019 · Adopting the multi-team master prompt: keep the rules, add parallel lanes

Status: DECIDED (human answered in chat, 7 Nov 2026).

## Context
The human pasted a "multi-agent organization" master prompt (one Product Owner, paired BA + full-stack developer teams, a `/documentation` tree, `develop` branch, fewer approvals, a story tracker). Parts conflict with the project rules in CLAUDE.md and the hooks (human approval lines, folder ownership, mockup is the design, no commits by agents).

## Options offered
1. Keep rules, add parallel teams (recommended).
2. Full reorganization (human must first edit CLAUDE.md, .claude/ hooks and docs/approvals.md).
3. Tracker and plan only.

## Decision
Option 1. The current gates stay. The human is the Product Owner (approval lines, merge, mockup changes). Added:
- Parallel lanes: several business-analyst agents write stories for different modules at the same time; independent stories may be built at the same time by java/angular agents (separate Git worktrees where they would touch the same files; only one backend test run at a time on `sahaj_test`, one frontend test run at a time: the lock protocol stays).
- A status tracker at the top of docs/handoffs/BOARD.md: totals, per-status counts, blocked stories with reason / owner / action, per-agent progress.
- No change to CLAUDE.md, .claude/ or docs/approvals.md. No `/documentation` tree, no `develop` branch (git stays the human's).

## Lanes opened 7 Nov 2026
- Lane 1: SAH-35 Medicine round on the Safety board (stories done; architect next).
- Lane 2: SAH-36 Admitted patient cards (BA).
- Lane 3: HARD-3 colleague PIN guessing and dose event wording (BA).
