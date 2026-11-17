package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-31 {@code ipd.medicine_order} (ADR-009, docs/database/SAH-31.md) with the SAH-76 head columns (ADR-027 D1,
 * docs/database/SAH-76.md section 1.4). Every method is scoped to the tenant. The database sets the id, the clinic, the
 * order time and the row counter {@code version} on insert, and stamps the verified, rejected and stopped times on the
 * matching transition; the application never sends them. {@code current_version} is the number users see as "v2"; the
 * row counter {@code version} is never mixed with it. A refusal by a database check, the guard trigger or a unique
 * index is a code fault and is never swallowed. The head and its version row are written in the same transaction (see
 * {@link OrderVersionRepository}); the database checks their agreement at commit.
 */
public interface MedicineOrderRepository {

    String PENDING = "PENDING";
    String QUERIED = "QUERIED";
    String ACTIVE = "ACTIVE";
    String ON_HOLD = "ON_HOLD";
    String REJECTED = "REJECTED";
    String STOPPED = "STOPPED";

    /** The database clock ({@code clock_timestamp()}), never the application clock. */
    Instant databaseNow();

    Optional<OrderRow> findById(UUID tenantId, UUID orderId);

    Optional<OrderRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** Every order of the admission in chart order: {@code ordered_at}, then id. */
    List<OrderRow> findByAdmission(UUID tenantId, UUID admissionId);

    /** The live orders (PENDING, QUERIED, ACTIVE and ON_HOLD) of these admissions, chart order within each admission. */
    List<OrderRow> findLiveByAdmissions(UUID tenantId, Collection<UUID> admissionIds);

    /** Inserts a new order (PENDING, or ACTIVE when its version needs no check); returns it as stored. */
    OrderRow insert(UUID tenantId, NewOrder order);

    /** PENDING to ACTIVE (verify). False when the order was not PENDING. */
    boolean markActive(UUID tenantId, UUID orderId, UUID verifiedBy);

    /** PENDING or QUERIED to REJECTED. False when the order was neither. */
    boolean markRejected(UUID tenantId, UUID orderId, UUID rejectedBy, String reasonKey);

    /** PENDING, QUERIED, ACTIVE or ON_HOLD to STOPPED with the doctor's reason. False when the order was not live. */
    boolean markStopped(UUID tenantId, UUID orderId, UUID stoppedBy, String reason);

    /** PENDING to QUERIED (the pharmacist's query). */
    boolean markQueried(UUID tenantId, UUID orderId);

    /** QUERIED to PENDING (the doctor's reply). */
    boolean markReplied(UUID tenantId, UUID orderId);

    /** ACTIVE to ON_HOLD. */
    boolean markHeld(UUID tenantId, UUID orderId);

    /** ON_HOLD to ACTIVE. */
    boolean markResumed(UUID tenantId, UUID orderId);

    /**
     * A change: {@code current_version} + 1, the status PENDING or ACTIVE and the priority of the new version.
     * False when the order was not PENDING, QUERIED or ACTIVE.
     */
    boolean markChanged(UUID tenantId, UUID orderId, int newVersion, String newStatus, String priority);

    /**
     * Orders waiting for the pharmacist (PENDING) and queried orders (QUERIED) of ADMITTED admissions, oldest first
     * ({@code ordered_at}, then id), strictly after {@code after} when it is set; at most {@code limit} rows.
     */
    List<PendingRow> findPending(UUID tenantId, Position after, int limit);

    /** Id and row counter of every order counted in the queue (PENDING and QUERIED). For the summary token. */
    List<WaitingKey> findWaitingKeys(UUID tenantId);

    /** How many orders are waiting (PENDING only): {@code select count(*)} with the rule of {@link #findPending}. */
    int countWaiting(UUID tenantId);

    /** How many orders are queried (QUERIED, ADMITTED admissions). */
    int countQueried(UUID tenantId);

    /** The keyset position of a queue row. */
    record Position(Instant orderedAt, UUID id) {
    }

    record WaitingKey(UUID id, long version) {
    }

    /**
     * The values of a new order. {@code frequencyKey} is null for a PRN or STAT order (the head keeps the frequency of
     * version 1). {@code status} is PENDING, or ACTIVE when the first version needs no pharmacist check.
     */
    record NewOrder(UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, UUID orderedBy,
                    String overrideReasonKey, List<String> warnings, String status, String priority) {

        /** A PENDING, ROUTINE order (SAH-31 shape). */
        public NewOrder(UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, UUID orderedBy,
                        String overrideReasonKey, List<String> warnings) {
            this(admissionId, clientKey, drugKey, frequencyKey, orderedBy, overrideReasonKey, warnings, PENDING,
                    "ROUTINE");
        }

        @Override
        public String toString() {
            return "NewOrder[admissionId=" + admissionId + ", drugKey=" + drugKey + "]";
        }
    }

    /**
     * The warning lines are clinical text: {@code toString} leaves them out. {@code currentVersion} is the number of the
     * current version, {@code version} the row counter; {@code stopReason} is clinical text (null for older stops).
     */
    record OrderRow(UUID id, UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, String status,
                    UUID orderedBy, Instant orderedAt, String overrideReasonKey, List<String> warnings,
                    UUID verifiedBy, Instant verifiedAt, UUID rejectedBy, Instant rejectedAt, String rejectReasonKey,
                    UUID stoppedBy, Instant stoppedAt, long version, int currentVersion, String priority,
                    String stopReason) {

        public OrderRow {
            warnings = List.copyOf(warnings);
        }

        /** An order as SAH-31 knew it: version 1, ROUTINE, no stop reason. */
        public OrderRow(UUID id, UUID admissionId, UUID clientKey, String drugKey, String frequencyKey, String status,
                        UUID orderedBy, Instant orderedAt, String overrideReasonKey, List<String> warnings,
                        UUID verifiedBy, Instant verifiedAt, UUID rejectedBy, Instant rejectedAt,
                        String rejectReasonKey, UUID stoppedBy, Instant stoppedAt, long version) {
            this(id, admissionId, clientKey, drugKey, frequencyKey, status, orderedBy, orderedAt, overrideReasonKey,
                    warnings, verifiedBy, verifiedAt, rejectedBy, rejectedAt, rejectReasonKey, stoppedBy, stoppedAt,
                    version, 1, "ROUTINE", null);
        }

        /** Pharmacist check, queried, active or on hold: still on the chart (SAH-76, ADR-027 D1). */
        public boolean live() {
            return PENDING.equals(status) || QUERIED.equals(status) || ACTIVE.equals(status)
                    || ON_HOLD.equals(status);
        }

        @Override
        public String toString() {
            return "OrderRow[id=" + id + ", status=" + status + "]";
        }
    }

    /** A queue order with its admission's patient and current bed. */
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
