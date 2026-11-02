package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientUpdateInput;

/** {@code PatientUpdate} in {@code docs/api/patient.yaml}; on PATCH only the fields sent change. */
public record PatientUpdateRequest(String name, Integer age, String sex, String area, String language,
                                    String allergy, String chronic, Boolean abhaLinked) {

    public PatientUpdateInput toInput() {
        return new PatientUpdateInput(name, age, sex, area, language, allergy, chronic, abhaLinked);
    }
}
