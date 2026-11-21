# SAH-84/85/86 · business-analyst drafts (saved by the tech lead, 19 Nov 2026)

The analyst could not write: requirements.lock was held by the stopped SAH-82 run. The drafts are kept here so the next analyst run writes them as they are. Not approved; see DEC-085.

## Handoff · SAH-84, SAH-85, SAH-86 · business-analyst → solution-architect (via tech lead)
Status: BLOCKED. Nothing was written. Every Write was refused by the ownership hook: "Blocked: area 'requirements' is locked by another running business-analyst (a05610c6eb035f608)". The block covers `docs/stories/` as well. The lock file `.claude/locks/requirements.lock` reads `a05610c6eb035f608 business-analyst 1795101509206`, which is about 20:48 IST, 19 Nov 2026. That is probably the earlier SAH-82 run, cut off when the weekly limit was hit. It goes stale 2 hours after it was set, at about 22:48 IST. Only a human may touch `.claude/`.

**To unblock:** wait until the lock is stale (or have a human delete the lock file). Then re-run the business-analyst with the instruction "write the six drafts from the SAH-84/85/86 handoff as they are". All six drafts are complete below, in the format of the requirement-analysis skill. Each first "Mockup screen:" line matches a row of `docs/design/screen-map.md` ("Billing (sidebar → Billing)" for SAH-84 and SAH-85, "Queue (sidebar → Queue)" for SAH-86). Run `node scripts/validate.mjs docs` after the write. It was not run (nothing was written, and I have no shell).

Mockup screen: Billing (sidebar → Billing) for SAH-84 and SAH-85; Queue (sidebar → Queue) for SAH-86.

Files to be written (all absolute):
- d:\generi_clinical_platform\sahaj-claude-team\sahaj-platform\docs\requirements\SAH-84.md and docs\stories\SAH-84.md
- d:\generi_clinical_platform\sahaj-claude-team\sahaj-platform\docs\requirements\SAH-85.md and docs\stories\SAH-85.md
- d:\generi_clinical_platform\sahaj-claude-team\sahaj-platform\docs\requirements\SAH-86.md and docs\stories\SAH-86.md

Validation: not run (blocked before writing).

### Findings that change the decisions (please read before approving)
1. **The mockup already draws all three gaps, and some wording differs from DEC-084.**
 - **₹ 0 bill:** the counter button reads **"Close visit · no charge"**, not "Close bill" (`collectBtn`, mockup 3009-3013). The toast is "Visit closed with no charge". The bill gets a number and the Receipt sheet opens (4859-4867). The Queue card shows "Collect ₹0" in Billing and "Closed" when Done (2738, 2754).
 - **Receipt:** `receiptHTML` and `openReceipt` (3021-3034). Number format "SSC/26-27/0418", "Draft · numbered on payment" (2990). "Bill format" in Fees and billing (3592-3595). A "Receipt" button on past visits in the Patients file (3874).
 - **Fee "before":** the walk-in sheet shows "Collect now by" Cash/UPI/Card and "Issue token and collect", then "₹400 collected by UPI." (4048-4092). The workflow skips Billing when the timing is "before" and the balance is 0 (`moveForward`, 2647). The Queue card shows a "Paid" pill (2750). An appointment's "Create token" only shows a "collect ₹400" toast (2830).
2. **Fee timing "before" is the stored default today, but the mockup's default is "after".** Today's default is in the migration V2026_11_01_1017 and `FeeSettingsService.DEFAULTS` (FeeSettingsService.java:23), and the setup wizard pre-selects "before" (setup-wizard.page.ts:193). The mockup uses "after" (`S.feeTiming` and `su.feeTiming`, lines 2595-2597, and the admin default "After consultation"). If SAH-86 ships as is, every clinic left on the default suddenly has to collect at the token. This is OQ-86-1 and must be answered.
3. **Possible defect (java-developer to confirm with a test; based on reading the code only).** `BillingService.recordPayment` (BillingService.java:213-237) calls `advanceToDone` whenever the balance reaches 0, and `advanceToDone` throws "This visit is not awaiting payment" unless the stage is BILLING (VisitBillingAdapter.java:102-104). As a result:
 - Paying off a **Due** (a Done visit) in full may fail with a 400 and roll back.
 - A part payment at any stage is accepted with no stage rule at all.
4. **Under "before", the token payment collides with existing EMR rules.** Once a bill has a payment, the EMR never removes or changes its lines ("Already billed. Ask billing to adjust.") and stops recomputing the consultation fee (billing.yaml 0.2.0 and 0.3.0). A bill paid at the token has a payment before the doctor sees the patient (OQ-86-7).
5. **Order inside the sign.** ADR-025 D6 lists the workflow move before the final bill-line sync. The skip decision has to see the balance *after* the Bill-today lines are added (architect).

### Open questions with recommendations (the stories use the recommended default)
SAH-84:
- OQ-84-1 Action name: (A) the mockup's "Close visit · no charge" (recommended) or (B) "Close bill · no charge". The same label shows when the balance is 0 because the money was already paid.
- OQ-84-2 Confirmation: (A) as drawn, one press with no dialog; the toast and receipt confirm it (recommended). (B) A "Close this visit with no charge?" dialog, which is not drawn.
- OQ-84-3 Closed ₹ 0 visits are not listed on the counter (as drawn) (recommended); or a "Closed today · no charge" group. Go-live check: accountant.
- OQ-84-4 Queue card text: keep "Collect ₹ 0" as drawn (recommended).
- OQ-84-5 Refusal wording: "This bill now has ₹ <balance> to pay. Collect the payment instead." and "This visit is already closed." (recommended).
- OQ-84-6 A 100% discount follows the role's limit as today (recommended). Go-live check: accountant.
- OQ-84-7 The billing role reads only (ADR-022 H2), so it cannot close. Keep (recommended); any change should apply to all collecting.

SAH-85:
- OQ-85-1 Clinic code: (A) three letters from the clinic name, shown read-only in "Bill format" and fixed at the first number (recommended); (B) set by the owner; (C) no prefix. Go-live check: accountant.
- OQ-85-2 The series restarts at 0001 each 1 April (IST), as the drawn "26-27" implies (recommended). Go-live check: accountant.
- OQ-85-3 Keep visit bills and course receipts (RC-<n>) as separate series (recommended). Go-live check: accountant.
- OQ-85-4 A ₹ 0 close takes a number and gets a receipt, as drawn (recommended). Go-live check: accountant.
- OQ-85-5 Who may open receipts: (B) as drawn, owner, doctor, visiting doctor, front desk and billing; nurse, pharmacist and counsellor may not (recommended). (A) the course-receipt rule (no doctors).
- OQ-85-6 GST: no GSTIN and no GST lines in phase 1, with the footer "Healthcare services are exempt from GST." as drawn (recommended). A GST tax-invoice story is needed before go-live for clinics selling taxable items. Go-live check: accountant.
- OQ-85-7 No UPI QR on the receipt yet (recommended).
- OQ-85-8 Number the bills already paid once at release, oldest first payment first (recommended). Go-live check: accountant.
- OQ-85-9 No "Duplicate" mark on a reprint (as drawn) (recommended). Go-live check: accountant.
- OQ-85-10 No UHID or clinic phone, as drawn (recommended), or add them like the course receipt.

SAH-86:
- OQ-86-1 At release, set existing clinics to "After consultation" (what they run today), make it the default for new clinics as drawn, and write one audit entry per clinic (recommended).
- OQ-86-2 A visit keeps the timing in force when its token was issued, like the workflow version (recommended). The mockup reads the current setting.
- OQ-86-3 "Split" stays out of scope and behaves as "after" for now (recommended).
- OQ-86-4 Appointment arrivals: as drawn, a reminder toast only; the fee is collected at Billing after the consultation (recommended).
- OQ-86-5 No discount at the token (recommended).
- OQ-86-6 A patient who leaves before seeing the doctor: the owner refunds from a bill reachable at the counter. Recommended: "Paid today" also lists bills with a token payment today. Not drawn. Go-live check: accountant.
- OQ-86-7 The token payment does not lock the doctor's Bill-today lines, and the consultation fee is frozen once paid at the token (recommended). Go-live check: accountant.
- OQ-86-8 Refuse counter payments outside Billing and Dues; the token payment is its own action (recommended).
- OQ-86-9 Under "before", a role that may not collect (nurse) issues the token without collecting (recommended).

