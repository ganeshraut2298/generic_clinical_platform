# ADR-014 · Colleague PIN hardening (HARD-3): durable pair cap, who typed the tries, the PIN owner's notice, the owner's view, the ward line, and the dose event wording

Story: SAH-38 (backlog name HARD-3)
Mockup screens: Nursing sheet → Medicines tab and the Give sheet (`sfGive`, `sf-wit` "Checked by", `sf-pin` "Their PIN", `sf-id`, `sfgiveok`, `sfLog` give and deny events); Clinic setup → Team and access, panel Staff (`f-staff`, the "PIN set" pill).
Module: platform (PIN data, pair cap, notice, Team data) and ipd (Give sheet texts, dose event texts). ipd only calls `platform.api.StaffPinPort`.
Status: PROPOSED (7 Nov 2026) with the SAH-38 contract; accepted when the human approves `contract`. The five product choices are DECIDED by default (DEC-020, DEC-023: every recommendation taken). OQ-38-5 (ward procedure) and OQ-38-6 (permanent event wording) stay go-live conditions for clinical sign-off.
Deciders: Ganesh (human), tech-lead; recorded by solution-architect
Amends: **ADR-010 D5** (lockout: who typed the run; colleague-caused locks do not count toward the switch-off), **ADR-010 D13** and **ADR-011 D7** (colleague tries no longer use the colleague's own failed-check allowance; the pair cap and two new outcomes), **ADR-011 D9** (give and deny event texts, forward only). Answers ADR-011 Q14, Q15, Q16 (security review SAH-32 S1, S2, S3). Leaves ADR-011 Q17 (idle lock) and HARD-2 open.
Related: DEC-017, DEC-018, DEC-020, DEC-023, `docs/reviews/SAH-32-security.md`, `docs/api/platform.yaml`, `docs/api/ipd.yaml`

## Context

1. SAH-32 lets nurse A record a high-alert dose only when nurse B, named as "Checked by", types B's own staff PIN on A's screen. `StaffPinPort.checkColleague` compares it with B's row in `platform.staff_pin` (ADR-011 D7).
2. The security review found:
   - **S1:** A can guess B's PIN quietly. The per-caller caps (5 per 15 minutes, 10 per 24 hours) are in memory, so a restart or a second instance resets them. B's `failed_count` returns to 0 at B's next right PIN, so up to 4 wrong tries between B's own uses never lock, and nobody is told.
   - **S2:** A can lock B twice a day. Through a restart or a second caller, a third lock switches B's PIN off. Meanwhile B's own failed-check bucket fills, so anyone who names B gets 429.
   - **S3:** the identity box accepts typing and the identity bar shows the UHID, but the permanent event says "wristband scanned".
3. `platform.staff_pin` has an update guard trigger (`platform.guard_staff_pin_update`) that enforces the lock state machine. It requires every lock to raise `lock_count` (or restart it at 1), and the third lock in 24 hours must switch the PIN off. Any change to who counts toward the switch-off must change that guard.
4. `platform.audit_event` is the largest-growing table (about 155,000 to 160,000 rows a day from SAH-32 alone; no partition plan yet, ADR-011 Q9). The audit keeps `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` rows with the colleague and caller ids inside `after` (jsonb).
5. Phase 1 has one application instance, no scheduler, no notifications and no shared cache. HARD-2 will bring a shared limiter for every endpoint. This story must not build that.
6. The mockup shows none of the counters, notices, limits, lock states or ward texts. Every user-visible text here is NEW and is listed in the story's register (T1 to T10, plus T11 to T13 below).

## Decision

### D1 · Scope and module boundary

- **platform** owns every new piece of data: the colleague-try event table (D2), the two new `staff_pin` columns (D6), the notice (D8) and the owner's view (D9). These are its own schema and its own package.
- **ipd** keeps calling `staffPinPort.checkColleague(...).requireOk()` exactly as today, outside any transaction. It gets two new outcomes through `platform.api` (`PinCheck.Outcome.PAIR_LIMIT`, `PinCheck.Outcome.CHECK_FAILED`), mapped by `StaffPinException` and the global `ProblemExceptionHandler`. ipd writes no platform table and reads none. Its only code change is the two event templates (D11).
- No new module dependency, library, service, scheduler, cache, event or menu key. `ModularityTests` stay green.

### D2 · The durable store: a small append-only platform table (chosen), not a count of audit rows

**Chosen:** `platform.staff_pin_colleague_event`. It holds one row per relevant event about one person's PIN (`membership_id` = the PIN's owner). Kinds:

| kind | Written when | `by_membership_id` | `seen_through` |
|---|---|---|---|
| `WRONG` | a colleague typed this person's PIN and it was compared and wrong | the caller (colleague) | null |
| `LOCKED` | a lock caused by a run containing a colleague try (D6) | null | null |
| `RIGHT_AFTER_WRONG` | a caller typed the right PIN while the pair had at least one `WRONG` in its window (D4); clears the pair | the caller | null |
| `NOTICE_SEEN` | the PIN's owner tapped "Got it" (D8) | null | the newest try the notice showed (capped at the database clock) |

