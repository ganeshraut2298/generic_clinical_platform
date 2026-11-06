package com.sahaj.platform.application.admin;

/**
 * Small decisions the sign-in service takes from its surroundings, kept as ports so the service
 * stays free of servlet and rate-limit classes.
 */
public final class AdminSignInPolicies {

    private AdminSignInPolicies() {
    }

    /** 10 login requests per email per 15 minutes (ADR-003 D2.5), in memory. */
    @FunctionalInterface
    public interface LoginLimiter {
        /** Counts one attempt. Returns 0 when it may go ahead, else the seconds to wait. */
        long retryAfterSecondsIfRefused(String emailKeyHash);
    }

    /** The dev-only accept-any-code switch (ADR-003 D5). */
    @FunctionalInterface
    public interface TotpDevBypass {
        /** True only when the bypass is on and the request comes from a loopback address. */
        boolean appliesTo(String remoteAddress);
    }
}
