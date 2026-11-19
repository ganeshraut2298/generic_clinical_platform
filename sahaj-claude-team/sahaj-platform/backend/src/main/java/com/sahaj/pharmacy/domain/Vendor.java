package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code pharmacy.vendor} (SAH-78-8). {@code active} is the stored status (ACTIVE or INACTIVE). "Blocked" is not
 * stored: it is worked out from the licence valid-till date and today's IST date on every read, valid through the date and
 * blocked from the next day (OQ-78-23). {@code damagedCreditPercent} and {@code expiredCreditPercent} (0.2.1, DEC-075) are
 * optional credit levels for vendor returns (SAH-79 WP-E); null means the standard {@code returnCreditPercent} applies.
 */
public record Vendor(UUID id, String name, String gstin, boolean licenceRequired, String licenceNo,
                     LocalDate licenceValidTill, String contactName, String contactPhone, int creditDays,
                     Integer returnDaysBeforeExpiry, BigDecimal returnCreditPercent, BigDecimal damagedCreditPercent,
                     BigDecimal expiredCreditPercent, boolean active, long version) {

    /** The licence state on {@code today}: NOT_REQUIRED, EXPIRED (valid-till before today) or VALID. */
    public LicenceState licenceState(LocalDate today) {
        return LicenceState.of(licenceRequired, licenceValidTill, today);
    }

    /** True for an ACTIVE vendor whose licence has expired; an INACTIVE vendor is INACTIVE, not Blocked. */
    public boolean blocked(LocalDate today) {
        return active && licenceState(today) == LicenceState.EXPIRED;
    }

    /** ACTIVE, BLOCKED or INACTIVE. */
    public String status(LocalDate today) {
        if (!active) {
            return "INACTIVE";
        }
        return blocked(today) ? "BLOCKED" : "ACTIVE";
    }
}
