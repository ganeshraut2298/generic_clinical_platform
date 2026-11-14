-- SAH-59 (ADR-022 D11) · Clinic forms of the EMR form builder. Forward-only, additive.
--   opd.emr_form          one row per form of the clinic: the working draft, the live version number, the approval state
--   opd.emr_form_version  one immutable row per approved or published version, kept for ever
-- Every table: the clinic column defaults to platform.current_tenant() (the application never writes it), ENABLE + FORCE row
-- level security, policy for sahaj_app on platform.current_tenant(), the first index column is the clinic column, and every
-- reference to another clinic-owned row is a composite key including it. Setup data, no patient data. No delete anywhere.

-- ---------------------------------------------------------------------------------------------
-- 1. opd.emr_form
--    form_key: server-made f_ + 10 lower-case base32 characters, never reused; it is also the section key of the form in
--    department layouts and records. Library section keys never start with f_ or d_.
--    Version numbers: live_version_no is the live version (0 = never published); the working draft is always the next one,
--    draft_version_no = live_version_no + 1 (the check keeps it so). draft_state NONE = no working draft (right after
--    an approval or a publish); a form never published has a draft.
--    draft_definition: schemaVersion 1, name, settings, fields (0 to 80 in a draft; the publish checks are in the
--    application), at most 256 KB.
--    Names are unique in the clinic ignoring case (DEC-042 H15). At most 100 forms per clinic (application rule, 400 form-limit).
--    idempotency_key / request_hash: POST /emr/forms is idempotent (opd.yaml 0.4.0); the same key with the same body returns the
--    first form, with another body 409 idempotency-conflict. Added to the ADR-022 column list because the contract needs them.
--    revision: optimistic token (409 form-changed), +1 per change.
--    Updates are column-limited: never the id, the clinic, the key, the starter or the created stamps.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_form (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_emr_form_tenant references platform.tenant (id),
  form_key          text not null
                    constraint ck_emr_form_key check (form_key ~ '^f_[a-z2-7]{10}$'),
  name              text not null
                    constraint ck_emr_form_name check (char_length(btrim(name)) between 1 and 80),
  starter_key       text
                    constraint fk_emr_form_starter references opd.emr_form_starter (starter_key),
  status            text not null default 'ACTIVE'
                    constraint ck_emr_form_status check (status in ('ACTIVE', 'RETIRED')),
  live_version_no   integer not null default 0
                    constraint ck_emr_form_live_version_no check (live_version_no >= 0),
  draft_version_no  integer not null default 1
                    constraint ck_emr_form_draft_version_no check (draft_version_no >= 1),
  draft_state       text not null default 'DRAFT'
                    constraint ck_emr_form_draft_state check (draft_state in ('NONE', 'DRAFT', 'WAITING_APPROVAL', 'SENT_BACK')),
  draft_definition  jsonb
                    constraint ck_emr_form_draft_shape check (
                      draft_definition is null
                      or (jsonb_typeof(draft_definition) = 'object'
                          and coalesce(jsonb_typeof(draft_definition -> 'schemaVersion'), '') = 'number'
                          and coalesce(jsonb_typeof(draft_definition -> 'fields'), '') = 'array'))
                    constraint ck_emr_form_draft_size check (draft_definition is null or octet_length(draft_definition::text) <= 262144),
  sent_back_reason  text
                    constraint ck_emr_form_sent_back_reason check (sent_back_reason is null or char_length(btrim(sent_back_reason)) between 1 and 300),
  submitted_at      timestamptz,
  submitted_by      uuid,
  idempotency_key   text
                    constraint ck_emr_form_idempotency_key check (idempotency_key is null or idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  request_hash      text
                    constraint ck_emr_form_request_hash check (request_hash is null or char_length(request_hash) between 16 and 128),
  revision          bigint not null default 0
                    constraint ck_emr_form_revision check (revision >= 0),
  created_at        timestamptz not null default now(),
  created_by        uuid not null,
  updated_at        timestamptz,
  updated_by        uuid,
  constraint uq_emr_form_tenant_key unique (tenant_id, form_key),
  constraint uq_emr_form_tenant_id unique (tenant_id, id),
  constraint uq_emr_form_tenant_idempotency unique (tenant_id, idempotency_key),
  -- the working draft is the next version
  constraint ck_emr_form_draft_is_next check (draft_version_no = live_version_no + 1),
  -- a working draft exists exactly when the state is not NONE; a form is never empty (a draft or a live version)
  constraint ck_emr_form_draft_present check ((draft_state = 'NONE') = (draft_definition is null)),
  constraint ck_emr_form_not_empty check (live_version_no >= 1 or draft_definition is not null),
  -- the send-back reason belongs to the SENT_BACK state only
  constraint ck_emr_form_sent_back check ((draft_state = 'SENT_BACK') = (sent_back_reason is not null)),
  -- a form waiting for approval has been submitted by someone at some time; the pair is set or null together
  constraint ck_emr_form_submitted_pair check ((submitted_at is null) = (submitted_by is null)),
  constraint ck_emr_form_waiting_submitted check (draft_state <> 'WAITING_APPROVAL' or submitted_at is not null),
  -- an idempotent create carries both parts
  constraint ck_emr_form_idempotency_pair check ((idempotency_key is null) = (request_hash is null))
);

-- One name per clinic, ignoring case (H15). Also the forms list of a clinic, in name order (at most 100 rows).
create unique index if not exists uq_emr_form_tenant_name_lower on opd.emr_form (tenant_id, lower(name));

alter table opd.emr_form enable row level security;
alter table opd.emr_form force row level security;

create policy p_emr_form_tenant on opd.emr_form
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- 2. opd.emr_form_version: one row per approved (or published without approval) version. The definition is the full form
--    (schemaVersion 1, name, settings, fields 1 to 80), at most 256 KB. IMMUTABLE and kept for ever: records point to
--    (form_key, version_no) in emr_record.form_refs. approved_by / approved_at are null when approval was not required.
--    The version number is live_version_no + 1 taken under the form row lock; the unique key refuses a second publish of
--    the same number.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_form_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_emr_form_version_tenant references platform.tenant (id),
  form_id       uuid not null,
  version_no    integer not null
                constraint ck_emr_form_version_no check (version_no >= 1),
  definition    jsonb not null
                constraint ck_emr_form_version_shape check (
                  jsonb_typeof(definition) = 'object'
                  and coalesce(jsonb_typeof(definition -> 'schemaVersion'), '') = 'number'
                  and coalesce(jsonb_typeof(definition -> 'fields'), '') = 'array')
                constraint ck_emr_form_version_size check (octet_length(definition::text) <= 262144),
  approved_by   uuid,
  approved_at   timestamptz,
  published_at  timestamptz not null default now(),
  published_by  uuid not null,
  constraint uq_emr_form_version_no unique (tenant_id, form_id, version_no),
  constraint uq_emr_form_version_tenant_id unique (tenant_id, id),
  constraint ck_emr_form_version_approved_pair check ((approved_by is null) = (approved_at is null)),
  constraint fk_emr_form_version_form foreign key (tenant_id, form_id)
    references opd.emr_form (tenant_id, id)
);

