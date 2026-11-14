package com.sahaj.patient.application;

import java.util.List;

/**
 * AC1, AC9: name, age, sex and mobile are mandatory; the rest are optional with sensible defaults.
 * SAH-17: {@code consents} are the optional consent answers recorded with the new patient.
 * SAH-73: {@code dateOfBirth} is optional; {@code age} is required only when there is no date of birth.
 */
public record PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                            String allergy, String chronic, Boolean abhaLinked, List<ConsentInput> consents,
                            DateOfBirthInput dateOfBirth) {

    /** Without a date of birth (the shape before SAH-73). */
    public PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                         String allergy, String chronic, Boolean abhaLinked, List<ConsentInput> consents) {
        this(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, consents,
                DateOfBirthInput.ABSENT);
    }

    public PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                         String allergy, String chronic, Boolean abhaLinked) {
        this(name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, List.of(),
                DateOfBirthInput.ABSENT);
    }
}
