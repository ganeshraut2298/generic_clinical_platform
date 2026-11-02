-- SAH-6 AC10 · a clinic can never be left without an active owner.
-- Backstop for the service check (the service refuses first with a clean 409). Fires when an
-- active OWNER is demoted or switched off / removed. It locks the other active owners
-- (FOR UPDATE) so two owners switching each other off at the same moment cannot both pass:
-- the second waits, re-reads, finds no other active owner and is refused (or one side is
-- aborted by PostgreSQL as a deadlock, which is also safe).
-- Runs as the caller, so row level security scopes it to the bound clinic.
create or replace function platform.guard_last_owner()
returns trigger
language plpgsql
as $$
begin
  if old.role = 'OWNER' and old.status = 'ACTIVE'
     and (new.role <> 'OWNER' or new.status <> 'ACTIVE') then
    perform 1
    from platform.membership m
    where m.tenant_id = old.tenant_id
      and m.role = 'OWNER'
      and m.status = 'ACTIVE'
      and m.id <> old.id
    for update;
    if not found then
      raise exception 'last_owner: a clinic must keep at least one active owner'
        using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_membership_last_owner on platform.membership;
create trigger trg_membership_last_owner
  before update of role, status on platform.membership
  for each row execute function platform.guard_last_owner();
