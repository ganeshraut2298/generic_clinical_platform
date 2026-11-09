# SAH-39 · Homepage ambient background: design note

Story: SAH-39 (`docs/stories/SAH-39.md`, rules BR1 to BR26 in `docs/requirements/SAH-39.md`). Decision record: DEC-024.
Mockup screen: Start screen and sign-in, public Home page part (`pLanding`, hero `h-hero`). The background is a human-accepted deviation (D1 to D9).
Module: platform (frontend only). **No API, no table, no contract change** (`docs/api/` untouched, nothing for db-engineer).
**No ADR.** The app shell (`app.ts`, `features/shell/`), the routes and every signed-in screen stay as they are. The only global files touched are the theme tokens and the public primitives stylesheet (BR3).

Facts this note relies on (checked in the code on 7 Nov 2026):
- Angular 22, **zoneless** (no `zone.js` in `package.json`, no polyfills in `angular.json`). `NgZone` is a no-op zone.
- **No SSR or prerender** (`angular.json` has only the `application` browser build). The home route is lazy (`loadChildren` in `app.routes.ts`).
- The enquiry sheet is a `MatDialog` (`enquiry-sheet.service.ts`), so it renders in `.cdk-overlay-container` on `<body>`, outside `HomePage`.
- `<html data-theme="dark">` is set by `applyTheme()` from the signed-in shell and can still be there when a signed-in user goes back to `/`.
- Unit tests run on Vitest (`@angular/build:unit-test`). Playwright e2e has its own `frontend/e2e/package.json` and two projects: `phone-360` (`hasTouch: true`) and `desktop-1440`.

---

## 1. Mounting and stacking

```
<app-home-page>                        :host display block (not positioned, no stacking context)
  <app-kinetic-background aria-hidden="true">   position: fixed; inset: 0; z-index: 0; pointer-events: none
     <canvas aria-hidden="true">       width/height 100%; radial mask
  <div class="pubwrap">               + position: relative; z-index: 10; isolation: isolate
     <app-home-header>                 sticky, z-index 30 (now relative to .pubwrap)
     <main id="main"> …sections… </main>
     <app-home-footer>
<div class="cdk-overlay-container">   on <body>, fixed, z-index 1000 (Material)
```

- `app-kinetic-background` is the **first child of the `HomePage` template, before and outside `.pubwrap`**. It is not put inside `.pubwrap` even though a fixed flex child would be out of flow: outside, it cannot touch the flex gap or the box of anything.
- **Host styles** (component styles): `display: block; position: fixed; inset: 0; z-index: 0; pointer-events: none; contain: strict; background: var(--bg-base)`. Use `inset: 0`, **never `100vw`/`100vh`** (`100vw` adds a horizontal scrollbar on Windows, which would fail AC 1-5). Host has `aria-hidden="true"` and no `tabindex`.
- **Canvas styles**: `display: block; width: 100%; height: 100%; mask-image` and `-webkit-mask-image` with a radial gradient (stops are a visual choice for the developer, shown to the human with the OQ-39-6 screenshots).
- **Page background (D3, OQ-39-4 default)**: `--bg-base` is painted by the **host** (unmasked), and the mask is on the **canvas** only, so the edges fade to `--bg-base` and never show the old gradient. The global `body` background (`--bg` + `--bg-grad` in `_primitives.scss`) is **not** changed; it stays for every other route. When the component is in mode `off` (section 3) the host has no background, so the page looks as before (AC 4-10).
- **Content wrapper**: add `position: relative; z-index: 10; isolation: isolate` to `.pubwrap` in `features/home/home.page.scss`. No other property changes. Checked: every absolutely positioned element on the homepage already has a positioned ancestor inside its component (`.hstage`, `.jrail`, `.canvas`, `.node`, `.team-row`, header host, `.tbl-wrap`), so making `.pubwrap` positioned changes no containing block of anything visible. Only `.sr-only` elements without a positioned ancestor change their containing block, and they are clipped to 1 px.
- **Sticky header**: `app-home-header` keeps `position: sticky; top: 10px; z-index: 30`. Its z-index now counts inside the `.pubwrap` stacking context. Every other z-indexed homepage element (`.hpanel` 1, `.hch` 2, `.jrec` 2, workflow `.tip` 5, final-cta 1, `.skip` 40 inside the header) is also inside `.pubwrap`, so their order relative to each other is unchanged. Sticky still works: `.pubwrap` has no `overflow`. Same for the sticky `.calc-out` in Compare.
- **Material overlays**: `.cdk-overlay-container` is a child of `<body>` with `z-index: 1000` in the root stacking context, above both `0` and `10`. The enquiry dialog, its backdrop, its sticky sheet footer, snack bars and any tooltip stay above the page and the layer, and are laid out exactly as today. When the dialog blocks scroll (`cdk-global-scrollblock` on `<html>`), the fixed layer still covers the viewport (no transform on any ancestor).
- **Signed-in shell**: not touched. `app-kinetic-background` is imported only by `HomePage`; the shell template, `shell.component.*`, `_shell.scss` and `_console.scss` get no class, attribute, wrapper, `z-index` or `isolation` (AC 5-3, 5-4). Lazy loading of the home route keeps the component's code out of other routes.

