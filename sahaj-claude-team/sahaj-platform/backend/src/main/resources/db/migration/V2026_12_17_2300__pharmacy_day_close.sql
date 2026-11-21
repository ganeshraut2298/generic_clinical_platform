-- SAH-80 WP-N (story 80-20), migration N1. ADR-030 S14 "Day close" (OQ-80-14), "Data needed" (WP-N).
-- New table pharmacy.day_close: one row per store, person and closed period; append-only (select and insert; triggers refuse update, delete,
-- truncate). Expected amounts come from bill_payment, customer_account_entry (udhar payments received) and refund rows of that person in the
-- period (computed by the service at close and stored here, so a later correction of a source row cannot change a past close); counted is what
-- the person counted; difference = counted - expected per mode (check). The pharmacy drawer is separate from billing's (G-28).
-- No patient data. Membership ids are opaque uuids. Never deleted.
--
-- Report and command-centre indexes: none are added in this release. ADR-030 allows them "only after EXPLAIN on realistic data", and no
-- measured data set exists yet. The to-do for QA and the java-developer is in docs/database/SAH-80-sales.md, section "Index to-do".

set local lock_timeout = '30s';

create table if not exists pharmacy.day_close (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                               constraint fk_day_close_tenant references platform.tenant (id),
  unit_id                    uuid not null,
  store_id                   uuid not null,
  person_membership_id       uuid not null,
  closed_by                  uuid not null,
  closed_at                  timestamptz not null default now(),
  period_from                timestamptz not null,
  period_to                  timestamptz not null,
  expected_cash_paise        bigint not null
                               constraint ck_day_close_expected_cash check (expected_cash_paise >= 0),
  expected_upi_paise         bigint not null
                               constraint ck_day_close_expected_upi check (expected_upi_paise >= 0),
  expected_card_paise         bigint not null
                               constraint ck_day_close_expected_card check (expected_card_paise >= 0),
  counted_cash_paise         bigint not null
                               constraint ck_day_close_counted_cash check (counted_cash_paise >= 0),
  counted_upi_paise          bigint not null
                               constraint ck_day_close_counted_upi check (counted_upi_paise >= 0),
  counted_card_paise         bigint not null
                               constraint ck_day_close_counted_card check (counted_card_paise >= 0),
  -- counted minus expected; negative when short.
  difference_cash_paise      bigint not null,
  difference_upi_paise       bigint not null,
  difference_card_paise      bigint not null,
  -- Shown beside the sheet, not part of the cash count.
  credit_note_used_paise     bigint not null default 0
                               constraint ck_day_close_credit_note_used check (credit_note_used_paise >= 0),
  udhar_given_paise          bigint not null default 0
                               constraint ck_day_close_udhar_given check (udhar_given_paise >= 0),
  -- Reasons for a difference, per mode (list day-close-difference); the note is a short staff note, never logged.
  cash_reason_key            text
                               constraint ck_day_close_cash_reason check (cash_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  cash_reason_note           varchar(300)
                               constraint ck_day_close_cash_note check (char_length(btrim(cash_reason_note)) between 1 and 300),
  upi_reason_key             text
                               constraint ck_day_close_upi_reason check (upi_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  upi_reason_note            varchar(300)
                               constraint ck_day_close_upi_note check (char_length(btrim(upi_reason_note)) between 1 and 300),
  card_reason_key            text
                               constraint ck_day_close_card_reason check (card_reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  card_reason_note           varchar(300)
                               constraint ck_day_close_card_note check (char_length(btrim(card_reason_note)) between 1 and 300),
  constraint uq_day_close_person_period unique (tenant_id, store_id, person_membership_id, period_to),
  constraint fk_day_close_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_day_close_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint ck_day_close_period check (period_to > period_from),
  constraint ck_day_close_difference check (
    difference_cash_paise = counted_cash_paise - expected_cash_paise
    and difference_upi_paise = counted_upi_paise - expected_upi_paise
    and difference_card_paise = counted_card_paise - expected_card_paise),
  constraint ck_day_close_notes_need_reason check (
    (cash_reason_note is null or cash_reason_key is not null)
    and (upi_reason_note is null or upi_reason_key is not null)
    and (card_reason_note is null or card_reason_key is not null))
);

-- The closes of a store, newest first (the day close list). The previous close of a person (where the next period starts) is served by
-- uq_day_close_person_period (tenant_id, store_id, person_membership_id, period_to).
create index if not exists ix_day_close_store_closed on pharmacy.day_close (tenant_id, store_id, closed_at desc);

alter table pharmacy.day_close enable row level security;
alter table pharmacy.day_close force row level security;
create policy p_day_close_select on pharmacy.day_close for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_day_close_insert on pharmacy.day_close for insert to sahaj_app with check (tenant_id = platform.current_tenant());
revoke all on pharmacy.day_close from sahaj_app;
revoke all on pharmacy.day_close from public;
grant select, insert on pharmacy.day_close to sahaj_app;

create trigger trg_day_close_no_update_delete before update or delete on pharmacy.day_close
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_day_close_no_truncate before truncate on pharmacy.day_close
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.day_close is
  'Day close of one person at one store (SAH-80-20, ADR-030 S14): expected, counted and difference per mode (cash, UPI, card) for a closed period, with reasons for differences. Append-only, never deleted. The expected amounts are stored as computed at the close. No patient data.';
