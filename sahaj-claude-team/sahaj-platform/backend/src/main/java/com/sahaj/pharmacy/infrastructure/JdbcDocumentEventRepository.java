package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DocumentEventRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC over {@code pharmacy.document_event}. Never writes {@code occurred_at}: the database clock sets it. */
@Repository
public class JdbcDocumentEventRepository implements DocumentEventRepository {

    private static final String INSERT = """
            insert into pharmacy.document_event (tenant_id, unit_id, doc_type, doc_id, event, store_id, actor_membership_id,
                   acting_as, line_id, qty_before, qty_after, reason_key)
            values (:tenantId, :unitId, :docType, :docId, :event, :storeId, :actor, cast(:actingAs as text),
                   cast(:lineId as uuid), cast(:before as integer), cast(:after as integer), cast(:reason as text))
            """;
    private static final String FOR_DOCUMENT = """
            select event, occurred_at, actor_membership_id, acting_as, store_id, line_id, qty_before, qty_after, reason_key
              from pharmacy.document_event
             where tenant_id = :tenantId and doc_type = :docType and doc_id = :docId
             order by occurred_at, id
            """;

    private final JdbcClient jdbc;

    public JdbcDocumentEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID tenantId, UUID unitId, String docType, UUID docId, String event, UUID storeId,
                       UUID actorMembershipId, String actingAs, UUID lineId, Integer qtyBefore, Integer qtyAfter,
                       String reasonKey) {
        jdbc.sql(INSERT).param("tenantId", tenantId).param("unitId", unitId).param("docType", docType)
                .param("docId", docId).param("event", event).param("storeId", storeId).param("actor", actorMembershipId)
                .param("actingAs", actingAs).param("lineId", lineId).param("before", qtyBefore).param("after", qtyAfter)
                .param("reason", reasonKey).update();
    }

    @Override
    public List<Event> forDocument(UUID tenantId, String docType, UUID docId) {
        return jdbc.sql(FOR_DOCUMENT).param("tenantId", tenantId).param("docType", docType).param("docId", docId)
                .query((rs, n) -> {
                    int before = rs.getInt("qty_before");
                    Integer qtyBefore = rs.wasNull() ? null : before;
                    int after = rs.getInt("qty_after");
                    Integer qtyAfter = rs.wasNull() ? null : after;
                    Timestamp at = rs.getTimestamp("occurred_at");
                    return new Event(rs.getString("event"), at.toInstant(), rs.getObject("actor_membership_id", UUID.class),
                            rs.getString("acting_as"), rs.getObject("store_id", UUID.class),
                            rs.getObject("line_id", UUID.class), qtyBefore, qtyAfter, rs.getString("reason_key"));
                }).list();
    }
}
