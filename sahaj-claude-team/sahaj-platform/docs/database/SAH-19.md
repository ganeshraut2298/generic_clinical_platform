# SAH-19 database design: Plan and modules

Migration: `V2026_11_08_1000__platform_module_entitlement.sql` (forward-only, new table only).

## platform.module_entitlement

| Column | Type | Null | Default | Check |
| --- | --- | --- | --- | --- |
| tenant_id | uuid | no | platform.current_tenant() | PK, FK platform.tenant(id) |
| ipd_state | text | no | 'off' | in ('off','trial','on','readonly') |
| ipd_trial_ends_at | timestamptz | yes | | |
| hra_on | boolean | no | false | |
| bed_limit | integer | no | 25 | between 5 and 300 |
| ipd_requested_at | timestamptz | yes | | |
| updated_at / updated_by | timestamptz / uuid | yes | | |
| version | bigint | no | 0 | optimistic lock |

- Indexes: the primary key on tenant_id serves every query (one row per tenant); no others.
- RLS: enabled and forced; policy `p_module_entitlement_tenant` for `sahaj_app` using and with check `tenant_id = platform.current_tenant()`.
- Grants: `sahaj_app` select, insert, update. No delete.
- Rows: at most 1 per tenant (about 10,100 in total). Lazy: no row until the first write; the app returns defaults when absent (same pattern as privacy_settings).
- Retention: never deleted.
- Backward compatibility: new table only; the previous app version is unaffected.
