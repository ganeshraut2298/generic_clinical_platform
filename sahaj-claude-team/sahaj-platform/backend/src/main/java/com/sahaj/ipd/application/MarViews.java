package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-32 and SAH-76 request and response shapes of {@code docs/api/ipd.yaml} 0.4.0 (the MAR, giving a dose, undo and
 * recorded in error). Names, the scanned text, the notes and the PIN are personal or clinical: every record that
 * carries them hides them from {@code toString}, so they cannot reach a log line by accident.
 */
public final class MarViews {

    private MarViews() {
    }

    /** One correction of a dose (contract {@code DoseCorrectionRow}). The name is a staff name (on screen only). */
    public record CorrectionView(String kind, KeyLabel reason, String correctedByName, Instant correctedAt) {
        @Override
        public String toString() {
            return "CorrectionView[kind=" + kind + "]";
        }
    }

    /** One recorded dose (contract {@code DoseRow}). The names are staff names (on screen only). */
    public record DoseView(UUID id, Instant slotAt, String outcome, KeyLabel reason, Instant recordedAt,
                           String recordedByName, String secondCheckByName, int orderVersion, String method,
                           KeyLabel overrideReason, boolean beforeVerification, CorrectionView correction,
                           int undoSecondsLeft, boolean canUndo, boolean canMarkInError) {

        /** A dose with no correction and no action flags (the EMR "Last dose" line, an older reader). */
        public DoseView(UUID id, Instant slotAt, String outcome, KeyLabel reason, Instant recordedAt,
                        String recordedByName, String secondCheckByName) {
            this(id, slotAt, outcome, reason, recordedAt, recordedByName, secondCheckByName, 1,
                    "GIVEN".equals(outcome) ? "SCAN" : null, null, false, null, 0, false, false);
        }

        @Override
        public String toString() {
            return "DoseView[id=" + id + ", outcome=" + outcome + "]";
        }
    }

    /** One dose time of today. {@code state} is a {@code DoseSlots.State} name. */
    public record SlotView(Instant slotAt, String state, Integer lateHours, boolean canGive, DoseView dose,
                           int orderVersion, Integer lateMinutes, String stateBy, Instant stateAt,
                           List<DoseView> history, boolean beforeVerification) {
    }

    /** The current (or noticed) version of an order (contract {@code OrderDetails}). The indication is clinical text. */
    public record OrderDetailsView(String kind, BigDecimal doseAmount, String doseUnit, String routeKey,
                                   String routeLabel, String frequencyKey, String frequencyLabel, Instant startAt,
                                   Instant endAt, Integer durationDays, String indication, String priority,
                                   Integer prnMinIntervalMinutes, Integer prnMaxPer24h, Boolean pharmacistChecked) {
        @Override
        public String toString() {
            return "OrderDetailsView[kind=" + kind + "]";
        }
    }

    /** The open hold of an order (contract {@code OrderHold}); {@code reason} is always null on the MAR. */
    public record HoldView(Instant heldAt, String heldByName, String reason) {
        @Override
        public String toString() {
            return "HoldView[heldAt=" + heldAt + "]";
        }
    }

    /** The PRN line of a PRN order (contract {@code PrnLine}). The indication is clinical text. */
    public record PrnView(String indication, int minIntervalMinutes, int maxPer24h, int givenLast24h,
                          Instant lastGivenAt, Instant nextAllowedAt, String lockReason, boolean canGive) {
        @Override
        public String toString() {
            return "PrnView[canGive=" + canGive + "]";
        }
    }

    /** One order block of the MAR. */
    public record MarOrderView(UUID orderId, String drugKey, String drugName, String route, String highAlert,
                               String frequencyKey, String frequencyLabel, String orderedByName, String status,
                               List<SlotView> slots, int orderVersion, boolean changed, OrderDetailsView details,
                               HoldView hold, PrnView prn, boolean hasMedicineCode, Instant lastGivenAt,
                               boolean acknowledged, boolean folded) {
        @Override
        public String toString() {
            return "MarOrderView[orderId=" + orderId + ", status=" + status + "]";
        }
    }

    /** One option of "Checked by". The name is a staff name (on screen only). */
    public record SecondNurseOption(UUID membershipId, String name) {
        @Override
        public String toString() {
            return "SecondNurseOption[membershipId=" + membershipId + "]";
        }
    }

    /** The header counters of the medicines area. */
    public record Counters(int late, int dueNow, int given) {
    }

    /** The windows in force for the patient and the undo window (contract {@code MarWindows}). */
    public record Windows(int beforeMinutes, int afterMinutes, int statMinutes, int undoSeconds) {
    }

    /** The badge of the Orders tab (contract {@code NoticeCount}). */
    public record NoticeCountView(int count, int stat, int overdue) {
    }

    /** The tab "Medicines (MAR)". The patient and the orders are clinical data: hidden from {@code toString}. */
    public record Mar(UUID admissionId, String status, String state, Instant asOf, EmrPatient patient, int doseCount,
                      boolean canGive, String giveLockReason, List<MarOrderView> orders,
                      List<SecondNurseOption> secondNurses, List<KeyLabel> notGivenReasons, Counters counters,
                      int foldedCount, Windows windows, List<KeyLabel> overrideReasons, List<KeyLabel> inErrorReasons,
                      NoticeCountView ordersToAcknowledge) {
        @Override
        public String toString() {
            return "Mar[admissionId=" + admissionId + "]";
        }
    }

    /** The manual override of the Give sheet: reason key and the nurse's note (clinical text). */
    public record OverrideInput(String reasonKey, String note) {
        @Override
        public String toString() {
            return "OverrideInput[redacted]";
        }
    }

    /**
     * Request body of {@code POST /medicine-orders/{id}/doses}. Every field is loose (text or {@code Object}) so that
     * the server is the only judge of the format and gives the contract's own message. The wristband text, the medicine
     * code, the notes and the PIN are never logged: hidden from {@code toString}.
     */
    public record DoseInput(String clientKey, String slotAt, Object orderVersion, String outcome, String wristband,
                            String medicineCode, OverrideInput override, String note, Object identityConfirmed,
                            String secondNurseId, String secondNursePin, String reasonKey) {

        /** The same request without the PIN, for the locked transaction (which never sees it). */
        DoseInput withoutPin() {
            return new DoseInput(clientKey, slotAt, orderVersion, outcome, wristband, medicineCode, override, note,
                    identityConfirmed, secondNurseId, null, reasonKey);
        }

        @Override
        public String toString() {
            return "DoseInput[redacted]";
        }
    }

    /** Request body of undo and recorded in error. */
    public record CorrectionInput(String clientKey, String reasonKey) {
    }

    /** Answer of the dose endpoints: 201 recorded, 200 replay, 200 corrected. */
    public record DoseRecorded(boolean replayed, DoseView dose, Mar mar) {
        @Override
        public String toString() {
            return "DoseRecorded[replayed=" + replayed + "]";
        }
    }
}
