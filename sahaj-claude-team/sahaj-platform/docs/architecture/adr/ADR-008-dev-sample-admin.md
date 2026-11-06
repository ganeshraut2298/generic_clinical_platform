# ADR-008 · Dev-only endpoint that creates one sample platform admin

Decision record: `docs/decisions/DEC-016-sample-admin-for-testing.md` (human chose option B, 5 Nov 2026)
Amends: ADR-003 revision 2, D5 "Dummy admin" (adds a second, dev-only way to get an admin) and, only in dev and test databases, D1 / Data needed "no `INSERT` for the definer" (see H-DEV-1)
Module: platform (admin realm). New code lives in a separate source set that the production build never compiles.
Mockup screen: none. This is a developer tool with **no UI**; it is called with `curl` from the developer's own machine. Nothing is added to any screen (the human asked for an API only, DEC-016).
Contract: `docs/api/admin.yaml` 0.3.1, operation `devCreateSampleAdmin` (`x-dev-only: true`)
Status: PROPOSED, revision 2 (security review fixes R1 to R12, `docs/reviews/DEC-016-security.md`, 5 Nov 2026; see "Review fixes" at the end). Waiting for human `contract · APPROVED` (review M4) before first use.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect

---

## Context

- The admin portal (ADR-003 rev 2) is a separate realm: chain `/api/v1/admin/**`, password (BCrypt 12) plus authenticator app (TOTP), SECURITY DEFINER functions owned by the `NOLOGIN` role `sahaj_admin_definer`. `sahaj_app` has no privilege on any `platform_admin*` table, and the definer has **no `INSERT`** on `platform.platform_admin`: admins are created only by the migrator (dev seed, human provisioning).
- The designed dev path is the env seed (ADR-003 D5): `AdminSeedFlywayCustomizer` (`@Profile("dev")`) validates `SAHAJ_ADMIN_SEED_EMAIL` / `_NAME` / `_PASSWORD`, checks `AdminPasswordPolicy`, hashes with `AdminPasswordHasher`, and hands only email, name and hash to the Flyway callback `db/dev-seed/afterMigrate__dev_seed_platform_admin.sql`, which runs **as `sahaj_migrator`**. The seeded admin starts **not enrolled**; TOTP is enrolled on the first sign-in through `AdminAuthService.enrol` (20 random bytes, `TotpSecretCipher.encrypt(secret, adminId, keyVersion)`, Base32, `otpauth://` URI).
- The human wants one call that returns a working admin login. DEC-016 option B sets the conditions: absent from the production jar unless built with Maven profile `dev-tools`; at runtime needs profile `dev` AND `sahaj.admin.dev-sample-enabled=true` AND a loopback caller; returns the password and TOTP secret once; never logs them; 404 otherwise or when an admin already exists.
- Two facts make this harder than "add a controller":
  1. **The default Spring profile is `dev`** (`application.yml`: `${SPRING_PROFILES_ACTIVE:dev}`). A runtime profile check alone protects nothing in a deployment that forgets to set a profile. The build-time exclusion is the real control; every runtime check is a second layer.
  2. **At request time the app is `sahaj_app`.** It cannot insert an admin, and the definer cannot either. The seed's database write (a Flyway callback as migrator) cannot be reused per request. Something must change in the database, and only in dev/test databases (H-DEV-1).

---

## Decision

### D1 · Endpoint

`POST /api/v1/admin/dev/sample-admin`, no request body (a body, if sent, is never read), no authentication. Under the admin path so it inherits the admin network allow-list and never reaches the clinic chain, but in its own security chain, outside authentication.

Response `201` with `DevSampleAdmin` (contract): `adminId`, `email` (always `sample-admin@example.test`), `fullName` (always `Sample admin`), `password`, `secretKey` (Base32, groups of 4), `otpauthUri`, `accountName`, `issuer`, `digits`, `periodSeconds`, `algorithm`, `totpEnrolled` (always true), `totpDevBypassOn`, `signInPath`. Headers `Cache-Control: no-store` and `Pragma: no-cache`. Shown **once**: nothing can return them again.

`example.test` is a reserved name (RFC 2606/6761); it can never be a real mailbox.

### D2 · When it exists: build time first

**Build.** New Maven profile `dev-tools` in `backend/pom.xml` (java-developer):

- A separate source set `backend/src/devtools/java` and `backend/src/devtools/resources`, added **only inside the profile** with `build-helper-maven-plugin` (`add-source`, `add-resource`; version pinned in the profile, see R11; build-time only, nothing is added to the runtime classpath). Without `-Pdev-tools` the compiler never sees these files.
- Inside the profile, **a separate build directory**: `<build><directory>${project.basedir}/target-devtools</directory></build>`, and `finalName` `sahaj-backend-devtools-${project.version}`. Reason: Maven does not delete stale `.class` files. If the dev-tools build wrote to `target/classes`, a later plain `mvn package` without `clean` could pack the dev classes into the production jar. With a separate directory, `target/` never holds them.
- Inside the profile, the jar manifest gets `Sahaj-Dev-Tools: true` (spring-boot-maven-plugin `manifestEntries` or maven-jar-plugin `archive`), so a dev-tools jar is identifiable.
- Surefire and failsafe in the profile get the system property `sahaj.devtools=true` (tests use it to choose between "must be present" and "must be absent").
- All dev-tools classes live in packages `com.sahaj.platform.devtools..`; all dev-tools resources under `db/dev-tools/`. Nothing in `src/main` references a dev-tools class.

How to run it locally: `mvn -f backend/pom.xml -Pdev-tools spring-boot:run "-Dspring-boot.run.arguments=--sahaj.admin.dev-sample-enabled=true"`. The flag counts **only** as a command-line argument (C2, R2), and the call needs the per-start nonce from the startup warning (G7, R1). Full usage: R12.

The profile also skips `install` and `deploy` and renames the container image (R7), so a dev-tools build never lands in `~/.m2`, a repository or an image under the production name.

**Runtime, all of these, checked when the beans are created (a dev-tools `@Configuration` with conditions):**

| # | Condition | How |
| --- | --- | --- |
| C1 | Profile `dev` is active, and every active profile is `dev` or `test` | same rule as `AdminTotpDevBypassGuard.verify`, as a `Condition` (not a startup failure, see H-DEV-4) |
| C2 | `sahaj.admin.dev-sample-enabled=true` **given as a command-line argument of this run** | A `Condition` (replaces `@ConditionalOnProperty`, R2) that reads the raw value from the `commandLineArgs` property source only (`CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME`), never through `Environment.getProperty`. Accepted only when that source exists and its raw value is `true` (case-insensitive, no placeholder resolution). The same key in an environment variable, `SPRING_APPLICATION_JSON`, a system property (`-D`, `JAVA_TOOL_OPTIONS`, IDE VM options), any `application*.yml` or `spring.config.*location` file is **ignored**: the endpoint stays absent. Default false. Tests pass it as an argument too (R2), never as a system property |
| C3 | The admin realm is on: `sahaj.admin.enabled=true` and both secrets present | `@ConditionalOnAdminRealm` (needs `TotpSecretCipher`, `AdminPasswordHasher`) |
| C4 | No proxy configuration: `server.forward-headers-strategy` absent or `none`, `server.tomcat.remoteip.remote-ip-header` and `server.tomcat.remoteip.protocol-header` blank (`SAHAJ_REMOTE_IP_HEADER` unset), **`spring.main.cloud-platform` blank (any value, `none` included, refuses) and no cloud platform detected (`CloudPlatform.getActive(environment)` is null)** (R6) | a `Condition`. With any of them set, Spring Boot may take `getRemoteAddr()` from a header, so the loopback check would mean nothing: **fail closed** |
| C5 | The server binds to a loopback address: `server.address` is set and is a loopback IP literal (`application-dev.yml` sets `127.0.0.1`) | a `Condition`, using `IpLiterals.parse` |

