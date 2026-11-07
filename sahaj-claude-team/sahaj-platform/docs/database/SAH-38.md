# SAH-38 · Database design: colleague PIN hardening (HARD-3)

Migration (forward-only, additive; no earlier migration is edited, nothing dropped or renamed): `V2026_12_01_1000__platform_staff_pin_colleague_tries.sql` (latest before it: `V2026_11_29_1000__ipd_mar_dose_administration.sql`; no newer file existed when this was written). Decision record: ADR-014 (D2, D3, D4, D6, D8, D9, D12 and "Data needed"), which amends ADR-010 D5, D13 and ADR-011 D7. Mockup screens: Nursing sheet → Medicines tab and Give sheet (`sfGive`, `sf-wit`, `sf-pin`); Clinic setup → Team and access, panel Staff (`f-staff`). Everything user-visible here is NEW (not in the mockup) and signed off through the SAH-38 story register.

Second migration (QA finding B3, section 7 below): `V2026_12_02_1000__platform_staff_pin_colleague_event_kind_indexes.sql` adds two indexes that carry `kind`. Section 7 also changes the time bound in three statements of section 4 (volatile `clock_timestamp()` replaced by `statement_timestamp()` or a bound value).

Changes of the first migration, nothing else: two columns and three checks on `platform.staff_pin`, two columns added to its update grant, `platform.guard_staff_pin_update()` replaced (`create or replace`, same trigger `trg_staff_pin_guard_update`), one new table `platform.staff_pin_colleague_event` with three indexes, two policies, three triggers and one insert-guard function. `platform.audit_event`, `platform.membership`, `ipd.*` (including `ipd.clinical_event`) are unchanged (the two event texts of ADR-014 D11 are application templates).

No existing row is rewritten or lost: the new columns take the default `false` for every row, and the three new checks hold for that state.

## 1. platform.staff_pin (additions)

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `run_has_colleague_try` | boolean | no | `false` | True when at least one wrong try in the current run (the `failed_count` run) came from a colleague check. |
| `lock_by_colleagues` | boolean | no | `false` | True when the current or last lock was caused by a run with a colleague try. |

Checks:
- `ck_staff_pin_run_colleague`: `(not run_has_colleague_try) or failed_count > 0`.
- `ck_staff_pin_lock_by_colleagues`: `(not lock_by_colleagues) or locked_until is not null`.
- `ck_staff_pin_colleague_flags_when_off`: `pin_hash is not null or (not run_has_colleague_try and not lock_by_colleagues)`.

Grant: `update (run_has_colleague_try, lock_by_colleagues)` to `sahaj_app`, in addition to the 13 columns of SAH-31 (15 updatable columns in total). Insert grant unchanged (5 columns; both flags take their default on a first PIN). No new index (one row per membership).

### Guard `platform.guard_staff_pin_update()` (SECURITY INVOKER, `search_path = pg_catalog`, revoked from public and `sahaj_app`, SQLSTATE 23514, every message contains "staff pin")
Every SAH-31 rule is kept (identity, version + 1, pepper, switch-off kinds, new PIN, lock/wrong/right, 24-hour window, "now" = `clock_timestamp()`). New rules, with the ADR-014 letter:

| Rule | Where | Requirement |
|---|---|---|
| (b) | every update | `failed_count = 0` means `run_has_colleague_try = false` |
| (a) | wrong try / right PIN (no lock) | the run flag may go false → true or stay; true → false only when `failed_count` returns to 0 |
| (c) lock, colleague-caused | lock (5th wrong try: `failed_count` 4 → 0 with `lock_count` changed or `locked_until` newly set) with `lock_by_colleagues = true` | `lock_count` equal to old and `lock_window_started_at` equal to old; `locked_until` stamped `now + 15 minutes`; the "third lock must switch off" rule does not apply; no restart of the window |
| (c) lock, own | same, `lock_by_colleagues = false` | refused when `old.run_has_colleague_try` is true ("must be marked as caused by colleagues"); otherwise the SAH-31 count rules apply unchanged (+1 inside the window, restart at 1, third lock refused) and the flag is cleared by this lock |
| (d) | switch-off `TOO_MANY_LOCKS` | additionally requires `old.run_has_colleague_try = false` |
| (e) | new PIN, owner reset, switch-off | both flags false in the new row |
| (f) | wrong try / right PIN | `lock_by_colleagues` unchanged, except true → false when the same update sets `locked_until` to null (clearing an expired lock) |
| (g) | all | no other flag transition is possible: every branch above is exhaustive, and the three table checks are the second line |

(Tightened by section 8, V2026_12_03_1000: a lock with `lock_by_colleagues = true` now needs proof of a colleague try, or the guard answers 23514.) The guard cannot see who typed the fifth try. The service decides: a colleague's fifth try is a colleague lock (flag true); an own fifth try with `run_has_colleague_try` true is also a colleague lock (mixed run); an own fifth try with the flag false is an own-session lock.

## 2. platform.staff_pin_colleague_event (tenant table, append-only)

