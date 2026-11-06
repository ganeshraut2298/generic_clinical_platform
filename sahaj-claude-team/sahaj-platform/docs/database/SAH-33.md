# SAH-33 · Database design: IPD Safety board (escalation state machine, closing user, re-open time)

Migration (forward-only; no earlier migration is edited): `V2026_11_26_1000__ipd_safety_board.sql` (latest before it: `V2026_11_25_1010`). Decision record: ADR-007 D1 and D2 ("Data needed"). Mockup screen: IPD → Safety tab (`escRow`, `sfack`, `sfresp`, `sfrespok`, `sfup`, `sfLateEsc`, `sfMins`, `sfLog`).

No new table. One table changes (`ipd.ews_escalation`), one function is replaced (`ipd.guard_ews_escalation_update()`), one index is added. `ipd.clinical_event`, `ipd.admission` and `ipd.discharge` are unchanged.

## ipd.ews_escalation: what changes
| Column | Type | Null | Rule |
|---|---|---|---|
| closed_by (new) | uuid | yes | user id (IPD actor convention, no FK). Set exactly when `status = 'DONE'` (`ck_ews_escalation_done_closed_by`). The doctor, or the discharging user for `CLOSED_AT_DISCHARGE`. |
| reopened_at (new) | timestamptz | yes | `>= raised_at` (`ck_ews_escalation_reopened`). Set only by the trigger when an ACK escalation is re-opened. Response clock start = `coalesce(reopened_at, raised_at)`. |
| status | text | no | unchanged: OPEN, ACK, DONE. Now updatable (guard decides the transition). |
| acknowledged_by | uuid | yes | agrees with status (`ck_ews_escalation_ack_by_status`): null while OPEN; set while ACK; set when DONE unless `CLOSED_AT_DISCHARGE`. `ck_ews_escalation_ack_pair` (with `acknowledged_at`) stays. |
| acknowledged_at, closed_at | timestamptz | yes | stamped by the trigger from `clock_timestamp()`; NOT granted to `sahaj_app`. `ck_ews_escalation_closed` (DONE iff `closed_at`) stays. |
| response_action | text | yes | one of `BEDSIDE_REVIEW`, `TREATMENT_CHANGED`, `MOVED_ICU_HDU`, `RAPID_RESPONSE`, `NO_CHANGE`, `CLOSED_AT_DISCHARGE` (`ck_ews_escalation_response_action`); set exactly when DONE (`ck_ews_escalation_done_action`). |
| response_note | text | yes | 1..1000 characters when present (`ck_ews_escalation_response_note`, code points); present exactly when DONE with a doctor action, null for `CLOSED_AT_DISCHARGE` (`ck_ews_escalation_response_note_done`). Clinical text. |
| senior_calls | smallint | no | unchanged (`>= 0`); now updatable, +1 only (guard). |
| version | bigint | no | unchanged type; now exact +1 rule (guard). |

Check validation: `ck_ews_escalation_done_action`, `ck_ews_escalation_done_closed_by`, `ck_ews_escalation_response_note_done` and `ck_ews_escalation_ack_by_status` are added `NOT VALID`: enforced for every new and changed row, existing rows are not scanned. `ck_ews_escalation_response_action`, `ck_ews_escalation_response_note` and `ck_ews_escalation_reopened` are added validated (no existing row can violate them: the columns are null). No real database holds a violating row (before SAH-33 only the table owner could close or acknowledge an escalation and nothing in the app did); `sahaj_test` does, from the SAH-30 test helper. A fresh environment behaves identically. To validate later, clean the test data, then `alter table ipd.ews_escalation validate constraint <name>` for each of the four.

Guard and legacy DONE rows: the first rule of the trigger refuses any update of a DONE row (DONE is final), before any other column is read, so a legacy DONE row never reaches the other rules. Confirmed.

