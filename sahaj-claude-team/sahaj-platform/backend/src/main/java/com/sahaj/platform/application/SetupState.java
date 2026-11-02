package com.sahaj.platform.application;

import java.util.Map;

/** {@code SetupState} in {@code docs/api/platform.yaml} (SAH-4): the owner's current place in the wizard. */
public record SetupState(int currentStep, Map<String, Object> answers, int maxDoctors, int maxUsers) {
}
