-- SAH-67-2, SAH-67-3 (ADR-025 D9, D10, "Data needed" items 9 and 10) · Emergency "Send to OPD" and the admission request link.
-- Forward-only; nothing is dropped, renamed or rewritten. RLS, policies and grants stay as they are except the column grants below.
-- 1. ipd.emergency_case:
--    * status gains SENT_TO_OPD (a closed state: the doctor's outcome "Send to OPD"; never reopens);
--    * sent_to_opd_visit_id (null, opaque opd.visit id, no foreign key: cross-module): the OPD visit the case was sent to;
--    * a SENT_TO_OPD case has its patient (an existing patient of the clinic chosen by the doctor, DEC-048 H2), the visit, closed_at
--      and closed_by; only a SENT_TO_OPD case has a visit id;
--    * ck_emergency_case_patient_admitted (V2026_11_24_1000) said "a patient only together with ADMITTED"; it is widened to
--      ADMITTED or SENT_TO_OPD, same name, so the SAH-28 rule for every other status is unchanged;
--    * ck_emergency_case_closed, ck_emergency_case_admitted and the bay index already do the right thing for SENT_TO_OPD
--      (closed_at and closed_by are required for every status except WAITING and SEEN; no admission; the bay is freed because
--      the open-bay index covers WAITING and SEEN only);
--    * the guard trigger function is replaced (create or replace, same name): SENT_TO_OPD is a closed status (no update after
--      it) and the mobile number is cleared in the update that sets SENT_TO_OPD, as for ADMITTED, RELEASED and REFERRED; an
--      update that moves a case to any closed status and leaves the mobile not null is refused (QA F-4).
--    * column grant: sent_to_opd_visit_id may be updated (status, patient_id, closed_*, mobile are already granted).
-- 2. ipd.admission.admission_request_id (null, opaque opd.emr_admission_request id, no foreign key): the request this admission
--    completed. Set at insert only (insert grant, no update grant). One admission per request (partial unique index).
-- Existing rows have status in the old list and no visit id, so every widened or new check holds for them; checks are added
-- NOT VALID and validated at once as in earlier migrations.

alter table ipd.emergency_case
  add column if not exists sent_to_opd_visit_id uuid;

alter table ipd.emergency_case drop constraint ck_emergency_case_status;
alter table ipd.emergency_case
  add constraint ck_emergency_case_status
  check (status in ('WAITING', 'SEEN', 'ADMITTED', 'RELEASED', 'REFERRED', 'SENT_TO_OPD')) not valid;
alter table ipd.emergency_case validate constraint ck_emergency_case_status;

alter table ipd.emergency_case drop constraint ck_emergency_case_patient_admitted;
alter table ipd.emergency_case
  add constraint ck_emergency_case_patient_admitted
  check (patient_id is null or status in ('ADMITTED', 'SENT_TO_OPD')) not valid;
alter table ipd.emergency_case validate constraint ck_emergency_case_patient_admitted;

alter table ipd.emergency_case
  add constraint ck_emergency_case_sent_to_opd check (
    case when status = 'SENT_TO_OPD'
         then patient_id is not null and sent_to_opd_visit_id is not null and closed_at is not null and closed_by is not null
         else sent_to_opd_visit_id is null end) not valid;
alter table ipd.emergency_case validate constraint ck_emergency_case_sent_to_opd;

grant update (sent_to_opd_visit_id) on ipd.emergency_case to sahaj_app;

-- Guard: the version of V2026_11_24_1000 with SENT_TO_OPD added to both lists. SECURITY INVOKER, fixed search_path,
-- SQLSTATE 23514 for every refusal.
create or replace function ipd.guard_emergency_case_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if old.status in ('ADMITTED', 'RELEASED', 'REFERRED', 'SENT_TO_OPD') then
    raise exception 'emergency case is closed (update refused)'
      using errcode = 'check_violation';
  end if;
  if old.status = 'SEEN' and new.status = 'WAITING' then
    raise exception 'emergency case cannot go back to waiting'
      using errcode = 'check_violation';
  end if;
  if new.mobile is distinct from old.mobile
     and (new.mobile is not null or new.status not in ('ADMITTED', 'RELEASED', 'REFERRED', 'SENT_TO_OPD')) then
    raise exception 'emergency case mobile can only be cleared when the case is closed'
      using errcode = 'check_violation';
  end if;
  -- SAH-68 QA F-4 (security review M1(a)): the update that closes a case (old status is open here, the closed old statuses
  -- were refused above) must leave the mobile null. Every close in the code (close, send to OPD, admit) clears it.
  if new.status in ('ADMITTED', 'RELEASED', 'REFERRED', 'SENT_TO_OPD') and new.mobile is not null then
    raise exception 'emergency case mobile must be cleared in the update that closes the case'
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
revoke all on function ipd.guard_emergency_case_update() from public;
revoke all on function ipd.guard_emergency_case_update() from sahaj_app;

alter table ipd.admission
  add column if not exists admission_request_id uuid;

create unique index if not exists uq_admission_tenant_admission_request
  on ipd.admission (tenant_id, admission_request_id) where admission_request_id is not null;

grant insert (admission_request_id) on ipd.admission to sahaj_app;

comment on column ipd.emergency_case.sent_to_opd_visit_id is 'Opaque opd.visit id of the OPD visit this case was sent to (SAH-67-2, ADR-025 D9). Set with status SENT_TO_OPD; no foreign key (cross-module).';
comment on constraint ck_emergency_case_sent_to_opd on ipd.emergency_case is 'SAH-67-2: a SENT_TO_OPD case has its patient, the OPD visit and a closure; only a SENT_TO_OPD case has a visit id.';
comment on constraint ck_emergency_case_patient_admitted on ipd.emergency_case is 'SAH-28 and SAH-67-2: a patient is set only together with ADMITTED or SENT_TO_OPD.';
comment on function ipd.guard_emergency_case_update() is 'SAH-28 guard: a closed emergency case (ADMITTED, RELEASED, REFERRED, SENT_TO_OPD) is never updated; SEEN never returns to WAITING; mobile changes only to null in the update that closes the case, and that update must clear it. SQLSTATE 23514.';
comment on column ipd.admission.admission_request_id is 'Opaque opd.emr_admission_request id when the admission completed a doctor''s request from the EMR (SAH-67-3, ADR-025 D10). No foreign key (cross-module). Set at insert only; at most one admission per request.';
