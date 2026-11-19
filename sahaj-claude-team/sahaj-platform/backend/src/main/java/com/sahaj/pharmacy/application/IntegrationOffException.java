package com.sahaj.pharmacy.application;

/**
 * 409 {@code integration-off} (docs/api/pharmacy.yaml components/responses/IntegrationOff, ADR-028 D5): an operation that
 * exists only in Integrated mode was called while the unit is Standalone. Nothing is written. No 0.1.x operation throws it;
 * the SAH-79 and SAH-80 operations that need OPD or IPD throw it unchanged, so there is one shared answer. The text is
 * fixed plain English and carries no value.
 */
public class IntegrationOffException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String TITLE = "Not used in Standalone mode";
    public static final String DETAIL =
            "This works only when OPD and IPD are connected. Change Integration mode in Unit settings.";

    public IntegrationOffException() {
        super(DETAIL);
    }
}
