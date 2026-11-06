package com.sahaj.ipd.infrastructure.persistence;

import com.sahaj.ipd.application.EscalationBoardRepository;
import com.sahaj.ipd.application.EscalationRepository;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import com.sahaj.ipd.domain.ResponseAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Early warning escalations (SAH-30, SAH-33). The insert lists only the columns the database role may write (status,
 * raise time and version come from the database). The score update sets score, risk and target together, as the risk
 * and target check requires, and only when the new score is strictly higher. The Safety board statements are the ones
 * the guard trigger accepts (docs/database/SAH-33.md): every one carries the status and version it expects, and none
 * sends {@code acknowledged_at}, {@code closed_at} or {@code reopened_at}, which the trigger stamps from the database
 * clock. {@code version} is a bigint. Rows are never deleted.
 */
@Repository
public class JdbcEscalationRepository implements EscalationRepository, EscalationBoardRepository {

    private static final String FIND_OPEN = """
            select id, admission_id, ews_score, ews_risk, target_minutes, status, raised_at, reopened_at
            from ipd.ews_escalation
            where tenant_id = :tenantId and admission_id = :admissionId and status <> 'DONE'
            """;
    private static final String INSERT = """
            insert into ipd.ews_escalation (admission_id, ews_score, ews_risk, target_minutes, raised_by)
            values (:admissionId, :score, :risk, :target, :by)
            returning id
            """;
    private static final String RAISE_SCORE = """
            update ipd.ews_escalation
            set ews_score = :score, ews_risk = :risk, target_minutes = :target, version = version + 1
            where tenant_id = :tenantId and id = :id and status <> 'DONE' and ews_score < :score
            """;
    private static final String REOPEN = """
            update ipd.ews_escalation
            set status = 'OPEN', acknowledged_by = null, ews_score = :score, ews_risk = 'HIGH', target_minutes = 15,
                version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'ACK' and ews_risk = 'MEDIUM'
            """;
    private static final String CLOSE_AT_DISCHARGE = """
            update ipd.ews_escalation
            set status = 'DONE', response_action = 'CLOSED_AT_DISCHARGE', closed_by = :user, version = version + 1
            where tenant_id = :tenantId and id = :id and status <> 'DONE'
            """;

    private static final String CLOCK = "select clock_timestamp() as db_now";
    private static final String COLUMNS = """
            e.id, e.admission_id, e.version, e.ews_score, e.ews_risk, e.target_minutes, e.status, e.raised_at,
            e.raised_by, e.reopened_at, e.acknowledged_by, e.acknowledged_at, e.response_action, e.response_note,
            e.senior_calls, e.closed_by, e.closed_at
            """;
    private static final String FIND_BY_ID = "select " + COLUMNS + """
            from ipd.ews_escalation e
            where e.tenant_id = :tenantId and e.id = :id
            """;
    private static final String ON_BOARD = "select " + COLUMNS + """
            , a.patient_id, b.code as bed_code
            from ipd.ews_escalation e
            join ipd.admission a on a.tenant_id = e.tenant_id and a.id = e.admission_id
            join ipd.bed b on b.tenant_id = a.tenant_id and b.id = a.bed_id
            where e.tenant_id = :tenantId and a.status = 'ADMITTED'
            """;
    private static final String ON_BOARD_24H = ON_BOARD
            + " and (e.status <> 'DONE' or e.closed_at > cast(:asOf as timestamptz) - interval '24 hours')";
    private static final String LIVE_ON_BOARD = ON_BOARD + " and e.status <> 'DONE'";

    private static final String ACKNOWLEDGE = """
            update ipd.ews_escalation
            set status = 'ACK', acknowledged_by = :doctor, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'OPEN' and version = :sent
            """;
    private static final String CLOSE_FROM_OPEN = """
            update ipd.ews_escalation
            set status = 'DONE', response_action = :action, response_note = :note, closed_by = :doctor,
                acknowledged_by = :doctor, version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'OPEN' and version = :sent
            """;
    private static final String CLOSE_FROM_ACK = """
            update ipd.ews_escalation
            set status = 'DONE', response_action = :action, response_note = :note, closed_by = :doctor,
                version = version + 1
            where tenant_id = :tenantId and id = :id and status = 'ACK' and version = :sent
            """;
    private static final String CALL_SENIOR = """
            update ipd.ews_escalation
            set senior_calls = senior_calls + 1
            where tenant_id = :tenantId and id = :id and status = 'OPEN' and version = :sent
              and senior_calls = :seen
            """;

    private final JdbcClient jdbc;

