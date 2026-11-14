package com.sahaj.patient.application;

import java.time.Instant;
import java.time.LocalDate;
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
     * the UHID or the mobile number. SAH-73: {@code today} (India) gives the date bounds of the {@code kids} (under 13)
     * and {@code seniors} (60 or over) chips: dated patients by date of birth, the others by the stored age. The
     * date of birth is never searched and never part of a list row.
     */
    PatientPage search(UUID tenantId, String q, String filter, int limit, int offset, LocalDate today);

    /** Another clinic's id, or any id at all, is simply absent. Returns a merged-away record too. */
    Optional<PatientRow> findById(UUID tenantId, UUID id);

    /** SAH-24: several records in one query; unknown or other-clinic ids are absent. */
    List<PatientRow> findByIds(UUID tenantId, Collection<UUID> ids);

    /** Every active (not merged-away) record sharing this mobile number, excluding {@code excludeId}. */
    List<PatientRow> findFamily(UUID tenantId, String mobile, UUID excludeId);

    /** {@code dateOfBirth} is null when not recorded; {@code age} is the fallback snapshot (ADR-024 D4). */
    void insert(UUID id, String name, int age, LocalDate dateOfBirth, String sex, String mobile, String area,
                String language, String allergy, String chronic, boolean abhaLinked, UUID actorUserId);

    void update(UUID id, String name, int age, LocalDate dateOfBirth, String sex, String area, String language,
                String allergy, String chronic, boolean abhaLinked, UUID actorUserId);

    /** The record locked until the end of the transaction (the EMR write-back of SAH-68-3). */
    Optional<PatientRow> findByIdForUpdate(UUID tenantId, UUID id);

    /**
     * SAH-68-3 (ADR-025 D13): sets the allergy and/or long-term conditions text from the EMR; a null argument leaves that
     * field alone. {@code allergy_recorded_at/by} are set together with the allergy text. Bumps the version.
     */
    void updateClinicalFacts(UUID id, String allergy, String chronic, UUID actorUserId);

    /** Soft merge: points {@code id} at {@code keepId}. The row is never removed. */
    void setMergedInto(UUID id, UUID keepId, UUID actorUserId);

    /**
     * One row. {@code age} is the stored whole number: while {@code dateOfBirth} is present it is only a fallback
     * snapshot and is never read for display or rules (ADR-024 D1, D4). The date of birth is personal data, so
     * {@code toString} shows the id only.
     */
    record PatientRow(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                       String language, String allergy, String chronic, boolean abhaLinked, UUID mergedIntoId,
                       Instant createdAt, long version, LocalDate dateOfBirth) {

        /** A row with no date of birth (the shape before SAH-73). */
        public PatientRow(UUID id, String uhid, String name, int age, String sex, String mobile, String area,
                           String language, String allergy, String chronic, boolean abhaLinked, UUID mergedIntoId,
                           Instant createdAt, long version) {
            this(id, uhid, name, age, sex, mobile, area, language, allergy, chronic, abhaLinked, mergedIntoId,
                    createdAt, version, null);
        }

        @Override
        public String toString() {
            return "PatientRow[id=" + id + "]";
        }
    }

    record PatientPage(List<PatientRow> items, long total) {
    }
}