| Column | Type | Null | Default | Rule |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | `pk_staff_pin_colleague_event` |
| `tenant_id` | uuid | no | `platform.current_tenant()` | `fk_staff_pin_colleague_event_tenant` → `platform.tenant (id)` |
| `membership_id` | uuid | no | | the PIN's owner; `fk_staff_pin_colleague_event_pin (tenant_id, membership_id)` → `platform.staff_pin (tenant_id, membership_id)` (`uq_staff_pin_tenant_membership`) |
| `by_membership_id` | uuid | yes | | the caller; `fk_staff_pin_colleague_event_by (tenant_id, by_membership_id)` → `platform.membership (tenant_id, id)` (`uq_membership_tenant_id`) |
| `kind` | text | no | | `ck_staff_pin_colleague_event_kind`: WRONG, LOCKED, RIGHT_AFTER_WRONG, NOTICE_SEEN |
| `seen_through` | timestamptz | yes | | NOTICE_SEEN only |
| `at` | timestamptz | no | `clock_timestamp()` | stamped by the insert guard; not insertable |
| `created_by` | uuid | no | | user id of the signed-in session (no FK, user-id convention) |

Row checks: `ck_staff_pin_colleague_event_by_kind` `(kind in ('WRONG','RIGHT_AFTER_WRONG')) = (by_membership_id is not null)`; `ck_staff_pin_colleague_event_by_not_self` `by_membership_id is null or by_membership_id <> membership_id`; `ck_staff_pin_colleague_event_seen_kind` `(kind = 'NOTICE_SEEN') = (seen_through is not null)`. No `updated_*` and no `version`.

| kind | `by_membership_id` | `seen_through` |
|---|---|---|
| `WRONG` | caller | null |
| `LOCKED` | null | null |
| `RIGHT_AFTER_WRONG` | caller | null |
| `NOTICE_SEEN` | null | newest try the notice showed (capped at the DB clock by the guard) |

### Indexes and the query each serves (all lead with `tenant_id`)
| Index | Serves |
|---|---|
| `ix_staff_pin_colleague_event_pair (tenant_id, membership_id, by_membership_id, at)` (first version; superseded by `..._pair_kind`, section 7) | pair cap count (`kind = 'WRONG'`, `at > window_start`) and the pair's latest `RIGHT_AFTER_WRONG`, both under the owner's `staff_pin ... for update`; a pair has only a few rows in a week, so the `kind` filter is applied on the index range |
| `ix_staff_pin_colleague_event_member (tenant_id, membership_id, at)` | notice count (WRONG after the latest NOTICE_SEEN), latest NOTICE_SEEN, owner history (last 7 days, latest first); child side of `fk_staff_pin_colleague_event_pin` |
| partial `ix_staff_pin_colleague_event_wrong_7d (tenant_id, at) where kind = 'WRONG'` | Team page: grouped `WRONG` counts of the last 7 days per membership for the clinic |

There is no index leading `(tenant_id, by_membership_id)` for `fk_staff_pin_colleague_event_by`: memberships are never deleted and their key never changes, so the parent side never scans the child table.

### Tenant scope and RLS
`tenant_id` not null; ENABLE and FORCE; `p_staff_pin_colleague_event_select` (select) and `p_staff_pin_colleague_event_insert` (insert, with check), both `to sahaj_app` and `tenant_id = platform.current_tenant()` only. No update or delete policy. With no tenant bound nothing is visible and an insert fails. A cross-clinic id fails the composite FKs (23503) and is invisible to reads.

### Grants (`sahaj_app`)
`select`; `insert (membership_id, by_membership_id, kind, seen_through, created_by)`. `id`, `tenant_id`, `at` always default (naming them gives 42501). No update, delete, truncate, references or trigger. Nothing for public.

### Triggers
- `trg_staff_pin_colleague_event_no_update_delete` (before update or delete, each row) and `trg_staff_pin_colleague_event_no_truncate` (before truncate) → `platform.deny_audit_mutation()`: 42501 for every role, the owner included; message "platform.staff_pin_colleague_event is append-only (UPDATE refused)".
- `trg_staff_pin_colleague_event_guard_insert` → `platform.guard_staff_pin_colleague_event_insert()` (SECURITY INVOKER, `search_path = pg_catalog`, revoked from public and `sahaj_app`, SQLSTATE 23514, message contains "colleague event"): `new.at := clock_timestamp()`; for NOTICE_SEEN `new.seen_through := least(new.seen_through, clock_timestamp())`.

## 3. Expected rows, retention, compatibility
- Rows: rare. Extreme planning figure 100 hospitals × 50 nurses × 10 events a week ≈ 2.6 million narrow rows a year across all clinics (about 100 bytes plus three index entries). A typical OPD clinic has a handful a year. No partitioning (far below 50 million). `staff_pin` stays at most one row per membership.
- Retention: kept (no delete grant, triggers refuse update, delete, truncate). The app reads 7 days (pair, owner) and "since the last Got it" (notice). A later purge of rows older than 90 days by `sahaj_migrator` (the trigger would have to be disabled by that migration; it is not built) is allowed by ADR-014 D12.
- Privacy: ids, a kind and times only; never a PIN, hash, name or mobile number; names are looked up on screen only.
- Compatibility: additive. The previous app version never writes the new flags (they stay false) and never touches the event table; every statement of SAH-31 still passes the replaced guard while both flags are false, which they are for every old row. After the new app version has run, the SAH-31 statements below need the flag changes listed in "Statements" or the guard refuses them. Rollback before any event exists: a new forward migration that drops the event table, its guard function, the three checks and the two columns, and restores the SAH-31 guard.

