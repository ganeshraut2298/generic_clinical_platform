package com.sahaj.opd.domain.emr.specialty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The tag every specialty rule value carries (ADR-023 D4, DEC-039): the number was drawn in the EMR mockup and accepted
 * by the human, and has not been clinically reviewed. Each rule class names the story id and the rule id (C-1 ...) in
 * its Javadoc; every number has a unit test whose name carries {@code tagged}. Small shared helpers live here too. A patient's age is not one of them (ADR-024 D1: {@code com.sahaj.patient.api.PatientAge}).
 */
public final class RuleTag {

    /** The exact wording shown with every rule value (EmrSpecialtyView.tagNote). */
    public static final String TAG =
            "value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed";

    private RuleTag() {
    }

    /** An unmodifiable map that keeps the order given (key, value, key, value ...): lists shown to the doctor are ordered. */
    public static java.util.Map<String, String> ordered(String... pairs) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** SHA-256 of the text (UTF-8) as 64 lower-case hex characters. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
