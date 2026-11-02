package com.sahaj.opd.application;

import java.time.LocalDate;
import java.util.UUID;

/** {@code AppointmentCreate} in {@code docs/api/opd.yaml} (SAH-11). */
public record AppointmentCreateInput(UUID patientId, UUID doctorMembershipId, LocalDate apptDate, String apptTime,
                                      String visitType, String source, String note) {
}
