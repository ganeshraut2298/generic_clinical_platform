package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-32 and SAH-76 {@code ipd.dose_administration} and {@code ipd.dose_correction} (ADR-011 D1, ADR-027 D3, docs/database/
 * SAH-32.md and SAH-76.md): append-only dose rows and append-only correction rows. Every method is scoped to the tenant.
 * There is no update and no delete. The database sets the id, the clinic, {@code recorded_at} and {@code undo_until}
 * ({@code clock_timestamp()} and the undo setting in force); the application never sends them. A refusal by a unique
 * index, a database check, the guard trigger or a foreign key on an insert is a code fault: it is never swallowed.
 */
public interface DoseRepository {

    String GIVEN = "GIVEN";
    String KIND_UNDO = "UNDO";
    String KIND_IN_ERROR = "IN_ERROR";

    /** A plain read of the dose recorded with this client key (replay lookup), with its correction. */
    Optional<DoseRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** The latest attempt (any outcome, corrected or not) of this order and slot, with its correction. */
    Optional<DoseRow> findByOrderAndSlot(UUID tenantId, UUID orderId, Instant slotAt);

    /** A dose by id, with its correction. */
    Optional<DoseRow> findById(UUID tenantId, UUID doseId);

    /**
     * The doses of the admission with {@code from <= slot_at < to} (today's MAR), slot time order, every attempt, each
     * with its correction when it has one. The caller splits live records, struck-through history and undone records.
     */
    List<DoseRow> findBetween(UUID tenantId, UUID admissionId, Instant from, Instant to);

    /** The live (uncorrected) dose with the latest slot of each order of the admission (any day, any outcome). */
    List<DoseRow> findLastPerOrder(UUID tenantId, UUID admissionId);

    /** The recorded time of the latest live GIVEN dose of each order of the admission (any day). */
    Map<UUID, Instant> findLastGiven(UUID tenantId, UUID admissionId);

    /** The recorded time of every live GIVEN dose of the admission since {@code since}, with its order. */
    List<GivenAt> findGivenSince(UUID tenantId, UUID admissionId, Instant since);

    /** True when the order has a live GIVEN dose (any day): the STAT before verification rule. */
    boolean hasLiveGiven(UUID tenantId, UUID orderId);

    /** Inserts one dose with the one statement of docs/database/SAH-76.md; returns the id, recorded time and undo time. */
    Stored insert(UUID tenantId, NewDose dose);

    String STOP_WRISTBAND = "WRISTBAND";
    String STOP_MEDICINE = "MEDICINE";

    /**
     * Inserts one {@code ipd.dose_scan_stop} row (ADR-027 A9): a wristband or medicine mismatch of a GIVEN request.
     * {@code checkKind} is {@link #STOP_WRISTBAND} or {@link #STOP_MEDICINE}. The row holds no scanned text. The database
     * sets the id, the clinic and the time.
     */
    void insertScanStop(UUID tenantId, UUID admissionId, UUID orderId, Instant slotAt, String checkKind, UUID actor);

    /**
     * True when {@code actor} has a scan stop on this order within the last 15 minutes by the database clock
     * ({@code now()}); with {@code slotAtOrNull} only a stop of that slot counts, null (a PRN order) takes any slot.
     */
    boolean hasScanStopWithin15Minutes(UUID tenantId, UUID orderId, UUID actor, Instant slotAtOrNull);

    /** Inserts one correction (undo or in error); returns the corrected time. */
    Instant insertCorrection(UUID tenantId, NewCorrection correction);

    /** The correction made with this client key, for the replay of an undo or an in-error mark. */
    Optional<CorrectionRow> findCorrectionByClientKey(UUID tenantId, UUID clientKey);

    /**
     * The orders the MAR lists for the admission, chart order: PENDING, QUERIED, ACTIVE, ON_HOLD, and STOPPED or
     * REJECTED with the stop or reject time in {@code [from, to)} (today, IST).
     */
    List<MarHead> findMarHeads(UUID tenantId, UUID admissionId, Instant from, Instant to);

    /** The open (not acknowledged) order notices of the admission, for the badge and the "acknowledged" flag. */
    List<OpenNotice> findOpenNotices(UUID tenantId, UUID admissionId);

    /** What the database made of an insert. {@code undoUntil} is null unless the dose is GIVEN. */
    record Stored(UUID id, Instant recordedAt, Instant undoUntil) {
    }

