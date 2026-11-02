package com.sahaj.patient.application;

import java.time.Instant;
import java.util.UUID;

/** The {@code Patient} shape of {@code docs/api/patient.yaml}. */
public record PatientView(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                           String language, String allergy, String chronic, boolean abhaLinked, Instant createdAt,
                           UUID possibleDuplicateOfId) {
}
