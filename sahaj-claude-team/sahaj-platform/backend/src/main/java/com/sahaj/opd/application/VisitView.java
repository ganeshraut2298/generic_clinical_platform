package com.sahaj.opd.application;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code Visit} in {@code docs/api/opd.yaml} (SAH-9). 0.6.0 (SAH-67-4, SAH-68-2) adds the triage level of an Emergency visit
 * and, in the answer of the visit-type change only, the fee change.
 */
public record VisitView(UUID id, int tokenNo, UUID patientId, String patientName, int age, String sex,
                         UUID doctorMembershipId, String doctorName, String visitType, String stage, long feePaise,
                         Instant createdAt, int workflowVersionNo, UUID emrRecordId, String emrStatus,
                         Integer triageLevel, String triageLevelName, String triageTone, FeeChange feeChange) {

    /** {@code outcome} is UPDATED, UNCHANGED or ALREADY_BILLED (ADR-025 D14). */
    public record FeeChange(String outcome, long beforePaise, long afterPaise) {
    }

    /** A visit with an EMR record and no triage level (the shape before 0.6.0). */
    public VisitView(UUID id, int tokenNo, UUID patientId, String patientName, int age, String sex,
                      UUID doctorMembershipId, String doctorName, String visitType, String stage, long feePaise,
                      Instant createdAt, int workflowVersionNo, UUID emrRecordId, String emrStatus) {
        this(id, tokenNo, patientId, patientName, age, sex, doctorMembershipId, doctorName, visitType, stage,
                feePaise, createdAt, workflowVersionNo, emrRecordId, emrStatus, null, null, null, null);
    }

    /** A visit with no EMR record yet. */
    public VisitView(UUID id, int tokenNo, UUID patientId, String patientName, int age, String sex,
                      UUID doctorMembershipId, String doctorName, String visitType, String stage, long feePaise,
                      Instant createdAt, int workflowVersionNo) {
        this(id, tokenNo, patientId, patientName, age, sex, doctorMembershipId, doctorName, visitType, stage,
                feePaise, createdAt, workflowVersionNo, null, "NONE");
    }

    /** The same visit with the level's name and colour (read through the Emergency port) and the fee change. */
    public VisitView withTriage(String levelName, String tone, FeeChange change) {
        return new VisitView(id, tokenNo, patientId, patientName, age, sex, doctorMembershipId, doctorName, visitType,
                stage, feePaise, createdAt, workflowVersionNo, emrRecordId, emrStatus, triageLevel, levelName, tone,
                change);
    }
}
