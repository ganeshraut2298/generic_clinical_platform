-- SAH-3 · platform.consent_record : Terms and Privacy consent, recorded at sign-up.
-- No tenant column: consent happens before a tenant exists. No row level security for the
-- same reason; access is only through the sign-up transaction, never a list endpoint.
create table if not exists platform.consent_record (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null
                  constraint fk_consent_record_user references platform.app_user (id),
  terms_version   text not null
                  constraint ck_consent_record_terms_version check (char_length(terms_version) between 1 and 40),
  privacy_version text not null
                  constraint ck_consent_record_privacy_version check (char_length(privacy_version) between 1 and 40),
  at              timestamptz not null default now(),
  source_ip       inet
);

-- Serves "the consent history for this user" (compliance lookup).
create index if not exists ix_consent_record_user_at on platform.consent_record (user_id, at desc);

revoke all on platform.consent_record from sahaj_app;
revoke all on platform.consent_record from public;
grant select, insert on platform.consent_record to sahaj_app;
-- No update, no delete: a consent record is a legal fact, immutable once written.

comment on table platform.consent_record is
  'Terms and Privacy consent recorded at sign-up, with version and time. Never updated or deleted.';
