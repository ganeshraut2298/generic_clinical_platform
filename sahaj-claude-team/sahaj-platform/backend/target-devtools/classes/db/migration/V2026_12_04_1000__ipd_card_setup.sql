-- SAH-37 · IPD patient card setup (ADR-015 D1, D5, D9). Forward-only, additive: two new tables, no existing table touched.
-- ipd.card_setup_version: one append-only row per publish (version 2, 3, ...; v1 is the Sahaj standard setup built in code
-- and is never stored). The live setup of a clinic is its row with the highest version.
-- ipd.card_setup_draft: at most one row per clinic (primary key tenant_id), replaced on Save draft, deleted on Discard
-- and on Publish. The setup is a jsonb document read and written whole; the Java CardSetupValidator owns its content,
-- the database only guarantees "a JSON object of at most 16 KB".
-- Neither table points at another tenant table, so there is no tenant-composite foreign key to add
-- (docs/database/tenant-fk-survey.md): the only key is to the global platform.tenant. published_by and saved_by are
-- user ids (IPD actor convention, no key).

-- 1. ipd.card_setup_version (append-only)
create table if not exists ipd.card_setup_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null default platform.current_tenant()
                constraint fk_card_setup_version_tenant references platform.tenant (id),
  version       integer not null
                constraint ck_card_setup_version_version check (version >= 2),
  setup         jsonb not null
                constraint ck_card_setup_version_setup check (jsonb_typeof(setup) = 'object' and octet_length(setup::text) <= 16384),
  is_standard   boolean not null,
  changed_parts text[] not null
                constraint ck_card_setup_version_changed_parts check (changed_parts <@ array['layout', 'sections', 'fields', 'quickActions']::text[]),
  published_by  uuid not null,
  published_at  timestamptz not null default now(),
  -- one version number per clinic (backstop behind the clinic advisory lock); also serves "latest version" by a
  -- backward scan with limit 1 and the newest-first versions list
  constraint uq_card_setup_version_tenant_version unique (tenant_id, version)
);

-- 2. ipd.card_setup_draft (one row per clinic)
create table if not exists ipd.card_setup_draft (
  tenant_id    uuid primary key default platform.current_tenant()
               constraint fk_card_setup_draft_tenant references platform.tenant (id),
  setup        jsonb not null
               constraint ck_card_setup_draft_setup check (jsonb_typeof(setup) = 'object' and octet_length(setup::text) <= 16384),
  -- live version the editor started from (1 = the Sahaj standard setup, which has no stored row)
  base_version integer not null
               constraint ck_card_setup_draft_base_version check (base_version >= 1),
  -- optimistic concurrency counter, bumped by every Save draft
  revision     bigint not null
               constraint ck_card_setup_draft_revision check (revision >= 1),
  saved_by     uuid not null,
  saved_at     timestamptz not null default now()
);

-- 3. RLS (ENABLE and FORCE). The version table has select and insert policies only (no update or delete policy);
-- the draft table has all four.
alter table ipd.card_setup_version enable row level security;
alter table ipd.card_setup_version force row level security;
alter table ipd.card_setup_draft enable row level security;
alter table ipd.card_setup_draft force row level security;

create policy p_card_setup_version_select on ipd.card_setup_version
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_card_setup_version_insert on ipd.card_setup_version
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());

create policy p_card_setup_draft_select on ipd.card_setup_draft
  for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_card_setup_draft_insert on ipd.card_setup_draft
  for insert to sahaj_app with check (tenant_id = platform.current_tenant());
create policy p_card_setup_draft_update on ipd.card_setup_draft
  for update to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());
create policy p_card_setup_draft_delete on ipd.card_setup_draft
  for delete to sahaj_app using (tenant_id = platform.current_tenant());

-- 4. Grants. Schema default privileges give sahaj_app every command, so take everything away first.
-- Column-level insert and update: id, tenant_id and published_at are never written by the app; tenant_id cannot be
-- changed on a draft. No truncate, references or trigger privilege.
revoke all on ipd.card_setup_version, ipd.card_setup_draft from sahaj_app;
revoke all on ipd.card_setup_version, ipd.card_setup_draft from public;

grant select on ipd.card_setup_version, ipd.card_setup_draft to sahaj_app;
grant insert (version, setup, is_standard, changed_parts, published_by) on ipd.card_setup_version to sahaj_app;
grant insert (setup, base_version, revision, saved_by) on ipd.card_setup_draft to sahaj_app;
grant update (setup, base_version, revision, saved_by, saved_at) on ipd.card_setup_draft to sahaj_app;
grant delete on ipd.card_setup_draft to sahaj_app;

-- 5. Triggers (SQLSTATE 42501 for every role, the owner included).
-- Versions are history: update, delete and truncate refused.
create trigger trg_card_setup_version_no_update_delete
  before update or delete on ipd.card_setup_version
  for each row execute function platform.deny_audit_mutation();
create trigger trg_card_setup_version_no_truncate
  before truncate on ipd.card_setup_version
  for each statement execute function platform.deny_audit_mutation();
-- Draft: replaced and deleted by the app, but never truncated (that would wipe every clinic's draft).
create trigger trg_card_setup_draft_no_truncate
  before truncate on ipd.card_setup_draft
  for each statement execute function platform.deny_audit_mutation();

comment on table ipd.card_setup_version is 'IPD patient card setup, one row per publish (SAH-37, ADR-015 D1). Version 2, 3, ...; v1 is the Sahaj standard setup built in code and is not stored. The live setup of a clinic is its highest version. Append-only: no update, delete or truncate (privilege and trigger). Holds no patient data. Kept for the life of the clinic account (configuration history).';
comment on column ipd.card_setup_version.setup is 'Canonical setup document (layout, cardsPerRow, sections, fields, quickActions): a JSON object of at most 16 KB. Validated by CardSetupValidator in Java; repaired in memory on read; never queried by field. Owner-typed labels: not logged.';
comment on column ipd.card_setup_version.is_standard is 'True when the published setup equals the Sahaj standard setup ("Back to the Sahaj standard card").';
comment on column ipd.card_setup_version.changed_parts is 'Parts that differ from the previous live setup: layout, sections, fields, quickActions.';
comment on column ipd.card_setup_version.published_by is 'Signed-in user id (IPD actor convention). Name resolved at read time.';
comment on table ipd.card_setup_draft is 'IPD patient card setup draft (SAH-37, ADR-015 D1, D5). At most one row per clinic (primary key tenant_id). Saved by Save draft, deleted by Discard and Publish. revision is the optimistic token (409 card-setup-changed on mismatch). Holds no patient data. Truncate refused by trigger.';
comment on column ipd.card_setup_draft.base_version is 'Live version the editor started from; 1 means the Sahaj standard setup (no stored row).';
comment on column ipd.card_setup_draft.revision is 'Bumped by every Save draft; the editor sends it back as draftRevision.';
