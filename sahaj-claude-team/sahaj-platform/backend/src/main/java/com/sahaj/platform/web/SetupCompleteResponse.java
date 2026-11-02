package com.sahaj.platform.web;

/** {@code POST /setup/complete} in {@code docs/api/platform.yaml} (SAH-4 AC1, AC12). {@code next} is always {@code APP}. */
public record SetupCompleteResponse(String next, String brand) {

    public static SetupCompleteResponse of(String brand) {
        return new SetupCompleteResponse("APP", brand);
    }
}
