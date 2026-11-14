# DEC-051 · Decisions from the first full database run (13 Nov 2026)

Date: 13 Nov 2026. Status: ANSWERED 13 Nov 2026 (human, chat). Raised by: tech lead, from docs/test-plans/full-run-2026-11-13.md (QA triage of 139 failing ITs: 61 code/migration bugs, 9 decisions, 58 stale tests, 11 environmental).
Not blocked: the java-developer (M1, M2, M4–M7) and qa-engineer (T group) start now.

## Q1 · M3 starter form seed uses `"type"` where the model says `kind` (3 tests, 500 on the form screens)
V2026_12_10_1020 is now applied in sahaj_test (and sahaj_dev if the server was started), so editing it would cause a checksum mismatch.
- (a) New forward migration that rewrites `type` → `kind` in the score rules of the affected global starter rows in `opd.emr_form_starter` (seed data only, no clinic or patient data). Recommended.
- (b) Edit V2026_12_10_1020 in place and recreate sahaj_dev and sahaj_test again.
The escalation rule lists "data migration on existing rows", hence the question.

## Q2 · D1 review sent in the same save as the first allergy class
Today the review is cleared but still audited. (a) The review survives (saved with the class). (b) It is cleared, and no audit is written for a cleared review. Recommended (b) (two steps in the UI already).

## Q3 · D3 allergy text in the 409 `ipd-order-not-placed` rows
The refusal rows carry the allergy text (e.g. "Penicillin rash") to the signing doctor. (a) Remove it: rows carry the drug, the check kind and a fixed reason; the doctor sees the allergy on the record. Recommended (problem details never carry clinical text). (b) Keep it.

## Q4 · D4 finish bill line `amountPaise`
The contract is silent. (a) Line total (quantity × unit price), as the code does, with `unitPaise` and `quantity` added for display. Recommended. (b) Unit price.

## Decided by the tech lead (contract alignment, no rule change)
- D2: `finish-check` on a discharged admission or closed case answers 409 `encounter-closed`, like draft save (code as built; the architect aligns opd.yaml; QA updates the test).
- D5: QA confirms the MLC pin cause rule from the stories and fixes the test or reports a bug.

## Answer (human, chat, 13 Nov 2026; all recommended options)
1. M3: **forward migration** rewriting `type` → `kind` in the affected global starter rows (seed only).
2. D1: **the review is cleared and no audit entry is written for it.**
3. D3: **remove the allergy text** from the 409 rows (drug, check kind, fixed reason only).
4. D4: **line total** (quantity × unit price); `unitPaise` and `quantity` added for display.
D2 and D5 as decided by the tech lead above.

## Follow-up (tech lead, 13 Nov 2026, from the architect's opd.yaml 0.6.3 hand-back)
- D4: `EmrFinishBillLine` already has `qty` (required, ≥ 1), so only `unitPaise` was added; no separate `quantity` field.
- D3: two fixed reason texts for the 409 rows were invented (the IPD finish dialog is not in the mockup, H17): listed for the human's wording review.
- Wording alignment (no behaviour change): the finish-check 200 `EmrFinishOrderCheck.texts` shows the SAH-31 finding text to the signing doctor, as the IPD medicine screen does; it may name the allergy; it is never logged or audited. The architect corrects the description ("never the patient's allergy text" was wrong). Only the 409 error rows are text-free (D3).
