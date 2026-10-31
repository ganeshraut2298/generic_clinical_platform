# Story board (tech-lead keeps this current)

| Story | Title | Stage | Current agent | Branch | Waiting for | Last update |
|---|---|---|---|---|---|---|
| SAH-1 | Project scaffold | Reviews done; security FAIL | — | `feature/SAH-1-project-scaffold` | **Human: DEC-002 + DEC-003 (both still open)** | 2026-10-31 |

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
| `sahaj_dev` / `sahaj_test` databases | **BLOCKED** — server is up on `localhost:5432`, but the `postgres` superuser password is rejected. Databases still unverified. |
| `git status` clean on `main` | **NO** — 9 modified/deleted, 4 untracked (see below) |
| 9 agents available | OK — 8 subagents + tech-lead in `.claude/agents/` |
| Mobile push notifications | **UNAVAILABLE** — no notification tool in this session |

Notes:
- The git repository root is `D:\generi_clinical_platform`, one level above the project. Stray entries at that level: untracked `playbook.md`, deleted `sahaj-claude-team.zip`.
- Most uncommitted changes are in human-owned paths (`CLAUDE.md`, `.claude/`, `docs/approvals.md`, `docs/design/screen-map.md`, `SETUP.md`, `.env.example`), so the tech-lead cannot commit them.
- `frontend/` is empty and `backend/` has no `pom.xml` — expected, SAH-1 creates the scaffold.

## Blocker — 2026-10-31: PostgreSQL superuser password

`psql -U postgres -h localhost -d sahaj_dev` returns `FATAL: password authentication failed for user "postgres"`.
The server is running and reachable; only the credential is wrong.

`pg_hba.conf` uses `scram-sha-256` for all local and loopback connections, so a correct password is mandatory.

This single blocker also covers `.env` and the database check, because
`scripts/setup/create-database.mjs` reads `PG_SUPERUSER_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD`
and `SAHAJ_DB_APP_PASSWORD` from `.env` and does the rest.
`scripts/db/init.sql` is idempotent (`WHERE NOT EXISTS`), so it is safe to re-run.

Tech-lead cannot resolve this: `.env` is human-owned, and resetting the password needs an
elevated edit of `pg_hba.conf` plus a service restart.

## Open decisions

| Decision | What it blocks | Status |
|---|---|---|
| DEC-002 · Karma flag in `validate.mjs` | SAH-1 QA and merge | Human chose "drop the flag"; **edit not applied yet** (line 28 unchanged) |
| DEC-003 · Real postgres password in unpushed commit `3f59573` | SAH-1 merge; do not `git push` | Human chose "rotate, sanitise, rewrite"; **not done yet** (`.env.example` on `main` still holds the real value) |
| DEC-004 · Mockup shows screens with no story | Order of work after SAH-1 | **DECIDED** — all three early, in mockup order. Stage A becomes 6 stories; everything from old SAH-5 shifts +2. Human to paste the screen-map and playbook rows. |
| DEC-005 · "HRA" names two unrelated modules | Module list, `hra` package and schema, SAH-32 and SAH-33 | **Awaiting human** |

## Mockup — verified 31 Oct 2026

`docs/design/mockup/sahaj-mockup.html` (1,131,000 bytes, saved 21:20) matches the mockup the human
supplied as the key reference. Confirmed by distinctive markers: `sfMar`, `pcDefault`, `orgDirPage`,
`SEED_RAVET`, `ipdSeedHospital`, `axSeedMenu`, `bimpCheck`.

`docs/design/screen-map.md` has 28 screens and is **missing three** the mockup now shows
(Home page, Sign up and log in, Setup wizard), and its HRA row is stale. See DEC-004.
