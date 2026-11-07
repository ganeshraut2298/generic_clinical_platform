# Security review · SAH-36 (Admitted patient cards, GET /admissions extension)

Reviewer: security-reviewer (read-only), 7 Nov 2026. Saved by the tech lead. Read-only review of `git diff HEAD` plus untracked files. No new runtime libraries.

**Verdict: PASS WITH CONDITIONS.** No Critical or High. 1 Medium (a DPDP condition to settle before go-live), 2 Low, 3 Info.

## Medium
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| S36-1 | AdmissionService.java:233 (writes nothing); ADR-013 D10; DEC-021 (human answer OQ-36-7) | The list now sends allergy text, EWS score and risk, vitals due and escalation minutes for every admitted patient to OWNER, DOCTOR and NURSE, reloaded every 60 s, possibly on a shared nurses' station, and writes no `record_access` row. Insider browsing leaves no patient-level trace. Inconsistent with the pharmacist list (DEC-017), which writes rows. The human chose this explicitly (DEC-021), so this is a condition, not a defect. | Condition before go-live: (a) record the exception in the privacy register / access-log policy with purpose (ward safety overview) and roles; (b) as a compensating control, confirm the request log keeps tenant, user id, path and time for each `GET /api/v1/ipd/admissions` (no patient ids), 180 days in India (CERT-In); (c) revisit when ward scoping (OQ-33-2) arrives. | GO-LIVE CONDITION (human; policy). Code unchanged. |

## Low
| # | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|
| S36-2 | AdmissionService.java:262; AdmittedCards.java:58-60 | FRONT_DESK requests load allergy text they never see: `cards.patients` calls `PatientClinicalPort.findAll` for every role. Dropped before the response (tested on raw JSON), so no leak today, but one future mapping change could leak it. | Call `PatientClinicalPort` only when `clinicalAccess` is true; otherwise use `PatientLookupPort` (or a port method without allergy) for name, age, sex and UHID. | FIX (java; data minimisation). |
| S36-3 | application.yml:7-13 | HTTP compression is now on for every `application/json` response of 2 KB or more, not just this list (admin and auth too). BREACH: no practical exposure today (bearer token in a header, never echoed in a body; CSRF disabled so no token; no endpoint echoes attacker input next to a stable secret). Residual: a front-desk insider controls patient names that sit in the same compressed list as other patients' clinical data; exploiting it needs TLS length observation on the ward network plus many edits and polls (impractical). | Keep, with a deployment note: when a reverse proxy exists, compress there limited to `/api/v1/ipd/admissions` and set `SAHAJ_HTTP_COMPRESSION=false`. Never compress auth, refresh or admin responses. | ACCEPT with deployment note (human decides per deployment); record in DEC-021. |

## Info
- I36-1: every row still carries deposit, minimum deposit, deposit state and running bill for DOCTOR and NURSE (pre-dates the story: SAH-23 table, OQ-36-4); the card only hides "Deposit low" in the UI. If billing minimisation for clinical roles is wanted, the server should null these fields (human decision; follow-up story).
- I36-2: `uhid` is now sent to FRONT_DESK; an identifier the front desk already uses. Acceptable.
- I36-3: caching passes: Spring Security default headers are not disabled, so the list returns `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` and `Pragma: no-cache`; no ETag added.

## Passed
FRONT_DESK never gets clinical data (`clinical` and `safety` built only when the token role AND the membership role are OWNER, DOCTOR or NURSE; otherwise `Clinical.NONE`, the escalation, vitals and round reads do not run, key left out by NON_NULL); membership re-read each request (inactive or outside the four list roles → 403); IPD gate first, abilities from the server from Trial/On/Read-only; tenant isolation (every new statement filters `tenant_id`, joins tenant-matched, parameters only, RLS); batch reads in a fixed number of set queries; `toString` redaction on `AdmittedPatientRow`, `AdmittedClinicalFlags`, `CardAllergy`, `LatestScore`; frontend (search and ward filter on the device only, allergy text interpolated, no innerHTML, no ids or patient data in URLs, card actions open existing sheets through component input).

## Done well
One snapshot (REPEATABLE READ plus one `asOf`) feeds banner, counts and card flags so they always agree; raw-JSON test that `clinical` is absent for FRONT_DESK; the narrower side of each mockup-vs-server role conflict was built.
