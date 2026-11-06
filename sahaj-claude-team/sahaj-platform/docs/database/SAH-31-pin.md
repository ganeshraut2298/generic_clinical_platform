# SAH-31 · Database design: personal staff PIN (`platform.staff_pin`)

Migration (forward-only; no earlier migration is edited): `V2026_11_28_1100__platform_staff_pin.sql` (latest before it: `V2026_11_28_1000__ipd_medicine_orders.sql`). Decision record: ADR-010 "Data needed" and D1 to D14; DEC-017 Q1. Mockup screens: Clinic setup → Team and access, Staff panel `f-staff` ("PIN set" pill); IPD → Safety, Verify sheet (`sfver`). The IPD medicine-order tables are in `docs/database/SAH-31.md`.

One new table (`platform.staff_pin`), two trigger functions (`platform.guard_staff_pin_insert()`, `platform.guard_staff_pin_update()`) and four triggers. No other table changes (`app_user`, `otp_challenge`, `refresh_token`, `audit_event`, `membership` are untouched; `uq_membership_tenant_id` from `V2026_11_21_1000` is the FK target). The history of every change is the existing append-only `platform.audit_event` (ADR-010 D10), so there is no history table.

## platform.staff_pin
| Column | Type | Null | Default | Rule |
| --- | --- | --- | --- | --- |
| membership_id | uuid | no | | `pk_staff_pin` primary key. The person in one clinic. |
| tenant_id | uuid | no | `platform.current_tenant()` | `fk_staff_pin_tenant` to `platform.tenant (id)`; not insertable by the app. |
| pin_hash | text | yes | | `ck_staff_pin_hash_format`: `~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'` (60-character BCrypt). Null = PIN off. |
| pepper_version | smallint | no | 1 | `ck_staff_pin_pepper_version` (`>= 1`). |
| failed_count | smallint | no | 0 | `ck_staff_pin_failed_count` (0 to 4). |
| locked_until | timestamptz | yes | | End of the 15-minute lock; a past value = not locked. |
| lock_count | smallint | no | 0 | `ck_staff_pin_lock_count` (0 to 2). |
| lock_window_started_at | timestamptz | yes | | `ck_staff_pin_lock_window`: `(lock_count = 0) = (lock_window_started_at is null)`. |
| set_at | timestamptz | yes | | `ck_staff_pin_set_at`: `(pin_hash is null) = (set_at is null)`. |
| not_set_reason | text | yes | | `ck_staff_pin_not_set_reason` in (`RESET_BY_OWNER`, `TOO_MANY_LOCKS`); `ck_staff_pin_reason_only_when_off`: `pin_hash is null or not_set_reason is null`. |
| reset_at, reset_by | timestamptz, uuid | yes | | `ck_staff_pin_reset_pair`: both null or both set. `reset_by` = user id of the owner (no FK, user-id convention). |
| created_at | timestamptz | no | `now()` | |
| created_by | uuid | yes | | user id. |
| updated_at | timestamptz | yes | | stamped by the guard from `clock_timestamp()`. |
| updated_by | uuid | yes | | user id. |
| version | bigint | no | 0 | exact +1 on every update (guard). Use `getLong`. |

