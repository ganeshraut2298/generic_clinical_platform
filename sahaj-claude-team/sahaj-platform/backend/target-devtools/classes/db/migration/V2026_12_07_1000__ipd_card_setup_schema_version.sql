-- SAH-37 audit gap G-5 · schema_version for the two jsonb setup tables (.claude/rules/postgresql.md: jsonb with a schema_version).
-- Forward-only and additive: one new column on each of ipd.card_setup_version and ipd.card_setup_draft, nothing existing changes.
-- A constant default is stored in the catalogue (PostgreSQL 11+), so there is no table rewrite; existing rows read as 1.
-- The previous app version does not name the column in its insert or update statements, so the default (1) applies and it
-- keeps working against the new schema.
-- Triggers: the version table's before update/delete trigger and the no-truncate triggers are untouched; adding a column
-- fires no row trigger. The version table stays append-only.
-- Grants: grants are additive, so the existing column grants are kept and the new column is added to them
-- (version table: insert; draft table: insert and update). Nothing is revoked.

-- 1. ipd.card_setup_version
alter table ipd.card_setup_version
  add column if not exists schema_version smallint not null default 1;
alter table ipd.card_setup_version
  add constraint ck_card_setup_version_schema_version check (schema_version >= 1);

-- 2. ipd.card_setup_draft
alter table ipd.card_setup_draft
  add column if not exists schema_version smallint not null default 1;
alter table ipd.card_setup_draft
  add constraint ck_card_setup_draft_schema_version check (schema_version >= 1);

-- 3. Column-level grants for sahaj_app (the app writes schema_version explicitly)
grant insert (schema_version) on ipd.card_setup_version to sahaj_app;
grant insert (schema_version) on ipd.card_setup_draft to sahaj_app;
grant update (schema_version) on ipd.card_setup_draft to sahaj_app;

-- 4. Comments
comment on column ipd.card_setup_version.schema_version is 'Shape version of the setup jsonb document. 1 = layout, cardsPerRow, sections, fields, quickActions as written by SAH-37. A reader that does not know the stored version falls back to the Sahaj standard setup (fallback: true), as for an unreadable row. Never changed after insert (append-only).';
comment on column ipd.card_setup_draft.schema_version is 'Shape version of the setup jsonb document (same meaning as on ipd.card_setup_version). Written with every Save draft.';
