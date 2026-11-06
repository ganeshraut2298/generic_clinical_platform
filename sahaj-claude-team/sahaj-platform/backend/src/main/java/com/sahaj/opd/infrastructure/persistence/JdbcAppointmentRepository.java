package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.AppointmentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final String COUNT_BOOKED = """
            select count(*) from opd.appointment
            where tenant_id = :tenantId and doctor_membership_id = :doctorMembershipId
              and appt_date between :fromDate and :toDate and status = 'BOOKED'
            """;
    private static final String COUNT_BOOKED_BY_DAY = """
            select doctor_membership_id, appt_date, count(*) as booked from opd.appointment
            where tenant_id = :tenantId and doctor_membership_id in (:doctorIds)
              and appt_date between :fromDate and :toDate and status = 'BOOKED'
            group by doctor_membership_id, appt_date
            """;
    private static final String LOCK_SLOT =
            "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) locked";
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
    public void lockSlot(UUID tenantId, UUID doctorMembershipId, LocalDate apptDate, LocalTime apptTime) {
        String key = tenantId + "|" + doctorMembershipId + "|" + apptDate + "|" + apptTime;
        jdbc.sql(LOCK_SLOT).param("key", key).query(Long.class).single();
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
    public int countBooked(UUID tenantId, UUID doctorMembershipId, LocalDate from, LocalDate to) {
        return jdbc.sql(COUNT_BOOKED)
                .param("tenantId", tenantId)
                .param("doctorMembershipId", doctorMembershipId)
                .param("fromDate", from)
                .param("toDate", to)
                .query(Integer.class)
                .single();
    }

    @Override
    public Map<UUID, Map<LocalDate, Integer>> countBookedByDay(UUID tenantId, Collection<UUID> doctorMembershipIds,
                                                              LocalDate from, LocalDate to) {
        Map<UUID, Map<LocalDate, Integer>> result = new HashMap<>();
        if (doctorMembershipIds.isEmpty()) {
            return result;
        }
        jdbc.sql(COUNT_BOOKED_BY_DAY)
                .param("tenantId", tenantId)
                .param("doctorIds", doctorMembershipIds)
                .param("fromDate", from)
                .param("toDate", to)
                .query((rs, rowNum) -> {
                    result.computeIfAbsent(rs.getObject("doctor_membership_id", UUID.class), key -> new HashMap<>())
                            .put(rs.getObject("appt_date", LocalDate.class), rs.getInt("booked"));
                    return rowNum;
                })
                .list();
        return result;
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
