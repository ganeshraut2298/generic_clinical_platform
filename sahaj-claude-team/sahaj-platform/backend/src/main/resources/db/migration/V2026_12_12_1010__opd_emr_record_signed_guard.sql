-- SAH-51 (ADR-025 D3, "Data needed" items 1 and 7) · A signed EMR record is immutable in the database.
-- Forward-only. Two trigger functions: a new one on opd.emr_record, and a replacement of opd.deny_chemo_order_change
-- (V2026_12_11_1110) that also freezes a DRAFT chemotherapy order of a signed record. Nothing is dropped, renamed or
-- rewritten; the existing triggers trg_chemo_order_immutable and trg_chemo_order_no_truncate keep calling the same
-- function name.
--
-- 1. (see also 1b below, the before-insert DRAFT-only trigger.)
--    opd.emr_record, before update or delete, for each row. When the OLD row is SIGNED:
--      * every change is refused (SQLSTATE 42501, message contains "immutable"), except
--      * print_language, paper_size, updated_at, updated_by (not clinical: SAH-55 print choices), and
--      * patient_id, only from null to a value, only on an ER_ASSESSMENT record (an Emergency case has no patient until it is
--        admitted or sent to OPD, ADR-005, ADR-025 D9 and D10); a second change of patient_id is refused because it is then
--        no longer null.
--    The rule is an allow-list written on the whole row (to_jsonb), so a column added later is frozen by default and
--    status can never leave SIGNED. A DELETE of a SIGNED row is refused too (the application has no delete grant anyway).
--    A DRAFT to SIGNED update passes (the old row is DRAFT): the sign writes status, signed_*, sign_*, clears the hold and
--    adds 1 to revision in that one update. After it, revision, sections, edit hold and every other column are frozen: a print
--    preference save or the ER patient link must not touch revision.
--    On a partitioned table the trigger on the parent is cloned onto every partition (existing, default and future).
create or replace function opd.guard_signed_emr_record()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
  allowed text[] := array['print_language', 'paper_size', 'updated_at', 'updated_by'];
begin
  if old.status <> 'SIGNED' then
    if tg_op = 'DELETE' then
      return old;
    end if;
    return new;
  end if;
  if tg_op = 'DELETE' then
    raise exception 'opd.emr_record is immutable once signed (DELETE refused)'
      using errcode = 'insufficient_privilege';
  end if;
  if old.encounter_type = 'ER_ASSESSMENT' and old.patient_id is null and new.patient_id is not null then
    allowed := allowed || array['patient_id'];
  end if;
  if (to_jsonb(new) - allowed) is distinct from (to_jsonb(old) - allowed) then
    raise exception 'opd.emr_record is immutable once signed: a correction is an addendum (UPDATE refused)'
      using errcode = 'insufficient_privilege';
  end if;
  return new;
end;
$$;

revoke all on function opd.guard_signed_emr_record() from public;
revoke all on function opd.guard_signed_emr_record() from sahaj_app;

create trigger trg_emr_record_signed_guard
  before update or delete on opd.emr_record
  for each row execute function opd.guard_signed_emr_record();

comment on function opd.guard_signed_emr_record() is
  'SAH-51 (ADR-025 D3): once an EMR record is SIGNED, an update changes only print_language, paper_size, updated_at, updated_by, or patient_id from null to a value on an ER_ASSESSMENT record; every other change and every delete is refused (42501, immutable). Allow-list on the whole row, so later columns are frozen by default.';

-- 1b. opd.emr_record, before insert, for each row: a new record must be a DRAFT (defence in depth, security review SAH-51-68 I2).
--    The update guard above never sees an INSERT, and sahaj_app holds a table-level insert grant, so a buggy insert could
--    create a row that is already SIGNED and was never checked. A record becomes SIGNED only by the DRAFT to SIGNED update.
--    Refused with SQLSTATE 23514 (check_violation: the value of status is wrong, no privilege is missing). A BEFORE ROW
--    trigger on a partitioned table is allowed since PostgreSQL 13; the one on the parent is cloned onto every partition
--    (existing, default and future), fires on the partition the row is routed to, and also covers a direct insert into a
--    partition. It does not change the row, so partition routing is not affected. It runs before the check constraints, so
--    an insert with status SIGNED now fails here and not on ck_emr_record_signed.
create or replace function opd.require_draft_emr_record_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.status is distinct from 'DRAFT' then
    raise exception 'opd.emr_record must be inserted as DRAFT: a record becomes SIGNED only by the sign update'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

revoke all on function opd.require_draft_emr_record_insert() from public;
revoke all on function opd.require_draft_emr_record_insert() from sahaj_app;

create trigger trg_emr_record_insert_draft
  before insert on opd.emr_record
  for each row execute function opd.require_draft_emr_record_insert();

comment on function opd.require_draft_emr_record_insert() is
  'SAH-51 (security review I2): a new opd.emr_record row must have status DRAFT (23514 otherwise); SIGNED is reached only by the DRAFT to SIGNED update, which opd.guard_signed_emr_record lets through.';

-- 2. opd.chemo_order: a DRAFT order can no longer change once its record is SIGNED (the order row carries the record's key).
--    A SENT or HELD row stays immutable, and every DELETE and TRUNCATE stays refused, as before. The look-up reads the
--    record under the caller's tenant (security invoker): the sign sets the record to SIGNED last, so any order write
--    that belongs to the sign (none today) must come before that update. A corrected order (supersedes_order_id set) is
--    a new row; once its record is signed it is frozen like any other DRAFT order (relax in a later migration if the
--    correction flow needs it).
create or replace function opd.deny_chemo_order_change()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if tg_op = 'UPDATE' and old.status = 'DRAFT' then
    if exists (select 1
                 from opd.emr_record r
                where r.tenant_id = old.tenant_id
                  and r.id = old.record_id
                  and r.created_at = old.record_created_at
                  and r.status = 'SIGNED') then
      raise exception 'opd.chemo_order of a signed EMR record is immutable (UPDATE refused)'
        using errcode = 'insufficient_privilege';
    end if;
    return new;
  end if;
  raise exception 'opd.chemo_order is immutable once sent or held and is never deleted (% refused)', tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

comment on function opd.deny_chemo_order_change() is
  'SAH-66 and SAH-51: a SENT or HELD chemotherapy order is immutable; a DRAFT order is frozen once its EMR record is SIGNED; no delete and no truncate. SQLSTATE 42501.';
