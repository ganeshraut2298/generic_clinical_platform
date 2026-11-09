# Security re-review · DEC-025 audit fix pass (SAH-35, SAH-36, SAH-38)

Reviewer: security-reviewer, read-only. Date: 8 Nov 2026. Scope: the files named in the audit "Fixes made" table only.

**Verdict: PASS WITH CONDITIONS.** No Critical or High findings.

| # | Sev | File:line | Finding | Fix | Owner | Tech lead decision |
|---|---|---|---|---|---|---|
| 1 | Medium | web/admin/AdminSecurityConfig.java:54-69 | The admin chain (`/api/v1/admin/**`, `@Order(1)`) has neither AccessLogFilter nor RequestBodySizeLimitFilter. Its log context also runs before the security context is set. The realm is off by default (`SAHAJ_ADMIN_ENABLED=false`). | Add both filters to the admin chain. | java-developer | FIX now (audit fix pass) |
| 2 | Medium | infrastructure/security/AccessLogFilter.java:50 | The access line has no source IP. CERT-In tracing of 401/403/413 bursts and failed OTP sign-ins is weaker without it. | Add `remoteAddr` (proxy-safe through Tomcat remoteip). | java-developer / human | HUMAN: IP under DPDP (security purpose) plus 180-day retention |
| 3 | Low | web/security/SecurityConfig.java:163-164 | A chunked body sent with no session is read up to 64 KB before the 401, instead of the 4 KB public cap. | Use the 4 KB cap when there is no principal; add an IT for it. | java-developer, qa-engineer | FIX now |
| 4 | Low | web/ProblemExceptionHandler.java:405-432 | `primaryDbMessage` can miss unquoted values that follow a colon in some pgjdbc wordings. | Cut at the first `:` after the severity prefix; add a unit case. | java-developer | FIX now |
| 5 | Low | ipd/emergency/emergency.component.ts:127-131; ipd/pharmacist/pharmacist.store.ts:78 | A 403 on refresh keeps stale names on screen in Emergency and Pharmacist. These are outside SAH-35/36/38. | Same 403 handling as G-2. | angular-developer | FOLLOW-UP (backlog, separate story) |
| 6-10 | Info | RequestBodySizeLimitFilter, AccessLogFilter / RequestLogContextFilter, SkippedOrderWarnings, give-sheet PIN, RELOAD_PAGE_FIELDS | All verified: no bypass, no PII or log injection, memory bounded, the PIN is never kept or sent, fixed messages. S36-1 compensating control closed. Optional: clear the PIN when the browser pre-check refuses. | none | — | Optional PIN clear: include if cheap |

How well the ITs cover the controls:
- ApiAccessLogIT, RequestBodyLimitIT and ReleaseAuditHardeningIT prove the controls.
- Remaining gaps are a no-session chunked case (#3), admin realm coverage (#1) and an IP field (#2).
