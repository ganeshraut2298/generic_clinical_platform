-- SAH-67-1 (ADR-025 D8, "Data needed" item 8) · The signed daily progress note of an admitted patient ("Round done").
-- Forward-only, additive. One new clinic table.
--   ipd.round_note   one row per admission per Asia/Kolkata day, written by ipd when a doctor signs the day's IPD_ROUND record
--                    in the EMR (through the opd.api port EmrRoundPort). It holds no clinical text except the one line
--                    "Investigations advised" for the nursing sheet; the record itself stays in opd.
--
-- Insert-only like the other ipd logs: no update, delete or truncate for any role (privilege, no policy, trigger). A retry
-- of the same sign finds the row (unique key) and is answered as "already recorded". Clinical: kept for the life of the clinic.
-- Row level security: ENABLE + FORCE, select and insert policies for sahaj_app on platform.current_tenant(), tenant-first indexes.
-- The admission is a composite key (tenant_id, admission_id) so the row cannot name another clinic's admission. emr_record_id is an
-- opd id: no foreign key (cross-module), one note per record.

create table if not exists ipd.round_note (
  id                         uuid primary key default gen_random_uuid(),
  tenant_id                  uuid not null default platform.current_tenant()
                             constraint fk_round_note_tenant references platform.tenant (id),
  admission_id               uuid not null,
  round_date                 date not null,
  emr_record_id              uuid not null,
  signed_by_user_id          uuid not null,
  signed_by_membership_id    uuid not null,
  signed_at                  timestamptz not null,
  orders_placed              integer not null default 0
                             constraint ck_round_note_orders_placed check (orders_placed >= 0),
  investigations_advised     text
                             constraint ck_round_note_investigations check (investigations_advised is null or char_length(btrim(investigations_advised)) between 1 and 1000),
  created_at                 timestamptz not null default now(),
  constraint uq_round_note_tenant_id unique (tenant_id, id),
  constraint uq_round_note_tenant_admission_date unique (tenant_id, admission_id, round_date),
  constraint uq_round_note_tenant_record unique (tenant_id, emr_record_id),
  constraint fk_round_note_admission foreign key (tenant_id, admission_id) references ipd.admission (tenant_id, id)
);

-- The Admitted list: which admissions have a note on the given day ("Round done"), index-only for one clinic and one date.
-- The latest note of one admission (the nursing sheet) and the one-per-day rule use the unique key
-- (tenant_id, admission_id, round_date).
create index if not exists ix_round_note_tenant_date_admission
  on ipd.round_note (tenant_id, round_date, admission_id);

alter table ipd.round_note enable row level security;
alter table ipd.round_note force row level security;

create policy p_round_note_select on ipd.round_note
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());
create policy p_round_note_insert on ipd.round_note
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

create trigger trg_round_note_no_update_delete
  before update or delete on ipd.round_note
  for each row execute function platform.deny_audit_mutation();
create trigger trg_round_note_no_truncate
  before truncate on ipd.round_note
  for each statement execute function platform.deny_audit_mutation();

-- Grants: take everything away first (the schema default privileges give all four commands). Column-level insert so id, the
-- clinic and created_at keep their defaults.
revoke all on ipd.round_note from sahaj_app;
revoke all on ipd.round_note from public;
grant select on ipd.round_note to sahaj_app;
grant insert (admission_id, round_date, emr_record_id, signed_by_user_id, signed_by_membership_id, signed_at,
              orders_placed, investigations_advised)
  on ipd.round_note to sahaj_app;

comment on table ipd.round_note is
  'The doctor''s signed daily progress note of an admission (SAH-67-1, ADR-025 D8): one per admission per Asia/Kolkata day, written by ipd through opd.api EmrRoundPort when the IPD_ROUND record is signed. Insert only: no update, delete or truncate (privilege, policy and trigger). emr_record_id is an opaque opd id. investigations_advised is a clinical line for the nursing sheet; never logged or audited by value. Life of the clinic; one row per admitted day at most.';
comment on column ipd.round_note.round_date is 'The Asia/Kolkata calendar day of the round (the IPD_ROUND record''s round_date).';
comment on column ipd.round_note.investigations_advised is 'The active investigation texts of the signed round joined with "; ", at most 1,000 characters (nursing sheet line "Investigations advised"). No lab order is made. Clinical text; never logged.';
