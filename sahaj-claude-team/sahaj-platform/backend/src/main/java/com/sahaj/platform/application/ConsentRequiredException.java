package com.sahaj.platform.application;

/** AC12: sign-up for a brand new clinic needs Terms and Privacy consent. Mapped to HTTP 400. */
public class ConsentRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConsentRequiredException() {
        super("Accept the Terms and Privacy to continue.");
    }
}
