package com.sahaj.hra.infrastructure.persistence;

import com.sahaj.hra.application.AttendanceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code hra.attendance} (SAH-20): one row per member and IST day, changed in place the same day, never
 * deleted. {@code ck_attendance_marked_time} needs {@code marked_time} null for {@code abs} and set for
 * {@code in} and {@code late}, so every upsert writes status and time together.
 */
@Repository
public class JdbcAttendanceRepository implements AttendanceRepository {

    private static final String FIND_FOR_DATE = """
            select membership_id, work_date, status, marked_time
            from hra.attendance
            where tenant_id = :tenantId and work_date = :workDate
            """;
    private static final String FIND = """
            select membership_id, work_date, status, marked_time
            from hra.attendance
            where tenant_id = :tenantId and membership_id = :membershipId and work_date = :workDate
            """;
    private static final String UPSERT = """
            insert into hra.attendance
                (tenant_id, membership_id, work_date, status, marked_time, marked_by, marked_at)
            values
                (:tenantId, :membershipId, :workDate, :status, cast(:markedTime as time), :actor, :now)
            on conflict (tenant_id, membership_id, work_date) do update set
                status = excluded.status,
                marked_time = excluded.marked_time,
                marked_by = excluded.marked_by,
                marked_at = excluded.marked_at,
                version = hra.attendance.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcAttendanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AttendanceRow> findForDate(UUID tenantId, LocalDate date) {
        return jdbc.sql(FIND_FOR_DATE)
                .param("tenantId", tenantId)
                .param("workDate", date)
                .query(JdbcAttendanceRepository::map)
                .list();
    }

    @Override
    public Optional<AttendanceRow> find(UUID tenantId, UUID membershipId, LocalDate date) {
        return jdbc.sql(FIND)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("workDate", date)
                .query(JdbcAttendanceRepository::map)
                .optional();
    }

    @Override
    public void upsert(UUID tenantId, UUID membershipId, LocalDate date, String status, LocalTime markedTime,
                       UUID actorUserId, Instant now) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("workDate", date)
                .param("status", status)
                .param("markedTime", markedTime == null ? null : Time.valueOf(markedTime))
                .param("actor", actorUserId)
                .param("now", now)
                .update();
    }

    private static AttendanceRow map(ResultSet rs, int rowNum) throws SQLException {
        Time markedTime = rs.getTime("marked_time");
        return new AttendanceRow(rs.getObject("membership_id", UUID.class), rs.getObject("work_date", LocalDate.class),
                rs.getString("status"), markedTime == null ? null : markedTime.toLocalTime());
    }
}
