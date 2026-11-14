package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.specialty.ChemoOrderRepository;
import com.sahaj.opd.application.emr.specialty.ChemoOrderRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code opd.chemo_order} (SAH-66, SAH-70, ADR-023 D6). Clinical data: the json documents are read and written as text and
 * never logged. Row level security limits every statement to the bound clinic; the tenant id is also in each statement.
 */
@Repository
public class JdbcChemoOrderRepository implements ChemoOrderRepository {

    private static final String COLUMNS = """
            id, record_id, record_created_at, patient_id, course_id, cycle_no, status, protocol_key, level_pct,
            reduction_reason_key, reduction_reason_text, inputs::text as inputs, snapshot::text as snapshot,
            fingerprint, doxorubicin_mg_m2, prior_outside_mg_m2, prescriber_membership_id,
            last_edited_by_membership_id, checker_membership_id, checker_role, checker_named_at, checker_named_by,
            confirmed_by_membership_id, confirmed_by_user_id, confirmed_via, confirmed_at, confirmed_fingerprint,
            confirmation_cleared_at, sent_at, sent_by, hold_kind, hold_reason, held_at, revision, created_at
            """;
    private static final String BY_RECORD = "select " + COLUMNS
            + " from opd.chemo_order where tenant_id = :tenantId and record_id = :id and supersedes_order_id is null";
    private static final String BY_ID = "select " + COLUMNS + " from opd.chemo_order where tenant_id = :tenantId and id = :id";
    private static final String SENT = "select " + COLUMNS + " from opd.chemo_order where tenant_id = :tenantId"
            + " and patient_id = :patient and status = 'SENT' order by sent_at asc";
    private static final String WAITING = "select " + COLUMNS + " from opd.chemo_order where tenant_id = :tenantId"
            + " and checker_membership_id = :checker and status = 'DRAFT' and confirmed_at is null"
            + " order by checker_named_at asc, id asc offset :offset limit :limit";
    private static final String COUNT_WAITING = "select count(*) from opd.chemo_order where tenant_id = :tenantId"
            + " and checker_membership_id = :checker and status = 'DRAFT' and confirmed_at is null";
    private static final String INSERT = """
            insert into opd.chemo_order (id, tenant_id, record_id, record_created_at, patient_id, course_id, cycle_no,
                status, protocol_key, level_pct, reduction_reason_key, reduction_reason_text, inputs, snapshot,
                fingerprint, doxorubicin_mg_m2, prior_outside_mg_m2, prescriber_membership_id,
                last_edited_by_membership_id, revision, created_by)
            values (:id, :tenantId, :recordId, :recordCreatedAt, :patient, :course, :cycleNo, 'DRAFT', :protocol,
                :level, :reasonKey, :reasonText, cast(:inputs as jsonb), cast(:snapshot as jsonb), :fingerprint,
                :dox, :prior, :prescriber, :editor, 0, :actor)
            """;
    private static final String UPDATE = """
            update opd.chemo_order set course_id = :course, cycle_no = :cycleNo, status = :status,
                protocol_key = :protocol, level_pct = :level, reduction_reason_key = :reasonKey,
                reduction_reason_text = :reasonText, inputs = cast(:inputs as jsonb),
                snapshot = cast(:snapshot as jsonb), fingerprint = :fingerprint, doxorubicin_mg_m2 = :dox,
                prior_outside_mg_m2 = :prior, last_edited_by_membership_id = :editor,
                checker_membership_id = :checker, checker_role = :checkerRole, checker_named_at = :namedAt,
                checker_named_by = :namedBy, confirmed_by_membership_id = :confBy, confirmed_by_user_id = :confUser,
                confirmed_via = :confVia, confirmed_at = :confAt, confirmed_fingerprint = :confFp,
                confirmation_cleared_at = :clearedAt, sent_at = :sentAt, sent_by = :sentBy, hold_kind = :holdKind,
                hold_reason = :holdReason, held_at = :heldAt, revision = revision + 1, updated_at = now(),
                updated_by = :actor
            where tenant_id = :tenantId and id = :id and revision = :expected
            """;

    private final JdbcClient jdbc;

    public JdbcChemoOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChemoOrderRow> findByRecord(UUID tenantId, UUID recordId) {
        return jdbc.sql(BY_RECORD).param("tenantId", tenantId).param("id", recordId)
                .query(JdbcChemoOrderRepository::row).optional();
    }

    @Override
    public Optional<ChemoOrderRow> findByRecordForUpdate(UUID tenantId, UUID recordId) {
        return jdbc.sql(BY_RECORD + " for update").param("tenantId", tenantId).param("id", recordId)
                .query(JdbcChemoOrderRepository::row).optional();
    }

    @Override
    public Optional<ChemoOrderRow> findById(UUID tenantId, UUID orderId) {
        return jdbc.sql(BY_ID).param("tenantId", tenantId).param("id", orderId)
                .query(JdbcChemoOrderRepository::row).optional();
    }

    @Override
    public Optional<ChemoOrderRow> findByIdForUpdate(UUID tenantId, UUID orderId) {
        return jdbc.sql(BY_ID + " for update").param("tenantId", tenantId).param("id", orderId)
                .query(JdbcChemoOrderRepository::row).optional();
    }