When all hold, the dev-tools configuration creates: the gate (`DevEndpointGate` implementation), its own `SecurityFilterChain`, the controller, the service, the repository, the rate limiter, and a Flyway customizer that adds `classpath:db/dev-tools` (D5). It also creates the per-start nonce (R1) and logs one `WARN` at start: "Dev-only sample admin endpoint is on (dev-tools build). POST /api/v1/admin/dev/sample-admin from this machine creates an admin once. Send header X-Sahaj-Dev-Nonce: <32 hex characters>. The value changes at every start." The nonce is the only value in that line; no other secret, no email.

### D3 · Every other case answers the same 404, from main code

A 404 must come back in **every** other case, including the production jar where no dev class exists. Without help, an unauthenticated request to that path would get `401` from the admin chain, `503` from the "admin unavailable" chain, or `401` from the clinic chain when the realm is off, which differ from each other and reveal configuration.

So main code (shipped, but containing no endpoint logic) gets one small reserved-path filter:

- `com.sahaj.platform.web.admin.AdminDevPathFilter`, registered always (no condition) as a servlet filter with an order **before** Spring Security (`FilterRegistrationBean`, order lower than `SecurityProperties.DEFAULT_FILTER_ORDER`), matching the decoded lookup path (`AdminRequestPaths.lookupPath`, so `%2F` and similar tricks do not dodge it) `/api/v1/admin/dev` and everything under it.
- Main code also defines the public interface `com.sahaj.platform.web.admin.DevEndpointGate { boolean permits(HttpServletRequest request); }`. No implementation exists in main code.
- The filter takes `ObjectProvider<DevEndpointGate>`. **No gate bean (production jar, or any C1..C5 false): answer 404.** Gate present but `permits` is false: answer 404. Otherwise continue.
- The 404 is always the same bytes: status 404, **empty body**, no `Content-Type`, `Cache-Control: no-store`, no CORS headers, no `WWW-Authenticate`. It does not depend on method, path, headers or configuration.

**The gate's request checks (dev-tools, `permits`), all must pass:**

| # | Check | Otherwise |
| --- | --- | --- |
| G1 | Lookup path is exactly `/api/v1/admin/dev/sample-admin` and the method is `POST` | 404 (never 405) |
| G2 | `request.getRemoteAddr()` parsed by `IpLiterals.parse` (never resolves a host name) is a loopback address (`127.0.0.0/8`, `::1`) | 404 |
| G3 | None of these headers is present (names case-insensitive): `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Forwarded-Prefix`, `X-Forwarded-Server`, `X-Original-Forwarded-For`, `X-Real-IP`, `X-Client-IP`, `Client-IP`, `True-Client-IP`, `CF-Connecting-IP`, `Fastly-Client-IP`, `X-Cluster-Client-IP`, `Via` (R5). Their values are never read or trusted; presence alone means "came through a proxy" | 404 |
| G4 | `Host` is `127.0.0.1`, `localhost` or `[::1]`, with or without `:<port>` (defeats DNS rebinding: a rebound page sends its own host name) | 404 |
| G5 | No `Origin` header and no `Sec-Fetch-Site` / `Sec-Fetch-Mode` header (browsers send them on every `POST`; `curl` does not). A web page the developer visits can therefore never trigger it | 404 |
| G6 | No admin exists yet (checked in the database function, D5, not in the gate) | 404 (H-DEV-2) |
| G7 | Exactly one `X-Sahaj-Dev-Nonce` header whose value equals this start's nonce, compared in constant time (R1) | 404, same bytes; not counted by the rate limiter |

The controller calls `gate.permits(request)` again before the service (defence in depth).

### D4 · Its own security chain, outside authentication

Dev-tools contributes `SecurityFilterChain` `@Order(0)` with `securityMatcher` = exactly `POST /api/v1/admin/dev/sample-admin` (ahead of the admin chain at `@Order(1)`). Stateless, CSRF off (no cookie is read or set), **CORS off** (no `Access-Control-*` headers, so no browser can read a response), no form login, no basic, `permitAll`. Filters: `RequestLogContextFilter` (request id), the admin `AdminNetworkFilter` (same allow-list as the rest of `/api/v1/admin/**`; empty list is allowed only in dev), then the dev rate limiter (D6). No JWT filter: a bearer token or cookie sent with the request is ignored, never parsed. The controller carries `@PublicEndpoint` so the ADR-003 D8 convention test accepts it (java-developer may need to make the annotation and `AdminNetworkFilter` public).

The main admin chain, the "admin unavailable" chain and the clinic chain are not changed.

### D5 · One code path with the seed; one new dev-only database function

**Java, shared with the seed (no duplicated hashing or validation).** java-developer extracts from main code, without changing behaviour:

- `com.sahaj.platform.application.admin.AdminProvisioning.prepare(email, name, password)` returns `PreparedAdmin(email, name, passwordHash)`: the trim and lower-case, email pattern and length, `$sq$` refusal, name length, `AdminPasswordPolicy.isAcceptable`, then `AdminPasswordHasher.hash`. Today this logic sits inside `AdminSeedFlywayCustomizer.placeholders()`; the customizer then calls `prepare`, and so does the dev service.
- `TotpEnrolmentText.of(email, secretBytes)` returns the grouped Base32 key and the `otpauth://` URI (today inside `AdminAuthService.enrol`); `enrol` calls it, and so does the dev service.
- `TotpSecrets.newSecret(SecureRandom)`: 20 random bytes (today inline in `enrol`).

**Dev service (dev-tools, `@Transactional`):**
1. `adminId = UUID.randomUUID()` (needed before encrypting: the GCM associated data binds the ciphertext to the admin id, ADR-003 D2.3).
2. Password: 24 characters drawn with `SecureRandom.nextInt(57)` (unbiased) from `ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789` (no `I`, `O`, `l`, `0`, `1`; about 140 bits). It has no `-`, so it can never contain the email name `sample-admin`; it still goes through `AdminProvisioning.prepare` (policy plus hash), like the seed.
3. TOTP: `TotpSecrets.newSecret`, `TotpSecretCipher.encrypt(secret, adminId, CURRENT_KEY_VERSION)`.
4. One call to `platform.admin_dev_create_sample_admin(...)` (below). Returns created or not.
5. Not created: answer the D3 404. Created: build the response with `TotpEnrolmentText.of`; zero the secret byte array; return. The response record's `toString()` prints no field values (like `AdminAuthService.Enrolment`).

The sample admin is created **already enrolled** (the human asked for the TOTP secret in the response). Its first sign-in therefore gets `next: TOTP`, not `ENROL`, and it has **no recovery codes** (H-DEV-3). With `sahaj.admin.totp.dev-bypass=true` (dev default) any 6 digits work anyway; set the bypass to false to test real codes with the returned secret.

