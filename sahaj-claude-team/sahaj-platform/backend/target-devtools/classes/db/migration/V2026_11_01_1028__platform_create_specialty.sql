-- SAH-7 · Specialty templates : global reference data, read-only for the application.
-- No clinic owns these rows, so there is no row level security (same as platform.role_permission_default).
-- sahaj_app gets SELECT only; rows are changed by migrations (and later by Sahaj admin, phase 9).
--
-- platform.specialty          : the list shown on Clinic setup > Specialty template.
-- platform.specialty_template : what applying a specialty loads. phase 1 = can be applied,
--                               phase 2 = shown with a "Phase 2" pill and cannot be applied yet.
-- template_json shape (schema_version 1):
--   { "schema_version": 1,
--     "features": ["..."],                       -- bullet list on the card (API Specialty.features)
--     "services": [ {"name": "...", "category": "...", "price_paise": 15000} ] }
-- Applying a template inserts the listed services only when the clinic does not already have a
-- service with that name; it never deletes or overwrites anything.
create table if not exists platform.specialty (
  code        text primary key
              constraint ck_specialty_code check (code ~ '^[a-z][a-z0-9_]{1,19}$'),
  name        text not null
              constraint ck_specialty_name check (char_length(btrim(name)) between 1 and 120),
  sort_order  smallint not null
);

create table if not exists platform.specialty_template (
  code           text primary key
                 constraint fk_specialty_template_specialty references platform.specialty (code),
  phase          smallint not null
                 constraint ck_specialty_template_phase check (phase in (1, 2)),
  template_json  jsonb not null
                 constraint ck_specialty_template_json check (
                   jsonb_typeof(template_json) = 'object'
                   and (template_json ->> 'schema_version') = '1'
                   and jsonb_typeof(template_json -> 'features') = 'array'
                   and jsonb_typeof(template_json -> 'services') = 'array')
);

revoke all on platform.specialty from sahaj_app;
revoke all on platform.specialty from public;
grant select on platform.specialty to sahaj_app;

revoke all on platform.specialty_template from sahaj_app;
revoke all on platform.specialty_template from public;
grant select on platform.specialty_template to sahaj_app;

comment on table platform.specialty is
  'Global, read-only list of clinic specialties (SAH-7). Changed by migration only.';
comment on table platform.specialty_template is
  'Global, read-only template per specialty: phase (1 usable, 2 coming) and template_json (features, starter services). Changed by migration only.';
