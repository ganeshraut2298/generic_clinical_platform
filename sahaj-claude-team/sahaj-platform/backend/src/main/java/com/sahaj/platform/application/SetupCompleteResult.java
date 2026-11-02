package com.sahaj.platform.application;

/** Result of {@code POST /setup/complete} (SAH-4 AC1, AC12): the app always opens next. */
public record SetupCompleteResult(String brand) {
}
