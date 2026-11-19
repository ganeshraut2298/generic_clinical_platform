-- SAH-79 WP-D, migration D7 (last of WP-D). ADR-029 P14 "Linked ward", "Data needed" D7, SAH-78-3 touch-up (requirement request 5).
-- Additive: one nullable column on pharmacy.store and one check. The column holds the id of an IPD ward (another module's id: no foreign
-- key, as for every cross-module id). It is set only on a WARD store, and only by the owner operation PUT /stores/{storeId}/linked-ward
-- (WP-G, answers 409 integration-off until the IPD reader port exists). Nothing reads it in WP-D. Existing rows stay null, so the check
-- holds for every existing row. The store update guard (V2026_12_17_1010) is not changed. Grants are table-level (select, insert, update).

set local lock_timeout = '30s';

alter table pharmacy.store
  add column if not exists ipd_ward_id uuid;

do $$
begin
  if not exists (select 1 from pg_catalog.pg_constraint
                  where conname = 'ck_store_ipd_ward'
                    and conrelid = 'pharmacy.store'::regclass) then
    alter table pharmacy.store
      add constraint ck_store_ipd_ward check (ipd_ward_id is null or type = 'WARD');
  end if;
end
$$;

comment on column pharmacy.store.ipd_ward_id is
  'The IPD ward this WARD store serves (SAH-79 P14), for patient-wise consumption in Integrated mode. An id of another module: no foreign key; the service checks it through the IPD reader port. Null unless type is WARD. Never a patient or a name.';