The rows are tenant-scoped with FORCE RLS. Composite foreign keys keep both ids in the same clinic. `at` comes from `clock_timestamp()`. `sahaj_app` gets select and a column insert only. Triggers refuse update, delete and truncate. The rows hold ids, a kind and times only, never a PIN, hash, name or mobile number.

**Why not count `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` audit rows:**
- **Cost on the hot table.** The pair query needs the colleague id, and that sits in `after` jsonb. It would need an expression index `(tenant_id, actor_user_id, action, (after->>'membershipId'), at)` on the largest and fastest-growing table. Every one of its 160,000 inserts a day would pay for a query that serves a few colleague tries. The index would also have to be carried through the pending audit partition plan (ADR-011 Q9).
- **Clearing.** "A right PIN clears the pair" needs a marker that the audit does not have. A new audit action would make a security control depend on the audit's free-form `after` keys and on `AuditSanitizer`, which redacts keys by name. A sanitizer change could quietly switch the cap off.
- **Purpose.** The audit is the record of who changed what. It is not a control store. The notice and the owner's view need typed columns and simple indexes.

**Why not a mutable counter row per pair:** a rolling 7-day window needs the time of each try. A counter with a window start gives a fixed window, which lets a guesser make 5 tries just before a reset and 5 after. A row per try is exact, append-only (no update or delete grant) and tiny.

**Volume:** wrong colleague tries are rare. Even an extreme planning figure of 100 hospitals × 50 nurses × 10 events a week gives about 2.6 million rows a year, all narrow. No partitioning is needed. Retention is in D12.

**Durable and shared (BR2):** the store is the one database, so it survives a restart and is the same on every instance.

### D3 · The pair cap: 5 wrong tries per (caller, colleague) per rolling 7 days, checked under the colleague's row lock

`StaffPinOps.checkColleague` runs in one READ COMMITTED platform transaction, as today, in this order. Each step stops the check.

