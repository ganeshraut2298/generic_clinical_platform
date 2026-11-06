package com.sahaj.platform.application.admin;

import java.security.SecureRandom;

/**
 * New authenticator secrets (ADR-003 D2.2): 20 random bytes, the length RFC 4226 recommends for
 * HMAC-SHA1. Shared by the enrolment step and the dev-only sample admin (ADR-008 D5).
 */
public final class TotpSecrets {

    public static final int SECRET_BYTES = 20;

    private TotpSecrets() {
    }

    public static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }
}
