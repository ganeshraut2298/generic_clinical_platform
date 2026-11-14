package com.sahaj.patient.web;

import com.sahaj.patient.api.PatientAge;

/**
 * {@code AgeDetail} in {@code docs/api/patient.yaml} (SAH-73): the unit and value the screen shows, worked out on the
 * server as of today in India. It carries no date, no day count and no "as of" day.
 */
public record AgeDetailResponse(int value, String unit, boolean fromDateOfBirth) {

    public static AgeDetailResponse from(PatientAge age) {
        return new AgeDetailResponse(age.value(), age.unit().name(), age.fromDateOfBirth());
    }
}