1. Pepper missing → `UNAVAILABLE` (503 `pin-unavailable`). Unchanged.
2. The caller's in-memory colleague bucket (5 failed per 15 minutes, 10 per 24 hours, key `tenantId:callerUserId`) is full → `RATE_LIMITED` (429 `rate-limit`). Unchanged, and still in memory until HARD-2. **B's own failed-check bucket is no longer read or filled here** (D7).
3. Eligibility (caller ACTIVE; colleague ACTIVE NURSE of the clinic, not the caller) → `NOT_ELIGIBLE` (400 "Choose a nurse from the list."). Unchanged. Another clinic's id looks the same as an unknown id.
4. Transaction: `set local lock_timeout = '3s'` and `set local statement_timeout = '5s'` (D5). Eligibility again. B's `staff_pin` row is read `for update`. This lock serialises every try at B, so two parallel tries by A both count and cannot pass the cap together.
5. No PIN → `NOT_SET`. Locked → `LOCKED`. Answers unchanged. The try is not counted anywhere.
6. **Pair cap.** Count the `WRONG` rows where `membership_id = B` and `by_membership_id = A` and `at > window_start`. Here `window_start` = the greatest of `statement_timestamp() - interval '7 days'` (a stable database clock, see Operational notes), `staff_pin.set_at` (the current PIN's life starts there, so an owner reset or a new PIN starts the pair again), and the latest `RIGHT_AFTER_WRONG` row of the same pair. **Count ≥ 5 → `PAIR_LIMIT`.** The PIN is not compared. Nothing is counted on B's row, in the event table, in the caller's bucket or in the audit. One log line holds ids and the outcome only.
7. HMAC + BCrypt comparison (ADR-010 D2, D6). Unchanged.
8. Wrong: B's row moves as in D6; insert `WRONG (B, A)`; audit `PLATFORM_PIN_COLLEAGUE_CHECK_FAILED` (unchanged); the caller's bucket +1. Answer `wrong-pin` "That PIN is not right." with no count, or `pin-locked` when this try caused a lock. The 5th wrong try of a pair answers like any wrong try. The 6th is refused at step 6.
9. Right: D4.

The 5 and the 7 days are the human's numbers (OQ-38-1, DEC-023). They are constants in `StaffPinOps`, not settings.

**Refusal (BR5):** **429**, slug **`colleague-pin-limit`**, title "Too many wrong PIN tries", detail (T1) **"Too many wrong PIN tries for this nurse. Choose another nurse."**. There is **no `retryAfterSeconds`** and no count: the wait is days, a client must not auto-retry, and a guesser is not told when the window reopens.
- Why 429 and not 403: it is the same family as the per-caller "too many tries" (429 `rate-limit`), and the request is refused because of how many tries were made, not because of who the caller is.
- Why a new slug: the text and the client action differ ("choose another nurse", no retry).
- The Give sheet keeps every entry except the PIN (ADR-011 D8, unchanged).

### D4 · A right PIN clears the pair

- When the comparison matches and the pair (B, A) has at least one `WRONG` row after `window_start`, the same transaction inserts `RIGHT_AFTER_WRONG (B, A)`. Its `at` becomes the new lower bound for the pair, so the pair count is 0 again (BR3).
- It also writes the audit **`PLATFORM_PIN_COLLEAGUE_RIGHT_AFTER_WRONG`** with `after = {membershipId: B, checkedByMembershipId: A, wrongTries: <n>}` (ids and a number). This is the owner's "likely successful guess" line (OQ-38-7). It is written only in that case, never for every right PIN.
- A right PIN with no wrong tries in the pair's window writes nothing new. B's `failed_count` and the run flag return to 0 / false as today (D6).
- B's own right PIN (own check, `StaffPinPort.check`) does **not** clear any pair. Only the same caller's right PIN clears that caller's pair.
- An owner cannot clear a pair (OQ-38-8, proposal kept). The cap expires by itself.

### D5 · Fail closed (T7)

- The colleague check's platform transaction sets `lock_timeout` 3 s and `statement_timeout` 5 s (`set local`). `StaffPinService.checkColleague`, the non-transactional facade, catches a `DataAccessException` or `TransactionException` from that transaction. Everything is already rolled back, so nothing is counted, right or wrong. It answers **`CHECK_FAILED`** → **503**, slug **`pin-check-failed`**, title "PIN check failed", detail (T7) **"We could not check the PIN. Try again."**.
- **Which failures (RESOLVED, 7 Nov 2026; the db note `docs/database/SAH-38.md` "Error classes" and the build follow this):** only a store that is slow or unreachable becomes 503 `pin-check-failed`: timeouts (`55P03` lock timeout, `57014` statement timeout), a lost connection, and lock failures (deadlock, serialization, lock not available). A constraint or permission refusal by the database (`23514` check or guard, `23503` foreign key, `42501` permission, append-only or RLS) is a code bug: it is rethrown and stays a plain **500**, with the transaction rolled back. It is never turned into an answer about the PIN. In the build, `StaffPinService.isCodeFault` rethrows `DataIntegrityViolationException`, `PermissionDeniedDataAccessException`, `BadSqlGrammarException` and `InvalidDataAccessApiUsageException`; everything else from that transaction is `CHECK_FAILED`.
- **No oracle:** the answer is the same whether the failure came before or after the comparison, and whether the PIN matched or not. A right PIN whose writes failed is never reported as right, and ipd records nothing.
- `pin-unavailable` (pepper missing, a configuration state) keeps its own text. `pin-check-failed` is transient and the client may let the nurse retry.
- Scope: the colleague check only. The own check (`check`) keeps its current behaviour; a database failure there is a 500 as today.

### D6 · Who typed the run: colleague-caused locks lock but never count toward the switch-off (amends ADR-010 D5)

Two columns are added to `platform.staff_pin` (additive, default false):

- **`run_has_colleague_try boolean`**: true when at least one wrong try in the current run of wrong tries (the `failed_count` run) came from a colleague check. It is set by a colleague wrong try. It returns to false whenever `failed_count` returns to 0 (right PIN, lock, new PIN, owner reset, switch-off).
- **`lock_by_colleagues boolean`**: true when the current or last lock was caused by a run with a colleague try. It is set at the lock and cleared by a new PIN, an owner reset or a switch-off, and by the next own-run lock.

Rules (the fifth wrong try of a run, own check or colleague check):

| The run (5 wrong tries in a row) | Result |
|---|---|
| every try typed in the person's own session (`run_has_colleague_try` false and the 5th try is the own check) | **as ADR-010 D5:** lock 15 minutes, `lock_count + 1`; the third such lock in 24 hours switches the PIN off (409 `pin-not-set`, `TOO_MANY_LOCKS`); audit `PLATFORM_PIN_LOCKED` / `PLATFORM_PIN_CLEARED_AFTER_LOCKS` with `causedBy: SELF` |
| at least one try by a colleague (flag true, or the 5th try is a colleague check), **mixed runs included** (OQ-38-4) | lock 15 minutes (`locked_until` stamped by the guard), **`lock_count` and `lock_window_started_at` unchanged**, `lock_by_colleagues = true`; event `LOCKED`; audit `PLATFORM_PIN_LOCKED` with `causedBy: COLLEAGUES` and the unchanged `lockCount`. **Never a switch-off.** The answer is `pin-locked` (colleague text for a colleague check, own text for the own check) |

- So a PIN is switched off only by three own-session locks within 24 hours. Any number of colleague-caused locks never switch it off, and the answer is `pin-locked` (403), never `pin-not-set` (409).
- While locked, nothing changes (ADR-010 D5: no comparison, not counted). The person can wait, or set a new PIN with a code to their own mobile (ADR-010 D7). Both columns are cleared then.
- **Why a flag on the row, not two counters:** two counters (own and colleague) would double the tries before a lock (5 own + 5 colleague). One run with one flag keeps "5 wrong tries in a row lock the PIN", whoever typed them, and needs no PIN-related data.
- **Guard change (db-engineer):** `platform.guard_staff_pin_update()` is replaced (`create or replace`) to accept a lock with `lock_count` unchanged only when `new.lock_by_colleagues` is true, and to require it when `old.run_has_colleague_try` is true. It also enforces the flag transitions listed in "Data needed".
- **Residual, accepted:** an accomplice who types one wrong try into the person's run turns an own-session run into a colleague-caused lock. The pair cap limits this to 5 taints per accomplice per week, and each one is a visible `WRONG` and `LOCKED` row. Guessing in the person's own session gains at most 4 extra tries per taint.

### D7 · Colleague tries never use the colleague's own allowance (BR9; amends ADR-010 D13 and ADR-011 D7)

- The colleague check no longer adds to, and no longer reads, the colleague's own failed-check bucket (ADR-010 D13: 10 per 5 minutes, key the colleague's user). That bucket is only for the person's own checks.
- The limits on colleague tries are now: the caller's in-memory bucket (unchanged until HARD-2), the durable pair cap (D3), and the database lockout on the colleague's row (D6).
- Effect: other nurses' guesses never make "too many tries" (429) appear for anyone who names B, and never stop B's own checks.

