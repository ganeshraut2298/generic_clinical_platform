-- SAH-58, SAH-48 (ADR-022 D7, D12) · Billing: the treatment course account and the EMR lines of a visit bill. Forward-only.
--   billing.receipt_counter         per-clinic receipt number counter (RC-<n>); gaps allowed, duplicates never
--   billing.course_payment          APPEND-ONLY: one row per payment collected against a treatment course
--   billing.course_credit_transfer  APPEND-ONLY: course credit carried to an IPD admission (one per admission)
--   billing.bill_item               two new nullable columns: source_kind, source_key (EMR service lines of a visit bill)
-- Money is bigint paise (the codebase convention: billing.payment, opd.service). Adjustments for credit used are derived on
-- read and not stored. Every table: the clinic column defaults to platform.current_tenant(), ENABLE + FORCE row level
-- security, policy for sahaj_app on platform.current_tenant(), tenant-first indexes. Cross-module ids (the course and its
-- sitting in opd, the admission in ipd) are opaque: no foreign key, as the rule says; patient.patient is the allowed exception
-- and is referenced by the composite key (clinic, id). Billing rows are never deleted.

-- ---------------------------------------------------------------------------------------------
-- 1. billing.receipt_counter: next receipt number per clinic. One row per clinic, created by the first course payment.
--    Used as:  insert ... (tenant_id, last_no) values (platform.current_tenant(), 1)
--              on conflict (tenant_id) do update set last_no = billing.receipt_counter.last_no + 1, updated_at = now()
--              returning last_no
--    The conflict update takes the row lock, so two payments at once get two numbers; the counter row and the payment row
--    are in one transaction, so a rolled back payment leaves a gap at most. This numbering covers course payments only
--    (DEC-042 H4): the visit bill payments (billing.payment) have no receipt number.
-- ---------------------------------------------------------------------------------------------
create table if not exists billing.receipt_counter (
  tenant_id   uuid not null default platform.current_tenant()
              constraint fk_receipt_counter_tenant references platform.tenant (id),
  last_no     bigint not null
              constraint ck_receipt_counter_last_no check (last_no >= 0),
  updated_at  timestamptz,
  constraint pk_receipt_counter primary key (tenant_id)
);

alter table billing.receipt_counter enable row level security;
alter table billing.receipt_counter force row level security;

create policy p_receipt_counter_tenant on billing.receipt_counter
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 2. billing.course_payment (D12): the financial record of a course account. Append-only like billing.payment: select and
--    insert only, and a trigger refuses UPDATE, DELETE and TRUNCATE for every role (the owner included). A mistake is a
--    new row, not an edit. No refund type in phase 3: amount_paise is always positive.
--    receipt_no: from billing.receipt_counter, unique per clinic, shown RC-<receipt_no>.
--    amount_paise: whole rupees (a multiple of 100), 100 to 10,000,000,000 (the contract bound).
--    type IPD_DEPOSIT is earmarked to the planned admission sitting it is for: against_sitting_id is then required.
--    against_label: the text of the ledger and the receipt ("Course advance", "Sitting 2", "Admission IP-2232").
--    use_credit: recorded for the derived "advance used" adjustment text only.
--    idempotency_key + request_hash: required; a retry with the same key and body returns the first payment (the application
--    compares the hash); another body is 409 idempotency-conflict. The key is unique per clinic for ever.
--    course_id and against_sitting_id are opd ids: no foreign key (cross-module). patient_id repeats the course's patient
--    so receipts and the day report need no call into opd.
-- ---------------------------------------------------------------------------------------------
create table if not exists billing.course_payment (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_course_payment_tenant references platform.tenant (id),
  course_id         uuid not null,
  patient_id        uuid not null,
  receipt_no        bigint not null
                    constraint ck_course_payment_receipt_no check (receipt_no >= 1),
  type              text not null
                    constraint ck_course_payment_type check (type in
                      ('VISIT_PAYMENT', 'FULL_BALANCE', 'PART_PAYMENT', 'ADVANCE', 'IPD_DEPOSIT')),
  mode              text not null
                    constraint ck_course_payment_mode check (mode in ('CASH', 'UPI', 'CARD')),
  amount_paise      bigint not null
                    constraint ck_course_payment_amount check (amount_paise between 100 and 10000000000 and amount_paise % 100 = 0),
  against_sitting_id uuid,
  against_label     text not null
                    constraint ck_course_payment_against_label check (char_length(btrim(against_label)) between 1 and 80),
  use_credit        boolean not null default false,
  idempotency_key   text not null
                    constraint ck_course_payment_idempotency_key check (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash      text not null
                    constraint ck_course_payment_request_hash check (char_length(request_hash) between 16 and 128),
  recorded_by       uuid not null,
  recorded_at       timestamptz not null default now(),
  constraint uq_course_payment_tenant_receipt unique (tenant_id, receipt_no),
  constraint uq_course_payment_tenant_idempotency unique (tenant_id, idempotency_key),
  constraint ck_course_payment_deposit_sitting check (type <> 'IPD_DEPOSIT' or against_sitting_id is not null),
  constraint fk_course_payment_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id)
);

-- The account and the ledger of one course in time order; the credit carried to IPD reads the earmarked deposits here.
create index if not exists ix_course_payment_tenant_course
  on billing.course_payment (tenant_id, course_id, recorded_at);
