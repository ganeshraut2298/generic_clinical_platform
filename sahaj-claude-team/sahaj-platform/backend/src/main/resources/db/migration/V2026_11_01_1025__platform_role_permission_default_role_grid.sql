-- SAH-6 · Default "Who can do what" grid for the roles the mockup matrix does not show.
-- Global reference data only (platform.role_permission_default); no tenant row is touched, and
-- V2026_11_01_1020 is not edited. Clinics keep their own cell changes in platform.role_permission,
-- which still win over these defaults through platform.effective_role_permissions().
--
-- Only the seven existing permission keys exist (token, vitals, collect, cancel, refund,
-- revenue, export). Consulting, prescribing, dispensing, viewing patients, notes and
-- discounts within the limits are not matrix keys: they follow the role (NavigationPolicy)
-- and the clinic discount limits, so they need no row here.
--
-- Conservative: cancel, refund and export stay owner-only for every other role, as in the mockup.
--   DOCTOR     : token (book a follow-up, mockup task "Schedule follow-up"), vitals. No money.
--   BILLING    : collect payments, revenue (basics). No cancel, refund or export.
--   PHARMACIST : nothing beyond their own steps (no matching key).
--   COUNSELLOR : nothing beyond their own steps (no matching key).
--   FRONT_DESK, NURSE, VISITING_DOCTOR, OWNER : unchanged.
update platform.role_permission_default d
set allowed = (d.role, d.permission) in (
      ('DOCTOR', 'token'), ('DOCTOR', 'vitals'),
      ('BILLING', 'collect'), ('BILLING', 'revenue'))
where d.role in ('DOCTOR', 'BILLING', 'PHARMACIST', 'COUNSELLOR')
  and d.allowed is distinct from ((d.role, d.permission) in (
      ('DOCTOR', 'token'), ('DOCTOR', 'vitals'),
      ('BILLING', 'collect'), ('BILLING', 'revenue')));
