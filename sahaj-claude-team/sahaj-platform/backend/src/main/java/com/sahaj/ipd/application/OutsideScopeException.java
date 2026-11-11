package com.sahaj.ipd.application;

import com.sahaj.platform.api.ForbiddenException;

/**
 * SAH-34 (ADR-017 D2): the admission exists in the caller's clinic but is not in the caller's patient scope. The platform
 * error handler answers it as 403 {@code https://sahaj.in/problems/outside-scope}, title "Outside your scope", detail
 * "This patient is not in your wards.". The body never holds a name, a bed, a ward or a UHID: the message is fixed text.
 */
public class OutsideScopeException extends ForbiddenException {

    private static final long serialVersionUID = 1L;

    public static final String TYPE = "outside-scope";
    public static final String TITLE = "Outside your scope";
    public static final String DETAIL = "This patient is not in your wards.";

    public OutsideScopeException() {
        super(TYPE, TITLE, DETAIL);
    }
}
