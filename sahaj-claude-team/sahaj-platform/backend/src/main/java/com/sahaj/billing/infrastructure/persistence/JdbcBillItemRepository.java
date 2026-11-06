package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.BillItemRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code billing.bill_item} (SAH-10). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcBillItemRepository implements BillItemRepository {

    private static final String SELECT = """
            select id, service_id, name, amount_paise, qty from billing.bill_item
            """;
    private static final String FIND_BY_BILL = SELECT
            + " where tenant_id = :tenantId and bill_id = :billId order by created_at";
    private static final String FIND_BY_ID = SELECT
            + " where tenant_id = :tenantId and bill_id = :billId and id = :id";
    private static final String FIND_BY_BILLS = """
            select bill_id, name, amount_paise, qty from billing.bill_item
            where tenant_id = :tenantId and bill_id in (:billIds)
            """;
    private static final String INSERT = """
            insert into billing.bill_item (id, bill_id, service_id, name, amount_paise, qty, created_by)
            values (:id, :billId, :serviceId, :name, :amountPaise, :qty, :actor)
            """;
    private static final String DELETE = "delete from billing.bill_item where tenant_id = :tenantId and id = :id";

    private final JdbcClient jdbc;

    public JdbcBillItemRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BillItemRow> findByBill(UUID tenantId, UUID billId) {
        return jdbc.sql(FIND_BY_BILL).param("tenantId", tenantId).param("billId", billId)
                .query(JdbcBillItemRepository::map).list();
    }

    @Override
    public List<BillItemOfBill> findByBills(UUID tenantId, java.util.Collection<UUID> billIds) {
        if (billIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(FIND_BY_BILLS).param("tenantId", tenantId).param("billIds", billIds)
                .query((rs, rowNum) -> new BillItemOfBill(rs.getObject("bill_id", UUID.class), rs.getString("name"),
                        rs.getLong("amount_paise"), rs.getInt("qty")))
                .list();
    }

    @Override
    public void insert(UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty,
                        UUID actorUserId) {
        jdbc.sql(INSERT)
                .param("id", id)
                .param("billId", billId)
                .param("serviceId", serviceId)
                .param("name", name)
                .param("amountPaise", amountPaise)
                .param("qty", qty)
                .param("actor", actorUserId)
                .update();
    }

    @Override
    public Optional<BillItemRow> findById(UUID tenantId, UUID billId, UUID itemId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("billId", billId).param("id", itemId)
                .query(JdbcBillItemRepository::map).optional();
    }

    @Override
    public void delete(UUID tenantId, UUID id) {
        jdbc.sql(DELETE).param("tenantId", tenantId).param("id", id).update();
    }

    private static BillItemRow map(ResultSet rs, int rowNum) throws SQLException {
        return new BillItemRow(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("name"), rs.getLong("amount_paise"), rs.getInt("qty"));
    }
}
