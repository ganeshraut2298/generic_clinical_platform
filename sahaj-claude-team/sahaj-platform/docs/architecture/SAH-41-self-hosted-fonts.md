# SAH-41 · Self-host the web fonts: design note

Story: SAH-41 (`docs/stories/SAH-41.md`, stories 41-1 to 41-5; rules BR1 to BR15 in `docs/requirements/SAH-41.md`). Source: `docs/reviews/SAH-39-security.md` finding #2.
Mockup screen: Foundation (no screen). Every screen loads the fonts, and none of them changes.
Module: platform (frontend foundation only). **No API, no contract change, no table.** Nothing for the db-engineer or the java-developer.
**No ADR.** Removing a third-party runtime dependency does not change a module boundary, add a dependency or add a cross-cutting rule. It also adds no npm package.
Open questions: recommended defaults taken (DEC-023). OQ-41-1 uses vendored woff2 files. OQ-41-2 uses the latin, latin-ext and devanagari subsets. OQ-41-3 sends one year plus immutable for hashed files; the production server is a go-live condition. OQ-41-4 copies Google's files as they are (see section 4). OQ-41-5 is not in this story.

Facts this note relies on (checked in the repository on 9 Nov 2026):
- `frontend/src/index.html` lines 13 to 18 hold two `preconnect` links (to `fonts.googleapis.com` and `fonts.gstatic.com`) and one render-blocking stylesheet link to `css2?family=Figtree:wght@400;500;600;700&family=Noto+Sans+Devanagari:wght@400;500;600&family=Caveat:wght@500&display=swap`. No other file in `frontend/src` names a Google host.
- `frontend/angular.json` uses builder `@angular/build:application` (Angular 22). Its assets are `{ "glob": "**/*", "input": "public" }`. Production uses `outputHashing: "all"` and the default `optimization` (so critical-CSS inlining with beasties is on). The development configuration has `optimization: false` and no output hashing.
- The font stacks are `--font-display`, `--font-body` and `--font-hand` in `frontend/src/styles/_tokens.scss` lines 8 to 10, plus the Material `plain-family` and `brand-family` in `frontend/src/styles.scss` lines 18 to 19. The family names are `Figtree`, `Noto Sans Devanagari` and `Caveat`.
- `frontend/public/` holds only `favicon.ico` and `i18n/{en,hi,mr}.json`.
- **The Spring Boot app does not serve the frontend.** There is no `backend/src/main/resources/static/`, no resource handler and no `static-locations` setting. In dev, `ng serve` serves the app on :4200 and passes only `/api` to :8080 (`frontend/proxy.conf.json`).
- The two auth cookies are path-scoped to the API: `sahaj_rt` uses `Path=/api/v1/platform/auth` (`RefreshTokenCookie.java` line 19) and `sahaj_admin_rt` uses `Path=/api/v1/admin/auth` (`AdminRefreshCookie.java` line 19). The browser therefore sends no cookie with a request for a static font file.
- One spec already reads a source file with `fs` (`frontend/src/styles/tokens-dark-glass.spec.ts` line 15). That gives the pattern for the source guard.

---

## 1. File location and cache-busting

**How the build treats files today**
- Files under `public/` are **copied as they are**. `outputHashing` does not apply to assets, so a file in `public/fonts/` would keep its name, for example `/fonts/figtree.woff2`. A long immutable cache on that URL would then be unsafe, because a changed file would not reach browsers that already hold the old one.
- A `url()` in a global stylesheet that uses a **relative** path is resolved by the esbuild-based application builder. The builder bundles the referenced file and writes it to `dist/frontend/browser/media/<name>-<hash>.woff2`, because `outputHashing: all` includes media. It then rewrites the `url()` in `styles-<hash>.css`. A url that starts with `/` (root-relative) or with `http(s):` is left **external**: it is not processed and not hashed.

**Decision**
- The woff2 files live in **`frontend/src/assets/fonts/<family>/`** (folders `figtree/`, `noto-sans-devanagari/` and `caveat/`). They do **not** go in `public/`. Putting them in both places would ship the files twice and would leave an unhashed copy.
- The `@font-face` rules use **relative** urls, so the build hashes the files. Cache-busting comes from the content hash: a changed file gets a new hash, a new name and a rewritten `url()`. `index.html` and `styles-<hash>.css` then point to the new file. No versioned folder name is needed.
- The existing `public` assets entry does not touch `src/assets`, so `angular.json` does not need a change for the font files themselves. The licence files need one (section 6).
- In development (`ng serve`, development configuration) the files are served unhashed from memory. That is expected, and nothing in this story depends on it.
- **Developer check:** after `npm --prefix frontend run build`, `dist/frontend/browser/media/` contains one `*-<hash>.woff2` per file you ship. `styles-*.css` references `media/...` (or `./media/...`), with no `/assets/fonts/` and no Google host. If a url turns out not to be rebased, Sass did not resolve it from the partial's folder. In that case write the url relative to `src/styles.scss`, or move the rules into `styles.scss`, and record what you did. Do not fall back to `public/`.

