package com.sahaj.opd.application.emr.finish;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.opd.application.emr.EmrViews.RecordView;
import com.sahaj.opd.application.emr.specialty.ProfileEffects;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies and answers of the finish and sign of the Doctor EMR (docs/api/opd.yaml 0.6.0, ADR-025 D1 to D10). The
 * bodies are closed (the strict JSON module refuses unknown fields); their fields are nullable so that a missing field is
 * answered by the service with a field error. Answers hold ids, dates, counts, fixed words and screen text: never a clinical
 * value of the record.
 */
public final class FinishModels {

    private FinishModels() {
    }

    // ---- inputs ----

    public record AdmissionRequestInput(String admissionType, Integer plannedStayDays, String note) {

        /** The note is the doctor's words for the ward: never in {@code toString}. */
        @Override
        public String toString() {
            return "AdmissionRequestInput[" + admissionType + "]";
        }
    }

    public record ErOutcome(String kind, String referTo, String referNote, UUID opdPatientId,
                            AdmissionRequestInput admission) {

        @Override
        public String toString() {
            return "ErOutcome[" + kind + "]";
        }
    }

    public record IpdOrderReason(UUID rowId, String overrideReasonKey, String checkToken) {

        @Override
        public String toString() {
            return "IpdOrderReason[rowId=" + rowId + "]";
        }
    }

    public record DentalChoice(String nextInterval) {
    }

    public record NextSlot(LocalDate date, String time) {
    }

    public record PhysioChoice(NextSlot nextSlot) {
    }

    public record Share(Boolean whatsappPrescription) {
    }

    public record FinishChoices(ErOutcome erOutcome, List<IpdOrderReason> ipdOrderReasons, DentalChoice dental,
                                PhysioChoice physio, Boolean confirmAllergyRemoval, Share share) {

        /** No choices made. */
        public static FinishChoices none() {
            return new FinishChoices(null, null, null, null, null, null);
        }

        public boolean removeAllergies() {
            return Boolean.TRUE.equals(confirmAllergyRemoval);
        }
    }

    public record SignInput(Long revision, FinishChoices choices) {
    }

    public record EditHoldInput(Boolean takeOver) {
    }

    public record AddendumInput(String reason, String text) {

        /** Typed by the doctor: never in a log line. */
        @Override
        public String toString() {
            return "AddendumInput[redacted]";
        }
    }

    // ---- answers ----

    public record EditHold(UUID holderMembershipId, String holderName, Instant expiresAt, boolean mine,
                           boolean canTakeOver) {

        @Override
        public String toString() {
            return "EditHold[mine=" + mine + "]";
        }
    }

    public record Addendum(UUID id, String reason, String text, Instant createdAt, String createdByName) {

        @Override
        public String toString() {
            return "Addendum[id=" + id + "]";
        }
    }

    public record AdmissionRequestView(UUID id, String status, String admissionType, Integer plannedStayDays,
                                       Instant createdAt, UUID admissionId, String ipNumber) {
    }

    public record AdmissionState(String state, UUID requestId, UUID admissionId, String ipNumber) {

        public static AdmissionState none() {
            return new AdmissionState("NONE", null, null, null);
        }
    }

    public record FreeSlot(LocalDate date, String time) {
    }

    public record FinishBlocker(String key, String message, List<String> sectionKeys, List<UUID> rowIds) {

        public static FinishBlocker of(String key, String message) {
            return new FinishBlocker(key, message, List.of(), List.of());
        }
    }

    public record FinishBillLine(String name, int qty, long unitPaise, long amountPaise, String state, String source) {
    }

    /**
     * A row of the 409 ipd-order-not-placed body (opd.yaml 0.6.3, DEC-051 D3). No frequency, no check token and none of the
     * SAH-31 lines (they can hold the patient's allergy text): {@code checkKind} is a SAH-31 finding code and
     * {@code reason} one of two fixed texts.
     */
    public record IpdOrderNotPlacedRow(UUID rowId, String drugName, String outcome, String checkKind, String reason) {

        @Override
        public String toString() {
            return "IpdOrderNotPlacedRow[rowId=" + rowId + ", outcome=" + outcome + "]";
        }
    }

    public record FinishOrderCheck(UUID rowId, String drugName, String frequencyLabel, String outcome,
                                   List<String> texts, String checkToken) {

        @Override
        public String toString() {
            return "FinishOrderCheck[rowId=" + rowId + ", outcome=" + outcome + "]";
        }
    }

