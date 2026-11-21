-- SAH-80 WP-M (stories 80-16, 80-17), migration M2. ADR-030 S12 (TP-5 credits), O-7, "Data needed" (WP-M).
-- New tables: pharmacy.ward_return (a ward sends medicine back: recorded WAITING, then accepted to Available or Quarantine, written off as
-- wastage, or not accepted; carries the credit state "Credit pending") and pharmacy.discharge_clearance (pharmacy's own information mark
-- that an admission has no returns waiting; written to no IPD table). Guards: pharmacy.guard_ward_return_write().
-- Tenant tables, forced row level security, no delete grant. admission ids stay on pharmacy.ipd_issue; these tables hold ids only, no name.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.ward_return
-- ======================================================================================================================
create table if not exists pharmacy.ward_return (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_ward_return_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  store_id                      uuid not null,
  ipd_issue_line_id             uuid not null,
  qty                           integer not null
                                  constraint ck_ward_return_qty check (qty between 1 and 999999),
  -- List ward-return.
  reason_key                    text not null
                                  constraint ck_ward_return_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  return_condition              text not null
                                  constraint ck_ward_return_condition check (return_condition in ('SEALED', 'OPENED', 'EMPTY_AMPOULE')),
  status                        text not null default 'WAITING'
                                  constraint ck_ward_return_status check (status in
                                    ('WAITING', 'ACCEPTED_AVAILABLE', 'ACCEPTED_QUARANTINE', 'WASTAGE', 'NOT_ACCEPTED')),
  -- "Not accepted" reason (list approval-reject, open point O-7).
  decision_reason_key           text
                                  constraint ck_ward_return_decision_reason check (decision_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  -- The credit for the ward return on the IPD running bill; null until a credit is due.
  credit_paise                  bigint
                                  constraint ck_ward_return_credit check (credit_paise >= 0),
  credit_status                 text not null default 'NOT_APPLICABLE'
                                  constraint ck_ward_return_credit_status check (credit_status in
                                    ('CHARGED', 'CHARGE_PENDING', 'CREDITED', 'CREDIT_PENDING', 'NOT_APPLICABLE')),
  credit_ref                    varchar(60)
                                  constraint ck_ward_return_credit_ref check (char_length(btrim(credit_ref)) between 1 and 60),
  credit_failure                varchar(200)
                                  constraint ck_ward_return_credit_failure check (char_length(btrim(credit_failure)) between 1 and 200),
  recorded_by                   uuid not null,
  recorded_at                   timestamptz not null default now(),
  decided_by                    uuid,
  decided_at                    timestamptz,
  second_signer_membership_id   uuid,
  created_at                    timestamptz not null default now(),
  updated_at                    timestamptz,
  updated_by                    uuid,
  version                       bigint not null default 0,
  constraint uq_ward_return_tenant_id unique (tenant_id, id),
  constraint fk_ward_return_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_ward_return_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_ward_return_issue_line foreign key (tenant_id, ipd_issue_line_id) references pharmacy.ipd_issue_line (tenant_id, id),
  constraint ck_ward_return_decided_pair check ((decided_by is null) = (decided_at is null)),
  -- WAITING has no decision; every other status has one.
  constraint ck_ward_return_decided_shape check ((status = 'WAITING') = (decided_by is null)),
  constraint ck_ward_return_credited_has_ref check (credit_status <> 'CREDITED' or credit_ref is not null),
  constraint ck_ward_return_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> decided_by)
);

-- Returns waiting for the pharmacist (the ward returns list, the discharge clearance, the mode guard).
create index if not exists ix_ward_return_waiting on pharmacy.ward_return (tenant_id, store_id, recorded_at) where status = 'WAITING';
-- Credits waiting to be posted ("Credit pending"), oldest first.
create index if not exists ix_ward_return_credit_pending on pharmacy.ward_return (tenant_id, recorded_at) where credit_status = 'CREDIT_PENDING';
-- The returns of an issue line (the returnable quantity) and the foreign key.
create index if not exists ix_ward_return_issue_line on pharmacy.ward_return (tenant_id, ipd_issue_line_id);

