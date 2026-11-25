package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sahaj.opd.application.AppointmentRescheduleInput;
import java.time.LocalDate;
import java.util.UUID;

/** {@code AppointmentRescheduleInput} in {@code docs/api/opd.yaml} (SAH-91-3). Unknown properties (tenantId, clinicId) are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppointmentRescheduleRequest(Long version, UUID doctorMembershipId, LocalDate apptDate, String apptTime) {

    public AppointmentRescheduleInput toInput() {
        return new AppointmentRescheduleInput(version, doctorMembershipId, apptDate, apptTime);
    }
}