### Requests for other owners
- **solution-architect (billing.yaml, opd.yaml, ADR):**
- (1) A no-charge close operation, for example `POST /bills/{visitId}/close`, for OWNER and FRONT_DESK. It rechecks the balance in the transaction that moves the visit. It returns 409 when the balance has changed or the visit is already closed, and is safe to repeat. One audit entry.
- (2) `Bill` gains the visit stage (or a status), billNo (nullable), closedBy and closedAt.
- (3) A visit receipt read, for example `GET /bills/{visitId}/receipt`, with a `VisitReceipt` schema, 404 when the bill has no number, and roles per OQ-85-5.
- (4) Token plus payment in one transaction for "before". This crosses opd and billing, so it needs a port design.
- (5) The fee timing stamped on the visit (OQ-86-2).
- (6) Amend ADR-025 D6: a "before" visit goes straight to Done, and the bill lines must be synced before the skip decision. Also the sign preview and `stage.to` = DONE.
- (7) What `EmrVisitChargePort.hasPayment` means once there are token payments (OQ-86-7).
- (8) The stage rule in `recordPayment` (finding 3, OQ-86-8).
- **db-engineer:**
- (1) A bill number series per clinic per financial year: gap-free and concurrency-safe (a locked counter row, not a PostgreSQL sequence), with a unique (tenant, FY, number).
- (2) `bill` columns for bill number, numbered-at, closed-by and closed-at.
- (3) An `opd.visit` fee timing stamp.
- (4) Data migrations per OQ-86-1 (existing clinics to "after", default changed to "after") and OQ-85-8 (backfill numbers).
- **java-developer:** confirm finding 3 with a test. Change `FeeSettingsService.DEFAULTS` per OQ-86-1.
- **angular-developer:** change the setup-wizard default per OQ-86-1. The new-token dialog is `frontend/src/app/features/queue/new-token/new-token-dialog.component.html`.
- **tech lead:** clear or wait out the lock, re-run the business-analyst with the drafts below, run `validate docs`, and add the go-live checks (accountant) to the list.

Decisions made: none beyond the recommended defaults above. All billing and tax points are flagged "go-live check: accountant".

---------------------------------------------------------------
# DRAFT 1 · docs/requirements/SAH-84.md
---------------------------------------------------------------
# SAH-84 · Close a ₹ 0 visit bill at the billing counter
Module: billing (the stage move to Done goes through the existing opd.api `VisitBillingPort`)
Mockup screen: Billing (sidebar → Billing) (billing counter compose panel `vBilling` and `collectBtn`, mockup lines 3009-3013 and 3051-3104; the "collect" action, lines 4859-4867; the Queue card in the Billing and Done columns, lines 2738 and 2754)
Source: human, chat, 19 Nov 2026, DEC-084 answer 1 ("Close bill" at the counter for a ₹ 0 bill, option b). Found by the java-developer check in `docs/handoffs/BUG-emr-finish-no-call.md` (19 Nov 2026). Builds on SAH-10, SAH-12, SAH-51-2 and ADR-025 D6 (the sign moves the visit to its next step, never straight to Done).
Stories: `docs/stories/SAH-84.md`.

## Problem
A visit whose bill total is ₹ 0 lands in Billing after the doctor signs and cannot leave it.

Current behaviour (code, 19 Nov 2026):
- The sign moves the visit from With doctor to Counselling (if on) or Billing, and never straight to Done (`VisitService.finishConsultation`, VisitService.java:294-318; ADR-025 D6).
- Only a payment moves the visit to Done. `BillingService.recordPayment` (BillingService.java:213-237) calls `VisitBillingPort.advanceToDone` when the balance reaches 0 (VisitBillingAdapter.java:99-111).
- A payment must be at least 1 paise ("Enter an amount above zero", BillingService.java:218-220), so a ₹ 0 bill has no way to reach Done.
- The counter keeps every Billing-stage visit under "Awaiting payment", including earlier days (`BillingService.counter`, BillingService.java:95-129). The Today figure "bills pending" leaves out ₹ 0 bills (balance > 0 filter, BillingService.java:142), so these visits are hidden on Today but stuck on the counter.
- The counter screen shows a ₹ 0 bill with the "Settled" pill and no action (billing-counter.page.html:122-176), although the visit is still in Billing.
- Workaround today: the front desk or owner moves the Queue card to Done by hand. This is easy to forget, and nothing records who closed it at no charge.

Typical cases:
- A free follow-up inside the window, with no services.
- A bill brought to ₹ 0 by a 100% discount.
- After SAH-86: a bill paid in full at the token whose added services were removed at the counter.

Mockup: when the balance is ₹ 0, the counter's primary button reads "Close visit · no charge" instead of "Collect ₹ x · <mode>", and no payment box shows. Pressing it:
- moves the visit to Done;
- numbers the bill;
- opens the Receipt sheet;
- shows the toast "Visit closed with no charge". The mockup's toast adds "· receipt sent on WhatsApp", which is Later (DEC-040, DEC-049).

DEC-084 calls this action "Close bill". The stories use the mockup wording (OQ-84-1).

## Who uses it (roles) and in which setting
| Role | What they do |
| --- | --- |
| front desk | Closes the ₹ 0 bill at the counter (the same people who collect payments today). |
| owner | Same as front desk. May have made the bill ₹ 0 with a discount. |
| billing | Sees the bill read-only, as for every bill today (ADR-022 H2). Cannot close (OQ-84-7). |
| doctor, visiting doctor, nurse, pharmacist, counsellor | No change. They cannot collect, so they cannot close (403). Anyone who opens the bill sees "Only the front desk or the owner can take payments. You can view this bill." |

Setting: the OPD billing counter (front desk computer or tablet), the screen from SAH-10.

## In scope / Out of scope
In scope
- A "Close visit · no charge" action on a Billing-stage visit whose balance is ₹ 0.
- The server-side recheck at the moment of closing, the refusal messages, and a safe repeat.
- One audit entry for the no-charge close, plus the existing stage-move audit.
- How ₹ 0 bills show on the counter lists, the bill panel and the Queue card, before and after the close.

Out of scope
- Automatic Done at the sign (DEC-084 option a, not chosen).
- The bill number and the Receipt sheet that the mockup opens after the close. Both are SAH-85 (story SAH-85-4). Until SAH-85 is built, the close gives no number and opens no receipt.
- WhatsApp (Later, DEC-049).
- Whether a Billing card may still be moved to Done by hand on the Queue board. Unchanged here; the mockup does not let Billing cards be dragged (line 2748).
- Bill cancellation ("Bills are never deleted, only cancelled with a reason"): not built.
- IPD bills and treatment course accounts (SAH-58).

## Business rules (numbered)
1. **When the action is offered.** A Billing-stage visit whose balance is ₹ 0 (total minus payments net of refunds) shows the primary button "Close visit · no charge" in place of "Collect". The payment box (mode, "Paying now", cash received, UPI, card slip) is not shown. This covers a ₹ 0 total and a total already paid earlier.
2. **Who may close.** The roles that may collect a payment today: owner and front desk (`requireCollectRole` in BillingService; `MOVE_ANY_ROLES` at VisitBillingAdapter.java:34). Any other role gets 403 and sees no button. Another clinic's visit is 404; no login is 401. The clinic always comes from the signed-in user.
3. **No extra confirmation dialog** (as drawn; OQ-84-2). One press closes. The toast "Visit closed with no charge" shows, and once SAH-85 is built the Receipt sheet opens.
4. **The server rechecks the balance at the moment of closing**, in the same transaction that moves the visit. If the balance is no longer ₹ 0 (a line was added or a discount changed meanwhile), the close is refused and nothing changes. The bill reloads with the new balance and the Collect button, and the refusal says what to do (OQ-84-5).
5. **Only from Billing.** If the visit is already Done, the answer says so and nothing changes. At an earlier stage, the existing "This visit is not awaiting payment" applies.
6. **Safe to repeat.** A second close of the same visit (double tap, retry) changes nothing and adds no second audit entry.
7. **Effect.** The visit moves from Billing to Done (existing `VISIT_STAGE_MOVED` audit), and the bill records who closed it and when (IST). No payment row is written. The payment rule "amount above zero" is unchanged.
8. **Audit.** One entry for the no-charge close: visit id, bill id, total ₹ 0, the discount percent if any, who, when. Never the patient's name, mobile number or the service names.
9. **Money totals do not change.** Collected today, expected cash at day closing and revenue collected stay the same.
10. **Counter lists (as drawn).**
  - Before the close: under "Awaiting payment" with the amount "₹ 0" and "<doctor> · <n> item(s)". This includes earlier-day ₹ 0 bills still in Billing.
  - After the close: in no counter list ("Paid today" lists only bills with money paid, mockup line 3054; OQ-84-3).
  - Today's "bills pending" still counts only balances above ₹ 0.
