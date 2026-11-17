# GO-LIVE · OPD and shared open work (read-only audit, 17 Nov 2026)

Reviewer: code-reviewer (read-only, no builds or tests run). Saved by the tech lead. Context: DEC-058 (OPD + IPD live next week).

**Tech lead note:** item #5 is out of date. The SAH-76 QA final pass (docs/handoffs/SAH-76.md, "Stage 6: QA final pass GREEN") ran `validate backend` green (unit 5,462, IT 2,972, 0 failures) after all SAH-76 handlers existed, IpdScopeCoverageTest included. The gate still has to be re-run after the go-live fix round.

Headline: the OPD journey is proven (34 of 35 live, gm-01 fixed in code). Go-live is stopped by: no production OTP sender, no production profile, uncommitted/unmerged work, EMR e2e never run, open human decisions.

## A. Open items

| # | Area | Open item | Evidence | Type | Owner | Size | Go-live blocker |
|---|---|---|---|---|---|---|---|
| 1 | Login (SAH-3) | No OTP delivery outside dev: only `LogOtpSender` (`@Profile({"dev","test"})`); a production start fails, nobody can sign in. Notifications are Later. | `platform/infrastructure/otp/LogOtpSender.java:19` | human decision → feature | human, java | S–M | YES |
| 2 | Config | No production profile; `application.yml:28` defaults to `dev`; only dev and test yml. Hosting, TLS, backups, restore drill, 180-day source IP, DPDP notice, GL-41-1 are human items. | `application.yml:28`, `application-dev.yml:34,42` | feature + human | java, human (ops) | M | YES |
| 3 | Repo | All work uncommitted on `feature/SAH-39`; no approvals.md lines for SAH-43..76; build output committed (`backend/target-devtools/`, `.vitest-cache`); `frontend/e2e` git-ignored; commit 5151b7e holds a real owner mobile and `.claude/settings.json` edits (rewrite or accept). | DEC-054-security M2, L5, L7; SAH-75-security I1, I2 | human decision | human (tech lead prepares commit plan) | M | YES |
| 4 | e2e | EMR UI programme (SAH-45..75) e2e written, never run; no whole-folder e2e since the EMR work. | BOARD SAH-45..73; test-plans/SAH-75.md:55 | test gap | qa | L | YES |
| 5 | Gate | (Stale, see note.) Re-run `validate backend` and whole `mvn verify` after the fix round. | test-plans/DEC-054-backend-gates-2026-11-15.md | test gap | qa | M | YES (re-run) |
| 6 | Billing counter | 30-day lookback is an unwritten business rule (not in billing.yaml / SAH-10 AC5): an unpaid bill older than 30 days vanishes. | `BillingService.java:53,117` | human decision | human → architect | S | YES |
| 7 | Billing counter | gm-01 fix in code (`BillingCounterEarlierDaysIT` 6/6); rerun `opd-journey.mjs --only gm --per 1` after a dev restart. | `BillingService.java:95-127` | verification | qa | S | No once verified |
| 8 | Billing counter | N+1 fixed; no load measurement. | DEC-054 java fixes | test gap | qa | S | No |
| 9 | Visit receipt (SAH-10 AC6) | Mockup has a Receipt modal (print, bill number, UPI QR setting); app shows only an inline table; no billing.yaml read for a visit receipt. | `billing-counter.page.html:136-169`; mockup 3021, 3032, 3292, 3874 | feature (+ human: bill number series) | architect, java, angular | M | YES if printed receipts on day one |
| 10 | Surgery / oncology billing | (a) surgery bills consultation only, no OT/procedure charge; (b) oncology fallback fee (setup artefact); (c) `onc_ecog`/`onc_labs` not required before a chemo order. | DEC-054 Run 5 | human decision | human (clinical, billing) | S | (a), (c) YES for those clinics |
| 11 | Plans | FREE plan caps 5 users; upgrade needs admin portal (ADM-2/3 blocked, ADM-1 db half parked, SAH-42 db half unexecuted). | BOARD ADM rows | human decision | human | M–L | YES for clinics > 5 staff |
| 12 | Departments | Doctors can be assigned only when a department is added. | live-opd report | human decision | human, architect | S | No |
| 13 | Money visibility | Mockup hides money from nurse (`showMoney`, mockup 3799); Today "Collected" and `/billing/today-summary` go to all roles; counter open to NURSE, PHARMACIST, COUNSELLOR. | `BillingController.java:35,42,49`; `today.page.html:70-78` | bug (nurse) + human | java, angular; human | S | Fix nurse case before go-live |
| 14 | Follow-ups | 200-row cap, no `truncated` flag in opd.yaml. | `JdbcFollowUpRepository.java:91-92` | contract gap | architect, java, angular | S | No |
| 15 | Workflow config | Mockup "What changes when you publish", "Use recommended", `wfCheck` warnings blocking Publish not built. | mockup 3450-3488 | feature (mockup gap) | angular, java | M | No (do early) |
| 16 | Alerts (SAH-18) | Rules stored only, no engine (page says so). | `AlertRulesService`; SAH-18.md:8,41 | scope cut | human to accept | L | No if accepted in writing |
| 17 | Appointments | Double booking CLOSED (`lockSlot`, `AppointmentDoubleBookingIT`). BOARD stale. | `AppointmentService.java:162,275,300` | docs | tech lead | S | No |
| 18 | Queue | `mine=true` follows the visit's version: CLOSED. | `VisitService.java:162-171` | docs | tech lead | S | No |
| 19 | Staff and leave | Roster edit on leave day 409 `on-leave`: CLOSED. | `HraService.java:140-142,190` | docs | tech lead | S | No |
| 20 | Patients | Duplicate detection and merge BUILT. | `PatientController.java:72`; patient.yaml:71,129 | closed | — | — | No |
| 21 | Staff | Staff without logins (mockup roster) undecided. | SAH-20.md:8,48-49 | human decision | human | M | No |
| 22 | Revenue | "Still due" range vs all open dues; discount approver not stored. | `RevenueService.java:117-130,263-271` | human + small code | human, java | S | No |
| 23 | Patient allergy text | Front-desk none-word list differs from `AllergyStatus`; make it the single source. | SAH-75-code #6 | bug (safety) | java | S | Fix before go-live |
| 24 | EMR re-review | SAH-75 and SAH-51-68 Majors look fixed in code, no re-review recorded; open security L2, L4 (dental allergy chip writes to patient record at sign, policy), L6; minors #7, #8 unconfirmed. | SAH-75-code/-security; SAH-51-68-code | re-review + human (L4) | reviewers; human; java, angular | S | YES (re-review) |
| 25 | EMR design alignment | Most closed; SAH-75-8 / 75-11 residual closing list; 1920/1440 px layout never checked in a browser. | SAH-75 handoff | test gap + docs | angular, qa | M | No |
| 26 | Clinical sign-off | P-9 Physio ROM Left read-only; Dental wizard card count; all clinical values "not clinically reviewed"; DEC-052 H4, H5, H21, H24; DEC-048 H22-H38 wording; Paeds "mark all due". | gates doc C; audit P1, P-9 | human decision | clinical reviewers | S | YES for real patients |
| 27 | Contract and docs | opd/billing yaml need #6 and #9; OTP and plan items; stale BOARD rows; `validate docs` fails only on ADM-2.md. | — | docs | architect, tech lead | S | No |
| 28 | DB timeouts | `DatabaseTimeoutsIT`, `EmrDraftSaveHangIT` pass. Open: 30 s `statement_timeout` vs long reports; `SHOW statement_timeout` IT; integer `*_MS` binding; frontend 503 `Retry-After` with jitter. | DEC-054-code #7; security L1, L2 | test gap | java, qa, angular | S | No |
| 29 | EMR test debt | Missing ITs: SAH-75 db cases 5, 8, 10; H10 checksum fixture; ER same-key race; OPD sign rollback; sign vs card move; physio off-grid slot; `validate db`. SAH-51-68 #8 (physio next slot validation), #9 (`bookFirstFree` up to 200 selects in sign tx) unconfirmed. | test-plans/SAH-75.md; SAH-51-68-code | test gap + code | qa, java | M | #8 yes if physio is live |

## B. Ordered plan
- **Human first:** hook fix (DEC-058 fact 1); #1 OTP provider; #6 counter window; #10; #11; #26 clinical items; #3 commit plan.
- **java-developer:** #1/#2 OTP adapter + `application-prod.yml` + refuse `dev` on a non-loopback host; #23; #13 nurse money; #24 minors (L2, L6); #29 physio slot, `bookFirstFree`; #28.
- **angular-developer:** #13 hide money for NURSE; #15 workflow publish parts; #9 printable receipt after the contract read; #25 closing list, #14 note; #28 Retry-After.
- **db-engineer:** `validate db` on a recreated `sahaj_test`; additive migration only if #6 or #9 needs it; confirm the `emr_record` partition callback on the production migrate step.
- **qa-engineer:** #7 gm-01 rerun; #4 whole e2e + layout checks; #29 ITs; `validate all`; #8 load measure.
- **Reviewers:** one re-check pass of the final diff (#24, DEC-054, SAH-75, SAH-51-68).

## C. Not verified
No builds, tests, scripts or UI were run. IPD is out of scope except where it blocks the gate.
