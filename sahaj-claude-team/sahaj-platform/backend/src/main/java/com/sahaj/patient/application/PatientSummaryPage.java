package com.sahaj.patient.application;

import java.util.List;

/** A page of the Patients list: the rows with their worked-out age (no date of birth) and the total of every match. */
public record PatientSummaryPage(List<PatientSummaryView> items, long total) {
}