**Database: why a new function.** At request time the app is `sahaj_app`, which can only call definer functions, and the definer has no `INSERT` on `platform_admin` (ADR-003 D1). Options (H-DEV-1):

| | Option | Verdict |
| --- | --- | --- |
| **W1** | **Dev-only definer function and a column `INSERT` grant, created by a Flyway callback that exists only in the dev-tools source set** (`classpath:db/dev-tools`, location added by a dev-tools Flyway customizer only when C1..C5 hold) | **Recommended.** The app keeps connecting as `sahaj_app`. The production jar has neither the callback nor the customizer, so a production database never gets the grant or the function. The function can create only the one fixed identity, and only while there is no admin. |
| W2 | A dev-tools second `DataSource` that connects as `sahaj_migrator` and runs the seed's `INSERT` | Not chosen: puts migrator credentials on a request path, against the CLAUDE.md rule "the app connects as `sahaj_app`". |
| W3 | Run Flyway again from the request (the seed callback with generated placeholders) | Not chosen: running `migrate` from an HTTP request can apply pending migrations; the seed's upsert resets passwords; no TOTP. |

**Function `platform.admin_dev_create_sample_admin(p_admin_id uuid, p_password_hash text, p_totp_secret_enc bytea, p_totp_key_version smallint, p_ip inet, p_request_id text) returns boolean`** (details in Data needed):
- `SECURITY DEFINER`, owner `sahaj_admin_definer`, `SET search_path = pg_catalog, pg_temp`, schema-qualified names, no dynamic SQL; `EXECUTE` revoked from `PUBLIC`, granted to `sahaj_app`.
- Refuses (`42501`) when `platform.current_tenant()` is not null.
- Email `sample-admin@example.test` and name `Sample admin` are **constants inside the function**, not parameters: even an SQL injection as `sahaj_app` in a dev database can only create this one identity, once.
- `pg_advisory_xact_lock` on a fixed key, then if **any** row exists in `platform.platform_admin` (active or not, seeded or not): writes a `REFUSED` audit row and returns false. Otherwise inserts the enrolled admin and an `APPLIED` audit row and returns true. Same transaction.

### D6 · Rate limit

A new `SlidingWindowRateLimiter(5, 15 minutes)` instance inside the dev-tools configuration (not a bean, so it does not compete with the enquiry limiter), one fixed key. Applied **after** the gate: a request that fails the gate gets 404 and never consumes or reveals the limit. Over the limit: `429 rate-limit` with `retryAfterSeconds` and `Retry-After` (existing problem type).

### D7 · Audit and logs

- Audit in `platform.platform_admin_audit`, written by the function: kind `AUTH`, action `ADMIN_DEV_SAMPLE_CREATED` (outcome `APPLIED`, admin id and name of the new admin, summary "Sample admin created by the dev-only endpoint") or `ADMIN_DEV_SAMPLE_REFUSED` (outcome `REFUSED`, refusal code `admin-exists`, no admin id, summary "Sample admin not created: an admin already exists"); both with `ip` and `request_id`. **Never** a password, hash, secret, ciphertext or URI.
- Logs: no line contains the password, the Base32 secret (grouped or not), the `otpauth://` URI or the email. The service may log at `INFO` "Sample admin created" with the admin id and request id only. No request or response body logging on this path. Response DTO `toString()` redacted, so Spring's `DEBUG` "Writing [...]" lines print nothing secret. Exceptions carry no secret in their message; every database failure is wrapped in a message-free, cause-less exception before Spring sees it (R4), and any failure answers a bodyless `500`. The nonce appears only in the one startup `WARN` line (R1).

---

## Must-not-ship rule

**No artefact built without `-Pdev-tools` may contain any class under `com/sahaj/platform/devtools/` or any resource under `db/dev-tools/`.** Enforced by:

1. Test T13 (QA, failsafe, runs in the normal `mvn -f backend/pom.xml verify`): opens the jar just packaged in `target/` and fails if any forbidden entry exists (exact list in R9); also fails if `Class.forName("com.sahaj.platform.devtools.admin.DevSampleAdminController")` succeeds while `sahaj.devtools` is not `true`.
2. A unit test (java-developer, `DevToolsAbsentFromMainTest`) that fails if `src/main/java` contains a package `devtools` or any reference to it, and, without `sahaj.devtools=true`, if the class loader finds `com/sahaj/platform/devtools/` or `db/dev-tools/` (R9).
3. The separate build directory `target-devtools` (stale classes cannot leak).
4. Operating rule for the human: jars for any shared machine are built with `mvn -f backend/pom.xml clean package` **without** `-Pdev-tools`. A jar named `*-devtools-*` or with manifest `Sahaj-Dev-Tools: true` never leaves the developer's machine.
5. Requested (human-owned `scripts/`): `node scripts/validate.mjs backend` also runs the jar check from point 1.

---

## Tests QA must write

`backend/src/test/java/**/it/`. Condition-matrix tests may use `ApplicationContextRunner` / `WebApplicationContextRunner` (no database); end-to-end tests run in the `dev-tools` profile with `spring.profiles.active=dev,test` (later profile wins, so `test` keeps `sahaj_test`) and `server.address=127.0.0.1`.