## 4. Statements for the java-developer (one READ COMMITTED platform transaction, tenant bound; the colleague check follows ADR-014 D3)

Read and lock (adds the two flags and the clock; also the read for `GET /me/pin` without `for update`):
```sql
select pin_hash, pepper_version, failed_count, locked_until, lock_count, lock_window_started_at,
       set_at, not_set_reason, reset_at, run_has_colleague_try, lock_by_colleagues, version,
       clock_timestamp() as db_now
  from platform.staff_pin where tenant_id = :t and membership_id = :b for update
```

Pair cap (step 6, after the `for update`; `:windowStart` = the greatest of `statement_timestamp() - interval '7 days'`, `set_at`, and the latest RIGHT_AFTER_WRONG below, computed in one statement):
```sql
select count(*) from platform.staff_pin_colleague_event
 where tenant_id = :t and membership_id = :b and by_membership_id = :a
   and kind = 'WRONG'
   and at > greatest(statement_timestamp() - interval '7 days', :setAt,
         coalesce((select max(at) from platform.staff_pin_colleague_event
                    where tenant_id = :t and membership_id = :b and by_membership_id = :a
                      and kind = 'RIGHT_AFTER_WRONG'), '-infinity'::timestamptz))
```
The pair cap (COUNT_PAIR) and the two rolling 7-day team reads (WRONG_7D_BY_MEMBER and WRONG_7D_OF_MEMBER) take their lower bound from `statement_timestamp()`, not `now()` or `clock_timestamp()`, so the planner can use `at` as an Index Cond on `ix_staff_pin_colleague_event_pair_kind` and `ix_staff_pin_colleague_event_wrong_7d`. The pair-cap count runs after the colleague's `staff_pin` row lock (`FIND_FOR_UPDATE`) taken by an earlier statement of the same transaction. The window start is the latest of 7 days ago, the PIN `set_at` and the pair's last `RIGHT_AFTER_WRONG`. Stored times and lock decisions (`at` defaults, `locked_until`, and the history as-of time) stay on `clock_timestamp()`.

Count ≥ 5 → `PAIR_LIMIT`. Also use `at > :windowStart` with `:windowStart` computed by a second small select if clearer. `:setAt` is `staff_pin.set_at` (not null while a PIN is set).

Inserts (never send `id`, `tenant_id`, `at`; they come from defaults and the guard):
```sql
-- wrong colleague try          (caller :a = by_membership_id)
insert into platform.staff_pin_colleague_event (membership_id, by_membership_id, kind, created_by)
values (:b, :a, 'WRONG', :userId) returning at;
-- lock caused by colleagues
insert into platform.staff_pin_colleague_event (membership_id, kind, created_by) values (:b, 'LOCKED', :userId);
-- right PIN after wrong tries (only when the pair has at least one WRONG after window start)
insert into platform.staff_pin_colleague_event (membership_id, by_membership_id, kind, created_by)
values (:b, :a, 'RIGHT_AFTER_WRONG', :userId);
-- the owner's "Got it" (only when a WRONG row lies after the current marker and up to :seenThrough)
insert into platform.staff_pin_colleague_event (membership_id, kind, seen_through, created_by)
values (:me, 'NOTICE_SEEN', :seenThrough, :userId) returning seen_through;
```

Notice count (owner of the PIN reads own data; `:marker` = latest NOTICE_SEEN `seen_through`, `-infinity` when none):
```sql
select max(seen_through) from platform.staff_pin_colleague_event
 where tenant_id = :t and membership_id = :me and kind = 'NOTICE_SEEN';

select count(*), max(at) from platform.staff_pin_colleague_event
 where tenant_id = :t and membership_id = :me and kind = 'WRONG' and at > :marker;
```
Team page (OWNER only, one grouped query per page; uses the partial index; the 7-day bound is `statement_timestamp()`, see the note under the pair cap):
```sql
select membership_id, count(*) from platform.staff_pin_colleague_event
 where tenant_id = :t and kind = 'WRONG' and at > statement_timestamp() - interval '7 days'
 group by membership_id
```
Owner history of one person (latest first, limit 101 to know `truncated`; the 7-day bound is `statement_timestamp()` as above, while the history as-of time stays on `clock_timestamp()`):
```sql
select kind, at, by_membership_id from platform.staff_pin_colleague_event
 where tenant_id = :t and membership_id = :id and kind <> 'NOTICE_SEEN'
   and at > statement_timestamp() - interval '7 days'
 order by at desc limit 101
```
Group WRONG rows by caller and IST day in Java (or `date_trunc('day', at at time zone 'Asia/Kolkata')`). `at` and `seen_through` return as `OffsetDateTime`. Bind `:seenThrough` with a UTC offset; the guard caps it at the DB clock.

