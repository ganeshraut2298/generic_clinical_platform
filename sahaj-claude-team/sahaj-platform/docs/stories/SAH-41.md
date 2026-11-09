# SAH-41 · Stories: Self-host the web fonts
Requirement: `docs/requirements/SAH-41.md`. Module: platform.
Mockup screen: Foundation (no screen); affects every screen but changes none (`docs/design/mockup/sahaj-mockup.html` lines 4-6 load the same three families).
Rule numbers (BR1 ...) refer to the requirement.

### SAH-41-1 · As a visitor to the public Home page, I want the page to load without contacting Google, so that my IP address and browser details are not shared with a third party
Priority: Must
## Acceptance criteria
- Given a visitor who is not signed in, when they open the Home page, then the browser makes no request to `fonts.googleapis.com`, `fonts.gstatic.com` or any other non-own host for a font or font stylesheet, and the page source has no `preconnect`, `dns-prefetch` or `preload` link to such a host. (BR1)
- Given the Home page in the browser developer tools Network tab, when it finishes loading, then every font request is to the app's own origin. (BR1, BR5)
- Given the Home page, when it loads, then the headline, nav, pricing, compare table, FAQ and enquiry sheet show in Figtree, and the hand-written accent text shows in Caveat 500, as before. (BR2)
- Given the visitor switches the page language to Hindi or Marathi, when the page renders, then the Devanagari text shows in Noto Sans Devanagari and is not boxes or a mix of faces. (BR2, BR3)
- Given a Home page text with the ₹ sign and with an accented Latin letter, when it renders, then it is drawn in the same family as the surrounding text, not in a fallback face. (BR3)
- Given the Google Fonts hosts are unreachable (blocked in the test), when the Home page loads, then the page loads fully, the e2e test "page loads with the headline, no script errors and no horizontal page overflow" passes, and no console error mentions a font. (BR1, BR11, BR15)
- Given a local font file is missing or fails (simulated by blocking one file), when the page loads, then the text shows in the `system-ui` fallback, the page works, and no script error is raised. (BR15)
- Given the Home page at 360 px and 1440 px, when the fonts have loaded, then every existing Playwright home layout spec passes unchanged, with text positions and sizes within 1 px of before and no horizontal overflow. (BR10)
- Given a slow connection, when the page loads, then text is visible at once in the fallback face and swaps when the font is ready, and no text is invisible while waiting. (BR4)
## Notes for design
No visual change. The mockup defines the fonts; the page keeps the CSS variables `--font-display`, `--font-body`, `--font-hand`. Open question OQ-41-1 decides how the files get in.

### SAH-41-2 · As an owner, doctor, visiting doctor, nurse, front desk, billing or pharmacist, I want every sign-in and clinical screen to use fonts from Sahaj's own server, so that my clinic's visits are not visible to Google and the screens still read correctly when the clinic's internet is down
Priority: Must
## Acceptance criteria
- Given a signed-in user of any role (owner, doctor, visiting doctor, nurse, front desk, billing, pharmacist), when they open the sign-in, sign-up, today, queue, appointments, IPD (bed board, medicine round, safety), billing, HRA, team and settings screens that exist, then the browser makes no request to any third-party font host. (BR1)
- Given a Sahaj platform admin, when they open the admin sign-in and admin screens, then no third-party font request is made. (BR1)
- Given a user of another tenant, when they open any screen, then they get the same fonts from the same own-origin files; fonts are the same for every tenant and carry no tenant data, no cookie and no tenant id in the URL. (BR14)
- Given a clinic LAN with all external network access blocked and the app server reachable, when a nurse opens the medicine round or a front desk user opens the queue board, then every screen renders in Figtree (and Noto Sans Devanagari for Hindi or Marathi labels) with no console font error. (BR11)
- Given the user opened the app once, when they open another screen in the cache lifetime, then the font files come from the browser cache with no new download. (BR9, BR11)
- Given a clinical screen that shows doses and units (for example "µg", "½", "mg/mL") and the ₹ sign on billing, when it renders, then each character shows in the correct family and is clearly readable, with no empty boxes. (BR3, patient-safety point)
- Given any existing Playwright layout spec for a signed-in screen at the sizes it already tests, when it runs after the change, then it passes unchanged. (BR10)
- Given a user on an English-only screen, when the page loads, then the Devanagari font files are not downloaded; only the faces the page uses are requested. (BR12)
- Given a signed-in session, when the user opens the browser Network tab, then no font request carries a cookie or an authorisation header (the fonts are public static files). (BR14)
## Notes for design
No visual change. Whether the files are served by Spring Boot or a front web server is for the solution-architect (OQ-41-3). Fonts are public static files and must not need sign-in to fetch (the sign-in page needs them).

### SAH-41-3 · As a visitor or staff member, I want the text to look exactly as before in every language, so that nothing in the product is harder to read after this change
Priority: Must
## Acceptance criteria
- Given the shipped font set, when listed, then it contains exactly Figtree 400, 500, 600, 700; Noto Sans Devanagari 400, 500, 600; and Caveat 500; no other family or weight is added or dropped. (BR2)
- Given the font-face declarations, when read, then each one has `font-display: swap`, and the family names match the stacks in `--font-display`, `--font-body`, `--font-hand` and the Material typography so no component needs a font change. (BR2, BR4)
- Given the Latin, Latin Extended and Devanagari subsets, when a sample page of English, Hindi and Marathi text, digits, ₹ and an accented name is rendered before and after, then the family used for each character is the same as before (checked with browser tools), and the visual comparison shows no difference beyond browser rendering noise. (BR3)
- Given the Home page and one signed-in screen at 360 px and 1440 px, when screenshots are taken before and after the fonts load, then the layout shift on load is not worse than today (no visible jump of headings, buttons or table columns). (BR4, BR10)
- Given the font weight transferred for the English Home page, when measured, then it is not more than the Google download for the same page today, and the developer states both numbers in the handoff. (BR12)
- Given a weight not in the list (for example 300 or 800) used by some component, when found by the developer, then they report it to the tech lead and do not silently add it. (BR2)
## Notes for design
The mockup is the design (lines 4-6 and `--font-display`, `--font-body`). If a visual difference appears, it stops and goes to the human; it is not fixed by changing sizes.

