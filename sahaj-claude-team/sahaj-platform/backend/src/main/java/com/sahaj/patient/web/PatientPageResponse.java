package com.sahaj.patient.web;

import com.sahaj.patient.application.PatientSummaryPage;
import java.util.List;

/** The page of patients in {@code GET /patients}: {@code total} counts every match, not only this page. */
public record PatientPageResponse(List<PatientSummaryResponse> items, long total) {

    public static PatientPageResponse from(PatientSummaryPage page) {
        return new PatientPageResponse(page.items().stream().map(PatientSummaryResponse::from).toList(),
                page.total());
    }
}
