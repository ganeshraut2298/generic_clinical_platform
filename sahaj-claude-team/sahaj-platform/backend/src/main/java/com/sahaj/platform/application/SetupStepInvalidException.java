package com.sahaj.platform.application;

/** SAH-4: a step body that cannot be read as that step's shape at all (wrong types, wrong step). Mapped to HTTP 400. */
public class SetupStepInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SetupStepInvalidException(String message) {
        super(message);
    }
}
