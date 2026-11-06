# SAH-32 · Database design: IPD Medicines (MAR) and giving a dose

Migration (forward-only, additive; no earlier migration is edited, nothing dropped or renamed): `V2026_11_29_1000__ipd_mar_dose_administration.sql` (latest before it: `V2026_11_28_1200`). Decision record: ADR-011 (D1, D2, D5, D6, D8, D13 and "Data needed" items 1 and 2). Mockup screens: Nursing sheet → Medicines (MAR) tab and Give sheet (`sfMar`, `sfSlot`, `sfGive`, `sfgiveok`).

Changes: one new unique constraint on `ipd.medicine_order`, one new table `ipd.dose_administration`, one guard function and its trigger. Nothing else. **`platform.staff_pin` is unchanged**: ADR-011 D7 reuses the same row, hash and counters for the second nurse's PIN (no second migration `V2026_11_29_1100`). `ipd.clinical_event` and `platform.audit_event` are unchanged (volumes restated below). No ambiguity or destructive step found in ADR-011 "Data needed"; two small notes in "Notes for the human" below.

## ipd.medicine_order (one addition)
`uq_medicine_order_tenant_id_admission unique (tenant_id, id, admission_id)`: target of the composite FK, so a dose cannot name an order of another admission. Checked first: it did not exist (only `uq_medicine_order_tenant_id (tenant_id, id)`). `id` is already unique, so the new constraint can never fail on existing data. Adding it builds one index under a brief table lock; the table holds at most tens of thousands of rows at this point (SAH-31 is not released without SAH-32, ADR-011 D15). Cost afterwards: one more index entry per new order (about 16 million orders a year at full use: accepted). Existing guard triggers are untouched: `ipd.guard_medicine_order_update()` forbids changing `id`, `tenant_id` and `admission_id`, so the pair stays valid.

## ipd.dose_administration (tenant table)

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | FK `fk_dose_administration_tenant` → `platform.tenant` |
| `admission_id` | uuid | no | | FK `fk_dose_administration_admission (tenant_id, admission_id)` → `ipd.admission (tenant_id, id)` |
| `order_id` | uuid | no | | FK `fk_dose_administration_order (tenant_id, order_id, admission_id)` → `ipd.medicine_order (tenant_id, id, admission_id)` |
| `client_key` | uuid | no | | `uq_dose_administration_tenant_client_key (tenant_id, client_key)` |
| `slot_at` | timestamptz | no | | `ck_dose_administration_slot_minute`: `slot_at = date_trunc('minute', slot_at)`; `uq_dose_administration_order_slot (tenant_id, order_id, slot_at)` |
| `outcome` | text | no | | `ck_dose_administration_outcome`: GIVEN, HELD, REFUSED |
| `reason_key` | text | yes | | `ck_dose_administration_reason`: PATIENT_REFUSED, HELD_DOCTOR, NIL_BY_MOUTH, NOT_AVAILABLE, AWAY_FROM_WARD (null allowed) |
| `recorded_by` | uuid | no | | signed-in user id (IPD actor convention), no FK |
| `second_check_by` | uuid | yes | | user id of the second nurse, no FK |
| `high_alert` | boolean | no | | snapshot of `ipd.drug.high_alert is not null` |
| `identity_scan` | text | yes | | `ck_dose_administration_identity_scan`: 1 to 40 characters when set |
| `identity_confirmed` | boolean | yes | | true for GIVEN, null otherwise |
| `recorded_at` | timestamptz | no | `clock_timestamp()` | database clock at the insert; not insertable by the app |

