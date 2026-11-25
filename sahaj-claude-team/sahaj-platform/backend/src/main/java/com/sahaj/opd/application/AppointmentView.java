package com.sahaj.opd.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** {@code Appointment} in {@code docs/api/opd.yaml} (SAH-11); {@code version} is the optimistic version (0.9.0, SAH-91). */
public record AppointmentView(UUID id, UUID patientId, String patientName, Integer age, String sex,
                               UUID doctorMembershipId, String doctorName, LocalDate apptDate, LocalTime apptTime,
                               String status, String visitType, String source, String note, UUID visitId,
                               Integer tokenNo, long version) {

    /** A view that does not carry the version (version 0). */
    public AppointmentView(UUID id, UUID patientId, String patientName, Integer age, String sex,
                           UUID doctorMembershipId, String doctorName, LocalDate apptDate, LocalTime apptTime,
                           String status, String visitType, String source, String note, UUID visitId,
                           Integer tokenNo) {
        this(id, patientId, patientName, age, sex, doctorMembershipId, doctorName, apptDate, apptTime, status,
                visitType, source, note, visitId, tokenNo, 0L);
    }
}
