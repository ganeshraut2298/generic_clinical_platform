-- SAH-75 (ADR-026 D2; DEC-052 item 6; contract approved in DEC-053) · Starter department defaults follow the doctor screens, and a NEW
-- immutable starter layout version (2) is added for all 15 starters. Forward-only. Needs V2026_12_14_1000 (the seven library sections).
-- Design note: docs/database/SAH-75.md.
--
-- Scope: GLOBAL reference tables of schema opd only: emr_starter_department_section (placements), emr_starter_layout (insert only) and
-- emr_starter_department (current_layout_version). No clinic, department draft, department version, EMR record, form, course, patient
-- or billing row is read or written. A record keeps the starter layout version it opened with (ADR-020 D8, BR45-18); layout version 1
-- of every starter stays byte for byte as it is.
--
-- Immutability: opd.emr_starter_layout refuses UPDATE, DELETE and TRUNCATE by trigger (trg_emr_starter_layout_immutable,
-- trg_emr_starter_layout_no_truncate). This migration only INSERTS new versions, which the triggers allow, so no trigger is disabled
-- (unlike V2026_12_13_1010 and V2026_11_24_1000, which had to correct an existing row). The block at the end asserts that both
-- triggers are still enabled.
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. Every Show, Required, Part and order value below is a value from the EMR doctor screens
-- (docs/design/emr-config/emr-config-design-handoff/emr-config/source/Doctor*.dc.html), accepted by the human in DEC-052 and DEC-053,
-- not clinically reviewed (SAH-75, DEC-052).
--
-- Result per starter (ADR-026 D2 table). "Before" is V2026_12_08_1030.
--   every starter  rxformat required true -> false (shown stays true)                                             (G4, 15 starters)
--   gm             vitals required false -> true; procedures shown false -> true; local_exam added after exam (ASSESSMENT, shown, optional,
--                  print on, copy off, who doctor); Treatment order: diagnosis, rx, investigations, procedures, advice, followup  (T2)
--   dental         required = complaints, dental_medhx, diagnosis, tx_plan. Before: complaints, diagnosis, rx, dental_chart (and rxformat)
--                  were required, so complaints and diagnosis stay required, rx and dental_chart stop (SAH-75-4, H18), dental_medhx and
--                  tx_plan become required. Six cards added after complaints in the drawn order (ASSESSMENT, shown, optional except
--                  dental_medhx, print off, copy off, who doctor); the generic intraoral and extraoral placements are kept with
--                  shown = false (never removed, so no stored draft or version falls out of the section set); Treatment order:
--                  diagnosis, dental_chart, tx_plan, rx, advice
--   og             menstrual required false -> true (hidden by code in ANC mode, ADR-026 D9)
--   surgery        required = complaints, sx_fitness, diagnosis, sx_plan, sx_consent; rx required true -> false (OQ-75-2); local_exam added
--                  after sx_findings and before sx_fitness (ASSESSMENT, shown, optional, same settings as in gm)
--   onco           complaints required true -> false
-- Positions are renumbered 1..n without a gap for gm, dental and surgery.

set local lock_timeout = '30s';

-- ---------------------------------------------------------------------------------------------
-- 1. New placements. They are inserted with temporary high positions (5001 and up) so nothing collides; section 3 renumbers.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_starter_department_section
  (starter_key, section_key, position, part, shown, required, print, copy_from_last, collapsed, who_doctor, who_nurse, who_reception, always_shown) values
  ('gm',      'local_exam',       5001, 'ASSESSMENT', true, false, true,  false, false, true, false, false, false),
  ('surgery', 'local_exam',       5002, 'ASSESSMENT', true, false, true,  false, false, true, false, false, false),
  ('dental',  'dental_medhx',     5003, 'ASSESSMENT', true, true,  false, false, false, true, false, false, false),
  ('dental',  'dental_hx',        5004, 'ASSESSMENT', true, false, false, false, false, true, false, false, false),
  ('dental',  'dental_pain',      5005, 'ASSESSMENT', true, false, false, false, false, true, false, false, false),
  ('dental',  'dental_extraoral', 5006, 'ASSESSMENT', true, false, false, false, false, true, false, false, false),
  ('dental',  'dental_intraoral', 5007, 'ASSESSMENT', true, false, false, false, false, true, false, false, false),
  ('dental',  'dental_xray',      5008, 'ASSESSMENT', true, false, false, false, false, true, false, false, false);

