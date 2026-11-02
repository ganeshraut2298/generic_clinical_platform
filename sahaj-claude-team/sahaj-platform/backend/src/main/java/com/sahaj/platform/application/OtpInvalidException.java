package com.sahaj.platform.application;

/** The code is wrong, expired, already used, or there is no challenge for this mobile. Mapped to HTTP 401. */
public class OtpInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OtpInvalidException(String message) {
        super(message);
    }
}
