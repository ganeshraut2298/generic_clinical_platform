package com.sahaj.hra.infrastructure.persistence;

import com.sahaj.hra.application.RosterRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code hra.roster_shift} and {@code hra.roster_state} (SAH-20). Row level security scopes every
 * statement to the bound tenant; the tenant id is also in every where clause and insert.
 */
@Repository
public class JdbcRosterRepository implements RosterRepository {

    private static final String FIND_BETWEEN = """
            select membership_id, work_date, shift
            from hra.roster_shift
            where tenant_id = :tenantId and work_date between :fromDate and :toDate
            """;
    private static final String FIND_SHIFT = """
            select shift
            from hra.roster_shift
            where tenant_id = :tenantId and membership_id = :membershipId and work_date = :workDate
            """;
    private static final String UPSERT_SHIFT = """
            insert into hra.roster_shift (tenant_id, membership_id, work_date, shift, updated_at, updated_by)
            values (:tenantId, :membershipId, :workDate, :shift, :now, :actor)
            on conflict (tenant_id, membership_id, work_date) do update set
                shift = excluded.shift,
                updated_at = excluded.updated_at,
                updated_by = excluded.updated_by
            """;
    private static final String FIND_PUBLISHED_AT = """
            select published_at
            from hra.roster_state
            where tenant_id = :tenantId and published_at is not null
            """;
    private static final String PUBLISH = """
            insert into hra.roster_state (tenant_id, published_at, published_by)
            values (:tenantId, :now, :actor)
            on conflict (tenant_id) do update set
                published_at = excluded.published_at,
                published_by = excluded.published_by
            where hra.roster_state.published_at is null
            """;

    private final JdbcClient jdbc;

    public JdbcRosterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ShiftRow> findBetween(UUID tenantId, LocalDate from, LocalDate to) {
        return jdbc.sql(FIND_BETWEEN)
                .param("tenantId", tenantId)
                .param("fromDate", from)
                .param("toDate", to)
                .query((rs, rowNum) -> new ShiftRow(rs.getObject("membership_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class), rs.getString("shift")))
                .list();
    }

    @Override
    public Optional<String> findShift(UUID tenantId, UUID membershipId, LocalDate date) {
        return jdbc.sql(FIND_SHIFT)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("workDate", date)
                .query((rs, rowNum) -> rs.getString("shift"))
                .optional();
    }

    @Override
    public void upsertShift(UUID tenantId, UUID membershipId, LocalDate date, String shift, UUID actorUserId,
                            Instant now) {
        jdbc.sql(UPSERT_SHIFT)
                .param("tenantId", tenantId)
                .param("membershipId", membershipId)
                .param("workDate", date)
                .param("shift", shift)
                .param("now", now)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<Instant> findPublishedAt(UUID tenantId) {
        return jdbc.sql(FIND_PUBLISHED_AT)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> {
                    Timestamp publishedAt = rs.getTimestamp("published_at");
                    return publishedAt.toInstant();
                })
                .optional();
    }

    @Override
    public void publish(UUID tenantId, UUID actorUserId, Instant now) {
        jdbc.sql(PUBLISH)
                .param("tenantId", tenantId)
                .param("now", now)
                .param("actor", actorUserId)
                .update();
    }
}
