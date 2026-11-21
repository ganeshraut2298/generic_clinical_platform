package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.ConsumptionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.consumption} and {@code consumption_line} (SAH-79-16). Every statement names the tenant as well as relying on row level security. */
@Repository
public class JdbcConsumptionRepository implements ConsumptionRepository {

    private static final String COLUMNS = """
            id, unit_id, store_id, consumption_type, purpose_key, note, admission_id, ordered_by_membership_id, ordered_by_name,
            charge_status, stock_audit_line_id, second_signer_membership_id, recorded_at, recorded_by
            """;
    private static final String FIND = "select " + COLUMNS
            + " from pharmacy.consumption where tenant_id = :tenantId and store_id = :storeId and id = :id";
    private static final String INSERT = """
            insert into pharmacy.consumption (id, tenant_id, unit_id, store_id, consumption_type, purpose_key, note, admission_id,
                   ordered_by_membership_id, ordered_by_name, charge_status, stock_audit_line_id, second_signer_membership_id,
                   recorded_by)
            values (:id, :tenantId, :unitId, :storeId, :type, cast(:purpose as text), cast(:note as text),
                   cast(:admission as uuid), cast(:orderedBy as uuid), cast(:orderedByName as text), :charge,
                   cast(:auditLine as uuid), cast(:signer as uuid), :by)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.consumption_line (id, tenant_id, unit_id, consumption_id, product_id, batch_id, quantity, created_by)
            values (:id, :tenantId, :unitId, :consumptionId, :productId, :batchId, :quantity, :by)
            """;
    private static final String LINES = """
            select id, consumption_id, product_id, batch_id, quantity from pharmacy.consumption_line
             where tenant_id = :tenantId and consumption_id in (:ids) order by created_at, id
            """;
    private static final String PAGE_WHERE = """
             from pharmacy.consumption where tenant_id = :tenantId and store_id = :storeId
               and recorded_at >= :from and recorded_at < :to
            """;
    private static final String IP_NUMBERS = """
            select doc_id, max(doc_no) as ip_number from pharmacy.stock_ledger
             where tenant_id = :tenantId and doc_type = 'CONSUMPTION' and doc_id in (:ids) and admission_id is not null
             group by doc_id
            """;
    private static final String LINKED_WARD = """
            select ipd_ward_id from pharmacy.store where tenant_id = :tenantId and id = :storeId
            """;

    private final JdbcClient jdbc;

    public JdbcConsumptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcConsumptionRepository::map).optional();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID consumptionId) {
        return linesOf(tenantId, List.of(consumptionId)).getOrDefault(consumptionId, List.of());
    }

    @Override
    public Map<UUID, List<Line>> linesOf(UUID tenantId, Collection<UUID> consumptionIds) {
        Map<UUID, List<Line>> byEntry = new HashMap<>();
        if (consumptionIds.isEmpty()) {
            return byEntry;
        }
        jdbc.sql(LINES).param("tenantId", tenantId).param("ids", consumptionIds)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getObject("consumption_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("quantity")))
                .list().forEach(l -> byEntry.computeIfAbsent(l.consumptionId(), k -> new java.util.ArrayList<>()).add(l));
        return byEntry;
    }

    @Override
    public void insert(UUID tenantId, Row r) {
        jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("unitId", r.unitId()).param("storeId", r.storeId())
                .param("type", r.type()).param("purpose", r.purposeKey()).param("note", r.note())
                .param("admission", r.admissionId()).param("orderedBy", r.orderedByMembershipId())
                .param("orderedByName", r.orderedByName()).param("charge", r.chargeStatus())
                .param("auditLine", r.stockAuditLineId()).param("signer", r.secondSignerMembershipId())
                .param("by", r.recordedBy()).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, Line l, UUID actor) {
        jdbc.sql(INSERT_LINE).param("id", l.id()).param("tenantId", tenantId).param("unitId", unitId)
                .param("consumptionId", l.consumptionId()).param("productId", l.productId()).param("batchId", l.batchId())
                .param("quantity", l.quantity()).param("by", actor).update();
    }

    @Override
    public Slice page(UUID tenantId, UUID storeId, Instant from, Instant to, int limit, int offset) {
        long total = jdbc.sql("select count(*)" + PAGE_WHERE).param("tenantId", tenantId).param("storeId", storeId)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Long.class).single();
        List<Row> items = jdbc.sql("select " + COLUMNS + PAGE_WHERE + " order by recorded_at desc, id limit :limit offset :offset")
                .param("tenantId", tenantId).param("storeId", storeId).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to)).param("limit", limit).param("offset", offset)
                .query(JdbcConsumptionRepository::map).list();
        return new Slice(items, total);
    }

    @Override
    public Map<UUID, String> ipNumbers(UUID tenantId, Collection<UUID> consumptionIds) {
        Map<UUID, String> numbers = new HashMap<>();
        if (consumptionIds.isEmpty()) {
            return numbers;
        }
        jdbc.sql(IP_NUMBERS).param("tenantId", tenantId).param("ids", consumptionIds).query((rs, n) -> {
            numbers.put(rs.getObject("doc_id", UUID.class), rs.getString("ip_number"));
            return 0;
        }).list();
        return numbers;
    }

    @Override
    public Optional<UUID> linkedWard(UUID tenantId, UUID storeId) {
        return jdbc.sql(LINKED_WARD).param("tenantId", tenantId).param("storeId", storeId)
                .query((rs, n) -> rs.getObject("ipd_ward_id", UUID.class)).optional().filter(w -> w != null);
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getString("consumption_type"), rs.getString("purpose_key"), rs.getString("note"),
                rs.getObject("admission_id", UUID.class), rs.getObject("ordered_by_membership_id", UUID.class),
                rs.getString("ordered_by_name"), rs.getString("charge_status"), rs.getObject("stock_audit_line_id", UUID.class),
                rs.getObject("second_signer_membership_id", UUID.class), rs.getTimestamp("recorded_at").toInstant(),
                rs.getObject("recorded_by", UUID.class));
    }
}
