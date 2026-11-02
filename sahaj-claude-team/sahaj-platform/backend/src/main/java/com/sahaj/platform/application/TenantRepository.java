package com.sahaj.platform.application;

import com.sahaj.platform.domain.TenantProfile;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.tenant}. */
public interface TenantRepository {

    /**
     * Inserts a new clinic with the given id (chosen before insert, so it can be bound first - ADR-002 D4).
     * {@code createdAt} is written explicitly (not left to a database default) so it is the exact same
     * instant {@code planEndsAt} was computed from - otherwise the few milliseconds between the caller
     * reading the clock and the database's own {@code now()} can truncate "12 months" down to 11
     * (SAH-3 AC13).
     */
    void insert(UUID id, String name, Instant createdAt, Instant planEndsAt);

    /** The name of the bound tenant. Needs {@code app.tenant_id} bound to this same id (SAH-1 {@code p_tenant_self}). */
    Optional<String> findName(UUID tenantId);

    /** The bound tenant's name, brand, plan and plan end date (SAH-5 {@code GET /me}). */
    Optional<TenantProfile> findProfile(UUID tenantId);

    /**
     * SAH-4 AC3: checked across every tenant, not only the bound one, through a narrow database
     * function that returns only a boolean (story technical design) - the application never reads
     * another clinic's row directly.
     */
    boolean isSlugAvailable(String slug);

    /**
     * SAH-4 "Create my clinic": claims the chosen web address and saves the chosen name and
     * colour on the bound tenant. Throws {@link org.springframework.dao.DataIntegrityViolationException}
     * when another clinic claimed the same slug first; the caller maps that to {@link SlugTakenException}.
     */
    void claimSlugAndBrand(UUID tenantId, String name, String brand, String slug);

    /** SAH-7: keeps the clinic list and the header in step with the Clinic profile (name and brand). */
    void updateNameAndBrand(UUID tenantId, String name, String brand);
}
