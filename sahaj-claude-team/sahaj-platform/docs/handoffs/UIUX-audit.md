# UI/UX audit: built screens against the mockup

Scope: every built screen in `frontend/` against `docs/design/mockup/sahaj-mockup.html` (the final clinic console, classes `.ax.cs`) and `docs/design/tokens.md`. Behaviour, API calls and test intent are unchanged. Written by the Team C UI/UX pass; no branch change, no commit.

## 1. What the mockup actually specifies (the standard used)

The mockup CSS has several layers. The last layer that applies to the clinic console wins, so the effective standard is:

| Area | Mockup value (console, `.ax.cs`) |
|---|---|
| Shell grid | sidebar 248 px (60 px collapsed), work area fills the rest; sidebar sticky, full height, `--surface`, right border |
| Drawer | at 1000 px and below the sidebar is a 280 px drawer with a scrim; no bottom tab bar (the older `.app` bottom bar is replaced) |
| Topbar | sticky, 7 px 20 px padding, bottom border, `--bg` 82 % with 14 px blur, 10 px gap, wraps |
| Work area | `main.page` padding 14 px 20 px, max width 1560 px, centred, 12 px gap |
| Base type | 13.5 px in the console, h1 1.18 rem in a page header, h2 .95 rem in a panel header |
| Page header | title, one-line lead (.8 rem, `--ink-2`), actions on the right, vertically centred, wraps |
| Panel | `.glass` (translucent, 20 px blur, glass edge, `--r` 18 px, `--shadow`), padding 13 px 14 px, 10 px gap |
| KPI tile | glass, padding 9 px 14 px, eyebrow on its own line, value 1.28 rem and sub text side by side; stacks on phones |
| List row | 9 px 2 px padding, 1 px `--line-2` divider, `.main b` 600, `.main small` `--ink-3` .78 rem |
| Empty state | dashed `--line` box, centred, `--ink-3`, .82 rem |
| Pills | 999 px, .72 rem, soft background with matching text: wait, warn, teal/consult, sec/blue/billing, good/done, crit |
| Tabs / segmented | glass bar, 3 px padding, selected = `--glass-hi` + `--pri-text` + soft shadow |
| Fields | label .8 rem 600 `--ink-2`; input 12 px radius, glass-hi, focus = `--pri` border + 3 px `--pri-soft` ring |
| Sheet (dialog) | 22 px radius, glass-hi, header 20/22, body 16 px gap, footer with top divider, bottom sheet at 620 px |
| Colours | tokens only; status colours always paired with text or icon |

## 2. Findings before the change

### 2.1 Systemic (the cause of the "inconsistent layout")

| # | Finding | Where |
|---|---|---|
| S1 | The same blocks (`.pagehead`, `.lead`, `.actions`, `.panel`, `.panel-h`, `.kpis`, `.kpi`, `.list`, `.li`, `.main`, `.empty`, `.notice`, `.tabs`, `.seg`, `.field`, `.input`, `.sw`, `.sheet*`) were re-declared in 25+ component styles with different numbers. Example: h1 was 1.4 rem on most pages, 1.5 rem in Clinic setup, 1.3 rem in the patient record; panel padding 16 px; `.li` padding 8 to 12 px; `.seg` was square-ish on Revenue, pill on Appointments and Staff, soft-grey on Billing. | all pages |
| S2 | Panels used `.surf` (opaque white, 16 px radius, from the landing page) where the mockup console uses `.glass` (18 px radius, translucent, blurred). | every console page |
| S3 | The shell page area had 16 px padding and no max width; Clinic setup added a second `<main class="page">` inside the shell `<main>` (nested landmarks, double padding, own 1280 px max width). | shell, config |
| S4 | Shell frame: sidebar 264/72 px (mockup 248/60), topbar opaque with no blur, 40 px controls, mode toggle and search not matching the mockup, plan card styled differently. | shell |
| S5 | Four undefined or wrong custom properties and hard-coded colours: `var(--r-md)` was used in 4 places but never defined (radius silently dropped), `var(--surf)` in the modules page, `#fff` in the setup checklist, `white` in the cancel button, a hex conic-gradient in the colour picker, a `rgba(0,0,0,.25)` shadow on Home. | config, appointments, home |
| S6 | Dialog sheets: `.sheet*` re-declared in 9 dialogs, each with `min-width: 420px` (overflow on phones), a different footer padding, no top divider on the footer, opaque surface instead of glass-hi. | 9 dialogs |
| S7 | Buttons: 44 px high everywhere (mockup console is 38 px, small 32 px). A secondary button had no glass background, primary had no glow, danger used a hard-coded white label. | all |
| S8 | `mat-form-field` defaulted to the filled look on Login and Setup, outlined elsewhere: the "+91" prefix and the floating label overlapped on Login. Form-field radius, outline colour and height were the Material defaults (4 px radius, 56 px height). | login, setup |
| S9 | The Material toggle group (`.seg` on Queue, Login) showed a tick before the selected label and was 46 px tall; plain `.seg` rules would have broken it. | queue, login |
| S10 | Several pages exceeded the 4 kB component style budget (13 build warnings) because the same SCSS was copied into each component, and Clinic setup pages each pulled in a 4.7 kB `config-common.scss`. | 13 files |
| S11 | `.stack` meant two different things (column of panels on Today, stacked share bar on Revenue). | today, revenue |
| S12 | Inline `style="font-size: ..."` repeated 19 times with 9 different values. | templates |