-- ---------------------------------------------------------------------------------------------
-- 2. Required and Show flags (existing placements).
-- ---------------------------------------------------------------------------------------------
-- rxformat is not required anywhere (G4); it stays shown.
update opd.emr_starter_department_section set required = false where section_key = 'rxformat' and required;

update opd.emr_starter_department_section set required = true  where starter_key = 'gm' and section_key = 'vitals';
update opd.emr_starter_department_section set shown = true     where starter_key = 'gm' and section_key = 'procedures';

-- dental: required = complaints, dental_medhx, diagnosis, tx_plan
update opd.emr_starter_department_section set required = false where starter_key = 'dental' and section_key in ('rx', 'dental_chart');
update opd.emr_starter_department_section set required = true  where starter_key = 'dental' and section_key = 'tx_plan';
-- the old generic cards stay in the set but are hidden (hidden is never required: they were already optional)
update opd.emr_starter_department_section set shown = false, required = false
 where starter_key = 'dental' and section_key in ('intraoral', 'extraoral');

update opd.emr_starter_department_section set required = true  where starter_key = 'og' and section_key = 'menstrual';

update opd.emr_starter_department_section set required = true  where starter_key = 'surgery' and section_key in ('sx_fitness', 'sx_plan', 'sx_consent');
update opd.emr_starter_department_section set required = false where starter_key = 'surgery' and section_key = 'rx';

update opd.emr_starter_department_section set required = false where starter_key = 'onco' and section_key = 'complaints';

-- ---------------------------------------------------------------------------------------------
-- 3. Positions of gm, dental and surgery: the full new order is listed, the update runs in two steps (uq_emr_starter_department_section_
--    position is not deferrable): first every row of these three starters moves out of the way (+1000), then it takes its final position.
--    Every row of the three starters (the new ones too) ends up at 1001 or more, the final positions are 1 to 30: no step collides.
-- ---------------------------------------------------------------------------------------------
create temporary table tmp_sah75_order (
  starter_key   text not null,
  section_key   text not null,
  new_position  integer not null,
  primary key (starter_key, section_key)
) on commit drop;

insert into tmp_sah75_order (starter_key, section_key, new_position) values
  -- gm (20): local_exam after exam; Treatment part order diagnosis, rx, investigations, procedures, advice, followup
  ('gm', 'vitals', 1), ('gm', 'complaints', 2), ('gm', 'hpi', 3), ('gm', 'history', 4), ('gm', 'allergy', 5), ('gm', 'exam', 6),
  ('gm', 'local_exam', 7), ('gm', 'diagnosis', 8), ('gm', 'rx', 9), ('gm', 'investigations', 10), ('gm', 'procedures', 11),
  ('gm', 'advice', 12), ('gm', 'followup', 13), ('gm', 'referral', 14), ('gm', 'attachments', 15), ('gm', 'chronic', 16),
  ('gm', 'adult_vax', 17), ('gm', 'abha', 18), ('gm', 'consent', 19), ('gm', 'rxformat', 20),
  -- dental (30): the six cards right after complaints in the drawn order; Treatment part order diagnosis, dental_chart, tx_plan, rx, advice
  ('dental', 'vitals', 1), ('dental', 'complaints', 2),
  ('dental', 'dental_medhx', 3), ('dental', 'dental_hx', 4), ('dental', 'dental_pain', 5), ('dental', 'dental_extraoral', 6),
  ('dental', 'dental_intraoral', 7), ('dental', 'dental_xray', 8),
  ('dental', 'hpi', 9), ('dental', 'history', 10), ('dental', 'allergy', 11), ('dental', 'exam', 12),
  ('dental', 'diagnosis', 13), ('dental', 'dental_chart', 14), ('dental', 'tx_plan', 15), ('dental', 'rx', 16), ('dental', 'advice', 17),
  ('dental', 'investigations', 18), ('dental', 'procedures', 19), ('dental', 'followup', 20), ('dental', 'referral', 21),
  ('dental', 'attachments', 22), ('dental', 'intraoral', 23), ('dental', 'extraoral', 24), ('dental', 'perio', 25),
  ('dental', 'dental_done', 26), ('dental', 'dental_lab', 27), ('dental', 'abha', 28), ('dental', 'consent', 29), ('dental', 'rxformat', 30),
  -- surgery (27): local_exam after sx_findings and before sx_fitness; every later row moves up by one
  ('surgery', 'vitals', 1), ('surgery', 'complaints', 2), ('surgery', 'hpi', 3), ('surgery', 'history', 4), ('surgery', 'allergy', 5),
  ('surgery', 'exam', 6), ('surgery', 'diagnosis', 7), ('surgery', 'investigations', 8), ('surgery', 'rx', 9), ('surgery', 'advice', 10),
  ('surgery', 'procedures', 11), ('surgery', 'followup', 12), ('surgery', 'referral', 13), ('surgery', 'attachments', 14),
  ('surgery', 'sx_findings', 15), ('surgery', 'local_exam', 16), ('surgery', 'sx_fitness', 17), ('surgery', 'sx_plan', 18),
  ('surgery', 'sx_consent', 19), ('surgery', 'sx_preop', 20), ('surgery', 'sx_preauth', 21), ('surgery', 'sx_checklist', 22),
  ('surgery', 'sx_opnote', 23), ('surgery', 'sx_postop', 24), ('surgery', 'abha', 25), ('surgery', 'consent', 26), ('surgery', 'rxformat', 27);

