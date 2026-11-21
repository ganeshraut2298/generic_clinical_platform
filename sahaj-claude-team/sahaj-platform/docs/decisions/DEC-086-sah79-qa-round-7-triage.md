# DEC-086 · SAH-79 QA round 7: triage

Date: 20 Nov 2026 · Programme: DEC-061 · Authority: DEC-073 · Status: DECIDED (auto). Go-live check for a pharmacist/accountant on D-QA-3 and D-QA-5.

## Decisions
1. **D-QA-3 (debit note credit percent):** a `creditPercent` sent on a debit note line is honoured (0 to 100); the vendor's level is only the starting value when none is sent. A line whose saved percent differs from the starting credit is marked `creditPercentChanged` (contract 0.3.3). Owner: java fixes; QA tests stay.
2. **D-QA-4:** unknown vendor and non-eligible batch are 400 as the contract says (not 404 / 201). Owner: java.
3. **D-QA-5 (order line receivedQty):** counts billed (paid) units received only; free units are not added. The test is wrong; QA changes it to expect 8. (Ordered quantities are paid units; free goods are a bonus.)
4. **D-QA-6:** the sentence is "Type at most 60 characters." everywhere; QA changes the SAH-78 catalogue test.
5. **D-QA-1, D-QA-2, D-QA-7:** java (D-QA-1 and D-QA-7 may already be fixed in java round 7; QA re-runs). D-QA-2: while a Schedule X line is skipped the indent stays PARTLY_DISPATCHED with `chips.scheduleXWaiting` above 0.
6. **D-QA-9:** `scripts/validate.mjs` line 38 regex has no digit, so `pharmacy.h1_register_row` is cut at `h`. Humans own scripts/. Options: fix the regex to `[a-z_0-9]+`, or db-engineer renames the table. Recommended: fix the regex (human). Until then `validate db` cannot pass and QA runs `mvn verify` directly.
7. **e2e:** items 1 to 4 go to the angular-developer with QA's spec (remark reason on a reduced approved line; credit percent 70 vs 80 on vendor return; back-order control ids; raise-indent mock). Item 5 (older products spec line 785) goes to angular.
