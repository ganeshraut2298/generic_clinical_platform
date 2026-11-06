package com.sahaj.platform.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.privacy_settings} (SAH-17): at most one row per clinic, created on first save. */
public interface PrivacySettingsRepository {

    /** Empty until the clinic saves once. */
    Optional<PrivacyRow> find(UUID tenantId);

    /** Inserts the clinic's single row or overwrites it ({@code on conflict (tenant_id)}). */
    void upsert(UUID tenantId, PrivacyRow row, UUID actorUserId);

    record PrivacyRow(boolean consentRequired, boolean promoConsent, boolean recordCopy, String hfrId) {
    }
}
