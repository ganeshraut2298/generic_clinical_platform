# DEC-070 · Give sheet: sticky header (differs from the mockup) and the late "Undone." message

Date: 19 Nov 2026. Status: OPEN. Raised by: tech lead from code review round 5 #2 and security review round 5 R5-2 (`docs/reviews/SAH-76-code.md`, `-security.md`).
Blocked: the SAH-76 merge (round 5 merge condition 2).

## 1. Sticky header (code review #2)
- **Why it exists:** in round 4 the sheet scrolled to show a refusal and pushed the title and the Close button off screen on a 360 px phone, where there is no Esc key (defect 1). The fix keeps the sheet's top bar (title and Close) fixed at the top while the body scrolls. Angular also added a new colour token `--sheet-solid` (light `#f2f3f5`, dark `#15243e`) so that fields scrolled underneath are hidden behind the bar.
- **The mockup differs:** in the mockup the top bar is not fixed and has no background of its own. At rest the new bar looks the same (QA measured the same pixel values above and below it). It differs only when the sheet is scrolled.
- **Options:**
  - **A (recommended): accept the sticky header and the token.** Pro: Close is always reachable on a phone; it is built and tested (phone and desktop, 25 repeats). Con: a design addition. The token is not in `docs/design/tokens.md`, which only you can edit. Dark is calculated, not measured.
  - **B: keep the old, non-sticky header** and keep the refusal lines scrolled into view without moving the header off screen, for example by scrolling less. Con: this brings back the risk that Close is off screen on a phone, and needs another angular, QA and review round.
- **Merge condition either way:** your own look at the sheet on a real device, light and dark, at desktop width and at 360 px, and with the phone keyboard open (PIN and note boxes). I cannot check these.

## 2. Held "Undone." and refusal messages (security R5-2)
- A late answer to Undo can arrive while the next dose sheet is open. Its message is held until that sheet closes (the DEC-068 rule: no message over an open give sheet). It then names only the medicine, and the nurse may have moved to another patient by then. She could read "Paracetamol undone." as being about the patient on screen. The MAR itself is always correct.
- **Options:**
  - **A (recommended): prefix a held message with the time it happened**, for example "Earlier, 10:42 AM: Paracetamol undone." It is a new text and needs your sign-off. Only held messages get it, which is rare.
  - **B: add the bed label to held messages** (not the patient's name). Con: needs the bed label passed in, and a longer message.
  - **C: accept as it is.** Pro: no change. Con: the confusion risk stays.

## Tech lead defaults (no mockup impact; recorded here)
- **Code review #1 (race):** the next dose's Undo message must not be replaced by a held one. The give-sheet "Given" toast goes through the same queue, and a held Undone/refusal message is dropped when a newer message is queued, because the MAR already shows the state.
- **Security R5-1:** the PIN, second-nurse, reason and ward-line messages are scrolled into view like the other bottom lines.
- **Security R5-4 / code #9:** register the sheet at the end of the constructor; each held message runs in a try/catch.
- **Code #5, #8:** remove the stale mock, rename `whenNoDialog` to `whenNoGiveSheet`, amend DEC-068 (the rule is about give sheets only).
- **Code #3:** a comment above both `--sheet-solid` values lists what they depend on.
- **Security R5-3 (sideways phone, large text):** QA measures 740x360 at 200% text; angular fixes it only if the bars overlap.
- **Code #7, #10:** left as they are (nits).
- **Outside SAH-76:** a session that expires while a give sheet is open may leave the sheet over the login page. A later story.

## Recommendation
1 A, 2 A.

## Answer
(pending)
