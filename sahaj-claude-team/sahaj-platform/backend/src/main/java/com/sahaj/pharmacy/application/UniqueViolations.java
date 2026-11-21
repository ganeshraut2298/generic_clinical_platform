package com.sahaj.pharmacy.application;

import java.sql.SQLException;

/** Reads a database unique violation (SQLSTATE 23505) by the name of its constraint, without any driver class. */
final class UniqueViolations {

    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueViolations() {
    }

    /** True when {@code error} or a cause is a unique violation whose message names {@code constraint}. */
    static boolean is(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
