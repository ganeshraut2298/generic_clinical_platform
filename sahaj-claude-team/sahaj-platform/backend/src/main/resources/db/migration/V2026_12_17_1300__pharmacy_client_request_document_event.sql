-- SAH-79 WP-D (stories 79-1 to 79-5, work package D), migration D1. ADR-029 P5 (idempotency), P9 (history), "Data needed" D1.
-- New objects only: function pharmacy.deny_append_only_mutation(), tables pharmacy.client_request and pharmacy.document_event.
-- Both tables are tenant tables with forced row level security and no delete grant. document_event is append-only (select and insert,
-- deny triggers). client_request is insert-only for the app too (a replay reads it; nothing ever changes it).
-- Nothing is dropped, renamed or updated. Out-of-order Flyway is off: SAH-79 WP-D takes V2026_12_17_1300 to 1390.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. Shared deny trigger function for every append-only pharmacy table (document_event, stock_ledger, approval_decision, later
--    vendor_payable_entry). SQLSTATE 42501 like platform.deny_audit_mutation(). Fixed search_path; no execute for public or sahaj_app
--    (a trigger function needs no execute right to fire).
-- ======================================================================================================================
create or replace function pharmacy.deny_append_only_mutation()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  raise exception '% is append-only (% refused)', tg_table_name, tg_op using errcode = 'insufficient_privilege';
end;
$$;
revoke all on function pharmacy.deny_append_only_mutation() from public;
revoke all on function pharmacy.deny_append_only_mutation() from sahaj_app;

comment on function pharmacy.deny_append_only_mutation() is
  'SAH-79 (ADR-029 P3, P6, P9): trigger function that refuses UPDATE, DELETE and TRUNCATE on an append-only pharmacy table (SQLSTATE 42501, message "<table> is append-only (<op> refused)").';

-- ======================================================================================================================
-- 2. pharmacy.client_request: idempotency of every create and post (ADR-029 P5, existing rule 10). Inserted in the same transaction as
--    the action it protects. The same key from the same caller answers the first result; the same key from another caller is
--    409 client-key-reused (the service looks the key up by (tenant_id, client_key), index below).
-- ======================================================================================================================
create table if not exists pharmacy.client_request (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null default platform.current_tenant()
                   constraint fk_client_request_tenant references platform.tenant (id),
  unit_id        uuid not null,
  membership_id  uuid not null,
  client_key     uuid not null,
  operation      text not null
                   constraint ck_client_request_operation check (char_length(operation) between 1 and 60),
  subject_id     uuid not null,
  created_at     timestamptz not null default now(),
  constraint uq_client_request_key unique (tenant_id, membership_id, client_key),
  constraint fk_client_request_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

-- "Was this key used by anybody in this tenant?" (another caller: 409 client-key-reused).
create index if not exists ix_client_request_key on pharmacy.client_request (tenant_id, client_key);

alter table pharmacy.client_request enable row level security;
alter table pharmacy.client_request force row level security;
create policy p_client_request_select on pharmacy.client_request for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_client_request_insert on pharmacy.client_request for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.client_request from sahaj_app;
revoke all on pharmacy.client_request from public;
grant select, insert on pharmacy.client_request to sahaj_app;

comment on table pharmacy.client_request is
  'Idempotency keys of Phase 2 creates and posts (SAH-79, ADR-029 P5). One row per accepted clientKey, written in the same transaction as the action. Select and insert only, never deleted. membership_id is an opaque uuid (no cross-module key). subject_id is the document the first result created or changed.';
comment on column pharmacy.client_request.operation is
  'Operation name of the first call (for example pharmacyPostOpeningStock). A replay with another operation is client-key-reused.';

-- ======================================================================================================================
-- 3. pharmacy.document_event: the History card of every document (BR-26, ADR-029 P9). Append-only. doc_type and event are patterns,
--    not lists, so a later work package adds its own types without widening a check (the services own the vocabulary).
-- ======================================================================================================================
create table if not exists pharmacy.document_event (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_document_event_tenant references platform.tenant (id),
  unit_id               uuid not null,
  doc_type              text not null
                          constraint ck_document_event_doc_type check (doc_type ~ '^[A-Z][A-Z_]{1,39}$'),
  doc_id                uuid not null,
  event                 text not null
                          constraint ck_document_event_event check (event ~ '^[A-Z][A-Z_]{1,39}$'),
  store_id              uuid not null,
  actor_membership_id   uuid not null,
  acting_as             text
                          constraint ck_document_event_acting_as check (acting_as in ('HOD', 'DELEGATE', 'OWNER', 'ESCALATED')),
  occurred_at           timestamptz not null default clock_timestamp(),
  line_id               uuid,
  qty_before            integer
                          constraint ck_document_event_qty_before check (qty_before >= 0),
  qty_after             integer
                          constraint ck_document_event_qty_after check (qty_after >= 0),
  reason_key            text
                          constraint ck_document_event_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                  text
                          constraint ck_document_event_note check (char_length(note) between 1 and 300),
  constraint fk_document_event_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_document_event_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

-- History card of one document, in time order.
create index if not exists ix_document_event_doc on pharmacy.document_event (tenant_id, doc_type, doc_id, occurred_at);

alter table pharmacy.document_event enable row level security;
alter table pharmacy.document_event force row level security;
create policy p_document_event_select on pharmacy.document_event for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_document_event_insert on pharmacy.document_event for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.document_event from sahaj_app;
revoke all on pharmacy.document_event from public;
grant select, insert on pharmacy.document_event to sahaj_app;

create trigger trg_document_event_no_update_delete before update or delete on pharmacy.document_event
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_document_event_no_truncate before truncate on pharmacy.document_event
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.document_event is
  'History of pharmacy documents (SAH-79, ADR-029 P9, BR-26): one row per event (CREATED, SUBMITTED, DECIDED, DISPATCHED, RECEIVED, CANCELLED ...) with who, in which capacity, when, and the quantities and reason where relevant. Append-only: select and insert only, triggers refuse update, delete and truncate. Never deleted. No patient name, mobile or ABHA number; the note is a short staff note, never logged.';
comment on column pharmacy.document_event.occurred_at is
  'Database clock at the insert (clock_timestamp), so events of one document are in lock order even when transactions started in another order.';
comment on column pharmacy.document_event.actor_membership_id is
  'Membership id (opaque uuid, no foreign key). The person who acted; an approval event also sets acting_as.';
