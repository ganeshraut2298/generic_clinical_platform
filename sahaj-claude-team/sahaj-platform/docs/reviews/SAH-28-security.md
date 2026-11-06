# Security review · SAH-28 (IPD emergency queue, triage, Emergency setup)

Reviewer: security-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Tests not executed by the reviewer. No new dependencies (npm and Maven files unchanged).

**Verdict: PASS.** No Critical or High. 3 Medium, 4 Low, 8 Info.

## Medium
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| M1 | V2026_11_22_1000 :44-55,152-153,169-191; ADR-005 | Emergency personal data (display_name, mobile often of a relative, age, complaint) is kept forever on RELEASED/REFERRED/ADMITTED rows, outside `patient`, with no way to mask, correct or erase (no update grant; guard refuses every update on a closed case). DPDP s.8(7) storage limitation not met. Exposure is at database level (no API returns closed cases except the L1 replay). | (a) clear `mobile` in the closing UPDATE (update(mobile) grant; guard accepts `new.mobile is null` only on the open-to-closed transition); (b) after the legal retention (IMC 2002: at least 3 years; medico-legal cases may need longer) pseudonymise name and complaint via a migrator-owned SECURITY DEFINER function with an audit row; (c) add the table to the data-principal rights procedure; (d) owner-only audited correction path or document migrator correction. | Do (a) now (db-engineer + java-developer). (b), (c), (d) and the retention period: human decision (DEC-013 item 12). |
| M2 | emergency-badge.store.ts:27; emergency.component.ts:90; AuthService.java:84 | 60-second polls from every page keep the session alive for ever (sliding 30-day refresh token; no idle or absolute timeout). Shared ER workstation can stay signed in; ASVS V3.3.1/V3.3.2 not met. Platform gap that SAH-28 makes visible. | Platform: absolute session lifetime (for example 12 h) on the refresh chain; client idle timeout on user input; do not refresh on polls when idle. | Not SAH-28 scope. Hardening story HARD-2 (java + angular), human to set the lifetimes. |
| M3 | EmergencyService.java:97-103; AdmissionService.java:174-212 | No read trail for the Admit hand-off, which shows one case with name, mobile and complaint (no patient_id, so no record_access). Queue list unlogged is consistent with the list rule. | One access event when the hand-off opens with `emergencyCaseId` (audit row IPD_ER_HANDOFF_OPENED with case id and user only, or extend PatientAccessPort); change the contract text "writes no record-access row". | Fix (audit row; contract wording by architect). |

## Low
| # | Where | Finding | Fix | Decision |
|---|---|---|---|---|
| L1 | EmergencyService.java:143-146 | A repeat of a client key returns `actedCase` (name, age, sex, complaint) in its current state even after the case is closed, and for a different caller. Only same-clinic users who know the browser-generated v4 key can do it. | If the case is closed or `registered_by` differs from the caller, return 200 with id, level and status only. | Fix |
| L2 | IpdText.java:39-55 | Block-list works on UTF-16 chars: tag characters U+E0001-E007F, U+00AD, U+034F, Hangul fillers (U+115F/1160/3164/FFA0), U+2800, U+180E, U+FE00-FE0F, U+206A-206F, U+FFF9-FFFB and lone surrogates pass. Blank-looking or look-alike names (display spoofing). Shared with SAH-26. | Iterate by code point; refuse FORMAT, CONTROL, UNASSIGNED, PRIVATE_USE, SURROGATE plus fillers and U+2800, keep ZWJ/ZWNJ-after-virama; require one letter in name, one letter or digit in complaint; QA cases. | Fix |
| L3 | EmergencyService.java:137-172 | No rate limit or cap on registrations; rows cannot be deleted. A rogue FRONT_DESK/NURSE can bury real red cases. Each insert is audited. | Per-user rate limit on POST /emergency/cases (429); owner-only "closed as entered in error" bulk action (needs mockup). | Backlog HARD-2 with M2 (platform rate limiting); bulk close needs human and mockup. |
| L4 | JwtAuthenticationFilter | Changed role takes up to 15 min to apply (platform behaviour; deactivation within 60 s). | Accept, or include role in the 60-second membership check. | Accept (platform backlog). |

## Info
I1 `patient_id` updatable on an open case: add `check (patient_id is null or status = 'ADMITTED')` (fix with M1 migration). I2 actor columns have no FK to membership (server sets them from the token; accept). I3 gate.check before the advisory lock: negligible read-only race. I4 DOCTOR reads the full queue but has no menu item (OQ-28-4 as proposed; human). I5 lawful basis DPDP s.7(d) medical emergency: record in the privacy notice / register of processing (human). I6 admission from a case does not force admissionType emergency (as the contract says). I7 name has only a deny-list; neutralise leading `= + - @` if a CSV export is ever added. I8 advisory lock hash collision only serialises two tenants.

## Passed
Tenant isolation (FORCE RLS, composite FKs, tenant from session, every query filters tenant); IDOR on case, bed and patient ids (same 404); authorisation per endpoint twice; grants and append-only design (column-level insert, no delete, state-guard trigger, triage log insert-only); parameterised SQL; input validation with database CHECKs; idempotency under the tenant lock; races and lock order (tested); audit and log privacy (toString redaction, log-capture IT); error responses with fixed messages; data exposure (cards without mobile, counts-only badge); frontend (no innerHTML, storage, console); no secrets, no new dependencies.

## Done well
Four layers of defence on writes; insert grant makes tenant, status and time from the body impossible at database level; redacted `toString` plus a log-capture privacy IT; counts-only summary endpoint for the badge (reusable pattern); thorough concurrency ITs; ADR-005 flags the retention gap itself.
