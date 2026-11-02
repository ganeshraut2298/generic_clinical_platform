package com.sahaj.platform.application;

import com.sahaj.platform.domain.TenantSummary;
import java.time.Instant;
import java.util.List;

/**
 * The result of verifying a one-time code, or of choosing a clinic (SAH-3 {@code AuthResult}).
 * Exactly one of the four {@code next} outcomes applies; the fields that do not apply are null.
 */
public record VerifyOutcome(
        Next next,
        TokenService.AccessToken accessToken,
        String rawRefreshToken,
        Instant refreshExpiresAt,
        String selectionToken,
        List<TenantSummary> clinics) {

    public enum Next {
        SETUP,
        APP,
        CHOOSE_CLINIC,
        NOT_REGISTERED
    }

    public static VerifyOutcome notRegistered() {
        return new VerifyOutcome(Next.NOT_REGISTERED, null, null, null, null, null);
    }

    public static VerifyOutcome setup(TokenService.AccessToken accessToken, String rawRefreshToken, Instant refreshExpiresAt) {
        return new VerifyOutcome(Next.SETUP, accessToken, rawRefreshToken, refreshExpiresAt, null, null);
    }

    public static VerifyOutcome app(TokenService.AccessToken accessToken, String rawRefreshToken, Instant refreshExpiresAt) {
        return new VerifyOutcome(Next.APP, accessToken, rawRefreshToken, refreshExpiresAt, null, null);
    }

    public static VerifyOutcome chooseClinic(String selectionToken, List<TenantSummary> clinics) {
        return new VerifyOutcome(Next.CHOOSE_CLINIC, null, null, null, selectionToken, clinics);
    }
}