11. **Bill panel (as drawn).** While the visit is in Billing, a ₹ 0 bill shows the normal bill panel: the "Awaiting payment" pill, the lines, the discount and the totals with "Balance ₹ 0". Staff may still add a service; the button then becomes "Collect". The "Settled" view is for Done visits only.
12. **Free follow-up notice (as drawn, line 3068).** "Follow-up within <n> days. Consultation is free under your fee rules." The text reads "at 50%" or "at full fee" for the other follow-up fee settings.
13. **Queue card (as drawn).** The Billing card's action reads "Collect ₹ 0" and opens the bill (OQ-84-4). After the close, the Done card reads "Closed".
14. **Bill number and receipt.** Once SAH-85 is built, the close numbers the bill and opens the Receipt sheet, as drawn (mockup 4862, 4867; OQ-85-4).

## Patient-safety and privacy points
- No clinical effect: only the billing and the stage of an already signed visit change.
- The audit entry and logs never hold the patient's name, mobile number or service names (CLAUDE.md; SAH-10 AC10).
- Refusal messages name amounts, never the patient.

## Plan or entitlement (which plan/module unlocks it)
OPD billing counter, every plan (as SAH-10). No new entitlement or permission.

## Open questions for the human
Billing rules are flagged **go-live check: accountant**. The stories use the recommended default.
- **OQ-84-1 Action name.**
- Options: (A) the mockup's "Close visit · no charge" and toast "Visit closed with no charge" (CLAUDE.md rule 1); (B) "Close bill · no charge".
- Recommended: A.
- The same label shows when the balance is ₹ 0 because the money was paid earlier (SAH-86). Say so if you want another text in that case.
- **OQ-84-2 Confirmation.**
- Options: (A) one press, no dialog; the toast and receipt confirm it (as drawn); (B) a dialog "Close this visit with no charge?" (not drawn).
- Recommended: A.
- **OQ-84-3 Where closed ₹ 0 visits show afterwards.**
- Options: (A) not listed on the counter (as drawn), found in the patient's file; (B) a "Closed today · no charge" group (not drawn).
- Recommended: A. **Go-live check: accountant.**
- **OQ-84-4 Queue card text.**
- Options: (A) keep "Collect ₹ 0" as drawn; (B) "Close · no charge".
- Recommended: A.
- **OQ-84-5 Refusal wording** (not drawn).
- "This bill now has ₹ <balance> to pay. Collect the payment instead."
- "This visit is already closed."
- Recommended: these texts.
- **OQ-84-6 100% discount.** Unchanged: the role's discount limit applies (SAH-10 AC3) and a reason is needed. Recommended: no change. **Go-live check: accountant** (whether a 100% discount should always need the owner).
- **OQ-84-7 Billing role.** ADR-022 H2 lets the billing role only read at the counter, although stories say "billing collects". Recommended: keep it (owner and front desk close). Any change should apply to all collecting, as its own decision.

---------------------------------------------------------------
# DRAFT 2 · docs/stories/SAH-84.md
---------------------------------------------------------------
# SAH-84 · Stories: close a ₹ 0 visit bill at the billing counter
Requirement: `docs/requirements/SAH-84.md` (rules 1 to 14; OQ-84-1 to OQ-84-7, stories use the recommended defaults). Decision: DEC-084 answer 1. Builds on SAH-10, SAH-12, SAH-51-2, ADR-025 D6.
Module: billing (stage move through the existing opd.api `VisitBillingPort`).
Mockup screen: Billing (sidebar → Billing) (compose panel and `collectBtn`, mockup lines 3009-3013, 3051-3104; "collect" action 4859-4867; Queue card 2738 and 2754)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (the platform also has counsellor). Only owner and front desk may close, the same as collecting today. The clinic always comes from the signed-in user.
Wording: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST. Test data only ("Test Patient", "Dr Test").

### SAH-84-1 · As front desk, I want to close a visit whose bill is ₹ 0, so that free visits leave Billing with a record of who closed them
Priority: Must
Screen: Billing counter, compose panel of a bill under "Awaiting payment" (primary button "Close visit · no charge").
## Acceptance criteria
- Given a Billing-stage visit with total ₹ 0 and nothing paid (a free follow-up with no services), when front desk opens it, then:
- the panel shows the "Awaiting payment" pill, the lines, the discount field, the totals with "Balance ₹ 0", and the primary button "Close visit · no charge";
- no payment mode, "Paying now", cash, UPI or card fields show. (rules 1, 11)
- Given that bill, when front desk presses "Close visit · no charge", then in one action:
- the visit moves to Done;
- the bill records who closed it and when (IST);
- the toast "Visit closed with no charge" shows;
- no payment row is written. (rules 3, 7; OQ-84-1, OQ-84-2)
- Given the close succeeded, then:
- exactly one no-charge-close audit entry holds the visit id, bill id, total ₹ 0, the discount percent if any, who and when;
- the existing Billing → Done stage audit is written;
- neither holds the patient's name, mobile number or service names. (rule 8)
- Given the close succeeded, then collected today, the day closing's expected cash and the revenue collected are unchanged. (rule 9)
- Given a Billing-stage bill whose total is above ₹ 0 but whose balance is ₹ 0 because it was paid earlier (for example at the token, SAH-86), then the same button shows and closes it the same way. (rule 1)
- Given the owner, then the owner closes in the same way. (rule 2)
- Given a billing, doctor, visiting doctor, nurse, pharmacist or counsellor user who opens the bill, then:
- no button shows, and the notice "Only the front desk or the owner can take payments. You can view this bill." shows;
- a direct close call answers 403 and changes nothing. (rule 2)
- Given another clinic's visit id, then 404 and nothing changes; given no login, then 401. (rule 2)
- Given the consultation line is a free follow-up inside the window, then the notice "Follow-up within <n> days. Consultation is free under your fee rules." shows ("at 50%" or "at full fee" for the other settings). (rule 12)
- Given SAH-85 is built, then after the close the bill has its number and the Receipt sheet opens (SAH-85-4); until then no number and no sheet. (rule 14)
- Given a slow network:
- the button shows it is working and cannot be pressed again until the answer arrives;
- on failure or timeout, a notice says nothing was closed and the button is available again. (rule 6)
## Notes for design
The button takes the Collect button's place (mockup `collectBtn`). The panel must know the visit stage to choose between the bill view and the "Settled" view. Today any ₹ 0 bill shows "Settled" (billing-counter.page.html:122-176). The architect decides how the stage reaches the screen.

### SAH-84-2 · As front desk, I want the close refused when the bill changed meanwhile, so that a visit with something to pay is never closed for free
Priority: Must
Screen: Billing counter, compose panel (refusal notice; the bill reloads).
## Acceptance criteria
- Given front desk A has a ₹ 0 bill open and front desk B adds a ₹ 150 service to it, when A presses "Close visit · no charge", then:
- the close is refused and the visit stays in Billing;
- no close audit is written;
- "This bill now has ₹ 150 to pay. Collect the payment instead." shows;
- the bill reloads with the new line and the Collect button. (rule 4; OQ-84-5)
- Given a discount was lowered meanwhile so the balance is above ₹ 0, then the close is refused in the same way with the new balance. (rule 4)
- Given the recheck and the stage move run in one transaction, then the visit is never Done with an unpaid balance because of this close. (rule 4)
- Given the visit was already closed or paid to Done by someone else, when A presses close, then:
- nothing changes;
- "This visit is already closed." shows;
- the panel shows the Done view. (rules 5, 6)
- Given A double-taps or the network retries the same close, then the visit is closed once and one audit entry exists. (rule 6)
- Given a visit at Waiting, Vitals, With doctor or Counselling, when the close is called directly, then "This visit is not awaiting payment" and nothing changes. (rule 5)
- Given any refusal, then the message names amounts only, never the patient. (privacy)
## Notes for design
The refusal shows where other bill errors show (the notice above the actions). The reload uses the existing bill read.

