package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.BillRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code billing.bill} (SAH-10). Row level security scopes every statement to the bound tenant. */
@Repository
public class JdbcBillRepository implements BillRepository {

    private static final String SELECT = """
            select id, visit_id, discount_percent, discount_reason, version from billing.bill
            """;
    private static final String FIND_BY_VISIT = SELECT + " where tenant_id = :tenantId and visit_id = :visitId";
    // Lets two concurrent first writes on the same visit race safely: whichever insert wins, the
    // other falls through to the select below and finds the same row (uq_bill_tenant_visit).
    private static final String INSERT_IF_ABSENT = """
            insert into billing.bill (id, visit_id, created_by)
            values (:id, :visitId, :actor)
            on conflict (tenant_id, visit_id) do nothing
            """;
    private static final String UPDATE_DISCOUNT = """
            update billing.bill
            set discount_percent = :percent, discount_reason = :reason, updated_by = :actor, updated_at = now(),
                version = version + 1
            where tenant_id = :tenantId and id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcBillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BillRow> findByVisit(UUID tenantId, UUID visitId) {
        return jdbc.sql(FIND_BY_VISIT).param("tenantId", tenantId).param("visitId", visitId)
                .query(JdbcBillRepository::map).optional();
    }

    @Override
    public UUID getOrCreate(UUID tenantId, UUID visitId, UUID actorUserId) {
        return findByVisit(tenantId, visitId).map(BillRow::id).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            jdbc.sql(INSERT_IF_ABSENT).param("id", id).param("visitId", visitId).param("actor", actorUserId).update();
            return findByVisit(tenantId, visitId).map(BillRow::id).orElseThrow(
                    () -> new IllegalStateException("Bill row missing right after insert"));
        });
    }

    @Override
    public void updateDiscount(UUID tenantId, UUID id, int percent, String reason, UUID actorUserId) {
        jdbc.sql(UPDATE_DISCOUNT).param("tenantId", tenantId).param("id", id).param("percent", percent)
                .param("reason", reason).param("actor", actorUserId).update();
    }

    private static BillRow map(ResultSet rs, int rowNum) throws SQLException {
        return new BillRow(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                rs.getInt("discount_percent"), rs.getString("discount_reason"), rs.getLong("version"));
    }
}
