package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.finish.EmrFinishRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The phase 5 columns of {@code opd.emr_record} and the tables {@code opd.emr_record_addendum} and
 * {@code opd.emr_admission_request} (ADR-025). Clinical data: read and written as text, never logged. Row level security
 * limits every statement to the bound clinic and the tenant id is also in each statement.
 */
@Repository
public class JdbcEmrFinishRepository implements EmrFinishRepository {

    private static final String STATE = """
            select id, status, signed_by_membership_id, signed_by, sign_idempotency_key, sign_request_hash,
                   sign_result::text as sign_result, edit_hold_membership_id, edit_hold_user_id, edit_hold_expires_at,
                   (edit_hold_expires_at is not null and edit_hold_expires_at > clock_timestamp()) as hold_active,
                   patient_facts_fingerprint
            from opd.emr_record where tenant_id = :tenantId and id = :id
            """;
    private static final String STATE_FOR_UPDATE = STATE.stripTrailing() + " for update";
    private static final String TAKE_HOLD = """
            update opd.emr_record
            set edit_hold_membership_id = :membershipId, edit_hold_user_id = :userId,
                edit_hold_expires_at = clock_timestamp() + make_interval(secs => :seconds)
            where tenant_id = :tenantId and id = :id and status = 'DRAFT'
              and (edit_hold_membership_id is null or edit_hold_expires_at <= clock_timestamp()
                   or edit_hold_membership_id = :membershipId or :takeOver)
            """;
    private static final String RELEASE_HOLD = """
            update opd.emr_record
            set edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null
            where tenant_id = :tenantId and id = :id and status = 'DRAFT' and edit_hold_membership_id = :membershipId
            """;
    private static final String SET_FINGERPRINT = """
            update opd.emr_record set patient_facts_fingerprint = :fingerprint
            where tenant_id = :tenantId and id = :id and status = 'DRAFT' and patient_facts_fingerprint is null
            """;
    private static final String MARK_SIGNED = """
            update opd.emr_record
            set status = 'SIGNED', signed_at = now(), signed_by = :userId, signed_by_membership_id = :membershipId,
                sign_idempotency_key = :key, sign_request_hash = :hash, sign_result = cast(:result as jsonb),
                edit_hold_membership_id = null, edit_hold_user_id = null, edit_hold_expires_at = null,
                revision = revision + 1, updated_at = now(), updated_by = :userId
            where tenant_id = :tenantId and id = :id and status = 'DRAFT'
            """;
    private static final String MARK_ADVISED = """
            update opd.emr_record set admission_advised = true, updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and status = 'DRAFT'
            """;
    private static final String LINK_PATIENT = """
            update opd.emr_record set patient_id = :patientId
            where tenant_id = :tenantId and id = :id and encounter_type = 'ER_ASSESSMENT' and patient_id is null
            """;
    private static final String ADDENDA = """
            select id, record_id, reason, addendum_text, created_at, created_by_membership_id, idempotency_key,
                   request_hash
            from opd.emr_record_addendum where tenant_id = :tenantId and record_id = :recordId
            order by created_at, id
            """;
    private static final String COUNT_ADDENDA = """
            select count(*) from opd.emr_record_addendum where tenant_id = :tenantId and record_id = :recordId
            """;
    private static final String ADDENDUM_BY_KEY = """
            select id, record_id, reason, addendum_text, created_at, created_by_membership_id, idempotency_key,
                   request_hash
            from opd.emr_record_addendum where tenant_id = :tenantId and idempotency_key = :key
            """;
    private static final String INSERT_ADDENDUM = """
            insert into opd.emr_record_addendum (record_id, record_created_at, reason, addendum_text,
                                                 created_by_membership_id, created_by_user_id, idempotency_key,
                                                 request_hash)
            values (:recordId,
                    (select created_at from opd.emr_record where tenant_id = :tenantId and id = :recordId),
                    :reason, :text, :membershipId, :userId, :key, :hash)
            """;
    private static final String REQUEST_COLUMNS = """
            id, record_id, encounter_type, opd_visit_id, er_case_id, patient_id, requested_by_membership_id,
            admission_type, planned_stay_days, reason_text, procedure_text, note, status, admission_id, created_at,
            idempotency_key, request_hash
            """;
    private static final String OPEN_OF_RECORD = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and record_id = :recordId and status = 'OPEN'";
    private static final String LATEST_OF_RECORD = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and record_id = :recordId"
            + " order by created_at desc limit 1";
    private static final String REQUEST_BY_KEY = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and idempotency_key = :key";
    private static final String REQUEST_BY_ID = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and id = :id";
    private static final String LOCK_OPEN_REQUEST = REQUEST_BY_ID + " and status = 'OPEN' for update";
    private static final String LIST_OPEN = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and status = 'OPEN'"
            + " order by created_at desc limit 200";
    private static final String OPEN_OF = "select " + REQUEST_COLUMNS
            + " from opd.emr_admission_request where tenant_id = :tenantId and status = 'OPEN'"
            + " and ((cast(:patientId as uuid) is not null and patient_id = cast(:patientId as uuid))"
            + " or (cast(:caseId as uuid) is not null and er_case_id = cast(:caseId as uuid))) for update";
    private static final String INSERT_REQUEST = """
            insert into opd.emr_admission_request (record_id, record_created_at, encounter_type,
                opd_visit_id, er_case_id, patient_id, requested_by_membership_id, admission_type, planned_stay_days,
                reason_text, procedure_text, note, idempotency_key, request_hash, created_by)
            values (:recordId, :recordCreatedAt, :type, :visitId, :caseId, :patientId, :membershipId,
                    :admissionType, :stay, :reason, :procedure, :note, :key, :hash, :actor)
            returning id
            """;
    private static final String MARK_ADMITTED = """
            update opd.emr_admission_request set status = 'ADMITTED', admission_id = :admissionId,
                admitted_at = now(), updated_at = now(), updated_by = :actor
            where tenant_id = :tenantId and id = :id and status = 'OPEN'
            """;
    private static final String RECORD_CREATED = """
            select created_at from opd.emr_record where tenant_id = :tenantId and id = :id
            """;
    private static final String ER_RECORD = """
            select id from opd.emr_record where tenant_id = :tenantId and er_case_id = :caseId
            """;

