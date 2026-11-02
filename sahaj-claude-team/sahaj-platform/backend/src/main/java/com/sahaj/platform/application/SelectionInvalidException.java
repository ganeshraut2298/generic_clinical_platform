package com.sahaj.platform.application;

/** The selection token is missing, expired or not well-formed. Mapped to HTTP 401. */
public class SelectionInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SelectionInvalidException(String message) {
        super(message);
    }
}
