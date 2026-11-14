-- SAH-62, 63, 65, 66 (ADR-023 D4, "Data needed" items 1 to 6) · Seed of the global specialty reference tables of
-- V2026_12_11_1000. Reference data only; no patient data. Forward-only: "on conflict do nothing", so a re-run adds nothing.
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. Every value below is a value from the EMR mockup
-- docs/design/mockup/EMR-Configuration-Smart-Clinic.html (window.__DC_SRC), accepted by the human in DEC-039, not clinically
-- reviewed: the doses, vial steps, normal ranges, centile bands and schedule are the mockup's drawing, not medical advice.
-- A clinician must review every row before real use.
--   immunization groups and vaccines   DoctorPaeds SCHED (9 groups, 30 vaccines); the age windows are the analyst reading
--                                      of ADR-023 H9, accepted in DEC-044
--   growth bands                       DoctorPaeds weight note (5 bands at 18 months). The length and head circumference
--                                      centile notes are NOT seeded: the human decided to hide them (DEC-044, H8)
--   milestones                         DoctorPaeds MS (6 milestones at 18 months)
--   joint items                        DoctorPhysio ROM, TESTS and the muscle groups of the strength line (the knee)
--   chemotherapy protocols             DoctorOncology REG (AC, paclitaxel 3-weekly, paclitaxel weekly, TC)
-- Not seeded on purpose: sample patient values of the mockup (right and left degrees, labs, weights), demo rates (DEC-044).

-- ---------------------------------------------------------------------------------------------
-- 1. Immunization groups (9). Windows (end exclusive): Birth 0 to 6 weeks; 6 weeks to 10 weeks; 10 to 14 weeks;
--    14 weeks to 6 months; 6 to 9 months; 9 to 12 months; 12 to 15 months; 15 to 16 months; 16 to 19 months.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_immunization_group (group_key, label, position, window_from_value, window_from_unit, window_to_value, window_to_unit) values
  ('birth',        'Birth',         1, 0,  'DAYS',   6,  'WEEKS'),
  ('week_6',       '6 weeks',       2, 6,  'WEEKS',  10, 'WEEKS'),
  ('week_10',      '10 weeks',      3, 10, 'WEEKS',  14, 'WEEKS'),
  ('week_14',      '14 weeks',      4, 14, 'WEEKS',  6,  'MONTHS'),
  ('month_6_9',    '6–9 months',    5, 6,  'MONTHS', 9,  'MONTHS'),
  ('month_9',      '9 months',      6, 9,  'MONTHS', 12, 'MONTHS'),
  ('month_12',     '12 months',     7, 12, 'MONTHS', 15, 'MONTHS'),
  ('month_15',     '15 months',     8, 15, 'MONTHS', 16, 'MONTHS'),
  ('month_16_18',  '16–18 months',  9, 16, 'MONTHS', 19, 'MONTHS')
on conflict do nothing;

-- ---------------------------------------------------------------------------------------------
-- 2. Vaccines (30), in the order drawn.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_immunization_vaccine (vaccine_key, group_key, label, position) values
  ('bcg',              'birth',       'BCG',             1),
  ('opv_0',            'birth',       'OPV 0',           2),
  ('hep_b_1',          'birth',       'Hep B 1',         3),
  ('dtwp_1',           'week_6',      'DTwP 1',          1),
  ('ipv_1',            'week_6',      'IPV 1',           2),
  ('hib_1',            'week_6',      'Hib 1',           3),
  ('hep_b_2',          'week_6',      'Hep B 2',         4),
  ('rota_1',           'week_6',      'Rota 1',          5),
  ('pcv_1',            'week_6',      'PCV 1',           6),
  ('dtwp_2',           'week_10',     'DTwP 2',          1),
  ('ipv_2',            'week_10',     'IPV 2',           2),
  ('hib_2',            'week_10',     'Hib 2',           3),
  ('rota_2',           'week_10',     'Rota 2',          4),
  ('pcv_2',            'week_10',     'PCV 2',           5),
  ('dtwp_3',           'week_14',     'DTwP 3',          1),
  ('ipv_3',            'week_14',     'IPV 3',           2),
  ('hib_3',            'week_14',     'Hib 3',           3),
  ('rota_3',           'week_14',     'Rota 3',          4),
  ('pcv_3',            'week_14',     'PCV 3',           5),
  ('influenza_1',      'month_6_9',   'Influenza 1',     1),
  ('influenza_2',      'month_6_9',   'Influenza 2',     2),
  ('typhoid_conj',     'month_6_9',   'Typhoid conj.',   3),
  ('mmr_1',            'month_9',     'MMR 1',           1),
  ('hep_a_1',          'month_12',    'Hep A 1',         1),
  ('mmr_2',            'month_15',    'MMR 2',           1),
  ('varicella_1',      'month_15',    'Varicella 1',     2),
  ('pcv_booster',      'month_15',    'PCV booster',     3),
  ('dtwp_booster_1',   'month_16_18', 'DTwP booster 1',  1),
  ('ipv_booster_1',    'month_16_18', 'IPV booster 1',   2),
  ('hib_booster_1',    'month_16_18', 'Hib booster 1',   3)
