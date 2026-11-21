package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.consumption} and {@code consumption_line} (SAH-79-16, ADR-029 P12). Insert only: an entry is never
 * edited or deleted. The patient is an opaque admission id; the IP number shown is the reference of the entry's ledger lines.
 * Names are never stored here except the typed "Other" ordered-by name, which is never logged.
 */
public interface ConsumptionRepository {

    record Row(UUID id, UUID unitId, UUID storeId, String type, String purposeKey, String note, UUID admissionId,
               UUID orderedByMembershipId, String orderedByName, String chargeStatus, UUID stockAuditLineId,
               UUID secondSignerMembershipId, Instant recordedAt, UUID recordedBy) {
    }

    record Line(UUID id, UUID consumptionId, UUID productId, UUID batchId, int quantity) {
    }

    record Slice(List<Row> items, long total) {
    }

    Optional<Row> find(UUID tenantId, UUID storeId, UUID id);

    List<Line> lines(UUID tenantId, UUID consumptionId);

    /** The lines of several entries at once (one statement for a page). */
    Map<UUID, List<Line>> linesOf(UUID tenantId, Collection<UUID> consumptionIds);

    void insert(UUID tenantId, Row row);

    void insertLine(UUID tenantId, UUID unitId, Line line, UUID actorMembershipId);

    /** Entries recorded in {@code [from, to)}, newest first. */
    Slice page(UUID tenantId, UUID storeId, Instant from, Instant to, int limit, int offset);

    /** The IP numbers of patient entries: the reference of their ledger lines, by consumption id. */
    Map<UUID, String> ipNumbers(UUID tenantId, Collection<UUID> consumptionIds);

    /** The IPD ward linked to a ward store, when it has one. */
    Optional<UUID> linkedWard(UUID tenantId, UUID storeId);
}
