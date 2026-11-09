package com.sahaj.ipd.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import com.sahaj.ipd.domain.EscalationState;
import com.sahaj.ipd.domain.MedicineSafetyCheck;
import com.sahaj.ipd.domain.card.ResolvedCardSetup;
import com.sahaj.ipd.domain.card.VitalMarks.Mark;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SAH-23 response and request shapes of {@code docs/api/ipd.yaml} (admission rules, options, list, detail). */
public final class AdmissionViews {

    private AdmissionViews() {
    }

    public record DepositAmounts(Long planned, Long emergency, Long daycare, Long maternity) {
    }

    public record CategoryAdds(Long gen, Long semi, Long pvt, Long icu) {
    }

    /** Request body of {@code PUT /admission-rules}; fields are boxed so that a missing one is a 400, not a 0. */
    public record RulesInput(DepositAmounts depositPaise, CategoryAdds categoryAddPaise,
                             Boolean emergencyWithoutDeposit, Integer alertPercent) {
    }

    public record Rules(DepositAmounts depositPaise, CategoryAdds categoryAddPaise, boolean emergencyWithoutDeposit,
                        int alertPercent, boolean confirmed) {
    }

    /** Request body of {@code POST /admissions/{id}/deposits}. */
    public record DepositInput(Long amountPaise, String mode) {
    }

    public record DoctorOption(UUID membershipId, String name) {
    }

    public record BedOption(UUID id, String code, String wardName, String categoryKey, String categoryName,
                            long ratePaise) {
    }

    /** SAH-28: {@code emergencyCase} is set only when the request had an emergency case id. */
    public record Options(List<DoctorOption> doctors, List<BedOption> beds, Rules rules,
                          EmergencyViews.Handoff emergencyCase) {

        public Options(List<DoctorOption> doctors, List<BedOption> beds, Rules rules) {
            this(doctors, beds, rules, null);
        }
    }

    /** One row of the Admitted list. The reason is not part of it. */
    public record Summary(UUID id, String ipNumber, UUID patientId, String patientName, Integer age, String sex,
                          String doctorName, String bedCode, String categoryName, String wardName,
                          String admissionType, String payer, int dayNumber, long depositPaise,
                          long minimumDepositPaise, String depositState, Instant admittedAt,
                          long runningBillPaise, boolean depositLow, boolean goingHomeToday) {

        @Override
        public String toString() {
            return "Summary[id=" + id + ", ipNumber=" + ipNumber + "]";
        }
    }

    /**
     * SAH-28: {@code emergency} holds the counts for the second tile and the tab. SAH-33: {@code safety} holds the Safety
     * tab count and the banner for OWNER, DOCTOR and NURSE; it is null for every other role. SAH-36 (ADR-013 D1):
     * {@code asOf} is the database clock of the answer, {@code wards} the options of the ward select and {@code card}
     * the card abilities of the caller, once for the whole list. SAH-37 (ADR-015 D4): {@code cardSetup} is the published
     * setup the Cards view draws, read in the same transaction as the rows.
     */
    public record AdmissionList(int bedsTotal, int bedsOccupied, int bedsAvailable, boolean canAdmit,
                                int depositAlerts, int alertPercent, int dischargedToday, int goingHomeToday,
                                EmergencyViews.Counts emergency, SafetyViews.Banner safety, Instant asOf,
                                List<WardOption> wards, AdmittedCardAbilities card, ResolvedCardSetup cardSetup,
                                List<AdmittedPatientRow> items) {
    }

    /** SAH-36 one option of the select "Ward": the id is the filter value (ADR-013 D1). */
    public record WardOption(UUID id, String name) {
    }

    /**
     * SAH-36 (ADR-013 D5) what the card shows for this caller. Each is true only when the role in the token and the role
     * of the active membership are both in the set of the ability, and the IPD state allows it. SAH-37: {@code deposit}
     * is the new quick action, {@code visibleGroups} the data groups the caller may see on the card. Computed by
     * {@code CardAccessRule}.
     */
    public record AdmittedCardAbilities(boolean nursing, boolean emr, boolean transfer, boolean discharge,
                                        boolean deposit, boolean depositLowFlag, List<String> visibleGroups) {
    }

