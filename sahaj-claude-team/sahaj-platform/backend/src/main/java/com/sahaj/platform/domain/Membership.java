package com.sahaj.platform.domain;

import java.util.UUID;

/** A person's role in one clinic (SAH-1, {@code platform.membership}). */
public record Membership(UUID id, UUID tenantId, UUID userId, MembershipRole role, MembershipStatus status) {
}
