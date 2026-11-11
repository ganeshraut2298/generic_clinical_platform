package com.sahaj.platform.devtools.bootstrap;

import java.util.regex.Pattern;

/**
 * The three ways a database call of the bootstrap registration can end other than normally (ADR-018 D8, ADR-008 R4).
 * None has a message, a cause or a stack trace, so nothing the driver put into its text (the failing row: email, hash,
 * ticket) can reach a log line, a "Resolved [...]" line or a response.
 */
final class BootstrapExceptions {

    private BootstrapExceptions() {
    }

    /** SQLSTATE {@code SB001}: an admin exists. The caller answers the empty 404 and rolls back. */
    static final class Closed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Closed() {
            super(null, null, false, false);
        }

        @Override
        public String toString() {
            return "BootstrapExceptions.Closed";
        }
    }

    /** SQLSTATE {@code 55P03}: the wait for the row (or the advisory lock) was over 10 seconds. 429, nothing counted. */
    static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Busy() {
            super(null, null, false, false);
        }

        @Override
        public String toString() {
            return "BootstrapExceptions.Busy";
        }
    }

    /** Any other failure. Keeps only the five-character SQLSTATE, when one was found. */
    static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

        private final String sqlState;

        Failed(String sqlState) {
            super(null, null, false, false);
            this.sqlState = sqlState != null && SQL_STATE.matcher(sqlState).matches() ? sqlState : null;
        }

        String sqlState() {
            return sqlState;
        }

        @Override
        public String toString() {
            return "BootstrapExceptions.Failed[sqlState=" + (sqlState == null ? "none" : sqlState) + "]";
        }
    }
}