Check names for the java-developer (a hit is a code bug, 500, SQLSTATE 23514): `ck_ews_escalation_response_action`, `ck_ews_escalation_done_action`, `ck_ews_escalation_done_closed_by`, `ck_ews_escalation_response_note`, `ck_ews_escalation_response_note_done`, `ck_ews_escalation_ack_by_status`, `ck_ews_escalation_reopened`. The trigger runs before the checks, so a bad transition is reported by the trigger (message contains "escalation").

Index added: `ix_ews_escalation_tenant_admission (tenant_id, admission_id, raised_at)`, not partial. Serves the board's closed rows of admitted patients (join to admitted `ipd.admission`) and "all escalations of one admission". The partial unique index `ux_ews_escalation_tenant_admission_open` stays the one-open guarantee and serves the open-row lookup under the admission lock.

Index NOT added: `ix_clinical_event_tenant_actor_at (tenant_id, actor, at desc, seq desc)`. Reason: the nurse filter is `actor = me or admission_id in (admitted)`; nearly every event belongs to an admitted patient, so the backward scan of `ix_clinical_event_tenant_at` finds its 14 to 50 rows after a short scan, and an extra index would add a third index write to the fastest-growing table. Revisit if `explain` on a real hospital shows more than about 1,000 rows filtered per page (then add it in its own migration, `create index concurrently`, no transaction).

## Tenant scope and RLS
Unchanged. `tenant_id` not null, ENABLE and FORCE row level security, policies for `sahaj_app` on `tenant_id = platform.current_tenant()` (select, insert, update; no delete). The update policy already has `with check`, so a row cannot be moved to another clinic. Composite FK to `ipd.admission (tenant_id, id)` unchanged.

## Grants (sahaj_app) after this migration
- `select`
- `insert (admission_id, ews_score, ews_risk, target_minutes, raised_by)` (unchanged)
- `update (ews_score, ews_risk, target_minutes, version, status, acknowledged_by, response_action, response_note, senior_calls, closed_by)` (the last six are new)
- Not granted (42501 if named): `id`, `tenant_id`, `admission_id`, `raised_at`, `raised_by`, `acknowledged_at`, `closed_at`, `reopened_at`. The trigger sets the three times; a BEFORE trigger may write columns outside the caller's grant. This is a deliberate tightening of the story's list (ADR-007 D1): the app can never back-date or forward-date an acknowledgement, close or re-open.
- No delete, no truncate (existing triggers, SQLSTATE 42501).

## Guard `ipd.guard_ews_escalation_update()` (SECURITY INVOKER, `search_path = pg_catalog`, revoked from public and `sahaj_app`, SQLSTATE 23514)
Order of the rules (the first three keep their SAH-30 messages and position): DONE row refused; score falls; risk falls; version falls; identity fields change; version rule; senior-call rule; response-only-when-closing; then the transition.

| From → to | Allowed when | Trigger sets | `version` |
|---|---|---|---|
| DONE → anything | never | | |
| OPEN → OPEN | a score update (score never lower, risk never lower, target agrees by check), or exactly one senior call alone; `acknowledged_by` stays null | | +1 with a score/risk/target change; unchanged for a senior call |
| OPEN → ACK | `old.acknowledged_by` null, `new.acknowledged_by` set; score, risk, target unchanged; no response fields | `acknowledged_at = clock_timestamp()` | +1 |
| ACK → ACK | `acknowledged_by` unchanged; risk unchanged (a score rise inside the risk, or no change) | | +1 with a score change |
| ACK → OPEN | `old.ews_risk = 'MEDIUM'`, `new.ews_risk = 'HIGH'`, `new.ews_score > old.ews_score`, `new.acknowledged_by` null (the app must send it); no response fields | `acknowledged_at = null`, `reopened_at = now()` | +1 |
| OPEN → DONE, doctor action | action set, `closed_by` set, `response_note` set; admission ADMITTED; `new.acknowledged_by = new.closed_by`; score, risk, target unchanged | `acknowledged_at = closed_at = clock_timestamp()` | +1 |
| ACK → DONE, doctor action | as above; `acknowledged_by` unchanged | `closed_at = clock_timestamp()` | +1 |
| OPEN or ACK → DONE, `CLOSED_AT_DISCHARGE` | admission DISCHARGED (an `exists`-style read of `ipd.admission` in the same transaction); note null; `acknowledged_by` unchanged (null from OPEN); `closed_by` set | `closed_at = clock_timestamp()`; acknowledgement fields kept | +1 |
| any other pair (OPEN → ACK with a changed score, ACK → OPEN without a MEDIUM → HIGH rise, ...) | refused | | |

