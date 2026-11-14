package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.EmergencyCaseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ipd.emergency_case} (SAH-28). The insert lists only the columns the database role may write; the id, the
 * clinic, the status, the arrival time (clock_timestamp()) and the version come from the database defaults. Each
 * update names the statuses it may start from, and sets every column the table checks need together, in one
 * statement. The two updates that close a case clear the mobile number in the same statement; no other update sends
 * it, and only the hand-off read selects it.
 */
@Repository
public class JdbcEmergencyCaseRepository implements EmergencyCaseRepository {

    /** Every read except the hand-off leaves the mobile number out (security review M1, code review 9). */
    private static final String COLUMNS = """
            id, patient_id, display_name, age, sex, cast(null as text) as mobile, complaint, identity_pending, level,
            bay, status, arrived_at, registered_by, seen_at, seen_by, closed_at, closed_by, admission_id, client_key,
            version
            """;
    private static final String HANDOFF_COLUMNS = """
            id, patient_id, display_name, age, sex, mobile, complaint, identity_pending, level,
            bay, status, arrived_at, registered_by, seen_at, seen_by, closed_at, closed_by, admission_id, client_key,
            version
            """;
    private static final String INSERT = """
            insert into ipd.emergency_case (display_name, age, sex, mobile, complaint, identity_pending, level, bay,
                                            registered_by, client_key)
            values (:name, :age, :sex, :mobile, :complaint, :pending, :level, :bay, :by, :clientKey)
            returning id, arrived_at
            """;
    private static final String CLOCK = "select clock_timestamp() as db_now";
    private static final String FIND_BY_CLIENT_KEY = "select " + COLUMNS + """
            from ipd.emergency_case
            where tenant_id = :tenantId and client_key = :clientKey
            """;
    private static final String FIND = "select " + COLUMNS + """
            from ipd.emergency_case
            where tenant_id = :tenantId and id = :id
            """;
    private static final String FIND_FOR_HANDOFF = "select " + HANDOFF_COLUMNS + """
            from ipd.emergency_case
            where tenant_id = :tenantId and id = :id
            """;
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String FIND_OPEN = "select " + COLUMNS + """
            from ipd.emergency_case
            where tenant_id = :tenantId and status in ('WAITING', 'SEEN')
            """;
    private static final String MARK_SEEN = """
            update ipd.emergency_case
            set status = 'SEEN', seen_at = :at, seen_by = :by, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'WAITING'
            """;
    private static final String UPDATE_LEVEL = """
            update ipd.emergency_case
            set level = :level, version = version + 1
            where tenant_id = :tenantId and id = :id and status in ('WAITING', 'SEEN')
            """;
    private static final String CLOSE = """
            update ipd.emergency_case
            set status = :status, closed_at = :at, closed_by = :by, mobile = null, version = version + 1
            where tenant_id = :tenantId and id = :id and status in ('WAITING', 'SEEN')
            """;
    private static final String CLOSE_SENT_TO_OPD = """
            update ipd.emergency_case
            set status = 'SENT_TO_OPD', patient_id = :patientId, sent_to_opd_visit_id = :visitId, closed_at = :at,
                closed_by = :by, mobile = null, version = version + 1
            where tenant_id = :tenantId and id = :id and status in ('WAITING', 'SEEN')
            """;
    private static final String MARK_ADMITTED = """
            update ipd.emergency_case
            set status = 'ADMITTED', admission_id = :admissionId, patient_id = :patientId, closed_at = :at,
                closed_by = :by, mobile = null, version = version + 1
            where tenant_id = :tenantId and id = :id and status in ('WAITING', 'SEEN')
            """;

    private final JdbcClient jdbc;

