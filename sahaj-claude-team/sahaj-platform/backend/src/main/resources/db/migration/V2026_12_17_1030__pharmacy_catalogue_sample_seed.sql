-- SAH-78 WP-B (story 78-5), migration B2 of 8. DEC-062 Q2, ADR-028 D3: SAMPLE catalogue, never in production.
--
-- 30 fake products (G-000101 to G-000130, mockup rows: Ceftriaxone, Paracetamol, Pantoprazole, Metformin, gloves and so on), each with
-- sample = true. Names start with "Sample", manufacturers are fake (Sample Labs (fake) and similar). The values (schedule, GST rate,
-- HSN, units) are NOT clinically validated and must not be used for real stocking: "Sample - not clinically validated".
-- The real catalogue is a later story, once the human names a licensed source and a pharmacist reviews it.
--
-- GUARD: Flyway placeholder ${sahajSampleData}. Its default must be false in application.yml (every environment); it is true only in
-- application-dev.yml and application-test.yml (java-developer). When it is not exactly 'true' this migration inserts nothing and is
-- still recorded as applied. Flyway computes the checksum before placeholder replacement, so the value never changes the checksum.
-- Consequence: a database that applied this file with false stays without samples; to get them, use a fresh database or the test
-- profile (clean on sahaj_test) or insert through a later dev-only migration. Never set the placeholder to true in production.
-- Check for production: select count(*) from pharmacy.catalogue_product where sample; must be 0.
--
-- This file creates no table. Runs as sahaj_migrator, which has the all-commands policy on the global tables (migration B1).

