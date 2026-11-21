package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DiscountRequestRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.sale_discount_request} (append-only; select and insert). */
@Repository("pharmacyJdbcDiscountRequestRepository")
public class JdbcDiscountRequestRepository implements DiscountRequestRepository {

    private static final String COLUMNS = """
            id, unit_id, store_id, approval_request_id, cart_digest, discount_percent, discount_paise, held_bill_id,
            requested_by, created_at
            """;

    private final JdbcClient jdbc;

    public JdbcDiscountRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from pharmacy.sale_discount_request where tenant_id = :t and id = :id")
                .param("t", tenantId).param("id", id).query(JdbcDiscountRequestRepository::map).optional();
    }

    @Override
    public Optional<Row> findByApprovalRequest(UUID tenantId, UUID approvalRequestId) {
        return jdbc.sql("select " + COLUMNS
                + " from pharmacy.sale_discount_request where tenant_id = :t and approval_request_id = :a")
                .param("t", tenantId).param("a", approvalRequestId).query(JdbcDiscountRequestRepository::map).optional();
    }

    @Override
    public List<Row> bySeller(UUID tenantId, UUID storeId, UUID requestedBy, UUID heldBillId) {
        return jdbc.sql("select " + COLUMNS + """
                 from pharmacy.sale_discount_request
                where tenant_id = :t and store_id = :s and requested_by = :by
                  and held_bill_id is not distinct from cast(:held as uuid)
                order by created_at desc
                """).param("t", tenantId).param("s", storeId).param("by", requestedBy).param("held", heldBillId)
                .query(JdbcDiscountRequestRepository::map).list();
    }

    @Override
    public List<Row> byHeldBill(UUID tenantId, UUID storeId, UUID heldBillId) {
        return jdbc.sql("select " + COLUMNS + """
                 from pharmacy.sale_discount_request
                where tenant_id = :t and store_id = :s and held_bill_id = :held
                order by created_at desc
                """).param("t", tenantId).param("s", storeId).param("held", heldBillId)
                .query(JdbcDiscountRequestRepository::map).list();
    }

    @Override
    public void insert(UUID tenantId, NewRow r, UUID actorMembershipId) {
        jdbc.sql("""
                insert into pharmacy.sale_discount_request (id, tenant_id, unit_id, store_id, approval_request_id, cart_digest,
                       discount_percent, discount_paise, held_bill_id, requested_by)
                values (:id, :t, :unit, :store, :approval, :digest, :percent, :paise, :held, :by)
                """).param("id", r.id()).param("t", tenantId).param("unit", r.unitId()).param("store", r.storeId())
                .param("approval", r.approvalRequestId()).param("digest", r.cartDigest())
                .param("percent", r.discountPercent()).param("paise", r.discountPaise()).param("held", r.heldBillId())
                .param("by", r.requestedBy()).update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp at = rs.getTimestamp("created_at");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getObject("approval_request_id", UUID.class),
                rs.getString("cart_digest"), rs.getBigDecimal("discount_percent"), rs.getLong("discount_paise"),
                rs.getObject("held_bill_id", UUID.class), rs.getObject("requested_by", UUID.class),
                at == null ? null : at.toInstant());
    }
}