Field rules in the same function:
- `id`, `tenant_id`, `admission_id`, `raised_at`, `raised_by` never change (refused for every role, the owner too).
- `acknowledged_at`, `closed_at`, `reopened_at`: overwritten by the trigger on the transitions above, otherwise required unchanged (a migrator that sets them directly is refused).
- `response_action`, `response_note`, `closed_by` change only on a move to DONE.
- `senior_calls` changes only OPEN → OPEN, by exactly +1, in an update that changes nothing else (so `version` is unchanged).
- `version`: exactly `old + 1` when any of `status`, `ews_score`, `ews_risk`, `target_minutes`, `acknowledged_by`, `response_action`, `response_note`, `closed_by` changes; unchanged otherwise; a lower value keeps the message "version cannot go down".
- The admission read is a plain `select` in the caller's transaction: no lock, and it runs under the caller's RLS. Under the Safety board's admission row lock (ADR-007 D3) the status cannot change between the app's check and the trigger. A role that cannot see the admission (for example the table owner with `ipd.admission` still under FORCE RLS) is refused for a DONE transition; test helpers that close as the owner must lift RLS on `ipd.admission` too.

SAH-30 compatibility (statements checked in `backend/src/main`): the only statement that updates `ipd.ews_escalation` is `JdbcEscalationRepository.RAISE_SCORE` (`set ews_score, ews_risk, target_minutes, version = version + 1 ... and ews_score < :score`). It changes the score and adds exactly 1, so the new guard accepts it on an OPEN row and on an ACK row inside the same risk. On an ACK row whose risk rises MEDIUM → HIGH it is refused (23514) on purpose: the new `VitalsService` must send the re-open update instead (status OPEN, `acknowledged_by = null`, score, risk HIGH, target 15, `version = version + 1`). No other statement in `backend/src/main` sets the escalation `version`; other `version = version + 1` hits are on admission, bed and emergency tables.

## What the java-developer needs (statements)
- Acknowledge: `update ipd.ews_escalation set status = 'ACK', acknowledged_by = :doctor, version = version + 1 where tenant_id = :t and id = :id and status = 'OPEN' and version = :sent`.
- Respond (doctor, from OPEN or ACK): `set status = 'DONE', response_action = :code, response_note = :note, closed_by = :doctor, version = version + 1`; from OPEN also `acknowledged_by = :doctor` (must equal `closed_by`); from ACK leave `acknowledged_by` out.
- Call senior: `set senior_calls = senior_calls + 1 where ... status = 'OPEN' and version = :sent and senior_calls = :seen` (do not touch `version`).
- Re-open (VitalsService): `set status = 'OPEN', acknowledged_by = null, ews_score = :s, ews_risk = 'HIGH', target_minutes = 15, version = version + 1 where ... status = 'ACK' and ews_risk = 'MEDIUM'`.
- Close at discharge: after the admission row is DISCHARGED: `set status = 'DONE', response_action = 'CLOSED_AT_DISCHARGE', closed_by = :user, version = version + 1` (no note, no `acknowledged_by`).
- Never send `acknowledged_at`, `closed_at`, `reopened_at` (42501).
- Read `acknowledged_at`, `closed_at`, `reopened_at`, `closed_by`, `response_action`, `response_note`, `senior_calls`, `version` as needed. `version` is bigint: use `getLong`.

