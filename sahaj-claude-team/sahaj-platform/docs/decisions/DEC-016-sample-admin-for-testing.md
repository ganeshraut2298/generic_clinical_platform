# DEC-016 · A way to get a sample admin user for testing

Status: DECIDED (human chose option B in chat, 5 Nov 2026). Blocks: nothing else; only the admin test login.
Requested by the human: "create one API that creates a sample admin that I can use for testing".

## Context
- The admin portal (ADR-003 rev 2) has its own security chain `/api/v1/admin/**`, TOTP, and SECURITY DEFINER functions owned by the NOLOGIN role `sahaj_admin_definer`.
- Its migrations are parked in `backend/src/main/resources/db/pending/admin/` (V2026_11_15_1000/1010). The role `sahaj_admin_definer` does not exist yet (needs `scripts/db/init.sql` and `scripts/setup/create-database.mjs`, human-owned). Until both are done, no admin table exists, so no endpoint can create an admin.
- The designed way to get the first admin is a seed from environment settings (`SAHAJ_ADMIN_SEED_EMAIL`, `_PASSWORD`, `_NAME`) with `SAHAJ_ADMIN_ENABLED=true`, `SAHAJ_ADMIN_JWT_SECRET` and `SAHAJ_ADMIN_TOTP_KEY`. `.env` is human-owned.
- The Spring default profile is `dev` (open item). An endpoint guarded only by "dev profile" would be live in any deployment that forgets to set a profile.
- A public endpoint that creates an admin is a privilege-escalation backdoor: anyone who can reach it owns every clinic.

## Options
| | Option | Pros | Cons |
|---|---|---|---|
| A | **Dev seed at startup, no endpoint** (recommended). With profile `dev` AND `SAHAJ_ADMIN_ENABLED=true`, create the admin once from the seed variables if none exists, enrol TOTP, print the TOTP setup secret once to the local console only. Plus a `scripts/` helper the human can run to print a login. | No new attack surface; uses the approved ADR-003 design; works offline; password stays in `.env`. | Slightly less convenient: you set the three variables yourself. |
| B | **Dev-only endpoint** `POST /api/v1/admin/dev/sample-admin`: exists only if the property `sahaj.admin.dev-sample-enabled=true` (off by default, never set in prod) AND profile `dev`; accepts loopback requests only (remote address check, no X-Forwarded-For trust); creates `sample-admin@example.test` with a random password and a TOTP secret, returned once in the response; fails with 404 if an admin exists or the property is off. | One call gives credentials; no manual `.env` edits for the password. | Auth change, needs an ADR amendment and a security review; a misconfigured server with default profile `dev` plus the property is a full admin takeover; the credentials sit in a chat or log; test-only code in production artifacts unless excluded by a build profile. |
| C | Do nothing; use only the clinic dev login (any 10-digit mobile + any 6-digit code in the dev profile). | Nothing to build. | No admin portal testing. |

## Recommendation
Option A. If B is chosen anyway, it must be: absent from the jar unless a Maven profile `dev-tools` is used, requires the property AND the dev profile AND loopback, returns the secret only once and never logs it, and goes through a full security review before use.

## What is blocked
Nothing. The human-only prerequisites (role `sahaj_admin_definer`, `.env` keys, moving the parked migrations) are needed for A and B alike.

## Decision
Human chose **B, dev-only endpoint**, in chat. Conditions the tech lead sets (from the recommendation): the endpoint is absent from the production jar (only built with Maven profile dev-tools), and at runtime requires profile dev AND property sahaj.admin.dev-sample-enabled=true AND a loopback caller; it returns the password and TOTP secret once, never logs them, answers 404 otherwise or when an admin already exists; ADR-003 amendment and a full security review before use. The human-only prerequisites still apply (role sahaj_admin_definer, .env admin keys, moving the parked migrations).

