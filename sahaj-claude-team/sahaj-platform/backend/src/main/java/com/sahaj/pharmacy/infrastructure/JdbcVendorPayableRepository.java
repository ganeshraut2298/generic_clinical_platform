package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.VendorPayableRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.vendor_payable} and {@code pharmacy.vendor_payable_entry}. Amounts are never logged here. */
@Repository
public class JdbcVendorPayableRepository implements VendorPayableRepository {

    private static final String COLUMNS = """
            p.id, p.vendor_id, p.goods_receipt_id, p.invoice_no, p.invoice_date, p.amount_paise, p.credited_paise, p.due_date,
            p.status, p.version
            """;
    private static final String BY_RECEIPT = "select " + COLUMNS + """
              from pharmacy.vendor_payable p
             where p.tenant_id = :tenantId and p.goods_receipt_id = :receiptId
            """;
    private static final String LOCK_OPEN = "select " + COLUMNS + """
              from pharmacy.vendor_payable p
             where p.tenant_id = :tenantId and p.vendor_id = :vendorId and p.status = 'OPEN' and p.credited_paise < p.amount_paise
             order by p.due_date, p.id
               for update of p
            """;
    private static final String INSERT = """
            insert into pharmacy.vendor_payable (id, tenant_id, unit_id, vendor_id, goods_receipt_id, invoice_no, invoice_date,
                   amount_paise, due_date, created_by)
            values (:id, :tenantId, :unitId, :vendorId, :receiptId, :invoiceNo, :invoiceDate, :amount, :dueDate, :actor)
            """;
    private static final String INSERT_ENTRY = """
            insert into pharmacy.vendor_payable_entry (tenant_id, unit_id, payable_id, entry_type, amount_paise,
                   open_after_paise, debit_note_id, created_by)
            values (:tenantId, :unitId, :payableId, :type, :amount, :openAfter, cast(:debitNoteId as uuid), :actor)
            """;
    private static final String APPLY = """
            update pharmacy.vendor_payable
               set credited_paise = credited_paise + :applied,
                   status = case when credited_paise + :applied >= amount_paise then 'SETTLED_BY_CREDIT' else 'OPEN' end,
                   updated_at = now(), updated_by = :actor, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'OPEN'
            """;

    private final JdbcClient jdbc;

    public JdbcVendorPayableRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findByReceipt(UUID tenantId, UUID goodsReceiptId) {
        return jdbc.sql(BY_RECEIPT).param("tenantId", tenantId).param("receiptId", goodsReceiptId)
                .query(JdbcVendorPayableRepository::map).optional();
    }

    @Override
    public List<Row> lockOpenOfVendor(UUID tenantId, UUID vendorId) {
        return jdbc.sql(LOCK_OPEN).param("tenantId", tenantId).param("vendorId", vendorId)
                .query(JdbcVendorPayableRepository::map).list();
    }

    @Override
    public void insertWithInvoiceEntry(UUID tenantId, UUID unitId, UUID id, UUID vendorId, UUID goodsReceiptId, String invoiceNo,
                                       LocalDate invoiceDate, long amountPaise, LocalDate dueDate, UUID actor) {
        jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("id", id).param("vendorId", vendorId)
                .param("receiptId", goodsReceiptId).param("invoiceNo", invoiceNo)
                .param("invoiceDate", java.sql.Date.valueOf(invoiceDate)).param("amount", amountPaise)
                .param("dueDate", java.sql.Date.valueOf(dueDate)).param("actor", actor).update();
        entry(tenantId, unitId, id, "INVOICE", amountPaise, amountPaise, null, actor);
    }

    @Override
    public void applyCredit(UUID tenantId, UUID unitId, Row payable, long applied, UUID debitNoteId, UUID actor) {
        if (jdbc.sql(APPLY).param("tenantId", tenantId).param("id", payable.id()).param("applied", applied).param("actor", actor)
                .update() != 1) {
            throw new IllegalStateException("A vendor payable changed under its lock");
        }
        entry(tenantId, unitId, payable.id(), "CREDIT_NOTE", -applied, payable.openPaise() - applied, debitNoteId, actor);
    }

    private void entry(UUID tenantId, UUID unitId, UUID payableId, String type, long amount, long openAfter, UUID debitNoteId,
                       UUID actor) {
        jdbc.sql(INSERT_ENTRY).param("tenantId", tenantId).param("unitId", unitId).param("payableId", payableId)
                .param("type", type).param("amount", amount).param("openAfter", openAfter)
                .param("debitNoteId", debitNoteId, java.sql.Types.OTHER).param("actor", actor).update();
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class),
                rs.getObject("goods_receipt_id", UUID.class), rs.getString("invoice_no"), rs.getDate("invoice_date").toLocalDate(),
                rs.getLong("amount_paise"), rs.getLong("credited_paise"), rs.getDate("due_date").toLocalDate(),
                rs.getString("status"), rs.getLong("version"));
    }
}