-- The list must cover every placement of these three starters exactly once (otherwise a row would keep a 1000+ position).
do $$
declare
  missing integer;
  extra integer;
begin
  select count(*) into missing
    from opd.emr_starter_department_section p
   where p.starter_key in ('gm', 'dental', 'surgery')
     and not exists (select 1 from tmp_sah75_order o where o.starter_key = p.starter_key and o.section_key = p.section_key);
  select count(*) into extra
    from tmp_sah75_order o
   where not exists (select 1 from opd.emr_starter_department_section p where p.starter_key = o.starter_key and p.section_key = o.section_key);
  if missing <> 0 or extra <> 0 then
    raise exception 'SAH-75 position list does not match the placements (% missing, % unknown)', missing, extra;
  end if;
end
$$;

update opd.emr_starter_department_section set position = position + 1000 where starter_key in ('gm', 'dental', 'surgery');

update opd.emr_starter_department_section p
   set position = o.new_position
  from tmp_sah75_order o
 where p.starter_key = o.starter_key and p.section_key = o.section_key;

-- ---------------------------------------------------------------------------------------------
-- 4. Starter layout version 2 for all 15 starters (ADR-026 D2 item 4). Same builder as version 1 (V2026_12_08_1030) so the document and
--    the tables cannot differ, and every field now also copies "presentation" and every option "alert" (ADR-026 D6; the names are the
--    ones of docs/api/opd.yaml 0.7.0: EmrFieldDefinition.presentation, EmrFieldOption.alert). Version 1 is not touched.
--    The department then points at it. The key from emr_starter_department to the layout is deferred, so both happen in this transaction.
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
                                              jsonb_build_object('key', o.option_key, 'label', o.label, 'isNone', o.is_none, 'alert', o.is_alert)
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
where d.current_layout_version = 1;

update opd.emr_starter_department set current_layout_version = 2 where current_layout_version = 1;

comment on table opd.emr_starter_department_section is
  'Global read-only section placements of a starter department (SAH-53, ADR-020 D4; SAH-75 adds 8 and changes flags): 349 rows with position, part, Show, Required, Print, Copy from last visit, Collapsed and Who can fill at start. always_shown is the hide lock of the starter (PCPNDT Form F in og). A placement is never removed (a starter that stops using a section keeps it hidden, ADR-026 D2). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039 and DEC-052, not clinically reviewed (SAH-75, DEC-052).';
comment on table opd.emr_starter_layout is
  'Global read-only immutable starter layout documents (SAH-53, ADR-020 D4, D6): one json per starter and layout_version, built from the library and the placements. Update, delete and truncate are refused by trigger. A change is a new layout_version; visits already opened keep theirs. Version 2 (SAH-75) adds presentation and option alert. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039 and DEC-052, not clinically reviewed (SAH-75, DEC-052).';

