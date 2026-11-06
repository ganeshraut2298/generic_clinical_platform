-- SAH-7 · opd.medicine : global medicine list for prescriptions. Read-only for the application.
-- No clinic owns these rows, so no row level security; sahaj_app gets SELECT only and rows are
-- changed by migrations (Sahaj admin screens come in phase 9). Seeded by the next migrations.
--   name       : generic name with strength, for example "Paracetamol 650 mg"
--   form       : Tablet, Capsule, Syrup, Suspension, Injection, Cream, Ointment, Lotion, Inhaler, Sachet, Eye drops, Ear drops
--   schedule   : usual timing, morning-afternoon-night such as 1-0-1, or SOS, Once, Once a week
--   usual_dose : usual dose per time, for example 1 tablet or 5 ml
-- The id is derived from the name, so every environment gets the same id for the same medicine.
create table if not exists opd.medicine (
  id          uuid primary key default gen_random_uuid(),
  name        text not null
              constraint ck_medicine_name check (char_length(btrim(name)) between 1 and 160),
  form        text not null
              constraint ck_medicine_form check (char_length(btrim(form)) between 1 and 40),
  schedule    text
              constraint ck_medicine_schedule check (schedule is null or char_length(schedule) <= 40),
  usual_dose  text
              constraint ck_medicine_usual_dose check (usual_dose is null or char_length(usual_dose) <= 80),
  created_at  timestamptz not null default now()
);

-- One row per name, ignoring case. Also the prefix search: where lower(name) like lower(:q) || '%'
-- (text_pattern_ops makes LIKE 'abc%' use the index under any database collation).
create unique index if not exists uq_medicine_name_lower on opd.medicine (lower(name) text_pattern_ops);

revoke all on opd.medicine from sahaj_app;
revoke all on opd.medicine from public;
grant select on opd.medicine to sahaj_app;

comment on table opd.medicine is
  'Global read-only starter medicine list (SAH-7). Changed by migration only. Starter list: a clinician must review it before real use.';