    public record StageEffect(String outcome, String to) {
    }

    public record FollowUpEffect(LocalDate dueDate, String interval, FreeSlot firstFreeSlot, boolean ignoredInterval) {
    }

    public record PatientRecordEffect(List<String> fields, List<String> removals, boolean conflict) {

        @Override
        public String toString() {
            return "PatientRecordEffect[fields=" + fields + ", conflict=" + conflict + "]";
        }
    }

    public record EmergencyEffect(int triageLevel, String triageLevelName, String outcome) {
    }

    /**
     * A document the client prints by itself after the sign (docs/api/opd.yaml 0.6.1, EmrFinishPrint; ADR-025 D19). The
     * constants are in print order: a list of prints is always sorted by {@link #ordinal()}.
     */
    public enum FinishPrint {
        PRESCRIPTION, CYCLE_RECORD, CHEMO_CALENDAR, ADMISSION_LETTER, PREOP_SHEET
    }

    /**
     * What the finish check says the sign will do. {@code profile} is the record's profile block (null without a profile)
     * and {@code prints} the documents that will print after the sign (0.6.1).
     */
    public record Effects(StageEffect stage, FollowUpEffect followUp, List<FinishBillLine> billLines,
                          long billTotalPaise, PatientRecordEffect patientRecord, List<FinishOrderCheck> ipdOrders,
                          String investigationsAdvised, EmergencyEffect emergency, ProfileEffects profile,
                          List<FinishPrint> prints) {

        @Override
        public String toString() {
            return "Effects[lines=" + (billLines == null ? 0 : billLines.size()) + "]";
        }
    }

    public record ShareState(boolean available, String reason) {
    }

    public record Shares(ShareState whatsapp, ShareState abha) {

        public static Shares unavailable() {
            return new Shares(new ShareState(false, "Needs the WhatsApp service"),
                    new ShareState(false, "Needs the ABDM service"));
        }
    }

    public record FinishCheck(boolean ready, List<FinishBlocker> blockers, String encounterType, Effects effects,
                              List<String> info, Shares share) {
    }

    // ---- the outcome of the sign (stored as sign_result: ids, dates and counts only) ----

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FollowUpOutcome(UUID id, LocalDate dueDate, LocalDate appointmentDate, String appointmentTime,
                                  boolean noFreeSlot) {
    }

    public record StageOutcome(String outcome, String to) {
    }

    public record IpdOrderPlaced(UUID rowId, UUID orderId) {
    }

    public record RoundOutcomeView(UUID admissionId, LocalDate roundDate) {
    }

    public record EmergencyOutcome(String outcome, UUID admissionRequestId, UUID opdVisitId, Integer tokenNo) {
    }

    public record CourseOutcome(UUID sittingDoneId, UUID nextSittingId, LocalDate nextAppointmentDate,
                                String nextAppointmentTime, boolean packageComplete) {
    }

    /**
     * What the sign did. {@code prints} (0.6.1) is computed on every answer and never stored: the stored copy has it null
     * (left out of the JSON) and the answer sets it with {@link #withPrints}.
     */
    public record SignOutcome(StageOutcome stage, FollowUpOutcome followUp, int billLines, long billTotalPaise,
                              List<IpdOrderPlaced> ipdOrders, RoundOutcomeView round, EmergencyOutcome emergency,
                              String patientRecord, CourseOutcome course,
                              @JsonInclude(JsonInclude.Include.NON_NULL) List<FinishPrint> prints) {

        /** The stored shape: no prints. */
        public SignOutcome(StageOutcome stage, FollowUpOutcome followUp, int billLines, long billTotalPaise,
                           List<IpdOrderPlaced> ipdOrders, RoundOutcomeView round, EmergencyOutcome emergency,
                           String patientRecord, CourseOutcome course) {
            this(stage, followUp, billLines, billTotalPaise, ipdOrders, round, emergency, patientRecord, course, null);
        }

        public SignOutcome withPrints(List<FinishPrint> computed) {
            return new SignOutcome(stage, followUp, billLines, billTotalPaise, ipdOrders, round, emergency,
                    patientRecord, course, computed);
        }
    }

    public record SignResult(boolean replayed, SignOutcome outcome, RecordView record) {
    }
}