    @Override
    public void insert(UUID tenantId, ChemoOrderRow r, UUID actorUserId) {
        jdbc.sql(INSERT).param("id", r.id).param("tenantId", tenantId).param("recordId", r.recordId)
                .param("recordCreatedAt", Timestamp.from(r.recordCreatedAt)).param("patient", r.patientId)
                .param("course", r.courseId).param("cycleNo", r.cycleNo).param("protocol", r.protocolKey)
                .param("level", r.levelPct).param("reasonKey", r.reductionReasonKey)
                .param("reasonText", r.reductionReasonText).param("inputs", r.inputs).param("snapshot", r.snapshot)
                .param("fingerprint", r.fingerprint).param("dox", r.doxorubicinMgM2).param("prior", r.priorOutsideMgM2)
                .param("prescriber", r.prescriberMembershipId).param("editor", r.lastEditedByMembershipId)
                .param("actor", actorUserId).update();
    }

    @Override
    public boolean update(UUID tenantId, ChemoOrderRow r, long expectedRevision, UUID actorUserId) {
        return jdbc.sql(UPDATE).param("tenantId", tenantId).param("id", r.id).param("expected", expectedRevision)
                .param("course", r.courseId).param("cycleNo", r.cycleNo).param("status", r.status)
                .param("protocol", r.protocolKey).param("level", r.levelPct).param("reasonKey", r.reductionReasonKey)
                .param("reasonText", r.reductionReasonText).param("inputs", r.inputs).param("snapshot", r.snapshot)
                .param("fingerprint", r.fingerprint).param("dox", r.doxorubicinMgM2).param("prior", r.priorOutsideMgM2)
                .param("editor", r.lastEditedByMembershipId).param("checker", r.checkerMembershipId)
                .param("checkerRole", r.checkerRole).param("namedAt", ts(r.checkerNamedAt))
                .param("namedBy", r.checkerNamedBy).param("confBy", r.confirmedByMembershipId)
                .param("confUser", r.confirmedByUserId).param("confVia", r.confirmedVia)
                .param("confAt", ts(r.confirmedAt)).param("confFp", r.confirmedFingerprint)
                .param("clearedAt", ts(r.confirmationClearedAt)).param("sentAt", ts(r.sentAt))
                .param("sentBy", r.sentBy).param("holdKind", r.holdKind).param("holdReason", r.holdReason)
                .param("heldAt", ts(r.heldAt)).param("actor", actorUserId).update() == 1;
    }

    @Override
    public List<ChemoOrderRow> sentForPatient(UUID tenantId, UUID patientId) {
        return jdbc.sql(SENT).param("tenantId", tenantId).param("patient", patientId)
                .query(JdbcChemoOrderRepository::row).list();
    }

    @Override
    public List<ChemoOrderRow> waitingFor(UUID tenantId, UUID checkerMembershipId, int offset, int limit) {
        return jdbc.sql(WAITING).param("tenantId", tenantId).param("checker", checkerMembershipId)
                .param("offset", offset).param("limit", limit).query(JdbcChemoOrderRepository::row).list();
    }

    @Override
    public long countWaitingFor(UUID tenantId, UUID checkerMembershipId) {
        return jdbc.sql(COUNT_WAITING).param("tenantId", tenantId).param("checker", checkerMembershipId)
                .query(Long.class).single();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ChemoOrderRow row(ResultSet rs, int rowNum) throws SQLException {
        ChemoOrderRow r = new ChemoOrderRow();
        r.id = rs.getObject("id", UUID.class);
        r.recordId = rs.getObject("record_id", UUID.class);
        r.recordCreatedAt = instant(rs, "record_created_at");
        r.patientId = rs.getObject("patient_id", UUID.class);
        r.courseId = rs.getObject("course_id", UUID.class);
        int cycle = rs.getInt("cycle_no");
        r.cycleNo = rs.wasNull() ? null : cycle;
        r.status = rs.getString("status");
        r.protocolKey = rs.getString("protocol_key");
        r.levelPct = rs.getInt("level_pct");
        r.reductionReasonKey = rs.getString("reduction_reason_key");
        r.reductionReasonText = rs.getString("reduction_reason_text");
        r.inputs = rs.getString("inputs");
        r.snapshot = rs.getString("snapshot");
        r.fingerprint = rs.getString("fingerprint");
        r.doxorubicinMgM2 = rs.getBigDecimal("doxorubicin_mg_m2");
        r.priorOutsideMgM2 = rs.getBigDecimal("prior_outside_mg_m2");
        r.prescriberMembershipId = rs.getObject("prescriber_membership_id", UUID.class);
        r.lastEditedByMembershipId = rs.getObject("last_edited_by_membership_id", UUID.class);
        r.checkerMembershipId = rs.getObject("checker_membership_id", UUID.class);
        r.checkerRole = rs.getString("checker_role");
        r.checkerNamedAt = instant(rs, "checker_named_at");
        r.checkerNamedBy = rs.getObject("checker_named_by", UUID.class);
        r.confirmedByMembershipId = rs.getObject("confirmed_by_membership_id", UUID.class);
        r.confirmedByUserId = rs.getObject("confirmed_by_user_id", UUID.class);
        r.confirmedVia = rs.getString("confirmed_via");
        r.confirmedAt = instant(rs, "confirmed_at");
        r.confirmedFingerprint = rs.getString("confirmed_fingerprint");
        r.confirmationClearedAt = instant(rs, "confirmation_cleared_at");
        r.sentAt = instant(rs, "sent_at");
        r.sentBy = rs.getObject("sent_by", UUID.class);
        r.holdKind = rs.getString("hold_kind");
        r.holdReason = rs.getString("hold_reason");
        r.heldAt = instant(rs, "held_at");
        r.revision = rs.getLong("revision");
        r.createdAt = instant(rs, "created_at");
        return r;
    }
}
