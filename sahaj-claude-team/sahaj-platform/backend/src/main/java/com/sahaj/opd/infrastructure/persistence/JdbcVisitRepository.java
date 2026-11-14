package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.VisitRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.visit} (SAH-9). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcVisitRepository implements VisitRepository {

    /** The visit with its EMR record (SAH-46): at most one record per visit, so the left join adds no rows. */
    private static final String SELECT = """
            select v.id, v.patient_id, v.doctor_membership_id, v.visit_type, v.stage, v.token_no, v.fee_paise,
                   v.created_at, v.version, v.workflow_version_no, r.id as emr_record_id, r.status as emr_status,
                   v.triage_level, v.triage_scale
            from opd.visit v
            left join opd.emr_record r on r.tenant_id = v.tenant_id and r.opd_visit_id = v.id
            """;
    private static final String FIND_BY_ID = SELECT + " where v.tenant_id = :tenantId and v.id = :id";
    private static final String FIND_SINCE = SELECT
            + " where v.tenant_id = :tenantId and v.created_at >= :since order by v.token_no";
    private static final String FIND_BETWEEN = SELECT
            + " where v.tenant_id = :tenantId and v.created_at >= :from and v.created_at < :to order by v.created_at";
    private static final String FIND_BY_IDS = SELECT + " where v.tenant_id = :tenantId and v.id in (:ids)";
    private static final String MOVE_IF_CURRENT = """
            update opd.visit
            set stage = :to, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id and stage = :from
            """;
    private static final String UPDATE_TYPE = """
            update opd.visit
            set visit_type = :visitType, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;
    private static final String UPDATE_TYPE_TRIAGE = """
            update opd.visit
            set visit_type = :visitType, triage_level = :level, triage_scale = :scale, updated_by = :actor,
                updated_at = now(), version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String UPDATE_FEE = """
            update opd.visit set fee_paise = :fee, updated_by = :actor, updated_at = now(), version = version + 1
            where tenant_id = :tenantId and id = :id
            """;
    private static final String LOCK_BY_ID = "select id from opd.visit where tenant_id = :tenantId and id = :id for update";
    private static final String LAST_VISIT_BEFORE = """
            select created_at from opd.visit
            where tenant_id = :tenantId and patient_id = :patientId and doctor_membership_id = :doctorMembershipId
              and created_at < :before
            order by created_at desc limit 1
            """;
    private static final String PREVIOUS_VISIT = """
            select created_at from opd.visit
            where tenant_id = :tenantId and patient_id = :patientId and created_at < :before
            order by created_at desc limit 1
            """;
    private static final String COUNT_UP_TO = """
            select count(*) from opd.visit
            where tenant_id = :tenantId and patient_id = :patientId and created_at <= :upTo
            """;
    private static final String LAST_VISIT_AT = """
            select created_at from opd.visit
            where tenant_id = :tenantId and patient_id = :patientId and doctor_membership_id = :doctorMembershipId
            order by created_at desc
            limit 1
            """;
    private static final String INSERT = """
            insert into opd.visit (id, patient_id, doctor_membership_id, visit_type, stage, token_no, fee_paise,
                                    workflow_version_no, created_by)
            values (:id, :patientId, :doctorMembershipId, :visitType, :stage, :tokenNo, :feePaise,
                    :workflowVersionNo, :actor)
            """;
    private static final String UPDATE_STAGE = """
            update opd.visit
            set stage = :stage, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcVisitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int nextTokenNo() {
        return jdbc.sql("select opd.next_token_no()").query(Integer.class).single();
    }

    @Override
    public void insert(UUID id, UUID patientId, UUID doctorMembershipId, String visitType, String stage,
                        int tokenNo, long feePaise, int workflowVersionNo, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("visitType", visitType)
                .param("stage", stage)
                .param("tokenNo", tokenNo)
                .param("feePaise", feePaise)
                .param("workflowVersionNo", workflowVersionNo)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<VisitRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcVisitRepository::map).optional();
    }

    @Override
    public List<VisitRow> findSince(UUID tenantId, Instant since) {
        return jdbc.sql(FIND_SINCE).param("tenantId", tenantId).param("since", since)
                .query(JdbcVisitRepository::map).list();
    }

    @Override
    public List<VisitRow> findBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(FIND_BETWEEN).param("tenantId", tenantId).param("from", from).param("to", to)
                .query(JdbcVisitRepository::map).list();
    }

    @Override
    public List<VisitRow> findByIds(UUID tenantId, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FIND_BY_IDS).param("tenantId", tenantId).param("ids", ids)
                .query(JdbcVisitRepository::map).list();
    }

    @Override
    public Optional<Instant> lastVisitAt(UUID tenantId, UUID patientId, UUID doctorMembershipId) {
        // Zero rows when there is no prior visit; at most one row (the latest) otherwise.
        return jdbc.sql(LAST_VISIT_AT)
                .param("tenantId", tenantId)
                .param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId)
                .query((rs, rowNum) -> rs.getTimestamp("created_at"))
                .optional()
                .map(java.sql.Timestamp::toInstant);
    }

    @Override
    public void updateStage(UUID id, String stage, UUID actorUserId) {
        jdbc.sql(UPDATE_STAGE).param("id", id).param("stage", stage).param("actor", actorUserId).update();
    }

    @Override
    public boolean moveStageIfCurrent(UUID id, String from, String to, UUID actorUserId) {
        return jdbc.sql(MOVE_IF_CURRENT).param("id", id).param("from", from).param("to", to)
                .param("actor", actorUserId).update() == 1;
    }

    @Override
    public void updateVisitType(UUID id, String visitType, UUID actorUserId) {
        jdbc.sql(UPDATE_TYPE).param("id", id).param("visitType", visitType).param("actor", actorUserId).update();
    }

    @Override
    public void updateVisitTypeAndTriage(UUID tenantId, UUID id, String visitType, Integer triageLevel,
                                         Integer triageScale, UUID actorUserId) {
        jdbc.sql(UPDATE_TYPE_TRIAGE).param("tenantId", tenantId).param("id", id).param("visitType", visitType)
                .param("level", triageLevel, java.sql.Types.SMALLINT).param("scale", triageScale, java.sql.Types.SMALLINT)
                .param("actor", actorUserId).update();
    }

    @Override
    public void updateFee(UUID tenantId, UUID id, long feePaise, UUID actorUserId) {
        jdbc.sql(UPDATE_FEE).param("tenantId", tenantId).param("id", id).param("fee", feePaise)
                .param("actor", actorUserId).update();
    }

    @Override
    public boolean lockById(UUID tenantId, UUID id) {
        return jdbc.sql(LOCK_BY_ID).param("tenantId", tenantId).param("id", id).query(UUID.class).optional().isPresent();
    }

    @Override
    public Optional<Instant> lastVisitBefore(UUID tenantId, UUID patientId, UUID doctorMembershipId, Instant before) {
        return jdbc.sql(LAST_VISIT_BEFORE).param("tenantId", tenantId).param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId).param("before", java.sql.Timestamp.from(before))
                .query((rs, rowNum) -> rs.getTimestamp("created_at").toInstant()).optional();
    }

    @Override
    public Optional<Instant> previousVisitAt(UUID tenantId, UUID patientId, Instant before) {
        return jdbc.sql(PREVIOUS_VISIT).param("tenantId", tenantId).param("patientId", patientId)
                .param("before", java.sql.Timestamp.from(before))
                .query((rs, rowNum) -> rs.getTimestamp("created_at").toInstant()).optional();
    }

    @Override
    public int countVisitsUpTo(UUID tenantId, UUID patientId, Instant upTo) {
        return jdbc.sql(COUNT_UP_TO).param("tenantId", tenantId).param("patientId", patientId)
                .param("upTo", java.sql.Timestamp.from(upTo)).query(Integer.class).single();
    }

    private static VisitRow map(ResultSet rs, int rowNum) throws SQLException {
        return new VisitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("doctor_membership_id", UUID.class),
                rs.getString("visit_type"),
                rs.getString("stage"),
                rs.getInt("token_no"),
                rs.getLong("fee_paise"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"),
                rs.getInt("workflow_version_no"),
                rs.getObject("emr_record_id", UUID.class),
                rs.getString("emr_status") == null ? "NONE" : rs.getString("emr_status"),
                (Integer) rs.getObject("triage_level"), (Integer) rs.getObject("triage_scale"));
    }
}