    private final JdbcClient jdbc;

    public JdbcEmrFinishRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RecordState> stateForUpdate(UUID tenantId, UUID recordId) {
        return state(STATE_FOR_UPDATE, tenantId, recordId);
    }

    @Override
    public Optional<RecordState> state(UUID tenantId, UUID recordId) {
        return state(STATE, tenantId, recordId);
    }

    private Optional<RecordState> state(String sql, UUID tenantId, UUID recordId) {
        return jdbc.sql(sql).param("tenantId", tenantId).param("id", recordId).query((rs, row) -> new RecordState(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("signed_by_membership_id", UUID.class), rs.getObject("signed_by", UUID.class),
                rs.getString("sign_idempotency_key"), rs.getString("sign_request_hash"), rs.getString("sign_result"),
                rs.getObject("edit_hold_membership_id", UUID.class), rs.getObject("edit_hold_user_id", UUID.class),
                instant(rs, "edit_hold_expires_at"), rs.getBoolean("hold_active"),
                rs.getString("patient_facts_fingerprint"))).optional();
    }

    @Override
    public boolean takeHold(UUID tenantId, UUID recordId, UUID membershipId, UUID userId, int seconds,
                            boolean takeOver) {
        return jdbc.sql(TAKE_HOLD).param("tenantId", tenantId).param("id", recordId)
                .param("membershipId", membershipId).param("userId", userId).param("seconds", (double) seconds)
                .param("takeOver", takeOver).update() == 1;
    }

    @Override
    public void releaseHold(UUID tenantId, UUID recordId, UUID membershipId) {
        jdbc.sql(RELEASE_HOLD).param("tenantId", tenantId).param("id", recordId).param("membershipId", membershipId)
                .update();
    }

    @Override
    public void setFingerprint(UUID tenantId, UUID recordId, String fingerprint) {
        jdbc.sql(SET_FINGERPRINT).param("tenantId", tenantId).param("id", recordId).param("fingerprint", fingerprint)
                .update();
    }

    @Override
    public boolean markSigned(UUID tenantId, UUID recordId, UUID userId, UUID membershipId, String key, String hash,
                              String resultJson) {
        return jdbc.sql(MARK_SIGNED).param("tenantId", tenantId).param("id", recordId).param("userId", userId)
                .param("membershipId", membershipId).param("key", key).param("hash", hash)
                .param("result", resultJson).update() == 1;
    }

    @Override
    public void markAdvised(UUID tenantId, UUID recordId, UUID actorUserId) {
        jdbc.sql(MARK_ADVISED).param("tenantId", tenantId).param("id", recordId).param("actor", actorUserId).update();
    }

    @Override
    public boolean linkPatient(UUID tenantId, UUID recordId, UUID patientId) {
        return jdbc.sql(LINK_PATIENT).param("tenantId", tenantId).param("id", recordId).param("patientId", patientId)
                .update() == 1;
    }

    @Override
    public List<AddendumRow> addenda(UUID tenantId, UUID recordId) {
        return jdbc.sql(ADDENDA).param("tenantId", tenantId).param("recordId", recordId)
                .query(JdbcEmrFinishRepository::addendum).list();
    }

    @Override
    public int countAddenda(UUID tenantId, UUID recordId) {
        return jdbc.sql(COUNT_ADDENDA).param("tenantId", tenantId).param("recordId", recordId)
                .query((rs, row) -> rs.getInt(1)).single();
    }

