# SAH-41 · Test plan: self-host the web fonts

Story: `docs/stories/SAH-41.md` (41-1 to 41-5). Rules BR1 to BR15: `docs/requirements/SAH-41.md`. Design: `docs/architecture/SAH-41-self-hosted-fonts.md` (section 10 is the test list).
Mockup screen: Foundation (no screen). Nothing visible changes, so this plan checks requests, loading and layout, not new screens.
Module: platform, frontend only. No API, no table, no backend integration test (nothing for `*IT.java`; the three standard backend checks do not apply: no endpoint, no tenant data, no role).

## Where each kind of test lives

| Kind | File | Runs under |
| --- | --- | --- |
| Source guard (unit, Vitest) | `frontend/src/styles/no-third-party-fonts.spec.ts` (developer) | `npm --prefix frontend test` |
| Browser tests, new | `frontend/e2e/self-hosted-fonts.spec.ts` (QA) | `npx playwright test` in `frontend/e2e`, against the production build, at 360 px (phone-360) and 1440 px (desktop-1440) |
| Browser tests, old (font-swap shift check, BR10) | `home.spec.ts`, `home-background.spec.ts`, `layout-matrix.spec.ts`, `app-shell.spec.ts` (unchanged) | same |
| Build check | `npm --prefix frontend run build`, then `dist/frontend/browser/media/` | build |

How the browser tests see the world: the real backend is not running. Signed-in screens use the shared mock `e2e/sah31-world.ts` with a second route for `GET /platform/me` to set Hindi or Marathi (the mock has English only). They prove what the browser requests and draws, not a server rule. `e2e/serve.mjs` is a static test server: it sends no cache header, so GL-41-1 cannot be tested here.
Fonts are the same for all roles and tenants (BR14). Role and tenant checks here mean: the font URLs and requests are the same for every role and for two different clinics, and carry no tenant data.

## Not testable now (stated, not skipped silently)

| Item | Why | Where it goes |
| --- | --- | --- |
| 41-4 AC3: `Cache-Control: public, max-age=31536000, immutable` on `/media/*`, `no-cache` on `index.html` | Spring Boot does not serve the frontend and the production static server is not defined (design section 7, GL-41-1). `serve.mjs` is not production. | Go-live condition GL-41-1 (human). QA writes the header test when a production server exists. |
| 41-3 AC5: weight transferred is not more than Google sent | Needs Google's number from before the change; the network is not used. | Developer's handoff states 81,340 B for the English Home page. QA records the measured bytes in each run (annotation `font-bytes`) and asserts only that the Devanagari files and the Caveat latin-ext file are not downloaded. |
| 41-3 AC3: visual comparison before/after with a pixel diff | No "before" build (the Google version) is kept, and the network is not used. | Layout specs (below) are the proxy; a human eye-check on the first deploy. Family-per-character is tested with the browser "Rendered Fonts" list. |
| 41-2 AC5: second screen served from the browser cache | Depends on the cache header (GL-41-1). | With GL-41-1. |
| 41-4 AC5: build with no internet | The Playwright run has no network use and the build ran offline from committed files (design section 4); there is no way to cut the machine's network from a test. | The font files are committed; the build output is listed in test E-30. |
| Home page language switch (41-1 AC4) | The Home page has no language switch (it is English only; not in the mockup). Hindi and Marathi are tested on the signed-in shell, where the switch exists. | Reported as a note. |

## Index of the browser tests (file: `frontend/e2e/self-hosted-fonts.spec.ts`)

