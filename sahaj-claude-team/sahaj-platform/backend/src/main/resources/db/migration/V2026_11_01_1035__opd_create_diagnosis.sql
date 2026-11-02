-- SAH-7 · opd.diagnosis : global ICD-10 diagnosis list for the case paper. Read-only for the application.
-- No clinic owns these rows, so no row level security; sahaj_app gets SELECT only.
--   code       : ICD-10 code, for example J06.9
--   name       : official ICD-10 title (WHO wording, shortened where the title is very long)
--   specialty  : the specialty that uses it most (hint for ordering only; never filter by it)
create table if not exists opd.diagnosis (
  code       text primary key
             constraint ck_diagnosis_code check (code ~ '^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$'),
  name       text not null
             constraint ck_diagnosis_name check (char_length(btrim(name)) between 1 and 200),
  specialty  text not null default 'gp'
             constraint ck_diagnosis_specialty check (specialty in
               ('gp', 'dental', 'paeds', 'homeo', 'ayur', 'physio', 'derma', 'gynae')),
  created_at timestamptz not null default now()
);

-- Search by name prefix: where lower(name) like lower(:q) || '%'
create index if not exists ix_diagnosis_name_lower on opd.diagnosis (lower(name) text_pattern_ops);
-- Search by code prefix: where code like upper(:q) || '%'  (the primary key index is not usable for LIKE
-- under a non-C collation, so this one is needed)
create index if not exists ix_diagnosis_code_prefix on opd.diagnosis (code text_pattern_ops);

revoke all on opd.diagnosis from sahaj_app;
revoke all on opd.diagnosis from public;
grant select on opd.diagnosis to sahaj_app;

comment on table opd.diagnosis is
  'Global read-only starter ICD-10 list (SAH-7). Changed by migration only. Starter list: a clinician must review it before real use.';
