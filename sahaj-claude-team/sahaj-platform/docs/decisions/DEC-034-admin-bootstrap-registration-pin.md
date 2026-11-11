# DEC-034 · Admin bootstrap registration behind a static PIN

Date: 10 Nov 2026. Status: NEEDS DECISION (the human asked for it in chat; it changes the admin authentication surface, so the safeguards need a human answer).
Requested by the human: "a function to register as admin in the admin portal; once I create the admin I will disable it; I have one static PIN that you encrypt on your side; when I put this PIN that interface will open."
The PIN itself is **not written in this file or anywhere in the repository** (rule: no secrets in code or docs). The human enters it in `.env` as a hash only.

## Context
- DEC-016 chose a dev-only sample-admin endpoint and warned that a public endpoint that creates an admin is a privilege-escalation backdoor: whoever reaches it owns every clinic.
- The admin portal (ADR-003 rev 2) is on its own security chain `/api/v1/admin/**` with TOTP. The admin tables are still parked in `db/pending/admin/` and the role `sahaj_admin_definer` does not exist; the human-only steps are listed in DEC-016 and in the last answer to the human.
- A 6-digit PIN has 1,000,000 values. Without a hard limit on attempts it can be guessed.

## What is requested (the design the architect will write, once the human answers)
1. One screen at `/admin/register` (new, not in the mockup: stops for the human, rule 1) that first asks for the PIN, and only after a correct PIN shows the form: name, email, password, then enrols the authenticator (TOTP), as the sign-in does.
2. One endpoint pair under `/api/v1/admin/bootstrap`: `POST /unlock` (PIN, returns a short single-use ticket) and `POST /register` (ticket + name, email, password; creates the first admin). Everything answers an empty 404 when the feature is off.
3. Safeguards I recommend as conditions:
   - **Switch:** off unless `SAHAJ_ADMIN_BOOTSTRAP_ENABLED=true` in `.env`. The human turns it off after the first admin, as he said.
   - **Self-closing:** the feature is also closed by the code the moment any platform admin exists, so a forgotten switch cannot create a second admin.
   - **PIN storage:** only a slow hash of the PIN (Argon2id or bcrypt) in `SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH`; the PIN is never in code, docs, logs or the repository; constant-time compare.
   - **Attempt limit:** at most 5 wrong PINs, then the feature locks for 60 minutes for everyone (global, not per IP, because the caller address cannot be trusted), with an audit entry; the lock also survives a restart (stored in the database).
   - **Ticket:** single use, valid 5 minutes, bound to the browser; the password rules and the breached-password check are the same as the sign-in.
   - **Reach:** loopback only in the dev profile; anywhere else only when the human explicitly opts in (see question 1).
   - **Audit:** unlock failures, the lock, and the creation are written to the admin audit table with no PIN and no password.
4. Not built: any way to create a second admin or reset an admin with the PIN (a separate story).

## Options for the questions that need the human
| # | Question | Options | Recommendation |
|---|---|---|---|
| 1 | Where may the bootstrap run? | (a) dev profile and loopback only; (b) any environment when the switch is on, behind the PIN | (a) for now; (b) only with a longer PIN and a human security sign-off |
| 2 | PIN strength | (a) keep the 6-digit PIN with the attempt limit; (b) a longer PIN or passphrase (8+ characters) | (b), because the 6-digit PIN was typed into a chat and is now known to the chat history; choose a new one and set only its hash in `.env` |

## What is blocked
The human-only prerequisites (moving the parked admin migrations, the role `sahaj_admin_definer`, the `.env` keys) block running anything. The design and the code can proceed after the answers.

## Human answer
(none yet)

## Human answer (chat, 10 Nov 2026, via the question dialog)
- Q1 where it runs: **Local dev only** (dev profile and loopback only).
- Q2 PIN strength: **Keep the 6-digit PIN**, with the attempt limit (5 wrong tries lock the feature for 60 minutes for everyone, stored in the database). The tech lead notes: the PIN was typed in chat and is in the chat history, so it is treated as known; it is never written into any file; the human puts only a hash in `.env`. The human may change to a longer PIN at any time without a code change (the hash is the only input).
- Status: DECIDED (work proceeds as story SAH-42). The registration screen is not in the mockup: the BA and architect design a minimal page that reuses the admin sign-in card (`pAdminLogin`), and the page is listed in DEC-013 as a deviation for the human's sign-off before it is built.