### SAH-84-3 · As front desk, I want ₹ 0 bills shown clearly on the counter and the Queue, so that none is left behind
Priority: Must
Screen: Billing counter left list ("Awaiting payment", "Dues", "Paid today"); Queue cards in the Billing and Done columns.
## Acceptance criteria
- Given a ₹ 0 bill in Billing, then it is under "Awaiting payment" with the token, the patient, "<doctor> · <n> item(s)" and "₹ 0", in the same order as other bills. (rule 10)
- Given an earlier-day ₹ 0 bill still in Billing, then it also shows under "Awaiting payment" and can be closed. (rule 10)
- Given the close succeeded, then the visit is under none of "Awaiting payment", "Dues" or "Paid today". (rule 10; OQ-84-3)
- Given no bill waits after the close, then the panel shows "Nothing to bill right now." with the count of visits paid today, as built. (empty state)
- Given the Queue board:
- a ₹ 0 visit in Billing shows the action "Collect ₹ 0", which opens its bill at the counter;
- after the close, its Done card reads "Closed". (rule 13; OQ-84-4)
- Given the Today screen, then "bills pending" still counts only balances above ₹ 0. (rule 10)
- Given another clinic's ₹ 0 bills, then they never appear in this clinic's lists. (tenant)
## Notes for design
List item and card are as drawn; only the ₹ 0 case is new.

## Out of scope for SAH-84
Automatic Done at the sign; bill number and Receipt sheet (SAH-85); WhatsApp (Later); bill cancellation; moving a Billing card by hand on the Queue (unchanged); IPD and course bills.

## Traceability
Rules 1, 3, 7, 8, 9, 11, 12, 14 -> 84-1. Rule 2 -> 84-1, 84-3. Rules 4, 5 -> 84-2. Rule 6 -> 84-1, 84-2. Rules 10, 13 -> 84-3.

## Open questions
In `docs/requirements/SAH-84.md`: OQ-84-1 to OQ-84-7 (OQ-84-3 and OQ-84-6: go-live check: accountant). Stories use the recommended defaults.

## Status log
- 19 Nov 2026 · written by the business-analyst from DEC-084 answer 1, the java-developer findings (BUG-emr-finish-no-call), the mockup billing counter and the current code. Awaiting human approval of `stories`.

---------------------------------------------------------------
# DRAFT 3 · docs/requirements/SAH-85.md
---------------------------------------------------------------
# SAH-85 · Visit receipt: bill number, print and reprint for an OPD visit bill
Module: billing
Mockup screen: Billing (sidebar → Billing) (Receipt sheet `openReceipt` and `receiptHTML`, mockup lines 3021-3034; bill number `billNo`, line 2990; receipt opened after every collect, line 4867; "Settled" panel, lines 3061-3063; Patients file "Receipt" on a past visit, line 3874; Clinic setup → Fees and billing "Bill format", lines 3592-3595)
Source: human, chat, 19 Nov 2026, DEC-084 answer 2 (visit receipt, own story now). Earlier notes: DEC-054 ("no visit receipt operation in billing.yaml"), ADR-022 H4 ("RC-<n>" for course payments only), SAH-10 AC6 ("simple receipt").
Stories: `docs/stories/SAH-85.md`.

## Problem
Current behaviour:
- billing.yaml 0.4.1 has receipts only for treatment course payments: `GET /courses/{courseId}/receipts/{receiptNo}`, numbered RC-<n> per clinic (SAH-58-5, ADR-022 H4).
- A visit bill has no number, no receipt read and no print.
- The counter shows a paid bill as an inline table in the "Settled" view (billing-counter.page.html:136-169). It has the lines, discount, total, and "Paid · <mode> · by <name>". It has no clinic letterhead, bill number, date, "Balance due" line or Print.
- SAH-10 left numbering out ("number series/invoice numbering beyond a simple per-tenant counter if needed later").

Mockup:
- **Receipt sheet:** title "Receipt", buttons "Print" and "Done". The subtitle "Sent on WhatsApp to <mobile>" is Later.
- **Body:**
- Letterhead: the clinic name and address. At the right: "Bill", the bill number and the date.
- Patient line "<name> · <age> <sex>" and "T-<token> · <doctor>".
- Lines with "× <qty>" and "(follow-up)".
- "Discount <n>% · <reason>" and "− ₹ x", then "Total".
- Payment lines "Paid · <mode> · <reference> · <time>" and "Refund · …".
- "Balance due", then the footer "Healthcare services are exempt from GST."
- **Bill number** "SSC/26-27/0418": clinic code / financial year / four digits. Before the first payment the bill reads "Draft · numbered on payment". The number is given at the first payment at the counter (line 4862) or at the token (line 4086).
- **When the receipt opens:** after every collect, whether full, part or a ₹ 0 close.
- **Settled panel:** shows the receipt, "Send again" (Later), and "Refund" for the owner or "Refunds need the owner" for others.
- **Patients → file → Visits:** a "Receipt" button on a past visit with total > 0. It is shown to every role except nurse. The "Bills" tab lists bills with total > 0 only.
- **Clinic setup → Fees and billing → "Bill format":**
- "Number series SSC/26-27/0418", shown, not editable;
- the "UPI QR printed on the bill" switch;
- "Bills are never deleted, only cancelled with a reason · Keeps the bill series unbroken for your CA", always on.
- **Admin portal billing configuration** (not built): "Bill number prefix =org.code/26-27/", and "Charge GST on taxable items", off by default, with the note "Consultation is exempt; use for cosmetics or retail items".
- **Document templates:** "Bill and receipt · Bill · A5 · English".

## Who uses it (roles) and in which setting
| Role | What they do |
| --- | --- |
| front desk, owner | See the receipt after collecting or closing; print and reprint. |
| billing | Open, print and reprint (as course receipts). |
| doctor, visiting doctor | As drawn, see "Receipt" on a past visit in the Patients file (OQ-85-5). |
| nurse | No receipt and no money figures (as drawn, `showMoney`). |
| pharmacist, counsellor | No access (403). |

Setting: the OPD billing counter and the patient's file at the front desk.

## In scope / Out of scope
In scope
- A bill number series per clinic.
- A receipt read for a visit bill.
- The Receipt sheet with Print after a collect and after a ₹ 0 close.
- Reprint from the counter (Done and Due bills) and from the Patients file.
- The print layout.
- Showing the series in "Bill format".

Out of scope
- Sending on WhatsApp and PDF files (Later, DEC-049). "Send on WhatsApp" shows switched off with "Needs the WhatsApp service", as SAH-58-5.
- GST tax invoices: GSTIN, SAC or HSN codes, tax split (OQ-85-6).
- A UPI QR on the receipt (OQ-85-7).
- Bill cancellation.
- IPD bills and deposit receipts; pharmacy invoices; course receipts (unchanged); token slip and case paper print.

## Business rules (numbered)
1. **When a bill is numbered.** A bill gets its number once, at the first payment recorded on it. That is a counter payment, or a token-time payment under SAH-86. A no-charge close under SAH-84 also numbers the bill (as drawn; OQ-85-4). Before that, the bill header reads "Bill · Draft · numbered on payment". A number never changes and is never reused. A refund or a later payment gives no new number.
2. **Format** "<clinic code>/<yy-yy>/<nnnn>", for example "TST/26-27/0001".
 - The financial year runs 1 April to 31 March (IST) and is taken from the date the number is given.
 - Numbers are consecutive per clinic per financial year and start at 0001 (OQ-85-2). They have at least four digits and grow past 9999.
 - There are no gaps: a failed or rolled-back payment takes no number.
 - Two counters at the same moment never get the same number.
 - **Go-live check: accountant.**
