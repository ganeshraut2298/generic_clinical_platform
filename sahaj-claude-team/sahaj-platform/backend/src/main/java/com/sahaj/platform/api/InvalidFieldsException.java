package com.sahaj.platform.api;

import java.util.List;

/**
 * A well formed request that breaks rules on several fields at once (SAH-28 quick registration: "all field errors
 * together"). The platform error handler turns it into a 400 problem of type validation with one field error per
 * entry. The messages are shown to the user, so they are fixed plain English and never echo what was typed.
 */
public class InvalidFieldsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldMessage> errors;

    private final boolean firstMessageAsDetail;

    public InvalidFieldsException(List<FieldMessage> errors) {
        this(errors, false);
    }

    /** When {@code firstMessageAsDetail} is true the problem detail is the first field message (SAH-30). */
    public InvalidFieldsException(List<FieldMessage> errors, boolean firstMessageAsDetail) {
        super("Some fields are missing or not valid.");
        this.errors = List.copyOf(errors);
        this.firstMessageAsDetail = firstMessageAsDetail;
    }

    public boolean firstMessageAsDetail() {
        return firstMessageAsDetail;
    }

    public List<FieldMessage> errors() {
        return errors;
    }

    /** One field and the message to show next to it. */
    public record FieldMessage(String field, String message) {
    }
}
