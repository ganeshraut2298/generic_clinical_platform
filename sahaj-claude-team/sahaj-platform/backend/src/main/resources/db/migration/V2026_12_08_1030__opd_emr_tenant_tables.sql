-- SAH-43 and SAH-44 (ADR-019 D5, D10, D11) · Clinic-owned EMR tables. Forward-only, additive.
--   opd.emr_template_setup    one revision counter per clinic and template (optimistic lock of the override page)
--   opd.emr_template_override Show / Required overrides per clinic, template and section
--   opd.emr_settings          EMR screen defaults per clinic (one row, created on the first save)
--   opd.emr_item_usage        per-doctor use count of master items, for the quick-add ranking
-- Every table: tenant_id defaults to platform.current_tenant() (the application never writes it), ENABLE + FORCE row
-- level security, policy for sahaj_app on platform.current_tenant(), first index column is tenant_id.
-- No foreign key to platform.membership or to any global EMR table (cross-module rule; keys survive template versions).

-- ---------------------------------------------------------------------------------------------
-- 1. opd.emr_template_setup: created on the first change of a template by a clinic. Every change and every reset
--    that changes something adds 1 to revision; a request carries the revision it read (stale = 409).
--    One revision per clinic and template, not one version per override row, because a reset deletes override
--    rows and a per-row version would restart at 0 (ADR-019 D5).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_template_setup (
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_emr_template_setup_tenant references platform.tenant (id),
  template_key  text not null
                constraint ck_emr_template_setup_key check (template_key in ('gp', 'dental', 'paeds', 'homeo')),
  revision      bigint not null default 0
                constraint ck_emr_template_setup_revision check (revision >= 0),
  created_at    timestamptz not null default now(),
  created_by    uuid not null,
  updated_at    timestamptz,
  updated_by    uuid,
  constraint pk_emr_template_setup primary key (tenant_id, template_key)
);

alter table opd.emr_template_setup enable row level security;
alter table opd.emr_template_setup force row level security;

