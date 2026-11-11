# SAH-37 release audit (DEC-032), Phase 0 and the analysis half of Phase 1: input and validation audit

Story: SAH-37, IPD patient card setup (Clinic setup → Patient card; the Admitted cards that follow it). Mockup screen: screen-map row 29, `cfgIpCard`, `pcBar`, `pcPreview`, `pcCard`.
Author: qa-engineer, 9 Nov 2026. Mode: read and analyse only. No product code, no migration, no test was changed. Nothing was run (no Maven, no Playwright, so no lock was taken).
Read: DEC-032, story and requirement SAH-37, ADR-015, design note (V1 to V14), `docs/api/ipd.yaml` (SAH-37 parts), `docs/database/SAH-37.md`, `docs/test-plans/SAH-37.md`, the mockup `cfgIpCard` / `pcLabel` code, and every file listed in section 1.
How to read the verdict marks: ✔ = enforced and I read the line. ✘ = not enforced. ◐ = partly. n/a = the layer cannot or need not enforce it.

## 0. Result in one view

- Input fields, controls, write-body fields and query parameters audited: **47** rows (section 2: rows 1 to 34 on the setup page and the write calls, rows 35 to 40 on the Admitted page, rows 41 to 47 the read-side promises).
- Validation rules in the matrix: **41** (section 3).
- Gaps: **11**. Sev-1: **0**. Sev-2: **0**. Sev-3: **4** (G-1, G-2, G-4, G-5). Sev-4: **7** (G-3, G-6 to G-11).
- Gaps that need a developer before sign-off: G-1, G-2 (angular-developer), G-5 (db-engineer, then java-developer). G-4 needs a human decision first (a new rule V15), then java-developer and angular-developer. G-3 (angular-developer) is a small fix that I also recommend.
- Mockup conflicts that block a fix: **none that block**. Two proposed fixes (G-1, G-2) add a field-level error line to a screen whose mockup has none; the mockup has the pattern on another screen (`.ferr`, mockup line 1447, used at line 6134). Needs the human's yes before the angular-developer builds it (strict rule 1). Four earlier, already decided differences are listed in section 5 for completeness.
- The server and the database are sound. Every locked-item rule (V1 to V14) is enforced on the server on Save draft and Publish, all errors together, in problem+json with `fieldErrors`, without echoing input. Tenant isolation is by row-level security on both tables and by the clinic taken from the signed-in user. There is **no route that takes an id** (the setup is one document per clinic), so there is no IDOR path; the only ids on the setup page are the preview's patient ids, chosen from the owner's own list and never sent anywhere.
- The weak layer is the screen: it prevents most mistakes by construction (closed choices, disabled controls) but gives no message for the one free-text input (the label) and shows a server 400 only as one banner line.

## 1. Map of the slice

### Angular (`frontend/src/app/features/`)
| Part | Files |
| --- | --- |
| Page, tabs, status bar, versions, leave guard | `config/ipd/ipcard/ipcard.page.ts`, `ipcard.page.html`, `ipcard.page.scss`, `ipcard-leave.guard.ts`, `leave-dialog.component.ts`; route in `config/config.routes.ts:101`; menu entry `config/config-sections.ts:216`; Overview tile `config/overview/overview.logic.ts:115` |
| State and write calls (baseVersion, draftRevision, errors, busy) | `config/ipd/ipcard/card-setup.store.ts` |
| Tabs | `layout-tab.component.{ts,html}` (templates, layout, cards per row, sections), `fields-tab.component.{ts,html}` (field on/off, order, move, label), `actions-tab.component.{ts,html}` (quick actions), `card-preview.component.{ts,html,scss}` (View as, Patient, Desktop/Tablet), `card-editor.helpers.ts` |
| Pure rules (mirror of the server) | `ipd/card/card-setup.logic.ts`, `card-setup.model.ts`, `card-setup.api.ts` (the only HTTP), `card-sample.ts`, `card-view.ts`, `card-actions.component.*`, `card-vitals.component.*` |
| Admitted page | `ipd/admitted/admitted-panel.component.{ts,html}` (search, ward, cards per row, Customize card), `admitted-view.state.ts`, `admitted.model.ts`, `patient-card.component.*`, `ipd/admissions.api.ts:55` (`cardFacts`) |

### Spring (`backend/src/main/java/com/sahaj/`)
| Part | Files |
| --- | --- |
| Controller | `ipd/web/CardSetupController.java` (GET, PUT, DELETE, POST), `ipd/web/AdmissionController.java:74` (`GET /admissions`, `status`, `cardFacts`) |
| Service | `ipd/application/CardSetupService.java` (role, module, body, Read-only, lock, tokens), `CardSetupJson.java` (strict body read), `CardSetups.java`, `CardSetupViews.java`, `CardSetupRepository.java`, `AdmissionService.java:243` and `:518` (`cardFacts`), `IpdText.java` (V12 text rule) |
| Domain (no Spring) | `ipd/domain/card/CardCatalogue.java`, `CardSetupValidator.java` (V1 to V14), `CardSetupRepair.java`, `CardSetupCanonical.java`, `CardSetupDefaults.java`, `CardAccessRule.java`, `TextRules.java`, `VitalMarks.java`, `ResolvedCardSetup.java`, `CardSetup.java` |
| Repository | `ipd/infrastructure/persistence/JdbcCardSetupRepository.java` (every statement has `where tenant_id = :tenantId`, on top of row-level security) |
| Shared | `platform/web/ProblemExceptionHandler.java` (problem+json, `fieldErrors`, scrubbed details), `platform/web/security/RequestBodySizeLimitFilter.java:51` (64 KB cap for signed-in calls, `application.yml:62`) |

### Database
`backend/src/main/resources/db/migration/V2026_12_04_1000__ipd_card_setup.sql`: `ipd.card_setup_version` (lines 12 to 28; append-only), `ipd.card_setup_draft` (31 to 44), row-level security ENABLE and FORCE (48 to 67), grants by column (72 to 79), triggers (83 to 92). Design: `docs/database/SAH-37.md`.

### Existing tests that already guard this slice (not changed here)
`CardSetupIT` (26), `CardSetupStoredIT` (10), `CardFactsIT` (11), `CardSetupPlansIT` (1), `AdmittedCardsIT`; unit tests `CardSetupValidatorTest`, `CardSetupJsonTest`, `CardSetupServiceTest`, `CardSetupRepairTest`; `frontend/e2e/patient-card-setup.spec.ts` (63 per project, all API calls mocked). Plan: `docs/test-plans/SAH-37.md`.

## 2. INPUT FIELD table

