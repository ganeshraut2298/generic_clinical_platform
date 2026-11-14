-- SAH-60-4 (ADR-023 D10, H12, "Data needed" item 11) · Visit templates ("Start from a visit template"). Forward-only, additive.
--   opd.emr_visit_template   a named set of complaints, diagnoses, investigations, services, medicines and advice that a doctor
--                            saves from the current visit and any of the clinic's doctors applies to a later visit
-- Setup data of the clinic: clinical text and master keys only, never patient data (no patient id, no visit id, no name).
-- Applying a template is a client merge followed by the normal draft save, so this table is read and inserted only.
-- Row level security: ENABLE + FORCE, policy for sahaj_app on platform.current_tenant(), tenant-first indexes.
-- No foreign key to platform.membership (cross-module); the creator's membership id is opaque.
-- At most 100 per clinic: kept by the application (400 visit-template-limit), not by the database.

create table if not exists opd.emr_visit_template (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                             constraint fk_emr_visit_template_tenant references platform.tenant (id),
  name                       text not null
                             constraint ck_emr_visit_template_name check (char_length(btrim(name)) between 1 and 60),
  sub_line                   text
                             constraint ck_emr_visit_template_sub_line check (sub_line is null or char_length(btrim(sub_line)) between 1 and 120),
  items                      jsonb not null
                             constraint ck_emr_visit_template_items_shape check (
                               jsonb_typeof(items) = 'object' and coalesce(jsonb_typeof(items -> 'schemaVersion'), '') = 'number')
                             constraint ck_emr_visit_template_items_size check (octet_length(items::text) <= 32768),
  created_by_membership_id   uuid not null,
  active                     boolean not null default true,
  created_at                 timestamptz not null default now(),
  created_by                 uuid not null,
  updated_at                 timestamptz,
  updated_by                 uuid,
  constraint uq_emr_visit_template_tenant_id unique (tenant_id, id)
);

-- One name per clinic, ignoring case ("A template with this name exists"). Also the list read: the clinic's active templates
-- by name (where active, order by lower(name)); at most 100 rows, so the filter on active costs nothing.
create unique index if not exists uq_emr_visit_template_tenant_name_lower
  on opd.emr_visit_template (tenant_id, lower(name));

alter table opd.emr_visit_template enable row level security;
alter table opd.emr_visit_template force row level security;

create policy p_emr_visit_template_tenant on opd.emr_visit_template
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Grants: the schema default privileges give sahaj_app all four commands, so take everything away first. No delete (a template
-- is switched off with active = false). The name and the items never change after creation.
revoke all on opd.emr_visit_template from sahaj_app;
revoke all on opd.emr_visit_template from public;

grant select, insert on opd.emr_visit_template to sahaj_app;
grant update (active, updated_at, updated_by) on opd.emr_visit_template to sahaj_app;

comment on table opd.emr_visit_template is
  'A clinic''s EMR visit template (SAH-60-4, ADR-023 D10, H12): name, sub line and the items (complaints, diagnoses, investigations, services, medicines, advice as master keys or text, repeatLastRx) as json with a schema version. Shared by the clinic''s doctors, at most 100 per clinic (application rule). No patient data. Never deleted: active = false switches it off. Created by a doctor or an owner with doctor details.';
comment on column opd.emr_visit_template.items is 'Json {schemaVersion, complaints, diagnoses, investigations, services, medicines, advice, repeatLastRx}: clinical text and master keys only, at most 32 KB.';
