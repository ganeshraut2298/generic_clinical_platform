package com.sahaj.platform.api;

import java.util.UUID;

/**
 * Owner-only check for clinic masters (SAH-7 AC4). The role in the access token is not trusted
 * for a change: the person's membership is read again, so an owner who was demoted a moment ago
 * is refused at once. Must be called inside the caller's transaction (the clinic is bound there).
 * Refusal is an {@link org.springframework.security.access.AccessDeniedException}, mapped to 403.
 */
public interface OwnerGuard {

    void requireOwner(UUID tenantId, UUID userId);
}