    /** The recorded time of one live GIVEN dose. */
    record GivenAt(UUID orderId, Instant recordedAt) {
    }

    /** An open order notice: only what the badge needs. */
    record OpenNotice(UUID orderId, String priority, Instant createdAt) {
    }

    /** A correction row: who and when; the reason key is set for IN_ERROR only. */
    record CorrectionRow(UUID doseId, String kind, String reasonKey, UUID correctedBy, Instant correctedAt,
                         UUID clientKey) {
    }

    /** What is written for one correction. */
    record NewCorrection(UUID doseId, String kind, String reasonKey, UUID correctedBy, UUID clientKey) {
    }

    /**
     * An order as the MAR lists it. {@code currentVersion} is the number users see as "v2". No clinical text.
     */
    record MarHead(UUID orderId, String drugKey, String status, UUID orderedBy, Instant orderedAt, int currentVersion,
                   UUID stoppedBy, Instant stoppedAt) {
        @Override
        public String toString() {
            return "MarHead[orderId=" + orderId + ", status=" + status + "]";
        }
    }

    /**
     * The values of one dose. The typed texts (medicine scan, override note, not-given note, identity scan) are clinical or
     * free text: hidden from {@code toString}. {@code attempt} is 1 for a first record; {@code supersedesDoseId} names the
     * corrected record an attempt above 1 follows.
     */
    record NewDose(UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome, String reasonKey,
                   UUID recordedBy, UUID secondCheckBy, boolean highAlert, String identityScan,
                   Boolean identityConfirmed, int attempt, UUID supersedesDoseId, int orderVersion, String doseKind,
                   String scanMethod, String overrideReasonKey, String overrideNote, String medicineScan,
                   boolean beforeVerification, String notGivenNote) {

        /** The built shape (SAH-32): first attempt, version 1, scheduled, scan. For unit tests of older code. */
        public NewDose(UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome,
                       String reasonKey, UUID recordedBy, UUID secondCheckBy, boolean highAlert, String identityScan,
                       Boolean identityConfirmed) {
            this(admissionId, orderId, clientKey, slotAt, outcome, reasonKey, recordedBy, secondCheckBy, highAlert,
                    identityScan, identityConfirmed, 1, null, 1, "SCHEDULED", GIVEN.equals(outcome) ? "SCAN" : null,
                    null, null, null, false, null);
        }

        @Override
        public String toString() {
            return "NewDose[orderId=" + orderId + ", outcome=" + outcome + "]";
        }
    }

    /** A stored dose with its correction (null when live). The typed texts are not read back. */
    record DoseRow(UUID id, UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome,
                   String reasonKey, UUID recordedBy, UUID secondCheckBy, boolean highAlert, Instant recordedAt,
                   int attempt, int orderVersion, String doseKind, String scanMethod, String overrideReasonKey,
                   boolean beforeVerification, Instant undoUntil, CorrectionRow correction) {

        /** The built shape (SAH-32): a live dose of version 1. */
        public DoseRow(UUID id, UUID admissionId, UUID orderId, UUID clientKey, Instant slotAt, String outcome,
                       String reasonKey, UUID recordedBy, UUID secondCheckBy, boolean highAlert, Instant recordedAt) {
            this(id, admissionId, orderId, clientKey, slotAt, outcome, reasonKey, recordedBy, secondCheckBy, highAlert,
                    recordedAt, 1, 1, "SCHEDULED", GIVEN.equals(outcome) ? "SCAN" : null, null, false, null, null);
        }

        /** True when no correction (undo or in error) names this dose. */
        public boolean live() {
            return correction == null;
        }

        public boolean given() {
            return GIVEN.equals(outcome);
        }

        /** The same dose with a correction attached (the answer of an undo or an in-error mark). */
        public DoseRow corrected(CorrectionRow row) {
            return new DoseRow(id, admissionId, orderId, clientKey, slotAt, outcome, reasonKey, recordedBy,
                    secondCheckBy, highAlert, recordedAt, attempt, orderVersion, doseKind, scanMethod,
                    overrideReasonKey, beforeVerification, undoUntil, row);
        }

        @Override
        public String toString() {
            return "DoseRow[id=" + id + ", outcome=" + outcome + "]";
        }
    }

}
