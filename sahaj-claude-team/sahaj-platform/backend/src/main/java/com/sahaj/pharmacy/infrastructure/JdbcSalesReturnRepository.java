package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.SalesReturnRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.sales_return} and {@code sales_return_line}. Every statement names the tenant. The status moves only as the
 * table guard allows; an update that finds another version answers false and the service answers 409 changed.
 */
@Repository("pharmacyJdbcSalesReturnRepository")
public class JdbcSalesReturnRepository implements SalesReturnRepository {

    private static final String COLUMNS = """
            id, unit_id, store_id, bill_id, status, reason_key, note, started_by, started_at, accepted_by, accepted_at,
            completed_by, completed_at, approval_request_id, refund_total_paise, refunds::text as refunds_text, credit_note_no,
            version
            """;
    private static final String LINE_COLUMNS = """
            id, bill_line_id, product_id, batch_id, qty, condition_key, decision, destination, refund_paise, taxable_paise,
            tax_paise, second_signer_membership_id
            """;

    private final JdbcClient jdbc;

    public JdbcSalesReturnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from pharmacy.sales_return where tenant_id = :t and id = :id")
                .param("t", tenantId).param("id", id).query(JdbcSalesReturnRepository::row).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from pharmacy.sales_return where tenant_id = :t and id = :id for update")
                .param("t", tenantId).param("id", id).query(JdbcSalesReturnRepository::row).optional();
    }

    @Override
    public List<LineRow> lines(UUID tenantId, UUID returnId) {
        return jdbc.sql("select " + LINE_COLUMNS + " from pharmacy.sales_return_line where tenant_id = :t"
                + " and sales_return_id = :r order by created_at, id").param("t", tenantId).param("r", returnId)
                .query((rs, n) -> new LineRow(rs.getObject("id", UUID.class), rs.getObject("bill_line_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("qty"),
                        rs.getString("condition_key"), rs.getString("decision"), rs.getString("destination"),
                        rs.getLong("refund_paise"), rs.getLong("taxable_paise"), rs.getLong("tax_paise"),
                        rs.getObject("second_signer_membership_id", UUID.class))).list();
    }

    @Override
    public Slice page(UUID tenantId, UUID storeId, String status, UUID startedBy, int limit, int offset) {
        String where = " where tenant_id = :t and store_id = :s and (cast(:status as text) is null or status = :status)"
                + " and (cast(:by as uuid) is null or started_by = cast(:by as uuid))";
        long total = jdbc.sql("select count(*) from pharmacy.sales_return" + where).param("t", tenantId).param("s", storeId)
                .param("status", status).param("by", startedBy).query(Long.class).single();
        List<Row> items = jdbc.sql("select " + COLUMNS + " from pharmacy.sales_return" + where
                + " order by started_at desc, id limit :limit offset :offset").param("t", tenantId).param("s", storeId)
                .param("status", status).param("by", startedBy).param("limit", limit).param("offset", offset)
                .query(JdbcSalesReturnRepository::row).list();
        return new Slice(items, total);
    }

    @Override
    public void insert(UUID tenantId, Row r, UUID actor) {
        jdbc.sql("""
                insert into pharmacy.sales_return (id, tenant_id, unit_id, store_id, bill_id, status, reason_key, note, started_by,
                       accepted_by, accepted_at, completed_by, completed_at, approval_request_id, refund_total_paise, refunds,
                       credit_note_no)
                values (:id, :t, :unit, :store, :bill, :status, :reason, :note, :by, :acceptedBy, :acceptedAt, :completedBy,
                       :completedAt, :approval, :total, cast(:refunds as jsonb), :creditNote)
                """).param("id", r.id()).param("t", tenantId).param("unit", r.unitId()).param("store", r.storeId())
                .param("bill", r.billId()).param("status", r.status()).param("reason", r.reasonKey()).param("note", r.note())
                .param("by", r.startedBy()).param("acceptedBy", r.acceptedBy()).param("acceptedAt", ts(r.acceptedAt()))
                .param("completedBy", r.completedBy()).param("completedAt", ts(r.completedAt()))
                .param("approval", r.approvalRequestId()).param("total", r.refundTotalPaise())
                .param("refunds", r.refundsJson()).param("creditNote", r.creditNoteNo()).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID returnId, LineRow l, UUID actor) {
        jdbc.sql("""
                insert into pharmacy.sales_return_line (id, tenant_id, unit_id, sales_return_id, bill_line_id, product_id, batch_id,
                       qty, condition_key, decision, destination, refund_paise, taxable_paise, tax_paise,
                       second_signer_membership_id, created_by)
                values (:id, :t, :unit, :ret, :billLine, :product, :batch, :qty, :condition, :decision, :destination, :refund,
                       :taxable, :tax, :signer, :actor)
                """).param("id", l.id()).param("t", tenantId).param("unit", unitId).param("ret", returnId)
                .param("billLine", l.billLineId()).param("product", l.productId()).param("batch", l.batchId())
                .param("qty", l.qty()).param("condition", l.conditionKey()).param("decision", l.decision())
                .param("destination", l.destination()).param("refund", l.refundPaise()).param("taxable", l.taxablePaise())
                .param("tax", l.taxPaise()).param("signer", l.secondSigner()).param("actor", actor).update();
    }

    @Override
    public Map<UUID, Integer> openQtyByBillLine(UUID tenantId, UUID billId) {
        Map<UUID, Integer> out = new HashMap<>();
        jdbc.sql("""
                select l.bill_line_id, sum(l.qty)::int as qty
                  from pharmacy.sales_return_line l
                  join pharmacy.sales_return r on r.tenant_id = l.tenant_id and r.id = l.sales_return_id
                 where l.tenant_id = :t and r.bill_id = :b and r.status in ('WAITING_PHARMACIST', 'WAITING_APPROVAL', 'APPROVED')
                   and l.destination <> 'REFUSED'
                 group by l.bill_line_id
                """).param("t", tenantId).param("b", billId).query((rs, n) -> {
                    out.put(rs.getObject("bill_line_id", UUID.class), rs.getInt("qty"));
                    return null;
                }).list();
        return out;
    }

    @Override
    public boolean markAccepted(UUID tenantId, UUID id, long version, String newStatus, UUID acceptedBy, UUID approvalRequestId,
                                long refundTotalPaise, String refundsJson, UUID actor) {
        return jdbc.sql("""
                update pharmacy.sales_return set status = :status, accepted_by = :by, accepted_at = now(),
                       approval_request_id = :approval, refund_total_paise = :total, refunds = cast(:refunds as jsonb),
                       updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :t and id = :id and version = :version
                """).param("status", newStatus).param("by", acceptedBy).param("approval", approvalRequestId)
                .param("total", refundTotalPaise).param("refunds", refundsJson).param("actor", actor).param("t", tenantId)
                .param("id", id).param("version", version).update() == 1;
    }

    @Override
    public boolean markDecided(UUID tenantId, UUID id, long version, String newStatus, UUID actor) {
        return jdbc.sql("""
                update pharmacy.sales_return set status = :status, updated_at = now(), updated_by = :actor, version = version + 1
                 where tenant_id = :t and id = :id and version = :version
                """).param("status", newStatus).param("actor", actor).param("t", tenantId).param("id", id)
                .param("version", version).update() == 1;
    }

    @Override
    public boolean markCompleted(UUID tenantId, UUID id, long version, UUID completedBy, String creditNoteNo,
                                 long refundTotalPaise, String refundsJson, UUID acceptedBy, Instant acceptedAt) {
        return jdbc.sql("""
                update pharmacy.sales_return set status = 'COMPLETED', completed_by = :by, completed_at = now(),
                       credit_note_no = :no, refund_total_paise = :total, refunds = cast(:refunds as jsonb),
                       accepted_by = coalesce(accepted_by, :acceptedBy), accepted_at = coalesce(accepted_at, :acceptedAt),
                       updated_at = now(), updated_by = :by, version = version + 1
                 where tenant_id = :t and id = :id and version = :version
                """).param("by", completedBy).param("no", creditNoteNo).param("total", refundTotalPaise)
                .param("refunds", refundsJson).param("acceptedBy", acceptedBy).param("acceptedAt", ts(acceptedAt))
                .param("t", tenantId).param("id", id).param("version", version).update() == 1;
    }

    @Override
    public void updateLine(UUID tenantId, UUID lineId, String decision, String destination, long refundPaise, long taxablePaise,
                           long taxPaise, UUID secondSigner) {
        jdbc.sql("""
                update pharmacy.sales_return_line set decision = :decision, destination = :destination, refund_paise = :refund,
                       taxable_paise = :taxable, tax_paise = :tax, second_signer_membership_id = :signer
                 where tenant_id = :t and id = :id
                """).param("decision", decision).param("destination", destination).param("refund", refundPaise)
                .param("taxable", taxablePaise).param("tax", taxPaise).param("signer", secondSigner).param("t", tenantId)
                .param("id", lineId).update();
    }

    @Override
    public Optional<Instant> creditNoteIssuedAt(UUID tenantId, String creditNoteNo) {
        return jdbc.sql("select issued_at from pharmacy.credit_note where tenant_id = :t and credit_note_no = :no")
                .param("t", tenantId).param("no", creditNoteNo).query((rs, n) -> rs.getTimestamp("issued_at").toInstant())
                .optional();
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("bill_id", UUID.class), rs.getString("status"), rs.getString("reason_key"), rs.getString("note"),
                rs.getObject("started_by", UUID.class), rs.getTimestamp("started_at").toInstant(),
                rs.getObject("accepted_by", UUID.class), acceptedAt == null ? null : acceptedAt.toInstant(),
                rs.getObject("completed_by", UUID.class), completedAt == null ? null : completedAt.toInstant(),
                rs.getObject("approval_request_id", UUID.class), rs.getLong("refund_total_paise"),
                rs.getString("refunds_text"), rs.getString("credit_note_no"), rs.getLong("version"));
    }
}