do $seed$
begin
  if '${sahajSampleData}' = 'true' then

    insert into pharmacy.catalogue_product
      (code, name, salt, strength, form, manufacturer, hsn, gst_rate, schedule, ndps, purchase_unit, issue_unit, conversion, active, sample, dup_key)
    select v.code, v.name, v.salt, v.strength, v.form, v.manufacturer, v.hsn, v.gst_rate, v.schedule, v.ndps,
           v.purchase_unit, v.issue_unit, v.conversion, true, true,
           -- dup_key = salt | strength | form: trimmed, lower case, spaces collapsed; strength without spaces, grams written as milligrams.
           lower(regexp_replace(btrim(v.salt), '\s+', ' ', 'g'))
           || '|' ||
           case when v.strength ~* '^[0-9]+(\.[0-9]+)?\s*g$'
                then trim_scale(regexp_replace(v.strength, '\s*g$', '', 'i')::numeric * 1000)::text || 'mg'
                else lower(regexp_replace(v.strength, '\s+', '', 'g'))
           end
           || '|' ||
           lower(regexp_replace(btrim(v.form), '\s+', ' ', 'g'))
    from (values
      ('G-000101', 'Sample Ceftriaxone 1 g injection',              'Ceftriaxone',                  '1 g',          'injection',   'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Box of 10',        'vial',     10),
      ('G-000102', 'Sample Ceftriaxone 500 mg injection',           'Ceftriaxone',                  '500 mg',       'injection',   'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Box of 10',        'vial',     10),
      ('G-000103', 'Sample Paracetamol 650 mg tablet',              'Paracetamol',                  '650 mg',       'tablet',      'Demo Pharma (fake)',      '3004', 12, 'NONE', false, 'Strip of 15',      'tablet',   15),
      ('G-000104', 'Sample Paracetamol 1 g infusion',               'Paracetamol',                  '1 g',          'infusion',    'Demo Pharma (fake)',      '3004', 12, 'NONE', false, 'Box of 10',        'bottle',   10),
      ('G-000105', 'Sample Pantoprazole 40 mg tablet EC',           'Pantoprazole',                 '40 mg',        'tablet EC',   'Sample Remedies (fake)',  '3004', 12, 'H',    false, 'Strip of 15',      'tablet',   15),
      ('G-000106', 'Sample Pantoprazole 40 mg injection',           'Pantoprazole',                 '40 mg',        'injection',   'Sample Remedies (fake)',  '3004', 12, 'H',    false, 'Box of 10',        'vial',     10),
      ('G-000107', 'Sample Metformin 500 mg tablet SR',             'Metformin',                    '500 mg',       'tablet SR',   'Demo Pharma (fake)',      '3004', 12, 'H',    false, 'Strip of 15',      'tablet',   15),
      ('G-000108', 'Sample Atorvastatin 10 mg tablet',              'Atorvastatin',                 '10 mg',        'tablet',      'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Strip of 15',      'tablet',   15),
      ('G-000109', 'Sample Azithromycin 500 mg tablet',             'Azithromycin',                 '500 mg',       'tablet',      'Sample Remedies (fake)',  '3004', 12, 'H',    false, 'Strip of 3',       'tablet',    3),
      ('G-000110', 'Sample Amoxicillin and Clavulanic acid tablet', 'Amoxicillin + Clavulanic acid', '500 mg + 125 mg', 'tablet',   'Demo Pharma (fake)',      '3004', 12, 'H',    false, 'Strip of 10',      'tablet',   10),
      ('G-000111', 'Sample Amlodipine 5 mg tablet',                 'Amlodipine',                   '5 mg',         'tablet',      'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Strip of 15',      'tablet',   15),
      ('G-000112', 'Sample Cetirizine 10 mg tablet',                'Cetirizine',                   '10 mg',        'tablet',      'Sample Remedies (fake)',  '3004', 12, 'NONE', false, 'Strip of 10',      'tablet',   10),
      ('G-000113', 'Sample Ondansetron 4 mg injection',             'Ondansetron',                  '4 mg',         'injection',   'Demo Pharma (fake)',      '3004', 12, 'H',    false, 'Box of 5',         'ampoule',   5),
      ('G-000114', 'Sample Diclofenac 50 mg tablet',                'Diclofenac',                   '50 mg',        'tablet',      'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Strip of 10',      'tablet',   10),
      ('G-000115', 'Sample Ibuprofen 400 mg tablet',                'Ibuprofen',                    '400 mg',       'tablet',      'Sample Remedies (fake)',  '3004', 12, 'NONE', false, 'Strip of 15',      'tablet',   15),
      ('G-000116', 'Sample Tramadol 50 mg capsule',                 'Tramadol',                     '50 mg',        'capsule',     'Demo Pharma (fake)',      '3004', 12, 'H1',   false, 'Strip of 10',      'capsule',  10),
      ('G-000117', 'Sample Morphine 10 mg injection',               'Morphine',                     '10 mg',        'injection',   'Sample Labs (fake)',      '3004', 12, 'X',    true,  'Box of 10',        'ampoule',  10),
      ('G-000118', 'Sample Insulin glargine 100 IU/ml pen',         'Insulin glargine',             '100 IU/ml',    'injection',   'Sample Remedies (fake)',  '3004',  5, 'H',    false, 'Box of 5',         'pen',       5),
      ('G-000119', 'Sample Vitamin D3 60000 IU capsule',            'Cholecalciferol',              '60000 IU',     'capsule',     'Demo Pharma (fake)',      '3004', 12, 'NONE', false, 'Strip of 4',       'capsule',   4),
      ('G-000120', 'Sample ORS powder 21 g sachet',                 'Oral rehydration salts',       '21 g',         'powder',      'Sample Labs (fake)',      '3004',  5, 'NONE', false, 'Box of 25',        'sachet',   25),
      ('G-000121', 'Sample Salbutamol 100 mcg inhaler',             'Salbutamol',                   '100 mcg',      'inhaler',     'Sample Remedies (fake)',  '3004', 12, 'H',    false, 'Inhaler',          'inhaler',   1),
      ('G-000122', 'Sample Normal saline 0.9% 500 ml',              'Sodium chloride',              '0.9%',         'infusion',    'Demo Pharma (fake)',      '3004',  5, 'NONE', false, 'Carton of 24',     'bottle',   24),
      ('G-000123', 'Sample Dextrose 5% 500 ml',                     'Dextrose',                     '5%',           'infusion',    'Demo Pharma (fake)',      '3004',  5, 'NONE', false, 'Carton of 24',     'bottle',   24),
      ('G-000124', 'Sample Povidone iodine 5% solution 100 ml',     'Povidone iodine',              '5%',           'solution',    'Sample Labs (fake)',      '3004', 12, 'NONE', false, 'Box of 12',        'bottle',   12),
      ('G-000125', 'Sample Heparin 5000 IU/ml injection',           'Heparin',                      '5000 IU/ml',   'injection',   'Sample Remedies (fake)',  '3004', 12, 'H',    false, 'Box of 10',        'vial',     10),
      ('G-000126', 'Sample Omeprazole 20 mg capsule',               'Omeprazole',                   '20 mg',        'capsule',     'Sample Labs (fake)',      '3004', 12, 'H',    false, 'Strip of 15',      'capsule',  15),
      ('G-000127', 'Sample surgical gloves, size 7.5',              'Surgical gloves',              'Size 7.5',     'pair',        'Demo Pharma (fake)',      '4015', 12, 'NONE', false, 'Box of 50 pairs',  'pair',     50),
      ('G-000128', 'Sample disposable syringe 5 ml',                'Syringe',                      '5 ml',         'device',      'Sample Remedies (fake)',  '9018', 12, 'NONE', false, 'Box of 100',       'piece',   100),
      ('G-000129', 'Sample IV cannula 20G',                         'IV cannula',                   '20G',          'device',      'Sample Labs (fake)',      '9018', 12, 'NONE', false, 'Box of 50',        'piece',    50),
      ('G-000130', 'Sample absorbent cotton roll 500 g',            'Absorbent cotton',             '500 g',        'roll',        'Demo Pharma (fake)',      '3005', 12, 'NONE', false, 'Carton of 10',     'roll',     10)
    ) as v (code, name, salt, strength, form, manufacturer, hsn, gst_rate, schedule, ndps, purchase_unit, issue_unit, conversion)
    on conflict (code) do nothing;

    -- A few fake barcodes (letters, digits, hyphen) so the scan lookup and the product detail have something to show.
    insert into pharmacy.catalogue_barcode (catalogue_product_id, barcode)
    select p.id, b.barcode
    from (values
      ('G-000101', 'SAMPLE-8900000101'),
      ('G-000103', 'SAMPLE-8900000103'),
      ('G-000103', 'SAMPLE-8900000203'),
      ('G-000105', 'SAMPLE-8900000105'),
      ('G-000107', 'SAMPLE-8900000107'),
      ('G-000127', 'SAMPLE-8900000127')
    ) as b (code, barcode)
    join pharmacy.catalogue_product p on p.code = b.code
    on conflict (barcode) do nothing;

  end if;
end
$seed$;