-- Day closing (expected cash), the revenue report and collected-by-mode over a date range (DEC-042 H3).
create index if not exists ix_course_payment_tenant_recorded_at
  on billing.course_payment (tenant_id, recorded_at);

alter table billing.course_payment enable row level security;
alter table billing.course_payment force row level security;

create policy p_course_payment_select on billing.course_payment
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_course_payment_insert on billing.course_payment
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

create trigger trg_course_payment_no_update_delete
  before update or delete on billing.course_payment
  for each row execute function platform.deny_audit_mutation();
create trigger trg_course_payment_no_truncate
  before truncate on billing.course_payment
  for each statement execute function platform.deny_audit_mutation();

-- ---------------------------------------------------------------------------------------------
-- 3. billing.course_credit_transfer (D12): the earmarked course credit moved to an IPD admission's deposits. Append-only,
--    same protections. One per admission (unique) and one per sitting (the sitting is linked to one admission).
--    The IPD side records a deposit with source COURSE pointing at this row's id (ipd.deposit.course_credit_transfer_id).
--    amount_paise: 100 to 100,000,000 (₹10,00,000) because that is the largest single IPD deposit (ck_deposit_amount on
--    ipd.deposit): the application caps the carried amount there, so the deposit insert in the same transaction cannot fail.
--    course_id, sitting_id and admission_id are opaque ids from opd and ipd: no foreign key.
-- ---------------------------------------------------------------------------------------------
create table if not exists billing.course_credit_transfer (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                  constraint fk_course_credit_transfer_tenant references platform.tenant (id),
  course_id       uuid not null,
  sitting_id      uuid not null,
  admission_id    uuid not null,
  amount_paise    bigint not null
                  constraint ck_course_credit_transfer_amount check (amount_paise between 100 and 100000000 and amount_paise % 100 = 0),
  transferred_by  uuid not null,
  transferred_at  timestamptz not null default now(),
  constraint uq_course_credit_transfer_tenant_admission unique (tenant_id, admission_id),
  constraint uq_course_credit_transfer_tenant_sitting unique (tenant_id, sitting_id)
);

-- Paid = payments - credit carried: all transfers of one course.
create index if not exists ix_course_credit_transfer_tenant_course
  on billing.course_credit_transfer (tenant_id, course_id);

alter table billing.course_credit_transfer enable row level security;
alter table billing.course_credit_transfer force row level security;

create policy p_course_credit_transfer_select on billing.course_credit_transfer
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_course_credit_transfer_insert on billing.course_credit_transfer
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

create trigger trg_course_credit_transfer_no_update_delete
  before update or delete on billing.course_credit_transfer
  for each row execute function platform.deny_audit_mutation();
create trigger trg_course_credit_transfer_no_truncate
  before truncate on billing.course_credit_transfer
  for each statement execute function platform.deny_audit_mutation();

-- ---------------------------------------------------------------------------------------------
-- 4. billing.bill_item (D7): EMR service lines. Two nullable columns: the previous application version never writes them,
--    so its lines (and the counter's lines) stay source_kind null and are never touched by the EMR sync.
--    source_key = the EMR item id (a uuid as text, 1 to 40 characters); both columns are set or both null. The partial unique
--    key makes the sync idempotent: a retry or double tap gives one line per item. bill_item already has the table-level
--    update grant (SAH-10), so qty can be changed; delete is granted too. No other grant change.
-- ---------------------------------------------------------------------------------------------
alter table billing.bill_item
  add column if not exists source_kind text
    constraint ck_bill_item_source_kind check (source_kind is null or source_kind in ('EMR')),
  add column if not exists source_key text
    constraint ck_bill_item_source_key check (source_key is null or char_length(source_key) between 1 and 40);

alter table billing.bill_item
  add constraint ck_bill_item_source_pair check ((source_kind is null) = (source_key is null));

create unique index if not exists uq_bill_item_tenant_bill_source_key
  on billing.bill_item (tenant_id, bill_id, source_key) where source_key is not null;

-- ---------------------------------------------------------------------------------------------
-- 5. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on billing.receipt_counter, billing.course_payment, billing.course_credit_transfer from sahaj_app;
revoke all on billing.receipt_counter, billing.course_payment, billing.course_credit_transfer from public;

grant select, insert, update on billing.receipt_counter to sahaj_app;
grant select, insert on billing.course_payment to sahaj_app;
grant select, insert on billing.course_credit_transfer to sahaj_app;

comment on table billing.receipt_counter is 'Next receipt number per clinic (SAH-58, DEC-042 H4): RC-<last_no>, course payments only. Gaps allowed, duplicates never.';
comment on table billing.course_payment is
  'Append-only financial record of a treatment course account (SAH-58, ADR-022 D12): every payment with its receipt number, type, mode and amount in paise. Never updated, deleted or truncated - enforced by trigger as well as privilege. Idempotent by (clinic, idempotency_key) with a request hash. Cross-module ids are opaque.';
comment on table billing.course_credit_transfer is
  'Append-only: course credit carried to an IPD admission (SAH-58, ADR-022 D12): one row per admission, amount capped at the IPD deposit limit. Never updated, deleted or truncated - enforced by trigger as well as privilege.';
comment on column billing.bill_item.source_kind is 'EMR for a service line made by the doctor''s EMR (SAH-48); null for a line added at the counter. The EMR sync never touches null lines.';
comment on column billing.bill_item.source_key is 'The EMR item id that made this line (SAH-48); unique per bill, so a retry gives one line.';
