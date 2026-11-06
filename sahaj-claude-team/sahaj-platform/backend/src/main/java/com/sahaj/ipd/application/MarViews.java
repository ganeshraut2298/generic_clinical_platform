package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.EmrPatient;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-32 request and response shapes of {@code docs/api/ipd.yaml} (the MAR and giving a dose). Names, the scanned
 * text and the PIN are personal or clinical: every record that carries them hides them from {@code toString}, so they
 * cannot reach a log line by accident.
 */
public final class MarViews {

    private MarViews() {
    }

    /** One recorded dose. The names are staff names (on screen only). */
    public record DoseView(UUID id, Instant slotAt, String outcome, KeyLabel reason, Instant recordedAt,
                           String recordedByName, String secondCheckByName) {

        @Override
        public String toString() {
            return "DoseView[id=" + id + ", outcome=" + outcome + "]";
        }
    }

    /** One dose time of today. {@code state} is a {@code DoseSlots.State} name. */
    public record SlotView(Instant slotAt, String state, Integer lateHours, boolean canGive, DoseView dose) {
    }

    /** One order block of the MAR. */
    public record MarOrderView(UUID orderId, String drugKey, String drugName, String route, String highAlert,
                               String frequencyKey, String frequencyLabel, String orderedByName, String status,
                               List<SlotView> slots) {

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

    /** The tab "Medicines (MAR)". The patient and the orders are clinical data: hidden from {@code toString}. */
    public record Mar(UUID admissionId, String status, String state, Instant asOf, EmrPatient patient, int doseCount,
                      boolean canGive, String giveLockReason, List<MarOrderView> orders,
                      List<SecondNurseOption> secondNurses, List<KeyLabel> notGivenReasons) {

        @Override
        public String toString() {
            return "Mar[admissionId=" + admissionId + "]";
        }
    }

    /**
     * Request body of {@code POST /medicine-orders/{id}/doses}. Every field is loose (text or {@code Object}) so that
     * the server is the only judge of the format and gives the contract's own message. The wristband text and the PIN
     * are never logged: hidden from {@code toString}.
     */
    public record DoseInput(String clientKey, String slotAt, String outcome, String wristband,
                            Object identityConfirmed, String secondNurseId, String secondNursePin, String reasonKey) {

        /** The same request without the PIN, for the locked transaction (which never sees it). */
        DoseInput withoutPin() {
            return new DoseInput(clientKey, slotAt, outcome, wristband, identityConfirmed, secondNurseId, null,
                    reasonKey);
        }

        @Override
        public String toString() {
            return "DoseInput[redacted]";
        }
    }

    /** Answer of the dose endpoint: 201 recorded, 200 replay. */
    public record DoseRecorded(boolean replayed, DoseView dose, Mar mar) {

        @Override
        public String toString() {
            return "DoseRecorded[replayed=" + replayed + "]";
        }
    }
}