## 2. File layout

| Path | Content |
| --- | --- |
| `frontend/src/app/shared/ui/kinetic-background/kinetic-background.component.ts` | Standalone, OnPush component `KineticBackgroundComponent`, selector `app-kinetic-background`, template `<canvas aria-hidden="true">` (rendered only when mode is not `off`), inline host and canvas styles. Signal inputs `interactionRadius` (90), `gridSpacing` (28), `friction` (0.75), `enabled` (true). Lifecycle, listeners, loop, drawing. |
| `…/kinetic-background/kinetic-background.config.ts` | `export interface KineticBackgroundConfig` (the four inputs plus constants), `KINETIC_DEFAULTS` (dot radius 1.6, node radius 1.25, node travel 11, spring 0.14, settle 0.02, max radius 90, exclusion 120, DPR cap 2), `TOKEN_FALLBACKS` (the six spec colours). |
| `…/kinetic-background/kinetic-physics.ts` | **Pure functions, no DOM**, unit-tested on their own (list below). |
| `…/kinetic-background/kinetic-tokens.ts` | `readTokens(style: Pick<CSSStyleDeclaration,'getPropertyValue'>): KineticColours` with trim and fallback per token. |
| `…/kinetic-background/kinetic-physics.spec.ts`, `kinetic-tokens.spec.ts`, `kinetic-background.component.spec.ts` | Unit tests (Vitest). |
| `frontend/src/styles/_tokens.scss` | Six tokens in the light `:root` block: `--bg-base`, `--dot-base`, `--node-a`, `--node-b`, `--node-c`, `--node-d` (spec values), plus one helper token `--glass-surface: rgba(255, 255, 255, 0.85)` so the glass colour is not hard-coded. No dark values (OQ-39-5). |
| `frontend/src/styles/_primitives.scss` | `.surface-solid` and `.surface-glass` (section 4). This file already holds the public-page building blocks; the shell stylesheets are not touched. |
| `frontend/src/app/features/home/home.page.ts` / `.scss` | Mount the component; wrapper styles on `.pubwrap`. |
| `frontend/src/app/features/home/**` | `data-surface`, `data-dot-exclude` and `.surface-*` on the blocks in section 4. |
| `frontend/README.md` | New section "Homepage ambient background" (AC 5-10), written by the angular-developer, including the node-opacity choice (story note SAH-39-2). |

