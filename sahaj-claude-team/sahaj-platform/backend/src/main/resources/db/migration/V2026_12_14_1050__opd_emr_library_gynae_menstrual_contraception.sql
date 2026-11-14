-- SAH-75 follow-up (ADR-026 D13 and "Data needed" items 10, 11; opd.yaml 0.7.1; DEC-053 addendum) · Gynae Menstrual history and
-- Contraception as drawn: library data. Forward-only. Needs V2026_12_14_1040 (option hint) and V2026_12_14_1000 (presentation).
-- Design note: docs/database/SAH-75.md (section 7). The new starter layout version that copies this data follows in V2026_12_14_1060.
--
-- Scope: GLOBAL reference tables of schema opd only: emr_section_field (update in place, insert) and emr_section_field_option (insert).
-- No clinic, department draft, department version, EMR record, form, course, patient or billing row is read or written. emr_section is
-- not changed (107 sections, descriptions stay). Starter layout versions 1 and 2 are not touched (this file never writes
-- opd.emr_starter_layout; the block at the end asserts that both immutability triggers are still enabled).
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. Every label, option and hint below is a value from the EMR mockup
-- (docs/design/emr-config/emr-config-design-handoff/emr-config/source/DoctorGynae.dc.html lines 189-212 and 372-378), accepted by the human
-- in DEC-053 (addendum), not clinically reviewed (SAH-75, DEC-052).
--
-- Keys are kept and two fields change type IN PLACE (first in-place retype of a library field, ADR-026 D13): menstrual.cycle
-- (PATTERN -> SINGLE_CHOICE) and menstrual.dysmenorrhoea (TOGGLE -> SINGLE_CHOICE, label "Pain"); menstrual.flow keeps CHOICE kind
-- (DROPDOWN -> SINGLE_CHOICE, stored keys unchanged). Safe because every record validates against its own snapshot and starter layout
-- versions hold copied definitions (ADR-020 D6, D8); the copy-from-last-visit fit rule is application code (java-developer).
-- No option is is_none or is_alert (single-choice rows). Option keys follow the library rule (a digit start gets an "o" prefix).
--
-- Field positions are unique per section (uq_emr_section_field_position, not deferrable). The updates below run from the last position
-- backwards so that no statement meets an occupied position; no temporary position is needed.
--   menstrual    before: lmp 1, cycle 2, flow 3, dysmenorrhoea 4, age_at_menarche 5
--                after : lmp 1, cycle 2, cycle_length 3, flow 4, dysmenorrhoea 5, age_at_menarche 6
--   contraception before: findings 1, template 2, attachments 3
--                after : method 1, findings 2, template 3, attachments 4

set local lock_timeout = '30s';

-- ---------------------------------------------------------------------------------------------
-- 1. Menstrual history: fields (ADR-026 D13 / Data needed item 10)
-- ---------------------------------------------------------------------------------------------
update opd.emr_section_field set position = 6
 where section_key = 'menstrual' and field_key = 'age_at_menarche';

update opd.emr_section_field
   set position = 5, label = 'Pain', field_type = 'SINGLE_CHOICE', meta = '', options_source = 'FIELD', options_master = null,
       allow_free_text = false, presentation = 'CHIPS'
 where section_key = 'menstrual' and field_key = 'dysmenorrhoea';

update opd.emr_section_field
   set position = 4, field_type = 'SINGLE_CHOICE', meta = '', presentation = 'CHIPS'
 where section_key = 'menstrual' and field_key = 'flow';

update opd.emr_section_field
   set field_type = 'SINGLE_CHOICE', meta = '', options_source = 'FIELD', options_master = null, allow_free_text = false, presentation = 'CHIPS'
 where section_key = 'menstrual' and field_key = 'cycle';

