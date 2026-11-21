package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DamageReportRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.damage_report} (SAH-79-14). Every statement names the tenant as well as relying on row level security. */
@Repository
public class JdbcDamageReportRepository implements DamageReportRepository {

    private static final String COLUMNS = """
            id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, from_state, quantity, reason_key, note, after_approval,
            status, reject_outcome, approval_request_id, second_signer_membership_id, decided_at, created_at, created_by, version
            """;
    private static final String FIND = "select " + COLUMNS
            + " from pharmacy.damage_report where tenant_id = :tenantId and store_id = :storeId and id = :id";
    private static final String FIND_BY_ID = "select " + COLUMNS
            + " from pharmacy.damage_report where tenant_id = :tenantId and id = :id";
    private static final String FIND_FOR_UPDATE = FIND_BY_ID + " for update";
    private static final String INSERT = """
            insert into pharmacy.damage_report (id, tenant_id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id,
                   from_state, quantity, reason_key, note, after_approval, status, second_signer_membership_id, decided_at,
                   created_by)
            values (:id, :tenantId, :unitId, :docSeq, :docNo, :storeId, :productId, :batchId, :fromState, :quantity, :reason,
                   :note, :after, :status, :signer, case when :decided then now() end, :by)
            """;
    private static final String ATTACH = """
            update pharmacy.damage_report set approval_request_id = :requestId, updated_at = now(), updated_by = :by,
                   version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL' and approval_request_id is null
            """;
    private static final String DECIDE = """
            update pharmacy.damage_report set status = :status, reject_outcome = :outcome, decided_at = now(),
                   updated_at = now(), updated_by = :by, version = version + 1
             where tenant_id = :tenantId and id = :id and status = 'WAITING_APPROVAL'
            """;

    private final JdbcClient jdbc;

    public JdbcDamageReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> find(UUID tenantId, UUID storeId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("storeId", storeId).param("id", id)
                .query(JdbcDamageReportRepository::map).optional();
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", id).query(JdbcDamageReportRepository::map).optional();
    }

    @Override
    public Optional<Row> findForUpdate(UUID tenantId, UUID id) {
        return jdbc.sql(FIND_FOR_UPDATE).param("tenantId", tenantId).param("id", id).query(JdbcDamageReportRepository::map)
                .optional();
    }

    @Override
    public int waitingQuantity(UUID tenantId, UUID storeId, UUID batchId) {
        return jdbc.sql("""
                select coalesce(sum(quantity), 0)::int from pharmacy.damage_report
                 where tenant_id = :tenantId and store_id = :storeId and batch_id = :batchId and status = 'WAITING_APPROVAL'
                """).param("tenantId", tenantId).param("storeId", storeId).param("batchId", batchId).query(Integer.class)
                .single();
    }

    @Override
    public void insert(UUID tenantId, NewRow r) {
        jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("unitId", r.unitId()).param("docSeq", r.docSeq())
                .param("docNo", r.docNo()).param("storeId", r.storeId()).param("productId", r.productId())
                .param("batchId", r.batchId()).param("fromState", r.fromState()).param("quantity", r.quantity())
                .param("reason", r.reasonKey()).param("note", r.note()).param("after", r.afterApproval())
                .param("status", r.status()).param("signer", r.secondSignerMembershipId()).param("decided", r.decided())
                .param("by", r.createdBy()).update();
    }

    @Override
    public void attachRequest(UUID tenantId, UUID id, UUID requestId, UUID actor) {
        if (jdbc.sql(ATTACH).param("tenantId", tenantId).param("id", id).param("requestId", requestId).param("by", actor)
                .update() != 1) {
            throw new IllegalStateException("A damage report changed before its request was attached");
        }
    }

    @Override
    public boolean decide(UUID tenantId, UUID id, String status, String rejectOutcome, UUID actor) {
        return jdbc.sql(DECIDE).param("tenantId", tenantId).param("id", id).param("status", status)
                .param("outcome", rejectOutcome).param("by", actor).update() == 1;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getLong("doc_seq"),
                rs.getString("doc_no"), rs.getObject("store_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getString("from_state"), rs.getInt("quantity"),
                rs.getString("reason_key"), rs.getString("note"), rs.getString("after_approval"), rs.getString("status"),
                rs.getString("reject_outcome"), rs.getObject("approval_request_id", UUID.class),
                rs.getObject("second_signer_membership_id", UUID.class), instant(rs.getTimestamp("decided_at")),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("created_by", UUID.class), rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
