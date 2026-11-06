package com.sahaj.patient.infrastructure.persistence;

import com.sahaj.patient.application.RecordAccessRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code patient.record_access} (SAH-17). Append-only: only an insert and a select exist here.
 * Ids and the time only; {@code tenant_id}, {@code id} and {@code at} come from the table defaults.
 */
@Repository
public class JdbcRecordAccessRepository implements RecordAccessRepository {

    private static final String INSERT = """
            insert into patient.record_access (patient_id, user_id)
            values (:patientId, :userId)
            """;
    private static final String RECENT = """
            select patient_id, user_id, at
            from patient.record_access
            where tenant_id = :tenantId
            order by at desc, id desc
            limit :limit
            """;

    private final JdbcClient jdbc;

    public JdbcRecordAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID patientId, UUID userId) {
        jdbc.sql(INSERT).param("patientId", patientId).param("userId", userId).update();
    }

    @Override
    public List<AccessRow> findRecent(UUID tenantId, int limit) {
        return jdbc.sql(RECENT).param("tenantId", tenantId).param("limit", limit)
                .query(JdbcRecordAccessRepository::map).list();
    }

    private static AccessRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AccessRow(rs.getObject("patient_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getTimestamp("at").toInstant());
    }
}
