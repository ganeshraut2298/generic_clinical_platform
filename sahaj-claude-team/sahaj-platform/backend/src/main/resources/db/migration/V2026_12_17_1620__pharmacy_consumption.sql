-- SAH-79 WP-G (story 79-16), migration G3. ADR-029 P12 "Consumption", P14 (the IPD reader port: an admission id, never a name), OQ-79-22 to
-- OQ-79-24, DEC-072 answer 2 (SAH-80 posts CHARGE_PENDING entries), "Data needed" WP-G.
-- New objects only: tables pharmacy.consumption and pharmacy.consumption_line. Depends on WP-D only (batch, store). Tenant tables, forced row
-- level security, no delete grant, **no update grant**: a consumption entry is never edited (the ledger lines carry the stock effect). When
-- SAH-80 later needs to mark an entry as charged, its own migration widens the charge_status check and grants the one update it needs.
-- stock_audit_line_id is a plain uuid now; WP-H adds its foreign key. Personal data: only admission_id (an id) and, for "Other", a typed
-- name of the person who ordered (2 to 80 characters), which is never logged, never in a report or an export.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.consumption
-- ======================================================================================================================
create table if not exists pharmacy.consumption (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                              constraint fk_consumption_tenant references platform.tenant (id),
  unit_id                   uuid not null,
  -- The store the stock leaves, and the store the cost is booked to (OQ-79-24c: the same store).
  store_id                  uuid not null,
  consumption_type          text not null
                              constraint ck_consumption_type check (consumption_type in ('GENERAL', 'PATIENT')),
  -- GENERAL: list consumption-purpose. A .other key needs a note: service rule.
  purpose_key               text
                              constraint ck_consumption_purpose check (purpose_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                      text
                              constraint ck_consumption_note check (char_length(note) between 1 and 300),
  -- PATIENT: the admission (an id from ipd.api.AdmittedPatients: no foreign key, another module's id; never a name).
  admission_id              uuid,
  -- PATIENT: who ordered it: a membership id of the option list, or "Other" with a typed name. Not both.
  ordered_by_membership_id  uuid,
  ordered_by_name           text
                              constraint ck_consumption_ordered_by_name check (char_length(ordered_by_name) between 2 and 80),
  -- NOT_APPLICABLE for a general entry; CHARGE_PENDING until SAH-80 posts it (OQ-79-23).
  charge_status             text not null
                              constraint ck_consumption_charge_status check (charge_status in ('NOT_APPLICABLE', 'CHARGE_PENDING')),
  -- The stock-audit count line this entry corrects ("Post as consumption instead", 79-17). FK added by WP-H.
  stock_audit_line_id       uuid,
  second_signer_membership_id uuid,
  recorded_at               timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who recorded it.
  recorded_by               uuid not null,
  constraint uq_consumption_tenant_id unique (tenant_id, id),
  constraint fk_consumption_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_consumption_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  -- The two types differ in what they carry: a general entry has a purpose and no patient; a patient entry has an admission and is charged.
  constraint ck_consumption_shape check (
    (consumption_type = 'GENERAL' and purpose_key is not null and admission_id is null and ordered_by_membership_id is null
       and ordered_by_name is null and charge_status = 'NOT_APPLICABLE')
    or (consumption_type = 'PATIENT' and purpose_key is null and admission_id is not null and charge_status = 'CHARGE_PENDING')),
  constraint ck_consumption_ordered_by_one check (ordered_by_membership_id is null or ordered_by_name is null),
  constraint ck_consumption_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> recorded_by)
);

-- The entries of a store, newest first (the consumption list) and the ward-consumption report by store and date range.
create index if not exists ix_consumption_store on pharmacy.consumption (tenant_id, store_id, recorded_at desc);
-- The charge worklist SAH-80 reads: patient entries still waiting to be posted, oldest first.
create index if not exists ix_consumption_charge_pending on pharmacy.consumption (tenant_id, recorded_at)
  where charge_status = 'CHARGE_PENDING';
-- The entries of one admission (patient-wise view, the IPD discharge check).
create index if not exists ix_consumption_admission on pharmacy.consumption (tenant_id, admission_id) where admission_id is not null;

alter table pharmacy.consumption enable row level security;
alter table pharmacy.consumption force row level security;
create policy p_consumption_select on pharmacy.consumption for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_consumption_insert on pharmacy.consumption for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.consumption from sahaj_app;
revoke all on pharmacy.consumption from public;
grant select, insert on pharmacy.consumption to sahaj_app;

comment on table pharmacy.consumption is
  'Stock used on a ward (SAH-79-16, ADR-029 P12): GENERAL (a purpose, not billed) or PATIENT (an admission id, CHARGE_PENDING until SAH-80 posts it). Insert only: never edited or deleted. Holds an admission id, never a patient name; the typed ordered-by name is never logged.';
comment on column pharmacy.consumption.admission_id is
  'Opaque admission id from the IPD reader port (no foreign key, another module). Never joined to a name in a report or export.';
comment on column pharmacy.consumption.ordered_by_name is
  'Typed name of the person who ordered the stock when "Other" is chosen (OQ-79-24b). Never logged, never in a list, report or export.';

-- ======================================================================================================================
-- 2. pharmacy.consumption_line: one row per batch picked by FEFO. Insert only.
-- ======================================================================================================================
create table if not exists pharmacy.consumption_line (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                    constraint fk_consumption_line_tenant references platform.tenant (id),
  unit_id         uuid not null,
  consumption_id  uuid not null,
  product_id      uuid not null,
  batch_id        uuid not null,
  quantity        integer not null
                    constraint ck_consumption_line_quantity check (quantity between 1 and 999999),
  created_at      timestamptz not null default now(),
  created_by      uuid not null,
  constraint uq_consumption_line_tenant_id unique (tenant_id, id),
  constraint uq_consumption_line_batch unique (tenant_id, consumption_id, batch_id),
  constraint fk_consumption_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_consumption_line_consumption foreign key (tenant_id, consumption_id) references pharmacy.consumption (tenant_id, id),
  constraint fk_consumption_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id)
);

-- The lines of one entry are served by the unique key (tenant_id, consumption_id, batch_id).
-- Consumption of a product or a batch (batch history, product stock card) and the batch FK.
create index if not exists ix_consumption_line_batch on pharmacy.consumption_line (tenant_id, batch_id);
create index if not exists ix_consumption_line_product on pharmacy.consumption_line (tenant_id, product_id);

alter table pharmacy.consumption_line enable row level security;
alter table pharmacy.consumption_line force row level security;
create policy p_consumption_line_select on pharmacy.consumption_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_consumption_line_insert on pharmacy.consumption_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.consumption_line from sahaj_app;
revoke all on pharmacy.consumption_line from public;
grant select, insert on pharmacy.consumption_line to sahaj_app;

comment on table pharmacy.consumption_line is
  'One batch consumed in a consumption entry, quantity in issue units (SAH-79-16). Select and insert only. Never deleted.';
