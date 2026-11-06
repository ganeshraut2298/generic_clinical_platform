package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.TriageEventRepository;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.emergency_triage_event} (SAH-28). The insert lists only the columns the database role may write; the id,
 * the clinic and the time come from the database defaults.
 */
@Repository
public class JdbcTriageEventRepository implements TriageEventRepository {

    private static final String INSERT = """
            insert into ipd.emergency_triage_event (case_id, from_level, level, scale, reason, recorded_by)
            values (:caseId, :fromLevel, :level, :scale, :reason, :by)
            """;
    private static final String FIND_BY_CASE = """
            select id, case_id, from_level, level, scale, reason, recorded_at, recorded_by
            from ipd.emergency_triage_event
            where tenant_id = :tenantId and case_id = :caseId
            order by recorded_at, id
            """;

    private final JdbcClient jdbc;

    public JdbcTriageEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID tenantId, UUID caseId, Integer fromLevel, int level, int scale, String reason, UUID by) {
        jdbc.sql(INSERT).param("caseId", caseId).param("fromLevel", fromLevel, Types.SMALLINT).param("level", level)
                .param("scale", scale).param("reason", reason).param("by", by).update();
    }

    @Override
    public List<EventRow> findByCase(UUID tenantId, UUID caseId) {
        return jdbc.sql(FIND_BY_CASE).param("tenantId", tenantId).param("caseId", caseId)
                .query((rs, rowNum) -> {
                    int from = rs.getInt("from_level");
                    Integer fromLevel = rs.wasNull() ? null : from;
                    OffsetDateTime at = rs.getObject("recorded_at", OffsetDateTime.class);
                    return new EventRow(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), fromLevel,
                            rs.getInt("level"), rs.getInt("scale"), rs.getString("reason"), at.toInstant(),
                            rs.getObject("recorded_by", UUID.class));
                }).list();
    }
}
