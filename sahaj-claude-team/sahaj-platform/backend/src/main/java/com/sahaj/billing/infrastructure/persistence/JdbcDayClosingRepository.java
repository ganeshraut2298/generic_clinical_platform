package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.DayClosingRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code billing.day_closing} (SAH-10 AC9). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcDayClosingRepository implements DayClosingRepository {

    private static final String FIND_BY_DATE = """
            select id, business_date, counted_cash_paise, expected_cash_paise, diff_paise, note, closed_by,
                   closed_at
            from billing.day_closing
            where tenant_id = :tenantId and business_date = :businessDate
            """;
    private static final String INSERT = """
            insert into billing.day_closing (id, business_date, counted_cash_paise, expected_cash_paise, diff_paise,
                                              note, closed_by)
            values (:id, :businessDate, :countedCashPaise, :expectedCashPaise, :diffPaise, :note, :actor)
            """;

    private final JdbcClient jdbc;

    public JdbcDayClosingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DayClosingRow> findByDate(UUID tenantId, LocalDate businessDate) {
        return jdbc.sql(FIND_BY_DATE).param("tenantId", tenantId).param("businessDate", businessDate)
                .query(JdbcDayClosingRepository::map).optional();
    }

    @Override
    public void insert(UUID id, LocalDate businessDate, long countedCashPaise, long expectedCashPaise,
                        long diffPaise, String note, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("businessDate", businessDate)
                .param("countedCashPaise", countedCashPaise)
                .param("expectedCashPaise", expectedCashPaise)
                .param("diffPaise", diffPaise)
                .param("note", note)
                .param("actor", actorUserId)
                .update();
    }

    private static DayClosingRow map(ResultSet rs, int rowNum) throws SQLException {
        return new DayClosingRow(rs.getObject("id", UUID.class), rs.getObject("business_date", LocalDate.class),
                rs.getLong("counted_cash_paise"), rs.getLong("expected_cash_paise"), rs.getLong("diff_paise"),
                rs.getString("note"), rs.getObject("closed_by", UUID.class), rs.getTimestamp("closed_at").toInstant());
    }
}
