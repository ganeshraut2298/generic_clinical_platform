# DEC-065 · Scanning a medicine that has no barcode loaded (SAH-76 security round 2, M-1)

Date: 18 Nov 2026. Status: ANSWERED (A + B; L-1 accept; L-4 keep on). Raised by: tech lead from `docs/reviews/SAH-76-security.md` Round 2 (M-1, L-1, L-4).
Blocked: SAH-76 merge for the IPD pilot (security M-1 must be fixed or accepted in writing); GO-LIVE-security #9 clinical item.

## Context
- **Codes start empty.** Contract 0.4.3 (DEC-057) compares a scanned medicine code with the codes loaded for that drug in `ipd.drug_code`. Every medicine-code mismatch writes a scan-stop row, and the same nurse's manual override for that order is then refused for 15 minutes (EMERGENCY included). The aim is that a nurse who gets a STOP cannot simply override it.
- **No codes are shipped.** Nothing loads `ipd.drug_code`, so every medicine starts with no codes.
- **Where it goes wrong:** a nurse scans the real barcode on the box (the natural first action), the comparison with an empty list fails, and it is treated as a mismatch. That nurse is then locked out of the override for 15 minutes, even for a STAT or emergency dose. The likely workaround is handing the dose to another nurse who never saw the STOP, which is exactly what the rule was meant to prevent.
- **The give sheet is not enough.** It shows a hint ("No code is loaded for this medicine. Use Manual override: Medicine has no barcode.") but does not stop the scan.

## Options
- **A (recommended): a distinct answer, no lock-out.** When the ordered drug has no codes loaded and the scanned code is not a known code of any drug, the server answers with a distinct refusal: "No code is loaded for this medicine. Use Manual override". It writes no stop row, so the override stays available at once.
  - A scanned code that belongs to *another* drug is still a real mismatch → STOP and the 15-minute rule.
  - Pro: removes the safety trap and keeps the rule where codes exist.
  - Con: a small contract change (ipd.yaml 0.4.4, one new problem type and text) and work for java, angular and QA; about a day.
- **B: load the pilot formulary's barcodes before the pilot.** The human or pharmacy provides the codes, and the db-engineer writes a data script.
  - Pro: no code change, and scanning really works.
  - Con: any medicine missed still hits the trap; it depends on someone collecting the codes this week.
- **C: accept in writing.** Tell pilot nurses never to scan a medicine with no code loaded and to use the override directly.
  - Pro: no work.
  - Con: relies on training, and the trap stays for real patients.

A and B can be combined (A now, B before real patients).

## Also for the human (same review)
- **L-1 (Low):** a nurse who fires a wrong-medicine scan and an override at the same instant (needs a script, not the UI) could pass her own stop. Skipping the scan gets the same result, so the recommendation is to accept it and note it in ADR-027 A9. The other choice is that java takes the admission lock before writing a stop row.
- **L-4 (Low):** an OWNER can switch the pharmacist check off in a ward profile and change orders straight to ACTIVE; both are audited (accepted in A9.2). Confirm that the pilot ward profiles keep the pharmacist check on.

## Recommendation
A for M-1, plus B before the first real patient. Accept L-1 with an ADR note. Confirm L-4 for the pilot wards.

## Answer
**Human, 18 Nov 2026:**
- **M-1:** A now. When no codes are loaded for the ordered drug and the scanned code is unknown to the catalogue, the server gives a distinct answer "No code is loaded for this medicine. Use Manual override" with no stop row; a code of another drug still stops. ipd.yaml 0.4.4. B (load the pilot formulary barcodes) before the first real patient.
- **L-1:** accepted, noted in ADR-027 A9; no code change.
- **L-4:** pilot ward profiles keep the pharmacist check on, and IPD settings audits are reviewed.

