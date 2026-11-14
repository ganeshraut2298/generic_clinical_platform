-- SAH-51-5 (ADR-025 D3, "Data needed" item 2) · Addenda to signed EMR records. Forward-only, additive. One new clinic table.
--   opd.emr_record_addendum   a correction or addition to a SIGNED record: reason, text, who, when. Append-only.
--
-- Clinical data, kept for ever (never deleted, never updated). The reason and the text are clinical: never in a log line or an
-- audit value (the audit EMR_ADDENDUM_ADDED holds ids only).
-- Row level security: ENABLE + FORCE, policies for sahaj_app on platform.current_tenant() for select and insert only (no update
-- or delete policy), tenant-first index. The reference to the record is a composite key that includes the clinic column
-- (foreign key checks ignore row level security): (tenant_id, record_id, record_created_at) on the unique key
-- uq_emr_record_tenant_id_created_at of the partitioned opd.emr_record (V2026_12_11_1100). No foreign key to
-- platform.membership or any user table (cross-module): both ids are opaque, checked by the application.
-- Append-only is enforced three ways: no update, delete or truncate privilege for sahaj_app, no policy for them, and a trigger
-- that refuses them for every role (the table owner included) through the shared platform.deny_audit_mutation.
-- Rules left to the application (they depend on other rows): the record is SIGNED when the addendum is added; at most 50 per
-- record (400 addendum-limit); the doctor of the record or the owner with doctor details.
-- The text column is named addendum_text (a column simply named text is legal but easy to misquote in generated SQL).
-- request_hash is not in the ADR list: the contract answers 409 idempotency-conflict for a key reused with another body, which
-- needs the hash of the first body (same pattern as opd.treatment_course).

create table if not exists opd.emr_record_addendum (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                             constraint fk_emr_record_addendum_tenant references platform.tenant (id),
  record_id                  uuid not null,
  record_created_at          timestamptz not null,
  reason                     text not null
                             constraint ck_emr_record_addendum_reason check (char_length(btrim(reason)) between 1 and 200),
  addendum_text              text not null
                             constraint ck_emr_record_addendum_text check (char_length(btrim(addendum_text)) between 1 and 4000),
  created_by_membership_id   uuid not null,
  created_by_user_id         uuid not null,
  idempotency_key            text not null
                             constraint ck_emr_record_addendum_idempotency_key check (idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash               text not null
                             constraint ck_emr_record_addendum_request_hash check (request_hash ~ '^[0-9a-f]{64}$'),
  created_at                 timestamptz not null default clock_timestamp(),
  constraint uq_emr_record_addendum_tenant_id unique (tenant_id, id),
  constraint uq_emr_record_addendum_tenant_idempotency unique (tenant_id, idempotency_key),
  constraint fk_emr_record_addendum_record foreign key (tenant_id, record_id, record_created_at)
    references opd.emr_record (tenant_id, id, created_at)
);

-- The addenda of one record, oldest first (the view shows them newest last; at most 50): the record view, history details,
-- the case-paper context and the 50 limit count.
create index if not exists ix_emr_record_addendum_tenant_record_created
  on opd.emr_record_addendum (tenant_id, record_id, created_at);

alter table opd.emr_record_addendum enable row level security;
alter table opd.emr_record_addendum force row level security;

create policy p_emr_record_addendum_select on opd.emr_record_addendum
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_emr_record_addendum_insert on opd.emr_record_addendum
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

create trigger trg_emr_record_addendum_no_update_delete
  before update or delete on opd.emr_record_addendum
  for each row execute function platform.deny_audit_mutation();
create trigger trg_emr_record_addendum_no_truncate
  before truncate on opd.emr_record_addendum
  for each statement execute function platform.deny_audit_mutation();

-- Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- Column-level insert: id, the clinic and created_at keep their defaults (the clinic is never chosen by the caller).
revoke all on opd.emr_record_addendum from sahaj_app;
revoke all on opd.emr_record_addendum from public;
grant select on opd.emr_record_addendum to sahaj_app;
grant insert (record_id, record_created_at, reason, addendum_text, created_by_membership_id, created_by_user_id,
              idempotency_key, request_hash)
  on opd.emr_record_addendum to sahaj_app;

comment on table opd.emr_record_addendum is
  'Correction or addition to a SIGNED EMR record (SAH-51-5, ADR-025 D3): reason, text, signer membership and user, database time. Append-only: no update, delete or truncate (privilege, policy and trigger). At most 50 per record (application). Idempotent by (clinic, idempotency_key) with a request hash. Clinical text: never logged or audited by value. Kept for the life of the clinic. A few rows per thousand records.';
comment on column opd.emr_record_addendum.addendum_text is 'The correction or addition, plain text 1 to 4,000 after trimming. Clinical text; never in a log line or an audit value.';
comment on column opd.emr_record_addendum.record_created_at is 'Part of the key of the partitioned opd.emr_record (id, created_at).';