    @Override
    public Optional<AddendumRow> addendumByKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(ADDENDUM_BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcEmrFinishRepository::addendum).optional();
    }

    @Override
    public AddendumRow insertAddendum(UUID tenantId, UUID recordId, String reason, String text, UUID membershipId,
                                      UUID userId, String idempotencyKey, String requestHash) {
        jdbc.sql(INSERT_ADDENDUM).param("tenantId", tenantId).param("recordId", recordId)
                .param("reason", reason).param("text", text).param("membershipId", membershipId)
                .param("userId", userId).param("key", idempotencyKey).param("hash", requestHash).update();
        return addendumByKey(tenantId, idempotencyKey).orElseThrow();
    }

    @Override
    public Optional<RequestRow> openRequestOfRecord(UUID tenantId, UUID recordId) {
        return jdbc.sql(OPEN_OF_RECORD).param("tenantId", tenantId).param("recordId", recordId)
                .query(JdbcEmrFinishRepository::request).optional();
    }

    @Override
    public Optional<RequestRow> latestRequestOfRecord(UUID tenantId, UUID recordId) {
        return jdbc.sql(LATEST_OF_RECORD).param("tenantId", tenantId).param("recordId", recordId)
                .query(JdbcEmrFinishRepository::request).optional();
    }

    @Override
    public Optional<RequestRow> requestByKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(REQUEST_BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcEmrFinishRepository::request).optional();
    }

    @Override
    public Optional<RequestRow> requestById(UUID tenantId, UUID requestId) {
        return jdbc.sql(REQUEST_BY_ID).param("tenantId", tenantId).param("id", requestId)
                .query(JdbcEmrFinishRepository::request).optional();
    }

    @Override
    public Optional<RequestRow> lockOpenRequest(UUID tenantId, UUID requestId) {
        return jdbc.sql(LOCK_OPEN_REQUEST).param("tenantId", tenantId).param("id", requestId)
                .query(JdbcEmrFinishRepository::request).optional();
    }

    @Override
    public RequestRow insertRequest(UUID tenantId, NewRequest r, UUID actorUserId) {
        UUID id = jdbc.sql(INSERT_REQUEST).param("recordId", r.recordId())
                .param("recordCreatedAt", Timestamp.from(r.recordCreatedAt())).param("type", r.encounterType())
                .param("visitId", r.opdVisitId(), Types.OTHER).param("caseId", r.erCaseId(), Types.OTHER)
                .param("patientId", r.patientId(), Types.OTHER).param("membershipId", r.requestedByMembershipId())
                .param("admissionType", r.admissionType()).param("stay", r.plannedStayDays(), Types.INTEGER)
                .param("reason", r.reasonText()).param("procedure", r.procedureText()).param("note", r.note())
                .param("key", r.idempotencyKey()).param("hash", r.requestHash()).param("actor", actorUserId)
                .query((rs, row) -> rs.getObject(1, UUID.class)).single();
        return requestById(tenantId, id).orElseThrow();
    }

    @Override
    public List<RequestRow> listOpenRequests(UUID tenantId) {
        return jdbc.sql(LIST_OPEN).param("tenantId", tenantId).query(JdbcEmrFinishRepository::request).list();
    }

    @Override
    public List<RequestRow> openRequestsOf(UUID tenantId, UUID patientId, UUID erCaseId) {
        return jdbc.sql(OPEN_OF).param("tenantId", tenantId).param("patientId", patientId, Types.OTHER)
                .param("caseId", erCaseId, Types.OTHER).query(JdbcEmrFinishRepository::request).list();
    }

    @Override
    public void markRequestAdmitted(UUID tenantId, UUID requestId, UUID admissionId, UUID actorUserId) {
        jdbc.sql(MARK_ADMITTED).param("tenantId", tenantId).param("id", requestId).param("admissionId", admissionId)
                .param("actor", actorUserId).update();
    }

    @Override
    public Optional<Instant> recordCreatedAt(UUID tenantId, UUID recordId) {
        return jdbc.sql(RECORD_CREATED).param("tenantId", tenantId).param("id", recordId)
                .query((rs, row) -> rs.getTimestamp(1).toInstant()).optional();
    }

    @Override
    public Optional<UUID> erRecordOfCase(UUID tenantId, UUID caseId) {
        return jdbc.sql(ER_RECORD).param("tenantId", tenantId).param("caseId", caseId)
                .query((rs, row) -> rs.getObject(1, UUID.class)).optional();
    }

    private static AddendumRow addendum(ResultSet rs, int row) throws SQLException {
        return new AddendumRow(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                rs.getString("reason"), rs.getString("addendum_text"), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by_membership_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("request_hash"));
    }

    private static RequestRow request(ResultSet rs, int row) throws SQLException {
        int stay = rs.getInt("planned_stay_days");
        Integer plannedStay = rs.wasNull() ? null : stay;
        return new RequestRow(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                rs.getString("encounter_type"), rs.getObject("opd_visit_id", UUID.class),
                rs.getObject("er_case_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("requested_by_membership_id", UUID.class), rs.getString("admission_type"), plannedStay,
                rs.getString("reason_text"), rs.getString("procedure_text"), rs.getString("note"),
                rs.getString("status"), rs.getObject("admission_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getString("idempotency_key"),
                rs.getString("request_hash"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
