package com.sahaj.patient.application;

/** AC1, AC9: name, age, sex and mobile are mandatory; the rest are optional with sensible defaults. */
public record PatientInput(String name, Integer age, String sex, String mobile, String area, String language,
                            String allergy, String chronic, Boolean abhaLinked) {
}