### SAH-41-4 · As a platform admin / security reviewer, I want the font licence shipped, the files fingerprinted and long-cached, and the app buildable with no internet, so that we meet the font licence and every release is predictable
Priority: Must
## Acceptance criteria
- Given the repository, when the font folder is opened, then it contains each family's SIL Open Font License 1.1 text and copyright line next to its files (one licence file per family or one combined notice file). (BR8)
- Given a production build, when the output is listed, then the licence and copyright notice files are present in it, and the font file names carry a content hash. (BR8, BR9)
- Given the font files served by the running app, when the response headers are read, then font files have a long cache lifetime marked immutable (value per OQ-41-3), and `index.html` is not cached for long. (BR9)
- Given a changed font file, when a new build is made, then its hash and file name change, so a browser downloads the new file. (BR9)
- Given a machine with dependencies installed and no internet access, when `npm --prefix frontend run build` runs, then it succeeds and the output includes the fonts. (BR6)
- Given the font files, when checked, then they are `woff2`, and the handoff states whether any of them was subset or converted and confirms the licence allows it for that family. (BR7, BR8)
- Given `package.json`, when the diff is read, then no new dependency was added unless the human approved an `@fontsource` package under OQ-41-1. (BR6)
- Given the frontend README, when read, then it says where the fonts live, where the licences are, and how to update a font. (scope item 8)
## Notes for design
Cache header ownership (front web server or Spring Boot static resources) and the exact value are for the solution-architect (OQ-41-3). The DB engineer is not involved.

### SAH-41-5 · As a tech lead / security reviewer, I want an automatic check that fails when a third-party font host comes back, so that the privacy fix cannot quietly be undone
Priority: Should
## Acceptance criteria
- Given the frontend source (`frontend/src`, excluding docs and the mockup), when the check runs, then it fails if it finds `fonts.googleapis.com` or `fonts.gstatic.com`, and passes on the current source after the change. (BR13)
- Given a Playwright test (written by QA in `frontend/e2e/`), when it visits the Home page, sign-in, and at least one signed-in screen with all non-own-origin requests recorded, then it fails if any request goes to a font host that is not the app's own origin. (BR1, BR13)
- Given the Playwright test with the Google hosts routed to fail, when it runs, then it still passes (no flake from an unreachable Google). (BR1, BR11)
- Given `node scripts/validate.mjs frontend` and `node scripts/validate.mjs docs`, when run, then they pass. If the source check is to run inside `validate.mjs`, that script is human-owned: the developer writes the request in the handoff and does not edit it. (BR13)
## Notes for design
`scripts/` is human-owned. The source check may live as a unit test or lint rule in `frontend/` instead; the developer chooses. The wider "no third-party request at all" check is OQ-41-5.

## Open questions
Each has a safe default taken under DEC-023. The human may override any of them.

- **OQ-41-1 · How do the font files get in?** (a) Vendor the `woff2` files into the frontend (`frontend/public/fonts/` or `src/assets`) with a local `@font-face` CSS file and the licence files; (b) add the `@fontsource` npm packages (`@fontsource/figtree`, `@fontsource/noto-sans-devanagari`, `@fontsource/caveat`). **Default: (a), vendored files, no new dependency.** The files come from the official Google Fonts repository releases, which carry the OFL. Reason: no dependency to update or audit, works with no internet at build time, the CLAUDE.md "no new library without approval" rule is kept. If the human prefers (b), say so and the angular-developer will use it; this is a new npm library and needs your approval.
- **OQ-41-2 · Which glyph subsets?** **Default: latin, latin-ext and devanagari**, the same ranges Google serves for these families today; no Vietnamese or Cyrillic. Hindi and Marathi both use Devanagari. If a language is added later (Tamil, Bengali and so on) it needs its own story and its own font.
- **OQ-41-3 · Cache headers: who and how long?** **Default: `Cache-Control: public, max-age=31536000, immutable` for the hashed font files, and a short or no-cache rule for `index.html`.** Who sets it (Spring Boot or a front web server) depends on the production setup, which is not described in the repository yet. The solution-architect confirms and writes it in the handoff. If production is not decided yet, the developer sets it for the dev server and Spring Boot only and records the rest as a go-live condition.
- **OQ-41-4 · Variable font or static files?** **Default: the developer's choice**, as long as the weights in BR2 look the same and the download is not larger than today. A variable Figtree file is smaller, but a static file per weight is the easiest to keep the same as the mockup.
- **OQ-41-5 · Wider "no third-party request" check?** **Default: not in this story.** BR13 checks fonts only. If you want one test that fails on any non-own-origin request from every page, it is a separate small story for QA.
- **OQ-41-6 · Mockup file (note, not a question).** `docs/design/mockup/sahaj-mockup.html` is human-owned and still loads Google Fonts. It is a design file and does not ship, so nothing breaks. No agent will edit it; you may update it if you want the design and the app to match.
