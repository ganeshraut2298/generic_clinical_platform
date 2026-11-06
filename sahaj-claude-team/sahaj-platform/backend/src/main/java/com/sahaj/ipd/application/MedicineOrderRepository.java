package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-31 {@code ipd.medicine_order} (ADR-009, docs/database/SAH-31.md). Every method is scoped to the tenant. The
 * database sets the id, the clinic, the status PENDING, the order time and the version on insert, and stamps the
 * verified, rejected and stopped times on the matching transition; the application never sends them. A refusal by a
 * database check, the guard trigger or a unique index is a code fault and is never swallowed.
 */
public interface MedicineOrderRepository {

    String PENDING = "PENDING";
    String ACTIVE = "ACTIVE";
    String REJECTED = "REJECTED";
    String STOPPED = "STOPPED";

    /** The database clock ({@code clock_timestamp()}), never the application clock. */
    Instant databaseNow();

    Optional<OrderRow> findById(UUID tenantId, UUID orderId);

    Optional<OrderRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** Every order of the admission in chart order: {@code ordered_at}, then id. */
    List<OrderRow> findByAdmission(UUID tenantId, UUID admissionId);

    /** The live orders (PENDING and ACTIVE) of these admissions, chart order within each admission. */
    List<OrderRow> findLiveByAdmissions(UUID tenantId, Collection<UUID> admissionIds);

    /** Inserts a PENDING order; returns it as stored (id, order time and version from the database). */
    OrderRow insert(UUID tenantId, NewOrder order);

    /** PENDING to ACTIVE. False when the order was not PENDING. */
    boolean markActive(UUID tenantId, UUID orderId, UUID verifiedBy);

    /** PENDING to REJECTED. False when the order was not PENDING. */
    boolean markRejected(UUID tenantId, UUID orderId, UUID rejectedBy, String reasonKey);

    /** PENDING or ACTIVE to STOPPED. False when the order was not live. */
    boolean markStopped(UUID tenantId, UUID orderId, UUID stoppedBy);

    /**
     * Orders waiting for the pharmacist (PENDING) of ADMITTED admissions, oldest first ({@code ordered_at}, then id),
     * strictly after {@code after} when it is set; at most {@code limit} rows.
     */
    List<PendingRow> findPending(UUID tenantId, Position after, int limit);

    /** Id and version of every order counted as waiting (the same rule as {@link #findPending}). For the summary token. */
    List<WaitingKey> findWaitingKeys(UUID tenantId);

    /** How many orders are waiting: {@code select count(*)} with the rule of {@link #findPending} (review R3). */
    int countWaiting(UUID tenantId);

    /** The keyset position of a queue row. */
    record Position(Instant orderedAt, UUID id) {
    }

    record WaitingKey(UUID id, long version) {
    }

    record NewOrder(UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, UUID orderedBy,
                    String overrideReasonKey, List<String> warnings) {

        @Override
        public String toString() {
            return "NewOrder[admissionId=" + admissionId + ", drugKey=" + drugKey + "]";
        }
    }

    /** The warning lines are clinical text: {@code toString} leaves them out. */
    record OrderRow(UUID id, UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, String status,
                    UUID orderedBy, Instant orderedAt, String overrideReasonKey, List<String> warnings,
                    UUID verifiedBy, Instant verifiedAt, UUID rejectedBy, Instant rejectedAt, String rejectReasonKey,
                    UUID stoppedBy, Instant stoppedAt, long version) {

        public OrderRow {
            warnings = List.copyOf(warnings);
        }

        public boolean live() {
            return PENDING.equals(status) || ACTIVE.equals(status);
        }

        @Override
        public String toString() {
            return "OrderRow[id=" + id + ", status=" + status + "]";
        }
    }

    /** A waiting order with its admission's patient and current bed. */
    record PendingRow(OrderRow order, UUID patientId, String bedCode) {

        public Position position() {
            return new Position(order.orderedAt(), order.id());
        }

        @Override
        public String toString() {
            return "PendingRow[orderId=" + order.id() + "]";
        }
    }
}
