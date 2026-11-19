package com.sahaj.pharmacy.web;

/**
 * The body of a Pharmacy and Inventory request could not be read: it is missing, is not JSON, carries a field the closed
 * body does not list, or has a value of the wrong type. {@link PharmacyBodies} raises it when the service asks for the
 * body, which is after the module and access-level checks, so a caller who is refused with 403 never learns that the body
 * was wrong. {@link PharmacyProblemHandler} turns it into the same 400 problems as an unreadable bound body. The cause is
 * the Jackson error, which the handler reads for the field name only; it is never sent to the client.
 */
final class UnreadableBodyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnreadableBodyException(Throwable cause) {
        super("The request body could not be read.", cause);
    }
}
