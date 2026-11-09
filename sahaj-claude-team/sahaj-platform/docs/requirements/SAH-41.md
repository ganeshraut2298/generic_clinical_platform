# SAH-41 · Self-host the web fonts (no visitor or staff request to Google)
Module: platform
Mockup screen: Foundation (no screen) — `docs/design/screen-map.md` row "Foundation (no screen) | platform". It touches every screen (public Home page, Start screen and sign-in, and all signed-in OPD, IPD, billing, HRA and team screens), but **no screen changes visually**. The mockup (`docs/design/mockup/sahaj-mockup.html` lines 4-6) loads the same three families (Figtree 400/500/600/700, Noto Sans Devanagari 400/500/600, Caveat 500) from Google; the requirement is "same fonts, same weights, same look, served from our own origin". Built in `frontend/src/index.html` and the global styles in `frontend/src/styles/`.
Source: Tech lead, from `docs/reviews/SAH-39-security.md` finding #2 (Info, pre-existing; first seen in `docs/reviews/SAH-1-security.md`). Human gave the go-ahead to continue development (chat, 8-9 Nov 2026). Recommended defaults are taken per DEC-023.

## Problem
Every page of the app (public Home page, sign-in, and every signed-in clinical screen) loads its fonts from `fonts.googleapis.com` and `fonts.gstatic.com` (`frontend/src/index.html` lines 13-17). Each page load therefore sends the visitor's or staff member's IP address and browser details (user agent) to Google, a third party outside India. This conflicts with the spirit of "all patient data stays in India" (CLAUDE.md) and with data minimisation under the DPDP Act. It also has two practical costs:
- On a clinic LAN with a slow or cut internet link, the fonts do not load, the text falls back to another face, and the layout can shift. A clinic must not depend on a Google server to read a prescription screen.
- Any future Content Security Policy would have to allow a remote style and font source, which weakens it (SAH-1 security review).
- The e2e test "page loads with the headline, no script errors..." (`frontend/e2e/home.spec.ts`) failed once when Google Fonts was unreachable. Self-hosting removes that source of flakiness.

The fix is to serve the same font files from Sahaj's own origin. Nothing the user sees should change.

## Who uses it (roles) and in which setting
No role performs an action; every role is affected because every page loads the fonts.
- **Visitor** (not signed in; a prospective clinic owner or doctor), on the public Home page and the Start screen / sign-in. Benefit: no request to Google.
- **Owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist** (signed in), at the front desk, in OPD rooms, on the hospital ward (tablets, shared PCs, ward Wi-Fi) and in clinics with a LAN and poor or no internet. Benefit: no request to Google, same text everywhere, screens usable when the internet is down.
- **Sahaj platform admin** (admin portal, `frontend/src/app/features/admin`): the admin pages use the same fonts and are covered by the same rule.
- **Security reviewer / human approver:** needs a simple way to verify there is no third-party font request.

