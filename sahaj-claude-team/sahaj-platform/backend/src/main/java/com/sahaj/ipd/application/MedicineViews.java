package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MarViews.DoseView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-31 request and response shapes of {@code docs/api/ipd.yaml} (EMR sheet, medicine orders, pharmacist check).
 * Names, allergy text, findings and the PIN are personal or clinical data: every record that carries them hides them
 * from {@code toString}, so they cannot reach a log line by accident.
 */
public final class MedicineViews {

    private MedicineViews() {
    }

    /** One option of a fixed select: the key sent to the server and the label shown. */
    public record KeyLabel(String key, String label) {
    }

    public record DrugOption(String key, String name, String route, String highAlert) {
    }

    /** The identity bar. Name, allergy text and UHID are personal or clinical: hidden from {@code toString}. */
    public record EmrPatient(UUID patientId, String name, String uhid, Integer age, String sex, String bedCode,
                             String allergyText, String allergyStatus) {

        @Override
        public String toString() {
            return "EmrPatient[patientId=" + patientId + "]";
        }
    }

    /** One order of the list "Medicines on chart". Names are staff names. */
    public record MedicineOrderRow(UUID id, String drugKey, String drugName, String route, String highAlert,
                                   String frequencyKey, String frequencyLabel, String status, String orderedByName,
                                   Instant orderedAt, KeyLabel overrideReason, String verifiedByName,
                                   Instant verifiedAt, KeyLabel rejectReason, String rejectedByName,
                                   Instant rejectedAt, String stoppedByName, Instant stoppedAt, boolean canStop,
                                   DoseView lastDose) {

        @Override
        public String toString() {
            return "MedicineOrderRow[id=" + id + ", status=" + status + "]";
        }
    }

    public record EmrSheet(UUID admissionId, String status, String state, Instant asOf, EmrPatient patient,
                           String admittingDoctorName, String diagnosis, int dayNumber,
                           List<MedicineOrderRow> orders, boolean canOrder, String orderLockReason,
                           List<DrugOption> drugs, List<KeyLabel> frequencies, List<KeyLabel> overrideReasons) {

        @Override
        public String toString() {
            return "EmrSheet[admissionId=" + admissionId + "]";
        }
    }

    /** One finding. The text may hold the allergy text: hidden from {@code toString}. */
    public record OrderFinding(String code, String severity, String otherDrugKey, String text) {

        @Override
        public String toString() {
            return "OrderFinding[code=" + code + "]";
        }
    }

    public record OrderCheckView(String allergyStatus, List<OrderFinding> hardStops, List<OrderFinding> warnings,
                                 String checkToken) {

        @Override
        public String toString() {
            return "OrderCheckView[allergyStatus=" + allergyStatus + "]";
        }
    }

    /** Request body of {@code POST /admissions/{id}/medicine-orders}; every field is text so the server judges it. */
    public record MedicineOrderInput(String clientKey, String drugKey, String frequencyKey, String overrideReasonKey,
                                     String checkToken) {

        @Override
        public String toString() {
            return "MedicineOrderInput[redacted]";
        }
    }

    /** Outcome PLACED, BLOCKED or NEEDS_REASON. */
    public record MedicineOrderAnswer(String outcome, boolean replayed, OrderCheckView check,
                                      MedicineOrderRow order, EmrSheet sheet) {

        @Override
        public String toString() {
            return "MedicineOrderAnswer[outcome=" + outcome + ", replayed=" + replayed + "]";
        }
    }

    public record PharmacistCheckRow(UUID orderId, UUID admissionId, String drugKey, String drugName,
                                     String highAlert, String frequencyKey, String frequencyLabel,
                                     EmrPatient patient, String orderedByName, Instant orderedAt,
                                     KeyLabel overrideReason, OrderCheckView check, boolean canVerify,
                                     String verifyLockReason, boolean canReject) {

        @Override
        public String toString() {
            return "PharmacistCheckRow[orderId=" + orderId + "]";
        }
    }

    public record PharmacistChecks(String state, Instant asOf, int waiting, String verifierName,
                                   List<KeyLabel> rejectReasons, List<PharmacistCheckRow> items,
                                   String nextCursor) {

        @Override
        public String toString() {
            return "PharmacistChecks[waiting=" + waiting + "]";
        }
    }

    /** Counts only (ADR-005 D3). */
    public record PharmacistChecksSummary(int waiting, String changeToken, Instant asOf) {
    }

    /** Request body of verify. The PIN is never logged: hidden from {@code toString}. */
    public record VerifyOrderInput(String pin, String checkToken) {

        @Override
        public String toString() {
            return "VerifyOrderInput[redacted]";
        }
    }

    public record RejectOrderInput(String reasonKey) {
    }

    public record ActionOutcome(UUID orderId, String drugName, String outcome, boolean replayed, String actorName,
                                KeyLabel rejectReason) {

        @Override
        public String toString() {
            return "ActionOutcome[orderId=" + orderId + ", outcome=" + outcome + "]";
        }
    }

    /** The answer of verify and reject: what happened and the first page of the queue now. */
    public record PharmacistActionResult(ActionOutcome result, PharmacistChecks checks) {
    }
}
