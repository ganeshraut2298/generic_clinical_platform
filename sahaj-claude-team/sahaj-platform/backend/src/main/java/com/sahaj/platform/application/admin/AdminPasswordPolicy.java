package com.sahaj.platform.application.admin;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The admin password policy (ADR-003 D2.1, NIST SP 800-63B style): 12 to 64 characters, at most 72
 * bytes in UTF-8 (the BCrypt limit), and it must not contain the part of the email before "@"
 * (case-insensitive). No composition rules, no expiry. Checked wherever a password is set: the dev
 * seed and, later, the provisioning procedure. Sign-in does not apply it.
 */
public final class AdminPasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_CHARACTERS = 64;
    public static final String SEED_MESSAGE =
            "SAHAJ_ADMIN_SEED_PASSWORD does not meet the admin password policy "
                    + "(12 to 64 characters, not containing the email name).";

    private AdminPasswordPolicy() {
    }

    public static boolean isAcceptable(String email, String password) {
        if (password == null || email == null) {
            return false;
        }
        int characters = password.codePointCount(0, password.length());
        if (characters < MIN_CHARACTERS || characters > MAX_CHARACTERS) {
            return false;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > AdminPasswordHasher.MAX_BYTES) {
            return false;
        }
        int at = email.indexOf('@');
        String name = (at < 0 ? email : email.substring(0, at)).trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() || !password.toLowerCase(Locale.ROOT).contains(name);
    }
}
