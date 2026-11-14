-- SAH-59 (ADR-022 D11) · Starter forms for the form builder. Forward-only, additive.
-- Global reference data: no clinic owns a row, so no clinic column and no row level security (same kind as
-- opd.emr_starter_layout). sahaj_app gets SELECT only. IMMUTABLE: a shipped starter is never updated or deleted
-- (trigger, as opd.emr_starter_layout); a changed starter is a new starter_key added by a migration.
-- "Use this starter" copies the definition into a clinic's own draft; the starter never changes.
-- The six rows are seeded in V2026_12_10_1020 from the mockup's #FormBuilder (window.__DC_SRC FormBuilder, TEMPLATES).
-- Values come from the EMR mockup, accepted by the human in DEC-039, NOT clinically reviewed.

create table if not exists opd.emr_form_starter (
  starter_key  text not null
               constraint ck_emr_form_starter_key check (starter_key ~ '^[a-z][a-z0-9_]{1,39}$'),
  name         text not null
               constraint ck_emr_form_starter_name check (char_length(btrim(name)) between 1 and 80),
  description  text not null
               constraint ck_emr_form_starter_description check (char_length(btrim(description)) between 1 and 200),
  suited_for   text not null
               constraint ck_emr_form_starter_suited_for check (char_length(btrim(suited_for)) between 1 and 200),
  position     integer not null
               constraint ck_emr_form_starter_position check (position >= 1),
  definition   jsonb not null
               constraint ck_emr_form_starter_shape check (
                 jsonb_typeof(definition) = 'object'
                 and coalesce(jsonb_typeof(definition -> 'schemaVersion'), '') = 'number'
                 and coalesce(jsonb_typeof(definition -> 'fields'), '') = 'array')
               constraint ck_emr_form_starter_size check (octet_length(definition::text) <= 262144),
  created_at   timestamptz not null default now(),
  constraint pk_emr_form_starter primary key (starter_key),
  constraint uq_emr_form_starter_position unique (position)
);

-- Same function as the starter layouts (V2026_12_08_1000): refuses UPDATE, DELETE and TRUNCATE for every role.
create trigger trg_emr_form_starter_immutable
  before update or delete on opd.emr_form_starter
  for each row execute function opd.deny_emr_immutable_change();
create trigger trg_emr_form_starter_no_truncate
  before truncate on opd.emr_form_starter
  for each statement execute function opd.deny_emr_immutable_change();

revoke all on opd.emr_form_starter from sahaj_app;
revoke all on opd.emr_form_starter from public;
grant select on opd.emr_form_starter to sahaj_app;

comment on table opd.emr_form_starter is
  'Global read-only immutable starter forms for the EMR form builder (SAH-59, ADR-022 D11): six rows from the mockup (diabetic_foot, gcs, morse, mlc_injury, pre_anaesthetic, untitled) with the full definition (fields, options, scores, rules, bands, ranges, show-if). Update, delete and truncate are refused by trigger. Changed by migration only (a new key). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed.';
comment on column opd.emr_form_starter.definition is 'Form definition, schemaVersion 1 (ADR-022 D11 shape: name, settings, fields). A clinic gets a copy as draft v1, part ASSESSMENT.';
