# DEC-012 · Admin portal: decisions needed from the human (H1, H2, H3)

Status: DECIDED 5 Nov by the human via AskUserQuestion: H1 = option B (email, password and TOTP); H2 = option A (new role sahaj_admin_definer; the human must add it to scripts/setup/create-database.mjs and scripts/db/init.sql and re-run the database setup). H3 and H4-H14 stay at the architect defaults. Source: docs/architecture/adr/ADR-003-sahaj-admin-portal.md, contract docs/api/admin.yaml. Full list H1 to H14 is in the ADR; the architect's defaults stand for H4 to H14 unless the human says otherwise.

## H1 · Sign-in factor
The mockup draws email + password + authenticator app. DEC-011 said mobile OTP (single factor).
- A. Mobile OTP now, add TOTP before production (recommended: matches the clinic login, fastest, dev bypass works for the dummy admin).
- B. Build email + password + TOTP now (matches the mockup, adds password storage and TOTP enrolment; about one more story).

## H2 · Who runs the cross-clinic functions
Every clinic table has FORCE ROW LEVEL SECURITY, so a function owned by the migrator cannot see other clinics' rows. Options:
- A. A new NOLOGIN database role `sahaj_admin_definer` that owns the functions, with policies granted only to that role (recommended; keeps FORCE intact). Needs a human edit of `scripts/setup/create-database.mjs` / `scripts/db/init.sql` to create the role, then re-running the setup script (I may not run it).
- B. Open the clinic tables to the migrator (weakens FORCE; not recommended).

## H3 · Separate database login for admin requests (`sahaj_admin_app`)
Without it, an SQL injection anywhere in clinic code could call the admin sign-in function. Recommended before production, can follow in a later story.

## Blocked until answered
ADM-1 database migration (needs H2). ADM-1 sign-in design (needs H1).