### staff_pin statements that change (each must keep the flags legal, or the guard answers 23514)
- Wrong try, own check: unchanged (`failed_count + 1`); the flag stays as it is.
- Wrong try, colleague check: `set failed_count = failed_count + 1, run_has_colleague_try = true, updated_by = :u, version = version + 1`.
- Right PIN (own or colleague, only when `failed_count` > 0): `set failed_count = 0, run_has_colleague_try = false, updated_by = :u, version = version + 1`. Without the flag reset the guard refuses it when the run had a colleague try.
- Colleague lock (fifth wrong try of a run, colleague check, or own 5th try when the flag is true): `set failed_count = 0, run_has_colleague_try = false, lock_by_colleagues = true, locked_until = clock_timestamp() + interval '15 minutes', updated_by = :u, version = version + 1`. Do not touch `lock_count` or `lock_window_started_at`. Then insert the `LOCKED` event and the audit row.
- Own-session lock (own 5th try, flag false): the SAH-31 statement plus `run_has_colleague_try = false, lock_by_colleagues = false`. Third own lock switch-off: the SAH-31 statement plus both flags false, and only when `run_has_colleague_try` is false.
- New PIN, owner reset, access removal: the SAH-31 statements plus `run_has_colleague_try = false, lock_by_colleagues = false`.
- A wrong try or right PIN that clears an expired `locked_until` to null must also set `lock_by_colleagues = false` (the check requires it); one that keeps it may leave the flag.
- A wrong try after an expired lock where `lock_by_colleagues` is still true leaves it true. It stays true until the next own-run lock, a new PIN, a reset, a switch-off, or the clearing of `locked_until`.

### Error classes (a hit on any of these is a code bug: 500, full rollback, never swallowed; the only expected failure is a database timeout, which becomes `CHECK_FAILED` per ADR-014 D5)
| SQLSTATE | Constraint or message | Meaning |
|---|---|---|
| 23514 | message contains "staff pin" (guard) | a flag transition or lock kind the guard refuses (see tables above) |
| 23514 | `ck_staff_pin_run_colleague`, `ck_staff_pin_lock_by_colleagues`, `ck_staff_pin_colleague_flags_when_off` | flag set in a state the checks forbid |
| 23514 | message contains "colleague event" | NOTICE_SEEN without `seen_through` (also caught by `ck_staff_pin_colleague_event_seen_kind`) |
| 23514 | `ck_staff_pin_colleague_event_*` | kind/caller/`seen_through` combination wrong; caller equal to owner |
| 23503 | `fk_staff_pin_colleague_event_pin` or `_by` | owner has no PIN row, or an id of another clinic or an unknown membership |
| 23502 | not-null | a required value null (also: no tenant bound) |
| 42501 | permission denied / "append-only" | a column outside the grant named, or an update, delete or truncate tried |
| 55P03 / 57014 | lock timeout / statement timeout | `set local lock_timeout = '3s'` / `statement_timeout = '5s'`: the expected fail-closed case |

## 5. QA test list (real database, as `sahaj_app` with a bound tenant, and as the table owner where stated)
Set-up: clinics A and B, each with ACTIVE memberships (nurses N1, N2, N3 per clinic) and a PIN row for N1 and N2. Hashes are any valid 60-character BCrypt strings. States the app cannot reach (expired lock, old window, `lock_count` 2) are written by the owner in one transaction with `alter table platform.staff_pin disable trigger trg_staff_pin_guard_update` first and `enable trigger` before commit. Owner sessions lift RLS in a rolled-back transaction as the other ITs do.

Guard, positive:
1. Colleague wrong tries: from `failed_count` 0, `failed_count + 1` with `run_has_colleague_try = true` four times is accepted; the flag stays true. An own wrong try in the middle (flag untouched) keeps it true.
2. Colleague lock: from `failed_count` 4 and flag true or false, the colleague-lock statement is accepted: `failed_count` 0, flag false, `lock_by_colleagues` true, `lock_count` and `lock_window_started_at` equal to before (also from `lock_count` 2 inside the window and from 0), `locked_until` about 15 minutes ahead on the DB clock even when the statement sent a day ahead.
3. Many colleague locks never switch off: repeat 1 and 2 five times (moving `locked_until` into the past between runs as owner); `lock_count` stays, the PIN is never off.
4. Own lock after a colleague lock: with `lock_by_colleagues` true and the lock expired, an own-session lock with flags false gives `lock_by_colleagues` false and the SAH-31 count (+1 / restart at 1).
5. Third own lock: `lock_count` 2, `failed_count` 4, window under 24 hours, flag false: the switch-off is accepted with both flags false.
6. Right PIN after a colleague try: `failed_count` 0 with `run_has_colleague_try` false is accepted; a new PIN, owner reset and the switch-off with both flags false are accepted from a state where `lock_by_colleagues` is true.
7. Expired colleague lock cleared: `locked_until` set to null with `lock_by_colleagues` false is accepted; kept expired with the flag true is also accepted on a wrong try.
8. Existing rows after the migration: every pre-migration row has both flags false; the SAH-31 statements pass unchanged on them (compatibility).

Guard, negative (each 23514; the row is unchanged afterwards):
9. `run_has_colleague_try` true with `failed_count` 0 (guard (b); and with the guard disabled the check `ck_staff_pin_run_colleague`).
10. Run flag true → false while `failed_count` rises or stays above 0 (guard (a)).
11. Colleague lock that changes `lock_count`, or `lock_window_started_at`, or has `failed_count` not 4 → 0.
12. Own-type lock (`lock_by_colleagues` false) when `old.run_has_colleague_try` is true.
13. Switch-off `TOO_MANY_LOCKS` when `old.run_has_colleague_try` is true; switch-off, owner reset or new PIN with either flag true.
14. `lock_by_colleagues` false → true on a wrong try or right PIN (no lock); true → false while `locked_until` stays non-null on a wrong try.
15. Checks with the guard disabled as owner: `ck_staff_pin_lock_by_colleagues` (flag true with null `locked_until`), `ck_staff_pin_colleague_flags_when_off` (null hash with either flag true).
16. All SAH-31 negatives (version, identity, pepper, jumps, window, third lock refusal, locked row) still hold (the SAH-31 list items 10 to 15 re-run).