    public JdbcEmergencyCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CaseRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return jdbc.sql(FIND_BY_CLIENT_KEY).param("tenantId", tenantId).param("clientKey", clientKey)
                .query(JdbcEmergencyCaseRepository::row).optional();
    }

    @Override
    public Created insert(UUID tenantId, NewCase c) {
        return jdbc.sql(INSERT).param("name", c.displayName()).param("age", c.age(), Types.SMALLINT)
                .param("sex", c.sex()).param("mobile", c.mobile(), Types.VARCHAR).param("complaint", c.complaint())
                .param("pending", c.identityPending()).param("level", c.level())
                .param("bay", c.bay(), Types.VARCHAR).param("by", c.registeredBy()).param("clientKey", c.clientKey())
                .query((rs, rowNum) -> new Created(rs.getObject("id", UUID.class), instant(rs, "arrived_at")))
                .single();
    }

    @Override
    public Instant databaseNow() {
        return jdbc.sql(CLOCK).query((rs, rowNum) -> instant(rs, "db_now")).single();
    }

    @Override
    public Optional<CaseRow> find(UUID tenantId, UUID caseId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", caseId)
                .query(JdbcEmergencyCaseRepository::row).optional();
    }

    @Override
    public Optional<CaseRow> findForHandoff(UUID tenantId, UUID caseId) {
        return jdbc.sql(FIND_FOR_HANDOFF).param("tenantId", tenantId).param("id", caseId)
                .query(JdbcEmergencyCaseRepository::row).optional();
    }

    @Override
    public Optional<CaseRow> findForUpdate(UUID tenantId, UUID caseId) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", caseId)
                .query(JdbcEmergencyCaseRepository::row).optional();
    }

    @Override
    public List<CaseRow> findOpen(UUID tenantId) {
        return jdbc.sql(FIND_OPEN).param("tenantId", tenantId).query(JdbcEmergencyCaseRepository::row).list();
    }

    @Override
    public boolean markSeen(UUID tenantId, UUID caseId, Instant at, UUID by) {
        return jdbc.sql(MARK_SEEN).param("tenantId", tenantId).param("id", caseId).param("at", at)
                .param("by", by).update() == 1;
    }

    @Override
    public boolean updateLevel(UUID tenantId, UUID caseId, int level) {
        return jdbc.sql(UPDATE_LEVEL).param("tenantId", tenantId).param("id", caseId).param("level", level)
                .update() == 1;
    }

    @Override
    public boolean close(UUID tenantId, UUID caseId, String status, Instant at, UUID by) {
        return jdbc.sql(CLOSE).param("tenantId", tenantId).param("id", caseId).param("status", status)
                .param("at", at).param("by", by).update() == 1;
    }

    @Override
    public boolean closeSentToOpd(UUID tenantId, UUID caseId, UUID patientId, UUID opdVisitId, Instant at, UUID by) {
        return jdbc.sql(CLOSE_SENT_TO_OPD).param("tenantId", tenantId).param("id", caseId)
                .param("patientId", patientId).param("visitId", opdVisitId).param("at", at).param("by", by)
                .update() == 1;
    }

    @Override
    public boolean markAdmitted(UUID tenantId, UUID caseId, UUID admissionId, UUID patientId, Instant at, UUID by) {
        return jdbc.sql(MARK_ADMITTED).param("tenantId", tenantId).param("id", caseId)
                .param("admissionId", admissionId).param("patientId", patientId)
                .param("at", at).param("by", by).update() == 1;
    }

    private static CaseRow row(ResultSet rs, int rowNum) throws SQLException {
        int age = rs.getInt("age");
        Integer ageOrNull = rs.wasNull() ? null : age;
        return new CaseRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getString("display_name"), ageOrNull, rs.getString("sex"), rs.getString("mobile"),
                rs.getString("complaint"), rs.getBoolean("identity_pending"), rs.getInt("level"), rs.getString("bay"),
                rs.getString("status"), instant(rs, "arrived_at"), rs.getObject("registered_by", UUID.class),
                instant(rs, "seen_at"), rs.getObject("seen_by", UUID.class), instant(rs, "closed_at"),
                rs.getObject("closed_by", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getObject("client_key", UUID.class), rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
