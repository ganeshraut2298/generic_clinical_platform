# SAH-17 · Privacy and consent — database design

Migration: `backend/src/main/resources/db/migration/V2026_11_05_1000__privacy_and_consent.sql`
(forward-only; no existing migration edited). Schemas: `platform` and `patient` (existing).

## Table `platform.privacy_settings`
| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| tenant_id | uuid | no | `platform.current_tenant()` | PK, FK `platform.tenant(id)` |
| consent_required | boolean | no | true | |
| promo_consent | boolean | no | true | |
| record_copy | boolean | no | true | |
| hfr_id | text | yes | null | `char_length(hfr_id) <= 32` |
| updated_at / updated_by | timestamptz / uuid | yes | null | |
| version | bigint | no | 0 | optimistic lock |

No seed row; created lazily on first save. RLS: ENABLE + FORCE, `p_privacy_settings_tenant` (`for all to sahaj_app`) on `tenant_id = platform.current_tenant()`, fail-closed. Grants: select, insert, update (no delete).

## Table `patient.patient_consent` (append-only)
| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| patient_id | uuid | no | — | FK `patient.patient(id)` |
| kind | text | no | — | check in `RECORDS`, `PROMOTIONAL` |
| given | boolean | no | — | false = withdrawn |
| recorded_by | uuid | no | — | acting user |
| recorded_at | timestamptz | no | `now()` | |

Index `ix_patient_consent_tenant_patient_kind_at (tenant_id, patient_id, kind, recorded_at desc)`: serves "current state per kind" (latest row). RLS: ENABLE + FORCE, separate select and insert policies. Grants: select, insert only, plus triggers `trg_patient_consent_no_update_delete` and `trg_patient_consent_no_truncate` calling `platform.deny_audit_mutation()`.

## Table `patient.record_access` (append-only)
| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| id | uuid | no | `gen_random_uuid()` | PK |
| tenant_id | uuid | no | `platform.current_tenant()` | FK `platform.tenant(id)` |
| patient_id | uuid | no | — | FK `patient.patient(id)` |
| user_id | uuid | no | — | who opened the file |
| at | timestamptz | no | `now()` | |

Index `ix_record_access_tenant_at (tenant_id, at desc)`: the owner's "recent access" list. Same RLS, grants and deny-mutation triggers as above. No name, mobile or ABHA number is ever stored here; ids only.

## Expected rows, retention
- `privacy_settings`: one per tenant (about 10,100 in total).
- `patient_consent`: roughly 1 to 3 rows per patient. A clinic registering 10,000 patients a year gives about 20,000 rows per year.
- `record_access`: one row per patient file open. A busy clinic at about 150 opens a day is roughly 55,000 rows per tenant per year; across 10,000 clinics this can pass 50 million within a few years, so plan monthly range partitioning by `at` before then. The index is already tenant-prefixed and time-ordered.
- Retention: all three tables are never deleted by the app (no delete grant; the two append-only tables are also trigger-protected). Any future archival of `record_access` is a separate, reviewed migration.

## Backward compatibility
New tables only. A previous app version ignores them. Depends on `platform.deny_audit_mutation()` from earlier migrations.

## Note for java-developer
1. **privacy_settings is lazy.** `GET` with no row returns defaults (all true, `hfrId` null) without inserting. `PUT` uses `insert ... on conflict (tenant_id) do update` (keep `version` handling consistent with `opd.booking_rules`); `tenant_id` comes from the column default, never the request.
2. **Current consent per kind** = the row with the latest `recorded_at` for `(patient_id, kind)` (`order by recorded_at desc limit 1`, or `distinct on (kind)`), served by the index. A kind with no row means "not recorded". Record and withdraw are both plain inserts; never update.
3. **record_access** is written by the patient detail read (`GET /patient/patients/{id}`) inside that read's own transaction, so that endpoint must not be a read-only transaction (the insert would fail). Insert ids only: `patient_id`, `user_id`; `tenant_id` and `at` default.
4. **Names at read time.** `GET /patient/record-access?limit=` returns `(patient_id, user_id, at)`; resolve user names through `TeamMemberPort` and patient names through the patient repository. Never store or log names, mobile numbers or ABHA numbers.
5. **limit and index.** Query `where tenant_id = ? order by at desc limit ?`; cap `limit` in the service (for example max 50, default 5) so the index scan stays small.
6. The patient module must not read `platform.privacy_settings` directly; the frontend calls both endpoints.

## Hardening (V2026_11_07_1000, security review L3 and L6)

- `sahaj_app` insert on `patient.patient_consent` is now column-level on `(patient_id, kind, given, recorded_by)`, and on `patient.record_access` on `(patient_id, user_id)`. `id`, `tenant_id` (`platform.current_tenant()`) and `recorded_at` / `at` can only come from defaults. Select is unchanged.
- `platform.deny_audit_mutation()` now reports `<schema>.<table> is append-only (<op> refused)`; SQLSTATE 42501 is unchanged.
