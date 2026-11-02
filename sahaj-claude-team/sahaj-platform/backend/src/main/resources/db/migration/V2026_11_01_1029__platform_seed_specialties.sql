-- SAH-7 · Seed: the eight specialties on the mockup's Specialty template page (cfgSpec) and the
-- starter service list for each Phase 1 template. Reference data only, no clinic data.
-- Phase 1 (can be applied): gp, dental, paeds, homeo. Phase 2 (shown, cannot be applied): ayur, physio, derma, gynae.
-- Feature text and the gp and dental service names and prices come from the mockup (cfgSpec, SERV).
-- ASSUMPTION to confirm: the paeds service prices are not in the mockup; they are starter values.
-- Homeopathy has no service list in the mockup, so it starts empty. Owners edit prices after applying.
-- Prices are in paise (150 rupees = 15000).
insert into platform.specialty (code, name, sort_order) values
  ('gp',      'General practice', 1),
  ('dental',  'Dental',           2),
  ('paeds',   'Paediatrics',      3),
  ('homeo',   'Homeopathy',       4),
  ('ayur',    'Ayurveda',         5),
  ('physio',  'Physiotherapy',    6),
  ('derma',   'Dermatology',      7),
  ('gynae',   'Gynaecology',      8)
on conflict (code) do nothing;

insert into platform.specialty_template (code, phase, template_json) values
  ('gp', 1, '{
    "schema_version": 1,
    "features": ["Quick complaint and drug chips", "Repeat prescription", "Free follow-up window"],
    "services": [
      {"name": "Dressing",          "category": "Procedure", "price_paise": 15000},
      {"name": "Nebulisation",      "category": "Procedure", "price_paise": 20000},
      {"name": "Injection",         "category": "Nursing",   "price_paise": 10000},
      {"name": "ECG",               "category": "Procedure", "price_paise": 30000},
      {"name": "Blood sugar (RBS)", "category": "Lab",       "price_paise": 8000}
    ]}'::jsonb),
  ('dental', 1, '{
    "schema_version": 1,
    "features": ["Tooth chart, plan per tooth", "Payment per sitting", "X-ray images"],
    "services": [
      {"name": "Scaling and polishing",  "category": "Dental",    "price_paise": 120000},
      {"name": "Tooth-coloured filling", "category": "Dental",    "price_paise": 150000},
      {"name": "IOPA X-ray",             "category": "Radiology", "price_paise": 25000},
      {"name": "Extraction",             "category": "Dental",    "price_paise": 80000},
      {"name": "Root canal sitting",     "category": "Dental",    "price_paise": 250000}
    ]}'::jsonb),
  ('paeds', 1, '{
    "schema_version": 1,
    "features": ["Growth chart, weight-based dose", "Vaccine card and reminders"],
    "services": [
      {"name": "Nebulisation",       "category": "Procedure",    "price_paise": 20000},
      {"name": "Vaccination visit",  "category": "Nursing",      "price_paise": 10000},
      {"name": "Developmental check", "category": "Consultation", "price_paise": 30000}
    ]}'::jsonb),
  ('homeo', 1, '{
    "schema_version": 1,
    "features": ["Detailed case-taking", "Remedy, potency, in-clinic dispensing", "Monthly packages"],
    "services": []}'::jsonb),
  ('ayur', 2, '{
    "schema_version": 1,
    "features": ["Prakriti assessment", "Panchakarma sessions"],
    "services": []}'::jsonb),
  ('physio', 2, '{
    "schema_version": 1,
    "features": ["Daily session tracking", "Pain-score trend"],
    "services": []}'::jsonb),
  ('derma', 2, '{
    "schema_version": 1,
    "features": ["Body map, before/after photos"],
    "services": []}'::jsonb),
  ('gynae', 2, '{
    "schema_version": 1,
    "features": ["LMP / EDD, antenatal card"],
    "services": []}'::jsonb)
on conflict (code) do nothing;
