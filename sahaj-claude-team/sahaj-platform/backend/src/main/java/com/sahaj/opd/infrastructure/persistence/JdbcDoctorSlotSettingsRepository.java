package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.DoctorSlotSettingsRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code opd.doctor_slot_template} and {@code opd.doctor_slot_session} (SAH-11). Row level
 * security scopes every statement to the bound tenant; {@code tenant_id} is denormalised onto the
 * template row, the same pattern {@code doctor_profile} uses.
 */
@Repository
public class JdbcDoctorSlotSettingsRepository implements DoctorSlotSettingsRepository {

    private static final String FIND_TEMPLATE = """
            select slot_minutes, working_days, walk_in_every_nth, max_online_per_day, close_before_hours
            from opd.doctor_slot_template
            where tenant_id = :tenantId and doctor_membership_id = :doctorMembershipId
            """;
    private static final String FIND_SESSIONS = """
            select session_index, start_time, end_time
            from opd.doctor_slot_session
            where tenant_id = :tenantId and doctor_membership_id = :doctorMembershipId
            order by session_index
            """;
    private static final String UPSERT_TEMPLATE = """
            insert into opd.doctor_slot_template
                (doctor_membership_id, slot_minutes, working_days, walk_in_every_nth, max_online_per_day, close_before_hours, updated_by)
            values
                (:doctorMembershipId, :slotMinutes, :workingDays, :walkInEveryNth, :maxOnlinePerDay, :closeBeforeHours, :actor)
            on conflict (doctor_membership_id) do update set
                slot_minutes = excluded.slot_minutes,
                working_days = excluded.working_days,
                walk_in_every_nth = excluded.walk_in_every_nth,
                max_online_per_day = excluded.max_online_per_day,
                updated_by = excluded.updated_by,
                close_before_hours = excluded.close_before_hours,
                updated_at = now(),
                version = opd.doctor_slot_template.version + 1
            """;
    private static final String FIND_ALL = """
            select t.doctor_membership_id, t.slot_minutes, t.working_days, t.walk_in_every_nth, t.max_online_per_day,
                   t.close_before_hours, s.session_index, s.start_time, s.end_time
            from opd.doctor_slot_template t
            left join opd.doctor_slot_session s
              on s.tenant_id = t.tenant_id and s.doctor_membership_id = t.doctor_membership_id
            where t.tenant_id = :tenantId
            order by t.doctor_membership_id, s.session_index
            """;
    private static final String DELETE_SESSIONS = """
            delete from opd.doctor_slot_session
            where tenant_id = :tenantId and doctor_membership_id = :doctorMembershipId
            """;
    private static final String INSERT_SESSION = """
            insert into opd.doctor_slot_session (doctor_membership_id, session_index, start_time, end_time)
            values (:doctorMembershipId, :sessionIndex, :startTime, :endTime)
            """;

    private final JdbcClient jdbc;

    public JdbcDoctorSlotSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SlotSettingsRow> find(UUID tenantId, UUID doctorMembershipId) {
        Optional<TemplateRow> template = jdbc.sql(FIND_TEMPLATE)
                .param("tenantId", tenantId)
                .param("doctorMembershipId", doctorMembershipId)
                .query(JdbcDoctorSlotSettingsRepository::mapTemplate)
                .optional();
        if (template.isEmpty()) {
            return Optional.empty();
        }
        List<SessionRow> sessions = jdbc.sql(FIND_SESSIONS)
                .param("tenantId", tenantId)
                .param("doctorMembershipId", doctorMembershipId)
                .query(JdbcDoctorSlotSettingsRepository::mapSession)
                .list();
        TemplateRow row = template.get();
        return Optional.of(new SlotSettingsRow(row.slotMinutes, row.workingDays, row.walkInEveryNth,
                row.maxOnlinePerDay, sessions, row.closeBeforeHours));
    }

    @Override
    public java.util.Map<UUID, SlotSettingsRow> findAllByTenant(UUID tenantId) {
        java.util.Map<UUID, TemplateRow> templates = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, List<SessionRow>> sessions = new java.util.HashMap<>();
        jdbc.sql(FIND_ALL).param("tenantId", tenantId).query((rs, n) -> {
            UUID id = rs.getObject("doctor_membership_id", UUID.class);
            templates.computeIfAbsent(id, key -> {
                try {
                    return mapTemplate(rs, n);
                } catch (SQLException e) {
                    throw new java.io.UncheckedIOException(new java.io.IOException(e));
                }
            });
            Time start = rs.getTime("start_time");
            if (start != null) {
                sessions.computeIfAbsent(id, key -> new ArrayList<>()).add(mapSession(rs, n));
            }
            return n;
        }).list();
        java.util.Map<UUID, SlotSettingsRow> result = new java.util.LinkedHashMap<>();
        templates.forEach((id, row) -> result.put(id, new SlotSettingsRow(row.slotMinutes, row.workingDays,
                row.walkInEveryNth, row.maxOnlinePerDay, sessions.getOrDefault(id, List.of()), row.closeBeforeHours)));
        return result;
    }

    @Override
    public void upsert(UUID tenantId, UUID doctorMembershipId, SlotSettingsRow row, UUID actorUserId) {
        jdbc.sql(UPSERT_TEMPLATE)
                .param("doctorMembershipId", doctorMembershipId)
                .param("slotMinutes", row.slotMinutes())
                .param("workingDays", row.workingDays().stream().mapToInt(Integer::intValue).toArray())
                .param("walkInEveryNth", row.walkInEveryNth())
                .param("maxOnlinePerDay", row.maxOnlinePerDay())
                .param("closeBeforeHours", row.closeBeforeHours(), java.sql.Types.INTEGER)
                .param("actor", actorUserId)
                .update();
        jdbc.sql(DELETE_SESSIONS)
                .param("tenantId", tenantId)
                .param("doctorMembershipId", doctorMembershipId)
                .update();
        for (SessionRow session : row.sessions()) {
            jdbc.sql(INSERT_SESSION)
                    .param("doctorMembershipId", doctorMembershipId)
                    .param("sessionIndex", session.sessionIndex())
                    .param("startTime", Time.valueOf(session.startTime()))
                    .param("endTime", Time.valueOf(session.endTime()))
                    .update();
        }
    }

    private static TemplateRow mapTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new TemplateRow(rs.getInt("slot_minutes"), toIntegerList(rs.getArray("working_days")),
                rs.getInt("walk_in_every_nth"), rs.getInt("max_online_per_day"),
                (Integer) rs.getObject("close_before_hours"));
    }

    private static SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(rs.getInt("session_index"), rs.getTime("start_time").toLocalTime(),
                rs.getTime("end_time").toLocalTime());
    }

    private static List<Integer> toIntegerList(Array array) throws SQLException {
        List<Integer> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        Object raw = array.getArray();
        if (raw instanceof Integer[] ints) {
            for (Integer value : ints) {
                values.add(value);
            }
        } else if (raw instanceof short[] shorts) {
            for (short value : shorts) {
                values.add((int) value);
            }
        } else if (raw instanceof Object[] objects) {
            for (Object value : objects) {
                values.add(((Number) value).intValue());
            }
        }
        return values;
    }

    private record TemplateRow(int slotMinutes, List<Integer> workingDays, int walkInEveryNth, int maxOnlinePerDay,
                               Integer closeBeforeHours) {
    }
}
