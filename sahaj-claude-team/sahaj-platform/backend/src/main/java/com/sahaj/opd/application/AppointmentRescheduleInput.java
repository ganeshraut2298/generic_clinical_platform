package com.sahaj.opd.application;

import java.time.LocalDate;
import java.util.UUID;

/** {@code AppointmentRescheduleInput} in {@code docs/api/opd.yaml} (SAH-91-3); validated by {@code AppointmentService}. */
public record AppointmentRescheduleInput(Long version, UUID doctorMembershipId, LocalDate apptDate, String apptTime) {
}