## Architect's design (ADR-008, 5 Nov) and questions that need the human
Design: docs/architecture/adr/ADR-008-dev-sample-admin.md; contract: docs/api/admin.yaml `POST /dev/sample-admin` (`x-dev-only`, 201 one-time response, empty-body 404, 429). Endpoint exists only when: built with Maven profile `dev-tools` (separate source set, separate `target-devtools` directory, own jar name); profile `dev` (and only dev/test) active; `sahaj.admin.dev-sample-enabled=true` on the command line; admin realm on with both secrets; no proxy settings; `server.address` loopback. Per request: loopback remote address, no forwarding headers (their presence refuses the call), Host is 127.0.0.1/localhost/[::1], no Origin/Sec-Fetch headers, POST only, no admin exists (checked atomically in the database). Anything else answers the same empty 404 (a reserved-path filter in main code makes the production jar answer 404 too). Password: 24 characters from SecureRandom, TOTP secret enrolled; returned once, never logged; hashing and enrolment reuse the env-seed code path.
Open questions (H-DEV-1 to H-DEV-8 in ADR-008): database write path (W1: a dev-tools-only Flyway callback gives `sahaj_admin_definer` INSERT on the admins table and creates a definer function; changes ADR-003 D1 in dev/test databases only), 404 vs 409 when an admin exists (default 404), recovery codes (default none), flag set outside dev (default: keep answering 404), renumbering of the parked migrations (their versions are below those already applied), ownership of `backend/src/devtools/`, a new build plugin (build-helper-maven-plugin), and a jar check in `scripts/validate.mjs`.

## Human answers (chat, 5 Nov 2026)
- H-DEV-1: **W1, dev-tools-only database script** (grants the admin definer role INSERT on the admins table and creates a definer function that makes exactly one sample admin; only in the dev-tools build, only dev/test databases).
- H-DEV-5: **Renumber** the parked V2026_11_15_* migrations to the next free versions when they are moved into db/migration (no outOfOrder).
- H-DEV-7: **build-helper-maven-plugin approved** (build time only) for the `dev-tools` Maven profile.
- H-DEV-2/3/4: **defaults accepted**: an existing admin gives the same empty 404; no recovery codes for the sample admin; the flag set outside dev still answers 404 (no startup refusal).
- Not asked, tech lead position: H-DEV-6 the Java code and the SQL callback both live in backend/src/devtools; the db-engineer writes the SQL in docs/database/ADR-008-dev-sample-admin.sql and the java-developer places it; H-DEV-8 (jar check in scripts/validate.mjs) is a recommendation for the human, a unit test and a QA jar test cover it meanwhile.
- Still needed from the human: a `contract` approval line in docs/approvals.md for this endpoint (the human's chat choice is not an approval line), the role `sahaj_admin_definer` in scripts/db/init.sql and scripts/setup/create-database.mjs and rerun of the setup, admin env keys in .env (leave SEED_EMAIL/SEED_PASSWORD empty, never put the dev-sample flag in .env or .env.example), permission for agents to write backend/src/devtools/ if the hooks block it.

## Security review fix pass (5 Nov): decisions by the tech lead
Security review: docs/reviews/DEC-016-security.md (PASS, no Critical/High; go for a developer machine with conditions; no-go for shared/staging/server). ADR-008 revision 2 and admin.yaml 0.3.1 add: a per-start nonce (header X-Sahaj-Dev-Nonce, printed once in the startup log line, constant-time compare, same empty 404 on a miss), the flag accepted from the command line only, a wider forwarding-header list, a cloud-platform check, message-free database errors, a production guard callback that stops the app if the sample-admin function/grant/row is found outside sahaj_dev and sahaj_test, a cleanup runbook, install/deploy skipped and a distinct image name in the dev-tools profile, and a packaged-jar test (T13) by QA.
- H-DEV-9 (who writes `backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql`): the db-engineer writes the SQL in docs/database/ and the java-developer places it (folder ownership unchanged). Adding the Flyway location `classpath:db/guard` is a defensive guard, not a new service or library; recorded here, the human may object.
- Usage will change: the human copies the nonce from the console and sends it as header `X-Sahaj-Dev-Nonce`.
- Human-only still open: contract approval line (M4), `backend/target-devtools/` in .gitignore (L7), jar check in scripts/validate.mjs (H-DEV-8), setup prerequisites.
