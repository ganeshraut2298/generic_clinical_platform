-- SAH-5 · platform.user_preference : one row per (tenant, user) — language, theme, menu mode
-- and sidebar state, read and written through GET/PATCH /me and /me/preferences.
-- A person can belong to several clinics (platform.membership); their preference can differ
-- per clinic (for example a different default language per clinic's staff), so the key is the
-- pair, not the user alone.
create table if not exists platform.user_preference (
  tenant_id         uuid not null default platform.current_tenant()
                    constraint fk_user_preference_tenant references platform.tenant (id),
  user_id           uuid not null
                    constraint fk_user_preference_user references platform.app_user (id),
  language          text not null default 'en'
                    constraint ck_user_preference_language check (language in ('en', 'mr', 'hi')),
  theme             text not null default 'light'
                    constraint ck_user_preference_theme check (theme in ('light', 'dark')),
  menu_mode         text not null default 'advanced'
                    constraint ck_user_preference_menu_mode check (menu_mode in ('simple', 'advanced')),
  sidebar_collapsed boolean not null default false,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz,
  version           bigint not null default 0,
  constraint pk_user_preference primary key (tenant_id, user_id)
);

alter table platform.user_preference enable row level security;
alter table platform.user_preference force row level security;

-- Tenant-scoped like every other tenant table, and additionally narrowed to the session's own
-- user: preferences are private per person and are only ever read/written for "me". This needs
-- the app to bind app.user_id (platform.current_user_id(), SET LOCAL, DEC-007) on every request
-- that calls /me or /me/preferences, not only right after OTP verify as SAH-3 first used it.
create policy p_user_preference_self on platform.user_preference
  for all to sahaj_app
  using (tenant_id = platform.current_tenant() and user_id = platform.current_user_id())
  with check (tenant_id = platform.current_tenant() and user_id = platform.current_user_id());

-- Never deleted: a user keeps one row per clinic; removed/inactive membership just stops using it.
revoke all on platform.user_preference from sahaj_app;
revoke all on platform.user_preference from public;
grant select, insert, update on platform.user_preference to sahaj_app;

comment on table platform.user_preference is
  'Per (tenant, user) shell preferences: language, theme, menu mode, sidebar state. Policy requires BOTH app.tenant_id and app.user_id bound, tighter than the usual tenant-only policy, since this data is private to the user, not shared clinic data.';
