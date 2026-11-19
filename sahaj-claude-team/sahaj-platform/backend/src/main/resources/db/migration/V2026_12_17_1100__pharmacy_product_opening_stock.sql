-- SAH-78 WP-B (stories 78-5, 78-6, 78-7), migration B3 of 8. ADR-028 D3, D10, D12, "Data needed".
-- Tables: pharmacy.product, pharmacy.product_store_setting, pharmacy.opening_stock_import, pharmacy.opening_stock_line,
-- pharmacy.opening_stock_line_issue. All new, all tenant tables with forced row level security, no delete grant anywhere.
-- Opening stock is STAGED and approved here; nothing is posted to a stock ledger (the first SAH-79 story posts it).
-- The foreign key product.preferred_vendor_id is added in migration C1 (the vendor table does not exist yet).
-- Money: line value is bigint paise; unit rates (mrp, purchase_rate) are numeric(14,4) rupees (ADR-028 D10, DEC-063 OQ-PHI-21).

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.product: the unit's product list. ADOPTED rows link to the global catalogue and hold no global field (a catalogue
--    correction reaches every adopter at once). OWN rows hold their own copy of the global field set. One check makes the two
--    kinds exclusive. Global fields are never copied.
-- ======================================================================================================================
create table if not exists pharmacy.product (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null default platform.current_tenant()
                           constraint fk_product_tenant references platform.tenant (id),
  unit_id                uuid not null,
  kind                   text not null
                           constraint ck_product_kind check (kind in ('ADOPTED', 'OWN')),
  catalogue_product_id   uuid
                           constraint fk_product_catalogue references pharmacy.catalogue_product (id),
  code                   text
                           constraint ck_product_code check (code ~ '^U-[A-Z0-9]{3}-[0-9]{4,}$'),
  name                   text constraint ck_product_name check (char_length(name) between 1 and 80),
  salt                   text constraint ck_product_salt check (char_length(salt) between 1 and 80),
  strength               text constraint ck_product_strength check (char_length(strength) between 1 and 80),
  form                   text constraint ck_product_form check (char_length(form) between 1 and 30),
  manufacturer           text constraint ck_product_manufacturer check (char_length(manufacturer) between 1 and 80),
  made_in_house          boolean not null default false,
  hsn                    text constraint ck_product_hsn check (hsn ~ '^[0-9]{4,8}$'),
  gst_rate               numeric(5,2) constraint ck_product_gst_rate check (gst_rate between 0 and 100),
  schedule               text constraint ck_product_schedule check (schedule in ('NONE', 'H', 'H1', 'X')),
  ndps                   boolean,
  purchase_unit          text constraint ck_product_purchase_unit check (char_length(purchase_unit) between 1 and 20),
  issue_unit             text constraint ck_product_issue_unit check (char_length(issue_unit) between 1 and 20),
  conversion             integer constraint ck_product_conversion check (conversion between 1 and 10000),
  dup_key                text constraint ck_product_dup_key check (char_length(dup_key) between 1 and 300),
  client_key             uuid,
  -- local settings, both kinds
  alias                  text constraint ck_product_alias check (char_length(alias) between 1 and 40),
  barcode                text constraint ck_product_barcode check (barcode ~ '^[A-Za-z0-9-]{1,40}$'),
  returnable             boolean not null default true,
  preferred_vendor_id    uuid,
  has_stock              boolean not null default false,
  status                 text not null default 'ACTIVE'
                           constraint ck_product_status check (status in ('ACTIVE', 'INACTIVE')),
  created_at             timestamptz not null default now(),
  created_by             uuid not null,
  updated_at             timestamptz,
  updated_by             uuid,
  version                bigint not null default 0,
  constraint uq_product_tenant_id unique (tenant_id, id),
  constraint fk_product_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  -- Adopting twice is refused (own rows have a null link, so they never collide).
  constraint uq_product_adopted unique (tenant_id, unit_id, catalogue_product_id),
  constraint uq_product_code unique (tenant_id, unit_id, code),
  constraint ck_product_kind_fields check (
    (kind = 'ADOPTED'
       and catalogue_product_id is not null and code is null and name is null and salt is null and strength is null
       and form is null and manufacturer is null and made_in_house = false and hsn is null and gst_rate is null
       and schedule is null and ndps is null and purchase_unit is null and issue_unit is null and conversion is null
       and dup_key is null and client_key is null)
    or
    (kind = 'OWN'
       and catalogue_product_id is null and code is not null and name is not null and salt is not null and strength is not null
       and form is not null and (made_in_house or manufacturer is not null) and hsn is not null and gst_rate is not null
       and schedule is not null and ndps is not null and purchase_unit is not null and issue_unit is not null
       and conversion is not null and dup_key is not null)
  )
);

