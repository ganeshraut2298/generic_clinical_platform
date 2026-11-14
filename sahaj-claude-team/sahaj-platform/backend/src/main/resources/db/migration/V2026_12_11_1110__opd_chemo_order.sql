-- SAH-66, SAH-70 (ADR-023 D6, "Data needed" item 10) · The chemotherapy order with an independent second check (opd).
-- Forward-only, additive. One new clinic table. Clinical: never hard-deleted.
--   opd.chemo_order   one row per OPD visit record of the oncology profile (plus a later corrected order, phase 5)
--
-- Why a table and not the record document: the named checker must see "orders waiting for my second check" across records
-- without opening any EMR record, and the database must make "sent without a current, independent confirmation" impossible
-- (ADR-023 D2, D6). The doctor's inputs and the server-computed snapshot (BSA, doses, labs, checks) are two json documents;
-- everything the checker confirms is summarised in the fingerprint (SHA-256 of the canonical text of what the checker sees).
-- Clinical values (doses, drugs, labs) are in inputs and snapshot only: never in a log line or an audit value.
--
-- Row level security: ENABLE + FORCE, policy for sahaj_app on platform.current_tenant(), tenant-first indexes. Every reference
-- inside the module or to a patient is a composite key that includes the clinic column (foreign key checks ignore row level
-- security). No foreign key to platform.membership (cross-module): the membership ids are opaque, checked by the application.
--
-- Rules the database enforces (each is an integration test):
--   * checker, when named, is not the prescriber and not the last editor of the order;
--   * the confirmation is by the named checker, all five confirmation columns are set together or none;
--   * a confirmation always matches the order's current fingerprint: a change that alters the fingerprint must clear the
--     confirmation in the SAME update (the application does that and writes the audit "cleared");
--   * status SENT needs a confirmation equal to the fingerprint, a send time and a sender; a reduced level needs a reason;
--   * status HELD needs a kind and a time; the hold columns are empty for every other status;
--   * a SENT or HELD row is immutable (trigger, every role); no order is ever deleted or truncated.

