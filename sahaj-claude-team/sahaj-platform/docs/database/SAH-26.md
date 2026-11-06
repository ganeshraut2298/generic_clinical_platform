# SAH-26 · Database design: IPD bed transfer and transfer rules

Migration: `V2026_11_17_1000__ipd_bed_transfer.sql` (forward-only; one new table, two new columns on `ipd.config`, one new column grant on `ipd.admission`). No existing migration is edited.

## ipd.config (two new columns)
| Column | Type | Null | Default / check |
|---|---|---|---|
| transfer_upgrade_owner_only | boolean | no | true (true = "Owner approves") |
| new_rate_from | text | no | 'same'; `ck_ipd_config_new_rate_from` in ('same','next') |

The config row is lazy (created by the first write). Rows that exist get the defaults. `sahaj_app` already holds table-level `select, insert, update` on `ipd.config` (SAH-22), so the `PUT /transfer-rules` upsert needs no new grant. The Java upsert must not break when the row is missing: insert with the defaults for the other columns, then set these two.

## ipd.bed_transfer (append-only)
| Column | Type | Null | Default / check |
|---|---|---|---|
| id | uuid PK | no | gen_random_uuid() |
| tenant_id | uuid | no | platform.current_tenant(); FK platform.tenant |
| admission_id | uuid | no | composite FK (tenant_id, admission_id) to ipd.admission (tenant_id, id) |
| from_bed_id | uuid | no | composite FK (tenant_id, from_bed_id) to ipd.bed (tenant_id, id) |
| to_bed_id | uuid | no | composite FK (tenant_id, to_bed_id) to ipd.bed (tenant_id, id); `from_bed_id <> to_bed_id` |
| from_category_key | text | no | in gen, semi, pvt, icu |
| to_category_key | text | no | in gen, semi, pvt, icu |
| segment_days | integer | no | >= 0 (bill days of the old category, fixed at transfer time) |
| new_rate_from | text | no | in same, next (rule value used) |
| reason | text | yes | null or one of: Needs closer monitoring, Moving to a step-down bed, Patient or family request, Isolation |
| transferred_at | timestamptz | no | now() (not insertable) |
| transferred_by | uuid | no | actor id, same pattern as deposit.collected_by and admission_charge.added_by |

Index `ix_bed_transfer_tenant_admission (tenant_id, admission_id, transferred_at)` serves the transfers of one admission in time order (running bill segments, and the sum of earlier segment days) and the batched `admission_id = any(?)` load for the list, board and tiles.

The old and new category keys are copied onto the row (bed categories can change later), so the bill segments do not depend on today's bed categories.

## Tenant scope and RLS
ENABLE and FORCE RLS. Separate `select` and `insert` policies for `sahaj_app` on `tenant_id = platform.current_tenant()`. No update/delete policy. Composite FKs stop a row pointing at another tenant's admission or bed.

## Append-only
`sahaj_app` has `select` and column-level `insert (admission_id, from_bed_id, to_bed_id, from_category_key, to_category_key, segment_days, new_rate_from, reason, transferred_by)`. `id`, `tenant_id` and `transferred_at` take defaults. Triggers deny update, delete and truncate through `platform.deny_audit_mutation()` (same as `ipd.deposit`).

## Grants on existing tables
- `ipd.admission`: `grant update (bed_id)` added. Existing update grant is `status, discharged_at, version`. Insert grant is unchanged.
- `ipd.bed`: no change. `sahaj_app` has table-level `select, insert, update, delete` (SAH-22, no later migration narrows it), so setting the old bed to `clean` and the new bed to `occ` in the same transaction already works. Note for a later story: the bed `delete` grant is still open; `fk_admission_bed` and the new bed FKs (NO ACTION) block deleting any bed that has history.

## Concurrency (for the java-developer)
The checks "bed is free" and "admission is Admitted" are not enforced by the database. Follow the story design: IPD tenant advisory lock, then `select ... for update` on the two beds in id order, then on the admission. The second of two concurrent moves to the same bed then sees status `occ` and returns `bed-not-free`.

