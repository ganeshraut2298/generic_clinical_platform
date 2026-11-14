-- SAH-69-3, SAH-68-2 (ADR-025 D7, D14, "Data needed" item 11) · Dental plan lines on the visit bill (source EMR_PLAN).
-- Forward-only; nothing is dropped, renamed or rewritten except the two check constraints that are widened (the earlier
-- migrations did the same, V2026_12_09_1000). RLS, policy and the table-level select, insert, update, delete grants of
-- billing.bill_item are unchanged (they cover the new column). The previous application version never writes EMR_PLAN or
-- label and keeps working.
-- 1. source_kind gains EMR_PLAN: a dental plan row done today, added once at the doctor's sign (source EMR is the service line of
--    "Bill today"). EMR_PLAN lines are paid-bill safe: a new open line, never a change of a paid one (application rule).
-- 2. label (null, 1 to 120): the procedure name shown on an EMR_PLAN line; only an EMR_PLAN line has one.
-- 3. service_id has been nullable since SAH-10 but every line had a service (the application refuses a line without one). It is
--    now a rule: service_id may be null only for EMR_PLAN (a dental plan row is priced by the estimate, not by a catalogue
--    service). Every existing line has a service, so validation passes; if a line without a service existed the migration
--    would stop here on purpose.
-- 4. source_key length widens from 40 to 80 (widening only): an EMR_PLAN key is "plan:<rowId>:<sittingNo>". The existing partial
--    unique index uq_bill_item_tenant_bill_source_key (tenant_id, bill_id, source_key) covers EMR_PLAN keys, so one line per
--    plan row and sitting per bill; ck_bill_item_source_pair (kind and key both set or both null) is unchanged.

alter table billing.bill_item
  add column if not exists label text
    constraint ck_bill_item_label check (label is null or char_length(btrim(label)) between 1 and 120);

alter table billing.bill_item drop constraint ck_bill_item_source_kind;
alter table billing.bill_item
  add constraint ck_bill_item_source_kind check (source_kind is null or source_kind in ('EMR', 'EMR_PLAN')) not valid;
alter table billing.bill_item validate constraint ck_bill_item_source_kind;

alter table billing.bill_item drop constraint ck_bill_item_source_key;
alter table billing.bill_item
  add constraint ck_bill_item_source_key check (source_key is null or char_length(source_key) between 1 and 80) not valid;
alter table billing.bill_item validate constraint ck_bill_item_source_key;

alter table billing.bill_item
  add constraint ck_bill_item_label_kind check (label is null or coalesce(source_kind, '') = 'EMR_PLAN'),
  add constraint ck_bill_item_service_or_plan check (service_id is not null or coalesce(source_kind, '') = 'EMR_PLAN') not valid;
alter table billing.bill_item validate constraint ck_bill_item_service_or_plan;

comment on column billing.bill_item.label is 'The procedure name of an EMR_PLAN line (SAH-69-3, ADR-025 D7), 1 to 120 characters; null for every other line.';
comment on column billing.bill_item.source_kind is 'EMR for a service line made by the doctor''s EMR (SAH-48); EMR_PLAN for a dental plan row done today, added once at the sign (SAH-69-3); null for a line added at the counter. The EMR sync never touches null lines.';
comment on column billing.bill_item.source_key is 'EMR: the EMR item id (SAH-48). EMR_PLAN: plan:<rowId>:<sittingNo>. At most 80 characters; unique per bill, so a retry gives one line.';
comment on constraint ck_bill_item_service_or_plan on billing.bill_item is 'SAH-69-3: service_id may be null only for an EMR_PLAN line.';