Paths: FE = `frontend/src/app/features/`; BE = `backend/src/main/java/com/sahaj/ipd/`. "Screen" = browser, "Server" = Spring, "DB" = PostgreSQL. A file:line after a mark says where the rule is enforced.

### 2.1 Setup page: choices that change the draft (owner only; everyone else sees them disabled)
| # | Field | Type | Req. | Min / max | Allowed values or pattern | Business rule | Screen | Server | DB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Layout (Compact, Standard, Expanded) | closed choice, 3 buttons | yes | 1 of 3 | `compact`, `standard`, `expanded` | rule 5, AC19 | ✔ buttons only: FE `config/ipd/ipcard/layout-tab.component.html:30-45`, `ipd/card/card-setup.logic.ts:151` | ✔ V1 BE `domain/card/CardSetupValidator.java:48-50` | ✘ (accepted, ADR-015 D1: only "object, 16 KB") |
| 2 | Cards per row | closed choice, 3 buttons | yes | 2, 3, 4 | integer 2, 3, 4 | rule 5, AC20 | ✔ `layout-tab.component.html:57-67`, `card-setup.logic.ts:155` | ✔ V2 `CardSetupValidator.java:51-53`; a string or a float is refused by the strict reader `application/CardSetupJson.java:40-54` | ✘ (accepted) |
| 3 | Template (Small clinic, Nursing home, Hospital) | closed choice, 3 buttons, browser-side action | no | 1 of 3 | keys from the server's `templates` | rule 6, AC18 | ✔ `layout-tab.component.html:11-21`, `card-setup.store.ts:338-345`, `card-setup.logic.ts:163` (keeps cards per row) | ✔ the result is validated as a normal setup on Save and Publish | ✘ |
| 4 | Section on/off (Admission and bed, Care team, Billing) | switch ×3 | yes | boolean | true or false | rule 7, AC23 | ✔ `layout-tab.component.html:119-134`, `card-setup.logic.ts:173-199` (refuses a section that holds a safety field, toast) | ✔ V5, V9 `CardSetupValidator.java:87-91`, `:144-147` | ✘ |
| 5 | Section on/off for Card header, Workflow status, Vitals, Clinical | no control, pill "Always on" or "Safety · always on" | yes | always true | true | rule 9, AC22 | ✔ no switch `layout-tab.component.html:82-92`, `:119-122` | ✔ V4, V5 `CardSetupValidator.java:81-91` | ✘ |
| 6 | Section order (7 sections) | drag, Move up, Move down | yes | 7 sections, header first | permutation of the 7 keys, `header` at index 0 | rule 7, AC24 | ✔ `layout-tab.component.html:105-156`, `card-setup.logic.ts:201-226` (header cannot move; `Math.max(1, …)`) | ✔ V3, V4 `CardSetupValidator.java:57-93` | ✘ |
| 7 | Field on/off (20 switchable fields) | switch ×20 | yes | boolean | true or false | rule 8, AC27 | ✔ `fields-tab.component.html:75-85`, `card-setup.logic.ts:228-248` | ✔ V8 `CardSetupValidator.java:141-143` | ✘ |
| 8 | Field on/off for Patient name, UHID, Alerts, Early warning, Allergies | no control, pill "Required" or "Safety · always on" | yes | always true | true | rule 9, AC29 | ✔ `fields-tab.component.html:68-74`, toast `card-setup.logic.ts:234-239` | ✔ V7, V8 `CardSetupValidator.java:132-143` | ✘ |
| 9 | Field order and section (move between sections) | drag, Move up, Move down | yes | 25 fields, each once | field key in an allowed section; name first in header | rule 8, AC30 | ✔ `fields-tab.component.html:86-109`, `fields-tab.component.ts:83-151`, `card-setup.logic.ts:279-340` | ✔ V6, V7, V9 `CardSetupValidator.java:100-150` | ✘ |
| 10 | Field label (20 editable boxes) | text input | no (empty = standard name) | 0 to 40 characters | plain text; no control, invisible or direction character; trimmed; NFC | rule 8, AC31 | ◐ `fields-tab.component.html:53-65`: `maxlength="40"` (UTF-16 units, FE `fields-tab.component.html:57`), cut again at `card-setup.logic.ts:268`, trimmed on change `fields-tab.component.ts:93-98` and on send `card-setup.logic.ts:38-46`; **no control/bidi check, no message, no counter** (G-2, G-9) | ✔ V11, V12, V14 `CardSetupValidator.java:153-176` (code points, NFC, strip) | ✘ (cannot; 16 KB cap only) |
| 11 | Label of a safety field or Patient name | text input, disabled, shows the standard name | n/a | fixed | equals the standard name or empty | rule 9, AC32 | ✔ `fields-tab.component.html:62`, `card-setup.logic.ts:251-253` | ✔ V7, V10 `CardSetupValidator.java:170-175` | ✘ |
| 12 | Label equal to another field's name | (no control) | n/a | n/a | none | not in the story | ✘ | ✘ | ✘ (G-4) |
| 13 | Quick action on/off (5 actions) | switch ×5 | yes | boolean | true or false | rule 10, AC37 | ✔ `actions-tab.component.html:39-48`, `card-setup.store.ts:421-426` | ✔ V13 (key rules only; any boolean) `CardSetupValidator.java:179-197` | ✘ |
| 14 | Quick action order | drag, Move up, Move down | yes | 5 actions, each once | permutation of the 5 keys | rule 10, AC41 | ✔ `actions-tab.component.html:49-70`, `card-setup.store.ts:428-446` | ✔ V13 | ✘ |
| 15 | "Who can use it" line | read-only text | n/a | n/a | from the server's catalogue | rule 10, AC38 | ✔ not editable | ✔ one copy: `domain/card/CardAccessRule.java` | n/a |

