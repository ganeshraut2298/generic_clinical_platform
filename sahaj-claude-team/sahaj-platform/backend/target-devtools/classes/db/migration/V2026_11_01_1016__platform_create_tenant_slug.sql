-- SAH-4 · platform.tenant_slug : a narrow, RLS-free mirror of platform.tenant.slug, used only to
-- answer "is this web address available?" (GET /setup/slug-availability) before any tenant is
-- bound in the session, or across tenants once one is bound.
--
-- Why a separate table instead of querying platform.tenant directly: platform.tenant has
-- ENABLE + FORCE ROW LEVEL SECURITY with policy p_tenant_self (id = current_tenant()).
-- Neither sahaj_app nor sahaj_migrator has BYPASSRLS (scripts/db/init.sql), and FORCE applies
-- row security to the table owner too, so no query against platform.tenant — not even one run
-- by a SECURITY DEFINER function owned by sahaj_migrator — can see another tenant's row. That
-- is correct for clinical/business data but blocks the one legitimate cross-tenant question
-- this feature needs to ask. This table holds nothing but the slug and the id it belongs to
-- (same shape and sensitivity as platform.app_user and platform.sales_enquiry: no RLS, by
-- design, for exactly that reason), so a global SELECT is harmless.
--
-- The application must keep this table in sync with platform.tenant.slug in the same
-- transaction, every time a slug is set or changed (setup/complete now; SAH-7 clinic settings
-- later, if a slug can be edited after setup). platform.tenant.slug stays the source of truth
-- and keeps its own unique index (SAH-1); this table is a lookup aid only, never read for
-- anything but availability.
--
-- The primary key column below is deliberately not named the usual way a tenant-owning foreign
-- key is named elsewhere in this schema: this table has no row level security by design (see
-- above), and that usual name is reserved in this codebase for columns a row-level-security
-- policy must govern.
create table if not exists platform.tenant_slug (
  owner_tenant uuid primary key
               constraint fk_tenant_slug_tenant references platform.tenant (id),
  slug         text not null
               constraint uq_tenant_slug_slug unique
               constraint ck_tenant_slug_slug check (slug ~ '^[a-z0-9][a-z0-9-]{2,23}$'),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz
);

-- No row level security, on purpose (see note above). Grants are narrow: no DELETE (a slug
-- mapping outlives the setup wizard; a tenant's slug history is not expected to need erasing),
-- and the application never lists this table, only looks up one slug at a time.
revoke all on platform.tenant_slug from sahaj_app;
revoke all on platform.tenant_slug from public;
grant select, insert, update on platform.tenant_slug to sahaj_app;

comment on table platform.tenant_slug is
  'RLS-free mirror of platform.tenant.slug, written in the same transaction as any slug set/change, read only to answer slug-availability checks across tenants. Not the source of truth; platform.tenant.slug and its unique index are. owner_tenant is the tenant this slug belongs to (fk to platform.tenant.id).';