### D8 · The notice to the PIN's owner (BR6, BR10; T2, T3)

- **What:** one in-app banner, shown at the top of the main content area of the app shell (above the page; any page; NEW placement, OQ-38-9). It has the text T2 and, under it, a T3 line by the PIN state, and one button **"Got it"**. It is shown only when `unseenCount > 0`.
  - T2: "N wrong tries of your PIN by colleagues since your last use." / "1 wrong try of your PIN by a colleague since your last use."
  - T3 when `state` is LOCKED: "Your PIN is locked until 11:05 AM IST." (from `lockedUntil`).
  - T3 when NOT_SET with `notSetReason` TOO_MANY_LOCKS: "Your PIN was switched off. Set a new one." plus the existing button "Set my PIN", which opens the existing Set PIN sheet (`features/pin`). After D6 this state comes only from the person's own tries, but it is still shown.
  - No name, no time of a try, nothing about who tried (OQ-38-2). `role="status"`; "Got it" is the only control besides "Set my PIN".
- **Count:** `WRONG` rows for the caller's own membership with `at` after the latest `NOTICE_SEEN.seen_through` of that membership (no marker yet: all kept rows). The person's own wrong tries are never in it (they are not `WRONG` rows).
  - The marker is "since the last 'Got it'", not "since the PIN was last typed" (OQ-38-3). A lucky guess by someone else therefore cannot clear the evidence.
  - A new PIN does not clear unseen tries either. This is the safer reading of "since the PIN was set", which is the same thing at the first PIN. See the questions at the end.
- **`GET /api/v1/platform/me/pin/colleague-tries`**: any clinic role with an ACTIVE membership, own data only (tenant and person from the session). Answers `{unseenCount, latestAt, state, lockedUntil, notSetReason}`. Writes nothing. With no PIN row, `unseenCount` is 0.
- **`POST /api/v1/platform/me/pin/colleague-tries/seen`** with `{seenThrough}` = the `latestAt` the banner showed. The server inserts `NOTICE_SEEN` with `seen_through = least(seenThrough, clock_timestamp())` (clamped by the insert guard). It writes **only** when at least one `WRONG` row lies between the current marker and `seenThrough`; otherwise nothing is written (a double tap is safe). 204.
  - So a try that arrives between showing and tapping stays unseen and appears at the next load. A future `seenThrough` cannot hide later tries.
  - `seenThrough` missing, longer than 40 characters (`maxLength: 40` in the contract), not a readable date-time, more than one day ahead of the server clock, or earlier than 1 Jan 2020 (00:00 UTC) → 400 validation, field `seenThrough` "Reload the page and try again." (one problem type and title for all; added 7 Nov 2026, security review S38-3: a value outside the `timestamptz` range must never reach the database and answer 500). The database cap of a future value stays as the second line.
  - Not audited: the `NOTICE_SEEN` row is its own record, and it is about staff, not patients.
- **Loading:** the shell calls the GET once after sign-in and on every full page load, after the first screen is shown, never blocking sign-in. On a failure no banner is shown and the shell tries again at the next route change, at most once a minute. Nothing is lost because the rows stay.
- In every plan and IPD state (staff accounts are platform). Pepper missing does not matter (no PIN is checked).

### D9 · The owner's view on the Team page (BR7, BR15; T4, T5, T10)

- **`TeamMember.pin.colleagueTries7d`** (`GET /team`): the number of `WRONG` rows for that membership with `at > now() - interval '7 days'` (a stable database clock, see Operational notes). It is filled **only when the caller is OWNER**, on every row. For every other role it is null; such a caller receives a `pin` object only on their own row (no `pin` is sent on other people's rows, SAH-31 rule, ADR-010 D12), so the null appears only there (a nurse's number reaches them only through the notice, BR10). It is one grouped query per page (partial index, Data needed).
  - Row text T4, shown only when the value is above 0: "N wrong tries by colleagues in 7 days" / "1 wrong try by a colleague in 7 days" (T4b, NEW). It is a link-style button that opens the history sheet.
  - The existing pill "PIN locked" is unchanged.
  - **This 7-day figure is evidence, not a counter:** an owner reset or a new PIN does not clear it, so the owner can still see why they reset. The pair counts, the run flag and the lock counters are cleared as the story asks.
