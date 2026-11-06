# Security review · SAH-30 (IPD nursing vitals, notes, early warning score) and the SAH-28 fix pass

Reviewer: security-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Tests not executed by the reviewer. No new dependencies. QA findings F1 (strict clientKey) and F2 (detail = first message) were excluded; a java-developer fixes them.

**Verdict: PASS.** No Critical or High. 3 Medium, 8 Low, 6 Info.

Before go-live (not before merge): M2 / OQ-30-2 Safety board must ship or escalations be gated; OQ-30-6 a doctor signs off the score bands and response times; M3 / OQ-30-8 human decides on prefilled vitals; I2 approvals for SAH-30 and SAH-28 in docs/approvals.md before the `merge` approval.

## Medium
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| M1 | ClinicUser.java:16-23; CachedActiveMembershipChecker.java:32-42; VitalsController.java:47-52; VitalsService.java:127 | Role comes only from the access token (15 min). The 60-second membership check confirms ACTIVE, not that the role is unchanged. A nurse moved to FRONT_DESK can still save permanent vitals and notes (and raise escalations) for up to 15 minutes; DOCTOR to FRONT_DESK can still read nursing sheets. Platform-wide. | Platform: cache `(active, role)` and refuse with 401 when the token role differs (HARD-2). Until then a `ClinicalRoleGuard` (like `OwnerGuard`) re-reading the role in the transaction for clinical writes. | Fix now: role re-read for the vitals POST (and GET). Platform-wide change touches auth: HARD-2, human decides. |
| M2 | nursing.model.ts:302,178; VitalsService.java:172-195; OQ-30-2; ADR-006 | An escalation reaches no one (no Safety board) and the toast "Escalation raised ... doctor review within 1 hour" suggests the doctor was told. Nothing stops a clinic using this on a live ward. | (a) ship the Safety board first; (b) gate behind trial state or a flag; (c) change the toast to "... Tell the doctor now. No alert is sent." | Fix now (c) on the toast (honest wording, DEC-013); (a) is the next IPD story; the human records the go-live condition. |
| M3 | nursing-sheet.component.ts:136-140; OQ-30-8; ADR-006 Q13 | Prefilled last values (as the mockup) can be saved by mistake as a new reading: a worsening patient scores Low, no escalation, and `vitalsDue` clears. | Empty fields with last values beside them, or confirm a save whose five values equal the last record. | Keep the mockup prefill; add a confirm step when all five values equal the last record (DEC-013). Human decides empty fields. |

## Low
| # | Where | Finding | Fix | Decision |
|---|---|---|---|---|
| L1 | VitalsService.java:135-141 | A replay ignores what was sent: a reused key with a new High reading is silently dropped. | On replay compare the five values and `noteAdded` with the stored row; mismatch answers 409 `client-key-reused`. | Fix (contract: architect) |
| L2 | VitalsService.java:221-229 | Replay returns the full sheet (name, vitals, other people's notes) with no audit or record-access row; repeated POSTs read the sheet untraced. | `patientAccess.recordOpened` on replay, or answer a replay with `saved` and `escalation` only. | Fix: record access on replay |
| L3 | VitalsService.java:187-193; JdbcEscalationRepository.java:29-33 | A rising score does not re-open an acknowledged (ACK) escalation. | Safety board story: set OPEN again and clear the acknowledgement, or add `reescalated_at`; update grant must include `status`. | Safety board story |
| L4 | DischargeService.java:159; ADR-006 Q9 | Discharge with an open escalation leaves it open for ever. | Safety board story: warn or close with a reason in the discharge transaction under the admission lock. | Safety board story |
| L5 | V2026_11_23_1000:61-90,195-215 | `ews_escalation` lacks score/risk consistency checks; guard does not protect `version` from decrement. | Future migration: `check ((ews_score >= 7) = (ews_risk = 'HIGH'))`, `check (ews_score >= 3)`; guard refuses `new.version < old.version`. | Safety board migration |
| L6 | V2026_11_24_1000:15-22; VitalsTableIT.java:70 | The migrator can disable append-only triggers or NO FORCE RLS; nothing checks they are still enabled. | Catalog IT: every `trg_%_no_%` and `trg_%_guard_update` has `tgenabled = 'O'`; `validate db` to flag `disable trigger` / `no force row level security` in any migration without a sign-off marker; migrator password out of the runtime environment in production. | QA: catalog IT now. Validate-script change and deployment: human (scripts/). |
| L7 | VitalsController.java:47-53 | No rate limit and no correction on permanent clinical writes: a stolen NURSE session can flood a record. | Per-user rate limit (HARD-2); decide OQ-30-5 "entered in error". | HARD-2 |
| L8 | VitalsRules.java:136-155 | No size limit before NFC of the note; no global body-size limit. | Refuse raw `note.length() > 4000` before `IpdText.nfc`; global JSON body limit (for example 64 KB) for `/api/v1/**`. | Fix the note check now; global limit in HARD-2 |

## Info
I1 Record-access logging of the GET is right for DPDP; it contradicts story AC13: human confirms DEC-013 item 11, BA amends AC13. I2 `docs/approvals.md` has no `stories` or `contract` line for SAH-28 or SAH-30 (work proceeded under DEC-014): human records them before `merge`. I3 Nursing notes are free text and may hold third-party data; kept for ever (Q10): add a UI hint "Do not write phone numbers", decide retention. I4 Systolic not above diastolic accepted (Q8): an error raises rather than hides an escalation. I5 Score bands match AC 6 at every edge; clinically unvalidated (OQ-30-6). I6 `ews_escalation` overwrites its score; history is rebuildable from append-only vitals and clinical_event; keep it that way.

## SAH-28 fix pass
The one-off data statement in V2026_11_24 is safe (one Flyway transaction, ACCESS EXCLUSIVE lock, rolls back as a whole, idempotent, not reachable from the app); clearing the mobile is correct (both closing UPDATEs, guard, every read except the hand-off nulls it); hand-off audit holds the case id only and only after 403/404/409; ActedCase/CaseRef exposes only id, level, status; IpdText is sound by code point; `ck_emergency_case_patient_admitted` added. No new issues. Remaining risk: the precedent that a migration may disable a trigger (L6).

## Passed
Tenant isolation (FORCE RLS, composite FKs, tenant from the session, 404 for other clinic); no IDOR (replay bound to clinic and admission, key never returned); authorisation twice with exact role sets; check order; append-only enforced by privileges and triggers (also for the owner); escalation cannot be weakened (Java check, SQL condition, guard trigger, partial unique index); races with one `for no key update` admission lock, 23505 never swallowed; strict input validation with matching database checks; parameterised SQL; privacy by structure (redacted toString, fixed clinical_event templates, minimal audit, log scan IT); frontend (no innerHTML, storage, console; no PHI in URLs or titles); no dependency changes; synthetic test data.

## Done well
Defence in depth on the escalation; column-level grants so the database owns time, status and version; partition plan decided now; privacy enforced by structure; client-key lifecycle makes retries safe; the ADR names its own safety gaps.
