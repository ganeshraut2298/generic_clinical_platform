package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientView;
import java.time.Instant;
import java.util.UUID;

/** {@code Patient} in {@code docs/api/patient.yaml}. */
public record PatientResponse(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                               String language, String allergy, String chronic, boolean abhaLinked,
                               Instant createdAt, UUID possibleDuplicateOfId) {

    public static PatientResponse from(PatientView view) {
        return new PatientResponse(view.id(), view.uhid(), view.name(), view.age(), view.sex(), view.mobile(),
                view.area(), view.language(), view.allergy(), view.chronic(), view.abhaLinked(), view.createdAt(),
                view.possibleDuplicateOfId());
    }
}
