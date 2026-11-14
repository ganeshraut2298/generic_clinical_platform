package com.sahaj.patient.application;

/**
 * SAH-73 (SO-73-8): the kept record after a merge, and whether the two records had different dates of birth (the kept
 * date was kept; the screen says so).
 */
public record MergeOutcome(PatientView patient, boolean datesOfBirthDiffered) {
}