-- One ACTIVE product per barcode in the unit (case-insensitive), the scan lookup and the duplicate-barcode refusal.
create unique index if not exists uq_product_barcode on pharmacy.product (tenant_id, unit_id, lower(barcode))
  where status = 'ACTIVE' and barcode is not null;
-- Create replay: the same caller sending the same clientKey twice (own products only).
create unique index if not exists uq_product_client_key on pharmacy.product (tenant_id, created_by, client_key)
  where client_key is not null;
-- Duplicate advice among the unit's own products (same salt, strength and form).
create index if not exists ix_product_dup_key on pharmacy.product (tenant_id, dup_key) where dup_key is not null;
-- Product list by status (Active / Inactive filter) with the unit.
create index if not exists ix_product_unit_status on pharmacy.product (tenant_id, unit_id, status);
-- Search by name, salt or alias (substring). A trigram index cannot lead with tenant_id (same as ix_patient_name_trgm); row level
-- security filters the rows after the index. Own-product name and salt only; adopted rows are searched through the catalogue.
create index if not exists ix_product_name_trgm on pharmacy.product using gin (lower(name) gin_trgm_ops);
create index if not exists ix_product_salt_trgm on pharmacy.product using gin (lower(salt) gin_trgm_ops);
create index if not exists ix_product_alias_trgm on pharmacy.product using gin (lower(alias) gin_trgm_ops);

alter table pharmacy.product enable row level security;
alter table pharmacy.product force row level security;
create policy p_product_select on pharmacy.product for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_product_insert on pharmacy.product for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_product_update on pharmacy.product for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
-- No delete grant and no delete policy: products are switched off by status, never removed.
revoke all on pharmacy.product from sahaj_app;
revoke all on pharmacy.product from public;
grant select, insert, update on pharmacy.product to sahaj_app;

-- Defence in depth: kind, link, code and tenant never change; has_stock never goes back to false; once the product has stock its
-- units and conversion are locked (approval of opening stock sets has_stock, ADR-028 D12). Switch-off stays a service rule.
create or replace function pharmacy.guard_product_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.kind is distinct from old.kind
     or new.catalogue_product_id is distinct from old.catalogue_product_id
     or new.code is distinct from old.code
     or new.tenant_id is distinct from old.tenant_id
     or new.unit_id is distinct from old.unit_id then
    raise exception 'product kind, catalogue link, code and unit cannot change' using errcode = 'check_violation';
  end if;
  if old.has_stock and not new.has_stock then
    raise exception 'product stock flag cannot be cleared' using errcode = 'check_violation';
  end if;
  if old.has_stock and (new.purchase_unit is distinct from old.purchase_unit
                        or new.issue_unit is distinct from old.issue_unit
                        or new.conversion is distinct from old.conversion) then
    raise exception 'product units and conversion are locked once the product has stock' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_product_update() from public;
revoke all on function pharmacy.guard_product_update() from sahaj_app;
create trigger trg_product_guard_update before update on pharmacy.product
  for each row execute function pharmacy.guard_product_update();

