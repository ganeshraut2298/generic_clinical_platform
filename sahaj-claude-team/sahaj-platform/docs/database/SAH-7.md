# Database design · SAH-7 (Clinic masters)

Modules: platform, opd · Author: db-engineer · Date: 1 Nov 2026
Sources: `docs/stories/SAH-7.md` section 3, `docs/api/platform.yaml`, `docs/api/opd.yaml`, mockup `cfgProfile`, `cfgSpec`, `cfgFees`, `cfgPaper`. Migrations 1026 to 1037, forward-only (1000 to 1025 untouched).

## Migrations
| File | Does |
| --- | --- |
| `V2026_11_01_1026__platform_clinic_profile_masters_columns.sql` | `clinic_profile`: add `whatsapp`, `maps_url`, `brand_hex`, `updated_by` |
| `V2026_11_01_1027__platform_create_clinic_holiday.sql` | `platform.clinic_holiday` (RLS) |
| `V2026_11_01_1028__platform_create_specialty.sql` | Global `platform.specialty`, `platform.specialty_template` (SELECT only) |
| `V2026_11_01_1029__platform_seed_specialties.sql` | Seed: 4 Phase 1 and 4 Phase 2 specialties with templates |
| `V2026_11_01_1030__opd_opd_settings_fee_columns.sql` | `opd_settings`: add `discount_limits`, `upi_on_bill`, `updated_by` |
| `V2026_11_01_1031__opd_create_service.sql` | `opd.service` (RLS) |
| `V2026_11_01_1032__opd_create_case_paper_settings.sql` | `opd.case_paper_settings` (RLS) |
| `V2026_11_01_1033__opd_create_medicine.sql` | Global `opd.medicine` (SELECT only) |
| `V2026_11_01_1034__opd_create_medicine_favourite.sql` | `opd.medicine_favourite` (RLS) |
| `V2026_11_01_1035__opd_create_diagnosis.sql` | Global `opd.diagnosis` (SELECT only) |
| `V2026_11_01_1036__opd_seed_medicine.sql` | 52 starter medicines |
| `V2026_11_01_1037__opd_seed_diagnosis.sql` | 53 starter ICD-10 diagnoses |

## Reused, not duplicated
- `platform.clinic_profile` (SAH-4): extended. API `area` is the existing `city` column (SAH-5 already shows city as the area). `specialty_code` keeps its Phase 1 check (gp, dental, paeds, homeo), so a Phase 2 template cannot be applied even by mistake.
- `opd.opd_settings` (SAH-4) is the fee settings row; no separate `fee_settings` table. Existing `fee_timing`, `follow_up_days`, `follow_up_fee` are reused. Doctor new-visit fee stays in `platform.doctor_profile.fee_paise`.
- `platform.audit_event`: unchanged; every master change is audited through `platform.api.AuditLog`. The setup overview's "last change" is the newest row.

## Tables
Standard columns (`created_at`, `version`) as in earlier stories. All tenant tables: ENABLE + FORCE RLS, policy `tenant_id = platform.current_tenant()` for `sahaj_app`, `tenant_id` defaults to `platform.current_tenant()` (java never writes it).

| Table | Key columns | Keys and indexes (query served) | Grants |
| --- | --- | --- | --- |
| `platform.clinic_holiday` | `holiday_date date`, `name` 1-120, `type` CLOSED/MORNING_ONLY/EVENING_ONLY, `created_by` | PK `id`; `uq_clinic_holiday_tenant_date (tenant_id, holiday_date)` refuses a repeated date (409) and lists in date order | select, insert, delete |
| `opd.service` | `name` 1-120, `category` (7 API values), `price_paise >= 0`, `active`, audit columns | `uq_service_tenant_name (tenant_id, lower(name))` duplicate guard and fee list; `ix_service_tenant_active` partial, billing picker | select, insert, update |
| `opd.case_paper_settings` | `size` A5/A4, `lang2` null/mr/hi, `preprinted`, `caps`, `sections_json` + `sections_schema_version` | PK `tenant_id` | select, insert, update |
| `opd.medicine_favourite` | `medicine_id` fk `opd.medicine`, `created_by` | PK `(tenant_id, medicine_id)`: idempotent PUT and the favourites left join | select, insert, delete |
| `opd.opd_settings` (added) | `discount_limits jsonb` default `{"FRONT_DESK":10,"NURSE":0,"OWNER":100}`, `upi_on_bill` default true | existing PK | unchanged |

