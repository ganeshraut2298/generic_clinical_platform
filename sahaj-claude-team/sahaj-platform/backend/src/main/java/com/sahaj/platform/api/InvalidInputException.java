package com.sahaj.platform.api;

/**
 * A request that is well formed but breaks a rule (a price of ₹0 on a new service, a follow-up
 * window of 40 days). The platform error handler turns it into a 400 problem with one field error.
 * The message is shown to the user, so it must be plain English and must not echo what was typed.
 */
public class InvalidInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public InvalidInputException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