### 2.2 Per screen

| Screen | Deviations found | Status |
|---|---|---|
| Shell (sidebar, topbar, drawer) | S3, S4; mobile topbar showed the `Ctrl K` hint and wrapped into three rows | Fixed |
| Page header (all pages) | S1: 1.4 / 1.5 / 1.3 rem h1, lead 0.9 rem, max 54 to 70 ch, top-aligned instead of centred | Fixed (shared `app-page-head` + `.pagehead`) |
| Today | S1, S2, S11; alert marker was an 8 px dot (mockup: 4 px severity bar); doctor card not glass-hi | Fixed |
| Patients | S1, S2; KPI tiles vertical and 1.5 rem; filter chips square and white; list rows 12 px; search field 56 px | Fixed |
| Queue | S1, S2, S9; columns 270 px fixed, cards opaque, "Move to With doctor" overflowed its button in 210 px columns | Fixed |
| Appointments | S1, S2; two different `.seg` styles; slot rows opaque; stat tiles 1.5 rem | Fixed |
| Billing counter | S1, S2; `.lines` without the mockup border and glass rows | Fixed |
| Follow-ups | S1; tabs opaque, 44 px tall, selected = tint only | Fixed |
| Revenue | S1, S2, S11; payment-mix bar 12 px (mockup 16 px with gaps); KPI auto-fit tiles | Fixed |
| Clinic setup (all 12 sections) | S3, S5, S10; section nav and header not glass, tip box flat yellow (mockup: sec gradient); switches green (mockup: primary); grid2 1fr 1fr everywhere | Fixed (frame + shared blocks) |
| Staff and leave | S1, S2; own `.seg`, `.sw`, `.field`, `.input`, tabs | Fixed |
| Beds and admissions, bed board | S1, S2; KPI tiles; ward panels | Fixed |
| Dialogs | S6, S7, S8 | Fixed |
| Login | S8, S9; card white instead of glass (mockup `.auth-card glass`) | Fixed |
| Setup wizard | S8; rail and card white instead of glass (mockup `.rail glass`, `.wcard glass`) | Fixed (class swap, not verified with live data) |
| Home (public) | S5 shadow only; uses the mockup landing v4 `.surf` system on purpose | Fixed (1 value) |
| Not allowed, coming soon | double padding from the shell | Fixed |

## 3. What was changed

### Shared building blocks (source of truth)

| File | Contents |
|---|---|
| `frontend/src/styles/_console.scss` (new) | `.glass`, console frame (`.shell main.page`), `.page`, `.pagehead`, `.actions`, `.panel`, `.panel-h`, `.grid2`, `.vstack`, `.stack`, `.kpis`/`.kpi`, `.list`/`.li`/`.main`, `.sev`, `.avatar`/`.av`, `.empty`, `.note`, `.rulerow`, `.notice` variants, `.dot`, `.tabs`, `.seg`, `.chipf`, `.field`, `.input`, `.hint`, `.err-text`, `.rs`, `.sw`, `.stepper`, `.xbtn`, `.iconbtn`, `.meter`, `.example`, dialog sheet (`.sheet-panel`, `.sheet*`, `.members`, `.member`, `.seg-wrap`, `.docbtn`, `.slip`), small type scale (`.t-xs`, `.t-sm`, `.t-md`), responsive rules |
| `frontend/src/styles/_shell.scss` (new) | app shell frame: sidebar, collapsed state, drawer, topbar, scrim, mobile topbar. Global but scoped under `.shell` |
| `frontend/src/styles/_primitives.scss` | pills (added `p-crit`, `p-blue`, `p-done`, `p-counsel`), Material button hierarchy (filled with glow, outlined on glass, text; `danger`, `btn-sm`), 40 px default and 38 px console height with Material's 48 px touch target kept, toggle group without tick, removal of the old dialog block |
| `frontend/src/styles/_tokens.scss` | added `--r-md`, `--crit-ink`, `--field-line`; Material form-field and snack-bar tokens mapped to the Sahaj tokens |
| `frontend/src/app/shared/ui/page-head.component.ts` (new) | `<app-page-head title lead>` with the page actions projected |
| `frontend/src/app/shared/ui/empty-state.component.ts` (new) | `<app-empty>` |
| `frontend/src/app/shared/ui/page-head.component.spec.ts` (new) | 3 unit tests |

