package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-32 {@code ipd.dose_administration} (ADR-011 D1, docs/database/SAH-32.md): one append-only row per order and
 * slot. Every method is scoped to the tenant. There is no update and no delete. The database sets the id, the clinic
 * and {@code recorded_at} ({@code clock_timestamp()}); the application never sends them. A refusal by a unique index,
 * a database check, the guard trigger or a foreign key on the insert is a code fault: it is never swallowed.
 */
public interface DoseRepository {

    String GIVEN = "GIVEN";

    /** A plain read of the dose recorded with this client key (replay lookup). */
    Optional<DoseRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** A plain read of the dose of this order and slot (the "already recorded" rule). */
    Optional<DoseRow> findByOrderAndSlot(UUID tenantId, UUID orderId, Instant slotAt);

    /** The doses of the admission with {@code from <= slot_at < to} (today's MAR), slot time order. */
    List<DoseRow> findBetween(UUID tenantId, UUID admissionId, Instant from, Instant to);

    /** The dose with the latest slot of each order of the admission (any day, any outcome): the EMR "Last dose". */
    List<DoseRow> findLastPerOrder(UUID tenantId, UUID admissionId);

    /** Inserts one dose with the one statement of docs/database/SAH-32.md; returns the id and recorded time. */
    Stored insert(UUID tenantId, NewDose dose);

    /** What the database made of an insert. */
    record Stored(UUID id, Instant recordedAt) {
    }

    /** The values of one dose. The scanned text is free text typed at the bedside: hidden from {@code toString}. */
    record NewDose(UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome, String reasonKey,
                   UUID recordedBy, UUID secondCheckBy, boolean highAlert, String identityScan,
                   Boolean identityConfirmed) {

        @Override
        public String toString() {
            return "NewDose[orderId=" + orderId + ", outcome=" + outcome + "]";
        }
    }

    /** A stored dose. The scanned text is not read back (it equals the UHID and is never needed). */
    record DoseRow(UUID id, UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome,
                   String reasonKey, UUID recordedBy, UUID secondCheckBy, boolean highAlert, Instant recordedAt) {

        @Override
        public String toString() {
            return "DoseRow[id=" + id + ", outcome=" + outcome + "]";
        }
    }
}
