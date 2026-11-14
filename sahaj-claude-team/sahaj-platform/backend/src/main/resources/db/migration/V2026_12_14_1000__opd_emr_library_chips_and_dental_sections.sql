-- SAH-75 (ADR-026 D2, D5, D6; DEC-052; contract approved in DEC-053) · EMR section library: chip presentation, alert options and
-- seven new library sections (Local examination and the six Dental assessment cards). Forward-only, additive.
-- Design note: docs/database/SAH-75.md. The starter placements and the new starter layout version follow in V2026_12_14_1010.
--
-- Scope: GLOBAL reference tables of schema opd only: emr_section, emr_section_field, emr_section_field_option.
-- No clinic, patient, visit, billing or admission table is read or written here. No existing row is changed: the two new columns
-- take their defaults on every existing row (presentation DEFAULT, is_alert false), 100 sections / 313 fields / 136 options stay as
-- they are. sahaj_app keeps SELECT only on these tables (V2026_12_08_1000); a new column needs no new grant.
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. Every section name, description, field label, chip and red (alert) flag below is a value
-- from the EMR mockup (docs/design/emr-config/emr-config-design-handoff/emr-config/source/DoctorDental.dc.html lines 468-475 and
-- DoctorGeneral.dc.html "Local examination · body map"), accepted by the human in DEC-052 and DEC-053, not clinically reviewed.
--
-- How the mockup text became rows (same rules as V2026_12_08_1020):
--   * Option key = the label in lower case, every run of other characters made one underscore, an "o" prefix when it starts with a
--     digit ("6–12 months" -> o6_12_months, "0" -> o0). "+", "++", "+++" (Calculus / stains) are plus_1, plus_2, plus_3.
--   * Single-choice rows are field_type SINGLE_CHOICE, many-choice rows are field_type CHIPS; both are presentation CHIPS (one chip row,
--     44 px chips). Options are fixed (options_source FIELD), no free text.
--   * is_alert follows the design's red chips: on the Medical history card every chip except "None"; every Habit; on Intra-oral
--     Soft tissue every chip except "Normal". "None" (dental_medhx) and "Normal" (soft_tissue, many-choice) are is_none: choosing one
--     clears the others of the field and any other clears it (ADR-026 D6). Single-choice rows have no is_none (one chip is chosen).
--   * "IOPA 36 (attached)" is the option "IOPA": the tooth number is sample data and attachment is Later (ADR-026 H17).
--   * dental_medhx fields exist for the configurator's field list; on a dental OPD record they are COVERED by the specialty editor
--     DENTAL_MED_HISTORY (ADR-026 D6), so none is required here.
--   * No field key is "specialty" (ADR-023 reserved key; ck_emr_section_field_key_not_reserved would refuse it).

set local lock_timeout = '30s';

-- ---------------------------------------------------------------------------------------------
-- 1. Two additive library columns (ADR-026 D2 item 2). Existing rows keep the defaults.
-- ---------------------------------------------------------------------------------------------
alter table opd.emr_section_field
  add column if not exists presentation text not null default 'DEFAULT';
alter table opd.emr_section_field
  add constraint ck_emr_section_field_presentation check (presentation in ('DEFAULT', 'CHIPS'));

alter table opd.emr_section_field_option
  add column if not exists is_alert boolean not null default false;

-- ---------------------------------------------------------------------------------------------
-- 2. Seven new library sections, positions 101 to 107 (library order; position is not unique). Kind SPECIALTY.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_section (section_key, name, kind, description, always_shown, position) values
  ('local_exam', 'Local examination', 'SPECIALTY', 'Mark local findings on the body map, front and back.', false, 101),
  ('dental_medhx', 'Medical history and alerts', 'SPECIALTY', 'Ask before any injection, extraction or surgery', false, 102),
  ('dental_hx', 'Dental history and habits', 'SPECIALTY', 'Shapes the treatment plan and advice', false, 103),
  ('dental_pain', 'Pain', 'SPECIALTY', 'For the main complaint', false, 104),
  ('dental_extraoral', 'Extra-oral examination', 'SPECIALTY', 'Face, jaw joint, lymph nodes and mouth opening.', false, 105),
  ('dental_intraoral', 'Intra-oral examination', 'SPECIALTY', 'Tooth-by-tooth findings go on the chart in Treatment', false, 106),
  ('dental_xray', 'Radiographs and photos', 'SPECIALTY', 'Tap to order; attached images open in the viewer', false, 107);

