-- SAH-51, SAH-67, SAH-68 (ADR-025 D1, D3, D4, D13, "Data needed" item 1) · Sign, edit-hold and write-back columns on
-- opd.emr_record. Forward-only, additive: nothing is dropped, renamed or rewritten. The previous application version
-- neither reads nor writes the new columns and keeps working against this schema.
--   signed_by_membership_id   the signer's membership (signed_at and signed_by already exist since SAH-53)
--   sign_idempotency_key      the Idempotency-Key of the sign call (a retry with the same key and body replays sign_result)
--   sign_request_hash         SHA-256 (64 lower-case hex) of the sign request body, to tell a replay from a conflict
--   sign_result               json object, at most 8 KB: ids, counts and outcome keys of the sign. No clinical text.
--   edit_hold_*               the two-minute edit hold (ADR-025 D4): who holds the record and until when; all three or none
--   patient_facts_fingerprint SHA-256 (64 lower-case hex) of the patient's allergy and chronic texts as read when the record
--                             opened (ADR-025 D13), compared at the sign before the write-back
-- status already allows SIGNED (SAH-53, ck_emr_record_status): no change to that check.
-- All columns are added to the partitioned parent, so to every monthly partition and the default partition. Nullable
-- columns without a default are a catalog change only (no table rewrite). The two new table-level checks scan the table
-- once; opd.emr_record holds only DRAFT rows before this release (nothing could sign), so the scan cannot fail.
-- Row level security, the policy and the table-level insert grant are unchanged: the insert grant covers the new columns, so
-- the open step can store the hold and the fingerprint. Only the column-level update grant grows.
-- No new index: the lookup of the previous signed IPD round of an admission (copy from last, ADR-025 D8) is served by
-- ix_emr_record_tenant_admission_round (tenant_id, ipd_admission_id, round_date), a handful of rows per admission.
-- No unique key on sign_idempotency_key: a unique index on a partitioned table must contain created_at, and the
-- key is read from the record row that the sign has already locked.

alter table opd.emr_record
  add column if not exists signed_by_membership_id uuid,
  add column if not exists sign_idempotency_key text
    constraint ck_emr_record_sign_idempotency_key check (sign_idempotency_key is null or sign_idempotency_key ~ '^[A-Za-z0-9_-]{8,64}$'),
  add column if not exists sign_request_hash text
    constraint ck_emr_record_sign_request_hash check (sign_request_hash is null or sign_request_hash ~ '^[0-9a-f]{64}$'),
  add column if not exists sign_result jsonb
    constraint ck_emr_record_sign_result_shape check (sign_result is null or jsonb_typeof(sign_result) = 'object')
    constraint ck_emr_record_sign_result_size check (sign_result is null or octet_length(sign_result::text) <= 8192),
  add column if not exists edit_hold_membership_id uuid,
  add column if not exists edit_hold_user_id uuid,
  add column if not exists edit_hold_expires_at timestamptz,
  add column if not exists patient_facts_fingerprint text
    constraint ck_emr_record_patient_facts_fingerprint check (patient_facts_fingerprint is null or patient_facts_fingerprint ~ '^[0-9a-f]{64}$');

-- The hold: holder membership, holder user and expiry are set together or not at all.
alter table opd.emr_record
  add constraint ck_emr_record_edit_hold_set check (num_nonnulls(edit_hold_membership_id, edit_hold_user_id, edit_hold_expires_at) in (0, 3));

-- A SIGNED record names its signer's membership and the idempotency key of the sign, and holds no edit hold
-- (the sign clears it, ADR-025 D1 step 8). signed_at and signed_by are already tied to SIGNED by ck_emr_record_signed.
alter table opd.emr_record
  add constraint ck_emr_record_signed_complete check (
    status <> 'SIGNED'
    or (signed_by_membership_id is not null and sign_idempotency_key is not null and edit_hold_membership_id is null));

-- Column-level update: the draft save takes and renews the hold; the open step stores the fingerprint (insert) and may refresh
-- it; the sign writes the signer, key, hash and result in the same update that sets status SIGNED.
grant update (signed_by_membership_id, sign_idempotency_key, sign_request_hash, sign_result,
              edit_hold_membership_id, edit_hold_user_id, edit_hold_expires_at, patient_facts_fingerprint)
  on opd.emr_record to sahaj_app;

comment on column opd.emr_record.signed_by_membership_id is 'The membership that signed the record (SAH-51, ADR-025 D1). Required when status is SIGNED. No foreign key (cross-module); checked by the application.';
comment on column opd.emr_record.sign_idempotency_key is 'Idempotency-Key of the sign call, 8 to 64 characters of A-Z a-z 0-9 _ -. Same key and same sign_request_hash after success replays sign_result; same key with another hash is 409 idempotency-conflict.';
comment on column opd.emr_record.sign_request_hash is 'SHA-256, 64 lower-case hex, of the sign request body (ADR-025 D1).';
comment on column opd.emr_record.sign_result is 'Json object of at most 8 KB: ids, counts and outcome keys of the sign (stage outcome, follow-up id, appointment id, bill lines, orders placed, patient record outcome). Never clinical text, never a name or a number of a patient.';
comment on column opd.emr_record.edit_hold_membership_id is 'Edit hold (ADR-025 D4): the membership that is editing. With edit_hold_user_id and edit_hold_expires_at, all three or none. Taken and renewed to now + 120 seconds by the draft save; cleared by the sign. Not audited except a take-over.';
comment on column opd.emr_record.edit_hold_user_id is 'Edit hold: the user of the holder. All three hold columns are set together or none.';
comment on column opd.emr_record.edit_hold_expires_at is 'Edit hold: when the hold lapses (database clock, 120 seconds after the last save or renewal).';
comment on column opd.emr_record.patient_facts_fingerprint is 'SHA-256, 64 lower-case hex, of the patient''s allergy and chronic texts as read when the record opened (ADR-025 D13). The sign compares it before updating the patient record. Not personal data by itself; never logged with the texts.';
comment on constraint ck_emr_record_signed_complete on opd.emr_record is 'SAH-51: a SIGNED record has its signer membership and sign idempotency key and no edit hold.';
