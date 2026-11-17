package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MarViews.DoseView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-31 request and response shapes of {@code docs/api/ipd.yaml} (EMR sheet, medicine orders, pharmacist check),
 * widened by SAH-76 (0.4.0: order versions, change, hold, query, review after dose). Names, allergy text, findings, the
 * PIN and every clinical text (indication, reasons, question, reply) are personal or clinical data: every record that
 * carries them hides them from {@code toString}, so they cannot reach a log line by accident.
 */
public final class MedicineViews {

    private MedicineViews() {
    }

    /** One option of a fixed select: the key sent to the server and the label shown. */
    public record KeyLabel(String key, String label) {
    }

    /** One option of the select "Medicine". SAH-76: the default route, the allowed dose units and the time-critical flag. */
    public record DrugOption(String key, String name, String route, String highAlert, String routeKey,
                             List<String> doseUnits, boolean timeCritical) {
    }

    /** The identity bar. Name, allergy text and UHID are personal or clinical: hidden from {@code toString}. */
    public record EmrPatient(UUID patientId, String name, String uhid, Integer age, String sex, String bedCode,
                             String allergyText, String allergyStatus) {

        @Override
        public String toString() {
            return "EmrPatient[patientId=" + patientId + "]";
        }
    }

    /** SAH-76: the current (or noticed) version of an order, as the screens show it. The indication is clinical text. */
    public record OrderDetails(String kind, BigDecimal doseAmount, String doseUnit, String routeKey,
                               String routeLabel, String frequencyKey, String frequencyLabel, Instant startAt,
                               Instant endAt, Integer durationDays, String indication, String priority,
                               Integer prnMinIntervalMinutes, Integer prnMaxPer24h, Boolean pharmacistChecked) {

        @Override
        public String toString() {
            return "OrderDetails[kind=" + kind + ", priority=" + priority + "]";
        }
    }

    /** SAH-76: one "Changed: label: old to new" line. The indication line shows the texts (clinical, on screen only). */
    public record OrderChangeLine(String field, String label, String before, String after) {

        @Override
        public String toString() {
            return "OrderChangeLine[field=" + field + "]";
        }
    }

    /** SAH-76: the open hold. The reason is clinical text (null for roles without clinical access). */
    public record OrderHold(Instant heldAt, String heldByName, String reason) {

        @Override
        public String toString() {
            return "OrderHold[heldAt=" + heldAt + "]";
        }
    }

    /** SAH-76-2: the pharmacist's question and the doctor's reply: clinical text, OWNER, DOCTOR and PHARMACIST only. */
    public record OrderQuery(String question, String askedByName, Instant askedAt, String reply,
                             String repliedByName, Instant repliedAt) {

        @Override
        public String toString() {
            return "OrderQuery[askedAt=" + askedAt + "]";
        }
    }

    /** SAH-76-5: what the nurses did with the order, for the doctor. */
    public record OrderExecution(int notGivenToday, Instant nextDueAt, int lateCount, boolean acknowledged,
                                 String acknowledgedByName, Instant acknowledgedAt) {
    }

    /** One order of the list "Medicines on chart". Names are staff names. */
    public record MedicineOrderRow(UUID id, String drugKey, String drugName, String route, String highAlert,
                                   String frequencyKey, String frequencyLabel, String status, String orderedByName,
                                   Instant orderedAt, KeyLabel overrideReason, String verifiedByName,
                                   Instant verifiedAt, KeyLabel rejectReason, String rejectedByName,
                                   Instant rejectedAt, String stoppedByName, Instant stoppedAt, boolean canStop,
                                   DoseView lastDose, int orderVersion, OrderDetails details,
                                   List<OrderChangeLine> changes, String changeReason, OrderHold hold,
                                   String stopReason, OrderQuery query, boolean canChange, boolean canHold,
                                   boolean canResume, boolean canReply, OrderExecution execution) {

        @Override
        public String toString() {
            return "MedicineOrderRow[id=" + id + ", status=" + status + "]";
        }
    }

