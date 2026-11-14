-- SAH-48 (ADR-022 D4, D5) · Drug-class allergy check and prescription row defaults. Forward-only, additive.
--   opd.emr_allergy_class        NEW global reference table: the two drawn allergy classes (SULFA, PENICILLIN)
--   opd.medicine                 four new nullable columns: allergy_class, default_frequency, default_days, default_when
-- Global reference data: no clinic owns a row, so no clinic column and no row level security (same kind as opd.medicine).
-- sahaj_app gets SELECT only; rows change by migration only. Nothing is dropped, renamed or rewritten except the seed
-- rows of opd.medicine that receive a class or defaults (an update of global seed data, not of clinic data).
-- Values come from the EMR mockup (DoctorGeneral FAVS and LAST_RX), accepted by the human in DEC-039 and DEC-042,
-- NOT clinically reviewed.

-- ---------------------------------------------------------------------------------------------
-- 1. opd.emr_allergy_class (ADR-022 D5): exactly the two classes drawn. A new class is a clinical decision and a migration.
-- ---------------------------------------------------------------------------------------------
create table if not exists opd.emr_allergy_class (
  class_key   text not null
              constraint ck_emr_allergy_class_key check (class_key ~ '^[A-Z][A-Z0-9_]{1,29}$'),
  label       text not null
              constraint ck_emr_allergy_class_label check (char_length(btrim(label)) between 1 and 60),
  position    integer not null
              constraint ck_emr_allergy_class_position check (position >= 1),
  created_at  timestamptz not null default now(),
  constraint pk_emr_allergy_class primary key (class_key),
  constraint uq_emr_allergy_class_position unique (position)
);

insert into opd.emr_allergy_class (class_key, label, position) values
  ('SULFA',      'Sulfa drugs', 1),
  ('PENICILLIN', 'Penicillin',  2)
on conflict (class_key) do nothing;

revoke all on opd.emr_allergy_class from sahaj_app;
revoke all on opd.emr_allergy_class from public;
grant select on opd.emr_allergy_class to sahaj_app;

comment on table opd.emr_allergy_class is
  'Global read-only drug allergy classes for the EMR allergy check (SAH-48, ADR-022 D5): SULFA and PENICILLIN. Changed by migration only. Starter list, not clinically reviewed: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed. The check covers listed classes only; no alert is not a clearance.';

-- ---------------------------------------------------------------------------------------------
-- 2. opd.medicine: allergy class and the row defaults (ADR-022 D4, D5). All nullable, so the previous application version
--    keeps working. The three defaults are all set or all null (a half-set default has no meaning).
--    A medicine with no defaults gets, in the application: frequency = its schedule when that is one of the four values,
--    else 1-0-1; 5 days; After food (DEC-042 H6).
-- ---------------------------------------------------------------------------------------------
alter table opd.medicine
  add column if not exists allergy_class text
    constraint fk_medicine_allergy_class references opd.emr_allergy_class (class_key),
  add column if not exists default_frequency text
    constraint ck_medicine_default_frequency check (default_frequency is null or default_frequency in ('1-0-0', '1-0-1', '0-0-1', '1-1-1')),
  add column if not exists default_days smallint
    constraint ck_medicine_default_days check (default_days is null or default_days between 1 and 365),
  add column if not exists default_when text
    constraint ck_medicine_default_when check (default_when is null or default_when in
      ('AFTER_FOOD', 'BEFORE_FOOD', 'BEDTIME', 'MORNING_EMPTY_STOMACH'));

alter table opd.medicine
  add constraint ck_medicine_defaults_together check (
    (default_frequency is null) = (default_days is null) and (default_days is null) = (default_when is null));

comment on column opd.medicine.allergy_class is 'Drug allergy class for the EMR check (SAH-48): null = none known to the check. Value from the EMR mockup, not clinically reviewed.';
comment on column opd.medicine.default_frequency is 'Saved prescription frequency when the medicine is tapped (SAH-48): 1-0-0, 1-0-1, 0-0-1 or 1-1-1. Null with default_days and default_when = no saved defaults.';
comment on column opd.medicine.default_days is 'Saved prescription days (1 to 365) when the medicine is tapped (SAH-48).';
comment on column opd.medicine.default_when is 'Saved prescription "when": AFTER_FOOD, BEFORE_FOOD, BEDTIME or MORNING_EMPTY_STOMACH (SAH-48).';

-- ---------------------------------------------------------------------------------------------
-- 3. Seed (ADR-022 D5, DEC-042). The id is derived from the name like every other medicine row.
--    Co-trimoxazole 960 mg is new (Tablet, sulfa drug, 1-0-1, 5 days, After food, as drawn).
-- ---------------------------------------------------------------------------------------------
insert into opd.medicine (id, name, form, schedule, usual_dose, allergy_class, default_frequency, default_days, default_when)
values (md5('opd.medicine:' || lower('Co-trimoxazole 960 mg'))::uuid, 'Co-trimoxazole 960 mg', 'Tablet', '1-0-1', '1 tablet',
        'SULFA', '1-0-1', 5, 'AFTER_FOOD')
on conflict do nothing;

-- The three Amoxicillin rows are penicillins.
update opd.medicine set allergy_class = 'PENICILLIN'
where lower(name) in ('amoxicillin 500 mg', 'amoxicillin 125 mg/5 ml', 'amoxicillin 500 mg + clavulanic acid 125 mg');

-- Co-trimoxazole again (in case the row existed before this migration) and the defaults of the mockup FAVS and LAST_RX
-- where the name exists in the master. "Metformin 500 mg SR" (LAST_RX) is not in the master, so it gets no default.
update opd.medicine m
set allergy_class = coalesce(v.allergy_class, m.allergy_class),
    default_frequency = v.freq, default_days = v.days, default_when = v.whn
from (values
  ('Co-trimoxazole 960 mg', 'SULFA', '1-0-1', 5::smallint,  'AFTER_FOOD'),
  ('Pantoprazole 40 mg',    null,    '1-0-0', 14::smallint, 'MORNING_EMPTY_STOMACH'),
  ('Paracetamol 650 mg',    null,    '1-0-1', 3::smallint,  'AFTER_FOOD'),
  ('Amlodipine 5 mg',       null,    '1-0-0', 30::smallint, 'AFTER_FOOD'),
  ('Cetirizine 10 mg',      null,    '0-0-1', 5::smallint,  'BEDTIME'),
  ('Telmisartan 40 mg',     null,    '1-0-0', 30::smallint, 'AFTER_FOOD'),
  ('Atorvastatin 10 mg',    null,    '0-0-1', 30::smallint, 'BEDTIME')
) as v (name, allergy_class, freq, days, whn)
where lower(m.name) = lower(v.name);
