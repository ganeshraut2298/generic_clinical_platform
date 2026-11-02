package com.sahaj.platform.application;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The narrow, privileged service the SAH-3 verify step needs (story section 3): one-time code
 * confirmation happens before any tenant is chosen or bound, but tenant creation and the
 * "which clinics does this user belong to" lookup both need a database principal bound for row
 * level security to let them through. This port runs one action with a transient principal
 * bound for its duration only, then always restores whatever was there before.
 *
 * <p>No tenant id and no user id ever comes from here into the request body, header or query -
 * callers pass in a value only after it has already been established (a verified
 * {@code app_user.id}, or a freshly generated id this same call is about to insert).
 */
public interface AuthenticationScope {

    /** Runs {@code action} as a full session for the given user in the given tenant. */
    <T> T runAsTenant(UUID userId, UUID tenantId, Supplier<T> action);

    /**
     * Runs {@code action} with only {@code app.user_id} bound (DEC-007): membership self-read
     * (migration 1011) and refresh token self-read/self-revoke (migration 1012), nothing else.
     */
    <T> T runAsVerifiedUser(UUID userId, Supplier<T> action);

    /**
     * Runs {@code action} with only {@code app.tenant_id} bound, from a tenant id that is not yet
     * confirmed to belong to anyone (the prefix of a presented refresh token cookie, before its
     * hash is matched against a stored row).
     */
    <T> T runAsTenantOnly(UUID tenantId, Supplier<T> action);
}
