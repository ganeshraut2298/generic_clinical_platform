package com.sahaj.patient.application;

import java.util.List;

/**
 * AC1, AC9: name, age, sex and mobile are mandatory; the rest are optional with sensible defaults.
 * SAH-17: {@code consents} are the optional consent answers recorded with the new patient.
 */
public record PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                            String allergy, String chronic, Boolean abhaLinked, List<ConsentInput> consents) {

    public PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                         String allergy, String chronic, Boolean abhaLinked) {
        this(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, List.of());
    }
}
