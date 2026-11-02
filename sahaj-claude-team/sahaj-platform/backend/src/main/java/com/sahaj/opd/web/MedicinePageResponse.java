package com.sahaj.opd.web;

import com.sahaj.opd.application.MedicineRepository.MedicinePage;
import java.util.List;

/** The page of medicines in {@code GET /medicines}: {@code total} counts every match, not only this page. */
public record MedicinePageResponse(List<MedicineResponse> items, long total) {

    public static MedicinePageResponse from(MedicinePage page) {
        return new MedicinePageResponse(page.items().stream().map(MedicineResponse::from).toList(), page.total());
    }
}
