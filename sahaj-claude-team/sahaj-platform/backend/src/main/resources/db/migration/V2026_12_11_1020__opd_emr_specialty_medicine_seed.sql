-- SAH-61, 62, 66 (ADR-023 D4, "Data needed" item 7) · Medicines the specialty screens name that the master does not have yet.
-- Forward-only, additive: insert only, "on conflict do nothing" (a row that exists is left as it is). Global reference data,
-- no clinic column, no row level security, SELECT only for sahaj_app (unchanged, V2026_11_01_1033).
--
-- STARTER LIST, NOT CLINICALLY REVIEWED. Names, forms and usual doses are values from the EMR mockup
-- docs/design/mockup/EMR-Configuration-Smart-Clinic.html, accepted by the human in DEC-039, not clinically reviewed.
-- The names are the mockup's own so that a prescription row made by a specialty screen matches the master:
--   Paediatrics (DoctorPaeds MEDS)       paracetamol syrup 120 and 250 mg / 5 ml, ibuprofen syrup 100 mg / 5 ml
--   Obstetrics ANC (DoctorGynae SUP)     ferrous ascorbate 100 mg, calcium 500 mg + vitamin D3, folic acid 5 mg (exists)
--   Oncology take-home (DoctorOncology THM)  ondansetron 8 mg, dexamethasone 4 mg, pegfilgrastim 6 mg, benzydamine mouthwash,
--                                            loperamide 2 mg (pantoprazole 40 mg exists)
-- No allergy class and no saved prescription defaults: the mockup draws the full "frequency, days, when" for none of them
-- (the three defaults are all set or all null, ck_medicine_defaults_together), so the application's own fallback applies
-- (DEC-042 H6). Paediatric doses are weight based and computed by the server, never stored here.
-- The id is derived from the name (md5), so every environment gets the same id for the same medicine.

insert into opd.medicine (id, name, form, schedule, usual_dose)
select md5('opd.medicine:' || lower(v.name))::uuid, v.name, v.form, v.schedule, v.usual_dose
from (values
  -- Paediatrics: syrups dosed by weight
  ('Paracetamol syrup 120 mg / 5 ml',      'Syrup',      'SOS',         'Dose by weight'),
  ('Paracetamol syrup 250 mg / 5 ml',      'Syrup',      'SOS',         'Dose by weight'),
  ('Ibuprofen syrup 100 mg / 5 ml',        'Syrup',      '1-1-1',       'Dose by weight, with food'),
  -- Obstetrics: antenatal supplements (Folic acid 5 mg is already in the master)
  ('Ferrous ascorbate 100 mg',             'Tablet',     '1-0-1',       '1 tablet, after food'),
  ('Calcium 500 mg + Vitamin D3',          'Tablet',     '0-1-0',       '1 tablet, 2 hours apart from iron'),
  -- Oncology: take-home medicines
  ('Ondansetron 8 mg',                     'Tablet',     '1-0-1',       '1 tablet'),
  ('Dexamethasone 4 mg',                   'Tablet',     '1-0-1',       '1 tablet'),
  ('Pegfilgrastim 6 mg',                   'Injection',  'Once',        '6 mg injection under the skin, once'),
  ('Benzydamine mouthwash',                'Mouthwash',  '1-1-1',       'Rinse 3 times a day'),
  ('Loperamide 2 mg',                      'Capsule',    'SOS',         'After each loose motion, at most 8 a day')
) as v (name, form, schedule, usual_dose)
on conflict do nothing;