## 2. Where the `@font-face` CSS lives

- Put it in a new partial, **`frontend/src/styles/_fonts.scss`**, used **first** in `frontend/src/styles.scss` (`@use './styles/fonts';` before `tokens`). The global stylesheet then carries the font faces, and no component stylesheet does. That matters because component budgets are 4 kB / 8 kB, and component styles are encapsulated.
- Use one `@font-face` block per family, weight and subset, exactly as in Google's response (section 4). In each block:
  - `font-family` is `'Figtree'`, `'Noto Sans Devanagari'` or `'Caveat'`. These match the stacks byte for byte, so no token or component changes (BR2).
  - `font-style: normal`.
  - `font-weight` is the weight from Google's block (400, 500, 600 or 700).
  - `font-display: swap` (BR4).
  - `src: url('<relative path>.woff2') format('woff2');`. Use `woff2` only, with no `local()` source. `local()` could pick up a different installed version and break "same look".
  - `unicode-range` copied verbatim from Google's block.
- Do not change `_tokens.scss` or the Material typography. The fallback chain (`system-ui`, `-apple-system`, `'Segoe UI'`, `sans-serif` / `cursive`) stays as it is, which is BR15's safe failure.
- Do not add fallback metric overrides (`size-adjust`, `ascent-override`) unless the developer sees a visible shift on swap. If one is needed, it goes to the tech lead first (BR10, story 41-3: a visual difference stops and goes to the human).

## 3. Subsets and character coverage (BR3, OQ-41-2)

Ship exactly the subsets Google serves today for the requested families, minus Cyrillic and Vietnamese:

| Family | Subsets shipped | Subsets Google serves that are dropped |
| --- | --- | --- |
| Figtree | latin, latin-ext | none (Google serves only these two) |
| Noto Sans Devanagari | devanagari, latin, latin-ext | none |
| Caveat | latin, latin-ext | cyrillic, cyrillic-ext (the app has no Cyrillic text) |

- **The `unicode-range` values are copied from Google's CSS, not rewritten.** The browser then picks a face for each character exactly as it does today, and downloads a file only when the page shows a character in that file's range (BR12). Because Figtree comes first in the stack and covers latin, the Noto latin and latin-ext files are only fetched for a Latin character Figtree lacks, as today.
- **₹ (U+20B9).** Google's latin-ext range for these families includes `U+20A0-20AB, U+20AD-20C0`, and the devanagari range lists `U+20B9`. A `unicode-range` only says what a file may contain, not what it does contain. The developer **confirms the glyph is present** with DevTools, Elements, Computed, "Rendered Fonts", on a ₹ in the Home pricing section and on a billing amount. Either way, the result must be the same family as before (BR3):
  - If Figtree latin-ext has the glyph, ₹ renders in Figtree, and the Figtree latin-ext file is downloaded on pages that show ₹.
  - If it lacks the glyph, ₹ falls through to Noto Sans Devanagari, and the browser downloads that face's file. This is today's behaviour too, so it is not a regression. Report it, because it counts toward the BR12 numbers on the Home page.
  - Do the same check for Caveat, which has ₹ only if the hand-written accent text shows one.
- **Clinical characters.** `µ` (U+00B5), `½` (U+00BD) and `×` (U+00D7) are all inside the latin range (`U+0000-00FF`). The developer lists the special characters found in `public/i18n/*.json` and the templates in the handoff, and checks the rendered family for each (patient-safety point).
- Hindi and Marathi both use the devanagari subset. Devanagari files are fetched only when Devanagari text is on screen, which meets the BR12 rule for English-only screens.

## 4. How the woff2 files are obtained (OQ-41-1, OQ-41-4)

