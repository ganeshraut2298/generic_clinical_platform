package com.sahaj.opd.application.emr.specialty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.emr_cancer_journey} (SAH-75-7; ADR-026 D7; docs/database/SAH-75.md 2.3): the patient's Cancer
 * journey as append-only versions, the highest version being the current one. Clinical data: never deleted or updated
 * (the grants, the policies and a trigger all refuse it), never logged; {@code toString} shows ids and the version only.
 * Row level security limits every statement to the bound clinic and the tenant id is in each statement too.
 */
public interface CancerJourneyRepository {

    /** One version. An empty value is null (the table refuses an empty string). */
    record JourneyRow(UUID id, UUID patientId, int version, String stageGroup, String tnmText, String markersText,
                      Integer ki67Pct, String geneticsText, String intent, String setting, Integer lineNo,
                      LocalDate boardDate, UUID sourceRecordId, UUID recordedByMembershipId, UUID recordedByUserId,
                      Instant recordedAt) {

        @Override
        public String toString() {
            return "JourneyRow[id=" + id + ", version=" + version + "]";
        }
    }

    /** The patient's highest version, if any. */
    Optional<JourneyRow> latest(UUID tenantId, UUID patientId);

    /**
     * Appends a version ({@code row.id()} and {@code row.recordedAt()} are the database's). A second insert of the same
     * (clinic, patient, version) fails with {@code DuplicateKeyException} (SQLSTATE 23505): a racing save.
     */
    UUID insert(UUID tenantId, JourneyRow row);
}
