package com.sahaj.platform.web;

import java.util.List;

/** The {@code AuthResult} schema of {@code docs/api/platform.yaml}. The refresh token is never in here. */
public record AuthResultResponse(
        String next,
        String accessToken,
        Integer expiresInSeconds,
        String selectionToken,
        List<ClinicOptionResponse> clinics) {

    public static AuthResultResponse notRegistered() {
        return new AuthResultResponse("NOT_REGISTERED", null, null, null, null);
    }

    public static AuthResultResponse session(String next, String accessToken, int expiresInSeconds) {
        return new AuthResultResponse(next, accessToken, expiresInSeconds, null, null);
    }

    public static AuthResultResponse chooseClinic(String selectionToken, List<ClinicOptionResponse> clinics) {
        return new AuthResultResponse("CHOOSE_CLINIC", null, null, selectionToken, clinics);
    }
}
