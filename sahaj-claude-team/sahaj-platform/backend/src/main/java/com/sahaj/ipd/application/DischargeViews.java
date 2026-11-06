package com.sahaj.ipd.application;

import com.sahaj.ipd.application.RunningBill.BillLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SAH-27 request and response shapes of {@code docs/api/ipd.yaml} (Discharge setup, the Discharge sheet, the
 * discharge record and the frozen final bill). The patient's name and the reason for admission are on screen only:
 * every record that carries them leaves them out of {@code toString}.
 */
public final class DischargeViews {

    private DischargeViews() {
    }

    public record TypeOption(String key, String label) {
    }

    public record TemplateOption(String key, String label) {
    }

    /** Request body of {@code PUT /discharge-settings}; boxed so that a missing field is a 400. */
    public record SettingsInput(String templateKey, Boolean approvalChain, Integer followUpDays) {
    }

    public record OpdFollowUpRule(int days, String fee) {
    }

    public record Settings(String templateKey, boolean approvalChain, int followUpDays, List<TypeOption> types,
                           List<TemplateOption> templates, OpdFollowUpRule opdFollowUpRule, boolean canEdit) {
    }

    /** Request body of {@code PUT /admissions/{id}/discharge/signoff}. */
    public record SignoffInput(String type, String templateKey) {
    }

    /**
     * Request body of {@code POST /admissions/{id}/discharge}; the total and the deposits are only compared with the
     * server's own figures, never used as an amount (SAH-27 F-27-2).
     */
    public record DischargeInput(String type, String templateKey, Long expectedTotalPaise,
                                 Long expectedDepositsPaise, String mode) {
    }

    public record Signature(String type, String templateKey, String signedByName, Instant signedAt) {
    }

    /** {@code diagnosis} is the reason for admission: clinical text, never logged. */
    public record Preview(String diagnosis, int stayDays, String bedCode, String doctorName, String doctorShortName,
                          String doctorSpecialty) {

        @Override
        public String toString() {
            return "Preview[bedCode=" + bedCode + ", stayDays=" + stayDays + "]";
        }
    }

    public record SheetBill(long totalPaise, long depositsPaise, long balancePaise) {
    }

    /**
     * Carries the patient's name and the reason: {@code toString} shows the admission id and IP number only.
     * SAH-33: {@code openEscalation} is true when the admission has an early warning escalation that is not closed
     * (a boolean only, no score: FRONT_DESK opens the sheet). {@code signoffBeforeEscalation} (ADR-007 R2) is true when
     * that is so, a sign-off exists and it is not later than the start of the escalation's response clock.
     */
    public record Sheet(UUID admissionId, String ipNumber, String patientName, String bedCode, int dayNumber,
                        boolean approvalChain, List<TypeOption> types, List<TemplateOption> templates,
                        String defaultType, String defaultTemplateKey, Signature signature, boolean canSign,
                        boolean canDischarge, boolean canRefund, Preview preview, SheetBill bill, int followUpDays,
                        LocalDate followUpDate, boolean openEscalation, boolean signoffBeforeEscalation) {

        @Override
        public String toString() {
            return "Sheet[admissionId=" + admissionId + ", ipNumber=" + ipNumber + "]";
        }
    }

    /** What the discharge recorded. {@code followUpDate} is null for death. */
    public record DischargeRecord(String type, String templateKey, String signedByName, Instant signedAt,
                                  String dischargedByName, Instant dischargedAt, int followUpDays,
                                  LocalDate followUpDate) {
    }

    /** The final bill frozen at discharge, with its settlement; {@code outcome} is collected, refunded or settled. */
    public record FinalBill(List<BillLine> lines, int billDays, long totalPaise, long depositsPaise,
                            long balancePaise, String outcome, String mode, String settledByName, Instant settledAt) {
    }
}
