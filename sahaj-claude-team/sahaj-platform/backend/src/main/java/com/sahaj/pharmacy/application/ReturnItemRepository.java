package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.return_item} and {@code return_item_note} (SAH-79-12 and 79-13, ADR-029 P12). A rejected receipt line
 * makes one item; the units wait as PENDING_RETURN at the holding store until the accepting store accepts them. The dispute is
 * set once; a note is append-only. Quantities are issue units and are never logged here.
 */
public interface ReturnItemRepository {

    record Row(UUID id, UUID unitId, String docNo, String itemType, UUID dispatchLineId, UUID storeReturnId, UUID damageReportId,
               UUID holdingStoreId, UUID acceptingStoreId, UUID productId, UUID batchId, int qty, String reasonKey,
               String status, String defaultPutBackInto, boolean scheduleX, UUID raisedBy, Instant raisedAt,
               String disputeReasonKey, UUID disputeBy, Instant disputeAt, UUID acceptedBy, Instant acceptedAt,
               UUID secondSignerMembershipId, String resultNote, long version) {
    }

    record Note(UUID returnItemId, String note, UUID createdBy, Instant createdAt) {
    }

    record NewItem(UUID id, UUID unitId, String docNo, String itemType, UUID dispatchLineId, UUID storeReturnId,
                   UUID damageReportId, UUID holdingStoreId, UUID acceptingStoreId, UUID productId, UUID batchId, int qty,
                   String reasonKey, String defaultPutBackInto, boolean scheduleX, UUID raisedBy) {

        /** A rejected dispatch line (WP-F): no store return and no damage report. */
        public NewItem(UUID id, UUID unitId, String docNo, String itemType, UUID dispatchLineId, UUID holdingStoreId,
                       UUID acceptingStoreId, UUID productId, UUID batchId, int qty, String reasonKey, String defaultPutBackInto,
                       boolean scheduleX, UUID raisedBy) {
            this(id, unitId, docNo, itemType, dispatchLineId, null, null, holdingStoreId, acceptingStoreId, productId, batchId, qty,
                    reasonKey, defaultPutBackInto, scheduleX, raisedBy);
        }
    }

    record Slice(List<Row> items, long total) {
    }

    Optional<Row> findById(UUID tenantId, UUID id);

    /** The item locked {@code FOR UPDATE} (lock order step 3b). */
    Optional<Row> findForUpdate(UUID tenantId, UUID id);

    /** The item of a store return (one per return, SAH-79-14). */
    Optional<Row> findByStoreReturn(UUID tenantId, UUID storeReturnId);

    /** The item of a damage report that was sent to the Central Store (one per report, SAH-79-14). */
    Optional<Row> findByDamageReport(UUID tenantId, UUID damageReportId);

    /** The item made from a dispatch line, if the line was rejected. */
    Optional<Row> findByDispatchLine(UUID tenantId, UUID dispatchLineId);

    void insert(UUID tenantId, NewItem item);

    /** The notes of an item, oldest first. */
    List<Note> notes(UUID tenantId, UUID itemId);

    /** The notes of several items at once (a list page), oldest first within an item. */
    Map<UUID, List<Note>> notesOf(UUID tenantId, Collection<UUID> itemIds);

    void insertNote(UUID tenantId, UUID unitId, UUID itemId, String note, UUID actor);

    /** WAITING to DISPUTED with the reason, by and at; false when the version is not current. */
    boolean dispute(UUID tenantId, UUID id, long version, String reasonKey, UUID by);

    /** WAITING or DISPUTED to ACCEPTED_SALEABLE or ACCEPTED_QUARANTINE, with the signer and the result note. */
    boolean accept(UUID tenantId, UUID id, long version, String status, UUID by, UUID secondSigner, String resultNote);

    /** {@code accepting} true lists the items the store accepts; false the items it holds. Newest first. */
    Slice page(UUID tenantId, UUID storeId, boolean accepting, String status, int limit, int offset);

    /** Units of rejected lines of this indent that wait for the accepting store (WAITING or DISPUTED). */
    int waitingQtyForIndent(UUID tenantId, UUID indentId);
}
