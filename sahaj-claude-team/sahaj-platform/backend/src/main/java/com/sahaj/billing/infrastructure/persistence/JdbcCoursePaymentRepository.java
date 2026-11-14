package com.sahaj.billing.infrastructure.persistence;

import com.sahaj.billing.application.CoursePaymentRepository;
import com.sahaj.billing.application.PaymentRepository.PaymentTotals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Course payments, credit carried to IPD, and the receipt counter (SAH-58; ADR-022 D12). Row level security scopes
 * every statement to the bound clinic and the tenant id is also in each statement. Payments and transfers are
 * insert-only (grants, policies and triggers); there is no update or delete anywhere.
 */
@Repository
public class JdbcCoursePaymentRepository implements CoursePaymentRepository {

    private static final String LOCK = "select pg_advisory_xact_lock(hashtextextended(:key, 0))";
    private static final String NEXT_RECEIPT = """
            insert into billing.receipt_counter (tenant_id, last_no, updated_at) values (:tenantId, 1, now())
            on conflict (tenant_id) do update set last_no = receipt_counter.last_no + 1, updated_at = now()
            returning last_no
            """;
    private static final String COLUMNS = """
            id, course_id, patient_id, receipt_no, type, mode, amount_paise, against_sitting_id, against_label,
            use_credit, request_hash, recorded_by, recorded_at
            """;
    private static final String INSERT = """
            insert into billing.course_payment (id, tenant_id, course_id, patient_id, receipt_no, type, mode,
                                                amount_paise, against_sitting_id, against_label, use_credit,
                                                idempotency_key, request_hash, recorded_by)
            values (:id, :tenantId, :courseId, :patientId, :receiptNo, :type, :mode, :amount, :sittingId, :label,
                    :useCredit, :idempotencyKey, :requestHash, :actor)
            """;
    private static final String BY_KEY = "select " + COLUMNS
            + " from billing.course_payment where tenant_id = :tenantId and idempotency_key = :key";
    private static final String BY_COURSE = "select " + COLUMNS
            + " from billing.course_payment where tenant_id = :tenantId and course_id = :courseId order by receipt_no";
    private static final String BY_RECEIPT = "select " + COLUMNS
            + " from billing.course_payment where tenant_id = :tenantId and course_id = :courseId"
            + " and receipt_no = :receiptNo";
    private static final String EARMARKED = """
            select coalesce(sum(amount_paise), 0) from billing.course_payment
            where tenant_id = :tenantId and course_id = :courseId and type = 'IPD_DEPOSIT'
              and against_sitting_id = :sittingId
            """;
    private static final String SUM_BY_MODE = """
            select coalesce(sum(amount_paise), 0) from billing.course_payment
            where tenant_id = :tenantId and mode = :mode and recorded_at >= :since
            """;
    private static final String TOTALS = """
            select coalesce(sum(amount_paise), 0) as net, count(*) as cnt from billing.course_payment
            where tenant_id = :tenantId and recorded_at >= :from and recorded_at < :to
            """;
    private static final String BETWEEN = """
            select mode, amount_paise, recorded_at from billing.course_payment
            where tenant_id = :tenantId and recorded_at >= :from and recorded_at < :to
            order by recorded_at, receipt_no
            """;
    private static final String INSERT_TRANSFER = """
            insert into billing.course_credit_transfer (id, tenant_id, course_id, sitting_id, admission_id,
                                                        amount_paise, transferred_by)
            values (:id, :tenantId, :courseId, :sittingId, :admissionId, :amount, :actor)
            """;
    private static final String TRANSFERS = """
            select id, course_id, sitting_id, admission_id, amount_paise, transferred_by, transferred_at
            from billing.course_credit_transfer
            where tenant_id = :tenantId and course_id = :courseId order by transferred_at, id
            """;

    private final JdbcClient jdbc;

    public JdbcCoursePaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(UUID tenantId, String scope) {
        jdbc.sql(LOCK).param("key", "billing-course:" + tenantId + ":" + scope).query().singleRow();
    }

    @Override
    public long nextReceiptNo(UUID tenantId) {
        return jdbc.sql(NEXT_RECEIPT).param("tenantId", tenantId).query((rs, rowNum) -> rs.getLong(1)).single();
    }