    public JdbcEscalationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EscalationRow> findOpen(UUID tenantId, UUID admissionId) {
        return jdbc.sql(FIND_OPEN).param("tenantId", tenantId).param("admissionId", admissionId)
                .query((rs, rowNum) -> new EscalationRow(rs.getObject("id", UUID.class),
                        rs.getObject("admission_id", UUID.class), rs.getInt("ews_score"),
                        Risk.valueOf(rs.getString("ews_risk")), rs.getInt("target_minutes"), rs.getString("status"),
                        instant(rs, "raised_at"), instant(rs, "reopened_at")))
                .optional();
    }

    @Override
    public UUID insert(UUID tenantId, UUID admissionId, int ewsScore, Risk ewsRisk, int targetMinutes,
                       UUID raisedBy) {
        return jdbc.sql(INSERT).param("admissionId", admissionId).param("score", ewsScore)
                .param("risk", ewsRisk.name()).param("target", targetMinutes).param("by", raisedBy)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class)).single();
    }

    @Override
    public boolean raiseScore(UUID tenantId, UUID id, int ewsScore, Risk ewsRisk, int targetMinutes) {
        return jdbc.sql(RAISE_SCORE).param("tenantId", tenantId).param("id", id).param("score", ewsScore)
                .param("risk", ewsRisk.name()).param("target", targetMinutes).update() == 1;
    }

    @Override
    public boolean reopen(UUID tenantId, UUID id, int ewsScore) {
        return jdbc.sql(REOPEN).param("tenantId", tenantId).param("id", id).param("score", ewsScore).update() == 1;
    }

    @Override
    public boolean closeAtDischarge(UUID tenantId, UUID id, UUID closedBy) {
        return jdbc.sql(CLOSE_AT_DISCHARGE).param("tenantId", tenantId).param("id", id).param("user", closedBy)
                .update() == 1;
    }

    // ---- the Safety board ----

    @Override
    public Instant databaseNow() {
        return jdbc.sql(CLOCK).query((rs, rowNum) -> rs.getObject("db_now", OffsetDateTime.class).toInstant())
                .single();
    }

    @Override
    public Optional<Detail> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcEscalationRepository::detail).optional();
    }

    @Override
    public List<BoardRow> findOnBoard(UUID tenantId, Instant asOf) {
        return jdbc.sql(ON_BOARD_24H).param("tenantId", tenantId).param("asOf", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))
                .query(JdbcEscalationRepository::boardRow).list();
    }

    @Override
    public List<BoardRow> findLiveOnBoard(UUID tenantId) {
        return jdbc.sql(LIVE_ON_BOARD).param("tenantId", tenantId).query(JdbcEscalationRepository::boardRow).list();
    }

    @Override
    public boolean acknowledge(UUID tenantId, UUID id, UUID doctor, long sentVersion) {
        return jdbc.sql(ACKNOWLEDGE).param("tenantId", tenantId).param("id", id).param("doctor", doctor)
                .param("sent", sentVersion).update() == 1;
    }

    @Override
    public boolean close(UUID tenantId, UUID id, Status from, UUID doctor, ResponseAction action, String note,
                         long sentVersion) {
        String sql = from == Status.OPEN ? CLOSE_FROM_OPEN : CLOSE_FROM_ACK;
        return jdbc.sql(sql).param("tenantId", tenantId).param("id", id).param("doctor", doctor)
                .param("action", action.name()).param("note", note).param("sent", sentVersion).update() == 1;
    }

    @Override
    public boolean callSenior(UUID tenantId, UUID id, long sentVersion, int seenCalls) {
        return jdbc.sql(CALL_SENIOR).param("tenantId", tenantId).param("id", id).param("sent", sentVersion)
                .param("seen", seenCalls).update() == 1;
    }

    private static BoardRow boardRow(ResultSet rs, int rowNum) throws SQLException {
        return new BoardRow(detail(rs, rowNum), rs.getObject("patient_id", UUID.class), rs.getString("bed_code"));
    }

    private static Detail detail(ResultSet rs, int rowNum) throws SQLException {
        return new Detail(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getLong("version"), rs.getInt("ews_score"), Risk.valueOf(rs.getString("ews_risk")),
                rs.getInt("target_minutes"), Status.of(rs.getString("status")), instant(rs, "raised_at"),
                rs.getObject("raised_by", UUID.class), instant(rs, "reopened_at"),
                rs.getObject("acknowledged_by", UUID.class), instant(rs, "acknowledged_at"),
                rs.getString("response_action"), rs.getString("response_note"), rs.getInt("senior_calls"),
                rs.getObject("closed_by", UUID.class), instant(rs, "closed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
