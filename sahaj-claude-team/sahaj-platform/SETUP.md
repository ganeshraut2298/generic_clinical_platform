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

## 3. Put the project in your working folder and start Git

Use `D:\generi_clinical_platform` as the project root: copy the **contents** of this `sahaj-platform` folder into it, so `CLAUDE.md`, `.claude\`, `docs\` and `scripts\` sit directly in `D:\generi_clinical_platform`. Keep your `.env` there too (Git ignores it).

```powershell
cd D:\generi_clinical_platform
git init -b main
git add .
git commit -m "chore: Sahaj Claude Code team, rules, mockup and database setup"
```

## 4. Open Claude Code

```powershell
cd D:\generi_clinical_platform
claude
```
1. **Accept the workspace trust prompt.** Without it the guard hooks do not run.
2. The session starts as the **tech-lead** agent (set in `.claude/settings.json`). Type `/agents` — you should see 9 agents.

## 5. Build the project with the agents

Follow `docs/process/playbook.md` from SAH-1 to SAH-32. Your first message:

> Start SAH-1.

For every story you do only four things: approve `stories`, approve `contract`, answer escalations, approve and run the `merge`. The playbook shows the exact lines and commands.

## 6. Check that the guards work (optional, 2 minutes)

- Ask: "Have the angular-developer edit backend/pom.xml" → blocked (ownership).
- Before approving a contract, ask the tech-lead to start the java-developer → blocked (workflow).
- A story file without `Mockup screen:` → `node scripts/validate.mjs docs` fails.
- Ask the code-reviewer to create a file → it has no write tools.

## Changing the design

Only humans change `docs/design/mockup/sahaj-mockup.html` and `docs/design/screen-map.md`. Update the mockup first, then start or resume the story. HRA screens are not in the mockup yet.
