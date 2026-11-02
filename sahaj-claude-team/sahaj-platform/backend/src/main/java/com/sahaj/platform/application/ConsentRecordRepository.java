package com.sahaj.platform.application;

import java.util.UUID;

/** Port over {@code platform.consent_record} (no tenant, no row level security; append-only). */
public interface ConsentRecordRepository {

    void insert(UUID userId, String termsVersion, String privacyVersion, String sourceIp);
}