There is a **one-time manual download by the angular-developer**, outside the build. It adds **no npm dependency** (`@fontsource` is not used). The files are committed, so an offline build works (BR6). The steps:
1. Request the exact `css2` URL from today's `index.html` (section "Facts") with a current Chrome or Edge desktop user agent, so that the response lists `woff2` with `unicode-range`. Save the response as the reference.
2. Keep only the latin, latin-ext and devanagari blocks (section 3). Download each **unique** `fonts.gstatic.com` file URL once. Figtree, Noto Sans Devanagari and Caveat are variable families, so several weight blocks may share one file URL. Keep that sharing: write one file and have several `@font-face` blocks point to it. If Google returns a different URL per weight, keep one file per weight. This copies Google's own layout, so the look, the weight matching and the byte count all stay the same as today. That is the simplest way to meet BR2, BR3, BR10 and BR12, and it settles OQ-41-4.
3. Give the files plain names, for example `figtree-latin.woff2`, `figtree-latin-ext.woff2`, `noto-sans-devanagari-devanagari.woff2` and `caveat-latin-500.woff2`. Changing the **file** name is allowed. The font's internal name tables are not edited.
4. Take each family's `OFL.txt` from the upstream source, the Google Fonts repository (`github.com/google/fonts`, folders `ofl/figtree/`, `ofl/notosansdevanagari/` and `ofl/caveat/`). Save it as `frontend/src/assets/fonts/<family>/OFL.txt`. The file includes the copyright line.
5. Write **`frontend/src/assets/fonts/README.md`**, with:
   - for each family: upstream source URL, font version (from the `METADATA.pb` / `name` table), licence, and whether a Reserved Font Name is declared in the `OFL.txt` header
   - the css2 URL used, the date, and the user agent
   - for each file: original gstatic URL, local name, SHA-256, and size in bytes
   - "how to update": repeat steps 1 to 5 and replace the files. The new hash then busts the cache by itself.
6. **Licence note (BR8):** Google's files are subset versions of the upstream fonts. OFL 1.1 allows modified versions (subsetting and format conversion) on condition that a Reserved Font Name is not used for a modified version. The developer reads each `OFL.txt` header and records in the handoff whether it declares a Reserved Font Name. If any family declares one, stop and report to the tech lead before shipping.

## 5. Preload and the critical-CSS inliner (beasties)

- **No preload link is written by hand in `src/index.html`.** The file names carry a build hash that the source cannot know. A preload to an unhashed copy would mean a second, unhashed file, which is what section 1 avoids. BR12 makes preload optional ("may"). The files come from the same origin, so there is no DNS or TLS setup to hide, and that was the main gain of the old `preconnect`.
- **Production build and beasties.** With the default `optimization`, Angular inlines the critical CSS for the static `index.html` (only `<app-root>` is present, so the inlined block is small). It then loads the full `styles-<hash>.css` in a non-blocking way (the `media="print"` / `onload` swap). Consequences:
  - The `@font-face` rules are always in `styles-<hash>.css`. Beasties may also copy some into the inlined `<style>`, depending on its font options. Either way the `url()` values point to the hashed `media/` files, which resolve against `<base href="/">`. The developer checks the built `index.html`: there must be no reference to `/assets/fonts/`, no Google host, and no `preload` to a non-own host.
  - Beasties might add a `<link rel="preload" as="font">` for fonts it finds in critical CSS. That is acceptable only if the link points to `media/...-<hash>.woff2` on the own origin.
  - Font discovery now waits for `styles-<hash>.css` to apply. Today the Google stylesheet was render-blocking. First paint can therefore show the fallback face for a moment, which `swap` allows (BR4). Because the files are local, this should be short. If the developer measures a visible jump at 360 px or 1440 px, report it to the tech lead with numbers. Do not switch off `inlineCritical`: that is a build-wide change and needs a decision.
- **SAH-39 `settlePage()`** (in `frontend/e2e/home-background.spec.ts`, see `docs/handoffs/SAH-39.md` line 77) waits for the late `styles-*.css` swap and for the fonts (`document.fonts`). Both waits stay valid, and the font wait should now end sooner and no longer depend on the internet. QA does not change `settlePage()` in this story unless a layout spec flakes. The layout specs at 360 px and 1440 px must pass unchanged (BR10).

## 6. Licence files in the built output (BR8)

- The licence files are kept next to the files in the repository (`src/assets/fonts/<family>/OFL.txt`).
- The angular-developer adds one assets entry to `frontend/angular.json` (inside `frontend/`, so it is their file) so the notices ship:
  `{ "glob": "*/OFL.txt", "input": "src/assets/fonts", "output": "fonts/licenses" }`
  The built output then contains `fonts/licenses/<family>/OFL.txt`. Text files may stay unhashed; they are not long-cached (section 7).
- Do not copy the woff2 files through `assets`; they come in through `url()` only. `extractLicenses` (`3rdpartylicenses.txt`) covers npm packages only and does not replace this.
- Optional: add `/*! Fonts: SIL OFL 1.1, see /fonts/licenses/ */` at the top of `_fonts.scss` (the `/*!` form survives minification).

## 7. Cache headers (OQ-41-3)

