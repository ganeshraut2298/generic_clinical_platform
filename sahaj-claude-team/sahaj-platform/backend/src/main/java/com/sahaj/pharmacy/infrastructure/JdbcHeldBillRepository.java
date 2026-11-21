package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.HeldBillRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.held_bill} (SAH-80-5). The cart is a jsonb text; every statement names the tenant. */
@Repository("pharmacyJdbcHeldBillRepository")
public class JdbcHeldBillRepository implements HeldBillRepository {

    private static final String COLUMNS = """
            id, unit_id, store_id, holder_membership_id, cast(cart as text) as cart_text, status, discount_request_id,
            held_net_paise, created_at, version
            """;
    private static final String FIND = "select " + COLUMNS + " from pharmacy.held_bill where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String OPEN = "select " + COLUMNS + """
             from pharmacy.held_bill where tenant_id = :tenantId and store_id = :storeId and status = 'OPEN'
            order by created_at, id limit :limit
            """;
    private static final String COUNT_OPEN = """
            select count(*) from pharmacy.held_bill where tenant_id = :tenantId and store_id = :storeId and status = 'OPEN'
            """;
    private static final String INSERT = """
            insert into pharmacy.held_bill (id, tenant_id, unit_id, store_id, holder_membership_id, cart, discount_request_id,
                   held_net_paise, created_by)
            values (:id, :tenantId, :unitId, :storeId, :holder, cast(:cart as jsonb), :discountRequestId, :net, :holder)
            """;
    private static final String CLOSE = """
            update pharmacy.held_bill set status = :status, version = version + 1, updated_at = now(), updated_by = :by
             where tenant_id = :tenantId and id = :id and version = :version and status = 'OPEN'
            """;

    private final JdbcClient jdbc;

    public JdbcHeldBillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcHeldBillRepository::row).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcHeldBillRepository::row)
                .optional();
    }

    @Override
    public List<Row> open(UUID tenantId, UUID storeId, int limit) {
        return jdbc.sql(OPEN).param("tenantId", tenantId).param("storeId", storeId).param("limit", limit)
                .query(JdbcHeldBillRepository::row).list();
    }

    @Override
    public int countOpen(UUID tenantId, UUID storeId) {
        return jdbc.sql(COUNT_OPEN).param("tenantId", tenantId).param("storeId", storeId).query(Integer.class).single();
    }

    @Override
    public void insert(UUID tenantId, NewHeld h) {
        jdbc.sql(INSERT).param("id", h.id()).param("tenantId", tenantId).param("unitId", h.unitId())
                .param("storeId", h.storeId()).param("holder", h.holder()).param("cart", h.cartJson())
                .param("discountRequestId", h.discountRequestId()).param("net", h.heldNetPaise()).update();
    }

    @Override
    public boolean close(UUID tenantId, UUID id, long version, String status, UUID actorMembershipId) {
        return jdbc.sql(CLOSE).param("tenantId", tenantId).param("id", id).param("version", version).param("status", status)
                .param("by", actorMembershipId).update() == 1;
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getObject("holder_membership_id", UUID.class),
                rs.getString("cart_text"), rs.getString("status"), rs.getObject("discount_request_id", UUID.class),
                rs.getLong("held_net_paise"), rs.getTimestamp("created_at").toInstant(), rs.getLong("version"));
    }
}
