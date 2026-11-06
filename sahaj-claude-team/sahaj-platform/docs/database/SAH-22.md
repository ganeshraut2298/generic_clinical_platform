# SAH-22 database design: IPD wards, rooms, beds, charges
Migration: `V2026_11_11_1000__ipd_wards_rooms_beds.sql` (schema `ipd`). All tables carry `tenant_id` (default `platform.current_tenant()`, FK `platform.tenant`), ENABLE + FORCE RLS, policy `p_<table>_tenant` for `sahaj_app` on `tenant_id = platform.current_tenant()`.

| Table | Key | Notes |
|---|---|---|
| ward | id; unique (tenant_id,id); unique (tenant_id, lower(name)) | name 1-80, station 1-60 default 'Nursing station 1' |
| room | id; unique (tenant_id,id); FK (tenant_id,ward_id) | name 1-40; index (tenant_id,ward_id) |
| bed | id; unique (tenant_id,code); FK (tenant_id,ward_id), (tenant_id,room_id) null ok | code 1-20; category_key gen/semi/pvt/icu default gen; status free/occ/clean/blk/res default free; version; indexes ward, room (partial), category |
| bed_category | PK (tenant_id,key) | rate_paise >= 0; multiplier numeric(4,2) 0.5..5; lazy (app synthesizes defaults) |
| config | PK tenant_id | room_level true; bed_day_cut midnight/24h; nursing_paise 30000; visit_paise 50000; lazy |

Tenant isolation: same-schema FKs are composite (tenant_id, id), so a child cannot reference another tenant's ward/room even if the app errs. The 100000 rupee upper bound on rate and the 0.5..5 multiplier are checked in the app and (multiplier) in the DB.
Grants (sahaj_app): select/insert/update on all; delete on `bed` only.
Expected rows per tenant: wards 1-30, rooms up to ~200, beds 25-500 (plan limit), bed_category 4, config 1. Tiny; no partitioning.
Retention: wards, rooms, config, categories never deleted. Beds deletable only while no patient history; SAH-23+ must replace delete with a guard.
Backward compatibility: purely additive new tables; previous app version unaffected.