| Where | Who serves the files | Decision |
| --- | --- | --- |
| Dev (`ng serve`) | Angular dev server, in memory, unhashed | **No change.** Development needs no long cache. Do not set a dev-server `headers` option: it applies to every response, including `index.html`. |
| Spring Boot | **Does not serve the frontend** (see "Facts") | **Nothing to configure in this story.** The java-developer is not needed. |
| Production static server | **Not defined in the repository** | **Go-live condition GL-41-1** (below). |

**GL-41-1 (go-live condition, for the human and whoever owns deployment).** The server that serves `dist/frontend/browser/` must:
- send `Cache-Control: public, max-age=31536000, immutable` for `/media/*` and for the hashed `*.js` / `*.css` files
- send `Cache-Control: no-cache` for `index.html` and for every SPA fallback route that returns `index.html`
- give unhashed files (`/i18n/*.json`, `favicon.ico`, `/fonts/licenses/*`) a short lifetime or `no-cache`
- send `Content-Type: font/woff2` for `.woff2`
- set no cookie on `/` or on static paths, so that the API cookies keep their `/api/v1/...` scope
- serve the SPA and `/api` from the **same origin**.

If production later has Spring Boot serve the SPA, that is a deployment and boundary decision and needs an ADR. Spring Security's default headers (`Cache-Control: no-cache, no-store`) would then have to be relaxed for `/media/**` only.

## 8. CSP-friendliness and privacy

- After the change, every font and font stylesheet comes from `'self'`. A future policy can use `font-src 'self'` and `style-src 'self'` with no Google host (backlog: platform hardening, SAH-39 finding #3).
- Not caused by this story, and not solved by it: beasties' inlined `<style>` and its `onload` attribute need a nonce, a hash or `'unsafe-inline'` under a strict CSP. Record this for the platform-hardening story.
- The font requests carry no cookie, because the cookies are path-scoped to `/api/v1/.../auth`. They carry no `Authorization` header (the browser never adds one to a CSS-initiated fetch), and no tenant id. Fonts need no sign-in (BR14, story 41-2). Nothing is logged or stored.

## 9. Removal from `index.html`

Delete lines 13 to 18 of `frontend/src/index.html`: both `preconnect` links and the Google stylesheet link. Add nothing in their place (section 5). `favicon.ico`, the meta tags and `<base href="/">` stay as they are.

## 10. Guards against regression (BR13, story 41-5)

- **Source guard (angular-developer).** A Vitest spec, for example `frontend/src/styles/no-third-party-fonts.spec.ts`, follows the `fs` pattern of `tokens-dark-glass.spec.ts`. It walks `frontend/src` and fails if any file contains `fonts.googleapis.com` or `fonts.gstatic.com`. It runs under `npm --prefix frontend run test`, and so inside `validate frontend`. The mockup and the docs are outside `frontend/src`, so they are not scanned. A grep in `scripts/validate.mjs` would also work, but that script is human-owned: the developer may only request it in the handoff.
- **e2e guard (qa-engineer, `frontend/e2e/`).**
  1. Record every request with `page.on('request')` on the Home page, the Start screen / sign-in, the admin sign-in, and at least one signed-in screen (for example the queue or the medicine round). Fail if a request with `resourceType` `font` or `stylesheet` goes to a host other than the app origin, or if any request goes to `fonts.googleapis.com` / `fonts.gstatic.com`.
  2. Route `**/fonts.googleapis.com/**` and `**/fonts.gstatic.com/**` to `abort`, then run the existing "page loads with the headline, no script errors and no horizontal page overflow" test. It must pass.
  3. Assert that `document.fonts.check('600 16px Figtree')` and `document.fonts.check('500 16px Caveat')` are true after load. On a Hindi or Marathi page, also assert `'400 16px "Noto Sans Devanagari"'`.
  4. BR15: abort one local `media/*.woff2` and assert there is no page error and the text is visible.
  5. BR12: on the English Home page, assert that no devanagari file is requested, unless the ₹ fallback in section 3 applies. Record the total font bytes for the handoff.
  6. Font responses carry no `cookie` request header.
  7. The existing 360 px and 1440 px layout specs run unchanged.
- QA notes in the test plan that the cache headers (GL-41-1) cannot be tested until the production server exists. In dev, only "served from own origin" and "hashed name in the production build" (`ng build`, then list `dist/frontend/browser/media/`) can be checked.

## 11. Frontend README (scope item 8, angular-developer)

Add a short section to `frontend/README.md` that covers:
- the fonts live in `src/assets/fonts/` and are declared in `src/styles/_fonts.scss`
- the licences are in `src/assets/fonts/<family>/OFL.txt`, shipped to `fonts/licenses/`
- the build hashes the files through `url()`
- how to update a font: follow `src/assets/fonts/README.md`
- never add a Google Fonts link back; the guard spec will fail.
