package com.sahaj.billing.web;

/** Role expressions for the billing endpoints (SAH-10): anyone signed in to a clinic reads; collecting money is owner/front desk; a refund is owner only. */
final class BillingRoles {

    static final String ANY_CLINIC_ROLE =
            "hasAnyRole('OWNER','DOCTOR','VISITING_DOCTOR','NURSE','FRONT_DESK','BILLING','PHARMACIST','COUNSELLOR')";
    static final String COLLECT = "hasAnyRole('OWNER','FRONT_DESK')";
    static final String OWNER = "hasRole('OWNER')";

    private BillingRoles() {
    }
}
