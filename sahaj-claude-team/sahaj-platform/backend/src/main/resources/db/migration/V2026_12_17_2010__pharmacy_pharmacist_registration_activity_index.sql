-- SAH-80 WP-L (story 80-19), migration L2. ADR-030 S10 (activity feed), "Data needed" (WP-L).
--   1. New table pharmacy.pharmacist_registration: one pharmacist registration number and validity per team member of the unit.
--   2. Licences: nothing to add. The drug licence (number, validity) is on pharmacy.unit since V2026_12_17_1010; the Schedule X licence
--      number and validity were added by V2026_12_17_1800.
--   3. document_event.event: no check to widen (V2026_12_17_1300 keeps doc_type and event as patterns), so the new event names
--      (ALLERGY_OVERRIDE, H1_OVERRIDE, DISCOUNT_REQUESTED, DISCOUNT_DECIDED, BILL_CANCELLED, SALES_RETURN, SECOND_SIGNATURE) need no change.
--   4. One index on document_event for the activity feed (GET /activity and the activity-log report): the feed reads the events of a store
--      in time order across all documents, which the only existing index (tenant_id, doc_type, doc_id, occurred_at) cannot serve. The reason
--      is structural, not a measurement. The table is new in this release and small when this file runs, so a plain create index is safe here.
-- membership_id is an opaque uuid (no cross-module foreign key); the team member is checked through TeamMemberPort.

set local lock_timeout = '30s';

create table if not exists pharmacy.pharmacist_registration (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                      constraint fk_pharmacist_registration_tenant references platform.tenant (id),
  unit_id           uuid not null,
  membership_id     uuid not null,
  registration_no   varchar(40) not null
                      constraint ck_pharmacist_registration_no check (char_length(btrim(registration_no)) between 1 and 40),
  valid_till        date,
  created_at        timestamptz not null default now(),
  created_by        uuid not null,
  updated_at        timestamptz,
  updated_by        uuid,
  version           bigint not null default 0,
  constraint uq_pharmacist_registration_member unique (tenant_id, unit_id, membership_id),
  constraint fk_pharmacist_registration_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);
-- The member lookup is served by uq_pharmacist_registration_member. The expiring-soon read of the compliance screen scans the few rows
-- of a unit (a unit has tens of pharmacists at most), so no further index.

alter table pharmacy.pharmacist_registration enable row level security;
alter table pharmacy.pharmacist_registration force row level security;
create policy p_pharmacist_registration_select on pharmacy.pharmacist_registration for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_pharmacist_registration_insert on pharmacy.pharmacist_registration for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_pharmacist_registration_update on pharmacy.pharmacist_registration for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.pharmacist_registration from sahaj_app;
revoke all on pharmacy.pharmacist_registration from public;
grant select, insert, update on pharmacy.pharmacist_registration to sahaj_app;

comment on table pharmacy.pharmacist_registration is
  'Pharmacist registration number and validity of a team member (SAH-80-19, ADR-030 "Data needed" WP-L). One row per member of the unit, updated in place, never deleted. The number is shown on the compliance screen and printed where the contract says; never logged.';

-- The activity feed: events of a store in time order.
create index if not exists ix_document_event_store_time on pharmacy.document_event (tenant_id, store_id, occurred_at desc);
