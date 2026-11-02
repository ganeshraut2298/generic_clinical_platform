package com.sahaj.platform.application;

/** SAH-6: a team change that clashes with the current state of the team. Mapped to HTTP 409. */
public class TeamConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What clashed. The platform error handler picks the problem type and message from this. */
    public enum Kind {
        /** AC10: the change would leave the clinic with no active owner. */
        LAST_OWNER,
        /** AC12: the mobile number is already on this team. */
        DUPLICATE_MOBILE,
        /** Two changes met (deadlock or lost race); the caller may simply try again. */
        CONCURRENT_CHANGE
    }

    private final Kind kind;

    public TeamConflictException(Kind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