Global tables (no tenant column, no RLS, SELECT only for `sahaj_app`, changed by migration only):
- `platform.specialty (code, name, sort_order)` and `platform.specialty_template (code, phase 1|2, template_json)`. `template_json` is `{schema_version:1, features:[text], services:[{name, category, price_paise}]}`. API `Specialty` = specialty join template (`features` from the json, `phase` from the column).
- `opd.medicine (id, name, form, schedule, usual_dose)`. Ids come from `md5('opd.medicine:'||lower(name))::uuid`, same in every environment. Prefix index `uq_medicine_name_lower (lower(name) text_pattern_ops)`; query `where lower(name) like lower(:q) || '%'`.
- `opd.diagnosis (code pk, name, specialty)`. Prefix indexes on `lower(name)` and on `code`, both `text_pattern_ops`. `specialty` is an ordering hint only; do not filter by it.

Rules held in the database: `rx` section always on and only the nine mockup section keys in `sections_json`; `discount_limits` is an object whose keys are API `Role` names; holiday type and service category lists; service name unique per clinic ignoring case.

## Seed data (human decision, 1 Nov 2026)
- **Starter lists, not clinically reviewed.** 52 generic medicines and 53 common ICD-10 diagnoses in separate seed files. A clinician must review names, strengths, schedules, doses, codes and specialty tags before real use. Reference data only; no patient data.
- Specialty cards and the gp and dental service names and prices come from the mockup. **Assumption to confirm:** paediatrics service prices (Nebulisation 200, Vaccination visit 100, Developmental check 300 rupees) are not in the mockup. Homeopathy and the Phase 2 templates have no services.
- `medicine.schedule` is the usual timing (1-0-1, SOS, Once). Paediatric liquids say "weight based".

## Volume, retention, compatibility
- Rows per clinic: holidays about 15 a year; services 5 to 60; case paper settings 1; favourites 0 to 50; `opd_settings` 1. At 10,100 clinics the largest table is services, about 0.6 million. No partitioning.
- Never hard-deleted: services (use `active=false`), settings. Deleted by design (configuration, audit_event keeps the trail): holidays, medicine favourites.
- Backward compatible: only additive columns (nullable or with defaults) and new tables. The previous app version runs unchanged.

## For java-developer
1. Bind `app.tenant_id` as usual; do not write `tenant_id`. Pass `created_by` on service, holiday, favourite inserts (not null).
2. Map unique violations to 409: `uq_service_tenant_name` ("That service is already in the list"), `uq_clinic_holiday_tenant_date`. A removed service still holds its name; offer reactivate (PATCH `active=true`) rather than a new row. Require price above 0 on add in the service layer (the database allows 0 for edits).
3. Money: API rupees x 100 = `price_paise`. Template service prices are already in paise.
4. Apply template: read `specialty_template` (reject phase 2 with 400), then `insert into opd.service ... on conflict (tenant_id, lower(name)) do nothing`; it needs an explicit conflict target on the expression index. Then set `clinic_profile.specialty_code`. This happens in one transaction through `opd.api` from `platform` (module boundary).
5. Fee settings = `opd_settings` columns; `discountLimits` JSON maps straight to `discount_limits`. Validate each value 0 to 100 in the service (the database checks only keys). A role missing from the map has no discount right. No row yet means defaults; `opd_settings` is created at setup complete, upsert on conflict `tenant_id`.
6. Case paper: no row until the first save. GET returns defaults when absent (A5, English only, letterhead off, caps off, sections all on except `history`); PUT upserts on `tenant_id`. API `secondLanguage` maps to `lang2`, `sections` to `sections_json`; always send `rx: true`.
7. Clinic profile: `mapsUrl` must start with http(s)://, `brandHex` is `#rrggbb`; keep `platform.tenant.brand` equal to `clinic_profile.brand` on every edit. WhatsApp stored as typed (max 20 chars).
8. Medicine search: favourites first via the left join in `1034`'s header comment; page with limit and offset. Favourite PUT: `insert ... on conflict do nothing`; DELETE of a non-favourite returns 404 per contract, so check the row count. An unknown `medicineId` fails on `fk_medicine_favourite_medicine` (23503), map to 404.
9. `sahaj_app` cannot write the global tables; any insert, update or delete there fails with permission denied (a good integration test).
10. Setup overview: computed from the fixed checklist in code (profile name, holidays or timings, specialty, services, fee settings, case paper row) and the latest `audit_event`.
