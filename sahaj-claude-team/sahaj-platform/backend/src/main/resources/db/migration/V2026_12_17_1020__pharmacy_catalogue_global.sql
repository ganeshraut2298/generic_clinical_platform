-- SAH-78 WP-B (story 78-5), migration B1 of 8. ADR-028 D3 "Global product catalogue: shared, read-only, outside tenant RLS".
-- Own file on purpose (DEC-063): the two global tables sit apart from every tenant table. Pattern: ipd.drug (V2026_11_28_1000).
--   * no tenant column: the catalogue is shared by every clinic;
--   * row level security is ENABLED and FORCED (so the repository gates pass without an exception list) with a select policy for
--     sahaj_app (using true) and an all-commands policy for sahaj_migrator, which maintains the content by later migrations;
--   * sahaj_app gets SELECT only (revoke all first, from sahaj_app and public);
--   * delete and truncate are refused for everybody by trigger; a withdrawn product is active = false, set by migration.
-- The catalogue holds NO rows here. Sample rows are the next migration (placeholder-guarded). The real catalogue is a later story.
-- Depends on: pg_trgm, created by V2026_11_01_1039 (patient). No new extension.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.catalogue_product
-- ======================================================================================================================
create table if not exists pharmacy.catalogue_product (
  id             uuid primary key default gen_random_uuid(),
  code           text not null
                   constraint uq_catalogue_product_code unique
                   constraint ck_catalogue_product_code check (code ~ '^G-[0-9]{6}$'),
  name           text not null
                   constraint ck_catalogue_product_name check (char_length(name) between 1 and 80),
  salt           text not null
                   constraint ck_catalogue_product_salt check (char_length(salt) between 1 and 80),
  strength       text not null
                   constraint ck_catalogue_product_strength check (char_length(strength) between 1 and 80),
  form           text not null
                   constraint ck_catalogue_product_form check (char_length(form) between 1 and 30),
  manufacturer   text
                   constraint ck_catalogue_product_manufacturer check (char_length(manufacturer) between 1 and 80),
  hsn            text not null
                   constraint ck_catalogue_product_hsn check (hsn ~ '^[0-9]{4,8}$'),
  gst_rate       numeric(5,2) not null
                   constraint ck_catalogue_product_gst_rate check (gst_rate between 0 and 100),
  schedule       text not null
                   constraint ck_catalogue_product_schedule check (schedule in ('NONE', 'H', 'H1', 'X')),
  ndps           boolean not null default false,
  purchase_unit  text not null
                   constraint ck_catalogue_product_purchase_unit check (char_length(purchase_unit) between 1 and 20),
  issue_unit     text not null
                   constraint ck_catalogue_product_issue_unit check (char_length(issue_unit) between 1 and 20),
  conversion     integer not null
                   constraint ck_catalogue_product_conversion check (conversion between 1 and 10000),
  active         boolean not null default true,
  sample         boolean not null default false,
  dup_key        text not null
                   constraint ck_catalogue_product_dup_key check (char_length(dup_key) between 1 and 300),
  created_at     timestamptz not null default now()
);

-- GET /catalogue-products?q= : substring search on name and salt (ILIKE or lower(x) LIKE '%q%'). A trigram index cannot lead with the
-- clinic; the catalogue is global, so this is the same shape as ix_patient_name_trgm.
create index if not exists ix_catalogue_product_name_trgm on pharmacy.catalogue_product using gin (lower(name) gin_trgm_ops);
create index if not exists ix_catalogue_product_salt_trgm on pharmacy.catalogue_product using gin (lower(salt) gin_trgm_ops);
-- Duplicate advice (OQ-78-17): "same salt + strength + form" is an equality lookup on the normalised key.
create index if not exists ix_catalogue_product_dup_key on pharmacy.catalogue_product (dup_key);

comment on table pharmacy.catalogue_product is
  'Global product catalogue (SAH-78, ADR-028 D3). Shared by every clinic, no tenant column, select-only for sahaj_app, changed by migration only, never deleted (withdrawn = active false). Rows with sample = true are fake sample data, Sample - not clinically validated, never in production.';
comment on column pharmacy.catalogue_product.dup_key is
  'Normalised salt|strength|form, computed by the application and by the seed with the same rule (design note section 3.1). Advice for the duplicate check only.';
comment on column pharmacy.catalogue_product.sample is
  'true for the fake sample rows seeded in dev and test only (placeholder sahajSampleData). Must be zero rows in production.';

-- ======================================================================================================================
-- 2. pharmacy.catalogue_barcode
-- ======================================================================================================================
create table if not exists pharmacy.catalogue_barcode (
  id                     uuid primary key default gen_random_uuid(),
  catalogue_product_id   uuid not null
                           constraint fk_catalogue_barcode_product references pharmacy.catalogue_product (id),
  barcode                text not null
                           constraint uq_catalogue_barcode_barcode unique
                           constraint ck_catalogue_barcode_barcode check (barcode ~ '^[A-Za-z0-9-]{1,40}$'),
  created_at             timestamptz not null default now()
);

-- Barcodes of one product (product detail). The unique key on barcode serves scan lookups.
create index if not exists ix_catalogue_barcode_product on pharmacy.catalogue_barcode (catalogue_product_id);

comment on table pharmacy.catalogue_barcode is
  'Barcodes of global catalogue products (SAH-78, ADR-028 D3). Same rules as pharmacy.catalogue_product. Never deleted.';

-- ======================================================================================================================
-- 3. Row level security, grants, delete and truncate refusal (shared by the third global table, pharmacy.reason_default, later)
-- ======================================================================================================================
alter table pharmacy.catalogue_product enable row level security;
alter table pharmacy.catalogue_product force row level security;
alter table pharmacy.catalogue_barcode enable row level security;
alter table pharmacy.catalogue_barcode force row level security;
create policy p_catalogue_product_select on pharmacy.catalogue_product for select to sahaj_app using (true);
create policy p_catalogue_product_migrator on pharmacy.catalogue_product for all to sahaj_migrator using (true) with check (true);
create policy p_catalogue_barcode_select on pharmacy.catalogue_barcode for select to sahaj_app using (true);
create policy p_catalogue_barcode_migrator on pharmacy.catalogue_barcode for all to sahaj_migrator using (true) with check (true);

revoke all on pharmacy.catalogue_product, pharmacy.catalogue_barcode from sahaj_app;
revoke all on pharmacy.catalogue_product, pharmacy.catalogue_barcode from public;
grant select on pharmacy.catalogue_product, pharmacy.catalogue_barcode to sahaj_app;

create or replace function pharmacy.deny_reference_delete()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  raise exception 'pharmacy reference data is never deleted (% refused); withdraw a row by setting active = false in a migration', tg_op
    using errcode = 'insufficient_privilege';
end;
$$;
revoke all on function pharmacy.deny_reference_delete() from public;
revoke all on function pharmacy.deny_reference_delete() from sahaj_app;

create trigger trg_catalogue_product_no_delete before delete on pharmacy.catalogue_product
  for each row execute function pharmacy.deny_reference_delete();
create trigger trg_catalogue_product_no_truncate before truncate on pharmacy.catalogue_product
  for each statement execute function pharmacy.deny_reference_delete();
create trigger trg_catalogue_barcode_no_delete before delete on pharmacy.catalogue_barcode
  for each row execute function pharmacy.deny_reference_delete();
create trigger trg_catalogue_barcode_no_truncate before truncate on pharmacy.catalogue_barcode
  for each statement execute function pharmacy.deny_reference_delete();
