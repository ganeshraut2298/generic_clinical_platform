package com.sahaj.platform.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.tenant}. */
public interface TenantRepository {

    /** Inserts a new clinic with the given id (chosen before insert, so it can be bound first - ADR-002 D4). */
    void insert(UUID id, String name, Instant planEndsAt);

    /** The name of the bound tenant. Needs {@code app.tenant_id} bound to this same id (SAH-1 {@code p_tenant_self}). */
    Optional<String> findName(UUID tenantId);
}
