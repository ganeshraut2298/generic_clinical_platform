# SAH-21 database design: leave requests

Migration: `V2026_11_10_1000__hra_leave_request.sql` (forward-only, new table only).

## hra.leave_request
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| membership_id | uuid | no | logical reference only (no cross-schema FK) |
| leave_type | text | no | in CL, SL, EL |
| from_date, to_date | date | no | to_date >= from_date; to_date - from_date <= 60 |
| reason | text | yes | length <= 200; free text, never logged or audited |
| status | text | no | 'pending'; in pending, approved, declined |
| created_at / created_by | timestamptz / uuid | no | now() / app |
| decided_at / decided_by | timestamptz / uuid | yes | both null when pending, both set otherwise |
| version | bigint | no | 0 |

## Indexes
- `(tenant_id, membership_id, from_date)`: overlap check, own list, balance, per-day leave lookups.
- `(tenant_id, status)`: pending count tile, approved-leave lookups.

## RLS and grants
Enabled and forced; policy `p_leave_request_tenant` (all, to sahaj_app) on `platform.current_tenant()`. Grants: select, insert, update. No delete.

## Volume and retention
About 20 to 200 rows per tenant per year. Never deleted; no partitioning.

## Compatibility
New table only; the previous app version is unaffected.
