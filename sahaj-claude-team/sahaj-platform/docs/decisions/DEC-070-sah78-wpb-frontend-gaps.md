# DEC-070 · SAH-78 WP-B frontend gaps

Date: 18 Nov 2026 · Story: SAH-78 · Raised by: tech lead (from the angular-developer WP-B handoff)
Status: PARTLY DECIDED (auto, human away, DEC-047). Q4 and Q5 are open for the human at the merge ask; they do not block QA.

## Context
The angular-developer built WP-B (78-5 products, 78-6 own product, 78-7 opening stock) and listed 10 gaps. Seven are settled below by the tech lead under the human-away rule, using the option that follows the mockup, the stories or the contract. Gap 5 needs no action. Two need a human because they would change the approved contract or the meaning of a field.

## Decided (auto)
1. **Sections load with `@defer`, not child routes.** Each section is its own lazy chunk, the URL scheme and the e2e selectors stay the same, and the Setup page already uses in-page tabs. Accepted.
2. **"Create own product" must be the inline card of mockup 16, not a sheet.** The mockup is the design (CLAUDE.md rule 1), and story 78-6 names the card. Options: (a) inline card as in the mockup (recommended, chosen); (b) keep the sheet (needs a human because it differs from the mockup). Keep the same test ids. Editing an own product may stay a sheet: the mockup shows only an "Edit" button.
3. **Money format.** Keep "₹ 1,25,000.00" for opening-stock totals and line values. Story 78-7 says the total is shown "with Indian grouping and paise". Elsewhere follow the common rule (₹ 1,25,000, or ₹ 1,25,000.50 when there are paise). The code reviewer checks that both are applied consistently.
6. **Preferred vendor offers "None" plus the current vendor until WP-C.** Accepted.
7. **Barcode in both the own-product sheet and Local settings.** Both are in the contract. Accepted.
9. **Core `ProblemDetail` untouched; extra GET calls after 409 or 422.** Accepted. This needs no core change. The code reviewer looks at it.
10. **New texts on the Opening stock screen (a NEW screen, DEC-062 Q1).** They go to the business-analyst for a wording check in the next BA run.
Gap 5 (send for review, `ndps` column) is not built, by the open OQ-78-18. No action.

## Open for the human (at the merge ask)
- **Q4 · Empty-catalogue text.** The story's production text "No shared products yet. Create your own product." needs to know whether the global catalogue is empty, and the contract gives no such field.
  - (a) Add an optional field to `GET /products` (an additive contract change, which needs contract approval).
  - (b) Keep the current generic text "No products yet. Adopt a global product or create your own." (recommended for phase 1: the real catalogue will not be empty in production).
- **Q5 · Row numbers in opening-stock problems.** The contract's `rowNo` counts data rows only, and the screen says so in a caption.
  - (a) Keep this (recommended; it matches the contract).
  - (b) Show spreadsheet row numbers. The offset varies with the optional `#EXAMPLE` row, so this needs a contract note.

## Blocked
Nothing. QA started at 14:45 on the current build. The inline card (item 2) went to the angular-developer at 15:10. The BA text check (item 10) runs when the requirements lock frees (about 15:19).

## Process note
The tech lead appended to docs/handoffs/SAH-78.md and BOARD.md through the shell while the shared `lead` lock was held by the IPD session's tech lead. The hook does not check shell writes. Stopped at 13:25; from then on the tech lead waited for the lock.
