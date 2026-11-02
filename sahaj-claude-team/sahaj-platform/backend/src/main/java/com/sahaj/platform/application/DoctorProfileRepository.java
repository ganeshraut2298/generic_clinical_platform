package com.sahaj.platform.application;

import java.util.UUID;

/** Port over {@code platform.doctor_profile} (SAH-4 {@code complete}). Needs {@code app.tenant_id} bound. */
public interface DoctorProfileRepository {

    void insert(UUID membershipId, UUID tenantId, String specialty, long feePaise, String degree, String registrationNo);
}