-- ---------------------------------------------------------------------------------------------
-- 5. Checks inside the transaction. Any failure rolls the whole migration back, so nothing is half applied.
-- ---------------------------------------------------------------------------------------------
do $$
declare
  want text;
  have text;
  s text;
begin
  -- counts: 341 old placements + 8 new
  if (select count(*) from opd.emr_starter_department_section) <> 349 then
    raise exception 'SAH-75: expected 349 starter placements';
  end if;
  -- positions are 1..n without a gap for every starter, and nothing is left at 1000 or more
  if exists (select 1 from opd.emr_starter_department_section where position >= 1000) then
    raise exception 'SAH-75: a placement kept a temporary position';
  end if;
  if exists (select 1 from opd.emr_starter_department_section group by starter_key having max(position) <> count(*) or min(position) <> 1) then
    raise exception 'SAH-75: positions of a starter are not 1..n';
  end if;
  -- 15 starters now at version 2, version 1 documents still 15, one version 2 document per starter
  if (select count(*) from opd.emr_starter_department where current_layout_version = 2) <> 15
     or (select count(*) from opd.emr_starter_layout where layout_version = 1) <> 15
     or (select count(*) from opd.emr_starter_layout where layout_version = 2) <> 15 then
    raise exception 'SAH-75: starter layout versions are not 15 x version 1 and 15 x version 2';
  end if;
  -- the version 2 document holds exactly the placements of its starter, in position order
  if exists (select 1 from opd.emr_starter_layout l
              where l.layout_version = 2
                and jsonb_array_length(l.layout -> 'sections') <>
                    (select count(*) from opd.emr_starter_department_section p where p.starter_key = l.starter_key)) then
    raise exception 'SAH-75: a version 2 layout does not hold every placement';
  end if;
  -- rxformat is optional everywhere and still shown
  if exists (select 1 from opd.emr_starter_department_section where section_key = 'rxformat' and (required or not shown)) then
    raise exception 'SAH-75: rxformat must be shown and not required in every starter';
  end if;
  -- required sets of the seven drawn starters, exactly (ADR-026 D2)
  for s, want in select * from (values
      ('gm',      'complaints,diagnosis,rx,vitals'),
      ('dental',  'complaints,dental_medhx,diagnosis,tx_plan'),
      ('og',      'anc,complaints,diagnosis,menstrual,pcpndt,rx'),
      ('surgery', 'complaints,diagnosis,sx_consent,sx_fitness,sx_plan'),
      ('onco',    'diagnosis,rx')) as v(k, w) loop
    select string_agg(section_key, ',' order by section_key collate "C") into have
      from opd.emr_starter_department_section where starter_key = s and required;
    if have is distinct from want then
      raise exception 'SAH-75: % required set is [%], expected [%]', s, have, want;
    end if;
  end loop;
  -- visible placements the stories name
  if not exists (select 1 from opd.emr_starter_department_section where starter_key = 'gm' and section_key = 'procedures' and shown)
     or not exists (select 1 from opd.emr_starter_department_section where starter_key = 'gm' and section_key = 'local_exam' and shown and not required)
     or not exists (select 1 from opd.emr_starter_department_section where starter_key = 'surgery' and section_key = 'local_exam' and shown and not required)
     or (select count(*) from opd.emr_starter_department_section where starter_key = 'dental' and section_key like 'dental\_%' and section_key in
           ('dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray') and shown) <> 6
     or exists (select 1 from opd.emr_starter_department_section where starter_key = 'dental' and section_key in ('intraoral', 'extraoral') and shown) then
    raise exception 'SAH-75: a drawn placement is missing or has the wrong Show flag';
  end if;
  -- the immutability triggers of the starter layouts exist and are enabled (nothing was disabled here)
  if (select count(*) from pg_trigger
       where tgrelid = 'opd.emr_starter_layout'::regclass
         and tgname in ('trg_emr_starter_layout_immutable', 'trg_emr_starter_layout_no_truncate') and tgenabled = 'O') <> 2 then
    raise exception 'SAH-75: the starter layout immutability triggers are not both enabled';
  end if;
end
$$;
