# Runbook: switch IPD on for one clinic

Why: IPD can only be switched on today by the clinic owner through a 30-day trial (flag `sahaj.modules.owner-ipd-trial`, DEC-010), after which it reads as read-only. DEC-059 (tech lead default 1, security audit #7) replaces that with an audited, per-clinic operator step. Script: `docs/database/runbook-ipd-on.sql`. Rounds 2 and 3 of the SAH-76 reviews changed how it is run and what it records: read "How to run" and "Run record" again even if you used it before.

## How the state is stored
`platform.module_entitlement`, one row per clinic, created on first write (no row means IPD off, HRA off, Pharmacy off, 25 beds). `ipd_state` is one of `off`, `trial`, `on`, `readonly`; `ipd_trial_ends_at` is only used by `trial`. The app turns a `trial` whose end has passed into `readonly` at read time and stores nothing for it (`ModuleEntitlements.effectiveIpdState`); a stored `on` never expires. So **"permanently on" is `ipd_state = 'on'` with `ipd_trial_ends_at` null: no schema change is needed.** The app reads the row on each request (no cache), so the change is live at once.

The column `pharmacy_on` (added by `V2026_12_17_1000`, default false) is not touched by this script. A clinic with no row gets one with the column default (false). The audit snapshot keeps the app's five keys (`ipdState`, `ipdTrialEndsAt`, `hraOn`, `bedLimit`, `ipdRequestedAt`); the app's snapshot (`ModulesService.snapshot`) does not include `pharmacy_on` either, so there is no drift.

## What the script does
1. Refuses to run, before any change, when `tenant_id`, `operator`, `approval` or `reason` is missing, empty or malformed, when the old `dry_run` variable is given, when it is not run as `sahaj_migrator`, or when the tenant id is not a uuid or does not exist. Each refusal is an error raised before `begin`, so **psql exits with code 3** (see "Exit codes"). The messages never repeat the value that was given.
2. Says at the top whether this is a **DRY RUN** or a **REAL RUN**, and prints the database, `session_user`, client address and application name (on your screen only).
3. In one transaction: `set local app.tenant_id` to the target clinic; prints the entitlement before; sets `ipd_state = 'on'`, `ipd_trial_ends_at = null`, `version + 1`; leaves HRA, Pharmacy, bed limit and the request time as they are (a clinic with no row gets the app defaults).
4. Writes one `platform.audit_event` row like the app does: entity `MODULE_ENTITLEMENT`, the same five before and after keys as the app, action `MODULE_IPD_SET_ON_RUNBOOK`. The actor column is a uuid, so it is null. `after_json` adds **only** `actor` = `ops-runbook` and `approval`. The change history screen shows it with no person name.
5. Prints the entitlement after and the latest audit rows, checks in `pg_class` that row level security and FORCE are back on for the three tables, then **commits only when `-v commit=yes`**; every other case rolls back.
6. After the COMMIT or ROLLBACK, prints the **Run record** (see below).
7. Already on with no trial end: prints a notice and writes nothing, no audit row (safe to repeat).

`sahaj_migrator` cannot bypass row level security and the three tables have policies for `sahaj_app` only, so the script lifts `FORCE ROW LEVEL SECURITY` on `platform.tenant`, `platform.module_entitlement` and `platform.audit_event` inside the transaction and restores it before the commit (same method as `V2026_12_15_1000`). This takes a short exclusive lock on those tables (the lock waits at most 5 seconds, then the script fails and changes nothing). Run it off peak. The transaction also has `statement_timeout` 30 s and `idle_in_transaction_session_timeout` 30 s, so a stalled session is ended by the server and rolled back.

## Required values
| Variable | What to give | Rules |
| --- | --- | --- |
| `tenant_id` | the clinic's `platform.tenant.id` (uuid), from the human who approved the go-live | must be a uuid (8-4-4-4-12 hex) and an existing clinic |
| `operator` | your staff reference (employee or ops account id) | 1-64 characters: letters, digits and `. _ : / # -`. A name with a space is refused. Refused too when, after removing `- . _ : / # +`, it still has 10 or more digits in a row (so `98765.43210` and `98765-43210` are refused, like a plain mobile number). A staff reference with 10 or more digits in a row cannot be used: ask for a shorter one. |
| `approval` | the decision id or ticket that approved this go-live, for example `DEC-nnn` | same characters, 1-64. No digit guard: ticket ids can be long. |
| `reason` | short text, one line | 1-200 characters, no control characters. Refused when, after removing spaces, dots and dashes, it has 10 or more digits in a row (so `98765 43210` is refused). No patient data, no names, no mobile numbers. |
| `commit` | `yes` to save | optional; any other value, or none, is a dry run |

Values are passed to SQL with psql `:'var'` quoting only (no shell or SQL string building).

## Exit codes
Check with `echo $?` straight after the command.
- **0**: a good dry run, or a real run that finished (committed).
- **3**: a refusal (missing or bad input, `dry_run` given, wrong role, unknown tenant) or any other error (for example a lock timeout). Nothing was saved. psql prints `ERROR:  Refused: ...` (two spaces after the colon; that is psql's own format).
- 1 or 2 (psql could not connect or start): nothing ran.

A refusal prints no Run record. If the exit code is not 0, do not record the run as done.

## How to run
Needs `psql` 12 or later and the migrator credentials from the operator's own secret store (never from this repository; never paste the password into a ticket or a command line; use `PGPASSFILE` or the prompt).

**Run it with `psql -f` only. Never `\i`, never paste it into an open psql session.** An aborted transaction in an interactive session would keep exclusive locks on the three tables.

```
# 1. Dry run (the default): runs everything, prints before and after, then ROLLBACK.
psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v operator=<staff ref> -v approval=<DEC id or ticket> -v reason='<short text>' -f docs/database/runbook-ipd-on.sql
echo $?    # expect 0

# 2. Real run: the same command plus -v commit=yes (exactly this spelling).
psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v operator=<staff ref> -v approval=<DEC id or ticket> -v reason='<short text>' -v commit=yes -f docs/database/runbook-ipd-on.sql
echo $?    # expect 0
```
Do the dry run first and read it: the top line must say DRY RUN, the BEFORE and AFTER blocks must be for the right clinic, the line before the Run record must say `DRY RUN: rolled back`, and the exit code must be 0. Then run the real command. If `commit` is missing, mistyped (`Yes`, `1`, `true`) or anything but `yes`, the script rolls back and says it was a dry run. The old `-v dry_run=...` is refused (it used to commit when its value was `0`; it is gone).

Output shows ids, states and times only: no patient data, no secrets.

### Negative tests (once, on a non-production database)
Each of these must print `ERROR:  Refused: ...` and exit with code 3. Use placeholders for the other values; none of them may change anything.

| Command change | Expected |
| --- | --- |
| leave out one of `-v tenant_id`, `-v operator`, `-v approval`, `-v reason` | Refused: all four are required; exit 3 |
| add `-v dry_run=0` | Refused: dry_run is no longer used; exit 3 |
| `-v operator=98765.43210` | Refused: operator looks like a mobile number; exit 3 |
| `-v operator=98765-43210` | same; exit 3 |
| `-v reason='call 98765 43210'` | Refused: reason holds a mobile number; exit 3 |

## Why the tenant audit row has no operator, reason or machine details (security N-1)
The audit row is written into the clinic's own `platform.audit_event`, and the clinic OWNER can see it in the change history. That is customer-visible. The operator's staff reference, the free-text reason, the database user names, the client address (our network) and the application name are internal facts about how we run the platform, and the reason could carry a name or number by mistake. They are not in the row. The row keeps `actor` = `ops-runbook` and `approval` (the decision id or ticket), which is what the clinic may see. The internal facts are printed in the Run record instead, which the human keeps in `docs/decisions` (internal). The tenant row and the Run record are tied together by the tenant id, the approval id and the time: the Run record's UTC time is the audit row's `at` (both are the transaction start time).

## Run record (human)
After every run the script prints a block `--- Run record (copy into docs/decisions) ---` in expanded form. It is on the operator's screen only and is not stored in the database. For each **real** run, the human copies it into `docs/decisions` using this template (a dry run needs no record, but save the first one as the SAH-76 merge condition 4 evidence):

```
# Run record · IPD on · <clinic name or slug> · <date IST>
Story: SAH-76 · Runbook: docs/database/runbook-ipd-on.sql
outcome:            COMMITTED
tenant_id:          <uuid>
operator:           <staff ref>
approval:           <DEC id or ticket>
reason:             <text>
session_user:       <database login role>
current_user:       <role the script ran as; sahaj_migrator>
client_address:     <ip, or "none (unix socket)"; behind a proxy this is the proxy>
application_name:   <as sent by the client; a statement, not proof>
database:           <database name>
run_time_ist:       <24 Oct 2026, 11:05:12 AM IST>
run_time_utc:       <2026-10-24T05:35:12.123456Z, equals the audit row "at">
audit_row_saved:    yes   (no when IPD was already on, or on a dry run)
exit code:          0
checked afterwards: <owner sees IPD "on"; audit row seen in the change history>  (who and when)
```
`audit_row_saved` is `yes` only when the run committed and inserted the row. If `outcome` is COMMITTED and `audit_row_saved` is `no`, IPD was already on and nothing was written.

## Human and ops items
- **`log_connections = on` for production** (human/ops): `session_user` and the client address in the Run record are only as good as the server's own connection log. `sahaj_migrator` is a shared role, so the connection log, the secret store's access log and the `operator` value together tie a run to a person. Ask ops to keep the connection log for as long as the Run records.
- Ops to confirm that the migrator password is held by named people only, and rotated when one of them leaves.
- `application_name` and `operator` are typed or set by the operator, so treat them as statements, not proof.

## Check afterwards
- Output ends with `COMMITTED.`, the AFTER block shows `ipd_state = on` and an empty `ipd_trial_ends_at`; the audit row list shows `actor_marker = ops-runbook` and your `approval`; the Run record follows; the exit code is 0.
- The clinic owner opens Plan and modules: IPD reads "on". The owner trial button is gone for that clinic (the app offers it only when IPD is off).
- To undo: there is no scripted undo. A change back is a new reviewed step (set `ipd_state` to `readonly` or `off` the same way, with its own audit row).

## Limits
- One clinic per run. No bulk mode, on purpose.
- Does not change the bed limit, HRA or Pharmacy. Set those through the app.
- Not for use by the app or by `sahaj_app`. The admin realm that would do this from a portal is parked (needs the role `sahaj_admin_definer`, a human decision).
- If the Run record is lost (the terminal closed) after a commit, the facts can only be rebuilt from the connection log and the secret store's access log; keep the output until it is filed.
- Not run against a database by the db-engineer yet: the first dry run is the human's (SAH-76 merge condition 4). This round was checked by reading only.
