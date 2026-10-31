# How to set up and use the Sahaj AI development team

## 1. Install the tools (once per computer)

**Windows 10/11** — open PowerShell **as Administrator** in this folder:
```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\setup\install-windows.ps1
```
Installs Git (with Git Bash), Java 17, Node LTS, PostgreSQL 16 and Maven 3.9.9. Close the window and open a **new** terminal.

**macOS:** `bash scripts/setup/install-macos.sh` (needs Homebrew). **Ubuntu:** `bash scripts/setup/install-ubuntu.sh`.

Check everything:
```bash
node scripts/setup/check-env.mjs
```

## 2. Secure PostgreSQL and create the Sahaj databases

1. Change the default superuser password (Windows installs it as `postgres`):
   `psql -U postgres -c "ALTER USER postgres PASSWORD 'your-strong-password';"`
2. Copy `.env.example` to `.env` and fill in `PG_SUPERUSER_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD`, `SAHAJ_DB_APP_PASSWORD`.
3. Create everything:
   ```bash
   node scripts/setup/create-database.mjs
   ```
   This creates roles `sahaj_migrator` (owns tables, runs Flyway) and `sahaj_app` (used by the app, cannot bypass row-level security), databases `sahaj_dev` and `sahaj_test`, and schemas `platform, patient, opd, ipd, hra, billing`. It is safe to run again. Tables come later from Flyway migrations.

## 3. Start the local Git repository

```bash
git init -b main
git add .
git commit -m "chore: SAH-0 Claude Code team, rules, mockup and database setup"
```
No GitHub for now. Agents cannot push, merge or add remotes.

## 4. Open in Claude Code

1. Install Claude Code and run `claude` in this folder.
2. **Accept the workspace trust prompt.** Without it the agents still run, but their guard hooks are skipped.
3. Type `/agents` — you should see 8 agents: business-analyst, solution-architect, db-engineer, java-developer, angular-developer, qa-engineer, code-reviewer, security-reviewer.

## 5. First story: SAH-0, the project scaffold

`docs/approvals.md` already approves SAH-0 stories and contract (it has no screens). Tell the main session:

> Act as tech lead. Create branch feature/SAH-0-scaffold. Ask the java-developer to create the Spring Boot 3 (Java 17, Maven) app in backend/ with modules platform, patient, opd, ipd, hra, billing, Spring Modulith, Flyway, profiles dev and test reading SAHAJ_DB_URL / SAHAJ_TEST_DB_URL. Ask the db-engineer for the first migration: platform.tenant and platform.app_user with row-level security. Ask the angular-developer to create the Angular app in frontend/ with Angular Material, the theme from docs/design/tokens.md and the app shell (sidebar, header) from the mockup. Then QA, then both reviewers. Stop for my approval before any merge.

## 6. Every feature after that

1. Pick a screen from `docs/design/screen-map.md` (Phase 1).
2. Tell the main session: *"Act as tech lead. Start SAH-12 for the mockup screen 'Appointments'. Run the business-analyst."*
3. Read `docs/stories/SAH-12.md`. If correct, add to `docs/approvals.md`: `| SAH-12 | stories | APPROVED | Aarti | <date> |`, then say *"continue"*.
4. Read `docs/api/<module>.yaml`. If correct, add `| SAH-12 | contract | APPROVED | ... |`, then *"continue"*.
5. The tech lead runs DB → developers → QA → reviewers and shows you the reports.
6. If you are happy: add `| SAH-12 | merge | APPROVED | ... |`, then
   `git switch main && git merge --no-ff feature/SAH-12-appointments`.

## 7. Check that the guards work

- Ask the angular-developer to edit `backend/pom.xml` → blocked (ownership).
- On a story without `contract · APPROVED`, ask the java-developer to write code → blocked (workflow).
- Write a story without `Mockup screen:` → `node scripts/validate.mjs docs` fails.
- Ask the code-reviewer to create a file → it has no write tools.

## Changing the design

Only humans change `docs/design/mockup/sahaj-mockup.html` and `docs/design/screen-map.md`. Update the mockup first, then start the story. HRA screens are not in the mockup yet.
