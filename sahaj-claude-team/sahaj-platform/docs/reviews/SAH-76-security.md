# SAH-76 · Security and privacy review

Date: 16 Nov 2026. Reviewer: security-reviewer (read-only). Branch: feature/SAH-39-home-kinetic-background (uncommitted; `git diff HEAD` plus untracked files). No new dependencies (pom.xml, package.json, package-lock.json unchanged vs HEAD).

**Verdict: PASS.** No Critical or High. Two Medium findings need a fix or an explicit human acceptance before go-live.

## Findings

| # | Severity | Where | Finding | Fix | Owner |
|---|---|---|---|---|---|
| M1 | Medium | `ipd/application/MedicineAdministration.java:363-390`, `DoseRules.java:190-192`, ADR-027 line 123 | One override object waives both bedside identity checks (missing wristband and missing medicine code). The "no override after a wrong-patient STOP" rule exists only in the client: after a 409 `wristband-mismatch` a direct API call with an override and no wristband records the dose on `identityConfirmed=true` alone. With `ipd.drug_code` shipped empty every Given needs an override, so in practice the wristband scan becomes optional. Detectable afterwards (audit `method=OVERRIDE`), not prevented. | (a) Split the override by what it covers (WRISTBAND, MEDICINE_CODE). (b) Server refuses an override after a wristband/medicine mismatch by the same user on the same order and slot within N minutes unless the new request scans a match. (c) Until codes are loaded, a clinic/global switch "medicine code not required" instead of a blanket override. At minimum, explicit human acceptance as part of the Q1 go-live condition. | solution-architect, java-developer, human |
| M2 | Medium | `MedicineOrderService.java:551, 586`; `PharmacistQueryService.java:84`; migration `V2026_12_15_1000…sql:473-479`; `OrderChangeService.java:149` | Separation of duties: the self-verification rule compares the caller only with `medicine_order.ordered_by` (author of version 1), not with `medicine_order_version.created_by`. OWNER is in both order and pharmacist roles, so an OWNER can change an order (e.g. 10x dose → v2 PENDING) and verify v2 alone. Same gap in the database guard and for query. Matches the contract wording ("placed by the caller"): a design gap, not drift. | Refuse verify and query when the actor equals the current version's `created_by` as well as `ordered_by` (403 `self-verification`); same check in `ipd.guard_medicine_order_version_update` by a new forward migration; contract text; unit test and IT. | solution-architect, java-developer, db-engineer |
| L1 | Low | `MedicineAdministration.java:531-551` (pre-existing, SAH-32) | Dose client-key replay does not check the caller is the recorder; another in-scope nurse with someone's `clientKey` gets 200 `replayed` and a record-access row. `DoseCorrectionService.java:166-168` does check the actor: inconsistent. | Add `stored.recordedBy().equals(actor)` to `checkRepeat`, else 409 `client-key-reused`. | java-developer |
| L2 | Low | `AuditDevice.java:42-48`, `AccessLogFilter.java:54` | `device.id` is client-supplied (validated, no injection) so it is a label, not evidence. No source IP is recorded in audit or access log (pre-existing); CERT-In expects source addresses in retained logs. | Document in ADR-027 D11 that `device.id` is advisory; confirm the reverse proxy keeps source IP with the request id, in India, 180 days. | tech lead / human (ops) |
| L3 | Low | `frontend/src/app/features/ipd/ipd-device.ts:19-33` | DPDP: persistent per-browser id links staff activity across sessions on shared tablets (staff personal data, legitimate audit purpose, no patient data). | Add to the staff privacy notice and the record of processing. | human |
| L4 | Low | `backend/src/test/java/com/sahaj/platform/it/ScopeRaceIT.java:~28-33` | `GRANT_ENDS_IN_SECONDS` 30 → 5 (not weakened: still fails loudly); the Javadoc still explains the old 30 s choice. | Update the comment. | qa-engineer |

## Re-check of changes since DEC-054 and SAH-75
- AdmittedCardsTest: mixed allergy text is RECORDED (safe side); new stamped-interval test. Fine.
- ScopeRaceIT: see L4.
- EmrOverridesIT, EmrSettingsIT, EmrTemplatesReadIT: empty at HEAD; deletion loses no coverage.

