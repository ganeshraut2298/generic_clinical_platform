-- SAH-7 · opd.case_paper_settings : how the clinic's case paper prints. One row per clinic, created by
-- the first save; until then the app returns the defaults below (they match the mockup's S.paper).
-- Rules kept in the database:
--   sections_json holds only the nine mockup section keys, and rx is always true.
--   (The doctor's registration number is always printed and is not stored.)
-- sections_json shape (sections_schema_version 1): {"complaints":true,"vitals":true,"history":false,
--   "exam":true,"diagnosis":true,"inv":true,"rx":true,"advice":true,"next":true}
-- lang2 is the second language on the paper: null = English only, 'mr' = + Marathi, 'hi' = + Hindi.
create table if not exists opd.case_paper_settings (
  tenant_id                uuid primary key
                           constraint fk_case_paper_settings_tenant references platform.tenant (id),
  size                     text not null default 'A5'
                           constraint ck_case_paper_settings_size check (size in ('A5', 'A4')),
  lang2                    text
                           constraint ck_case_paper_settings_lang2 check (lang2 is null or lang2 in ('mr', 'hi')),
  preprinted               boolean not null default false,
  caps                     boolean not null default false,
  sections_schema_version  smallint not null default 1,
  sections_json            jsonb not null
                           default '{"complaints": true, "vitals": true, "history": false, "exam": true, "diagnosis": true, "inv": true, "rx": true, "advice": true, "next": true}'::jsonb
                           constraint ck_case_paper_settings_sections check (
                             jsonb_typeof(sections_json) = 'object'
                             and sections_json @> '{"rx": true}'::jsonb
                             and (sections_json - array['complaints', 'vitals', 'history', 'exam',
                                                        'diagnosis', 'inv', 'rx', 'advice', 'next']) = '{}'::jsonb),
  created_at               timestamptz not null default now(),
  updated_at               timestamptz,
  updated_by               uuid,
  version                  bigint not null default 0
);

alter table opd.case_paper_settings enable row level security;
alter table opd.case_paper_settings force row level security;

create policy p_case_paper_settings_tenant on opd.case_paper_settings
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on opd.case_paper_settings from sahaj_app;
revoke all on opd.case_paper_settings from public;
grant select, insert, update on opd.case_paper_settings to sahaj_app;

comment on table opd.case_paper_settings is
  'Case paper print settings per clinic (SAH-7): size, second language, pre-printed letterhead, capitals, sections. rx is always on. Row created on first save; defaults apply before that.';
