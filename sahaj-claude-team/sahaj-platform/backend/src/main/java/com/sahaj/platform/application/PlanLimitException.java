package com.sahaj.platform.application;

/** SAH-4 AC4: adding a 3rd doctor, or a 6th user in total, on the Free or Professional plan. Mapped to HTTP 409. */
public class PlanLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlanLimitException(String message) {
        super(message);
    }
}
