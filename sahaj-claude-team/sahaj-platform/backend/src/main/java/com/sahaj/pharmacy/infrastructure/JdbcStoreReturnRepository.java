package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.StoreReturnRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.store_return} (SAH-79-14). Every statement names the tenant as well as relying on row level security. */
@Repository
public class JdbcStoreReturnRepository implements StoreReturnRepository {

    private static final String FIND = """
            select id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, quantity, reason_key, note,
                   second_signer_membership_id, created_at, created_by
              from pharmacy.store_return where tenant_id = :tenantId and id = :id
            """;
    private static final String INSERT = """
            insert into pharmacy.store_return (id, tenant_id, unit_id, doc_seq, doc_no, store_id, product_id, batch_id, quantity,
                   reason_key, note, second_signer_membership_id, created_by)
            values (:id, :tenantId, :unitId, :docSeq, :docNo, :storeId, :productId, :batchId, :quantity, :reason, :note,
                   :signer, :by)
            """;

    private final JdbcClient jdbc;

    public JdbcStoreReturnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("id", id)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("unit_id", UUID.class),
                        rs.getLong("doc_seq"), rs.getString("doc_no"), rs.getObject("store_id", UUID.class),
                        rs.getObject("product_id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("quantity"),
                        rs.getString("reason_key"), rs.getString("note"),
                        rs.getObject("second_signer_membership_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("created_by", UUID.class))).optional();
    }

    @Override
    public void insert(UUID tenantId, NewRow r) {
        jdbc.sql(INSERT).param("id", r.id()).param("tenantId", tenantId).param("unitId", r.unitId()).param("docSeq", r.docSeq())
                .param("docNo", r.docNo()).param("storeId", r.storeId()).param("productId", r.productId())
                .param("batchId", r.batchId()).param("quantity", r.quantity()).param("reason", r.reasonKey())
                .param("note", r.note()).param("signer", r.secondSignerMembershipId()).param("by", r.createdBy()).update();
    }
}
