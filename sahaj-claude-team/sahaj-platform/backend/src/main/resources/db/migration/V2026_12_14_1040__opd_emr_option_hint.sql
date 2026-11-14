-- SAH-75 follow-up (ADR-026 D13 and "Data needed" item 9; opd.yaml 0.7.1; DEC-053 addendum) · One additive library column: the
-- short second line of an option ("Not using" under "None"). Forward-only. Needs V2026_12_14_1000 (is_alert, presentation).
-- Design note: docs/database/SAH-75.md (section 7). The Gynae data that uses it follows in V2026_12_14_1050 and the new starter layout
-- version in V2026_12_14_1060.
--
-- Scope: GLOBAL reference table opd.emr_section_field_option only (one ALTER TABLE). No row is read or written, so every existing
-- option keeps hint null. sahaj_app keeps SELECT only (V2026_12_08_1000); a new column needs no new grant. No clinic, department draft,
-- version, EMR record, form, course, patient or billing table is touched.
--
-- Immutability: opd.emr_starter_layout is not touched in this file; the block at the end asserts both triggers are still enabled.

set local lock_timeout = '30s';

alter table opd.emr_section_field_option
  add column if not exists hint text;

alter table opd.emr_section_field_option
  add constraint ck_emr_section_field_option_hint check (hint is null or char_length(hint) between 1 and 60);

comment on column opd.emr_section_field_option.hint is
  'Optional short second line of the option, 1 to 60 characters (SAH-75, ADR-026 D13). A chip field with at least one hint is drawn as tiles (label bold, hint below). Null means no second line. Value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052).';

-- ---------------------------------------------------------------------------------------------
-- Checks inside the transaction (a failure rolls the file back).
-- ---------------------------------------------------------------------------------------------
do $$
begin
  if not exists (select 1 from information_schema.columns
                  where table_schema = 'opd' and table_name = 'emr_section_field_option' and column_name = 'hint'
                    and data_type = 'text' and is_nullable = 'YES' and column_default is null) then
    raise exception 'SAH-75 hint: opd.emr_section_field_option.hint is missing or is not nullable text without a default';
  end if;
  if (select count(*) from pg_constraint
       where conrelid = 'opd.emr_section_field_option'::regclass and conname = 'ck_emr_section_field_option_hint' and contype = 'c') <> 1 then
    raise exception 'SAH-75 hint: the hint length check is missing';
  end if;
  if exists (select 1 from opd.emr_section_field_option where hint is not null) then
    raise exception 'SAH-75 hint: an existing option got a hint';
  end if;
  if (select count(*) from opd.emr_section_field_option) <> 227 then
    raise exception 'SAH-75 hint: the option count changed (expected 227)';
  end if;
  -- the starter layout immutability triggers are untouched and enabled
  if (select count(*) from pg_trigger
       where tgrelid = 'opd.emr_starter_layout'::regclass
         and tgname in ('trg_emr_starter_layout_immutable', 'trg_emr_starter_layout_no_truncate') and tgenabled = 'O') <> 2 then
    raise exception 'SAH-75 hint: the starter layout immutability triggers are not both enabled';
  end if;
end
$$;
