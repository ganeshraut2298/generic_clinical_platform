package com.sahaj.platform.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The display name a clinic sees for one of its own users (SAH-10): the clinic's own display name
 * for them (membership) if set, otherwise their account name, the same lookup
 * {@code platform.audit_event}'s own {@code actorName} uses. Needed by {@code billing} for
 * {@code Payment.recordedByName} and {@code DayClosing.closedByName} without joining
 * {@code platform.membership}/{@code platform.app_user} directly. Runs in the caller's transaction
 * (tenant already bound).
 */
public interface ActorDirectoryPort {

    /** Another clinic's user id, or any id at all, is simply absent. */
    Optional<String> displayName(UUID tenantId, UUID userId);
}
