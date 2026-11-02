package com.sahaj.opd.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** {@code Appointment} in {@code docs/api/opd.yaml} (SAH-11). */
public record AppointmentView(UUID id, UUID patientId, String patientName, Integer age, String sex,
                               UUID doctorMembershipId, String doctorName, LocalDate apptDate, LocalTime apptTime,
                               String status, String visitType, String source, String note, UUID visitId,
                               Integer tokenNo) {
}
