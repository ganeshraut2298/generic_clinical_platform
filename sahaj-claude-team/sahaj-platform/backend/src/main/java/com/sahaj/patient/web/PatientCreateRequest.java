package com.sahaj.patient.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.patient.application.ConsentInput;
import com.sahaj.patient.application.PatientInput;
import java.util.List;

/**
 * {@code PatientCreate} in {@code docs/api/patient.yaml}; validated by {@code PatientService}. SAH-73:
 * {@code dateOfBirth} is read as a JSON node (see {@link DateOfBirthJson}); {@code toString} shows nothing of the body.
 */
public record PatientCreateRequest(String name, Integer age, JsonNode dateOfBirth, String sex, String mobile,
                                    String area, String language, String allergy, String chronic,
                                    Boolean abhaLinked, List<ConsentAnswerRequest> consents) {

    public PatientInput toInput() {
        List<ConsentInput> answers = consents == null ? List.of()
                : consents.stream().map(answer -> answer == null ? null : answer.toInput()).toList();
        return new PatientInput(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, answers,
                DateOfBirthJson.read(dateOfBirth));
    }

    @Override
    public String toString() {
        return "PatientCreateRequest[]";
    }
}
