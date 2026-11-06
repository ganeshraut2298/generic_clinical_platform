-- SAH-7 · Seed: starter list of common medicines for an Indian OPD clinic (generic names).
-- STARTER LIST, NOT CLINICALLY REVIEWED. Human decision (1 Nov 2026): about 50 common medicines.
-- A clinician must review names, strengths, schedules and doses before real use.
-- Reference data only. No brand names, no real patient data. schedule is morning-afternoon-night
-- (1-0-1 = morning and night), or SOS (when needed), Once, Once a week. Paediatric liquids are
-- weight based: the doctor sets the actual dose.
-- The id is derived from the name (md5), so every environment gets the same id for the same medicine.
insert into opd.medicine (id, name, form, schedule, usual_dose)
select md5('opd.medicine:' || lower(v.name))::uuid, v.name, v.form, v.schedule, v.usual_dose
from (values
  -- Pain and fever
  ('Paracetamol 500 mg',                         'Tablet',     '1-1-1', '1 tablet'),
  ('Paracetamol 650 mg',                         'Tablet',     '1-0-1', '1 tablet'),
  ('Paracetamol 125 mg/5 ml',                    'Syrup',      '1-1-1', '5 ml, weight based'),
  ('Ibuprofen 400 mg',                           'Tablet',     '1-0-1', '1 tablet'),
  ('Ibuprofen 100 mg/5 ml',                      'Suspension', '1-1-1', '5 ml, weight based'),
  ('Diclofenac 50 mg',                           'Tablet',     '1-0-1', '1 tablet'),
  ('Aceclofenac 100 mg',                         'Tablet',     '1-0-1', '1 tablet'),
  -- Antibiotics and antiparasitics
  ('Amoxicillin 500 mg',                         'Capsule',    '1-1-1', '1 capsule'),
  ('Amoxicillin 125 mg/5 ml',                    'Suspension', '1-1-1', '5 ml, weight based'),
  ('Amoxicillin 500 mg + Clavulanic acid 125 mg','Tablet',     '1-0-1', '1 tablet'),
  ('Azithromycin 500 mg',                        'Tablet',     '1-0-0', '1 tablet'),
  ('Cefixime 200 mg',                            'Tablet',     '1-0-1', '1 tablet'),
  ('Cefpodoxime 200 mg',                         'Tablet',     '1-0-1', '1 tablet'),
  ('Ciprofloxacin 500 mg',                       'Tablet',     '1-0-1', '1 tablet'),
  ('Doxycycline 100 mg',                         'Capsule',    '1-0-1', '1 capsule'),
  ('Metronidazole 400 mg',                       'Tablet',     '1-1-1', '1 tablet'),
  ('Nitrofurantoin 100 mg',                      'Capsule',    '1-0-1', '1 capsule'),
  ('Albendazole 400 mg',                         'Tablet',     'Once',  '1 tablet'),
  -- Allergy, cough and breathing
  ('Cetirizine 10 mg',                           'Tablet',     '0-0-1', '1 tablet'),
  ('Cetirizine 5 mg/5 ml',                       'Syrup',      '0-0-1', '5 ml, weight based'),
  ('Levocetirizine 5 mg',                        'Tablet',     '0-0-1', '1 tablet'),
  ('Chlorpheniramine 4 mg',                      'Tablet',     '0-0-1', '1 tablet'),
  ('Montelukast 10 mg',                          'Tablet',     '0-0-1', '1 tablet'),
  ('Salbutamol 100 mcg per dose',                'Inhaler',    'SOS',   '2 puffs'),
  ('Budesonide 200 mcg per dose',                'Inhaler',    '1-0-1', '1 puff'),
  ('Ambroxol 30 mg/5 ml',                        'Syrup',      '1-1-1', '5 ml'),
  ('Dextromethorphan 10 mg/5 ml',                'Syrup',      '1-1-1', '5 ml'),
  -- Stomach and gut
  ('Pantoprazole 40 mg',                         'Tablet',     '1-0-0', '1 tablet, before food'),
  ('Omeprazole 20 mg',                           'Capsule',    '1-0-0', '1 capsule, before food'),
  ('Domperidone 10 mg',                          'Tablet',     '1-1-1', '1 tablet, before food'),
  ('Ondansetron 4 mg',                           'Tablet',     'SOS',   '1 tablet'),
  ('Dicyclomine 20 mg',                          'Tablet',     'SOS',   '1 tablet'),
  ('ORS (WHO low osmolarity)',                   'Sachet',     'SOS',   '1 sachet in 1 litre of water'),
  ('Zinc 20 mg',                                 'Tablet',     '1-0-0', '1 dispersible tablet'),
  ('Lactulose 10 g/15 ml',                       'Syrup',      '0-0-1', '15 ml'),
  ('Aluminium hydroxide + Magnesium hydroxide',  'Suspension', '1-1-1', '10 ml'),
  -- Long-term conditions
  ('Metformin 500 mg',                           'Tablet',     '1-0-1', '1 tablet, after food'),
  ('Glimepiride 1 mg',                           'Tablet',     '1-0-0', '1 tablet, with breakfast'),
  ('Amlodipine 5 mg',                            'Tablet',     '1-0-0', '1 tablet'),
  ('Telmisartan 40 mg',                          'Tablet',     '1-0-0', '1 tablet'),
  ('Losartan 50 mg',                             'Tablet',     '1-0-0', '1 tablet'),
  ('Atorvastatin 10 mg',                         'Tablet',     '0-0-1', '1 tablet'),
  ('Levothyroxine 50 mcg',                       'Tablet',     '1-0-0', '1 tablet, empty stomach'),
  -- Vitamins and minerals
  ('Iron + Folic acid',                          'Tablet',     '1-0-0', '1 tablet'),
  ('Folic acid 5 mg',                            'Tablet',     '1-0-0', '1 tablet'),
  ('Cholecalciferol (Vitamin D3) 60000 IU',      'Sachet',     'Once a week', '1 sachet'),
  ('Calcium carbonate 500 mg + Vitamin D3 250 IU','Tablet',    '1-0-0', '1 tablet'),
  -- Skin, eye and ear
  ('Clotrimazole 1%',                            'Cream',      '1-0-1', 'Apply a thin layer'),
  ('Mupirocin 2%',                               'Ointment',   '1-0-1', 'Apply a thin layer'),
  ('Permethrin 5%',                              'Lotion',     'Once',  'Apply to whole body, wash off after 8 to 12 hours'),
  ('Moxifloxacin 0.5%',                          'Eye drops',  '1-1-1-1', '1 drop in each affected eye'),
  ('Ofloxacin 0.3%',                             'Ear drops',  '1-0-1', '3 drops in the affected ear')
) as v (name, form, schedule, usual_dose)
on conflict do nothing;
