package com.sahaj.platform.api;

/**
 * Thrown when a record does not exist for the current tenant. The platform error handler turns it
 * into a 404 RFC 9457 problem. A record that belongs to another tenant is reported the same way,
 * so the API never confirms that it exists elsewhere (ADR-002 D6).
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
