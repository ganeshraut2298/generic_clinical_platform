-- SAH-75 follow-up (ADR-026 D13, D14 and "Data needed" items 12, 13; opd.yaml 0.7.1; DEC-053 addendum) · Oncology Assessment card order as
-- drawn, and a NEW immutable starter layout version (3) for the og and onco starters only. Forward-only.
-- Needs V2026_12_14_1050 (Gynae library data) and V2026_12_14_1010 (version 2). Design note: docs/database/SAH-75.md (section 7).
--
-- Scope: GLOBAL reference tables of schema opd only: emr_starter_department_section (onco positions), emr_starter_layout (insert only)
-- and emr_starter_department (current_layout_version of og and onco). No clinic, department draft, department version, EMR record, form,
-- course, patient or billing row is read or written. A record keeps the starter layout version it opened with (ADR-020 D8); versions 1
-- and 2 of every starter stay byte for byte as they are. The 13 other starters stay at version 2.
--
-- Immutability: opd.emr_starter_layout refuses UPDATE, DELETE and TRUNCATE by trigger (trg_emr_starter_layout_immutable,
-- trg_emr_starter_layout_no_truncate). This migration only INSERTS version 3 rows, which the triggers allow, so no trigger is disabled
-- (as in V2026_12_14_1010). The block at the end asserts that both triggers are still enabled.
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. The Oncology card order is the numbering of the design
-- (docs/design/emr-config/emr-config-design-handoff/emr-config/source/DoctorOncology.dc.html lines 84-233), accepted by the human in
-- DEC-053 (addendum), not clinically reviewed (SAH-75, DEC-052).
--
-- Result:
--   onco  onc_tox 17, onc_bsa 18, onc_labs 19, onc_ecog 20 (was onc_ecog 17, onc_tox 18, onc_bsa 19, onc_labs 20); part ASSESSMENT and every
--         other flag unchanged; no other onco row moves.
--   og    placements unchanged; the version 3 document copies the new Menstrual history and Contraception definitions (V2026_12_14_1050).
--   both  layout version 3, current_layout_version 3.

set local lock_timeout = '30s';

-- ---------------------------------------------------------------------------------------------
-- 1. Onco order (D14). uq_emr_starter_department_section_position is not deferrable: the four rows first move out of the way (+1000),
--    then take their final positions (all four finals, 17 to 20, are free once the four have moved).
-- ---------------------------------------------------------------------------------------------
update opd.emr_starter_department_section set position = position + 1000
 where starter_key = 'onco' and section_key in ('onc_ecog', 'onc_tox', 'onc_bsa', 'onc_labs');

update opd.emr_starter_department_section p
   set position = o.new_position
  from (values ('onc_tox', 17), ('onc_bsa', 18), ('onc_labs', 19), ('onc_ecog', 20)) as o(section_key, new_position)
 where p.starter_key = 'onco' and p.section_key = o.section_key;

-- ---------------------------------------------------------------------------------------------
-- 2. Starter layout version 3 for og and onco (item 13). Same builder as version 2 (V2026_12_14_1010), and every option now also
--    carries "hint" when it has one (the name of docs/api/opd.yaml 0.7.1: EmrFieldOption.hint). An option without a hint has no "hint"
--    key (jsonb_strip_nulls on the option object only; its other values are never null). Versions 1 and 2 are not touched.
--    The department then points at version 3. The key from emr_starter_department to the layout is deferred, so both happen in this
--    transaction.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_starter_layout (starter_key, layout_version, layout)
select d.starter_key, d.current_layout_version + 1,
       jsonb_build_object(
         'schemaVersion', 1,
         'sections', (
           select jsonb_agg(
                    jsonb_build_object(
                      'key', p.section_key,
                      'source', 'LIBRARY',
                      'name', s.name,
                      'kind', s.kind,
                      'description', s.description,
                      'part', p.part,
                      'position', p.position,
                      'shown', p.shown,
                      'required', p.required,
                      'print', p.print,
                      'copy', p.copy_from_last,
                      'collapsed', p.collapsed,
                      'whoCanFill', to_jsonb(array_remove(array[
                          case when p.who_doctor then 'DOCTOR' end,
                          case when p.who_nurse then 'NURSE' end,
                          case when p.who_reception then 'RECEPTION' end], null)),
                      'hideLocked', (s.always_shown or p.always_shown),
                      'fields', coalesce((
                        select jsonb_agg(
                                 jsonb_build_object(
                                   'key', f.field_key,
                                   'label', f.label,
                                   'type', f.field_type,
                                   'meta', f.meta,
                                   'required', f.required,
                                   'optionsSource', f.options_source,
                                   'master', f.options_master,
                                   'allowFreeText', f.allow_free_text,
                                   'presentation', f.presentation,
                                   'options', coalesce((
                                     select jsonb_agg(
                                              jsonb_strip_nulls(
                                                jsonb_build_object('key', o.option_key, 'label', o.label, 'isNone', o.is_none,
                                                                   'alert', o.is_alert, 'hint', o.hint))
                                              order by o.position)
                                     from opd.emr_section_field_option o
                                     where o.section_key = f.section_key and o.field_key = f.field_key), '[]'::jsonb),
                                   'columns', f.columns,
                                   'min', f.min_value,
                                   'max', f.max_value)
                                 order by f.position)
                        from opd.emr_section_field f
                        where f.section_key = s.section_key), '[]'::jsonb))
                    order by p.position)
           from opd.emr_starter_department_section p
           join opd.emr_section s on s.section_key = p.section_key
           where p.starter_key = d.starter_key))
