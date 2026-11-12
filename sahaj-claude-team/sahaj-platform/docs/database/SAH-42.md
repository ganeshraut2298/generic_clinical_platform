# SAH-42 · Database design: bootstrap registration of the first platform admin (PIN)

Decision records: `docs/decisions/DEC-034-admin-bootstrap-registration-pin.md`, `docs/architecture/adr/ADR-018-admin-bootstrap-registration-pin.md` (Revision 3: "Data needed", D4 "count first, judge after", D6, D7, the rollback table, "Error mapping", tests R3-U1 to R3-I12), `docs/architecture/adr/ADR-003-sahaj-admin-portal.md` (D1, D4, D7), ADR-008 database work (`docs/database/ADR-008-dev-sample-admin.md`).
Contract: `docs/api/admin.yaml` 0.5.0 (`bootstrapUnlock`, `bootstrapRegister`, both `x-dev-only`).
Mockup screen: none for the database (the `/admin/register` page is a rule-1 deviation, DEC-013 item 16; nothing here depends on it).
Revision: 3 (11 Nov 2026), after ADR-018 Revision 3, code review findings 7, 11, 17 and security review M1. Revision 2 shapes are replaced; nothing under `db/migration` is touched.
Revision 5 note (11 Nov 2026), after ADR-018 Revision 5: the register 201 no longer carries an ENROL pre-auth token and the bootstrap no longer calls `platform.admin_preauth_create`. **No SQL behaviour change**: one pre-flight comment in the callback and three sentences here were reworded; no executable statement changed.

SQL (companions of this note, all text only):
- `docs/database/SAH-42-admin-bootstrap.sql`: the body of the callback. The java-developer places it **byte for byte** at `backend/src/devtools/resources/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql` (folder name changed from `dev-tools-bootstrap`).
- `docs/database/ADR-008-guard.sql`: wording of the raise message and hint changed (finding 17); checks unchanged. Place byte for byte over `backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql`.
- `docs/database/runbooks/admin-bootstrap-clear-lock.sql`: human runbook; now also clears the lease.
- `docs/database/runbooks/dev-tools-sample-admin-cleanup.sql`: drops the new and the old function signatures.

**Status: written by reading only. NOT YET EXECUTED. No psql, no database, no role was touched while writing it.** The first run is the QA gate and is blocked by human prerequisites: role `sahaj_admin_definer` is missing in `scripts/db/init.sql` and `scripts/setup/create-database.mjs`, and the admin tables are still parked in `backend/src/main/resources/db/pending/admin/`. `node scripts/validate.mjs db` was not run: nothing under `db/migration` changed and the rules forbid touching a database. No PIN value and no PIN hash appear in any file of this story.

## What this is, and what it is not

- A **Flyway callback** (`afterMigrate`) in its own location `classpath:db/bootstrap-dev-tools`, **not a migration**. Nothing is added to `backend/src/main/resources/db/migration/`. It runs as `sahaj_migrator` after every migrate, only in a `-Pdev-tools` build whose conditions B1 to B7 hold (ADR-018 D2), and only when `current_database()` matches `^sahaj_(dev|test)$`.
- The location name shares no prefix with ADR-008's `db/dev-tools` (ADR-018 D1 "Folder name rule"). The old name `db/dev-tools-bootstrap` must not exist in any build.
- The production jar does not contain it. A production database never gets the table, the functions or the column grant. The production guard fails a non-dev database that has them.
- Platform scope: no tenant table is read or written, `app.tenant_id` is never set. It amends ADR-003 D1 ("no INSERT for the definer") in dev and test databases only, as the human approved for ADR-008 (H-DEV-1) and accepted again as H-BOOT-3.
- Idempotent: "create if not exists" and "add column if not exists" for the table, "create or replace" for the functions, additive grants. The only drops are of the Revision 2 function shapes, and only while they are present. It never drops, deletes, truncates or resets the table or its row, so the count and the lock survive every restart and re-run.

## Prerequisites (human, unchanged)

1. Role `sahaj_admin_definer` (`NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT`) and `GRANT sahaj_admin_definer TO sahaj_migrator` (the callback checks `member`, `usage` and `set`).
2. The parked admin migrations active in `db/migration` and renumbered (`V2026_11_15_1000`, `V2026_11_15_1010`). The callback's pre-flight also requires `platform.admin_preauth_create(uuid,text,text,text,inet)` (from `V2026_11_15_1010`) so it can never run between the two and make that migration's "13 admin functions" count fail (the ordering reason). The function is also needed by the first sign-in: the owner's first sign-in enrols this admin through it. `register` itself does not call it (ADR-018 Revision 5).
3. Request to the owner of `V2026_11_15_1010` at activation (this role, db-engineer): any later repeat of its `admin\_%` function count must exclude `admin\_dev\_%` and `admin\_bootstrap\_%`.

## Objects

### Table `platform.platform_admin_bootstrap_state`

One row, platform scope. Owner `sahaj_migrator`. No `tenant_id`, no RLS: it is not tenant data and privileges are the control, like every `platform_admin*` table. (The name is singular, as the other `platform_admin*` tables.)

| Column | Type | Null | Default | Rule |
| --- | --- | --- | --- | --- |
| `id` | smallint | no | none | primary key `pk_platform_admin_bootstrap_state`; `ck_..._id` check `id = 1` (one row only) |
| `failures` | smallint | no | 0 | `ck_..._failures` between 0 and 5. The global count of counted tries. Since Revision 3 it is raised by the claim (`begin`), not by the judge |
| `locked_until` | timestamptz | yes | null | end of the 60-minute lock |
| `ticket_hash` | text | yes | null | `ck_..._ticket_hash` `^[0-9a-f]{64}$` (HMAC-SHA256 hex of the ticket) |
| `binding_hash` | text | yes | null | `ck_..._binding_hash` same pattern (HMAC of the cookie value) |
| `ticket_expires_at` | timestamptz | yes | null | unlock time + 5 minutes |
| `updated_at` | timestamptz | no | `now()` | set by every function; no trigger |
| `try_id` | uuid | yes | null | **Revision 3.** The try that was counted and is being judged. Random value chosen by Java for each try; not a secret, never audited, never logged |
| `try_expires_at` | timestamptz | yes | null | **Revision 3.** End of that try's lease: claim time + 30 seconds |