## Volume and retention
One row per move, rarely more than a few per admission. A 100-bed hospital at 80 percent occupancy and a 5-day stay is about 5,500 admissions a year; at most a few thousand transfer rows a year per tenant. No partitioning. Never deleted (billing data).

## Compatibility
Additive. The previous app version ignores the new table and columns and the new column grant. The new columns have defaults, so older `ipd.config` writes still work. Rollback path: a new forward migration revoking the grant and dropping the table and columns (only before any transfer is recorded).

## Security review fixes M1 and L1 (migration `V2026_11_19_1000__ipd_transfer_guards.sql`)

Forward-only, no table or column changes. Source: `docs/reviews/SAH-26-security.md`.

### M1 · Bed change guard on ipd.admission
- **Index** `ux_admission_tenant_bed_admitted` unique on `(tenant_id, bed_id) where status = 'ADMITTED'`. At most one current admission per bed. Discharged rows are excluded, so a bed's history is unaffected. Existing `ix_admission_tenant_bed` stays for the bed-history check. Serves "which admission is in this bed" for current admissions.
- **Trigger** `trg_admission_guard_bed_change`: `before update of bed_id on ipd.admission for each row when (new.bed_id is distinct from old.bed_id)`, function `ipd.guard_admission_bed_change()`. It refuses (SQLSTATE 42501, message `bed change refused`, no data in the message) unless `old.status = 'ADMITTED'` and `new.status = 'ADMITTED'` and an `ipd.bed_transfer` row exists with the same tenant, admission, `from_bed_id = old.bed_id`, `to_bed_id = new.bed_id` and `transferred_at = now()`.
- **Why `transferred_at = now()` proves "same transaction":** `now()` is the transaction start time. `transferred_at` defaults to `now()` and `sahaj_app` has no insert privilege on that column, so only a row inserted by the current transaction can match.
- **Function properties:** SECURITY INVOKER (default), `set search_path = pg_catalog`, all objects schema-qualified, owned by `sahaj_migrator`, `EXECUTE` revoked from public and `sahaj_app` (trigger firing does not need it). The select on `ipd.bed_transfer` runs as `sahaj_app`, so RLS applies: the invoker sees its own tenant's row (the `p_bed_transfer_select` policy), which is what the check needs.
- **Flows checked:** admit is an INSERT (trigger not fired). Discharge (SAH-27) updates `status`, `discharged_at`, `version` only (trigger not fired). Transfer inserts the `bed_transfer` row first, then updates `bed_id` in the same transaction (guard passes). A no-op `set bed_id = bed_id` does not fire the trigger. A direct `update ... set bed_id` by `sahaj_app`, on a DISCHARGED admission, or without a transfer row, is refused. Owners and superusers can still bypass for repair (BYPASSRLS roles are not used by the app).
- **Pre-flight:** the migration first counts beds with more than one ADMITTED admission and stops with a counts-only error. The service already serialises admit and transfer (advisory lock, bed row lock), so none are expected.
- **Compatibility:** the previous app version never changes `bed_id` outside a transfer in SAH-26 code, so it keeps working. Rollback: a new forward migration dropping the trigger, function and index.
- **Java follow-up (java-developer):** `UPDATE_BED` gets `and status = 'ADMITTED'` and the code throws `IllegalStateException` unless the update count is 1 (also for the bed status updates in the transfer). Map SQLSTATE 42501 from this trigger to a generic server error, not to a user message.

### L1 · Ward names ignore joiners
- **Index** `ux_ward_tenant_name_nojoiners` unique on `(tenant_id, lower(translate(name, U&'\200C\200D', '')))`. `IC<ZWJ>U` and `ICU` are now duplicates in the same clinic. `ux_ward_tenant_lower_name` (SAH-22) is kept.
- **Pre-flight:** counts-only error if two existing wards in one clinic differ only by U+200C or U+200D.
- **Java follow-up:** `NAME_EXISTS` in `JdbcWardRepository` should use the same expression (`lower(translate(name, U&'\200C\200D', ''))` on both sides) so the friendly 409 appears before the index error; NFC-normalise and apply the CONTEXTJ rule in `IpdText`. The index is the last line of defence.

### Volume and retention
No new rows. Two partial or expression indexes on tables of a few thousand rows per tenant per year. Nothing deleted.
