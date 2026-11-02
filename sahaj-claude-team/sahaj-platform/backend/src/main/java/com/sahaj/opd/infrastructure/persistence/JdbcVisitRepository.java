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

    private static final String SELECT = """
            select id, patient_id, doctor_membership_id, visit_type, stage, token_no, fee_paise, created_at, version,
                   workflow_version_no
            from opd.visit
            """;
    private static final String FIND_BY_ID = SELECT + " where tenant_id = :tenantId and id = :id";
    private static final String FIND_SINCE = SELECT
            + " where tenant_id = :tenantId and created_at >= :since order by token_no";
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
                rs.getInt("workflow_version_no"));
    }
}
