package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.SaleBillRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC over {@code pharmacy.bill}, {@code bill_line} and {@code bill_payment} (SAH-80-3, 80-5). Every statement names the tenant as
 * well as relying on row level security. The number is taken by the service before the insert; nothing here issues one.
 */
@Repository("pharmacyJdbcSaleBillRepository")
public class JdbcSaleBillRepository implements SaleBillRepository {

    private static final String BILL_COLUMNS = """
            id, unit_id, store_id, bill_no, series_kind, fiscal_year, status, buyer_kind, patient_id, customer_id, walk_in_name,
            walk_in_phone, link_pending, allergy_not_checked, rx_record_id, rx_source, prescriber_name, prescriber_reg_no,
            prescription_date, held_bill_id, discount_percent, discount_request_id, gross_paise, discount_paise, taxable_paise,
            cgst_paise, sgst_paise, round_off_paise, net_paise, udhar_paise, saved_at, saved_by, pharmacist_membership_id,
            cancelled_at, cancelled_by, cancel_reason_key, cancel_note, back_on_shelf, refund_due_paise, refund_credit_note_no,
            version
            """;
    private static final String FIND = "select " + BILL_COLUMNS + " from pharmacy.bill where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND + " for update";
    private static final String LINES = """
            select id, line_no, product_id, batch_id, qty, returned_qty, mrp, gst_rate, hsn, gross_paise, discount_paise,
                   net_paise, taxable_paise, tax_paise, prescribed_product_id, substitution_reason_key, substitution_note,
                   patient_agreed, allergy_override_key, allergy_override_note, h1_override_key, h1_override_note,
                   override_decided_by, actor_membership_id, second_signer_membership_id, rx_line_key
              from pharmacy.bill_line where tenant_id = :tenantId and bill_id = :billId order by line_no
            """;
    private static final String PAYMENTS = """
            select mode, amount_paise from pharmacy.bill_payment where tenant_id = :tenantId and bill_id = :billId
             order by paid_at, mode
            """;
    private static final String HAS_RETURNS = """
            select exists (select 1 from pharmacy.bill_line where tenant_id = :tenantId and bill_id = :billId and returned_qty > 0)
                or exists (select 1 from pharmacy.sales_return where tenant_id = :tenantId and bill_id = :billId
                           and status in ('WAITING_PHARMACIST', 'WAITING_APPROVAL', 'APPROVED', 'COMPLETED'))
            """;
    private static final String INSERT_BILL = """
            insert into pharmacy.bill (id, tenant_id, unit_id, store_id, bill_no, series_kind, fiscal_year, status, buyer_kind,
                   patient_id, customer_id, walk_in_name, walk_in_phone, link_pending, allergy_not_checked, rx_record_id,
                   rx_source, prescriber_name, prescriber_reg_no, prescription_date, held_bill_id, discount_percent,
                   discount_request_id, gross_paise, discount_paise, taxable_paise, cgst_paise, sgst_paise, round_off_paise,
                   net_paise, udhar_paise, saved_at, saved_by, pharmacist_membership_id)
            values (:id, :tenantId, :unitId, :storeId, :billNo, :seriesKind, :fiscalYear, 'SAVED', :buyerKind, :patientId,
                   :customerId, :walkInName, :walkInPhone, :linkPending, :allergyNotChecked, :rxRecordId, :rxSource,
                   :prescriberName, :prescriberRegNo, :prescriptionDate, :heldBillId, :discountPercent, :discountRequestId,
                   :gross, :discount, :taxable, :cgst, :sgst, :roundOff, :net, :udhar, :savedAt, :savedBy, :pharmacist)
            """;
    private static final String INSERT_LINE = """
            insert into pharmacy.bill_line (id, tenant_id, unit_id, bill_id, line_no, product_id, batch_id, qty, mrp, gst_rate,
                   hsn, gross_paise, discount_paise, net_paise, taxable_paise, tax_paise, prescribed_product_id,
                   substitution_reason_key, substitution_note, patient_agreed, allergy_override_key, allergy_override_note,
                   h1_override_key, h1_override_note, override_decided_by, actor_membership_id, second_signer_membership_id,
                   rx_line_key, created_by)
            values (:id, :tenantId, :unitId, :billId, :lineNo, :productId, :batchId, :qty, :mrp, :gstRate, :hsn, :gross,
                   :discount, :net, :taxable, :tax, :prescribedProductId, :substitutionReasonKey, :substitutionNote,
                   :patientAgreed, :allergyOverrideKey, :allergyOverrideNote, :h1OverrideKey, :h1OverrideNote, :decidedBy,
                   :actor, :secondSigner, :rxLineKey, :actor)
            """;
    private static final String INSERT_PAYMENT = """
            insert into pharmacy.bill_payment (tenant_id, unit_id, store_id, bill_id, mode, amount_paise, actor_membership_id)
            values (:tenantId, :unitId, :storeId, :billId, :mode, :amount, :actor)
            """;
    private static final String LIST_FROM = """
              from pharmacy.bill b
              left join pharmacy.customer c on c.tenant_id = b.tenant_id and c.id = b.customer_id
             where b.tenant_id = :tenantId and b.store_id = :storeId
               and (:number = '' or lower(b.bill_no) = :number)
               and b.saved_at >= :from and b.saved_at < :to
               and (:status = '' or b.status = :status)
               and (:mark = '' or (:mark = 'LINK_PENDING' and b.link_pending)
                    or (:mark = 'ALLERGY_NOT_CHECKED' and b.allergy_not_checked)
                    or (:mark = 'REFUND_DUE' and b.status = 'CANCELLED' and b.refund_due_paise > 0
                        and b.refund_credit_note_no is null))
            """;
    private static final String LIST = """
            select b.id, b.bill_no, b.saved_at, b.buyer_kind, b.patient_id, c.patient_id as customer_patient_id,
                   c.name as customer_name, b.walk_in_name, b.net_paise, b.status, b.link_pending, b.allergy_not_checked,
                   (b.status = 'CANCELLED' and b.refund_due_paise > 0 and b.refund_credit_note_no is null) as refund_due,
                   (select count(distinct l.product_id) from pharmacy.bill_line l
                     where l.tenant_id = b.tenant_id and l.bill_id = b.id) as item_count,
                   exists (select 1 from pharmacy.bill_line l where l.tenant_id = b.tenant_id and l.bill_id = b.id
                           and l.allergy_override_key is not null) as allergy_override,
                   exists (select 1 from pharmacy.bill_line l where l.tenant_id = b.tenant_id and l.bill_id = b.id
                           and l.h1_override_key is not null) as h1_override,
                   exists (select 1 from pharmacy.bill_line l where l.tenant_id = b.tenant_id and l.bill_id = b.id
                           and l.prescribed_product_id is not null) as substituted,
                   exists (select 1 from pharmacy.bill_line l where l.tenant_id = b.tenant_id and l.bill_id = b.id
                           and l.returned_qty > 0) as has_returns
            """ + LIST_FROM + " order by b.saved_at desc, b.id desc limit :limit offset :offset";
    private static final String LIST_COUNT = "select count(*)" + LIST_FROM;
    private static final String MARK_CANCELLED = """
            update pharmacy.bill set status = 'CANCELLED', cancelled_at = now(), cancelled_by = :by,
                   cancel_reason_key = :reason, cancel_note = :note, back_on_shelf = :shelf, refund_due_paise = :refundDue,
                   version = version + 1, updated_at = now(), updated_by = :by
             where tenant_id = :tenantId and id = :id and version = :version and status = 'SAVED'
            """;
    private static final String SET_REFUND_CN = """
            update pharmacy.bill set refund_credit_note_no = :no, version = version + 1, updated_at = now(), updated_by = :by
             where tenant_id = :tenantId and id = :id and version = :version and status = 'CANCELLED'
               and refund_credit_note_no is null
            """;
    private static final String SALE_LEDGER = """
            select id, doc_line_id, batch_id, product_id, qty_out from pharmacy.stock_ledger
             where tenant_id = :tenantId and doc_type = 'BILL' and doc_id = :billId and txn_type = 'COUNTER_SALE'
             order by occurred_at, id
            """;
    private static final String REGISTRATION = "select registration_no from pharmacy.pharmacist_registration where tenant_id = :tenantId and membership_id = :membershipId";
    private static final String REFUND_MODES = "select refund_modes from pharmacy.unit where tenant_id = :tenantId";

