-- SAH-55, SAH-59 (ADR-022 D9, D11, D13) · Three additive columns on opd.emr_record. Forward-only.
--   print_language  the visit's chosen print language (EN, MR, HI); null = the clinic's Case paper language (SAH-55)
--   paper_size      the visit's chosen paper size (A4, A5); null = the clinic's Case paper size (SAH-55)
--   form_refs       the form versions this record opened with: [{formKey, versionNo, part, position, required, print, copy,
--                   collapsed, whoCanFill}] (SAH-59). Form versions are immutable, so the reference is as safe as the
--                   layout reference. A json array of at most 16 KB.
-- The two nullable columns and the not null column with a constant default are a catalog change only (no table rewrite),
-- and the previous application version keeps working (it neither reads nor writes them). The columns are added to the
-- partitioned parent and so to every monthly partition and the default partition. No new index.
-- Row level security, the partitions and the grants of the table are unchanged; only the update grant is extended.

alter table opd.emr_record
  add column if not exists print_language text
    constraint ck_emr_record_print_language check (print_language is null or print_language in ('EN', 'MR', 'HI')),
  add column if not exists paper_size text
    constraint ck_emr_record_paper_size check (paper_size is null or paper_size in ('A4', 'A5')),
  add column if not exists form_refs jsonb not null default '[]'::jsonb
    constraint ck_emr_record_form_refs_shape check (jsonb_typeof(form_refs) = 'array')
    constraint ck_emr_record_form_refs_size check (octet_length(form_refs::text) <= 16384);

-- Column-level update: print preferences are saved by the doctor on a DRAFT or SIGNED record (SAH-55), and the form
-- references are written when the record is opened or its layout is refreshed with live forms (SAH-59).
grant update (print_language, paper_size, form_refs) on opd.emr_record to sahaj_app;

comment on column opd.emr_record.print_language is 'Per-visit print language EN, MR or HI (SAH-55); null = the clinic default. Not clinical, not audited.';
comment on column opd.emr_record.paper_size is 'Per-visit paper size A4 or A5 (SAH-55); null = the clinic default. Not clinical, not audited.';
comment on column opd.emr_record.form_refs is 'Json array of the form versions held by this record: [{formKey, versionNo, part, position, required, print, copy, collapsed, whoCanFill}] (SAH-59, ADR-022 D11). Immutable versions, so later publishes never change an opened record.';