alter table opd.emr_form_version enable row level security;
alter table opd.emr_form_version force row level security;

-- Select and insert policies only: no update or delete policy, so row level security refuses them as well.
create policy p_emr_form_version_select on opd.emr_form_version
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_emr_form_version_insert on opd.emr_form_version
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

create trigger trg_emr_form_version_immutable
  before update or delete on opd.emr_form_version
  for each row execute function opd.deny_emr_immutable_change();
create trigger trg_emr_form_version_no_truncate
  before truncate on opd.emr_form_version
  for each statement execute function opd.deny_emr_immutable_change();

-- ---------------------------------------------------------------------------------------------
-- 3. Grants: the schema default privileges give sahaj_app all four commands, so take everything away first.
-- ---------------------------------------------------------------------------------------------
revoke all on opd.emr_form, opd.emr_form_version from sahaj_app;
revoke all on opd.emr_form, opd.emr_form_version from public;

grant select, insert on opd.emr_form to sahaj_app;
grant update (name, status, live_version_no, draft_version_no, draft_state, draft_definition, sent_back_reason,
              submitted_at, submitted_by, revision, updated_at, updated_by)
  on opd.emr_form to sahaj_app;

grant select, insert on opd.emr_form_version to sahaj_app;

comment on table opd.emr_form is
  'A clinic form of the EMR form builder (SAH-59, ADR-022 D11): the working draft, live version number, approval state (NONE, DRAFT, WAITING_APPROVAL, SENT_BACK) and revision. form_key is also the section key of the form in layouts and records. Setup data, no patient data. Never deleted (retire is a status). At most 100 rows per clinic.';
comment on table opd.emr_form_version is
  'Immutable published versions of a clinic form (SAH-59, ADR-022 D11): one row per approval or publish with the full definition. Update, delete and truncate are refused (privilege, row level security and trigger). Kept for ever: EMR records point to (form_key, version_no). No patient data.';