    private final JdbcClient jdbc;

    public JdbcSaleBillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BillRow> find(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id).query(JdbcSaleBillRepository::bill).optional();
    }

    @Override
    public Optional<BillRow> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcSaleBillRepository::bill)
                .optional();
    }

    @Override
    public List<LineRow> lines(UUID tenantId, UUID billId) {
        return jdbc.sql(LINES).param("tenantId", tenantId).param("billId", billId).query(JdbcSaleBillRepository::line).list();
    }

    @Override
    public List<PaymentRow> payments(UUID tenantId, UUID billId) {
        return jdbc.sql(PAYMENTS).param("tenantId", tenantId).param("billId", billId)
                .query((rs, n) -> new PaymentRow(rs.getString("mode"), rs.getLong("amount_paise"))).list();
    }

    @Override
    public boolean hasReturns(UUID tenantId, UUID billId) {
        return jdbc.sql(HAS_RETURNS).param("tenantId", tenantId).param("billId", billId).query(Boolean.class).single();
    }

    @Override
    public void insertBill(UUID tenantId, BillRow b) {
        jdbc.sql(INSERT_BILL).param("id", b.id()).param("tenantId", tenantId).param("unitId", b.unitId())
                .param("storeId", b.storeId()).param("billNo", b.billNo()).param("seriesKind", b.seriesKind())
                .param("fiscalYear", b.fiscalYear()).param("buyerKind", b.buyerKind()).param("patientId", b.patientId())
                .param("customerId", b.customerId()).param("walkInName", b.walkInName()).param("walkInPhone", b.walkInPhone())
                .param("linkPending", b.linkPending()).param("allergyNotChecked", b.allergyNotChecked())
                .param("rxRecordId", b.rxRecordId()).param("rxSource", b.rxSource())
                .param("prescriberName", b.prescriberName()).param("prescriberRegNo", b.prescriberRegNo())
                .param("prescriptionDate", b.prescriptionDate() == null ? null : Date.valueOf(b.prescriptionDate()))
                .param("heldBillId", b.heldBillId()).param("discountPercent", b.discountPercent())
                .param("discountRequestId", b.discountRequestId()).param("gross", b.grossPaise())
                .param("discount", b.discountPaise()).param("taxable", b.taxablePaise()).param("cgst", b.cgstPaise())
                .param("sgst", b.sgstPaise()).param("roundOff", b.roundOffPaise()).param("net", b.netPaise())
                .param("udhar", b.udharPaise()).param("savedAt", Timestamp.from(b.savedAt())).param("savedBy", b.savedBy())
                .param("pharmacist", b.pharmacistMembershipId()).update();
    }

    @Override
    public void insertLine(UUID tenantId, UUID unitId, UUID billId, LineRow l, UUID actorMembershipId) {
        jdbc.sql(INSERT_LINE).param("id", l.id()).param("tenantId", tenantId).param("unitId", unitId).param("billId", billId)
                .param("lineNo", l.lineNo()).param("productId", l.productId()).param("batchId", l.batchId())
                .param("qty", l.qty()).param("mrp", l.mrp()).param("gstRate", l.gstRate()).param("hsn", l.hsn())
                .param("gross", l.grossPaise()).param("discount", l.discountPaise()).param("net", l.netPaise())
                .param("taxable", l.taxablePaise()).param("tax", l.taxPaise())
                .param("prescribedProductId", l.prescribedProductId())
                .param("substitutionReasonKey", l.substitutionReasonKey()).param("substitutionNote", l.substitutionNote())
                .param("patientAgreed", l.patientAgreed()).param("allergyOverrideKey", l.allergyOverrideKey())
                .param("allergyOverrideNote", l.allergyOverrideNote()).param("h1OverrideKey", l.h1OverrideKey())
                .param("h1OverrideNote", l.h1OverrideNote()).param("decidedBy", l.overrideDecidedBy())
                .param("actor", actorMembershipId).param("secondSigner", l.secondSigner()).param("rxLineKey", l.rxLineKey())
                .update();
    }

    @Override
    public void insertPayment(UUID tenantId, UUID unitId, UUID storeId, UUID billId, String mode, long amountPaise,
                              UUID actorMembershipId) {
        jdbc.sql(INSERT_PAYMENT).param("tenantId", tenantId).param("unitId", unitId).param("storeId", storeId)
                .param("billId", billId).param("mode", mode).param("amount", amountPaise).param("actor", actorMembershipId)
                .update();
    }

    @Override
    public ListSlice page(UUID tenantId, UUID storeId, String number, Instant from, Instant to, String status, String mark,
                          int limit, int offset) {
        List<ListRow> items = jdbc.sql(LIST).param("tenantId", tenantId).param("storeId", storeId).param("number", number)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("status", status)
                .param("mark", mark).param("limit", limit).param("offset", offset).query(JdbcSaleBillRepository::listRow).list();
        long total = jdbc.sql(LIST_COUNT).param("tenantId", tenantId).param("storeId", storeId).param("number", number)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("status", status)
                .param("mark", mark).query(Long.class).single();
        return new ListSlice(items, total);
    }

    @Override
    public boolean markCancelled(UUID tenantId, UUID id, long version, UUID cancelledBy, String reasonKey, String note,
                                 boolean backOnShelf, long refundDuePaise) {
        return jdbc.sql(MARK_CANCELLED).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("by", cancelledBy).param("reason", reasonKey).param("note", note).param("shelf", backOnShelf)
                .param("refundDue", refundDuePaise).update() == 1;
    }

    @Override
    public boolean setRefundCreditNote(UUID tenantId, UUID id, long version, String creditNoteNo, UUID actorMembershipId) {
        return jdbc.sql(SET_REFUND_CN).param("tenantId", tenantId).param("id", id).param("version", version)
                .param("no", creditNoteNo).param("by", actorMembershipId).update() == 1;
    }

    @Override
    public List<SaleLedger> saleLedger(UUID tenantId, UUID billId) {
        return jdbc.sql(SALE_LEDGER).param("tenantId", tenantId).param("billId", billId)
                .query((rs, n) -> new SaleLedger(rs.getObject("id", UUID.class), rs.getObject("doc_line_id", UUID.class),
                        rs.getObject("batch_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getInt("qty_out")))
                .list();
    }

    @Override
    public Optional<String> pharmacistRegistration(UUID tenantId, UUID membershipId) {
        return jdbc.sql(REGISTRATION).param("tenantId", tenantId).param("membershipId", membershipId).query(String.class)
                .optional();
    }

    @Override
    public List<String> refundModes(UUID tenantId) {
        return jdbc.sql(REFUND_MODES).param("tenantId", tenantId).query((rs, n) -> {
            java.sql.Array array = rs.getArray("refund_modes");
            return array == null ? List.<String>of() : List.of((String[]) array.getArray());
        }).optional().orElse(List.of());
    }

    @Override
    public boolean linkPatient(UUID tenantId, UUID id, long version, UUID patientId, UUID actorMembershipId) {
        return jdbc.sql("""
                update pharmacy.bill set buyer_kind = 'PATIENT', patient_id = :patient, link_pending = false,
                       version = version + 1, updated_at = now(), updated_by = :by
                 where tenant_id = :t and id = :id and version = :version and buyer_kind = 'WALK_IN' and link_pending
                """).param("patient", patientId).param("by", actorMembershipId).param("t", tenantId).param("id", id)
                .param("version", version).update() == 1;
    }

    @Override
    public Optional<Integer> returnWindowDays(UUID tenantId) {
        return jdbc.sql("select return_window_days from pharmacy.unit where tenant_id = :t and return_window_days is not null")
                .param("t", tenantId).query(Integer.class).optional();
    }

    @Override
    public Optional<BillRow> findByNumber(UUID tenantId, UUID storeId, String lowerNumber) {
        return jdbc.sql("select " + BILL_COLUMNS + " from pharmacy.bill where tenant_id = :t and store_id = :s"
                + " and lower(bill_no) = :n").param("t", tenantId).param("s", storeId).param("n", lowerNumber)
                .query(JdbcSaleBillRepository::bill).optional();
    }

    @Override
    public List<BillRow> findByBuyer(UUID tenantId, UUID storeId, String termLower, String phone, Instant since, int limit) {
        String like = "%" + termLower.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("select " + BILL_COLUMNS + """
                 from pharmacy.bill
                where tenant_id = :t and store_id = :s and saved_at >= :since
                  and (lower(walk_in_name) like :like escape '\\' or (cast(:phone as text) is not null and walk_in_phone = :phone)
                       or customer_id in (select c.id from pharmacy.customer c where c.tenant_id = :t
                             and (lower(c.name) like :like escape '\\' or (cast(:phone as text) is not null and c.phone = :phone))))
                order by saved_at desc limit :limit
                """).param("t", tenantId).param("s", storeId).param("since", java.sql.Timestamp.from(since))
                .param("like", like).param("phone", phone).param("limit", limit).query(JdbcSaleBillRepository::bill).list();
    }

    @Override
    public void addReturnedQty(UUID tenantId, UUID billLineId, int qty) {
        jdbc.sql("update pharmacy.bill_line set returned_qty = returned_qty + :q where tenant_id = :t and id = :id")
                .param("q", qty).param("t", tenantId).param("id", billLineId).update();
    }

    @Override
    public java.util.Map<String, Long> refundedByMode(UUID tenantId, UUID billId) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        jdbc.sql("select mode, sum(amount_paise)::bigint as total from pharmacy.refund where tenant_id = :t and bill_id = :b"
                + " group by mode").param("t", tenantId).param("b", billId).query((rs, n) -> {
                    out.put(rs.getString("mode"), rs.getLong("total"));
                    return null;
                }).list();
        return out;
    }

    private static BillRow bill(ResultSet rs, int n) throws SQLException {
        Date prescriptionDate = rs.getDate("prescription_date");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        boolean shelfNull;
        boolean shelf = rs.getBoolean("back_on_shelf");
        shelfNull = rs.wasNull();
        return new BillRow(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getString("bill_no"), rs.getString("series_kind"),
                rs.getInt("fiscal_year"), rs.getString("status"), rs.getString("buyer_kind"),
                rs.getObject("patient_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("walk_in_name"),
                rs.getString("walk_in_phone"), rs.getBoolean("link_pending"), rs.getBoolean("allergy_not_checked"),
                rs.getObject("rx_record_id", UUID.class), rs.getString("rx_source"), rs.getString("prescriber_name"),
                rs.getString("prescriber_reg_no"), prescriptionDate == null ? null : prescriptionDate.toLocalDate(),
                rs.getObject("held_bill_id", UUID.class), rs.getBigDecimal("discount_percent"),
                rs.getObject("discount_request_id", UUID.class), rs.getLong("gross_paise"), rs.getLong("discount_paise"),
                rs.getLong("taxable_paise"), rs.getLong("cgst_paise"), rs.getLong("sgst_paise"), rs.getLong("round_off_paise"),
                rs.getLong("net_paise"), rs.getLong("udhar_paise"), rs.getTimestamp("saved_at").toInstant(),
                rs.getObject("saved_by", UUID.class), rs.getObject("pharmacist_membership_id", UUID.class),
                cancelledAt == null ? null : cancelledAt.toInstant(), rs.getObject("cancelled_by", UUID.class),
                rs.getString("cancel_reason_key"), rs.getString("cancel_note"), shelfNull ? null : shelf,
                rs.getLong("refund_due_paise"), rs.getString("refund_credit_note_no"), rs.getLong("version"));
    }

    private static LineRow line(ResultSet rs, int n) throws SQLException {
        boolean agreed = rs.getBoolean("patient_agreed");
        Boolean patientAgreed = rs.wasNull() ? null : agreed;
        return new LineRow(rs.getObject("id", UUID.class), rs.getInt("line_no"), rs.getObject("product_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getInt("qty"), rs.getInt("returned_qty"), rs.getBigDecimal("mrp"),
                rs.getBigDecimal("gst_rate"), rs.getString("hsn"), rs.getLong("gross_paise"), rs.getLong("discount_paise"),
                rs.getLong("net_paise"), rs.getLong("taxable_paise"), rs.getLong("tax_paise"),
                rs.getObject("prescribed_product_id", UUID.class), rs.getString("substitution_reason_key"),
                rs.getString("substitution_note"), patientAgreed, rs.getString("allergy_override_key"),
                rs.getString("allergy_override_note"), rs.getString("h1_override_key"), rs.getString("h1_override_note"),
                rs.getObject("override_decided_by", UUID.class), rs.getObject("actor_membership_id", UUID.class),
                rs.getObject("second_signer_membership_id", UUID.class), rs.getString("rx_line_key"));
    }

    private static ListRow listRow(ResultSet rs, int n) throws SQLException {
        return new ListRow(rs.getObject("id", UUID.class), rs.getString("bill_no"), rs.getTimestamp("saved_at").toInstant(),
                rs.getString("buyer_kind"), rs.getObject("patient_id", UUID.class),
                rs.getObject("customer_patient_id", UUID.class), rs.getString("customer_name"), rs.getString("walk_in_name"),
                rs.getInt("item_count"), rs.getLong("net_paise"), rs.getString("status"), rs.getBoolean("link_pending"),
                rs.getBoolean("allergy_not_checked"), rs.getBoolean("refund_due"), rs.getBoolean("allergy_override"),
                rs.getBoolean("h1_override"), rs.getBoolean("substituted"), rs.getBoolean("has_returns"));
    }
}
