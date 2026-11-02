package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.AppointmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.appointment} (SAH-11). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcAppointmentRepository implements AppointmentRepository {

    private static final String SELECT = """
            select id, patient_id, doctor_membership_id, appt_date, appt_time, status, visit_type, source, note,
                   visit_id, version
            from opd.appointment
            """;
    private static final String FIND_BY_ID = SELECT + " where tenant_id = :tenantId and id = :id";
    private static final String FIND_BY_DATE = SELECT
            + " where tenant_id = :tenantId and appt_date = :apptDate"
            + " and (cast(:doctorMembershipId as uuid) is null or doctor_membership_id = :doctorMembershipId)"
            + " order by appt_time";
    private static final String FIND_ACTIVE_CONFLICT = SELECT
            + " where tenant_id = :tenantId and doctor_membership_id = :doctorMembershipId"
            + " and appt_date = :apptDate and appt_time = :apptTime and status in ('BOOKED', 'ARRIVED')";
    private static final String INSERT = """
            insert into opd.appointment
                (id, patient_id, doctor_membership_id, appt_date, appt_time, status, visit_type, source, note,
                 created_by)
            values
                (:id, :patientId, :doctorMembershipId, :apptDate, :apptTime, 'BOOKED', :visitType, :source, :note,
                 :actor)
            """;
    private static final String UPDATE_STATUS = """
            update opd.appointment
            set status = :status, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;
    private static final String SET_VISIT = """
            update opd.appointment
            set visit_id = :visitId, status = :status, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcAppointmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, UUID patientId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime,
                        String visitType, String source, String note, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("patientId", patientId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("apptDate", apptDate)
                .param("apptTime", Time.valueOf(apptTime))
                .param("visitType", visitType)
                .param("source", source)
                .param("note", note)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<AppointmentRow> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id)
                .query(JdbcAppointmentRepository::map).optional();
    }

    @Override
    public List<AppointmentRow> findByDate(UUID tenantId, LocalDate apptDate, UUID doctorMembershipId) {
        return jdbc.sql(FIND_BY_DATE)
                .param("tenantId", tenantId)
                .param("apptDate", apptDate)
                .param("doctorMembershipId", doctorMembershipId)
                .query(JdbcAppointmentRepository::map)
                .list();
    }

    @Override
    public Optional<AppointmentRow> findActiveConflict(UUID tenantId, UUID doctorMembershipId, LocalDate apptDate,
                                                         LocalTime apptTime) {
        return jdbc.sql(FIND_ACTIVE_CONFLICT)
                .param("tenantId", tenantId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("apptDate", apptDate)
                .param("apptTime", Time.valueOf(apptTime))
                .query(JdbcAppointmentRepository::map)
                .optional();
    }

    @Override
    public void updateStatus(UUID id, String status, UUID actorUserId) {
        jdbc.sql(UPDATE_STATUS).param("id", id).param("status", status).param("actor", actorUserId).update();
    }

    @Override
    public void setVisit(UUID id, UUID visitId, String status, UUID actorUserId) {
        jdbc.sql(SET_VISIT)
                .param("id", id)
                .param("visitId", visitId)
                .param("status", status)
                .param("actor", actorUserId)
                .update();
    }

    private static AppointmentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AppointmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("patient_id", UUID.class),
                rs.getObject("doctor_membership_id", UUID.class),
                rs.getObject("appt_date", LocalDate.class),
                rs.getTime("appt_time").toLocalTime(),
                rs.getString("status"),
                rs.getString("visit_type"),
                rs.getString("source"),
                rs.getString("note"),
                rs.getObject("visit_id", UUID.class),
                rs.getLong("version"));
    }
}
