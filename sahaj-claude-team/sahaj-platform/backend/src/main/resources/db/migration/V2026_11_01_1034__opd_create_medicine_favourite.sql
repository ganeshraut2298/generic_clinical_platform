-- SAH-7 · opd.medicine_favourite : the medicines a clinic marks as favourite. They list first when
-- doctors of that clinic search medicines. One row per clinic and medicine (PUT is idempotent).
-- Search with favourites first (row level security already limits f to the bound clinic):
--   select m.id, m.name, m.form, m.schedule, m.usual_dose, (f.medicine_id is not null) as favourite
--     from opd.medicine m
--     left join opd.medicine_favourite f on f.medicine_id = m.id
--    where lower(m.name) like lower(:q) || '%'
--    order by (f.medicine_id is null), lower(m.name)
--    limit :n offset :o;
-- The medicine is global; this table links a clinic to it. A medicine can never be deleted by the
-- app, so the foreign key cannot dangle.
create table if not exists opd.medicine_favourite (
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_medicine_favourite_tenant references platform.tenant (id),
  medicine_id  uuid not null
               constraint fk_medicine_favourite_medicine references opd.medicine (id),
  created_at   timestamptz not null default now(),
  created_by   uuid not null,
  constraint pk_medicine_favourite primary key (tenant_id, medicine_id)
);

alter table opd.medicine_favourite enable row level security;
alter table opd.medicine_favourite force row level security;

create policy p_medicine_favourite_tenant on opd.medicine_favourite
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- A preference, not a clinical or billing record: un-favouriting deletes the row.
-- The trail is in platform.audit_event.
revoke all on opd.medicine_favourite from sahaj_app;
revoke all on opd.medicine_favourite from public;
grant select, insert, delete on opd.medicine_favourite to sahaj_app;

comment on table opd.medicine_favourite is
  'Per-clinic favourite medicines (SAH-7). Primary key (clinic, medicine). Deleting a row un-favourites; audit_event keeps the history.';
