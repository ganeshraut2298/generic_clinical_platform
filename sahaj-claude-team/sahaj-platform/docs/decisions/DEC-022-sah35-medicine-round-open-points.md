# DEC-022 · SAH-35 Medicine round on the Safety board: open points (proceeding on the safer proposals, DEC-014)

Status: PROCEEDING. Stories docs/stories/SAH-35.md; design ADR-012 (PROPOSED); contract docs/api/ipd.yaml (YAML check OK, 7 files).
Needs the human (build proceeds on the proposal):
- OQ-35-1: a DOCTOR reads the round (no Give); mockup shows the tab to nurse and owner only. 
- OQ-35-2 (CLINICAL, go-live condition with OQ-30-6): a dose nobody records by midnight IST leaves the list and cannot be recorded next day (DEC-018 item 7), so a missed dose is silent. Built as the mockup; a "missed dose" story needs a doctor's decision.
- OQ-35-3: tab number counts Late doses only, tile counts Due + Late (as mockup).
- OQ-35-6 (privacy): the list shows medicine names and patient names with no record-access row; each Give tap writes one (through the MAR read).
- OQ-35-8 (with DEC-018 item 4): in a read-only clinic doses cannot be recorded; no Give button; line "IPD is read-only. Doses cannot be recorded."
- OQ-35-9 / ADR-012 Q6: new wording ("Showing the 200 most overdue doses.", Give accessible name "Give <medicine> to <patient name>"); the panel's own load-error text from the story (AC8) is dropped because the round is part of GET /safety and the board's own states cover it.
Deviations from the mockup (ADR-012): doctor sees tab without Give; read-only state; 200-row cap + tie-break; Give makes one MAR read before the sheet opens; no Scan wristband; no Emergency access tile (SAH-34); nurse sees every admitted patient (OQ-33-2); Give hidden (not disabled) for roles that cannot give.
Design: one set-based read for the whole clinic, one pure MedicineRound function over DoseSlots (invariant: dosesDue+dosesLate = sum of Mar.doseCount over admitted patients), changeToken carries the dose lines, no new table or migration expected.

## Review results and decisions (7 Nov 2026, tech lead)
Code review APPROVE WITH COMMENTS (docs/reviews/SAH-35-code.md), security PASS (docs/reviews/SAH-35-security.md). Fixed in the fix pass: R35-1 (a bad catalogue key no longer blanks the Safety board, summary, banner and the Admitted list), R35-2 (Give keeps focus while the MAR read runs), R35-3/4/5.
- PRODUCT CONSEQUENCE of R35-1 (human to be aware): an order whose drug key is missing from the cached catalogue or whose frequency key is unknown is SKIPPED in the Medicine round only. Its due or late dose is then not listed on the round or counted in the tile; the only signal is a WARN log line (`medicine-round order-skipped tenantId=.. orderId=.. cause=DRUG_NOT_IN_CATALOGUE|FREQUENCY_UNKNOWN`, no names). The nurse's own MAR (single patient) still fails loudly and shows the dose. This can only happen in a rolling deploy with an old catalogue cache (database constraints guarantee the keys exist). A generic "Medicine" row was NOT added (the reviewer's optional idea): decide at UAT if wanted.
- Go-live conditions unchanged: OQ-35-2 (a late dose leaves the list silently at midnight; clinician sign-off), privacy exception for lists without an access row (DEC-021).
