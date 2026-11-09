# SAH-39 · Homepage ambient background (kinetic dot grid)
Module: platform
Mockup screen: Start screen and sign-in (opening screen of the mockup) — only its first part, the public Home page (landing: features, pricing, compare, Start free; `S.screen==='landing'`, function `pLanding`; `docs/design/screen-map.md` has no separate "Home page" row, the human or tech lead may add one), hero section `id="h-hero"` ("Clinic software that works the way your OPD already runs."). Built in `frontend/src/app/features/home`. **The background itself is not in the mockup: this is a human-accepted deviation, see "Deviations from the mockup".**
Source: Human, 7 Nov 2026 (pasted spec "Kinetic micro-texture", then "only do this change for homepage / entry page"). Recorded in `docs/decisions/DEC-024-kinetic-background-layer.md`. Tech lead opened SAH-39 on 7 Nov 2026.

## Problem
The public homepage is the first thing a clinic owner sees. The mockup gives it a flat page background. The human wants a quiet, living texture behind the page: a grid of small grey dots that, when the mouse comes near, split into four small coloured points and then settle back. It must add polish without hurting reading, without slowing the page on cheap laptops and phones, and without touching any text, layout or behaviour of the homepage. The same effect must never appear in the signed-in clinical app.

## Who uses it (roles) and in which setting
- **Visitor** (a prospective clinic owner or doctor, not signed in), on a desktop or laptop with a mouse or trackpad in a clinic office or at home. This is the only audience that sees the effect.
- **Visitor on a phone or tablet (touch)**, and **visitor who prefers reduced motion**: see the still dot grid only, no movement.
- **Owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist** (signed in, in the clinic or ward): never see the effect. Signed-in pages have no canvas, no extra listeners and no extra cost. These roles are named so that the "no change to the signed-in app" rule can be tested.

## In scope / Out of scope
In scope (homepage only):
1. A standalone component `KineticBackgroundComponent` (selector `app-kinetic-background`) mounted once in the home page (`HomePage`), not in the app shell.
2. Theme tokens `--bg-base`, `--dot-base`, `--node-a`, `--node-b`, `--node-c`, `--node-d` in the global theme, read at runtime.
3. Still dot grid, pointer-driven splitting into four micro-nodes, spring return, and all guardrails (`data-surface`, `data-dot-exclude`, `.surface-solid`, `.surface-glass`, edge mask) on the homepage.
4. Idle loop, reduced-motion and touch fallback, devicePixelRatio cap, clean-up on destroy.
5. Inputs `interactionRadius`, `gridSpacing`, `friction`, `enabled`, and a `KineticBackgroundConfig` interface.
6. A short section in the frontend README (written by the angular-developer) explaining the `data-surface` and `data-dot-exclude` contract.
7. Unit and browser tests for all of the above, and a contrast check of every homepage text.

Out of scope:
- The signed-in app shell, every clinical screen, panel, table, form, dialog and card in OPD, IPD, billing, HRA and Team (DEC-024 narrowed the request to the homepage). No `.surface-*` class, no `data-surface`, no wrapper, no `z-index` or `isolation` change in any of them.
- The sign-in / start screen and the sign-up screen (OQ-39-2: default no).
- Any change to homepage text, layout, field order, links, scenes, calculator, enquiry sheet or any other behaviour.
- Any new library. Any server change, API, table, tracking or analytics. Notifications. Documents.
- Per-clinic or per-user settings for the effect. A user-facing on/off switch (reduced-motion is respected automatically).
- Dark-theme tokens (OQ-39-5).
- Editing `docs/design/` (human-owned). The human may later add the layer to the mockup.