Event table:
17. Insert each kind with the statements above: WRONG, LOCKED, RIGHT_AFTER_WRONG, NOTICE_SEEN accepted; `id`, `tenant_id`, `at` defaulted; two rows in one transaction get increasing `at`.
18. `at` supplied in an insert: 42501 (not in the grant). `seen_through` one hour in the future: stored as at most the DB clock (guard). `seen_through` in the past stays as sent.
19. Checks: WRONG without `by_membership_id`; LOCKED or NOTICE_SEEN with `by_membership_id`; NOTICE_SEEN without `seen_through`; WRONG with `seen_through`; `by_membership_id` equal to `membership_id`; kind `OTHER`: 23514 with the matching constraint name.
20. Composite FK cross-tenant refusal: in clinic A's session, `membership_id` of a clinic B membership, `by_membership_id` of clinic B, a random uuid, and a `membership_id` with no `staff_pin` row: 23503 (`fk_staff_pin_colleague_event_pin` or `_by`). As owner, `tenant_id` = B with A's membership ids: 23503. `tenant_id` named by `sahaj_app`: 42501.
21. RLS: A cannot select B's rows; with no tenant bound nothing is visible and an insert fails (23502 or 42501); an insert with another tenant's `tenant_id` as owner under FORCE is refused for the `sahaj_app` policy check.
22. Append-only: `update`, `delete`, `truncate` as `sahaj_app`: 42501 privilege; as the owner: 42501 from the trigger ("append-only"); rows unchanged, including `update ... set kind = kind`.
23. Grants catalog: `sahaj_app` has select and insert on exactly the five columns; no update, delete, truncate, references, trigger; nothing for public; `has_column_privilege` false for `at`, `id`, `tenant_id` insert. `sahaj_app` update columns on `platform.staff_pin` are exactly the 15.
24. Catalog: RLS enabled and forced; exactly two policies (`r`, `a`), both `to sahaj_app`, on `platform.current_tenant()` only (the `RowLevelSecurityCatalogIT` rule); three triggers enabled; `guard_staff_pin_colleague_event_insert()` and `guard_staff_pin_update()` are SECURITY INVOKER with `search_path=pg_catalog` in `proconfig`, execute revoked from public and `sahaj_app`; the three indexes exist with the columns above, the partial one with predicate `kind = 'WRONG'`; table and column comments present and free of a PIN or hash.
25. Query plans (about 1 million event rows over 200 clinics, `analyze`): the pair-cap count and the latest-RIGHT_AFTER_WRONG probe use `ix_staff_pin_colleague_event_pair` with `tenant_id`, `membership_id`, `by_membership_id` as index conditions (restart-proof: the answer comes from the table alone, with no state in the app); the notice count and latest NOTICE_SEEN and the owner history use `ix_staff_pin_colleague_event_member`; the Team grouped count uses the partial index `ix_staff_pin_colleague_event_wrong_7d` (an index or bitmap scan, no sequential scan); the `Index Cond` for `at` appears on each. (Superseded in part by section 7: the pair statements use `ix_staff_pin_colleague_event_pair_kind`, and the 7-day bounds use `statement_timestamp()`.)
26. Restart-proof cap: write 5 WRONG rows for a pair in connection 1, commit; in a new connection (fresh app state) the count is 5; rows 8 days old (written as owner with the insert guard disabled, or via `alter table ... disable trigger trg_staff_pin_colleague_event_guard_insert`) are not counted; rows before the latest RIGHT_AFTER_WRONG or before `staff_pin.set_at` are not counted.
27. Concurrency (two connections): two parallel colleague checks on one owner row, each `select ... for update` then the count, insert and update: the second waits and sees the first; five committed WRONG rows then make the sixth `PAIR_LIMIT`; both parallel wrong tries are counted (`failed_count` +2, two WRONG rows). A NOTICE_SEEN insert by the owner while a colleague check holds `for update` waits for the lock (key-share) without error and succeeds after.
28. Forced timeout: `set local lock_timeout = '1ms'` against a held `for update` gives 55P03 and the transaction rolls back with no event row and no `staff_pin` change.
29. Flyway: the migration applies on a database that already has `V2026_11_29_1000` and from scratch (`clean` in `sahaj_test`); re-running `node scripts/validate.mjs db` is green.

## 6. Notes for the owners of other files
- java-developer: the SAH-31 statements in `docs/database/SAH-31-pin.md` section "Statements" stay valid only while both flags are false; use the flag changes in section 4 above for right PIN, new PIN, reset, access removal, switch-off and every lock.
- qa-engineer: `RowLevelSecurityCatalogIT` and any list of tables with append-only triggers need `platform.staff_pin_colleague_event`; any test that lists the update-grant columns of `platform.staff_pin` (13 in SAH-31) now expects 15.
- No change is needed in `docs/database/SAH-31-pin.md` (history is kept); this note supersedes it where they differ.

## 7. Index change after QA query plans (finding B3) - `V2026_12_02_1000__platform_staff_pin_colleague_event_kind_indexes.sql`

