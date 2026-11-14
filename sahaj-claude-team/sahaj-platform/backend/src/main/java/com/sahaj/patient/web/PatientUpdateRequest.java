package com.sahaj.patient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.patient.application.PatientUpdateInput;

/**
 * {@code PatientUpdate} in {@code docs/api/patient.yaml}; on PATCH only the fields sent change. SAH-73:
 * {@code dateOfBirth} has three states (missing = unchanged, a date = set or correct, JSON null = clear), read from
 * the JSON node (see {@link DateOfBirthJson}).
 */
public record PatientUpdateRequest(String name, Integer age, JsonNode dateOfBirth, String sex, String area,
                                    String language, String allergy, String chronic, Boolean abhaLinked) {

    public PatientUpdateInput toInput() {
        return new PatientUpdateInput(name, age, sex, area, language, allergy, chronic, abhaLinked,
                DateOfBirthJson.read(dateOfBirth));
    }

    @Override
    public String toString() {
        return "PatientUpdateRequest[]";
    }
}
