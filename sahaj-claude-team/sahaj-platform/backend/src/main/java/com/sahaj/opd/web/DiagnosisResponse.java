package com.sahaj.opd.web;

import com.sahaj.opd.application.DiagnosisRepository.DiagnosisRow;

/** One match of {@code GET /diagnoses} in {@code docs/api/opd.yaml}. */
public record DiagnosisResponse(String code, String name, String specialty) {

    public static DiagnosisResponse from(DiagnosisRow row) {
        return new DiagnosisResponse(row.code(), row.name(), row.specialty());
    }
}
