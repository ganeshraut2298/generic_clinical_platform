package com.sahaj.patient.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SAH-68-3 (ADR-025 D13): the one write the Doctor EMR makes to the patient file at sign: the allergy and the long-term
 * conditions texts. Implemented by {@code patient}, the owner of {@code patient.patient}; {@code opd} never writes that
 * table. Runs in the caller's transaction (the clinic is already bound) and acts for the signed-in user.
 *
 * <p>The texts are clinical data: never logged, and never audited (the audit holds the field names only).
 */
public interface PatientClinicalUpdatePort {

    enum FactsOutcome { UPDATED, NOT_CHANGED, CONFLICT }

    /**
     * {@code allergyText} and {@code chronicText} are the whole new text, or null for "leave this field alone". Each is at
     * most 400 characters (the patient file's limit).
     */
    record FactsChange(String allergyText, String chronicText) {

        @Override
        public String toString() {
            return "FactsChange[redacted]";
        }
    }

    /**
     * Writes the change when the patient's current texts still match {@code expectedFingerprint} (what the visit read at
     * open); otherwise nothing is written and the answer is CONFLICT. A change equal to what is stored is NOT_CHANGED.
     * {@code allergy_recorded_at/by} are set with the allergy text. Audits PATIENT_CLINICAL_FACTS_UPDATED (patient id and
     * field names only).
     */
    FactsOutcome updateFacts(UUID tenantId, UUID actorUserId, UUID patientId, String expectedFingerprint,
                             FactsChange change);

    /** SHA-256 (lower-case hex) of the allergy and chronic texts; a null text counts as empty. */
    static String fingerprint(String allergyText, String chronicText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = (allergyText == null ? "" : allergyText) + "\u0000" + (chronicText == null ? "" : chronicText);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
