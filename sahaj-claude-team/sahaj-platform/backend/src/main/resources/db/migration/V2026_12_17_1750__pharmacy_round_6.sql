-- SAH-79 round 6 (DEC-082, SAH-78 handoff decision D-5). Additive.
-- 1. pharmacy.approval_request.rule_version_id becomes nullable: null means "the served Standard default rule (version 0)", which has no
--    row in pharmacy.approval_rule_version. The composite foreign key (tenant_id, rule_version_id) is kept: with MATCH SIMPLE (the default)
--    a null in any referencing column skips the check, so a null version id inserts and updates without a rule version row.
--    The guard trigger pharmacy.guard_approval_request_update (1360) compares rule_version_id with "is distinct from", which treats
--    null = null as equal and null <> value as a change, so it needs no change. No other function, view or check reads this column
--    (searched every migration: only 1360 names it).

set local lock_timeout = '30s';

alter table pharmacy.approval_request alter column rule_version_id drop not null;

comment on column pharmacy.approval_request.rule_version_id is
  'The rule version used (BR-12); a later rule change does not touch this request. Null = the served Standard default (version 0), which is not a stored row. When not null, the foreign key to pharmacy.approval_rule_version applies; when null it is skipped (MATCH SIMPLE). Never changes after insert (guard trigger).';