comment on table pharmacy.product is
  'Unit product list (SAH-78-5, ADR-028 D3). ADOPTED rows point at pharmacy.catalogue_product and hold no global field; OWN rows hold the full global field set. Never deleted; switched off by status.';
comment on column pharmacy.product.dup_key is
  'Own rows only: normalised salt|strength|form, computed by the application (design note section 3.1). Advice for the duplicate check.';
comment on column pharmacy.product.code is
  'Own rows only: U-<unit code>-nnnn, number taken from pharmacy.unit.own_product_next_no in the same transaction. Adopted rows show the catalogue code.';
comment on column pharmacy.product.preferred_vendor_id is
  'Preferred vendor (same tenant). The foreign key is added with the vendor table.';

-- ======================================================================================================================
-- 2. pharmacy.product_store_setting: reorder level and rack per product and store. A "removed" setting is a row with both values null
--    (no delete grant), so a save upserts the rows it sends.
-- ======================================================================================================================
create table if not exists pharmacy.product_store_setting (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                    constraint fk_product_store_setting_tenant references platform.tenant (id),
  unit_id         uuid not null,
  product_id      uuid not null,
  store_id        uuid not null,
  reorder_level   integer
                    constraint ck_product_store_setting_reorder check (reorder_level between 0 and 999999),
  rack            text
                    constraint ck_product_store_setting_rack check (char_length(rack) between 1 and 30),
  created_at      timestamptz not null default now(),
  created_by      uuid not null,
  updated_at      timestamptz,
  updated_by      uuid,
  version         bigint not null default 0,
  constraint uq_product_store_setting unique (tenant_id, product_id, store_id),
  constraint fk_product_store_setting_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_product_store_setting_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id),
  constraint fk_product_store_setting_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id)
);

-- Settings of one store (reorder report in Phase 2). The product side is served by the unique key (tenant_id, product_id, store_id).
create index if not exists ix_product_store_setting_store on pharmacy.product_store_setting (tenant_id, store_id);

alter table pharmacy.product_store_setting enable row level security;
alter table pharmacy.product_store_setting force row level security;
create policy p_product_store_setting_select on pharmacy.product_store_setting for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_product_store_setting_insert on pharmacy.product_store_setting for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_product_store_setting_update on pharmacy.product_store_setting for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.product_store_setting from sahaj_app;
revoke all on pharmacy.product_store_setting from public;
grant select, insert, update on pharmacy.product_store_setting to sahaj_app;

comment on table pharmacy.product_store_setting is
  'Per product and store: reorder level and rack (SAH-78-5). Upserted, never deleted; a cleared setting has both values null.';