| # | Test | Expect |
| --- | --- | --- |
| T1 | **404 in every other combination.** Turn off each of C1..C5 alone (C1: profiles `test`, `prod`, `dev,prod`, `dev,staging`; C2: absent, `false`, `yes`, `1`; C3: realm off, realm on with a secret missing (the "unavailable" case); C4: `SAHAJ_REMOTE_IP_HEADER=X-Forwarded-For`, `server.forward-headers-strategy=native`, `=framework`; C5: `server.address` absent, `0.0.0.0`, a LAN address) | 404, empty body, same headers as the production-jar 404 (ignore `Date`); no dev-tools bean (no gate, controller, chain, Flyway location); no admin row; no audit row |
| T2 | Production build (no `-Pdev-tools`), realm on, realm off, realm unavailable: `POST`, `GET`, `OPTIONS` (with CORS preflight headers) on `/api/v1/admin/dev/sample-admin`, `/api/v1/admin/dev`, `/api/v1/admin/dev/x` | 404 each, byte-identical to T1; never 401, 403, 405 or 503 |
| T3 | **Non-loopback caller**, everything else on: remote `10.0.0.5`, `192.168.1.20`, `2001:db8::1`, `::ffff:10.0.0.5` | 404 |
| T4 | **Forwarded headers ignored and refused**: remote `10.0.0.5` with `X-Forwarded-For: 127.0.0.1` (and `Forwarded: for=127.0.0.1`, `X-Real-IP: 127.0.0.1`); remote `127.0.0.1` with any one of the G3 headers | 404, no admin created |
| T5 | Browser and host checks: `Origin: http://localhost:4200`; `Sec-Fetch-Site: same-origin`; `Host: evil.example:8080`; wrong method; trailing slash; `%2F`-encoded path; upper-case path | 404 |
| T6 | **Happy path**: `127.0.0.1` and `::1`, no body | 201, `Cache-Control: no-store`; fields per contract; password 24 characters from the alphabet; `secretKey` decodes to 20 bytes; URI matches `secretKey`. Then sign in with the dev bypass **off**: `/auth/login` gives `next: TOTP`; a code computed from the secret on `/auth/totp/verify` gives 200 |
| T7 | Storage like the normal path | `password_hash` is BCrypt `$2a$12$` and matches the returned password; `totp_secret_enc` decrypts with the test key and AAD of this admin id to the returned secret, and fails with another admin id; `totp_enrolled_at` set; no recovery codes; no column anywhere holds the password, secret or URI |
| T8 | **Second call never reveals or resets**: call twice | second answers the T1 404; `password_hash`, `totp_secret_enc`, `updated_at` byte-equal before and after; one `ADMIN_DEV_SAMPLE_REFUSED` row |
| T9 | Admin already exists from the env seed, or an inactive admin exists | 404; nothing inserted; the seed admin unchanged |
| T10 | Race: 10 parallel calls | exactly one 201, nine 404, one admin row |
| T11 | **Secrets never in logs or audit**: capture all output (`OutputCaptureExtension`) with root and `org.springframework.web`, `org.springframework.security`, `org.springframework.jdbc`, Hikari and the PostgreSQL driver at `DEBUG` during T6, T8 and a forced database failure (500) | none of: the password, the secret (grouped and ungrouped), the URI, `secret=`, the email, the password hash, the ciphertext (hex or Base64), `Failing row`; the 500 has no body (R4); every `platform_admin_audit` column of the new rows is free of them |
| T12 | Rate limit: 6 calls passing the gate within 15 minutes (admin exists after the first) | calls 2..5 answer 404, call 6 answers `429 rate-limit` with `Retry-After`; 50 calls from `10.0.0.5` all answer 404, never 429 |
| T13 | **Production jar has no class of the endpoint** (must-not-ship test): after `package` without the profile | no `BOOT-INF/classes/com/sahaj/platform/devtools/**`, no `BOOT-INF/classes/db/dev-tools/**`, no entry whose name contains `DevSample`; no `Sahaj-Dev-Tools` manifest attribute; `Class.forName` of the controller fails. After a `-Pdev-tools` build, `target/classes` still has no `devtools` folder |
| T14 | Database function as `sahaj_app` | refuses when `app.tenant_id` is bound (`42501`); creates only `sample-admin@example.test`; `sahaj_app` still cannot `SELECT` any `platform_admin*` table |
| T15 | Tokens are ignored: a clinic token, an admin token or `sahaj_admin_rt` sent with a passing request | same result as without them; with the endpoint off, still 404 |

---

## Risks

| Risk | Mitigation | Residual |
| --- | --- | --- |
| A dev-tools jar runs on a shared server with the default `dev` profile and the property set: anyone on that machine owns every clinic in that database | build exclusion; separate build dir; jar name and manifest; WARN at start; C4, C5; loopback only; one shot (any admin turns it off for good) | a human copying a dev-tools jar to a server **and** passing the property there |
| A web page the developer visits calls `127.0.0.1:8080` (CSRF, DNS rebinding) | G4 Host allow-list; G5 refuses any `Origin` / `Sec-Fetch-*`; CORS off; POST only | none known for current browsers |
| A local proxy, tunnel or port forward (nginx, `ssh -R`, ngrok) makes remote callers look like loopback | G3 refuses forwarding headers; C4 refuses proxy configuration; **G7 per-start nonce** that only the console shows (R1); Angular dev proxy sends forwarding headers (`xfwd`, R1) | a forwarder that adds no header **and** a caller who has read the console. Rules in R1: never `ng serve --host 0.0.0.0`, a port proxy, a tunnel or a published container port while the flag is on |
| The flag is left on through an env var, `.env`, IDE run configuration or yml file and stays on across restarts | C2 accepts only the `commandLineArgs` property source (R2) | a saved IDE run configuration whose **program arguments** carry the flag looks like a command line; rule: never save it there |
| Other processes or users on the same machine (WSL, containers with host networking) | loopback only; one shot | accepted for a single-user dev laptop |
| The credentials end up in a terminal history, a chat or a screenshot | shown once; `no-store`; never logged | the human's handling; it is a dev database admin only |
| Stale dev classes in a production jar | `target-devtools` build directory; T13 | none known |
| The dev function and grant stay in `sahaj_dev` after a later plain build | fixed identity, refuses while any admin exists; dev and test databases only (`^sahaj_(dev|test)$`, R3); production guard callback fails every migrate of any other database that has them (R3); repeatable cleanup script | accepted in `sahaj_dev` / `sahaj_test` |
| The sample admin is a real admin of `sahaj_dev` | dev data only (no real patient data anywhere) | removed only by a human: `active = false` as `sahaj_migrator` (no delete path) |
| Default profile `dev` (open item) | the build exclusion does not depend on it | none for this endpoint |

---

## Human prerequisites, in this order

1. Approve `contract` for this ADR and `docs/api/admin.yaml` 0.3.0 (and ADR-003 rev 2, if not yet approved). Answer H-DEV-1..8 or accept the defaults.
2. Create role `sahaj_admin_definer` in `scripts/db/init.sql` and `scripts/setup/create-database.mjs` (`NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT`, `GRANT sahaj_admin_definer TO sahaj_migrator`, `USAGE` on schemas `platform` and `ipd`, ADR-003 D4), then re-run `node scripts/setup/create-database.mjs` for `sahaj_dev` and `sahaj_test`.
3. Activate the parked admin migrations (db-engineer moves `backend/src/main/resources/db/pending/admin/V2026_11_15_1000` and `_1010` into `db/migration`), with the version question answered (H-DEV-5), then `node scripts/validate.mjs db`.
4. In your own `.env` (never committed): `SAHAJ_ADMIN_ENABLED=true`, `SAHAJ_ADMIN_JWT_SECRET` (32+ characters, different from `SAHAJ_JWT_SECRET`), `SAHAJ_ADMIN_TOTP_KEY` (Base64 of 32 random bytes). Leave `SAHAJ_ADMIN_SEED_EMAIL` and `SAHAJ_ADMIN_SEED_PASSWORD` empty, or the seed creates an admin first and this endpoint answers 404. Do **not** add `sahaj.admin.dev-sample-enabled` to `.env` or `.env.example`.
5. Allow writes to `backend/src/devtools/` (H-DEV-6).
6. db-engineer writes the dev-tools callback; java-developer and QA build and test; security-reviewer runs the full review (DEC-016 condition); you approve `merge`.
7. Use: as in R12 (command-line flag only, copy the nonce from the console, `curl.exe` with `X-Sahaj-Dev-Nonce`). Copy the password and key into a password manager or authenticator app. Sign in at `http://localhost:4200/admin/sign-in`. Restart without the flag afterwards.

---

## Consequences

**Positive.** One call gives a working, already enrolled admin. The production jar carries no endpoint code. The app still connects only as `sahaj_app`. Password and TOTP handling is the same code as the seed and the enrolment. The function can create only one fixed identity, once.

**Negative / accepted.** A new build profile and source set to maintain; one small always-present filter and interface in main code; a dev/test-only database grant that widens ADR-003 D1 there; the sample admin has no recovery codes and skips the enrolment screens (use the env seed to test those); a second admin-creation path that the security review must cover.

---

## Data needed (for the db-engineer)

