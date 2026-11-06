package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.PaymentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
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
    private static final String NET_SINCE = """
            select coalesce(sum(amount_paise), 0) as total from billing.payment
            where tenant_id = :tenantId and recorded_at >= :since
            """;
    private static final String FIND_BETWEEN = """
            select b.visit_id, p.mode, p.amount_paise, p.reference, p.recorded_by, p.recorded_at
            from billing.payment p
            join billing.bill b on b.id = p.bill_id and b.tenant_id = p.tenant_id
            where p.tenant_id = :tenantId and p.recorded_at >= :from and p.recorded_at < :to
            order by p.recorded_at
            """;
    private static final String TOTALS_BETWEEN = """
            select coalesce(sum(amount_paise), 0) as net, count(*) as cnt from billing.payment
            where tenant_id = :tenantId and recorded_at >= :from and recorded_at < :to
            """;
    private static final String PAID_BY_BILLS = """
            select bill_id, sum(amount_paise) as paid from billing.payment
            where tenant_id = :tenantId and bill_id in (:billIds)
            group by bill_id
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
    public long netSince(UUID tenantId, Instant since) {
        return jdbc.sql(NET_SINCE).param("tenantId", tenantId).param("since", since)
                .query(Long.class).optional().orElse(0L);
    }

    @Override
    public List<PaymentOfVisit> findBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(FIND_BETWEEN).param("tenantId", tenantId).param("from", from).param("to", to)
                .query((rs, rowNum) -> new PaymentOfVisit(rs.getObject("visit_id", UUID.class), rs.getString("mode"),
                        rs.getLong("amount_paise"), rs.getString("reference"),
                        rs.getObject("recorded_by", UUID.class), rs.getTimestamp("recorded_at").toInstant()))
                .list();
    }

    @Override
    public PaymentTotals totalsBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(TOTALS_BETWEEN).param("tenantId", tenantId).param("from", from).param("to", to)
                .query((rs, rowNum) -> new PaymentTotals(rs.getLong("net"), rs.getLong("cnt")))
                .optional().orElse(new PaymentTotals(0, 0));
    }

    @Override
    public List<BillPaid> paidByBills(UUID tenantId, Collection<UUID> billIds) {
        if (billIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(PAID_BY_BILLS).param("tenantId", tenantId).param("billIds", billIds)
                .query((rs, rowNum) -> new BillPaid(rs.getObject("bill_id", UUID.class), rs.getLong("paid")))
                .list();
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