-- ======================================================================================================================
-- 3. pharmacy.opening_stock_import: one uploaded opening-stock file for one store (SAH-78-7).
--    DRAFT -> NOT_ACCEPTED | WAITING_APPROVAL | REPLACED;  WAITING_APPROVAL -> APPROVED | REJECTED | REPLACED. The other four
--    states are final (trigger). Fixed rule "nobody approves their own request": ck_opening_stock_import_approver_differs.
-- ======================================================================================================================
create table if not exists pharmacy.opening_stock_import (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null default platform.current_tenant()
                            constraint fk_opening_stock_import_tenant references platform.tenant (id),
  unit_id                 uuid not null,
  store_id                uuid not null,
  status                  text not null default 'DRAFT'
                            constraint ck_opening_stock_import_status check (status in
                              ('DRAFT', 'NOT_ACCEPTED', 'WAITING_APPROVAL', 'APPROVED', 'REJECTED', 'REPLACED')),
  row_count               integer not null
                            constraint ck_opening_stock_import_row_count check (row_count between 1 and 5000),
  uploaded_by             uuid not null,
  client_key              uuid not null,
  submitted_at            timestamptz,
  decided_by              uuid,
  decided_at              timestamptz,
  reject_reason_key       text
                            constraint ck_opening_stock_import_reject_reason check (reject_reason_key in
                              ('WRONG_FILE', 'WRONG_QUANTITIES', 'WRONG_STORE', 'OTHER')),
  reject_note             text
                            constraint ck_opening_stock_import_reject_note check (char_length(reject_note) between 1 and 300),
  line_count              integer not null default 0
                            constraint ck_opening_stock_import_line_count check (line_count >= 0),
  total_quantity          bigint not null default 0
                            constraint ck_opening_stock_import_total_quantity check (total_quantity >= 0),
  total_value_paise       bigint not null default 0
                            constraint ck_opening_stock_import_total_value check (total_value_paise >= 0),
  error_count             integer not null default 0
                            constraint ck_opening_stock_import_error_count check (error_count >= 0),
  warning_count           integer not null default 0
                            constraint ck_opening_stock_import_warning_count check (warning_count >= 0),
  replaced_by_import_id   uuid,
  created_at              timestamptz not null default now(),
  created_by              uuid not null,
  updated_at              timestamptz,
  updated_by              uuid,
  version                 bigint not null default 0,
  constraint uq_opening_stock_import_tenant_id unique (tenant_id, id),
  constraint uq_opening_stock_import_client_key unique (tenant_id, uploaded_by, client_key),
  constraint fk_opening_stock_import_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_opening_stock_import_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  -- Deferred so a new import and the import it replaces can be written in either order inside one transaction.
  constraint fk_opening_stock_import_replaced_by foreign key (tenant_id, replaced_by_import_id)
    references pharmacy.opening_stock_import (tenant_id, id) deferrable initially deferred,
  constraint ck_opening_stock_import_uploader check (created_by = uploaded_by),
  -- Who decided, and when: exactly for APPROVED and REJECTED.
  constraint ck_opening_stock_import_decision check (
    (status in ('APPROVED', 'REJECTED') and decided_by is not null and decided_at is not null)
    or (status not in ('APPROVED', 'REJECTED') and decided_by is null and decided_at is null)),
  -- Fixed rule: the approver is never the uploader (the service also requires a different OWNER). Rejecting is open to any owner.
  constraint ck_opening_stock_import_approver_differs check (status <> 'APPROVED' or decided_by <> uploaded_by),
  constraint ck_opening_stock_import_submitted check (
    status in ('DRAFT', 'NOT_ACCEPTED', 'REPLACED') or submitted_at is not null),
  -- A rejection carries a reason (and, for OTHER, a note of at least 4 characters); no other status carries one.
  constraint ck_opening_stock_import_reject check (
    (status = 'REJECTED' and reject_reason_key is not null
       and (reject_reason_key <> 'OTHER' or coalesce(char_length(btrim(reject_note)), 0) >= 4))
    or (status <> 'REJECTED' and reject_reason_key is null and reject_note is null)),
  constraint ck_opening_stock_import_replaced check ((status = 'REPLACED') = (replaced_by_import_id is not null)),
  constraint ck_opening_stock_import_not_accepted check (status <> 'NOT_ACCEPTED' or error_count >= 1)
);

-- At most one import waiting for approval and one approved import per store (DB-level backstop to the service rule).
create unique index if not exists uq_opening_stock_import_one_waiting on pharmacy.opening_stock_import (tenant_id, store_id)
  where status = 'WAITING_APPROVAL';
create unique index if not exists uq_opening_stock_import_one_approved on pharmacy.opening_stock_import (tenant_id, store_id)
  where status = 'APPROVED';
-- Import list: per store, newest first, and the "waiting for approval" worklist of the owner.
create index if not exists ix_opening_stock_import_store on pharmacy.opening_stock_import (tenant_id, store_id, created_at desc);
create index if not exists ix_opening_stock_import_status on pharmacy.opening_stock_import (tenant_id, status, created_at desc);

