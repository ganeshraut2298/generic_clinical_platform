package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.ReviewOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-76-2 (ADR-027 D6; docs/database/SAH-76.md section 1.6): {@code ipd.pharmacist_review}, the review-after-dose
 * items. One per GIVEN dose recorded before verification (unique per dose). {@code created_at} and {@code closed_at}
 * are stamped by the database. Reads and writes are scoped to the tenant.
 */
public interface PharmacistReviewRepository {

    /** Opens the item for the dose (idempotent: false when the dose already has one). */
    boolean insert(UUID tenantId, UUID orderId, int orderVersion, UUID doseId);

    Optional<ReviewRow> findById(UUID tenantId, UUID reviewId);

    Optional<ReviewRow> findByDose(UUID tenantId, UUID doseId);

    /** Open items, oldest first ({@code created_at}, then id), strictly after {@code after}; at most {@code limit}. */
    List<ListRow> findOpen(UUID tenantId, Cursor after, int limit);

    /** How many items are open: the number of the heading "Review after dose (n)" and of the Safety tab. */
    int countOpen(UUID tenantId);

    /** The ids of the open items, for the summary change token. */
    List<UUID> findOpenIds(UUID tenantId);

    /** Closes the open item of this dose; false when it has none or it is closed. */
    boolean closeByDose(UUID tenantId, UUID doseId, UUID closedBy, ReviewOutcome outcome);

    /** Closes every open item of the order (a verify or reject); returns how many were closed. */
    int closeByOrder(UUID tenantId, UUID orderId, UUID closedBy, ReviewOutcome outcome);

    /** The keyset position of an item. */
    record Cursor(Instant createdAt, UUID id) {
    }

    /** An open item with its order, its admission and the dose it is about (one join, no read per row). */
    record ListRow(ReviewRow review, UUID admissionId, UUID patientId, String bedCode, String drugKey,
                   String orderStatus, UUID orderedBy, Instant givenAt, UUID givenBy) {

        @Override
        public String toString() {
            return "ListRow[reviewId=" + review.id() + "]";
        }
    }

    record ReviewRow(UUID id, UUID orderId, int orderVersion, UUID doseId, Instant createdAt, Instant closedAt,
                     UUID closedBy, ReviewOutcome outcome) {

        public boolean open() {
            return closedAt == null;
        }

        public Cursor cursor() {
            return new Cursor(createdAt, id);
        }
    }
}
