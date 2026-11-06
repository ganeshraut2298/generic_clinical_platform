# ADM-1 · Sahaj admin portal sign-in (ADR-003 rev 2)

## Handoff · angular-developer → qa-engineer (frontend) · 5 Nov

Branch: `feature/SAH-13-appointments-workspace` (uncommitted).

Built: lazy feature `frontend/src/app/features/admin/`, mounted at `/admin` outside the clinic shell. Routes `/admin/sign-in` (mockup pAdminLogin), `/admin/login` redirects to it, `/admin` redirects to `/admin/clinics` behind the admin guard. Sign-in with email, password and authenticator code; enrolment step; ten recovery codes; recovery-code sign-in; fixed error texts (no server text shown); admin shell with placeholder "Organizations coming in the next release". Own in-memory token store and interceptor (attached only to `/api/v1/admin/**`), silent refresh once on 401 through the cookie endpoint. The clinic interceptor was minimally edited to skip `/api/v1/admin/**` (isolation both ways, tested).

Results: lint pass; tests 96 files / 714 tests pass; build pass. `validate frontend` fails at its test step only because the human-owned script passes `--browsers=ChromeHeadless`, which the vitest runner rejects.

Open items:
1. Delete `frontend/src/app/features/admin/admin-coming-soon/` (unused; the agent could not delete it). Owner: angular-developer, next pass.
2. Topbar shows name and role only; `AdminMe` has no email. Contract change only if the human wants the email shown.
3. New texts not in the ADR (H13): password/recovery-code prompts, 503 and offline messages, copy toasts. Human to confirm wording.
4. e2e `frontend/e2e/home.spec.ts:210` expects `/admin$`; now redirects to `/admin/sign-in`. QA to update. Clinic links could point to `/admin/sign-in` directly.
5. No proactive timer for token expiry; the refresh happens on first 401.
6. Backend CORS must allow credentials for the admin paths (refresh cookie `sahaj_admin_rt`). Java to confirm.

Next: qa-engineer after the backend (java-developer, running) and the database part (blocked on the human creating `sahaj_admin_definer`).

## Handoff · java-developer → qa-engineer (backend) · 5 Nov
Backend complete behind `sahaj.admin.enabled` (default false): second security chain /api/v1/admin/**, admin JWT (own secret, aud sahaj-admin), TOTP (RFC 6238), AES-GCM secret cipher, BCrypt-12 hasher, recovery codes, rate limits, network allow-list, dev-only seed and TOTP bypass with guards, clinic JWT now rejects the admin audience. About 211 new tests; `validate backend` PASSED. Secrets are not in application.yml (ProfileConfigIT forbids defaults); Spring binds SAHAJ_ADMIN_* env vars directly.

Deviations and requests (details in the agent report):
- db-engineer (parked migrations): `admin_preauth_get` should return email and throttle key (removes PreAuthEmailBridge); `admin_session_rotate` should return auth_method; optional audit summary for dev-bypass sign-in.
- Request bodies do not reject unknown JSON fields (contract says additionalProperties false); minor.
- Enabling the feature before the parked migrations are active makes the dev seed fail.
- Human: create role `sahaj_admin_definer`, move parked V2026_11_15_* into db/migration, then set SAHAJ_ADMIN_ENABLED=true, SAHAJ_ADMIN_JWT_SECRET, SAHAJ_ADMIN_TOTP_KEY (Base64 of 32 bytes) and SAHAJ_ADMIN_SEED_* in .env; add keys to .env.example.
- QA after activation: all 13 functions on the real DB, full sign-in/enrol/recovery/replay/lockout, refresh rotation and reuse, idle/absolute expiry, admin transaction binding (no tenant rows), append-only audit, seed, cross-realm isolation.
