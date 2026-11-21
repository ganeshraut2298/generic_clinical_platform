-- SAH-80 WP-I (stories 80-2 to 80-5), migration I5. ADR-030 S3, S4, S5 and "Data needed" (WP-I).
-- New tables: pharmacy.bill, pharmacy.bill_line, pharmacy.bill_payment; guards pharmacy.guard_bill_update() and pharmacy.guard_bill_line_write().
-- Tenant tables, forced row level security, no delete grant. A bill is a GST invoice: never deleted, cancelled by status. Money in paise
-- (bigint); the maths of S4 is done by the service, the database keeps the identities that must always hold (net = gross - discount +
-- round off; taxable + CGST + SGST = gross - discount; per line n = a - d and t + x = n).
-- Patient data: patient_id, walk-in name and phone, prescriber and prescription date. Tenant isolated, never logged, never in the ledger
-- or the audit text. Decision fields that name a person hold membership ids (opaque uuid, no foreign key).
-- bill.discount_request_id is a plain uuid here; WP-J (V2026_12_17_1900) adds the composite foreign key to sale_discount_request.
-- bill.sales_return and refund links are in WP-L. customer_account_entry, credit_note and refund are in V2026_12_17_1850.

set local lock_timeout = '30s';

-- ======================================================================================================================
-- 1. pharmacy.bill
-- ======================================================================================================================
create table if not exists pharmacy.bill (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null default platform.current_tenant()
                              constraint fk_bill_tenant references platform.tenant (id),
  unit_id                   uuid not null,
  store_id                  uuid not null,
  -- The rendered number (prefix, optional financial-year part, padded sequence), at most 16 characters (document_series rule).
  bill_no                   varchar(16) not null
                              constraint ck_bill_no check (char_length(btrim(bill_no)) between 1 and 16),
  -- Which series issued it: the unit BILL series or the store's own series; fiscal_year as in the counters (0 = never restarts).
  series_kind               text not null
                              constraint ck_bill_series_kind check (series_kind in ('UNIT', 'STORE')),
  fiscal_year               integer not null
                              constraint ck_bill_fiscal_year check (fiscal_year = 0 or fiscal_year between 2000 and 2999),
  status                    text not null default 'SAVED'
                              constraint ck_bill_status check (status in ('SAVED', 'CANCELLED')),
  buyer_kind                text not null
                              constraint ck_bill_buyer_kind check (buyer_kind in ('PATIENT', 'CUSTOMER', 'WALK_IN', 'NONE')),
  patient_id                uuid,
  customer_id               uuid,
  walk_in_name              varchar(80)
                              constraint ck_bill_walk_in_name check (char_length(btrim(walk_in_name)) between 1 and 80),
  walk_in_phone             varchar(10)
                              constraint ck_bill_walk_in_phone check (walk_in_phone ~ '^[0-9]{10}$'),
  -- The patient service did not answer: sold as a walk-in, to be linked later (BR-3). Blocks the switch to Standalone.
  link_pending              boolean not null default false,
  -- The allergy read failed or was not possible at save (BRD section 6).
  allergy_not_checked       boolean not null default false,
  rx_record_id              uuid,
  rx_source                 text
                              constraint ck_bill_rx_source check (rx_source in ('E_RX', 'TYPED')),
  prescriber_name           varchar(80)
                              constraint ck_bill_prescriber_name check (char_length(btrim(prescriber_name)) between 1 and 80),
  prescriber_reg_no         varchar(40)
                              constraint ck_bill_prescriber_reg_no check (char_length(btrim(prescriber_reg_no)) between 1 and 40),
  prescription_date         date,
  held_bill_id              uuid,
  discount_percent          numeric(5,2) not null default 0
                              constraint ck_bill_discount_percent check (discount_percent between 0 and 100),
  discount_request_id       uuid,
  -- S4: A (MRP total), D (discount), taxable, CGST, SGST, round off, net payable. No IGST.
  gross_paise               bigint not null
                              constraint ck_bill_gross check (gross_paise >= 0),
  discount_paise            bigint not null default 0
                              constraint ck_bill_discount check (discount_paise >= 0),
  taxable_paise             bigint not null
                              constraint ck_bill_taxable check (taxable_paise >= 0),
  cgst_paise                bigint not null
                              constraint ck_bill_cgst check (cgst_paise >= 0),
  sgst_paise                bigint not null
                              constraint ck_bill_sgst check (sgst_paise >= 0),
  round_off_paise           bigint not null default 0
                              constraint ck_bill_round_off check (round_off_paise between -50 and 49),
  net_paise                 bigint not null
                              constraint ck_bill_net check (net_paise >= 0),
  udhar_paise               bigint not null default 0
                              constraint ck_bill_udhar check (udhar_paise >= 0),
  saved_at                  timestamptz not null default now(),
  saved_by                  uuid not null,
  -- The pharmacist who checked a scheduled-drug bill (null when none was needed).
  pharmacist_membership_id  uuid,
  cancelled_at              timestamptz,
  cancelled_by              uuid,
  cancel_reason_key         text
                              constraint ck_bill_cancel_reason check (cancel_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  cancel_note               varchar(300)
                              constraint ck_bill_cancel_note check (char_length(btrim(cancel_note)) between 1 and 300),
  -- Cancel: true = units go back on the shelf (Available), false = Quarantine (an expired batch always Quarantine, service rule).
  back_on_shelf             boolean,
  -- Cash, UPI and card part to give back; settled later by a refund with a credit note number (OQ-80-13).
  refund_due_paise          bigint not null default 0
                              constraint ck_bill_refund_due check (refund_due_paise >= 0),
  refund_credit_note_no     varchar(24)
                              constraint ck_bill_refund_cn check (char_length(btrim(refund_credit_note_no)) between 1 and 24),
  created_at                timestamptz not null default now(),
  updated_at                timestamptz,
  updated_by                uuid,
  version                   bigint not null default 0,
  constraint uq_bill_tenant_id unique (tenant_id, id),
  constraint fk_bill_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_bill_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_bill_customer foreign key (tenant_id, customer_id) references pharmacy.customer (tenant_id, id),
  constraint fk_bill_held_bill foreign key (tenant_id, held_bill_id) references pharmacy.held_bill (tenant_id, id),
  -- Who the buyer is. A link-pending sale is a WALK_IN until "Link to patient" (once) makes it a PATIENT.
  constraint ck_bill_buyer_shape check (
    (buyer_kind = 'PATIENT' and patient_id is not null)
    or (buyer_kind = 'CUSTOMER' and customer_id is not null and patient_id is null)
    or (buyer_kind = 'WALK_IN' and patient_id is null and walk_in_name is not null)
    or (buyer_kind = 'NONE' and patient_id is null and customer_id is null and walk_in_name is null and walk_in_phone is null)),
  constraint ck_bill_link_pending check (not link_pending or buyer_kind = 'WALK_IN'),
  constraint ck_bill_udhar_needs_customer check (udhar_paise = 0 or customer_id is not null),
  constraint ck_bill_udhar_within_net check (udhar_paise <= net_paise),
  constraint ck_bill_discount_within_gross check (discount_paise <= gross_paise),
  constraint ck_bill_total_identity check (net_paise = gross_paise - discount_paise + round_off_paise),
  constraint ck_bill_tax_identity check (taxable_paise + cgst_paise + sgst_paise = gross_paise - discount_paise),
  constraint ck_bill_cancel_shape check (
    (status = 'SAVED' and cancelled_at is null and cancelled_by is null and cancel_reason_key is null and cancel_note is null
       and back_on_shelf is null and refund_due_paise = 0 and refund_credit_note_no is null)
    or (status = 'CANCELLED' and cancelled_at is not null and cancelled_by is not null and cancel_reason_key is not null
       and back_on_shelf is not null))
);

-- The gapless bill number is unique per unit, ignoring case (the number embeds the series prefix).
create unique index if not exists uq_bill_no on pharmacy.bill (tenant_id, unit_id, lower(bill_no));
-- Today's bills of a store, newest first; the command-centre sales cards; the GST sales register by date range.
create index if not exists ix_bill_store_saved on pharmacy.bill (tenant_id, store_id, saved_at desc);
-- A customer's bills (udhar history, account screen).
create index if not exists ix_bill_customer on pharmacy.bill (tenant_id, customer_id, saved_at desc) where customer_id is not null;
-- The mode guard "a link-pending bill blocks Standalone" and the Link to patient list.
create index if not exists ix_bill_link_pending on pharmacy.bill (tenant_id, store_id) where link_pending;
-- Cancelled bills whose cash, UPI or card refund is still to be given.
create index if not exists ix_bill_refund_due on pharmacy.bill (tenant_id, store_id, cancelled_at)
  where status = 'CANCELLED' and refund_due_paise > 0 and refund_credit_note_no is null;
-- The bill a held bill became (foreign key).
create index if not exists ix_bill_held_bill on pharmacy.bill (tenant_id, held_bill_id) where held_bill_id is not null;

alter table pharmacy.bill enable row level security;
alter table pharmacy.bill force row level security;
create policy p_bill_select on pharmacy.bill for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_bill_insert on pharmacy.bill for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_bill_update on pharmacy.bill for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.bill from sahaj_app;
revoke all on pharmacy.bill from public;
grant select, insert, update on pharmacy.bill to sahaj_app;

-- Guard. Everything on the bill is final at save except: the cancel fields and status (SAVED to CANCELLED, once), refund_credit_note_no
-- (set once on a cancelled bill), the link to a patient (WALK_IN with link_pending to PATIENT, once), and version and update stamps.
create or replace function pharmacy.guard_bill_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  v_open text[] := array['status', 'cancelled_at', 'cancelled_by', 'cancel_reason_key', 'cancel_note', 'back_on_shelf',
                         'refund_due_paise', 'refund_credit_note_no', 'buyer_kind', 'patient_id', 'link_pending',
                         'version', 'updated_at', 'updated_by'];
begin
  if (to_jsonb(new) - v_open) is distinct from (to_jsonb(old) - v_open) then
    raise exception 'bill is final once saved' using errcode = 'check_violation';
  end if;
  if new.status is distinct from old.status then
    if not (old.status = 'SAVED' and new.status = 'CANCELLED') then
      raise exception 'bill cannot go from % to %', old.status, new.status using errcode = 'check_violation';
    end if;
  elsif old.status = 'CANCELLED' then
    if new.cancelled_at is distinct from old.cancelled_at or new.cancelled_by is distinct from old.cancelled_by
       or new.cancel_reason_key is distinct from old.cancel_reason_key or new.cancel_note is distinct from old.cancel_note
       or new.back_on_shelf is distinct from old.back_on_shelf or new.refund_due_paise is distinct from old.refund_due_paise then
      raise exception 'cancelled bill cannot change' using errcode = 'check_violation';
    end if;
  elsif new.cancelled_at is distinct from old.cancelled_at or new.refund_due_paise is distinct from old.refund_due_paise then
    raise exception 'bill cancel fields change only with the cancel' using errcode = 'check_violation';
  end if;
  if new.refund_credit_note_no is distinct from old.refund_credit_note_no then
    if old.refund_credit_note_no is not null or old.status <> 'CANCELLED' then
      raise exception 'bill refund credit note is set once on a cancelled bill' using errcode = 'check_violation';
    end if;
  end if;
  if new.buyer_kind is distinct from old.buyer_kind or new.patient_id is distinct from old.patient_id
     or new.link_pending is distinct from old.link_pending then
    if not (old.buyer_kind = 'WALK_IN' and old.link_pending and new.buyer_kind = 'PATIENT'
            and new.patient_id is not null and not new.link_pending) then
      raise exception 'bill buyer can only be linked to a patient once' using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_bill_update() from public;
revoke all on function pharmacy.guard_bill_update() from sahaj_app;

create trigger trg_bill_guard_update before update on pharmacy.bill
  for each row execute function pharmacy.guard_bill_update();

comment on table pharmacy.bill is
  'Counter bill, a GST invoice (SAH-80, ADR-030 S3 and S4). The number is taken at save, last in the transaction (gapless). SAVED or CANCELLED; never deleted. Final at save except the cancel, the refund credit note number (once) and the link to a patient (once). Patient data (patient id, walk-in name and phone, prescriber): never logged, never in the ledger or audit text.';
comment on column pharmacy.bill.bill_no is
  'Rendered number, for example SB/26-27/000123, at most 16 characters. Unique per unit ignoring case.';
comment on column pharmacy.bill.net_paise is
  'Net payable after round off (S4 step 5). Identity: net = gross - discount + round off.';
comment on column pharmacy.bill.pharmacist_membership_id is
  'Membership id of the pharmacist who checked the scheduled lines. Null when no scheduled line.';
comment on function pharmacy.guard_bill_update() is
  'Bill guard: final at save except status SAVED to CANCELLED with the cancel fields, refund_credit_note_no (once, cancelled bill), the one-time link WALK_IN (link pending) to PATIENT, version and update stamps. SQLSTATE 23514.';

-- ======================================================================================================================
-- 2. pharmacy.bill_line: one row per batch picked by FEFO. Final at save except returned_qty, which only grows up to qty.
-- ======================================================================================================================
create table if not exists pharmacy.bill_line (
  id                              uuid primary key default gen_random_uuid(),
  tenant_id                       uuid not null default platform.current_tenant()
                                    constraint fk_bill_line_tenant references platform.tenant (id),
  unit_id                         uuid not null,
  bill_id                         uuid not null,
  line_no                         integer not null
                                    constraint ck_bill_line_no check (line_no >= 1),
  product_id                      uuid not null,
  batch_id                        uuid not null,
  qty                             integer not null
                                    constraint ck_bill_line_qty check (qty between 1 and 999999),
  returned_qty                    integer not null default 0
                                    constraint ck_bill_line_returned_qty check (returned_qty >= 0),
  -- Batch MRP per issue unit in rupees including GST, the GST rate in percent and the HSN, as on the day of the sale.
  mrp                             numeric(14,4) not null
                                    constraint ck_bill_line_mrp check (mrp > 0),
  gst_rate                        numeric(5,2) not null
                                    constraint ck_bill_line_gst_rate check (gst_rate between 0 and 100),
  hsn                             text
                                    constraint ck_bill_line_hsn check (hsn ~ '^[0-9]{4,8}$'),
  -- S4: a = line amount, d = discount share, n = net (GST included), t = taxable, x = tax. Paise.
  gross_paise                     bigint not null
                                    constraint ck_bill_line_gross check (gross_paise >= 0),
  discount_paise                  bigint not null default 0
                                    constraint ck_bill_line_discount check (discount_paise >= 0),
  net_paise                       bigint not null
                                    constraint ck_bill_line_net check (net_paise >= 0),
  taxable_paise                   bigint not null
                                    constraint ck_bill_line_taxable check (taxable_paise >= 0),
  tax_paise                       bigint not null
                                    constraint ck_bill_line_tax check (tax_paise >= 0),
  -- Substitution (F6): the product the prescription named, when another was supplied.
  prescribed_product_id           uuid,
  substitution_reason_key         text
                                    constraint ck_bill_line_substitution_reason check (substitution_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  substitution_note               varchar(200)
                                    constraint ck_bill_line_substitution_note check (char_length(btrim(substitution_note)) between 1 and 200),
  patient_agreed                  boolean,
  -- Pharmacist overrides: allergy block and a missing prescriber on an H1 line. Reason keys and notes only; never the allergy text.
  allergy_override_key            text
                                    constraint ck_bill_line_allergy_override check (allergy_override_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  allergy_override_note           varchar(200)
                                    constraint ck_bill_line_allergy_override_note check (char_length(btrim(allergy_override_note)) between 1 and 200),
  h1_override_key                 text
                                    constraint ck_bill_line_h1_override check (h1_override_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  h1_override_note                varchar(200)
                                    constraint ck_bill_line_h1_override_note check (char_length(btrim(h1_override_note)) between 1 and 200),
  override_decided_by             uuid,
  -- The person who sold the line, and for a Schedule X line the second signer (never the same person).
  actor_membership_id             uuid not null,
  second_signer_membership_id     uuid,
  -- The line of the typed or e-prescription this supplies (OPD queue).
  rx_line_key                     varchar(40)
                                    constraint ck_bill_line_rx_line_key check (char_length(btrim(rx_line_key)) between 1 and 40),
  created_at                      timestamptz not null default now(),
  created_by                      uuid not null,
  constraint uq_bill_line_tenant_id unique (tenant_id, id),
  constraint uq_bill_line_no unique (tenant_id, bill_id, line_no),
  constraint fk_bill_line_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_bill_line_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id),
  constraint fk_bill_line_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  constraint fk_bill_line_prescribed_product foreign key (tenant_id, prescribed_product_id) references pharmacy.product (tenant_id, id),
  constraint ck_bill_line_returned_within_qty check (returned_qty <= qty),
  constraint ck_bill_line_net_identity check (net_paise = gross_paise - discount_paise),
  constraint ck_bill_line_tax_identity check (taxable_paise + tax_paise = net_paise),
  constraint ck_bill_line_substitution_needs_product check (
    prescribed_product_id is not null or (substitution_reason_key is null and substitution_note is null and patient_agreed is null)),
  constraint ck_bill_line_override_notes check (
    (allergy_override_note is null or allergy_override_key is not null) and (h1_override_note is null or h1_override_key is not null)),
  constraint ck_bill_line_override_decider check (
    (allergy_override_key is null and h1_override_key is null) or override_decided_by is not null),
  constraint ck_bill_line_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> actor_membership_id)
);

-- Batch and product foreign keys, and "bills that sold this batch" (batch history, recall).
create index if not exists ix_bill_line_batch on pharmacy.bill_line (tenant_id, batch_id);
create index if not exists ix_bill_line_product on pharmacy.bill_line (tenant_id, product_id);
-- The lines of a bill are served by uq_bill_line_no (tenant_id, bill_id, line_no).

alter table pharmacy.bill_line enable row level security;
alter table pharmacy.bill_line force row level security;
create policy p_bill_line_select on pharmacy.bill_line for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_bill_line_insert on pharmacy.bill_line for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_bill_line_update on pharmacy.bill_line for update to sahaj_app
  using (tenant_id = platform.current_tenant()) with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.bill_line from sahaj_app;
revoke all on pharmacy.bill_line from public;
grant select, insert, update on pharmacy.bill_line to sahaj_app;

-- Guard: a line starts with returned_qty 0; the only change allowed is returned_qty growing (never above qty, checked by the table check).
create or replace function pharmacy.guard_bill_line_write()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'INSERT' then
    if new.returned_qty <> 0 then
      raise exception 'bill line must start with nothing returned' using errcode = 'check_violation';
    end if;
    return new;
  end if;
  if (to_jsonb(new) - 'returned_qty'::text) is distinct from (to_jsonb(old) - 'returned_qty'::text) then
    raise exception 'bill line is final once saved; only the returned quantity changes' using errcode = 'check_violation';
  end if;
  if new.returned_qty < old.returned_qty then
    raise exception 'bill line returned quantity cannot go down' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_bill_line_write() from public;
revoke all on function pharmacy.guard_bill_line_write() from sahaj_app;

create trigger trg_bill_line_guard_write before insert or update on pharmacy.bill_line
  for each row execute function pharmacy.guard_bill_line_write();

comment on table pharmacy.bill_line is
  'One batch sold on a bill (SAH-80, ADR-030 S3): quantity, MRP, GST rate, HSN and the S4 amounts a, d, n, t, x in paise, plus substitution and override records. Final at save except returned_qty, which only grows up to qty (the sales return updates it). Never deleted.';
comment on column pharmacy.bill_line.allergy_override_note is
  'Typed note of the pharmacist for the chip Other. Never logged, never in audit or activity text (the reason key only).';
comment on function pharmacy.guard_bill_line_write() is
  'Bill line guard: starts with returned_qty 0; afterwards only returned_qty may change and only upwards. SQLSTATE 23514.';

-- ======================================================================================================================
-- 3. pharmacy.bill_payment: how the bill was paid, one row per mode (split payment). Append-only. Written with the bill; also the source of
--    the day close (expected cash, UPI and card of a person in a period).
-- ======================================================================================================================
create table if not exists pharmacy.bill_payment (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null default platform.current_tenant()
                          constraint fk_bill_payment_tenant references platform.tenant (id),
  unit_id               uuid not null,
  store_id              uuid not null,
  bill_id               uuid not null,
  mode                  text not null
                          constraint ck_bill_payment_mode check (mode in ('CASH', 'UPI', 'CARD', 'UDHAR', 'CREDIT_NOTE')),
  amount_paise          bigint not null
                          constraint ck_bill_payment_amount check (amount_paise > 0),
  actor_membership_id   uuid not null,
  paid_at               timestamptz not null default now(),
  constraint uq_bill_payment_mode unique (tenant_id, bill_id, mode),
  constraint fk_bill_payment_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_bill_payment_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_bill_payment_bill foreign key (tenant_id, bill_id) references pharmacy.bill (tenant_id, id)
);

-- Day close: payments of a person at a store in a period.
create index if not exists ix_bill_payment_actor on pharmacy.bill_payment (tenant_id, store_id, actor_membership_id, paid_at);

alter table pharmacy.bill_payment enable row level security;
alter table pharmacy.bill_payment force row level security;
create policy p_bill_payment_select on pharmacy.bill_payment for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_bill_payment_insert on pharmacy.bill_payment for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.bill_payment from sahaj_app;
revoke all on pharmacy.bill_payment from public;
grant select, insert on pharmacy.bill_payment to sahaj_app;

create trigger trg_bill_payment_no_update_delete before update or delete on pharmacy.bill_payment
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_bill_payment_no_truncate before truncate on pharmacy.bill_payment
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.bill_payment is
  'Payment of a bill by mode (CASH, UPI, CARD, UDHAR, CREDIT_NOTE), one row per mode (SAH-80-4). Recorded only: no gateway, no reference. Append-only (select and insert; triggers refuse update, delete, truncate). A cancelled bill keeps its rows; the money back is in refund and customer_account_entry. The sum of the rows equals the bill net (service rule, checked at save).';
