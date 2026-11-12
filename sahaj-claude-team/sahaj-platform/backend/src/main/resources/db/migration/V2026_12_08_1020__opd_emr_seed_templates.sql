-- SAH-44 (ADR-019 D4, D6) · Seed: the four EMR specialty templates, version 1, with their 38 sections,
-- the template chip options and the 8 specialty aliases.
-- STARTER LIST, NOT CLINICALLY REVIEWED (OQ-43-8). Reference data only; no real patient data.
--
-- Source of the sections: docs/stories/SAH-44.md "Notes for design" table, which is the business analyst's reading of
-- BRD section 4 (FR-11) and section 5 (SO-44-10). Section keys become permanent once a visit stores a snapshot;
-- the human must confirm them before SAH-46 ships. Order within a part is the BRD order.
-- Counts (sections, required by default): General practice 11 (6), Dental 11 (7), Paediatrics 9 (4), Homeopathy 7 (6).
--
-- Not hideable (OQ-43-14 default, ADR-019 D4): prescription, remedy_potency, history_allergies, medical_alerts.
-- icon_key is null everywhere until the EMR mockup shows the icons (OQ-43-20).
-- Chip options: only what the BRD and the repo mockup name are filled in (the seven signs and four systems of the
-- BRD, the mockup's allergy and chronic condition names). Every TEMPLATE chip list also has one "None" / "Nothing abnormal"
-- option (is_none) so that a required chip list can be completed with no finding (OQ-43-19). The other chip lists
-- (local, extra-oral, intra-oral examination, radiographs, birth and immunisation, development, vaccines, case history,
-- mentals and generals) have no named content in the BRD or the mockup and are left to a clinician.

insert into opd.emr_template (template_key, version, name) values
  ('gp',     1, 'General practice'),
  ('dental', 1, 'Dental'),
  ('paeds',  1, 'Paediatrics'),
  ('homeo',  1, 'Homeopathy');

-- (template_key, version, section_key, position, label, part, required_default, hideable, editor_type, options_source, options_master)
insert into opd.emr_template_section
  (template_key, version, section_key, position, label, part, required_default, hideable, editor_type, options_source, options_master)
values
  -- General practice: 6 in Assessment, 5 in Treatment
  ('gp', 1, 'chief_complaints',   1, 'Chief complaints',          'ASSESSMENT', true,  true,  'COMPLAINTS',      'MASTER',   'complaints'),
  ('gp', 1, 'history_allergies',  2, 'History & allergies',       'ASSESSMENT', false, false, 'CHIP_LIST',       'TEMPLATE', null),
  ('gp', 1, 'vitals',             3, 'Vitals',                    'ASSESSMENT', true,  true,  'VITALS_STEPPERS', 'NONE',     null),
  ('gp', 1, 'general_exam',       4, 'General examination',       'ASSESSMENT', true,  true,  'SIGNS',           'TEMPLATE', null),
  ('gp', 1, 'systemic_exam',      5, 'Systemic examination',      'ASSESSMENT', false, true,  'SYSTEMS',         'TEMPLATE', null),
  ('gp', 1, 'local_exam',         6, 'Local examination',         'ASSESSMENT', false, true,  'CHIP_LIST',       'TEMPLATE', null),
  ('gp', 1, 'diagnosis',          7, 'Diagnosis',                 'TREATMENT',  true,  true,  'DIAGNOSIS',       'MASTER',   'diagnoses'),
  ('gp', 1, 'prescription',       8, 'Prescription',              'TREATMENT',  true,  false, 'PRESCRIPTION',    'MASTER',   'medicines'),
  ('gp', 1, 'investigations',     9, 'Investigations',            'TREATMENT',  false, true,  'CHIP_LIST',       'MASTER',   'investigations'),
  ('gp', 1, 'services',          10, 'Procedures & services',     'TREATMENT',  false, true,  'SERVICES',        'TARIFF',   null),
  ('gp', 1, 'advice_followup',   11, 'Advice & follow-up',        'TREATMENT',  true,  true,  'ADVICE',          'MASTER',   'advice'),
  -- Dental: 6 in Assessment, 5 in Treatment
  ('dental', 1, 'chief_complaints', 1, 'Chief complaints',          'ASSESSMENT', true,  true,  'COMPLAINTS',     'MASTER',   'complaints'),
  ('dental', 1, 'medical_alerts',   2, 'Medical alerts',            'ASSESSMENT', true,  false, 'CHIP_LIST',      'TEMPLATE', null),
  ('dental', 1, 'extra_oral_exam',  3, 'Extra-oral examination',    'ASSESSMENT', false, true,  'CHIP_LIST',      'TEMPLATE', null),
  ('dental', 1, 'intra_oral_exam',  4, 'Intra-oral examination',    'ASSESSMENT', true,  true,  'CHIP_LIST',      'TEMPLATE', null),
  ('dental', 1, 'tooth_chart',      5, 'Tooth chart',               'ASSESSMENT', true,  true,  'TOOTH_CHART',    'NONE',     null),
  ('dental', 1, 'radiographs',      6, 'Radiographs',               'ASSESSMENT', false, true,  'CHIP_LIST',      'TEMPLATE', null),
  ('dental', 1, 'diagnosis',        7, 'Diagnosis',                 'TREATMENT',  true,  true,  'DIAGNOSIS',      'MASTER',   'diagnoses'),
  ('dental', 1, 'treatment_plan',   8, 'Treatment plan & sittings', 'TREATMENT',  true,  true,  'TREATMENT_PLAN', 'NONE',     null),
  ('dental', 1, 'prescription',     9, 'Prescription',              'TREATMENT',  false, false, 'PRESCRIPTION',   'MASTER',   'medicines'),
  ('dental', 1, 'services',        10, 'Procedures & services',     'TREATMENT',  false, true,  'SERVICES',       'TARIFF',   null),
  ('dental', 1, 'advice_followup', 11, 'Advice & follow-up',        'TREATMENT',  true,  true,  'ADVICE',         'MASTER',   'advice'),
  -- Paediatrics: 5 in Assessment, 4 in Treatment
  ('paeds', 1, 'chief_complaints',    1, 'Chief complaints',              'ASSESSMENT', true,  true,  'COMPLAINTS',      'MASTER',   'complaints'),
  ('paeds', 1, 'birth_immunisation',  2, 'Birth & immunisation history',  'ASSESSMENT', false, true,  'CHIP_LIST',       'TEMPLATE', null),
  ('paeds', 1, 'vitals_growth',       3, 'Vitals & growth',               'ASSESSMENT', true,  true,  'VITALS_STEPPERS', 'NONE',     null),
  ('paeds', 1, 'general_exam',        4, 'General examination',           'ASSESSMENT', true,  true,  'SIGNS',           'TEMPLATE', null),
  ('paeds', 1, 'development',         5, 'Development',                   'ASSESSMENT', false, true,  'CHIP_LIST',       'TEMPLATE', null),
  ('paeds', 1, 'diagnosis',           6, 'Diagnosis',                     'TREATMENT',  true,  true,  'DIAGNOSIS',       'MASTER',   'diagnoses'),
  ('paeds', 1, 'prescription',        7, 'Prescription (by weight)',      'TREATMENT',  false, false, 'PRESCRIPTION',    'MASTER',   'medicines'),
  ('paeds', 1, 'vaccines_today',      8, 'Vaccines given today',          'TREATMENT',  false, true,  'CHIP_LIST',       'TEMPLATE', null),
  ('paeds', 1, 'advice_followup',     9, 'Advice & follow-up',            'TREATMENT',  false, true,  'ADVICE',          'MASTER',   'advice'),
  -- Homeopathy: 4 in Assessment, 3 in Treatment
  ('homeo', 1, 'chief_complaints', 1, 'Chief complaints',   'ASSESSMENT', true,  true,  'COMPLAINTS',      'MASTER',   'complaints'),
  ('homeo', 1, 'case_history',     2, 'Case history',       'ASSESSMENT', true,  true,  'CHIP_LIST',       'TEMPLATE', null),
  ('homeo', 1, 'mentals_generals', 3, 'Mentals & generals', 'ASSESSMENT', true,  true,  'CHIP_LIST',       'TEMPLATE', null),
  ('homeo', 1, 'vitals',           4, 'Vitals',             'ASSESSMENT', false, true,  'VITALS_STEPPERS', 'NONE',     null),
  ('homeo', 1, 'diagnosis',        5, 'Diagnosis',          'TREATMENT',  true,  true,  'DIAGNOSIS',       'MASTER',   'diagnoses'),
  ('homeo', 1, 'remedy_potency',   6, 'Remedy & potency',   'TREATMENT',  true,  false, 'PRESCRIPTION',    'MASTER',   'medicines'),
  ('homeo', 1, 'advice_followup',  7, 'Advice & follow-up', 'TREATMENT',  true,  true,  'ADVICE',          'MASTER',   'advice');

-- (template_key, version, section_key, option_key, position, label, style, is_none)
insert into opd.emr_template_option
  (template_key, version, section_key, option_key, position, label, style, is_none)
values
  -- General practice, General examination: the seven signs of BRD FR-11
  ('gp', 1, 'general_exam', 'pallor',        1, 'Pallor',        'NORMAL', false),
  ('gp', 1, 'general_exam', 'icterus',       2, 'Icterus',       'NORMAL', false),
  ('gp', 1, 'general_exam', 'cyanosis',      3, 'Cyanosis',      'NORMAL', false),
  ('gp', 1, 'general_exam', 'clubbing',      4, 'Clubbing',      'NORMAL', false),
  ('gp', 1, 'general_exam', 'lymph_nodes',   5, 'Lymph nodes',   'NORMAL', false),
  ('gp', 1, 'general_exam', 'oedema',        6, 'Oedema',        'NORMAL', false),
  ('gp', 1, 'general_exam', 'dehydration',   7, 'Dehydration',   'NORMAL', false),
  -- General practice, Systemic examination: the four systems of BRD FR-11
  ('gp', 1, 'systemic_exam', 'cvs',          1, 'CVS',           'NORMAL', false),
  ('gp', 1, 'systemic_exam', 'rs',           2, 'RS',            'NORMAL', false),
  ('gp', 1, 'systemic_exam', 'cns',          3, 'CNS',           'NORMAL', false),
  ('gp', 1, 'systemic_exam', 'abdomen',      4, 'Abdomen',       'NORMAL', false),
  -- General practice, History & allergies: allergy chips are the red style (names from the mockup Allergy master,
  -- chronic conditions from the mockup patient list)
  ('gp', 1, 'history_allergies', 'penicillin',          1, 'Penicillin',          'ALERT',  false),
  ('gp', 1, 'history_allergies', 'sulfa_drugs',         2, 'Sulfa drugs',         'ALERT',  false),
  ('gp', 1, 'history_allergies', 'nsaids',              3, 'NSAIDs',              'ALERT',  false),
  ('gp', 1, 'history_allergies', 'iodinated_contrast',  4, 'Iodinated contrast',  'ALERT',  false),
  ('gp', 1, 'history_allergies', 'peanut',              5, 'Peanut',              'ALERT',  false),
  ('gp', 1, 'history_allergies', 'egg',                 6, 'Egg',                 'ALERT',  false),
  ('gp', 1, 'history_allergies', 'latex',               7, 'Latex',               'ALERT',  false),
  ('gp', 1, 'history_allergies', 'dust_mite',           8, 'Dust mite',           'ALERT',  false),
  ('gp', 1, 'history_allergies', 'hypertension',        9, 'Hypertension',        'NORMAL', false),
  ('gp', 1, 'history_allergies', 'type_2_diabetes',    10, 'Type 2 diabetes',     'NORMAL', false),
  ('gp', 1, 'history_allergies', 'hypothyroidism',     11, 'Hypothyroidism',      'NORMAL', false),
  ('gp', 1, 'history_allergies', 'none',               12, 'None known',          'NORMAL', true),
  -- General practice, Local examination
  ('gp', 1, 'local_exam', 'none',                       1, 'Nothing abnormal',    'NORMAL', true),
  -- Dental, Medical alerts: red alert chips (allergy names and chronic conditions named in the mockup only) and None
  ('dental', 1, 'medical_alerts', 'penicillin',         1, 'Penicillin',          'ALERT',  false),
  ('dental', 1, 'medical_alerts', 'nsaids',             2, 'NSAIDs',              'ALERT',  false),
  ('dental', 1, 'medical_alerts', 'latex',              3, 'Latex',               'ALERT',  false),
  ('dental', 1, 'medical_alerts', 'hypertension',       4, 'Hypertension',        'ALERT',  false),
  ('dental', 1, 'medical_alerts', 'type_2_diabetes',    5, 'Type 2 diabetes',     'ALERT',  false),
  ('dental', 1, 'medical_alerts', 'none',               6, 'None',                'NORMAL', true),
  -- Dental, the other chip lists: only the None option
  ('dental', 1, 'extra_oral_exam', 'none',              1, 'Nothing abnormal',    'NORMAL', true),
  ('dental', 1, 'intra_oral_exam', 'none',              1, 'Nothing abnormal',    'NORMAL', true),
  ('dental', 1, 'radiographs',     'none',              1, 'None',                'NORMAL', true),
  -- Paediatrics: the same seven signs as General practice (BRD FR-11 Signs editor), then the chip lists
  ('paeds', 1, 'general_exam', 'pallor',                1, 'Pallor',              'NORMAL', false),
  ('paeds', 1, 'general_exam', 'icterus',               2, 'Icterus',             'NORMAL', false),
  ('paeds', 1, 'general_exam', 'cyanosis',              3, 'Cyanosis',            'NORMAL', false),
  ('paeds', 1, 'general_exam', 'clubbing',              4, 'Clubbing',            'NORMAL', false),
  ('paeds', 1, 'general_exam', 'lymph_nodes',           5, 'Lymph nodes',         'NORMAL', false),
  ('paeds', 1, 'general_exam', 'oedema',                6, 'Oedema',              'NORMAL', false),
  ('paeds', 1, 'general_exam', 'dehydration',           7, 'Dehydration',         'NORMAL', false),
  ('paeds', 1, 'birth_immunisation', 'none',            1, 'Nothing abnormal',    'NORMAL', true),
  ('paeds', 1, 'development',        'none',            1, 'Nothing abnormal',    'NORMAL', true),
  ('paeds', 1, 'vaccines_today',     'none',            1, 'None',                'NORMAL', true),
  -- Homeopathy: the two required chip lists carry only the None option
  ('homeo', 1, 'case_history',     'none',              1, 'None',                'NORMAL', true),
  ('homeo', 1, 'mentals_generals', 'none',              1, 'None',                'NORMAL', true);

-- Doctor specialty text (normalised) to template. Only the BRD and mockup names (ADR-019 D6).
insert into opd.emr_specialty_alias (alias_normalised, template_key) values
  ('general physician', 'gp'),
  ('general practice',  'gp'),
  ('dental surgeon',    'dental'),
  ('dental',            'dental'),
  ('paediatrician',     'paeds'),
  ('paediatrics',       'paeds'),
  ('homeopath',         'homeo'),
  ('homeopathy',        'homeo');