on conflict do nothing;

-- ---------------------------------------------------------------------------------------------
-- 3. Growth bands: weight for an 18-month child, as drawn (below 8.6 kg: below 3rd centile, alert; below 9.8: about 15th;
--    below 11: about 25 to 50th; below 13.7: about 50 to 97th; above: above 97th, alert). sex null = either.
--    Length and head circumference: not seeded (DEC-044, H8). The id is derived from the key, as the other seeds do.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_growth_band (id, measure, sex, age_months_from, age_months_to, upper_exclusive, label, alert, position) values
  (md5('opd.emr_growth_band:WEIGHT:ANY:18:1')::uuid, 'WEIGHT', null, 18, 18, 8.6,  'Below 3rd centile',    true,  1),
  (md5('opd.emr_growth_band:WEIGHT:ANY:18:2')::uuid, 'WEIGHT', null, 18, 18, 9.8,  '~15th centile',        false, 2),
  (md5('opd.emr_growth_band:WEIGHT:ANY:18:3')::uuid, 'WEIGHT', null, 18, 18, 11,   '~25–50th centile',    false, 3),
  (md5('opd.emr_growth_band:WEIGHT:ANY:18:4')::uuid, 'WEIGHT', null, 18, 18, 13.7, '~50–97th centile',    false, 4),
  (md5('opd.emr_growth_band:WEIGHT:ANY:18:5')::uuid, 'WEIGHT', null, 18, 18, null, 'Above 97th centile',   true,  5)
on conflict do nothing;

-- ---------------------------------------------------------------------------------------------
-- 4. Milestones at 18 months (6), in the order drawn.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_milestone (age_band_months, milestone_key, label, domain, position) values
  (18, 'walks_alone',             'Walks alone',               'GROSS_MOTOR', 1),
  (18, 'climbs_stairs_with_help', 'Climbs stairs with help',   'GROSS_MOTOR', 2),
  (18, 'scribbles_with_crayon',   'Scribbles with crayon',     'FINE_MOTOR',  3),
  (18, 'feeds_self_with_spoon',   'Feeds self with spoon',     'SOCIAL',      4),
  (18, 'points_to_show_interest', 'Points to show interest',   'SOCIAL',      5),
  (18, 'says_6_or_more_words',    'Says 6 or more words',      'LANGUAGE',    6)
on conflict do nothing;

-- ---------------------------------------------------------------------------------------------
-- 5. Knee reference. ROM normals in degrees: flexion 0 to 135, extension 0 to 0, flexion (passive) 0 to 135.
--    MMT groups of the strength line: quadriceps, hamstrings. Six special tests, in the order drawn.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_joint_item (joint_key, kind, item_key, label, normal_from_deg, normal_to_deg, position) values
  ('knee', 'ROM',          'flexion',            'Flexion',            0,    135,  1),
  ('knee', 'ROM',          'extension',          'Extension',          0,    0,    2),
  ('knee', 'ROM',          'flexion_passive',    'Flexion (passive)',  0,    135,  3),
  ('knee', 'MMT',          'quadriceps',         'Quadriceps',         null, null, 1),
  ('knee', 'MMT',          'hamstrings',         'Hamstrings',         null, null, 2),
  ('knee', 'SPECIAL_TEST', 'mcmurray_medial',    'McMurray (medial)',  null, null, 1),
  ('knee', 'SPECIAL_TEST', 'lachman',            'Lachman',            null, null, 2),
  ('knee', 'SPECIAL_TEST', 'patellar_grind',     'Patellar grind',     null, null, 3),
  ('knee', 'SPECIAL_TEST', 'anterior_drawer',    'Anterior drawer',    null, null, 4),
  ('knee', 'SPECIAL_TEST', 'valgus_stress',      'Valgus stress',      null, null, 5),
  ('knee', 'SPECIAL_TEST', 'apley_compression',  'Apley compression',  null, null, 6)
