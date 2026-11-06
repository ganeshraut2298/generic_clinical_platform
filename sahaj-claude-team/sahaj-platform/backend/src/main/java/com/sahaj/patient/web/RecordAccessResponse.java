package com.sahaj.patient.web;

import com.sahaj.patient.application.RecordAccessService.RecordAccessView;
import java.time.Instant;
import java.util.UUID;

/** {@code RecordAccess} in {@code docs/api/patient.yaml}. */
public record RecordAccessResponse(Instant at, String userName, UUID patientId, String patientName) {

    public static RecordAccessResponse from(RecordAccessView view) {
        return new RecordAccessResponse(view.at(), view.userName(), view.patientId(), view.patientName());
    }
}