insert into opd.emr_section_field
  (section_key, field_key, position, label, field_type, meta, required, options_source, options_master, allow_free_text, columns, presentation) values
  ('menstrual', 'cycle_length', 3, 'Cycle length', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS');

-- ---------------------------------------------------------------------------------------------
-- 2. Menstrual history: options (17 in total with section 4; no hint, no none, no alert)
--    flow keeps scanty 1, normal 2, heavy 3 (V2026_12_08_1020) and gets with_clots 4.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_section_field_option (section_key, field_key, option_key, position, label, is_none, is_alert, hint) values
  ('menstrual', 'cycle', 'regular', 1, 'Regular', false, false, null),
  ('menstrual', 'cycle', 'irregular', 2, 'Irregular', false, false, null),

  ('menstrual', 'cycle_length', 'under_21_d', 1, '< 21 d', false, false, null),
  ('menstrual', 'cycle_length', 'o21_27_d', 2, '21–27 d', false, false, null),
  ('menstrual', 'cycle_length', 'o28_30_d', 3, '28–30 d', false, false, null),
  ('menstrual', 'cycle_length', 'o31_35_d', 4, '31–35 d', false, false, null),
  ('menstrual', 'cycle_length', 'over_35_d', 5, '> 35 d', false, false, null),

  ('menstrual', 'flow', 'with_clots', 4, 'With clots', false, false, null),

  ('menstrual', 'dysmenorrhoea', 'none', 1, 'None', false, false, null),
  ('menstrual', 'dysmenorrhoea', 'mild', 2, 'Mild', false, false, null),
  ('menstrual', 'dysmenorrhoea', 'severe', 3, 'Severe', false, false, null);

-- ---------------------------------------------------------------------------------------------
-- 3. Contraception: new "Method" field at position 1, the three generic fields move down one (item 11). Moved from the last one up.
-- ---------------------------------------------------------------------------------------------
update opd.emr_section_field set position = 4 where section_key = 'contraception' and field_key = 'attachments';
update opd.emr_section_field set position = 3 where section_key = 'contraception' and field_key = 'template';
update opd.emr_section_field set position = 2 where section_key = 'contraception' and field_key = 'findings';

insert into opd.emr_section_field
  (section_key, field_key, position, label, field_type, meta, required, options_source, options_master, allow_free_text, columns, presentation) values
  ('contraception', 'method', 1, 'Method', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS');

insert into opd.emr_section_field_option (section_key, field_key, option_key, position, label, is_none, is_alert, hint) values
  ('contraception', 'method', 'none', 1, 'None', false, false, 'Not using'),
  ('contraception', 'method', 'condoms', 2, 'Condoms', false, false, 'Barrier'),
  ('contraception', 'method', 'ocp', 3, 'OCP', false, false, 'Daily pill'),
  ('contraception', 'method', 'cu_t', 4, 'Cu-T', false, false, 'IUCD, 5–10 years'),
  ('contraception', 'method', 'injectable', 5, 'Injectable', false, false, 'Every 3 months'),
  ('contraception', 'method', 'sterilisation', 6, 'Sterilisation', false, false, 'Permanent');

-- ---------------------------------------------------------------------------------------------
-- 4. Table comments (counts and tag; the column comments of V2026_12_14_1000 and V2026_12_14_1040 stay).
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_section_field is
  'Global read-only fields of an EMR library section (SAH-53, ADR-020 D3; SAH-75 adds 20 Dental and Local examination fields and, in the follow-up, 2 Gynae fields): label, type (30 values), meta hint, required flag, options source, presentation (DEFAULT or CHIPS). Sections with no field list in the mockup carry the mockup FALLBACK fields (Findings, Template, Attachments). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, DEC-052 and DEC-053, not clinically reviewed (SAH-75, DEC-052).';
comment on table opd.emr_section_field_option is
  'Global read-only fixed options of a library field with options_source FIELD (SAH-53; SAH-75 adds the Dental chips and, in the follow-up, the Gynae menstrual and contraception options). is_none clears the other chips of the field; is_alert shows the chip red when chosen; hint is an optional second line (tiles). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, DEC-052 and DEC-053, not clinically reviewed (SAH-75, DEC-052).';

-- ---------------------------------------------------------------------------------------------
-- 5. Checks inside the transaction (a failure rolls the whole file back).
-- ---------------------------------------------------------------------------------------------
do $$
declare
  have text;
begin
  -- totals: sections unchanged at 107, fields 333 + 2, options 227 + 17
  if (select count(*) from opd.emr_section) <> 107
     or (select count(*) from opd.emr_section_field) <> 335
     or (select count(*) from opd.emr_section_field_option) <> 244 then
    raise exception 'SAH-75 gynae: expected 107 sections, 335 fields and 244 options';
  end if;

  -- field order and types
  select string_agg(field_key || ':' || position || ':' || field_type || ':' || presentation, ',' order by position) into have
    from opd.emr_section_field where section_key = 'menstrual';
  if have is distinct from 'lmp:1:DATE:DEFAULT,cycle:2:SINGLE_CHOICE:CHIPS,cycle_length:3:SINGLE_CHOICE:CHIPS,flow:4:SINGLE_CHOICE:CHIPS,dysmenorrhoea:5:SINGLE_CHOICE:CHIPS,age_at_menarche:6:NUMBER:DEFAULT' then
    raise exception 'SAH-75 gynae: menstrual fields are [%]', have;
  end if;
  select string_agg(field_key || ':' || position, ',' order by position) into have
    from opd.emr_section_field where section_key = 'contraception';
  if have is distinct from 'method:1,findings:2,template:3,attachments:4' then
    raise exception 'SAH-75 gynae: contraception fields are [%]', have;
  end if;

  -- label, meta, source, free text, required as in ADR-026 D13 (lmp stays the only required menstrual field)
  if not exists (select 1 from opd.emr_section_field where section_key = 'menstrual' and field_key = 'dysmenorrhoea' and label = 'Pain')
     or exists (select 1 from opd.emr_section_field
                 where section_key = 'menstrual' and field_key in ('cycle', 'cycle_length', 'flow', 'dysmenorrhoea')
                   and (meta <> '' or options_source <> 'FIELD' or options_master is not null or allow_free_text or required))
     or not exists (select 1 from opd.emr_section_field where section_key = 'menstrual' and field_key = 'lmp' and required)
     or exists (select 1 from opd.emr_section_field where section_key = 'menstrual' and field_key <> 'lmp' and required)
     or not exists (select 1 from opd.emr_section_field
                     where section_key = 'contraception' and field_key = 'method' and label = 'Method'
                       and meta = '' and options_source = 'FIELD' and not allow_free_text and not required) then
    raise exception 'SAH-75 gynae: a field has the wrong label, meta, source, free-text or required flag';
  end if;
  if exists (select 1 from opd.emr_section_field
              where presentation = 'CHIPS' and section_key in ('menstrual', 'contraception') and field_type <> 'SINGLE_CHOICE') then
    raise exception 'SAH-75 gynae: a Gynae chip field is not single choice';
  end if;

  -- options per field, in order
  for have in
    select o.field_key || '=' || string_agg(o.option_key || ':' || o.label || coalesce(':' || o.hint, ''), '|' order by o.position)
      from opd.emr_section_field_option o
     where o.section_key in ('menstrual', 'contraception')
     group by o.section_key, o.field_key
  loop
    if have not in (
        'cycle=regular:Regular|irregular:Irregular',
        'cycle_length=under_21_d:< 21 d|o21_27_d:21–27 d|o28_30_d:28–30 d|o31_35_d:31–35 d|over_35_d:> 35 d',
        'flow=scanty:Scanty|normal:Normal|heavy:Heavy|with_clots:With clots',
        'dysmenorrhoea=none:None|mild:Mild|severe:Severe',
        'method=none:None:Not using|condoms:Condoms:Barrier|ocp:OCP:Daily pill|cu_t:Cu-T:IUCD, 5–10 years|injectable:Injectable:Every 3 months|sterilisation:Sterilisation:Permanent') then
      raise exception 'SAH-75 gynae: unexpected option list [%]', have;
    end if;
  end loop;
  if (select count(*) from opd.emr_section_field_option where section_key in ('menstrual', 'contraception')) <> 20 then
    raise exception 'SAH-75 gynae: expected 20 Gynae options';
  end if;

  -- no option is none or alert here; a hint exists on the six contraception methods only
  if exists (select 1 from opd.emr_section_field_option where section_key in ('menstrual', 'contraception') and (is_none or is_alert))
     or (select count(*) from opd.emr_section_field_option where hint is not null) <> 6
     or exists (select 1 from opd.emr_section_field_option where hint is not null and (section_key, field_key) <> ('contraception', 'method')) then
    raise exception 'SAH-75 gynae: a Gynae option has a wrong none, alert or hint value';
  end if;

  -- reserved key rule (ADR-023) and library flags of every other field are unchanged
  if exists (select 1 from opd.emr_section_field where field_key = 'specialty') then
    raise exception 'SAH-75 gynae: a field uses the reserved key specialty';
  end if;
  if (select count(*) from opd.emr_section_field where presentation = 'CHIPS') <> 24 then
    raise exception 'SAH-75 gynae: expected 24 CHIPS fields (19 Dental, 4 Menstrual, 1 Contraception)';
  end if;

  -- the starter layout immutability triggers are untouched and enabled; nothing wrote a layout here
  if (select count(*) from opd.emr_starter_layout) <> 30 then
    raise exception 'SAH-75 gynae: the starter layout rows changed';
  end if;
  if (select count(*) from pg_trigger
       where tgrelid = 'opd.emr_starter_layout'::regclass
         and tgname in ('trg_emr_starter_layout_immutable', 'trg_emr_starter_layout_no_truncate') and tgenabled = 'O') <> 2 then
    raise exception 'SAH-75 gynae: the starter layout immutability triggers are not both enabled';
  end if;
end
$$;
