-- SAH-80 (DEC-091 item 2, review SAH-80-code Major 2). A discount approval is single use: one saved bill per discount request.
-- Adds a unique partial index pharmacy.bill (tenant_id, discount_request_id) where discount_request_id is not null, as the database backstop
-- under the service check (the service locks the approval request FOR SHARE and refuses a request that already has a bill).
-- Additive: one index, no column, no function, no data change. The old non-unique ix_bill_discount_request (1900) is left in place; it is now
-- redundant and is dropped in a later release (expand and contract).
-- The time of approval needed for "valid only on the IST day of approval" already exists: pharmacy.approval_decision.decided_at of the
-- APPROVED decision of the request (final level). No column is added.

set local lock_timeout = '30s';

-- Stop with a clear message, before the index, if any tenant already has two bills on one discount request (not expected: sales are not live).
do $$
declare
  v_dupes bigint;
begin
  select count(*) into v_dupes from (
    select 1 from pharmacy.bill where discount_request_id is not null
    group by tenant_id, discount_request_id having count(*) > 1) d;
  if v_dupes > 0 then
    raise exception 'pharmacy.bill has % discount request(s) used on more than one bill; resolve them before this migration', v_dupes;
  end if;
end $$;

create unique index if not exists uq_bill_discount_request on pharmacy.bill (tenant_id, discount_request_id)
  where discount_request_id is not null;

comment on index pharmacy.uq_bill_discount_request is
  'A discount approval is single use (DEC-091): at most one bill per sale_discount_request. A second insert fails with SQLSTATE 23505.';
