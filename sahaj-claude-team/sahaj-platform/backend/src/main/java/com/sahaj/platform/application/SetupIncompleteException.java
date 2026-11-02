package com.sahaj.platform.application;

/** SAH-4: "Create my clinic" called before all five steps have been answered. Mapped to HTTP 400. */
public class SetupIncompleteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SetupIncompleteException(String message) {
        super(message);
    }
}