## Deviations from the mockup
Every point below differs from, or is absent in, `docs/design/mockup/sahaj-mockup.html`. The human accepted the background on the homepage only (DEC-024, follow-up of 7 Nov 2026). Reviewers must not flag these as mockup mismatches, and must flag anything else that differs.
- D1. A new animated `<canvas>` layer sits behind the homepage content. The mockup has no such layer.
- D2. New theme tokens (`--bg-base` #F8FAFC, `--dot-base` #E2E8F0, `--node-a` rgba(59,130,246,0.45), `--node-b` rgba(248,113,113,0.40), `--node-c` rgba(52,211,153,0.45), `--node-d` rgba(167,139,250,0.45)). The mockup defines none of them.
- D3. The homepage page background becomes `--bg-base` #F8FAFC behind the dots. The mockup page background is `--bg` #ebf1f8 with a left-to-right gradient (`--bg-grad`). To be confirmed in OQ-39-4.
- D4. Dots on a 28 px grid are visible on open background. The mockup has no dot texture.
- D5. A pointer-driven effect (splitting dots in four colours) that the mockup does not have. It includes a red-family point (`--node-b`), see OQ-39-1.
- D6. New utility classes `.surface-solid` (solid white) and `.surface-glass` (`backdrop-filter: blur(16px)`, background rgba(255,255,255,0.85)) on homepage blocks that hold text over open background. The mockup has no frosted glass. These blocks gain a white or frosted panel look that the mockup does not show (OQ-39-6).
- D7. A radial `mask-image` fades the layer toward the viewport edges.
- D8. Dots near the header/navigation fade out within 120 px of it.
- D9. New component and attributes in the homepage markup: `data-surface`, `data-dot-exclude`, `app-kinetic-background`. These are invisible.

## Business rules (numbered)
Numbers in square brackets are the human-given parameters. They are inputs or constants, not decisions for the developer.

**Scope and layering**
BR1. **Homepage only.** The component is mounted once, inside `HomePage`. It is not in the app shell. On every other route (sign-in, sign-up, any signed-in page, 404) there is no canvas, no listener, no observer and no animation frame.
BR2. **One layer.** There is exactly one `<canvas>` in a fixed, full-viewport layer, `pointer-events: none`, behind the homepage content. The homepage content sits above it in its own stacking context. The layer never receives clicks, focus or text selection and never blocks scrolling, links, buttons, forms, the enquiry sheet or Material overlays.
BR3. **Nothing else changes.** No homepage text, layout, field order, link, scene, form behaviour or other behaviour changes. No change to any non-homepage file except the global theme tokens and utility classes (BR4, BR9), and the frontend README section.

**Tokens**
BR4. The six tokens (D2) live in the global theme. The component reads them at runtime with `getComputedStyle`. If a token is missing or unreadable, the component uses the spec value as a fallback and does not fail.

**Static grid**
BR5. Dots on a square grid every **[28] px** (`gridSpacing`), radius about **[1.6] px**, colour `--dot-base`. The grid covers the whole viewport and is redrawn after resize.

**Interaction (fission)**
BR6. **Radius.** A dot is affected only when the pointer is within `interactionRadius` of it. Default **[90] px**, and **90 px is the hard maximum**: a larger value passed to the input is clamped to 90.
BR7. **Split amount.** For a dot at distance d from the pointer, d at most 90 px: target split = 1 − d / 90. At d = 0 it is 1. At d of 90 px or more it is 0.
BR8. **Look of a split.** An affected dot fades toward nothing (fully gone at split 1) and four micro-nodes of radius about **[1.25] px** appear and move diagonally outward by up to about **[11] px** at split 1: top-left `--node-a`, top-right `--node-b`, bottom-left `--node-c`, bottom-right `--node-d`. Distance moved scales with the split amount.
BR9. **Spring return.** Each dot keeps a split value s and a velocity v. Each frame: `v += (target − s) * 0.14; v *= friction; s += v;` with `friction` default **[0.75]**. When both |s| and |v| are below **[0.02]**, s and v are set to exactly 0. A dot whose pointer has gone returns to its original grid position and original look exactly (no residue, no drift).

**Guardrails**
BR10. **Surfaces block the trigger.** When the pointer is over any element with, or inside an element with, the attribute `data-surface` (`event.target.closest('[data-surface]')`), the pointer produces no new split anywhere. Dots already split return by BR9. `data-surface` is set on the homepage's text blocks, cards, the setup assistant card, forms (the enquiry sheet form), the FAQ, the pricing table and cards, and the compare table and cards.
BR11. **Text never on the raw canvas.** Every homepage block that holds text over open background uses `.surface-solid` or `.surface-glass`. The two classes are defined in the global theme: solid is solid white; glass is `backdrop-filter: blur(16px)` with `background: rgba(255,255,255,0.85)`. They are used only on homepage blocks, and only where text would otherwise sit on the canvas. Text positions do not move because of them (BR3).
BR12. **Edge mask.** The layer has a radial `mask-image` that fades the dots and nodes toward the viewport edges.
BR13. **Header exclusion.** Elements marked `data-dot-exclude` (the homepage header and navigation) clear the dots around them: dots within **[120] px** of such an element fade to zero opacity, with a smoothstep curve (zero at the element, full at 120 px). Split nodes in that zone are scaled the same way, so no coloured point shows under the header.
BR14. **Re-measure.** The component re-measures the viewport and the `data-dot-exclude` rectangles when the window is resized, when the observed elements change size (`ResizeObserver`), and when the page scrolls (so that exclusion stays right if the header moves; the mockup header is sticky).

**Performance**
BR15. **Idle loop.** Drawing runs on `requestAnimationFrame`. When no dot has s or v other than 0 and the pointer has been inactive (no move, or left the window), the loop is cancelled and no further frame is requested. A pointer move, resize or scroll restarts it.
BR16. **Outside Angular.** The loop and all pointer listeners run outside Angular's zone. Pointer events cause no change detection.
BR17. **Pixel ratio.** The canvas is sized with `devicePixelRatio` capped at **[2]**.
BR18. **Quiet fallback.** When `prefers-reduced-motion: reduce` matches, or `pointer: fine` does not match (touch devices), only the still dot grid is drawn: no pointer listeners, no frames after the first draw, no splitting. If either media query changes while the page is open, the component switches accordingly without a reload.

**Lifecycle and API**
BR19. **Clean-up.** On destroy (for example navigating from the homepage to the sign-in page) the component removes every listener (pointer, resize, scroll, media query), disconnects its `ResizeObserver`, cancels any pending frame, and the canvas leaves the DOM.
BR20. **Inputs.** `@Input()` (or equivalent Angular input) `interactionRadius` (default 90, clamped to 90), `gridSpacing` (default 28), `friction` (default 0.75), `enabled` (default true). When `enabled` is false: no canvas drawing, no listeners, page shows its normal background. A `KineticBackgroundConfig` interface describes these values and the constants of BR5, BR8, BR9, BR13 and BR17.
BR21. **No extra data.** The component makes no network request, stores nothing (no cookie, no `localStorage`), logs nothing, and handles no patient data or personal data. No new library.
BR22. **Safe failure.** If a 2D canvas context cannot be created, the component does nothing and the homepage works exactly as without it. An error in drawing never breaks a button, link or form.

**Accessibility**
BR23. **Contrast holds.** The WCAG 2.2 AA contrast of every homepage text stays at or above 4.5:1 (3:1 for large text, 3:1 for icons and control borders), measured on the worst case: text over the lightest area of the page with the strongest dot or node that can be drawn under it. Colour is never the only signal; the layer carries no meaning.
BR24. **Hidden from assistive tools.** The canvas is decorative: hidden from the accessibility tree (`aria-hidden`), not focusable, not announced. Keyboard focus order, focus rings and reading order of the homepage do not change.
BR25. **Docs.** The frontend README has a short section on the contract: what `data-surface` means (pointer over it never triggers), what `data-dot-exclude` means (dots near it fade), when to use `.surface-solid` and `.surface-glass`, and that the layer is homepage only. Written by the angular-developer.

**Regression**
BR26. The existing homepage unit tests and Playwright e2e tests still pass unchanged. The homepage at 360 px and at 1440 px looks the same as before except for the deviations D1 to D8: the same position and size of every text, control and card (within 1 px).

## Patient-safety and privacy points
- No patient or clinical data is on the homepage and none is added. The effect is for anonymous visitors only; it is never mounted in a screen that shows a patient.
- The red-family `--node-b` is kept only because no clinical data is shown. It must never appear in a screen where red means an alert (the signed-in app). BR1 and its tests guarantee this (OQ-39-1).
- The component stores and sends nothing (BR21). It does not read the pointer position for anything but drawing, and does not keep it after the page is left.
- Accessibility is a safety concern for older users and low-vision users: contrast (BR23), reduced motion (BR18), and a layer that cannot catch clicks (BR2).
- Low-end devices: the idle loop and touch/reduced-motion fallback (BR15, BR18) keep the page usable on cheap laptops and phones.

## Plan or entitlement
None. The homepage is public and shown in every plan. No plan flag, no module flag, no per-clinic setting. The signed-in app, which depends on plans, is not touched.

## Open questions for the human
Full text and proposed defaults are in `docs/stories/SAH-39.md` section "Open questions". Summary:
- **OQ-39-1** The red `--node-b` colour on a marketing page. Default: keep the spec colour (rgba(248,113,113,0.40)), homepage only.
- **OQ-39-2** Show the layer on the sign-in / sign-up / start screen too? Default: no, homepage only.
- **OQ-39-3** Should `enabled` be switchable by an environment or config flag? Default: input only, on for the homepage, no env flag.
- **OQ-39-4** Should `--bg-base` #F8FAFC replace the mockup page background #ebf1f8 (and its gradient) on the homepage? Default: yes, as in the spec, listed as deviation D3.
- **OQ-39-5** Dark theme: the tokens are light only. Default: if the homepage can be shown in the dark theme, the layer is not drawn there until the human supplies dark tokens.
- **OQ-39-6** Hero text and section headings get a white or frosted panel (D6). Default: apply `.surface-glass` to the hero text block and `.surface-solid` to cards that already are white; section headings only where the contrast check fails. Human confirms the look after seeing it.
