# Sahaj build playbook: foundation to release

The tech-lead agent works through this backlog top to bottom. Each row is one story, one branch and one full pipeline run.
You approve three times per story (`stories`, `contract`, `merge`) in `docs/approvals.md` and answer escalations.
Screens refer to `docs/design/screen-map.md`; the mockup is the design.

## Stage A · Foundation (nothing else starts until A is merged)

| Story | Title | Mockup screen | Agents mainly involved | Done when |
|---|---|---|---|---|
| SAH-1 | Project scaffold | Foundation (no screen) | java-developer, angular-developer, qa-engineer | Spring Boot 3 / Java 17 app with the six module packages and Spring Modulith check; Angular app with Material and lint/test/build; `validate all` passes |
| SAH-2 | Tenancy, users, roles and audit | Foundation (no screen) | solution-architect, db-engineer, java-developer, security-reviewer | `platform.tenant`, `app_user`, `role`, `audit_event` with RLS; tenant set per request; cross-tenant test fails as expected |
| SAH-3 | Sign-in and session | Start screen and sign-in | all | Login, JWT session, role from the user, logout, locked after failed tries. **Escalation expected:** Spring Security JWT now vs Keycloak (roadmap) |
| SAH-4 | App shell and theme | App shell | angular-developer | Sidebar, header, Simple/Advanced, light/dark, brand colours, Marathi/Hindi/English labels, matches the mockup |

## Stage B · Platform setup

| Story | Title | Mockup screen |
|---|---|---|
| SAH-5 | Clinic profile and setup overview | Clinic setup → Overview, Clinic profile |
| SAH-6 | Team and access (staff, roles, permissions) | Clinic setup → Team and access |
| SAH-7 | Plan and modules (entitlements: OPD, IPD, HRA on/off, trial, read-only) | Clinic setup → Plan and modules |
| SAH-8 | Sahaj admin: tenants and plans | Sahaj admin portal |

## Stage C · Patients

| Story | Title | Mockup screen |
|---|---|---|
| SAH-9 | Patient registration and search | Patients |
| SAH-10 | Duplicate detection and merge | Patients |

## Stage D · OPD

| Story | Title | Mockup screen |
|---|---|---|
| SAH-11 | Appointment slots and OPD workflow setup | Clinic setup → OPD workflow, Appointments and slots |
| SAH-12 | Booking rules | Clinic setup → Booking rules |
| SAH-13 | Front-desk appointments workspace | Appointments |
| SAH-14 | Queue and tokens | Queue |
| SAH-15 | Consultation, prescription and case paper settings | Queue; Clinic setup → Specialty template, Case paper and Rx |
| SAH-16 | Follow-up lists | Follow-ups |
| SAH-17 | Today dashboard per role | Today |

## Stage E · Billing

| Story | Title | Mockup screen |
|---|---|---|
| SAH-18 | Fees, tariff, branch and payer rates, pricing rules | Clinic setup → Fees and billing, Tariff and services |
| SAH-19 | Billing counter: bills, service orders, payments, refunds, day close | Billing |
| SAH-20 | Owner billing view and revenue lists | Billing; Revenue, Reports |

## Stage F · IPD

| Story | Title | Mockup screen |
|---|---|---|
| SAH-21 | Wards, rooms, beds and bed categories | Clinic setup → Wards/rooms/beds, Bed categories |
| SAH-22 | Admission and deposits | Clinic setup → Admission and deposits; IPD → Bed board |
| SAH-23 | Bed board | IPD → Bed board |
| SAH-24 | Admitted list and configurable patient card (draft/publish, roles, locked safety fields) | IPD → Admitted; Clinic setup → Patient card, Roles and access |
| SAH-25 | Nursing vitals, notes and EWS escalation | Nursing sheet; IPD → Safety |
| SAH-26 | Medicine orders, safety checks, pharmacist check and MAR | EMR sheet; Nursing sheet; IPD → Safety |
| SAH-27 | Transfer, discharge and case paper | Transfer, Discharge, Case paper |
| SAH-28 | Emergency and triage | IPD → Emergency; Clinic setup → Emergency and triage |
| SAH-29 | Safety board, emergency access and clinical audit trail | IPD → Safety |

## Stage G · Staff, HRA and hardening

| Story | Title | Mockup screen |
|---|---|---|
| SAH-30 | Staff and leave | Staff and leave |
| SAH-31 | HRA questionnaires and risk scores | HRA (**blocked until you add HRA screens to the mockup**) |
| SAH-32 | Hardening: full security review, load test with seeded 10,000-clinic data, backup and restore drill, UAT checklist, release `v2027.1.0` | Foundation (no screen) |

## How a story runs (what you see)

1. You: **"Start SAH-<n>."**
2. tech-lead creates `feature/SAH-<n>-<slug>`, runs the business-analyst, and shows you the stories.
3. You read `docs/stories/SAH-<n>.md`, add `| SAH-<n> | stories | APPROVED | <you> | <date> |`, say **"continue"**.
4. tech-lead runs the solution-architect and shows the API contract.
5. You read `docs/api/<module>.yaml`, add the `contract` approval, say **"continue"**.
6. tech-lead runs db-engineer → java-developer ∥ angular-developer → qa-engineer → both reviewers, retrying failed gates.
7. tech-lead shows the review results and proposes a commit (you confirm).
8. You check the app (`npm --prefix frontend start`, `mvn -f backend/pom.xml spring-boot:run`), add `merge` approval, then:
   `git switch main` → `git merge --no-ff feature/SAH-<n>-<slug>`
9. You: **"Next story."**

Whenever the tech-lead escalates, it writes `docs/decisions/DEC-<nnn>.md` and asks you with options. Answer, and it continues.

## Useful prompts

- Status: **"Show the board and anything waiting for me."**
- Resume after a break: **"Read BOARD.md and continue where we stopped."**
- Change of mind: **"Pause SAH-<n>. I will update the mockup first."**
- Bug: **"Open fix/SAH-<n>-<slug> for: <problem>. Run the normal pipeline."**
