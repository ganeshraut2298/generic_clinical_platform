# SAH-74 · Login page motion and animated background
Module: platform
Mockup screen: Start screen and sign-in (opening screen of the mockup) — the sign-in part only: function `pLogin`, `docs/design/mockup/sahaj-mockup.html` lines 4636 to 4661 ("Welcome back", "Free for your first year", tabs "Log in" and "New clinic", Mobile number, Send OTP, the OTP step). Same screen in both modes (login and "New clinic"). Frontend only.
Source: Human, chat, 13 Nov 2026, answer to DEC-050 (`docs/decisions/DEC-050-login-page-interactivity.md`), option 4 only. Tech lead opened SAH-74.
Requirement of reference: SAH-39 (`docs/requirements/SAH-39.md`, `docs/stories/SAH-39.md`), the home page background.

## Problem
The human asked to make the clinic login page "more interactive". Options 1 to 3 (smarter mobile field, Send OTP button states, six OTP boxes) were not chosen. Only option 4 was: motion and background. The page must feel alive, and still be usable at once, readable, and fast on a cheap phone.

**Difference from the mockup (signed off by the human in DEC-050, 13 Nov 2026):** the login page differs from the mockup only by motion and background. These are deviations: M1 soft entrance of the left panel and the card; M2 sliding highlight between the two tabs; M3 the SAH-39 animated dot background behind the page (with the SAH-39 deviations D1 to D9 that come with it, such as the page background colour and any panel behind text). Anything else that differs from the mockup is still a defect or goes to the human.

## Who uses it (roles) and in which setting
- **Not signed in** (visitor, or any clinic user before login: owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist), on a phone, tablet or computer in a clinic, ward or home. This page has no role and no tenant yet; no tenant data is shown.
- Signed-in screens are not touched.

## In scope / Out of scope
In scope: M1, M2, M3 on the sign-in page (both tabs, both steps: mobile and OTP); reduced-motion behaviour; contrast and focus checks; no layout shift; one acceptance check on when the "Enter a 10-digit mobile number" error appears.
Out of scope: labels, fields, order, the mobile field behaviour (no grouping, no tick), the Send OTP button (no spinner, no disabled state), the OTP step (one field, "Resend code in 0:28" as in the mockup), errors and toasts, the "Admin portal" and "Home" buttons, the sign-up wizard and every other screen, any API, table, library or setting. A user switch for motion (the system setting is respected). Dark theme (as SAH-39 OQ-39-5).

## Business rules (numbered)
BR1. **Motion and background only.** Every label, field, order, button, link, message and state of the sign-in page stays as in the mockup. Only M1, M2, M3 are added.
BR2. **Soft entrance (M1).** When the page opens, the left panel ("Free for your first year", heading, text, the three ticks) and the card come in softly (short fade and small move). Duration is short (the architect or developer proposes, about 0.4 s at most). It never hides or delays any field or button: the fields can be read, focused and typed in from the first frame.
BR3. **Sliding highlight (M2).** The selected tab's highlight slides between "Log in" and "New clinic" when the tab changes. The tab still switches at once (content and `aria-pressed` change at the same moment as in the mockup, `authmode`). The highlight is visual only.
BR4. **Same background as the home page (M3).** The SAH-39 kinetic dot background (same component, tokens and guardrails: `data-surface`, `data-dot-exclude`, one fixed `pointer-events: none` canvas, `aria-hidden`, idle loop, still grid on touch) shows behind the sign-in page. This amends SAH-39 BR1 and SAH-39-5 for this one page: the sign-in page is now the second place where the layer exists. No other page changes (sign-up wizard, setup, signed-in app, admin portal: no canvas).
BR5. **Reduced motion.** When the system asks for reduced motion (`prefers-reduced-motion: reduce`), M1, M2 and the pointer effect of M3 are all off: no entrance, no slide (the highlight just changes), the still dot grid only, no animation frame after the first draw. If the setting changes while the page is open it follows without a reload.
BR6. **Usable at once and accessible.** Keyboard focus order and focus rings do not change; focus is not lost during motion (the mockup focuses the mobile field when the page opens from the home page, `go`, and the OTP field after Send OTP; that still works). The animation does not move a field under the user's finger or cursor while they tap. Text contrast over the background stays at WCAG 2.2 AA: 4.5:1 normal text, 3:1 large text and control borders, measured on the worst case (as SAH-39 BR23). Where text sits on open background (the left panel), a surface is used to keep contrast, without moving the text.
BR7. **Performance.** No layout shift: only `transform` and `opacity` are animated for M1 and M2, and the cumulative layout shift caused by this feature is 0. The canvas cost follows SAH-39 (idle loop, capped pixel ratio, outside Angular's zone, nothing on touch). The animation work stays small on a low-end phone (4x CPU throttle: scroll and typing stay smooth; QA reports the measured frame time). No new library, no network call, no storage, no log.
BR8. **Clean-up.** When the user leaves the page (to the wizard, the home page, the app), the canvas, listeners and frame are removed, as in SAH-39 BR19. Nothing from this feature remains on the signed-in pages.
BR9. **Error timing (checked against the mockup).** The mockup (`sendotp`, line 4786) shows "Enter a 10-digit mobile number" (a red toast) only after the user presses Send OTP with fewer or more than 10 digits. It shows nothing on an empty field before that, and nothing when the user leaves the field. Build exactly that: the red error appears only after Send OTP is pressed with an invalid number, never before the user has typed or pressed anything. If the current build shows the error on an empty field, that is a defect to fix back to the mockup (angular-developer), not a design change. The human's wording in DEC-050 also mentioned "after leaving the field"; the mockup does not do that, so it is not built (OQ-74-2).

## Patient-safety and privacy points
- No patient or clinical data is on this page. Mobile numbers are typed here: the feature never reads, stores, logs or sends them (BR7), and the canvas is aria-hidden and cannot capture input or focus.
- Reduced motion and contrast are accessibility and safety concerns for older and low-vision users, and for staff on cheap phones (BR5, BR6, BR7).
- The red `--node-b` dot colour of SAH-39 may appear on a page where an error is red. Colour carries no meaning here, and the error stays a toast and text, but see OQ-74-3.

## Plan or entitlement
None. The sign-in page is public for every plan and every tenant.

## Open questions for the human
Each has a safe default; the team builds the default if there is no answer.
- **OQ-74-1** Page background on the sign-in page. SAH-39 replaced the home page background with `--bg-base` #F8FAFC (deviation D3). Default: the sign-in page gets the same background as the home page, so the two look alike. Alternative: keep the mockup's #ebf1f8 gradient behind the dots.
- **OQ-74-2** Error "after leaving the field". DEC-050 asked for the error "after leaving the field or pressing Send OTP", but the mockup shows it only on Send OTP. Default: follow the mockup (Send OTP only), as the tech lead instructed.
- **OQ-74-3** The red `--node-b` dot near a form with red errors. Default: keep the SAH-39 colour as decided there (OQ-39-1) and review the first look; change the token if it confuses.
- **OQ-74-4** Left panel panel. A frosted panel behind "Welcome back" and the ticks (SAH-39 D6) may be needed for contrast. Default: only where the contrast check fails, no change to text position; the human confirms the look from a screenshot at 360 px and 1440 px.
