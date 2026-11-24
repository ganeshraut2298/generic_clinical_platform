package com.sahaj.pharmacy.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The unit row of a clinic ({@code pharmacy.unit}, one per tenant, created by the first master write). {@code updatedAt}
 * is set only by a save of the unit settings, so {@code saved()} tells "the owner has saved settings" from "the row exists
 * because a store was created".
 */
public record Unit(UUID id, String code, String gstin, String drugLicenceNo, LocalDate drugLicenceValidTill,
                   IntegrationMode integrationMode, Instant updatedAt, long version, Integer returnWindowDays,
                   List<String> refundModes, String scheduleXLicenceNo, LocalDate scheduleXLicenceValidTill) {

    /** A unit with no sale settings (SAH-80-1): no return window, no refund mode, no Schedule X licence. */
    public Unit(UUID id, String code, String gstin, String drugLicenceNo, LocalDate drugLicenceValidTill,
                IntegrationMode integrationMode, Instant updatedAt, long version) {
        this(id, code, gstin, drugLicenceNo, drugLicenceValidTill, integrationMode, updatedAt, version, null, List.of(),
                null, null);
    }

    public boolean saved() {
        return updatedAt != null;
    }
}
