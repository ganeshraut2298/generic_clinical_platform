package com.sahaj.patient.application;

/** A PATCH: any field left null is unchanged. Mobile is not editable here (it defines the family). */
public record PatientUpdateInput(String name, Integer age, String sex, String area, String language,
                                  String allergy, String chronic, Boolean abhaLinked) {
}
