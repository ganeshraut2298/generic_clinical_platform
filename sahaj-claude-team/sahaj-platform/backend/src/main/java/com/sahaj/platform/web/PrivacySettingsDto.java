package com.sahaj.platform.web;

import com.sahaj.platform.application.PrivacySettingsRepository.PrivacyRow;

/** {@code PrivacySettings} in {@code docs/api/platform.yaml}; validated by {@code PrivacyService}. */
public record PrivacySettingsDto(Boolean consentRequired, Boolean promoConsent, Boolean recordCopy, String hfrId) {

    public static PrivacySettingsDto from(PrivacyRow row) {
        return new PrivacySettingsDto(row.consentRequired(), row.promoConsent(), row.recordCopy(), row.hfrId());
    }
}
