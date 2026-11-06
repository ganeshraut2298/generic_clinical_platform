package com.sahaj.platform.application.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recovery codes (ADR-003 D2.6): ten codes of 16 Base32 characters (80 random bits), shown once as
 * {@code XXXX-XXXX-XXXX-XXXX} and stored only as a keyed hash of the normalised form.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    public static final int LENGTH = 16;

    private RecoveryCodes() {
    }

    /** Ten distinct codes in display form. */
    public static List<String> generate(SecureRandom random) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            byte[] bytes = new byte[10];
            random.nextBytes(bytes);
            codes.add(display(Base32.encode(bytes)));
        }
        return new ArrayList<>(codes);
    }

    /** Upper case, without hyphens or spaces: the form that is hashed. */
    public static String normalise(String input) {
        return input.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isWellFormed(String normalised) {
        if (normalised.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < normalised.length(); i++) {
            if (Base32.ALPHABET.indexOf(normalised.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String display(String sixteen) {
        return sixteen.substring(0, 4) + "-" + sixteen.substring(4, 8) + "-" + sixteen.substring(8, 12) + "-"
                + sixteen.substring(12, 16);
    }
}
