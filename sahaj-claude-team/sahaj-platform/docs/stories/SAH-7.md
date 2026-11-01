# SAH-7 · Clinic masters
Mockup screen: Clinic setup → Clinic profile, Specialty template, Fees and billing, Case paper and Rx
Module: platform (profile, specialty), opd (services and fees, medicine favourites, case paper)

## 1. Story
As a clinic owner, I want to keep my clinic's details, services, fees, medicines and case paper up to date, so that papers and bills are correct and staff pick from fixed lists.

## Acceptance criteria
1. Given the owner edits the clinic name, then new case papers and bills show the new name. (Until those screens exist, verified through the profile API and the case paper preview.)
2. Given the owner adds a service with a fee, then it is available for billing later and appears in the fee list; a duplicate name is refused ("That service is already in the list").
3. Given the owner marks a medicine as favourite, then it is listed first for doctors of that clinic only (tenant check).
4. Given a non-owner, when they try to change masters, then the API returns 403.
5. Given global reference data, then no clinic can change it.
6. Given any change, then it is audited, and the setup overview shows progress and the last change.
7. Given Clinic profile, then the fields match wizard step 1 and the mockup: Clinic name, Name in Marathi or Hindi, Address, Clinic phone, WhatsApp number, Google Maps link and clinic web address (read-only).
8. Given Timings, leave and holidays on Clinic profile, then holidays can be added and removed with date, name and type (Closed, Morning only, Evening only); a repeated date is refused.
9. Given Specialty template, then templates in use and "Phase 2" templates show, and applying a template keeps all data.
10. Given Fees and billing, then the owner sets when the fee is collected, each doctor's new-visit fee, the follow-up window (1 to 30 days) and fee (Free, 50%, Full), a live example sentence, discount limits by role, and UPI QR on the bill.
11. Given Case paper and Rx, then the owner sets paper size (A5, A4), language (English, + मराठी, + हिंदी), pre-printed letterhead, medicine names in capitals and which sections show, with a live print preview. Medicines (Rx) and the doctor's registration number cannot be switched off.
12. Given the overview, then it shows a setup percentage, a checklist with Fix links, and tiles for each area.
13. Given a service price, then it is at least ₹0 for edits and above ₹0 when added, and a removed service stays on past bills.
14. Given the seed, then specialties with templates, the starter medicine list and ICD-10 diagnoses exist in every environment.

## 3. Technical design
- `platform`: extend `clinic_profile` with `whatsapp`, `maps_url`; new `clinic_holiday` (`tenant_id`, `date`, `name`, `type`; unique on tenant and date). RLS.
- `platform` global read-only: `specialty`, `specialty_template` (`code`, `template_json`, `phase`). `sahaj_app` has SELECT only; no `tenant_id`.
- `opd` tenant tables with RLS:
  - `service` (`id`, `tenant_id`, `name`, `category`, `price_paise`, `active`; unique on tenant and lower name).
  - `fee_settings` (fee timing, follow-up, discount limits by role, `upi_on_bill`), which extends `opd_settings` from SAH-4.
  - `medicine_favourite` (`tenant_id`, `medicine_id`; unique).
  - `case_paper_settings` (`tenant_id`, `size`, `lang2`, `preprinted`, `caps`, `sections_json`).
- `opd` global read-only: `medicine` (`id`, `name`, `form`, `schedule`, `usual_dose`), `diagnosis` (`code`, `name`, `specialty`). Seed by Flyway in separate migrations with no `tenant_id`: about 50 common medicines and about 50 common ICD-10 codes (human decision, 1 Nov 2026). A clinician should review the list before real use. Search is by `name` with a `text_pattern_ops` or trigram-free prefix index.
- Medicine search orders favourites first through a left join on the tenant's favourites.
- Services are soft-deleted (`active=false`), so later bills keep their history.
- Applying a specialty template creates services from the template only when missing; never deletes or overwrites.
- Setup overview is computed by the server from a fixed checklist and the latest `audit_event`.
- `opd` calls `platform.api.AuditLog` and reads the clinic name through `platform.api` only.

## 4. API
| Method | Path | Role | Request | Response | Errors |
|---|---|---|---|---|---|
| GET, PUT | `/platform/clinic/profile` | read any, write owner | `ClinicProfile` | `ClinicProfile` | 400, 403 |
| GET, POST | `/platform/clinic/holidays` | read any, write owner | `Holiday` | `Holiday` | 400, 403, 409 |
| DELETE | `/platform/clinic/holidays/{id}` | owner | none | 204 | 403, 404 |
| GET | `/platform/specialties` | any | none | `Specialty[]` | 401 |
| PUT | `/platform/clinic/specialty` | owner | `{code}` | `ClinicProfile` | 400, 403 |
| GET | `/platform/setup/overview` | owner | none | `SetupOverview` | 403 |
| GET | `/platform/audit-events?limit=` | owner | none | `AuditEvent[]` | 403 |
| GET, POST | `/opd/services` | read any, write owner | `Service` | `Service` | 400, 403, 409 |
| PATCH | `/opd/services/{id}` | owner | `Service` fields | `Service` | 400, 403, 404, 409 |
| GET, PUT | `/opd/fee-settings` | read any, write owner | `FeeSettings` | `FeeSettings` | 400, 403 |
| GET | `/opd/medicines?q=&page=` | any | none | page of `Medicine` | 401 |
| PUT, DELETE | `/opd/medicine-favourites/{medicineId}` | owner | none | 204 | 403, 404 |
| GET | `/opd/diagnoses?q=` | any | none | `Diagnosis[]` | 401 |
| GET, PUT | `/opd/case-paper-settings` | read any, write owner | `CasePaperSettings` | same | 400, 403 |

All paths are under `/api/v1`. Contracts: `docs/api/platform.yaml`, `docs/api/opd.yaml`.

## 5. Tasks
### Tasks — Backend
- Migrations and seed, all endpoints, owner checks, audit, tenant isolation.
- Tests: criteria 2 to 6 and 13; `sahaj_app` cannot write global tables; another tenant's service returns 404; favourites do not leak between tenants.

### Tasks — Frontend
- Pages inside the Clinic setup layout from SAH-6, per mockup `cfgHome`, `cfgProfile`, `cfgSpec`, `cfgFees`, `cfgPaper`.
- Search settings box, read-only view for non-owners, ₹ formatting with Indian grouping, live paper preview, setup percentage pill.
- Medicine favourites list with search (see Open questions).
- Unit tests. Playwright smoke: edit name, add a service, refuse a duplicate.

## 6. Out of scope, Open questions
Out of scope: Sahaj admin screens for global data (phase 9), tariff and pricing rules, packages, custom sections, WhatsApp and Privacy pages.
Open questions:
- **Medicine favourites has no Clinic setup screen in the mockup.** It appears only as "Rx favourites" in template text and in admin masters. Please confirm my proposal: a "Medicine favourites" section at the bottom of Case paper and Rx.
- **Service category** is in your requirement but not on the mockup's service list. Recommended: add a simple category field.
- **Logo upload** is on the mockup's Clinic profile, but Phase 1 has no documents service. Recommended: store a small image (2 MB limit) in the database for now, or defer it. Custom brand colour stays in this story.
- **Change history "Undo"** is in the mockup but not your requirement. Recommended: show history without Undo in Phase 1.
- Doctor leave switches on Clinic profile need appointments, which come later. Recommended: hide them until then.

## 7. Status log
