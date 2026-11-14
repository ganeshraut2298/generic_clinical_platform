package com.sahaj.patient.application;

/**
 * A PATCH: any field left null is unchanged. Mobile is not editable here (it defines the family). SAH-73:
 * {@code dateOfBirth} has three states (absent = unchanged, a date = set or correct, explicit null = clear); a Java
 * null is read as absent.
 */
public record PatientUpdateInput(String name, Integer age, String sex, String area, String language,
                                  String allergy, String chronic, Boolean abhaLinked,
                                  DateOfBirthInput dateOfBirth) {

    /** Without a date of birth (the shape before SAH-73). */
    public PatientUpdateInput(String name, Integer age, String sex, String area, String language,
                               String allergy, String chronic, Boolean abhaLinked) {
        this(name, age, sex, area, language, allergy, chronic, abhaLinked, DateOfBirthInput.ABSENT);
    }
}
