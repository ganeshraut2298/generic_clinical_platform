package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientRepository.PatientRow;
import com.sahaj.patient.application.PatientSummaryView;
import java.time.Instant;
import java.util.UUID;

/** {@code PatientSummary} in {@code docs/api/patient.yaml}. {@code lastVisitAt} is always null until SAH-9. */
public record PatientSummaryResponse(UUID id, String uhid, String name, int age, String sex, String mobile,
                                      String chronic, Instant lastVisitAt) {

    public static PatientSummaryResponse from(PatientSummaryView view) {
        return new PatientSummaryResponse(view.id(), view.uhid(), view.name(), view.age(), view.sex(),
                view.mobile(), view.chronic(), view.lastVisitAt());
    }

    public static PatientSummaryResponse from(PatientRow row) {
        return new PatientSummaryResponse(row.id(), row.uhid(), row.name(), row.age(), row.sex(), row.mobile(),
                row.chronic(), null);
    }
}
