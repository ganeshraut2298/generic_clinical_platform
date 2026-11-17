package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-76-2 (ADR-027 D6; docs/database/SAH-76.md section 1.6): {@code ipd.medicine_order_query}, the pharmacist's
 * question and the doctor's reply. One open query per order (partial unique index). The question and the reply are
 * clinical text: stored, shown to OWNER, DOCTOR and PHARMACIST, never logged or audited. {@code asked_at},
 * {@code replied_at} and {@code closed_at} are stamped by the database.
 */
public interface OrderQueryRepository {

    String CLOSED_BY_REPLY = "REPLY";
    String CLOSED_BY_CHANGE = "CHANGE";
    String CLOSED_BY_STOP = "STOP";
    String CLOSED_BY_REJECT = "REJECT";

    /** Opens a query on the current version (the order is PENDING and the asker is not the orderer). */
    QueryRow insert(UUID tenantId, UUID orderId, int orderVersion, String question, UUID askedBy);

    /** The query of the order that is still open, if any. */
    Optional<QueryRow> findOpen(UUID tenantId, UUID orderId);

    /** The latest query of each of these orders (newest {@code asked_at}), open or closed; orders with none are absent. */
    Map<UUID, QueryRow> findLatest(UUID tenantId, Collection<UUID> orderIds);

    /**
     * Closes the open query once. {@code closedBy} is REPLY (with {@code reply} and {@code repliedBy}), or CHANGE,
     * STOP or REJECT (reply and repliedBy null). False when no query was open.
     */
    boolean close(UUID tenantId, UUID orderId, String closedBy, String reply, UUID repliedBy);

    /** A stored query. Question and reply are clinical text: hidden from {@code toString}. */
    record QueryRow(UUID id, UUID orderId, int orderVersion, String question, UUID askedBy, Instant askedAt,
                    String reply, UUID repliedBy, Instant repliedAt, String closedBy, Instant closedAt) {

        public boolean open() {
            return closedAt == null;
        }

        @Override
        public String toString() {
            return "QueryRow[id=" + id + ", orderId=" + orderId + "]";
        }
    }
}
