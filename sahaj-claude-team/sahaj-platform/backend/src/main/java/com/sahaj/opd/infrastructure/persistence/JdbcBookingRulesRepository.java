package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.BookingRulesRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code opd.booking_rules}, one row per clinic (SAH-11). */
@Repository
public class JdbcBookingRulesRepository implements BookingRulesRepository {

    private static final String FIND = """
            select close_before_hours, remind_before_hours, no_show_after_minutes, follow_priority, self_book, overbook
            from opd.booking_rules where tenant_id = :tenantId
            """;
    private static final String UPSERT = """
            insert into opd.booking_rules
                (tenant_id, close_before_hours, remind_before_hours, no_show_after_minutes, follow_priority,
                 self_book, overbook, updated_by)
            values
                (:tenantId, :closeBeforeHours, :remindBeforeHours, :noShowAfterMinutes, :followPriority,
                 :selfBook, :overbook, :actor)
            on conflict (tenant_id) do update set
                close_before_hours = excluded.close_before_hours,
                remind_before_hours = excluded.remind_before_hours,
                no_show_after_minutes = excluded.no_show_after_minutes,
                follow_priority = excluded.follow_priority,
                self_book = excluded.self_book,
                overbook = excluded.overbook,
                updated_by = excluded.updated_by,
                updated_at = now(),
                version = opd.booking_rules.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcBookingRulesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BookingRulesRow> find(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(JdbcBookingRulesRepository::map).optional();
    }

    @Override
    public void upsert(UUID tenantId, BookingRulesRow row, UUID actorUserId) {
        jdbc.sql(UPSERT)
                .param("tenantId", tenantId)
                .param("closeBeforeHours", row.closeBeforeHours())
                .param("remindBeforeHours", row.remindBeforeHours())
                .param("noShowAfterMinutes", row.noShowAfterMinutes())
                .param("followPriority", row.followPriority())
                .param("selfBook", row.selfBook())
                .param("overbook", row.overbook())
                .param("actor", actorUserId)
                .update();
    }

    private static BookingRulesRow map(ResultSet rs, int rowNum) throws SQLException {
        return new BookingRulesRow(rs.getInt("close_before_hours"), rs.getInt("remind_before_hours"),
                rs.getInt("no_show_after_minutes"), rs.getBoolean("follow_priority"), rs.getBoolean("self_book"),
                rs.getBoolean("overbook"));
    }
}