## Races (QA, real database; all under the admission row lock, ADR-007 D3)
The trigger is the backstop; the lock and the `where status = ... and version = :sent` give the answers. Because every writer holds the admission `for no key update` (or `for update` for the discharge) first, two writers never run the trigger on the same row at once. If the lock were skipped, the second UPDATE re-evaluates its `where` after the first commits (READ COMMITTED) and matches no row, or hits the trigger (23514) when it targets the row by id only: either way no double write.
| Race | Outcome the database must give |
|---|---|
| Two Acknowledges | one OPEN → ACK; the second sees ACK (0 rows by `status = 'OPEN'`) |
| Acknowledge against a re-opening vitals save | ack first, save second: ACK → OPEN with `reopened_at`; save first, ack second: version differs, 0 rows |
| Close against discharge | close first: discharge then sees DONE, nothing to close; discharge first: a doctor close is refused by the trigger (admission DISCHARGED) and the service answers 404 |
| Call senior against close | close first: the senior call is refused (`status = 'OPEN'`, trigger would refuse too) |
| Discharge (closed at discharge) against a new vitals save | serialised by the admission lock; a save after the discharge finds DISCHARGED (404) |

## Expected rows, retention, compatibility
- Rows: unchanged by this story (0.3 to 0.5 escalations per admission, 4,000 to 7,000 per hospital per year, under 1 million for 100 hospitals, SAH-30 note). The new index adds about 40 bytes a row. No partitioning.
- Retention: never deleted (no delete privilege, delete and truncate triggers). The plan text is in-patient record; the period is the human's (ADR-006 Q10, OQ-33-16).
- Privacy: `response_note` is clinical text, shown to OWNER, DOCTOR and NURSE only, never logged, audited or put in `ipd.clinical_event.text`.
- Compatibility: additive (two nullable columns, checks every existing row satisfies, wider grant, a stricter trigger). The SAH-30 app version runs against the new schema unchanged as long as no escalation is ACK (only SAH-33 code creates ACK rows). Rollback before any ACK/DONE row exists: a new forward migration that restores the V2026_11_25_1000 function and drops the new checks, index and columns.

## QA test list (real database, as `sahaj_app` and as the owner)
Positive: every transition of the table above as `sahaj_app` with the exact statements above, checking the stamped times (`acknowledged_at`, `closed_at`, `reopened_at` from the database clock, not the values sent), `version` +1, senior call leaves `version`.
Negative (each 23514, message contains "escalation", nothing changed):
1. DONE row: any update, `version = version + 1` included (app and owner).
2. Score down; HIGH → MEDIUM; version down (existing messages kept).
3. Version: a tracked change with `version` unchanged, `+2`, or lower; `version = version + 1` alone; `version = 3000000000` alone.
4. OPEN → ACK with `acknowledged_by` null; ACK → ACK with another `acknowledged_by`; OPEN → ACK with a changed score; ACK → ACK with a risk rise.
5. ACK → OPEN with MEDIUM → MEDIUM, HIGH → HIGH, a lower or equal score, `acknowledged_by` not null.
6. OPEN → DONE with no action, no `closed_by`, no note (the trigger answers first; the checks `ck_ews_escalation_done_action`, `_done_closed_by`, `_response_note_done` are the second line and can only be proved with the trigger disabled in a rolled-back owner transaction), an unknown code (`ck_ews_escalation_response_action`), a note of 0 and 1001 characters (`ck_ews_escalation_response_note`), `acknowledged_by <> closed_by` from OPEN, a doctor code on a DISCHARGED admission, `CLOSED_AT_DISCHARGE` on an ADMITTED admission, `CLOSED_AT_DISCHARGE` with a note, with a changed `acknowledged_by`.
7. Senior call: -1, +2, on ACK, together with a score change, together with a `version` change.
8. `response_action`, `response_note` or `closed_by` set while the status does not become DONE.
9. Columns outside the grant: `raised_at`, `raised_by`, `acknowledged_at`, `closed_at`, `reopened_at`, `id`, `tenant_id`, `admission_id` as `sahaj_app` (42501); as the owner `raised_at`, `raised_by`, `admission_id`, `id` (23514) and a direct `closed_at`/`reopened_at`/`acknowledged_at` change on a non-owning transition (23514).
10. Catalog: update privileges of `sahaj_app` on the table are exactly the ten columns listed; `closed_by` and `reopened_at` exist; the trigger `trg_ews_escalation_guard_update` is enabled; function revoked from public and `sahaj_app`; `ix_ews_escalation_tenant_admission` exists.
11. A closed-at-discharge row from OPEN keeps `acknowledged_by` and `acknowledged_at` null; from ACK it keeps both; a doctor close from OPEN sets `acknowledged_at = closed_at`.
12. Another clinic's escalation: update affects 0 rows (RLS); an update with another `tenant_id` is refused (42501).
13. Races of the table above with two connections.

