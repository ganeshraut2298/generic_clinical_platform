package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.PinState;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code TeamMember} in {@code docs/api/platform.yaml} (SAH-6). {@code id} is the membership id.
 * The mobile is already masked here: the full number never leaves the application layer.
 * {@code pin} (SAH-31, ADR-010 D12) is filled for the owner on every row and for the caller's own row, otherwise null.
 */
public record TeamMemberView(UUID id, String name, String mobileMasked, MembershipRole role,
                              MembershipStatus status, Instant lastLoginAt, DoctorView doctor, PinView pin) {

    /** A member without PIN information (the row is not the caller's and the caller is not the owner). */
    public TeamMemberView(UUID id, String name, String mobileMasked, MembershipRole role, MembershipStatus status,
                          Instant lastLoginAt, DoctorView doctor) {
        this(id, name, mobileMasked, role, status, lastLoginAt, doctor, null);
    }

    public record DoctorView(String specialty, String degree, String registrationNo, Integer feeRupees) {
    }

    /** {@code TeamMemberPin}: state, when the current PIN was set (SET and LOCKED) and the end of a lock (LOCKED). */
    public record PinView(PinState state, Instant setAt, Instant lockedUntil) {
    }
}