Column order after the callback (the self-check requires exactly this): `id, failures, locked_until, ticket_hash, binding_hash, ticket_expires_at, updated_at, try_id, try_expires_at`. The two new columns are added with `alter table ... add column if not exists`, so a table made by an earlier run keeps its row, count and lock, and the new columns start null; a fresh table goes through the same `alter`, so both end identical.

Table checks (prefix `ck_platform_admin_bootstrap_state_`); the table has nine named constraints in all (the primary key, `id`, `failures`, `ticket_hash`, `binding_hash` and the four below):
- `ticket_all_or_none`: the three ticket columns are all null or all set (as the ADR asks).
- `lock_matches_failures`: `(failures = 5) = (locked_until is not null)`. Added by the first design (not in the ADR): the row can never carry a half lock. A lock that has run out keeps `failures = 5` and a past `locked_until` until the next `begin` clears both together.
- `no_ticket_while_locked`: `locked_until is null or ticket_hash is null`. A lock cancels the open ticket (BR7e) and the database enforces it.
- `try_both_or_none` (**Revision 3**, `ck_platform_admin_bootstrap_state_try_both_or_none`, 50 characters): `(try_id is null) = (try_expires_at is null)`. Added only when missing (a lookup in `pg_constraint`, then `alter table ... add constraint`). A lease **may** coexist with a lock: the claim of the 5th try sets both, and the judge that follows clears the lease.

No other index: the primary key serves every lookup (`where id = 1`). Constraint names are at most 56 characters (limit 63).

