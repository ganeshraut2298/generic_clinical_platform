package com.sahaj.platform.devtools.admin;

/** Not an error to report: the endpoint answers the same empty 404 as every other "no". Carries no detail. */
final class DevSampleAdminUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    DevSampleAdminUnavailableException() {
        super("The dev-only sample admin is not available", null, false, false);
    }
}
