-- SAH-80 WP-J (stories 80-6, 80-7), migration J1. ADR-030 S14 "Discount" and "Data needed" (WP-J).
--   1. approval_request.subject_type: + SALE_DISCOUNT, SALES_RETURN (the transaction_type check of 1360 already has both values).
--   2. New table pharmacy.sale_discount_request: links an engine request to the cart digest, percent and held bill. Append-only.
--   3. The two foreign keys that WP-I left open: held_bill.discount_request_id and bill.discount_request_id.
-- "A changed cart or a higher percent makes the request VOID" is computed by the service from the digest and percent; nothing is updated.
-- Allergy overrides and substitution records have no table of their own: ADR-030 S9 and S3 keep them on bill_line (V2026_12_17_1840:
-- substitution_reason_key, patient_agreed, allergy_override_key and note, h1_override_key and note, override_decided_by).

set local lock_timeout = '30s';

alter table pharmacy.approval_request drop constraint if exists ck_approval_request_subject_type;
alter table pharmacy.approval_request add constraint ck_approval_request_subject_type check (subject_type in
  ('PURCHASE_ORDER', 'GOODS_RECEIPT', 'DEBIT_NOTE', 'INDENT', 'TRANSFER', 'DAMAGE_REPORT',
   'SHORTAGE', 'STOCK_ADJUSTMENT', 'STOCK_AUDIT', 'SALE_DISCOUNT', 'SALES_RETURN'));

create table if not exists pharmacy.sale_discount_request (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_sale_discount_request_tenant references platform.tenant (id),
  unit_id               uuid not null,
  -- The selling store: the approval rule store (ADR-030 S14).
  store_id              uuid not null,
  approval_request_id   uuid not null,
  -- Digest of the cart (lines, batches, MRPs and percent) the approver saw; the save needs an APPROVED request with the same digest.
  cart_digest           varchar(64) not null
                          constraint ck_sale_discount_request_digest check (cart_digest ~ '^[0-9a-f]{16,64}$'),
  discount_percent      numeric(5,2) not null
                          constraint ck_sale_discount_request_percent check (discount_percent > 0 and discount_percent <= 100),
  discount_paise        bigint not null
                          constraint ck_sale_discount_request_discount check (discount_paise > 0),
  held_bill_id          uuid,
  requested_by          uuid not null,
  created_at            timestamptz not null default now(),
  constraint uq_sale_discount_request_tenant_id unique (tenant_id, id),
  constraint uq_sale_discount_request_approval unique (tenant_id, approval_request_id),
  constraint fk_sale_discount_request_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_sale_discount_request_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_sale_discount_request_approval foreign key (tenant_id, approval_request_id) references pharmacy.approval_request (tenant_id, id),
  constraint fk_sale_discount_request_held_bill foreign key (tenant_id, held_bill_id) references pharmacy.held_bill (tenant_id, id)
);

-- The request of a held bill (resume, withdrawal on discard or a new request).
create index if not exists ix_sale_discount_request_held_bill on pharmacy.sale_discount_request (tenant_id, held_bill_id) where held_bill_id is not null;
-- The requests of a store, newest first (the inbox and the activity feed read the engine; this serves the store list).
create index if not exists ix_sale_discount_request_store on pharmacy.sale_discount_request (tenant_id, store_id, created_at desc);

alter table pharmacy.sale_discount_request enable row level security;
alter table pharmacy.sale_discount_request force row level security;
create policy p_sale_discount_request_select on pharmacy.sale_discount_request for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_sale_discount_request_insert on pharmacy.sale_discount_request for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.sale_discount_request from sahaj_app;
revoke all on pharmacy.sale_discount_request from public;
grant select, insert on pharmacy.sale_discount_request to sahaj_app;

create trigger trg_sale_discount_request_no_update_delete before update or delete on pharmacy.sale_discount_request
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_sale_discount_request_no_truncate before truncate on pharmacy.sale_discount_request
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.sale_discount_request is
  'Discount approval link of the POS (SAH-80-6, ADR-030 S14): one row per engine request of subject SALE_DISCOUNT with the cart digest, percent and discount. Append-only (a changed cart or higher percent makes it VOID by computation; a new request is a new row). Never deleted. No patient data.';

-- The links left open by WP-I (the tables are new in this release, so the constraints need no NOT VALID).
alter table pharmacy.held_bill drop constraint if exists fk_held_bill_discount_request;
alter table pharmacy.held_bill add constraint fk_held_bill_discount_request
  foreign key (tenant_id, discount_request_id) references pharmacy.sale_discount_request (tenant_id, id);
alter table pharmacy.bill drop constraint if exists fk_bill_discount_request;
alter table pharmacy.bill add constraint fk_bill_discount_request
  foreign key (tenant_id, discount_request_id) references pharmacy.sale_discount_request (tenant_id, id);
create index if not exists ix_held_bill_discount_request on pharmacy.held_bill (tenant_id, discount_request_id) where discount_request_id is not null;
create index if not exists ix_bill_discount_request on pharmacy.bill (tenant_id, discount_request_id) where discount_request_id is not null;
