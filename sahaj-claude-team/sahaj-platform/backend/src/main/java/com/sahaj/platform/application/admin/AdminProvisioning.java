package com.sahaj.platform.application.admin;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks and prepares the three values an admin row needs from outside: email, name and password
 * (ADR-008 D5, extracted from the dev seed without changing its behaviour). Trims and lower-cases the
 * email, checks the email pattern and length, refuses the text {@code $sq$} (it would break out of
 * the Flyway callback's quoting), checks the name length, applies {@link AdminPasswordPolicy} and
 * hashes the password with {@link AdminPasswordHasher}. Used by the env seed and the dev-only
 * sample admin, so there is one validation and one hashing path.
 *
 * <p>A refusal names the problem only ({@link Reason}); it never carries a submitted value.
 */
public final class AdminProvisioning {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MAX_NAME = 120;

    /** Why a value was refused. */
    public enum Reason { EMAIL_INVALID, NAME_INVALID, PASSWORD_POLICY }

    /** A value was refused. The message names the problem and never a value. */
    public static final class RefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;

        public RefusedException(Reason reason) {
            super("Admin value refused: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** The checked values: lower-case email, trimmed name and the BCrypt hash. {@code toString} prints nothing. */
    public record PreparedAdmin(String email, String name, String passwordHash) {
        @Override
        public String toString() {
            return "PreparedAdmin[]";
        }
    }

    private final AdminPasswordHasher hasher;

    public AdminProvisioning(AdminPasswordHasher hasher) {
        this.hasher = hasher;
    }

    public PreparedAdmin prepare(String rawEmail, String rawName, String password) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        String name = rawName == null ? "" : rawName.trim();
        if (!EMAIL.matcher(email).matches() || email.length() > MAX_EMAIL || email.contains("$sq$")) {
            throw new RefusedException(Reason.EMAIL_INVALID);
        }
        if (name.length() > MAX_NAME || name.contains("$sq$")) {
            throw new RefusedException(Reason.NAME_INVALID);
        }
        if (!AdminPasswordPolicy.isAcceptable(email, password)) {
            throw new RefusedException(Reason.PASSWORD_POLICY);
        }
        return new PreparedAdmin(email, name, hasher.hash(password));
    }
}
