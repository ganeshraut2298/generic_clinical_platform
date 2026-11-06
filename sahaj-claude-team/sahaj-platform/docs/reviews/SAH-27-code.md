# Code review · SAH-27 (IPD discharge, final bill, discharge setup)

Reviewer: code-reviewer (read-only), 5 Nov 2026. Saved by the tech lead.

**Verdict: APPROVE WITH COMMENTS.** No Blocker, no Major. Money, lock order, idempotency, IST day bounds, module boundaries and the ADR-004 fee-window rule are correct.

| # | Sev | Where | Finding | Fix | Tech lead decision |
|---|---|---|---|---|---|
| 1 | Minor | AdmissionService.java:507-513,531 | Legacy DISCHARGED row without a bill still shows `depositLow` from the running bill (red "further deposit" notice on a discharged patient). | Pass `admitted && s.depositLow()`; assert in the legacy-row IT. | Fix |
| 2 | Minor | discharge-sheet html:128-134, ts:47-49 | `canDischarge`/`canRefund` received but unused; DOCTOR/NURSE button always ends in 403. | Disable the button when `!canDischarge` with hint "Front desk or the owner completes the discharge"; keep the server 403. | Fix (listed in DEC-013) |
| 3 | Minor | discharge-sheet ts:214-217,226-238 | After a lost answer the message hides that the patient may already be discharged. | For a non-ApiError or 5xx add "Check the admission before trying again." and emit `outdated`. | Fix |
| 4 | Minor | ipd.model.ts:257-266; bed-board html:70-75 (AC10) | Mockup is exclusive (teal pill instead of the payer/deposit pill; tile shows go flag or Deposit low, never both); story AC10 and the code show both. | Confirm AC10 or make exclusive. | Follow the mockup (rule 1): exclusive; AC10 amended; DEC-013 |
| 5 | Minor | ADR-004 header, D4 | Status PROPOSED; D4 still names `ipd.discharge.followup_id` (dropped). | Architect: ACCEPTED, correct D4. | Architect |
| 6 | Minor | Discharge ITs (about 20 `assumeTrue(day.equals(istToday()))`) | Money tests skip silently around IST midnight. | Clock hook or backdate helper. | QA, later |
| 7 | Minor | ipd.yaml:287 vs DischargeService.updateSettings | PUT text says module Off before role; code does role first. | Change the text. | Tech lead |
| 8 | Nit | GET /admissions/{id}/discharge | No record_access. | (Superseded by security M3: log it.) | Fix via M3 |
| 9 | Nit | DischargeRules.java:18 | `UNDER_EVALUATION` unused. | Delete. | Fix |
| 10 | Nit | DischargeController.java:68 | Uses `AdmissionController.DEPOSIT_TAKERS` for discharge. | `DISCHARGERS` constant. | Fix |
| 11 | Nit | DischargeService.java:231 | `Long` vs `long` compare looks like a reference compare. | `long` locals. | Fix |
| 12 | Nit | VisitService:101, FollowUpService, JdbcFollowUpRepository:126-128 | Fully qualified types. | Import. | Fix |
| 13 | Nit | ipdis.page.html | `id="dis-chain"` unused. | `aria-labelledby` or drop. | Fix |
| 14 | Nit | ipdis.page.html | Stepper buttons disabled while saving, keyboard focus lost. | Disable only for `!canEdit`; ignore clicks while busy. | Fix |
| 15 | Nit | ipd.model.ts doc; discharge-sheet html:44 | Stale comment; `$any($event.target)`. | Update; typed handler. | Fix |
| 16 | Nit | V2026_11_18_1000 and V2026_11_20_1000 | Column added then dropped. | None. | None |

## Checked and correct
Check order and messages (AC7), server-computed money and the expected total/deposits comparison, frozen bill identical to the running bill, lock order (advisory, bed, admission; follow-up port last, MANDATORY), idempotent follow-up, due date and reason rules, fee window (a review exactly 7 days after discharge with a 6-day window is a NEW visit at full fee, as specified), going-home flag and IST bounds, no N+1, module boundaries, migrations, test quality (no disabled tests), frontend a11y and texts.

## Done well
All bill maths in `RunningBill`; the expectedDepositsPaise addition end to end; atomic design with no new service; toString overrides; the F-27-3 cross-tenant finding closed with a migration and a test.
