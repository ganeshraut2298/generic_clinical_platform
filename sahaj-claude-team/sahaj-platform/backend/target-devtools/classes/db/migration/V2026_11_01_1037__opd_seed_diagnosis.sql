-- SAH-7 · Seed: starter list of common ICD-10 diagnoses seen in Indian OPD clinics.
-- STARTER LIST, NOT CLINICALLY REVIEWED. Human decision (1 Nov 2026): about 50 common ICD-10 codes.
-- A clinician must review codes, wording and specialty tags before real use.
-- Names follow the WHO ICD-10 wording. specialty is a hint for the specialty that sees it most;
-- searches must not filter by it. Reference data only.
insert into opd.diagnosis (code, name, specialty)
values
  -- General practice: fever and infections
  ('R50.9', 'Fever, unspecified',                                    'gp'),
  ('B34.9', 'Viral infection, unspecified',                          'gp'),
  ('J00',   'Acute nasopharyngitis (common cold)',                   'gp'),
  ('J02.9', 'Acute pharyngitis, unspecified',                        'gp'),
  ('J03.9', 'Acute tonsillitis, unspecified',                        'gp'),
  ('J06.9', 'Acute upper respiratory infection, unspecified',        'gp'),
  ('J20.9', 'Acute bronchitis, unspecified',                         'gp'),
  ('J18.9', 'Pneumonia, unspecified',                                'gp'),
  ('J45.9', 'Asthma, unspecified',                                   'gp'),
  ('J30.4', 'Allergic rhinitis, unspecified',                        'gp'),
  ('R05',   'Cough',                                                 'gp'),
  ('A90',   'Dengue fever [classical dengue]',                       'gp'),
  ('B54',   'Unspecified malaria',                                   'gp'),
  ('A01.0', 'Typhoid fever',                                         'gp'),
  ('N39.0', 'Urinary tract infection, site not specified',           'gp'),
  ('H10.9', 'Conjunctivitis, unspecified',                           'gp'),
  ('H66.9', 'Otitis media, unspecified',                             'gp'),
  -- General practice: stomach and gut
  ('A09',   'Diarrhoea and gastroenteritis of presumed infectious origin', 'gp'),
  ('K29.7', 'Gastritis, unspecified',                                'gp'),
  ('K21.9', 'Gastro-oesophageal reflux disease without oesophagitis','gp'),
  ('K30',   'Dyspepsia',                                             'gp'),
  ('K59.0', 'Constipation',                                          'gp'),
  ('R10.4', 'Other and unspecified abdominal pain',                  'gp'),
  -- General practice: long-term conditions
  ('I10',   'Essential (primary) hypertension',                      'gp'),
  ('E11.9', 'Type 2 diabetes mellitus without complications',        'gp'),
  ('E03.9', 'Hypothyroidism, unspecified',                           'gp'),
  ('E78.5', 'Hyperlipidaemia, unspecified',                          'gp'),
  ('D50.9', 'Iron deficiency anaemia, unspecified',                  'gp'),
  ('E55.9', 'Vitamin D deficiency, unspecified',                     'gp'),
  -- General practice: pain and other
  ('R51',   'Headache',                                              'gp'),
  ('G43.9', 'Migraine, unspecified',                                 'gp'),
  ('M54.5', 'Low back pain',                                         'gp'),
  ('M25.5', 'Pain in joint',                                         'gp'),
  ('M17.9', 'Gonarthrosis (osteoarthritis of knee), unspecified',    'gp'),
  -- Skin
  ('L30.9', 'Dermatitis, unspecified',                               'derma'),
  ('L20.9', 'Atopic dermatitis, unspecified',                        'derma'),
  ('B35.9', 'Dermatophytosis, unspecified',                          'derma'),
  ('B86',   'Scabies',                                               'derma'),
  ('L70.0', 'Acne vulgaris',                                         'derma'),
  -- Dental
  ('K02.9', 'Dental caries, unspecified',                            'dental'),
  ('K04.0', 'Pulpitis',                                              'dental'),
  ('K04.7', 'Periapical abscess without sinus',                      'dental'),
  ('K05.1', 'Chronic gingivitis',                                    'dental'),
  ('K05.3', 'Chronic periodontitis',                                 'dental'),
  ('K03.6', 'Deposits [accretions] on teeth',                        'dental'),
  -- Paediatrics
  ('J21.9', 'Acute bronchiolitis, unspecified',                      'paeds'),
  ('B01.9', 'Varicella without complication',                        'paeds'),
  ('B05.9', 'Measles without complication',                          'paeds'),
  ('B82.0', 'Intestinal helminthiasis, unspecified',                 'paeds'),
  ('E46',   'Unspecified protein-energy malnutrition',               'paeds'),
  ('Z23',   'Need for immunization against single bacterial diseases','paeds'),
  -- Gynaecology
  ('N92.6', 'Irregular menstruation, unspecified',                   'gynae'),
  ('N94.6', 'Dysmenorrhoea, unspecified',                            'gynae')
on conflict (code) do nothing;
