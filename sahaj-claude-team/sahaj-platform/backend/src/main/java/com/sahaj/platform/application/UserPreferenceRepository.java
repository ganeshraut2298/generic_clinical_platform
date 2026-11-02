package com.sahaj.platform.application;

import com.sahaj.platform.domain.UserPreference;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.user_preference} (SAH-5). Needs {@code app.tenant_id} bound. */
public interface UserPreferenceRepository {

    /** This user's own saved preferences in the bound tenant, or empty before they ever saved any. */
    Optional<UserPreference> find(UUID tenantId, UUID userId);

    /** Inserts or replaces this user's one preference row in the bound tenant. */
    void upsert(UUID tenantId, UUID userId, UserPreference preference);
}
