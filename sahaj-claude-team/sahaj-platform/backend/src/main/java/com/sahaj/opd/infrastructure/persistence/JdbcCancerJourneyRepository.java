package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.emr.specialty.CancerJourneyRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The patient's Cancer journey versions (SAH-75-7; ADR-026 D7). Append-only: this class only selects and inserts. The
 * insert names only the columns the application role may insert; the id, the clinic and the time are the database's.
 */
@Repository
public class JdbcCancerJourneyRepository implements CancerJourneyRepository {

    private static final String COLUMNS = """
            id, patient_id, version, stage_group, tnm_text, markers_text, ki67_pct, genetics_text, intent, setting,
            line_no, board_date, source_record_id, recorded_by_membership_id, recorded_by_user_id, recorded_at
            """;
    private static final String LATEST = "select " + COLUMNS + " from opd.emr_cancer_journey"
            + " where tenant_id = :tenantId and patient_id = :patientId order by version desc limit 1";
    private static final String INSERT = """
            insert into opd.emr_cancer_journey (patient_id, version, stage_group, tnm_text, markers_text, ki67_pct,
                                                genetics_text, intent, setting, line_no, board_date, source_record_id,
                                                recorded_by_membership_id, recorded_by_user_id)
            values (:patientId, :version, :stage, :tnm, :markers, :ki67, :genetics, :intent, :setting, :line, :board,
                    :source, :member, :user)
            returning id
            """;

    private final JdbcClient jdbc;

    public JdbcCancerJourneyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<JourneyRow> latest(UUID tenantId, UUID patientId) {
        return jdbc.sql(LATEST).param("tenantId", tenantId).param("patientId", patientId)
                .query(JdbcCancerJourneyRepository::row).optional();
    }

    @Override
    public UUID insert(UUID tenantId, JourneyRow r) {
        return jdbc.sql(INSERT).param("patientId", r.patientId()).param("version", r.version())
                .param("stage", r.stageGroup()).param("tnm", r.tnmText()).param("markers", r.markersText())
                .param("ki67", r.ki67Pct()).param("genetics", r.geneticsText()).param("intent", r.intent())
                .param("setting", r.setting()).param("line", r.lineNo()).param("board", r.boardDate())
                .param("source", r.sourceRecordId()).param("member", r.recordedByMembershipId())
                .param("user", r.recordedByUserId()).query((rs, n) -> rs.getObject(1, UUID.class)).single();
    }

    private static JourneyRow row(ResultSet rs, int n) throws SQLException {
        Timestamp at = rs.getTimestamp("recorded_at");
        return new JourneyRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getInt("version"), rs.getString("stage_group"), rs.getString("tnm_text"),
                rs.getString("markers_text"), integer(rs, "ki67_pct"), rs.getString("genetics_text"),
                rs.getString("intent"), rs.getString("setting"), integer(rs, "line_no"),
                rs.getObject("board_date", LocalDate.class), rs.getObject("source_record_id", UUID.class),
                rs.getObject("recorded_by_membership_id", UUID.class), rs.getObject("recorded_by_user_id", UUID.class),
                at == null ? null : at.toInstant());
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
