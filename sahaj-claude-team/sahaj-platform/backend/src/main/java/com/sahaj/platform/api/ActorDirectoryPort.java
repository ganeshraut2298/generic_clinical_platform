package com.sahaj.platform.api;

import java.util.Collection;
import java.util.Map;
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

    /**
     * SAH-33: the display names of many users in one query, keyed by user id, for a list that names several people
     * (the Safety board, the clinical audit trail) without one lookup per row. A user id that is unknown, or belongs to
     * another clinic, is simply absent from the map. Same naming rule as {@link #displayName}.
     */
    Map<UUID, String> displayNames(UUID tenantId, Collection<UUID> userIds);
}