- **No new table, no new column, nothing in `db/migration`.** No tenant data; platform scope only. Revision 2 adds one main-code Flyway guard callback outside `db/migration` and one cleanup script (R3).
- **New dev-tools-only Flyway callback** `backend/src/devtools/resources/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql` (location `classpath:db/dev-tools`, added by a dev-tools customizer only when C1..C5 hold; runs as `sahaj_migrator` after every migrate; must be idempotent):
  0. Stop unless `current_database() ~ '^sahaj_(dev|test)$'` (exact names only, R3).
  1. Stop with a clear message if `sahaj_admin_definer` is missing, or `to_regclass('platform.platform_admin')` is null ("Admin tables are missing: activate the parked admin migrations first (DEC-016).").
  2. `GRANT INSERT (id, email, full_name, password_hash, totp_secret_enc, totp_key_version, totp_enrolled_at) ON platform.platform_admin TO sahaj_admin_definer` (other columns take their defaults; `totp_last_used_step` stays null).
  3. Temporary `CREATE` on schema `platform` for the definer (as V2026_11_15_1010 does), `CREATE OR REPLACE FUNCTION platform.admin_dev_create_sample_admin(p_admin_id uuid, p_password_hash text, p_totp_secret_enc bytea, p_totp_key_version smallint, p_ip inet, p_request_id text) RETURNS boolean`, owner `sahaj_admin_definer`, then take the `CREATE` back.
  4. `REVOKE EXECUTE … FROM PUBLIC`; `GRANT EXECUTE … TO sahaj_app`.
- **Function rules** (ADR-003 D4 rules 1, 2, 4, 7): `SECURITY DEFINER`, `SET search_path = pg_catalog, pg_temp`, schema-qualified, no `EXECUTE`. Refuse `42501` when `platform.current_tenant()` is not null. Validate `p_admin_id` not null, `p_password_hash` matches the table's BCrypt pattern, `octet_length(p_totp_secret_enc)` between 48 and 64, `p_totp_key_version >= 1` (else `22023`). `pg_advisory_xact_lock(<fixed bigint>)`. If any row exists in `platform.platform_admin`: insert audit (`AUTH`, `ADMIN_DEV_SAMPLE_REFUSED`, outcome `REFUSED`, refusal code `admin-exists`, no admin id, summary "Sample admin not created: an admin already exists", ip, request id) and return false. Else insert `(p_admin_id, 'sample-admin@example.test', 'Sample admin', p_password_hash, p_totp_secret_enc, p_totp_key_version, now())`, insert audit (`AUTH`, `ADMIN_DEV_SAMPLE_CREATED`, `APPLIED`, admin id, admin name, summary "Sample admin created by the dev-only endpoint", ip, request id), return true. Email and name are literals in the body, never parameters.
- **Grants unchanged elsewhere**: `sahaj_app` still has no privilege on any `platform_admin*` table.
- **Retention**: the admin row is kept (never deleted; `active = false` by a human); audit rows 7 years, append-only (ADR-003 D7).
- Production databases never receive this callback, grant or function (the production jar does not contain the resource).

---

## Needs human decision

| # | Question | Default used until answered |
| --- | --- | --- |
| H-DEV-1 | **Database write path.** W1 gives `sahaj_admin_definer` `INSERT` on `platform.platform_admin` and adds an admin-creating function, **in dev and test databases only**. This changes ADR-003 D1 ("no `INSERT` for the definer") there. W2 (migrator connection on a request path) and W3 (Flyway from a request) are the alternatives. | W1 |
| H-DEV-2 | **404 or 409 when an admin already exists.** DEC-016 says 404 (same as "off"). The tech lead's brief allowed 409. A 409 (`sample-admin-exists`, "An admin already exists. This endpoint creates the first admin only.") would be clearer for a developer and is reachable only after every gate has passed. | 404, as DEC-016 |
| H-DEV-3 | **Recovery codes for the sample admin.** It is created enrolled, with no recovery codes, so "Use a recovery code" cannot be tested with it. Option: also generate 10 recovery codes and return them once. | none |
| H-DEV-4 | **Property set outside `dev`.** Now: 404 (the app starts). Alternative: refuse to start, like the dev bypass guard. That guard would have to live in main code, because the production jar has no dev-tools class. | 404 and the app starts |
| H-DEV-5 | **Parked migration versions.** `V2026_11_15_*` are lower than migrations already applied in `sahaj_dev` (up to `V2026_11_26_1000`). Flyway refuses an older unapplied version by default ("Detected resolved migration not applied"). Either the db-engineer renumbers them above the latest version (recommended, the files have never run), or dev and test run with `outOfOrder=true`. The README in `db/pending/admin` says "keep their versions". | renumber (db-engineer and human to confirm) |
| H-DEV-6 | **Ownership of `backend/src/devtools/`.** The ownership table gives `backend/` (everything else) to java-developer. The SQL callback there is db-engineer work. Allow the db-engineer to write `backend/src/devtools/resources/db/dev-tools/`, as was asked for `db/dev-seed/`. | java-developer owns the source set; db-engineer writes only the SQL file, with your permission |
| H-DEV-7 | **Build plugin and ignore file.** `build-helper-maven-plugin` is new to the pom (build time only, version pinned 3.6.1, R11). `backend/target-devtools/` must be added to `.gitignore` (review L7, human-owned file). | accepted |
| H-DEV-8 | **Jar check in the gate.** Add the T13 jar check to `node scripts/validate.mjs backend` (`scripts/` is human-owned). | not added; T13 runs in `mvn verify` |

---

## Handoff · DEC-016 (ADM-1 test support) · solution-architect → db-engineer
Status: NEEDS DECISION (human `contract` approval; H-DEV-1..8; human prerequisites 2 to 5 above)
Mockup screen: none (developer tool, no UI; DEC-016 option B)
Files changed:
- `docs/architecture/adr/ADR-008-dev-sample-admin.md`: new.
- `docs/architecture/adr/ADR-003-sahaj-admin-portal.md`: pointer to ADR-008 in the header and in D5.
- `docs/api/admin.yaml`: 0.3.0, `POST /dev/sample-admin` (`x-dev-only: true`), `DevSampleAdmin`, `DevNotFound`.
Validation: not run (no shell in this session).

---

## Review fixes (revision 2, 5 Nov 2026)

Source: `docs/reviews/DEC-016-security.md` (PASS with conditions; tech lead decisions in its table). The D-sections above already point here. Where this section and an earlier line differ, this section wins.

| # | Review item | Fix | Owner |
| --- | --- | --- | --- |
| R1 | M1 forwarder without headers | Per-start nonce (G7), `xfwd` on the Angular dev proxy, operating rules | java-developer, angular-developer |
| R2 | M3 flag from any property source | C2 reads only the `commandLineArgs` property source | java-developer |
| R3 | L1 name guard, L2 dormant function and grant | `^sahaj_(dev|test)$`; main-code guard callback; cleanup script | db-engineer, java-developer |
| R4 | L3 database failure carries row data | Message-free, cause-less exception; bodyless 500 | java-developer |
| R5 | L4 missing forwarding headers | Eight more G3 headers | java-developer |
| R6 | L5 cloud platform turns on forward headers | C4 adds cloud-platform checks | java-developer |
| R7 | L6 dev-tools jar under production coordinates | Skip install and deploy; image name override | java-developer |
| R8 | I4 bodyless 500 not in contract | `DevServerError` in `admin.yaml` 0.3.1 | solution-architect (done) |
| R9 | M2 must-not-ship not checked on an artefact | T13 on the packaged jar; extended unit test; new tests | qa-engineer, java-developer |
| R10 | M4, L7, H-DEV-8 | Stay with the human | human |
| R11 | I5 plugin version | Keep pinned 3.6.1, reason recorded | none (pom unchanged) |
| R12 | Usage note | Rewritten | human (operating rule) |

