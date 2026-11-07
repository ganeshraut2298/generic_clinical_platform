-- SAH-38 (QA finding B3): two more indexes on platform.staff_pin_colleague_event that carry `kind`. Forward-only, additive,
-- no data change, nothing dropped or renamed. The earlier migration V2026_12_01_1000__platform_staff_pin_colleague_tries.sql
-- is not edited.
-- Why: every statement on this table names kind = '...' (the one exception is the owner history, kind <> 'NOTICE_SEEN').
-- The first two indexes stop at `at` and have no `kind`, so QA's plans (docs/database/SAH-38.md section 7) read a person's
-- or a pair's whole history and throw most of it away as a filter: the latest-RIGHT_AFTER_WRONG probe removed 12,814
-- rows to find none; the latest-NOTICE_SEEN lookup fell back to a parallel sequential scan (175 ms).
-- Plain `create index` (no `concurrently`): the table is narrow and rare (at most about 2.6 million rows a year across all
-- clinics at the extreme planning figure), so the build holds its share lock for a short time. Tables are owned by
-- sahaj_migrator (Flyway runs as it); an index needs no grant.

-- Pair cap, and the pair's latest RIGHT_AFTER_WRONG:
--   count(*) ... where tenant_id, membership_id, by_membership_id, kind = 'WRONG', at > :windowStart
--   max(at)  ... where tenant_id, membership_id, by_membership_id, kind = 'RIGHT_AFTER_WRONG'
-- All five columns are index conditions; the count can be an index-only scan (the table is append-only, so the
-- visibility map stays set), the max is one step at the end of the range.
create index if not exists ix_staff_pin_colleague_event_pair_kind
  on platform.staff_pin_colleague_event (tenant_id, membership_id, by_membership_id, kind, at);

-- One person's events by kind:
--   max(seen_through) ... where tenant_id, membership_id, kind = 'NOTICE_SEEN'                  (latest "Got it")
--   count(*), max(at) ... where tenant_id, membership_id, kind = 'WRONG', at > :marker          (notice count)
--   exists / count    ... where tenant_id, membership_id, kind = 'WRONG', at > :after [and at <= :through]
create index if not exists ix_staff_pin_colleague_event_member_kind
  on platform.staff_pin_colleague_event (tenant_id, membership_id, kind, at);

comment on index platform.ix_staff_pin_colleague_event_pair_kind is 'SAH-38: pair cap count and latest RIGHT_AFTER_WRONG of one (owner, caller) pair; kind and at are index conditions. Supersedes ix_staff_pin_colleague_event_pair, which is kept until a human decides to drop it.';
comment on index platform.ix_staff_pin_colleague_event_member_kind is 'SAH-38: latest NOTICE_SEEN, notice count and WRONG counts of one person; kind and at are index conditions. The older ix_staff_pin_colleague_event_member stays for the owner history (kind <> NOTICE_SEEN, bounded by at).';