| ID | Test title (start) |
| --- | --- |
| E-01 | "Home page / sign-in page / admin sign-in page: no request to a Google font host, every font and stylesheet request is own-origin" (3 tests) |
| E-02 | the same title for "signed-in OWNER /app/today", "signed-in NURSE /app/queue", "signed-in PHARMACIST /app/ipd" (3 tests) |
| E-03 | "signed-in `<ROLE>`: today screen makes no request to a Google font host" (7 tests, one per role) |
| E-04 | "the page source has no preconnect, dns-prefetch or preload link to another host ..." |
| E-06 | "guard self-test: the recorder DOES report a Google stylesheet and an off-origin stylesheet" |
| E-05 | "`<page>`: loads with the Google hosts aborted, no script error, no overflow, no request to them" (6 tests) |
| E-08 | "Home page with the Google hosts aborted: same headline as the home.spec.ts load test, footer reached" |
| E-09 | "another tenant sees the same own-origin font files ..." |
| E-10 | "a clinic LAN with every outside address blocked ..." |
| E-12 | "Home page: Figtree 600 and Caveat 500 are loaded; the faces are the local ones" |
| E-13, E-14 | "signed-in screen in Hindi / Marathi: Noto Sans Devanagari loads ..." |
| E-15 | "English signed-in and Home screens request no Devanagari file (BR12)" |
| E-16 | "`<Home page / signed-in OWNER /app/today>`: the rupee sign, the accented letter and the dose characters are drawn in Figtree, as before" (2 tests) |
| E-17 | "Home page: the hand-written accent text (patient journey, stage 4) is drawn in Caveat" |
| E-18 | "the declared faces are exactly the agreed set and all use font-display swap" |
| E-20, E-21, E-22 | "Home page with the Figtree latin file / the Caveat latin file / every font file blocked ..." |
| E-23 | "signed-in screen with every font file blocked ..." |
| E-24 | "BR4: with the font files held back the text is visible at once in the fallback font, then swaps when the font arrives" |
| E-25 | "English Home page: records the bytes of the font files for the handoff ..." |
| E-26 | "`<page>`: font requests carry no cookie and no authorization header" (4 tests) |
| E-27 | "positive control: the API cookie IS sent to the API path it is scoped to, and not to a static path" |
| E-28 | "licence text of `<family>` is reachable ..." (3 tests) |
| E-29 | "font files are served as media/<name>-<hash>.woff2 with a woff2 header; unhashed copies do not exist" |
| E-30 | "the production output lists 7 hashed woff2 files, 3 licence files, and no Google host in any html or css" |

## Acceptance criteria to tests

IDs are for this plan. "E" tests are in `self-hosted-fonts.spec.ts` (title as in the file). "U" tests are the developer's Vitest guard. Every E test runs at 360 px and 1440 px.

### Story 41-1 · Home page

| AC | Test |
| --- | --- |
| 1 No request to Google or another host for a font or stylesheet; no preconnect, dns-prefetch, preload to such a host | E-01 "Home page: no request to a Google font host, every font and stylesheet request is own-origin". E-04 "the page source has no preconnect, dns-prefetch or preload link to another host, and no Google host anywhere" (raw HTML of `/`, `/login`, `/admin/sign-in`). U-2 "finds no Google Fonts host in any source file". |
| 2 Every font request is to the own origin | E-01 (same test; the recorder asserts font and stylesheet requests). E-12 "Home page: Figtree 600 and Caveat 500 are loaded; the faces are the local ones" checks host and the `/media/...-<hash>.woff2` path of every font request. |
| 3 Figtree for headline, nav, pricing, compare, FAQ, sheet; Caveat 500 for accent text | E-12 (`document.fonts.check` true for Figtree 400/600/700 and Caveat 500, and the list of loaded faces contains Figtree 600 and Caveat 500, because `check()` is also true for an unknown family). E-17 "the hand-written accent text (patient journey, stage 4) is drawn in Caveat" (browser Rendered Fonts list for the `.hl` text). |
| 4 Hindi or Marathi page: Devanagari in Noto Sans Devanagari | E-13 "signed-in screen in Hindi ..." and E-14 "... in Marathi ..." (Devanagari text on screen; `check('400 16px "Noto Sans Devanagari"', 'हिंदी')` true; the devanagari file was requested; Rendered Fonts list contains Noto Sans Devanagari; no overflow; no script error). Note: the bare `check('400 16px "Noto Sans Devanagari"')` tests the space character, which is in Noto's latin file; Figtree comes first so that file is correctly never fetched, and the bare check is false. It is recorded as annotation `bare-check`, not asserted. |
| 5 ₹ and accented Latin letters in the same family as the text | E-16 "Home page: the rupee sign, the accented letter and the dose characters are drawn in Figtree, as before" (browser Rendered Fonts list: only Figtree web faces, no system fallback). |
| 6 Google hosts unreachable: page loads, headline, no script error, no overflow, no font console error | E-05 "Home page: loads with the Google hosts aborted, no script error, no overflow, no request to them". E-08 "Home page with the Google hosts aborted: same headline as the home.spec.ts load test, footer reached". |
| 7 One local font file missing or failing: system-ui fallback, page works, no script error | E-20 "Home page with the Figtree latin file blocked ...", E-21 "... the Caveat latin file blocked ...", E-22 "... every font file blocked ...". Each asserts the aborted file really was requested, no pageerror and no console error other than "Failed to load resource" (expected), the headline text, `visibility: visible`, opacity above 0, colour not transparent, a real box, the fallback names (`system-ui`, `sans-serif`) in the computed stack, no overflow. E-23 "signed-in screen with every font file blocked" does the same for the queue. |
| 8 360 px and 1440 px layout specs pass unchanged; positions within 1 px | R-1 to R-4 (existing specs, run unchanged; see below). |
| 9 Slow connection: text visible at once in fallback, swaps later | E-24 "BR4: with the font files held back the text is visible at once in the fallback font, then swaps when the font arrives". The font route is held by a gate the test opens (no timer). While held: no Figtree face is loaded, `check("600 16px Figtree")` is false (so the fallback is drawing), and the h1 is visible with opacity above 0, a real box and a colour that is not transparent. After release: Figtree 600 becomes ready and there is no overflow. |

