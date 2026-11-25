package com.sahaj.opd.web;

/**
 * The PATCH body of {@code /opd/visits/{id}/stage} in {@code docs/api/opd.yaml} (SAH-9). {@code expectedStage} is optional
 * (0.9.0, SAH-92-3): the stage the client last saw.
 */
public record VisitStageRequest(String stage, String expectedStage) {
}
