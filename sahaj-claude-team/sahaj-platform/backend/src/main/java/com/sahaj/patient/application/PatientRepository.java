package com.sahaj.patient.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code patient.patient} (SAH-8). Needs {@code app.tenant_id} bound. */
public interface PatientRepository {

    /**
     * A page of the bound clinic's patients, merged-away records excluded, newest-to-oldest name
     * order. {@code filter} is one of {@code all}, {@code new}, {@code chronic}, {@code kids},
     * {@code seniors}; any other value (including the visit-data filters the service already
     * short-circuits) behaves like {@code all}. {@code q}, lower-cased or null, matches the name,
     * the UHID or the mobile number.
     */
    PatientPage search(UUID tenantId, String q, String filter, int limit, int offset);

    /** Another clinic's id, or any id at all, is simply absent. Returns a merged-away record too. */
    Optional<PatientRow> findById(UUID tenantId, UUID id);

    /** SAH-24: several records in one query; unknown or other-clinic ids are absent. */
    List<PatientRow> findByIds(UUID tenantId, Collection<UUID> ids);

    /** Every active (not merged-away) record sharing this mobile number, excluding {@code excludeId}. */
    List<PatientRow> findFamily(UUID tenantId, String mobile, UUID excludeId);

    void insert(UUID id, String name, int age, String sex, String mobile, String area, String language,
                String allergy, String chronic, boolean abhaLinked, UUID actorUserId);

    void update(UUID id, String name, int age, String sex, String area, String language, String allergy,
                String chronic, boolean abhaLinked, UUID actorUserId);

    /** Soft merge: points {@code id} at {@code keepId}. The row is never removed. */
    void setMergedInto(UUID id, UUID keepId, UUID actorUserId);

    record PatientRow(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                       String language, String allergy, String chronic, boolean abhaLinked, UUID mergedIntoId,
                       Instant createdAt, long version) {
    }

    record PatientPage(List<PatientRow> items, long total) {
    }
}
