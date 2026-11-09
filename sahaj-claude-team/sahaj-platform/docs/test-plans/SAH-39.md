# Test plan SAH-39 · Homepage ambient background (kinetic dot grid)

Story: `docs/stories/SAH-39.md` (5 stories, 47 acceptance criteria, rules BR1 to BR26 in `docs/requirements/SAH-39.md`) · Decision: DEC-024 · Design note: `docs/architecture/SAH-39-home-background.md` · Mockup screen: Start screen and sign-in, the public Home page (`pLanding`, hero `h-hero`). The background is a human-accepted deviation (D1 to D9).

## What is tested where

| Level | Where | Who | Covers |
| --- | --- | --- | --- |
| Unit (Vitest) | `frontend/src/app/shared/ui/kinetic-background/*.spec.ts`, `frontend/src/app/features/home/home-background.spec.ts` | angular-developer | the pure functions (grid, split, spring, clamps, smoothstep), tokens and fallbacks, inputs, rAF counts with a fake `requestAnimationFrame`, listener add and remove balance, `getContext` null or throwing, fake `ResizeObserver` (and none), media-query change events, `NgZone`/`ApplicationRef.tick` spy |
| Browser (Playwright) | `frontend/e2e/home-background.spec.ts` (55 tests, run in `phone-360` and `desktop-1440` = 110 runs) | qa-engineer | everything that needs a real browser: canvas pixels, the CSS mask, stacking and hit-testing, real media emulation, real layout boxes, real routes, listener balance across a real navigation, the signed-in app per role |
| Backend integration (`*IT.java`) | none | qa-engineer | **Not applicable.** SAH-39 is frontend only: no API, no table, no contract change (design note, first lines). There is nothing to test for "another tenant's data is invisible", "403 for a role" or "400 problem-details": the homepage shows no tenant data and calls no endpoint. The tenant and role rules of this story are the signed-in-pages rule (5-3, one test per role) and the "makes no request" rule (5-8). |

Results, run counts, the coverage table and the speed numbers: section "Results" at the end of this file. Screenshots for the human's visual check: `docs/test-plans/SAH-39-shots/`.

## How the browser tests read the layer