    /**
     * SAH-36 one admitted patient of the list: the {@link Summary} fields plus the card facts. {@code clinical} is
     * written only when it is not null, so for FRONT_DESK the property is absent from the JSON (ADR-013 D3); every other
     * property that is null is written as null. Name, UHID and clinical data are on screen only: ids in {@code toString}.
     */
    public record AdmittedPatientRow(UUID id, String ipNumber, UUID patientId, String patientName, Integer age,
                                     String sex, String doctorName, String bedCode, String categoryName,
                                     String wardName, String admissionType, String payer, int dayNumber,
                                     long depositPaise, long minimumDepositPaise, String depositState,
                                     Instant admittedAt, long runningBillPaise, boolean depositLow,
                                     boolean goingHomeToday, String uhid, UUID wardId, String roomName,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) AdmittedClinicalFlags clinical,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Optional<String> mobile) {

        /** The row of the table plus the card facts; {@code clinical} null for a caller who may not read it. */
        public static AdmittedPatientRow of(Summary s, String uhid, UUID wardId, String roomName,
                                            AdmittedClinicalFlags clinical) {
            return of(s, uhid, wardId, roomName, clinical, null);
        }

        /**
         * SAH-37: {@code mobile} is null (absent from the JSON) unless the caller may see the contact group and the
         * published setup has Mobile on; {@code Optional.empty()} is sent as null (the record has no mobile).
         */
        public static AdmittedPatientRow of(Summary s, String uhid, UUID wardId, String roomName,
                                            AdmittedClinicalFlags clinical, Optional<String> mobile) {
            return new AdmittedPatientRow(s.id(), s.ipNumber(), s.patientId(), s.patientName(), s.age(), s.sex(),
                    s.doctorName(), s.bedCode(), s.categoryName(), s.wardName(), s.admissionType(), s.payer(),
                    s.dayNumber(), s.depositPaise(), s.minimumDepositPaise(), s.depositState(), s.admittedAt(),
                    s.runningBillPaise(), s.depositLow(), s.goingHomeToday(), uhid, wardId, roomName, clinical, mobile);
        }

        @Override
        public String toString() {
            return "AdmittedPatientRow[id=" + id + ", ipNumber=" + ipNumber + "]";
        }
    }

    /**
     * SAH-36 the clinical facts behind the card flags, for OWNER, DOCTOR and NURSE. Here null means "none exists"
     * ({@code escalation} no open escalation, {@code latestEws} no vitals), never "not allowed".
     */
    public record AdmittedClinicalFlags(CardEscalationFlag escalation, VitalsViews.EwsSummary latestEws,
                                        boolean vitalsDue, CardAllergy allergy,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) String diagnosis,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) Optional<CardLatestVitals> latestVitals) {

        /** The SAH-36 facts only: no diagnosis and no vitals tiles (both absent from the JSON). */
        public AdmittedClinicalFlags(CardEscalationFlag escalation, VitalsViews.EwsSummary latestEws, boolean vitalsDue,
                                     CardAllergy allergy) {
            this(escalation, latestEws, vitalsDue, allergy, null, null);
        }

        @Override
        public String toString() {
            return "AdmittedClinicalFlags[redacted]";
        }
    }

    /** SAH-36 the open (OPEN or ACK) escalation of one admitted patient: minutes from {@code EscalationClock} at asOf. */
    public record CardEscalationFlag(EscalationState.Status status, int waitingMinutes, int targetMinutes,
                                     boolean pastTarget) {
    }

    /** SAH-36 the allergy status; {@code text} only when RECORDED (absent otherwise). Clinical text: hidden from toString. */
    public record CardAllergy(MedicineSafetyCheck.AllergyStatus status,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String text) {

        @Override
        public String toString() {
            return "CardAllergy[status=" + status + "]";
        }
    }

    /**
     * SAH-37 (ADR-015 D6) the latest vitals record of an admitted patient for the card tiles. Each reading is set only
     * when its field is switched on (or for the owner's preview); an unset one is absent from the JSON. Clinical data:
     * on screen only, never logged.
     */
    public record CardLatestVitals(Instant recordedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) String recordedByName,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CardBpReading bp,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CardReading pulse,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CardReading spo2,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CardReading temp,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CardReading rr) {

        @Override
        public String toString() {
            return "CardLatestVitals[redacted]";
        }
    }

    /** SAH-37 one reading with its mark; {@code mark} CHECK or CRITICAL from the SAH-30 points, absent at 0 points. */
    public record CardReading(BigDecimal value, @JsonInclude(JsonInclude.Include.NON_NULL) Mark mark) {

        @Override
        public String toString() {
            return "CardReading[redacted]";
        }
    }

    /** SAH-37 the blood pressure; the mark is from the systolic points only (the diastolic is not scored). */
    public record CardBpReading(int systolic, int diastolic, @JsonInclude(JsonInclude.Include.NON_NULL) Mark mark) {

        @Override
        public String toString() {
            return "CardBpReading[redacted]";
        }
    }

    public record DepositEntry(UUID id, long amountPaise, String mode, Instant collectedAt, String collectedByName) {
    }

    /**
     * The row fields plus mobile, reason and deposits. Clinical and personal text: never logged. SAH-27: once
     * DISCHARGED every value is fixed at discharge and {@code discharge} and {@code finalBill} are set.
     */
    public record Detail(UUID id, String ipNumber, UUID patientId, String patientName, Integer age, String sex,
                         String doctorName, String bedCode, String categoryName, String wardName,
                         String admissionType, String payer, int dayNumber, long depositPaise,
                         long minimumDepositPaise, String depositState, Instant admittedAt,
                         String mobile, String reason, List<DepositEntry> deposits, boolean canCollectDeposit,
                         long runningBillPaise, boolean depositLow, RunningBill bill, boolean canAddCharge,
                         boolean canTransfer, TransferRules transferRules, boolean goingHomeToday, String status,
                         Instant dischargedAt, boolean canStartDischarge, DischargeViews.DischargeRecord discharge,
                         DischargeViews.FinalBill finalBill, boolean canOpenNursing, Boolean vitalsDue,
                         VitalsViews.EwsSummary latestEws, boolean canOpenEmr) {

        @Override
        public String toString() {
            return "Detail[id=" + id + ", ipNumber=" + ipNumber + "]";
        }
    }

    /** SAH-25 one extra-charge service of the fixed list, base price in paise. */
    public record ChargeService(String key, String name, long basePaise) {
    }

    /** SAH-26 request body of {@code PUT /transfer-rules}; boxed so that a missing field is a 400. */
    public record TransferRulesInput(Boolean upgradeNeedsOwner, String newRateFrom) {
    }

    /** SAH-26 request body of {@code POST /admissions/{id}/transfer}. The reason is stored, never logged. */
    public record TransferInput(UUID toBedId, String reason) {

        @Override
        public String toString() {
            return "TransferInput[toBedId=" + toBedId + "]";
        }
    }

    /** Request body of {@code POST /admissions/{id}/charges}. */
    public record ChargeInput(String serviceKey) {
    }
}
