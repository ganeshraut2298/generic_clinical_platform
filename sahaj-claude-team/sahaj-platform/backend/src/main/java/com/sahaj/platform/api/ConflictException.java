package com.sahaj.platform.api;

/**
 * A change that clashes with what is already saved (a duplicate service name, a holiday on a date
 * that already has one). The platform error handler turns it into a 409 problem whose
 * {@code type} ends in {@code problemType}, whose title is {@code title} and whose detail is the
 * message. The message is shown to the user, so it must be plain English and carry no personal data.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String problemType;
    private final String title;

    public ConflictException(String problemType, String title, String message) {
        super(message);
        this.problemType = problemType;
        this.title = title;
    }

    public String problemType() {
        return problemType;
    }

    public String title() {
        return title;
    }
}