### 2.2 Setup page: commands, preview, navigation
| # | Field | Type | Req. | Min / max | Allowed values | Business rule | Screen | Server | DB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 16 | Save draft | button | n/a | n/a | enabled when there are unsaved edits | AC3, AC4, AC13 | ✔ `config/ipd/ipcard/ipcard.page.html:59-68`, `card-setup.store.ts:474-491` (busy lock, `unsaved()` guard) | ✔ `application/CardSetupService.java:123-158` | ✔ PK `tenant_id` |
| 17 | Discard draft | button | n/a | n/a | shown when a draft exists or there are unsaved edits | AC5 | ✔ `ipcard.page.html:49-58`, `card-setup.store.ts:494-521` | ✔ `CardSetupService.java:164-182` | n/a |
| 18 | Reset to default | button | n/a | n/a | disabled when the draft equals the standard | AC6 | ✔ `ipcard.page.html:39-48`, `card-setup.store.ts:453-460` | n/a (editor action; the result is validated on Save) | n/a |
| 19 | Publish | button | n/a | n/a | enabled only when the draft differs from live | AC7, AC8 | ✔ `ipcard.page.html:69-78`, `card-setup.store.ts:523-541` | ✔ `CardSetupService.java:189-217`; equal to live: 409 `card-setup-unchanged` | ✔ unique `(tenant_id, version)` |
| 20 | Tab (Layout and sections, Fields, Quick actions) | tab list | n/a | 1 of 3 | closed | AC17 | ✔ `ipcard.page.ts:88-114` (arrow keys, Home, End) | n/a | n/a |
| 21 | View as (preview) | select | yes | 1 of 4 | `catalogue.viewAsRoles` (OWNER, DOCTOR, NURSE, FRONT_DESK) | AC45 | ✔ options from the server catalogue `card-preview.component.html:25-29`; the value is cast without a check `card-preview.component.ts:39` (benign: closed list, no server call) | n/a (the preview never calls the server as another role, ADR-015 D7) | n/a |
| 22 | Patient (preview) | select | yes | 1 of N | ids from the owner's own `GET /admissions?cardFacts=all` or the built-in example | AC45, AC50 | ✔ `card-preview.component.html:33-39`, `card-setup.store.ts:194-201` | n/a (id never sent) | n/a |
| 23 | Desktop / Tablet | toggle, 2 buttons | yes | 1 of 2 | `desktop`, `tablet` | AC44, AC49 | ✔ `card-preview.component.html:8-19` | n/a | n/a |
| 24 | Leave with unsaved edits | route guard and `beforeunload` | n/a | n/a | n/a | AC4 | ✔ `ipcard-leave.guard.ts:13`, `ipcard.page.ts:121-140` | n/a | n/a |

### 2.3 Write bodies and query parameters (what the browser sends)
| # | Field | Type | Req. | Min / max | Allowed values or pattern | Business rule | Screen | Server | DB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 | `baseVersion` (PUT, POST) | integer | yes | ≥ 1 | live version the editor loaded | AC12 | ✔ taken from the last server answer `card-setup.store.ts:464-470` | ✔ missing or < 1: 400 `CardSetupService.java:241-243`; not the live version: 409 `:269-271` | ✔ `ck_card_setup_draft_base_version` (≥ 1, migration line 37) |
| 26 | `draftRevision` (PUT, POST) | integer 64-bit or null | yes (may be null) | ≥ 1 | opaque token, about 1.79e15, below 2^53 | AC12 | ✔ sent back unchanged `card-setup.store.ts:468` | ✔ < 1: 400 `CardSetupService.java:244-246`; mismatch: 409 `:272-278` | ✔ `ck_card_setup_draft_revision` (≥ 1, line 41) |
| 27 | `setup` (PUT, POST) | object | yes | exactly 4 members; body ≤ 64 KB | `layout`, `cardsPerRow`, `sections[7]`, `actions[5]`; no other property | rule 16 | ✔ typed model `ipd/card/card-setup.model.ts`; `canonicalSetup` `card-setup.logic.ts:38` | ✔ strict reader (unknown, missing, wrongly typed property: 400 with path) `CardSetupJson.java:78-93`; body cap `RequestBodySizeLimitFilter.java:51` (413) | ✔ `ck_card_setup_*_setup`: JSON object and ≤ 16 KB (lines 19, 35) |
| 28 | `setup.sections[i].key / on / fields[j].key / on / label` | string, boolean, string | yes | 7 sections, 25 fields | see rows 4 to 12 | rules 7 to 9 | ✔ (typed) | ✔ V3 to V12, V14 | ✘ |
| 29 | `setup.actions[i].key / on` | string, boolean | yes | 5 actions | see rows 13, 14 | rule 10 | ✔ (typed) | ✔ V13 | ✘ |
| 30 | `draftRevision` (DELETE query) | integer 64-bit | yes | ≥ 1 | opaque token | AC5 | ✔ `ipd/card/card-setup.api.ts:30-34` sends the stored revision | ◐ missing or not a number: Spring binding gives a generic 400 (no `fieldErrors`) `web/CardSetupController.java:49`; < 1: 400 with field `CardSetupService.java:169-171`; mismatch: 409 `:176-178` | n/a |
| 31 | `cardFacts` (query of `GET /admissions`) | string | no | `setup` or `all` | exactly `setup`, `all`, or absent | ADR-015 D6 | ✔ only the setup page sends `all`, only for the owner `ipd/admissions.api.ts:55-61`, `card-setup.store.ts:245-247` | ✔ other value: 400 `application/AdmissionService.java:522-524`; `all` for a non-owner: 403 `:525-527` | n/a |
| 32 | `status` (query of `GET /admissions`) | string | no | `ADMITTED` | `ADMITTED` | SAH-36 | ✔ fixed | ✔ other value: 400 `AdmissionService.java:248-250` | n/a |
| 33 | Clinic and user | from the token | n/a | n/a | never from the body or the URL | CLAUDE.md rule | n/a | ✔ `web/CardSetupController.java:36,43,50,57` (`ClinicUser.current()`) | ✔ `tenant_id` default `platform.current_tenant()`, never in a grant (migration lines 14, 36, 76-78) |
| 34 | Role of the caller | from the token and re-read from the membership | n/a | n/a | OWNER for writes and the draft; 4 roles to read | rule 3 | ✔ controls disabled or absent | ✔ `@PreAuthorize` `CardSetupController.java:34,41,48,55`, `SafetySupport.membershipRole` `CardSetupService.java:110` and `OwnerGuard` `:237` | n/a |

