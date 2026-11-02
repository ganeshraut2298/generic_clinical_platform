package com.sahaj.platform.application;

/** SAH-4 AC3: the chosen web address was taken by another clinic between the check and "Create my clinic". Mapped to HTTP 409. */
public class SlugTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SlugTakenException(String message) {
        super(message);
    }
}
