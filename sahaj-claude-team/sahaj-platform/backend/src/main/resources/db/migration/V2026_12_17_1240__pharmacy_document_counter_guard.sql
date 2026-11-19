-- SAH-78 WP-C review fix round 1 (security L5). Forward migration; V2026_12_17_1220 is NOT edited.
-- sahaj_app may update pharmacy.document_counter (the grant is needed: pharmacy.next_document_no() runs as the caller and does
-- INSERT ... ON CONFLICT DO UPDATE). A bug or a direct statement could therefore make next_value go down and issue a document
-- number a second time. This trigger is the backstop. It adds one function and one trigger; no table, column or row changes.
--
-- What next_document_no() does to an existing row: "set next_value = c.next_value + 1, updated_at = now()". So a normal call
-- always makes next_value grow by exactly 1, and the trigger accepts any strictly increasing value (new > old). An equal value is
-- refused too: nothing in Phase 1 writes the counter except the function. The ON CONFLICT DO UPDATE path fires this BEFORE UPDATE
-- trigger; the first insert of a counter row (next_value 2) is an INSERT and does not.
-- The key (tenant, unit, document type, fiscal year) cannot change: a changed key would restart a series under another key.

set local lock_timeout = '30s';

create or replace function pharmacy.guard_document_counter_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.tenant_id is distinct from old.tenant_id
     or new.unit_id is distinct from old.unit_id
     or new.doc_type is distinct from old.doc_type
     or new.fiscal_year is distinct from old.fiscal_year then
    raise exception 'document counter key cannot change' using errcode = 'check_violation';
  end if;
  if new.next_value <= old.next_value then
    raise exception 'document counter can only go up' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function pharmacy.guard_document_counter_update() from public;
revoke all on function pharmacy.guard_document_counter_update() from sahaj_app;

drop trigger if exists trg_document_counter_guard_update on pharmacy.document_counter;
create trigger trg_document_counter_guard_update before update on pharmacy.document_counter
  for each row execute function pharmacy.guard_document_counter_update();

comment on function pharmacy.guard_document_counter_update() is
  'Backstop for pharmacy.document_counter (SAH-78 review L5): next_value must strictly increase and the key (tenant, unit, doc_type, fiscal_year) cannot change. SQLSTATE 23514.';
