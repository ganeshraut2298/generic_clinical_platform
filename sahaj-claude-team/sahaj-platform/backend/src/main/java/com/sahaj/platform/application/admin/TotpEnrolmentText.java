package com.sahaj.platform.application.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The two texts a person needs to add an authenticator app: the Base32 key in groups of four (for
 * typing) and the {@code otpauth://} URI (for a QR code). Built in one place for the enrolment step
 * and the dev-only sample admin (ADR-008 D5). {@code toString} prints no value.
 */
public record TotpEnrolmentText(String secretKey, String otpauthUri) {

    public static TotpEnrolmentText of(String email, byte[] secret) {
        String base32 = Base32.encode(secret);
        String account = URLEncoder.encode(email, StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/Sahaj%20admin:" + account + "?secret=" + base32
                + "&issuer=Sahaj%20admin&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
        return new TotpEnrolmentText(Base32.inGroupsOfFour(base32), uri);
    }

    @Override
    public String toString() {
        return "TotpEnrolmentText[]";
    }
}