## In scope / Out of scope
In scope:
1. Serve Figtree, Noto Sans Devanagari and Caveat from the app's own origin, in the same families and the same weights as today: Figtree 400, 500, 600, 700; Noto Sans Devanagari 400, 500, 600; Caveat 500.
2. Remove the `preconnect` and stylesheet links to Google from `frontend/src/index.html`. Add local font-face declarations (the developer's choice of file layout, see OQ-41-1) with `font-display: swap`.
3. Cover the scripts the app shows: Latin (including the Latin Extended characters used in Indian names and the ₹ sign) and Devanagari (Hindi and Marathi labels; the app has `en`, `hi` and `mr` text files).
4. Ship the font licence text (SIL Open Font License 1.1) and copyright notices next to the font files, in the repository and in the built output.
5. Fingerprinted file names (build asset hashing) and long-lived cache headers for the font files, so a font is downloaded once per version.
6. A repeatable check that no page requests any third-party font host: a browser test (Playwright network assertion, written by QA) and a source check that fails if a Google Fonts URL returns to the frontend source.
7. Works with no internet access at all (clinic LAN or offline after the first visit).
8. A short note in the frontend README on where the fonts live, how the licence is kept, and how to update a font (written by the angular-developer).

Out of scope:
- Any change to fonts, sizes, weights, colours, spacing or layout. No new family and no new weight.
- A new npm library (unless the human approves, OQ-41-1).
- Other third-party requests (analytics, maps, scripts, images). The test in 41-1 will only check fonts and stylesheets; a wider "no third party at all" check is a separate backlog item if the human wants it.
- A Content Security Policy (backlog: platform hardening, SAH-39 review finding #3). This story only makes it possible to have a strict one later.
- Editing `docs/design/mockup/sahaj-mockup.html`. It is human-owned and still references Google Fonts (lines 4-6). **Note for the human:** the mockup is a design file and is not shipped, so no change is needed for this story. If you want it consistent, you may point it to local files; no agent will touch it.
- Server or API changes, tables, notifications, documents.
- Offline support for the whole app (service worker, offline data). Only "fonts do not need the internet" is in scope.

## Business rules (numbered)
BR1. **No third-party font request.** On every route (Home page, Start screen and sign-in, sign-up, admin sign-in, every signed-in screen, 404), the browser makes no request to `fonts.googleapis.com`, `fonts.gstatic.com`, or any other host that is not the app's own origin, to get a font or font stylesheet. This includes no `preconnect`, `dns-prefetch` or `preload` link to such a host.
BR2. **Same fonts.** The families and weights are exactly: Figtree 400, 500, 600, 700; Noto Sans Devanagari 400, 500, 600; Caveat 500. The CSS font stacks stay as they are (`--font-display`, `--font-body`, `--font-hand` in `frontend/src/styles/_tokens.scss`, and the Material typography in `frontend/src/styles.scss`). No new or removed weight.
BR3. **Same character coverage.** Latin, Latin Extended (so that names with accents and the ₹ sign render in Figtree and not in a fallback face) and Devanagari (Hindi and Marathi). A character that rendered in the right family before must render in the same family after. The glyph subsets are the same as Google serves for these families (for example latin, latin-ext, devanagari), or wider, never narrower. The developer confirms the ₹ (U+20B9) is in the shipped Figtree and Caveat files, or in the fallback chain exactly as before.
BR4. **Swap behaviour kept.** Each font face uses `font-display: swap`, as today: text shows at once in the fallback face and swaps when the font is ready. Because the files are local, the swap should be almost invisible. Fallback faces are tuned only if needed to avoid a visible layout shift (see BR10).
BR5. **Own origin only.** Font files and the CSS that names them are served by the same origin as the app. No CDN, no other domain, no runtime download from the internet. No font is fetched from the internet at build time by the running app (the build may copy files already in the repository).
BR6. **Files are in the repository.** The font files are committed (vendored) or come from an approved package (OQ-41-1). A build on a machine with no internet access (after dependencies are installed) still produces a working app with the fonts.
BR7. **Format.** The font files are `woff2` (all supported browsers read it). No `ttf` or `otf` is shipped to browsers unless a browser the product supports needs it (the developer states which browsers are supported if in doubt).
BR8. **Licence shipped.** Figtree, Noto Sans Devanagari and Caveat are published under the SIL Open Font License 1.1. The licence text and each family's copyright line are committed next to the font files and are present in the built output (for example `OFL.txt` per family or one combined notice file). The licence terms are met: the notice travels with the files; the fonts are not sold on their own; reserved font names are respected (files are not renamed or modified in a way the licence forbids; subsetting or format conversion is allowed only if the licence allows it for that family, and the developer records this).
BR9. **Cache and hashing.** The font files get a content hash in their file name in the production build (the build already uses `outputHashing: all` for application files; the font files must follow the same rule or an equivalent), and are served with a long cache lifetime marked immutable. The page itself (`index.html`) is not cached for long. What cache header value is used, and who sets it (the web server or the Spring Boot app), is for the solution-architect (OQ-41-3).
BR10. **No layout regression.** At 360 px and 1440 px, every screen with a Playwright layout spec still passes unchanged. Text positions and sizes are the same as before within 1 px once the font has loaded, and there is no new horizontal overflow. The first paint does not show invisible text (swap, not block).
BR11. **Works with no internet.** With all external network access blocked (clinic LAN with the internet cut), every screen renders in the right fonts, and the console shows no font error. After the first visit the font files are in the browser cache and need no request for their cache lifetime.
BR12. **Does not slow the first view.** Only the fonts a page needs are requested (the browser downloads a face when the page uses it; Devanagari faces are not downloaded for an English-only screen). The files for the Home page's first view are the Latin faces. The developer may preload the one or two most important files from the own origin. The total font weight transferred for an English page is not more than the Google download is today for the same page (the developer states the numbers in the handoff).
BR13. **A guard against regression.** A check fails the build or the test run if a Google Fonts URL (`fonts.googleapis.com`, `fonts.gstatic.com`) appears in `frontend/src` (excluding the mockup and docs), or if a page at run time makes a request to those hosts.
BR14. **No data collected.** The change adds no cookie, storage, logging, tracking or analytics. The logs never carry the visitor's IP together with the page (nothing is added).
BR15. **Failure is safe.** If a local font file fails to load (for example a deleted file), the page still works in the fallback face (`system-ui`) and no script error is raised.

## Patient-safety and privacy points
- Privacy: the visitor's or staff member's IP address and browser details are no longer sent to a third party on every page load. This supports "all patient data stays in India" and DPDP data minimisation. Staff IP addresses combined with the time of visit to a clinical screen can indirectly reveal a clinic's activity; this is also removed.
- No patient data is involved in this change, and none is added. Never log patient names, mobile numbers, ABHA numbers or clinical notes (unchanged).
- Patient safety (readability): the clinical screens (medicine round, allergies, doses, bed board) must be as readable as today. Wrong or missing glyphs in doses and units (for example "µg", "½", "₹") are a safety concern. BR3 and BR10 make the test cover the characters used in the existing screens (digits, ½, µ, ₹ if shown in the product text). The developer lists the special characters found in the app text in the handoff, and QA checks them in the browser.
- Availability: a ward must be able to open the screens when the internet is down (BR11).
- Hindi and Marathi labels must not turn into boxes or a mix of faces if the Devanagari file fails to load (BR15).

## Plan or entitlement
None. Fonts are part of the frontend foundation and apply to every plan, module and role. No plan flag, no module flag, no per-clinic setting.

## Open questions for the human
Full text and recommended defaults are in `docs/stories/SAH-41.md` section "Open questions". Summary (all defaults taken under DEC-023 unless you answer otherwise):
- **OQ-41-1** How to get the font files in: vendored `woff2` files plus local `@font-face` CSS, or an `@fontsource` npm package? Default: vendored files, no new dependency.
- **OQ-41-2** Which glyph subsets? Default: latin, latin-ext and devanagari, as Google serves today; no Vietnamese, no Cyrillic.
- **OQ-41-3** Who sets the cache header (web server in front of the app, or Spring Boot) and how long? Default: one year and immutable for hashed font files; for the architect to confirm for the production setup.
- **OQ-41-4** Variable font file or one static file per weight? Default: the developer picks whichever gives the same look and smaller download; both are allowed if the weights in BR2 look the same.
- **OQ-41-5** Should a wider "no third-party request at all" check be a separate story? Default: not now; BR13 covers fonts only.
