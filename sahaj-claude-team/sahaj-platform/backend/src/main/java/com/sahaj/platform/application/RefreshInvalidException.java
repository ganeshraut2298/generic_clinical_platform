package com.sahaj.platform.application;

/** The refresh cookie is missing, not well-formed, unknown, reused, expired or revoked. Mapped to HTTP 401. */
public class RefreshInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefreshInvalidException(String message) {
        super(message);
    }
}
