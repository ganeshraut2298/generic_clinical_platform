-- SAH-12 · OPD workflow configuration: workflow_draft, workflow_version, and opd.visit's
-- version-stamp column.
--
-- steps jsonb shape (both tables below), documented here rather than enforced by a check
-- constraint (JSON shape validation belongs to the Java layer, per the story's technical design):
-- a JSON array, in display/flow order, of objects {key, on, owner}:
--   key:   one of token, queue, vitals, consult, counsel, casepaper, billing, followup
--   on:    boolean
--   owner: one of FRONT_DESK, NURSE, DOCTOR, OWNER - present only on steps that have an owner
--          selector in the mockup (vitals, counsel); absent on the rest (token, queue, consult,
--          casepaper, billing, followup have no stored owner)
-- Fixed defaults the app writes when it lazily creates a row (not seeded by this migration):
-- token/queue/consult/billing always {on: true} (no owner, always-on, locked in the UI);
-- vitals {on: true, owner: "NURSE"}; counsel {on: false, owner: "DOCTOR"}; casepaper {on: true};
-- followup {on: true}.

-- opd.workflow_draft : one row per tenant, the in-progress edit of the step list before it is
-- published. Lazy-created on first write by the app (same pattern as billing.bill / SAH-10 and
-- opd.booking_rules / SAH-11) - this migration seeds no row; until the owner first saves a draft,
-- GET /opd/workflow/draft is synthesized by the service layer from the current published
-- version's steps (or the fixed defaults above if no version exists yet either).
create table if not exists opd.workflow_draft (
  tenant_id   uuid not null
              constraint fk_workflow_draft_tenant references platform.tenant (id),
  steps       jsonb not null,
  updated_at  timestamptz,
  updated_by  uuid,
  version     bigint not null default 0,
  constraint pk_workflow_draft primary key (tenant_id)
);

alter table opd.workflow_draft enable row level security;
alter table opd.workflow_draft force row level security;

create policy p_workflow_draft_tenant on opd.workflow_draft
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

revoke all on opd.workflow_draft from sahaj_app;
revoke all on opd.workflow_draft from public;
-- No delete: a draft is edited in place (upsert), never removed - discarding a draft means
-- overwriting it back to the current published version's steps, not deleting the row.
grant select, insert, update on opd.workflow_draft to sahaj_app;

comment on table opd.workflow_draft is
  'One row per tenant (SAH-12): the in-progress, unpublished edit of the OPD workflow steps. Lazy-created on first write by the app. Never affects the live queue until published into opd.workflow_version. Never deleted.';

-- opd.workflow_version : append-only history of published workflow step lists. Every publish adds
-- a new row and bumps version_no; a visit created under an earlier version keeps following that
-- version's steps for its whole life (AC3), via opd.visit.workflow_version_no below. No row is
-- seeded by this migration - the first GET or the first token issued on a tenant with none lazily
-- inserts version_no = 1 from the fixed defaults above (same lazy pattern as opd.workflow_draft),
-- so there is always at least one real, queryable version once the clinic is in use.
create table if not exists opd.workflow_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null
                 constraint fk_workflow_version_tenant references platform.tenant (id),
  version_no     integer not null
                 constraint ck_workflow_version_version_no check (version_no > 0),
  steps          jsonb not null,
  published_at   timestamptz not null default now(),
  published_by   uuid not null,
  constraint uq_workflow_version_tenant_version_no unique (tenant_id, version_no)
);

-- "Find the current version" (max(version_no) for a tenant) and the version-history list
-- (newest first), per the story's technical design.
create index if not exists ix_workflow_version_tenant_version_no
  on opd.workflow_version (tenant_id, version_no desc);

alter table opd.workflow_version enable row level security;
alter table opd.workflow_version force row level security;

create policy p_workflow_version_select on opd.workflow_version
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_workflow_version_insert on opd.workflow_version
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on opd.workflow_version from sahaj_app;
revoke all on opd.workflow_version from public;
-- Append-only, same spirit as billing.day_closing: select/insert only, no update, no delete - a
-- published version is permanent history and is never edited or removed, even by a later publish.
grant select, insert on opd.workflow_version to sahaj_app;

comment on table opd.workflow_version is
  'Append-only history of published OPD workflow step lists (SAH-12), one row per tenant per publish. version_no starts at 1, lazily created on first use if nothing was ever published. Never updated or deleted.';

-- opd.visit : additive column only (existing SAH-9 table, not edited above this point in history).
-- Which published workflow version this visit's stage transitions follow; stamped once at
-- creation (issueToken), never changed afterwards - so a visit started under an older version
-- keeps that version's enabled-stage list for its whole life, per AC3. Plain integer, not a
-- foreign key to opd.workflow_version: a version row is guaranteed to exist for every tenant by
-- the time a visit can be created (lazy v1 create on first use, see opd.workflow_version above),
-- but the two tables are looked up independently by (tenant_id, version_no) rather than by a
-- referential link, so this stays a simple snapshot value, not a constrained relationship.
alter table opd.visit
  add column if not exists workflow_version_no integer not null default 1;

comment on column opd.visit.workflow_version_no is
  'Which published opd.workflow_version.version_no this visit''s stage transitions follow (SAH-12); stamped once at creation, never changed. Not a foreign key - a plain snapshot of the tenant''s current version at issueToken time. Existing rows default to 1.';