Row checks (all `ck_dose_administration_*`):
- `reason_by_outcome`: `(outcome = 'GIVEN') = (reason_key is null)`.
- `refused_reason`: `(outcome = 'REFUSED') = (reason_key is not distinct from 'PATIENT_REFUSED')`. So REFUSED is exactly PATIENT_REFUSED; HELD takes one of the other four.
- `second_check_outcome`: `second_check_by is null or outcome = 'GIVEN'`; `second_check_other`: `second_check_by is null or second_check_by <> recorded_by`.
- `high_alert_second_check`: `not (high_alert and outcome = 'GIVEN') or second_check_by is not null`.
- `identity_scan_by_outcome`: `(outcome = 'GIVEN') = (identity_scan is not null)`; `identity_confirmed_by_outcome`: `(outcome = 'GIVEN') = (identity_confirmed is true)`; `identity_confirmed_only_given`: `identity_confirmed is null or outcome = 'GIVEN'` (blocks `false`).
- No `created_*`, `updated_*` or `version`: append-only, `recorded_by/at` carry who and when.

### Indexes and the query each serves
| Index | Serves |
|---|---|
| primary key `(id)` | row identity; partitioning later makes it `(id, slot_at)` |
| `uq_dose_administration_tenant_client_key (tenant_id, client_key)` | replay lookup of a retried dose (ADR-011 D8) |
| `uq_dose_administration_order_slot (tenant_id, order_id, slot_at)` | "is this slot recorded" (the 409 `dose-recorded` rule and its backstop); "last dose of each order" for the EMR (`order by slot_at desc limit 1` per order, ADR-011 D11); child-side lookup for `fk_dose_administration_order` (leading `tenant_id, order_id`) |
| `ix_dose_administration_tenant_admission_slot (tenant_id, admission_id, slot_at)` | MAR of today for one admission (`slot_at >= :dayStart and slot_at < :dayEnd`), the tab count, and `fk_dose_administration_admission` from the child side |

Suggested read for the MAR (java-developer): `select ... from ipd.dose_administration where tenant_id = :t and admission_id = :a and slot_at >= :dayStartUtc and slot_at < :dayEndUtc`. Last dose per order: `select distinct on (order_id) ... where tenant_id = :t and admission_id = :a order by order_id, slot_at desc` (uses the admission index; at most a few hundred rows per admission).

### Tenant scope and RLS
`tenant_id` not null; ENABLE and FORCE RLS; policies `p_dose_administration_select` and `p_dose_administration_insert`, both `to sahaj_app`, `tenant_id = platform.current_tenant()` (insert has the same expression as `with check`). No update or delete policy.

### Grants (`sahaj_app`)
- `select`.
- `insert (admission_id, order_id, client_key, slot_at, outcome, reason_key, recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed)`. `id`, `tenant_id` and `recorded_at` keep their defaults; naming them in an insert gives 42501.
- No update, delete or truncate. `public` has nothing.

