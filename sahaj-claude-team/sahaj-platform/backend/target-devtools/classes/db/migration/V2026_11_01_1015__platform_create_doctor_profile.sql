-- SAH-4 · platform.doctor_profile : the clinical details for a membership whose role is
-- DOCTOR or VISITING_DOCTOR, one row per such membership. Written at setup/complete for the
-- doctors entered in wizard step 3, and later by SAH-6 (team) for doctors added afterwards.
-- mobile is additive beyond the story's table list, per SAH-4's open question: the mockup's
-- step 3 doctor card has no mobile field, but doctors other than the owner need a login mobile
-- like any other staff member. The owner's own mobile is already platform.app_user.mobile via
-- their membership; this column is for doctor 2 (and later doctors) only and is nullable so the
-- owner-doctor row does not need to repeat it.
create table if not exists platform.doctor_profile (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null default platform.current_tenant()
                  constraint fk_doctor_profile_tenant references platform.tenant (id),
  membership_id   uuid not null
                  constraint fk_doctor_profile_membership references platform.membership (id)
                  constraint uq_doctor_profile_membership unique,
  specialty       text not null
                  constraint ck_doctor_profile_specialty check (char_length(btrim(specialty)) between 1 and 120),
  fee_paise       bigint not null default 0
                  constraint ck_doctor_profile_fee_paise check (fee_paise >= 0),
  degree          text
                  constraint ck_doctor_profile_degree check (degree is null or char_length(degree) <= 120),
  registration_no text
                  constraint ck_doctor_profile_registration_no check (registration_no is null or char_length(registration_no) <= 80),
  mobile          char(10)
                  constraint ck_doctor_profile_mobile check (mobile is null or mobile ~ '^[6-9][0-9]{9}$'),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  version         bigint not null default 0
);

-- uq_doctor_profile_membership also serves "find the doctor row for this membership" and starts
-- with an implicit tenant scope through the fk, but a direct tenant-first index serves the team
-- list ("every doctor in this clinic") without a join.
create index if not exists ix_doctor_profile_tenant on platform.doctor_profile (tenant_id);

alter table platform.doctor_profile enable row level security;
alter table platform.doctor_profile force row level security;

create policy p_doctor_profile_tenant on platform.doctor_profile
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- Never deleted: a doctor leaving the clinic is handled through membership status (REMOVED),
-- which keeps this row as history (past prescriptions/appointments still name the doctor).
revoke all on platform.doctor_profile from sahaj_app;
revoke all on platform.doctor_profile from public;
grant select, insert, update on platform.doctor_profile to sahaj_app;

comment on table platform.doctor_profile is
  'Clinical details (specialty, fee, degree, registration no, login mobile) for a DOCTOR/VISITING_DOCTOR membership. One row per membership; fee_paise is integer paise.';
comment on column platform.doctor_profile.mobile is
  'Additive beyond the SAH-4 story design: the mockup has no mobile field for doctors, but non-owner doctors need one to log in. Null for the owner-doctor, who already has one via app_user.';
