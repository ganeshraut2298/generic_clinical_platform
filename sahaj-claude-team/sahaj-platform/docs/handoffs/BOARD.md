# Story board (tech-lead keeps this current)

| Story | Title | Stage | Current agent | Branch | Waiting for | Last update |
|---|---|---|---|---|---|---|
| SAH-1 | Project scaffold | BLOCKED (pre-flight) | — | — | Human: clean tree, create `.env`, confirm databases | 2026-10-31 |

## Pre-flight check — 2026-10-31

| Check | Result |
|---|---|
| Java 17 (JDK) | OK — openjdk 17.0.21 LTS (Microsoft) |
| Maven 3.9+ | OK — Apache Maven 3.9.9 |
| Node 20+ | OK — v26.13.0 |
| npm | OK — 11.2.0 |
| Git | OK — 2.56.0.windows.4 |
| PostgreSQL 16 client | OK — installed at `C:\Program Files\PostgreSQL\16`, `psql` on PATH |
| `scripts/setup/check-env.mjs` | Could not run — blocked by the session permission layer. All six of its checks verified by hand instead. |
| `.env` | **MISSING** — only `.env.example` is present |
| `sahaj_dev` / `sahaj_test` databases | **UNVERIFIED** — `psql` execution blocked in this session; needs the human to confirm |
| `git status` clean on `main` | **NO** — 9 modified/deleted, 4 untracked (see below) |
| 9 agents available | OK — 8 subagents + tech-lead in `.claude/agents/` |
| Mobile push notifications | **UNAVAILABLE** — no notification tool in this session |

Notes:
- The git repository root is `D:\generi_clinical_platform`, one level above the project. Stray entries at that level: untracked `playbook.md`, deleted `sahaj-claude-team.zip`.
- Most uncommitted changes are in human-owned paths (`CLAUDE.md`, `.claude/`, `docs/approvals.md`, `docs/design/screen-map.md`, `SETUP.md`, `.env.example`), so the tech-lead cannot commit them.
- `frontend/` is empty and `backend/` has no `pom.xml` — expected, SAH-1 creates the scaffold.