## Checked with no issue
- **RLS on the 9 new tables:** ENABLE + FORCE on all (7 tenant, 2 global); policies on `platform.current_tenant()` with USING and WITH CHECK; column-limited insert grants never include `tenant_id`; no delete grant or policy; delete/truncate deny triggers everywhere; `dose_correction`, `drug_code`, `nursing_profile_template` also refuse update; composite `(tenant_id, …)` foreign keys; guard functions pin `search_path`, execute revoked. Proven by RowLevelSecurityCatalogIT and Sah76MigrationIT.
- **DEC-056 RLS switch:** NO FORCE (line 344) and FORCE (353) in one Flyway transaction (no `executeInTransaction=false`, no mixed, no .conf); a failure rolls the ALTER back; ACCESS EXCLUSIVE until commit; `sahaj_app` never affected.
- **Scope guard:** acknowledge, undo, recorded-in-error find (404) → lock admission → `requireAdmissionLocked` → re-read; change, hold, resume, query-reply order-locked and guarded; query and pharmacist reviews scope-all as contracted; IpdScopeCoverageTest knows NOTICE and DOSE and the 7 scoped list reads.
- **Roles:** token role and active membership role checked per contract; settings writes OWNER-only; clinic, user and role from `ClinicUser.current()`.
- **Override path:** a sent wristband or code is still checked and a mismatch stops; identity confirmation, high-alert second nurse with PIN, PRN and order-state rules never skipped; allergy is a hard stop at order and verify; database refuses a `drug_key` change.
- **Idempotency:** correction replay tied to dose, kind, actor, reason; order-action replays require the same actor (dose replay: L1).
- **X-Sahaj-Device:** `^[A-Za-z0-9-]{8,36}$` after trim, else null; agent is a fixed family; audit label only, never authorisation; CORS adds only this header with explicit configured origins; the frontend sends it only to `/api/v1/ipd`.
- **Logs, audit, events:** ids, keys, numbers and SQL state only; no names or free text (indication, reasons, questions, replies, override and not-given notes); 500 handler strips values; every write audited with `device`.
- **SQL:** constant text with bound parameters.
- **Frontend:** no `console`, `innerHTML` or `bypassSecurityTrust*`; URLs carry ids, limit and cursor only; localStorage holds only the random device id.

---

# Round 2 · go-live fix round (17-18 Nov 2026)
Reviewer: security-reviewer (read-only; no Maven, npm or `.env`; no server calls). SAH-76 code in 4192d3c. The only Pharmacy touch is `platform.module_entitlement.pharmacy_on` (default false), and the runbook still works with it.

**Verdict: PASS.** No Critical or High findings. Two Medium findings need a fix or written human acceptance before the pilot.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| M-1 | Medium | `MedicineAdministration.java:376-398`, `domain/MedicineCodeCheck.java:43` | `ipd.drug_code` ships empty. Scanning the real box barcode of a medicine with no codes loaded = mismatch → stop row → the nurse's override is refused for 15 minutes, EMERGENCY included. Patient-safety risk: a STAT dose is delayed or handed to a nurse who never saw the STOP. | No codes loaded and the scanned code unknown to the catalogue → a distinct answer "No code is loaded for this medicine. Use Manual override", with no stop row; or load pilot codes; or written acceptance (GO-LIVE #9). | architect (contract), java, human |
| M-2 | Medium | `runbook-ipd-on.sql:97-109`, `.md:11` | The runbook audit row has no person, approval, `session_user` or client address. `sahaj_migrator` is shared, so it cannot be tied to a person or an approval. | Require `-v operator=` and `-v approval=` (refuse without them); write them plus `session_user`, `inet_client_addr()` and `application_name` into after_json; record runs in docs/decisions; `log_connections` in prod. | db, human/ops |
| L-1 | Low | `MedicineAdministration.java:185-198,368-398` | A same-nurse mismatch and an override fired at the same instant: the override's locked check may run before the stop row commits. Needs scripting; skipping the scan is equivalent. | Accept and note in A9, or take the admission lock (`for key share`) before writing a stop row. | architect, java |
| L-2 | Low | `runbook-ipd-on.sql:29-40,138-144` | (a) The dry run is opt-in, so a typo commits; (b) `\i` in interactive psql leaves an aborted transaction holding exclusive locks. FORCE can never stay off. | Require `-v commit=yes`; `set local idle_in_transaction_session_timeout='30s'`; `.md`: run with `-f` only. | db |
| L-3 | Low | `Sah76ReviewRoundIT.java:107,169-174`, `IpdJourneyIT.java:813-853` | Test gaps: (a) the R-01 audit check runs before the mismatch and override audits; (b) R-03 does not probe the column-limited insert grant; (c) step15Privacy does not check free clinical text. | Repeat the check at the end with `codeOf("hep")`; 3 insert probes; add the typed free texts to `never[]`. | qa |
| L-4 | Low (info) | `OrderChangeService.java:142`, `MedicineOrderService.java:330` | An OWNER can switch `verificationRequired` off and change an order straight to ACTIVE (accepted in A9.2, audited). | Human confirms pilot ward profiles keep the pharmacist check on, and that settings audits are reviewed. | human |
| L-5 | Low | ADR-027 A9 | `slot_at` "null for PRN" vs NOT NULL (doc only). | Correct the ADR. | architect |