Forward-only and additive: two `create index if not exists` and two index comments. No data change, no drop, no grant change (the table is owned by `sahaj_migrator`, and an index needs no grant). Plain `create index`, not `concurrently`: the table is narrow and rare (section 3) and the build is short.

### What QA measured (`backend/target/qa-plans/plans.txt`, rows generated and rolled back, about 260,000 event rows)
- Latest `RIGHT_AFTER_WRONG` of a pair: `Index Scan Backward using ix_staff_pin_colleague_event_pair`, `Index Cond: (tenant_id, membership_id, by_membership_id, at IS NOT NULL)`, `Filter: kind = 'RIGHT_AFTER_WRONG'`, `Rows Removed by Filter: 12814`, 13,120 buffer hits to return 0 rows.
- Pair-cap count: `Bitmap Heap Scan`, index condition only on the first three columns, `Filter: (kind = 'WRONG') AND (at > GREATEST((clock_timestamp() - '7 days'), ...))`, `Rows Removed by Filter: 12565` for 249 rows kept; 18.8 ms in the saved plan (QA also reported 47 to 55 ms on a 12.8k-row pair and 30 to 67 ms on a 50k-row person).
- Latest `NOTICE_SEEN`: `Parallel Seq Scan`, `Filter: tenant_id, membership_id, kind = 'NOTICE_SEEN'`, `Rows Removed by Filter: 83619`, 175.8 ms.
- Notice count: `ix_staff_pin_colleague_event_member` with `at` as `Index Cond`, but `Filter: kind = 'WRONG'`, `Rows Removed by Filter: 2741`.
- Owner history: bitmap scan on the member index, `Index Cond` on `tenant_id, membership_id` only; `Filter: (kind <> 'NOTICE_SEEN') AND (at > (clock_timestamp() - '7 days'))`, `Rows Removed by Filter: 49271`.
- Team page: with `clock_timestamp()` in the statement, `Parallel Seq Scan` (74.0 ms); the same statement with `now()` uses the partial index with `Index Cond: (tenant_id, at > now() - '7 days')` (2.8 ms).

### Two causes, two fixes
1. Missing `kind`: the two older indexes end at `at`, so `kind` can only be a filter. Fix: the two new indexes below.
2. Volatile time function. `clock_timestamp()` is volatile, and PostgreSQL never uses a volatile expression as an index condition. That is why `at` is a `Filter` in every plan above that contains `clock_timestamp()`, and an `Index Cond` in every plan that contains `now()` or a bound value (the notice count, and the Team-page plan with `now()`). Adding index columns cannot fix this. The statements in section 4 now use `statement_timestamp()` for the 7-day bound (pair cap, Team page, owner history). `statement_timestamp()` is stable, so it is a legal index condition; it is the start of the current statement, so it is never earlier than the row lock taken by an earlier statement of the same transaction. A value read earlier in the same transaction and bound as a parameter (as `JdbcStaffPinColleagueEventRepository` already does with `:asOf` and `:after`) is equally fine. The pair cap (COUNT_PAIR) and the two rolling 7-day team reads (WRONG_7D_BY_MEMBER and WRONG_7D_OF_MEMBER) take their lower bound from `statement_timestamp()`, not `now()` or `clock_timestamp()`, so the planner can use `at` as an Index Cond on `ix_staff_pin_colleague_event_pair_kind` and `ix_staff_pin_colleague_event_wrong_7d`. The pair-cap count runs after the colleague's `staff_pin` row lock (`FIND_FOR_UPDATE`) taken by an earlier statement of the same transaction. The window start is the latest of 7 days ago, the PIN `set_at` and the pair's last `RIGHT_AFTER_WRONG`. Stored times and lock decisions (`at` defaults, `locked_until`, and the history as-of time) stay on `clock_timestamp()`. The java-developer's final code uses this form in `COUNT_PAIR`, `WRONG_7D_BY_MEMBER` and `WRONG_7D_OF_MEMBER`.

### New indexes
| Index | Columns | Serves | Expected plan |
|---|---|---|---|
| `ix_staff_pin_colleague_event_pair_kind` | `(tenant_id, membership_id, by_membership_id, kind, at)` | (a) pair-cap `count(*)` with `kind = 'WRONG'` and `at > :windowStart`; (b) the pair's `max(at)` with `kind = 'RIGHT_AFTER_WRONG'` | (a) range scan, all five columns in `Index Cond`; may be index-only once vacuumed (append-only table). (b) one backward step at the end of the range, no `Rows Removed by Filter`. |
| `ix_staff_pin_colleague_event_member_kind` | `(tenant_id, membership_id, kind, at)` | (c) `max(seen_through)` with `kind = 'NOTICE_SEEN'`; (d) notice count `count(*), max(at)` with `kind = 'WRONG'` and `at > :marker`; (e) `WRONG_7D_OF_MEMBER`, `EXISTS_WRONG_BETWEEN`, `HISTORY_WRONG_COUNT` (`kind = 'WRONG'` with an `at` range) | `tenant_id, membership_id, kind` (and `at` where a bound exists) in `Index Cond`; (c) reads only that person's few `NOTICE_SEEN` rows (one heap fetch each for `seen_through`) instead of a sequential scan. |

