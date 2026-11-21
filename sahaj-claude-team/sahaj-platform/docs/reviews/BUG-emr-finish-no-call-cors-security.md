# Security review · BUG-emr-finish-no-call · CORS allows Idempotency-Key

Date: 19 Nov 2026 · Reviewer: security-reviewer (read-only) · Saved by: tech lead
Scope: SecurityConfig.java:126-127 (adds "Idempotency-Key" to the CORS allowed request headers), the new CorsPreflightTest.java.

**Verdict: PASS WITH FINDINGS. No Critical, High or Medium.**

## Checks
- **Wildcards: pass.** One named header is added. There is no `*` for headers, origins or methods. Methods, exposed headers, max-age and credentials are unchanged.
- **Credentials with explicit origins only: pass.** `setAllowedOrigins` matches exactly (blank entries filtered) with `allowCredentials(true)`.
- **Origins per profile: pass.**
  - The default is empty.
  - Only application-dev.yml:28-30 sets origins (localhost:4200, 127.0.0.1:4200).
  - There is no prod or pilot profile, and .env.example has no CORS variable.
  - Outside dev, every cross-origin call is refused.
- **Admin chain: pass, for information.** AdminSecurityConfig.java:57 shares the same `/api/**` source. No admin endpoint reads the header, so the impact is nil.
- **Idempotency-Key handling: pass.**
  - It is checked against `^[A-Za-z0-9_-]{8,64}$` everywhere it is used.
  - It is scoped to tenant and record, with a hash of the request body.
  - The same key with a different body gives 409.
  - It is never logged (AccessLogFilter writes no headers).
- **The test refuses a bad origin: yes.** `http://evil.example` gets 403 with no ACAO or ACAH. A header the app never sends (x-tenant-id) is refused, and `*` is never echoed back.

## Findings
| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| 1 | Low | CorsPreflightTest.java:30 | Nothing tests the default (no property): localhost:4200 should then be refused. | Add a test with no property that expects 403. | java-developer |
| 2 | Low | CorsPreflightTest.java:112-121 | The case where an extra header is asked for along with allowed ones does not check the status. | Add `status().isOk()`. | java-developer |
| 3 | Low (existing) | SecurityConfig.java:122-124 | Origins are not checked at startup: `*` gives 500s; a plain http LAN origin is accepted. | Outside dev, refuse `*` and non-https origins at startup. | java-developer (backlog, go-live hardening) |

## Notes
- The preflight max-age is 3600 s. Browsers must hard-reload after the server restart.
- `node scripts/validate.mjs backend` must pass before merge. Not run yet because another session is running ITs.
