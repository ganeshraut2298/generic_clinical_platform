package com.sahaj.pharmacy.domain;

import java.time.LocalDate;

/** Where a vendor's drug licence stands (docs/api/pharmacy.yaml Vendor.licenceState). Never stored. */
public enum LicenceState {
    VALID,
    EXPIRED,
    NOT_REQUIRED;

    /**
     * A vendor that does not need a licence is NOT_REQUIRED and never blocked. Otherwise the licence is VALID on its
     * valid-till date and EXPIRED from the next day, with no grace days. A missing date (not possible for a vendor that
     * needs a licence) counts as VALID.
     */
    public static LicenceState of(boolean licenceRequired, LocalDate validTill, LocalDate today) {
        if (!licenceRequired) {
            return NOT_REQUIRED;
        }
        return validTill != null && validTill.isBefore(today) ? EXPIRED : VALID;
    }
}
