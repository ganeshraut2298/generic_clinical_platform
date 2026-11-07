package com.sahaj.platform.application;

import java.util.UUID;

/**
 * Request limits of the staff PIN (SAH-31, ADR-010 D13). The in-memory implementation is per application instance
 * (phase 1 runs one); the lockout in the database stays the real limit on guessing.
 *
 * <p>The {@code ...Wait} methods that count one attempt answer {@code 0} when it may go ahead or the seconds to wait.
 * {@link #pinCheckWait} only looks; {@link #pinCheckFailed} is what counts.
 */
public interface StaffPinLimits {

    /**
     * 10 FAILED PIN checks per 5 minutes per person ({@code StaffPinPort.check}; since SAH-38, ADR-014 D7, only the
     * person's OWN checks: colleague checks no longer read or fill the colleague's bucket). Only looks: answers {@code 0} or the
     * seconds to wait and never uses allowance, so successful checks never count (review R1). Call it before comparing
     * the PIN, and {@link #pinCheckFailed} after a wrong one.
     */
    long pinCheckWait(UUID tenantId, UUID userId);

    /** Counts one wrong PIN toward the limit of {@link #pinCheckWait}. */
    void pinCheckFailed(UUID tenantId, UUID userId);

    /**
     * Per CALLER: 5 failed colleague checks per 15 minutes and 10 per 24 hours, any colleague (SAH-32, ADR-011 D7). Only
     * looks: answers {@code 0} or the seconds to wait (the longer of the two windows when both are full) and never uses
     * allowance. Call it before any hash, and {@link #colleagueCheckFailed} after a wrong colleague PIN. Still in
     * memory until HARD-2; the durable limit on one caller and one colleague is the pair cap in the database (SAH-38).
     */
    long colleagueCheckWait(UUID tenantId, UUID callerUserId);

    /** Counts one wrong colleague PIN against the caller in both windows of {@link #colleagueCheckWait}. */
    void colleagueCheckFailed(UUID tenantId, UUID callerUserId);

    /** 10 PIN set or change attempts per 5 minutes per person ({@code PUT /me/pin}), a bucket of its own. */
    long pinSetWait(UUID tenantId, UUID userId);

    /** 5 code requests per person per hour ({@code POST /me/pin/code}). */
    long codeRequestWait(UUID tenantId, UUID userId);

    /** 30 owner resets per owner per hour ({@code POST /team/{id}/pin/reset}). */
    long resetWait(UUID tenantId, UUID ownerUserId);
}
