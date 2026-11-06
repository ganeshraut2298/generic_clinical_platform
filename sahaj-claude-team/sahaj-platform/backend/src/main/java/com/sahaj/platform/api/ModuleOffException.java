package com.sahaj.platform.api;

/**
 * Thrown when a clinic calls an endpoint of an add-on module it has not switched on (SAH-20: HRA).
 * The platform error handler turns it into a 403 problem whose type ends in {@code module-off}.
 * The message is fixed plain English and carries no personal data.
 */
public class ModuleOffException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModuleOffException(String message) {
        super(message);
    }
}
