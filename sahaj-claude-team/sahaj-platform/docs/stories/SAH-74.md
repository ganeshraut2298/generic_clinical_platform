# SAH-74 · Login page motion and animated background
Mockup screen: Start screen and sign-in (opening screen of the mockup) — the sign-in part only (`pLogin`, mockup lines 4636 to 4661; "Welcome back", tabs "Log in" and "New clinic", Mobile number, Send OTP, OTP step).
Module: platform
Requirement: `docs/requirements/SAH-74.md` (rules BR1 to BR9). Reference: `docs/stories/SAH-39.md`.

**Difference from the mockup: motion and background only, signed off by the human in DEC-050 (13 Nov 2026).** Labels, fields, field order, the mobile field, Send OTP, the OTP step and errors stay exactly as in the mockup.

Role in these stories: **not signed in** (visitor or any clinic user before login). No tenant data on this page; "other tenant" and "wrong role" do not apply, and signed-in pages must show nothing from this feature (story 5). Frontend only, no API change.

Test levels: **[U]** unit, **[B]** browser test (Playwright, by QA).

---

### SAH-74-1 · As a person opening the login page, I want the left panel and the card to come in softly, so that the page feels polished without making me wait
Priority: Must

## Acceptance criteria
- Given the login page opens (on the "Log in" tab), when it loads, then the left panel (pill, "Welcome back", text, three ticks) and the card come in with a short fade and small move, each finishing in about 0.4 s or less. [B]
- Given the entrance is running, when the user taps or clicks the mobile field, types, or presses Tab, then it works from the first frame: the field is not hidden, not disabled, not covered, and keyboard focus and typed digits are not lost. [B]
- Given the entrance, then only `transform` and `opacity` are animated, and the page layout does not shift (layout shift score from this feature is 0; the position of every field is the same before, during and after). [B]
- Given the user opens the page from the home page "Log in", then the mobile field gets focus as in the mockup and the entrance does not remove it. [B]
- Given the user switches to "New clinic", or moves from the mobile step to the OTP step, then labels, fields and order are exactly as in the mockup and they are visible at once. [B]
- Given the user's system asks for reduced motion, then there is no entrance: the panel and the card are in place at the first frame. [U] [B: emulate reduced motion]
- Given a slow network or a slow phone, when the page shows, then the fields are never held back waiting for an animation to start or end. [B: 4x CPU throttle]

## Notes for design
- The entrance must not need JavaScript to reveal content: if the animation code does not run, the page is fully visible.

---

### SAH-74-2 · As a person choosing between "Log in" and "New clinic", I want a highlight that slides to the chosen tab, so that the change is easy to see
Priority: Must

## Acceptance criteria
- Given the "Log in" tab is selected, when the user presses "New clinic" (and the other way), then the highlight slides to it in a short time (about 0.25 s or less), and the heading, text and fields change at the same moment as in the mockup (not after the slide). [B]
- Given either tab, then only the selected tab has `aria-pressed="true"`, the group keeps its label "Account", and the keyboard (Tab, Enter, Space) works as before; focus rings are visible during and after the slide. [B]
- Given the tab is changed twice quickly, then the highlight ends on the last chosen tab and never stays between tabs. [B]
- Given the browser window is resized or text size is changed, then the highlight still lines up with the selected tab. [B at 360 px and 1440 px]
- Given the user's system asks for reduced motion, then the highlight changes tab with no slide. [U] [B]
- Given the slide, then it does not shift the layout, and the contrast of "Log in" and "New clinic" text on and off the highlight stays at 4.5:1 or better. [B]

## Notes for design
- The mockup's selected tab look (`.seg`, `aria-pressed`) is kept; only the move of the highlight is new.

---

### SAH-74-3 · As a person on the login page, I want the same quiet animated background as the home page, so that the sign-in page and the home page feel like one product
Priority: Must

## Acceptance criteria
- Given the login page opens, when it has loaded, then exactly one `<canvas>` exists in a fixed full-viewport layer behind the content, `pointer-events: none` and `aria-hidden="true"`, using the same component, tokens and settings as the home page (SAH-39). [B]
- Given a mouse over open background, when it moves, then dots near it split and settle back as in SAH-39; given the pointer is over the left panel, the card, the tabs, the fields or buttons (`data-surface`), then no dot splits because of that move. [B]
- Given the login header (brand, "Admin portal", "Home"), then it is marked `data-dot-exclude` and dots within 120 px of it fade out, as in SAH-39. [B]
- Given the page is shown on a touch device, then only the still dot grid is drawn and touching causes no splitting. [U] [B: emulate touch]
- Given the 2D canvas cannot be created, then nothing is thrown and the user can still type the number and press Send OTP. [U]
- Given the canvas, then it never takes focus or clicks, and typing, paste, the keyboard, the mobile field, Send OTP, "Change number" and the OTP field all work with it running. [B]
- Given the user stays on the page without moving the pointer, then no animation frame runs after the first draw and after the last dot settles (idle loop). [U] [B: rAF spy over 2 s]
- Given the user leaves the page (to the home page, the setup wizard or the app), then the canvas is removed and every listener, observer and pending frame is cleaned up; coming back five times still gives one canvas. [U] [B]
- Given the feature runs, then it makes no network request, writes no storage and no console output, and never reads or keeps the mobile number or the OTP. [B]

