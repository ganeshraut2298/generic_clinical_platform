-- SAH-79 WP-G (story 79-14), migration G1. ADR-029 P12 "Return to Central Store", "Damage report", P7 (Schedule X), P9 (numbers: RET and DMG
-- are taken when the document is created: there is no draft), P11 (lists store-return and damage), OQ-79-27, "Data needed" WP-G.
-- New objects only: tables pharmacy.store_return and pharmacy.damage_report with guard functions and triggers, plus the foreign keys and
-- unique keys that tie return_item (migration F3) to them. Depends on F3 (return_item). Tenant tables, forced row level security, no delete
-- grant. The stock effects (AVAILABLE to PENDING_RETURN, AVAILABLE to QUARANTINE, write-off) are ledger lines written by the service.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.store_return: a store sends back stock to the Central Store. One batch, one quantity. Insert only: what was returned never
--    changes; the acceptance lives on its return_item (F3).
-- ======================================================================================================================
create table if not exists pharmacy.store_return (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_store_return_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  -- RET number, taken at creation (no draft); doc_no is the text rendered then.
  doc_seq                       bigint not null
                                  constraint ck_store_return_doc_seq check (doc_seq >= 1),
  doc_no                        text not null
                                  constraint ck_store_return_doc_no check (char_length(doc_no) between 1 and 16),
  -- The returning store (the units move AVAILABLE to PENDING_RETURN there).
  store_id                      uuid not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  quantity                      integer not null
                                  constraint ck_store_return_quantity check (quantity between 1 and 999999),
  -- List store-return; a .other key needs a note: service rule.
  reason_key                    text not null
                                  constraint ck_store_return_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                          text
                                  constraint ck_store_return_note check (char_length(note) between 1 and 300),
  second_signer_membership_id   uuid,
  created_at                    timestamptz not null default now(),
  -- Membership id (opaque uuid) of the person who returned it.
  created_by                    uuid not null,
  constraint uq_store_return_tenant_id unique (tenant_id, id),
  constraint uq_store_return_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_store_return_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_store_return_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_store_return_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_store_return_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_store_return_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> created_by)
);

-- The returns of a store, newest first (the returns list) and the store FK.
create index if not exists ix_store_return_store on pharmacy.store_return (tenant_id, store_id, created_at desc);
-- The returns of a batch and the batch FK.
create index if not exists ix_store_return_batch on pharmacy.store_return (tenant_id, batch_id);

alter table pharmacy.store_return enable row level security;
alter table pharmacy.store_return force row level security;
create policy p_store_return_select on pharmacy.store_return for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_store_return_insert on pharmacy.store_return for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.store_return from sahaj_app;
revoke all on pharmacy.store_return from public;
grant select, insert on pharmacy.store_return to sahaj_app;

comment on table pharmacy.store_return is
  'Return of stock from a store to the Central Store (SAH-79-14, ADR-029 P12): RET number, one batch, one quantity, a reason. Insert only; the acceptance is the return_item that points here. Never deleted. No patient data.';

-- ======================================================================================================================
-- 2. pharmacy.damage_report: damaged or expired stock reported by a store. The units go to QUARANTINE at once (a Quarantine batch is
--    reported without a move); a WRITE_OFF approval request decides what happens next. Level 0 applies the chosen outcome at once.
-- ======================================================================================================================
create table if not exists pharmacy.damage_report (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_damage_report_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  doc_seq                       bigint not null
                                  constraint ck_damage_report_doc_seq check (doc_seq >= 1),
  doc_no                        text not null
                                  constraint ck_damage_report_doc_no check (char_length(doc_no) between 1 and 16),
  store_id                      uuid not null,
  product_id                    uuid not null,
  batch_id                      uuid not null,
  -- The state the units were reported from.
  from_state                    text not null
                                  constraint ck_damage_report_from_state check (from_state in ('AVAILABLE', 'QUARANTINE')),
  quantity                      integer not null
                                  constraint ck_damage_report_quantity check (quantity between 1 and 999999),
  -- List damage; a .other key needs a note: service rule.
  reason_key                    text not null
                                  constraint ck_damage_report_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  note                          text
                                  constraint ck_damage_report_note check (char_length(note) between 1 and 300),
  -- What the reporter asked for if approved: write off at the store, or send to the Central Store.
  after_approval                text not null
                                  constraint ck_damage_report_after_approval check (after_approval in ('WRITE_OFF', 'SEND_TO_CS')),
  status                        text not null default 'WAITING_APPROVAL'
                                  constraint ck_damage_report_status check (status in
                                    ('WAITING_APPROVAL', 'WRITTEN_OFF', 'SENT_TO_CS', 'RELEASED', 'KEPT_IN_QUARANTINE')),
  -- The outcome the HOD chose when rejecting (RELEASED or KEPT_IN_QUARANTINE); null otherwise.
  reject_outcome                text
                                  constraint ck_damage_report_reject_outcome check (reject_outcome in ('RELEASE_TO_AVAILABLE', 'KEEP_IN_QUARANTINE')),
  approval_request_id           uuid,
  second_signer_membership_id   uuid,
  decided_at                    timestamptz,
  created_at                    timestamptz not null default now(),
  created_by                    uuid not null,
  updated_at                    timestamptz,
  updated_by                    uuid,
  version                       bigint not null default 0,
  constraint uq_damage_report_tenant_id unique (tenant_id, id),
  constraint uq_damage_report_doc_no unique (tenant_id, unit_id, doc_no),
  constraint uq_damage_report_doc_seq unique (tenant_id, unit_id, doc_seq),
  constraint fk_damage_report_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_damage_report_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_damage_report_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_damage_report_request foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint ck_damage_report_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> created_by),
  -- The final status follows the request and the choice: write-off or send (approved or level 0), release or keep (rejected only).
  constraint ck_damage_report_written_off check (status <> 'WRITTEN_OFF' or after_approval = 'WRITE_OFF'),
  constraint ck_damage_report_sent check (status <> 'SENT_TO_CS' or after_approval = 'SEND_TO_CS'),
  constraint ck_damage_report_rejected check (
    (status in ('RELEASED', 'KEPT_IN_QUARANTINE')) = (reject_outcome is not null)
    and (status not in ('RELEASED', 'KEPT_IN_QUARANTINE') or approval_request_id is not null)),
  constraint ck_damage_report_rejected_matches check (
    (status = 'RELEASED' and reject_outcome = 'RELEASE_TO_AVAILABLE')
    or (status = 'KEPT_IN_QUARANTINE' and reject_outcome = 'KEEP_IN_QUARANTINE')
    or status not in ('RELEASED', 'KEPT_IN_QUARANTINE')),
  constraint ck_damage_report_decided check ((status = 'WAITING_APPROVAL') = (decided_at is null))
);

