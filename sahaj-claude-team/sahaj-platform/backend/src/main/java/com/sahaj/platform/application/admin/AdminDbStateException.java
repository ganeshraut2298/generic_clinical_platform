package com.sahaj.platform.application.admin;

/**
 * A database function refused with one of the SQLSTATEs of docs/database/ADM-1.md. The message is
 * fixed text; it never carries a value from the request.
 */
public class AdminDbStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** SQLSTATE meaning, in the words of ADM-1.md. */
    public enum State {
        /** 42501: no live admin session, or a clinic session is bound. */
        NO_SESSION("42501"),
        /** SA001: pre-auth token unknown, expired or used. */
        PREAUTH_INVALID("SA001"),
        /** SA002: TOTP step already used. */
        TOTP_REPLAY("SA002"),
        /** SA003: recovery code unknown or already used. */
        RECOVERY_INVALID("SA003"),
        /** SA004: admin not found, switched off or not eligible. */
        ADMIN_UNAVAILABLE("SA004"),
        /** SA005: recovery set is not ten distinct hashes. */
        RECOVERY_SET_INVALID("SA005"),
        /** SA006: no pending authenticator secret on the enrolment token. */
        NO_PENDING_SECRET("SA006"),
        /** SA007: the admin is already enrolled. */
        ALREADY_ENROLLED("SA007"),
        /** 22023: a parameter the function refused. */
        BAD_PARAMETER("22023");

        private final String sqlState;

        State(String sqlState) {
            this.sqlState = sqlState;
        }

        public String sqlState() {
            return sqlState;
        }

        public static State fromSqlState(String sqlState) {
            for (State state : values()) {
                if (state.sqlState.equals(sqlState)) {
                    return state;
                }
            }
            return null;
        }
    }

    private final State state;

    public AdminDbStateException(State state) {
        super("Admin database function refused: " + state.name());
        this.state = state;
    }

    public State state() {
        return state;
    }
}