Kept, with the reason:
- `ix_staff_pin_colleague_event_wrong_7d` (partial): Team page; correct once the statement uses `statement_timestamp()`.
- `ix_staff_pin_colleague_event_member (tenant_id, membership_id, at)`: still needed. The owner history uses `kind <> 'NOTICE_SEEN'`, which is not a btree condition, so a `kind` column ahead of `at` cannot bound it; this index gives `tenant_id, membership_id, at` as `Index Cond` (with a bound `:asOf` or `statement_timestamp()`), and `kind <> ...` is a cheap filter on at most 7 days of rows.
- `ix_staff_pin_colleague_event_pair (tenant_id, membership_id, by_membership_id, at)`: now redundant. Every pair statement names `kind`, so `..._pair_kind` serves all of them. It is kept (dropping an index on an existing table is a human decision; flagged in the handoff). Dropping it later saves one index entry per insert and the disk space. If the history statement is later written as `kind in ('WRONG', 'LOCKED', 'RIGHT_AFTER_WRONG')`, `..._member` could go too (`..._member_kind` has the same leading columns for the child side of `fk_staff_pin_colleague_event_pin`).

### Write cost (append-only, 10,000 clinics, 100 hospitals)
Extreme planning figure 2.6 million rows a year, about 7,000 a day, about one insert every 12 seconds on average. Two more btree entries per row (about 40 to 60 bytes each): roughly 100 to 150 MB a year more at the extreme figure, nothing noticeable for a typical OPD clinic. The table has no update or delete, so there is no index churn or bloat. Each insert maintains up to seven index entries instead of five (primary key, pair, member, pair_kind, member_kind, and the partial one for `WRONG` rows only). The inserts run in the short colleague-check transaction after the `for update` on the owner's `staff_pin` row, so the extra work (microseconds) is not on a contended path.

### Plan line items QA must re-check (item 25 of section 5), after applying the migration and running `analyze`
1. Latest `RIGHT_AFTER_WRONG` probe: uses `ix_staff_pin_colleague_event_pair_kind`; `Index Cond` has `tenant_id`, `membership_id`, `by_membership_id`, `kind = 'RIGHT_AFTER_WRONG'`; no `Filter` and no `Rows Removed by Filter`.
2. Pair-cap count: uses `ix_staff_pin_colleague_event_pair_kind`; `Index Cond` has the five columns including `kind = 'WRONG'` and `at > ...`; no `Rows Removed by Filter`. This holds only after the Java statement uses `statement_timestamp()` or a bound value. If `clock_timestamp()` still appears in a `Filter`, the code was not changed; it is not an index problem.
3. Latest `NOTICE_SEEN`: uses `ix_staff_pin_colleague_event_member_kind`; `Index Cond` has `tenant_id`, `membership_id`, `kind = 'NOTICE_SEEN'`; no sequential scan.
4. Notice count: uses `ix_staff_pin_colleague_event_member_kind`; `Index Cond` has `kind = 'WRONG'` and `at > ...`; no `Filter: kind`.
5. Team grouped count: `ix_staff_pin_colleague_event_wrong_7d` with `Index Cond: (tenant_id, at > statement_timestamp() - '7 days')`; no sequential scan (needs the `statement_timestamp()` statement).
6. Owner history: `ix_staff_pin_colleague_event_member` with `tenant_id`, `membership_id` and `at` in `Index Cond` (needs the bound or `statement_timestamp()`); `kind <> 'NOTICE_SEEN'` may stay a filter.
7. `plans.txt` must use the production strings copied from `JdbcStaffPinColleagueEventRepository`, not variants with `now()` or `statement_timestamp()` substituted by hand, or the measurement does not show the code.
8. Catalog (item 24): both new indexes exist with the column lists above, are valid (`indisvalid`), carry a comment; the three older indexes still exist.

## 8. Guard change after the security review (finding S38-1) - `V2026_12_03_1000__platform_staff_pin_lock_by_colleagues_proof.sql`

Forward-only. One statement: `create or replace function platform.guard_staff_pin_update()`, plus the function comment. No data change, no drop, no table, column, index, grant, revoke or trigger change. `create or replace` keeps the function's owner and ACL (execute stays revoked from public and `sahaj_app`) and the trigger `trg_staff_pin_guard_update` keeps pointing at it. Still `SECURITY INVOKER`, `set search_path = pg_catalog`, SQLSTATE 23514. The migrations `V2026_11_28_1100`, `V2026_12_01_1000` and `V2026_12_02_1000` are not edited. Backward compatibility: the previous app version, which sets `lock_by_colleagues = true` without a `WRONG` row, would now be refused, so the new Java code (insert the `WRONG` row first, in the same transaction) ships with this migration. Every other statement is unchanged.

### Why
In `V2026_12_01_1000` the guard accepted `lock_by_colleagues = true` on any fifth-try lock. A defect or a crafted path in the application could then use the flag to lock a PIN without moving `lock_count` and the 24-hour window, which skips the third-lock switch-off and the rate limit that counts own locks. ADR-014 "Data needed" 1(c) (tightened 7 Nov 2026) requires proof of a colleague try in the database itself, not only in Java.

### The change
Only the lock branch with `new.lock_by_colleagues = true` gets a new first step. The rest of the function body is identical to the body in `V2026_12_01_1000` (checked with a diff):