## Stale rows in `sahaj_test` (resolved with NOT VALID)
`sahaj_test` is not cleaned between runs and holds DONE rows left by the SAH-30 test helper `VitalsSupport.closeEscalation` (committed as the owner: `status = 'DONE', closed_at = now()`, no action). Validated checks failed on them. The four checks a legacy row can violate are therefore `NOT VALID` (see "Check validation" above); the migration applies without a database reset. The helper must still be rewritten (next section) so new rows satisfy the enforced checks.

## Existing tests that must change (qa-engineer; they test the SAH-30 shape)
All in `backend/src/test/java/com/sahaj/platform/it/`.
- `VitalsSupport.closeEscalation` (line about 494) and `acknowledgeEscalation` (about 507) update the table as the owner with `status`, `closed_at`, `acknowledged_at` and no `version`. They now fail the guard and the checks. Rewrite them with the statements above (`version = version + 1`, `response_action`, `response_note`, `closed_by` for a close), and list `ipd.admission` in the `asOwner` table array as well (the guard reads the admission; without it the owner sees no row and the doctor-code close is refused).
- `VitalsTableIT.sahajAppMayUpdateOnlyScoreRiskTargetAndVersionOfAnEscalation`: `status = 'ACK'`, `acknowledged_by`, `response_action`, `response_note`, `senior_calls` are now granted columns (no longer 42501; the guard or a check answers instead). Keep `raised_at`, `raised_by`, `acknowledged_at`, `closed_at`, `admission_id`, `tenant_id`, `id` and add `reopened_at` as 42501. The "granted together with a not granted column" case (`status = 'DONE'` with the score) is now 23514, so use `closed_at` for the 42501 case.
- `VitalsTableIT.theGuardTriggerRefusesALowerScore...`: the positive controls `set ews_score = 7, version = version + 1` (nothing tracked changes), `set ews_score = 9` (no version bump) and the Medium to High update without `version + 1` are now refused; give each the exact `version = version + 1` (and drop the first, or expect 23514). The `closeEscalation` call follows the helper change; the `closed` message check still holds.
- `VitalsTableIT.theStateColumnsKeepTheirPairingChecks`: the owner updates `status = 'DONE'`, `closed_at = now()`, `acknowledged_by = gen_random_uuid()` now stop in the guard before the checks, with different messages (and `ck_ews_escalation_ack_pair` can no longer be reached by an `acknowledged_by` update on an OPEN row). `status = 'GONE'` is also reported by the guard first ("status change is not allowed") and `senior_calls = -1` by the guard (senior calls rule). Assert the new messages, or prove the constraints with the trigger disabled in a rolled-back owner transaction (item 6).
- `VitalsTableIT.theEscalationVersionIsABigint...`: the positive controls `version = version + 1` alone and `version = 3000000000` alone are now refused (23514, "version changes only together with a change"). `version = version` stays accepted. The negative cases (`version = 0`, `-1`, `-5`, `ews_score = 9, version = 0`, owner `version = 0`) still give "version cannot go down".
- `VitalsTableIT.theTableOwnerIsStoppedByTheGuardTriggerToo` and the downgrade/lower-score negatives: unchanged (rules and order kept).
- `VitalsEscalationIT.anAcknowledgedEscalationIsStillOpenAndKeepsItsAcknowledgementWhenTheScoreRises` (about line 210): expects "3|MEDIUM|60|ACK|0" after the helper (version is now 1 after the acknowledge) and that a MEDIUM → HIGH save leaves the escalation ACK (the old L3 behaviour). Under ADR-007 D4 that save re-opens the escalation (status OPEN, acknowledgement cleared, outcome REOPENED, `reopened_at` set). The same-risk score rise inside ACK stays ACK. Line 253 (`"7|HIGH|15|DONE|0"`) becomes version 1 when the close helper adds 1.
- `JdbcVitalsRepositoriesTest` (unit, string checks) is not affected: the insert statement is unchanged; the RAISE_SCORE statement is accepted by the new guard.

