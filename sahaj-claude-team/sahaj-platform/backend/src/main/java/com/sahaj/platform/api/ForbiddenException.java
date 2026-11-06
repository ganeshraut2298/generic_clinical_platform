package com.sahaj.platform.api;

/**
 * A request the signed-in person may not make for a business reason that the user must be told about (for example
 * moving a patient to a dearer bed category when only the owner may). The platform error handler turns it into a
 * 403 problem whose {@code type} ends in {@code problemType}, whose title is {@code title} and whose detail is the
 * message. The message is shown to the user, so it must be plain English and carry no personal data.
 */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String problemType;
    private final String title;

    public ForbiddenException(String problemType, String title, String message) {
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