### R1 · Per-start nonce (M1)

**Why.** G2 to G4 trust that a loopback caller with a local `Host` is the developer. A forwarder that adds no header breaks that: `ng serve --host 0.0.0.0` with the current proxy, `netsh interface portproxy`, `ssh -R`, a bare nginx `proxy_pass`, rootless Docker or Podman port publishing. The remote caller then arrives from `127.0.0.1`, with `Host: localhost`, and no forwarding header. A secret that only the developer's console shows closes this whole class: the remote caller cannot read the console.

**Decision.**
- New dev-tools class `DevSampleAdminNonce` (package `com.sahaj.platform.devtools.admin`, package-private, final), created **once** by the dev-tools configuration, only when C1 to C5 hold.
- Value: 16 bytes (128 bits) from `new SecureRandom().nextBytes(...)`, encoded as **32 lower-case hex characters** (`java.util.HexFormat`, Java 17). Kept in memory only, in the gate (as the US-ASCII bytes of the hex text).
- **Printed once**: the startup `WARN` of D2, which becomes exactly: `Dev-only sample admin endpoint is on (dev-tools build). POST /api/v1/admin/dev/sample-admin from this machine creates an admin once. Send header X-Sahaj-Dev-Nonce: <nonce>. The value changes at every start.` That line carries the nonce and no other secret (no email, no password, no key, no URL with a secret).
- **Required**: new gate check G7, evaluated inside `permits` after G1 to G5: exactly one `X-Sahaj-Dev-Nonce` header (zero or two or more values refuse); its value must be US-ASCII and equal to the nonce, compared with `MessageDigest.isEqual` on the bytes (constant time for equal length; the length, 32, is public). No trimming beyond the container's, no case folding (the console prints lower case).
- **No oracle**: a missing, malformed or wrong nonce gives the same empty 404 (`DevNotFound`) as every other refusal, from the same place (the main-code reserved-path filter). It is not counted by the rate limiter (D6 runs after the gate).
- **Never anywhere else**: regenerated at every application context start (a restart, including a spring-boot-devtools restart, makes a new one and prints a new line); never persisted (no file, no database, no `.env`); not a Spring property, property source, system property, MDC entry or JMX/actuator value (so `/env`-style views cannot show it); never in a response body or header, audit row, exception message or other log line. `DevSampleAdminNonce.toString()` and the gate's `toString()` print no value. The header is never logged (this path already logs no headers; no header logging may be added for it).

**Angular dev proxy (angular-developer, `frontend/proxy.conf.json`).** Add `"xfwd": true` to the `/api` entry. The dev server then adds `X-Forwarded-For`, `X-Forwarded-Port` and `X-Forwarded-Proto` to every proxied request, so G3 refuses anything that comes through port 4200, even with the right nonce. Do **not** add `changeOrigin`: it rewrites `Host` to `localhost:8080`, which passes G4. Main code is unaffected: in dev `SAHAJ_REMOTE_IP_HEADER` is blank, so `ClientKey`, `AdminNetworkFilter` and Tomcat keep using the socket address and never read these headers. The developer always calls the endpoint directly on `127.0.0.1:8080`.

**Operating rules (risk, also in R12).** While the flag is on, never: `ng serve --host 0.0.0.0` (or any non-loopback `--host`), a port proxy (`netsh interface portproxy`, `socat`, a bare nginx `proxy_pass`), a tunnel (`ssh -R`, ngrok, cloudflared, VS Code or dev-tunnels port forwarding), or a published container port (`docker run -p` / `podman run -p` for 8080 or 4200). The nonce makes these much safer, not safe: a person who can see the developer's screen or console log can still use it.

### R2 · Command line only (M3)

- `ConditionalOnDevSampleAdmin` drops `@ConditionalOnProperty` for C2 and uses a new `DevSampleAdminConditions.FlagOnCommandLine` `Condition`:
  1. the environment is a `ConfigurableEnvironment`, else false;
  2. `getPropertySources().get(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME)` (`"commandLineArgs"`) exists, else false;
  3. its **raw** `getProperty("sahaj.admin.dev-sample-enabled")` is a `String` equal to `true` ignoring case, else false. No relaxed-binding variants (`devSampleEnabled`, `DEV_SAMPLE_ENABLED`), no placeholder resolution (`--sahaj.admin.dev-sample-enabled=${X}` is false), an empty value is false.
- Ignored, so the endpoint stays absent: environment variables (including `.env` loaded into the environment), `SPRING_APPLICATION_JSON` / `spring.application.json`, system properties (`-D...`, `JAVA_TOOL_OPTIONS`, `-Dspring-boot.run.jvmArguments`, IDE VM options), `application*.yml` / `.properties` in any location, `spring.config.import` and `spring.config.additional-location` files, test property sources.
- **No system-property exception for tests** (the review allowed one; not taken). A system property is exactly how IDE VM options and `JAVA_TOOL_OPTIONS` set it, so a test-only exception would reopen M3. Tests use real arguments instead: `@SpringBootTest(args = "--sahaj.admin.dev-sample-enabled=true")` creates the `commandLineArgs` source; context-runner tests add `new SimpleCommandLinePropertySource("--sahaj.admin.dev-sample-enabled=true")` (default name `commandLineArgs`) with an initializer.
- Ways to pass it that work: `spring-boot:run "-Dspring-boot.run.arguments=--sahaj.admin.dev-sample-enabled=true"` (program arguments) or `java -jar <dev-tools jar> --sahaj.admin.dev-sample-enabled=true`.
- Known limit: a saved IDE run configuration that puts the flag in **program arguments** is indistinguishable from a command line. Rule: never save it in a run configuration. H-DEV-4 (refuse to start outside dev) keeps its default.

### R3 · Database guards (L1, L2)

**L1.** The dev-tools callback's pre-flight uses `current_database() !~ '^sahaj_(dev|test)$'` (exact names; `sahaj_dev_staging`, `sahaj_test_prod`, `sahaj_dev_1` refuse). db-engineer edits `backend/src/devtools/resources/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql` line 21 (permission H-DEV-6).

**L2. Chosen mechanism: a main-code Flyway SQL callback that runs after every migrate, in every build and profile.**
- File `backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql`; `application.yml` becomes `spring.flyway.locations: classpath:db/migration,classpath:db/guard` (java-developer). Customizers that add `db/dev-seed` or `db/dev-tools` must **append** to the configured locations, never replace them.
- Runs as `sahaj_migrator`. In `sahaj_dev` and `sahaj_test` it does nothing. In any other database (`current_database() !~ '^sahaj_(dev|test)$'`) it raises, failing the migrate and so the app start, when **any** of these holds (each check skipped when its object does not exist, so a fresh database or one without the admin tables passes):
  1. `to_regprocedure('platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text)') is not null`;
  2. role `sahaj_admin_definer` exists, `platform.platform_admin` exists, and `has_any_column_privilege('sahaj_admin_definer', 'platform.platform_admin', 'INSERT')` (covers table and column grants);
  3. `platform.platform_admin` has an **active** row with email `sample-admin@example.test` (a database cloned from `sahaj_dev`).
