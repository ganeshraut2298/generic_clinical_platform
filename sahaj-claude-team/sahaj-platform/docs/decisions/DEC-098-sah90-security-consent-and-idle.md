# DEC-098 · SAH-90..93 security findings: consent on the console's new-patient form, idle screen

Date: 25 Nov 2026 · Status: DECIDED 25 Nov (F1 A, F2 A)
Blocked: merge of SAH-90..93 (security verdict FAIL). Source: docs/reviews/SAH-90-security.md.

## F1 (High, DPDP): consent missing on the console's "New patient" form
The console's minimal new-patient form registers patients without showing the clinic's consent notice or recording consent. Every other registration path does both when the clinic has consent switched on. The console mockup shows a minimal form with no consent block, so the fix differs from the mockup.

Options:
- **A (recommended): add the consent block to the console's form.** When the clinic requires consent, the form shows the same consent line and checkboxes as the existing patient form and sends them. A shared consent component is preferred.
  - Pros: keeps the quick inline form; consistent with every other path.
  - Cons: a small addition to the mockup's form.
- B: make "New patient" open the existing full patient registration dialog.
  - Pros: no new UI; consent comes for free.
  - Cons: a heavier step than the mockup shows.
- Both options leave the server unchanged. Making `POST /patients` refuse a create with no consents (while consent is on) is a patient-module change. The recommendation is to put it on the backlog.

## F2 (Medium): an unattended reception screen stays signed in
Polling plus the 30-day sliding refresh keeps the console signed in indefinitely.

Options:
- **A (recommended): the console pauses after 15 minutes with no keyboard or mouse input.** It blanks the board and shows a "Continue" button. A platform-wide idle timeout and an absolute refresh-token lifetime go to the backlog (session policy).
- B: accept for now, and handle it later as platform session policy.

## Other findings
Accepted as conditions, decided by the tech lead:
- F3: rate limit or memo on the snapshot. Backlog; measure after go-live.
- F4: truncated flag. Fixed in this round.
- F5: tenant_id in the UPDATEs. Fixed in this round.
- F6: none.
- F7, F8: backlog.
- F9: human cleanup.

## Human answers
- 25 Nov 2026 (AskUserQuestion):
  - F1 = **A**: add the consent block to the quick new-patient form. The server-side refusal goes to the backlog.
  - F2 = **A**: pause after 15 minutes idle, with a "Continue" button. Platform session policy goes to the backlog.
  - Pharmacy: fix the 4 failing pharmacy ITs on this branch, in their own commit labelled SAH-80, after the console fix round.
- Status: DECIDED.
- Fix round code-review items, decided by the tech lead:
  - Fix now: #1, #2, #3, #4, #5, #6, #9, #12.
  - Backlog: #10 (overbook from the console), #11, #13 (contract wording), #8 and #14 (QA, optional).
  - #7 is the human's sahaj_dev repair.
