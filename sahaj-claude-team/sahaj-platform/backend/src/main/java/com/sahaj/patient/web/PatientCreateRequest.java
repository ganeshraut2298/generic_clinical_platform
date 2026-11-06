package com.sahaj.patient.web;

import com.sahaj.patient.application.ConsentInput;
import com.sahaj.patient.application.PatientInput;
import java.util.List;

/** {@code PatientCreate} in {@code docs/api/patient.yaml}; validated by {@code PatientService}. */
public record PatientCreateRequest(String name, Integer age, String sex, String mobile, String area,
                                    String language, String allergy, String chronic, Boolean abhaLinked,
                                    List<ConsentAnswerRequest> consents) {

    public PatientInput toInput() {
        List<ConsentInput> answers = consents == null ? List.of()
                : consents.stream().map(answer -> answer == null ? null : answer.toInput()).toList();
        return new PatientInput(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, answers);
    }
}
