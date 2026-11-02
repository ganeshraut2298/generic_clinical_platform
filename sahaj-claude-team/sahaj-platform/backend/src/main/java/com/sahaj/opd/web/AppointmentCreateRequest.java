package com.sahaj.opd.web;

import com.sahaj.opd.application.AppointmentCreateInput;
import java.time.LocalDate;
import java.util.UUID;

/** {@code AppointmentCreate} in {@code docs/api/opd.yaml} (SAH-11); validated by {@code AppointmentService}. */
public record AppointmentCreateRequest(UUID patientId, UUID doctorMembershipId, LocalDate apptDate, String apptTime,
                                        String visitType, String source, String note) {

    public AppointmentCreateInput toInput() {
        return new AppointmentCreateInput(patientId, doctorMembershipId, apptDate, apptTime, visitType, source, note);
    }
}
