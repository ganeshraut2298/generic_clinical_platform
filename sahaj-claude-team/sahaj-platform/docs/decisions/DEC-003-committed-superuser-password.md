# DEC-003 · A real PostgreSQL superuser password is committed to git

Date: 31 Oct 2026
Raised by: security-reviewer (C1), verified by tech-lead
Status: **DECIDED** — Option A, plus a secret-scan gate. Human decided 31 Oct 2026.
Severity: **raised as Critical, downgraded to High after verification — see "Containment" below**
Blocks: SAH-1 merge until `.env.example` is sanitised and the unpushed commit is rewritten

## Containment — verified by the tech-lead after the review

The security review assumed, from `CLAUDE.md` ("Git is local only for now — no GitHub, no CI yet"),
that no remote existed. **That assumption is wrong: a GitHub remote is configured**
(`origin → https://github.com/ganeshraut2298/generic_clinical_platform.git`). That made the real
question whether the secret had been pushed. It has not:

| Check | Result |
|---|---|
| `PG_SUPERUSER_PASSWORD` on **`origin/main`** | **`change-me`** — still the placeholder |
| Unpushed local commits | exactly one: `3f59573` "chore: tech-lead role, playbook and setup updates" |
| Commit holding the real value | `3f59573` — **the unpushed one** |
| Commits on `origin` touching the variable | `ed2c07d`, which set it to `change-me` |

So the credential **never left this machine via git**. It lives in exactly one local, unpushed
commit. No force-push is needed, no shared history is affected, and nothing is public.

Severity downgraded **Critical → High**. It stays High rather than Medium because:
- The value was also pasted into the session chat transcript, which is a real exposure independent
  of git and is the reason rotation is still required.
- One `git push` would publish it, and the branch is sitting one commit ahead right now.

Also recorded: `CLAUDE.md`'s "Git is local only (no GitHub, no CI yet)" is factually out of date and
should be corrected, since both reviewers and the tech-lead reasoned from it.

## Context

The security review of SAH-1 found a real PostgreSQL **superuser** password committed in
`.env.example`, reachable from both `main` and `feature/SAH-1-project-scaffold`. The file is
deleted in the working tree but still tracked and present in history.

The tech-lead verified this independently rather than taking the finding on trust. The value is
deliberately not reproduced in this file:

| Check | Result |
|---|---|
| `PG_SUPERUSER_PASSWORD` on `main` equals `change-me`? | **No** — a real value |
| Of the three password variables, how many are still `change-me`? | **2 of 3** |
| `SAHAJ_DB_MIGRATOR_PASSWORD` / `SAHAJ_DB_APP_PASSWORD` | still `change-me` — unaffected |

So exactly one credential is exposed, and it is the superuser one.
`scripts/setup/create-database.mjs` passes this variable as `PGPASSWORD` to connect as `postgres`,
and rejects any value starting with `change-me` — so a committed non-`change-me` value is by
construction the working credential.

This breaks `CLAUDE.md`'s non-negotiable "No secrets in code" rule and falsifies SAH-1-4's first
acceptance criterion. OWASP ASVS V14.1.4 / V6.4.1, CWE-798, CWE-540. DPDP Act s.8(5) requires
reasonable security safeguards for systems that will hold patients' health data.

## Why Critical, when exploitability today is low

Git is local-only — no remote, no CI (`CLAUDE.md`) — and the account is a local dev instance. Rated
Critical regardless:

1. Git history is permanent. The exposure window never closes on its own; it opens the first time a
   remote, backup, archive or the planned GitHub migration happens.
2. Deleting the file, which the working tree already does, removes **nothing** from history.
3. **The remediation the pipeline had documented made it worse.** ADR-001 D5 and
   `docs/handoffs/SAH-1.md` both told the human to `git restore .env.example`, which re-materialises
   the secret on disk. The tech-lead repeated that instruction three times in session before this
   review caught it. Corrected now; recorded here because it is the more useful lesson.
4. The same value was also pasted into the session chat transcript, so it must be treated as
   compromised independently of git.

## Options

**A · Rotate, sanitise, squash history (recommended).**
1. Rotate the local `postgres` password now, and anywhere else the value was reused.
2. Restore `.env.example`, immediately reset `PG_SUPERUSER_PASSWORD` to `change-me`, and add
   `SERVER_PORT=8080` and `SPRING_PROFILES_ACTIVE=dev` while there.
3. Squash the three commits into one clean initial commit, removing the value from history. The repo
   is three commits old, local, and has no remote — this is the cheapest moment it will ever be.
- Pros: closes the problem permanently while it is nearly free. Keeps `main` honest before SAH-2
  starts adding real schema work.
- Cons: rewrites the three existing commits, so any local clone or stash referencing them needs care.

**B · Rotate and sanitise, leave history alone.**
Steps 1-2 only. Accept the value staying in history, with a note never to add a remote before
purging.
- Pros: no history rewrite.
- Cons: leaves a live landmine for the GitHub migration `CLAUDE.md` plans. The note will be forgotten.

**C · Rotate only.**
Treat the value as dead and move on, leaving both history and `.env.example` as they are.
- Pros: fastest.
- Cons: a non-placeholder password stays in a file whose entire purpose is to hold placeholders, so
  SAH-1-4's acceptance criterion stays false and the next `create-database.mjs` run silently uses a
  stale credential. Not recommended.