### Story 41-2 · Sign-in and clinical screens, all roles, admin

| AC | Test |
| --- | --- |
| 1 Any role, any listed screen: no third-party font host | E-02 "signed-in OWNER /app/today", "signed-in NURSE /app/queue", "signed-in PHARMACIST /app/ipd" (full offender check). E-03 x7 "signed-in `<ROLE>`: today screen makes no request to a Google font host" for OWNER, DOCTOR, VISITING_DOCTOR, NURSE, FRONT_DESK, BILLING, PHARMACIST. E-01 also covers "sign-in page" (`/login`). Sign-up is the `/setup` wizard and needs a session; it loads the same global stylesheet and has no own font reference (U-2 covers the source). Billing, HRA, team and settings share the same stylesheet and are covered by U-2 and the three screens above; the Playwright layout specs open team, billing and IPD pages. |
| 2 Admin sign-in and admin screens | E-01 "admin sign-in page". The admin shell needs an admin session that no existing mock provides; it uses the same global stylesheet (U-2, E-30). |
| 3 Another tenant: same files, no tenant data, no cookie, no tenant id in the URL | E-09 "another tenant sees the same own-origin font files: the URL carries no tenant data and no query" (two clinics, the font URL lists equal, each `/media/<name>-<hash>.woff2` with no query). E-26 (no cookie, no `authorization`, no query). |
| 4 Clinic LAN, internet blocked: medicine round / queue renders in Figtree (and Noto for Hindi/Marathi) with no console font error | E-10 "a clinic LAN with every outside address blocked: the queue and the home page render with all the fonts loaded" (the public Home page and then the signed-in queue are both opened under the block; the own origin comes from the project baseURL; every request off the app origin is aborted; none is even tried; a Figtree face is really loaded on Home; `check('600 16px Figtree')` true; no console error). The medicine round screen uses the same global fonts; the queue stands in. E-13/E-14 cover Hindi and Marathi. |
| 5 Second screen: files from cache with no new download | Not testable now (GL-41-1; see table). |
| 6 Dose characters (µg, ½, mg/mL) and ₹ on billing drawn in the right family, no empty boxes | E-16 on the Home page and on a signed-in screen (a probe line with ₹, é, µ, ½, ×, ± and "mg/mL" in the body font of the page: all drawn by Figtree web faces, no system face). The characters ≥ ≤ → ✓ come from a system font, as before; not asserted (Google's subsets are the same). |
| 7 Existing signed-in layout specs pass unchanged | R-1 to R-4. |
| 8 English-only screen: Devanagari files are not downloaded | E-15 "English signed-in and Home screens request no Devanagari file (BR12)" (Home, queue, today). E-25 "English Home page: records the bytes ..." asserts fewer than all seven files were fetched and logs the byte total. |
| 9 No font request carries a cookie or authorisation header | E-26 x4 "`<page>`: font requests carry no cookie and no authorization header" (Home, sign-in, admin sign-in, signed-in today) with two API-scoped cookies set (`sahaj_rt` at `/api/v1/platform/auth`, `sahaj_admin_rt` at `/api/v1/admin/auth`). E-27 "positive control: the API cookie IS sent to the API path it is scoped to, and not to a static path" proves the header check can see a cookie. |

### Story 41-3 · The look is unchanged

| AC | Test |
| --- | --- |
| 1 Exactly Figtree 400/500/600/700, Noto 400/500/600, Caveat 500 | U-4 "ships exactly the agreed families and weights" (source). E-18 "the declared faces are exactly the agreed set and all use font-display swap" (in the browser: 19 faces, 8 family-weight combinations, no other family). |
| 2 `font-display: swap` on each; family names match the stacks | U-3 "declares only own-file urls in _fonts.scss, with swap and woff2". E-18 (every face `display === 'swap'`, normal style). E-12 and E-17 (the stacks in `--font-display`, `--font-body`, `--font-hand` resolve to the loaded faces; no token was changed). |
| 3 Same family per character as before | E-13, E-14, E-16, E-17 (Rendered Fonts list). No pixel diff (see "Not testable now"). |
| 4 Layout shift on load not worse than today | R-1 to R-4 (the layout specs would fail on a wrapped heading, a moved button or an overflow). E-24 shows the swap with a delayed font does not overflow. No screenshot-diff before/after (no "before" build). |
| 5 Weight not more than Google | Developer's handoff (81,340 B). E-25 logs the measured number each run. |
| 6 A weight not in the list is reported, not added | Process check, not a test. Developer reported `overview.page.scss` weight 800 (drawn as 700). E-18 would fail if a 9th combination appeared. |

### Story 41-4 · Licence, hashed files, build

| AC | Test |
| --- | --- |
| 1 Licence text and copyright next to the files | Repository check: `frontend/src/assets/fonts/{figtree,noto-sans-devanagari,caveat}/OFL.txt` exist (read by U-3 only for woff2). E-29 below reads the shipped copies. |
| 2 Production output contains the licence files; font file names carry a hash | E-28 x3 "licence text of `<family>` is reachable at /fonts/licenses/`<family>`/OFL.txt" (200, `text/plain`, contains "SIL OPEN FONT LICENSE Version 1.1" and "Copyright", is not the SPA index page). E-29 "font files are served as media/`<name>`-`<hash>`.woff2 with a woff2 header; unhashed copies do not exist" (body starts with `wOF2`; the old unhashed paths return no font). E-30 "the production output lists 7 hashed woff2 files, 3 licence files, and no Google host in any html or css" (reads `dist/frontend/browser`). |
| 3 Cache headers | Not testable now (GL-41-1). |
| 4 Changed font file gives a new hash | Not tested in a browser. The build hashes by content (`outputHashing: all`); the name carries the hash (E-29, E-30). Developer check in the handoff. |
| 5 Build with no internet | See table. E-30 lists the output after `npm --prefix frontend run build`. |
| 6 woff2 only; subsetting/conversion stated; licence allows | E-29 (`wOF2` signature on each served file). Handoff and `frontend/src/assets/fonts/README.md` state that Google did the subsetting and no Reserved Font Name is declared. Documentation check, no test. |
| 7 No new dependency in `package.json` | Review check: `git diff -- frontend/package.json` shows no change (reported in the handoff). |
| 8 Frontend README says where fonts and licences are and how to update | Review check of `frontend/README.md` section "Fonts (self-hosted)". |

### Story 41-5 · Regression guard

| AC | Test |
| --- | --- |
| 1 Source check fails on a Google font host, passes now | U-1 "scans the frontend source", U-2 "finds no Google Fonts host in any source file". E-06 proves the detector works: it injects a Google stylesheet and an off-origin stylesheet (answered by a route, no real network) and the recorder reports both. The Vitest guard was not mutated by QA (the source is the developer's folder); its four tests are read and run. |
| 2 Playwright visits Home, sign-in and a signed-in screen and fails on any font host off the app origin | E-01 (Home, sign-in, admin sign-in, three signed-in screens) with the anti-vacuity assertion that the recorder saw the app's own stylesheet. |
| 3 Google hosts routed to fail: still passes | E-05 (all six pages), E-08, E-10. |
| 4 `validate frontend` and `validate docs` pass | `validate frontend` fails on the known `--browsers` flag (human fix); reported. `validate docs` result in the handoff. |

## Negative, boundary and role cases (summary)

- Negative: a Google host aborted (E-05, E-08), one file missing (E-20, E-21), all files missing (E-22, E-23), font files held back by a gate until released (E-24), all outside traffic blocked (E-10).
- Boundary: 360 px and 1440 px for every E test; the fonts' unicode-range edge characters (₹, é, µ, ½, ×) in E-16; the exact face set (19 faces, 8 combinations) in E-18.
- Roles: all seven roles open the today screen with no Google request (E-03); owner, nurse and pharmacist for the full offender check (E-02). The fonts do not depend on the role.
- Tenant isolation: two clinics get an identical list of font URLs with no query (E-09). There is no tenant data to leak or hide.

## Existing specs to re-run for font-swap shift (BR10)

| ID | Spec | Why |
| --- | --- | --- |
| R-1 | `home.spec.ts` (phone-360, desktop-1440) | Home layout, order, no overflow. |
| R-2 | `home-background.spec.ts` (phone-360, desktop-1440) | `settlePage()` waits for the late stylesheet and `document.fonts`; positions of the dot layer depend on the layout. |
| R-3 | `layout-matrix.spec.ts` (seven projects) | Signed-in IPD screens at seven sizes and two zooms. |
| R-4 | `app-shell.spec.ts` | Signed-in shell, boards and sheets. |

Results and pass/fail counts: see the handoff of this story.

## Evidence

Run results, counts per run and any defect are in the QA report returned to the tech lead, who records them in `docs/handoffs/SAH-41.md`.

## Results of the review fix pass (9 Nov 2026)

Changes after `docs/reviews/SAH-41-code.md` (QA findings #1, #4, #5, #6, #7, #10):

| Finding | Change |
| --- | --- |
| 1 | E-24 holds the font route with a gate the test opens. While held it asserts no Figtree face is loaded, `check("600 16px Figtree")` is false, and the h1 is visible with opacity above 0, a real box and a non-transparent colour. After release it waits for Figtree 600 and checks no overflow. |
| 4 | E-10 opens the Home page and then the signed-in queue under the outside-blocked route; for both, nothing outside is tried, no offender, `check` true (and a Figtree face is loaded on Home). |
| 5 | E-10 uses `new URL(baseURL).origin`; the cookie domain in E-26/E-27 is `new URL(baseURL).hostname`. |
| 6 | E-06 waits with `page.waitForRequest` for the two injected requests instead of 500 ms. |
| 7 | Header note in the spec: the 8-character hash pattern is a deliberate canary for a change in the build's hashing. |
| 10 | README heading in 41-4 AC8 is now "Fonts (self-hosted)". |

Runs (production build rebuilt before the runs, Chromium, projects phone-360 and desktop-1440):

| Run | Command | Passed | Failed | Skipped |
| --- | --- | --- | --- | --- |
| 1 | `self-hosted-fonts.spec.ts` | 96 | 0 | 0 |
| 2 | `self-hosted-fonts.spec.ts` | 96 | 0 | 0 |
| 3 | `self-hosted-fonts.spec.ts` | 96 | 0 | 0 |
| 4 | `home.spec.ts` + `home-background.spec.ts` | 174 | 0 | 2 |
| 5 | `npm --prefix frontend test -- --watch=false` (Vitest) | 2,242 (155 files) | 0 | 0 |

The 2 skipped tests in run 4 are existing `test.skip` conditions in `home.spec.ts` (section links are hidden at 980 px and below, so they run only on desktop-1440); they are not in the SAH-41 spec.
