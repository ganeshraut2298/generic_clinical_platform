package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.FollowUpRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.follow_up} (SAH-14). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcFollowUpRepository implements FollowUpRepository {

    private static final String INSERT = """
            insert into opd.follow_up
                (id, patient_id, doctor_membership_id, source_visit_id, due_date, reason, created_by)
            values
                (:id, :patientId, :doctorMembershipId, :sourceVisitId, :dueDate, :reason, :actor)
            on conflict (source_visit_id) do nothing
            """;
    private static final String INSERT_FOR_ADMISSION = """
            insert into opd.follow_up
                (id, patient_id, doctor_membership_id, source_admission_id, due_date, reason, created_by)
            values
                (:id, :patientId, :doctorMembershipId, :sourceAdmissionId, :dueDate, :reason, :actor)
            on conflict (source_admission_id) do nothing
            """;
    private static final String FIND_BY_ADMISSION = """
            select id from opd.follow_up
            where tenant_id = :tenantId and source_admission_id = :sourceAdmissionId
            """;
    private static final String LAST_DISCHARGE_AT = """
            select created_at from opd.follow_up
            where tenant_id = :tenantId and patient_id = :patientId and doctor_membership_id = :doctorId
              and source_admission_id is not null
            order by created_at desc
            limit 1
            """;
    private static final String CLOSE_OLDEST = """
            update opd.follow_up
            set returned_visit_id = :returnedVisitId, returned_at = now(), updated_by = :actor, updated_at = now(),
                version = version + 1
            where id = (
                select id from opd.follow_up
                where tenant_id = :tenantId and patient_id = :patientId and doctor_membership_id = :doctorId
                  and returned_visit_id is null and source_visit_id is distinct from :returnedVisitId
                order by due_date, created_at
                limit 1
                for update)
            returning id
            """;
    private static final String COUNTS = """
            select count(*) filter (where returned_visit_id is null and due_date = :today) as due,
                   count(*) filter (where returned_visit_id is null and due_date < :today) as missed,
                   count(*) filter (where returned_visit_id is null and due_date > :today) as upcoming,
                   count(*) filter (where returned_visit_id is not null) as returned
            from opd.follow_up
            where tenant_id = :tenantId
            """;
    private static final String COUNT_RETURNED_SINCE = """
            select count(*) from opd.follow_up
            where tenant_id = :tenantId and returned_visit_id is not null and returned_at >= :since
            """;
    private static final String SELECT = """
            select id, patient_id, doctor_membership_id, source_visit_id, source_admission_id, due_date, reason,
                   returned_visit_id
            from opd.follow_up
            where tenant_id = :tenantId and""";
    private static final String ORDER_BY_DUE = " order by due_date, created_at limit " + MAX_ROWS;
    private static final String ORDER_BY_RETURN = " order by returned_at desc, created_at limit " + MAX_ROWS;

    private final JdbcClient jdbc;

    public JdbcFollowUpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(UUID id, UUID patientId, UUID doctorMembershipId, UUID sourceVisitId,
                                   LocalDate dueDate, String reason, UUID actorUserId) {
        return jdbc.sql(INSERT)
                .param("id", id)
                .param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("sourceVisitId", sourceVisitId)
                .param("dueDate", dueDate)
                .param("reason", reason)
                .param("actor", actorUserId)
                .update() > 0;
    }

    @Override
    public boolean insertForAdmissionIfAbsent(UUID id, UUID patientId, UUID doctorMembershipId,
                                              UUID sourceAdmissionId, LocalDate dueDate, String reason,
                                              UUID actorUserId) {
        return jdbc.sql(INSERT_FOR_ADMISSION)
                .param("id", id)
                .param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("sourceAdmissionId", sourceAdmissionId)
                .param("dueDate", dueDate)
                .param("reason", reason)
                .param("actor", actorUserId)
                .update() > 0;
    }

    @Override
    public Optional<UUID> findIdBySourceAdmission(UUID tenantId, UUID sourceAdmissionId) {
        return jdbc.sql(FIND_BY_ADMISSION)
                .param("tenantId", tenantId)
                .param("sourceAdmissionId", sourceAdmissionId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<Instant> lastDischargeAt(UUID tenantId, UUID patientId, UUID doctorMembershipId) {
        return jdbc.sql(LAST_DISCHARGE_AT)
                .param("tenantId", tenantId)
                .param("patientId", patientId)
                .param("doctorId", doctorMembershipId)
                .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    public Optional<UUID> closeOldestOpen(UUID tenantId, UUID patientId, UUID doctorMembershipId,
                                           UUID returnedVisitId, UUID actorUserId) {
        return jdbc.sql(CLOSE_OLDEST)
                .param("tenantId", tenantId)
                .param("patientId", patientId)
                .param("doctorId", doctorMembershipId)
                .param("returnedVisitId", returnedVisitId)
                .param("actor", actorUserId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Counts counts(UUID tenantId, LocalDate today) {
        return jdbc.sql(COUNTS)
                .param("tenantId", tenantId)
                .param("today", today)
                .query((rs, rowNum) -> new Counts(rs.getInt("due"), rs.getInt("missed"), rs.getInt("upcoming"),
                        rs.getInt("returned")))
                .single();
    }

    @Override
    public int countReturnedSince(UUID tenantId, Instant since) {
        return jdbc.sql(COUNT_RETURNED_SINCE)
                .param("tenantId", tenantId)
                .param("since", since)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<FollowUpRow> findByStatus(UUID tenantId, String status, LocalDate today) {
        if ("RETURNED".equals(status)) {
            return jdbc.sql(SELECT + " returned_visit_id is not null" + ORDER_BY_RETURN)
                    .param("tenantId", tenantId)
                    .query(JdbcFollowUpRepository::map)
                    .list();
        }
        String condition = switch (status) {
            case "DUE" -> "returned_visit_id is null and due_date = :today";
            case "MISSED" -> "returned_visit_id is null and due_date < :today";
            case "UPCOMING" -> "returned_visit_id is null and due_date > :today";
            default -> throw new IllegalArgumentException("Unknown follow-up status");
        };
        return jdbc.sql(SELECT + " " + condition + ORDER_BY_DUE)
                .param("tenantId", tenantId)
                .param("today", today)
                .query(JdbcFollowUpRepository::map)
                .list();
    }

    private static FollowUpRow map(ResultSet rs, int rowNum) throws SQLException {
        return new FollowUpRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("doctor_membership_id", UUID.class), rs.getObject("source_visit_id", UUID.class),
                rs.getObject("source_admission_id", UUID.class),
                rs.getObject("due_date", LocalDate.class), rs.getString("reason"),
                rs.getObject("returned_visit_id", UUID.class));
    }
}
