package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientInput;

/** {@code PatientCreate} in {@code docs/api/patient.yaml}; validated by {@code PatientService}. */
public record PatientCreateRequest(String name, Integer age, String sex, String mobile, String area,
                                    String language, String allergy, String chronic, Boolean abhaLinked) {

    public PatientInput toInput() {
        return new PatientInput(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked);
    }
}