3. **Clinic code** per OQ-85-1. Once the first number is given it is fixed for the series.
4. **Separate from course receipts.** Course payments keep RC-<n> (OQ-85-3).
5. **Content, in the drawn order:**
 - clinic name and address;
 - "Bill", the number, and the bill date (the date the number was given, "24 Oct 2026");
 - "<patient name> · <age> <sex>" and "T-<token> · <doctor>";
 - each line with its name, "× <qty>" when above 1, "(follow-up)" on a follow-up consultation, and the amount (price × qty);
 - the discount line "Discount <n>% · <reason>" with "− ₹ x";
 - "Total";
 - each payment and refund in time order, as "Paid" or "Refund" · mode · reference if any · time (with the date when it is not the bill date) · who collected (SAH-10 AC6; the course receipt's "collected by"), with the amount (refunds shown "− ₹ x");
 - "Balance due";
 - the footer "Healthcare services are exempt from GST." (OQ-85-6).
6. **Formats.** ₹ 1,25,000, with paise shown only when not whole rupees (₹ 1,250.50); 24 Oct 2026; 11:05 AM; IST.
7. **The receipt shows the bill as it is now.** It includes all payments and refunds to date and the balance due. A reprint has the same number. A receipt after a part payment shows the balance due.
8. **Where it opens.**
 - Automatically after each successful collect at the counter and after a no-charge close (as drawn).
 - Reprint from the counter's panel of a Done bill ("Settled") or a Due bill.
 - Reprint from the Patients file, on a past visit with total > 0 (as drawn).
9. **Print.**
 - Browser print of the receipt only: no menu, buttons or other patients.
 - A5 portrait, English (the drawn template "Bill and receipt · A5 · English").
 - A long bill continues on the next page, with the total and the balance on the last page.
10. **WhatsApp.** "Send on WhatsApp" is shown switched off with "Needs the WhatsApp service" (SAH-58-5, DEC-049). The drawn "Sent on WhatsApp to <mobile>" and "Send again" are not shown.
11. **GST.** Phase 1 has no GST registration on the clinic profile, no taxable flag on services, and the "Charge GST on taxable items" setting is not built. So the receipt has no GSTIN and no tax lines. **Go-live check: accountant** (OQ-85-6).
12. **Access.**
  - Owner, front desk and billing may read and print. Doctor and visiting doctor per OQ-85-5.
  - Nurse, pharmacist and counsellor get 403.
  - Another clinic's bill is 404; no login is 401.
  - A bill without a number has no receipt (404 receipt-not-found), and the button is not shown.
13. **Privacy.**
  - The receipt (name, items, amounts) is for the screen and print only.
  - It is never logged and never in a URL; it is addressed by visit id.
  - It is never kept in browser storage.
  - Opening or printing it is not audited, as for course receipts.
14. **Bills paid before release** are numbered per OQ-85-8.
15. **A numbered bill is never deleted** (as drawn, "Always on").

## Patient-safety and privacy points
- No clinical content on the receipt: no diagnosis and no medicines.
- The patient name and amounts are never in logs, URLs or browser storage.
- The print shows one patient only.

## Plan or entitlement (which plan/module unlocks it)
OPD billing counter, every plan. No new permission.

## Open questions for the human
**Go-live check: accountant** on every billing and tax point.
- **OQ-85-1 Clinic code.**
- Options: (A) three capital letters from the initials of the clinic name, padded with X, shown read-only in "Bill format" (as drawn) and fixed at the first number; (B) the owner sets it once before the first bill (not drawn as editable); (C) no prefix ("26-27/0001").
- Recommended: A.
- **OQ-85-2 Restart each year.**
- Options: (A) restart at 0001 every 1 April, as the drawn "26-27" implies; (B) never restart.
- Recommended: A.
- **OQ-85-3 One series or two.**
- Options: (A) visit bills in the new series, course payments stay RC-<n>; (B) course payments move to the bill series.
- Recommended: A.
- **OQ-85-4 ₹ 0 bills.**
- Options: (A) as drawn: a no-charge close takes a number and gets a receipt with "Total ₹ 0", "Balance due ₹ 0"; (B) no number and no receipt.
- Recommended: A.
- As drawn, the Patients file shows "Receipt" only when the total is above ₹ 0, so a ₹ 0 receipt is printed at the close only.
- **OQ-85-5 Who may open receipts.**
- Options: (A) the course-receipt rule: owner, front desk and billing only, which means hiding "Receipt" from doctors in the Patients file, unlike the drawing; (B) as drawn: owner, doctor, visiting doctor, front desk and billing, but not nurse, pharmacist or counsellor.
- Recommended: B (the receipt holds no clinical data).
- **OQ-85-6 GST.**
- Options: (A) phase 1 has no GST lines, and the footer reads as drawn; (B) add GSTIN, SAC or HSN codes and tax lines now (new design and data).
- Recommended: A. Before go-live, an accountant confirms whether the footer is right for every clinic, and whether clinics selling taxable items (cosmetic procedures, retail) need a GST tax-invoice story.
- **OQ-85-7 UPI QR on the receipt.** The switch exists, but the receipt drawing has no QR and the clinic has no UPI ID field. Recommended: later.
- **OQ-85-8 Bills paid before release.**
- Options: (A) number them once at release, oldest first payment first, in the financial year of that payment; (B) leave them without a number.
- Recommended: A.
- **OQ-85-9 Reprint mark.**
- Options: (A) no "Duplicate" mark (as drawn); (B) "Duplicate copy" on reprints.
- Recommended: A.
- **OQ-85-10 Extra fields.**
- Options: (A) as drawn: no UHID, no clinic phone; (B) add UHID and clinic phone, like the course receipt.
- Recommended: A.

---------------------------------------------------------------
# DRAFT 4 · docs/stories/SAH-85.md
---------------------------------------------------------------
# SAH-85 · Stories: visit receipt (bill number, print and reprint)
Requirement: `docs/requirements/SAH-85.md` (rules 1 to 15; OQ-85-1 to OQ-85-10, stories use the recommended defaults). Decision: DEC-084 answer 2. Builds on SAH-10, SAH-58-5, ADR-022 H4, SAH-84, SAH-86.
Module: billing.
Mockup screen: Billing (sidebar → Billing) (Receipt sheet, mockup lines 3021-3034; bill number line 2990; collect 4859-4867; Settled panel 3061-3063; Patients file "Receipt" 3874; Fees and billing "Bill format" 3592-3595)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (and counsellor). The clinic always comes from the signed-in user.
Wording: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST. Test data only.

### SAH-85-1 · As the owner, I want every visit bill to get a number from one unbroken series, so that my accountant can check the books
Priority: Must
Screen: Billing counter bill header ("Bill <number>" or "Bill · Draft · numbered on payment").
## Acceptance criteria
- Given a bill with no payment, then its header reads "Bill · Draft · numbered on payment". (rule 1)
- Given the first payment on a bill is recorded (at the counter, or at the token under SAH-86), then the bill gets the next number of the clinic's series for the current financial year, for example "TST/26-27/0001". The header shows it. (rules 1, 2)
- Given a later payment or a refund on the same bill, then the number does not change. (rule 1)
- Given a payment that fails or is rolled back, then no number is used and the next bill gets the next number, without a gap. (rule 2)
- Given two counters record first payments on two bills at the same moment, then they get two different consecutive numbers. (rule 2)
- Given the first number of a new financial year (on or after 1 April, IST), then the series starts at 0001 with the new year, for example "TST/27-28/0001". (rule 2; OQ-85-2)
- Given another clinic, then its series is independent and its numbers are never visible here. (tenant)
- Given course payments, then they keep their own "RC-<n>" numbers. (rule 4; OQ-85-3)
- Given bills paid before this release, then they are numbered once, oldest first payment first. (rule 14; OQ-85-8)
- Given a numbered bill, then there is no way to delete it. (rule 15)
## Notes for design
The clinic code comes per OQ-85-1. Numbering must be gap-free and safe under concurrency: a locked counter, not a database sequence (db-engineer).

### SAH-85-2 · As front desk, I want the receipt to open after I collect, with Print, so that the patient leaves with proof of payment
Priority: Must
Screen: Receipt sheet (`openReceipt`), opened after "Collect".
## Acceptance criteria
- Given a successful collect (full or part), then the Receipt sheet opens with the title "Receipt" and the buttons "Print" and "Done". (rule 8)
- Given the sheet, then it shows, in order:
- the clinic name and address;
- "Bill", the number and the bill date (for example "24 Oct 2026");
- "<patient> · <age> <sex>" and "T-<token> · <doctor>";
- each line with "× <qty>" when above 1 and "(follow-up)" on a follow-up consultation, and its amount;
- the discount line with percent and reason;
- "Total";
- each payment as "Paid · <mode> · <reference if any> · <time> · <collected by>" with its amount;
- "Balance due";
- the footer "Healthcare services are exempt from GST." (rules 5, 11)
- Given a part payment, then "Balance due" shows the remaining amount, for example "₹ 200". (rule 7)
- Given amounts, then they read as "₹ 1,25,000", with paise only when not whole rupees; times read as "11:05 AM" (IST). (rule 6)
- Given "Print", then the browser prints only the receipt (A5 portrait, English), with no app menu, buttons or other patients. A long bill continues on the next page, with the total and the balance on the last page. (rule 9)
- Given the sheet, then "Send on WhatsApp" shows switched off with "Needs the WhatsApp service". No "Sent on WhatsApp to <mobile>" text shows. (rule 10)
- Given the receipt is shown or printed, then no log line, URL or browser storage holds the patient's name, the items or the amounts. (rule 13)
- Given a slow network after the collect, then the sheet shows a loading state. If the receipt cannot load, a notice says the payment is saved and the receipt can be printed from the bill. (error case)
## Notes for design
The receipt view replaces the inline "Settled" table (billing-counter.page.html:136-169) and is reused by 85-3 and 85-4. Print layout uses print styles. No PDF.

### SAH-85-3 · As front desk or billing, I want to reprint a visit receipt from the counter or the patient's file, so that I can give a copy later
Priority: Must
Screen: Billing counter panel of a Done ("Settled") or Due bill; Patients → file → Visits → "Receipt".
## Acceptance criteria
- Given a Done bill with nothing left to pay, then the panel shows the receipt with Print. The owner also sees "Refund"; others see "Refunds need the owner". (rule 8)
- Given a Due bill, then the panel offers its receipt with the payments so far and "Balance due". (rules 7, 8)
- Given the Patients file, a past visit with total above ₹ 0 and a number, and a role that may see receipts, then "Receipt" opens the same sheet. A visit with total ₹ 0 shows no "Receipt" button there. (rule 8; OQ-85-4)
- Given a reprint after a refund or a later payment, then the receipt has the same number and shows every payment and refund to date with the current balance. (rule 7)
- Given owner, front desk or billing, then they may open and print. Given doctor or visiting doctor, then they may too (OQ-85-5 default B). (rule 12)
- Given nurse, pharmacist or counsellor, then no "Receipt" button shows and a direct read answers 403. (rule 12)
- Given another clinic's visit, then 404; given no login, then 401; given a bill without a number, then no button and a direct read answers 404. (rule 12)
- Given a reprint, then it carries no "Duplicate" mark (OQ-85-9). Opening or printing is not audited. (rule 13)
## Notes for design
The Patients file button is as drawn (line 3874). The read is addressed by visit id, never by name.

### SAH-85-4 · As front desk, I want a receipt after I close a ₹ 0 visit, so that a free visit also has a numbered record
Priority: Should
Screen: Receipt sheet after "Close visit · no charge" (SAH-84-1).
## Acceptance criteria
- Given a no-charge close (SAH-84-1), then the bill gets the next number and the Receipt sheet opens. It shows the lines (for example "Consultation (follow-up) ₹ 0"), "Total ₹ 0", no payment lines and "Balance due ₹ 0". (rule 1; OQ-85-4)
- Given "Print", then it prints as in 85-2.
- Given the human answers OQ-85-4 with B, then the close takes no number and no sheet opens. This story is dropped; SAH-84 is unchanged.
## Notes for design
Same sheet as 85-2.

### SAH-85-5 · As the owner, I want to see my bill number series in Clinic setup, so that I know how bills are numbered
Priority: Could
Screen: Clinic setup → Fees and billing → "Bill format" (mockup lines 3592-3595).
## Acceptance criteria
- Given the "Bill format" panel, then "Number series" shows the next number, for example "TST/26-27/0012", read-only. (rules 2, 3)
- Given the panel, then "Bills are never deleted, only cancelled with a reason" shows with the note "Keeps the bill series unbroken for your CA" and the pill "Always on". (rule 15)
- Given any role other than owner, then the panel is read-only, as built for other setup panels. (roles)
## Notes for design
The "UPI QR printed on the bill" switch stays as built. Its effect on the receipt is out of scope (OQ-85-7).

## Out of scope for SAH-85
WhatsApp and PDF (Later); GST tax invoice; UPI QR on the receipt; bill cancellation; IPD and pharmacy documents; course receipts (unchanged).

## Traceability
Rules 1, 2 -> 85-1, 85-4, 85-5. Rule 3 -> 85-1, 85-5. Rule 4 -> 85-1. Rules 5, 6, 9, 10, 11 -> 85-2. Rules 7, 8 -> 85-2, 85-3. Rules 12, 13 -> 85-2, 85-3. Rule 14 -> 85-1. Rule 15 -> 85-1, 85-5.

## Open questions
In `docs/requirements/SAH-85.md`: OQ-85-1 to OQ-85-10 (all billing points: go-live check: accountant). Stories use the recommended defaults.

## Status log
- 19 Nov 2026 · written by the business-analyst from DEC-084 answer 2, the mockup Receipt sheet and Bill format, billing.yaml 0.4.1 and SAH-58-5. Awaiting human approval of `stories`.

---------------------------------------------------------------
# DRAFT 5 · docs/requirements/SAH-86.md
---------------------------------------------------------------
# SAH-86 · Collect the consultation fee before the consultation ("At the token")
Module: opd and billing (token issue in opd, payment in billing, the workflow move in opd)
Mockup screen: Queue (sidebar → Queue) (New token sheet `summaryHTML`, `modalHTML` and `issue`, mockup lines 4048-4092; Queue card "Paid" pill, line 2750; workflow skip in `moveForward`, line 2647; appointment "Create token", lines 2824-2831; Billing counter totals "Paid so far", line 3076; Clinic setup → Fees and billing "When is the fee collected?", line 3575; Today lead line, line 2711)
Source: human, chat, 19 Nov 2026, DEC-084 answer 3 (make "collect before the consultation" work, checked against the mockup billing screens). Earlier: SAH-12 scope decision (1 Nov 2026: fee timing "before" not built), SAH-15 (lead line left out), SAH-4 and SAH-7 (the setting).
Stories: `docs/stories/SAH-86.md`.

## Problem
Current behaviour:
- The clinic setting "When is the fee collected?" (At the token / After consultation / Split) is stored in `opd.opd_settings.fee_timing` but used nowhere.
- The stored default is "before": migration V2026_11_01_1017, `FeeSettingsService.DEFAULTS` (FeeSettingsService.java:23), and the setup wizard's pre-selected value (setup-wizard.page.ts:193).
- Token issue ignores the setting (`VisitService.issue`, VisitService.java:135-155). The New token dialog has no collect step (new-token-dialog.component.html).
- The sign ignores it (`VisitService.finishConsultation`, VisitService.java:294-318).
- A full payment outside Billing fails. `recordPayment` calls `advanceToDone`, which throws "This visit is not awaiting payment" (VisitBillingAdapter.java:102-104), so the request is rolled back with a 400.
- By code reading, a part payment at any stage is accepted with no stage rule. A full payment of a Due (a Done visit) may fail the same way (java-developer to confirm).
- ADR-025 D6: the sign never moves the visit straight to Done.

Mockup:
- **New token sheet, walk-in, timing not "after", fee above ₹ 0:**
- "Consultation fee ₹ 400" (without "· collected after consultation");
- a row "Collect now by" with Cash / UPI / Card (Cash pre-selected);
- the button "Issue token and collect".
- **After issue:** "Token issued" with the notice "₹ 400 collected by UPI.", and the buttons "Print slip", "Next token" and "Done". The bill is numbered.
- **Free fee:** "Free" and "Issue token".
- **Appointment "Create token":** only the toast "Token T-<n> created for <name> · <doctor> · collect ₹ 400. Sent on WhatsApp." No payment is recorded.
- **Queue card:** a green "Paid" pill until Done.
- **Workflow:** the move into Billing is skipped when the timing is "before" and the balance is ₹ 0.
- **Billing counter:** totals show "Paid so far − ₹ 400".
- **Today lead:** "fee collected at the token".
- **Defaults:** the mockup's default timing is "After consultation" (lines 2595-2597; admin default "After consultation").

## Who uses it (roles) and in which setting
| Role | What they do |
| --- | --- |
| front desk, owner | Issue a walk-in token and collect the fee in the same action; collect the rest at Billing if services were added; refunds stay owner-only. |
| nurse | May issue tokens (`CREATE_ROLES`) but not collect: issues without collecting (OQ-86-9). |
| doctor, visiting doctor | Unchanged consultation. After the sign, the visit goes to Done or Billing by the rule below. |
| billing, pharmacist, counsellor | No change. |

Setting: the front desk of a busy OPD clinic at token time; the billing counter after the consultation.

## In scope / Out of scope
In scope
- Collecting the consultation fee in the walk-in New token sheet when the timing is "At the token".
- The "Paid" pill on the Queue card.
- Skipping Billing after the consultation when nothing is left to pay; going to Billing when services were added.
- What appointment arrivals and Emergency "Send to OPD" tokens do.
- Which timing a visit follows.
- Existing clinics at release.
- The Today lead line.

Out of scope
- "Split" (OQ-86-3).
- Switching the Billing step off in the OPD workflow (SAH-12 keeps Billing always on).
- A payment gateway and a UPI "waiting for payment" state at the token (not drawn; the mode is a record only).
- A discount at the token (OQ-86-5).
- WhatsApp (Later).
- The token slip print ("Print slip" as built).
- IPD and treatment course payments.

## Business rules (numbered)
1. **Applies only to "At the token"** (stored "before"). "After consultation" is unchanged. "Split" is unchanged until its own story.
2. **A visit follows the timing in force when its token was issued**, even if the owner changes it later in the day (OQ-86-2; like the workflow version in SAH-12).
3. **Walk-in New token sheet** (Queue, Today, and Patients → file → New token all open it).
 - When the timing is "At the token" and the fee is above ₹ 0, the summary shows "Consultation fee ₹ 400" and the row "Collect now by" (Cash / UPI / Card, Cash first), and the button reads "Issue token and collect".
 - When the fee is ₹ 0, it shows "Free" and "Issue token".
 - Under "After consultation", it shows "Consultation fee ₹ 400 · collected after consultation" and "Issue token" (as drawn).
4. **Issue and collect is one action.** The token and a payment of the full consultation fee (the visit's fee after the follow-up rule) are saved together, or neither is saved. The amount is not editable and has no reference field (not drawn). The mode is a record only.
5. **Only roles that may collect** (owner, front desk) see the collect row. A nurse issues the token without collecting, and the fee is collected at Billing after the consultation (OQ-86-9).
6. **After issue,** the "Token issued" sheet shows "₹ 400 collected by <mode>." (as drawn). The bill gets its number (SAH-85-1). The receipt is reachable from the counter (OQ-86-6).
7. **Appointment "Create token"** (as drawn): no payment is recorded; the toast reads "Token T-<n> created for <name> · <doctor> · collect ₹ 400." (the WhatsApp part is Later). The fee is collected at Billing after the consultation, because the balance is above ₹ 0 (OQ-86-4).
8. **Emergency "Send to OPD" token** (`issueTokenForDoctor`): no collection; Billing after the consultation.
9. **Queue card.** A green "Paid" pill shows while the visit is not Done and its balance is ₹ 0 with money paid (as drawn).
10. **Leaving the doctor or Counselling.** When the next step would be Billing (by the sign, "Consult done" or "Counselled") and the visit follows "At the token" with a balance of ₹ 0 after the sign's Bill-today lines are added, the visit goes to Done and skips Billing. Otherwise it goes to Billing as today. Counselling, when on, still comes first. Follow-up creation is unchanged.
11. **The sign** ("Check before completing") shows the next step as Done in that case. The sign's outcome stage is Done, and the Queue card moves to Done. This changes ADR-025 D6 for "At the token" only.
12. **At Billing,** a visit with a token payment shows "Paid so far − ₹ 400" and the balance (as drawn). Collecting the rest moves it to Done, as today. A balance of ₹ 0 there closes per SAH-84.
13. **Bill-today lines and the fee after a token payment** (OQ-86-7):
  - The token payment does not lock the doctor's Bill-today lines; only counter payments do ("Already billed. Ask billing to adjust.").
  - The consultation fee is not recomputed after a token payment: a visit-type change by the doctor leaves the fee as paid.
  - **Go-live check: accountant.**
14. **Payments outside Billing.** Only the token-time payment (rule 4) is allowed before Billing. Counter payments are allowed in Billing and on Dues (OQ-86-8).
15. **Money.** Token payments count in collected today, the day closing's expected cash (cash) and the revenue modes, like any payment.
16. **A patient who leaves before the doctor** is refunded by the owner from the bill (OQ-86-6). **Go-live check: accountant.**
17. **Existing clinics at release** per OQ-86-1. Recommended: all set to "After consultation" (what they run today), and "After consultation" becomes the default for new clinics (as drawn).
18. **Payment never blocks care.** The doctor opens the EMR and signs whatever the payment state. An unpaid token never stops the queue.
19. **Today lead** (as drawn): "… · fee collected at the token", "… · fee collected after consultation", or "… · consultation at token, procedures after".

## Patient-safety and privacy points
- Payment state never blocks the consultation, the EMR or an Emergency visit (rule 18).
- A failed collect leaves no half-issued token: both are saved or neither (rule 4). The front desk can then issue without collecting (nurse path) or retry.
- Audit and logs never hold the patient's name or mobile number; the payment audit is as today (visit id, mode, amount).

## Plan or entitlement (which plan/module unlocks it)
OPD, every plan. No new permission: token rights (SAH-9) and collect rights (SAH-10) as today.

## Open questions for the human
**Go-live check: accountant** on every billing point.
- **OQ-86-1 Existing clinics and the default (must answer).** The stored default is "before", but clinics on it run "after" in practice.
- Options: (A) at release, set every existing clinic to "After consultation", make it the default for new clinics and in the setup wizard (as drawn), with one audit entry per clinic; (B) switch "before" behaviour on for every clinic stored as "before"; (C) ask each owner at first login.
- Recommended: A.
- **OQ-86-2 Which timing a visit follows.**
- Options: (A) the timing at token issue, kept with the visit (like the workflow version, SAH-12 AC3); (B) the current setting (as the mockup computes).
- Recommended: A.
- **OQ-86-3 Split.**
- Options: (A) out of scope, behaves as "after" until its own story; (B) include now (the mockup collects at the token for split too, but never skips Billing).
- Recommended: A.
- **OQ-86-4 Appointment arrivals.**
- Options: (A) as drawn: a reminder in the toast, and the fee is collected at Billing after the consultation; (B) a collect step in "Create token" like the walk-in sheet (not drawn); (C) a counter list of tokens not yet paid (not drawn).
- Recommended: A now, B once drawn.
- **OQ-86-5 Discount at the token.**
- Options: (A) none (not drawn); a needed concession is given as a refund by the owner or at Billing; (B) a discount field in the sheet.
- Recommended: A.
- **OQ-86-6 Refund and receipt for a token payment when the patient leaves before the doctor.**
- Options: (A) the counter's "Paid today" also lists bills with a token payment today, so the owner can open the bill, refund and print the receipt (not drawn: the mockup lists Done visits only); (B) through a visit cancel (not built).
- Recommended: A.
- **OQ-86-7 Doctor's lines and fee after a token payment.**
- Options: (A) the token payment does not lock the Bill-today lines, and the fee is frozen once paid at the token; (B) today's rule applies (any payment locks the lines and stops fee recompute).
- Recommended: A.
- **OQ-86-8 Payments outside Billing.**
- Options: (A) counter payments only in Billing and on Dues, plus the token payment as its own action; (B) keep today's behaviour (part payments at any stage).
- Recommended: A. The java-developer also confirms whether paying off a Due in full fails today.
- **OQ-86-9 A role that may issue tokens but not collect (nurse).**
- Options: (A) issues without collecting, and the fee is collected at Billing after the consultation; (B) cannot issue under "At the token".
- Recommended: A.

---------------------------------------------------------------
# DRAFT 6 · docs/stories/SAH-86.md
---------------------------------------------------------------
# SAH-86 · Stories: collect the consultation fee at the token
Requirement: `docs/requirements/SAH-86.md` (rules 1 to 19; OQ-86-1 to OQ-86-9, stories use the recommended defaults). Decision: DEC-084 answer 3. Builds on SAH-4, SAH-7, SAH-9, SAH-10, SAH-12, SAH-15, SAH-51-2, ADR-025 D6, SAH-84, SAH-85.
Module: opd and billing.
Mockup screen: Queue (sidebar → Queue) (New token sheet lines 4048-4092; Queue card 2750; workflow skip 2647; appointment token 2824-2831; Billing totals 3076; Fees and billing 3575; Today lead 2711)
Roles in every story: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist (and counsellor). The clinic always comes from the signed-in user.
Wording: plain English, sentence case, ₹ 1,25,000, 24 Oct 2026, 11:05 AM IST. Test data only.

### SAH-86-1 · As front desk, I want to collect the consultation fee when I issue a walk-in token, so that the patient pays before seeing the doctor
Priority: Must
Screen: Queue → New token (also Today → New token, Patients → file → New token).
## Acceptance criteria
- Given the clinic timing "At the token", a walk-in and a fee of ₹ 400, then:
- the summary shows "Consultation fee ₹ 400" (no "· collected after consultation");
- the row "Collect now by" shows Cash / UPI / Card, with Cash pressed;
- the button reads "Issue token and collect". (rule 3)
- Given a fee of ₹ 0 (free follow-up), then the summary shows "Free", there is no collect row, and the button reads "Issue token". (rule 3)
- Given "After consultation", then the summary shows "Consultation fee ₹ 400 · collected after consultation" and the button reads "Issue token". (rule 3)
- Given front desk chooses UPI and presses "Issue token and collect", then:
- the token and a ₹ 400 UPI payment are saved together;
- the "Token issued" sheet shows the notice "₹ 400 collected by UPI.";
- the bill has its number (SAH-85-1). (rules 4, 6)
- Given the save fails or times out, then neither the token nor the payment is saved, an error says so, and front desk can try again. (rule 4)
- Given the follow-up rule makes the fee ₹ 200 (50%), then ₹ 200 is collected. The amount cannot be edited in the sheet. (rule 4)
- Given a nurse issues a token under "At the token", then no collect row shows, the button reads "Issue token", and the fee stays to be collected at Billing. (rule 5; OQ-86-9)
- Given billing, doctor, visiting doctor, pharmacist or counsellor, then they cannot issue tokens, as today (403). Another clinic: 404; no login: 401. (roles, tenant)
- Given the Queue board, then the card of a visit paid at the token shows the green "Paid" pill until the visit is Done. (rule 9)
- Given the payment, then it counts in collected today, the day closing's expected cash (for cash) and the revenue modes. (rule 15)
- Given the audit, then the payment entry holds visit id, mode and amount only, never the name or mobile. (privacy)
## Notes for design
New token dialog: `frontend/src/app/features/queue/new-token/new-token-dialog.component.html`. The "Token issued" sheet's "Print slip" stays as built. No UPI waiting state, reference field or discount at the token (not drawn).

### SAH-86-2 · As front desk, I want a visit paid at the token to skip Billing when nothing is left to pay, and to reach Billing when services were added, so that only real bills wait at the counter
Priority: Must
Screen: Queue board (card moves), EMR "Check before completing" next step, Billing counter totals.
## Acceptance criteria
- Given a visit issued under "At the token" with the ₹ 400 fee paid and no Bill-today services, when the doctor signs, then:
- the visit goes to Done, not Billing;
- the sign check shows the next step as Done;
- the Queue card moves to Done. (rules 10, 11)
- Given the same visit with a ₹ 150 "Bill today" service added by the doctor, when the doctor signs, then:
- the visit goes to Billing;
- the counter shows the lines, "Paid so far − ₹ 400" and "Balance ₹ 150";
- collecting ₹ 150 moves it to Done. (rules 10, 12)
- Given Counselling is on, then the visit goes to Counselling first. When "Counselled" is pressed with a balance of ₹ 0, it goes to Done; otherwise it goes to Billing. (rule 10)
- Given front desk or owner presses "Consult done" on the Queue card, then the same rule applies. (rule 10)
- Given the balance is decided, then the Bill-today lines of the sign are counted before the decision. (rule 10)
- Given a visit issued under "After consultation", then it goes to Billing as today, even if the owner switched to "At the token" later. Given a visit issued under "At the token", then it follows that rule even if the owner switched away. (rule 2; OQ-86-2)
- Given an "At the token" visit whose fee was not paid (nurse-issued or appointment arrival), then it goes to Billing. (rules 5, 7)
- Given the doctor adds a Bill-today service on a visit paid at the token, then the EMR may still change or remove it until a counter payment. The consultation fee stays as paid if the doctor changes the visit type. (rule 13; OQ-86-7)
- Given a counter payment is attempted on a visit before Billing (other than the token payment), then it is refused with "This visit is not awaiting payment" and nothing is saved. Payments on Dues still work. (rule 14; OQ-86-8)
- Given any state of payment, then the doctor can open the EMR and sign. (rule 18)
- Given follow-up creation at the end of the consultation, then it is unchanged. (rule 10)
## Notes for design
This changes ADR-025 D6 for "At the token" only (architect). The skip decision must run after the sign's bill-line sync.

### SAH-86-3 · As front desk, I want tokens made without the walk-in sheet to keep working under "At the token", so that no patient is stuck or charged twice
Priority: Should
Screen: Appointments strip "Create token"; Emergency "Send to OPD" (existing).
## Acceptance criteria
- Given "At the token" and an appointment with a ₹ 400 fee, when front desk presses "Create token", then:
- the token is created with no payment;
- the toast reads "Token T-<n> created for <name> · <doctor> · collect ₹ 400.";
- after the consultation the visit goes to Billing, where the fee is collected. (rule 7; OQ-86-4)
- Given a free follow-up appointment, then the toast has no "collect" part. (rule 7)
- Given an Emergency "Send to OPD", then the token is created with no payment and goes to Billing after the consultation. (rule 8)
- Given a patient paid at the token leaves before seeing the doctor, then the owner finds the bill under "Paid today" on the counter, refunds it with a reason as today, and can print the receipt (SAH-85). Other roles see "Refunds need the owner". (rule 16; OQ-86-6)
## Notes for design
No new drawing; the "Paid today" listing of token payments is the recommended default of OQ-86-6.

### SAH-86-4 · As the owner, I want my clinic to keep working as it does today when this is released, and to switch "At the token" on myself, so that the front desk is not surprised
Priority: Must
Screen: Clinic setup → Fees and billing ("When is the fee collected?"), setup wizard step 4.
## Acceptance criteria
- Given an existing clinic stored as "before" at release, then it is set to "After consultation" with one audit entry (who: system, before and after). Its front desk sees no collect row until the owner chooses "At the token". (rule 17; OQ-86-1)
- Given a new clinic, then the wizard and the default show "After consultation" pressed (as drawn). (rule 17)
- Given the owner chooses "At the token" and saves, then new tokens follow it, and visits issued earlier keep their timing. (rules 2, 17)
- Given any role other than owner, then the setting is read-only, as built. (roles)
## Notes for design
Data migration and default change: db-engineer, java-developer (`FeeSettingsService.DEFAULTS`) and angular-developer (setup wizard default). If the human answers OQ-86-1 with B or C, this story changes.

### SAH-86-5 · As front desk, I want the Today screen to say when the fee is collected, so that new staff follow the clinic's rule
Priority: Could
Screen: Today (lead line under the greeting, mockup line 2711).
## Acceptance criteria
- Given "At the token", then the lead line ends "· fee collected at the token"; given "After consultation", "· fee collected after consultation"; given "Split", "· consultation at token, procedures after". (rule 19)
- Given the setting cannot be read, then the lead line leaves the fee part out. (error case)
## Notes for design
SAH-15 left this text out because the timing was not built.

## Out of scope for SAH-86
"Split" (OQ-86-3); Billing step off; payment gateway and UPI waiting state; discount at the token; WhatsApp; IPD and course payments.

## Traceability
Rules 3, 4, 6, 15 -> 86-1. Rule 5 -> 86-1, 86-2. Rule 9 -> 86-1. Rules 10, 11, 12, 13, 14, 18 -> 86-2. Rule 2 -> 86-2, 86-4. Rules 7, 8, 16 -> 86-3. Rule 17 -> 86-4. Rule 19 -> 86-5. Rule 1 -> all.

## Open questions
In `docs/requirements/SAH-86.md`: OQ-86-1 to OQ-86-9 (OQ-86-1 must be answered; billing points: go-live check: accountant). Stories use the recommended defaults.

## Status log
- 19 Nov 2026 · written by the business-analyst from DEC-084 answer 3, the mockup New token sheet, Queue, Billing and Fees screens, and the current code. Awaiting human approval of `stories`.
