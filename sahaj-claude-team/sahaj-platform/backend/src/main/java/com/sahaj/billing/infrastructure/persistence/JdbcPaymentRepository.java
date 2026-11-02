package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.PaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code billing.payment} (SAH-10), append-only. Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final String FIND_BY_BILL = """
            select id, mode, amount_paise, reference, recorded_by, recorded_at from billing.payment
            where tenant_id = :tenantId and bill_id = :billId order by recorded_at
            """;
    private static final String INSERT = """
            insert into billing.payment (id, bill_id, mode, amount_paise, reference, recorded_by)
            values (:id, :billId, :mode, :amountPaise, :reference, :actor)
            """;
    private static final String SUM_BY_MODE_SINCE = """
            select coalesce(sum(amount_paise), 0) as total from billing.payment
            where tenant_id = :tenantId and mode = :mode and recorded_at >= :since
            """;
    private static final String EXISTS_SINCE = """
            select exists(
                select 1 from billing.payment
                where tenant_id = :tenantId and bill_id = :billId and recorded_at >= :since
            ) as found
            """;

    private final JdbcClient jdbc;

    public JdbcPaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PaymentRow> findByBill(UUID tenantId, UUID billId) {
        return jdbc.sql(FIND_BY_BILL).param("tenantId", tenantId).param("billId", billId)
                .query(JdbcPaymentRepository::map).list();
    }

    @Override
    public void insert(UUID id, UUID billId, String mode, long amountPaise, String reference, UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("billId", billId)
                .param("mode", mode)
                .param("amountPaise", amountPaise)
                .param("reference", reference)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public long sumByModeSince(UUID tenantId, String mode, Instant since) {
        return jdbc.sql(SUM_BY_MODE_SINCE).param("tenantId", tenantId).param("mode", mode).param("since", since)
                .query(Long.class).single();
    }

    @Override
    public boolean existsSince(UUID tenantId, UUID billId, Instant since) {
        return jdbc.sql(EXISTS_SINCE).param("tenantId", tenantId).param("billId", billId).param("since", since)
                .query(Boolean.class).single();
    }

    private static PaymentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getString("mode"), rs.getLong("amount_paise"),
                rs.getString("reference"), rs.getObject("recorded_by", UUID.class),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