    @Override
    public UUID insert(UUID tenantId, NewPayment p, UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT).param("id", id).param("tenantId", tenantId).param("courseId", p.courseId())
                .param("patientId", p.patientId()).param("receiptNo", p.receiptNo()).param("type", p.type())
                .param("mode", p.mode()).param("amount", p.amountPaise()).param("sittingId", p.againstSittingId())
                .param("label", p.againstLabel()).param("useCredit", p.useCredit())
                .param("idempotencyKey", p.idempotencyKey()).param("requestHash", p.requestHash())
                .param("actor", actorUserId).update();
        return id;
    }

    @Override
    public Optional<PaymentRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(BY_KEY).param("tenantId", tenantId).param("key", idempotencyKey)
                .query(JdbcCoursePaymentRepository::payment).optional();
    }

    @Override
    public List<PaymentRow> findByCourse(UUID tenantId, UUID courseId) {
        return jdbc.sql(BY_COURSE).param("tenantId", tenantId).param("courseId", courseId)
                .query(JdbcCoursePaymentRepository::payment).list();
    }

    @Override
    public Optional<PaymentRow> findByReceipt(UUID tenantId, UUID courseId, long receiptNo) {
        return jdbc.sql(BY_RECEIPT).param("tenantId", tenantId).param("courseId", courseId)
                .param("receiptNo", receiptNo).query(JdbcCoursePaymentRepository::payment).optional();
    }

    @Override
    public long earmarkedFor(UUID tenantId, UUID courseId, UUID sittingId) {
        return jdbc.sql(EARMARKED).param("tenantId", tenantId).param("courseId", courseId)
                .param("sittingId", sittingId).query((rs, rowNum) -> rs.getLong(1)).single();
    }

    @Override
    public long sumByModeSince(UUID tenantId, String mode, Instant since) {
        return jdbc.sql(SUM_BY_MODE).param("tenantId", tenantId).param("mode", mode).param("since", since)
                .query((rs, rowNum) -> rs.getLong(1)).single();
    }

    @Override
    public PaymentTotals totalsBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(TOTALS).param("tenantId", tenantId).param("from", from).param("to", to)
                .query((rs, rowNum) -> new PaymentTotals(rs.getLong("net"), rs.getLong("cnt"))).single();
    }

    @Override
    public List<Point> findBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql(BETWEEN).param("tenantId", tenantId).param("from", from).param("to", to)
                .query((rs, rowNum) -> new Point(rs.getString("mode"), rs.getLong("amount_paise"),
                        rs.getTimestamp("recorded_at").toInstant())).list();
    }

    @Override
    public UUID insertTransfer(UUID tenantId, UUID courseId, UUID sittingId, UUID admissionId, long amountPaise,
                               UUID actorUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT_TRANSFER).param("id", id).param("tenantId", tenantId).param("courseId", courseId)
                .param("sittingId", sittingId).param("admissionId", admissionId).param("amount", amountPaise)
                .param("actor", actorUserId).update();
        return id;
    }

    @Override
    public List<TransferRow> transfersOf(UUID tenantId, UUID courseId) {
        return jdbc.sql(TRANSFERS).param("tenantId", tenantId).param("courseId", courseId)
                .query((rs, rowNum) -> new TransferRow(rs.getObject("id", UUID.class),
                        rs.getObject("course_id", UUID.class), rs.getObject("sitting_id", UUID.class),
                        rs.getObject("admission_id", UUID.class), rs.getLong("amount_paise"),
                        rs.getObject("transferred_by", UUID.class), rs.getTimestamp("transferred_at").toInstant()))
                .list();
    }

    private static PaymentRow payment(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getLong("receipt_no"), rs.getString("type"),
                rs.getString("mode"), rs.getLong("amount_paise"), rs.getObject("against_sitting_id", UUID.class),
                rs.getString("against_label"), rs.getBoolean("use_credit"), rs.getString("request_hash"),
                rs.getObject("recorded_by", UUID.class), rs.getTimestamp("recorded_at").toInstant());
    }
}
