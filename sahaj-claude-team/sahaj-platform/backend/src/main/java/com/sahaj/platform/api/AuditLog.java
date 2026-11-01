package com.sahaj.platform.api;

import java.util.Map;
import java.util.UUID;

/**
 * Audit port of the {@code platform} module (SAH-1, ADR-002 D7).
 *
 * <p>Other modules call this inside their own transaction, so the audit row and the change it
 * describes commit or roll back together. The tenant and the actor are taken from the logged-in
 * user; they are never passed in. A call with no active transaction, or with no logged-in user
 * that belongs to a clinic, fails: an unaudited change is not allowed to succeed quietly.
 *
 * <p>Callers pass identifiers and field values, never patient names, mobile numbers or clinical
 * text. As a second line of defence the implementation masks any {@code mobile}, {@code phone} or
 * {@code whatsapp} value to its last four digits and replaces {@code otp}, {@code otpCode},
 * {@code pin}, {@code password}, {@code secret} and {@code token} values before anything is stored.
 */
public interface AuditLog {

    /**
     * Records one change for the current tenant.
     *
     * @param action     what happened, for example {@code CLINIC_PROFILE_UPDATED} (1 to 100 characters)
     * @param entityType the kind of record that changed, for example {@code TENANT} (1 to 100 characters)
     * @param entityId   the record that changed; may be null for changes that have no single record
     * @param before     the values before the change, or null for a creation
     * @param after      the values after the change, or null for a removal
     */
    void record(String action, String entityType, UUID entityId,
                Map<String, Object> before, Map<String, Object> after);
}