- **`GET /api/v1/platform/team/{id}/pin/colleague-tries`** (membership id): **OWNER only**. Other roles get 403 `forbidden` "Only the clinic owner can do this."; another clinic's or an unknown id gets 404 `not-found`.
  - Answers the entries of the last 7 days (database clock), latest first, at most 100, with `truncated`. Each entry has `kind`, `at`, `byMembershipId`, `byName`, `count`:
    - `WRONG_TRIES`: the `WRONG` rows grouped by caller and IST calendar day; `at` = the latest try of the group; `count` = the number of rows. T5 "Wrong PIN tries by <name>: N".
    - `LOCKED`: one entry per row. T5 "PIN locked after wrong tries by colleagues" (no name).
    - `RIGHT_AFTER_WRONG`: one entry per row. T5 "Right PIN entered by <name> after wrong tries".
  - Times are shown as "24 Oct 2026, 11:05 AM IST".
  - Names are looked up at display time through the existing actor directory (membership → name). A switched-off or removed person keeps their name, because the mockup says "Their past entries stay in the records under their name" and `DELETE /team/{id}` keeps the record. **T10 "Former staff member" is used only when no name can be found** (deviation from the story, see the questions).
  - Writes nothing. No access row (staff data, not patient data).
  - UI (NEW): a sheet "Wrong PIN tries · <name>" (T11) with the lines; empty state "No wrong tries by colleagues in the last 7 days." (T12); footer "Close".
- The existing clinic-wide Change history (`f-audit`, `GET /audit-events`) keeps showing the audit rows as today. Nothing new is designed there.

### D10 · The ward line on the Give sheet (BR11; T6)

- **Client only, a fixed text; no server field.** For a high-alert medicine the Give sheet shows, under the reason under "Their PIN", one extra line (T6): "No second PIN works? Do not give this medicine yet. Choose another nurse, or ask the nurse in charge or the clinic owner. Never share a PIN."
- **Shown when** the last answer was `pin-locked`, `pin-not-set`, `rate-limit` or `colleague-pin-limit`, or when `Mar.secondNurses` is empty (only "Choose a nurse" in the select; single-nurse shift, ADR-011 Q6).
- **Not shown** for `wrong-pin` (a typing mistake), `pin-unavailable` or `pin-check-failed` (503: "choose another nurse" would not help), a medicine that is not high-alert, or a role that cannot open the sheet.
- **Hidden** when the nurse chooses another nurse in "Checked by", or when a new answer arrives that is not one of the four.
- Announced with the reason, in the same polite live region.
- There is no bypass: no "give anyway", no paper option, no new not-given reason (OQ-38-5). The ward's written procedure is the clinic's (go-live condition). The same sheet is used from the Safety board's Medicine round (ADR-012), so the line appears there too.

### D11 · Dose event wording, forward only (BR12; amends ADR-011 D9; T8, T9)

| Kind | New template (from this release) | Was |
|---|---|---|
| give (GIVEN) | "Gave <drug name> (<slot time> dose) · identity checked against UHID" + " · second check by <second nurse display name>" for a high-alert medicine | "… · wristband scanned" |
| deny (mismatch) | "Stopped at the identity check: entered <scan>, expected <UHID> (<drug name>)" (`<scan>` as ADR-011 D4: the UHID-shaped text, else "text that is not a UHID") | "Stopped at the wristband check: scanned …" |

- These are template changes in ipd (`DoseRules` and the deny builder). No schema change, no migration, no rewrite. `ipd.clinical_event` is append-only, and rows already written keep "wristband scanned" and "scanned".
- The log shows both wordings in time order. The release note must say that older "wristband scanned" rows did not prove a scan (OQ-38-6).
- Lengths: the give text grows by 12 characters (longest about 257 of 300); the deny text shrinks by 1.
- Unchanged: the comparison, the normalisation, the 409 `wristband-mismatch` detail, the box label "Wristband MRN", its placeholder, the toast and line under the box, the audit `IPD_DOSE_WRISTBAND_MISMATCH`, and the Medicines tab legend "Every dose needs the wristband scan …" (mockup; OQ-38-6 leaves it).
- Tests that assert the old text on new events must be updated by their owners: `backend/src/test/java/com/sahaj/platform/it/DoseSecondNurseIT.java` (qa-engineer), ipd unit tests of `DoseRules` (java-developer), `docs/test-plans/SAH-32.md` (qa-engineer, as history).

### D12 · Privacy, logs, audit and retention