## Addendum, architect's points H-BOOT-1..7 (10 Nov 2026, defaults applied under DEC-023; the human can reverse any)
| # | Point | Default applied |
|---|---|---|
| H-BOOT-1 | The feature exists only in a `-Pdev-tools` build, so the human starts the backend with `-Pdev-tools` (adds a condition to BR1). | Accept. |
| H-BOOT-2 | The switch is read from the environment so `.env` works (ADR-008's sample flag is command-line only). | Accept. |
| H-BOOT-3 | A dev-tools callback creates one table, five definer functions and an INSERT column grant, in dev and test databases only. Residual risk: an SQL injection as `sahaj_app` in a dev database with no admin could create one without the PIN. | Accept (dev only). |
| H-BOOT-4 | SQL in docs/database/ by the db-engineer; the java-developer places it (as H-DEV-6). | Accept. |
| H-BOOT-5 | BCrypt cost 14 (Argon2 needs BouncyCastle, a new library). | BCrypt 14, no new library. |
| H-BOOT-6 | A per-start console nonce on the page. | No. |
| H-BOOT-7 | `.env.example` needs `SAHAJ_ADMIN_BOOTSTRAP_ENABLED=false` and an empty `SAHAJ_ADMIN_BOOTSTRAP_PIN_HASH=` (human-owned). | Human. |
Design: docs/architecture/adr/ADR-018-admin-bootstrap-registration-pin.md; contract: docs/api/admin.yaml 0.5.0.

## Addendum 11 Nov 2026: security review findings (docs/reviews/SAH-42-security.md)
No Critical or High. Verdict PASS with one condition: do not set `SAHAJ_ADMIN_BOOTSTRAP_ENABLED=true` on any machine until the 83 skipped database ITs (plus 9 others) have run green.
- **M1 (Medium, fix queued, default under DEC-023):** the wrong-PIN count is written after the compare, so if the counting function ever fails, the guess goes uncounted and a correct PIN still works (an unlimited oracle). Fix: count the try before comparing, in the same transaction under the row lock; reset on success. The architect updates ADR-018 first (Revision 3), then db-engineer, java-developer, QA (forced-failure IT).
- **M2 (Medium, the human's):** the PIN was typed in chat and is in 4 transcript files on disk, so it is known. The attempt limit does not protect against anyone who has read them. Recommendation: choose a new PIN of at least 10 random characters (option b of the original question; only the hash in `.env` changes, no code change), and never type it in chat. Until then keep the switch off.
- **M3 (Medium, the human's):** on Windows, "loopback" includes Docker Desktop containers, WSL2 in mirrored mode, other Windows sessions, VS Code port forwarding or dev tunnels and `netsh portproxy`. Accept explicitly (turn the switch on only for the minutes needed, with no containers or tunnels), or adopt H-BOOT-6 (a console nonce printed at each start) and/or H-BOOT-2 (a command-line-only switch). Default until answered: accept and keep the operating rule (DEC-023, not an approval).
- **L1:** the five database functions and the INSERT grant stay after the switch is turned off; create the first admin promptly (that closes them for good) or run the cleanup runbook. **L3:** `backend/target-devtools/` is tracked in git (1,735 files); add it to `.gitignore` and `git rm -r --cached backend/target-devtools` (human). **L4:** enrol the authenticator straight after registering; keep `dev-bypass` off once a real authenticator exists. **L2, I1-I4:** accept.
- **Code review:** CHANGES REQUIRED (docs/reviews/SAH-42-code.md): skips can hide failures (QA fix), the gate does not run the database ITs (merge approval must require `-Pdev-tools verify` with `Bootstrap*IT,PackagedJar*IT,DevToolsGuardCallbackIT`, 0 failed, 0 skipped among the Bootstrap database ITs), own branch needed.

## Addendum 11 Nov 2026 (later): human chat message "SAH-42 ... for approved and start dev"
The human asked in chat to treat SAH-42 as approved and to start development. Recorded as the human's intent. It is NOT a line in `docs/approvals.md` (agents and the tech lead cannot edit it), so the hook state is unchanged: no SAH-42 `stories` or `contract` line exists. Development of the server side is already built (see docs/handoffs/SAH-42.md). What still blocks the next step is human-owned: the role `sahaj_admin_definer` (scripts/), the activation of the parked admin migrations (needs the role first), the new PIN (M2), M3, H-BOOT-1..7, `.env.example`, `target-devtools`, and the `requireDb=true` run condition for merge.
Lines to add to `docs/approvals.md` (human only): `| SAH-42 | stories | APPROVED | Human | 2026-11-11 |` and `| SAH-42 | contract | APPROVED | Human | 2026-11-11 |`.

## Addendum 11 Nov 2026 (later): human chat message "approved and go ahead for pending items"
Given in reply to the tech lead's table of pending items, one of which was "SAH-42 /admin/register page: not in the mockup; you sign off DEC-013 item 16". Recorded as the human's sign-off of that one deviation (OQ-42-13, DEC-013 item 16): a minimal reuse of the `pAdminLogin` card, exactly as specified in docs/stories/SAH-42.md, no other screen added. It is NOT a line in `docs/approvals.md`. It does NOT cover ADM-2/ADM-3 (28 open questions), the 34A nurse-to-ward screen or the HRA questionnaires (not specified, nothing named), nor the database role (scripts/ is human-only). The Angular page is started under the standing chat go-ahead; the registration switch stays off.
