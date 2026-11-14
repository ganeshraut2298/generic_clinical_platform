package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAge;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code PatientSummary} shape of {@code docs/api/patient.yaml}. {@code lastVisitAt} is always
 * null: SAH-9 (Queue/consultation) is where a visit first exists. SAH-73: {@code age} is the effective completed
 * years, {@code ageDetail} the unit and value the screen shows; a list row never carries the date of birth.
 */
public record PatientSummaryView(UUID id, String uhid, String name, int age, String sex, String mobile,
                                  String chronic, Instant lastVisitAt, PatientAge ageDetail) {

    @Override
    public String toString() {
        return "PatientSummaryView[id=" + id + "]";
    }
}