alter table pharmacy.opening_stock_import enable row level security;
alter table pharmacy.opening_stock_import force row level security;
create policy p_opening_stock_import_select on pharmacy.opening_stock_import for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_opening_stock_import_insert on pharmacy.opening_stock_import for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_opening_stock_import_update on pharmacy.opening_stock_import for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.opening_stock_import from sahaj_app;
revoke all on pharmacy.opening_stock_import from public;
grant select, insert, update on pharmacy.opening_stock_import to sahaj_app;

-- A new import always starts as DRAFT.
create or replace function pharmacy.guard_opening_stock_import_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.status <> 'DRAFT' then
    raise exception 'opening stock import must start as a draft' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_import_insert() from public;
revoke all on function pharmacy.guard_opening_stock_import_insert() from sahaj_app;
create trigger trg_opening_stock_import_guard_insert before insert on pharmacy.opening_stock_import
  for each row execute function pharmacy.guard_opening_stock_import_insert();

-- Final states cannot be updated; only the listed transitions are allowed; identity columns never change; a waiting import keeps its totals.
create or replace function pharmacy.guard_opening_stock_import_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status in ('APPROVED', 'REJECTED', 'NOT_ACCEPTED', 'REPLACED') then
    raise exception 'opening stock import is final (%)', old.status using errcode = 'check_violation';
  end if;
  if new.tenant_id is distinct from old.tenant_id or new.unit_id is distinct from old.unit_id
     or new.store_id is distinct from old.store_id or new.uploaded_by is distinct from old.uploaded_by
     or new.client_key is distinct from old.client_key or new.row_count is distinct from old.row_count
     or new.created_at is distinct from old.created_at or new.created_by is distinct from old.created_by then
    raise exception 'opening stock import identity cannot change' using errcode = 'check_violation';
  end if;
  if old.status = 'DRAFT' then
    if new.status not in ('DRAFT', 'NOT_ACCEPTED', 'WAITING_APPROVAL', 'REPLACED') then
      raise exception 'opening stock import cannot go from draft to %', new.status using errcode = 'check_violation';
    end if;
  else  -- WAITING_APPROVAL
    if new.status not in ('APPROVED', 'REJECTED', 'REPLACED') then
      raise exception 'opening stock import waiting for approval can only be approved, rejected or replaced' using errcode = 'check_violation';
    end if;
    if new.line_count is distinct from old.line_count or new.total_quantity is distinct from old.total_quantity
       or new.total_value_paise is distinct from old.total_value_paise or new.error_count is distinct from old.error_count
       or new.warning_count is distinct from old.warning_count or new.submitted_at is distinct from old.submitted_at then
      raise exception 'opening stock import totals are fixed once submitted' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_import_update() from public;
revoke all on function pharmacy.guard_opening_stock_import_update() from sahaj_app;
create trigger trg_opening_stock_import_guard_update before update on pharmacy.opening_stock_import
  for each row execute function pharmacy.guard_opening_stock_import_update();

comment on table pharmacy.opening_stock_import is
  'Opening-stock file of one store (SAH-78-7, ADR-028 D12): staged, approved by a different owner, posted later by SAH-79. The file itself is never stored; the parsed lines are kept as evidence and as the posting source. Never deleted. APPROVED, REJECTED, NOT_ACCEPTED and REPLACED rows are final.';

