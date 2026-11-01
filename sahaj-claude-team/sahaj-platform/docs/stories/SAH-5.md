# SAH-5 · App shell
Mockup screen: App shell (sidebar, header, Simple/Advanced, light/dark, language)
Module: platform

## 1. Story
As a clinic user, I want a clear sidebar and header that show only what my role allows, so that I can move around quickly on any device.

## Acceptance criteria
1. Given a nurse, when the app opens, then only the nurse's menu items show; typing a hidden page's URL shows "not allowed".
2. Given I switch language to Marathi, then menu and header labels change and the choice is remembered for my user.
3. Given I switch dark mode or Simple/Advanced, then it applies at once and is remembered.
4. Given a screen width of a tablet or phone, then the layout matches the mockup's tablet and phone versions.
5. Given the header, then it shows clinic name, area, date and time in IST (`Sat, 24 Oct 2026 · 11:05 AM`), the search box "Search mobile number", "Go to…" with Ctrl K, language select, user menu with role, light or dark switch, and logout.
6. Given Advanced, then the menu is grouped (Daily work, Patients, Money, Clinic) with collapsible groups; given Simple, then it shows a short flat list with larger buttons.
7. Given the sidebar, then it can collapse to icons, has "Search menu", shows the clinic card, and at the bottom shows the plan card (Free year, days left).
8. Given 1000 px or narrower, then the sidebar becomes a drawer opened from the menu button, and Esc or the backdrop closes it.
9. Given Ctrl K, then the Go to palette opens, shows only pages my role and plan allow, supports arrow keys, Enter and Esc, and finds setup pages.
10. Given my clinic's brand colour, then the shell uses it on every load.
11. Given a page my role cannot open, then the API also refuses it with 403; the menu is not the protection.

## 3. Technical design
- Backend returns menu and permissions in `GET /me`, so the server stays the source of truth. `NavigationPolicy` in `platform` maps role and plan modules to allowed menu keys. Every later API checks role itself.
- Table `platform.user_preference`: `user_id`, `tenant_id`, `language`, `theme`, `menu_mode`, `sidebar_collapsed`. RLS.
- Modules for now: OPD on; IPD and HRA off. Entitlement screens are not in this phase.
- Frontend: `ShellComponent` with router outlet, `roleGuard` with an `/not-allowed` page, signals store seeded from `/me`.
- i18n: `@angular/localize` needs one build per language, which does not fit a live switch. Use a small in-house translation service with JSON files for `en`, `mr`, `hi`. No new library. Shell labels only; each later story adds its own.
- Clock and dates always use `Asia/Kolkata`, whatever the device time zone.
- Search box and Go to: searching patients waits for the patient story. Go to covers pages and setup links only.
- Pages from later stories open a plain placeholder inside the shell.

Proposed role menu (see Open questions):
| Role | Menu keys |
|---|---|
| owner | today, queue, appointments, patients, billing, revenue, followups, reports, config |
| doctor, visiting doctor | today, queue, appointments, patients, followups |
| nurse | today, queue, patients |
| front desk | today, queue, appointments, patients, billing, followups |
| billing | today, billing |
| pharmacist | today |

Simple mode: today, queue, appointments, patients, billing, config, filtered by role.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/me` | any | none | `Me` | 401 |
| PATCH | `/me/preferences` | any | `{language?, theme?, menuMode?, sidebarCollapsed?}` | `Me` | 400, 401 |

All paths are under `/api/v1/platform`.

## 5. Tasks
### Tasks — Backend
- Migration, `/me`, preferences, `NavigationPolicy`.
- Tests: each role's menu; preferences per user and tenant; cross-tenant; plan modules filter.

### Tasks — Frontend
- Shell per mockup `csSideHTML`, `csNavHTML`, header markup, Go to palette `openCmd`, drawer, collapse, modes, theme, language.
- Guards, not-allowed page, placeholder page, translation files for shell labels in English, Marathi and Hindi.
- Unit tests. Playwright smoke at desktop and phone widths, covering a nurse's blocked URL.

## 6. Out of scope, Open questions
Out of scope: page content, patient search, the mockup's demo role switcher (real login replaces it), Sahaj admin shell.
Open questions:
- **Role menus differ.** The mockup's default shows nearly every item to every role and blocks at page level, for example Revenue. Your criterion says only the nurse's items show. Please confirm the table above; it also defines menus for doctor, billing and pharmacist, which the mockup does not list.
- WhatsApp usage meter on the plan card: WhatsApp is not in Phase 1. Recommended: hide it.
- Marathi and Hindi wording comes from the mockup where present; other labels are my translations and need a native review.

## 7. Status log
