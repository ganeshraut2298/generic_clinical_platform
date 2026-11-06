package com.sahaj.ipd.application;

import com.sahaj.ipd.application.IpdSettingsRepository.TransferRules;
import java.time.Instant;
import java.util.List;
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
     * tab count and the banner for OWNER, DOCTOR and NURSE; it is null for every other role.
     */
    public record AdmissionList(int bedsTotal, int bedsOccupied, int bedsAvailable, boolean canAdmit,
                                int depositAlerts, int alertPercent, int dischargedToday, int goingHomeToday,
                                EmergencyViews.Counts emergency, SafetyViews.Banner safety, List<Summary> items) {
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