### 2.4 Admitted page (where the published card is used)
| # | Field | Type | Req. | Min / max | Allowed values or pattern | Business rule | Screen | Server | DB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 35 | Search | search box | no | 0 to 100 characters | any text; matched on name, UHID, bed, IP number, case-insensitive, edge spaces ignored | SAH-36 AC7 | ◐ `ipd/admitted/admitted-panel.component.html:23-33` (`maxlength` 100, `admitted.model.ts:25`), filter `admitted.model.ts:88-103`; no Unicode normalisation (G-10) | n/a (on the device by design, ADR-013 D8) | n/a |
| 36 | Ward filter | select | no | 1 of N + "All wards" | ward ids from the answer | SAH-36 | ✔ `admitted-panel.component.html:36-47`; unknown ward falls back to All wards `admitted-panel.component.ts:114-116` | n/a (server never receives it) | n/a |
| 37 | Cards per row (the person's choice) | closed choice, 3 buttons | no | 2, 3, 4 | 2, 3, 4 | SAH-36 AC1 | ✔ `admitted-panel.component.html:49-66`, `admitted-view.state.ts:30-37`; the published value other than 2, 3, 4 falls back to 3 `admitted-panel.component.ts:121-124` | ✔ the published value is validated at save (row 2) | ✘ |
| 38 | View (Cards / Table) | toggle | no | 1 of 2 | closed | SAH-36 | ✔ | n/a | n/a |
| 39 | Show 24 more / Show all | buttons | no | n/a | n/a | SAH-36 AC8 | ✔ `admitted-view.state.ts:50-57` | n/a | n/a |
| 40 | Customize card | link, owner only | no | n/a | present only for OWNER | AC62 | ✔ `admitted-panel.component.html:88-92` (not disabled, absent) | n/a (the setup page and the API refuse everyone else) | n/a |

### 2.5 Read answer fields that carry a validation promise
| # | Field | Type | Rule | Where |
| --- | --- | --- | --- | --- |
| 41 | `draft`, `versions` | absent for non-owners | AC10 | ✔ server `CardSetupService.java:337-339` (null is not written), tested in raw JSON by `CardSetupIT.theDraftAndTheVersionsAreAbsentFromTheRawJson…` |
| 42 | `cardSetup` in the list answer | repaired live setup, only items that are on | AC54, AC57, AC61 | ✔ server `AdmissionService.java:254`, `domain/card/CardSetupRepair.java`; screen fallback to the standard card `admitted-panel.component.ts:96-98` |
| 43 | Catalogue limits (`labelMaxLength` 40, `viewAsRoles`, groups, actions) | server constants | one copy of the rule | ✔ `domain/card/CardCatalogue.java:20,155`; screen reads them `fields-tab.component.ts:77` |
| 44 | Published `cardsPerRow`, `layout` | validated at save, repaired at read | AC54 | ✔ `CardSetupRepair.java:31-34` |
| 45 | New card facts (`diagnosis`, `latestVitals`, `mobile`) | present only when the role may see them and the published setup switches them on | ADR-015 D6 | ✔ server (`CardFactsIT`), screen draws `card.visibleGroups` |
| 46 | Audit lines (draft saved, published) | parts and version numbers only | AC16 | ✔ `CardSetupService.java:150-156`, `:208-215` |
| 47 | Error body | problem+json, `fieldErrors`, fixed texts | project rule | ✔ `platform/web/ProblemExceptionHandler.java:224-233`, `:136-147`, `:382-391` |

Count: 47 rows. Rows 1 to 24 are what a person operates on the setup page (row 15 is read-only text, row 12 is the missing control that G-4 is about), rows 25 to 34 are what the browser sends, rows 35 to 40 are the Admitted page, rows 41 to 47 are read-side promises. In the handoff the number of fields is **47**.

## 3. Validation matrix (field × rule × layer)

Screen = browser, Server = Spring, DB = PostgreSQL. Evidence for the server column is in section 2 and in the existing tests named in section 1.

| # | Field | Rule | Screen | Server | DB | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| M-1 | layout | one of compact, standard, expanded | ✔ | ✔ V1 | ✘ | accepted design (Java owns content) |
| M-2 | cardsPerRow | 2, 3 or 4, integer | ✔ | ✔ V2 (strict type) | ✘ | accepted |
| M-3 | sections | 7 known keys, each once, none missing | ✔ (fixed list) | ✔ V3 | ✘ | accepted |
| M-4 | Card header | first and on | ✔ (no move, no switch) | ✔ V4 | ✘ | accepted |
| M-5 | Workflow status, Vitals, Clinical | on | ✔ (pill) | ✔ V5 | ✘ | accepted |
| M-6 | fields | 25 known keys, each once, none missing | ✔ | ✔ V6 | ✘ | accepted |
| M-7 | Patient name | in header, first, on, label empty | ✔ | ✔ V7 | ✘ | accepted |
| M-8 | UHID, Alerts, Early warning, Allergies | on | ✔ (pill, toast on a call) | ✔ V8 | ✘ | accepted |
| M-9 | safety field | not in a switched-off section | ✔ (toast) | ✔ V9 | ✘ | accepted |
| M-10 | safety label | empty or the standard name | ✔ (box disabled) | ✔ V10 | ✘ | accepted |
| M-11 | label length | at most 40 code points after trim and NFC | ◐ `maxlength` counts UTF-16 units; silent stop; no counter | ✔ V11 | ✘ | G-9 (Sev-4) |
| M-12 | label characters | no control, invisible or direction character (joiner rule) | ✘ | ✔ V12 | ✘ (cannot) | **G-2 (Sev-3)** |
| M-13 | raw label length | at most 400 UTF-16 units before normalisation | n/a (40 wins) | ✔ V14 | ◐ (16 KB cap) | ok |
| M-14 | label spaces | trimmed (also no-break and ideographic spaces) | ✔ (JS trim, on change and on send) | ✔ canonical form | n/a | ok |
| M-15 | label equals the standard name | stored as empty | ✘ (pill and Publish treat it as a change) | ✔ canonical | n/a | G-3 (Sev-4) |
| M-16 | label uniqueness or impersonation | none today | ✘ | ✘ | ✘ | **G-4 (Sev-3, human decides)** |
| M-17 | actions | 5 known keys, each once, none missing | ✔ | ✔ V13 | ✘ | accepted |
| M-18 | body shape | exact properties and JSON types; no extra property | n/a (typed) | ✔ strict reader | n/a | ok |
| M-19 | baseVersion | integer ≥ 1 and equal to the live version | ✔ (from the answer) | ✔ 400 and 409 | ✔ check ≥ 1 | ok |
| M-20 | draftRevision (body) | null or ≥ 1 and equal to the stored draft's | ✔ (opaque) | ✔ 400 and 409 | ✔ check ≥ 1 | ok |
| M-21 | draftRevision (DELETE query) | present, integer, ≥ 1, equal | ✔ | ◐ generic 400 without `fieldErrors` when missing or not a number | n/a | G-8 (Sev-4, accepted: the contract says `BadRequest`) |
| M-22 | setup size | JSON object, small | n/a | ✔ 64 KB body cap, 413 | ✔ object and ≤ 16 KB | ok |
| M-23 | version number | next number, unique, ≥ 2 | n/a | ✔ lock, then 409 | ✔ unique key and check | ok |
| M-24 | clinic | from the signed-in user only | n/a | ✔ | ✔ default plus row-level security | ok |
| M-25 | who may write | OWNER (token and membership) | ✔ disabled, not shown | ✔ 403 | n/a | ok |
| M-26 | IPD Off / Read-only | 403 `module-off`; 409 `ipd-readonly` after body check | ✔ | ✔ | n/a | ok |
| M-27 | draft and versions hidden from non-owners | absent from the JSON | ✔ | ✔ | n/a | ok |
| M-28 | double submit | one version, one draft write | ✔ busy lock, buttons disabled, method guard | ✔ lock, second request 409 | ✔ unique key | ok |
| M-29 | cardFacts | `setup`, `all`; `all` only for OWNER | ✔ | ✔ 400 / 403 | n/a | ok |
| M-30 | status | `ADMITTED` | ✔ | ✔ 400 | n/a | ok |
| M-31 | View as | one of the catalogue's four roles | ✔ select from the server list | n/a | n/a | ok |
| M-32 | Patient (preview) | an id from the owner's own list, or the example | ✔ | n/a | n/a | ok |
| M-33 | Admitted search | at most 100 characters, trimmed, case-insensitive | ◐ no Unicode normalisation | n/a | n/a | G-10 (Sev-4) |
| M-34 | Admitted ward / cards per row | closed lists; unknown ward falls back | ✔ | n/a | n/a | ok |
| M-35 | unsaved edits | warn before leaving | ✔ router guard and `beforeunload` | n/a | n/a | ok |
| M-36 | error display, 400 | every message next to its field, also after submit | ✘ one banner line (first message) | ✔ all together, with paths | n/a | **G-1 (Sev-3)** |
| M-37 | error display, 409 and 403 and network | text, Reload, edits kept; 403 clears the page | ✔ | ✔ | n/a | ok |
| M-38 | error body | problem+json with `fieldErrors`, no stack, no SQL, no echo of input | n/a | ✔ | n/a | ok |
| M-39 | many errors | list stays small | n/a | ◐ not capped, bounded by the 64 KB body | n/a | G-11 (Sev-4) |
| M-40 | tenant isolation and by-id access | other clinic invisible; no by-id route | n/a | ✔ every statement filters `tenant_id`; `CardSetupIT.anotherClinics…` | ✔ FORCE row-level security, column grants | ok |
| M-41 | jsonb carries a `schema_version` (project rule, `.claude/rules/postgresql.md`) | present | n/a | ✘ | ✘ | **G-5 (Sev-3)** |

Totals: 41 rules; ✘ in at least one layer where a layer should enforce it: M-12, M-15, M-16, M-36, M-41; partial: M-11, M-21, M-33, M-39. Everything else is enforced on the server, and the database enforces tenant isolation, the sizes, the tokens and the unique version.

## 4. Checks asked for in the audit

### 4.1 Screen messages, trimming, double submit
| Check | Result | Evidence |
| --- | --- | --- |
| Messages are specific and human-readable | ✔ for refused edits (toasts such as "Allergies is a safety field and cannot be hidden.", "This section holds a safety field (Allergies). Move it out first."), for conflict, read-only and load errors | `ipd/card/card-setup.logic.ts:183-239`, `config/ipd/ipcard/card-setup.store.ts:56-62` |
| Shown on touch or blur, and on submit | ✘ for the label (the only free text): nothing on blur, one banner line on submit (G-1, G-2). The choices are closed, so they need no message | `fields-tab.component.html:53-65` |
| Double submit prevented | ✔ `busy` set before the call, buttons disabled while busy, method guard; the server makes one version for a double tap | `card-setup.store.ts:134`, `:475-478`, `:524-527`; `CardSetupService.java:269-271`; `CardSetupIT.twoPublishesAtTheSameMoment…` |
| Trimming | ✔ on change (box rewritten), on send, and again on the server (NFC, strip of spaces and no-break spaces) | `fields-tab.component.ts:93-98`, `card-setup.logic.ts:38-46`, `CardSetupValidator.java:160-165` |
| Server 400 shown field by field | ✘ first message only, in a banner; `fieldErrors` ignored | `card-setup.store.ts:567-570`, `config/config-format.ts:119-122`, `ipcard.page.html:90-100` |
| Server 409 shown | ✔ message from the server with a Reload button; the owner's edits stay until Reload | `card-setup.store.ts:551-566`, `ipcard.page.html:94-98` |
| Failed or offline save | ✔ fixed text, edits kept, nothing claimed saved | `card-setup.store.ts:572`, AC13 e2e |
| Toast only? | ✔ errors use a persistent `role="alert"` banner, not a toast; toasts are for information and refused edits | `ipcard.page.html:90-92` |

### 4.2 Server
| Check | Result | Evidence |
| --- | --- | --- |
| Bean Validation or equivalent | ✔ a pure validator V1 to V14, all errors together, ordered by rule, with JSON paths; strict body reader (unknown, missing, wrongly typed property is 400) | `CardSetupValidator.java:36-45`, `CardSetupJson.java:40-93` |
| One error body (problem+json with `fieldErrors`) | ✔ `InvalidFieldsException` and `InvalidInputException` give type `validation` with `fieldErrors`; the first message is `detail` | `ProblemExceptionHandler.java:215-233` |
| No stack or SQL leak, no echo of input | ✔ fixed texts; Spring's own details are replaced; unexpected errors give a generic 500 with the database message trimmed in the log only | `ProblemExceptionHandler.java:136-147`, `:382-391` |
| Tenant isolation on every query | ✔ every statement has `where tenant_id = :tenantId`; row-level security underneath | `JdbcCardSetupRepository.java:22-57` |
| IDOR (another clinic's id gives 404/403) | n/a: no route takes an id. Singletons per clinic. The cross-clinic case is tested: another clinic's setup, draft and versions are invisible and cannot be changed | `CardSetupIT.anotherClinicsSetupDraftAndVersionsAreInvisibleAndCannotBeChanged` |
| Role | ✔ `@PreAuthorize` plus the membership re-read, in both directions (a demoted owner gets 403) | `CardSetupController.java`, `CardSetupService.java:107-112`, `:236-237` |
| Check order | ✔ role 403, module 403, body 400, Read-only 409, lock, tokens 409 | `CardSetupService.java:235-260` |
| Request size | ✔ 64 KB cap for signed-in calls | `RequestBodySizeLimitFilter.java:51` |
| Logs and audit | ✔ no label, no setup body; `toString` of the setup records prints keys only; audit holds version numbers and part names | `domain/card/CardSetup.java:20-43`, `CardSetupService.java:150-156`, `:208-215` |

### 4.3 Database
| Check | Result | Evidence |
| --- | --- | --- |
| Constraints | ✔ `ck_card_setup_version_version` ≥ 2, `…_setup` object ≤ 16 KB, `…_changed_parts` ⊆ four names, draft `base_version` ≥ 1, `revision` ≥ 1, unique `(tenant_id, version)`, primary key `tenant_id` for the draft, foreign key to `platform.tenant` | migration lines 16-28, 34-44 |
| Row-level security | ✔ ENABLE and FORCE; policies `to sahaj_app` on `tenant_id = platform.current_tenant()`; no update or delete policy on versions | lines 48-67 |
| Append-only versions, no truncate | ✔ column grants, triggers (42501) | lines 72-92 |
| Content shape of the document | ✘ by design (G-6) | line 19 |
| `schema_version` for the jsonb | ✘ (G-5) | `.claude/rules/postgresql.md` |
| Audit columns | ◐ `published_at/by`, `saved_at/by`; no `created_*`, `updated_*`; `revision` plays the version role (G-7) | lines 23-24, 42-43 |

## 5. Mockup and acceptance-criteria conflicts

None blocks a fix. Listed so that nothing is guessed:

1. **Fixes that add something the mockup does not show (needs the human's yes before the angular-developer builds it).**
   - G-1 and G-2 put a one-line error under the label box. The Patient card section of the mockup has no error state (the label input is at `sahaj-mockup.html:7612`, the `input` handler at `:7672`). The mockup has the pattern elsewhere: class `ferr` (line 1447), `bad` on the input, removed on the next keystroke (line 6350). Proposed: reuse exactly that.
   - G-4 adds a rule (no label that duplicates another field's name) that neither the mockup (it allows any label) nor AC31 contains.
2. **Differences between the mockup and the approved stories that already exist (not new; recorded in `docs/decisions/DEC-013-ui-deviations-for-sign-off.md`, DEC-027, DEC-028).** Safety labels are fixed (mockup lets the owner rename them); the label box stops at 40 characters (mockup has no limit); no "Workflow stages" tab, no stage field, no Assigned nurse, no Case paper, no Last nursing note; "Reset" toast without "roles and access"; no "Change who can use each action" button; Mobile shown only to owner and front desk (mockup also to doctor and nurse); non-owners never see the draft (the mockup shows the shared draft to every role).
3. **One inconsistency inside the approved documents.** AC (notes for design, story 1) says draft equality "is decided by the server on a canonical form, not by comparing text in the browser". The design note section 5 and the store let the browser compute the pill and the Publish button on unsaved edits ("the server's values win after each answer"). Result: G-3. The fix keeps the design note (browser mirror) and makes the mirror canonical.

## 6. Gap list with proposed fixes and owners

Severity: Sev-1 data loss, safety or leak; Sev-2 wrong behaviour on a main path; Sev-3 a rule or message missing on a reachable path, with a workaround; Sev-4 cosmetic, accepted design or rare edge. Class: DEFECT = should be fixed in this audit; DECISION = a human decides first; ACCEPTED = design, kept.

| ID | Sev | Class | Gap | Evidence | Proposed fix | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| G-1 | 3 | DEFECT | A server 400 is not shown field by field. The page shows only the first message in one banner; `fieldErrors` (with the path of the label, for example `setup.sections[0].fields[3].label`) is thrown away. Reachable: a label with a pasted direction mark or a zero-width character; the owner reads "Label cannot contain control or text-direction characters" but not which of the 20 boxes it is. | `card-setup.store.ts:567-570`, `config/config-format.ts:119-122`, `ipcard.page.html:90-100`, `fields-tab.component.html:53-65` | Keep `error.fieldErrors` in the store; map a `…fields[j].label` path to the field key through the setup that was sent; show the message under that box (`.ferr`), set `aria-invalid="true"` and `aria-describedby`, clear it on the next keystroke; move focus to the first box in error on submit; list the other messages (more than one) in the banner instead of only the first. Keep the banner for 409 and unknown paths. Needs the human's yes (section 5, point 1). | angular-developer |
| G-2 | 3 | DEFECT | No check in the browser of the V12 rule (control, invisible, direction characters, wrong joiner) or of the 40-code-point count; the owner learns only at Save or Publish, and only through G-1. The messages asked for "on touch or blur and on submit" do not exist for the one free-text field. | `fields-tab.component.html:53-65`, `card-setup.logic.ts:255-272` (only `slice`) | Add `labelProblem(label)` to `card-setup.logic.ts`, a mirror of `IpdText.hasControlOrBidi` for the main categories (Cc, Cf, Cn, Co, Cs, Zl, Zp, the blank-looking letters U+115F, U+1160, U+3164, U+FFA0, U+2800, U+180E, U+034F, U+FE00 to U+FE0F, and the joiner-after-virama rule) and for code-point length. Show the message on blur and on Save; keep Save and Publish disabled while a label is in error. Unit tests use the same vectors as `IpdTextTest`. The server stays the protection. | angular-developer |
| G-3 | 4 | DEFECT (small) | The browser treats a label equal to the standard name as a change; the server stores it as empty. Typing "Bed" in the Bed box makes the pill "Draft · 1 change not published" and enables Publish; Publish then answers 409 `card-setup-unchanged` ("There are no changes to publish.") and Save draft toasts "Draft saved." while the server removed the draft and the box returns to empty. | `card-setup.logic.ts:38-50`, `CardSetupCanonical.java:52-53` | `canonicalSetup(setup, catalogue)` maps a label equal to the standard name (after trim) to "" before comparing and sending; pass the catalogue from the store. | angular-developer |
| G-4 | 3 | DECISION | A label can duplicate or impersonate another field. V10 stops the safety field's own label from changing, but nothing stops "Allergies" as the label of Diagnosis, or "UHID" as the label of IP number. On a ward screen that is a misreading risk (two "Allergies" headings, one of them not the allergy). Not an AC; the mockup allows it. | `CardSetupValidator.java:153-176` | New rule V15 (human to approve the rule and the wording): after trim, NFC and case folding, a label may not equal the standard name or the label of any other field. Message proposal: "This label is already used by another field." Server (validator, unit and integration tests), screen mirror (G-2 helper), qa test. Stored drafts and versions that already break the rule are not repaired (no data is dropped); they stay readable. | java-developer, then angular-developer; BA/human to approve |
| G-5 | 3 | DEFECT (project rule) | Both tables hold a `jsonb` document with no `schema_version`. `.claude/rules/postgresql.md`: "jsonb only for configurable forms and card layouts, with a schema_version". Repair-on-read tolerates old shapes today, but a future shape change could not tell old from new. | migration lines 18, 34 | New forward-only migration (never edit the applied one): add `schema_version smallint not null default 1 constraint ck_card_setup_*_schema_version check (schema_version >= 1)` to `ipd.card_setup_version` and `ipd.card_setup_draft`; grant `insert (schema_version)` and, for the draft, `update (schema_version)` to `sahaj_app`. The default of 1 means no Java change is needed now (existing inserts keep working); java-developer reads it in `CardSetupRepair` when a version 2 exists. Update `docs/database/SAH-37.md`. | db-engineer (java-developer later) |
| G-6 | 4 | ACCEPTED | The database checks only "object, at most 16 KB", not the content (layout, keys). | migration line 19; ADR-015 D1, D3 | None. The Java validator owns the content and a bad stored row is repaired on read. Optional: check that `setup ? 'layout'` and `setup ? 'sections'`. | (none) |
| G-7 | 4 | ACCEPTED | Audit columns are partial: `published_at/by`, `saved_at/by`; no `created_*`, `updated_*`, `version`. The version table is append-only and the draft's `revision` is its version. | migration lines 23-24, 42-43 | None. | (none) |
| G-8 | 4 | ACCEPTED | `DELETE /card-setup/draft` with a missing or non-numeric `draftRevision` returns a generic 400 (no `fieldErrors`) from Spring's binding; the check `draftRevision == null` in the service is dead code (the controller parameter is a required `Long`). The screen never sends it. | `CardSetupController.java:49`, `CardSetupService.java:169-171` | Optional: remove the dead `== null` test. The contract names `BadRequest` for this response, so the behaviour conforms. | java-developer (optional) |
| G-9 | 4 | ACCEPTED (optional polish) | The label box stops typing or truncates a paste at 40 UTF-16 units with no message and no counter; a 40-emoji label (40 code points, 80 units) is refused by the browser though the server allows it. Stricter than the server, never looser. | `fields-tab.component.html:57`, `card-setup.logic.ts:268` | Optional with G-1: a small "12 / 40" under the box and the message "Keep the label to 40 characters." when a paste was cut. Counting code points needs `maxlength` replaced by script. | angular-developer (optional) |
| G-10 | 4 | ACCEPTED | The Admitted search compares lower-cased text without Unicode normalisation: a name typed or pasted in decomposed form (for example "e" plus a combining accent) does not match the same name stored composed. | `admitted.model.ts:93-101` | Normalise query and values with NFC (`String.prototype.normalize('NFC')`) before comparing. Not a SAH-37 field (SAH-36 behaviour); deferred. | angular-developer (later) |
| G-11 | 4 | ACCEPTED | Save draft and Publish return every validation error; the list is not capped. It is bounded by the 64 KB body (about 1,800 errors for a body of unknown sections) and only the owner can send it. | `CardSetupValidator.java:36-45`, `RequestBodySizeLimitFilter.java:51` | Optional: stop at 50 errors. | java-developer (optional) |

Real defects needing a developer: **G-1, G-2** (angular-developer), **G-3** (angular-developer, small), **G-5** (db-engineer). **G-4** needs a human decision, then java-developer and angular-developer. Sev-1: 0. Sev-2: 0. Sev-3: 4 (G-1, G-2, G-4, G-5). Sev-4: 7 (G-3, G-6 to G-11).

Suggested order for Phase 1: G-3 and G-2 (one helper in `card-setup.logic.ts`, one change to the store), G-1 (store plus the fields tab), G-5 (migration), G-4 after the human answers. Tests to add in Phase 3 and 4 are listed in section 8.

## 7. Deferred or out of scope (with reproduction)

There is **no Sev-1 or Sev-2 item**, so nothing forbidden is deferred. These stay open on purpose.

| ID | Sev | Why it is deferred | Reproduction |
| --- | --- | --- | --- |
| G-6 | 4 | Accepted design (ADR-015 D1, D3). | As `sahaj_migrator`, `insert into ipd.card_setup_version (tenant_id, version, setup, is_standard, changed_parts, published_by) values (…, 2, '{"x":1}', false, '{}', …)` succeeds (the database accepts it); `GET /api/v1/ipd/card-setup` for that clinic then answers a repaired setup instead of failing (the repair rules are tested by `CardSetupStoredIT`). |
| G-7 | 4 | Accepted design. | `\d ipd.card_setup_draft` shows `saved_at`, `saved_by` and no `created_*`, `updated_*`. |
| G-8 | 4 | Contract-conforming; screen never sends it. | As the owner, `DELETE /api/v1/ipd/card-setup/draft` without a query, or `?draftRevision=abc`: 400, body has `type` and `title` but no `fieldErrors`. |
| G-9 | 4 | Optional polish; AC31 says "the box stops at 40". | Fields tab, paste 45 letters into "Label for Doctor": 40 remain, no message. Paste 25 emoji: about 20 remain. |
| G-10 | 4 | SAH-36 search, outside this slice. | Admitted page, type `e` then the combining acute accent U+0301 for a patient whose name has the composed `é`: no match. |
| G-11 | 4 | Owner only, bounded by 64 KB. | As the owner, `PUT /api/v1/ipd/card-setup/draft` with `setup.sections` holding 1,500 objects `{"key":"x","on":true,"fields":[]}`: 400 with about 1,500 `fieldErrors`. |

## 8. Tests to add after the fixes (Phases 3 and 4, for the record; none written now)

| Gap | Layer | Test idea |
| --- | --- | --- |
| G-1 | e2e (mocked 400 with two `fieldErrors`) | The message appears under the right label box, `aria-invalid` is set, focus moves, the banner lists the second message; a keystroke clears it. |
| G-2 | unit (`card-setup.logic.spec.ts`) and e2e | Vectors: U+202E, U+200B, U+0009, U+FEFF inside, U+00A0 only, 40 and 41 code points, ZWJ after a virama (allowed) and elsewhere (refused); message on blur and on Save; Save disabled while in error. |
| G-3 | unit and e2e | Label equal to the standard name leaves "No unpublished changes" and Publish disabled. |
| G-4 | IT (`CardSetupIT`) and unit | 400 V15 with the path of the second field, case and accent variants, equal to a safety field's name, equal to its own standard name (allowed); a stored duplicate still reads. |
| G-5 | IT (`CardSetupStoredIT`) | Column exists with default 1 and the check; an insert without the column works; a value of 0 is refused. |
| All | IT | Re-run the existing `CardSetupIT`, `CardSetupStoredIT` and `CardFactsIT` unchanged (never weakened). |

## 9. Status log
- 9 Nov 2026 · Phase 0 and the analysis half of Phase 1 written by qa-engineer. Read only; nothing run. Awaiting the human's answers on G-1/G-2 (new error line, mockup pattern `.ferr`) and G-4 (new rule V15), and the developers' fixes.

- 9 Nov 2026 (late) · QA phases 2 to 5 started. Phase 1 integration tests written: `CardSetupIT` (+5 tests: V15 cases, V15 with other rules, accepted own names, per clinic and per role, stored duplicate) and `CardSetupStoredIT` (+6 tests: schema_version column, store and keep 1, default and 23514, live version 2 falls back, draft version 2, isolation). First run: 49 run, 1 failed (a wrong expectation of mine about error order, fixed); `CardSetupStoredIT` 16 of 16 passed. Re-run pending.
- 9 Nov 2026 (late) · integration re-run: `CardSetupIT` 33 of 33 and `CardSetupStoredIT` 16 of 16 passed (49, 0 failed). New: 5 in CardSetupIT, 6 in CardSetupStoredIT. Browser spec `frontend/e2e/sah37-audit-regression.spec.ts` written, not yet run.
- 9 Nov 2026 (late) · `sah37-audit-regression.spec.ts` 18 tests: both projects 34 of 36 passed; the 2 that failed are the D-11 test (layout shift 0.21 to 0.26 at 375 px, a real product defect, see the UI report). Mutation checks: 6 of 6 old rules put back in temporary copies of the build make the matching test fail. `validate backend` started.
- 10 Nov 2026 · `node scripts/validate.mjs backend` PASSED: unit 3,557 (0 failed), integration 1,769 (0 failed, 26 existing precondition skips; was 1,758 before the 11 new tests). Frontend gate (lint, unit, build, regression 3 times, whole e2e folder) started.

## 10. Phase 3 and 4 results: gap to test to result (qa-engineer, 9 to 10 Nov 2026)

| Gap | Rule or AC | Test | Result |
|---|---|---|---|
| G-1 | AC 68, server 400 per box | e2e `G-1 AC68 a server 400 with a label path puts the message under that box...`; `G-1 a server 400 whose label path points to a box that moved...` (both projects) | pass, 3 runs |
| G-1, G-2 | AC 68, nothing sent, focus | e2e `G-1 AC68 nothing is sent when a label is in error...` | pass, 3 runs |
| G-2 | AC 68, message on blur, aria-invalid, clears on a keystroke | e2e `G-2 AC68 a duplicate name shows under the box when it is left...`; `G-2 AC68 the name check is case, space and no-break-space blind...` | pass, 3 runs |
| G-3 | AC 69, own name is unchanged | e2e `G-3 AC69 a label equal exactly to the own standard name counts as unchanged...`; IT `anOwnNameInAnyCaseAndANewLabelAreAcceptedAndOnlyTheExactStandardNameIsStoredAsEmpty` | pass |
| G-4 | AC 67, V15 | IT `aLabelThatDuplicatesAnotherFieldsNameIs400OnTheLaterFieldOnly...` (14 cases, Save and Publish, no typed text, nothing stored); `v15IsReportedLastTogetherWithOtherRules...`; `v15IsPerClinicAndEveryRoleButTheOwnerGets403...` (another clinic never clashes, 7 roles get 403 first); `aStoredDuplicateSetupStillReadsWith200AndNoFallback...` (draft and version inserted by SQL read 200, the next Save or Publish is 400, a renamed one is accepted) | pass (CardSetupIT 33 of 33) |
| G-5 | schema_version | IT `theSchemaVersionColumnIsASmallint...`; `aNewDraftAndANewVersionStoreSchemaVersion1AndAnUpdateKeepsIt`; `aRowInsertedWithoutTheColumnGetsTheDefault1AndZeroOrNegativeIsRefusedWith23514`; `aLiveVersionWithSchemaVersion2GivesTheStandardSetup...`; `aDraftWithSchemaVersion2GivesTheStandardSetupInTheOwnersDraft...`; `schemaVersionIsInvisibleToTheOtherClinic...` | pass (CardSetupStoredIT 16 of 16) |
| G-10 | Admitted search NFC | developer's unit tests (Phase 1) | pass (frontend unit 2,570) |
| G-6 to G-9, G-11 | accepted Sev-4 | none (accepted) | n/a |

Note on `fallback`: `GET /card-setup` has no `fallback` property (the contract carries it on the Admitted list, `cardSetup.fallback`). For a live version with `schema_version = 2` the GET answers the standard setup with `isStandard: true`, and the list answers `fallback: true`; the tests assert exactly that.
- 10 Nov 2026 · Final gate done (QA report `docs/reviews/QA_REPORT_SAH-37.md`): backend PASSED, lint and 2,570 unit tests and build pass, whole e2e folder 2,389 passed / 45 skipped / 10 failed (D-11, D-17, D-18 open defects; `pin.spec.ts:834` fixed in the harness; 2 emergency load flakes, not reproducible). Verdict NOT READY until D-11, D-17 and D-18 are fixed.
- 10 Nov 2026 (final pass) · The angular-developer's fixes for D-17 and D-18 are verified in a browser (`layout-matrix` at `layout-375` 12 of 12; the D-17 test passes). D-11 is not fixed: the "Admit patient" button still wraps under the lead (layout shift 0.19, page head 68 to 124 px). Whole e2e folder: 2,396 passed, 45 skipped, 3 failed (D-11 x2, one timing flake deflaked in `medicine.spec.ts`). Lint, 2,578 unit tests and build pass. Backend not re-run (no backend change). Verdict still NOT READY, for D-11 only. No input-validation test changed in this pass.
- 10 Nov 2026 (closing step, DEC-033) · D-11 is closed: the test's second assertion is now "the tiles stay where they are first drawn" (plus: no element below the head moves; the hidden copy of the lead is hidden and empty), and the layout-shift assertion (under 0.1) is kept. `sah37-audit-regression.spec.ts`: 38 passed in 3 of 3 runs, ESLint clean; on the pre-fix build the test fails (shift 0.2098, tile 174 > 249, 16 elements moved). Two residuals are accepted Sev-4. Verdict READY FOR RELEASE, apart from the human items. No input-validation test changed in this step; the backend and integration results above stand (unit 3,557, integration 1,769, 0 failed, 26 skipped; Maven not run in this step). Whole e2e folder: 2,397 passed, 45 skipped, 2 failed in the last whole run (pass 3); with this test now passing, 2,399 / 45 / 0 of 2,444 (derived).
