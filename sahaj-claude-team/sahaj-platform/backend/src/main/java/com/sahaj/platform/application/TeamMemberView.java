package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code TeamMember} in {@code docs/api/platform.yaml} (SAH-6). {@code id} is the membership id.
 * The mobile is already masked here: the full number never leaves the application layer.
 */
public record TeamMemberView(UUID id, String name, String mobileMasked, MembershipRole role,
                              MembershipStatus status, Instant lastLoginAt, DoctorView doctor) {

    public record DoctorView(String specialty, String degree, String registrationNo, Integer feeRupees) {
    }
}
