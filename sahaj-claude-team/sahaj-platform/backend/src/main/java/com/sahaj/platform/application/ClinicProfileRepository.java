package com.sahaj.platform.application;

import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.clinic_profile} (SAH-4 {@code complete}, SAH-7 edits). Needs {@code app.tenant_id} bound. */
public interface ClinicProfileRepository {

    /** Inserts or replaces the one profile row for the bound tenant. */
    void upsert(UUID tenantId, String name, String address, String city, String pin, String phone,
                String registrationNo, String specialtyCode, String brand, String defaultLanguage);

    /** The bound tenant's city, shown as the header's "area" (SAH-5 {@code GET /me}). */
    Optional<String> findCity(UUID tenantId);

    /** SAH-7: the whole profile, with the clinic's web address from the tenant row. */
    Optional<ProfileRow> find(UUID tenantId);

    /** SAH-7: replaces every editable field below; the specialty and the web address are not touched. */
    void update(UUID tenantId, ProfileRow row, UUID actorUserId);

    /** SAH-7: the specialty chosen on Specialty template. The database allows only the Phase 1 codes. */
    void setSpecialty(UUID tenantId, String specialtyCode, UUID actorUserId);

    /**
     * One clinic profile. {@code city} is the API's {@code area} as well (SAH-5 shows the city as the
     * area). {@code brandHex} is set only when {@code brand} is {@code custom}.
     */
    record ProfileRow(String name, String nameLocal, String address, String city, String pin, String phone,
                      String whatsapp, String mapsUrl, String registrationNo, String slug, String specialtyCode,
                      String brand, String brandHex) {
    }
}
