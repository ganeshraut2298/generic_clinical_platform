-- SAH-7 · Fees and billing settings live on the existing opd.opd_settings row (one row per clinic).
-- fee_timing, follow_up_days and follow_up_fee already exist (SAH-4, 1017) and are reused as they are.
-- Added here:
--   discount_limits : percent a role may give without owner approval. Keys are API Role names.
--                     Defaults are the mockup's: front desk 10, nurse 0, owner 100. A role with no key
--                     has no discount right (treat as 0). Values must be integers 0 to 100: the app
--                     checks that; the database checks the shape and the allowed role names.
--   upi_on_bill     : print a UPI QR on the bill (mockup default on).
--   updated_by      : user who last edited the row (who and when also go to audit_event).
alter table opd.opd_settings
  add column if not exists discount_limits jsonb not null
    default '{"FRONT_DESK": 10, "NURSE": 0, "OWNER": 100}'::jsonb,
  add column if not exists upi_on_bill boolean not null default true,
  add column if not exists updated_by uuid;

alter table opd.opd_settings
  add constraint ck_opd_settings_discount_limits check (
    jsonb_typeof(discount_limits) = 'object'
    and (discount_limits - array['OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE',
                                 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR']) = '{}'::jsonb);

comment on column opd.opd_settings.discount_limits is
  'Discount percent allowed without owner approval, by role name, e.g. {"FRONT_DESK":10,"NURSE":0,"OWNER":100}.';
