-- SAH-7 · platform.clinic_profile : add the Clinic profile fields the wizard did not collect.
-- Additive and nullable, so the previous app version keeps working. 1014 is unchanged.
--   whatsapp   : clinic WhatsApp number (mockup Clinic profile, "WhatsApp number")
--   maps_url   : Google Maps link
--   brand_hex  : custom brand colour, used when brand = 'custom' (API ClinicProfile.brandHex)
--   updated_by : user who last edited the profile (who and when also go to audit_event)
-- The API field "area" is the existing city column (SAH-5 already treats city as the header area).
alter table platform.clinic_profile
  add column if not exists whatsapp   text,
  add column if not exists maps_url   text,
  add column if not exists brand_hex  char(7),
  add column if not exists updated_by uuid;

alter table platform.clinic_profile
  add constraint ck_clinic_profile_whatsapp
    check (whatsapp is null or char_length(whatsapp) <= 20),
  add constraint ck_clinic_profile_maps_url
    check (maps_url is null or (char_length(maps_url) <= 500 and maps_url ~* '^https?://')),
  add constraint ck_clinic_profile_brand_hex
    check (brand_hex is null or brand_hex ~ '^#[0-9a-fA-F]{6}$');

comment on column platform.clinic_profile.brand_hex is
  'Custom brand colour, #rrggbb. Meaningful when brand = custom; the app must also keep platform.tenant.brand equal to brand.';