on conflict do nothing;

-- ---------------------------------------------------------------------------------------------
-- 6. Chemotherapy protocols (4), as drawn in DoctorOncology REG. Only the paclitaxel 3-weekly protocol is "Suggested" for
--    Change regimen. Drug tuple in the mockup: [name, dose per m2, infusion minutes, vial step mg, flag, note]; flag "dox" =
--    doxorubicin = vesicant and anthracycline (lifetime limit). Pre-medicine tuple: [text, minutes]. The mockup's per-patient
--    state (consented, scheme) is not protocol data and is not seeded. Keys of a pre-medicine are scoped to its protocol.
-- ---------------------------------------------------------------------------------------------
insert into opd.emr_chemo_protocol (protocol_key, label, sub_line, interval_days, emetic_risk, suggested_on_change, position, definition) values
  ('AC', 'AC', 'Doxorubicin + cyclophosphamide · every 21 days', 21, 'HIGH', false, 1,
   '{"schemaVersion":1,
     "drugs":[
       {"drugKey":"doxorubicin","name":"Doxorubicin","dosePerM2":60,"infusionMinutes":15,"vialStepMg":5,"vesicant":true,"anthracycline":true,"note":"Vesicant: give by port, check blood return"},
       {"drugKey":"cyclophosphamide","name":"Cyclophosphamide","dosePerM2":600,"infusionMinutes":30,"vialStepMg":20,"vesicant":false,"anthracycline":false}],
     "premeds":[
       {"key":"ondansetron_iv","text":"Ondansetron 8 mg IV","minutes":15},
       {"key":"dexamethasone_iv","text":"Dexamethasone 12 mg IV","minutes":15},
       {"key":"aprepitant_oral","text":"Aprepitant 125 mg oral","minutes":0}]}'::jsonb),
  ('PACLI_3W', 'Paclitaxel 3-weekly', 'Paclitaxel 175 mg/m² · every 21 days', 21, 'LOW', true, 2,
   '{"schemaVersion":1,
     "drugs":[
       {"drugKey":"paclitaxel","name":"Paclitaxel","dosePerM2":175,"infusionMinutes":180,"vialStepMg":10,"vesicant":false,"anthracycline":false,"note":"Watch first 15 min for reaction · in-line filter"}],
     "premeds":[
       {"key":"dexamethasone_iv","text":"Dexamethasone 20 mg IV","minutes":15},
       {"key":"pheniramine_iv","text":"Pheniramine 45.5 mg IV","minutes":15},
       {"key":"famotidine_iv","text":"Famotidine 20 mg IV","minutes":0}]}'::jsonb),
  ('PACLI_WEEKLY', 'Paclitaxel weekly', 'Paclitaxel 80 mg/m² · every 7 days', 7, 'LOW', false, 3,
   '{"schemaVersion":1,
     "drugs":[
       {"drugKey":"paclitaxel","name":"Paclitaxel","dosePerM2":80,"infusionMinutes":60,"vialStepMg":10,"vesicant":false,"anthracycline":false,"note":"In-line filter"}],
     "premeds":[
       {"key":"dexamethasone_iv","text":"Dexamethasone 8 mg IV","minutes":15},
       {"key":"pheniramine_iv","text":"Pheniramine 45.5 mg IV","minutes":15},
       {"key":"famotidine_iv","text":"Famotidine 20 mg IV","minutes":0}]}'::jsonb),
  ('TC', 'TC', 'Docetaxel + cyclophosphamide · every 21 days', 21, 'MODERATE', false, 4,
   '{"schemaVersion":1,
     "drugs":[
       {"drugKey":"docetaxel","name":"Docetaxel","dosePerM2":75,"infusionMinutes":60,"vialStepMg":10,"vesicant":false,"anthracycline":false,"note":"Watch for reaction · needs G-CSF"},
       {"drugKey":"cyclophosphamide","name":"Cyclophosphamide","dosePerM2":600,"infusionMinutes":30,"vialStepMg":20,"vesicant":false,"anthracycline":false}],
     "premeds":[
       {"key":"dexamethasone_oral","text":"Dexamethasone 8 mg oral (day before)","minutes":0},
       {"key":"ondansetron_iv","text":"Ondansetron 8 mg IV","minutes":15}]}'::jsonb)
on conflict do nothing;