-- ======================================================================================================================
-- 4. pharmacy.opening_stock_line: the raw text of the seven file columns plus what the server parsed from it.
--    Written only while the parent import is DRAFT (trigger). Kept after approval as evidence and posting source.
-- ======================================================================================================================
create table if not exists pharmacy.opening_stock_line (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null default platform.current_tenant()
                        constraint fk_opening_stock_line_tenant references platform.tenant (id),
  unit_id             uuid not null,
  import_id           uuid not null,
  row_no              integer not null
                        constraint ck_opening_stock_line_row_no check (row_no between 1 and 5000),
  store_code_raw      text not null default '' constraint ck_opening_stock_line_store_raw check (char_length(store_code_raw) <= 40),
  product_code_raw    text not null default '' constraint ck_opening_stock_line_product_raw check (char_length(product_code_raw) <= 40),
  batch_raw           text not null default '' constraint ck_opening_stock_line_batch_raw check (char_length(batch_raw) <= 40),
  expiry_raw          text not null default '' constraint ck_opening_stock_line_expiry_raw check (char_length(expiry_raw) <= 40),
  mrp_raw             text not null default '' constraint ck_opening_stock_line_mrp_raw check (char_length(mrp_raw) <= 40),
  purchase_rate_raw   text not null default '' constraint ck_opening_stock_line_rate_raw check (char_length(purchase_rate_raw) <= 40),
  quantity_raw        text not null default '' constraint ck_opening_stock_line_quantity_raw check (char_length(quantity_raw) <= 40),
  product_id          uuid,
  batch_no            text constraint ck_opening_stock_line_batch_no check (char_length(batch_no) between 1 and 20),
  expiry_date         date,
  mrp                 numeric(14,4) constraint ck_opening_stock_line_mrp check (mrp >= 0),
  purchase_rate       numeric(14,4) constraint ck_opening_stock_line_rate check (purchase_rate >= 0),
  qty                 integer constraint ck_opening_stock_line_qty check (qty >= 1),
  value_paise         bigint constraint ck_opening_stock_line_value check (value_paise >= 0),
  created_at          timestamptz not null default now(),
  created_by          uuid not null,
  updated_at          timestamptz,
  updated_by          uuid,
  version             bigint not null default 0,
  constraint uq_opening_stock_line_row unique (tenant_id, import_id, row_no),
  constraint uq_opening_stock_line_tenant_id unique (tenant_id, id),
  constraint fk_opening_stock_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_opening_stock_line_import foreign key (tenant_id, import_id) references pharmacy.opening_stock_import (tenant_id, id),
  constraint fk_opening_stock_line_product foreign key (tenant_id, product_id) references pharmacy.product (tenant_id, id)
);

-- File-wide check "the same product and batch twice" and the SAH-79 posting read.
create index if not exists ix_opening_stock_line_product_batch on pharmacy.opening_stock_line (tenant_id, import_id, product_id, batch_no);
-- A product's own lines across imports (to know whether it was ever staged).
create index if not exists ix_opening_stock_line_product on pharmacy.opening_stock_line (tenant_id, product_id) where product_id is not null;

alter table pharmacy.opening_stock_line enable row level security;
alter table pharmacy.opening_stock_line force row level security;
create policy p_opening_stock_line_select on pharmacy.opening_stock_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_opening_stock_line_insert on pharmacy.opening_stock_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_opening_stock_line_update on pharmacy.opening_stock_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.opening_stock_line from sahaj_app;
revoke all on pharmacy.opening_stock_line from public;
grant select, insert, update on pharmacy.opening_stock_line to sahaj_app;

create or replace function pharmacy.guard_opening_stock_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status    text;
  v_row_count integer;
begin
  select i.status, i.row_count into v_status, v_row_count
    from pharmacy.opening_stock_import i
   where i.tenant_id = new.tenant_id and i.id = new.import_id;
  if v_status is distinct from 'DRAFT' then
    raise exception 'opening stock lines can change only while the import is a draft' using errcode = 'check_violation';
  end if;
  if new.row_no > v_row_count then
    raise exception 'opening stock row number is beyond the import row count' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' then
    if new.import_id is distinct from old.import_id or new.row_no is distinct from old.row_no
       or new.tenant_id is distinct from old.tenant_id then
      raise exception 'opening stock line identity cannot change' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_line_write() from public;
revoke all on function pharmacy.guard_opening_stock_line_write() from sahaj_app;
create trigger trg_opening_stock_line_guard_write before insert or update on pharmacy.opening_stock_line
  for each row execute function pharmacy.guard_opening_stock_line_write();