- Message (value-free apart from the database name): `Dev-tools sample admin objects found in database "<name>". Run docs/database/runbooks/dev-tools-sample-admin-cleanup.sql as sahaj_migrator, then start again (ADR-008 R3).`
- It never drops or revokes by itself: a migrate that silently changes privileges in a non-dev database is worse than a stop the human sees.
- Not chosen: a versioned `V` migration (runs once, so it misses a database restored or renamed from `sahaj_dev` later); an `R__` repeatable migration (reruns only when its checksum changes; forcing it with `${flyway:timestamp}` writes a history row at every start); an `afterMigrate__` file inside `db/migration` (Flyway would run it, but `validate db` accepts only `V`/`R__` names there); a check in `validate db` alone (`scripts/` is human-owned and reads files, not databases; the human may add one later, R10).

**Cleanup script (db-engineer):** `docs/database/runbooks/dev-tools-sample-admin-cleanup.sql`, plain SQL, one transaction, idempotent (safe to run any number of times), run by the human: `psql -v ON_ERROR_STOP=1 -U sahaj_migrator -d <database> -f docs/database/runbooks/dev-tools-sample-admin-cleanup.sql`.
1. `DROP FUNCTION IF EXISTS platform.admin_dev_create_sample_admin(uuid,text,bytea,smallint,inet,text);`
2. When the role and table exist: `REVOKE INSERT ON platform.platform_admin FROM sahaj_admin_definer;` (also removes the column grants; ADR-003 D1 state: no `INSERT` for the definer).
3. Only when the database is not `sahaj_dev` / `sahaj_test` and the table exists: set `active = false` on `sample-admin@example.test` (never delete; retention), revoke its open admin sessions, and write one append-only admin audit row for it if the audit action list allows (if it needs a new action code, the db-engineer adds it to the parked admin migration before activation and says so in the design note).
4. Self-check: raise if the function still exists or the definer still has any `INSERT` on `platform.platform_admin`.
In `sahaj_dev` it may be run after each use; the next dev-tools start recreates the function and grant.

### R4 · Database failures carry nothing (L3)

- `JdbcDevSampleAdminRepository.createSampleAdmin` catches every `RuntimeException` (all `DataAccessException`s included). SQLSTATE `23505` still becomes `DevSampleAdminUnavailableException` (404). Everything else becomes a new `DevSampleAdminFailedException`: **no message, no cause, no suppressed exceptions** (`super(null, null, false, false)`); it may keep only the 5-character SQLSTATE as a field, and its `toString()` prints the class name and that SQLSTATE only.
- The controller also wraps anything else escaping `service.create` (for example a commit-time `TransactionSystemException` from the `@Transactional` proxy, or a cipher failure) in the same exception **before** Spring's exception resolvers see it, so their `DEBUG` "Resolved [...]" line prints nothing but the class name.
- Answer: bodyless `500` (`DevServerError` in the contract): no body, no `Content-Type`, `Cache-Control: no-store`. One `ERROR` log line with the exception class and SQLSTATE only.

### R5 · G3 headers (L4)

