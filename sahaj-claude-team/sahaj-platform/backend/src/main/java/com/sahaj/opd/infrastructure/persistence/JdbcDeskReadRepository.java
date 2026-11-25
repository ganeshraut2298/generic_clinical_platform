package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.BookingRulesRepository.BookingRulesRow;
import com.sahaj.opd.application.DeskReadRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The front desk console's reads (SAH-90, ADR-032 D1). Row level security scopes every statement to the bound tenant; the
 * {@code tenant_id} filter is repeated so the planner can use the tenant-first indexes. Patient names and doctor names are not
 * joined here: they come through {@code patient.api} and {@code platform.api}.
 */
@Repository
public class JdbcDeskReadRepository implements DeskReadRepository {

    private static final String RULES_AND_FEES = """
            select b.close_before_hours, b.remind_before_hours, b.no_show_after_minutes, b.follow_priority, b.self_book,
                   b.overbook, (b.tenant_id is not null) as has_rules, s.follow_up_fee
            from (select cast(:tenantId as uuid) as tenant_id) t
            left join opd.booking_rules b on b.tenant_id = t.tenant_id
            left join opd.opd_settings s on s.tenant_id = t.tenant_id
            """;
    private static final String DEPARTMENTS = """
            select x.doctor_membership_id, d.department_key, d.name, d.starter_key, d.room_labels
            from opd.emr_department d
            join opd.emr_department_doctor x on x.tenant_id = d.tenant_id and x.department_id = d.id
            where d.tenant_id = :tenantId and d.published_version > 0
            order by d.name
            """;
    /** Index ix_appointment_tenant_date_active: tenant_id, appt_date where status <> 'CANCELLED'. */
    private static final String APPOINTMENTS = """
            select a.id, a.patient_id, a.doctor_membership_id, a.appt_date, a.appt_time, a.status, a.visit_type, a.version,
                   v.id as v_id, v.visit_type as v_type, v.stage as v_stage, v.token_no as v_token,
                   v.created_at as v_created, v.workflow_version_no as v_workflow,
                   v.consult_started_at as v_started, v.consult_ended_at as v_ended
            from opd.appointment a
            left join opd.visit v on v.tenant_id = a.tenant_id and v.id = a.visit_id
            where a.tenant_id = :tenantId and a.appt_date between :fromDate and :toDate and a.status <> 'CANCELLED'
            order by a.appt_date, a.appt_time
            limit :limit
            """;
    private static final String WALK_INS = """
            select v.patient_id, v.doctor_membership_id,
                   v.id as v_id, v.visit_type as v_type, v.stage as v_stage, v.token_no as v_token,
                   v.created_at as v_created, v.workflow_version_no as v_workflow,
                   v.consult_started_at as v_started, v.consult_ended_at as v_ended
            from opd.visit v
            where v.tenant_id = :tenantId and v.created_at >= :fromAt and v.created_at < :toAt
              and not exists (select 1 from opd.appointment a
                              where a.tenant_id = v.tenant_id and a.visit_id = v.id
                                and a.appt_date = :today and a.status <> 'CANCELLED')
            order by v.created_at
            limit :limit
            """;
    private static final String FOLLOW_UPS_OF_VISITS = """
            select f.id, f.source_visit_id, f.due_date, f.returned_visit_id, f.appointment_id,
                   (f.not_needed_at is not null) as not_needed, a.appt_date, a.appt_time
            from opd.follow_up f
            left join opd.appointment a on a.tenant_id = f.tenant_id and a.id = f.appointment_id
            where f.tenant_id = :tenantId
              and f.source_visit_id in (select v.id from opd.visit v
                                        where v.tenant_id = :tenantId and v.created_at >= :fromAt and v.created_at < :toAt)
            """;
    /** Index ix_follow_up_tenant_due_to_book: the three null tests are the partial-index predicate. */
    private static final String TO_BOOK = """
            select f.id, f.patient_id, f.doctor_membership_id, f.due_date, f.reason,
                   (f.source_admission_id is not null) as from_discharge, count(*) over () as total
            from opd.follow_up f
            where f.tenant_id = :tenantId
              and f.returned_visit_id is null and f.appointment_id is null and f.not_needed_at is null
              and f.due_date <= :today
            order by f.due_date, f.created_at
            limit :limit
            """;
    private static final String TAKEN = """
            select appt_date, appt_time from opd.appointment
            where tenant_id = :tenantId and doctor_membership_id = :doctorId
              and appt_date between :fromDate and :toDate
              and status <> 'CANCELLED' and status in ('BOOKED', 'ARRIVED')
            """;

    private final JdbcClient jdbc;