comment on table pharmacy.opening_stock_line is
  'One data row of an opening-stock import (SAH-78-7): raw text of the 7 columns and the parsed values. Written only while the import is DRAFT. Kept for good as evidence and as the SAH-79 posting source. No patient data. Batch numbers and quantities are never logged.';

-- ======================================================================================================================
-- 5. pharmacy.opening_stock_line_issue: row errors and warnings. No delete grant, so when a row is sent again the old issues are
--    marked superseded (the only column that may change, false to true; trigger) and the new issues are inserted. Current issues
--    are those with superseded = false. Written only while the parent import is DRAFT: write the issues BEFORE the import leaves DRAFT.
-- ======================================================================================================================
create table if not exists pharmacy.opening_stock_line_issue (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
                 constraint fk_opening_stock_line_issue_tenant references platform.tenant (id),
  unit_id      uuid not null,
  line_id      uuid not null,
  field        text not null
                 constraint ck_opening_stock_line_issue_field check (field in
                   ('storeCode', 'productCode', 'batch', 'expiryDate', 'mrp', 'purchaseRate', 'quantity')),
  code         text not null
                 constraint ck_opening_stock_line_issue_code check (code in
                   ('store-mismatch', 'product-unknown', 'batch-invalid', 'batch-repeated', 'expiry-invalid', 'expiry-past',
                    'mrp-invalid', 'rate-invalid', 'quantity-invalid', 'rate-above-mrp')),
  severity     text not null
                 constraint ck_opening_stock_line_issue_severity check (severity in ('ERROR', 'WARNING')),
  superseded   boolean not null default false,
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  constraint fk_opening_stock_line_issue_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_opening_stock_line_issue_line foreign key (tenant_id, line_id) references pharmacy.opening_stock_line (tenant_id, id)
);

-- Issues of one line (current ones) and of a whole import through the line; GET .../lines?withIssues=true.
create index if not exists ix_opening_stock_line_issue_line on pharmacy.opening_stock_line_issue (tenant_id, line_id)
  where not superseded;

alter table pharmacy.opening_stock_line_issue enable row level security;
alter table pharmacy.opening_stock_line_issue force row level security;
create policy p_opening_stock_line_issue_select on pharmacy.opening_stock_line_issue for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_opening_stock_line_issue_insert on pharmacy.opening_stock_line_issue for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_opening_stock_line_issue_update on pharmacy.opening_stock_line_issue for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.opening_stock_line_issue from sahaj_app;
revoke all on pharmacy.opening_stock_line_issue from public;
grant select, insert, update on pharmacy.opening_stock_line_issue to sahaj_app;

create or replace function pharmacy.guard_opening_stock_line_issue_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_status text;
begin
  select i.status into v_status
    from pharmacy.opening_stock_line l
    join pharmacy.opening_stock_import i on i.tenant_id = l.tenant_id and i.id = l.import_id
   where l.tenant_id = new.tenant_id and l.id = new.line_id;
  if v_status is distinct from 'DRAFT' then
    raise exception 'opening stock issues can change only while the import is a draft' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' then
    if old.superseded or not new.superseded
       or (pg_catalog.to_jsonb(new) - 'superseded') is distinct from (pg_catalog.to_jsonb(old) - 'superseded') then
      raise exception 'an opening stock issue can only be marked superseded' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_opening_stock_line_issue_write() from public;
revoke all on function pharmacy.guard_opening_stock_line_issue_write() from sahaj_app;
create trigger trg_opening_stock_line_issue_guard_write before insert or update on pharmacy.opening_stock_line_issue
  for each row execute function pharmacy.guard_opening_stock_line_issue_write();

comment on table pharmacy.opening_stock_line_issue is
  'Row errors and warnings of an opening-stock line (SAH-78-7). Insert, and mark superseded (false to true); never deleted. Current issues have superseded = false.';
