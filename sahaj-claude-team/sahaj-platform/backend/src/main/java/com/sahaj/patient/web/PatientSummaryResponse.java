package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientSummaryView;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code PatientSummary} in {@code docs/api/patient.yaml}. {@code lastVisitAt} is always null until SAH-9. SAH-73:
 * {@code age} is the effective completed years, {@code ageDetail} the unit and value; a list or family row never
 * carries the date of birth (BR73-12).
 */
public record PatientSummaryResponse(UUID id, String uhid, String name, int age, AgeDetailResponse ageDetail,
                                      String sex, String mobile, String chronic, Instant lastVisitAt) {

    public static PatientSummaryResponse from(PatientSummaryView view) {
        return new PatientSummaryResponse(view.id(), view.uhid(), view.name(), view.age(),
                AgeDetailResponse.from(view.ageDetail()), view.sex(), view.mobile(), view.chronic(),
                view.lastVisitAt());
    }
}
