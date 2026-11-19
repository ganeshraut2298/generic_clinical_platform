-- SAH-78 (Pharmacy & Inventory Phase 1 masters), migration A1 of 8. ADR-028 revision 2 "Additive-only changes", rows 1 and 2.
-- Design note: docs/database/SAH-78-pharmacy-masters.md. Forward-only; no earlier migration is edited.
--
-- 1. platform.module_entitlement.pharmacy_on: new column, not null, default false. Existing rows read false (constant default, no
--    table rewrite). The existing policy p_module_entitlement_tenant already covers the column and the app grants (select, insert,
--    update) are table-level, so nothing else changes. With pharmacy_on false every existing screen, menu and API behaves as before.
-- 2. platform.membership: ck_membership_role is replaced by the same list plus 'STORE_STAFF' (DEC-062 Q3, DEC-064 Q3), the same
--    approach as V2026_11_01_1019 (COUNSELLOR). The new check admits every old value, so no existing row can fail it and no row is
--    changed. role_permission and role_permission_default are NOT touched (STORE_STAFF has no row there, ADR-028 D4).
--
-- NOT in this migration: platform.admin_set_modules. DEC-064 Q2 allowed the admin switch for pharmacy_on, but that function and its
-- companions (admin_lock_modules, admin_clinic_json, the admin session guard and platform_admin_audit) have never been written:
-- they wait for the role sahaj_admin_definer (ADM-2 and ADM-3, parked). There is nothing to extend additively, and writing the whole
-- admin function set here would be new work outside SAH-78. Until it exists, test fixtures and the dev setup write pharmacy_on
-- directly (the app already upserts module_entitlement). See the design note, section 7.

set local lock_timeout = '30s';

alter table platform.module_entitlement
  add column if not exists pharmacy_on boolean not null default false;

comment on column platform.module_entitlement.pharmacy_on is
  'Pharmacy and Inventory module switch (SAH-78, ADR-028 D11). Default false. Read through ModuleEntitlementPort.pharmacyOn. Switched on per clinic by the Sahaj admin (admin function pending) or by test fixtures.';

alter table platform.membership
  drop constraint ck_membership_role;

alter table platform.membership
  add constraint ck_membership_role check (role in
    ('OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR', 'STORE_STAFF'));
