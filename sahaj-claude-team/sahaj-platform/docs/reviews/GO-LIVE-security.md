# GO-LIVE · Security and operations audit (whole application, 17 Nov 2026)

Reviewer: security-reviewer (read-only; `npm audit --omit=dev` 0 vulnerabilities; no Maven, no server calls, `.env` and `.env.example` not read). Saved by the tech lead. Context: DEC-058.

**Verdict: FAIL for real patient data.** No production configuration exists (only `dev` starts, and it accepts any 6-digit code for any mobile), and no one-time code can be delivered outside dev.

**Tech lead note:** finding #11 (commit 5151b7e pushed to a GitHub remote) could not be verified by the tech lead: the git remote check was denied by the permission settings. The human checks it.

## Findings
| # | Severity | Area | Finding | Fix | Owner |
|---|---|---|---|---|---|
| 1 | Blocker | Profiles | Default profile `dev` (`application.yml:28`); only dev and test define a datasource. `dev` turns on the OTP bypass for any mobile incl. new-clinic sign-up (`application-dev.yml:34`), owner IPD trial (`:24`), non-Secure refresh cookie (`:30`), admin authenticator bypass (`:42`), `LogOtpSender`. "Loopback only" is just `server.address` (`:9`), overridable by `SERVER_ADDRESS` or a same-host proxy; `OtpService.java:58-60,87-89` never checks the caller address (unlike `AdminDevBypass.java:22`). | `application-prod.yml` (datasource from env, no bypass, Secure cookie, explicit CORS); fail at start with no profile; bypass only for loopback callers. | java, human |
| 2 | Blocker | Login | Only `OtpSender` is `LogOtpSender` `@Profile({"dev","test"})` (`LogOtpSender.java:18-20`). Production sign-in impossible. Notifications are Later. | Human picks an SMS provider (TRAI DLT sender and template registration); story, contract, `SmsOtpSender`. | human → architect, java |
| 3 | Blocker (with #2) | Login | OTP limits bypassable: (a) failed count written as an absolute value, no row lock (`JdbcOtpChallengeRepository.java:18-28`, `OtpService.java:91-106`); (b) a new challenge after 30 s resets the count (`OtpService.java:62-75`); (c) `markConsumed` not conditional; (d) no per-IP/daily request cap (SMS pumping); (e) no `purpose` column (SAH-31 S5). | `for update`, `failed_count = failed_count + 1`, `consumed_at is null`; durable daily per-mobile failure count; per-IP and per-mobile caps; `purpose` column. | java, db |
| 4 | Blocker | Hosting | No proxy, TLS, Dockerfile, service unit or hosting doc. `init.sql:16-19` only makes `sahaj_dev`/`sahaj_test`; dev-tools guard trusts those names. | Production runbook: India region, TLS, separate prod DB name, roles NOBYPASSRLS, jar without `-Pdev-tools`. | human/ops |
| 5 | Blocker | Backups | None in repo; public pages promise "daily backups kept for 30 days" (`faq.component.ts:18`, `trust.component.ts:31`, `data.page.html:141`). | Encrypted nightly backups + WAL/PITR in India, one restore drill written up; or change the texts. | human/ops; angular if texts change |
| 6 | Blocker | CERT-In | No source IP in access log (`AccessLogFilter.java:54-57`); console logs only (`application.yml:68-72`); no retention, NTP, incident procedure. | Client IP via Tomcat remoteip; ship logs in India 180 days; NTP; incident contact. | java, human/ops |
| 7 | Blocker (IPD) | Module switch | IPD only via the owner's 30-day trial (`ModulesService.java:76-90`, off by default `application.yml:99`); read-only after day 30 (`ModuleEntitlements.java:16-17`); admin migrations parked. | Audited runbook setting `ipd_state='on'` per tenant, or activate the admin realm. | human, db |
| 8 | Blocker (IPD) | Secrets | Missing `SAHAJ_PIN_PEPPER` → every PIN 503 (`application.yml:89-95`); no rotation runbook (SAH-31 S12). | Set it (≥ 32 random bytes, Base64, distinct from JWT secret); runbook. | human/ops |
| 9 | Blocker (sign-off) | Clinical | Open: EWS bands (OQ-30-6); allergy list and catalogue (OQ-31-1/4); OWNER may order with no doctor profile (`MedicineRules.java:39`, SAH-31 S6); no-second-PIN ward procedure (OQ-38-5); old "wristband scanned" events note (OQ-38-6); empty `ipd.drug_code` + override (SAH-76 M1, being fixed per DEC-057). | Written sign-off or acceptance (DEC-058). | human; java for S6, M1 |
| 10 | Blocker | DPDP | No patient or staff privacy notice (consent versions stored, `AuthService.java:108-111`). | Publish: purposes, retention, grievance officer, emergency lawful basis. | human |
| 11 | High | Git | Reported: commit 5151b7e (real owner mobile, DEC-054 M2) is the tip of `origin/feature/SAH-39-home-kinetic-background`; `backend/target-devtools/` tracked and pushed (`.gitignore:2`). Unverified by tech lead. | Rewrite history, force-push, check repo visibility, purge cache; ignore and untrack `target-devtools`; correct CLAUDE.md "no GitHub". | human |
| 12 | High | Sessions | Refresh token slides 30 days (`AuthService.java:31,84`); no absolute lifetime, idle timeout or idle screen lock on ward devices. | ~12 h absolute, 15–30 min idle (server); shell idle lock. | java, angular, human (values) |
| 13 | High | Rate limits | HARD-2 unscheduled; PIN limits in memory (`InMemoryStaffPinLimits.java:24-35`); no per-user write limit; break-glass uncapped. | Ship HARD-2 or written acceptance for one instance. | java, human |
| 14 | Medium | Separation of duties | SAH-76 M2 (DEC-057 item 2, being fixed). | — | java, db |
| 15 | Medium | DPDP register | Unregistered exceptions: ward list allergy/EWS/diagnosis without access row; emergency picker directory; admission-request diagnosis to FRONT_DESK (SAH-51 M3); reprint profile block (SAH-51 M2); staff device id; emergency case retention; allergy text in audit (SAH-31 S2c). | Register entries; human decides M2/M3. | human |
| 16 | Medium | Database | HARD-1 single-column cross-tenant FKs incl. money path (`billing.bill.visit_id`, `bill_item.bill_id`, `payment.bill_id`, `opd.visit.patient_id`, `opd.appointment.patient_id`). | Composite keys, money path first (NOT VALID + VALIDATE). | db, qa |
| 17 | Medium | Flyway | Versions up to `V2026_12_15_1000` (future); a hotfix dated today sorts below and fails validation. | Rule: new versions above the latest applied (`V2026_12_16_*`+). | db |
| 18 | Medium | Headers | No CSP; HSTS needs the protocol header (`application.yml:15-22`). | CSP, HSTS, `X-Forwarded-Proto` + Tomcat `protocol-header`. | human/ops, java |
| 19 | Medium | Gate | DEC-003 secret scan not in `scripts/validate.mjs`. | Add it. | human |
| 20 | Low | Tenancy | Anyone past OTP can create a clinic; no suspend console. | Accept or gate; suspend runbook. | human |
| 21 | Low | Login | 5 wrong codes lock a doctor out 15 min (`OtpService.java:100-105`). | Accept or lock per (mobile, IP). | java |
| 22 | Low | Config | Must stay off: `SAHAJ_ADMIN_ENABLED`, `SAHAJ_ADMIN_BOOTSTRAP_ENABLED`, DEC-016 sample admin, owner IPD trial unless chosen; `SAHAJ_HTTP_COMPRESSION=false` behind the proxy. | Production env checklist. | human/ops |
| 23 | Low | Operations | No monitoring beyond `/platform/health`; no audit_event partition/retention; emr_record partitions last to Oct 2028. | Monitoring, partition plan. | human/ops, db |
| 24 | Low | Secrets list | `.env.example` unverified. Code reads `SAHAJ_DB_URL`, `SAHAJ_DB_APP_PASSWORD`, `SAHAJ_DB_MIGRATOR_PASSWORD`, `SAHAJ_JWT_SECRET` (≥ 32), `SAHAJ_PIN_PEPPER`, `SAHAJ_REMOTE_IP_HEADER`, `SAHAJ_TRUSTED_PROXIES`, `SPRING_PROFILES_ACTIVE`, optional `*_MS`, limit and admin keys. | Human compares. | human |
| 25 | Low | Dependencies | Spring Boot 3.5.16 support line; `mvn dependency:tree` + OWASP check not run. | Run before release. | java |
| 26 | Low | Tests | `frontend/e2e` fully gitignored (`.gitignore:8`). | Narrow the rule. | human |

## Passing
`sahaj_app` NOSUPERUSER NOBYPASSRLS; Flyway as `sahaj_migrator`; ENABLE + FORCE RLS on every tenant table (globals by design), per-partition RLS on `emr_record`; no defaulted secrets, JWT length enforced, missing pepper fails closed; cookie HttpOnly + SameSite=Strict; CORS from config only; RFC 9457 errors with values stripped; no patient data in logs; no third-party hosts; no patient data in web storage; SAH-33 M1/M2 and SAH-51 M4 fixed.

## Minimum before the first real patient
#1; #2 + #3; #4, #5, #6; #10; #11; for IPD also #7, #8, #9, #12, #13. SMS with DLT registration usually takes longer than a week; if the date cannot move, the reviewer's safe option is DEC-058 option C (test data only) until #1–#6, #10, #11 are closed.