Keys and constraints:
- `fk_staff_pin_membership` composite foreign key `(tenant_id, membership_id) → platform.membership (tenant_id, id)`: a PIN row can only name a membership of the same clinic. Not cascading; memberships are never deleted.
- `uq_staff_pin_tenant_membership unique (tenant_id, membership_id)`: the tenant-first index. Serves the team list (`left join` of the clinic's memberships to their PIN state, by `tenant_id` and membership ids) and the checklist count ("every active member has a PIN set or locked"). The primary key on `membership_id` serves the check, set and reset by membership id (always with `tenant_id = ?` as well, which RLS adds anyway).
- `ck_staff_pin_cleared_when_off`: `pin_hash is not null or (failed_count = 0 and locked_until is null and lock_count = 0)`.
- No other index: the table has one row per membership (10 to 300 per clinic); every query is by tenant and membership.

State as read by the app (not stored): NOT_SET = `pin_hash is null` (reason in `not_set_reason`; no row at all = never set); LOCKED = `pin_hash is not null and locked_until > clock_timestamp()`; SET = otherwise.

## Tenant scope and RLS
Tenant table. ENABLE and FORCE row level security. Policies, all `to sahaj_app` and built on `tenant_id = platform.current_tenant()`: `p_staff_pin_select` (select), `p_staff_pin_insert` (insert, with check), `p_staff_pin_update` (update, using and with check, so a row cannot be moved to another clinic). No delete policy. Fails closed when no tenant is bound (`current_tenant()` is null: no row visible, no insert).

## Grants (`sahaj_app`)
- `select`
- `insert (membership_id, pin_hash, pepper_version, set_at, created_by)`. `tenant_id`, `failed_count`, `lock_count`, `version`, `created_at`, `reset_*`, `not_set_reason` always take their defaults.
- `update (pin_hash, pepper_version, failed_count, locked_until, lock_count, lock_window_started_at, set_at, not_set_reason, reset_at, reset_by, updated_at, updated_by, version)`. Not granted (42501 if named): `membership_id`, `tenant_id`, `created_at`, `created_by`.
- No delete, no truncate. Nothing granted to public.
- Triggers `trg_staff_pin_no_delete` and `trg_staff_pin_no_truncate` call `platform.deny_audit_mutation()` for every role, the owner included (SQLSTATE 42501; message `platform.staff_pin is append-only (DELETE refused)`; the word "append-only" is the shared function's wording).
- `select ... for update` works with the column-level update grant (it needs update on at least one column).

## Guards (SECURITY INVOKER, `search_path = pg_catalog`, revoked from public and `sahaj_app`, SQLSTATE 23514, every message contains "staff pin")
`guard_staff_pin_insert` (before insert): a row is created only with a PIN (`pin_hash` null is refused); `set_at := clock_timestamp()` whatever was sent.

`guard_staff_pin_update` (before update): every update is exactly one of the kinds below. The table checks run after the trigger, so the trigger reports first.

| Kind | New row | Guard requires | Trigger stamps | version |
| --- | --- | --- | --- | --- |
| Set / change a PIN (from set, locked or off) | `pin_hash` new and not null | `failed_count` 0, `locked_until` null, `lock_count` 0, window null, `reset_at` and `reset_by` unchanged, `not_set_reason` null (check) | `set_at`, `updated_at` | +1 |
| Owner reset | `pin_hash` null, `not_set_reason = RESET_BY_OWNER` | old row has a PIN (set or locked); `reset_by` set; counters cleared (check) | `reset_at`, `updated_at` | +1 |
| Third lock | `pin_hash` null, `not_set_reason = TOO_MANY_LOCKS` | old `failed_count` 4, old `lock_count` 2, old window started less than 24 hours ago, not currently locked; `reset_*` unchanged; counters cleared (check) | `updated_at` | +1 |
| Wrong try | same `pin_hash`, `failed_count + 1` | old not locked (`locked_until` null or past); nothing else changes | `updated_at` | +1 |
| Right PIN | same `pin_hash`, `failed_count = 0` | old not locked; nothing else changes (a write with `failed_count` already 0 is accepted) | `updated_at` | +1 |
| Lock (fifth wrong try) | same `pin_hash`, `failed_count = 0`, `lock_count` changed, `locked_until` not null | old `failed_count` 4; old not locked; `lock_count` = 1 when old is 0 or the window started 24 hours ago or more, otherwise old + 1; a lock with old `lock_count` 2 inside the window is refused (it must be the third-lock switch-off) | `locked_until = now + 15 minutes`, `lock_window_started_at` (now for a new window, else kept), `updated_at` | +1 |
| anything else | | refused: identity or created_* changed, version not exactly +1, pepper version lowered, off row updated again, `failed_count` jumping by more than one, window changed without a lock, `set_at` or reason changed with the same PIN | | |

"Now" is `clock_timestamp()` read once at the start of the trigger. The app never needs to send a time: send `set_at`, `locked_until`, `reset_at` only as the statements below do (they are overwritten by the trigger on the kinds that stamp them). On the non-stamping kinds a changed `set_at`, `reset_at`, `reset_by`, window or `locked_until` is refused.

## Statements for the java-developer (all in one short READ COMMITTED platform transaction, tenant bound)
Read and lock (also the read for `GET /me/pin`, without `for update`):
```sql
select pin_hash, pepper_version, failed_count, locked_until, lock_count, lock_window_started_at,
       set_at, not_set_reason, reset_at, version, clock_timestamp() as db_now
  from platform.staff_pin where tenant_id = :t and membership_id = :m for update
```
Use `db_now` for every decision (locked? window older than 24 hours?), never the application clock, so the guard and the app agree. No row = never set.

- First PIN (no row): `insert into platform.staff_pin (membership_id, pin_hash, pepper_version, set_at, created_by) values (:m, :hash, :pv, clock_timestamp(), :u)`. Do not send `tenant_id`. Two first saves at once: the second gets 23505 (`pk_staff_pin` or `uq_staff_pin_tenant_membership`) = 409 `concurrent-change`.
- New PIN on an existing row (change, forgot, after reset or switch-off): `update platform.staff_pin set pin_hash = :hash, pepper_version = :pv, failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, set_at = clock_timestamp(), not_set_reason = null, updated_by = :u, version = version + 1 where tenant_id = :t and membership_id = :m`. Do not touch `reset_at` or `reset_by`.
- Wrong try (old `failed_count` < 4, not locked): `set failed_count = failed_count + 1, updated_by = :u, version = version + 1`.
- Right PIN (only when `failed_count` > 0): `set failed_count = 0, updated_by = :u, version = version + 1`. Never when locked (the app does no comparison then).
- Fifth wrong try (old `failed_count` = 4), when the window is new (`lock_count` 0 or `db_now - lock_window_started_at >= 24 hours`) the new count is 1, otherwise old + 1, and old `lock_count` 2 inside the window is the next statement instead: `set failed_count = 0, lock_count = :n, locked_until = clock_timestamp() + interval '15 minutes', lock_window_started_at = clock_timestamp(), updated_by = :u, version = version + 1`. `locked_until` must be sent and not null (the guard recognises a lock by it or by the changed `lock_count`; it overwrites the value with the database time).
- Third lock (old `failed_count` 4, `lock_count` 2, window under 24 hours old): `set pin_hash = null, set_at = null, not_set_reason = 'TOO_MANY_LOCKS', failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, updated_by = :u, version = version + 1`.
- Owner reset (only when `pin_hash` is not null; on an off row write nothing and answer 200): `set pin_hash = null, set_at = null, not_set_reason = 'RESET_BY_OWNER', failed_count = 0, locked_until = null, lock_count = 0, lock_window_started_at = null, reset_by = :owner, reset_at = clock_timestamp(), updated_by = :owner, version = version + 1`.
- Access removal (`DELETE /team/{id}`): the same statement as the owner reset, only when `pin_hash` is not null.
- Always `version = version + 1` (exactly), never `version = :sent + 1`; the row lock makes `version = version + 1` safe. A `where version = :read` is optional.
- Never send `membership_id`, `tenant_id`, `created_at`, `created_by` in an update (42501), and never `tenant_id`, `failed_count`, `lock_count`, `version` in the insert (42501).

Errors that are code bugs (500, full rollback): 23514 from the guard (message contains "staff pin") or from any `ck_staff_pin_*` check; 42501 on a column outside the grants or on delete/truncate; 23503 `fk_staff_pin_membership` (a membership id that does not exist in this clinic: the service must have read the ACTIVE membership first, so this only happens on a bug or a forged id). 23505 on first insert is the only expected conflict (retry answer 409). A `for update` that finds a row changed by a parallel transaction is not an error (the lock waits).

Three things that look like bugs but are intended: a write that sets the same value (`failed_count` 0 to 0) is accepted; a wrong try arriving as the fifth is refused by `ck_staff_pin_failed_count` (23514), because it must be a lock; an update of an already-off row is refused ("staff pin is already off").

## Expected rows, retention, compatibility
- Rows: at most one per membership. About 10 to 300 per clinic; for 10,000 OPD clinics (2 to 10 staff) and 100 IPD hospitals the total is well under 200,000. Rows are updated in place, not appended (one small update per wrong try; at most 15 wrong tries a day per person before the PIN is switched off). No partitioning (far under 50 million).
- Retention: kept as long as the membership; never deleted (no delete privilege, delete and truncate triggers). A reset nulls the hash and keeps the row. The hash is a credential, not exported; the table holds no name, mobile number or clinical text. History: `platform.audit_event` (`PLATFORM_PIN_*`, ADR-010 D10), ids only.
- Compatibility: additive. The previous app version never reads or writes the table and runs unchanged against the new schema. Rollback before any row exists: a new forward migration that drops the table and its two functions.
- Backup/privacy: pepper (`SAHAJ_PIN_PEPPER`) is outside the database (ADR-010 D2, D3). A database copy alone reveals no PIN.

## QA test list (real database, as `sahaj_app` with a bound tenant, and as the table owner where stated)
Set-up: two clinics A and B, each with ACTIVE memberships; hashes are any valid 60-character BCrypt strings (for example `'$2a$12$' || repeat('a', 53)`); the owner sessions lift RLS on `platform.staff_pin` and `platform.membership` in a rolled-back transaction, as the other IT helpers do. States the app cannot reach (a lock in the past, a window older than 24 hours, `lock_count` 2) are written by the owner in one transaction with `alter table platform.staff_pin disable trigger trg_staff_pin_guard_update` first and `enable trigger` before commit, because the update guard refuses these edits for the owner too.

Positive (each with the exact statements above; check the stamped times come from the database clock, not the value sent, and that `version` rises by exactly 1):
1. First PIN insert: row has `failed_count` 0, `lock_count` 0, `version` 0, `tenant_id` of the session, `set_at` within a second of `clock_timestamp()` even when the insert sends `set_at` a year ago.
2. Wrong tries: four wrong-try updates give `failed_count` 1, 2, 3, 4; a right PIN update returns it to 0 with `lock_count` unchanged.
3. First lock: from `failed_count` 4, the lock statement gives `failed_count` 0, `lock_count` 1, `locked_until` about 15 minutes ahead (database clock, even when the statement sent a value a day ahead), `lock_window_started_at` now.
4. While locked (`locked_until` in the future): a wrong try, a right-PIN update and another lock are each 23514 "staff pin is locked". After `locked_until` is moved into the past by the owner (guard disabled, see set-up), a wrong try is accepted again.
5. Second lock inside the window: `lock_count` 2, window start unchanged (the same value as after the first lock).
6. Third lock: with `lock_count` 2, `failed_count` 4, window under 24 hours old, the switch-off statement succeeds: `pin_hash`, `set_at`, `locked_until` null, `not_set_reason = 'TOO_MANY_LOCKS'`, counters 0, window null. The ordinary lock statement (`lock_count` 3 or 2) from that state is refused (23514 "third lock ... must switch the PIN off", or the check on `lock_count` 3).
7. Window restart: with `lock_count` 2 (or 1) and a window started more than 24 hours ago (set by the owner), the lock statement with `lock_count` 1 succeeds and stamps a new window; the same state with the switch-off statement is refused (23514, not within 24 hours); `lock_count` 2 sent there is refused (wrong count).
8. New PIN after a switch-off or reset: the set statement gives `pin_hash` new, `set_at` stamped, `not_set_reason` null, counters 0; `reset_at` and `reset_by` are kept; sending different `reset_at` or `reset_by` is 23514. A change while locked (counters set, `locked_until` future) succeeds and clears them.
9. Owner reset: from set and from locked, gives `pin_hash` null, `set_at` null, `not_set_reason = 'RESET_BY_OWNER'`, counters cleared, `reset_by` set, `reset_at` within a second of now even when the statement sent another value. Reset without `reset_by` is 23514. Reset of an already-off row is 23514 "already off". A reset with counters not cleared is 23514 on `ck_staff_pin_cleared_when_off`.

Negative (each 23514 unless stated; the row is unchanged afterwards):
10. Version: an update with `version` unchanged, `+2`, or lower; an update with `version = version + 1` and a changed `membership_id` (42501 as app, 23514 as owner).
11. Wrong try with `failed_count` jumping by 2; a wrong try from `failed_count` 4 (`failed_count` 5: `ck_staff_pin_failed_count`); `failed_count` going down to 2; the lock statement from `failed_count` 3 ("locks only after the fifth wrong try"); `failed_count` unchanged at 4 with `locked_until` set.
12. Same PIN with a changed `set_at`, `not_set_reason`, `reset_at`, `reset_by`, `pepper_version`, or `lock_window_started_at` without a lock.
13. Switch-off kinds: `pin_hash` null without a reason; reason `TOO_MANY_LOCKS` at `lock_count` 1 or 0, or `failed_count` 3, or while locked; reason `OTHER` (`ck_staff_pin_not_set_reason`); `pepper_version` changed while switching off.
14. Insert with `pin_hash` null (guard); with a hash that is not BCrypt-shaped: `'1234'`, 59 and 61 characters, `$2x$12$...`, a character outside `[./A-Za-z0-9]` (`ck_staff_pin_hash_format`); `pepper_version` 0 (check); `tenant_id` named (42501).
15. Catalog checks as the owner with the guard trigger disabled in a rolled-back transaction, proving the second line: `ck_staff_pin_lock_window` (`lock_count` 1 with null window and `lock_count` 0 with a window), `ck_staff_pin_set_at` (hash with null `set_at`, null hash with `set_at`), `ck_staff_pin_reason_only_when_off` (hash plus reason), `ck_staff_pin_reset_pair` (only one of the two), `ck_staff_pin_lock_count` (3), `ck_staff_pin_cleared_when_off` (null hash with `failed_count` 1, with `locked_until`, with `lock_count` 1).

Isolation and keys:
16. Cross-tenant: session of clinic A cannot select, update (0 rows) or `select ... for update` the row of clinic B; with no tenant bound nothing is visible and an insert is refused (RLS, 42501 or null tenant).
17. Forged membership: in clinic A's session, insert with a `membership_id` that belongs to clinic B is 23503 `fk_staff_pin_membership`; so is a random uuid; an insert naming `tenant_id` of B is 42501 (column), and as the owner with `tenant_id = B` and A's membership is 23503. An update with `tenant_id` set to another clinic is 42501; as the owner with RLS lifted it is 23514 (identity).
18. Duplicate: a second insert for the same membership is 23505 (primary key or `uq_staff_pin_tenant_membership`).
19. A removed or inactive membership does not matter to the database (the service checks it); the PIN row remains.

Grants, delete and truncate:
20. Catalog: update privileges of `sahaj_app` on the table are exactly the 13 columns listed; insert privileges exactly the 5; select on the table; `has_table_privilege` for delete, truncate, references and trigger is false; nothing granted to public.
21. `delete` and `truncate` as `sahaj_app` give 42501 (privilege); as the table owner (RLS lifted for `delete`) give 42501 from the trigger, message contains "append-only"; rows unchanged.
22. Catalog: RLS enabled and forced; exactly three policies, `r`, `a`, `w`, all `to sahaj_app`, all on `platform.current_tenant()` with no `true`, `or`, `coalesce` or `is null` (the existing `RowLevelSecurityCatalogIT` rule); the four triggers are enabled; both guard functions are SECURITY INVOKER with `search_path=pg_catalog` in `proconfig`; `execute` revoked from public and `sahaj_app`; the table comment is present and does not contain a hash or a PIN.
23. `explain` of the team-list join and the single-membership read use `uq_staff_pin_tenant_membership` or the primary key (no sequential scan with 20,000 rows across clinics after `analyze`).

Concurrency (two connections):
24. Two parallel wrong tries on one row, each `select ... for update` then the update: both are counted (`failed_count` 2), the second waits for the first.
25. Two parallel first-PIN inserts for one membership: one succeeds, the other 23505.
26. A reset against a wrong try: the second statement sees the committed first one under the row lock; a wrong try after the reset is refused by the guard ("already off") only if the app skipped the read (a code bug), which proves the backstop.

Flyway: the migration applies on a database that already has `V2026_11_28_1000`, and from scratch (`clean` in `sahaj_test`).
