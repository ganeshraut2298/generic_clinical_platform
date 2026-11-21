# DEC-084 · Visit bill gaps found while checking "Complete visit"

Date: 19 Nov 2026 · Raised by: tech lead, from the java-developer check of BUG-emr-finish-no-call · Status: ANSWERED 1-3 (human, chat, 19 Nov 2026); 4 tracked in the surgery story

## Context
The human asked to "verify the bill" after Complete visit. The backend is correct for the normal case: Consultation (doctor fee) plus Bill-today services, with no double billing. The check found these billing gaps; they are billing rules, so the human decides.

## 1. A ₹0 visit never leaves Billing
A free follow-up with no services lands in BILLING with balance ₹ 0. A payment needs at least 1 paise, and only a payment moves the visit to Done. Today the front desk or owner must move the card by hand.
- (a) The sign goes straight to Done when the bill total is ₹ 0. Simplest for staff, but it changes ADR-025 D6 and the counter never sees free visits.
- (b) A "Close bill" action at the billing counter for a ₹ 0 bill (new billing.yaml operation). The counter still sees and closes every visit, which leaves an audit trail. **Recommended.**
- (c) Leave as is, and the counter screen offers the card move. No API change, but it is easy to forget and leaves visits hanging.

## 2. No receipt for a visit bill
billing.yaml has course receipts only (already noted in DEC-054). Options: (a) add a visit receipt (print and reprint) in billing.yaml now, as its own story **(recommended)**; (b) leave it for later.

## 3. Fee timing "collect before the consultation"
The clinic setting is stored but not used. A full payment before Billing fails ("This visit is not awaiting payment"). Options: (a) a story so "before" lets the counter collect at token time and skips Billing after the sign **(recommended, check against the mockup's billing screens)**; (b) hide the "before" setting until it works; (c) leave it.

## 4. Surgery visit bills only the consultation (DEC-054)
OT and procedure charges do not reach the counter. Recommended: track it in the surgery course and OT story, not this bug.

## Answers (human, chat, 19 Nov 2026)
- 1: **(b) "Close bill" at the counter** for a ₹ 0 bill → story SAH-84.
- 2: **(a) visit receipt, own story now** → story SAH-85.
- 3: **(a) make "collect before the consultation" work** → story SAH-86 (checked against the mockup billing screens).
- 4: recommended default (surgery course and OT story).

## Blocked
Nothing for the bug fix itself. Each answer above becomes a story (architect for the billing.yaml change).
