# Runbook: switch IPD on for one clinic

Why: IPD can only be switched on today by the clinic owner through a 30-day trial (flag `sahaj.modules.owner-ipd-trial`, DEC-010), after which it reads as read-only. DEC-059 (tech lead default 1, security audit #7) replaces that with an audited, per-clinic operator step. Script: `docs/database/runbook-ipd-on.sql`.

## How the state is stored
`platform.module_entitlement`, one row per clinic, created on first write (no row means IPD off, HRA off, 25 beds). `ipd_state` is one of `off`, `trial`, `on`, `readonly`; `ipd_trial_ends_at` is only used by `trial`. The app turns a `trial` whose end has passed into `readonly` at read time and stores nothing for it (`ModuleEntitlements.effectiveIpdState`); a stored `on` never expires. So **"permanently on" is `ipd_state = 'on'` with `ipd_trial_ends_at` null: no schema change is needed.** The app reads the row on each request (no cache), so the change is live at once.

## What the script does
1. Refuses to run as any role other than `sahaj_migrator`, or with a tenant id that is not a uuid or does not exist (it stops and nothing is changed).
2. In one transaction: `set local app.tenant_id` to the target clinic; prints the entitlement before; sets `ipd_state = 'on'`, `ipd_trial_ends_at = null`, `version + 1`; leaves HRA, bed limit and the request time as they are (a clinic with no row gets the app defaults).
3. Writes one `platform.audit_event` row like the app does: entity `MODULE_ENTITLEMENT`, the same before and after keys, action `MODULE_IPD_SET_ON_RUNBOOK`. The actor column is a uuid, so it is null; the marker `"actor": "ops-runbook"` is in `after_json`. The change history screen shows it with no person name.
4. Prints the entitlement after and the latest audit rows, checks that row level security is back on, then commits (or rolls back for a dry run).
5. Already on with no trial end: prints a notice and writes nothing (safe to repeat).

`sahaj_migrator` cannot bypass row level security and the three tables have policies for `sahaj_app` only, so the script lifts `FORCE ROW LEVEL SECURITY` on `platform.tenant`, `platform.module_entitlement` and `platform.audit_event` inside the transaction and restores it before the commit (same method as `V2026_12_15_1000`). This takes a short exclusive lock on those tables (the lock waits at most 5 seconds, then the script fails and changes nothing). Run it off peak.

## How to run
Needs `psql` 12 or later and the migrator credentials from the operator's own secret store (never from this repository; never paste the password into a ticket).
```
# 1. Dry run: runs everything, prints before and after, then ROLLBACK.
psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -v dry_run=1 -f docs/database/runbook-ipd-on.sql
# 2. For real: same command without dry_run.
psql -h <host> -U sahaj_migrator -d <database> -v tenant_id=<tenant uuid> -f docs/database/runbook-ipd-on.sql
```
The tenant uuid is the clinic's `platform.tenant.id` (from the human who approved the clinic's IPD go-live). Output shows ids, states and times only: no patient data, no secrets.

## Check afterwards
- Output ends with `COMMITTED.` and the AFTER block shows `ipd_state = on` and an empty `ipd_trial_ends_at`.
- The clinic owner opens Plan and modules: IPD reads "on". The owner trial button is gone for that clinic (the app offers it only when IPD is off).
- To undo: there is no scripted undo. A change back is a new reviewed step (set `ipd_state` to `readonly` or `off` the same way, with its own audit row).

## Limits
- One clinic per run. No bulk mode, on purpose.
- Does not change the bed limit or HRA. Set those through the app.
- Not for use by the app or by `sahaj_app`. The admin realm that would do this from a portal is parked (needs the role `sahaj_admin_definer`, a human decision).
