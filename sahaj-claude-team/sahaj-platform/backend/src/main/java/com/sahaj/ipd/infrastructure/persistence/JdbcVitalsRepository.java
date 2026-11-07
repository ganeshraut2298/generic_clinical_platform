package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.VitalsRepository;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Vitals and nursing notes (SAH-30). Insert statements list only the columns the database role may write; ids, the
 * clinic and the recording time come from the database. Both tables are append-only. Vitals and notes are clinical
 * data and are never logged.
 */
@Repository
public class JdbcVitalsRepository implements VitalsRepository {

    private static final String COLUMNS = """
            v.id, v.admission_id, v.bp_systolic, v.bp_diastolic, v.pulse, v.spo2, v.temp_c, v.rr, v.ews_score,
            v.ews_risk, v.note_added, v.recorded_at, v.recorded_by
            """;
    private static final String FIND_LATEST = "select " + COLUMNS + """
            from ipd.vitals v
            where v.tenant_id = :tenantId and v.admission_id = :admissionId
            order by v.recorded_at desc, v.id desc
            limit 1
            """;
    // SAH-36 (ADR-013 R4): one probe of ix_vitals_tenant_admission_recorded per admitted admission, newest first.
    private static final String FIND_LATEST_OF_ADMITTED = """
            select v.admission_id, v.ews_score, v.ews_risk, v.recorded_at
            from ipd.admission a
            cross join lateral (
                select x.admission_id, x.ews_score, x.ews_risk, x.recorded_at
                from ipd.vitals x
                where x.tenant_id = a.tenant_id and x.admission_id = a.id
                order by x.recorded_at desc, x.id desc
                limit 1) v
            where a.tenant_id = :tenantId and a.status = 'ADMITTED'
            """;
    private static final String FIND_BY_CLIENT_KEY = "select " + COLUMNS + """
            from ipd.vitals v
            where v.tenant_id = :tenantId and v.client_key = :clientKey
            """;
    private static final String INSERT = """
            insert into ipd.vitals (admission_id, client_key, bp_systolic, bp_diastolic, pulse, spo2, temp_c, rr,
                                    ews_score, ews_risk, note_added, recorded_by)
            values (:admissionId, :clientKey, :systolic, :diastolic, :pulse, :spo2, :temp, :rr, :score, :risk,
                    :noteAdded, :by)
            returning id, recorded_at
            """;
    private static final String INSERT_NOTE = """
            insert into ipd.nursing_note (admission_id, note, recorded_by)
            values (:admissionId, :note, :by)
            returning id
            """;
    private static final String CLOCK = "select clock_timestamp() as db_now";
    private static final String FIND_NOTES = """
            select id, note, recorded_at, recorded_by
            from ipd.nursing_note
            where tenant_id = :tenantId and admission_id = :admissionId
            order by recorded_at desc, id desc
            limit :limit
            """;

    private final JdbcClient jdbc;

    public JdbcVitalsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant databaseNow() {
        return jdbc.sql(CLOCK).query((rs, rowNum) -> rs.getObject("db_now", OffsetDateTime.class).toInstant()).single();
    }

    @Override
    public Optional<VitalsRow> findLatest(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_LATEST).param("tenantId", tenantId).param("admissionId", admissionId)
                .query(JdbcVitalsRepository::vitals).optional();
    }

    @Override
    public Map<UUID, LatestScore> findLatestOfAdmitted(UUID tenantId) {
        Map<UUID, LatestScore> latest = new HashMap<>();
        jdbc.sql(FIND_LATEST_OF_ADMITTED).param("tenantId", tenantId)
                .query((rs, rowNum) -> new LatestScore(rs.getObject("admission_id", UUID.class),
                        rs.getInt("ews_score"), Risk.valueOf(rs.getString("ews_risk")),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .list().forEach(row -> latest.put(row.admissionId(), row));
        return latest;
    }

    @Override
    public Optional<VitalsRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query(JdbcVitalsRepository::vitals).optional();
    }

    @Override
    public Stored insert(UUID tenantId, NewVitals v) {
        return jdbc.sql(INSERT).param("admissionId", v.admissionId()).param("clientKey", v.clientKey())
                .param("systolic", v.bpSystolic()).param("diastolic", v.bpDiastolic()).param("pulse", v.pulse())
                .param("spo2", v.spo2()).param("temp", v.tempC()).param("rr", v.rr()).param("score", v.ewsScore())
                .param("risk", v.ewsRisk().name()).param("noteAdded", v.noteAdded()).param("by", v.recordedBy())
                .query((rs, rowNum) -> new Stored(rs.getObject("id", UUID.class),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    @Override
    public UUID insertNote(UUID tenantId, UUID admissionId, String note, UUID recordedBy) {
        return jdbc.sql(INSERT_NOTE).param("admissionId", admissionId).param("note", note).param("by", recordedBy)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public List<NoteRow> findRecentNotes(UUID tenantId, UUID admissionId, int limit) {
        return jdbc.sql(FIND_NOTES).param("tenantId", tenantId).param("admissionId", admissionId)
                .param("limit", limit)
                .query((rs, rowNum) -> new NoteRow(rs.getObject("id", UUID.class), rs.getString("note"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_by", UUID.class)))
                .list();
    }

    private static VitalsRow vitals(ResultSet rs, int rowNum) throws SQLException {
        return new VitalsRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getInt("bp_systolic"), rs.getInt("bp_diastolic"), rs.getInt("pulse"), rs.getInt("spo2"),
                rs.getBigDecimal("temp_c"), rs.getInt("rr"), rs.getInt("ews_score"),
                Risk.valueOf(rs.getString("ews_risk")), rs.getBoolean("note_added"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                rs.getObject("recorded_by", UUID.class));
    }
}