    public record EmrSheet(UUID admissionId, String status, String state, Instant asOf, EmrPatient patient,
                           String admittingDoctorName, String diagnosis, int dayNumber,
                           List<MedicineOrderRow> orders, boolean canOrder, String orderLockReason,
                           List<DrugOption> drugs, List<KeyLabel> frequencies, List<KeyLabel> overrideReasons,
                           List<KeyLabel> routes, List<KeyLabel> doseUnits, List<KeyLabel> priorities,
                           int statWindowMinutes) {

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

    /**
     * Request body of {@code POST /admissions/{id}/medicine-orders}; every text field is text so the server judges it.
     * SAH-76: dose, unit, route, start, duration, indication, priority and the PRN limits. The indication is clinical
     * text: {@code toString} hides everything.
     */
    public record MedicineOrderInput(String clientKey, String drugKey, String frequencyKey, String overrideReasonKey,
                                     String checkToken, BigDecimal doseAmount, String doseUnit, String routeKey,
                                     String startAt, Integer durationDays, String indication, String priority,
                                     Integer prnMinIntervalMinutes, Integer prnMaxPer24h) {

        /** The SAH-31 shape: none of the SAH-76 fields (the port path and the unit tests of the checks). */
        public MedicineOrderInput(String clientKey, String drugKey, String frequencyKey, String overrideReasonKey,
                                  String checkToken) {
            this(clientKey, drugKey, frequencyKey, overrideReasonKey, checkToken, null, null, null, null, null, null,
                    null, null, null);
        }

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
                                     String verifyLockReason, boolean canReject, String status, int orderVersion,
                                     OrderDetails details, List<OrderChangeLine> changes, String changeReason,
                                     OrderQuery query, boolean canQuery) {

        @Override
        public String toString() {
            return "PharmacistCheckRow[orderId=" + orderId + "]";
        }
    }

    public record PharmacistChecks(String state, Instant asOf, int waiting, String verifierName,
                                   List<KeyLabel> rejectReasons, List<PharmacistCheckRow> items,
                                   String nextCursor, int queried, int reviewsOpen) {

        @Override
        public String toString() {
            return "PharmacistChecks[waiting=" + waiting + "]";
        }
    }

    /** Counts only (ADR-005 D3). */
    public record PharmacistChecksSummary(int waiting, String changeToken, Instant asOf, int queried,
                                          int reviewsOpen) {
    }

    /** Request body of verify. The PIN is never logged: hidden from {@code toString}. */
    public record VerifyOrderInput(String pin, String checkToken, Integer orderVersion) {

        /** The SAH-31 shape, without the version (unit tests of the earlier checks). */
        public VerifyOrderInput(String pin, String checkToken) {
            this(pin, checkToken, null);
        }

        @Override
        public String toString() {
            return "VerifyOrderInput[redacted]";
        }
    }

    public record RejectOrderInput(String reasonKey) {
    }

    /** SAH-76 (ADR-027 D4): the body of stop. The reason is clinical text. */
    public record StopOrderInput(String clientKey, Integer orderVersion, String reason) {

        @Override
        public String toString() {
            return "StopOrderInput[redacted]";
        }
    }

    /** SAH-76: the body of hold. The reason is clinical text. */
    public record HoldOrderInput(String clientKey, Integer orderVersion, String reason) {

        @Override
        public String toString() {
            return "HoldOrderInput[redacted]";
        }
    }

    public record ResumeOrderInput(String clientKey, Integer orderVersion) {
    }

    /** SAH-76: the full new values of the editable fields. Reason and indication are clinical text. */
    public record ChangeOrderInput(String clientKey, Integer orderVersion, String reason, BigDecimal doseAmount,
                                   String doseUnit, String routeKey, String frequencyKey, String startAt,
                                   Integer durationDays, String indication, String priority,
                                   Integer prnMinIntervalMinutes, Integer prnMaxPer24h) {

        @Override
        public String toString() {
            return "ChangeOrderInput[redacted]";
        }
    }

    /** SAH-76-2: the pharmacist's question (clinical text). */
    public record QueryOrderInput(String clientKey, Integer orderVersion, String question) {

        @Override
        public String toString() {
            return "QueryOrderInput[redacted]";
        }
    }

    /** SAH-76-2: the doctor's reply (clinical text). */
    public record QueryReplyInput(String clientKey, Integer orderVersion, String reply) {

        @Override
        public String toString() {
            return "QueryReplyInput[redacted]";
        }
    }

    public record ActionOutcome(UUID orderId, String drugName, String outcome, boolean replayed, String actorName,
                                KeyLabel rejectReason) {

        @Override
        public String toString() {
            return "ActionOutcome[orderId=" + orderId + ", outcome=" + outcome + "]";
        }
    }

    /** The answer of verify, reject and query: what happened and the first page of the queue now. */
    public record PharmacistActionResult(ActionOutcome result, PharmacistChecks checks) {
    }

    /** SAH-76-2: one open item of "Review after dose". Names and allergy text on screen only. */
    public record PharmacistReviewRow(UUID id, UUID orderId, UUID admissionId, int orderVersion, String drugKey,
                                      String drugName, OrderDetails details, EmrPatient patient, UUID doseId,
                                      Instant givenAt, String givenByName, String orderStatus, OrderCheckView check,
                                      boolean canMarkReviewed, boolean canQuery) {

        @Override
        public String toString() {
            return "PharmacistReviewRow[id=" + id + "]";
        }
    }

    public record PharmacistReviewPage(Instant asOf, int open, List<PharmacistReviewRow> items, String nextCursor) {
    }
}
