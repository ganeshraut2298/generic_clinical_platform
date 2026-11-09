# SAH-39 · Homepage ambient background (kinetic dot grid)
Mockup screen: Start screen and sign-in (opening screen of the mockup) — only its first part, the public Home page (landing: features, pricing, compare, Start free; function `pLanding`; `docs/design/screen-map.md` has no separate "Home page" row), hero section `id="h-hero"`. The background is a human-accepted deviation (DEC-024, 7 Nov 2026); deviations D1 to D9 are listed in `docs/requirements/SAH-39.md`.
Module: platform
Requirement: `docs/requirements/SAH-39.md` (rules BR1 to BR26).

Roles in these stories: **visitor** (not signed in) sees the effect. Owner, doctor, visiting doctor, nurse, front desk, billing and pharmacist (signed in) never do. There is no tenant data on this page; "other tenant" is covered by the rule that signed-in pages carry nothing from this feature.

Test levels used below: **[U]** unit test (Jasmine/Karma or Jest, whichever the scaffold uses, with a fake canvas context and fake `requestAnimationFrame`), **[B]** browser test (Playwright, written by QA; may spy on `requestAnimationFrame`, `addEventListener` and read canvas pixels).

---

### SAH-39-1 · As a visitor, I want a calm still dot texture behind the homepage, so that the page feels polished while every word stays easy to read
Priority: Must

