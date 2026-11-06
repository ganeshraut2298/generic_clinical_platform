package com.sahaj.patient.infrastructure.persistence;

import com.sahaj.patient.application.ConsentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code patient.patient_consent} (SAH-17). Append-only: only an insert and a select exist here;
 * {@code id}, {@code tenant_id} and {@code recorded_at} come from the table defaults.
 */
@Repository
public class JdbcConsentRepository implements ConsentRepository {

    private static final String INSERT = """
            insert into patient.patient_consent (patient_id, kind, given, recorded_by)
            values (:patientId, :kind, :given, :recordedBy)
            """;
    private static final String LATEST_PER_KIND = """
            select distinct on (kind) kind, given, recorded_at, recorded_by
            from patient.patient_consent
            where tenant_id = :tenantId and patient_id = :patientId
            order by kind, recorded_at desc, id desc
            """;

    private final JdbcClient jdbc;

    public JdbcConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID patientId, String kind, boolean given, UUID recordedBy) {
        jdbc.sql(INSERT).param("patientId", patientId).param("kind", kind).param("given", given)
                .param("recordedBy", recordedBy).update();
    }

    @Override
    public List<ConsentRow> findLatestPerKind(UUID tenantId, UUID patientId) {
        return jdbc.sql(LATEST_PER_KIND).param("tenantId", tenantId).param("patientId", patientId)
                .query(JdbcConsentRepository::map).list();
    }

    private static ConsentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ConsentRow(rs.getString("kind"), rs.getBoolean("given"),
                rs.getTimestamp("recorded_at").toInstant(), rs.getObject("recorded_by", UUID.class));
    }
}
