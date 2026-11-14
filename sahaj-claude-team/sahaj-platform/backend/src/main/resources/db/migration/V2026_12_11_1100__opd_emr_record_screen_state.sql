-- SAH-60 to 66, 69 to 71 (ADR-023 D2, D6, "Data needed" items 9 and 10) · Two additive changes on opd.emr_record.
-- Forward-only. Nothing is dropped, renamed or rewritten.
--   1. screen_state jsonb (null): the facts of a specialty screen that belong to the screen, not to a section:
--      {schemaVersion, profile, mode, oncDecision} (visit mode of Obstetrics and Physiotherapy, Oncology's decision). It must
--      exist even when the owner hides a section. Added to the partitioned parent, so to every monthly partition and the
--      default partition. A nullable column with no default is a catalog change only (no table rewrite); the previous
--      application version neither reads nor writes it. No index (read with the record). Clinical: never logged.
--   2. A unique key (tenant column, id, created_at). The primary key (id, created_at) does not contain the clinic column, so a
--      table that points at a record (opd.chemo_order, V2026_12_11_1110) could not carry the clinic in its foreign key, and
--      a foreign key check ignores row level security: the order could name another clinic's record. This key lets the
--      foreign key include the clinic. A unique key on a partitioned table must contain the partition key, and it does
--      (created_at). It builds one index per partition (24 monthly partitions and the default) and takes a lock on
--      the table while it builds: negligible while the table is small (records exist only from phase 2). Before this
--      runs on an environment that already holds millions of records, a human must plan the build window.

alter table opd.emr_record
  add column if not exists screen_state jsonb
    constraint ck_emr_record_screen_state_shape check (
      screen_state is null
      or (jsonb_typeof(screen_state) = 'object' and coalesce(jsonb_typeof(screen_state -> 'schemaVersion'), '') = 'number'))
    constraint ck_emr_record_screen_state_size check (screen_state is null or octet_length(screen_state::text) <= 8192);

alter table opd.emr_record
  add constraint uq_emr_record_tenant_id_created_at unique (tenant_id, id, created_at);

-- Column-level update, as the other record columns: the doctor's draft save writes the screen state.
grant update (screen_state) on opd.emr_record to sahaj_app;

comment on column opd.emr_record.screen_state is 'Json {schemaVersion, profile, mode, oncDecision}: the specialty screen facts that are not section values (ADR-023 D2). At most 8 KB. Clinical, never logged.';
comment on constraint uq_emr_record_tenant_id_created_at on opd.emr_record is 'Lets a table that points at a record keep the clinic in its foreign key (opd.chemo_order). Not a one-record-per-encounter rule: that stays in the application (advisory lock).';