create table if not exists opd.chemo_order (
  id                          uuid primary key default gen_random_uuid(),
  tenant_id                   uuid not null default platform.current_tenant()
                              constraint fk_chemo_order_tenant references platform.tenant (id),
  record_id                   uuid not null,
  record_created_at           timestamptz not null,
  patient_id                  uuid not null,
  course_id                   uuid,
  cycle_no                    integer
                              constraint ck_chemo_order_cycle_no check (cycle_no is null or cycle_no >= 1),
  status                      text not null default 'DRAFT'
                              constraint ck_chemo_order_status check (status in ('DRAFT', 'SENT', 'HELD')),
  protocol_key                text not null,
  level_pct                   integer not null
                              constraint ck_chemo_order_level check (level_pct in (100, 90, 80, 75)),
  reduction_reason_key        text
                              constraint ck_chemo_order_reduction_key check (reduction_reason_key is null or reduction_reason_key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  reduction_reason_text       text
                              constraint ck_chemo_order_reduction_text check (reduction_reason_text is null or char_length(btrim(reduction_reason_text)) between 1 and 200),
  inputs                      jsonb not null
                              constraint ck_chemo_order_inputs_shape check (
                                jsonb_typeof(inputs) = 'object' and coalesce(jsonb_typeof(inputs -> 'schemaVersion'), '') = 'number')
                              constraint ck_chemo_order_inputs_size check (octet_length(inputs::text) <= 16384),
  snapshot                    jsonb not null
                              constraint ck_chemo_order_snapshot_shape check (
                                jsonb_typeof(snapshot) = 'object' and coalesce(jsonb_typeof(snapshot -> 'schemaVersion'), '') = 'number')
                              constraint ck_chemo_order_snapshot_size check (octet_length(snapshot::text) <= 32768),
  fingerprint                 text not null
                              constraint ck_chemo_order_fingerprint check (fingerprint ~ '^[0-9a-f]{64}$'),
  doxorubicin_mg_m2           numeric(8,2) not null default 0
                              constraint ck_chemo_order_doxorubicin check (doxorubicin_mg_m2 >= 0),
  prior_outside_mg_m2         numeric(8,2) not null default 0
                              constraint ck_chemo_order_prior_outside check (prior_outside_mg_m2 between 0 and 2000),
  prescriber_membership_id    uuid not null,
  last_edited_by_membership_id uuid not null,
  checker_membership_id       uuid,
  checker_role                text
                              constraint ck_chemo_order_checker_role check (checker_role is null or checker_role in
                                ('NURSE', 'PHARMACIST', 'DOCTOR', 'VISITING_DOCTOR', 'OWNER')),
  checker_named_at            timestamptz,
  checker_named_by            uuid,
  confirmed_by_membership_id  uuid,
  confirmed_by_user_id        uuid,
  confirmed_via               text
                              constraint ck_chemo_order_confirmed_via check (confirmed_via is null or confirmed_via in ('SIGN_IN', 'PIN')),
  confirmed_at                timestamptz,
  confirmed_fingerprint       text
                              constraint ck_chemo_order_confirmed_fingerprint check (confirmed_fingerprint is null or confirmed_fingerprint ~ '^[0-9a-f]{64}$'),
  confirmation_cleared_at     timestamptz,
  sent_at                     timestamptz,
  sent_by                     uuid,
  hold_kind                   text
                              constraint ck_chemo_order_hold_kind check (hold_kind is null or hold_kind in ('DELAY', 'STOP')),
  hold_reason                 text
                              constraint ck_chemo_order_hold_reason check (hold_reason is null or char_length(btrim(hold_reason)) between 1 and 300),
  held_at                     timestamptz,
  supersedes_order_id         uuid,
  revision                    bigint not null default 0
                              constraint ck_chemo_order_revision check (revision >= 0),
  created_at                  timestamptz not null default now(),
  created_by                  uuid not null,
  updated_at                  timestamptz,
  updated_by                  uuid,
  constraint uq_chemo_order_tenant_id unique (tenant_id, id),
  constraint fk_chemo_order_record foreign key (tenant_id, record_id, record_created_at)
    references opd.emr_record (tenant_id, id, created_at),
  constraint fk_chemo_order_patient foreign key (tenant_id, patient_id)
    references patient.patient (tenant_id, id),
  constraint fk_chemo_order_course foreign key (tenant_id, course_id)
    references opd.treatment_course (tenant_id, id),
  constraint fk_chemo_order_protocol foreign key (protocol_key)
    references opd.emr_chemo_protocol (protocol_key),
  -- a corrected order after sending (phase 5, SAH-51-5) points at the order it replaces, in the same clinic
  constraint fk_chemo_order_supersedes foreign key (tenant_id, supersedes_order_id)
    references opd.chemo_order (tenant_id, id),
  constraint ck_chemo_order_supersedes_other check (supersedes_order_id is null or supersedes_order_id <> id),
  -- the named checker: all four columns together or none; never the prescriber, never the last editor
  constraint ck_chemo_order_checker_pair check (
    (checker_membership_id is null) = (checker_role is null)
    and (checker_membership_id is null) = (checker_named_at is null)
    and (checker_membership_id is null) = (checker_named_by is null)),
  constraint ck_chemo_order_checker_independent check (
    checker_membership_id is null
    or (checker_membership_id <> prescriber_membership_id and checker_membership_id <> last_edited_by_membership_id)),
  -- the confirmation: five columns together or none; made by the named checker (a null checker never satisfies this)
  constraint ck_chemo_order_confirmation_pair check (
    (confirmed_by_membership_id is null) = (confirmed_by_user_id is null)
    and (confirmed_by_membership_id is null) = (confirmed_via is null)
    and (confirmed_by_membership_id is null) = (confirmed_at is null)
    and (confirmed_by_membership_id is null) = (confirmed_fingerprint is null)),
  constraint ck_chemo_order_confirmer_is_checker check (
    confirmed_by_membership_id is null
    or (checker_membership_id is not null and confirmed_by_membership_id = checker_membership_id)),
  -- a confirmation is never stale: it equals the fingerprint of the order as it stands
  constraint ck_chemo_order_confirmation_current check (
    confirmed_fingerprint is null or confirmed_fingerprint = fingerprint),
  -- "Order changed after the second check" is remembered only while there is no confirmation
  constraint ck_chemo_order_cleared_has_no_confirmation check (
    confirmation_cleared_at is null or confirmed_at is null),
  -- SENT: confirmed with the current fingerprint, time and sender stamped; a reduced level carries a reason
  constraint ck_chemo_order_sent check (
    status <> 'SENT'
    or (confirmed_at is not null and confirmed_fingerprint = fingerprint and sent_at is not null and sent_by is not null)),
  constraint ck_chemo_order_sent_stamp check (
    (status = 'SENT') = (sent_at is not null) and (sent_at is null) = (sent_by is null)),
  constraint ck_chemo_order_sent_reason check (
    status <> 'SENT' or level_pct = 100 or reduction_reason_key is not null or reduction_reason_text is not null),
  -- HELD: kind and time; empty for every other status
  constraint ck_chemo_order_held check (
    case status
      when 'HELD' then hold_kind is not null and held_at is not null
      else             hold_kind is null and hold_reason is null and held_at is null
    end)
);

-- One order per record (the first save creates it). A corrected order (supersedes_order_id set, phase 5) is another row.
create unique index if not exists uq_chemo_order_tenant_record
  on opd.chemo_order (tenant_id, record_id) where supersedes_order_id is null;

-- The checker's list "waiting for my second check" (GET /emr/chemo-checks, 20 a page, oldest naming first).
create index if not exists ix_chemo_order_tenant_checker_waiting
  on opd.chemo_order (tenant_id, checker_membership_id, checker_named_at)
  where status = 'DRAFT' and checker_membership_id is not null and confirmed_at is null;

-- A patient's sent orders by time: cycle 1 weight (the first), the lifetime doxorubicin total (the sum), consent per protocol,
-- the cycle number (SENT orders + 1) and the oncology cycles block of the history.
create index if not exists ix_chemo_order_tenant_patient_sent
  on opd.chemo_order (tenant_id, patient_id, sent_at) where status = 'SENT';

alter table opd.chemo_order enable row level security;
alter table opd.chemo_order force row level security;

create policy p_chemo_order_tenant on opd.chemo_order
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- Immutability. A DRAFT row may be updated; an UPDATE of a SENT or HELD row, and every DELETE or TRUNCATE, is refused for every
-- role (the owner and sahaj_migrator included), with SQLSTATE 42501 as the other immutable tables. The update that moves a
-- DRAFT to SENT or HELD is allowed because the old row is still DRAFT.
-- ---------------------------------------------------------------------------------------------
create or replace function opd.deny_chemo_order_change()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE' and old.status = 'DRAFT' then
    return new;
  end if;
  raise exception 'opd.chemo_order is immutable once sent or held and is never deleted (% refused)', tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_chemo_order_immutable
  before update or delete on opd.chemo_order
  for each row execute function opd.deny_chemo_order_change();
create trigger trg_chemo_order_no_truncate
  before truncate on opd.chemo_order
  for each statement execute function opd.deny_chemo_order_change();

-- ---------------------------------------------------------------------------------------------
-- Grants: the schema default privileges give sahaj_app all four commands, so take everything away first. No delete.
-- Updates are column-limited: never an id, the clinic, the record, the patient, the prescriber, the superseded order or created_*.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.chemo_order from sahaj_app;
revoke all on opd.chemo_order from public;

grant select, insert on opd.chemo_order to sahaj_app;
grant update (course_id, cycle_no, status, protocol_key, level_pct, reduction_reason_key, reduction_reason_text,
              inputs, snapshot, fingerprint, doxorubicin_mg_m2, prior_outside_mg_m2, last_edited_by_membership_id,
              checker_membership_id, checker_role, checker_named_at, checker_named_by,
              confirmed_by_membership_id, confirmed_by_user_id, confirmed_via, confirmed_at, confirmed_fingerprint,
              confirmation_cleared_at, sent_at, sent_by, hold_kind, hold_reason, held_at,
              revision, updated_at, updated_by)
  on opd.chemo_order to sahaj_app;

comment on table opd.chemo_order is
  'Chemotherapy order of one oncology OPD visit with an independent second check (SAH-66, SAH-70, ADR-023 D6). Doctor inputs and the server-computed snapshot (BSA, doses, labs, safety checks) are json; the fingerprint is the SHA-256 of what the checker is shown. The checker is never the prescriber; a confirmation always equals the current fingerprint; SENT needs it; SENT and HELD rows are immutable (trigger); never deleted. Clinical: doses, drugs and labs are never logged or audited by value. Value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. Tens of rows a day for a day-care clinic; no partitioning.';
comment on column opd.chemo_order.fingerprint is 'SHA-256 (64 lower-case hex) of the canonical text of what the checker is shown: protocol, level, reason, final doses, BSA used and cap, labs and drawn time, decision, check results, named checker. Recomputed by the application on every save.';
comment on column opd.chemo_order.confirmation_cleared_at is 'Set when a change after the second check cleared the confirmation (the view says "Order changed after the second check. A new confirmation is needed."); null again once confirmed.';
comment on column opd.chemo_order.doxorubicin_mg_m2 is 'This cycle: doxorubicin dose per m2 x level percent / 100. The lifetime total adds the sum over the patient''s SENT orders in this clinic and prior_outside_mg_m2.';
comment on column opd.chemo_order.supersedes_order_id is 'Reserved for phase 5 (SAH-51-5): a correction after sending is a new order that points at the one it replaces.';
comment on column opd.chemo_order.record_created_at is 'Part of the key of the partitioned opd.emr_record (id, created_at).';