### Triggers (SECURITY INVOKER, `search_path = pg_catalog`)
- `trg_dose_administration_no_update_delete` (before update or delete, each row) and `trg_dose_administration_no_truncate` (before truncate) → `platform.deny_audit_mutation()`: 42501 for every role, the owner included; message "platform.audit_event is append-only (UPDATE refused)" (the shared function's wording; it names `tg_op`, not the table).
- `trg_dose_administration_guard_insert` → `ipd.guard_dose_administration_insert()` (revoked from public and `sahaj_app`), SQLSTATE 23514, every message contains "dose". Rule order:
  1. Order not found for `(tenant_id, order_id, admission_id)`, or not visible: the guard returns and the composite FK answers 23503.
  2. Order status is not ACTIVE: "dose can be recorded only for an active medicine order".
  3. Admission not ADMITTED (or not visible: fail closed): "dose can be recorded only while the patient is admitted".
  4. `high_alert` differs from `(ipd.drug.high_alert is not null)` of the order's drug: "dose high-alert flag does not match the medicine".
  The reads take no lock. The app holds the admission row `for no key update` (ADR-006 D4, ADR-011 D6); the insert's FK checks take key-share locks on the admission and the order row. A BEFORE trigger runs before the check constraints and the foreign keys, so a state error (23514 from the guard) comes before a pair error; a constraint violation on a wrong pair is a 23514 as well, with the constraint name.

## Expected rows, partitioning, retention
- About 100 hospitals × 150 occupied beds × 3 to 5 live orders × 1 to 3 slots ≈ 10 doses per patient-day: **about 150,000 rows a day, 4.5 million a month, 55 million a year**; per hospital about 550,000 a year. Row size about 200 bytes plus four index entries. The 50-million trigger of `postgresql.md` is reached in the first year of full use.
- **Starts unpartitioned** (story, ADR-011 D13) and is built so range partitioning on **`slot_at`, monthly** applies later: no foreign key points at the table; `uq_dose_administration_order_slot` already contains `slot_at` (stays a real unique index); the primary key becomes `(id, slot_at)`; `(tenant_id, client_key)` becomes local per partition. The procedure is in the table comment. **Runbook item at go-live:** start the conversion at about 30 million rows (create the `(id, slot_at)` unique index concurrently first, ADR-006 D2); create next month's partition before the 25th once partitioned.
- **Also growing because of SAH-32:** `ipd.clinical_event` +1 per dose and per mismatch (about +150,000 a day; with the EMR views of ADR-009 D12 it reaches 30 million within months: its ADR-006 D2 partition by `at` must be planned at go-live); `platform.audit_event` +1 per dose (about +150,000 a day, 55 million a year; it has **no partition plan yet**: the solution-architect and tech lead must decide one, ADR-011 Q9. It is append-only with an insert grant and no foreign key points at it, so range partitioning by `created_at` (or its time column) monthly is feasible); `patient.record_access` +1 per MAR opening (10 to 20 per patient-day, patient module's plan).
- **Retention:** part of the in-patient record, never deleted (no delete grant or policy, triggers refuse update, delete and truncate). Period is the human's (ADR-006 Q10).
- **Privacy:** `identity_scan` holds the normalised UHID only for a GIVEN dose (the check makes it equal to the patient's UHID in Java); never logged or audited. No patient name, allergy or note in the table. `reason_key` and the ids are not free text.

## Backward compatibility
Additive. The previous app version never touches the new table or the new constraint and runs unchanged against the new schema. Rollback before any dose exists: a new forward migration that drops the table, the guard function and `uq_medicine_order_tenant_id_admission` (nothing earlier references them).

## Insert statement for the java-developer
One statement, in the locked transaction, after the admission row lock and the checks:

```sql
insert into ipd.dose_administration
  (admission_id, order_id, client_key, slot_at, outcome, reason_key,
   recorded_by, second_check_by, high_alert, identity_scan, identity_confirmed)
values (:admissionId, :orderId, :clientKey, :slotAt, :outcome, :reasonKey,
        :recordedBy, :secondCheckBy, :highAlert, :identityScan, :identityConfirmed)
returning id, recorded_at
```

Bind rules (a wrong combination is a code bug, see below):
- GIVEN: `outcome = 'GIVEN'`, `reason_key` null, `identity_scan` = the normalised box text (1 to 40 characters), `identity_confirmed = true`; `second_check_by` = the second nurse's **user id** (not the membership id) when `high_alert`, else null.
- NOT_GIVEN: `outcome = 'REFUSED'` when the reason is PATIENT_REFUSED, else `'HELD'`; `reason_key` set; `second_check_by`, `identity_scan`, `identity_confirmed` all null (send null, not false).
- `high_alert` = `drug.high_alert != null` of the order's drug, read in the same transaction.
- `slot_at`: a UTC instant at a whole minute (the IST dose time converted; bind as `OffsetDateTime`/`Timestamp` with a UTC offset).
- Never send `id`, `tenant_id`, `recorded_at` (42501). `recorded_at` comes back from `returning` (`getObject(..., OffsetDateTime.class)`).
- Replay lookup: `select ... from ipd.dose_administration where tenant_id = :t and client_key = :k` (plain read, before the insert and again under the lock).

### Error classes (a hit on the insert is a code bug: 500, full rollback, never swallowed or retried)
| SQLSTATE | Constraint or message | Meaning |
|---|---|---|
| 23505 | `uq_dose_administration_order_slot` | the Java 409 `dose-recorded` check was skipped or raced past the admission lock |
| 23505 | `uq_dose_administration_tenant_client_key` | the Java replay check was skipped |
| 23514 | message contains "dose" (guard) | order not ACTIVE, admission not ADMITTED, or `high_alert` not equal to the medicine's flag: Java checked the wrong state or read the flag wrongly |
| 23514 | any `ck_dose_administration_*` | outcome/reason/second-check/identity combination wrong (see bind rules); `slot_minute` = slot not a whole minute |
| 23503 | `fk_dose_administration_order` or `_admission` | order missing, of another admission, or another clinic's (RLS hides it) |
| 23502 | not-null | a required value was null (also: no tenant bound) |
| 42501 | permission denied / "append-only" | a column outside the grant was named, or an update, delete or truncate was tried |
| 22P02 / 22007 | bad uuid or timestamp | binding bug |

## Notes for the human (not blockers)
1. ADR-011 D1 says `second_check_by` is stored for "GIVEN of a high-alert medicine only"; the "Data needed" checks (built as written) only require it for high-alert GIVEN and forbid it for non-GIVEN. A GIVEN dose of a non-high-alert medicine with a second nurse is therefore allowed by the database; Java must send null (ADR-011 D4: "not stored"). A one-line check `second_check_by is null or high_alert` could be added in a later migration if wanted.
2. `date_trunc('minute', timestamptz)` in a check is stable, not immutable; it works as a CHECK and matches the ADR text. It depends on the session time zone only for zones with a sub-minute UTC offset, which no real zone has today.

## For the qa-engineer / existing tests
- `RowLevelSecurityCatalogIT`: the new table has the two policies (select, insert), both `to sahaj_app` and on `platform.current_tenant()`, so the existing generic assertions hold with no exemption; any list of expected `ipd` tables or of insert-only tables needs `dose_administration` added (QA's file).
- Append-only catalogue tests that list tables with `deny_audit_mutation` triggers need `ipd.dose_administration`.

## QA test list (real database, as `sahaj_app` with `app.tenant_id` bound, and as the table owner)
Set-up: tenant A and B, each with an ADMITTED admission and an ACTIVE order (one of a non-high-alert drug such as `pcm`, one of a high-alert drug such as `hep`). Each insert uses the statement above.

Positive:
1. GIVEN of a non-high-alert medicine: `reason_key` null, `second_check_by` null, `identity_scan = 'SJ-00001'`-style (1 to 40 characters), `identity_confirmed = true`, `high_alert = false`: inserted; `id`, `tenant_id` defaulted; `recorded_at` within a second of `clock_timestamp()` and two rows in one transaction get different, increasing `recorded_at`.
2. GIVEN of a high-alert medicine (`hep`): `high_alert = true`, `second_check_by` = a user id different from `recorded_by`: inserted.
3. HELD with each of `HELD_DOCTOR`, `NIL_BY_MOUTH`, `NOT_AVAILABLE`, `AWAY_FROM_WARD`; REFUSED with `PATIENT_REFUSED` (all with `second_check_by`, `identity_scan`, `identity_confirmed` null). HELD/REFUSED of a high-alert medicine with `high_alert = true` and no second nurse: inserted.
4. A 40-character `identity_scan` is accepted; `slot_at` at 08:00 IST (02:30 UTC) is accepted.
5. Same slot for two different orders, and two different slots for one order: inserted. The same `client_key` in tenant B: accepted.
6. `returning id, recorded_at` works for `sahaj_app` (select privilege).

Negative (each leaves the table unchanged; the failed insert does not consume a row):
7. Same `(tenant_id, order_id, slot_at)` twice with different `client_key` and any outcomes: 23505 `uq_dose_administration_order_slot`. Same `(tenant_id, client_key)` for another slot: 23505 `uq_dose_administration_tenant_client_key`.
8. `slot_at` with seconds (`02:30:30`) or milliseconds: 23514 `ck_dose_administration_slot_minute`.
9. Outcome/reason pairs: GIVEN with a reason; HELD with no reason; REFUSED with `HELD_DOCTOR`; HELD with `PATIENT_REFUSED`; reason `OTHER`; outcome `GONE`: 23514 (the matching constraint name).
10. Second nurse: `second_check_by = recorded_by`; `second_check_by` on a HELD or REFUSED row; high-alert GIVEN with `second_check_by` null: 23514 (`second_check_other`, `second_check_outcome`, `high_alert_second_check`).
11. Identity: GIVEN with `identity_scan` null; GIVEN with `identity_confirmed` null or `false`; HELD with an `identity_scan` set; HELD with `identity_confirmed = false` (blocked by `identity_confirmed_only_given`); empty string and 41-character scan: 23514.
12. Guard: order PENDING, REJECTED or STOPPED (insert as owner, since the app cannot make a dose for them): 23514 with "dose can be recorded only for an active medicine order"; admission DISCHARGED (order ACTIVE): 23514 "only while the patient is admitted"; `high_alert = false` for `hep` and `true` for `pcm`: 23514 "high-alert flag does not match the medicine". Guard state error comes before a pair-check error (e.g. PENDING order with a bad reason gives the guard message).
13. FK: unknown `order_id`; an order of another admission of the same clinic (admission A's id with admission B's order); tenant A session naming tenant B's order and admission: 23503 (`fk_dose_administration_order`), not 23514.
14. Columns outside the insert grant as `sahaj_app`: `id`, `tenant_id`, `recorded_at`: 42501.
15. Append-only: `update`, `delete`, `truncate` as `sahaj_app`: 42501 (privilege); as the owner: 42501 from the trigger ("append-only"); rows unchanged (including `update ... set recorded_by = recorded_by`).
16. RLS: tenant A cannot select tenant B's doses; an insert with `tenant_id` of B (as owner under FORCE for `sahaj_app` policy check, or a session switched between statements) is refused (42501 row-level security); with no `app.tenant_id` bound nothing is visible and an insert fails (23502 or 42501, never inserts).
17. Order-state change after a dose: Stop an ACTIVE order that has a dose: the dose row stays and is readable; the Stop is allowed by the order guard (the dose FK only needs the row to exist).
18. The order guard still refuses changing `admission_id`/`id` of a medicine order (so `uq_medicine_order_tenant_id_admission` pairs stay valid); the new unique exists on `ipd.medicine_order` and `(tenant_id, id, admission_id)` is unique.
19. Catalog: `ipd.dose_administration` has RLS enabled and forced with exactly the two policies (select, insert); `sahaj_app` has select and insert on exactly the eleven columns listed, no update, delete or truncate; triggers `trg_dose_administration_no_update_delete`, `_no_truncate`, `_guard_insert` are enabled; `ipd.guard_dose_administration_insert()` is revoked from public and `sahaj_app`, has `search_path=pg_catalog` in `proconfig` and is not SECURITY DEFINER; the three constraint names `uq_dose_administration_order_slot`, `uq_dose_administration_tenant_client_key`, `uq_medicine_order_tenant_id_admission` exist; index `ix_dose_administration_tenant_admission_slot` exists with columns `(tenant_id, admission_id, slot_at)`; table comment contains "slot_at" and "partition".
20. Query plans (about 200,000 rows over several admissions and orders, `analyze`): MAR of today for one admission uses `ix_dose_administration_tenant_admission_slot` with a range on `slot_at`; last-dose-per-order and the "is this slot recorded" probe use `uq_dose_administration_order_slot`; the replay lookup uses `uq_dose_administration_tenant_client_key`.
21. Races (two connections, admission row lock as in ADR-011 D6): two nurses give the same slot at once with different keys (one inserted, the other 409 `dose-recorded` from Java; with the lock skipped the second insert is 23505 `uq_dose_administration_order_slot`); the same `clientKey` twice (one 201, one 200 `replayed`); give vs Stop (Stop first: 409 `order-state` from Java; with the lock skipped the guard gives 23514; give first: Stop succeeds and the dose stays); give vs discharge (discharge first: 409 `not-admitted`; with the lock skipped the guard gives 23514); give at 60 vs 61 minutes before the slot (clock hook in Java, not the database).