    public JdbcDeskReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RulesAndFees rulesAndFees(UUID tenantId) {
        return jdbc.sql(RULES_AND_FEES).param("tenantId", tenantId).query((rs, n) -> {
            BookingRulesRow rules = rs.getBoolean("has_rules")
                    ? new BookingRulesRow(rs.getInt("close_before_hours"), rs.getInt("remind_before_hours"),
                            rs.getInt("no_show_after_minutes"), rs.getBoolean("follow_priority"),
                            rs.getBoolean("self_book"), rs.getBoolean("overbook"))
                    : null;
            return new RulesAndFees(rules, rs.getString("follow_up_fee"));
        }).single();
    }

    @Override
    public List<DepartmentLink> doctorDepartments(UUID tenantId) {
        return jdbc.sql(DEPARTMENTS).param("tenantId", tenantId).query((rs, n) -> new DepartmentLink(
                rs.getObject("doctor_membership_id", UUID.class), rs.getString("department_key"), rs.getString("name"),
                rs.getString("starter_key"), rooms(rs.getArray("room_labels")))).list();
    }

    @Override
    public List<AppointmentCardRow> appointments(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql(APPOINTMENTS).param("tenantId", tenantId).param("fromDate", from).param("toDate", to)
                .param("limit", MAX_CARD_ROWS + 1)
                .query((rs, n) -> new AppointmentCardRow(rs.getObject("id", UUID.class),
                        rs.getObject("patient_id", UUID.class), rs.getObject("doctor_membership_id", UUID.class),
                        rs.getObject("appt_date", LocalDate.class), rs.getTime("appt_time").toLocalTime(),
                        rs.getString("status"), rs.getString("visit_type"), rs.getLong("version"), visitPart(rs)))
                .list();
    }

    @Override
    public List<VisitCardRow> walkInVisits(UUID tenantId, Instant from, Instant to, LocalDate today) {
        return jdbc.sql(WALK_INS).param("tenantId", tenantId).param("fromAt", Timestamp.from(from))
                .param("toAt", Timestamp.from(to)).param("today", today).param("limit", MAX_CARD_ROWS + 1)
                .query((rs, n) -> new VisitCardRow(rs.getObject("patient_id", UUID.class),
                        rs.getObject("doctor_membership_id", UUID.class), visitPart(rs)))
                .list();
    }

    @Override
    public List<FollowUpCardRow> followUpsOfVisits(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(FOLLOW_UPS_OF_VISITS).param("tenantId", tenantId).param("fromAt", Timestamp.from(from))
                .param("toAt", Timestamp.from(to))
                .query((rs, n) -> {
                    Time time = rs.getTime("appt_time");
                    return new FollowUpCardRow(rs.getObject("id", UUID.class), rs.getObject("source_visit_id", UUID.class),
                            rs.getObject("due_date", LocalDate.class), rs.getObject("returned_visit_id", UUID.class),
                            rs.getObject("appointment_id", UUID.class), rs.getBoolean("not_needed"),
                            rs.getObject("appt_date", LocalDate.class), time == null ? null : time.toLocalTime());
                }).list();
    }

    @Override
    public ToBookPage followUpsToBook(UUID tenantId, LocalDate today, int limit) {
        int[] total = {0};
        List<ToBookRow> rows = jdbc.sql(TO_BOOK).param("tenantId", tenantId).param("today", today).param("limit", limit)
                .query((rs, n) -> {
                    total[0] = rs.getInt("total");
                    return new ToBookRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                            rs.getObject("doctor_membership_id", UUID.class), rs.getObject("due_date", LocalDate.class),
                            rs.getString("reason"), rs.getBoolean("from_discharge"));
                }).list();
        return new ToBookPage(total[0], rows);
    }

    @Override
    public Map<LocalDate, Set<LocalTime>> takenSlots(UUID tenantId, UUID doctorMembershipId, LocalDate from,
                                                     LocalDate to) {
        Map<LocalDate, Set<LocalTime>> result = new HashMap<>();
        jdbc.sql(TAKEN).param("tenantId", tenantId).param("doctorId", doctorMembershipId).param("fromDate", from)
                .param("toDate", to).query((rs, n) -> {
                    result.computeIfAbsent(rs.getObject("appt_date", LocalDate.class), d -> new HashSet<>())
                            .add(rs.getTime("appt_time").toLocalTime());
                    return n;
                }).list();
        return result;
    }

    /** The joined visit columns, or null when the appointment has no visit (v_id is null). */
    private static VisitPart visitPart(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("v_id", UUID.class);
        if (id == null) {
            return null;
        }
        Timestamp started = rs.getTimestamp("v_started");
        Timestamp ended = rs.getTimestamp("v_ended");
        return new VisitPart(id, rs.getString("v_type"), rs.getString("v_stage"), rs.getInt("v_token"),
                rs.getTimestamp("v_created").toInstant(), rs.getInt("v_workflow"),
                started == null ? null : started.toInstant(), ended == null ? null : ended.toInstant());
    }

    private static List<String> rooms(Array array) throws SQLException {
        List<String> rooms = new ArrayList<>();
        if (array != null) {
            for (Object room : (Object[]) array.getArray()) {
                rooms.add((String) room);
            }
        }
        return rooms;
    }
}
