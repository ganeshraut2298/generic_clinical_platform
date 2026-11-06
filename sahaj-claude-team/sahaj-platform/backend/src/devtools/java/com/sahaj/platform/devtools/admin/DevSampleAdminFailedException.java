package com.sahaj.platform.devtools.admin;

import java.util.regex.Pattern;

/**
 * Any database or commit failure of the dev-only endpoint (ADR-008 R4). It has no message, no cause and no
 * suppressed exceptions, so nothing the driver put into its text (the failing row: email, hash, ciphertext) can reach
 * a log line, a resolver's "Resolved [...]" line or a response. It keeps only the five-character SQLSTATE, when one was
 * found, and {@link #toString()} prints the class name and that value only.
 */
final class DevSampleAdminFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private final String sqlState;

    DevSampleAdminFailedException(String sqlState) {
        super(null, null, false, false);
        this.sqlState = sqlState != null && SQL_STATE.matcher(sqlState).matches() ? sqlState : null;
    }

    /** The SQLSTATE, or null when none was found. */
    String sqlState() {
        return sqlState;
    }

    @Override
    public String toString() {
        return "DevSampleAdminFailedException[sqlState=" + (sqlState == null ? "none" : sqlState) + "]";
    }
}
