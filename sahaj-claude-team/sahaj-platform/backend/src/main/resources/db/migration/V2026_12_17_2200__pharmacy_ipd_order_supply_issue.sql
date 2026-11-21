-- SAH-80 WP-M (stories 80-13 to 80-15), migration M1. ADR-030 S14 "IPD", S12 (TP-4, TP-5), "Data needed" (WP-M).
-- New tables: pharmacy.ipd_order_supply (the row the dispense locks, per IPD order and version), pharmacy.ipd_issue (the issue slip) and
-- pharmacy.ipd_issue_line (one batch of one order on a slip, with its charge state). Tables only: no table of the ipd schema is changed, and
-- admission_id, order_id and the numbers are opaque ids and text (no cross-module foreign key). The IPD ports are no-ops until the human and
-- the IPD session approve them (DEC-072 Q2); a line saved while the charge port is not connected is CHARGE_PENDING.
-- Patient data: ipd_issue holds a patient name snapshot for the printed slip (ADR-030 S12; never in lists, the ledger, logs, audit or the
-- activity feed), the admission number, ward and bed text. Tenant isolated.
-- Tenant tables, forced row level security, no delete grant. ipd_order_supply and ipd_issue_line change in a guarded way; ipd_issue is
-- append-only (a slip is a document; a correction is a ward return).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.ipd_order_supply: one row per store, order and order version. The dispense locks it, so two pharmacists cannot dispense the
--    same order version (409 changed). Created when an order is first picked or typed.
-- ======================================================================================================================
create table if not exists pharmacy.ipd_order_supply (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null default platform.current_tenant()
                     constraint fk_ipd_order_supply_tenant references platform.tenant (id),
  unit_id          uuid not null,
  store_id         uuid not null,
  admission_id     uuid not null,
  order_id         uuid not null,
  order_version    integer not null
                     constraint ck_ipd_order_supply_version check (order_version >= 1),
  typed_qty        integer
                     constraint ck_ipd_order_supply_typed check (typed_qty between 1 and 999999),
  picked_by        uuid,
  picked_at        timestamptz,
  supplied_qty     integer not null default 0
                     constraint ck_ipd_order_supply_supplied check (supplied_qty >= 0),
  created_at       timestamptz not null default now(),
  created_by       uuid not null,
  updated_at       timestamptz,
  updated_by       uuid,
  version          bigint not null default 0,
  constraint uq_ipd_order_supply_tenant_id unique (tenant_id, id),
  constraint uq_ipd_order_supply_order unique (tenant_id, store_id, order_id, order_version),
  constraint fk_ipd_order_supply_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_ipd_order_supply_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint ck_ipd_order_supply_picked_pair check ((picked_by is null) = (picked_at is null)),
  constraint ck_ipd_order_supply_within_typed check (typed_qty is null or supplied_qty <= typed_qty)
);

-- The supply rows of one admission (patient card, discharge clearance) and the pick list of a store.
create index if not exists ix_ipd_order_supply_admission on pharmacy.ipd_order_supply (tenant_id, admission_id);

alter table pharmacy.ipd_order_supply enable row level security;
alter table pharmacy.ipd_order_supply force row level security;
create policy p_ipd_order_supply_select on pharmacy.ipd_order_supply for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ipd_order_supply_insert on pharmacy.ipd_order_supply for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ipd_order_supply_update on pharmacy.ipd_order_supply for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.ipd_order_supply from sahaj_app;
revoke all on pharmacy.ipd_order_supply from public;
grant select, insert, update on pharmacy.ipd_order_supply to sahaj_app;

create or replace function pharmacy.guard_ipd_order_supply_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.admission_id is distinct from old.admission_id
     or new.order_id is distinct from old.order_id or new.order_version is distinct from old.order_version
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'ipd order supply identity cannot change' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_ipd_order_supply_update() from public;
revoke all on function pharmacy.guard_ipd_order_supply_update() from sahaj_app;

create trigger trg_ipd_order_supply_guard_update before update on pharmacy.ipd_order_supply
  for each row execute function pharmacy.guard_ipd_order_supply_update();

comment on table pharmacy.ipd_order_supply is
  'Pharmacy-owned supply state of one IPD medicine order version at one store (SAH-80-13, ADR-030 S14): typed quantity, picked, supplied. The dispense locks this row (lock step 3), so two pharmacists cannot dispense the same order version. Opaque ids only (no foreign key to the ipd schema). Never deleted.';