- `platform.staff_pin_colleague_event` holds ids, a kind and times. Nothing in it, in the new problem details, in the audit or in a log line is a PIN, hash, HMAC, code, name, mobile number or patient datum.
- New audit keys `causedBy` and `wrongTries` contain no "pin", so `AuditSanitizer` keeps them readable. No new pin-named key (ADR-010 D10 rule kept).
- Log lines: `staff-pin-colleague-check tenantId membershipId checkedByMembershipId outcome` (outcome now also `PAIR_LIMIT`, `CHECK_FAILED`); `staff-pin-colleague-notice-seen tenantId membershipId`.
- Names appear only on screen: the owner's history sheet and the existing Give sheet select.
- Retention: the event rows are kept (no delete grant). The logic reads 7 days (pair, owner) and "since the last Got it" (notice). A later purge of rows older than 90 days, run by `sahaj_migrator`, is allowed but not built. No scheduler in phase 1.

### D13 · What stays out

- HARD-2: a shared or database limiter for every endpoint; moving the in-memory buckets (caller colleague bucket, own failed-check bucket, set/code/reset buckets, sign-in codes) out of memory; limits on mismatches and MAR opens (S5). The pair cap is a narrow durable counter for one purpose, not a limiter framework.
- Idle screen lock for ward devices (ADR-011 Q17, S7).
- Hiding the UHID in the identity bar, an entry-mode hint, scanner integration, wristband printing.
- Any SMS, WhatsApp or push message. An owner override of a pair cap. Owner or doctor as second checker.
- PIN length, weak-PIN rules, pepper, the `otp_challenge` purpose (ADR-010 Q8, Q9), the audit partition plan (ADR-011 Q9).

## Options considered

- **Pair store:** append-only event table (**chosen**) vs a count of audit rows with an `(actor, action, at)` plus jsonb index (cost on the largest table, no clear marker, coupling to the sanitizer) vs a mutable counter per pair (fixed window, needs an update grant) vs the in-memory bucket (fails BR2).
- **Pair cap refusal:** 429 `colleague-pin-limit` without `retryAfterSeconds` (**chosen**) vs 429 `rate-limit` (wrong text, invites retry) vs 403 (reads as a permission problem).
- **Who typed the run:** one run flag plus a lock-cause flag (**chosen**) vs separate own and colleague counters (doubles tries before a lock) vs colleague tries never lock (unlimited guessing with many accounts; rejected in OQ-38-4).
- **Notice marker:** a `NOTICE_SEEN` row with `seen_through` (**chosen**; append-only, no change to the `staff_pin` guard, and a tap cannot hide a try that arrived later) vs a `seen_at` column on `staff_pin` (needs an update while locked, which the guard refuses) vs "since the last own PIN use" (a lucky guess clears evidence).
- **Notice delivery:** a shell banner from a separate endpoint (**chosen**; sign-in is not slowed) vs a field on `GET /me` (cached for the session, and a failure would break the shell).
- **Ward line:** client text keyed on slugs (**chosen**) vs a server field (more contract for a fixed text).

## Consequences

**Positive.**
- Guessing becomes "5 tries a week per pair", durable across restarts and instances, and visible to the PIN's owner and the clinic owner.
- A hostile colleague can no longer switch a PIN off or block others from naming a nurse.
- The permanent record no longer claims a scan.
- ipd is unchanged except two templates. There is no new library or service.

**Negative / accepted.**
- A hostile colleague can still lock a nurse for 15 minutes about once a week per pair (5 tries). With several hostile callers it can happen more often, but every lock is visible.
- **Residual, accepted (7 Nov 2026, security review SAH-38):** a hostile colleague can lock a nurse's PIN for 15 minutes about once a week per colleague per hostile account; HARD-2 should notify the PIN's owner of `LOCKED` events in-app. The database guard now also requires proof of a colleague `WRONG` try in the same transaction before it accepts `lock_by_colleagues = true`, so the application alone cannot mark an own-session lock as colleague-caused (new forward migration from the db-engineer; see Data needed 1(c)).
- The in-memory per-caller buckets stay per instance until HARD-2.
- The log shows two wordings.
- One more small table and two more columns.
- The `staff_pin` guard function is replaced. It is security-critical and needs its own tests.
- The Team page makes one more query for owners.
- New UI not in the mockup: the banner, T4, the history sheet and T6.

## Operational notes (added 7 Nov 2026 after the SAH-38 QA run)

- **Stable clock in rolling-window reads.** Every SQL read with a rolling window (`at > <clock> - interval '7 days'`: the pair count of D3, the Team count and the owner history of D9) uses a stable timestamp, `now()` or `statement_timestamp()`, never `clock_timestamp()` in the `where` clause. `clock_timestamp()` is volatile, so the planner cannot use it as an index bound. QA measured the Team grouped count with `clock_timestamp()` as a parallel sequential scan of the whole event table (174 to 380 ms); with `now()` it is a bitmap scan of the partial index `ix_staff_pin_colleague_event_wrong_7d` (6.5 ms) (`docs/test-plans/SAH-38.md`, B2). The difference between the clocks is at most the transaction's own few seconds (the timeouts of D5 cap it), which only makes the window a little wider, the safe direction for the pair cap. Times that are *stored* (`at` by the insert guard, `seen_through` capped, lock times) keep `clock_timestamp()`; the history's `asOf` may stay `clock_timestamp()` when it is read first and passed as a parameter.
- **Connections in the test suite.** The integration suite keeps about 9 Spring contexts alive at once, each with a Hikari pool of 10 (`SAHAJ_DB_POOL_SIZE`, default 10), so about 90 connections against the local default `max_connections` of 100. New tests must not add a context (reuse an existing one, as `MarSupport` does with its spy beans) or must lower the pool size for it, so that the total stays under `max_connections` with room for Flyway and a psql session. If the suite grows, lower the test pool size or raise `max_connections` on the test server.