## Acceptance criteria
1. Given the homepage opens on a desktop with a mouse, when it has loaded, then exactly one `<canvas>` exists in a fixed, full-viewport layer behind the content, with `pointer-events: none` and `aria-hidden="true"`, and the homepage content sits above it. [B]
2. Given the page has loaded and the pointer has not moved, then dots show every 28 px in colour `--dot-base` (#E2E8F0) with radius about 1.6 px, and the layer fades toward the viewport edges with a radial mask. [B: pixel sample at a grid point and at the corner] [U: grid positions]
3. Given the global theme, then it defines `--bg-base` #F8FAFC, `--dot-base` #E2E8F0, `--node-a` rgba(59,130,246,0.45), `--node-b` rgba(248,113,113,0.40), `--node-c` rgba(52,211,153,0.45), `--node-d` rgba(167,139,250,0.45), and the component reads them with `getComputedStyle` at run time. [U: change a token, grid colour follows] [B]
4. Given a token cannot be read (missing or empty), when the component starts, then it uses the spec value as a fallback and the page still works. [U]
5. Given the viewport is 360 px wide, and given it is 1440 px wide, when the page loads, then the position and size of every text block, button, card, table, header and footer is within 1 px of the same page without the component (compare with `enabled = false`), and there is no horizontal scroll. [B: bounding boxes at both widths]
6. Given the homepage is resized or the phone is rotated, then the canvas covers the new viewport, the dots are redrawn, and no blank strip appears. [B]
7. Given text sits over open background (for example the hero heading, lede and the section heading blocks), then that block uses `.surface-glass` (blur 16 px, background rgba(255,255,255,0.85)) or `.surface-solid` (solid white), so no text sits directly on the canvas, and the text has not moved (AC5). `.surface-*` classes appear only inside the homepage. [B: computed style] [U: class on those blocks]
8. Given every text on the homepage, in the 360 px and 1440 px layouts, when contrast is measured on the worst case (lightest background with the strongest dot or node drawn under it), then normal text is at least 4.5:1, large text and icons or control borders at least 3:1. An automated accessibility check (axe) reports no new contrast violation compared with the page before this change. [B]
9. Given a user who tabs through the homepage, then the order, focus rings and reading order are unchanged and the canvas is never focusable or announced by a screen reader. [B]
10. Given the 2D canvas context cannot be created (older device, blocked), when the page loads, then nothing is thrown, no canvas error shows, and every button, link and form works. [U]
11. Given the existing homepage unit tests and Playwright e2e tests, when they run, then they pass without any edit to them. [B]

## Notes for design
- Parameters are constants/inputs (BR5, BR17, BR20). Where the spec says "about" (radius 1.6, 1.25, travel 11) the developer uses the stated number; QA tolerates plus or minus 0.5 px in pixel checks.
- AC7: which blocks carry the glass or solid surface is a visual decision to confirm with the human after a first look (OQ-39-6). The surface must not alter text position: it must be painted without adding layout size (architect/developer choose how).
- The page background colour change (D3) is open (OQ-39-4).

---

### SAH-39-2 · As a visitor using a mouse, I want the dots near my pointer to split into four tiny points and settle back, so that the page feels alive without getting in my way
Priority: Must

## Acceptance criteria
1. Given the pointer rests over open background, when it is exactly on a dot, then that dot's split is 1: the dot is gone and four micro-nodes (radius about 1.25 px) sit about 11 px away on the diagonals: top-left `--node-a`, top-right `--node-b`, bottom-left `--node-c`, bottom-right `--node-d`. [U: node positions and colours] [B: pixel colours]
2. Given a dot at distance d from the pointer with d less than 90 px, then its target split is 1 − d/90 (for example d = 45 gives 0.5, and the nodes sit about 5.5 px away). [U]
3. Given a dot at 90 px or more from the pointer, then it does not move or change at all, and the nodes of no dot appear beyond 90 px. [U] [B: pixel sample at 95 px stays the plain dot]
4. Given `interactionRadius` is set above 90 (for example 150), then it is treated as 90. Given it is set below 90 (for example 60), then it is used. Given zero or a negative number, then it falls back to 90 or disables the effect without throwing. [U]
5. Given the pointer moves away or leaves the browser window, when the dots return, then each follows `v += (target − s) * 0.14; v *= friction; s += v` per frame with friction 0.75 by default, and a changed `friction` input changes the settling. [U: frame-by-frame values]
6. Given a dot returns, when both |s| and |v| fall below 0.02, then s and v become exactly 0, the micro-nodes disappear, and the dot is drawn at its original position, size and colour, pixel for pixel equal to its look before the pointer came. [U: exact equality to 0] [B: pixel compare before and after, with a tolerance of zero for the dot centre]
7. Given the pointer is over any element that is, or is inside, `[data-surface]` (text blocks, cards, the setup assistant card, the enquiry form, FAQ, pricing, compare tables), when it moves there, then no dot splits anywhere because of that move; dots already split return by AC5. [U: handler with a target inside and outside a data-surface] [B: hover over a pricing card and over a FAQ item, sample canvas]
8. Given the pointer moves from a `data-surface` element onto open background, then the effect starts at once at the new position. [B]
9. Given the pointer moves quickly across the page, then the effect follows without visible lag and the page scroll, link clicks and button clicks are unaffected (the layer cannot catch them). [B: click a link and a button while the effect runs]
10. Given the page is scrolled, then the effect still follows the pointer (viewport coordinates) and `data-surface` is evaluated for the element under the pointer at that moment. [B]

## Notes for design
- Split looks (BR8): dot fades with the split; nodes scale with the split. The spec does not say whether nodes fade in; default is that node opacity is the token alpha multiplied by the split. The angular-developer records the choice in the README.
- "About 11 px" is read as the distance along the diagonal at split 1.
- Colours of nodes: see OQ-39-1.

---

### SAH-39-3 · As a visitor, I want the area around the header and navigation to stay clean, so that the menu is never lost in the texture
Priority: Must

## Acceptance criteria
1. Given the homepage header and navigation carry `data-dot-exclude`, when the page loads, then dots at the header edge are fully faded, and dots 120 px or further from it are fully drawn, with a smoothstep curve between (at 60 px the opacity is 0.5). [U: smoothstep] [B: pixel sample at 0, 60 and 120 px]
2. Given the pointer is near the header (inside the 120 px zone), then micro-nodes in that zone are scaled by the same curve, and no coloured point appears over or just under the header or its buttons ("Admin portal", "Log in", "Start Free"). [B]
3. Given the browser window is resized (for example 1440 px to 360 px, where the section links hide below 980 px), then the excluded zone follows the new header size. [B]
4. Given the header changes size without a window resize (for example fonts load), then the excluded zone is re-measured through the `ResizeObserver`. [U with a fake ResizeObserver]
5. Given the page is scrolled and the header is sticky, then the excluded zone stays at the header; if the excluded element moves, the zone is re-measured on scroll. [B]
6. Given the browser has no `ResizeObserver`, then the component falls back to resize and scroll re-measuring and does not throw. [U]
7. Given no element has `data-dot-exclude`, then the full grid is drawn and nothing throws. [U]

## Notes for design
- The 120 px is measured from the element's rectangle edge. Architect confirms the exact distance function (rectangle distance versus centre) in the design.
- Re-measuring on scroll is a small addition to the spec (BR14) so a non-sticky element stays correct; it must be throttled to one measure per frame.

---

### SAH-39-4 · As a visitor on a cheap laptop, a phone, or with reduced-motion settings, I want the effect to stay still or off, so that the page stays fast and comfortable
Priority: Must

## Acceptance criteria
1. Given the pointer is inactive and every dot is at rest, when the last dot settles, then the animation frame loop is cancelled and no `requestAnimationFrame` call is made for at least 2 seconds. [U: fake rAF count] [B: rAF spy over 2 s, expect 0]
2. Given the page is loaded and the pointer never moves, then no `requestAnimationFrame` runs after the first draw. [U] [B]
3. Given the loop is idle, when the pointer moves over open background, or the window is resized, or the page scrolls, then the loop restarts. [U]
4. Given `prefers-reduced-motion: reduce` matches, when the page loads, then only the still dot grid is drawn; moving the pointer over open background changes nothing; no pointer listener is attached; no frame runs after the first draw. [U] [B: emulate reduced motion]
5. Given the device does not match `pointer: fine` (touch phone or tablet), then the same still grid only applies, and touching or dragging causes no splitting. [U] [B: emulate touch with `hasTouch`]
6. Given the setting changes while the page is open (reduced motion turned on, or a mouse attached), then the component switches to still or to interactive without a reload, and removes or adds its listeners accordingly. [U: fire the media query change event]
7. Given `devicePixelRatio` is 3, then the canvas backing size uses a ratio of 2; given it is 1, then 1; the canvas still fills the viewport in CSS pixels. [U]
8. Given the loop and pointer listeners, then they run outside Angular's zone: moving the pointer for 3 seconds triggers zero change-detection cycles of the home page. [U: `NgZone` spy] [B]
9. Given a typical 4-year-old laptop profile (4x CPU throttle in the browser), when the pointer moves continuously over open background, then the page stays responsive: scroll and clicks work and the per-frame work stays inside a frame budget (target: drawing under 8 ms per frame in a profile; QA reports the measured number). [B] — see OQ-39-7.
10. Given `enabled = false`, then there is no canvas drawing, no listener, no frame, and the page background is the normal one. [U]

## Notes for design
- Detect the capabilities with `matchMedia('(prefers-reduced-motion: reduce)')` and `matchMedia('(pointer: fine)')`.
- A hybrid device (touch laptop with a mouse) follows the primary pointer as reported by `pointer: fine`; no special case.
- AC9's 8 ms is a proposal for QA to confirm, not a requirement from the human.

---

### SAH-39-5 · As the product team, we want the effect to exist only on the homepage and to leave no trace elsewhere, so that clinical screens stay exactly as designed
Priority: Must

## Acceptance criteria
1. Given a visitor on the homepage, when they click "Log in" or "Start Free" and the sign-in or sign-up page opens, then the homepage canvas is no longer in the DOM, and the component has removed all its listeners (pointer, resize, scroll, media query), disconnected its `ResizeObserver` and cancelled any pending frame. [U: destroy test with spies on `removeEventListener`, `disconnect`, `cancelAnimationFrame`] [B: after navigation `document.querySelectorAll('canvas')` has no kinetic canvas; the add/remove listener counts balance]
2. Given a visitor goes back to the homepage, then exactly one canvas exists again (no duplicate after repeated visits: visit five times, still one). [B]
3. Given a signed-in owner, doctor, visiting doctor, nurse, front desk, billing or pharmacist, when any signed-in page opens (Today, Queue, Appointments, Patients, Billing, IPD pages, Team, Clinic setup), then there is no kinetic canvas, no element with `.surface-solid` or `.surface-glass`, no `data-surface` and no `data-dot-exclude` attribute added by this feature, and no `backdrop-filter` from it. [B: run per role, DOM query on the shell and one page of each module]
4. Given the app shell, then the component is not declared in it; it is imported only by the home page. [U/review: the shell template does not contain `app-kinetic-background`]
5. Given the sign-in, sign-up and start screens (OQ-39-2 default), then they have no canvas. [B]
6. Given the existing signed-in screens' unit and e2e tests (layout, dialogs, sticky sheet footers, Material overlays), when they run, then they pass unchanged. [B]
7. Given the component, then it declares inputs `interactionRadius` (90), `gridSpacing` (28), `friction` (0.75) and `enabled` (true), and exports a `KineticBackgroundConfig` interface; the home page uses the defaults. [U: defaults]
8. Given the component runs, then it makes no network request, writes no `localStorage`, `sessionStorage` or cookie, and writes nothing to the console. [B: network, storage and console listeners stay empty because of this feature]
9. Given the project dependencies, then `package.json` has no new library from this change. [review check]
10. Given the frontend README, then it has a short section that explains: `data-surface` (pointer over it never triggers the effect; put it on text blocks, cards, forms, FAQ, pricing, compare tables), `data-dot-exclude` (dots within 120 px fade out; put it on the header/navigation), when to use `.surface-solid` and `.surface-glass`, and that the layer is homepage only. [review check by code-reviewer]
11. Given `node scripts/validate.mjs frontend` runs, then it passes. [gate]

## Notes for design
- Angular-developer writes the README section (frontend README is under `frontend/`, so the BA does not write it).
- Because the component is standalone and mounted in `HomePage`, lazy loading of the home route already keeps it out of other routes; this is what AC3 and AC5 verify.
- Placement of the canvas layer inside `HomePage` and the stacking of homepage content (own stacking context, no change to Material overlays such as the enquiry sheet) is for the architect; a short note in the architecture folder is enough, no ADR is needed unless the stacking change touches the shell.

---

## Traceability: rules to acceptance criteria
| Rule | Covered by |
|---|---|
| BR1 homepage only | 5-1, 5-2, 5-3, 5-4, 5-5 |
| BR2 one layer, no clicks blocked | 1-1, 2-9 |
| BR3 nothing else changes | 1-5, 1-11, 5-6 |
| BR4 tokens | 1-3, 1-4 |
| BR5 static grid | 1-2, 1-6 |
| BR6, BR7 radius and split | 2-2, 2-3, 2-4 |
| BR8 look of a split | 2-1 |
| BR9 spring return and exact zero | 2-5, 2-6 |
| BR10 data-surface | 2-7, 2-8 |
| BR11 surface classes | 1-7 |
| BR12 edge mask | 1-2 |
| BR13 header exclusion | 3-1, 3-2, 3-7 |
| BR14 re-measure | 3-3, 3-4, 3-5, 3-6, 1-6 |
| BR15 idle loop | 4-1, 4-2, 4-3 |
| BR16 outside Angular | 4-8 |
| BR17 pixel ratio | 4-7 |
| BR18 reduced motion and touch | 4-4, 4-5, 4-6 |
| BR19 clean-up | 5-1 |
| BR20 inputs and config | 2-4, 4-10, 5-7 |
| BR21 no data, no library | 5-8, 5-9 |
| BR22 safe failure | 1-10 |
| BR23 contrast | 1-8 |
| BR24 hidden from assistive tools | 1-1, 1-9 |
| BR25 README | 5-10 |
| BR26 regression, 360 and 1440 px | 1-5, 1-11 |

## Open questions
Each has a safe default. If the human does not answer, the team builds the default; the first three are the human's explicit asks.

- **OQ-39-1 · The red `--node-b` on a marketing page.** In the app, red means alert, and DEC-024 flagged this. Because the homepage shows no clinical data and the effect appears only for anonymous visitors on the homepage, **default: keep the spec colour rgba(248,113,113,0.40)**. The tests in 5-3 guarantee it never shows in the signed-in app. Alternative if the human prefers: replace `--node-b` with a non-red colour (for example amber). It is one token, so changing it later needs no code change.
- **OQ-39-2 · Also on the sign-in / start screen?** **Default: no, homepage only** (the human said "homepage / entry page"). "Entry page" is read as the public homepage, the page before sign-in. If the human means the sign-in screen as well, it is a separate small story, because those screens have forms with one-time codes and PINs and need their own contrast and focus checks.
- **OQ-39-3 · Switch by environment or config flag?** **Default: no flag; `enabled` is an input, true for the homepage.** If the human wants a kill switch without a code change, the proposal is a single build-time value in the Angular environment file; the BA does not propose a server or per-clinic setting.
- **OQ-39-4 · Page background.** The spec's `--bg-base` #F8FAFC differs from the mockup page background (#ebf1f8 with a gradient). **Default: use `--bg-base` behind the dots on the homepage (deviation D3).** White cards may then stand out less against the page; QA checks card borders stay visible (3:1 for borders that carry meaning).
- **OQ-39-5 · Dark theme.** All tokens are light. **Default: if the homepage can be shown in the dark theme, the layer is not drawn there (page looks as before) until the human supplies dark tokens.**
- **OQ-39-6 · Frosted panels on the hero.** `.surface-glass` on the hero text and section heading blocks adds a visible white panel that the mockup does not show (D6). **Default: apply it only to blocks where text would sit on open canvas, without changing text position, and show the human a screenshot at 360 px and 1440 px before the merge.**
- **OQ-39-7 · Performance target.** The spec gives no frame budget. **Default: QA measures on a 4x throttled CPU and reports; a budget of 8 ms per frame is the proposal.** The human may set a different number.
- **OQ-39-8 · Mockup record.** The mockup is not edited. **Default: the deviation stays recorded in this requirement and DEC-024.** The human may add the layer to `docs/design/` later, which is human-owned.
