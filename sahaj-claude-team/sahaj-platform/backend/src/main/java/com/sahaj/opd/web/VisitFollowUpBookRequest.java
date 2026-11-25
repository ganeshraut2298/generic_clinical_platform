package com.sahaj.opd.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sahaj.opd.application.DeskFollowUpService.VisitBooking;
import java.time.LocalDate;

/** {@code VisitFollowUpBookInput} in {@code docs/api/opd.yaml} 0.9.0 (SAH-93-1). Unknown properties are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VisitFollowUpBookRequest(String intervalKey, LocalDate apptDate, String apptTime, String visitType) {

    public VisitBooking toInput() {
        return new VisitBooking(intervalKey, apptDate, apptTime, visitType);
    }
}
