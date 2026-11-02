package com.sahaj.opd.web;

/** Role expressions for the OPD endpoints (SAH-7): anyone signed in to a clinic reads; only the owner changes. */
final class OpdRoles {

    static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";
    static final String OWNER = "hasRole('OWNER')";

    /** SAH-9 AC1, AC8: who may give out a walk-in token. The service re-checks this too. */
    static final String ISSUE_TOKEN = "hasAnyRole('OWNER','FRONT_DESK','NURSE')";

    /** SAH-11: who may change a doctor's slot settings or an appointment (book, cancel, no-show, token). */
    static final String OWNER_OR_FRONT_DESK = "hasAnyRole('OWNER','FRONT_DESK')";

    private OpdRoles() {
    }
}
