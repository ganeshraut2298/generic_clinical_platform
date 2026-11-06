# DEC-011 · Parallel teams, UI/UX pass and the Sahaj admin portal

Status: DECIDED 5 Nov by the human (chat instruction): run several full-stack teams in parallel, start with the Sahaj admin portal (a dummy admin in the user master, admin login, then one admin feature at a time), complete pending clinical parts, add a senior UI/UX agent to fix the inconsistent layout against the mockup, and keep story writing running in parallel.

## Teams (the tech lead is the main session; agents cannot spawn agents, so each "team lead" is a brief the tech lead gives to a fixed set of agents)
| Team | Scope | Agents |
|---|---|---|
| A · Admin portal | Platform admin login (dummy admin), organizations directory, modules/plan management | solution-architect → db-engineer → java-developer ∥ angular-developer → qa-engineer |
| B · Clinical (IPD and open fixes) | SAH-25 running bill, SAH-22/23/24 review fixes, next IPD stories | db-engineer → java-developer ∥ angular-developer → qa-engineer |
| C · UI/UX | Senior UI/UX pass: shell, layout, spacing, tokens, consistency against the mockup (repo mockup `docs/design/mockup/sahaj-mockup.html`, `docs/design/tokens.md`) | angular-developer in a senior UI/UX role (frontend only, shared layout and styles) |
| D · Stories | Requirements and stories for the next IPD/HRA/admin stories, written ahead of development | business-analyst |

## Rules that keep parallel work safe
1. One shared working tree and one `sahaj_test` database. Maven, `validate backend` and `validate db` must never run at the same time, and neither may `npm` test/build overlap. Every agent takes a lock before such a command: backend lock directory `<scratchpad>/locks/backend`, frontend lock directory `<scratchpad>/locks/frontend`. Acquire with `mkdir` (atomic), retry every 20 s, release with `rmdir`. A lock older than 60 minutes is stale and may be removed.
2. File ownership by team: Team C owns `frontend/src/styles*`, `frontend/src/app/core/shell/`, shared layout components and tokens; feature teams do not edit those without telling the tech lead. Team A owns `docs/api/admin.yaml` and the admin packages; Team B owns `docs/api/ipd.yaml`. Contracts are written by the architect or the tech lead before code.
3. No agent commits or pushes. Everything stays uncommitted on `feature/SAH-13-appointments-workspace`.

## Admin portal (Team A) — architecture change, recorded here as required
The platform admin is a person who works for Sahaj and can see all clinics. This is a new kind of user outside any tenant. Design constraints given to the architect: separate admin login (OTP as today, admin identified in a `platform` admin table, never a clinic membership), JWT role `PLATFORM_ADMIN` with no tenant, a separate security chain under `/api/v1/admin/**`; no change to the tenant RLS of clinic tables; cross-tenant reads and writes only through narrow `SECURITY DEFINER` functions owned by the migrator (list organizations, read one, set plan/modules/bed limit), each audited; the dummy admin is created only in the dev profile from environment values, never from a committed password or a migration; the dev OTP bypass rules already in place apply. The architect may propose a safer design; deviations come back to the human.