alter table pharmacy.ward_return enable row level security;
alter table pharmacy.ward_return force row level security;
create policy p_ward_return_select on pharmacy.ward_return for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ward_return_insert on pharmacy.ward_return for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ward_return_update on pharmacy.ward_return for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.ward_return from sahaj_app;
revoke all on pharmacy.ward_return from public;
grant select, insert, update on pharmacy.ward_return to sahaj_app;

-- Guard: starts WAITING; identity and the recorded facts never change; the decision is made once (WAITING to a decided status); after
-- that only the credit state (credit_status, credit_ref, credit_failure) and version and stamps change.
create or replace function pharmacy.guard_ward_return_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.status <> 'WAITING' then
      raise exception 'ward return must start waiting' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.ipd_issue_line_id is distinct from old.ipd_issue_line_id
     or new.qty is distinct from old.qty or new.reason_key is distinct from old.reason_key
     or new.return_condition is distinct from old.return_condition or new.recorded_by is distinct from old.recorded_by
     or new.recorded_at is distinct from old.recorded_at or new.created_at is distinct from old.created_at then
    raise exception 'ward return facts cannot change' using errcode = 'check_violation';
  end if;
  if old.status <> 'WAITING' then
    if new.status is distinct from old.status or new.decided_by is distinct from old.decided_by
       or new.decided_at is distinct from old.decided_at or new.decision_reason_key is distinct from old.decision_reason_key
       or new.second_signer_membership_id is distinct from old.second_signer_membership_id
       or new.credit_paise is distinct from old.credit_paise then
      raise exception 'ward return is decided (%)', old.status using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_ward_return_write() from public;
revoke all on function pharmacy.guard_ward_return_write() from sahaj_app;

create trigger trg_ward_return_guard_write before insert or update on pharmacy.ward_return
  for each row execute function pharmacy.guard_ward_return_write();

comment on table pharmacy.ward_return is
  'Medicine a ward sends back (SAH-80-16, ADR-030 S12): WAITING, then ACCEPTED_AVAILABLE, ACCEPTED_QUARANTINE, WASTAGE or NOT_ACCEPTED, with the credit state (Credit pending when the IPD charge port is not connected). Never deleted. Ids and quantities only; no patient name.';
comment on function pharmacy.guard_ward_return_write() is
  'Ward return guard: starts WAITING; recorded facts never change; the decision is made once; afterwards only the credit state, version and stamps change. SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. pharmacy.discharge_clearance: pharmacy's own mark "Cleared by <name>, <time>" for an admission whose discharge has started. Information
--    only (TP-7 is not a gate, OQ-80-33): it writes nothing to IPD. One mark per admission. Append-only.
-- ======================================================================================================================
create table if not exists pharmacy.discharge_clearance (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                    constraint fk_discharge_clearance_tenant references platform.tenant (id),
  unit_id         uuid not null,
  admission_id    uuid not null,
  cleared_by      uuid not null,
  cleared_at      timestamptz not null default now(),
  -- List clearance; required by the service only when returns are still waiting.
  reason_key      text
                    constraint ck_discharge_clearance_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  constraint uq_discharge_clearance_admission unique (tenant_id, admission_id),
  constraint fk_discharge_clearance_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id)
);

alter table pharmacy.discharge_clearance enable row level security;
alter table pharmacy.discharge_clearance force row level security;
create policy p_discharge_clearance_select on pharmacy.discharge_clearance for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_discharge_clearance_insert on pharmacy.discharge_clearance for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.discharge_clearance from sahaj_app;
revoke all on pharmacy.discharge_clearance from public;
grant select, insert on pharmacy.discharge_clearance to sahaj_app;

create trigger trg_discharge_clearance_no_update_delete before update or delete on pharmacy.discharge_clearance
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_discharge_clearance_no_truncate before truncate on pharmacy.discharge_clearance
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.discharge_clearance is
  'Pharmacy discharge clearance mark (SAH-80-17, ADR-030 S12): who cleared an admission and when. Information only, no IPD table is written. One row per admission (the admission id is opaque). Append-only, never deleted. No patient name.';