G3 (D3 table) now refuses on the presence of any of 16 headers, compared by name case-insensitively: `Forwarded`, `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, `X-Forwarded-Prefix`, `X-Forwarded-Server`, `X-Original-Forwarded-For`, `X-Real-IP`, `X-Client-IP`, `Client-IP`, `True-Client-IP`, `CF-Connecting-IP`, `Fastly-Client-IP`, `X-Cluster-Client-IP`, `Via`. Values are never read.

### R6 · Cloud platform (L5)

C4 also needs `spring.main.cloud-platform` to be blank (any value, `none` included, makes C4 false: simplest fail-closed rule) **and** `CloudPlatform.getActive(environment)` to be null (no platform detected, for example no `KUBERNETES_SERVICE_HOST` / `KUBERNETES_SERVICE_PORT`). `server.tomcat.remoteip.protocol-header` blank stays (already implemented).

### R7 · Dev-tools artefacts never under production names (L6)

In the `dev-tools` profile of `backend/pom.xml` (java-developer):
- `<properties>` `maven.install.skip=true` and `maven.deploy.skip=true`: nothing built with the profile reaches `~/.m2` or a remote repository.
- `spring-boot-maven-plugin` `<configuration><image><name>localhost/sahaj-backend-devtools:${project.version}</name><publish>false</publish></image></configuration>`: `spring-boot:build-image` never uses the production image name, and the `localhost/` prefix keeps a stray `docker push` away from a public registry.
- Not chosen: a `devtools` classifier on `repackage` alone. The repackaged jar would be attached under a classifier, but the plain jar (which also holds the dev classes) would still install under the production coordinates. Skipping install and deploy closes both.

### R8 · Contract (I4)

`docs/api/admin.yaml` 0.3.1: `X-Sahaj-Dev-Nonce` header parameter (required, `^[0-9a-f]{32}$`), updated `x-runtime-conditions` (C2, C4) and `x-request-checks` (G3, G7), `500` → `DevServerError` (bodyless). `security: []` unchanged.

### R9 · Tests (M2 and the fixes)

**T13, replaced (QA, failsafe IT, for example `backend/src/test/java/com/sahaj/platform/it/PackagedJarDevToolsIT.java`).**
- The jar path comes from a failsafe system property `sahaj.packagedJar=${project.build.directory}/${project.build.finalName}.jar` (java-developer adds it to the failsafe `systemPropertyVariables` of the main build; the dev-tools profile inherits it with its own directory and name). The IT **fails** if the property is missing or the file does not exist (never passes vacuously). Opened with `java.util.jar.JarFile`, top-level entries (nested `BOOT-INF/lib/*.jar` are third-party and not opened).
- Normal build (`sahaj.devtools` not `true`): fail on any entry whose name
  1. starts with `BOOT-INF/classes/com/sahaj/platform/devtools/`;
  2. starts with `BOOT-INF/classes/db/dev-tools/`;
  3. starts with `com/sahaj/platform/devtools/` or `db/dev-tools/` (a jar that was not repackaged);
  4. contains `DevSample`;
  5. ends with `afterMigrate__dev_tools_sample_admin.sql`;
  6. starts with `BOOT-INF/classes/` and contains `/devtools/` (case-insensitive);
  and fail if the main manifest has a `Sahaj-Dev-Tools` attribute, or the file name contains `-devtools-`. Also assert the production jar **does** contain `BOOT-INF/classes/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql` (R3).
- Dev-tools build (`sahaj.devtools=true`), positive control proving the scan works: the jar contains `BOOT-INF/classes/com/sahaj/platform/devtools/admin/DevSampleAdminController.class`, `BOOT-INF/classes/db/dev-tools/afterMigrate__dev_tools_sample_admin.sql` and manifest `Sahaj-Dev-Tools: true`; and `${project.basedir}/target/classes/com/sahaj/platform/devtools` does not exist.
- Known limit: `-DskipTests` / `-DskipITs` skip it. That is why H-DEV-8 (the same check in `node scripts/validate.mjs backend`) stays a **recommendation to the human**.

**Extended unit test (java-developer, `DevToolsAbsentFromMainTest`).** When `System.getProperty("sahaj.devtools")` is not `true`: the context class loader's `getResource("com/sahaj/platform/devtools/")`, `getResource("db/dev-tools/")` and `getResource("db/dev-tools/afterMigrate__dev_tools_sample_admin.sql")` are all null (the file lookup is exact where directory lookups vary by class loader). When it is `true`, the file is found.

**Extensions and new tests (QA).**

| # | Test | Expect |
| --- | --- | --- |
| T1+ | C2: flag via environment variable `SAHAJ_ADMIN_DEV_SAMPLE_ENABLED=true`, system property, `SPRING_APPLICATION_JSON`, a yml / map property source, `--spring.config.additional-location` file; command-line `--sahaj.admin.dev-sample-enabled=`, `=yes`, `=${X}` | no dev-tools bean, 404. Command-line `=true` and `=TRUE`: beans present |
| T1+ | C4: `spring.main.cloud-platform=kubernetes`, `=none`, `=heroku`; `KUBERNETES_SERVICE_HOST` and `KUBERNETES_SERVICE_PORT` set | no dev-tools bean, 404 |
| T4+ | Each of the eight new G3 headers alone, in upper and lower case, with the right nonce | 404 |
| T11+ | Forced database failure (as migrator, `REVOKE EXECUTE` on the function from `sahaj_app` for the test, then grant back); unit test with a `JdbcClient` that throws a `DataIntegrityViolationException` whose message holds the email | bodyless 500; the thrown exception has null message and null cause; captured output at `DEBUG` has no email, hash, ciphertext or `Failing row` |
| T16 | Nonce: no header; empty; 31 or 33 characters; a wrong 32-hex value; the right value upper-cased; the right value twice (two headers); the right value with any other gate check failing | 404, byte-identical to T1; 50 such calls then a correct one gives 201 (not counted). Correct value: 201 |
| T17 | Nonce handling: capture start-up and T6 output | the `WARN` line appears once and holds exactly one 32-hex value; two context starts give different values; the value occurs in no other output line, response, audit column or property source value (iterate every `EnumerablePropertySource`) |
| T18 | Guard callback (R3) in `sahaj_test` | runs clean with the dev objects present. The same SQL text with `current_database()` replaced by the literal `'sahaj_staging'` (test-only substitution) raises when only the function exists, when only the column grant exists, when only an active sample admin exists; does not raise when none exist. The dev-tools callback pre-flight with `'sahaj_dev_staging'` and `'sahaj_test_prod'` raises (L1) |
| T19 | Cleanup script in `sahaj_test` | first run removes the function and every definer `INSERT` privilege; second run changes nothing and passes; a later dev-tools migrate recreates them |
| M-1 | Manual (test plan): `mvn -f backend/pom.xml -Pdev-tools install` | no new artefact under `~/.m2/repository/com/sahaj/`; with Docker available, `-Pdev-tools spring-boot:build-image` names the image `localhost/sahaj-backend-devtools:<version>` |
| M-2 | Manual (test plan): backend with the flag on, `ng serve` with the new proxy | `POST http://localhost:4200/api/v1/admin/dev/sample-admin` with the right nonce: 404; the same call on `127.0.0.1:8080`: 201 |

### R10 · Stays with the human

- **M4**: add the `contract` approval line for ADR-008 revision 2 and `docs/api/admin.yaml` 0.3.1; mark ADR-008 accepted; do prerequisites 2 to 5.
- **H-DEV-8**: add the T13 jar check to `node scripts/validate.mjs backend` (recommended; `-DskipTests` skips T13). Optionally also a database check of the R3 guard conditions there.
- **L7**: add `backend/target-devtools/` to `.gitignore`.
- **H-DEV-9 (new)**: ownership of `backend/src/main/resources/db/guard/`. It is under `backend/` (java-developer). Default: as H-DEV-6, the db-engineer writes the SQL file there with your permission and the java-developer adds the Flyway location; without permission the db-engineer gives the SQL in the design note and the java-developer places it.

### R11 · Plugin version (I5)

Keep `build-helper-maven-plugin` **pinned at 3.6.1** in the profile (the pom is unchanged; the earlier "managed by the Spring Boot parent" line is corrected). Reason: this plugin decides whether `src/devtools` is compiled and packed, so it is part of the must-not-ship control. Its behaviour should change only through a reviewed pom edit, not silently with a Spring Boot parent upgrade. T13 (negative and positive control) catches a regression either way. Review the pin at each Spring Boot upgrade.

### R12 · Usage note (replaces prerequisite 7 and the D2 run line)

1. Start with the flag **as a command-line argument**, nowhere else:
   `mvn -f backend/pom.xml -Pdev-tools spring-boot:run "-Dspring-boot.run.arguments=--sahaj.admin.dev-sample-enabled=true"`
   (or `java -jar backend/target-devtools/sahaj-backend-devtools-<version>.jar --sahaj.admin.dev-sample-enabled=true`). An environment variable, `.env`, `SPRING_APPLICATION_JSON`, `-D`, an IDE run configuration or any `application-*.yml` is ignored: the endpoint stays absent.
2. In the console, find the one `WARN` line `... Send header X-Sahaj-Dev-Nonce: <value>. The value changes at every start.` and copy the 32 characters.
3. On the same machine, straight to port 8080 (never through 4200):
   `curl.exe -s -X POST -H "X-Sahaj-Dev-Nonce: <value>" http://127.0.0.1:8080/api/v1/admin/dev/sample-admin`
4. A 404 never says why. Check: the flag is on the command line, the nonce is from this start, no admin exists yet, no proxy, tunnel or forwarding header.
5. Copy the password and key into a password manager or authenticator app, sign in at `http://localhost:4200/admin/sign-in`, then stop the app and start it again **without** the flag.
6. While the flag is on: no `ng serve --host 0.0.0.0`, no port proxy, no tunnel, no published container port (R1).

## Handoff · DEC-016 (ADM-1 test support) · solution-architect → db-engineer
Status: NEEDS DECISION (human `contract` approval of revision 2 / 0.3.1, M4; H-DEV-9 new; H-DEV-1..8 unchanged)
Mockup screen: none (developer tool, no UI; DEC-016 option B)
Files changed:
- `docs/architecture/adr/ADR-008-dev-sample-admin.md`: revision 2, "Review fixes" R1 to R12; D2 (C2, C4, WARN line, run line, plugin version), D3 (G3, new G7), D7, must-not-ship rule 1 (T9 → T13) and 2, T11, risks, prerequisite 7, Data needed and H-DEV-7 updated.
- `docs/api/admin.yaml`: 0.3.1, nonce header, C2/C4/G3/G7 text, bodyless 500 `DevServerError`.
Validation: not run (no shell in this session); YAML parse check not run.
Decisions made: nonce 32 lower-case hex, header `X-Sahaj-Dev-Nonce`, G7; C2 from `commandLineArgs` only with no system-property exception; L2 by a main-code `afterMigrate` guard in `classpath:db/guard` plus a cleanup script in `docs/database/runbooks/`; L6 by skipping install/deploy plus `localhost/` image name (no classifier); plugin stays pinned.
Open questions / requests for other owners: db-engineer R3; java-developer R1, R2, R4 to R7, R9 unit test and failsafe property; angular-developer R1 `xfwd`; QA R9; human R10.
