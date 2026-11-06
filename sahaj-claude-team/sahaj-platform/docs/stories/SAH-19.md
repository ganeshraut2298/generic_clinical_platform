# SAH-19 · Plan and modules
Mockup screen: Clinic setup → Plan and modules (`cfgModules`, key `modules`)
Module: platform

## 1. Story
As the clinic owner, I want to see which modules my clinic has (OPD, IPD, HRA) and ask for the ones I do not have, so that staff see only the menus the clinic really uses.

## Acceptance criteria
1. Given any signed-in clinic user opens Clinic setup → Plan and modules, then the panel "Modules on your plan" shows a plan pill ("Free year" for the free plan, "Pro" otherwise) and these rows in order: **OPD** ("Appointments, tokens, queue, case paper, billing and follow-ups. Always on.", pill Included); **WhatsApp messages** ("Later: needs the messaging service, not in this phase", pill Later); **In-patients (IPD)** ("Emergency, wards and beds, admissions, deposits, transfers and discharge. Sahaj switches it on for your clinic."); **Staff, timings and leave (HRA)** ("Shifts, leave approval, attendance and holidays. Doctor timings and holidays work without it.", label Add-on, switch); **Pharmacy and Lab** ("Dispensing, stock, lab orders and reports.", pill Phase 2).
2. Given the IPD row, then its pill shows the clinic's IPD state: Not on your plan (off), Trial (with end date), On, or Read-only. When off and the owner has not asked yet, the owner sees a "Request IPD" button; after the request the row shows "Request sent · Sahaj will call you". Non-owners see the pill only. Requesting is idempotent (a second request keeps the first time). No notification is sent in this phase; the request is stored for the Sahaj team.
3. Given state Read-only, then the notice "IPD is read-only. Past admissions, bills and summaries stay viewable for 90 days, then IPD is hidden. Nothing is deleted." is shown.
4. Given the owner switches HRA on or off, then it is saved at once, audited, and the page shows the new state; non-owners see the switch disabled. Switching off never deletes data.
5. Given the "Menu mode" panel, then two cards Simple and Advanced show how many menu items each mode gives the signed-in user ("N menu items ..."), the current one is selected, and choosing one saves the user's own preference through the existing preferences endpoint (SAH-5) and updates the menu. The line "Nothing is lost in Simple mode. Every page still opens from Go to… (Ctrl K) at the top." is shown only if the Ctrl K palette exists in the app; otherwise the line reads "Nothing is lost in Simple mode. Switch to Advanced any time."
6. Given the panel "Which masters each module uses", then three groups show: Common to all modules, OPD only, IPD only and HRA only, with the chips listed in the mockup, each group marked "In use" or "Module off" according to the entitlements (Common and OPD always in use).
7. Only the OWNER may change HRA or request IPD (403 otherwise); every clinic role may read. Tenant isolation (RLS) applies.
8. Per DEC-010 (human chose option 2, 5 Nov): while IPD is off the owner also sees the mockup notice "Try IPD: start a 30-day trial here. In the product the Sahaj team does this from the admin console." with a "Start 30-day IPD trial" button (`POST /modules/ipd-trial`). It sets state Trial with an end date 30 days ahead, menus and setup for IPD appear later with the IPD stories, and it is audited. It is temporary: the property `sahaj.modules.owner-ipd-trial` (default true) turns it off, after which the endpoint returns 404 and the notice is not shown (the response carries `ipdTrialAvailable`). The Sahaj admin console (changing IPD state for any clinic) remains a later story.

## 3. Technical design
- `platform.module_entitlement`: `tenant_id` PK, `ipd_state text check in ('off','trial','on','readonly') default 'off'`, `ipd_trial_ends_at timestamptz null`, `hra_on boolean default false`, `bed_limit int default 25 check between 5 and 300`, `ipd_requested_at timestamptz null`, `updated_at/by`, `version`. FORCE RLS; `sahaj_app` select, insert, update. Lazy row: synthesized with defaults until the first write (same pattern as privacy_settings).
- Plan name comes from the existing tenant plan field. Menu counts: computed with `NavigationPolicy.menuFor(role, "simple")` and `"advanced"` for the signed-in user's role.
- Other modules read entitlements only through a `platform.api` port (`ModuleEntitlementPort`), added now so later IPD and HRA stories use it. No other module is changed in this story.

## 4. API (`/api/v1/platform`, contract `docs/api/platform.yaml`)
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/modules` | any clinic role | none | `ModulesView` | 401 |
| POST | `/modules/ipd-request` | OWNER | none | `ModulesView` | 403, 409 (IPD already on, trial or read-only) |
| POST | `/modules/ipd-trial` | OWNER | none | `ModulesView` | 403, 404 (switched off), 409 (IPD not off) |
| PUT | `/modules/hra` | OWNER | `{on}` | `ModulesView` | 400, 403 |

## 5. Tasks
### Tasks — Backend
Migration (db-engineer); service, controller, entitlement port, audit; unit tests. Real-DB tests by qa-engineer.
### Tasks — Frontend
Page for key `modules` (`built: true`), per mockup; reuse the existing preferences call for menu mode; unit tests (mock HTTP).

## 6. Out of scope, Open questions
Out of scope: changing IPD state (admin console), bed limit editing, WhatsApp usage, hiding menus by module (comes with the IPD and HRA stories), the Ctrl K palette.
Open questions: DEC-010 (who changes IPD state until an admin console exists).

## 7. Status log
