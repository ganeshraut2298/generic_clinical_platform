# ADR-003 · Sahaj admin portal: platform admin identity, sign-in and cross-tenant access

Stories: ADM-1 (admin sign-in with email, password and authenticator app, dummy admin, admin shell), ADM-2 (organizations directory and clinic detail, read-only), ADM-3 (modules and plan management)
Mockup screens: Sahaj admin portal (screen map row "Sahaj admin portal (platform admin view)"): sign-in `pAdminLogin`; portal shell `pAdminV3` (`axHeadHTML`, `axside`, `axstrip`); Organizations directory `orgDirPage` (route `clinics`); clinic detail `aClinic` with tabs, Modules tab `aModules`, actions `ADM_KIND.plan` / `aActHTML` / `aago`; admin audit rows `aAuditRows`; Access and security `aSecurity` ("Two-step sign-in for all admins · Always")
Module: platform (new admin packages inside `com.sahaj.platform`); one read-only port implemented by ipd
Status: PROPOSED, revision 2 (5 Nov 2026). H1 and H2 decided by the human in DEC-012. Waiting for human `ADM · contract · APPROVED`
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Supersedes (phase 1 only): ADR-002 D5 paragraph "Sahaj's own staff (`platform_user`)" and ADR-002 D8 (platform admin switches into an organization on the clinic RLS path, "no `SECURITY DEFINER` wrapper"). Both were PROPOSED and never built. DEC-011 replaces them, as amended by DEC-012.
Related: `docs/decisions/DEC-010-ipd-switch-on-and-admin-console.md`, `docs/decisions/DEC-011-parallel-teams-and-admin-portal.md`, `docs/decisions/DEC-012-admin-portal-design-decisions.md`, `docs/architecture/adr/ADR-002-tenancy-identity-and-audit.md`, `docs/api/admin.yaml`, `docs/api/platform.yaml` (`/modules/*`, SAH-19)
Amended by: `docs/architecture/adr/ADR-016-admin-directory-and-modules.md` (ADM-2/3: function signatures, `POST /clinics/search` instead of `GET /clinics`, IPD filter withdrawn from H14, refused rows stored but not listed; DEC-029) and `docs/architecture/adr/ADR-008-dev-sample-admin.md` (DEC-016: dev-only endpoint that creates one sample admin; only in a `dev-tools` build; amends D5 and, in dev and test databases only, the D1 "no `INSERT` for the definer" rule) and `docs/architecture/adr/ADR-018-admin-bootstrap-registration-pin.md` (SAH-42, DEC-034: dev-only, PIN-locked registration of the first admin; only in a `dev-tools` build; same D1 widening in dev and test databases only)

**Revision 2 changes (DEC-012).** H1 = email + password + authenticator app (TOTP), as the mockup `pAdminLogin` draws, **replacing mobile OTP** for admins. H2 = new `NOLOGIN` role `sahaj_admin_definer`; the human adds it to `scripts/setup/create-database.mjs` and `scripts/db/init.sql` and re-runs the setup. The db-engineer assumes the role exists. Rewritten: D1, D2, D5, the auth part of D4, threats, Data needed, ADM-1. Removed: every admin use of `OtpService`, `otp_challenge.purpose`, admin mobile, `SAHAJ_ADMIN_SEED_MOBILE`. Unchanged: D3 (token separation and session numbers), D4 rules, D6 to D9, audit.

---

## Context

The Sahaj team needs to look after every clinic: find a clinic, see its plan and modules, and switch IPD on. Today no one can switch IPD on except the clinic owner through the temporary 30-day trial (DEC-010, option 2), which must be locked before real clinics use the product.

A platform admin is a new kind of user: a Sahaj employee who belongs to no tenant and can see every clinic. Everything else in the system assumes the opposite. Four facts in the current code shape this ADR:

1. **Every tenant table is `ENABLE` + `FORCE ROW LEVEL SECURITY`.** `platform.tenant`, `clinic_profile`, `membership`, `setup_progress`, `module_entitlement` and every `ipd.*` table answer only for `platform.current_tenant()`. `FORCE` binds the table owner (`sahaj_migrator`) too. The comment in `V2026_11_01_1016__platform_create_tenant_slug.sql` says it plainly: *no query against platform.tenant (not even one run by a SECURITY DEFINER function owned by sahaj_migrator) can see another tenant's row.* A cross-tenant read therefore needs a deliberate policy, not just a definer function.
2. **Clinic sign-in is mobile OTP + HS256 JWT (`JwtTokenService`, claims `sub`, `tid`, `role`) + opaque refresh cookie `sahaj_rt` on `/api/v1/platform/auth`.** `AuthService.verify` with mode `SIGNUP` *creates a new tenant and makes the caller its owner* when the mobile is unknown. The admin realm must not share that code path.
3. **One class binds session settings:** `TenantBindingTransactionManager` issues `set_config('app.tenant_id' | 'app.user_id', ?, true)` at transaction start from the security context. Nothing else may.
4. **Crypto already on the classpath** (checked in `backend/pom.xml`, Spring Boot 3.5.16, so Spring Security 6.5): `spring-security-crypto` with `BCryptPasswordEncoder` (already used for OTP hashes in `AuthBeansConfig`) and `Pbkdf2PasswordEncoder`. `Argon2PasswordEncoder` is present as a class but **needs BouncyCastle at runtime, which is not a dependency**. The JDK supplies `javax.crypto.Mac` (`HmacSHA1`, `HmacSHA256`), `javax.crypto.Cipher` (`AES/GCM/NoPadding`), `SecureRandom` and `MessageDigest.isEqual`. The frontend (`frontend/package.json`) has **no QR-code library**.

The mockup draws more than phase 1 can build (approvals by a second admin, support sessions with owner OTP, tickets, feature rollout, templates, menu management, bulk jobs, saved views, four admin roles). This ADR covers only what ADM-1..3 need and lists the rest as out of scope.

---

## Decisions

### D1 · Platform admins are a separate identity in `platform`, never a clinic membership

New table `platform.platform_admin`, identified by **work email** (unique, stored lower case), with a **password hash** and an **authenticator-app (TOTP) secret**. It has **no `tenant_id`**, no link to `platform.app_user` or `platform.membership`, and no row in any tenant. A person may be both a clinic user (by mobile) and a platform admin (by email); the two identities never meet in code or data.