comment on function pharmacy.guard_ipd_order_supply_update() is
  'IPD order supply guard: tenant, unit, store, admission, order, order version and creation stamp never change. SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. pharmacy.ipd_issue: the slip. Append-only. The slip number is taken from next_document_no('IPD_ISSUE_SLIP') after the balance locks.
-- ======================================================================================================================
create table if not exists pharmacy.ipd_issue (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                        constraint fk_ipd_issue_tenant references platform.tenant (id),
  unit_id             uuid not null,
  store_id            uuid not null,
  slip_no             varchar(16) not null
                        constraint ck_ipd_issue_slip_no check (char_length(btrim(slip_no)) between 1 and 16),
  admission_id        uuid not null,
  admission_no        varchar(40) not null
                        constraint ck_ipd_issue_admission_no check (char_length(btrim(admission_no)) between 1 and 40),
  -- Snapshot for the printed slip and the patient label. Patient data: never in lists, ledger, logs, audit or activity text.
  patient_name        varchar(80) not null
                        constraint ck_ipd_issue_patient_name check (char_length(btrim(patient_name)) between 1 and 80),
  ward_name           varchar(60) not null
                        constraint ck_ipd_issue_ward_name check (char_length(btrim(ward_name)) between 1 and 60),
  bed_label           varchar(30)
                        constraint ck_ipd_issue_bed_label check (char_length(btrim(bed_label)) between 1 and 30),
  take_home           boolean not null default false,
  sent_by             uuid not null,
  sent_at             timestamptz not null default now(),
  constraint uq_ipd_issue_tenant_id unique (tenant_id, id),
  constraint fk_ipd_issue_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_ipd_issue_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

create unique index if not exists uq_ipd_issue_slip_no on pharmacy.ipd_issue (tenant_id, unit_id, lower(slip_no));
-- The issues of a store, newest first (the IPD issues list and the day's dispatch).
create index if not exists ix_ipd_issue_store_sent on pharmacy.ipd_issue (tenant_id, store_id, sent_at desc);
-- The issues of one admission (patient card, discharge clearance).
create index if not exists ix_ipd_issue_admission on pharmacy.ipd_issue (tenant_id, admission_id);

alter table pharmacy.ipd_issue enable row level security;
alter table pharmacy.ipd_issue force row level security;
create policy p_ipd_issue_select on pharmacy.ipd_issue for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ipd_issue_insert on pharmacy.ipd_issue for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.ipd_issue from sahaj_app;
revoke all on pharmacy.ipd_issue from public;
grant select, insert on pharmacy.ipd_issue to sahaj_app;

create trigger trg_ipd_issue_no_update_delete before update or delete on pharmacy.ipd_issue
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_ipd_issue_no_truncate before truncate on pharmacy.ipd_issue
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.ipd_issue is
  'IPD issue slip (SAH-80-14, ADR-030 S12): medicines sent to a ward for an admission. Append-only, never deleted. Holds a patient name snapshot for the slip print (patient data: never logged, never in lists, the ledger, audit or activity text) and the admission number, ward and bed as text.';
comment on column pharmacy.ipd_issue.patient_name is
  'Name at the time of the issue, for the printed slip and labels only. Never logged, never in a list, report, export, ledger line or audit text.';

-- ======================================================================================================================
-- 3. pharmacy.ipd_issue_line: one row per batch picked by FEFO for an order. Final at save except the charge state, the charge
--    reference, the failure text and returned_qty (which only grows up to qty).
-- ======================================================================================================================
create table if not exists pharmacy.ipd_issue_line (
  id                            uuid primary key default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_ipd_issue_line_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  ipd_issue_id                  uuid not null,
  order_id                      uuid not null,
  order_version                 integer not null
                                  constraint ck_ipd_issue_line_order_version check (order_version >= 1),
  product_id                    uuid not null,
  batch_id                      uuid not null,
  qty                           integer not null
                                  constraint ck_ipd_issue_line_qty check (qty between 1 and 999999),
  returned_qty                  integer not null default 0
                                  constraint ck_ipd_issue_line_returned_qty check (returned_qty >= 0),
  -- The price per issue unit in rupees (the OQ-80-15 price) and the amount of the line in paise.
  price                         numeric(14,4) not null
                                  constraint ck_ipd_issue_line_price check (price >= 0),
  amount_paise                  bigint not null
                                  constraint ck_ipd_issue_line_amount check (amount_paise >= 0),
  charge_status                 text not null
                                  constraint ck_ipd_issue_line_charge_status check (charge_status in
                                    ('CHARGED', 'CHARGE_PENDING', 'CREDITED', 'CREDIT_PENDING', 'NOT_APPLICABLE')),
  charge_ref                    varchar(60)
                                  constraint ck_ipd_issue_line_charge_ref check (char_length(btrim(charge_ref)) between 1 and 60),
  -- Why the last attempt to post the charge failed (a short code or text from the port; never a name).
  charge_failure                varchar(200)
                                  constraint ck_ipd_issue_line_charge_failure check (char_length(btrim(charge_failure)) between 1 and 200),
  second_signer_membership_id   uuid,
  created_at                    timestamptz not null default now(),
  created_by                    uuid not null,
  updated_at                    timestamptz,
  updated_by                    uuid,
  version                       bigint not null default 0,
  constraint uq_ipd_issue_line_tenant_id unique (tenant_id, id),
  constraint uq_ipd_issue_line_batch unique (tenant_id, ipd_issue_id, order_id, batch_id),
  constraint fk_ipd_issue_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_ipd_issue_line_issue foreign key (tenant_id, ipd_issue_id) references pharmacy.ipd_issue (tenant_id, id),
  constraint fk_ipd_issue_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint ck_ipd_issue_line_returned_within_qty check (returned_qty <= qty),
  constraint ck_ipd_issue_line_charged_has_ref check (charge_status <> 'CHARGED' or charge_ref is not null),
  constraint ck_ipd_issue_line_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> created_by)
);

-- The lines of a slip are served by uq_ipd_issue_line_batch (tenant_id, ipd_issue_id, order_id, batch_id).
-- "Post waiting charges", the mode guard "Charge pending exists" and the charges-waiting list: oldest first.
create index if not exists ix_ipd_issue_line_charge_pending on pharmacy.ipd_issue_line (tenant_id, created_at)
  where charge_status = 'CHARGE_PENDING';
-- Batch foreign key and "issues of this batch".
create index if not exists ix_ipd_issue_line_batch on pharmacy.ipd_issue_line (tenant_id, batch_id);
-- The lines supplied for one order version (the pick list shows what is already supplied).
create index if not exists ix_ipd_issue_line_order on pharmacy.ipd_issue_line (tenant_id, order_id, order_version);

alter table pharmacy.ipd_issue_line enable row level security;
alter table pharmacy.ipd_issue_line force row level security;
create policy p_ipd_issue_line_select on pharmacy.ipd_issue_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_ipd_issue_line_insert on pharmacy.ipd_issue_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_ipd_issue_line_update on pharmacy.ipd_issue_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.ipd_issue_line from sahaj_app;
revoke all on pharmacy.ipd_issue_line from public;
grant select, insert, update on pharmacy.ipd_issue_line to sahaj_app;

create or replace function pharmacy.guard_ipd_issue_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_open text[] := array['charge_status', 'charge_ref', 'charge_failure', 'returned_qty', 'version', 'updated_at', 'updated_by'];
begin
  if tg_op = 'INSERT' then
    if new.returned_qty <> 0 then
      raise exception 'ipd issue line must start with nothing returned' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if (to_jsonb(new) - v_open) is distinct from (to_jsonb(old) - v_open) then
    raise exception 'ipd issue line is final once saved; only the charge state and the returned quantity change' using errcode = 'check_violation';
  end if;
  if new.returned_qty < old.returned_qty then
    raise exception 'ipd issue line returned quantity cannot go down' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_ipd_issue_line_write() from public;
revoke all on function pharmacy.guard_ipd_issue_line_write() from sahaj_app;

create trigger trg_ipd_issue_line_guard_write before insert or update on pharmacy.ipd_issue_line
  for each row execute function pharmacy.guard_ipd_issue_line_write();

comment on table pharmacy.ipd_issue_line is
  'One batch of one IPD order on an issue slip (SAH-80-14, ADR-030 "Data needed" WP-M): quantity, price, amount, charge state (CHARGED, CHARGE_PENDING, CREDITED, CREDIT_PENDING, NOT_APPLICABLE), charge reference and failure. Final at save except the charge state and reference, the failure text and returned_qty (only grows up to qty). Never deleted. No patient data.';
comment on function pharmacy.guard_ipd_issue_line_write() is
  'IPD issue line guard: starts with returned_qty 0; afterwards only charge_status, charge_ref, charge_failure, returned_qty (upwards only), version and update stamps may change. SQLSTATE 23514.';
