package com.sahaj.patient.web;

/** Role expressions for the patient endpoints (SAH-8 AC8): everyone signed in to a clinic reads; only OWNER and FRONT_DESK write. */
final class PatientRoles {

    static final String READ = "hasAnyRole('OWNER','FRONT_DESK','NURSE','DOCTOR','VISITING_DOCTOR')";
    static final String WRITE = "hasAnyRole('OWNER','FRONT_DESK')";

    private PatientRoles() {
    }
}