Pure functions in `kinetic-physics.ts` (names are a proposal):
- `clampRadius(r)`: not finite or `<= 0` → 90; otherwise `min(r, 90)` (AC 2-4, architect's choice: fall back, not disable).
- `clampFriction(f)`: not finite, `<= 0` or `>= 1` → 0.75 (a friction of 1 or more never settles, so the loop would never idle).
- `clampSpacing(g)`: not finite or `< 12` → 28 (bounds the dot count; 12 px is a safety floor, not a product value).
- `capDpr(dpr)`: not finite or `<= 0` → 1; otherwise `min(dpr, 2)` (AC 4-7).
- `buildGrid(w, h, spacing)`: columns `i = 0 … floor(w / spacing)`, rows likewise; `x = round((w mod spacing) / 2 + i * spacing) + 0.5`, same for y. Grid is centred, anchored to the **viewport** (the layer is fixed, dots do not scroll), and centred on half pixels so a 1.6 px dot fully covers its centre pixel (makes pixel sampling exact). Returns `Float32Array` of x, y. QA uses the same formula to find sample points.
- `targetSplit(d, radius)`: `d >= radius ? 0 : 1 - d / radius` (AC 2-2, 2-3).
- `springStep(s, v, target, friction)`: `v += (target - s) * 0.14; v *= friction; s += v;` then if `target === 0 && |s| < 0.02 && |v| < 0.02` → `s = 0, v = 0` exactly (BR9). Also returns `settled = |s - target| < 0.02 && |v| < 0.02` (used for the idle rule, section 3); when settled at a non-zero target, s snaps to target and v to 0 (at most 0.22 px of node travel, invisible).
- `nodeOffset(s)`: distance along each diagonal `11 * s`, so per axis `11 * s / √2` (story note "about 11 px is along the diagonal"). Node radius `1.25 * s`. Order: a top-left, b top-right, c bottom-left, d bottom-right.
- `rectDistance(x, y, rect)`: **distance from the point to the rectangle edge**, 0 inside: `dx = max(rect.left - x, 0, x - rect.right)`, `dy` likewise, `hypot(dx, dy)` (answers the SAH-39-3 design note: rectangle distance, not centre distance).
- `smoothstep(t)` with `t = clamp(d / 120, 0, 1)`: `t * t * (3 - 2t)`; 0 at the edge, 0.5 at 60 px, 1 at 120 px (AC 3-1).
- `exclusionFactor(x, y, rects)`: minimum of `smoothstep` over all rects; 1 when there are none (AC 3-7).

## 3. Events, loop and lifecycle

**Modes.** `mode = off | still | interactive`, computed from: `enabled` input, 2D context available, `data-theme` not `dark`, `matchMedia('(prefers-reduced-motion: reduce)')`, `matchMedia('(pointer: fine)')`.
- `off`: `enabled` false, or no context, or dark theme. Nothing drawn, host has no background. With `enabled` false: **no listener, no observer at all** (AC 4-10). With dark theme: only the theme `MutationObserver` stays so the layer comes back if the theme returns to light. With no context: nothing at all, permanently (AC 1-10).
- `still`: reduced motion or not `pointer: fine`. Grid drawn once, redrawn synchronously on resize. Listeners: the two media queries, the theme observer, `ResizeObserver` (or window `resize` fallback). **No pointer, no scroll listener, no `requestAnimationFrame` ever** (AC 4-4, 4-5). Consequence: in `still` the header exclusion is measured at load and resize only; the sticky header moves at most 6 px while it sticks, which is accepted.
- `interactive`: all of `still` plus pointer, scroll and the frame loop.

**Start.** In `afterNextRender` (browser only; also safe if SSR is ever added). Read every browser API from `inject(DOCUMENT).defaultView`; if it is null or lacks `matchMedia`, treat as `still`; if `getContext('2d')` returns null or throws, go `off`. Do not use `@HostListener`, `host: { '(window:…)' }` or template event bindings for these events: in a zoneless app those schedule change detection. Register with `addEventListener` inside `NgZone.runOutsideAngular` (a no-op today; keeps AC 4-8 true if zone.js is ever added). **Never write a signal or call `markForCheck` from a listener or a frame.** The only template state is the mode (to render the canvas or not), changed only on mode switches.

**Listeners (interactive)**, all `{ passive: true }`, all on `window`:
- `pointermove`: ignore `pointerType === 'touch'` (hybrid devices, no special case otherwise). Store `x, y` (viewport, `clientX/Y`) and `blocked = isBlocked(event.target)`, where `isBlocked(t) = !!t?.closest('[data-surface], .cdk-overlay-container')`. Then wake the loop.
- `pointerout` with `relatedTarget === null` (pointer left the window; more reliable than `pointerleave` on `window`) and `blur`: set pointer inactive (all targets 0), wake the loop.
- `scroll` (passive): set a `measurePending` flag and wake the loop; the measuring happens once in the next frame (throttle: at most one measure per frame, story note SAH-39-3). In that frame, if the pointer is active, re-evaluate `blocked` with `document.elementFromPoint(x, y)` (the canvas has `pointer-events: none`, so this returns the page element) (AC 2-10).

**Overlay rule (architect's addition, invisible).** A pointer over anything inside `.cdk-overlay-container` (the enquiry dialog and its dimmed backdrop) counts as a surface: no new split behind an open dialog. `data-surface` is still put on the enquiry `<form>` as BR10 asks.

**Re-measure (BR14).**
- `ResizeObserver` observes **the host** (viewport size in CSS px, also catches phone rotation and the mobile URL bar) and **each `[data-dot-exclude]` element** (header height changes when fonts load or at the 980 px breakpoint, AC 3-3, 3-4). `document.body` is **not** observed: body height changes on every FAQ open or slider move and nothing excluded sits below changing content; scroll re-measure covers position changes.
- Window `resize` is registered too (fallback when `ResizeObserver` is missing, AC 3-6, and catches a DPR change from zoom).
- Resize handling is synchronous inside the callback (ResizeObserver runs before paint): read `capDpr(devicePixelRatio)`, set `canvas.width/height = round(css * dpr)`, `setTransform(dpr, 0, 0, dpr, 0, 0)`, rebuild grid, re-measure rects, full redraw. Resizing clears the canvas, so drawing in the same callback avoids any blank strip (AC 1-6).
- Exclusion rects are `getBoundingClientRect()` of `document.querySelectorAll('[data-dot-exclude]')` (viewport coordinates). Per-dot exclusion factors are cached in a `Float32Array` and recomputed only when a rect actually changed (compare numbers). After the header sticks, scrolling changes nothing, so no full redraw on scroll.

**Theme and tokens.** Tokens are read with `getComputedStyle(document.documentElement)` at start, on resize, and when a `MutationObserver` on `<html>` (`attributeFilter: ['data-theme', 'style']`) fires. `data-theme="dark"` switches to `off` (OQ-39-5 default). Missing or empty token → `TOKEN_FALLBACKS` (AC 1-4). Optional hardening: assign the value to `ctx.fillStyle` and fall back if the browser rejected it.

**Media queries.** Own `MediaQueryList` objects with `addEventListener('change', …)`, so the component removes exactly what it added. Do **not** reuse the root `ReducedMotion` service: its listener lives for the whole app, which would break the "add/remove counts balance" test (AC 5-1). A change recomputes the mode and adds or removes pointer and scroll listeners (AC 4-6).

**Frame loop and idle rule (BR15).**
- `wake()`: if no frame is pending, `requestAnimationFrame(frame)`.
- `frame()`: (1) if `measurePending`, measure; (2) compute targets only for dots inside the pointer's radius (grid index arithmetic, about 30 dots at radius 90 and spacing 28), 0 if pointer inactive or `blocked`; (3) `springStep` for the active set (dots with `s`, `v` or target non-zero), drop dots that reached exactly 0; (4) draw the dirty region; (5) request the next frame **only if** some active dot is not `settled` or a measure is pending. Otherwise stop. A pointer resting over open background therefore idles too once the split dots have settled (stricter than BR15, same visible result).
- **No frame at start**: the first draw is synchronous in `afterNextRender`; `wake()` is called only by pointer, scroll or the window `resize` listener (AC 4-2, 4-3).
- Every listener and frame body is wrapped in `try/catch`; on any error the component tears itself down to `off` silently (no console output, AC 5-8; and `provideBrowserGlobalErrorListeners` would otherwise log it).

**Drawing.** Full draw (start, resize, rect or token change): clear, then for each dot `globalAlpha = exclusion`, fill circle r 1.6 in `--dot-base` (batch dots by quantised alpha to keep the number of `fill()` calls low). **Per-frame draw uses a dirty rectangle**: the union of the active dots' cells, padded by node reach (11 + 1.25 + 1 px), snapped to whole device pixels; `save(); clip(rect); clearRect(rect)`; redraw every dot whose cell meets the rect (rest dots exactly as in the full draw; active dots: dot at alpha `(1 - s) * exclusion`, four nodes at radius `1.25 * s`, offset `11 * s` along the diagonals, alpha `s * exclusion` times the token alpha); `restore()`. The clip makes a returned dot pixel-identical to its first draw (AC 2-6) and needs no second full-size cache canvas (saves about 20 MB at 1440 × 900, DPR 2).

**Clean-up (`DestroyRef.onDestroy`, BR19).** Remove pointer, pointerout, blur, scroll and resize listeners from `window`; `change` listeners from both `MediaQueryList`s; `disconnect()` the `ResizeObserver` and the `MutationObserver`; `cancelAnimationFrame` the pending id; drop the context reference. The canvas leaves the DOM with the component.

**SSR guard.** No prerender today; `afterNextRender` plus the `defaultView` null check are enough.

## 4. Surfaces without moving text

**Rule.** A surface is painted by a pseudo-element, never by padding, margin, border or a wrapper with size. So no box and no text moves (AC 1-5).

In `_primitives.scss`:
```scss
:where(.surface-solid, .surface-glass) { position: relative; }   // zero specificity: a component's own position wins
.surface-solid, .surface-glass { --surface-bleed-x: 12px; --surface-bleed-y: 10px; }
.surface-solid::before, .surface-glass::before {
  content: ''; position: absolute; z-index: -1; pointer-events: none;
  inset: calc(-1 * var(--surface-bleed-y)) calc(-1 * var(--surface-bleed-x));
  border-radius: var(--surface-radius, var(--r));
}
.surface-solid::before { background: var(--surface); }
.surface-glass::before { background: var(--glass-surface); -webkit-backdrop-filter: blur(16px); backdrop-filter: blur(16px); }
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .surface-glass::before { background: var(--surface); }
}
```
Why it works: the element gets **no `isolation` and no z-index**, so its `::before` (z-index −1) paints in the nearest ancestor stacking context (`.pubwrap`): above the canvas, **under the element's own background** (so tinted blocks such as `.notice` keep their tint) and under its text. The bleed (12 px sideways, 10 px vertically) gives the text air without moving it; 12 px fits inside the 16 px `.pubwrap` side padding at 360 px, so there is no horizontal scroll, and it is below half of the smallest gap between homepage blocks (26 px).

**Do not put a `.surface-*` class directly on** an element that (a) is a scroll container (the pseudo-element would scroll and be clipped), (b) is already a stacking context with its own background (the pseudo would paint above that background), or (c) already uses `::before`. In those cases wrap it in a plain `<div>` with no padding or margin carrying the class (a block wrapper in a flex column takes the same box, so nothing moves).

**Contrast finding that decides solid versus glass (architectural, from the tokens).** `--ink-3` (#66778f), used by every eyebrow, `.faint` text, the footer and the journey labels, has a contrast of about 4.36:1 on `--bg-base` #F8FAFC, about 3.7:1 over a `--dot-base` dot, and about 4.4:1 on glass; only solid white gives 4.56:1. So **any block on open background that holds `--ink-3` text must be `.surface-solid`**; `.surface-glass` is safe only where the text is `--ink`, `--ink-2` or `--pri-text` (the hero text). This follows the OQ-39-6 default ("section headings only where the contrast check fails": they fail).

**Blocks by component** (`ds` = `data-surface`, `dx` = `data-dot-exclude`):

| Component | Element | Treatment | Why |
| --- | --- | --- | --- |
| header | `header.lnav.surf` | `dx` + `ds` | Already solid white; exclusion zone (AC 3-1). The `nav` is inside it, so one attribute covers both. |
| hero | `.hero-t` (pill, h1, lede, buttons) | `.surface-glass` + `ds` | Text on open canvas; only ink, ink-2, pri-text. Only glass block on the page. |
| hero | `.hpanel.surf` (Clinic setup assistant card) | `ds` | Already solid. |
| shared/section-head | host of `app-section-head` (all nine sections) | `.surface-solid` + `ds` via the component's `host` metadata (one change, homepage-only component) | Eyebrow is `--ink-3`. |
| setup | `.ssteps.surf`, `.spanel.surf` | `ds` | Already solid. |
| workflow | `.palette.surf`, `.canvas.surf` | `ds` | Already solid. |
| roles | `.visit.surf`, each `.member.surf` | `ds` | Already solid. |
| roles | `.notice.info` (shown when a role is focused) | `.surface-solid` + `ds` | Translucent tint over the canvas; the white goes under the tint. |
| journey | wrapper `<div>` around `.jrail-wrap` | `.surface-solid` + `ds` | Stage labels are `--ink-3` on transparent; `.jrail-wrap` scrolls, so wrap it. |
| journey | `.jdetail.surf` | `ds` | Already solid. |
| specialties | `.spcards` group | `ds` | Cards are already white; attribute on the group also covers the gaps. |
| specialties | `.spprev.surf` | `ds` | Already solid. |
| compare | `.calc-in.surf`, `.calc-out.surf` (sticky) | `ds` | Already solid; table inside. |
| pricing | `.grow.surf`, each `.price.surf` | `ds` | Already solid. |
| pricing | the `<div>` around the billing-period toggle | `.surface-solid` + `ds`, plus `width: fit-content` in `pricing.component.scss` | Toggle labels sit on a translucent tint; the Material toggle group may be its own stacking context, so the class goes on the wrapper. The toggle stays left-aligned, so it does not move. |
| pricing | `p.faint.note` | `.surface-solid` + `ds` | `--ink-3` text. |
| trust | each `.tpanel.surf` | `ds` | Already solid. |
| trust | `.tpoints` | `.surface-solid` + `ds` | Text on open canvas. |
| faq | `.faq` container | `ds` | `details.surf` already solid; container covers gaps. |
| final-cta | `section.final.surf` | `ds` | Already solid. |
| footer | `footer` | `.surface-solid` + `ds` | `--ink-3` text and link. |
| enquiry | `<form>` in `enquiry-sheet.component.html` | `ds` | BR10; it is in the overlay, also covered by the overlay rule. |

**Correction (DEC-025 release audit, code review #2).** The roles row above is wrong: `.member.surf` was not solid, because the global `_console.scss` rule `.member { background: transparent }` loads later and overrode it. Fixed in `roles.component.scss` with a component-scoped `background: var(--surface)`; and the selected specialty card now lays its tint on white.

Already-solid `.surf` cards do not get a `.surface-*` class: they already keep text off the canvas, and the class would have no visible effect. If the human wants the class on them for uniformity, it can be added later without any visual change. Headers of cards are not excluded; only the page header carries `data-dot-exclude`.

## 5. Tests and risks

**Unit (Vitest, angular-developer).**
- Pure functions: `buildGrid` positions and count at 360 × 740 and 1440 × 900; `targetSplit` (0 → 1, 45 → 0.5, 90 and 95 → 0); `clampRadius` (150 → 90, 60 → 60, 0, −5, NaN → 90); `springStep` frame-by-frame values for friction 0.75 and another friction, exact `0` after the snap; `nodeOffset` (s 1 → 11 / √2 per axis; s 0.5 → 5.5 along the diagonal); `rectDistance` inside, beside and at a corner; `smoothstep` at 0, 60, 120; `exclusionFactor` with no rect; `capDpr` (3 → 2, 1 → 1); `readTokens` with empty or missing values.
- Component: jsdom has no 2D canvas (and prints a "not implemented" error), so stub `HTMLCanvasElement.prototype.getContext` with a recording fake context; stub `matchMedia` (with controllable `change`), `ResizeObserver` (fake that the test can fire, and absent for AC 3-6), `requestAnimationFrame` / `cancelAnimationFrame` (manual queue), `devicePixelRatio`. Cover: no rAF after the first draw; rAF stops after settling; restart on pointer, resize, scroll; `still` adds no pointer listener and never calls rAF; media change switches modes and listener sets; `enabled = false` adds nothing; `getContext` returning null or throwing; `data-surface` target blocks; destroy calls `removeEventListener` for every added listener, `disconnect` on both observers and `cancelAnimationFrame`; no change detection: spy on `ApplicationRef.tick` (zoneless app) and assert zero calls across a burst of pointer events and frames (AC 4-8).

**Playwright (qa-engineer).**
- **rAF attribution**: a global `requestAnimationFrame` spy is not reliable on the homepage, because Angular's zoneless scheduler races `requestAnimationFrame` with `setTimeout` on every change detection, and the homepage scenes update signals on timers. Two options: (a) the component writes a `data-state="off|still|idle|running"` attribute on its host with `setAttribute` on mode or loop changes only (invisible, no change detection); QA asserts `idle` for 2 s and unchanged canvas pixels; (b) wrap rAF in `addInitScript` and run the idle check with `reducedMotion: 'reduce'` (scenes stop). **Recommendation: (a)**, plus exact rAF counts in unit tests. The angular-developer adds the attribute.
- **Pixels**: `page.evaluate` → `canvas.getContext('2d').getImageData(x * dpr, y * dpr, 1, 1)` at grid points from the `buildGrid` formula, at 95 px from the pointer, at 0, 60 and 120 px from the header edge, and node positions. The CSS mask is **not** in `getImageData`; check the edge fade (AC 1-2 corner) with a `page.screenshot({ clip })` sample instead.
- **Reduced motion**: `test.use({ reducedMotion: 'reduce' })`, and `page.emulateMedia({ reducedMotion })` mid-test for AC 4-6.
- **Touch**: the `phone-360` project has `hasTouch: true`. First assert `matchMedia('(pointer: fine)').matches === false` there; if Chromium still reports `fine`, override `matchMedia` for that query with `addInitScript`.
- **Layout within 1 px (AC 1-5)**: within one build, record bounding boxes of headings, paragraphs, buttons, cards, tables, header and footer; then inject a style that hides `app-kinetic-background`, sets `.surface-solid::before, .surface-glass::before { display: none }` and resets `.pubwrap` `position`, `z-index`, `isolation`; record again and compare (tolerance 1 px), at 360 and 1440 px, plus `scrollWidth <= clientWidth`. Optional stronger baseline: boxes from the build before SAH-39 saved as a fixture.
- **Contrast (AC 1-8)**: axe cannot see canvas pixels. Structural check: for every visible text element, walking up to `.pubwrap` must find an opaque background or a `.surface-solid` / `.surface-glass` ancestor-or-self. Plus a computed worst case from the tokens. `@axe-core/playwright` would be a new dependency in `frontend/e2e/package.json` (not `frontend/package.json`, so AC 5-9 still holds), but it is still a new library: human decision.
- **Clean-up and routes**: `addInitScript` wraps `window.addEventListener/removeEventListener`, `MediaQueryList.prototype.addEventListener/removeEventListener`, `ResizeObserver.prototype.observe/disconnect`, `MutationObserver.prototype.observe/disconnect`, recording function identities for the types `pointermove`, `pointerout`, `blur`, `scroll`, `resize`, `change`; open home, click "Log in", assert every function added on the homepage was removed and no kinetic canvas is in the DOM. Do not open the enquiry dialog in this test: CDK root services add their own long-lived listeners. Visit home five times, still one canvas. Signed-in pages per role: no `app-kinetic-background`, no `.surface-*`, no `[data-surface]`, no `[data-dot-exclude]`.
- **Performance (AC 4-9)**: CDP `Emulation.setCPUThrottlingRate({ rate: 4 })`, continuous `page.mouse.move`, trace, report the time per frame (8 ms proposal, OQ-39-7).

**Risks.**
- **GPU and fill rate on low-end laptops**: kept low by the dirty-rectangle redraw (about 210 × 210 CSS px per frame), the idle loop and `contain: strict`. A full-viewport canvas at DPR 2 is about 5 Mpx of memory once (about 20 MB); no second cache canvas. No `will-change`.
- **`backdrop-filter`**: one glass block only (hero text). The blur is recomputed where it overlaps a redrawn region, so moving the pointer behind the hero text costs most; measure there. Unsupported browsers get solid white through `@supports`. Any ancestor with `opacity < 1`, `filter`, `mask` or `mix-blend-mode` becomes a backdrop root and would make the glass blur nothing; `.hero-t` has none today, so keep it that way.
- **`mask-image`**: use the prefixed and unprefixed forms; without support the dots just reach the edges.
- **CLS**: none expected. The layer is fixed and out of flow, the canvas size is CSS, surfaces are absolute pseudo-elements, and the component is in the first render of `HomePage`.
- **Display rate**: the spring is per frame (spec), so on 120 or 144 Hz screens it settles faster. Product accepts the spec as written; not changed here.
- **Pre-existing contrast**: `--ink-3` on the current page background (#ebf1f8) is about 4.0:1, and `--sec` text (#0e8a7e, used in `.notice.info`) is about 4.2:1 even on white. Both exist before SAH-39 and come from the mockup tokens. The surfaces above improve `--ink-3`; the `--sec` case cannot reach 4.5:1 without changing a mockup colour, which goes to the human (BR23 asks for an absolute 4.5:1).
