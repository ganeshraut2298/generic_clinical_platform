-- SAH-30 review fix pass (code review M7, N2, N3; security review L5). Forward-only; V2026_11_23_1000 is not edited.
-- ipd.ews_escalation is empty or tiny (under 1 million rows for 100 hospitals), so the checks and the type change below
-- are safe in one transaction. The checks are plain (validated) because every existing row must already satisfy them.

-- 1. Score and risk consistency on the escalation (N2, L5).
-- HIGH iff total score is 7 or more (EarlyWarningScore). An escalation exists only for MEDIUM or HIGH. MEDIUM comes from a
-- total of 5 or 6, or from one parameter scoring 3 (the total is then 3 or more), so a MEDIUM score is never below 3.
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_risk_score check ((ews_score >= 7) = (ews_risk = 'HIGH'));
alter table ipd.ews_escalation
  add constraint ck_ews_escalation_score_min check (ews_score >= 3);

-- 2. version integer -> bigint (N3, rule: version bigint). Safe: small table, rewrite under a short lock; the column
-- privilege (update on version), the default 0 and the guard trigger stay; no index or check uses the column; the
-- previous app version reads it with getInt/getLong, both fine for small values.
alter table ipd.ews_escalation alter column version type bigint;

-- 3. Guard: the version never goes down (L5). Equal or higher passes. SECURITY INVOKER, fixed search_path, 23514.
create or replace function ipd.guard_ews_escalation_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status = 'DONE' then
    raise exception 'early warning escalation is closed (update refused)'
      using errcode = 'check_violation';
  end if;
  if new.ews_score < old.ews_score then
    raise exception 'early warning escalation score cannot go down'
      using errcode = 'check_violation';
  end if;
  if old.ews_risk = 'HIGH' and new.ews_risk <> 'HIGH' then
    raise exception 'early warning escalation risk cannot go down'
      using errcode = 'check_violation';
  end if;
  if new.version < old.version then
    raise exception 'early warning escalation version cannot go down'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_ews_escalation_update() from public;
revoke all on function ipd.guard_ews_escalation_update() from sahaj_app;

comment on function ipd.guard_ews_escalation_update() is 'SAH-30 guard: a DONE early warning escalation is never updated, and while open its score, risk and version never go down. SQLSTATE 23514.';
comment on table ipd.ews_escalation is 'IPD early warning escalation (SAH-30). At most one not-DONE row per admission (partial unique index). Never deleted (privilege and trigger). Score is 3 or more and HIGH exactly when the score is 7 or more (checks). sahaj_app may update only ews_score, ews_risk, target_minutes and version; trigger refuses any update of a DONE row and any fall of score, risk or version. The acknowledge/response/senior-call/close columns belong to the Safety board story. No message is sent to anyone in phase 1.';

-- 4. Partition runbook comments (M7). Replaces the "metadata-only step" wording of V2026_11_23_1000.
comment on table ipd.vitals is 'IPD nursing vitals (SAH-30). Append-only: no update, delete or truncate (privilege and trigger); a wrong entry is followed by a new one. Clinical data: shown on screen only, never logged or audited with values. Fastest-growing IPD table. PARTITION PLAN (ADR-006 D2), at about 30 million rows, in a maintenance window as the migrator: no foreign key points at this table. (1) create ipd.vitals_p partitioned by range on recorded_at, primary key (id, recorded_at), one partition per quarter (monthly when growth is at the top of the range; the rule asks monthly, quarterly is the planned departure while volume is lower). (2) On THIS table first run create unique index concurrently on (id, recorded_at), and add check (recorded_at < cut-off) not valid then validate, so the attach finds a matching index and skips the scan. (3) attach this table as the first partition (not metadata-only without step 2: it builds the index and blocks writes), rename. (4) unique (tenant_id, client_key) cannot stay global on a partitioned table; keep a local unique index per partition and rely on the admission row lock for the double-save guard. (5) re-create RLS policies, grants and triggers on the parent; create future partitions ahead of time.';
comment on table ipd.clinical_event is 'IPD clinical event log (SAH-30; also written by SAH-31/32 and the Safety board). Append-only: no update, delete or truncate (privilege and trigger). Fixed-template text only: never a patient name or a clinical note. admission_id may be null. seq orders events of one transaction time. Partitionable on at like ipd.vitals (no FK points at it): same runbook, with create unique index concurrently on (id, at) on the old table before attach; quarterly, monthly at the top of growth; seq (identity) stays on the parent.';
