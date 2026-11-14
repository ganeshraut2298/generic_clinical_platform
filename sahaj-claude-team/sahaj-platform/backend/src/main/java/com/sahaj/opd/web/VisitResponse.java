package com.sahaj.opd.web;

import com.sahaj.opd.application.VisitView;
import java.time.Instant;
import java.util.UUID;

/** {@code Visit} in {@code docs/api/opd.yaml} (SAH-9; 0.6.0 adds the triage level and the fee change). */
public record VisitResponse(UUID id, int tokenNo, UUID patientId, String patientName, int age, String sex,
                             UUID doctorMembershipId, String doctorName, String visitType, String stage,
                             long feePaise, Instant createdAt, int workflowVersionNo, UUID emrRecordId,
                             String emrStatus, Integer triageLevel, String triageLevelName, String triageTone,
                             VisitView.FeeChange feeChange) {

    public static VisitResponse from(VisitView view) {
        return new VisitResponse(view.id(), view.tokenNo(), view.patientId(), view.patientName(), view.age(),
                view.sex(), view.doctorMembershipId(), view.doctorName(), view.visitType(), view.stage(),
                view.feePaise(), view.createdAt(), view.workflowVersionNo(), view.emrRecordId(),
                view.emrStatus(), view.triageLevel(), view.triageLevelName(), view.triageTone(), view.feeChange());
    }
}