## Data needed (for db-engineer)

One forward-only, additive migration in the platform schema, at the next free version after any SAH-35 or SAH-36 migration, for example `V2026_12_01_1000__platform_staff_pin_colleague_tries.sql`. No existing row is changed: the new columns take their defaults, and no data is rewritten.

1. **`platform.staff_pin`**, add:
   - `run_has_colleague_try boolean not null default false`; `lock_by_colleagues boolean not null default false`.
   - Checks: `ck_staff_pin_run_colleague` `(not run_has_colleague_try) or failed_count > 0`; `ck_staff_pin_lock_by_colleagues` `(not lock_by_colleagues) or locked_until is not null`; `ck_staff_pin_colleague_flags_when_off` `pin_hash is not null or (not run_has_colleague_try and not lock_by_colleagues)`.
   - Grant: `update (run_has_colleague_try, lock_by_colleagues)` to `sahaj_app`, in addition to the existing column list. The insert grant is unchanged (defaults).
   - **Replace `platform.guard_staff_pin_update()`** keeping every existing rule, plus:
     - (a) **WRONG TRY:** `run_has_colleague_try` may go false → true or stay. It may not go true → false.
     - (b) **Every transition where `new.failed_count = 0`** (right PIN, lock, new PIN, reset, switch-off): `new.run_has_colleague_try` must be false.
     - (c) **LOCK:** if `new.lock_by_colleagues` is true, then `new.lock_count = old.lock_count` and `new.lock_window_started_at` is unchanged. `locked_until` is still stamped `v_now + 15 minutes`. The "third lock must switch off" rule does not apply to such a lock. If `old.run_has_colleague_try` is true, `new.lock_by_colleagues` must be true. If `new.lock_by_colleagues` is false, the existing count rules apply. Detect a lock by `new.locked_until` newly set **or** `lock_count` changed, as today. **Tightened (7 Nov 2026, forward migration, never an edit of the applied one):** the guard accepts `new.lock_by_colleagues = true` at a lock only with proof of a colleague try: `old.run_has_colleague_try` is true, or a `WRONG` row for this `(tenant_id, membership_id)` was inserted in the same transaction (`staff_pin_colleague_event.at` not earlier than the transaction start, or an equivalent same-transaction marker of the db-engineer's choice). Otherwise SQLSTATE 23514 with the "staff pin" prefix. The application therefore inserts the `WRONG` row **before** the lock update in the same transaction.
     - (d) **THIRD LOCK switch-off:** only with `old.run_has_colleague_try = false`.
     - (e) **New PIN, owner reset, switch-off:** both flags false.
     - (f) **RIGHT PIN, WRONG TRY:** `lock_by_colleagues` unchanged, except true → false when the same update clears an expired `locked_until` to null (the guard already allows that).
     - (g) Neither flag may change in any other way. Keep `security invoker`, `search_path = pg_catalog`, SQLSTATE 23514 and the "staff pin" message prefix. Keep the revokes.
   - Comments on the two columns (ADR-014 D6).
2. **New `platform.staff_pin_colleague_event`** (tenant table; singular name as the platform convention `staff_pin`, `audit_event`):
   - `id uuid primary key default gen_random_uuid()`
   - `tenant_id uuid not null default platform.current_tenant()` FK `platform.tenant`
   - `membership_id uuid not null`, FK `(tenant_id, membership_id) → platform.staff_pin (tenant_id, membership_id)` (`uq_staff_pin_tenant_membership`)
   - `by_membership_id uuid null`, FK `(tenant_id, by_membership_id) → platform.membership (tenant_id, id)`
   - `kind text not null` check in (`WRONG`, `LOCKED`, `RIGHT_AFTER_WRONG`, `NOTICE_SEEN`)
   - `seen_through timestamptz null`
   - `at timestamptz not null default clock_timestamp()`
   - `created_by uuid not null` (user id of the signed-in session that wrote it)
   - Checks: `(kind in ('WRONG','RIGHT_AFTER_WRONG')) = (by_membership_id is not null)`; `by_membership_id is null or by_membership_id <> membership_id`; `(kind = 'NOTICE_SEEN') = (seen_through is not null)`.
   - No `updated_*` and no `version`: append-only, as `platform.audit_event`.
   - Indexes:
     - `ix_staff_pin_colleague_event_pair (tenant_id, membership_id, by_membership_id, at)`: pair count and the pair's latest `RIGHT_AFTER_WRONG`.
     - `ix_staff_pin_colleague_event_member (tenant_id, membership_id, at)`: notice count, latest `NOTICE_SEEN`, owner history.
     - partial `ix_staff_pin_colleague_event_wrong_7d (tenant_id, at) where kind = 'WRONG'`: the Team page counts.
   - RLS: ENABLE and FORCE; `p_..._select` for select to `sahaj_app` using `tenant_id = platform.current_tenant()`; `p_..._insert` for insert to `sahaj_app` with check the same. No update or delete policy.
   - Grants: revoke all from `sahaj_app` and public; `select`; `insert (membership_id, by_membership_id, kind, seen_through, created_by)`. `id`, `tenant_id` and `at` are always defaults.
   - Triggers: deny update, delete (row) and truncate (statement) with `platform.deny_audit_mutation()`. BEFORE INSERT guard (security invoker, `search_path = pg_catalog`, revoked from public and `sahaj_app`, SQLSTATE 23514, message contains "colleague event"): `new.at := clock_timestamp()`; for `NOTICE_SEEN`, `new.seen_through := least(new.seen_through, clock_timestamp())`.
   - Comments: purpose (ADR-014 D2), ids only, never a PIN or name, append-only, retention (D12).
   - Volume: rare; worst case about 2.6 million narrow rows a year across all clinics. No partitioning.
3. **No change** to `platform.audit_event` (no new index; the new actions are values), `platform.membership`, `ipd.*` (event texts are application templates) or `ipd.clinical_event`.
4. **Tenant scope:** both tables are tenant tables under FORCE RLS. A cross-clinic id fails the composite FKs and is invisible to reads.
5. **Integration tests for the design note:** the guard accepts a colleague lock with `lock_count` unchanged and refuses it when the flag is false; it refuses a switch-off after a colleague run; it refuses `run_has_colleague_try` true with `failed_count` 0; the event table refuses update, delete and truncate; an insert with another tenant's membership id fails; `at` cannot be supplied.

## Instructions

**java-developer (platform):**
- `PinCheck.Outcome` gains `PAIR_LIMIT` and `CHECK_FAILED`. `StaffPinException` gains `colleague-pin-limit` (429, T1, no `retryAfterSeconds`) and `pin-check-failed` (503, T7).
- `StaffPinOps.checkColleague` follows the order of D3, with the pair count and the `WRONG` / `LOCKED` / `RIGHT_AFTER_WRONG` inserts and the audits of D4 and D6. Set the timeouts with `set local`.
- `StaffPinService.checkColleague`: the facade catches data access failures and answers `CHECK_FAILED` (D5). Stop reading and filling the colleague's own bucket (D7).
- The own check marks `causedBy: SELF` or `COLLEAGUES` by the run flag, and writes the `LOCKED` event for a mixed run.
- New repository for the event table. Notice service and endpoints (D8). `TeamMember.pin.colleagueTries7d` for OWNER (D9). The history endpoint (D9).
- ITs as `sahaj_app`:
  - restart-proof cap (the count comes from the table); parallel wrong tries both counted and the cap never passed;
  - the 8-day-old window; a right PIN clears; a reset or new PIN restarts the pair;
  - a colleague lock does not count; a mixed run does not count; three own locks switch off;
  - cross-tenant ids; non-owner 403; no PIN or name in logs;
  - fail closed with a forced lock timeout.

**java-developer (ipd):** the two templates of D11 and their unit tests. Nothing else.

**angular-developer:**
- The shell banner (D8) with T2, T3, "Got it" and "Set my PIN".
- The Team page T4 and the history sheet T11, T12 (D9). Show the existing error state on failure, never a partial 0.
- The Give sheet T6 rules (D10), and T1 / T7 under "Their PIN" from the problem detail. Keep the entries and clear the PIN, as today.

**qa-engineer:** update `DoseSecondNurseIT` for the new texts; add the ITs above where they are integration; add e2e for the banner and T6.

## Questions for the human (nothing here blocks the contract; each has a default)

- **Q1 (mockup, T10):** the story shows "Former staff member" for a colleague whose access was removed. The mockup says past entries stay under the person's name, and `DELETE /team/{id}` keeps the record. Default: show the name; T10 only when no name is found.
- **Q2 (notice marker):** a new PIN does not clear unseen colleague tries in the notice (only "Got it" does). Default: yes, safer for visibility. The alternative is to also reset at the PIN set.
- **Q3 (owner figure):** the owner's 7-day count and history are not cleared by an owner reset or a new PIN (they are evidence); the pair caps and lock counters are. Default as written.
- **Q4 (new texts):** T1 to T9 from the story, plus T4b "1 wrong try by a colleague in 7 days", T11 "Wrong PIN tries · <name>", T12 "No wrong tries by colleagues in the last 7 days.", T13 problem titles "Too many wrong PIN tries" and "PIN check failed". The banner placement (top of the app shell content) and the history sheet are NEW UI.
- Go-live conditions, unchanged: OQ-38-5 (ward procedure, clinical sign-off of T6) and OQ-38-6 (permanent wording T8, T9 and the release note); release with HARD-2 (OQ-38-11).
