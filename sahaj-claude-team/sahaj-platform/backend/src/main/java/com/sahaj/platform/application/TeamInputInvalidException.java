package com.sahaj.platform.application;

/** SAH-6: a request that is well formed but breaks a team rule (for example switching the owner off in the matrix). Mapped to HTTP 400. */
public class TeamInputInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TeamInputInvalidException(String message) {
        super(message);
    }
}