- Not a nullable-tenant `app_user` and not a magic membership role: either would put a "belongs to everyone" row inside the tables that carry clinic credentials and roles (ADR-002 D5's argument, still valid).
- **`sahaj_app` has no privilege at all** on any `platform_admin*` table (no `SELECT`, no `INSERT`). The application reaches them only through the functions in D4. A bug or an injection in a clinic endpoint cannot list admins with a plain query.
- Never deleted: an admin who leaves is set `active = false`; past audit rows keep their name.
- Single role in phase 1: `PLATFORM_ADMIN` (all ADM-1..3 powers). The mockup's four roles (Super admin, Support, Billing, Read-only auditor) and the permission matrix are out of scope (H5).

### D2 · Sign-in: email + password, then authenticator code, on separate endpoints `/api/v1/admin/auth/*`

**Why separate endpoints (unchanged reasoning).** A flag on the clinic `/platform/auth/*` endpoints would share a handler with the tenant-creating `SIGNUP` path and the clinic cookie. The admin realm now shares **nothing** with clinic sign-in: not `OtpService`, not `otp_challenge`, not the cookie, not the token issuer.

| Option | Verdict |
| --- | --- |
| A · Mobile OTP (DEC-011) | **Rejected by the human (DEC-012 H1).** Single factor, open to SIM swap; not what the mockup draws. |
| **B · Work email + password + code from an authenticator app (chosen, DEC-012)** | Matches `pAdminLogin` ("Work email", "Password", "Code from authenticator app", "Two-step sign-in is always on for admins"). Two factors: something known and something held. No SMS, so no notification dependency. |

#### D2.1 · Password storage

- **`BCryptPasswordEncoder`, strength 12**, from `spring-security-crypto` (already a dependency). Argon2 would need BouncyCastle, a new library, so it is not chosen. PBKDF2 is also available but BCrypt is already the team's pattern.
- The admin hasher is its **own object** (`AdminPasswordHasher` wrapping `new BCryptPasswordEncoder(12)`), **not a second Spring bean of type `PasswordEncoder`**: `OtpService` injects the existing `otpPasswordEncoder` bean by type, and a second bean of that type would break it.
- BCrypt reads at most 72 bytes. The policy below keeps every allowed password under that. On sign-in, a password over 72 UTF-8 bytes is treated as wrong without calling the encoder (and the dummy hash is still checked, see D2.4), so a long input can neither crash the encoder nor be truncated into a match.
- **Password policy.** The mockup shows none (H15 asks the human to confirm). Architect default, NIST SP 800-63B style: **12 to 64 characters, at most 72 bytes in UTF-8, must not contain the part of the email before "@" (case-insensitive)**, no composition rules, no expiry. It is checked wherever a password is set: the dev seed at startup (D5) and the human's production provisioning procedure (H17). Phase 1 has no screen that sets a password.

#### D2.2 · Authenticator app (TOTP), RFC 6238, no new dependency

- **Algorithm:** HMAC-SHA1 (`javax.crypto.Mac.getInstance("HmacSHA1")`), 30-second step, 6 digits, dynamic truncation per RFC 4226 section 5.3. These are the defaults every authenticator app supports.
- **Secret:** 20 random bytes (160 bits) from `SecureRandom`, shown as RFC 4648 Base32. The JDK has no Base32 codec, so a small encoder and decoder in `com.sahaj.platform.application.admin` (about 40 lines, unit-tested) is used. No library.
- **Accepted window:** the current step plus one step either side (±30 seconds of clock drift). Comparison with `MessageDigest.isEqual` (constant time).
- **Replay:** the matched step number is stored as `totp_last_used_step`. A code whose step is not greater than the last used step is refused, so a code seen over a shoulder cannot be used twice. The database function checks this atomically (D4).
- **Unit tests:** the RFC 6238 Appendix B SHA-1 vectors (secret `12345678901234567890`, last 6 digits of each expected value) and the RFC 4226 Appendix D vectors.

#### D2.3 · TOTP secret protection: AES-256-GCM with a key the database never sees

- The secret is stored **encrypted** in `platform_admin.totp_secret_enc` (`bytea` = 12-byte random nonce ‖ ciphertext ‖ 16-byte tag), `Cipher.getInstance("AES/GCM/NoPadding")`, 128-bit tag, a fresh nonce for every encryption.
- **Associated data** = `"sahaj-admin-totp:" + admin id + ":" + key version`. A ciphertext copied onto another admin's row does not decrypt.
- **Key:** `SAHAJ_ADMIN_TOTP_KEY`, Base64 of exactly 32 random bytes, from the environment only, never in source control. `totp_key_version smallint` (starts at 1) is stored with each ciphertext so that a later rotation can re-encrypt row by row. Rotation tooling is out of scope (H19).
- **Startup checks** (only when the admin realm is enabled, see D3 "Negative"): the key is present, decodes to 32 bytes, and is not equal to the bytes of `SAHAJ_JWT_SECRET` or `SAHAJ_ADMIN_JWT_SECRET`. Otherwise the admin realm refuses to start.
- Decryption happens only in Java, only inside the verify and enrol services, and the plain secret is never logged, never returned after enrolment and never put in an exception message. A database dump or an SQL injection reads ciphertext only.

#### D2.4 · Sign-in steps

```
Step 1  POST /auth/login        {email, password}
        → 200 {next: TOTP | ENROL, preAuthToken, expiresInSeconds: 300}
Step 2a (next = ENROL, first sign-in only)
        POST /auth/totp/enrol   {preAuthToken}
        → 200 {secretKey, otpauthUri, accountName, issuer}
Step 2b POST /auth/totp/verify  {preAuthToken, code}  or  {preAuthToken, recoveryCode}
        → 200 AdminAuthResult {accessToken, expiresInSeconds: 600, admin, recoveryCodes?}
           + Set-Cookie sahaj_admin_rt
```

**Pre-auth token.** Opaque, 256 random bits, Base64url, returned in the JSON body (not a cookie, so it never rides along automatically). Stored only as a keyed hash (D3 "Hashing of opaque tokens") on a `platform_admin_preauth` row with the admin id, purpose `TOTP` or `ENROL`, expiry **5 minutes**, attempts and `consumed_at`. **Single use:** a successful verify consumes it. **5 wrong codes on one pre-auth token** consume it, so the person must start again from step 1. It is useless on any other endpoint: it is not a JWT, the admin JWT filter does not accept it, and it carries no role.

**Step 1 rules (no user enumeration).**
- Email is trimmed and lower-cased (`Locale.ROOT`) before every use.
- Unknown email, wrong password and switched-off admin all answer the **same** `401 admin-credentials-invalid`, title "Email or password is not right.". For an unknown or switched-off email the server still runs `BCrypt.matches` against a fixed dummy hash of strength 12, so response time does not reveal whether the email exists.
- Lockout and the per-email rate limit apply to **any** email string, known or not (D2.5), so `423` and `429` reveal nothing either.
- A pre-auth token is issued only after a correct password for an active admin. The `next` value (`TOTP` or `ENROL`) is therefore shown only to someone who already knows the password.

**Step 2 rules.**
- Wrong code: `401 totp-invalid` "That code is not right.". Unknown, expired or consumed pre-auth token: `401 preauth-expired` "Your sign-in took too long. Start again.". The UI shows the same combined message for both 401s (D2.7).
- A recovery code (D2.6) may be sent instead of a code, only for purpose `TOTP`.
- On success: the server creates the admin session exactly as in D3, sets `totp_last_used_step`, clears the email's failure counter, and returns the access token and cookie.

#### D2.5 · Lockout and rate limits

| Limit | Key | Rule | Answer |
| --- | --- | --- | --- |
| Failed sign-in steps | email (keyed hash, D3) | **5 failures within 15 minutes** (wrong password, wrong code, wrong recovery code, all counted together) **lock that email for 15 minutes**. Success resets the counter. Stored in the database (`platform_admin_auth_throttle`), so a restart does not reset it, and kept for every email typed, known or not. | `423 admin-sign-in-locked` with `retryAfterSeconds` |
| Login attempts | email (keyed hash), in memory | 10 `POST /auth/login` per 15 minutes per email, success or not (stops pre-auth farming once a password is known) | `429 rate-limit` |
| Wrong codes | pre-auth token | 5, then the token is consumed | `401 preauth-expired` |
| Public sign-in endpoints | client address | 20 requests per 15 minutes across `/auth/login`, `/auth/totp/enrol`, `/auth/totp/verify` | `429 rate-limit` |
| Refresh | client address | 30 per 15 minutes on `/auth/refresh` | `429 rate-limit` |

The per-address limits use new instances of the existing `SlidingWindowRateLimiter`, keyed like the enquiry limiter (the servlet remote address; `X-Forwarded-For` is ignored unless a trusted proxy is configured). A locked email is not an oracle: the lock row exists for any typed email. A lock lets an attacker block a known admin for 15 minutes at a time; the network allow-list (D2.8) limits who can try.

#### D2.6 · Enrolment and recovery codes

- **When:** an active admin whose `totp_enrolled_at` is null gets `next: ENROL` from step 1. Every admin enrols on the first sign-in; there is no way to skip it ("Two-step sign-in is always on for admins").
- **`POST /auth/totp/enrol`** (pre-auth purpose `ENROL`): creates a pending secret, encrypts it (D2.3) and stores it **on the pre-auth row**, not on the admin. It returns:
  - `secretKey`: Base32 in groups of 4 (for example `JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP`) for manual entry;
  - `otpauthUri`: `otpauth://totp/Sahaj%20admin:{email}?secret={BASE32}&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30`;
  - `accountName` (the email) and `issuer` ("Sahaj admin").
  Calling it again with the same pre-auth token returns the same pending secret. Response header `Cache-Control: no-store`. Never logged, never audited with the secret.
- **QR code: not in phase 1.** The frontend has no QR library, and drawing a QR code without one means writing a QR encoder (Reed-Solomon, masking) in the app, which is not worth it for a few admins. The screen shows the **key for manual entry** and the `otpauth://` link as selectable text and as a link (on a phone, tapping it opens the authenticator app). Adding a QR library is a frontend dependency decision for the human (H18).
- **Confirmation:** `POST /auth/totp/verify` with the first code. On success, in one transaction: the pending secret moves to the admin (`totp_secret_enc`, `totp_key_version`, `totp_enrolled_at`, `totp_last_used_step`), **10 recovery codes** are generated and stored hashed, the session starts, and the response carries `recoveryCodes` **once**. Audited `ADMIN_TOTP_ENROLLED` and `ADMIN_SIGNED_IN`.
- **Recovery codes:** 16 characters from the Base32 alphabet (80 random bits), shown as `XXXX-XXXX-XXXX-XXXX`. Stored only as a keyed hash (D3) of the normalised code (upper case, hyphens and spaces removed), with `used_at`. Each works once, in place of a code on `/auth/totp/verify`. Use is audited `ADMIN_RECOVERY_CODE_USED`. With 80 random bits a fast keyed hash is safe; a slow hash is not needed. Regenerating codes and self-service reset of the authenticator are out of scope. A lost phone with no codes left is reset by a human (H16).

#### D2.7 · Screen and text (mockup `pAdminLogin`)

The mockup draws **one card** with all three fields. ADM-1 keeps that card exactly; the two API calls happen behind one button.

| Step | What the admin sees | Source |
| --- | --- | --- |
| Card | "Sign in"; **Work email** (`autocomplete="username"`); **Password** (`type=password`, `autocomplete="current-password"`); **Code from authenticator app** (6 digits, numeric, `autocomplete="one-time-code"`); button **Sign in to admin console** (lock icon); hint "Two-step sign-in is always on for admins, and sign-in works only from approved networks." | mockup (the hint drops the mockup-only prefix "Mockup: the details are filled in.") |
| Client check | Code not 6 digits: toast "Enter the 6-digit code from your authenticator app" (before any call, unless the admin has not enrolled yet, which the client cannot know; see below) | mockup `admin-in` |
| Submit | `POST /auth/login`, then, if `next = TOTP`, at once `POST /auth/totp/verify` with the typed code | architect |
| 401 (either call) | "The email, password or code is not right." | *not in mockup, H13* |
| 423 | "Too many attempts. Try again in {m} min." | *not in mockup, H13* |
| 429 | "Too many attempts. Try again in a few minutes." | *not in mockup, H13* |
| 403 network | "Sign-in works only from approved networks." | mockup hint wording, H13 |
| Success | `/admin/clinics`, toast "Signed in as {name} · Platform admin" | mockup "Signed in as {name} · {role}", H5 |
| `next = ENROL` | The card changes to **Set up your authenticator app**: "Two-step sign-in is always on for admins. Add Sahaj admin to an authenticator app on your phone, then enter the 6-digit code it shows."; "Key for manual entry" with the grouped key and **Copy key**; "Or open on your phone" with the `otpauth://` link; **Code from authenticator app**; button **Confirm and sign in**; link **Start again** | *not in mockup, H13*; the code field label is the mockup's |
| After enrolment | **Save your recovery codes**: "Each code signs you in once if you lose your phone. They are shown only now."; the 10 codes; **Copy codes**; button **I have saved them** opens `/admin/clinics` | *not in mockup, H13* |
| Recovery | Link under the code field **Use a recovery code** switches the field to **Recovery code** (placeholder "XXXX-XXXX-XXXX-XXXX") | *not in mockup, H13* |
| Pre-auth expired | "Your sign-in took too long. Start again." and the card resets | *not in mockup, H13* |

Since the card asks for the code before the server says `ENROL`, a not-yet-enrolled admin may leave the code empty: the client skips its 6-digit check when the code field is empty and calls only `/auth/login`. If the server answers `TOTP` and the code was empty, the client shows the mockup toast "Enter the 6-digit code from your authenticator app" and keeps the pre-auth token (5 minutes) so the second press calls only `/auth/totp/verify`.

#### D2.8 · Network restriction (unchanged)

Mockup: "sign-in works only from approved networks", "Admin console only from office network or VPN", default on. `SAHAJ_ADMIN_ALLOWED_NETWORKS` (comma-separated CIDRs). When set, every `/api/v1/admin/**` request from another address answers `403 admin-network-denied`. When empty: allowed in the `dev` profile only (the dev server binds to 127.0.0.1); **refused in every other profile** (fail closed). Phase 1 is configuration, not the mockup's on/off switch (H6).

### D3 · Admin token: own key, own audience, no tenant, short life, own refresh cookie and session row (unchanged except token hashing)

**Access token (JWT, HS256):**

| Claim | Value |
| --- | --- |
| `iss` | `sahaj` |
| `aud` | `sahaj-admin` |
| `sub` | platform admin id |
| `sid` | platform admin session id |
| `role` | `PLATFORM_ADMIN` |
| `amr` | `["pwd","otp"]` (or `["pwd","rcode"]` after a recovery code); for the audit and later step-up checks |
| `iat`, `exp`, `jti` | lifetime **10 minutes** |
| `tid` | **absent, always.** An admin token that carries `tid` is rejected. |

- **Signed with a separate key** `SAHAJ_ADMIN_JWT_SECRET` (32+ characters, no default). Startup fails if it is missing, shorter than 32 characters, or equal to `SAHAJ_JWT_SECRET`. A clinic token cannot verify on the admin chain, and an admin token cannot verify on the clinic chain.
- The admin parser requires `aud = sahaj-admin`, `role = PLATFORM_ADMIN`, a `sid` and no `tid`; the clinic `JwtTokenService.parseAccessToken` additionally rejects any token with `aud = sahaj-admin`.
- An access token is issued **only** by `/auth/totp/verify` (after both factors) and `/auth/refresh`. `/auth/login` never returns one.

**Refresh:** opaque 256-bit token in cookie `sahaj_admin_rt`, `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/admin/auth` (the existing dev-only `refresh-cookie-secure: false` applies). **Rotated on every refresh; presenting an already-rotated token revokes the whole session** (theft detection). Never shares a table, cookie name or path with the clinic `sahaj_rt`.

**Hashing of opaque tokens (new in revision 2).** Refresh tokens, pre-auth tokens, recovery codes and the email throttle key are stored as **HMAC-SHA256** with a key derived in Java: `HMAC-SHA256(SAHAJ_ADMIN_JWT_SECRET, "sahaj-admin-token-hash-v1")`. Not plain SHA-256 as the clinic `OpaqueTokenHash` does. Reason: someone who can run SQL as `sahaj_app` (an injection) could otherwise write a session row with the hash of a refresh token they chose and then call `/auth/refresh`. With a keyed hash they cannot compute the stored value without the key, which lives only in the Java process.

**Session row and per-request check.** Every admin request (not only refresh) calls `platform.admin_session_touch(sid, sub)` inside the admin JWT filter. It returns true only when the admin is active, the session is not revoked, the last activity is within the idle limit and the absolute limit has not passed, and it then moves `last_seen_at`. False means the request is anonymous: `401 admin-session-ended`.

| Property | Value | Source |
| --- | --- | --- |
| Access token life | 10 min | architect (unchanged) |
| Idle sign-out | 30 min of no API activity | mockup `S.adm.pol.timeout = 30` (unchanged) |
| Absolute session life | 12 h | architect, H7 (unchanged) |
| Pre-auth token life | 5 min, single use, 5 wrong codes | architect, H7 (new) |
| Admin switched off | next request refused (per-request check, no cache) | architect |
| Sign-out | revokes this session, clears the cookie | mockup "Sign out" |

Not cached (the clinic chain caches membership for 60 s). There are a handful of admins, so one indexed update per request is cheap, and an admin who is switched off must lose access at once.

### D4 · Cross-tenant access only through narrow `SECURITY DEFINER` functions owned by `sahaj_admin_definer`

The admin request path never binds `app.tenant_id`. So every ordinary query an admin request makes against a tenant table returns nothing (ADR-002's fail-closed default keeps working). The only way across tenants is a fixed list of functions.

**Function owner: `sahaj_admin_definer` (decided, DEC-012 H2).** Because of `FORCE ROW LEVEL SECURITY`, a migrator-owned definer function would see no tenant rows. The human creates the role in `scripts/setup/create-database.mjs` and `scripts/db/init.sql`: `NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT`, `GRANT sahaj_admin_definer TO sahaj_migrator` (so Flyway can assign ownership), `USAGE` on schemas `platform` and `ipd`. The migrations assume the role exists and fail fast with a clear message if it does not (`DO $$ … IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sahaj_admin_definer') THEN RAISE EXCEPTION …`). The role can do nothing except what its functions do: it cannot log in, owns no table, and gets column-level grants and policies `TO sahaj_admin_definer` only. The migrator stays fully bound by `FORCE`.

**Rules every admin function follows (db-engineer, reviewers):**

1. `SECURITY DEFINER`, `SET search_path = pg_catalog, pg_temp`, every object schema-qualified. No dynamic SQL (`EXECUTE`), no string-built `ORDER BY`; sorting and filtering by `CASE` and `($n IS NULL OR …)` on bound parameters only. `ILIKE` input has `%`, `_` and `\` escaped.
2. `REVOKE EXECUTE … FROM PUBLIC` (functions are executable by `PUBLIC` by default), then `GRANT EXECUTE … TO sahaj_app`.
3. **Guard first.** Every data function starts with `platform.admin_require_session()`, which raises SQLSTATE `42501` unless `app.admin_id` and `app.admin_session_id` are set, name an active admin and a live session (not revoked, not idle, not past absolute), **and `platform.current_tenant()` is NULL** (a clinic-bound transaction can never call an admin function). The actor id written to the audit comes from these settings, never from a parameter. The pre-session auth functions (marked "pre-auth" below) cannot use the guard; each also refuses when `platform.current_tenant()` is not NULL.
4. **Audit in the same statement.** Every data function writes its `platform_admin_audit` row itself, in the same transaction as the read or write. A refused change (for example IPD off while patients are admitted, D6) writes a `REFUSED` row.
5. **Column-level grants** to `sahaj_admin_definer`, so even a wrong function body cannot read what it should not: for example `setup_progress (tenant_id, current_step, completed_at)` but never `answers_json`; `ipd.admission (tenant_id, status)` but never `reason`; `app_user (id, full_name, mobile)`.
6. Policies are added **only `TO sahaj_admin_definer`**. No existing policy for `sahaj_app` is changed or widened. A clinic session sees exactly what it sees today.
7. **Secrets in and out.** No function receives a plain password, plain TOTP secret, plain token or plain recovery code. Passwords arrive never (BCrypt is checked in Java); secrets arrive encrypted; tokens and codes arrive as keyed hashes (D3).

**Function list (complete for ADM-1..3; anything else needs a new ADR entry):**

| Function | Story | Reads / writes | Audit row |
| --- | --- | --- | --- |
| `platform.admin_require_session()` returns uuid | all | admin, session | none (guard) |
| `platform.admin_login_lookup(p_email)` returns `(admin_id, password_hash, totp_enrolled)` · pre-auth | ADM-1 | the **active** admin with that email, or no row | none |
| `platform.admin_throttle_status(p_key_hash)` returns `locked_until` · pre-auth | ADM-1 | throttle row | none |
| `platform.admin_auth_failed(p_key_hash, p_admin_id, p_preauth_hash, p_step, p_ip, p_request_id)` returns `(locked_until, preauth_consumed)` · pre-auth | ADM-1 | throttle +1 (lock at 5 within 15 min); pre-auth attempts +1 (consumed at 5) | `AUTH ADMIN_SIGN_IN_FAILED` with step `PASSWORD`, `TOTP` or `RECOVERY_CODE`; admin id only when the email belongs to an admin; **never the typed email** |
| `platform.admin_preauth_create(p_admin_id, p_key_hash, p_token_hash, p_purpose, p_ip)` returns `expires_at` · pre-auth | ADM-1 | new pre-auth row (5 min) | none |
| `platform.admin_preauth_get(p_token_hash)` returns `(preauth_id, admin_id, purpose, totp_secret_enc, totp_key_version, pending_secret_enc, pending_key_version, totp_last_used_step)` · pre-auth | ADM-1 | live (not expired, not consumed) pre-auth row and its admin | none |
| `platform.admin_totp_set_pending(p_token_hash, p_secret_enc, p_key_version)` · pre-auth | ADM-1 | pending secret on an `ENROL` pre-auth row, only if none yet | none |
| `platform.admin_sign_in(p_token_hash, p_method, p_totp_step, p_recovery_hash, p_refresh_hash, p_ip, p_request_id)` returns `(session_id, admin_id, full_name)` · pre-auth | ADM-1 | consumes the pre-auth row (single use); for `TOTP` refuses unless `p_totp_step > totp_last_used_step` then sets it; for `RECOVERY_CODE` marks the unused code `used_at` or refuses; creates the session; sets `last_sign_in_at`; clears the throttle | `AUTH ADMIN_SIGNED_IN` (method), and `ADMIN_RECOVERY_CODE_USED` |
| `platform.admin_complete_enrolment(p_token_hash, p_totp_step, p_recovery_hashes text[10], p_refresh_hash, p_ip, p_request_id)` returns `(session_id, admin_id, full_name)` · pre-auth | ADM-1 | consumes an `ENROL` pre-auth row; moves the pending secret to the admin, sets `totp_enrolled_at` and `totp_last_used_step`; inserts the 10 recovery-code hashes; creates the session; clears the throttle | `AUTH ADMIN_TOTP_ENROLLED`, `AUTH ADMIN_SIGNED_IN` |
| `platform.admin_session_touch(p_session, p_admin)` returns boolean | ADM-1 | session `last_seen_at` | none |
| `platform.admin_session_rotate(p_old_hash, p_new_hash)` | ADM-1 | session; reuse of an old hash revokes the session | `AUTH ADMIN_SESSION_REVOKED` on reuse |
| `platform.admin_sign_out()` | ADM-1 | revokes the bound session | `AUTH ADMIN_SIGNED_OUT` |
| `platform.admin_me()` | ADM-1 | admin id and name | none |
| `platform.admin_list_clinics(…filters…)` returns jsonb | ADM-2 | tenant, clinic_profile, membership + app_user (owner), module_entitlement | `ACCESS CLINICS_LISTED` (filters and result count; **not** the search text) |
| `platform.admin_get_clinic(p_tenant)` returns jsonb | ADM-2 | same, one tenant | `ACCESS CLINIC_VIEWED` |
| `ipd.admin_admitted_count(p_tenant)` returns integer | ADM-2, ADM-3 | `ipd.admission (tenant_id, status)` | none (count only; the calling platform function audits) |
| `platform.admin_lock_modules(p_tenant)` | ADM-3 | inserts default `module_entitlement` row if absent, `SELECT … FOR UPDATE` | none |
| `platform.admin_set_modules(p_tenant, p_ipd_state, p_ipd_trial_ends_at, p_hra_on, p_bed_limit, p_summary, p_refusal)` | ADM-3 | `module_entitlement` | `ACTION MODULES_CHANGED` (APPLIED, or REFUSED with code) with before/after |
| `platform.admin_set_plan(p_tenant, p_plan, p_reason, p_ticket)` | ADM-3 | `tenant.plan` (column grant `UPDATE (plan, updated_at, version)`) | `ACTION PLAN_CHANGED` with before/after |
| `platform.admin_list_clinic_activity(p_tenant, p_limit, p_offset)` returns jsonb | ADM-3 | `platform_admin_audit` kind `ACTION` for that tenant | `ACCESS CLINIC_ACTIVITY_VIEWED` |

Removed in revision 2: `admin_sign_in(p_mobile, …)` and `admin_record_auth_event(p_event, p_mobile_last4, …)`.

**Where TOTP is checked.** In Java: `admin_preauth_get` returns the ciphertext, Java decrypts with `SAHAJ_ADMIN_TOTP_KEY`, computes the codes for steps t-1, t and t+1, and on a match passes the matched step number to `admin_sign_in` / `admin_complete_enrolment`, which enforce replay atomically. The database never sees the key or the plain secret.

**Java side.** `TenantBindingTransactionManager` stays the only binder. It gains one branch: when the principal is an `AdminPrincipal`, bind `app.admin_id` and `app.admin_session_id` and **never** `app.tenant_id`. The public auth endpoints bind nothing. A request is either clinic or admin (separate chains), so the branches cannot mix. Admin repositories call the functions with `JdbcClient` bound parameters. Business rules (password policy, TOTP, trial length, effective state, the admitted-patients refusal) stay in Java; the functions are narrow, checked primitives.

**Honest limit of this design (H3, still recommended).** `sahaj_app` can set any `app.*` setting and call every admin function, so the guard is the same trust class as RLS itself: it stops bugs and wrong bindings, not an SQL injection that runs arbitrary statements as `sahaj_app`. With revision 2 such an injection **can**: read admin BCrypt hashes through `admin_login_lookup` (slow to crack at strength 12), read TOTP ciphertext (useless without the Java-held key), create pre-auth and session rows, and bind `app.admin_*` to a session row it created to call the data functions directly in SQL. It **cannot** obtain an admin access token, because refresh and pre-auth lookups use keyed hashes (D3) and tokens are signed with a key the database never sees. The real fix is still a separate login role for the admin pool (`sahaj_admin_app`, the only role with `EXECUTE` on admin functions, used only by the `/api/v1/admin/**` chain). The contract does not change either way.

### D5 · Dummy admin: dev profile only, from environment values, never from a migration or a committed value

**Seeding.**
- A **Flyway `afterMigrate` callback** `afterMigrate__dev_seed_platform_admin.sql` in a **separate location `classpath:db/dev-seed`**, added to `spring.flyway.locations` **only in `application-dev.yml`**. It runs as `sahaj_migrator` (owner of `platform_admin`), so no seeding function is ever exposed to `sahaj_app`.
- Environment values, set outside source control (the developer's own `.env`, never committed): `SAHAJ_ADMIN_SEED_EMAIL`, `SAHAJ_ADMIN_SEED_PASSWORD`, `SAHAJ_ADMIN_SEED_NAME` (default `Dev admin`). No defaults for email and password.
- **The password is hashed in Java, never in SQL.** A `@Profile("dev")` `FlywayConfigurationCustomizer` reads the two values, checks the email format and the password policy (D2.1), hashes the password with `AdminPasswordHasher`, and passes **only** the email, name and BCrypt hash as Flyway placeholders `sahaj_admin_seed_email`, `sahaj_admin_seed_name`, `sahaj_admin_seed_password_hash`. The plain password never reaches the database, a placeholder or a log line. If either variable is empty, the placeholders are empty and the callback inserts nothing. If the password fails the policy, startup fails with "SAHAJ_ADMIN_SEED_PASSWORD does not meet the admin password policy (12 to 64 characters, not containing the email name)." (the value itself is never printed).
- Idempotent: `INSERT … ON CONFLICT (email) DO UPDATE SET active = true, full_name = EXCLUDED.full_name, password_hash = EXCLUDED.password_hash`. The TOTP enrolment is **kept** across restarts (so a developer who enrolled a real app keeps it), and the email's throttle row is deleted, so a developer who locked themselves out can restart.
- **Startup guard** `AdminSeedGuard` (same rules as `OtpDevBypassGuard.verify`): if `SAHAJ_ADMIN_SEED_EMAIL` or `SAHAJ_ADMIN_SEED_PASSWORD` is non-empty and the active profiles are not `dev` (optionally with `test`), the application refuses to start.
- Not in the `test` profile: integration tests insert their own admins as `sahaj_migrator`, with a known secret encrypted under the test key.

**TOTP in dev: an accept-any-code bypass, chosen over a fixed dev secret.**

| Option | Verdict |
| --- | --- |
| Fixed dev secret (`SAHAJ_ADMIN_SEED_TOTP_SECRET`, seeded as already enrolled) | Exercises the real TOTP code, but needs a phone with an authenticator app on every laptop, and creates a long-lived shared second-factor credential that works anywhere the matching row exists. If the seed row or the secret ever reached a shared environment, it would be a working second factor. **Not chosen.** |
| **Accept-any-code bypass `sahaj.admin.totp.dev-bypass` (chosen)** | No phone needed. Leaks nothing: there is no secret to copy, and the guard refuses to start outside `dev`. Same pattern and same guard rules the team already trusts for the clinic OTP (`sahaj.otp.dev-bypass`, `OtpDevBypassGuard`). |

Rules of the bypass:
1. Property `sahaj.admin.totp.dev-bypass`, default `false`, set `true` only in `application-dev.yml`.
2. **`AdminTotpDevBypassGuard`** refuses to start when the property is true and the profiles are not exactly `dev` or `dev,test`: the same `verify` logic as `OtpDevBypassGuard` (the java-developer may extract a shared helper).
3. **Bound to loopback twice:** the dev server binds to `127.0.0.1` (`application-dev.yml`, already), **and** the bypass applies only when the request's servlet remote address `isLoopbackAddress()` (`X-Forwarded-For` ignored). Any other address goes through real TOTP.
4. It replaces **only the 6-digit code check** on `/auth/totp/verify`, for both enrolment confirmation and normal sign-in: any 6 digits are accepted for a live pre-auth token. The **password is always checked for real**. Recovery codes are always checked for real. The replay check is skipped under the bypass.
5. The seeded admin starts **not enrolled**, so the developer sees the enrolment and recovery-code screens on the first sign-in (any 6 digits confirm). The secret and codes are stored as usual.
6. A `WARN` at startup: "Admin authenticator dev bypass is on. Any 6-digit code is accepted from this machine." Each sign-in under the bypass writes `ADMIN_SIGNED_IN` with `summary` "dev bypass".
7. The real TOTP path is still covered: unit tests with the RFC vectors, an integration test with the bypass off, and a developer can set the property to `false` and enrol a real app.

**Signing in locally (for the human):**
1. In your own `.env` (not committed): `SAHAJ_ADMIN_JWT_SECRET=<32+ random characters, different from SAHAJ_JWT_SECRET>`, `SAHAJ_ADMIN_TOTP_KEY=<Base64 of 32 random bytes>`, `SAHAJ_ADMIN_SEED_EMAIL=dev.admin@example.test`, `SAHAJ_ADMIN_SEED_PASSWORD=<12 to 64 characters>`.
2. Start the backend with profile `dev` and the frontend.
3. Open `http://localhost:4200/admin/sign-in`, enter the email and password, leave the code empty, press **Sign in to admin console**.
4. On "Set up your authenticator app", type any 6 digits, press **Confirm and sign in**, then **I have saved them**. Next time, type any 6 digits in the code field on the first card.

Production admins: created by a human with a one-off SQL run as `sahaj_migrator`, with a BCrypt hash produced offline (H17). An "Invite admin" screen is out of scope.

**Second dev path (ADR-008, DEC-016).** A dev-only endpoint `POST /api/v1/admin/dev/sample-admin` creates one enrolled sample admin (`sample-admin@example.test`) and returns its password and TOTP secret once. It exists only in a jar built with Maven profile `dev-tools`, and at runtime only with profile `dev`, `sahaj.admin.dev-sample-enabled=true`, the admin realm on, no proxy configuration and a loopback caller; otherwise, and whenever any admin exists, it answers 404. It reuses this section's validation and hashing (extracted to `AdminProvisioning`). The seed above is unchanged.

### D6 · Modules and plan changes: rules, concurrency, effect on the clinic (unchanged)

**IPD state** (`off | trial | on | readonly`, same values as SAH-19):

- Any state may be chosen from any other, as the mockup's segmented control allows, with one refusal: **to `off` or `readonly` while any patient is admitted** gives `409 ipd-patients-admitted`, detail "{n} patients are admitted in {clinic}. IPD stays {current state} until they are discharged." (mockup `amod` / `setIpd`). The refusal is audited as `REFUSED`.
- Choosing `trial` sets `ipd_trial_ends_at = now + 30 days` (the mockup label "Trial · 30 days"; same length as SAH-19). Choosing `trial` again on an expired trial (effective state `readonly`) starts a new 30 days. Choosing `on`, `off` or `readonly` clears `ipd_trial_ends_at`. A custom trial end date is not in the mockup (H9).
- The effective state shown everywhere uses the existing rule: an expired trial reads as `readonly` (`ModuleEntitlements.effectiveIpdState`).
- `ipd_requested_at` is kept as history.

**Admitted count, across the module boundary.** `platform.api` gains a read-only port `IpdOccupancyPort { int admittedNow(UUID tenantId); }`, implemented in `com.sahaj.ipd` with `ipd.admin_admitted_count`. Dependency direction ipd → platform.api, as today, so there is no cycle. platform never reads `ipd.*` tables, and ipd owns the function in its own schema.

**Concurrency (admit versus switch-off).** One admin transaction: `admin_lock_modules` (row lock) → `admittedNow` → `admin_set_modules`. **Request to Team B (java-developer):** the ipd admit transaction must read the entitlement `FOR SHARE` through `ModuleEntitlementPort`. Then an admission in progress makes the switch-off wait and count it, and an admission that starts after the lock sees the new state. Without that change a narrow race remains, where a patient is admitted in the same second IPD is switched off. That is accepted until the change lands, because the data is kept and can still be read in `readonly`.

**HRA** on/off: no guard (mockup: "Add-on · the owner can also switch it"; the owner switch from SAH-19 stays).

**Bed limit:** 5 to 300 in steps of 5 (mockup stepper ±5, database check 5..300). Lowering it below the beds that already exist is allowed: existing beds stay and new beds are refused by the existing `bed-limit` rule (mockup has no guard; H10).

**Plan:** `FREE | PRO` (SAH-19's `ModulesView.plan`). Requires a reason of at least 10 characters (mockup: "Write a reason of at least 10 characters. It goes into the audit log.") and an optional support ticket. Takes effect immediately; there is no billing in phase 1. The mockup's plans (Pro monthly, Pro yearly, Custom with price, Grace period, Suspended), "starts at the next renewal" and the second-admin approval are out of scope (H4).

**Effect on the clinic.** Entitlements are read per request (`ModulesService`, `ModuleEntitlementPort`), so the clinic's menus, `/ipd/*` access (`403 module-off`, `409 ipd-readonly`) and bed limit change on its next call. No event, cache or notification is needed.

**Ending the owner trial as the way to turn IPD on (DEC-010).** With ADM-3: `sahaj.modules.owner-ipd-trial` becomes `false` in `application-dev.yml` too (it is already `false` by default). `POST /api/v1/platform/modules/ipd-trial` then answers `404` in every profile, `ipdTrialAvailable` is `false`, and the owner sees only "Request IPD". The endpoint is marked deprecated in `platform.yaml`. Removing the endpoint and the flag is a later clean-up, after the test profile no longer relies on it (H8).

### D7 · Admin audit: one append-only table, with no privilege for the app

`platform.platform_admin_audit`, separate from the tenant-scoped `platform.audit_event`:

- Not tenant-owned: it has `target_tenant_id` (nullable, FK `platform.tenant`), deliberately not named `tenant_id`, because no RLS policy governs it (the same convention as `platform.tenant_slug.owner_tenant`).
- **`sahaj_app`: no grants at all.** `sahaj_admin_definer`: `INSERT` and `SELECT` only. Owner `sahaj_migrator`.
- Trigger `BEFORE UPDATE OR DELETE` (row) and `BEFORE TRUNCATE` (statement) → `platform.deny_audit_mutation()`, so updates are refused for every role including the owner. Only a superuser can get past it, and no superuser credential exists on the request path.
- `kind` ∈ `AUTH | ACCESS | ACTION`. The future Audit log screen (mockup `aAudit`, "Entries cannot be edited or deleted") shows `ACTION` rows. `ACCESS` rows answer "who looked at which clinic".
- `AUTH` actions (revision 2): `ADMIN_SIGNED_IN` (method `TOTP`, `RECOVERY_CODE` or `ENROL`), `ADMIN_SIGN_IN_FAILED` (step `PASSWORD`, `TOTP` or `RECOVERY_CODE`; admin id when the email belongs to an admin, otherwise none), `ADMIN_TOTP_ENROLLED`, `ADMIN_RECOVERY_CODE_USED`, `ADMIN_SIGNED_OUT`, `ADMIN_SESSION_REVOKED`. Each with time, address and request id.
- No patient data can reach it: the functions take no patient input. **No email typed at sign-in, no password, no code, no secret, no token, no recovery code** is ever stored. Search text is never stored (it may contain owner mobile digits).
- Retention: 7 years (mockup: "Kept for 7 years"). Volume is small, so there is no partitioning now and no delete path at all in phase 1.
- Whether the clinic's own Activity log should also show "Sahaj changed IPD to On" is not in the mockup (H11). Phase 1 writes the admin audit only.

### D8 · Web and frontend boundaries

- **Second `SecurityFilterChain`**, `@Order(1)`, `securityMatcher("/api/v1/admin/**")`: stateless, CSRF off (bearer plus a `SameSite=Strict` cookie scoped to the auth path), network filter (D2.8), rate limiters (D2.5), `AdminJwtAuthenticationFilter`. Public: `POST /auth/login`, `/auth/totp/enrol`, `/auth/totp/verify`, `/auth/refresh`. Everything else requires `hasRole('PLATFORM_ADMIN')`. The clinic chain is unchanged, and `/api/v1/admin/**` never reaches it.
- Clinic roles never satisfy an admin route (`ROLE_OWNER` ≠ `ROLE_PLATFORM_ADMIN`), and an admin token never authenticates on the clinic chain (different key).
- Controllers: `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` on every admin handler, plus a convention test that fails the build if an `/api/v1/admin/**` handler lacks it (or `@PublicEndpoint` for the four public auth endpoints).
- Responses of `/auth/login`, `/auth/totp/enrol` and `/auth/totp/verify` carry `Cache-Control: no-store`. Request logging must never include the bodies of these endpoints.
- Packages: `com.sahaj.platform.web.admin`, `.application.admin`, `.infrastructure.admin` (Team A, DEC-011). Nothing outside `platform` imports them.
- **Angular:** lazy feature at `/admin` with routes `/admin/sign-in`, `/admin/clinics`, `/admin/clinics/:id` (mockup host `admin.<domain>`, paths `/sign-in`, `/clinics`). Its own `AdminAuthService` (access token in memory only, restored on load through `/admin/auth/refresh`; the pre-auth token, enrolment key and recovery codes in component memory only, never in storage or the URL), its own HTTP interceptor that attaches the admin token **only** to `/api/v1/admin/**`. The clinic interceptor must never attach the clinic token to `/api/v1/admin/**`. The clinic sign-in header button "Admin portal" (`admin-login`) links to `/admin/sign-in`. No new frontend dependency (no QR library, H18).
- Same origin in phase 1. A separate origin (`admin.<domain>`, as the mockup draws) would stop an XSS in the clinic app from calling `/api/v1/admin/auth/refresh` in a browser where an admin is signed in. That is a hosting decision (H12).

### D9 · Explicitly out of scope (phase 1, ADM-1..3)

Overview dashboard and KPIs; Approvals and every "needs a second admin" rule; support sessions (owner OTP, needs notifications; screen map says Later); reset owner sign-in; extend free period; refund; WhatsApp allowance; suspend and reactivate; data export to owner; Settings for this clinic (feature overrides); Usage tab (visits, WhatsApp, sign-ins); Support tab and tickets; System health; the Audit log page (data is recorded, page later); Plans and billing configuration (prices, free-year days, GST); Feature rollout; Templates and master data / Global masters; Messaging and integrations; Access and security (admin list, roles, permission matrix, invite admin, timeout stepper, network switch); Data and compliance; Announcements; Bulk jobs; Growth and revenue; Clinics across India; Organization Management (menus, submenus, routes, menu visibility, clinical masters, org settings: the org-admin persona); org switcher, Ctrl K palette, notifications bell, help panel; saved views, pins, bulk selection, export list; "10 lakh" simulation toggle; any notification; **forgot password and password reset, change password, regenerate recovery codes, reset authenticator by self-service, QR code at enrolment, TOTP key rotation tooling, "remember this device"** (H15 to H19).

---

## Threats and mitigations

| Threat | Mitigations | Residual |
| --- | --- | --- |
| **Admin account takeover** (phishing, password reuse, guessing) | Two factors always (password + TOTP, no opt-out); BCrypt 12; 5 failures per email lock 15 min; 10 logins per email per 15 min; 20 sign-in requests per address per 15 min; network allow-list, fail closed outside dev; pre-auth token 5 min, single use, 5 codes; TOTP replay refused; 10-minute tokens; 30-minute idle and 12-hour absolute session; refresh rotation with reuse detection; `HttpOnly`/`Strict` cookie scoped to `/api/v1/admin/auth`; every sign-in step audited with address; switching an admin off takes effect on the next request | Real-time phishing relay of password and code (TOTP is not phishing-resistant; passkeys would be, later). A known email can be locked for 15 minutes at a time (limited by the network allow-list). |
| **User enumeration** | Same `401 admin-credentials-invalid` for unknown email, wrong password and switched-off admin; dummy BCrypt for unknown emails (same timing); lockout and per-email limit apply to every typed email; `next` only after a correct password; UI shows one combined message | none known |
| **Stolen database dump** | Passwords BCrypt 12; TOTP secrets AES-256-GCM with a key outside the database, bound to the admin id; tokens, recovery codes and throttle keys as HMAC with a key outside the database | an attacker with both the dump and the environment has everything |
| **Lost phone** | 10 single-use recovery codes, shown once, stored hashed; human reset (H16) | admin who lost phone and codes waits for a human |
| **Cross-realm token confusion** | Separate signing key (startup refuses equal keys); `aud` check both ways; no `tid` in admin tokens; pre-auth token is not a JWT and is refused by the JWT filter; separate cookie name, path and session table; no shared code with clinic OTP sign-in | none known |
| **Cross-tenant leakage into clinic sessions** | No clinic policy changes; new policies only `TO sahaj_admin_definer`; definer role cannot log in; admin functions refuse when `app.tenant_id` is bound; admin request path never binds a tenant, so plain queries return nothing | none |
| **Over-broad admin reads** (patient data) | Column-level grants (no `answers_json`, no admission `reason`, no patient tables at all); functions return counts and clinic-level fields only ("Platform view: counts only"); owner mobile returned as the last 5 digits only (mockup detail header) | none |
| **SQL injection in clinic code reaching admin functions** | No dynamic SQL in functions or repositories; guard requires a live admin session id; keyed token hashes stop an injection from obtaining an admin access token | an injection can read BCrypt hashes and TOTP ciphertext and call data functions in SQL with a session row it created. Full fix: separate `sahaj_admin_app` login role (H3). |
| **Audit tampering or forgery** | `sahaj_app` has no grant on the table; definer has `INSERT`/`SELECT` only; trigger blocks `UPDATE`/`DELETE`/`TRUNCATE` for every role including the owner; audit written inside the same function as the change; pre-auth functions write only `AUTH` rows | a superuser can disable triggers; no superuser on the request path |
| **Function hijack via `search_path`** | `SET search_path = pg_catalog, pg_temp`; schema-qualified names; `EXECUTE` revoked from `PUBLIC` | none |
| **Dev seed or bypass leaking to production** | Seed only from a dev-only Flyway location and a `@Profile("dev")` customizer; `AdminSeedGuard` refuses seed values outside `dev`; `AdminTotpDevBypassGuard` refuses the bypass outside `dev`; bypass only for loopback remote addresses on a loopback-bound server; password never bypassed; no seed secret exists | none known |
| **Secrets in logs** | No function receives plain secrets; no request-body logging on auth endpoints; plain TOTP secret only in memory during enrol/verify; seed password never printed | none known |
| **XSS in the shared Angular app driving admin APIs** | Admin token, pre-auth token and recovery codes in memory only; admin cookie `HttpOnly`, `Strict`, path-scoped; CSP from the existing frontend rules | same origin as the clinic app (H12) |
| **Race between admit and switch-off** | Row lock in the admin transaction; ipd admit reads the entitlement `FOR SHARE` (request to Team B) | until Team B's change lands, a one-second race; data is never lost |

---

## Consequences

**Positive**
- Matches the mockup sign-in. Two factors from day one, with no SMS dependency.
- Clinic RLS is untouched; every new cross-tenant capability is a named, audited function that a reviewer can read in one migration.
- The migrator stays bound by `FORCE` (role `sahaj_admin_definer`).
- Admin and clinic realms share no sign-in code at all. Keys, tokens, cookies, sessions and audit are all separate.
- No new backend or frontend library: BCrypt from Spring Security, HMAC-SHA1/SHA256 and AES-GCM from the JDK.
- IPD can be switched on the real product way, so the DEC-010 temporary owner trial can be locked.

**Negative / accepted costs**
- More ADM-1 work than mobile OTP: password hashing, TOTP, encryption, enrolment, recovery codes, five new screens' worth of states.
- Three admin secrets to manage (`SAHAJ_ADMIN_JWT_SECRET`, `SAHAJ_ADMIN_TOTP_KEY`, and per-developer seed values). **Losing `SAHAJ_ADMIN_TOTP_KEY` forces every admin to re-enrol** (a human clears the TOTP columns). Changing `SAHAJ_ADMIN_JWT_SECRET` ends every admin session and invalidates unused recovery codes (they are keyed hashes), so it must be treated as a rotation event (H19).
- Startup checks: the admin chain and its services are created only when `SAHAJ_ADMIN_JWT_SECRET` **and** `SAHAJ_ADMIN_TOTP_KEY` are set; when either is missing every `/api/v1/admin/**` request answers `503 admin-unavailable`, and the app still starts for clinic work. A key that is set but invalid fails startup.
- No password reset or change in phase 1; a human with migrator access handles it (H15).
- One `UPDATE` per admin request (session touch). Negligible at this scale.
- Business rules for modules live in two places: the owner path (SAH-19 `ModulesService`) and the admin path. Mitigation: the admin service reuses `ModuleEntitlements` (trial length, effective state) rather than copying it.

---

## Data needed (for the db-engineer)

All in schema `platform` unless stated. No new tenant-owned table. No cross-module foreign key (the ipd function reads only ipd). The role `sahaj_admin_definer` exists (DEC-012); the first admin migration checks for it and stops with a clear message if it is missing.

### Tables

| Entity | Fields | Notes |
| --- | --- | --- |
| `platform.platform_admin` | `id uuid pk default gen_random_uuid()`; `email text not null unique check (email = lower(btrim(email)) and length(email) between 3 and 254 and email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$')`; `full_name text not null check (1..120 chars, trimmed)`; `password_hash text not null check (password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$')`; `password_changed_at timestamptz not null default now()`; `totp_secret_enc bytea null check (octet_length between 48 and 64)` (12 nonce + 20 secret + 16 tag = 48); `totp_key_version smallint null check (>= 1)`; `totp_enrolled_at timestamptz null`; `totp_last_used_step bigint null`; check: the three `totp_secret_enc`, `totp_key_version`, `totp_enrolled_at` are all null or all set; `active boolean not null default true`; `last_sign_in_at timestamptz`; `created_at`, `created_by uuid null`, `updated_at`, `updated_by`, `version bigint` | No `tenant_id`, no RLS (not tenant data; privileges are the control). `REVOKE ALL FROM sahaj_app, PUBLIC`. `sahaj_admin_definer`: `SELECT (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at, totp_last_used_step, active)`, `UPDATE (totp_secret_enc, totp_key_version, totp_enrolled_at, totp_last_used_step, last_sign_in_at, updated_at, version)`. **No `UPDATE (password_hash)` and no `INSERT` for the definer**: passwords and admins are set only by the migrator (dev seed, human provisioning). Never deleted. |
| `platform.platform_admin_recovery_code` | `id uuid pk`; `admin_id uuid not null fk platform_admin`; `code_hash char(64) not null unique` (HMAC-SHA256 hex); `created_at timestamptz not null default now()`; `used_at timestamptz null` | No grants to `sahaj_app`; definer `SELECT, INSERT, UPDATE (used_at), DELETE` (delete only to replace the set at re-enrolment). Index `(admin_id) where used_at is null`. Kept while the admin exists. |
| `platform.platform_admin_preauth` | `id uuid pk`; `admin_id uuid not null fk platform_admin`; `token_hash char(64) not null unique`; `throttle_key char(64) not null`; `purpose text not null check in ('TOTP','ENROL')`; `pending_secret_enc bytea null`; `pending_key_version smallint null`; `attempts smallint not null default 0 check (0..5)`; `created_at timestamptz not null default now()`; `expires_at timestamptz not null`; `consumed_at timestamptz null`; `ip inet null` | No grants to `sahaj_app`; definer `SELECT, INSERT, UPDATE`. Check `pending_secret_enc is null or purpose = 'ENROL'`. Purge rows 1 day after expiry (job as migrator, later). |
| `platform.platform_admin_auth_throttle` | `key_hash char(64) pk` (HMAC of the normalised email); `failures smallint not null default 0`; `window_started_at timestamptz not null`; `locked_until timestamptz null`; `updated_at timestamptz not null` | Holds rows for **any** typed email, so it is not an admin list (keys are keyed hashes). No grants to `sahaj_app`; definer `SELECT, INSERT, UPDATE, DELETE`. Purge rows not updated for 1 day and not locked (job later). |
| `platform.platform_admin_session` | `id uuid pk`; `admin_id uuid not null fk platform_admin`; `refresh_token_hash char(64) not null unique` (HMAC-SHA256 hex); `previous_refresh_hash char(64) null` (reuse detection); `auth_method text not null check in ('TOTP','RECOVERY_CODE','ENROL')`; `created_at`, `last_seen_at`, `absolute_expires_at`, `revoked_at`; `revoked_reason text check in ('SIGN_OUT','REUSE','ADMIN_OFF','EXPIRED')`; `ip inet` | No RLS; no grants to `sahaj_app`; definer `SELECT, INSERT, UPDATE`. Index `(admin_id, created_at desc)`. Purge 30 days after end (job as migrator, later). |
| `platform.platform_admin_audit` | `id uuid pk`; `occurred_at timestamptz not null default now()`; `kind text check in ('AUTH','ACCESS','ACTION')`; `action text check (1..60)`; `auth_step text null check in ('PASSWORD','TOTP','RECOVERY_CODE','ENROL')`; `admin_id uuid null fk platform_admin`; `admin_name text null` (denormalised); `target_tenant_id uuid null fk platform.tenant`; `summary text check (<= 300)`; `reason text null check (10..500 when present)`; `ticket text null check (<= 20)`; `before_json jsonb`; `after_json jsonb`; `outcome text check in ('APPLIED','REFUSED')`; `refusal_code text null`; `ip inet null`; `request_id text null` | Append-only (D7): no grants to `sahaj_app`; definer `INSERT, SELECT`; `deny_audit_mutation` triggers for update/delete/truncate. Indexes `(target_tenant_id, occurred_at desc)`, `(occurred_at desc)`, `(admin_id, occurred_at desc)`. Retention 7 years, no delete path. Revision 2: `mobile_last4` removed, `auth_step` added. No column may hold an email typed at sign-in. |

### Changes to existing tables

| Table | Change |
| --- | --- |
| `platform.otp_challenge` | **None** (revision 2 drops the `purpose` column: admins no longer use OTP). |
| `platform.tenant` | optional tightening `check (plan in ('FREE','PRO'))` added `NOT VALID`, then `VALIDATE` after confirming existing rows (signup writes `FREE`). |

### Policies and grants for `sahaj_admin_definer`

| Table | Grant | Policy (`TO sahaj_admin_definer` only) |
| --- | --- | --- |
| `platform.tenant` | `SELECT (id, name, slug, plan, plan_ends_at, status, created_at)`, `UPDATE (plan, updated_at, version)` | `FOR SELECT USING (true)`; `FOR UPDATE USING (true) WITH CHECK (true)` |
| `platform.clinic_profile` | `SELECT (tenant_id, name, city, specialty_code)` | `FOR SELECT USING (true)` |
| `platform.membership` | `SELECT (tenant_id, user_id, role, status, last_login_at, created_at)` | `FOR SELECT USING (true)` |
| `platform.app_user` (no RLS) | `SELECT (id, full_name, mobile)` | none |
| `platform.setup_progress` | `SELECT (tenant_id, current_step, completed_at)` | `FOR SELECT USING (true)` |
| `platform.module_entitlement` | `SELECT, INSERT, UPDATE` | `FOR SELECT USING (true)`; `FOR INSERT WITH CHECK (true)`; `FOR UPDATE USING (true) WITH CHECK (true)` |
| `ipd.admission` | `SELECT (tenant_id, status)` | `FOR SELECT USING (true)` |
| `platform_admin*` tables | as in the table above | none (no RLS) |

No change to any `sahaj_app` policy. `EXECUTE` on every function in D4: revoked from `PUBLIC`, granted to `sahaj_app` (or to `sahaj_admin_app` under H3).

### Functions

As listed in D4, with the rules in D4. Each is its own `CREATE FUNCTION` in a versioned migration (not `R__`, so a change is a reviewed new version). Numbers the functions enforce: pre-auth 5 minutes, 5 attempts per pre-auth, throttle 5 failures within 15 minutes then locked 15 minutes, session idle 30 minutes, absolute 12 hours. `admin_list_clinics` and `admin_get_clinic` return `jsonb` in the shape of `ClinicPage` / `ClinicDetail` in `docs/api/admin.yaml` (the Java side maps it, and labels are added in Java). Derivations the functions compute:

- `lastActiveAt` = max(`membership.last_login_at`) over `ACTIVE` memberships of the tenant.
- `status` = `SUSPENDED` when `tenant.status <> 'ACTIVE'`; else `INACTIVE` when `lastActiveAt` is null or older than 7 days (mockup `lastAct > 6`); else `ACTIVE`.
- `health` = `SUSPENDED`; else `INACTIVE`; else `NEEDS_HELP` when `setup_progress.completed_at` is null; else `HEALTHY`.
- owner = the earliest-created `ACTIVE` membership with role `OWNER`.
- module values default to `off / null / false / 25` when there is no `module_entitlement` row (SAH-19 lazy row).

### Dev seed

`backend/src/main/resources/db/dev-seed/afterMigrate__dev_seed_platform_admin.sql` (db-engineer; a migration artefact outside `db/migration`, so the folder-ownership hook may need the human to allow it). Placeholders `sahaj_admin_seed_email`, `sahaj_admin_seed_name`, `sahaj_admin_seed_password_hash` (the hash is computed in Java, D5). Inserts nothing when the email or hash placeholder is empty. Upsert rule and throttle clean-up as in D5. Leaves the TOTP columns untouched on conflict and null on insert.

### Tenant scope and retention summary

- Tenant-scoped, RLS on: none new.
- Platform-scope, privilege-controlled, no RLS: `platform_admin`, `platform_admin_recovery_code`, `platform_admin_preauth`, `platform_admin_auth_throttle`, `platform_admin_session`, `platform_admin_audit`.
- Retention: `platform_admin` kept (switched off, never deleted); recovery codes kept while the admin exists; pre-auth purged 1 day after expiry; throttle purged after 1 day idle; sessions purged 30 days after end; `platform_admin_audit` 7 years, append-only.

---

## Stories (acceptance criteria for the tech lead)

Story IDs ADM-1..3 are working names; the tech lead may map them to SAH numbers. All stories: module `platform` (admin packages), contract `docs/api/admin.yaml`, branch per DEC-011 (uncommitted on `feature/SAH-13-appointments-workspace`). UI text is exact mockup text unless marked *(not in mockup, H13)*.

### ADM-1 · Admin sign-in (email, password, authenticator app), dummy admin and the admin shell

Mockup screens: Sahaj admin portal: sign-in `pAdminLogin`; portal shell `pAdminV3`; entry button "Admin portal" on the clinic sign-in header (`pLogin`, `admin-login`).

**Story.** As a member of the Sahaj team, I want to sign in to a separate admin console with my work email, my password and a code from my authenticator app, so that I can look after clinics without being part of any clinic.

**Acceptance criteria**
1. Given the clinic sign-in page, then the header button **Admin portal** (title "Admin portal for the Sahaj team") opens `/admin/sign-in`.
2. Given `/admin/sign-in`, then the page shows the header with the Sahaj brand and **Back to website**; the side panel with the pill "Sahaj team only", the heading "Admin console", the lede "For the Sahaj team to look after clinics on the platform, approve sensitive actions, and configure plans, features and templates." and the three ticks "Every action is logged with the admin's name and a reason", "Patient records stay private: admins see counts, not patients", "Support sessions need the clinic owner's OTP consent"; and a card "Sign in".
3. Given the card, then it shows, in this order, **Work email**, **Password** (masked), **Code from authenticator app** (6 digits, numeric keypad), the button **Sign in to admin console** and the hint "Two-step sign-in is always on for admins, and sign-in works only from approved networks." No field is pre-filled.
4. Given a code that is not empty and not 6 digits, then the toast "Enter the 6-digit code from your authenticator app" shows and nothing is sent. An email that is not an email address shows "Enter a valid work email" *(not in mockup, H13)*.
5. Given an enrolled admin with the right email, password and current code, then one press signs in (`/auth/login` then `/auth/totp/verify`): the server creates an admin session, returns an access token (10 minutes, `aud` `sahaj-admin`, `role` `PLATFORM_ADMIN`, `amr` `pwd`+`otp`, no tenant) and sets the `sahaj_admin_rt` cookie, the browser opens `/admin/clinics`, and the toast reads "Signed in as {name} · Platform admin" *(mockup: "Signed in as {name} · {role}"; role label H5)*.
6. Given an unknown email, a wrong password, a switched-off admin or a wrong code, then the card shows "The email, password or code is not right." *(not in mockup, H13)*. The API answers the same `401 admin-credentials-invalid` for unknown email, wrong password and switched-off admin, with no measurable timing difference (dummy BCrypt), and `401 totp-invalid` for a wrong code.
7. Given 5 failed steps (password, code or recovery code) for one email within 15 minutes, known or unknown, then that email is locked for 15 minutes: "Too many attempts. Try again in {m} min." (`423 admin-sign-in-locked`, `retryAfterSeconds`) *(not in mockup, H13)*. More than 10 login requests per email, or 20 sign-in requests per address, in 15 minutes: `429 rate-limit`. A successful sign-in resets the email's count.
8. Given a correct password and more than 5 minutes before the code is sent, or 5 wrong codes on one attempt, then "Your sign-in took too long. Start again." (`401 preauth-expired`) and the card resets *(not in mockup, H13)*. A code already used (same 30-second step or earlier) is refused.
9. Given an active admin who has not enrolled (first sign-in), when the right email and password are sent, then the card shows **Set up your authenticator app** with the key for manual entry in groups of 4, **Copy key**, the `otpauth://` link, **Code from authenticator app**, **Confirm and sign in** and **Start again** (texts in D2.7, *not in mockup, H13*). No QR code (H18). A correct code enrols the app, starts the session and shows **Save your recovery codes** with 10 codes in the form `XXXX-XXXX-XXXX-XXXX`, **Copy codes** and **I have saved them**, which opens `/admin/clinics`. The codes and key are never shown again.
10. Given an enrolled admin, then **Use a recovery code** lets them sign in with an unused recovery code instead of a code; each code works once (`ADMIN_RECOVERY_CODE_USED`).
11. Given a request from a network that is not allowed, then "Sign-in works only from approved networks." (`403 admin-network-denied`) on every `/api/v1/admin/**` call.
12. Given a signed-in admin, then the shell shows: sidebar with brand and the group **Platform Management** containing only the items built so far (ADM-1: none clickable besides the landing page; ADM-2 adds **Organizations**); collapse button "Collapse menu"; top bar with "Sahaj platform" / "All organizations" and the scope pill "Platform Management"; profile button (initials) opening a menu with the admin's name, "Platform admin · Sahaj team" and **Sign out**; the strip "Platform view: counts only. Patient records stay inside each organization."; a breadcrumb. The mockup's Switch, search (Ctrl K), Pilot/10 lakh toggle, notifications and help are not shown.
13. Given ADM-1 alone, then `/admin/clinics` shows the breadcrumb "Platform Management › Organizations" and an empty page area *(placeholder until ADM-2; H13)*.
14. Given the access token expires, then the app refreshes silently through `/admin/auth/refresh` (rotating the cookie). After 30 minutes with no admin API call, after 12 hours, or when the admin is switched off, the next call answers `401 admin-session-ended` and the app returns to `/admin/sign-in` with "Your session ended. Sign in again." *(not in mockup, H13)*. Refresh never asks for the password or code again within those limits.
15. Given **Sign out**, then the session is revoked, the cookie cleared, and the browser opens `/admin/sign-in`.
16. Isolation: an admin token is refused on every `/api/v1/platform|patient|opd|ipd|hra|billing/**` route (`401`); a clinic token is refused on every `/api/v1/admin/**` route except the four public auth endpoints (`401`); a pre-auth token is refused as a bearer token everywhere (`401`); the admin token carries no `tid`; an admin request binds no `app.tenant_id`, so a plain query on any tenant table returns no rows (proved as `sahaj_app`).
17. Secrets at rest: passwords are BCrypt (strength 12); the TOTP secret is stored only as AES-256-GCM ciphertext that does not decrypt on another admin's row; refresh tokens, pre-auth tokens, recovery codes and throttle keys are stored only as HMAC-SHA256. `sahaj_app` cannot `SELECT` any `platform_admin*` table (proved as `sahaj_app`).
18. Dummy admin: with profile `dev`, `SAHAJ_ADMIN_SEED_EMAIL` and `SAHAJ_ADMIN_SEED_PASSWORD` set (outside source control), an active admin with that email, that password and the name `SAHAJ_ADMIN_SEED_NAME` (default "Dev admin") exists after start, not yet enrolled. With `sahaj.admin.totp.dev-bypass=true` (dev only) and a request from the same machine, any 6 digits are accepted as the code, including at enrolment; the password is always checked. With any other profile and either seed variable set, or the bypass on, the app refuses to start. A seed password that fails the policy stops startup without printing it. Nothing is seeded by a versioned migration or from a committed value.
19. Audit: every successful sign-in (`ADMIN_SIGNED_IN` with method), failed step (`ADMIN_SIGN_IN_FAILED` with step, admin id only when the email is an admin's), enrolment, recovery-code use, sign-out and refresh-token reuse is written to `platform.platform_admin_audit` with time, address and request id. No typed email, password, code, secret, token or recovery code appears in any log, problem response or audit row.

**API (`/api/v1/admin`)**

| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| POST | `/auth/login` | public | `{email, password}` | `200 {next, preAuthToken, expiresInSeconds}` | 400, 401, 403, 423, 429, 503 |
| POST | `/auth/totp/enrol` | public (pre-auth token) | `{preAuthToken}` | `200 {secretKey, otpauthUri, accountName, issuer}` | 400, 401, 403, 409, 429, 503 |
| POST | `/auth/totp/verify` | public (pre-auth token) | `{preAuthToken, code}` or `{preAuthToken, recoveryCode}` | `200 AdminAuthResult` + cookie | 400, 401, 403, 409, 423, 429, 503 |
| POST | `/auth/refresh` | public (cookie) | none | `200 AdminAuthResult` + rotated cookie | 401, 403, 429, 503 |
| POST | `/auth/sign-out` | PLATFORM_ADMIN | none | `204` | 401, 403 |
| GET | `/me` | PLATFORM_ADMIN | none | `AdminMe` | 401, 403 |

**Tasks.** db-engineer: six admin tables, the auth functions, definer grants, role-exists check, dev-seed callback. java-developer: admin security chain, admin token service (own key, startup checks, `amr`), `AdminPasswordHasher` (BCrypt 12, not a `PasswordEncoder` bean), password policy, RFC 6238 TOTP with HMAC-SHA1 and Base32 codec, AES-GCM secret cipher, keyed token hasher, enrolment and recovery codes, dummy-hash timing, throttle and rate limiters, network filter, `AdminPrincipal` branch in `TenantBindingTransactionManager`, dev seed customizer, `AdminSeedGuard`, `AdminTotpDevBypassGuard` with the loopback check, the `aud` rejection in the clinic `JwtTokenService`, convention test; unit tests including the RFC vectors. angular-developer: `/admin` lazy feature, sign-in card exactly as the mockup, enrolment and recovery-code states, recovery-code entry, shell, admin auth service and interceptor, the "Admin portal" button; unit tests. qa-engineer: cross-realm and pre-auth token tests, enumeration equivalence (status, body and timing) for unknown email / wrong password / switched-off admin, lockout for unknown emails, TOTP replay, RLS-empty proof as `sahaj_app`, no-privilege proof on `platform_admin*` as `sahaj_app`, audit append-only as `sahaj_app` and `sahaj_migrator`, dev bypass refused from a non-loopback address.

**Out of scope.** Mobile OTP for admins; forgot password and password reset; change password; regenerate recovery codes; self-service authenticator reset; QR code; remember this device; admin roles other than one; invite admin; admin list; network on/off switch; overview dashboard.

### ADM-2 · Organizations directory and clinic detail (read-only)

Mockup screens: Sahaj admin portal: Organizations `orgDirPage` (sidebar Platform Management → Organizations, route `clinics`); clinic detail `aClinic` header and the tabs Plan and billing and Modules (read-only) (`aModules`).

**Story.** As a Sahaj admin, I want to find any clinic and see its plan and modules, so that I can answer a clinic's question without opening its records.

**Acceptance criteria**
1. Given an admin signed in with both factors (ADM-1), the sidebar shows **Organizations** under Platform Management, opening `/admin/clinics`; breadcrumb "Platform Management › Organizations".
2. Given the page, then the heading "Organizations" and the lead "{N} organizations. Search, filter and act on them in bulk." (N = all organizations, Indian digit grouping) *("act on them in bulk" while bulk is out of scope: H13)*.
3. Search: a segmented control **All fields · Name · Code · Owner mobile · City or state** and a search box whose placeholder follows the field: "Name, code, city, owner or mobile", "Organization name", "Code, e.g. SSC or K104512", "At least 4 digits", "City or state". With Owner mobile and fewer than 4 digits typed, the hint "Type at least 4 digits of the owner's mobile to search" shows and no search is sent. "Code" searches the clinic web address (slug) *(H14)*.
4. Signed up: segmented **Any time · 30 days · 90 days · 1 year** (title "Signed up in the last…").
5. Filters (left panel "Filters"), each with options and live counts, multi-select, a **Clear** link per filter, a "Find …" box when a filter has more than 8 options, and "Show all N" / "Show fewer" after 6: **Status** (Active, Inactive, Suspended), **Plan** (Free year, Pro), **Specialty** (General practice, Dental, Paediatrics, Homeopathy), **City**, and **IPD** (Off, Trial, On, Read-only) *(IPD filter is from the brief, not the mockup; mockup's State, Health, Type and Partner are not available: H14)*. Counts for a filter apply every other active filter and the search, but not the filter itself (mockup `odMatch(o, except)`).
6. Active search and filters appear as removable chips with **Clear all**.
7. Result bar: "{n} organizations" (or "1 organization") plus " of {N}" when filtered; **Sort**: Best match, Name A–Z, Recently active, Newest (Best match applies only to an All fields search; otherwise Name A–Z). The mockup's "Most visits" is not available.
8. Table columns: **Organization** (initials badge, name, "{code} · {owner name}"), **Location** (city), **Plan** ("Free year" or "Pro"), **Status** (pill Active / Inactive / Suspended), **Last active** ("Today", "Yesterday", "{n} days ago", "—" when no one has signed in). The mockup's selection ticks, Branches, Visits 7d and pin star are not shown *(H14)*. Each row opens the detail.
9. Pager: "Showing {from}–{to} of {n}", per page **25 · 50 · 100** (default 25), previous/next buttons, "Page [input] of {pages}".
10. Empty result: "No organizations match", "Remove a filter or search a different field." and **Clear all filters**.
11. Given a row, then `/admin/clinics/{id}` shows the breadcrumb "Platform Management › Organizations › {name}", the notice "Platform view: usage, billing and support only. This clinic's menus, masters and settings are in Organization Management." (without the **Manage configuration** button: out of scope, H13), and the header: initials, name, health pill (Healthy / Needs help / Inactive / Suspended), "{code} · {city} · {specialty} · {slug}.sahaj.in", and "Owner {name} · mobile ••••• {last 5 digits} · joined {24 Oct 26} · {plan text}". No action buttons in ADM-2.
12. Tabs: **Plan and billing** (default) and **Modules**; Usage, Settings for this clinic, Support and Activity log are not shown (Activity log arrives in ADM-3). Plan and billing shows the panel "Current plan" with the row Plan = "Free year" or "Pro" only *(Next renewal, UPI AutoPay, GST invoice to, Invoices: out of scope)*.
13. Modules (read-only for ADM-2): "Modules for this clinic" with the subline "Plan → module → clinic settings → role → user. Each layer can only narrow what the one above allows."; rows **OPD** "Core · always on" pill On; **In-patients (IPD)** subline "{n} patients admitted now" ("1 patient admitted now") or "No admissions", with the control Off · Trial · On · Read-only showing the current effective state, disabled; **Bed limit** "Beds the clinic can add in IPD setup" with the value; **Staff, timings and leave (HRA)** "Add-on · the owner can also switch it" Off · On, disabled; **Pharmacy · Lab** "Phase 2" pill "Not available yet". The mockup's **WhatsApp** row is not shown (Later) *(H13)*. Right panel "What switching IPD off does" exactly as the mockup, and the warning "IPD cannot be switched off or made read-only while patients are admitted. They must be discharged first." *(the mockup's "403 MODULE_NOT_ENABLED" line versus the real `module-off` problem type: H13)*.
14. Unknown or malformed clinic id: the detail shows the not-found state (`404`).
15. Privacy and audit: the list and detail return clinic-level fields and counts only: no patient data, no full owner mobile (last 5 digits in the detail only). Every list call writes `ACCESS CLINICS_LISTED` (filters and total, never the search text) and every detail call writes `ACCESS CLINIC_VIEWED` with the clinic id and the admin who signed in with both factors.
16. Performance: with 10,000 organizations the list answers within 1 s at the 95th percentile on the dev machine (single query with window count; facet counts in the same function).
17. Access: without a live admin session (no token, a clinic token, a pre-auth token, or an ended session) both endpoints answer `401`; the pages return to `/admin/sign-in`.

**API (`/api/v1/admin`)**

| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/clinics` | PLATFORM_ADMIN | `?q&field&status*&plan*&specialty*&city*&ipd*&signedWithinDays&sort&page&size` | `ClinicPage` | 400, 401, 403 |
| GET | `/clinics/{id}` | PLATFORM_ADMIN | none | `ClinicDetail` | 400, 401, 403, 404 |

**Tasks.** db-engineer: `admin_list_clinics`, `admin_get_clinic`, `ipd.admin_admitted_count`, definer grants and policies. java-developer: admin directory service and controller, `IpdOccupancyPort` in `platform.api` with the ipd implementation (coordinate with Team B; ipd package), label mapping; unit tests. angular-developer: Organizations page (search, field switch, signed-up switch, filters with counts, chips, sort, table, pager, empty state), detail header, tabs Plan and billing and Modules (read-only); unit tests. qa-engineer: facet-count correctness, paging limits, privacy (no patient fields, mobile masking), audit rows, proof that a clinic session still sees only its own tenant.

**Out of scope.** Saved views, export, bulk jobs and selection, pins, State/Health/Type/Partner filters, Branches and Visits 7d columns, org picker, Usage/Support/Settings tabs, Manage configuration.

### ADM-3 · Modules and plan management

Mockup screens: Sahaj admin portal: clinic detail Modules tab (`aModules`, actions `amod`, `amodbeds`), Plan and billing tab and the action sheet "Change plan or price" (`ADM_KIND.plan`, `aActHTML`, `aago`), Activity log tab (`aAuditRows`).

**Story.** As a Sahaj admin, I want to switch IPD and HRA, set the bed limit and change the plan for a clinic, with every change logged, so that clinics get IPD the product way rather than by starting a trial themselves.

**Acceptance criteria**
1. Given the Modules tab, then the IPD control **Off · Trial · On · Read-only**, the HRA control **Off · On** and the Bed limit stepper (− / +, aria labels "Fewer beds" / "More beds") are enabled.
2. Given an IPD choice different from the current state, then the server applies it: Trial sets the trial end to 30 days from now (shown as "Trial · 30 days"); On, Off and Read-only clear the trial end. Toast "IPD {state in lower case} for {clinic}" plus " · menus and setup appear for the clinic right away" for On and Trial (for example "IPD on for Shree Sai Family Clinic · menus and setup appear for the clinic right away"). Choosing the current state does nothing.
3. Given Off or Read-only while patients are admitted, then nothing changes and the panel shows the critical notice "{n} patients are admitted in {clinic}. IPD stays {current state in lower case} until they are discharged." (`409 ipd-patients-admitted`); the refusal is audited.
4. Given HRA, then toast "HRA on for {clinic}" / "HRA off for {clinic}".
5. Given the bed limit stepper, then each click changes the limit by 5 within 5 to 300 (buttons disabled at the ends); no toast (as the mockup). Existing beds above a lowered limit stay; new beds are refused by the existing IPD bed-limit rule.
6. Given the clinic's own app on its next request, then menus, `/ipd/*` access (`403 module-off` when Off, `409 ipd-readonly` when Read-only), the HRA menu and the bed limit follow the new values without signing in again.
7. Given Plan and billing, then the button **Change plan** (and, in the header actions, **Change plan or price**) opens the sheet titled "Change plan or price", subtitle "{clinic} · {code} · {city}", the info notice "The new plan starts at the next renewal unless the owner asks to switch now." *(phase 1 applies immediately: H4)*, **New plan** segmented Free year · Pro *(mockup: Pro monthly · Pro yearly · Custom: H4)*, **Reason (saved in the audit log)** (placeholder "What happened, and who asked for it"), **Support ticket (optional)** (placeholder "e.g. T-2304"), and the buttons **Cancel** and **Change plan or price**.
8. Given a reason shorter than 10 characters, then "Write a reason of at least 10 characters. It goes into the audit log." (`400`, field `reason`). Reason at most 500 characters, ticket at most 20.
9. Given a valid plan change, then the plan changes at once, the sheet closes, the toast reads "Plan changed to {Free year | Pro}", and the clinic's Plan and modules page shows the new plan on its next request. Choosing the current plan returns the clinic unchanged with no audit row.
10. Given the **Activity log** tab (now shown), then "Activity" with "Admin actions and security events for this clinic" and the table **When · Admin · Action · Reason · Second admin / consent**, newest first. Rows: "IPD set to {On | Off | Trial · 30 days | Read-only}", "HRA switched on" / "HRA switched off", "Bed limit set to {n}" (reason "Module entitlement"), "Plan changed to {plan}" (reason as typed, plus " (ticket {T-…})" when given), refused IPD changes with the notice text; last column "—". Empty: "No entries.". 20 per page with the standard pager.
11. Every change and refusal writes one `ACTION` row in `platform.platform_admin_audit` in the same transaction as the change: admin id and name (from the two-factor session, never from the request), clinic id, summary, reason/ticket where given, before/after values, outcome, request id. Rows cannot be updated or deleted by any application role (proved as `sahaj_app` and `sahaj_migrator`).
12. DEC-010 closed: `sahaj.modules.owner-ipd-trial` is `false` in every profile including `dev`; `POST /api/v1/platform/modules/ipd-trial` answers `404`; the owner's Plan and modules page offers only "Request IPD". (Test-profile handling: H8.)
13. Concurrency: the switch to Off/Read-only and an IPD admission cannot both succeed for the same clinic at the same moment once Team B's `FOR SHARE` read is in (see D6). Two admins changing different modules of one clinic at once both succeed.
14. Access: a change sent with a clinic token, a pre-auth token or an ended admin session answers `401` and changes nothing; no audit row is written for it.

**API (`/api/v1/admin`)**

| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| PATCH | `/clinics/{id}/modules` | PLATFORM_ADMIN | `ModulesChange` (one or more of `ipdState`, `hraOn`, `bedLimit`) | `ClinicDetail` | 400, 401, 403, 404, 409 |
| PUT | `/clinics/{id}/plan` | PLATFORM_ADMIN | `PlanChange` `{plan, reason, ticket?}` | `ClinicDetail` | 400, 401, 403, 404 |
| GET | `/clinics/{id}/activity` | PLATFORM_ADMIN | `?page&size` | `ActivityPage` | 400, 401, 403, 404 |

**Tasks.** db-engineer: `admin_lock_modules`, `admin_set_modules`, `admin_set_plan`, `admin_list_clinic_activity`, optional plan check constraint. java-developer: admin modules and plan service reusing `ModuleEntitlements`, admitted-count refusal, the dev profile flag flip, `platform.yaml` behaviour of `/modules/ipd-trial`; Team B: `FOR SHARE` entitlement read in admit; unit tests. angular-developer: enabled Modules controls, error notice, toasts, Change plan sheet, Activity log tab; unit tests. qa-engineer: refusal with admitted patients, trial date, clinic-side effect without re-sign-in, audit atomicity (rolled-back change leaves no audit row; refused change leaves a REFUSED row), append-only proof.

**Out of scope.** Second-admin approval and the Approvals page, custom price, Pro monthly/yearly, renewals and invoices, extend free period, suspend/reactivate, WhatsApp allowance, refund, support sessions, a custom trial end date, "Open the clinic app as owner", clinic-side visibility of admin changes.

---

## Needs human decision

| # | Question | Why it is not mine | Default used until answered |
| --- | --- | --- | --- |
| H1 | **Admin sign-in factor.** | | **DECIDED (DEC-012): email + password + authenticator app (TOTP), as the mockup.** Designed in D2. |
| H2 | **Function owner.** | | **DECIDED (DEC-012): new `NOLOGIN` role `sahaj_admin_definer`.** The human adds it to `scripts/setup/create-database.mjs` and `scripts/db/init.sql` and re-runs the setup; the ADM-1 migrations need it. |
| H3 | **Separate login role for the admin pool** (`sahaj_admin_app`, only role with `EXECUTE` on admin functions; the clinic pool cannot call them). Revision 2 narrows the gap (an injection can no longer obtain an admin token) but an injection can still read admin BCrypt hashes and call data functions in SQL. | Adds a credential, `.env.example` entries and a setup-script change (human-owned). | Not built in ADM-1..3; `sahaj_app` has `EXECUTE` (DEC-011). Recommended before production. |
| H4 | **Plan model.** Mockup plans are Free year, Pro monthly, Pro yearly, Custom (with price), Grace period, Suspended; the plan change "starts at the next renewal" and **needs a second admin** (default on in Access and security). Phase 1 has FREE/PRO only, immediate, no approval. Also the label for PRO ("Pro"). | Product and commercial scope; deviates from mockup text and flow. | FREE/PRO, label "Pro", immediate, no second admin, mockup notice text shown unchanged. |
| H5 | **Admin roles.** Mockup has Super admin, Support, Billing, Read-only auditor with a permission matrix; phase 1 has one role `PLATFORM_ADMIN`. Which label is shown ("Platform admin" from `AX_PERSONA`, or "Super admin")? | Product scope. | One role, label "Platform admin". |
| H6 | **Network restriction** as configuration (`SAHAJ_ADMIN_ALLOWED_NETWORKS`), fail closed outside dev, instead of the mockup's on/off switch. Production admins cannot sign in until the human sets it. | Operational/security policy. | As described in D2.8. |
| H7 | **Session and lockout numbers.** 10-minute token, 30-minute idle (mockup default), 12-hour absolute (unchanged); new: pre-auth 5 minutes and 5 codes; 5 failed steps per email in 15 minutes lock 15 minutes; 10 logins per email and 20 sign-in requests per address per 15 minutes. | Risk acceptance. | As listed. |
| H8 | **Owner IPD trial.** ADM-3 sets `owner-ipd-trial=false` in dev too. Should the `test` profile keep it on until tests set the entitlement another way, and when is the endpoint removed? | Affects Team B's tests and demos. | Dev off with ADM-3; test stays on until QA migrates tests; endpoint removed in a later clean-up. |
| H9 | **Trial end date.** The brief asks for a trial end date; the mockup only has "Trial · 30 days". | Not in the mockup. | Fixed 30 days from the moment Trial is chosen; no date picker. |
| H10 | **Lowering the bed limit below existing beds.** | Business rule not in the mockup. | Allowed; existing beds stay, new beds refused. |
| H11 | **Should the clinic's own Activity log show Sahaj admin changes** (for example "Sahaj set IPD to On")? | Transparency/privacy policy; not in the mockup. | No; admin audit only. |
| H12 | **Separate origin for the admin console** (`admin.<domain>` as the mockup shows) versus the same Angular origin. | Hosting decision. | Same origin, path `/admin`. |
| H13 | **Strings and elements not in the mockup, or mockup text that does not match phase 1.** Sign-in: "Enter a valid work email"; "The email, password or code is not right."; "Too many attempts. Try again in {m} min."; "Too many attempts. Try again in a few minutes."; "Your sign-in took too long. Start again."; the hint without the "Mockup:" prefix; the enrolment card (heading, instructions, "Key for manual entry", **Copy key**, "Or open on your phone", **Confirm and sign in**, **Start again**); the recovery-codes card (heading, text, **Copy codes**, **I have saved them**); **Use a recovery code** and the **Recovery code** field. Elsewhere (unchanged): "Sign-in works only from approved networks."; "Your session ended. Sign in again."; the ADM-1 empty landing area; lead "…act on them in bulk." with no bulk; hiding the WhatsApp row and the Manage configuration button; the static "Calls to /api/v1/ipd/* return 403 MODULE_NOT_ENABLED" and "Read-only for 90 days, then hidden" lines; default detail tab Plan and billing. | UI text is mockup-owned; the mockup draws no enrolment, recovery or error states for the admin sign-in. | As written in D2.7 and the stories. |
| H14 | **Directory substitutions.** The mockup's clinic "code" (for example SSC, C1040) does not exist; the slug is used. State, Health, Type and Partner filters, and Branches and Visits 7d columns, need data that does not exist or belongs to other modules. The brief's IPD filter is added. IPD, HRA and bed limit are returned in list items but shown only in the detail unless the human wants list columns. | Mockup deviation. | As written in ADM-2. |
| H15 | **Forgot password, password reset and change password (new).** The mockup has no "Forgot password" link and no change-password screen, and phase 1 has no email or SMS sending (notifications are out of scope), so there is **no mobile OTP or email fallback**. Also: confirm the password policy (12 to 64 characters, no composition rules, no expiry). | Product scope and an account-recovery policy. | **Phase 1 answer: no self-service reset or change.** An admin who forgets the password asks another Sahaj team member with migrator access, who sets a new BCrypt hash with a one-off SQL (D5 / H17 procedure) and clears the email's throttle row; the new password is handed over out of band. Policy as in D2.1. Recommend a "Change password" item in the profile menu as a later story. |
| H16 | **Lost authenticator and recovery codes (new).** Recovery codes and the enrolment screens are not in the mockup. If an admin loses the phone and all codes, who resets the authenticator? | Account-recovery policy; screens not in the mockup. | 10 single-use recovery codes shown once (D2.6). Full loss: a human with migrator access clears `totp_secret_enc`, `totp_key_version`, `totp_enrolled_at`, `totp_last_used_step` and deletes the recovery codes; the next sign-in forces enrolment. Audited only by the SQL operator's own records in phase 1. |
| H17 | **Production admin provisioning (new).** No "Invite admin" screen. | Operational procedure; human-only database access. | A human runs a one-off SQL as `sahaj_migrator`: insert email (lower case), name and a BCrypt hash produced offline (for example `htpasswd -nbBC 12 "" '<password>'`, `$2y$` is accepted by Spring's BCrypt), never typing the plain password into SQL or shell history on a shared machine. The admin enrols the authenticator on the first sign-in. Recommend a small admin CLI or the Invite admin screen later. |
| H18 | **QR code at enrolment (new).** The frontend has no QR library; the brief allows a QR only without a new dependency. | Adding a frontend dependency, or hand-writing a QR encoder, is a human call. | No QR: key for manual entry plus the `otpauth://` link as text and link. |
| H19 | **TOTP key management (new).** New secret `SAHAJ_ADMIN_TOTP_KEY` (Base64, 32 bytes). Losing it forces every admin to re-enrol; changing `SAHAJ_ADMIN_JWT_SECRET` ends all sessions and voids unused recovery codes. Rotation tooling is not built. | Secrets management is human-owned (`.env`). | Single key version 1; rotation by a later story; the human keeps both secrets in a password manager. |

---

## Handoff · ADM-1..3 · solution-architect → db-engineer
Status: READY FOR HUMAN `contract` APPROVAL (revision 2; H1 and H2 decided in DEC-012; the human must create role `sahaj_admin_definer` before the ADM-1 migrations run)
Mockup screen: Sahaj admin portal (platform admin view): `pAdminLogin`, `pAdminV3`, `orgDirPage`, `aClinic`, `aModules`, `aAuditRows`
Files changed:
- `docs/architecture/adr/ADR-003-sahaj-admin-portal.md`: revision 2 (D1, D2, D3 token hashing, D4 auth functions and owner, D5, D7 auth actions, D8, D9, threats, consequences, Data needed, ADM-1..3 criteria, H list).
- `docs/api/admin.yaml`: version 0.2.0; OTP endpoints replaced by `/auth/login`, `/auth/totp/enrol`, `/auth/totp/verify`; new schemas and problem types.
Validation: `node scripts/validate.mjs docs`: not run (no shell tool in this session); the tech lead to run it.
Decisions made: D1–D9 above, revision 2.
Open questions / requests for other owners:
- Human: approve `contract`; create `sahaj_admin_definer` in `scripts/setup/create-database.mjs` and `scripts/db/init.sql` and re-run the setup (DEC-012); add to `.env.example` `SAHAJ_ADMIN_JWT_SECRET`, `SAHAJ_ADMIN_TOTP_KEY`, `SAHAJ_ADMIN_SEED_EMAIL`, `SAHAJ_ADMIN_SEED_PASSWORD`, `SAHAJ_ADMIN_SEED_NAME`, `SAHAJ_ADMIN_ALLOWED_NETWORKS` (remove `SAHAJ_ADMIN_SEED_MOBILE` if it was added); allow the db-engineer to write `backend/src/main/resources/db/dev-seed/` if the ownership hook blocks it; answer H3–H19 or accept the defaults.
- db-engineer: "Data needed" above.
- java-developer (Team A): D2, D3, D4 Java side, D5 guards and seed customizer, D8; (Team B) `FOR SHARE` entitlement read in the ipd admit transaction and the `IpdOccupancyPort` implementation in the ipd package.
- angular-developer: D2.7 screen states, D8 frontend rules; no new dependency; the admin interceptor must never send the clinic token, and the clinic interceptor must never send the admin token.
- qa-engineer: the ADM-1 criteria 16–19 proofs; enumeration equivalence; TOTP replay; dev bypass refused off loopback.
