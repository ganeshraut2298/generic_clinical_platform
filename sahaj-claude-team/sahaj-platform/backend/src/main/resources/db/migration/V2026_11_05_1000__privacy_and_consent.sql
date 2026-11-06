-- SAH-17 · Privacy and consent.
-- platform.privacy_settings : one row per tenant, lazy-created by the app on first save (no seed row).
-- patient.patient_consent   : append-only consent history; latest row per (patient, kind) is current.
-- patient.record_access     : append-only "who opened which patient file" log.

create table if not exists platform.privacy_settings (
  tenant_id        uuid primary key default platform.current_tenant()
                   constraint fk_privacy_settings_tenant references platform.tenant (id),
  consent_required boolean not null default true,
  promo_consent    boolean not null default true,
  record_copy      boolean not null default true,
  hfr_id           text
                   constraint ck_privacy_settings_hfr_id check (hfr_id is null or char_length(hfr_id) <= 32),
  updated_at       timestamptz,
  updated_by       uuid,
  version          bigint not null default 0
);

alter table platform.privacy_settings enable row level security;
alter table platform.privacy_settings force row level security;

create policy p_privacy_settings_tenant on platform.privacy_settings
  for all to sahaj_app
  using (tenant_id = platform.current_tenant())
  with check (tenant_id = platform.current_tenant());

-- No delete: settings are changed, never removed.
revoke all on platform.privacy_settings from sahaj_app;
revoke all on platform.privacy_settings from public;
grant select, insert, update on platform.privacy_settings to sahaj_app;

comment on table platform.privacy_settings is
  'Per-clinic privacy switches (SAH-17): consent_required, promo_consent, record_copy and the optional HFR id. Lazy: no row until the first save; the app serves defaults (all true, no HFR id) when absent. Never deleted.';

-- patient.patient_consent : append-only. A change of mind is a new row, never an edit.
create table if not exists patient.patient_consent (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null default platform.current_tenant()
               constraint fk_patient_consent_tenant references platform.tenant (id),
  patient_id   uuid not null
               constraint fk_patient_consent_patient references patient.patient (id),
  kind         text not null
               constraint ck_patient_consent_kind check (kind in ('RECORDS', 'PROMOTIONAL')),
  given        boolean not null,
  recorded_by  uuid not null,
  recorded_at  timestamptz not null default now()
);

-- Current state per kind: latest row for (tenant, patient, kind) by recorded_at.
create index if not exists ix_patient_consent_tenant_patient_kind_at
  on patient.patient_consent (tenant_id, patient_id, kind, recorded_at desc);

alter table patient.patient_consent enable row level security;
alter table patient.patient_consent force row level security;

create policy p_patient_consent_select on patient.patient_consent
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_patient_consent_insert on patient.patient_consent
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on patient.patient_consent from sahaj_app;
revoke all on patient.patient_consent from public;
grant select, insert on patient.patient_consent to sahaj_app;

-- Third layer, same as billing.payment: refuse UPDATE/DELETE/TRUNCATE for every role.
create trigger trg_patient_consent_no_update_delete
  before update or delete on patient.patient_consent
  for each row execute function platform.deny_audit_mutation();

create trigger trg_patient_consent_no_truncate
  before truncate on patient.patient_consent
  for each statement execute function platform.deny_audit_mutation();

comment on table patient.patient_consent is
  'Append-only consent history (SAH-17): one row per record or withdraw of RECORDS or PROMOTIONAL consent; the latest row per patient and kind is the current state. Never updated, deleted or truncated - enforced by trigger as well as privilege.';

-- patient.record_access : append-only access log.
create table if not exists patient.record_access (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null default platform.current_tenant()
              constraint fk_record_access_tenant references platform.tenant (id),
  patient_id  uuid not null
              constraint fk_record_access_patient references patient.patient (id),
  user_id     uuid not null,
  at          timestamptz not null default now()
);

-- Recent-access list: newest first within a tenant.
create index if not exists ix_record_access_tenant_at
  on patient.record_access (tenant_id, at desc);

alter table patient.record_access enable row level security;
alter table patient.record_access force row level security;

create policy p_record_access_select on patient.record_access
  for select to sahaj_app
  using (tenant_id = platform.current_tenant());

create policy p_record_access_insert on patient.record_access
  for insert to sahaj_app
  with check (tenant_id = platform.current_tenant());

revoke all on patient.record_access from sahaj_app;
revoke all on patient.record_access from public;
grant select, insert on patient.record_access to sahaj_app;

create trigger trg_record_access_no_update_delete
  before update or delete on patient.record_access
  for each row execute function platform.deny_audit_mutation();

create trigger trg_record_access_no_truncate
  before truncate on patient.record_access
  for each statement execute function platform.deny_audit_mutation();

comment on table patient.record_access is
  'Append-only log (SAH-17) of which user opened which patient file and when. Stores ids only: no name, mobile or ABHA number is ever stored here; names are resolved at read time. Never updated, deleted or truncated - enforced by trigger as well as privilege.';