## Fix pass 2: `ipd.ews_senior_call` (ADR-007 R4; migration `V2026_11_27_1000__ipd_senior_call.sql`)
Forward-only and additive. `ipd.ews_escalation` columns, grants, trigger and the four NOT VALID checks are unchanged (`senior_calls` stays smallint; the cap at 32,767 is in Java). One change on that table: `uq_ews_escalation_tenant_id unique (tenant_id, id)`, the target of the composite foreign key (id was already the primary key, so no existing row can violate it).

### Table (append-only, tenant-owned)
| Column | Type | Null | Default | Check |
|---|---|---|---|---|
| `id` | uuid | no | `gen_random_uuid()` | primary key |
| `tenant_id` | uuid | no | `platform.current_tenant()` | FK `platform.tenant` |
| `escalation_id` | uuid | no | | composite FK `(tenant_id, escalation_id)` to `ipd.ews_escalation (tenant_id, id)` (`fk_ews_senior_call_escalation`) |
| `called_by` | uuid | no | | signed-in user id (IPD actor convention), no FK |
| `outcome` | text | no | | `ck_ews_senior_call_outcome`: `CALLED` or `ALREADY_CALLED` |
| `call_no` | smallint | yes | | `ck_ews_senior_call_call_no` (1 to 32767); `ck_ews_senior_call_call_no_outcome`: `(outcome = 'CALLED') = (call_no is not null)` |
| `called_at` | timestamptz | no | `clock_timestamp()` | not insertable |

No `updated_*` or `version` columns (rows never change). `call_no` is the escalation's `senior_calls` after the call (CALLED only).

### Indexes
- `ix_ews_senior_call_tenant_escalation_user (tenant_id, escalation_id, called_by, called_at desc)`: the throttle read and the replay read (`where tenant_id = ? and escalation_id = ? and called_by = ?` with `called_at > clock_timestamp() - interval '60 seconds'`, or `outcome = 'CALLED' and call_no > ?`). Also supports the composite FK check from the child side (leading columns `tenant_id, escalation_id`).
- `ux_ews_senior_call_tenant_escalation_call_no (tenant_id, escalation_id, call_no) where outcome = 'CALLED'`: last defence behind the admission lock, one CALLED row per count (a second insert is 23505 and a code bug; 500, full rollback).

