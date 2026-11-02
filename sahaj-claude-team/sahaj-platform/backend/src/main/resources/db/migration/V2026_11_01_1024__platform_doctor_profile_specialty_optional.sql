-- SAH-6 · platform.doctor_profile.specialty becomes optional.
-- The API's DoctorDetails.specialty is optional when the owner adds a doctor on Team and access
-- (only the registration number is asked for there, AC14), but SAH-4 made the column not null.
-- Relaxing a constraint is backward compatible: the previous app version still writes it.
-- The length check stays and applies when a value is present.
alter table platform.doctor_profile
  alter column specialty drop not null;
