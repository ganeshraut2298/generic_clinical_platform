package com.sahaj.platform.application;

/** AC5: a staff member whose access was removed or switched off is refused. Mapped to HTTP 403. */
public class AccessRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessRefusedException(String message) {
        super(message);
    }
}
