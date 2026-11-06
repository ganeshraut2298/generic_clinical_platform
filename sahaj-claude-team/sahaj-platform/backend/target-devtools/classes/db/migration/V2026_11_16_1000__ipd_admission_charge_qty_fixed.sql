-- SAH-25 security review L3 · one admission_charge row is always one unit. Forward-only.
-- The app no longer supplies qty: the column default 1 always applies, so the 50-per-service cap
-- (enforced in Java under an advisory lock) cannot be bypassed by a single row of qty 50.
-- Existing rows all have qty 1 (the service wrote one row per add), so the new check validates.

revoke insert (qty) on ipd.admission_charge from sahaj_app;

-- Added as NOT VALID then validated, so the constraint is explicit and the scan is separate from the DDL.
-- The older ck_admission_charge_qty (1 to 50) is kept: it is redundant but harmless and removing it gains nothing.
alter table ipd.admission_charge
  add constraint ck_admission_charge_qty_one check (qty = 1) not valid;
alter table ipd.admission_charge
  validate constraint ck_admission_charge_qty_one;

comment on column ipd.admission_charge.qty is 'Always 1: one row per added charge (SAH-25 L3). sahaj_app cannot insert this column; the default applies.';