**Privileges.** `revoke all ... from sahaj_app, public` on every run (the schema's default privileges would otherwise give `sahaj_app` all four commands). `sahaj_admin_definer`: `revoke all` then `grant select, insert, update` (no `DELETE`: there is no delete path). The self-check reads the table ACL and fails on any other grantee or privilege.

### Grant on `platform.platform_admin`

`grant insert (id, email, full_name, password_hash) on platform.platform_admin to sahaj_admin_definer`. Every other column takes its default: `active` true, `password_changed_at`, `created_at`, `version`; the TOTP columns stay null, so the admin is created **not enrolled** (`ck_platform_admin_totp_all_or_none` holds). Unchanged by Revision 3.

**Interaction with ADR-008's callback (important).** ADR-008's callback runs `revoke insert ... from sahaj_admin_definer` and then grants the seven columns (these four plus `totp_secret_enc`, `totp_key_version`, `totp_enrolled_at`). This callback therefore does **not** revoke anything. Whichever runs last, the result is "at least these four, at most those seven":
- bootstrap alone: exactly the four;
- sample admin alone: the seven;
- both, either order: the seven (the four are a subset).
The self-check tests exactly `required <@ actual <@ allowed`, so a table-wide grant or an extra column (`active`, `version`, ...) fails the run. The ADR-008 cleanup runbook revokes all INSERT, which removes both.

### The five functions (Revision 3 shapes)

All: `SECURITY DEFINER`, owner `sahaj_admin_definer`, `SET search_path = pg_catalog, pg_temp` (`pg_temp` last stops temp-schema hijack), every object schema-qualified, **no dynamic SQL**, `REVOKE ALL ... FROM PUBLIC`, `GRANT EXECUTE ... TO sahaj_app`. Each raises `42501` first when `platform.current_tenant()` is not null (a clinic session). Temporary `CREATE` on schema `platform` for the definer (needed by `ALTER FUNCTION ... OWNER TO`), taken back at the end of the script; the self-check fails the run if it stays. **`SET lock_timeout = '10s'` on the four row-locking functions** (`begin`, `unlock_failed`, `unlock_succeeded`, `register`); `ticket_status` takes no lock and has none.

Constants are inside the bodies, never parameters: **5 tries, 60 minutes lock, 5 minutes ticket, 10 seconds lock wait and (new) 30 seconds try lease** (`c_lease_for` in `begin`). The **only clock is the database's `clock_timestamp()`** (not `now()`, which is frozen at transaction start). Java passes no time, so no caller can move a lock, a ticket or a lease.

| Function | Returns | Locks | Writes | Audit |
| --- | --- | --- | --- | --- |
| `admin_bootstrap_begin(p_try_id uuid)` | table `(outcome text, failures smallint, locked_until timestamptz)`, outcome `CLOSED` \| `LOCKED` \| `BUSY` \| `COUNTED` | state row `FOR UPDATE` | creates row 1 on first use; clears a run-out lock; clears a run-out lease; on `COUNTED` adds 1, sets the lease, and on the 5th try the 60-minute lock and clears the ticket | `ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED` when it clears a run-out lease |
| `admin_bootstrap_unlock_failed(p_try_id uuid, p_ip inet, p_request_id text)` | table `(failures smallint, locked_until timestamptz)` | state row | clears the lease. **Never changes the count** | `ADMIN_BOOTSTRAP_UNLOCK_FAILED`; when the row is locked also `ADMIN_BOOTSTRAP_LOCKED` |
| `admin_bootstrap_unlock_succeeded(p_try_id uuid, p_ticket_hash text, p_binding_hash text)` | `timestamptz` (ticket expiry) | state row | `failures = 0`, `locked_until = null`, the three ticket columns (`clock + 5 min`), lease cleared | none (OQ-42-9) |
| `admin_bootstrap_ticket_status(p_ticket_hash text, p_binding_hash text)` | table `(status text, locked_until timestamptz)`, status `CLOSED` \| `LOCKED` \| `INVALID` \| `VALID`; `locked_until` set only with `LOCKED` | none (plain read) | nothing | none |
| `admin_bootstrap_register(p_ticket_hash, p_binding_hash, p_admin_id uuid, p_email, p_full_name, p_password_hash, p_ip inet, p_request_id)` | table `(outcome text, locked_until timestamptz)`, outcome `CREATED` \| `CLOSED` \| `LOCKED` \| `INVALID`; `locked_until` set only with `LOCKED` | advisory lock `202611050008`, then the state row | inserts the admin, clears the ticket; ignores the lease | `ADMIN_BOOTSTRAP_ADMIN_CREATED` |

#### `begin` (the claim)

Order of work, which the ADR fixes and the SQL follows:
1. `42501` when a tenant is bound; `22023` when `p_try_id` is null.
2. Any admin exists: `('CLOSED', 0, null)`, **nothing written, row not even created**.
3. Insert row 1 `on conflict do nothing`; `select ... for update` (`SB003` if the row is somehow missing); `v_now := clock_timestamp()`; look for an admin again after the lock (`CLOSED`, nothing written; a register that finished first has committed by now, READ COMMITTED).
4. A lock with `locked_until <= v_now` is cleared (`failures = 0`, `locked_until = null`, BR7d).
5. A lease with `try_expires_at <= v_now` is cleared and one `ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED` row is written (`REFUSED`, refusal code `try-unjudged`, `ip` and `request_id` null; they belong to the lost request, which is not stored). The count is **not** touched: the try stays counted.
6. A lock still in force: `('LOCKED', failures, locked_until)`. No count, no compare may follow.
7. A live lease: `('BUSY', failures, null)`. Nothing counted. Java waits 250 ms and asks again, for 10 seconds at most.
8. Otherwise one `UPDATE`: `failures + 1`, `try_id = p_try_id`, `try_expires_at = v_now + 30 s`; when `failures` reaches 5 the same statement also sets `locked_until = v_now + 60 min` and nulls the three ticket columns (BR7e). Returns `('COUNTED', failures, locked_until)`; `locked_until` is set only for the 5th try.

Because the check at step 6 precedes the count, `failures` can never pass 5, and because step 7 precedes it, at most one try is in flight. The caller **commits before it compares**; once that commit has returned, nothing that happens later can give the try back.

**UNJUDGED timing (decided in ADR-018 D7 and Revision 3 (f)).** The `ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED` row is written once, at the first `begin` after the 30-second lease runs out, whether or not the lock is in force. The Data-needed list (steps in the order above) puts the lease clearing **before** the lock check, so the SQL writes the row at the first `begin` after the lease runs out (30 seconds), even if the lock is still in force, then answers `LOCKED`. It is written once (the lease is cleared by the same statement), so a hostile loop cannot fill the audit table, and the row appears sooner, which is more useful.
#### `unlock_failed` and `unlock_succeeded` (the judges)

- Both: `42501`; `22023` for a null `p_try_id` (and, for `unlock_failed`, a request id over 100; for `unlock_succeeded`, a malformed ticket or binding hash); row `FOR UPDATE` (`SB003` no row); `SB001` when an admin exists; **`SB004` unless `try_id = p_try_id and try_expires_at > clock_timestamp()`**. Closed is checked before the lease.
- `unlock_failed` clears the lease and audits. The summary is "Bootstrap registration: wrong PIN (n of 5)" with `n` the row's `failures`, which already holds this try. When `locked_until` is not null (this was the 5th try: no other lock can coexist with a live lease) it also writes `ADMIN_BOOTSTRAP_LOCKED` with `after_json.lockedUntil`. It returns the row's `(failures, locked_until)`; Java reads `triesLeft = 5 - failures`, **not** `failures + 1`.
- `unlock_succeeded` does one `UPDATE`: `failures = 0` (BR7c), `locked_until = null` (this lifts the lock that this try's own claim set, so "4 wrong, then the correct PIN as 5th try" still unlocks), the ticket (BR8), lease cleared. Not audited.

#### `ticket_status` and `register`

- `ticket_status` precedence: any admin exists, `CLOSED`; `locked_until > clock`, `LOCKED` with `locked_until`; null or malformed hashes, no open ticket, no row: `INVALID` (never an error); both hashes equal and `ticket_expires_at > clock`: `VALID`; else `INVALID`. No row lock, ignores the lease. Java calls `ticket_status(null, null)` as the **peek** for a blank PIN or an unreadable unlock body: `CLOSED` gives the empty 404, `LOCKED` the 423, anything else the 400; it never counts and never audits.
- `register` order: parameter checks (`22023`, repeating the table rules for email and name; the hash pattern is the table's), advisory lock, state row `FOR UPDATE`, then `CLOSED` (any admin, active or not, seeded or not), `INVALID` (no row), `LOCKED` (with `locked_until`), `INVALID` (ticket not equal or older than 5 minutes), insert. The insert sits in an inner block (a savepoint): a `unique_violation` on `uq_platform_admin_email` returns `CLOSED` (it can only mean an admin exists); any other unique violation is re-raised. Then the ticket is cleared and the audit row is written. **Email and full name go only into `platform_admin`.** It never calls `begin` and never counts a try; Java computes the 423's `retryAfterSeconds` from the `locked_until` it returns.
- `lock_timeout` on `register` bounds both waits (advisory lock and row): a wait over 10 s raises `55P03`, answered as 429 (settled in ADR-018 Revision 1).

## Audit (inside the functions, same transaction)

Table `platform.platform_admin_audit` (append-only; the definer already has `SELECT, INSERT`). Every row: `kind` `AUTH`, `auth_step` null, `target_tenant_id` null, `occurred_at` = `clock_timestamp()`, `ip` and `request_id` from the caller (except where stated). **No PIN, hash, ticket, binding, try id, password, email, typed text or secret in any column.**

| Action | Outcome / refusal code | Admin id, name | Summary | Other |
| --- | --- | --- | --- | --- |
| `ADMIN_BOOTSTRAP_UNLOCK_FAILED` | `REFUSED` / `pin-invalid` | none | `Bootstrap registration: wrong PIN (n of 5)` | written by the judge (`unlock_failed`) |
| `ADMIN_BOOTSTRAP_LOCKED` | `REFUSED` / `locked` | none | `Bootstrap registration locked for 60 minutes` | `after_json = {"lockedUntil": "2026-11-10T11:05:00Z"}` (UTC, `Z`); written by the judge of the 5th try |
| `ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED` (**new**) | `REFUSED` / `try-unjudged` | none | `Bootstrap registration: a counted try ended without a result` | written by `begin` when it clears a run-out lease; `ip` and `request_id` null |
| `ADMIN_BOOTSTRAP_ADMIN_CREATED` | `APPLIED` | new admin's id and full name | `First admin created through bootstrap registration` | |
| `ADMIN_BOOTSTRAP_LOCK_CLEARED` | `APPLIED` | none | `Bootstrap registration lock cleared by hand` | written only by the runbook; no ip, no request id |

**The new row against the table's checks** (read in `V2026_11_15_1000`, section 6): `kind = 'AUTH'` is allowed; `action` is 31 characters (check 1 to 60); `summary` is 60 characters (check at most 300); `outcome = 'REFUSED'` is allowed; `refusal_code = 'try-unjudged'` is 12 characters (at most 60) and `ck_platform_admin_audit_refusal_outcome` requires `outcome = 'REFUSED'` when a refusal code is set, which holds; `ip` and `request_id` null are allowed (both nullable; the `request_id` check only limits length); `auth_step` null satisfies `ck_platform_admin_audit_auth_step_kind`; `admin_id` null needs no foreign key. No migration is needed.

Every counted try ends with exactly one of: `UNLOCK_FAILED` (judged wrong), `UNLOCK_UNJUDGED` (the judge never committed), or no row (judged correct). A try whose judge failed has no row until the lease runs out and the next `begin` writes `UNJUDGED`. The 5th wrong PIN writes two rows (`UNLOCK_FAILED` "5 of 5" and `LOCKED`). Not audited, by design: a correct unlock, a refusal while locked, a blank PIN, an invalid ticket, validation errors, every closed/off answer, `BUSY` and the 429 (BR12). `platform_admin_audit.action` is free text (1 to 60 characters), so no migration is needed; whoever keeps a list or labels of audit actions in Java or the Angular Audit log page (ADM-3) must add these five codes, or show unknown codes as plain text.

## Error mapping (for the java-developer; matches ADR-018 "Error mapping", which is binding)

`SB001`, `SB003`, `SB004` are custom SQLSTATEs (class `SB`, as `V2026_11_15_1010` uses `SA001` to `SA007`). **`SB002` ("locked") is retired: no function raises it and it is not reused.** If Java ever sees it: bodyless 500. Messages are fixed and carry no value. Every database error rolls back **its own** transaction; every return value commits. **A try counted by a committed claim is never given back.**

| Signal | Where | Java answer |
| --- | --- | --- |
| `55P03` (lock wait over 10 s) | `begin`, `register` | roll back; `429 rate-limit`, `Retry-After: 5`, `retryAfterSeconds: 5`; nothing counted, created or spent (the same ticket may be sent again) |
| `BUSY` for 10 s in a row | `begin` | each attempt commits (nothing written); then the same 429; not counted |
| `55P03` | `unlock_failed`, `unlock_succeeded` | roll back the judge; bodyless 500; the try stays counted (not a 429: the try is already used) |
| `SB001` closed | `unlock_failed`, `unlock_succeeded` | roll back the judge; empty 404 |
| `SB002` | none (retired) | bodyless 500 if ever seen |
| `SB003` row missing | `unlock_failed`, `unlock_succeeded` (and `begin`, which cannot normally reach it) | roll back; bodyless 500 (programming error) |
| `SB004` try not open (no lease, another try's lease, a lease that ran out) | `unlock_failed`, `unlock_succeeded` | roll back the judge; bodyless 500; the try stays counted |
| `42501` (tenant bound, or "permission denied" because the callback did not run), `22023`, `23505` other than the email (which `register` maps to `CLOSED`), any other SQLSTATE (`40001`, `40P01`, `57014`, `42883`, `3F000`), a failed commit | any | roll back that transaction; bodyless 500; log class and SQLSTATE only. After a committed claim: **never 200, never 401** |
| `CLOSED` | `begin`, `ticket_status`, `register` | commit; empty 404 |
| `LOCKED` with `locked_until` | `begin`, `ticket_status`, `register` | commit; `423 bootstrap-locked`, `retryAfterSeconds` computed in Java from `locked_until` (`ceil`, clamped 1 to 3600). Register no longer calls `begin` |
| `COUNTED` | `begin` | commit, **then** compare, then judge. If the commit throws: bodyless 500 and no compare |
| `INVALID` | `ticket_status`, `register` | commit; `401 bootstrap-ticket-invalid`, clear the cookie (from `ticket_status(null, null)` in the peek: the 400 of ADR-018 D2 instead) |
| `(failures 1..4, null)` | `unlock_failed` | commit; `401 bootstrap-pin-invalid`, `triesLeft = 5 - failures` |
| `(5, locked_until)` | `unlock_failed` | commit; `423`, `retryAfterSeconds` 3600, `Retry-After: 3600`, one WARN line |
| any other pair | `unlock_failed` | bodyless 500 |
| unlock success / `VALID` / `CREATED` | | commit, 200 / continue / commit, 201 |

## Transactions (Java must follow, or the limit does not hold)

- **Unlock is not one transaction and the service method must not be transactional** (`@Transactional(propagation = NEVER)`). It runs up to three short ones, READ COMMITTED (never `REPEATABLE READ` or higher):
  1. **Peek** (read only): `ticket_status(null, null)` for a blank PIN or an unreadable body. Never calls `begin`, never counts.
  2. **Claim (T1)**: `begin(try_id)` with a fresh random `try_id`. **Commit.** The BCrypt compare starts only after this commit has returned without an error.
  3. **Compare**: no transaction, no row lock, no pooled connection held. If the verifier throws: bodyless 500, no judge call, the try stays counted.
  4. **Judge (T2)**: `unlock_failed(try_id, ip, request_id)` or `unlock_succeeded(try_id, ticket_hash, binding_hash)`. Commit on every returned value (wrong PIN is a normal return and the audit rows must commit). Any exception rolls back T2 only; T1's count stays.
- **Locked answer.** `retryAfterSeconds` is computed in Java as `ceil(locked_until - Instant.now())` in seconds, clamped to 1 to 3600, from the `locked_until` that `begin`, `ticket_status` or `register` returned (Java and the database run on one machine). The 5th wrong PIN answers the fixed 3600.
- **Register = one transaction:** `ticket_status` (before form validation), validation in Java, `register`. It calls no sign-in function and hands out no pre-auth token (ADR-018 Revision 5). A failed commit after `CREATED` rolls back the admin and leaves the ticket unspent. `CLOSED`, `LOCKED`, `INVALID` write nothing, so commit or roll back, both are fine.
- The lease is what keeps tries one at a time while no row lock is held. **Fail-closed reading of every failure:** a request that dies after T1 leaves a counted, unjudged try; the lease runs out after 30 seconds; the next `begin` writes `UNJUDGED`. The price: a server failure during a 5th try whose PIN was correct leaves the lock in force for 60 minutes (the clear-lock runbook lifts it).

## Lock order, concurrency, idempotency

- **Order everywhere: advisory lock first (only `register`, and ADR-008's sample-admin function), then the state row.** The claim and the judges never take the advisory lock, so there is no cycle. A register waits only for a claim or judge in progress, which holds the row for an instant (no BCrypt runs under the row lock any more). Waits are bounded by 10 s.
- Concurrent claims serialise on the row lock inside `begin`, and the lease then refuses all but one: exactly one `COUNTED`, the rest `BUSY` until that try is judged or its lease runs out. The count can never pass 5.
- Two registers: the loser re-reads under the advisory lock, finds the admin, returns `CLOSED` (404). Register versus the sample-admin function: same advisory key, so one creates and the other is refused (the sample function audits `ADMIN_DEV_SAMPLE_REFUSED`; register returns `CLOSED`).
- An admin made by hand or by the env seed outside the advisory lock is still seen by the next statement (READ COMMITTED); an identical email is caught by `uq_platform_admin_email`.
- **Idempotency.** The callback: any number of runs, same end state, row untouched; a re-run with the new shapes drops nothing. `begin` is deliberately not idempotent (each `COUNTED` counts); `ticket_status` is repeatable; a second `register` after `CREATED` returns `CLOSED`. The clear-lock runbook: the second run changes nothing and writes no audit row.

## Expected rows, retention, plans

- **Tenant scope:** none, so no "rows per tenant". State table: **1 row** for the life of the dev database. Admin table: this path adds at most 1 row, ever. Audit: per lock cycle at most 5 rows from tries (each `UNLOCK_FAILED` or `UNLOCK_UNJUDGED`, one per counted try) + 1 `LOCKED`, + 1 `ADMIN_BOOTSTRAP_ADMIN_CREATED` in total. A cycle needs at least 60 minutes unless a human clears it. No partitioning concern.
- **Retention.** State row: kept while the dev database lives; not deleted by any callback. An expired ticket's two HMAC hashes stay in the row until the next unlock, lock or registration replaces or clears them (keyed hashes of one-use random values, 5-minute life). A run-out lease stays in the row until the next `begin` or the runbook clears it; it holds a random uuid only. Admin row: never deleted (a human sets `active = false`). Audit rows: 7 years, append-only (ADR-003 D7); these are dev and test rows. Clinical and billing rows are not involved.
- **Backward compatibility.** Nothing under `db/migration` changes, so the previous app version runs unchanged. The Revision 2 Java code cannot run against the Revision 3 functions (different signatures): the two Java halves must be released together; both exist only in dev-tools builds.
- **Expected query plans** (single-row lookups; **marked expected, not run**; the table has one row, so the planner may pick a sequential scan over the index, both are one buffer):
  - `select ... from platform.platform_admin_bootstrap_state where id = 1 [for update]`: `Index Scan using pk_platform_admin_bootstrap_state` (or `Seq Scan` on one row), plus `LockRows` for `FOR UPDATE`.
  - `update ... where id = 1`: same, then `ModifyTable`.
  - `exists (select a.id from platform.platform_admin a)`: `Result` over `Limit` over `Seq Scan on platform_admin` (or index-only scan of the primary key). Stops at the first row; the table has at most a handful of rows.
  - `insert ... on conflict (id) do nothing`: `Conflict Resolution: NOTHING`, arbiter index `pk_platform_admin_bootstrap_state`.
  - Audit inserts: plain `Insert`; the only foreign key checked is `admin_id` (register), by the primary key of `platform_admin`.

## Tests: how to simulate time (QA)

Run as `sahaj_migrator` on a separate connection and commit each change, or the app transaction will not see it. **Never read the clock in the test**: move the stored times with data (the functions use only `clock_timestamp()`).

| Wanted | SQL (as `sahaj_migrator`) |
| --- | --- |
| Lock has 30 minutes left | `update platform.platform_admin_bootstrap_state set locked_until = clock_timestamp() + interval '30 minutes' where id = 1;` (needs `failures = 5` and no ticket, else a check refuses it) |
| Lock ended 1 second ago | `update ... set locked_until = clock_timestamp() - interval '1 second' where id = 1;` (`failures` stays 5; the next `begin` resets both) |
| Ticket at 4 min 59 s | `update ... set ticket_expires_at = clock_timestamp() + interval '1 second' where id = 1;` |
| Ticket at 5 min 1 s | `update ... set ticket_expires_at = clock_timestamp() - interval '1 second' where id = 1;` |
| The lease ran out (**new**) | `update ... set try_expires_at = clock_timestamp() - interval '1 second' where id = 1 and try_id is not null;` |
| A live lease of another try (**new**) | `update ... set try_id = gen_random_uuid(), try_expires_at = clock_timestamp() + interval '30 seconds' where id = 1;` |
| Count is 4 | `update ... set failures = 4, locked_until = null where id = 1;` |
| Lock after the 5th try | `update ... set failures = 5, locked_until = clock_timestamp() + interval '60 minutes', ticket_hash = null, binding_hash = null, ticket_expires_at = null where id = 1;` |
| Clean slate between tests | run `docs/database/runbooks/admin-bootstrap-clear-lock.sql`, or the same `update` with `failures = 0` and everything else null, lease columns included (writes no audit row) |

Test admin rows: `platform_admin` rows are never deleted and audit rows block the delete through the foreign key. Use `sahaj_test`, rebuilt by `validate db`/the backend tests (Flyway clean), or a throwaway database named `sahaj_test` recreated by the test harness. The register case needs an empty `platform_admin`.

### Cases to cover (as `sahaj_app` unless stated)

Numbers S1 to S30 keep their meaning; **S6 to S9, S22 and S28 are rewritten for Revision 3**; S31 to S38 are new. Judge calls use the `try_id` that the same test passed to `begin`.

| # | Case | Expect |
| --- | --- | --- |
| S1 | Callback twice in a row | both succeed; second changes nothing (compare `pg_proc.proacl`, the table ACL, column privileges, the state row) |
| S2 | Callback with the row at `failures = 3` (and with a live lease, and with a lock) | row unchanged afterwards |
| S3 | Callback in a database not named `sahaj_dev`/`sahaj_test` (test replaces `current_database()`), with the role missing, with the admin tables missing, with `admin_preauth_create` missing | each fails with its own message before any grant |
| S4 | Metadata | five functions owned by the definer, `prosecdef`, `search_path=pg_catalog, pg_temp`, `lock_timeout` on `begin`, `unlock_failed`, `unlock_succeeded`, `register` (not `ticket_status`), ACL: EXECUTE for definer and `sahaj_app` only; table ACL: definer SELECT/INSERT/UPDATE; nine columns, nine constraints; `has_schema_privilege('sahaj_admin_definer','platform','CREATE')` false |
| S5 | `select * from platform.platform_admin_bootstrap_state`, `insert`, `update` as `sahaj_app` | permission denied |
| S6 | `begin(<uuid>)` on an empty database | one row `(COUNTED, 1, null)`; row 1 created with `failures = 1`, `try_id` = that uuid, `try_expires_at` about 30 s ahead; no audit row. `begin(null)`: `22023`. With an admin present: `(CLOSED, 0, null)` and **no row created** |
| S7 | Four tries each `begin` + commit + `unlock_failed` (fresh uuid each), then a 5th `begin` + `unlock_succeeded` | `begin` returns `COUNTED` with `failures` 1 to 4 and `unlock_failed` returns `(1, null)` to `(4, null)` with the count unchanged by the judge; the 5th `begin` returns `(COUNTED, 5, locked_until about +60 min)`; after `unlock_succeeded`: `failures = 0`, `locked_until` null, a ticket, no lease; 4 `UNLOCK_FAILED` rows, no `LOCKED`, no row for the success (4 wrong, then correct, unlocks) |
| S8 | Five tries each `begin` + `unlock_failed` | 5th `begin` returns `(COUNTED, 5, locked_until about +60 min)` and has already nulled the ticket columns and set the lock **before** any judge; 5th `unlock_failed` returns `(5, locked_until)` and writes two audit rows (`UNLOCK_FAILED` "5 of 5" and `LOCKED`, `after_json.lockedUntil` ending in `Z`); lease cleared |
| S9 | While locked | `begin` returns `(LOCKED, 5, locked_until)` and writes nothing (`updated_at` unchanged, count unchanged); `unlock_failed` and `unlock_succeeded` (any uuid) raise `SB004` (no live lease), never `SB002`; no row, no count change |
| S10 | Lock ended (time simulation) | `begin` returns `(COUNTED, 1, null)`: the run-out lock is cleared (count back to 0) and this try is the first of a new run |
| S11 | Lock cancels ticket | ticket set, then 5 tries: `ticket_status` is `(LOCKED, locked_until)`, and after the lock ends `INVALID` |
| S12 | New unlock replaces the ticket | old hashes `INVALID`, new `VALID` |
| S13 | `ticket_status` | `VALID` with matching hashes; `INVALID` for: swapped halves, one half wrong, null, empty, uppercase hex, 63 characters; `INVALID` after the 5 minutes (time simulation); `CLOSED` once an admin exists; `(INVALID, null)` for `ticket_status(null, null)` with a ticket open; `locked_until` set only with `LOCKED` |
| S14 | `register` with a valid ticket | `(CREATED, null)`; one admin (`active`, TOTP columns null, `email`/`full_name` as given); ticket columns null; one `ADMIN_BOOTSTRAP_ADMIN_CREATED` row with the id and name; **no audit column contains the email**; then `admin_login_lookup(email)` finds it with `totp_enrolled` false (the test `BootstrapFunctionsIT.s14` checks only this lookup; it does not call `admin_preauth_create`). Issuing the `ENROL` token is proven elsewhere, by `BootstrapRegisterIT` (login, then `ENROL`, then enrol, then verify). The register step itself calls neither function |
| S15 | `register` second time, same ticket | `(CLOSED, null)`; nothing written |
| S16 | `register` with a spent, expired, replaced, foreign, null ticket | `(INVALID, null)`; no admin; ticket unchanged |
| S17 | `register` while locked | `(LOCKED, locked_until)`; `locked_until` matches the row; the state row is unchanged (no count, no lease change) |
| S18 | `register` bad inputs: null id, uppercase email, no `@`, 255-character email, name with spaces at the ends, 121-character name, hash `x`, hash with 59 characters, request id over 100 | each `22023`; message contains no supplied value; nothing written |
| S19 | `register` when an admin exists (seeded, inactive, sample, or `ADMIN_DEV_SAMPLE`) | `(CLOSED, null)`; no `ADMIN_DEV_SAMPLE_REFUSED` row |
| S20 | Race: 10 sessions call `register` with the same valid ticket and different ids | exactly one `CREATED`, nine `CLOSED`, one admin, one `CREATED` audit row |
| S21 | Race: `register` against `admin_dev_create_sample_admin` | exactly one admin; the loser is refused/closed |
| S22 | Race: 10 sessions each run `begin(own uuid)` and commit at once, judging nothing | exactly one `COUNTED`, nine `BUSY`; `failures = 1`; the row holds the winner's `try_id`. Then the winner is judged (`unlock_failed`) and the next `begin` is `COUNTED` again. Variant: 10 sessions each loop `begin` + judge until they get `LOCKED`: `failures` never above 5, exactly 5 `COUNTED`, 5 `UNLOCK_FAILED` rows and one `LOCKED` row, the rest `LOCKED` |
| S23 | A session holds the row lock for 11 s (as `sahaj_migrator`: `select ... for update`), then `begin`, `unlock_failed`, `unlock_succeeded`, `register` each in turn | each raises `55P03` after about 10 s; nothing counted or changed. `ticket_status` in the same situation returns at once |
| S24 | Tenant bound (`set_config('app.tenant_id', '<uuid>', true)`) | all five functions raise `42501` |
| S25 | Check constraints as `sahaj_migrator` | `failures = 5` with `locked_until` null refused; `locked_until` set with `failures < 5` refused; ticket set while locked refused; only `ticket_hash` set refused; `id = 2` refused; uppercase hex refused; **`try_id` set with `try_expires_at` null refused, and the reverse refused; a lease together with a lock accepted** |
| S26 | `migrator` update of audit | refused by trigger (unchanged) |
| S27 | Production guard (below) | raises in a non-dev database holding the table, a function (either generation), or neither; the message names both features and the hint names the shared runbook |
| S28 | `docs/database/runbooks/admin-bootstrap-clear-lock.sql` | after S8: row clear **including `try_id` and `try_expires_at`**, one `ADMIN_BOOTSTRAP_LOCK_CLEARED` row; with only a live lease set (count 1): lease cleared, one audit row, and a judge of that try then raises `SB004` (R3-I12); second run: no new row; run without `-v ON_ERROR_STOP=1`: prints "Stopped", changes nothing; in a database not named `sahaj_dev`/`sahaj_test`: refuses; on a Revision 2 table without the lease columns: refuses with the message that names the callback |
| S29 | Extended cleanup runbook | on a Revision 3 database and on a Revision 2 database (old signatures): all functions and the table gone, definer has no INSERT on `platform_admin`; second run changes nothing; guard passes in a non-dev database afterwards |
| S30 | Search-path hijack: create `pg_temp.platform_admin` or a function in an earlier schema, then call | no effect |
| S31 | Judge with another `try_id`, with no lease, with a lease that ran out (time simulation), after an admin exists | `SB004` for the first three, `SB001` for the last; the transaction rolls back: count unchanged, lease unchanged, no audit row (R3-I7) |
| S32 | Unjudged try: `begin(u1)` and commit, judge nothing; move `try_expires_at` into the past; `begin(u2)` | `(COUNTED, 2, null)`, one `UNLOCK_UNJUDGED` row (`REFUSED`, `try-unjudged`, `ip` and `request_id` null, summary as the table above), lease now `u2`. Variant for the 5th try: lease of the 5th claim run out while the lock holds: `begin` returns `(LOCKED, 5, locked_until)` and one `UNJUDGED` row appears once; a second `begin` writes no further row (R3-I4, R3-I1) |
| S33 | `begin` while a live lease exists | `(BUSY, failures, null)`; `updated_at`, count, lease unchanged; no audit row (R3-I8) |
| S34 | The judge never changes the count | `unlock_failed` returns the same `failures` as the row held before it; count identical before and after; a second `unlock_failed` for the same `try_id` raises `SB004` (the lease is gone) |
| S35 | Callback re-run over a Revision 2 database (state table with 7 columns and 8 constraints, a row with a count and a lock, the five old function shapes; take the old file from git history or build it by hand) | old three shapes gone, `ticket_status` and `register` replaced (their result type was `text`), five new functions, table has the two lease columns and the ninth constraint, the row keeps its count, lock and ticket, the self-check passes; a second re-run changes nothing (R3-I11) |
| S36 | Forced failure on the wrong path (as `sahaj_migrator`: a temporary `BEFORE INSERT` trigger on `platform_admin_audit` that raises for `ADMIN_BOOTSTRAP_UNLOCK_FAILED`) | `begin` + commit, then `unlock_failed` raises: after the rollback the claim's count and lease are still there; repeat with the lease moved into the past: 5 tries reach the lock without any judge committing (R3-I1). Drop the trigger in `finally` |
| S37 | Forced failure on the correct path (temporary `BEFORE UPDATE` trigger on the state table that raises when `new.ticket_hash is not null`) | `unlock_succeeded` raises; the count from the claim stays (R3-I3). Drop the trigger in `finally` |
| S38 | `SB002` | no function body of the callback contains `SB002`; no test can make any function raise it |

## Production guard: the changed statements

Source of truth is `docs/database/ADR-008-guard.sql`; **the checks are unchanged** (checks 4 and 5 and the prefix `admin\_bootstrap\_%` already cover the new signatures, whatever their arguments). Revision 3 changes **wording only** (code review finding 17):
- the raise message is now `Dev-tools admin objects (sample admin or bootstrap registration) found in database "<name>". Run docs/database/runbooks/dev-tools-sample-admin-cleanup.sql as sahaj_migrator, then start again (ADR-008 R3, ADR-018).`;
- the hint now says that the runbook removes the objects of both dev-tools features and then gives the `psql` command;
- two header comment lines say the runbook removes both features.
The file is a byte copy source: place it unchanged over `backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql`. Still read-only, platform scope, skipped by the early `return` in `sahaj_dev`/`sahaj_test`, safe on a fresh database. QA T18 additions stay: only the table; only one function; neither (passes); names `sahaj_dev_staging`, `sahaj_test_prod` with the objects present (raise), `sahaj_dev`, `sahaj_test` (pass); the `TEST SUBSTITUTION POINT` line is unchanged. **`DevToolsGuardCallbackIT` line 138 asserts the old text `Dev-tools sample admin objects found in database "<name>"`; QA must update it to the new message** (a test file, not mine to edit).

## Cleanup runbook

`docs/database/runbooks/dev-tools-sample-admin-cleanup.sql` (Revision 3):
- pre-flight: the current user may drop the bootstrap functions (role `usage` of their owner) and the state table (owner); unchanged;
- step 1b: `drop function if exists` for the three Revision 3 signatures, the three Revision 2 signatures, and `ticket_status` and `register` (same argument list in both generations, so one statement each), then `drop table if exists platform.platform_admin_bootstrap_state` (no cascade, nothing depends on them). A database may hold either generation;
- step 2 (revoke all INSERT of the definer on `platform_admin`) already removes the four-column grant;
- self-check: no `admin_bootstrap_%` function whatever its signature, no state table. A shape not listed in step 1b would fail the run instead of staying.
The run still deletes no admin and no audit row, and a second run still changes nothing. In `sahaj_dev` it also forgets the PIN count and lock (the table is dropped); the next dev-tools start recreates everything with a clean count.

## Review by reading

Not executed. Points checked by reading against `V2026_11_15_1000` and `V2026_11_15_1010`: column names, types and checks of `platform_admin` (`ck_platform_admin_email`, `ck_platform_admin_full_name`, `ck_platform_admin_password_hash`, `ck_platform_admin_totp_all_or_none`, `uq_platform_admin_email`); audit columns and checks (section "Audit"); the definer's existing grants; the `CREATE` grant / owner / revoke pattern; the advisory key `202611050008` copied from `admin_dev_create_sample_admin`; the constraint names (63-character limit); the dollar-quote tags (`$pre$`, `$try$`, `$drop$`, `$fn$`, `$chk$`, `$clear$`, `$check$`; no `${`, so Flyway placeholders do nothing); `#variable_conflict use_column` plus qualified columns wherever an output column is named like a table column (`failures`, `locked_until`, `outcome`, `status`); every `RETURN QUERY` has explicit casts so its column types match the declared table; every grant, every `search_path`, every error path in the five functions; lock order; that the three table checks and the new fourth agree with every write path (claim: failures 1 to 4 with no lock, or 5 with lock, ticket null, lease set; judge failed: lease cleared only; judge succeeded: failures 0, no lock, ticket set, lease cleared; lock expiry: failures 0 and no lock; lease expiry: both lease columns null together; register: ticket cleared); that no function and no comment holds a PIN, a hash or a value.

Unverified until a run (fix forward if any fails):
- A `RETURNS TABLE` whose columns are named like table columns (`failures`, `locked_until`, `outcome`) with `#variable_conflict use_column`, and that `insert into platform.platform_admin_audit (..., outcome, ...)` and `on conflict (id)` are not treated as references to the output variables.
- Two `SET` clauses (`search_path`, `lock_timeout`) in one `CREATE FUNCTION`, and that the function-level `lock_timeout` is honoured for the advisory-lock wait in `register`.
- `pg_has_role(current_user, 'sahaj_admin_definer', 'set')` is accepted in PostgreSQL 16 (as in ADR-008).
- `ALTER FUNCTION ... OWNER TO`, `CREATE OR REPLACE` and `DROP FUNCTION` by `sahaj_migrator` on functions the definer already owns, on a re-run.
- `alter table ... add column if not exists` (two columns in one statement) and the conditional `add constraint` inside a `DO` block in a callback; `to_regprocedure(...)` with a text literal, `p.oid = to_regprocedure(...)` and `p.prorettype = 'text'::regtype` comparisons.
- `get stacked diagnostics ... constraint_name` returning `uq_platform_admin_email` for the email violation, and `RETURN QUERY` followed by `RETURN` inside an exception handler.
- The self-check's `proargnames` comparisons (that `proargnames` lists input arguments and `TABLE` columns in order), and `has_table_privilege(name, regclass, text)` with a `regclass` constant.
- Ordering of this callback against ADR-008's: both orders end in a valid state; QA should run both once.
- That a Flyway callback location holding a single `afterMigrate__` script runs on a migrate with nothing pending (ADR-008 relies on the same).

### What QA must watch on the first run, in order

1. **The callback itself fails first, if anywhere.** Read the Flyway error before any test result. The likely suspects are the list above; the self-check messages name the part that is wrong (`designed columns`, `designed constraints`, `Revision 3 signatures`, `not owned by sahaj_admin_definer ... missing lock_timeout`).
2. **Both generations.** Run once on a fresh database and once over a Revision 2 database (S35); the second must leave the count, lock and ticket as they were.
3. **The claim commits before the compare.** In S6 to S8 check from a second connection, right after `begin` returns and before any judge, that `failures`, `locked_until` and `try_id` are already visible (the claim is the only write the limit depends on).
4. **No `SB002` and no count change in a judge.** S34 and S38.
5. **BUSY, not a wait.** S22 and S33: the ten sessions must return within milliseconds, not queue for 10 s; if they wait, the row lock is being held past the commit (a Java transaction problem, not SQL).
6. **UNJUDGED while locked** (S32 variant): one row, written once, `ip` and `request_id` null; the timing is decided (ADR-018 D7, Revision 3 (f); see "UNJUDGED timing").
7. **Lock expiry together with a live lease** (time simulation on both): `begin` clears the lock, then answers `BUSY`; the count restarts at 0.
8. **Time simulation needs the check constraints**: a lock set by hand needs `failures = 5` and null ticket; the lease columns are set or cleared together.
9. **Runbooks** (S28, S29) run with psql 13 or later and `-v ON_ERROR_STOP=1`; the clear-lock runbook refuses a Revision 2 table.
10. **Guard message** (S27): the existing `DevToolsGuardCallbackIT` assertion on the old text will fail until QA updates it.

## Requests for other owners

- **java-developer:** place the SQL files **byte for byte** (paths in the handoff below), move the callback from `db/dev-tools-bootstrap` to `db/bootstrap-dev-tools` (the old folder must be deleted from `backend/src/devtools/resources/db/`), and update `BootstrapFlywayCustomizer`, `BootstrapSqlPlacementTest`, `BootstrapConditionsTest`, `DevToolsAbsentFromMainTest`, the `application.yml` comment and `JdbcBootstrapRepository` to the new signatures and result shapes exactly as in "Error mapping" and "Transactions". `begin` returns three columns `(outcome, failures, locked_until)`, `ticket_status` returns `(status, locked_until)` and `register` returns `(outcome, locked_until)`; each is a row set, read with a query, not a scalar.
- **qa-engineer:** cases S1 to S38 and the tests R3-I1 to R3-I12 of ADR-018, the T18 additions, both callback orders, the update of `DevToolsGuardCallbackIT` line 138 to the new guard message, and the folder-name checks in `PackagedJarDevToolsIT`, `PackagedJarDevToolsBuildIT`, `BootstrapCallbackIT`, `BootstrapCleanupRunbookIT`.
- **architect:** nothing. The `UNJUDGED` timing is decided in ADR-018 D7 and Revision 3 (f): the `ADMIN_BOOTSTRAP_UNLOCK_UNJUDGED` row is written once, at the first `begin` after the 30-second lease runs out, whether or not the lock is in force. The 429 / `55000` request of Revision 2 is also settled and removed.
- **human:** role `sahaj_admin_definer` in `scripts/db/init.sql` and `scripts/setup/create-database.mjs`, activation and renumbering of the parked admin migrations, `.env.example` keys (H-BOOT-7), and the psql 13+ requirement for the runbooks' `\if :{?ON_ERROR_STOP}` check.

## Handoff · SAH-42 · db-engineer → java-developer
Status: DONE (documents and SQL text only; written by reading; NOT YET EXECUTED; the first run is the QA gate)
Mockup screen: none for the database (page `/admin/register` is DEC-013 item 16)
Files changed (all under `docs/database/`):
- `docs/database/SAH-42.md` — this design note (rewritten for ADR-018 Revision 3; Revision 5 wording: register no longer calls `admin_preauth_create`)
- `docs/database/SAH-42-admin-bootstrap.sql` — callback body (Revision 5: comment lines 70-72 only, executable statements unchanged); copy byte for byte to `backend/src/devtools/resources/db/bootstrap-dev-tools/afterMigrate__dev_tools_admin_bootstrap.sql`, and delete the old `backend/src/devtools/resources/db/dev-tools-bootstrap/` folder
- `docs/database/ADR-008-guard.sql` — message and hint wording; copy byte for byte over `backend/src/main/resources/db/guard/afterMigrate__platform_dev_tools_absent_guard.sql`
- `docs/database/runbooks/admin-bootstrap-clear-lock.sql` — also clears the lease; human runbook, not copied
- `docs/database/runbooks/dev-tools-sample-admin-cleanup.sql` — drops new and old signatures; human runbook, not copied
Validation: `node scripts/validate.mjs db` not run (nothing under `db/migration` changed; no database may be touched; human prerequisites open).
Next agents: java-developer (angular-developer: nothing for the database part; the page waits for the DEC-013 item 16 sign-off).