## Recommendation

**Option A.** Three commits, no remote, one real secret. The cost of a clean squash now is minutes;
the cost after a GitHub push is a full credential rotation plus history surgery on a shared repo.

Also worth doing, separately: add a secret-scan leg to `scripts/validate.mjs` — fail if any tracked
file matches `PASSWORD=`/`SECRET=`/`TOKEN=` with a value that is neither `change-me` nor empty. The
gate has no such check, which is why this survived three commits and two agent stages. The
tech-lead cannot write that file.

## Carried into SAH-2 (not blocking SAH-1)

- **Boot / Modulith version mismatch (security M3, correcting code-review).** Modulith 1.3.x targets
  Boot 3.4.x; the project pins Boot 3.3.4, so `spring-modulith-apt` fails with
  `NoClassDefFoundError: JsonWriter$Members` and was excluded to silence it. Verified by the
  tech-lead: `spring-modulith-apt-1.3.0.pom` declares `spring-boot 3.4.0`. A cache purge reproduces
  the failure rather than fixing it. Align the pair — **Boot 3.5.x + Modulith 1.4.x preferred**, which
  also resolves M2 — then drop the exclusion.
- **Boot 3.3.4 is out of open-source support (M2).** Not exploitable in SAH-1 (no web starter, no
  actuator, no `DataBinder`) but becomes **High** at SAH-3 when Spring Security and the web starter
  arrive.
- **`@ActiveProfiles("dev")` in `SahajApplicationTests` (M1).** Change to `test` or remove, before
  SAH-2's datasource makes it point at `sahaj_dev`.
- **Google Fonts CDN in `index.html` (M4).** Self-host before SAH-4 sets a CSP; it is also a
  cross-border metadata transfer that cuts against "all patient data stays in India".
- **DEC-001 steps 2-4 unapplied (M5).** `CLAUDE.md` and `docs/approvals.md` still claim the
  `stories`/`contract` gates are enforced while the hook no longer enforces them.
- **At SAH-2, remember:** a table owner bypasses RLS unless `FORCE ROW LEVEL SECURITY` is set.
  Verify policies as `sahaj_app`, not as `sahaj_migrator`.

## Decision

**Option A — rotate, sanitise, rewrite — plus a secret-scan gate.** Decided by Ganesh, 31 Oct 2026.

Simplified by the containment finding: because `3f59573` is the **only** unpushed commit and the
secret exists nowhere else, there is no need to squash three commits or rewrite shared history. A
soft reset of that single commit is enough.

### Steps for the human (all in human-owned territory)

1. **Rotate the local `postgres` password.** Required regardless of git, because the value is in the
   session transcript. Rotate it anywhere else it was reused.
2. **Do not `git push` until step 4 is done.** One push publishes the secret.
3. **Restore `.env.example` from the clean remote version** — not from local history, which is the
   mistake the tech-lead made three times:
   ```
   git -C D:\generi_clinical_platform checkout origin/main -- sahaj-claude-team/sahaj-platform/.env.example
   ```
   `origin/main`'s copy already has `PG_SUPERUSER_PASSWORD=change-me`. Then add the two variables the
   SAH-1 backend reads: `SERVER_PORT=8080` and `SPRING_PROFILES_ACTIVE=dev`.
4. **Rewrite the one unpushed commit** so the value leaves history:
   ```
   git -C D:\generi_clinical_platform switch main
   git -C D:\generi_clinical_platform reset --soft origin/main
   git -C D:\generi_clinical_platform checkout origin/main -- sahaj-claude-team/sahaj-platform/.env.example
   # re-add SERVER_PORT / SPRING_PROFILES_ACTIVE to .env.example, then:
   git -C D:\generi_clinical_platform commit -m "chore: tech-lead role, playbook and setup updates"
   git -C D:\generi_clinical_platform branch -f feature/SAH-1-project-scaffold main
   git -C D:\generi_clinical_platform switch feature/SAH-1-project-scaffold
   ```
   The `branch -f` is safe: `feature/SAH-1-project-scaffold` has **no commits of its own** yet — all
   SAH-1 work is still uncommitted in the working tree and follows the worktree, not the branch.
   History rewriting, resets and branch deletion are denied to the tech-lead, so these are the
   human's to run.
5. **Verify:** `git log --all -S "<the old value>" --oneline` should return nothing.

### Secret-scan gate — also approved

`scripts/validate.mjs` has no secret scanning, which is why this survived three commits and two
agent stages. The human approved adding one. Suggested leg, to be added by the human (the tech-lead
cannot write `scripts/`):

```js
function secrets() {
  const tracked = spawnSync('git', ['ls-files'], { cwd: root, encoding: 'utf8' }).stdout.split('\n').filter(Boolean);
  const bad = [];
  for (const f of tracked) {
    let t; try { t = read(f); } catch { continue; }
    for (const m of t.matchAll(/^\s*[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|API_KEY)[A-Z0-9_]*\s*=\s*(.+)$/gmi)) {
      const v = m[2].trim();
      if (v && !/^(change-me|<|\$\{|""|'')/.test(v)) bad.push(`${f}: ${m[1]}`);
    }
  }
  if (bad.length) fail(`secret-looking values in tracked files:\n  ${bad.join('\n  ')}`);
  console.log('secrets: no committed credentials found');
}
```
Wire it into the `docs` and `all` areas so every story runs it.
