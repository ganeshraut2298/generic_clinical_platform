# DEC-050 · Login page: "make it more interactive"

Date: 13 Nov 2026. Status: ANSWERED 13 Nov 2026 (human, chat). Raised by: tech lead.
Request (human, chat, with a screenshot of the clinic login page): "make it more interactive".
Blocked: a new story (next id SAH-74). Nothing else.

## Facts
- The page matches the mockup's start screen (`docs/design/mockup/sahaj-mockup.html` ~4649, "Welcome back", "Free for your first year"; screen-map row "Start screen and sign-in", platform). CLAUDE.md rule 1: anything not in the mockup, or different from it, needs the human's sign-off. "More interactive" is not defined, so the human picks what to add.
- Precedent: SAH-39 added an animated background to the home page with the human's sign-off.
- The screenshot shows the red "Enter a 10-digit mobile number" on an empty field; whether that appears before the user has typed or tapped Send OTP is checked by the angular-developer (a fix if so, not a design change).

## Options (any combination)
1. Smarter mobile field: digits grouped as you type (98765 43210), a green tick when 10 digits are valid, the error only after leaving the field or pressing Send OTP.
2. Send OTP button: disabled until the number is valid, "Sending OTP…" with a spinner, then a smooth move to the code step.
3. OTP step: six boxes, auto-advance, paste the whole code, auto-submit on the 6th digit, "Resend in 0:30" countdown.
4. Motion: soft entrance of the card and the left panel, a sliding highlight between "Log in" and "New clinic", and the home page's animated background behind the page. Respects "reduce motion".

## Recommendation
1, 2 and 3 (they make login faster and clearer); 4 optional.

## Answer (human, chat, 13 Nov 2026)
**Option 4 only: motion and background.** Soft entrance of the card and the left panel, a sliding highlight between "Log in" and "New clinic", and the home page animated background (SAH-39) behind the login page; all motion off under prefers-reduced-motion. Options 1-3 not chosen (the mobile field, Send OTP button and OTP step stay as they are). New story SAH-74, frontend only (no API change). The empty-field error check stays with the angular-developer as a possible fix.
