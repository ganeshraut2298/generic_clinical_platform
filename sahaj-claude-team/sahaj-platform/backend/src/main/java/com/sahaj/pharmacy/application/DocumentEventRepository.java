package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Port over {@code pharmacy.document_event}: the append-only history of documents (ADR-029 P9, BR-26). Insert and select only. */
public interface DocumentEventRepository {

    record Event(String event, Instant occurredAt, UUID actorMembershipId, String actingAs, UUID storeId, UUID lineId,
                 Integer qtyBefore, Integer qtyAfter, String reasonKey) {
    }

    /** {@code occurred_at} is set by the database; {@code actingAs} is HOD, DELEGATE, OWNER, ESCALATED or null. */
    void insert(UUID tenantId, UUID unitId, String docType, UUID docId, String event, UUID storeId, UUID actorMembershipId,
                String actingAs, UUID lineId, Integer qtyBefore, Integer qtyAfter, String reasonKey);

    /** Oldest first. */
    List<Event> forDocument(UUID tenantId, String docType, UUID docId);
}