-- ---------------------------------------------------------------------------------------------
-- 3. Their 20 fields.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_section_field
  (section_key, field_key, position, label, field_type, meta, required, options_source, options_master, allow_free_text, columns, presentation) values
  ('local_exam', 'body_map', 1, 'Body map', 'BODY_MAP', 'Front / back', false, 'NONE', null, false, null, 'DEFAULT'),

  ('dental_medhx', 'conditions', 1, 'Conditions', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_medhx', 'medicines', 2, 'Medicines', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_medhx', 'allergies', 3, 'Allergies', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),

  ('dental_hx', 'last_dental_visit', 1, 'Last dental visit', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_hx', 'brushing', 2, 'Brushing', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_hx', 'habits', 3, 'Habits', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),

  ('dental_pain', 'type', 1, 'Type', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_pain', 'score', 2, 'Score (0–10)', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_pain', 'since', 3, 'Since', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),

  ('dental_extraoral', 'face', 1, 'Face', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_extraoral', 'tmj', 2, 'Jaw joint (TMJ)', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_extraoral', 'lymph_nodes', 3, 'Lymph nodes', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_extraoral', 'mouth_opening', 4, 'Mouth opening', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),

  ('dental_intraoral', 'oral_hygiene', 1, 'Oral hygiene', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_intraoral', 'gingiva', 2, 'Gingiva', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_intraoral', 'calculus', 3, 'Calculus / stains', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_intraoral', 'occlusion', 4, 'Occlusion', 'SINGLE_CHOICE', '', false, 'FIELD', null, false, null, 'CHIPS'),
  ('dental_intraoral', 'soft_tissue', 5, 'Soft tissue', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS'),

  ('dental_xray', 'images', 1, 'Images', 'CHIPS', '', false, 'FIELD', null, false, null, 'CHIPS');

-- ---------------------------------------------------------------------------------------------
-- 4. Their options (is_none, is_alert as drawn).
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_section_field_option (section_key, field_key, option_key, position, label, is_none, is_alert) values
  -- dental_medhx · Conditions
  ('dental_medhx', 'conditions', 'diabetes', 1, 'Diabetes', false, true),
  ('dental_medhx', 'conditions', 'high_bp', 2, 'High BP', false, true),
  ('dental_medhx', 'conditions', 'heart_disease', 3, 'Heart disease', false, true),
  ('dental_medhx', 'conditions', 'thyroid', 4, 'Thyroid', false, true),
  ('dental_medhx', 'conditions', 'asthma', 5, 'Asthma', false, true),
  ('dental_medhx', 'conditions', 'bleeding_disorder', 6, 'Bleeding disorder', false, true),
  ('dental_medhx', 'conditions', 'epilepsy', 7, 'Epilepsy', false, true),
  ('dental_medhx', 'conditions', 'pregnant', 8, 'Pregnant', false, true),
  ('dental_medhx', 'conditions', 'none', 9, 'None', true, false),
  -- dental_medhx · Medicines
  ('dental_medhx', 'medicines', 'blood_thinners', 1, 'Blood thinners', false, true),
  ('dental_medhx', 'medicines', 'aspirin', 2, 'Aspirin', false, true),
  ('dental_medhx', 'medicines', 'bisphosphonates', 3, 'Bisphosphonates', false, true),
  ('dental_medhx', 'medicines', 'steroids', 4, 'Steroids', false, true),
  ('dental_medhx', 'medicines', 'thyroid_medicine', 5, 'Thyroid medicine', false, true),
  ('dental_medhx', 'medicines', 'none', 6, 'None', true, false),
  -- dental_medhx · Allergies
  ('dental_medhx', 'allergies', 'penicillin', 1, 'Penicillin', false, true),
  ('dental_medhx', 'allergies', 'local_anaesthetic', 2, 'Local anaesthetic', false, true),
  ('dental_medhx', 'allergies', 'latex', 3, 'Latex', false, true),
  ('dental_medhx', 'allergies', 'painkillers_nsaids', 4, 'Painkillers (NSAIDs)', false, true),
  ('dental_medhx', 'allergies', 'none', 5, 'None', true, false),

  -- dental_hx · Last dental visit
  ('dental_hx', 'last_dental_visit', 'under_6_months', 1, 'Under 6 months', false, false),
  ('dental_hx', 'last_dental_visit', 'o6_12_months', 2, '6–12 months', false, false),
  ('dental_hx', 'last_dental_visit', 'over_1_year', 3, 'Over 1 year', false, false),
  ('dental_hx', 'last_dental_visit', 'never', 4, 'Never', false, false),
  -- dental_hx · Brushing
  ('dental_hx', 'brushing', 'once_a_day', 1, 'Once a day', false, false),
  ('dental_hx', 'brushing', 'twice_a_day', 2, 'Twice a day', false, false),
  ('dental_hx', 'brushing', 'irregular', 3, 'Irregular', false, false),
  -- dental_hx · Habits (every habit is red when chosen)
  ('dental_hx', 'habits', 'tobacco_chewing', 1, 'Tobacco chewing', false, true),
  ('dental_hx', 'habits', 'gutkha', 2, 'Gutkha', false, true),
  ('dental_hx', 'habits', 'paan_betel', 3, 'Paan / betel', false, true),
  ('dental_hx', 'habits', 'smoking', 4, 'Smoking', false, true),
  ('dental_hx', 'habits', 'alcohol', 5, 'Alcohol', false, true),
  ('dental_hx', 'habits', 'teeth_grinding', 6, 'Teeth grinding', false, true),
  ('dental_hx', 'habits', 'mouth_breathing', 7, 'Mouth breathing', false, true),

  -- dental_pain · Type
  ('dental_pain', 'type', 'sharp', 1, 'Sharp', false, false),
  ('dental_pain', 'type', 'throbbing', 2, 'Throbbing', false, false),
  ('dental_pain', 'type', 'on_chewing', 3, 'On chewing', false, false),
  ('dental_pain', 'type', 'cold_sensitivity', 4, 'Cold sensitivity', false, false),
  ('dental_pain', 'type', 'hot_sensitivity', 5, 'Hot sensitivity', false, false),
  ('dental_pain', 'type', 'night_pain', 6, 'Night pain', false, false),
  ('dental_pain', 'type', 'radiating', 7, 'Radiating', false, false),
  -- dental_pain · Score (0–10)
  ('dental_pain', 'score', 'o0', 1, '0', false, false),
  ('dental_pain', 'score', 'o1', 2, '1', false, false),
  ('dental_pain', 'score', 'o2', 3, '2', false, false),
  ('dental_pain', 'score', 'o3', 4, '3', false, false),
  ('dental_pain', 'score', 'o4', 5, '4', false, false),
  ('dental_pain', 'score', 'o5', 6, '5', false, false),
  ('dental_pain', 'score', 'o6', 7, '6', false, false),
  ('dental_pain', 'score', 'o7', 8, '7', false, false),
  ('dental_pain', 'score', 'o8', 9, '8', false, false),
  ('dental_pain', 'score', 'o9', 10, '9', false, false),
  ('dental_pain', 'score', 'o10', 11, '10', false, false),
  -- dental_pain · Since
  ('dental_pain', 'since', 'o1_day', 1, '1 day', false, false),
  ('dental_pain', 'since', 'o2_3_days', 2, '2–3 days', false, false),
  ('dental_pain', 'since', 'o1_week', 3, '1 week', false, false),
  ('dental_pain', 'since', 'over_2_weeks', 4, 'Over 2 weeks', false, false),

  -- dental_extraoral
  ('dental_extraoral', 'face', 'symmetrical', 1, 'Symmetrical', false, false),
  ('dental_extraoral', 'face', 'swelling_present', 2, 'Swelling present', false, false),
  ('dental_extraoral', 'tmj', 'normal', 1, 'Normal', false, false),
  ('dental_extraoral', 'tmj', 'clicking', 2, 'Clicking', false, false),
  ('dental_extraoral', 'tmj', 'pain', 3, 'Pain', false, false),
  ('dental_extraoral', 'tmj', 'deviation', 4, 'Deviation', false, false),
  ('dental_extraoral', 'lymph_nodes', 'not_palpable', 1, 'Not palpable', false, false),
  ('dental_extraoral', 'lymph_nodes', 'palpable_tender', 2, 'Palpable, tender', false, false),
  ('dental_extraoral', 'lymph_nodes', 'palpable_non_tender', 3, 'Palpable, non-tender', false, false),
  ('dental_extraoral', 'mouth_opening', 'normal', 1, 'Normal', false, false),
  ('dental_extraoral', 'mouth_opening', 'restricted', 2, 'Restricted', false, false),

  -- dental_intraoral
  ('dental_intraoral', 'oral_hygiene', 'good', 1, 'Good', false, false),
  ('dental_intraoral', 'oral_hygiene', 'fair', 2, 'Fair', false, false),
  ('dental_intraoral', 'oral_hygiene', 'poor', 3, 'Poor', false, false),
  ('dental_intraoral', 'gingiva', 'healthy', 1, 'Healthy', false, false),
  ('dental_intraoral', 'gingiva', 'mild_inflammation', 2, 'Mild inflammation', false, false),
  ('dental_intraoral', 'gingiva', 'bleeding', 3, 'Bleeding', false, false),
  ('dental_intraoral', 'gingiva', 'recession', 4, 'Recession', false, false),
  ('dental_intraoral', 'calculus', 'none', 1, 'None', false, false),
  ('dental_intraoral', 'calculus', 'plus_1', 2, '+', false, false),
  ('dental_intraoral', 'calculus', 'plus_2', 3, '++', false, false),
  ('dental_intraoral', 'calculus', 'plus_3', 4, '+++', false, false),
  ('dental_intraoral', 'occlusion', 'class_i', 1, 'Class I', false, false),
  ('dental_intraoral', 'occlusion', 'class_ii', 2, 'Class II', false, false),
  ('dental_intraoral', 'occlusion', 'class_iii', 3, 'Class III', false, false),
  ('dental_intraoral', 'soft_tissue', 'normal', 1, 'Normal', true, false),
  ('dental_intraoral', 'soft_tissue', 'ulcer', 2, 'Ulcer', false, true),
  ('dental_intraoral', 'soft_tissue', 'white_patch', 3, 'White patch', false, true),
  ('dental_intraoral', 'soft_tissue', 'red_patch', 4, 'Red patch', false, true),
  ('dental_intraoral', 'soft_tissue', 'tongue_coating', 5, 'Tongue coating', false, true),

  -- dental_xray · Images (choosing one is an order in this visit; "(attached)" and the viewer are Later)
  ('dental_xray', 'images', 'iopa', 1, 'IOPA', false, false),
  ('dental_xray', 'images', 'opg', 2, 'OPG', false, false),
  ('dental_xray', 'images', 'bitewing', 3, 'Bitewing', false, false),
  ('dental_xray', 'images', 'cbct', 4, 'CBCT', false, false),
  ('dental_xray', 'images', 'intra_oral_photos', 5, 'Intra-oral photos', false, false);

-- ---------------------------------------------------------------------------------------------
-- 5. Comments (the tag is on every table that is seeded).
-- ---------------------------------------------------------------------------------------------
comment on table opd.emr_section is
  'Global read-only EMR section library (SAH-53, ADR-020 D3; SAH-75 adds 7): 107 sections, kinds COMMON 14, SPECIALTY 85, COMPLIANCE 8. Changed by migration only. Section keys are permanent once a version or record refers to them. Starter list, not clinically reviewed: names and descriptions are values from the EMR mockup, accepted by the human in DEC-039 and DEC-052, not clinically reviewed.';
comment on table opd.emr_section_field is
  'Global read-only fields of an EMR library section (SAH-53, ADR-020 D3; SAH-75 adds 20): label, type (30 values), meta hint, required flag, options source, presentation (DEFAULT or CHIPS). Sections with no field list in the mockup carry the mockup FALLBACK fields (Findings, Template, Attachments). Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039 and DEC-052, not clinically reviewed (SAH-75, DEC-052).';
comment on table opd.emr_section_field_option is
  'Global read-only fixed options of a library field with options_source FIELD (SAH-53; SAH-75 adds the Dental chips). is_none clears the other chips of the field; is_alert shows the chip red when chosen. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039 and DEC-052, not clinically reviewed (SAH-75, DEC-052).';
comment on column opd.emr_section_field.presentation is
  'DEFAULT or CHIPS (SAH-75, ADR-026 D6). CHIPS: a single-choice field is one chip row (press the chosen chip again to clear), a many-choice field is a chip row where several are chosen. Stored values do not change. Value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052).';
comment on column opd.emr_section_field_option.is_alert is
  'True when the chip is shown red once chosen (SAH-75, ADR-026 D6): a risk for dental work (medical history, tobacco habit, soft-tissue finding). Value from the EMR mockup, not clinically reviewed (SAH-75, DEC-052).';

-- ---------------------------------------------------------------------------------------------
-- 6. Check inside the transaction: exact counts, defaults on every old row, and the field-key rule.
-- ---------------------------------------------------------------------------------------------
do $$
begin
  if (select count(*) from opd.emr_section
       where section_key in ('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')) <> 7
     or (select count(*) from opd.emr_section_field
          where section_key in ('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')) <> 20
     or (select count(*) from opd.emr_section_field_option
          where section_key in ('dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')) <> 91 then
    raise exception 'SAH-75 library: expected 7 new sections, 20 new fields and 91 new options';
  end if;
  if exists (select 1 from opd.emr_section_field
              where section_key not in ('local_exam', 'dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')
                and presentation <> 'DEFAULT') then
    raise exception 'an existing field did not keep presentation DEFAULT';
  end if;
  if exists (select 1 from opd.emr_section_field_option
              where section_key not in ('dental_medhx', 'dental_hx', 'dental_pain', 'dental_extraoral', 'dental_intraoral', 'dental_xray')
                and is_alert) then
    raise exception 'an existing option did not keep is_alert false';
  end if;
  if exists (select 1 from opd.emr_section_field f
              where f.options_source = 'FIELD' and f.section_key like 'dental\_%' and not exists (
                select 1 from opd.emr_section_field_option o where o.section_key = f.section_key and o.field_key = f.field_key)) then
    raise exception 'a new Dental chip field has no options';
  end if;
end
$$;