create policy p_emr_template_setup_tenant on opd.emr_template_setup
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 2. opd.emr_template_override: Show and Required only (OQ-43-15). Keyed by section key, not by template version, so an
--    override survives a new version; an override whose key is not in the newest version is ignored by the app, not
--    deleted. A row equal to the template default is not stored (the app deletes it). A hidden section is never
--    required (BR12). Setup data, not clinical: a reset deletes rows, and platform.audit_event keeps the history.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_template_override (
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_emr_template_override_tenant references platform.tenant (id),
  template_key  text not null
                constraint ck_emr_template_override_template_key check (template_key in ('gp', 'dental', 'paeds', 'homeo')),
  section_key   text not null
                constraint ck_emr_template_override_section_key check (section_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  shown         boolean not null,
  required      boolean not null,
  created_at    timestamptz not null default now(),
  created_by    uuid not null,
  updated_at    timestamptz,
  updated_by    uuid,
  constraint pk_emr_template_override primary key (tenant_id, template_key, section_key),
  constraint ck_emr_template_override_hidden_not_required check (shown or not required)
);

alter table opd.emr_template_override enable row level security;
alter table opd.emr_template_override force row level security;

create policy p_emr_template_override_tenant on opd.emr_template_override
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 3. opd.emr_settings: one row per clinic, created by the first save; until then the application returns the defaults
--    (COLLAPSED, CASE_PAPER, ASSESSMENT). default_part is stored but only ASSESSMENT is allowed (OQ-43-16).
--    version is the optimistic lock (0 before the first save; the first save creates the row at version 0).
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_settings (
  tenant_id         uuid primary key default platform.current_tenant()
                    constraint fk_emr_settings_tenant references platform.tenant (id),
  section_panel     text not null default 'COLLAPSED'
                    constraint ck_emr_settings_section_panel check (section_panel in ('COLLAPSED', 'OPEN')),
  right_panel_first text not null default 'CASE_PAPER'
                    constraint ck_emr_settings_right_panel_first check (right_panel_first in ('CASE_PAPER', 'PAST_VISITS')),
  default_part      text not null default 'ASSESSMENT'
                    constraint ck_emr_settings_default_part check (default_part = 'ASSESSMENT'),
  created_at        timestamptz not null default now(),
  created_by        uuid not null,
  updated_at        timestamptz,
  updated_by        uuid,
  version           bigint not null default 0
                    constraint ck_emr_settings_version check (version >= 0)
);

alter table opd.emr_settings enable row level security;
alter table opd.emr_settings force row level security;

create policy p_emr_settings_tenant on opd.emr_settings
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 4. opd.emr_item_usage: how often one doctor used one master item on signed visits (ADR-019 D10).
--    No patient id, no visit id, no free text: the item is named by master + item_key only. item_key is the item's uuid
--    as lower-case text (complaints, investigations, advice, medicines) or the ICD-10 code (diagnoses); the check
--    refuses anything else, so typed text cannot be stored. No foreign key to the five masters (one column) and none to
--    platform.membership (cross-module); a row whose item left its master is ignored on read.
--    Written only by the sign step through an internal service (upsert); never deleted. created_at / updated_at only:
--    the doctor is doctor_membership_id, the recorder has no other actor.
--    Upsert:  insert ... on conflict (tenant_id, doctor_membership_id, master, item_key)
--             do update set use_count = use_count + 1, last_used_at = greatest(opd.emr_item_usage.last_used_at, excluded.last_used_at)
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_item_usage (
  tenant_id            uuid not null default platform.current_tenant()
                       constraint fk_emr_item_usage_tenant references platform.tenant (id),
  doctor_membership_id uuid not null,
  master               text not null
                       constraint ck_emr_item_usage_master check (master in
                         ('complaints', 'diagnoses', 'medicines', 'investigations', 'advice')),
  item_key             text not null
                       constraint ck_emr_item_usage_item_key_length check (char_length(item_key) between 1 and 40),
  use_count            integer not null default 1
                       constraint ck_emr_item_usage_use_count check (use_count >= 1),
  last_used_at         timestamptz not null,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz,
  constraint pk_emr_item_usage primary key (tenant_id, doctor_membership_id, master, item_key),
  constraint ck_emr_item_usage_item_key_shape check (
    case master
      when 'diagnoses' then item_key ~ '^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$'
      else item_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    end)
);

-- The ranking read: one doctor, one master, used items by count then recency.
create index if not exists ix_emr_item_usage_ranking
  on opd.emr_item_usage (tenant_id, doctor_membership_id, master, use_count desc, last_used_at desc);

alter table opd.emr_item_usage enable row level security;
alter table opd.emr_item_usage force row level security;

create policy p_emr_item_usage_tenant on opd.emr_item_usage
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 5. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_template_setup, opd.emr_template_override, opd.emr_settings, opd.emr_item_usage from sahaj_app;
revoke all on opd.emr_template_setup, opd.emr_template_override, opd.emr_settings, opd.emr_item_usage from public;
grant select, insert, update         on opd.emr_template_setup    to sahaj_app;
grant select, insert, update, delete on opd.emr_template_override to sahaj_app;
grant select, insert, update         on opd.emr_settings          to sahaj_app;
grant select, insert, update         on opd.emr_item_usage        to sahaj_app;

comment on table opd.emr_template_setup is
  'Per clinic and template revision counter for the EMR template override page (SAH-44). Optimistic lock: one revision per clinic and template. Created on the first change.';
comment on table opd.emr_template_override is
  'Per clinic Show and Required override of one EMR template section (SAH-44). Keyed by section key so it survives template versions. Setup data: a reset deletes rows; audit_event keeps the history.';
comment on table opd.emr_settings is
  'Per clinic EMR screen defaults (SAH-44): section panel, right panel first, default part (ASSESSMENT only). One row created on the first save; defaults apply before that.';
comment on table opd.emr_item_usage is
  'Per clinic and doctor use count of EMR master items on signed visits, for quick-add ranking (SAH-43). No patient data and no free text. Written only by the sign step; never deleted.';