### RLS and grants (`sahaj_app`)
ENABLE and FORCE RLS; policies `p_ews_senior_call_select` and `p_ews_senior_call_insert` (to `sahaj_app`, `tenant_id = platform.current_tenant()`); no update, delete or truncate policy. Privileges: `select`, and `insert (escalation_id, called_by, outcome, call_no)` only. `id`, `tenant_id` and `called_at` always take their defaults, so the database clock cannot be back-dated and a row cannot be written for another clinic. No update or delete privilege. Triggers `trg_ews_senior_call_no_update_delete` and `trg_ews_senior_call_no_truncate` call `platform.deny_audit_mutation()` (SQLSTATE 42501) for every role, the owner included.

### Volume, partitioning, retention
At most one accepted row per user per escalation per 60 seconds, in practice one to three rows per escalation. Expected: well under 10,000 rows per hospital per year (100 hospitals: under 1 million a year in total); far below the 50 million row threshold, no partitioning. Nothing references the table, so `called_at` range partitioning stays possible later. Never deleted (part of the in-patient record, ADR-007 D15). Holds no name and no text.

### Compatibility
Additive. The app version before this fix never reads or writes the table and still works against the new schema (`senior_calls` and the guard trigger are unchanged).

### For the java-developer
- Table `ipd.ews_senior_call`; insert columns exactly `(escalation_id, called_by, outcome, call_no)`; `call_no` null for `ALREADY_CALLED`, the new `senior_calls` count for `CALLED`. Do not send `id`, `tenant_id` or `called_at` (42501).
- Throttle: `select 1 from ipd.ews_senior_call where tenant_id = :t and escalation_id = :e and called_by = :u and called_at > clock_timestamp() - interval '60 seconds' limit 1`. Own-call replay: `... and called_by = :u and outcome = 'CALLED' and call_no > :sent limit 1`.
- Do the CALLED insert in the same transaction as the `senior_calls + 1` update (guard trigger accepts exactly +1 with version unchanged). The new row's `called_at` is `clock_timestamp()`, so two writes of one transaction differ by microseconds; the throttle is read before any write.
- Errors: 42501 = a forbidden column or an update/delete attempt; 23503 = `fk_ews_senior_call_escalation` (escalation of another clinic or missing); 23514 = `ck_ews_senior_call_outcome`, `ck_ews_senior_call_call_no` or `ck_ews_senior_call_call_no_outcome`; 23505 = `ux_ews_senior_call_tenant_escalation_call_no`. All are code bugs here (500), none is a user-facing answer.

### QA test list (real database)
1. Append-only: as `sahaj_app`, `update`, `delete` and `truncate` give 42501 (privilege); as the table owner the same statements give 42501 from the trigger ("append-only"); rows unchanged.
2. Insert grant: `sahaj_app` insert of `(escalation_id, called_by, outcome, call_no)` succeeds with `tenant_id` and `called_at` defaulted (`called_at` within a second of `clock_timestamp()`); inserting `called_at`, `tenant_id` or `id` is 42501.
3. Composite FK: a row for an escalation id that belongs to another clinic (tenant A session, tenant B escalation) is 23503; so is a missing escalation id; the same through the owner with an explicit mismatching `tenant_id`.
4. RLS: tenant A cannot select tenant B's rows; insert with `tenant_id` of another clinic is refused; with no `app.tenant_id` set nothing is visible.
5. Checks: outcome `OTHER` is 23514; `CALLED` with null `call_no` and `ALREADY_CALLED` with a `call_no` are 23514; `call_no` 0 and 32768 are 23514; 32767 is accepted. Two `CALLED` rows with the same `call_no` on one escalation are 23505; two `ALREADY_CALLED` rows are accepted.
6. Throttle read path: with enough rows (for example 20,000 rows across escalations, `analyze`) `explain` of the throttle and replay queries uses `ix_ews_senior_call_tenant_escalation_user` (index scan, no sequential scan).
7. Catalog: RLS enabled and forced; the two policies exist and are `for select` and `for insert` only; `sahaj_app` has select on the table and insert on exactly the four columns, no update, delete or truncate; both triggers enabled; `uq_ews_escalation_tenant_id` exists.
