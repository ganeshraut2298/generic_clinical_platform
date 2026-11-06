package com.sahaj.platform.devtools.admin;

import java.security.SecureRandom;

/**
 * The sample admin's password (ADR-008 D5): 24 characters, each drawn with {@code SecureRandom.nextInt(57)}
 * (unbiased) from an alphabet without {@code I}, {@code O}, {@code l}, {@code 0} and {@code 1}; about 140
 * bits. It has no hyphen, so it can never contain the email name {@code sample-admin}.
 */
final class DevPasswordGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    static final int LENGTH = 24;

    private DevPasswordGenerator() {
    }

    static String generate(SecureRandom random) {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