-- The reports of a store, newest first (the damage list) and the store FK.
create index if not exists ix_damage_report_store on pharmacy.damage_report (tenant_id, store_id, created_at desc);
-- Reports waiting for a decision (the approver's inbox reads the approval request; this serves "waiting reports of this batch" before a new
-- report and the returns-and-write-offs report).
create index if not exists ix_damage_report_batch on pharmacy.damage_report (tenant_id, batch_id, status);

alter table pharmacy.damage_report enable row level security;
alter table pharmacy.damage_report force row level security;
create policy p_damage_report_select on pharmacy.damage_report for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_damage_report_insert on pharmacy.damage_report for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_damage_report_update on pharmacy.damage_report for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.damage_report from sahaj_app;
revoke all on pharmacy.damage_report from public;
grant select, insert, update on pharmacy.damage_report to sahaj_app;

-- Insert: WAITING_APPROVAL, or (level 0) the outcome applied at once: WRITTEN_OFF or SENT_TO_CS; never RELEASED or KEPT_IN_QUARANTINE
-- (those come only from a rejection). Update: only WAITING_APPROVAL moves, to any of the four final statuses; what was reported never changes.
create or replace function pharmacy.guard_damage_report_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status in ('RELEASED', 'KEPT_IN_QUARANTINE') then
      raise exception 'damage report cannot start as released or kept' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if old.status <> 'WAITING_APPROVAL' then
    raise exception 'damage report is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.doc_seq is distinct from old.doc_seq or new.doc_no is distinct from old.doc_no
     or new.store_id is distinct from old.store_id or new.product_id is distinct from old.product_id
     or new.batch_id is distinct from old.batch_id or new.from_state is distinct from old.from_state
     or new.quantity is distinct from old.quantity or new.reason_key is distinct from old.reason_key
     or new.note is distinct from old.note or new.after_approval is distinct from old.after_approval
     or new.second_signer_membership_id is distinct from old.second_signer_membership_id
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'damage report cannot change once saved' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_damage_report_write() from public;
revoke all on function pharmacy.guard_damage_report_write() from sahaj_app;
create trigger trg_damage_report_guard_write before insert or update on pharmacy.damage_report
  for each row execute function pharmacy.guard_damage_report_write();

comment on table pharmacy.damage_report is
  'Damaged or expired stock reported by a store (SAH-79-14, ADR-029 P12): DMG number, one batch, a quantity, the reporter''s wish after approval (write off or send to the Central Store). WAITING_APPROVAL moves once to WRITTEN_OFF, SENT_TO_CS, RELEASED or KEPT_IN_QUARANTINE. Never deleted; all four are final.';

-- ======================================================================================================================
-- 3. Tie return_item (F3) to its sources. The columns existed as plain uuid; no row can violate the new foreign keys (every existing
--    row has these columns null: WP-G is the first writer). One item per store return and per damage report (the contract answers one).
-- ======================================================================================================================
do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'fk_return_item_store_return' and conrelid = 'pharmacy.return_item'::regclass) then
    alter table pharmacy.return_item
      add constraint fk_return_item_store_return foreign key (tenant_id, store_return_id) references pharmacy.store_return (tenant_id, id);
  end if;
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'fk_return_item_damage_report' and conrelid = 'pharmacy.return_item'::regclass) then
    alter table pharmacy.return_item
      add constraint fk_return_item_damage_report foreign key (tenant_id, damage_report_id) references pharmacy.damage_report (tenant_id, id);
  end if;
end
$$;

create unique index if not exists uq_return_item_store_return on pharmacy.return_item (tenant_id, store_return_id)
  where store_return_id is not null;
create unique index if not exists uq_return_item_damage_report on pharmacy.return_item (tenant_id, damage_report_id)
  where damage_report_id is not null;
