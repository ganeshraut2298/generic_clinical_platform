package com.sahaj.platform.web;

import com.sahaj.platform.application.SetupState;
import java.util.Map;

/** {@code SetupState} in {@code docs/api/platform.yaml}: {@code GET /setup}, {@code PUT /setup/steps/{step}}. */
public record SetupStateResponse(int currentStep, Map<String, Object> answers, Limits limits) {

    public record Limits(int doctors, int users) {
    }

    public static SetupStateResponse from(SetupState state) {
        return new SetupStateResponse(state.currentStep(), state.answers(),
                new Limits(state.maxDoctors(), state.maxUsers()));
    }
}