```sql
if not old.run_has_colleague_try
   and not exists (
     select 1
       from platform.staff_pin_colleague_event e
      where e.tenant_id = old.tenant_id
        and e.membership_id = old.membership_id
        and e.kind = 'WRONG'
        and e.at >= pg_catalog.transaction_timestamp()
   ) then
  raise exception 'staff pin lock caused by colleagues needs a colleague try'
    using errcode = 'check_violation';
end if;
```

Accepted proof: (1) `old.run_has_colleague_try` is true (an earlier try of this run came from a colleague), or (2) a `WRONG` row for the same `(tenant_id, membership_id)` has `at >= transaction_timestamp()`. The insert guard stamps `at = clock_timestamp()`, which is never earlier than `transaction_timestamp()`, so a `WRONG` row written earlier in the same transaction always qualifies, and a row committed before the transaction began never does (its `at` is before the transaction start). The own-lock branch (`lock_by_colleagues = false`) and every non-lock branch are untouched. A lock with the flag false after a run with a colleague try is still refused, as before.

### RLS and tenant safety
- The trigger runs as the invoker, `sahaj_app`, which has `select` on the event table. The policy `p_staff_pin_colleague_event_select` limits the read to `tenant_id = platform.current_tenant()` (the bound `app.tenant_id`). The query adds `tenant_id = old.tenant_id` and `membership_id = old.membership_id`, so a row of another tenant (invisible under RLS) or of another membership (filtered out) never counts. Names are schema-qualified because the search path is `pg_catalog` only, and `pg_catalog.transaction_timestamp()` is written in full.
- Without a bound tenant the policy hides every row, so proof (2) fails closed (23514); proof (1) still works from the `staff_pin` row itself.
- Sessions of the table owner (`sahaj_migrator`) match no policy (both policies are `to sahaj_app`) and FORCE RLS applies, so they see no event rows and cannot satisfy proof (2). They can still satisfy proof (1), and they can disable the trigger, as the other tests do.
- Residual, accepted: under READ COMMITTED, a `WRONG` row of the same membership that another transaction inserts and commits after this transaction began also counts. That row is a real colleague wrong try for the same person, and the application takes `select ... for update` on the `staff_pin` row first, so it cannot appear concurrently with a colleague check on the same membership.

### Cost
The check runs only on a colleague-caused lock (rare) and only when `old.run_has_colleague_try` is false. It is a lookup on `ix_staff_pin_colleague_event_member_kind (tenant_id, membership_id, kind, at)`: all four predicates are index conditions, so no new index is needed.

### What the java-developer must do
Insert the `WRONG` row for the current colleague try before the `staff_pin` update that sets `lock_by_colleagues = true`, in the same transaction (the order in section 4). A lock whose run already had a colleague try needs no extra row (proof 1), but the fifth colleague try writes its `WRONG` row anyway. A 23514 with "needs a colleague try" is a code bug (500, full rollback), as in the error table of section 4.

### Test list for QA (`StaffPinColleagueGuardIT`; real database, `sahaj_app` with a bound tenant; set-up as section 5; `failed_count` 4, PIN not locked, `lock_count` 0 unless stated)
30. No proof: `old.run_has_colleague_try` false, no `WRONG` row for the membership; update `failed_count` 0, `lock_by_colleagues` true, `locked_until` set: 23514, message contains "needs a colleague try"; the row is unchanged afterwards.
31. Same-transaction `WRONG`: in one transaction insert a `WRONG` event for the membership (by a colleague), then the same lock update: accepted; `lock_by_colleagues` true, `lock_count` and `lock_window_started_at` unchanged, `locked_until` about 15 minutes ahead.
32. Run flag: `old.run_has_colleague_try` true, no `WRONG` row in this transaction, the colleague lock update: accepted.
33. Flag false unaffected: with no proof, the own lock (`lock_by_colleagues` false, `lock_count` + 1 or restart at 1) is accepted as before; with `old.run_has_colleague_try` true and the flag false it is still refused; wrong try, right PIN, new PIN, reset and switch-off behave as in section 5 items 1 to 16.
34. Earlier transaction does not count: insert the `WRONG` row and commit; in a new transaction run the lock update (run flag false): 23514.
35. Row of another membership does not count: in the same transaction insert a `WRONG` row for N2 (same clinic); N1's colleague lock without proof: 23514. A `LOCKED`, `RIGHT_AFTER_WRONG` or `NOTICE_SEEN` row for N1 in the same transaction does not count either (use valid column values for each kind): 23514.
36. Row of another tenant does not count: write a `WRONG` row for a clinic B membership (as a clinic B session); in clinic A's session, N1's colleague lock without its own row: 23514 (A cannot see B's rows, and the query is also fenced by `old.tenant_id`).
37. No tenant bound or owner session: with `app.tenant_id` unset, or as the table owner (no policy applies, so no event rows are visible), and `old.run_has_colleague_try` false, the lock with the flag true: 23514.
38. Catalog and Flyway: `guard_staff_pin_update()` is still SECURITY INVOKER, `proconfig` has `search_path=pg_catalog`, execute is not granted to public or `sahaj_app`, trigger `trg_staff_pin_guard_update` is enabled; the function source contains "needs a colleague try". The migration applies on a database at `V2026_12_02_1000` and from scratch; `node scripts/validate.mjs db` is green.
39. Plan (optional, with the section 7 data): the proof lookup uses `ix_staff_pin_colleague_event_member_kind` with `tenant_id`, `membership_id`, `kind = 'WRONG'`, `at >= ...` as index conditions.
