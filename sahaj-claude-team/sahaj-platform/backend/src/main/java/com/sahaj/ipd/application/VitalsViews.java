package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-30 request and response shapes of {@code docs/api/ipd.yaml} (Nursing sheet, vitals save, and the two fields of
 * the admission sheet). Vitals and nursing notes are clinical data: every record that carries them hides them from
 * {@code toString}, so they cannot reach a log line by accident.
 */
public final class VitalsViews {

    private VitalsViews() {
    }

    /** What the escalation rule did on one save. */
    public enum Outcome {
        RAISED, UPDATED, REOPENED, NONE
    }

    /**
     * Request body of {@code POST /admissions/{id}/vitals}. The five values are text so that the server is the only
     * judge of the format; the client key is text so that "not a UUID" is the contract's 400. Clinical: hidden.
     */
    public record VitalsInput(String clientKey, String bp, String pulse, String spo2, String temp, String rr,
                              String note) {

        @Override
        public String toString() {
            return "VitalsInput[redacted]";
        }
    }

    /** The score and risk of one vitals record. */
    public record EwsSummary(int score, Risk risk, Instant recordedAt) {
    }

    /** The latest record of the admission. Clinical data: hidden from {@code toString}. */
    public record LatestVitals(UUID id, String bp, int bpSystolic, int bpDiastolic, int pulse, int spo2,
                               BigDecimal tempC, int rr, int ewsScore, Risk ewsRisk, Instant recordedAt,
                               String recordedByName) {

        @Override
        public String toString() {
            return "LatestVitals[id=" + id + "]";
        }
    }

    /** One nursing note. Clinical text: hidden from {@code toString}. */
    public record NursingNote(UUID id, String text, Instant recordedAt, String recordedByName) {

        @Override
        public String toString() {
            return "NursingNote[id=" + id + "]";
        }
    }

    /** The Nursing sheet. {@code readOnlyReason} is ROLE, IPD_READONLY, NOT_ADMITTED or null. */
    public record Sheet(UUID admissionId, String patientName, String bedCode, String status, String state,
                        boolean canRecord, String readOnlyReason, Instant asOf, LatestVitals latest,
                        boolean vitalsDue, List<NursingNote> recentNotes, int doseCount) {

        @Override
        public String toString() {
            return "Sheet[admissionId=" + admissionId + "]";
        }
    }

    /** The record a save stored (or, for a replay, the first save of the client key). */
    public record Saved(UUID vitalsId, int ewsScore, Risk ewsRisk, Instant recordedAt, boolean noteAdded,
                        boolean replayed) {
    }

    /** The open escalation (OPEN or ACK) of the admission after a request. */
    public record OpenEscalation(UUID id, int ewsScore, Risk ewsRisk, int targetMinutes, String status,
                                 Instant raisedAt) {
    }

    public record EscalationResult(Outcome outcome, OpenEscalation open) {
    }

    /** Answer of the save: the Nursing sheet fields, flat, then {@code saved} and {@code escalation}. */
    public record VitalsSaved(UUID admissionId, String patientName, String bedCode, String status, String state,
                              boolean canRecord, String readOnlyReason, Instant asOf, LatestVitals latest,
                              boolean vitalsDue, List<NursingNote> recentNotes, int doseCount, Saved saved,
                              EscalationResult escalation) {

        static VitalsSaved of(Sheet sheet, Saved saved, EscalationResult escalation) {
            return new VitalsSaved(sheet.admissionId(), sheet.patientName(), sheet.bedCode(), sheet.status(),
                    sheet.state(), sheet.canRecord(), sheet.readOnlyReason(), sheet.asOf(), sheet.latest(),
                    sheet.vitalsDue(), sheet.recentNotes(), sheet.doseCount(), saved, escalation);
        }

        @Override
        public String toString() {
            return "VitalsSaved[admissionId=" + admissionId + "]";
        }
    }
}