Checked with no issue:
- **Migration:** `dose_scan_stop` (RLS ENABLE+FORCE, policies, 5-column insert grant, deny triggers, composite FKs, no scanned text); guard functions not SECURITY DEFINER, `search_path=pg_catalog`, execute revoked.
- **Runbook:** the FORCE handling is transactional and verified before commit.
- **Java:** wristband always required; override only with an empty code; after-mismatch on the DB clock in both transactions; refusals commit before the 409; replay by another recorder writes nothing; verify and query separation of duties; tenant and actor from `ClinicUser.current()`; logs and audit ids/keys only.
- **Angular:** no storage beyond the device id; no innerHTML or bypass; no typed values echoed.
- **Test trigger helpers:** test-only, unreachable from main code.

Earlier findings: M1 closed (the empty code list becomes M-1 and GO-LIVE #9); M2 closed; L1 closed; L2 partly closed (source IP is GO-LIVE #6); L3 open (human, GO-LIVE #10/#15); L4 closed.

GO-LIVE items:
- #7 closed for the pilot, on three conditions: the human dry run, M-2 fixed or accepted, and the owner trial flag off in prod.
- #9-M1 closed as security; as a clinical item it stays open (no codes loaded = every Given is an override) and needs written acceptance with M-1.
- #14 closed.

---

# Round 3 · after round 2 (runbook M-2/L-2, ipd.yaml 0.4.4, QA round) · 18 Nov 2026
Reviewer: security-reviewer (read-only; `git diff HEAD`; no Maven, npm, `.env` or server calls; Pharmacy out of scope; no dependency change).

**Verdict: PASS.** No Critical or High findings. All round-2 findings are closed or accepted in writing. One new Medium (N-1), caused by the round-2 M-2 advice, must be fixed or accepted before the first real runbook run.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| N-1 | Medium | `runbook-ipd-on.sql:170-178`; `platform/application/SetupOverviewService.java:98-115`; `platform/web/AuditEventResponse.java:11-16` | The M-2 fields (operator, approval, reason, sessionUser, currentUser, clientAddr, applicationName) go into the clinic's own `audit_event.after_json`. The OWNER's audit-events endpoint and setup overview `lastChange` return the whole `after` map, so the customer sees the ops staff reference, internal ticket and text, `sahaj_migrator` and the ops IP. A platform-to-tenant disclosure (not patient data). | Keep only `actor: "ops-runbook"` (+ `approval` if agreed) in after_json; put the rest in an ops-only place (the run record + psql output + `log_connections`, or a non-tenant table), or java whitelists the keys for MODULE_ENTITLEMENT rows. | db; java if filtered; human |
| N-2 | Low (accept) | `MedicineCodeCheck.java:45-47,55-56` | With no codes on the ordered drug, a nurse can deliberately turn another drug's code into no-code-loaded (pad past 100, insert a character, look-alikes NFKC does not fold). A scanner never does this; it equals skipping the scan (A9.11). Case, spaces, controls and full-width stay MISMATCH. | Accept; one sentence in A9 point 8. Keep >100 as NCL (long QR payloads). | architect |
| N-3 | Low | ADR-027 A9.8 / D9; DEC-065 B | The STOP needs an exact match with `ipd.drug_code`. A GS1 element string or an Indian QR payload against a stored bare GTIN gives no-code-loaded for a wrong box (drug with no codes), or a false STOP (drug with codes). | Before the DEC-065 B load, confirm what pilot scanners send; load codes in that form or extract AI (01) (contract change); QA case with a real pilot box. | human, db, architect |
| N-4 | Low | `runbook-ipd-on.sql:59,64,71-72,77` | (a) Refusals end with `\quit`, so the exit code is 0. (b) `reason` only refuses control characters, so a mobile number or name can enter the audit row (visible to the OWNER with N-1). | (a) Fail under ON_ERROR_STOP before `begin` (exit 3). (b) Refuse `[0-9]{10}` in reason. | db |
| N-5 | Low | `Sah76NoCodeLoadedIT.java:111-137` | N-02 sends another drug's code only in canonical form (unit tests cover the variants). | Add one variant (lower case, spaces, CRLF) → mismatch + stop row. | qa |

Checked with no issue:
- **M-1 rule:** both transactions, before the PIN; `CODE_SHAPE` matches the DB check; NFKC + `Locale.ROOT`; bound parameters.
- **Catalogue membership:** the 409 reveals only global reference data, behind a nurse role, an admitted patient, a wristband match and an audit (accepted in A9).
- **Scanned text:** never stored, logged or echoed.
- **Runbook:** required vars before `begin`, `:'var'` quoting only, commit only on `commit=yes`, `dry_run` refused, timeouts, FORCE restore checked.
- **L-3 tests:** they check what they claim.
- **Angular:** no echo, no storage, nothing hidden by the toast move (D8 is usability only).

Round 2 status:
- Closed: M-1 (DEC-065 B still before real patients; see N-3), L-2 (follow-up N-4 a), L-3, L-5.
- Closed, with a follow-up: M-2 (its fix creates N-1; human/ops `log_connections` and run records).
- Accepted: L-1 (A9.11), L-4 (A9.12; the human confirms pilot ward profiles).

## Round 4 (D8 check) · 18 Nov 2026
Reviewer: security-reviewer (read-only; `git diff HEAD` on the D8 files; nothing run; `.env` not read).

**Verdict: PASS.** No Critical, High or Medium findings. D8 removes no safety control. A STOP or code mismatch still turns Given off in the client, and the server still refuses the dose: the wristband is always checked, and an override is refused for 15 minutes after a mismatch.

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| R4-1 | Low | `give-sheet.component.html:308-320`; `give-sheet.component.ts:511-514, 523-543`; `mar.spec.ts:1898-1934` | The PRN lock and `give-failure` lines (500, lost answer, 403, `client-key-reused`, non-high-alert PIN) sit at the bottom of the sheet body and get no focus. At 360 px the nurse may see nothing change, and may believe a failed record went through (risk: an unrecorded dose, then a duplicate). Screen readers still read `role=alert`. The 360 px e2e test checks only the wristband line and ignores the sticky footer. | Scroll the line into view or focus it; add `scroll-padding-bottom` equal to the footer height; extend the 360 px e2e to `give-failure`, `give-prn-lock` and `code-mismatch`, checking each line is above `.sheet-f`. | angular, qa |
| R4-2 | Low | `give-sheet.component.ts:433-437` | `send()` clears `mismatch` and `codeMismatch` before every request, including Record not given. A failed Record not given after a STOP removes the STOP line and turns Given back on while the wrong scan is still in the box. The server still refuses (409 again; override refused for 15 minutes). | Clear them only on box or code edit, or when a GIVEN request is sent. Add a unit test: STOP, then a failed Record not given, then the STOP line is still there and Given is still off. | angular |
| R4-3 | Low (process) | `.gitignore:8` (`frontend/e2e`) | All of `frontend/e2e` is git-ignored. The D8, R11, PIN-hygiene and every other e2e test are in no diff or commit, and `main` will not have them. | Human decides whether e2e is tracked. If yes, remove line 8 and keep `frontend/e2e/.gitignore` for `node_modules/`, `report/` and `results/`. | human |

**Checked with no issue:**
- **Refusals:** the STOP and code lines are persistent polite live regions under their boxes, focus moves to the box, and Given is off while stopped. Before D8 the toast was already `politeness: 'off'`, so what a screen reader announces is unchanged. The server stays the authority on every path.
- **Patient data:** no patient name, mobile, ABHA number, PIN, scanned text or clinical note in any live region or toast. No `console.*`, `innerHTML`, `bypassSecurityTrust*` or storage in `features/ipd/mar` or `sheet-toast.ts`. The scanned code is cleared after `no-code-loaded`, and the PIN after every answer.
- **e2e data:** made-up fixture PINs and patients only; nothing reads `process.env`. The human should grep once for the real SAH-42 PIN across the repo, including git-ignored `frontend/e2e`.
- **No weakening:** M-1 and no-code-loaded, the 15-minute rule, override handling and the `sheetToast` `announce` option are all unchanged.

Round 3 status (N-1 to N-5): unchanged.

## Round 5 (sheet header, layout height, presence) · 19 Nov 2026
Reviewer: security-reviewer (read-only; `git diff HEAD` and file reading; nothing run; `.env` not read).

**Verdict: PASS.** No Critical, High or Medium findings. R4-1 and R4-2 are fixed (checked by reading). A nurse still cannot record a Given after a STOP: Given is off while a STOP shows, only a GIVEN request or a box edit clears a STOP, and the server still checks the wristband and code and refuses an override for 15 minutes after a mismatch. Four new Low findings; all fail safe (none can record a dose, hide one, or show patient data).

| # | Severity | File:line | Finding | Fix | Owner |
|---|---|---|---|---|---|
| R5-1 | Low | `give-sheet.component.ts:427, 558-566`; `give-sheet.component.html:228-230, 248-260, 282-284` | R4-1 is fixed for the bottom lines and the wristband and code lines, not for the messages under the other fields: `pin-error`, `second-nurse-error`, `reason-error`, the override errors and the ward line. Focus moves to the box and the browser scrolls only far enough to show the box, which then sits just above the sticky footer. At 360 px "Wrong PIN" or "PIN locked" and the ward line can be hidden under the footer. Risk: the nurse sees a red box without the reason and retries until the PIN locks, delaying a high-alert dose. Nothing is recorded; screen readers still read `role=alert`. | After `focusLater(...)` for these fields, scroll the field's own error line into view (as `mismatch` does with `wristband-line`), or add `scroll-margin-bottom` to the inputs. QA adds a 360 px in-view check for `pin-error` and the ward line, tapping Given from the top of the sheet. | angular, qa |
| R5-2 | Low | `undo-toast.component.ts:62-65, 78-85`; `give-sheet-presence.ts:29-44` | The held "Undone." and refusal toasts name only the medicine. They can show minutes later, after the nurse moved to another patient's MAR, and be read as about that patient. While a give sheet is open the Undo result is invisible until it closes. The MAR data is never wrong: `corrected` is filtered by `admissionId`, and the MAR is refreshed at once. No patient name, PIN or note in the toast; nothing logged. | Decide one: (a) prefix a held toast with the time it happened; (b) add the bed label (not the name); (c) show the held toast only on that patient's MAR. | human, angular |
| R5-3 | Low | `_console.scss:803-809, 826-837` | Two sticky bars (header `z-index: 2`, footer). On a short screen with large text (a phone held sideways, max-height 328 px, at 150-200% system text, with a long medicine name) they can overlap: the header paints over Given and Record not given, and the scroll paddings exceed the visible area. Not measured; QA tested only 360x740 and 1440 wide. A hidden button records nothing. | QA checks 740x360 at 200% text with the longest fixture title. If the bars overlap, drop the sticky header under a height breakpoint (for example `(max-height: 480px)`) or limit it to one line with ellipsis. | qa, angular |
| R5-4 | Low (nit) | `give-sheet.component.ts:286` | `GiveSheetPresence.enter()` is the first statement in the constructor. If a later statement throws, the sheet may never be destroyed and the count stays above 0; every later "Undone." and refusal toast is then held until reload. The MAR still refreshes. | Register at the end of the constructor or in `afterOpened()`. Optionally wrap each held callback so one error does not drop the rest. | angular |

**Checked, no issue:**
- Header and footer heights come from the layout height, rounded up, measured again by the ResizeObserver and at the end of the open animation; the paddings read them. `reveal()` uses fixed test ids only. Observer and listeners are removed on destroy. Close is in the sticky header.
- STOP clearing: Record not given passes `clearStops=false`; unit tests cover a failed 500 and a 400 for both kinds of STOP, and the box edit clearing them.
- The opaque header (`--sheet-solid`) cannot cover a safety line: STOP, code, PRN-lock and failure lines are scrolled below it. The second-nurse and PIN boxes use the focus scroll, which respects `scroll-padding-top`; their error lines are the gap in R5-1.
- Presence and Undo: only "Undone." and refusal toasts are held; each shows once and is never dropped; scope refusals are never held.
- No `console.*`, `innerHTML`, `bypassSecurityTrust*`, storage, URL data or logging added. No mobile or ABHA numbers; the spec PINs are fixtures from earlier rounds.
- e2e PNG decoder: uses `node:zlib` only; screenshots go through `testInfo.outputPath` under fixed names with no patient data; no `process.env`. No scratch file left in `frontend/e2e`.
- M-1, the no-code-loaded path, the 15-minute rule and override handling are not weakened.

**Observation outside this round (existing code, not checked when running):** `auth.interceptor.ts:52` sends the browser to `/login` with `router.navigate`; `MatDialog` closes dialogs only on back and forward navigation. If the session expires while a give sheet is open, the sheet and its patient identity bar may stay over the login page. Suggest a later check, and `MatDialog.closeAll()` on logout or session expiry.

Round 4: R4-1 closed (follow-up R5-1); R4-2 closed; R4-3 per DEC-069 A (the human removes line 8 of `.gitignore` and greps for the real PIN). Round 3 (N-1 to N-5): unchanged.
