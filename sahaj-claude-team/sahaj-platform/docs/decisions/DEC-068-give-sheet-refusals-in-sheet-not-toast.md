# DEC-068 · Give sheet: refusals shown in the sheet, not as a toast (SAH-76 D8)

Date: 18 Nov 2026. Status: ANSWERED (A). Raised by: tech lead from code review round 4 #4 (`docs/reviews/SAH-76-code.md`).
Blocked: the SAH-76 merge (round 4 merge condition 2).

## Context
- **The defect (D8, code review round 3, Major):** on a 360 px phone, the toast at the top covered the give sheet's Close button for up to 8 seconds.
- **The fix (angular, QA-tested 103/103 per project):** no toast is shown while the give sheet is open. Every refusal shows only in its line inside the sheet, which is visible and read out by screen readers. The refusals are:
  - wrong wristband;
  - wrong medicine code;
  - no code loaded;
  - field checks ("Scan the wristband first.", second identifier, high-alert second nurse, "Choose why the dose is not given.");
  - PIN errors;
  - PRN lock;
  - a failed request.
- Toasts still appear after the sheet closes: "Recorded as not given" and the reload answers.
- **The mockup** (`sahaj-mockup.html` about 8451-8456) gives the field-check refusals as toasts, with no line in the sheet. For a wrong wristband it shows both a toast and the line. So the fix differs from the mockup, and under rule 1 that needs your acceptance.

## Options
- **A (recommended): accept.** Refusals show in the sheet only; no toast while the sheet is open.
  - Pro: the Close and Given buttons are never covered. The message sits next to the field it is about and is read out.
  - Pro: QA has already tested it (mar.spec.ts 103/103 on desktop and phone).
  - Con: it differs from the mockup.
- **B: keep a toast for the field-check refusals only, placed below the sheet header** so it cannot cover Close.
  - Pro: closer to the mockup.
  - Con: two messages for the same refusal, and on a 360 px phone the toast can still cover the form.
  - Con: more angular and e2e work, plus another review round.

## Recommendation
A.

## Tech lead defaults in the same round (no mockup impact, recorded here)
- **Code review #5:** the remaining MAR-page toasts are all announced to screen readers, for consistency. These are "Recorded as given" without undo, "Undone." and the marked-in-error toast; the MAR page has no other live region.
- **Code review #6:** a late Undo answer waits until any open sheet is closed before it shows its toast.

## Answer
**Human, 18 Nov 2026:** A. Accept: refusals show in the give sheet only, with no toast while the sheet is open. This deviation from the mockup is accepted. The tech lead defaults for #5 and #6 stand.