from opd.emr_starter_department d
where d.starter_key in ('og', 'onco') and d.current_layout_version = 2;

update opd.emr_starter_department set current_layout_version = 3
 where starter_key in ('og', 'onco') and current_layout_version = 2;

comment on table opd.emr_starter_layout is
  'Global read-only immutable starter layout documents (SAH-53, ADR-020 D4, D6): one json per starter and layout_version, built from the library and the placements. Update, delete and truncate are refused by trigger. A change is a new layout_version; visits already opened keep theirs. Version 2 (SAH-75) adds presentation and option alert; version 3 (SAH-75 follow-up, og and onco only) adds the option hint. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, DEC-052 and DEC-053, not clinically reviewed (SAH-75, DEC-052).';

-- ---------------------------------------------------------------------------------------------
-- 3. Checks inside the transaction. Any failure rolls the whole migration back, so nothing is half applied.
-- ---------------------------------------------------------------------------------------------
do $$
declare
  have text;
begin
  -- layout rows: 15 x v1, 15 x v2, 2 x v3; current version 3 for og and onco, 2 for the other 13
  if (select count(*) from opd.emr_starter_layout where layout_version = 1) <> 15
     or (select count(*) from opd.emr_starter_layout where layout_version = 2) <> 15
     or (select count(*) from opd.emr_starter_layout where layout_version = 3) <> 2
     or (select count(*) from opd.emr_starter_layout) <> 32
     or not exists (select 1 from opd.emr_starter_layout where layout_version = 3 and starter_key = 'og')
     or not exists (select 1 from opd.emr_starter_layout where layout_version = 3 and starter_key = 'onco') then
    raise exception 'SAH-75 v3: starter layout rows are not 15 x version 1, 15 x version 2 and og, onco x version 3';
  end if;
  if (select count(*) from opd.emr_starter_department where current_layout_version = 3 and starter_key in ('og', 'onco')) <> 2
     or (select count(*) from opd.emr_starter_department where current_layout_version = 2) <> 13
     or (select count(*) from opd.emr_starter_department where current_layout_version not in (2, 3)) <> 0 then
    raise exception 'SAH-75 v3: current_layout_version must be 3 for og and onco and 2 for the other 13 starters';
  end if;

  -- placements: onco 27 rows, positions 1..27, the four cards in the drawn order, nothing left at a temporary position
  if (select count(*) from opd.emr_starter_department_section) <> 349
     or exists (select 1 from opd.emr_starter_department_section where position >= 1000)
     or exists (select 1 from opd.emr_starter_department_section group by starter_key having max(position) <> count(*) or min(position) <> 1) then
    raise exception 'SAH-75 v3: placements are not 349 rows with positions 1..n per starter';
  end if;
  select string_agg(section_key || ':' || position || ':' || part, ',' order by position) into have
    from opd.emr_starter_department_section where starter_key = 'onco' and section_key in ('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog');
  if have is distinct from 'onc_tox:17:ASSESSMENT,onc_bsa:18:ASSESSMENT,onc_labs:19:ASSESSMENT,onc_ecog:20:ASSESSMENT' then
    raise exception 'SAH-75 v3: onco cards are [%]', have;
  end if;

  -- version 3 holds exactly the placements of its starter, in position order; the onco document lists the four cards in the new order
  if exists (select 1 from opd.emr_starter_layout l
              where l.layout_version = 3
                and jsonb_array_length(l.layout -> 'sections') <>
                    (select count(*) from opd.emr_starter_department_section p where p.starter_key = l.starter_key)) then
    raise exception 'SAH-75 v3: a version 3 layout does not hold every placement';
  end if;
  select string_agg(s.value ->> 'key' || ':' || (s.value ->> 'position'), ',' order by s.ordinality) into have
    from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') with ordinality as s(value, ordinality)
   where l.starter_key = 'onco' and l.layout_version = 3 and s.value ->> 'key' in ('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog');
  if have is distinct from 'onc_tox:17,onc_bsa:18,onc_labs:19,onc_ecog:20' then
    raise exception 'SAH-75 v3: onco version 3 card order is [%]', have;
  end if;
  -- no other onco section moved or changed a flag between version 2 and version 3
  if exists (select 1
               from opd.emr_starter_layout v2
               cross join lateral jsonb_array_elements(v2.layout -> 'sections') as a(value)
               join opd.emr_starter_layout v3 on v3.starter_key = v2.starter_key and v3.layout_version = 3
               cross join lateral jsonb_array_elements(v3.layout -> 'sections') as b(value)
              where v2.starter_key = 'onco' and v2.layout_version = 2
                and b.value ->> 'key' = a.value ->> 'key'
                and a.value ->> 'key' not in ('onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog')
                and (a.value - 'fields') is distinct from (b.value - 'fields')) then
    raise exception 'SAH-75 v3: an onco section other than the four cards changed between version 2 and version 3';
  end if;

  -- og version 3: the new Menstrual history and Contraception fields in order; hints on the six methods only
  select string_agg(f.value ->> 'key', ',' order by f.ordinality) into have
    from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') as s(value),
         jsonb_array_elements(s.value -> 'fields') with ordinality as f(value, ordinality)
   where l.starter_key = 'og' and l.layout_version = 3 and s.value ->> 'key' = 'menstrual';
  if have is distinct from 'lmp,cycle,cycle_length,flow,dysmenorrhoea,age_at_menarche' then
    raise exception 'SAH-75 v3: og menstrual fields are [%]', have;
  end if;
  select string_agg(f.value ->> 'key', ',' order by f.ordinality) into have
    from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') as s(value),
         jsonb_array_elements(s.value -> 'fields') with ordinality as f(value, ordinality)
   where l.starter_key = 'og' and l.layout_version = 3 and s.value ->> 'key' = 'contraception';
  if have is distinct from 'method,findings,template,attachments' then
    raise exception 'SAH-75 v3: og contraception fields are [%]', have;
  end if;
  if (select count(*) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') as s(value),
                           jsonb_array_elements(s.value -> 'fields') as f(value), jsonb_array_elements(f.value -> 'options') as o(value)
       where l.starter_key = 'og' and l.layout_version = 3 and o.value ->> 'hint' is not null) <> 6
     or (select count(*) from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') as s(value),
                              jsonb_array_elements(s.value -> 'fields') as f(value), jsonb_array_elements(f.value -> 'options') as o(value)
          where l.starter_key = 'onco' and l.layout_version = 3 and o.value ->> 'hint' is not null) <> 0
     or not exists (select 1 from opd.emr_starter_layout l, jsonb_array_elements(l.layout -> 'sections') as s(value),
                                  jsonb_array_elements(s.value -> 'fields') as f(value), jsonb_array_elements(f.value -> 'options') as o(value)
                     where l.starter_key = 'og' and l.layout_version = 3 and s.value ->> 'key' = 'contraception'
                       and o.value ->> 'key' = 'cu_t' and o.value ->> 'hint' = 'IUCD, 5–10 years') then
    raise exception 'SAH-75 v3: option hints are not carried as expected (6 in og, none in onco)';
  end if;
  -- versions 1 and 2 carry no hint at all (they are untouched)
  if exists (select 1 from opd.emr_starter_layout where layout_version in (1, 2) and layout::text like '%"hint"%') then
    raise exception 'SAH-75 v3: a version 1 or 2 layout carries a hint';
  end if;

  -- the immutability triggers of the starter layouts exist and are enabled (nothing was disabled here)
  if (select count(*) from pg_trigger
       where tgrelid = 'opd.emr_starter_layout'::regclass
         and tgname in ('trg_emr_starter_layout_immutable', 'trg_emr_starter_layout_no_truncate') and tgenabled = 'O') <> 2 then
    raise exception 'SAH-75 v3: the starter layout immutability triggers are not both enabled';
  end if;
end
$$;
