package com.sahaj.patient.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code PatientSummary} shape of {@code docs/api/patient.yaml}. {@code lastVisitAt} is always
 * null: SAH-9 (Queue/consultation) is where a visit first exists.
 */
public record PatientSummaryView(UUID id, String uhid, String name, int age, String sex, String mobile,
                                  String chronic, Instant lastVisitAt) {
}
