# DEC-085 · SAH-84/85/86: mockup findings, data changes on existing rows, a possible payment defect

Date: 19 Nov 2026 · Raised by: tech lead, from the business-analyst drafts (docs/handoffs/SAH-84-86-ba-drafts.md) · Status: ANSWERED Q1-Q3 (human, chat, 19 Nov 2026)

## Context
The analyst found that the mockup already draws all three DEC-084 gaps. The mockup wins (CLAUDE.md rule 1), so the stories follow its wording:
- the ₹ 0 action is **"Close visit · no charge"** (not "Close bill"); one press, a toast "Visit closed with no charge", and the receipt opens;
- the receipt number looks like "SSC/26-27/0418" ("Draft · numbered on payment");
- with "before", the walk-in sheet collects at the token ("Issue token and collect"), and the visit skips Billing when the balance is 0.
Three points change data on existing rows or are billing rules, so the human decides.

## Q1 · Fee timing default (OQ-86-1): a data change on existing rows
Today the stored default is "before" (migration V2026_11_01_1017, FeeSettingsService.DEFAULTS, and the setup wizard pre-selects it), but it does nothing, so every clinic actually runs "after". The mockup default is "After consultation". If SAH-86 ships without a change, every clinic left on the default suddenly has to collect at the token.
- (a) At release, set existing clinics to "after" (what they run today), make "after" the default for new clinics, and write one audit entry per clinic. **Recommended.**
- (b) Change only the default for new clinics; existing clinics stay on "before" and start collecting at the token.
- (c) Ask each clinic owner on first login after release.

## Q2 · Receipt number prefix (OQ-85-1)
- (a) Three letters from the clinic name, shown read-only in "Bill format" and fixed at the first number. **Recommended.**
- (b) The owner sets it. (c) No prefix.
The series restarts each 1 April (financial year, "26-27" as drawn). Go-live check: accountant.

## Q3 · Number bills already paid (OQ-85-8): a data change on existing rows
- (a) At release, number the bills already paid, oldest payment first, so every past bill can print a receipt. **Recommended.**
- (b) Only new payments get numbers; old bills show "No receipt number".
Go-live check: accountant.

## Q4 · Other open questions
OQ-84-1..7, OQ-85-2..7 and 9..10, and OQ-86-2..9 take the analyst's recommended defaults (see the drafts), each billing or tax point marked "go-live check: accountant".

## Also found (no decision needed)
- **Possible defect:** BillingService.recordPayment calls advanceToDone whenever the balance reaches 0, and advanceToDone refuses unless the stage is BILLING. Paying off a Due (a Done visit) in full may fail with 400 and roll back. → java-developer to prove it with a test, and fix it if true (existing behaviour, no contract change).
- **Order inside the sign (ADR-025 D6):** the skip-Billing decision must see the balance after the Bill-today lines are added → architect.

## Answers (human, chat, 19 Nov 2026)
- Q1: **(a)** existing clinics set to "after" at release, with one audit entry each; "after" is the default for new clinics. A data migration on existing rows, approved here; db-engineer writes it as a forward migration with the audit entry.
- Q2: **(a)** three letters from the clinic name, read-only, fixed at the first number.
- Q3: **(a)** number the bills already paid at release, oldest first payment first. A data migration on existing rows, approved here.
- Q4: recommended defaults; the mockup wording wins over DEC-084 ("Close visit · no charge").

## Blocked
SAH-84/85/86 stories wait only for the requirements lock (stale at about 22:48 IST, or deleted by the human). The due-payment defect check waits for the backend lock (stale at about 23:06 IST).