## Notes for design
- This amends SAH-39-5 (criteria 1 and 5 said the sign-in screen has no canvas) for the login page only; QA updates those two checks for this page. The signed-in pages, the setup wizard and the admin portal keep no canvas.
- Page background colour: OQ-74-1.

---

### SAH-74-4 · As a user with reduced motion, a low-end phone, or low vision, I want the page to stay calm, readable and fast, so that I can log in without trouble
Priority: Must

## Acceptance criteria
- Given `prefers-reduced-motion: reduce` matches, when the page loads, then there is no entrance, no tab slide, no pointer effect and no animation frame after the first draw; only the still dot grid shows. [U] [B]
- Given the setting changes while the page is open, then the page follows it without a reload. [U: fire the media query change]
- Given every text on the page (left panel, tabs, card, labels, placeholder, hint, error toast) in the 360 px and 1440 px layouts, in the "Log in" and "New clinic" tabs and in the mobile and OTP steps, when contrast is measured on the worst case (lightest background under the strongest dot or node), then normal text is at least 4.5:1 and large text and control borders at least 3:1, and an automated accessibility check reports no new contrast violation compared with the page before this change. [B]
- Given a user who tabs through the page, then the order, focus rings and reading order are the same as before this change, and focus is visible over the background. [B]
- Given a 4x CPU throttle (a cheap phone), when the page opens and the user types a number, then typing and taps stay responsive and there is no layout shift (0 from this feature); QA reports the measured drawing time per frame. [B]
- Given the page is 360 px wide and 1440 px wide, then every field, button and text block is within 1 px of its position without this feature, and there is no horizontal scroll. [B]
- Given the browser window is resized or the phone is rotated, then the canvas covers the new viewport with no blank strip. [B]

## Notes for design
- A frosted or white panel behind the left panel text is allowed only if the contrast check needs it and text does not move (OQ-74-4).
- The numbers (0.4 s, 0.25 s) are proposals for the developer; the human did not set them.

---

### SAH-74-5 · As the product team, we want nothing else on the login page to change and the motion to stay on this page, so that sign-in works exactly as designed
Priority: Must

## Acceptance criteria
- Given the login page with this feature on, then labels, field order, the "+91" prefix, the placeholder "98XXX XXXXX", "Send OTP", the OTP step ("Enter the 6-digit code sent to +91 …", "Verify and continue", "Change number", "Resend code in 0:28"), the terms text and the "Admin portal" and "Home" buttons are exactly as in the mockup. [B: compare with the mockup text]
- Given the mobile field, then it still takes 10 digits as typed (no grouping and no green tick added) and Send OTP is always pressable (no disabled state, no spinner). [B]
- Given the user has not typed or pressed anything (empty field), when the page is open or when the user moves focus into and out of the field, then no red "Enter a 10-digit mobile number" shows. [B]
- Given the user presses Send OTP with fewer or more than 10 digits (or empty), then the red message "Enter a 10-digit mobile number" shows and the user stays on the mobile step, as in the mockup. [B]
- Given the user presses Send OTP with 10 digits, then the OTP step opens and the code-sent message shows, as in the mockup. [B]
- Given the current build shows that red error on an empty field before any action, then it is fixed back to the behaviour above (a defect fix by the angular-developer, not a design change). [B]
- Given the user in the OTP step enters a wrong-length code and presses Verify, then "Enter the 6-digit code" shows as in the mockup. [B]
- Given a signed-in owner, doctor, visiting doctor, nurse, front desk, billing or pharmacist opens any signed-in page, then there is no kinetic canvas, no entrance or tab-slide animation class from this feature, and no `data-surface` or `data-dot-exclude` from it. [B per role]
- Given the setup wizard, the admin portal and the 404 page, then they have no canvas. [B]
- Given the existing login unit and e2e tests, then they pass without edits to them (other than the SAH-39-5 canvas check noted in story 3). [B]
- Given the project dependencies, then `package.json` has no new library. [review check]
- Given `node scripts/validate.mjs frontend` runs, then it passes. [gate]

## Notes for design
- The error check follows the mockup at line 4786: a red toast after Send OTP only. Leaving the field shows nothing in the mockup (OQ-74-2).
- No API, no table: frontend only.

---

## Traceability: rules to acceptance criteria
| Rule | Covered by |
|---|---|
| BR1 motion and background only | 5 (first four criteria) |
| BR2 soft entrance | 1 |
| BR3 sliding highlight | 2 |
| BR4 same background | 3, 5 (signed-in, wizard, admin) |
| BR5 reduced motion | 1, 2, 4 |
| BR6 usable at once, accessible | 1, 2, 4 |
| BR7 performance, no layout shift | 1, 2, 3, 4 |
| BR8 clean-up | 3 |
| BR9 error timing | 5 |
