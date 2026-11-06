-- SAH-14-18 security review L3 and L6. Forward-only.

-- L3: sahaj_app may insert only the columns the app supplies. tenant_id, id and the timestamp keep their
-- defaults (tenant_id = platform.current_tenant()), so a caller cannot choose them. RLS with check stays.
revoke insert on patient.patient_consent from sahaj_app;
grant insert (patient_id, kind, given, recorded_by) on patient.patient_consent to sahaj_app;

revoke insert on patient.record_access from sahaj_app;
grant insert (patient_id, user_id) on patient.record_access to sahaj_app;

-- L6: the shared append-only trigger names the table it refused, not always platform.audit_event.
-- Same SQLSTATE (42501) and behaviour; the message still contains "append-only".
create or replace function platform.deny_audit_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception '% is append-only (% refused)', tg_table_schema || '.' || tg_table_name, tg_op
    using errcode = 'insufficient_privilege';
end;
$$;