Rule now: pages do not redeclare these classes. A page style file holds only what is specific to that screen.

### Pages migrated (duplicates removed, shared blocks used)

Shell, Today, Patients list, Patient record, Queue board, Appointments board, Billing counter, Follow-ups, Revenue, Staff, IPD overview, Bed board, Clinic setup frame, Team, Slots, Workflow, Overview, Modules, Profile, Data, shared stub, Login, Setup wizard (classes), and the dialogs: new token, book appointment, appointment detail, day closing, merge confirm, patient form, admit, admission, bed sheet.

`<app-page-head>` is used on Today, Patients, Queue, Appointments, Billing, Follow-ups, Revenue, Staff and IPD. `<app-empty>` is used where the empty state is a single sentence on those pages.

Template edits were attribute or wrapper changes only: `app-page-head`, `app-empty`, `vstack`, `sev warn`, `grid2 even`, `appearance="outline"`, `surf` to `glass` on Login and Setup, `btn-sm` on the Queue move button, `.cfgpage` instead of a nested `<main>`, 19 inline font sizes turned into `.t-*` classes.

## 4. Results

| Check | Before | After |
|---|---|---|
| Production build, component style budget warnings (4 kB) | 13 | 0 |
| Production build errors | 0 | 0 |
| Initial bundle | 322.61 kB raw / 87.71 kB transfer | 343.3 kB raw / 91.0 kB transfer (global styles moved out of components: `styles.css` 20.3 kB to 41.0 kB) |
| Changed tracked SCSS, characters | 73,033 | 45,295 |
| `ng lint` | pass | pass |
| `ng test` | 88 files, 655 tests | 89 files, 658 tests (3 new) |

Screens were checked with headless Chrome against the production build with the API mocked in the browser, in light and dark at 1440, 1024 and 390 px (Today, Queue, Patients, Follow-ups, Appointments, Billing, Revenue, Clinic setup overview, profile, team, slots, Staff, IPD, Login, Home, New token dialog).

## 5. Decisions that differ from the mockup on purpose

| Item | Mockup | Built | Why |
|---|---|---|---|
| Control height | 31 to 38 px | drawn 32 to 38 px; hit area at least 44 px (Material 48 px touch target, `::after` on tabs, chips, segmented buttons, icon buttons); stacked rows (sidebar items, list rows) stay 44 px | `.claude/rules/angular.md`: touch targets at least 44 px |
| Text field border | `--line` (12 % ink, about 1.2:1 on white) | `--field-line` (80 % of `--ink-3`, about 3.3:1) | WCAG 1.4.11 non-text contrast; the app already used the stronger Material outline |
| Switch on colour | `--pri` | `--pri` (changed from `--good`) | matches mockup; the old pages used green |
| Panel on public pages | mixed `.glass` and `.surf` | login, setup use `.glass`; landing keeps `.surf` | matches the mockup per page |

## 6. Remaining deviations and open items

1. Not verified with live data: Billing counter detail (bill, payment, refund), Revenue with a report, Bed board with wards, Patient record, Staff roster, Setup wizard steps, and the dialogs other than New token. The styles are shared and were reviewed in code, but not seen rendered with data.
2. Mockup 2 px scrollbars (`::-webkit-scrollbar`) are not adopted: they are hard to grab on a desktop. Needs a human decision.
3. Sidebar header: the mockup shows the Sahaj logo, the organisation picker and a collapse button; the built shell shows the clinic card and a collapse button (the organisation picker is a platform-portal feature, not in phase 1).
4. The topbar has "Go to" (Ctrl K) and a user menu that are not in the mockup topbar (the mockup has a role switch for the demo). Left as built; the hint is hidden on small screens.
5. Billing counter still carries about 20 inline layout styles (`style="..."`), Clinic setup slots and workflow a few more. They are layout-only and were left to avoid conflicting edits; they should move to classes in a later pass.
6. The IPD Clinic setup pages (`config/ipd/*`) were being edited by another team during this pass; they use the shared blocks through `config-common.scss` but their own SCSS was not reviewed line by line.
7. `shared/character` and `qr-sample` contain illustration colours (skin tones, QR modules). They are artwork, not UI colours, so they stay outside the token rule.
8. Mockup `.sched` slot grid and `.apws` appointments workspace layout are not reproduced; the built Appointments board keeps its column layout (same data, styled with the shared tokens).
9. `docs/design/tokens.md` does not list `--surface`, `--surface-2`, `--shadow-sm`, `--r-md`, `--crit-ink` or `--field-line`. They exist in the mockup (surface, shadow-sm) or are Angular-side derived tokens. A human may want to add them to `tokens.md`.
10. Other teams' concurrent files were touched only for class names and imports. Where the file was untracked or already modified by someone else I did not run a formatter over it.
