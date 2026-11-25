package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sahaj.opd.application.DeskFollowUpService.ListBooking;
import java.time.LocalDate;
import java.util.UUID;

/** {@code FollowUpBookInput} in {@code docs/api/opd.yaml} 0.9.0 (SAH-93-2). Unknown properties are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FollowUpBookRequest(UUID doctorMembershipId, LocalDate apptDate, String apptTime, String visitType) {

    public ListBooking toInput() {
        return new ListBooking(doctorMembershipId, apptDate, apptTime, visitType);
    }
}