- **State, not a frame spy.** A global `requestAnimationFrame` spy is unreliable on the homepage (Angular's zoneless scheduler also uses it, and the scenes update on timers). The host `app-kinetic-background` carries `data-state` = `off | still | idle | running`; tests record every change with a `MutationObserver`. As a second proof, an init script counts the calls of the layer's own frame function (named `frame`) and the whole canvas is hashed (FNV over `getImageData`): unchanged hash over 2 s means no drawing.
- **Pixels.** `canvas.getContext('2d').getImageData` at the grid: `x = round((w mod 28) / 2 + i * 28) + 0.5`, same for y (at 1440 x 900: offsets 6 and 2). Dot colour (226, 232, 240, 255). Node pixel = `floor(x +- 11/sqrt(2))`. Divided by the page's pixel ratio when it is not 1.
- **The CSS mask** is not in `getImageData`: the edge fade is checked on a real screenshot with the page content hidden.
- **Touch.** The `phone-360` project has `hasTouch: true`. A test asserts that `matchMedia('(pointer: fine)')` is false there (checked: it is, so no override of `matchMedia` is needed). Tests of the mouse logic switch touch off (`test.use({ hasTouch: false })`) and run at both widths, so the interactive code is also exercised on a narrow window.
- **Open background.** The test looks, in viewport coordinates, for a dot whose `document.elementFromPoint` is not inside `[data-surface]` or the Material overlay, at least 135 px from the header (so its opacity is 1), scrolling the page if needed. On the 1440 px layout this is the side margin; on 360 px it is the gap between two sections.
- **Layout within 1 px.** Boxes of every element in `.pubwrap` (about 1,700), measured twice with the layer on (to find elements that move by themselves, with reduced motion on), then with an injected style (`app-kinetic-background` hidden, `.surface-*::before` hidden, `.pubwrap` position/z-index/isolation reset). Compared at 360 and 1440 px.
- **Contrast.** `@axe-core/playwright` is not installed (a new library, a human decision). Two replacements: a structural check (every visible text has an opaque background or a `.surface-*` ancestor-or-self before it reaches `.pubwrap`) and a computed worst case (text colour over each background layer; under glass or open background, over the darkest thing the layer can draw: `--dot-base`, and each node on `--dot-base` and on `--bg-base`). The test fails for a violation that is new, or worse by more than 0.05, compared with the same computation for the page before the change (no layer, old page colour `#e0eaf6`).
- **Listener balance.** An init script wraps `EventTarget.prototype.addEventListener/removeEventListener` (window and `MediaQueryList`, types pointermove, pointerout, blur, scroll, resize, change), `ResizeObserver` (observe, unobserve, disconnect) and `MutationObserver` (observe, disconnect). The leak check compares the sign-in page after "home, then Log in" with the same journey on a home page whose layer was switched off (no 2D context): the layer must add nothing that stays.
- **Performance.** CDP `Emulation.setCPUThrottlingRate(4)`, 4 s of continuous `page.mouse.move` along the left edge; per frame: JS time inside the layer's frame function, plus the mean gap between frames, long tasks, main-thread busy percent, mouse moves handled per second. The same sweep runs on a control page with the layer off. Reported, not asserted (OQ-39-7).

## Test cases (acceptance criterion to case). Result column: see the "Results" section at the end

### SAH-39-1 · Still texture
| AC | Positive | Negative / boundary |
| --- | --- | --- |
| 1-1 | one canvas, fixed, inset 0 = viewport, `pointer-events: none`, `aria-hidden`, no tabindex, content wrapper above (z-index 10, isolate); a click point never hits the layer | tab order never enters the layer (1-9) |
| 1-2 | dot colour, radius 1.6 +- 0.5 (from covered area), 28 px pitch, empty between dots, offsets 6 / 2 at 1440 x 900; mask: centre dot, mid-edge dot and corner dot on a real screenshot | the corner dot fades to `--bg-base` |
| 1-3 | six tokens have the spec values; changing `--dot-base` changes the pixel and removing it brings the spec colour back | |
| 1-4 | `--dot-base` set to `initial` and to `not-a-colour`: spec colour, no error, page works | |
| 1-5 | 1,700 boxes within 1 px at 360 and 1440; `scrollWidth <= clientWidth` at top and bottom; sticky header stays at 10 px; sticky calculator at 84 px (1440) | |
| 1-6 | 1440 -> 740 x 360 -> 400 x 800 -> 1100 x 700 -> 1440: canvas = viewport x ratio, last column and row drawn, no blank strip | landscape; a dot on the half pixel outside the canvas is not a "strip" |
| 1-7 | `.hero-t.surface-glass` (blur 16 px, rgba(255,255,255,.85)), nine `app-section-head.surface-solid`, no `.surface-*` outside the home page; structural text-on-canvas check | fixed by the angular-developer (role cards `.member` on `var(--surface)`, selected specialty card tint-on-white); now passes |
| 1-8 | computed worst case; no new or worse violation | passes after the same fix |
| 1-9 | 40 Tab presses give the same sequence and the same focus ring with and without the layer; focus never enters the layer | |
| 1-10 | no context, and a context that throws: no canvas left, no page error, no console output, "See How It Works", "Log in" and "Start Free" work | |
| 1-11 | the older specs are run unchanged (see Results) | |
| OQ-39-5 | dark theme: state `off`, canvas hidden; light again: grid back | |
| BR2 | enquiry sheet: overlay z-index above the page, dialog and backdrop hit-test, sticky sheet footer visible and clickable | |

### SAH-39-2 · Mouse interaction
| AC | Positive | Negative / boundary |
| --- | --- | --- |
| 2-1 | pointer on a dot: centre alpha 0, four nodes at floor(x +- 7.78) in `--node-a..d` (alpha 0.45, 0.40, 0.45, 0.45, +-8), states running then idle | |
| 2-2 | pointer 45 px from a dot: alpha about 128; 63 px: about 178 (+-12) | |
| 2-3 | pointer 95 px from a dot: plain dot; every dot at 90 px or more and clear of the header is exactly (226, 232, 240, 255); no coloured pixel beyond 93 px | boundary: 90 and 95 |
| 2-4, 2-5 | unit only (clamp 150 -> 90, 60 -> 60, 0 / -5 -> 90; spring values) | |
| 2-6 | pointer leaves (pointerout with null related target) or moves onto a surface: whole-canvas hash after settling equals the hash before the pointer came (tolerance zero) | after a winding path |
| 2-7 | pricing card, FAQ item, setup card, hero panel, hero heading, footer, section head, trust panel, calculator input: hash unchanged; open dialog and its backdrop: hash unchanged; closing the dialog gives the effect back | |
| 2-8 | from the header onto open background: first split pixel within 1.5 s (measured) | |
| 2-9 | 60 quick moves, then "See How It Works" click, wheel scroll twice, "Log in" click; hit-test grid never returns the layer | |
| 2-10 | scroll with the wheel until a surface is under the still pointer: dots return; scroll back to open: split again; new move after scrolling splits at viewport coordinates | |

### SAH-39-3 · Header exclusion
| AC | Positive | Negative / boundary |
| --- | --- | --- |
| 3-1 | every dot within 160 px of the header: alpha = smoothstep(distance / 120) x 255 +- 6; reported samples at about 0, 60 and 120 px | inside the header box: alpha 0 |
| 3-2 | node alpha at 60 px = 0.5 x token alpha (+-10); no drawn pixel over the header or 8 px around it; no pixel inside the header box with the pointer 20 px away | |
| 3-3 | 1440 -> 360 -> 900 -> 1440: pattern follows the new header | |
| 3-4 | header content height changed without a window resize: pattern follows | a padding-only change is not observed (a known limit, see Results) |
| 3-5 | scroll to 900, 2500, 0: the sticky header's zone is still right | |
| 3-6, 3-7 | unit only | |

### SAH-39-4 · Quiet fallbacks and speed
| AC | Positive | Negative / boundary |
| --- | --- | --- |
| 4-1, 4-2 | after load: no `frame` call and unchanged canvas for 2.2 s; after a move and settle: the same for 2.2 s; state history is exactly `["idle"]` | |
| 4-3 | scroll, resize and pointer move each give `running` then `idle` and more frames | |
| 4-4 | reduced motion: `still`, no pointer/pointerout/blur listener, 0 frames over 2 s of moving, unchanged canvas | |
| 4-5 | touch: `pointer: fine` is false, `still`, no pointer listener, tap and CDP touch drag: no frame, no coloured pixel | |
| 4-6 | `emulateMedia` reduce then no-preference: `still` with the pointer listener removed, then `idle` with it back, splitting works again | |
| 4-7 | pixel ratio 3: backing size 2 x viewport, CSS size = viewport | ratio 1 is the default of all other tests |
| 4-8 | not testable in a browser (the home scenes also change the DOM on timers); covered by the unit test with the `ApplicationRef.tick` spy | |
| 4-9 | 4x throttle: responsive (click and scroll work), numbers reported | |
| 4-10 | unit only (`enabled = false`); the browser stand-in is "no 2D context" (1-10) | |

### SAH-39-5 · Home only and no trace
| AC | Positive | Negative / boundary |
| --- | --- | --- |
| 5-1 | home then "Log in": no host, no canvas; listeners, media-query listeners, resize observers and mutation observers back to what a layer-less home leaves | |
| 5-2 | five visits: one host, one canvas, the same listener counts as the first visit | |
| 5-3 | each of OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST on Today, Queue, Appointments, Patients, Billing, IPD, Team, Clinic setup (those the role may open): no canvas, no host, no `.surface-*`, no `[data-surface]`, no `[data-dot-exclude]`, no `data-state`, no 16 px blur on a surface class | roles sent away by a guard are skipped, at least 3 pages per role are probed |
| 5-4 | only `home.page.ts` references the component; the shell files contain no surface/data attribute or the component | |
| 5-5 | sign-in, sign-up, admin sign-in: nothing from the layer | |
| 5-8 | no API request but the silent refresh at load; no request during interaction other than static files; no `localStorage`/`sessionStorage`/cookie write; empty storage; no console output; no page error | |
| 5-9, 5-10 | `package.json` and `package-lock.json` unchanged (git); README mentions `data-surface`, `data-dot-exclude`, `.surface-solid`, `.surface-glass` and the homepage | the wording is for code-reviewer |
| 5-6 | the older signed-in specs are run unchanged (see Results) | |
| 5-11 | `node scripts/validate.mjs frontend` (see Results) | |

## Not covered, with the reason
- 4-8 (zero change detection) in the browser: unit test only.
- 2-4, 2-5, 3-6, 3-7, 4-10: unit tests (inputs and fakes the browser cannot set).
- Real GPU cost: headless Chromium rasterises in software, so raster time is not in the frame numbers.
- Contrast by axe: not installed (new library). The computed check above replaces it; the absolute list of texts below 4.5:1 (all of them already below before the change, from the mockup colours) is not listed by the test (it prints only the counts: failing now, failing before, new).
- A real touch laptop with a mouse attached: only the media-query change event is tested.

---

# Results (qa-engineer, 8 Nov 2026, branch `feature/SAH-39-home-kinetic-background`)

Build: `npm --prefix frontend run build` passed just before the runs. Browser: Playwright Chromium, headless, Windows 11. Config: `frontend/e2e/playwright.config.ts` (4 workers, projects `phone-360` and `desktop-1440`).

## Run counts

| Run | Command | Result |
| --- | --- | --- |
| 1, 2, 3 (after the `.member` and specialty fix) | `npx playwright test home-background.spec.ts home.spec.ts` | 168 passed, 2 skipped, 0 failed in each (5.5, 5.8, 5.8 min). 170 runs = 52 background tests x 2 projects + 33 home tests x 2 projects. |
| Final (after adding the 2 new tests and the load fixes below) | same | **172 passed, 2 skipped, 0 failed** (5.8 min). 174 runs = 54 x 2 + 33 x 2. |
| After the dark-theme test and the review fixes (8 Nov, evening), run 1 and run 2 in a row | `npx playwright test home-background.spec.ts home.spec.ts --workers=4` after `npm --prefix frontend run build` | **174 passed, 2 skipped, 0 failed** each time (5.6 and 5.8 min). 176 runs = 55 background tests x 2 projects + 33 home tests x 2 projects. |
| Speed tests alone, 3 times | `-g 4-9` | 4 passed each time (after the load fix below) |

The 2 skipped are by design: `home.spec.ts` "desktop: a section link scrolls to its section" and "desktop: Tab also reaches the five section links before Admin portal" (`test.skip` on `phone-360`, where the section links are hidden below 980 px). `home.spec.ts` is not edited (AC 1-11).

New in this pass: `screenshots of the role cards and the selected specialty` (writes the 8 OQ-39-6 images) and `4-9 one pointermove per animation frame ...` (frame spacing, reported).

## Root cause of the 13 "first look" pixel failures (8 Nov, evening)

Seen by the angular-developer after the review fixes: 2-6 (x2), 2-7 (x2), 2-8, 2-9, 2-10, 4-1 and 4-2, 4-4 and 4-9 on `desktop-1440`; 2-6 (x2) and 2-7 (x2) on `phone-360`. In each, the canvas hash after some steps was not the hash of the "first look", and the layer redrew about 935 ms after the navigation with no input.

**Cause: a late application stylesheet, not the web fonts, and not a product defect.**
- The production build inlines only the critical CSS and loads `styles-*.css` afterwards (`media="print"` swapped to `all` by a script at the end of the body). The app can boot, show the h1 and draw the layer before that file is applied.
- When it is applied the sticky header changes size: 1120 x 47 to 1118 x 47 at 1440 px, 300 x 103 to 298 x 103 at 360 px. The layer watches the header (`data-dot-exclude`) with a ResizeObserver, sees a new box, re-measures and redraws the whole grid with the new header exclusion zone (one `clearRect` of the full canvas, no state change). That is the intended behaviour of a layout change (AC 3-4), so the pixels change for a legitimate reason: the layout changed. The test took its "first look" a moment before.
- Evidence (probe script with Playwright, a request delay per resource, 3 runs each at 1440 and 360):

| Probe | Header box changed | Canvas redraw after the first draw |
| --- | --- | --- |
| Font CSS and font files delayed 1 s and 3 s | no | none in 4 s |
| Font requests blocked (`ERR_INTERNET_DISCONNECTED`) | no | none |
| 4x CPU throttle; 12 background pages burning CPU | no | none |
| `styles-*.css` delayed 0.8 s | yes, 2 px | one, 0.1 to 0.3 s after "ready" |
| `styles-*.css` delayed 1.5 s | yes, 2 px | one, 1.0 to 1.2 s after "ready" |
| `styles-*.css` delayed 3 s | yes, 2 px | one, about 2.5 s after "ready" |

- The same failing tests fail with a delayed stylesheet. With a test server delaying `styles-*.css` by 1.6 s and the spec as it was: 2-6, 2-8, 4-1/4-2, 4-4 failed (5 of 20 runs; the delay decides which tests the arrival lands in). The error shape is the one reported (hash and non-empty count differ from the first look).
- Why the earlier `document.fonts.ready` wait did not fix it: it waits for fonts, and the fonts do not move the header. Why the 13 appeared only sometimes: it depends on whether the file arrives before or after the test's first read; on an idle machine it arrives before the app is up (0 failures in the first run of this pass), on a busy machine after.
- Late colour re-read (code review nit 10): not shown to be involved. In every probe the redraw followed a ResizeObserver callback for the header, and the fix (wait for the stylesheet) removes the failures; the colour key itself was not logged separately.

**Fix (test only, no assertion weakened, `frontend/src` untouched):** `openHome` (and the layout test of 1-9) now call `settlePage`: wait until every same-origin stylesheet link is applied (`data-beasties-media` gone, `media` not `print`, `sheet` loaded), then the fonts (at most 8 s), then two animation frames. It polls with `evaluate`, because `page.waitForFunction` leaves a MutationObserver in the page and test 5-1 counts observers (it failed 2 of 2 runs with it, 0 now). With the fix and a 1.6 s and a 2.5 s stylesheet delay: 30 of 30 passed in each; the five tests above plus 5-1 pass at 1.6 s. Tolerance zero stays zero.
- Other test-file edits left by the previous pass, reviewed: `NAV = { waitUntil: 'domcontentloaded' }` for every `page.goto` in this file (a stalled font request no longer fails a test at `goto`; each test already waits for the h1 and `data-state`); `findOpen`/`pickOpen` got a `path` option so that every point of the 2-6 pointer path is over open background on 360 px (the assertion itself, zero tolerance, is unchanged). No assertion was removed or loosened.
- Nit for the angular-developer (product, optional): until `styles-*.css` arrives the page is shown with critical CSS only, and the header then shrinks by 2 px. This is the Angular `inlineCritical` swap; it is a layout shift the visitor can see as a 2 px jump, and it makes the dots near the header redraw once. No action needed for the story.
- A `home.spec.ts` failure seen once in a run on a quiet machine, `page loads with the headline, no script errors ...` on `phone-360`, was five `console: Failed to load resource: net::ERR_INTERNET_DISCONNECTED` messages: the Google font requests failed while the machine's network dropped. It did not repeat in the 2 final runs. The test (unedited, AC 1-11) fails on any console error, so it depends on the network reaching Google Fonts; a flaky external dependency, not SAH-39. Suggest the owner of `home.spec.ts` block `fonts.googleapis.com` and `fonts.gstatic.com` in that test.

## Dark theme: the hero glass panel (code review Major #1)

New test `1-8 dark theme: the hero glass panel resolves to a dark colour and the hero text reaches 4.5:1 on it`, both projects, passes. It switches `data-theme="dark"` on `html` after load, and checks: the layer is `off`; `.hero-t.surface-glass::before` resolves to exactly `rgba(22, 38, 66, 0.92)`; the panel stays dark even over pure white (relative luminance under 0.1); heading, heading emphasis and lede each reach at least 4.5:1 over the panel on the real page colour and on white (the worst case for light text); back to light the panel is `rgba(255, 255, 255, 0.85)` again. Without the dark `--glass-surface` value the panel would be near-white and the contrast would fall to about 1.1:1. Measured contrast values are attached to the test result as the annotation "dark hero contrast". The unit test `tokens-dark-glass.spec.ts` covers the token.

## The "7 home.spec desktop timeouts under load" note

- They do **not** happen in the normal run: 0 timeouts in 5 runs at 4 workers (3 x 170 runs, the 174-run final runs).
- They do happen when the machine is saturated. `home.spec.ts` alone at 10 workers (2.5 times the normal load, with a Maven gate running on the same machine):

| Run (10 workers) | Layer | Failed of 66 | Failure |
| --- | --- | --- | --- |
| 1 | on | 2 | `page.goto('/')` then `h1` not visible within the 7 s expect timeout |
| 2 | on | 1 | same |
| 3 | on | 0 | |
| 1 | **off** (`E2E_NO_KINETIC=1`: no 2D context, no surface CSS, no stacking change) | 6 | same |
| 2 | **off** | 3 | same |
| after the change below, run 1 | on | 0 | |
| after the change below, run 2 | on | 9 | `page.goto` hit the 30 s test timeout and the console showed `net::ERR_CONNECTION_TIMED_OUT` from the local test server: the machine itself stalled |

- Finding: it is cold-start time of the app (lazy home route) under CPU saturation. It is **not caused by the layer**: the control with the layer off fails as often or more (6 and 3 against 2, 1 and 0).
- Hardening done (no assertion relaxed): `expect.timeout` in `frontend/e2e/playwright.config.ts` from 7 s to 15 s. Only a passing wait gets longer; the 30 s test timeout is unchanged. It removes the "h1 not visible in 7 s" failure; it cannot help when the machine stalls the connection itself (the 9-failure run). Advice: do not run the browser suite together with a Maven gate on this machine, and keep the default 4 workers.
- Two measuring checks of mine were load-sensitive and are fixed: the speed test (4-9) asserted a minimum number of layer frames within 4 s of Playwright mouse moves (it saw 7 under load). It now carries on up to 15 s until 15 frames were drawn; the assertion (the layer ran frames during the moves) is unchanged. The new frame-spacing test asserts more than 10 frames, not 50, because the count depends on the machine.

## Coverage: acceptance criterion to test to result

Browser tests are in `frontend/e2e/home-background.spec.ts`, both projects unless noted. U = unit test of the angular-developer (2,117 unit tests pass, see Gate).

| AC | Test (browser) | Unit | Result |
| --- | --- | --- | --- |
| 1-1 | 1-1 exactly one decorative canvas in a fixed full-viewport layer behind the content | | pass |
| 1-2 | 1-2 dots every 28 px in --dot-base ...; 1-2 the layer fades toward the viewport edges with a radial mask | U grid | pass |
| 1-3 | 1-3 theme tokens have the spec values, and a changed token changes the grid colour | U | pass |
| 1-4 | 1-4 a missing or empty token falls back to the spec value | U | pass |
| 1-5 | 1-5 layout is within 1 px of the same page without the layer (about 1,700 boxes, 360 and 1440); 1-5 no horizontal scroll ... sticky header and calculator still stick | | pass |
| 1-6 | 1-6 resize and rotate: canvas covers the new viewport, no blank strip | | pass |
| 1-7 | 1-7 text blocks sit on .surface-glass or .surface-solid ...; 1-7 no homepage text sits directly on the canvas | U | pass (failed before the fix for the role cards and the selected specialty) |
| 1-8 | 1-8 contrast: no text becomes worse than before the change; **1-8 dark theme: the hero glass panel resolves to a dark colour and the hero text reaches 4.5:1 on it** (new, code review Major #1) | U (`tokens-dark-glass.spec.ts`) | pass (the first failed before the fix of the role cards). Axe is not installed (human decision pending); see Contrast below |
| 1-9 | 1-9 tab order and focus rings are the same as without the layer; the canvas is never focusable | | pass |
| 1-10 | 1-10 no 2D context ...; 1-10 the 2D context throws | U | pass |
| 1-11 | `home.spec.ts` and the older specs, unedited | | pass (66 runs: 64 pass, 2 skipped by design) |
| 2-1 | 2-1 pointer exactly on a dot: four coloured nodes about 11 px away | U | pass |
| 2-2 | 2-2 split follows 1 - d/90 (45 px, 63 px) | U | pass |
| 2-3 | 2-3 nothing changes 90 px or more from the pointer (95 px, no colour beyond reach) | U | pass |
| 2-4, 2-5 | not in a browser | U (clamps, spring frame by frame) | pass (unit) |
| 2-6 | 2-6 when the pointer leaves, every dot returns pixel for pixel (zero tolerance); 2-6 the same return onto a surface | U exact 0 | pass |
| 2-7 | 2-7 over a data-surface nothing splits (pricing card, FAQ item, setup card, hero panel, text block, footer); 2-7 an open dialog and its backdrop never split dots | U | pass |
| 2-8 | 2-8 moving from a data-surface onto open background starts at once | | pass |
| 2-9 | 2-9 fast moves; links and buttons still click and the page still scrolls | | pass |
| 2-10 | 2-10 after scrolling, the effect follows the pointer | | pass |
| 3-1 | 3-1 smoothstep 0 at the edge, 0.5 at 60 px, 1 at 120 px | U | pass |
| 3-2 | 3-2 nodes near the header are scaled by the same curve; 3-2 pointer directly over the header area | | pass |
| 3-3 | 3-3 resizing 1440 -> 360 and back | | pass |
| 3-4 | 3-4 the header changes size without a window resize | U fake ResizeObserver | pass (a padding-only change is not observed: ResizeObserver watches the content box; known limit) |
| 3-5 | 3-5 scrolled page, sticky header | | pass |
| 3-6, 3-7 | not in a browser | U | pass (unit) |
| 4-1, 4-2 | 4-1 and 4-2 no frame after the first draw, none for 2 s after the last dot settles | U fake rAF | pass |
| 4-3 | 4-3 the loop restarts on pointer move, on scroll and on resize | U | pass |
| 4-4 | 4-4 reduced motion: still grid only, no pointer listener, no frame | U | pass |
| 4-5 | 4-5 a touch device does not match pointer: fine; touching and dragging splits nothing (`phone-360` really reports a coarse pointer) | U | pass |
| 4-6 | 4-6 reduced motion turned on and off while the page is open | U | pass |
| 4-7 | 4-7 devicePixelRatio 3 gives a backing ratio of 2 | U | pass |
| 4-8 | not testable in a browser (the scenes also change the DOM on timers) | U `ApplicationRef.tick` spy | pass (unit) |
| 4-9 | 4-9 4x CPU throttle, continuous mouse moves; 4-9 one pointermove per animation frame | | pass; numbers below |
| 4-10 | not in a browser (stand-in: no 2D context, 1-10) | U | pass (unit) |
| 5-1 | 5-1 home to Log in: every listener and observer the layer added is gone, no canvas | U destroy spies | pass |
| 5-2 | 5-2 coming back five times leaves exactly one canvas and one set of listeners | | pass |
| 5-3 | 5-3 signed-in OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST (Today, Queue, Appointments, Patients, Billing, IPD, Team, Clinic setup; at least 3 pages per role): no canvas, no host, no `.surface-*`, no `[data-surface]`, no `[data-dot-exclude]`, no `data-state`, no 16 px blur on a surface class | | pass (7 roles x 2 projects) |
| 5-4 | 5-4 and 5-9 and 5-10 the shell does not use the layer ... | | pass |
| 5-5 | 5-5 sign-in, sign-up and the admin sign-in carry nothing from the layer | | pass |
| 5-6 | the older signed-in specs, unedited | | not re-run in this pass (whole-folder regression is the tech lead's gate) |
| 5-7 | | U defaults | pass (unit) |
| 5-8 | 5-8 the layer makes no request, writes no storage or cookie, and logs nothing | | pass |
| 5-9 | 5-4 and 5-9 and 5-10 (`package.json` and lock files unchanged; QA added no dependency) | | pass |
| 5-10 | same test: the README names `data-surface`, `data-dot-exclude`, `.surface-solid`, `.surface-glass`, homepage | | pass for presence; wording is for code-reviewer |
| 5-11 | `node scripts/validate.mjs frontend` | | **fails in the gate, not because of this story**, see Gate |
| OQ-39-5 | OQ-39-5 dark theme: not drawn, comes back with light; 1-8 dark theme (hero glass panel dark, contrast) | U | pass |
| BR2 | the enquiry sheet is above the page and the layer, laid out as before | | pass |

## Reduced motion, touch, dark theme, signed-in screens

All four pass at both sizes: reduced motion (4-4, 4-6: state `still`, zero frames over 2 s of moving, pointer listener removed and added again live), touch (4-5: `pointer: fine` is false on `phone-360`; a tap and a CDP touch drag give zero frames and zero coloured pixels), dark theme (OQ-39-5: state `off`, canvas hidden, grid back with the light theme), and signed-in screens (5-3: `/app/ipd` and the OPD pages of every role have no canvas, no host, no `.surface-*`, no `data-surface`, no `data-dot-exclude`, no `data-state`).

## Speed (AC 4-9, OQ-39-7): layer on against off, 4x CPU throttle

Method. CDP `Emulation.setCPUThrottlingRate(4)`, one worker. The machine still had a Maven gate and other tools open, so absolute numbers are noisy. "Layer off" is the same page with no 2D context (the layer does nothing; the surface CSS is still there, so only the canvas and its loop are removed). Raw runs: `docs/test-plans/SAH-39-shots/perf-runs/*.json` (`sweep-*` = Playwright mouse sweep, `cad-*` and `cadoff-*` = one pointer move per animation frame, layer-on-first and layer-off-first). "Layer ms" is JavaScript time inside the layer's own frame function (canvas calls on the main thread). Headless Chromium rasterises in software, so raster time is not in "layer ms" but is in the frame spacing.

**A. Layer drawing time per frame (proposed limit, OQ-39-7: under 8 ms)**

| Size and method | Median | p95 | Worst frame | Frames over 8 ms |
| --- | --- | --- | --- | --- |
| 1440, mouse sweep, 3 runs | 0.8, 1.1, 1.3 ms | 3.7, 5.2, 3.8 ms | 64, 17, 46 ms | not counted |
| 1440, one move per frame, 5 runs | 1.6 to 3.1 ms | 4.4 to 6.7 ms | 10.6 to 16.5 ms | 1 of 54 up to 3 of 69 (up to 4 %) |
| 360, mouse sweep, 3 runs | 0.5, 0.6, 1.0 ms | 3.2, 2.1, 2.8 ms | 14.6, 3.6, 16.5 ms | not counted |
| 360, one move per frame, 5 runs | 1.5 to 1.7 ms | 2.7 to 3.1 ms | 8.0 to 24.2 ms | 0 of 215 up to 2 of 208 (about 1 %) |

Result: **the 8 ms target is met** on median and p95 at both sizes (worst p95 is 6.7 ms). A few single frames go over (at most about 4 % of frames; worst 64 ms once at 1440, a scheduling or garbage-collection stall).

**B. Page frame spacing (what the visitor feels), one pointer move per frame, mean ms between page frames.** The second tab of a browser context gets fewer frames in headless mode, so the order was swapped in some runs to remove that bias.

| Size | Layer on (first tab) | Layer off (first tab) | Layer on (second tab) | Layer off (second tab) |
| --- | --- | --- | --- | --- |
| 1440 | 66, 72, 58 (about 15 fps) | 43, 47 (about 22 fps) | 95, 102 | 115, 113, 82 |
| 360 | 19.7, 24.0, 21.6 (about 48 fps) | 17.0, 17.1 (about 59 fps) | 23.3, 22.0 | 17.1, 19.8, 17.7 |

Result: with the layer on, at 4x throttle, the page is about **+20 ms per frame at 1440 (about 22 fps down to 15 fps)** and **+3 to +5 ms per frame at 360 (about 59 fps down to 48 fps)** compared with the same page and the same pointer movement with no layer. The layer's own JavaScript is 1 to 3 ms of that; the rest is painting the changed canvas area and re-blurring the hero glass panel behind it in software. The sweep runs along the left edge, partly next to the hero glass block, the worst case named in the design note. Main-thread busy is already 80 to 99 % at 4x on the page without the layer (its animated scenes), so the page is saturated at 4x with or without the layer.

Long tasks (over 50 ms) at 1440 in 5 s: 6 to 11 with the layer on against 2 to 5 off. At 360: 0 to 2 against 0 to 2 (no difference).

Limits of the story and where they stand:
- AC 4-9 "page stays responsive: scroll and clicks work": **met** (the speed test clicks "See How It Works" and scrolls under the 4x throttle with the pointer moving; passes in every run).
- AC 4-9 "drawing under 8 ms per frame (proposal)": **met** (table A).
- Not a story limit, but the human should know: at 1440 on a 4x slower, software-rendered machine the frame rate drops by about a third while the pointer moves over the margin next to the hero glass. With the pointer at rest the cost is zero (idle loop, test 4-1: zero frames for 2 s). If the human wants it lighter, the angular-developer can shrink the dirty rectangle or keep the layer away from the glass block. This is an optimisation choice, not a defect.

## Screenshots for the human (OQ-39-6): `docs/test-plans/SAH-39-shots/`

File names start `desktop-1440-` (1440 x 900) and `phone-360-` (360 x 740):
- Role cards: `...-roles-everyone.png` and `...-roles-one-role-selected.png` (with the info notice; the three other cards are dimmed on purpose, and the dimmed cards are see-through, so the dots show faintly through them: that is the existing `.dim` opacity, not a new rule).
- Selected specialty: `...-specialty-selected-default.png` (General physician) and `...-specialty-selected-second.png` (Dental clinic: tint on white, blue border).
- Page tour: `...-01-top`, `-02-setup`, `-03-roles`, `-04-compare`, `-05-pricing`, `-06-faq`, `-07-footer`, `-08-enquiry-sheet`, and `...-split-at-pointer`.
- The sticky header sits over the top of some images; that is where the page puts it at that scroll position.

## Contrast (AC 1-8)

No axe (not installed; the decision is the human's). The test combines a structural check (every visible text has an opaque background or a `.surface-*` ancestor-or-self before it reaches `.pubwrap`) and a computed worst case (each text colour over the darkest thing the layer can draw). It fails for any text that is new below 4.5:1 or 3:1, or worse than before the change by more than 0.05. It passes at both sizes. Texts that were already under 4.5:1 before the change (mockup colours, for example `--sec` on the notice, about 4.2:1 on white) are not new; they are the human's decision (design note, last risk).

## Gate

- `npm --prefix frontend run lint`: pass. `npm --prefix frontend run build`: pass.
- `npm --prefix frontend test -- --watch=false`: 2,117 pass, 0 fail (angular-developer's run after the review fixes; QA did not re-run the unit tests). The two `ist-clock.spec.ts` failures noted in the first pass are fixed by the angular-developer (the "plain space" change), so that note no longer applies.
- `node scripts/validate.mjs frontend` **fails** in its second step: the script passes `--browsers=ChromeHeadless`, which the Vitest runner of Angular 22 refuses (it needs `@vitest/browser-playwright`). `scripts/` is human-owned. The same unit tests run without that flag (above).
- `node scripts/validate.mjs docs` fails: `docs/stories/ADM-2.md: name files by story ID` (committed before SAH-39). Owner: business-analyst or the human.
- `validate all` and `validate db` run Maven (a backend gate was running), so QA did not run them.
