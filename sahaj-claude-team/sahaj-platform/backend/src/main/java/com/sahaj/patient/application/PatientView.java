package com.sahaj.patient.application;

import com.sahaj.patient.api.PatientAge;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code Patient} shape of {@code docs/api/patient.yaml}. {@code age} is the effective completed years and
 * {@code ageDetail} the unit and value the screen shows (SAH-73). {@code dateOfBirth} is null when none is recorded;
 * whether the caller's role may see it is decided by the web mapping (ADR-024 D3). Patient data: {@code toString}
 * shows the id only.
 */
public record PatientView(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                           String language, String allergy, String chronic, boolean abhaLinked, Instant createdAt,
                           UUID possibleDuplicateOfId, LocalDate dateOfBirth, PatientAge ageDetail) {

    @Override
    public String toString() {
        return "PatientView[id=" + id + "]";
    }
}
