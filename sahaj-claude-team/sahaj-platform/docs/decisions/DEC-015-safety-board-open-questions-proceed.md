# DEC-015 · SAH-33 Safety board: open questions, proceeding on the business analyst's proposals

Status: OPEN for the human to confirm or change. Development proceeds (same rule as DEC-014: the mockup wins; where the mockup is silent, the safer direction). Source: docs/stories/SAH-33.md section 6 (OQ-33-1 to OQ-33-16). Clinical items need a doctor's sign-off before any live use.

## Decisions taken so far (tech lead)
| OQ | Question | Decision taken | Why |
|---|---|---|---|
| OQ-33-1 (roles) | Who acknowledges and responds? | OWNER and DOCTOR acknowledge and record a response; NURSE may Call senior only; VISITING_DOCTOR, FRONT_DESK, BILLING no access. | Mockup lets only the Owner (demo doctor) act; the server cannot tell whether an OWNER is a clinician. Human decides whether an OWNER without a doctor profile may respond. |
| OQ-33-3 (Call senior) | No senior doctor, roster or number exists; phase 1 sends nothing. | Toast reads "No alert is sent: phone the senior doctor now" and the call is only recorded (counter and log). | Never claim an alert that was not sent. |
| OQ-33-4 (re-open) | Re-open an Acknowledged escalation when the risk rises? | Yes, on a risk rise (Medium to High): status back to Open, acknowledgement cleared, response clock restarts from the rise. | A worsening patient must not stay "Doctor reviewing". Clinical sign-off needed. |
| OQ-33-5 (discharge) | Escalation not closed at discharge. | Refuse discharge until a doctor records a response (error `escalation-open`); the architect defines the exceptions for Death, LAMA, Referred and Transfer with an automatic "Closed at discharge" only where a refusal would be unsafe. | Safer direction. Clinical sign-off needed. |
| OQ-33-7/8 (privacy) | Plan text, names, record access. | Plan text kept with the escalation, not in the event log; names read at display time, never stored in the log; reading the board does not write the record-access log; "Show earlier entries" beyond the latest entries. | Data minimisation. |
| OQ-33-9 (Read-only IPD) | Safety actions in Read-only? | Allowed (a patient already admitted must still be safe). | Safety. |
| OQ-33-10 (refresh) | 60-second refresh for a 15-minute target. | Accepted for phase 1; badge counts only. | No push channel in phase 1. |
| OQ-33-15 (sequence) | Which story creates the Safety tab? | SAH-33 creates the tab; the Medicine round panel, Pharmacist panel and Doses due tile come with SAH-31/SAH-32. | One owner per tab. |

## Still open for the human (no development blocked)
- OQ-33-2 ward scoping: the mockup scopes a nurse to her own wards; phase 1 has no ward assignment, so "Your patients" cannot be true yet.
- OQ-33-6: an Acknowledged escalation has no target time; proposal is to show "Acknowledged N min ago" and review later.
- OQ-33-12 go-live: SAH-33 built AND the clinical sign-off of the score bands (OQ-30-6) must both be recorded in a document before SAH-30/33 run on a live ward. There is no technical gate.
- OQ-33-1 detail: OWNER without a doctor profile, VISITING_DOCTOR.
- SAH-34 (break-glass emergency access) is split out: it needs ward scoping and its own security design. Not started.
