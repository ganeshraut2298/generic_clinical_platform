# Security review · SAH-27 (IPD discharge, final bill, discharge setup)

Reviewer: security-reviewer (read-only), 5 Nov 2026. Saved by the tech lead. Tests not executed by the reviewer. No new dependencies.

**Verdict: PASS.** No Critical or High. 3 Medium, 3 Low, 6 Info. Tech lead decisions: fix M1, M2, M3, L1, L2.

| # | Sev | Where | Finding | Fix |
|---|---|---|---|---|
| M1 | Medium | FollowUpService.java:87-90; FollowUpController.java:23; DischargeRules.java:67-73; DischargeService.java:269-270 | OQ-27-10: the admission reason (clinical text) is copied into opd.follow_up.reason ("Review after discharge · <reason>") and the Follow-ups list returns it to every clinic role, including BILLING, PHARMACIST, COUNSELLOR who have no IPD access; it outlives IPD. FRONT_DESK exposure is not new (reads Detail.reason). | Mask by role like the mobile: `REASON_ROLES = {OWNER, FRONT_DESK, NURSE, DOCTOR, VISITING_DOCTOR}`; for admission-sourced rows and other roles return "Review after discharge" without the tail; FollowUpListsIT case for BILLING/PHARMACIST/COUNSELLOR; note in opd.yaml FollowUp.reason. Human confirms OQ-27-10. |
| M2 | Medium | discharge-sheet.component.html:77; .ts:98-100 | The sign box always reads "signed by <admitting doctor>", but any DOCTOR or OWNER can sign; signature.signedByName and signedAt are returned and never shown. The screen can state that Dr A signed when Dr B did. Database record is correct. | Once signed show "Signed by <signedByName> · <date time IST>" from the server; before signing keep the mockup label. Differs from the mockup: human decides (recorded in DEC-013). |
| M3 | Medium | DischargeService.java:132-141 (sheet, read-only tx, no recordOpened); DischargeViewsIT:86 | GET /admissions/{id}/discharge returns name and diagnosis with no patient.record_access row: the only unlogged path to the diagnosis (bulk scraping by a nurse). | Make `sheet` read-write, pass the actor, `patientAccess.recordOpened` after the 404 and not-admitted checks; DischargeViewsIT:86 expects exactly one access row. AC1 wording: "changes nothing but the access log". |
| L1 | Low | DischargeService.java:149; DischargeRules.java:32 | Signer role comes from the token (up to 15 min stale); an OWNER without a doctor profile or registration number can sign a medical summary (OQ-27-1). | Re-read the caller's membership before inserting the sign-off (active; DOCTOR, or OWNER with a doctor profile); 403 otherwise; flag OWNER-signs to the human. |
| L2 | Low | FollowUpRepository.java:57; FollowUpView.java:14; FollowUpResponse.java:14 | Records carry name, mobile and clinical reason with default toString. | Override toString (ids, due date, status). |
| L3 | Low | OQ-27-7; DischargeService.java:268 | After a death discharge the patient's other open follow-ups and appointments stay open (calls to a deceased patient's family). | Human decides OQ-27-7; later: patient.api deceased mark, close open follow-ups through a port. |
| I1 | Info | V2026_11_18_1010 | uq_follow_up_source_admission is global, not per clinic. Not exploitable (random UUIDs). | Optional: unique (tenant_id, source_admission_id). |
| I2 | Info | discharge-sheet html:128-136 | The sheet ignores canDischarge/canRefund (UX only; server enforces). | Optional disabled state (differs from the mockup; decided to keep). |
| I3 | Info | ADR-004 D6, OQ-27-4 | Settlements are not in the Billing counter or day close: fraud room (cash recorded as UPI). | Keep on the go-live list. |
| I4 | Info | DischargeService.java:219-222 | A sign-off never expires. | Show signedAt (M2); optional warning when not from today. |
| I5 | Info | DischargeService.java:275,281 | Audit has ipNumber and type (including death); pseudonymous, owner-only. | Accept. |
| I6 | Info | dependencies | None added. | None. |

## Passed
Authorisation twice on every endpoint; refund with OwnerGuard re-read; sign-off integrity (append-only, signer from caller, composite FK, type/template bound, death decided from the sign-off); money integrity (server-computed balance, expected total and deposits compared, DB checks, frozen append-only bill, races tested); tenant isolation (FORCE RLS, composite FKs, F-27-3 closed, 404 without oracle); module checks; audit and logs ids only; port boundary (MANDATORY, idempotent); frontend (no innerHTML, storage, console).

## Done well
Layered protection of the final bill; discharge copies type, template and signer from the checked sign-off row; refunds cannot use a stale owner token; thorough real-database tests; migration V2026_11_21 closes the FK-bypasses-RLS gap in opd.follow_up.
