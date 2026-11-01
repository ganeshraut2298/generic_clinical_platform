# SAH-4 · Clinic setup wizard
Mockup screen: Setup wizard (5 steps: clinic, specialty, staff, OPD, look and web address)
Module: platform

## 1. Story
As a new clinic owner, I want a short guided setup, so that my clinic is ready to use in about 15 minutes.

## Acceptance criteria
1. Given I complete all five steps, then the clinic profile, specialty template, staff logins, OPD settings, colour and web address are saved and the app opens in my brand colour.
2. Given I leave at step 3, when I log in again, then I return to step 3 with earlier answers kept.
3. Given a web address already taken, then I am told and offered alternatives.
4. Given I add a 3rd doctor on the Free plan, then I am told the plan limit and cannot save it.
5. Given a staff member added in step 3, when they log in with their mobile, then they see only their role's menu.
6. Given the rail, then it shows "Set up your clinic", the 5 steps (Your clinic, Specialty, Doctors and staff, How your OPD runs, Look and web address), a progress bar and "About N minutes left. Everything can be changed later."
7. Given step 1, then the fields are Clinic name, Address, City, PIN code, Clinic phone and Registration number (optional). Clinic name and City are required: "Enter your clinic name", "Enter the city".
8. Given step 2, then General practice, Dental, Paediatrics and Homeopathy can be chosen, and "What we set up for …" lists that template's contents.
9. Given step 3, then doctors have name, specialty and fee, the first is marked Owner, staff have name, role (Front desk, Nurse, Counsellor, Pharmacist) and mobile, and the counter "N of 5 users on the free year" shows. Add is disabled at the limit.
10. Given step 4, then the four questions are: consultation fee (At the token, After consultation, Split), queue (Yes, with tokens; No, straight in), nurse vitals (Yes, No) and follow-up (1 to 30 days; Free, 50%, Full), with a live "Your OPD flow" preview.
11. Given step 5, then I choose a clinic colour, web address, default staff language (English, मराठी, हिंदी) and see a preview. The address allows 3 to 24 lowercase letters, numbers or hyphens, and shows availability.
12. Given "Create my clinic", then a progress screen shows and then the app opens with the Getting started panel and setup progress pill.
13. Given "Exit setup", then I am logged out and my progress stays.
14. Given a non-owner, then the setup API returns 403.

## 3. Technical design
- Tables, schema `platform`, all with `tenant_id` and RLS:
  - `setup_progress`: `tenant_id pk`, `current_step`, `answers_json`, `completed_at`.
  - `clinic_profile`: `tenant_id pk`, `name`, `address`, `city`, `pin`, `phone`, `registration_no`, `name_local`, `specialty_code`, `brand`, `default_language`.
  - `doctor_profile`: `membership_id`, `tenant_id`, `specialty`, `fee_paise`, `degree`, `registration_no`.
  - `opd_settings` is written through the `opd.api` port in schema `opd`: `fee_timing`, `queue_on`, `vitals_on`, `follow_up_days`, `follow_up_fee`.
- `tenant.slug` gets a unique index. Reserved words from the mockup's list: `apollo, care, demo, clinic, city, lifeline, sai, admin, www`. Alternatives: slug + city, slug + number. Availability is checked across tenants through a narrow function that returns only a boolean.
- Each `PUT step` validates that step, saves to `answers_json`, and is audited. `complete` writes the real rows in one transaction and creates inactive-login-free memberships for staff; it is idempotent.
- Plan limits live in `PlanLimits` in `platform.api`: Free and Professional are 2 doctors and 5 users. The server enforces them with 409 `plan-limit`.
- Specialty template content is a read-only seed shared with SAH-7.
- Fees are stored as integer paise, displayed as ₹ with Indian grouping.
- A person already registered in another clinic gets a second membership, not a second user.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET | `/setup` | owner | none | `{currentStep, answers, limits}` | 403 |
| PUT | `/setup/steps/{step}` | owner | step answers | `{currentStep, answers}` | 400, 403, 409 plan limit |
| GET | `/setup/slug-availability?slug=` | owner | none | `{available, alternatives[]}` | 400 |
| POST | `/setup/complete` | owner | none | `{next:"APP", brand}` | 400, 409 taken or limit |

All paths are under `/api/v1/platform`.

## 5. Tasks
### Tasks — Backend
- Migrations, step validation, resume, completion transaction, plan limits, slug check, audit, `opd.api` port.
- Tests: criteria 1 to 5 and 14; tenant isolation; slug race (unique index wins, returns 409).

### Tasks — Frontend
- Route `/setup`, guard sends `next=SETUP` users here. Rebuild from mockup `pSetup`, `suClinic`, `suSpec`, `suTeam`, `suOpd`, `suLook`, `pBuilding`.
- Save on Continue, resume from the server, debounced slug check, plan-limit message, responsive rail.
- Getting started panel and setup pill hook on Today, using a placeholder page until later stories.
- Unit tests. Playwright smoke: finish all 5 steps and reach the app in the chosen colour.

## 6. Out of scope, Open questions
Out of scope: logo upload (SAH-7), real clinic URLs per subdomain (path or dev host only in Phase 1), sample patients.
Open questions:
- **Doctors have no mobile field in the mockup**, but your requirement says each has a mobile and login. Recommended: add a mobile field for doctors other than the owner.
- Mockup says logins are sent on WhatsApp, which is not in Phase 1. Recommended: say "They can log in with this mobile number" instead.
- Mockup pre-fills example details with a notice. Recommended: start empty in production.
- Getting started steps "Install the Doctor App" and "Clear sample data" have nothing behind them yet. Recommended: hide them until they exist.
- Subdomains need DNS. Recommended: reserve and store the address now, and serve it later.

## 7. Status log
