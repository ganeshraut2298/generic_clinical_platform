# SAH-18 · Alerts and escalation rules — database design

Migration: `V2026_11_06_1000__opd_alert_rules.sql` (forward-only, new tables only).

## opd.alert_rule
| Column | Type | Null | Notes |
|---|---|---|---|
| id | uuid | no | pk, gen_random_uuid() |
| tenant_id | uuid | no | default platform.current_tenant(), fk platform.tenant |
| when_step | text | no | check length 1..120 |
| cond | text | no | check length 1..120 |
| then_action | text | no | check length 1..120 |
| enabled | boolean | no | default true |
| created_at / created_by | timestamptz / uuid | no | |
| updated_at / updated_by | timestamptz / uuid | yes | set on switch on/off |
| version | bigint | no | default 0 |

- `uq_alert_rule_tenant_rule` unique (tenant_id, when_step, cond, then_action): duplicate check (409) and tenant list.
- `ix_alert_rule_tenant_created` (tenant_id, created_at): list in creation order.
- The API validates values against the builder and suggestion lists; the DB only bounds length.

## opd.alert_rule_state
`tenant_id` pk (fk platform.tenant), `seeded_at timestamptz default now()`. Written once on the first write, when the tenant has no rules and no marker, in the same transaction as the five starter rules.

## RLS and grants
Both tables: ENABLE + FORCE RLS, policy `for all to sahaj_app` on `tenant_id = platform.current_tenant()` (using and with check). `sahaj_app` gets select/insert/update/delete on alert_rule (configuration; delete is required by the story and audited by the app) and select/insert on alert_rule_state.

## Volume and retention
About 5 to 30 rows per tenant, rarely changing; 1 state row per tenant. No partitioning. Deleted rules are removed; the audit log keeps before/after. Not clinical or billing data.

## Compatibility
Additive. The previous app version ignores the new tables.
