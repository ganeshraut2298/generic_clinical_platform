package com.sahaj.ipd.application;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The client key of an idempotent IPD save (SAH-28, SAH-30). Only the canonical 8-4-4-4-12 form of ASCII hex
 * digits is a key: {@link UUID#fromString} alone also accepts short groups such as {@code 1-1-1-1-1}.
 */
final class ClientKeys {

    private static final Pattern CANONICAL =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private ClientKeys() {
    }

    /** The key (after stripping), or null when it is missing or not in the canonical form. */
    static UUID parse(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return CANONICAL.matcher(stripped).matches() ? UUID.fromString(stripped) : null;
    }
}
