# DEC-094 · IPD go-live scope, legal flows, rule checks, small items

Date: 21 Nov 2026 · Programme: DEC-061 · Status: DECIDED (human answers in chat, 21 Nov)

## Decisions
1. **IPD scope for the first release: core only.** Admit, bed board, transfer, discharge, running bill, deposit (already built). The extras proposed in docs/handoffs/IPD-analysis-A-SAH-87.md (IPD-A-1..11) and IPD-analysis-B-SAH-88.md (PB-1..12) are NOT in this release. Each becomes a story only when it has a mockup screen and its open questions are answered. TP-4/5/6 (pharmacy to IPD bill) stay as no-op ports.
2. **Death, LAMA, MLC: record only, no printing.** The existing Discharge types "Death" and "On request (LAMA)" stay as status records. No death summary, no MLC print (SAH-27). A lawyer confirms wording before any print or form is added. Open questions OQ-A/OQ-B on these stay open, none blocks go-live.
3. **Rule checks.** Go-live proceeds on the auto-decided rules (DEC-070..DEC-093). Three checklists are prepared: pharmacist (Schedule X, substitution, FEFO, blind count), accountant (GST, discount spread, round off, returns), lawyer (consent, DPDP notice, record keeping). Any change they ask for is handled as a fix.
4. **Small items.** Pharmacist registration form: deferred, Sahaj admin enters it for now. Cashier discount keeps OQ-80-8; mockup 05 "Deny" is a recorded known deviation (mockup to be corrected by a human). OQ-78-8: tech lead decides under DEC-073 and logs it in the SAH-78 handoff.

## Not blocked
Nothing in this decision blocks the DB recreate and QA gate. The QA gate is still blocked by the Bash permission refusal (human action).
