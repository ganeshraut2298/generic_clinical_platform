package com.sahaj.opd.web;

/** The PATCH body of {@code /opd/visits/{id}/stage} in {@code docs/api/opd.yaml} (SAH-9). */
public record VisitStageRequest(String stage) {
}
