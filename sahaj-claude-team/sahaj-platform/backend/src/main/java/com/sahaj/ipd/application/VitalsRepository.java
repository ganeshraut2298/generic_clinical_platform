package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code ipd.vitals} and {@code ipd.nursing_note} (SAH-30). Both are append-only: there is no update and no delete.
 * Every method is scoped to the tenant. The database sets ids, the clinic and the recording time (the transaction
 * time); the application never sends them.
 */
public interface VitalsRepository {

    /**
     * The database clock now, {@code clock_timestamp()}: the base of {@code asOf} and "vitals due" (ADR-006 D1), so
     * that app and database skew never shows a wrong "N min ago". Never before the transaction time of a save.
     */
    Instant databaseNow();

    /** The newest record of the admission: {@code recorded_at} descending, then {@code id} descending. */
    Optional<VitalsRow> findLatest(UUID tenantId, UUID admissionId);

    /**
     * SAH-36 (ADR-013 R4): the score, risk and time of the newest record ({@code recorded_at} descending, then
     * {@code id} descending, the rule of {@link #findLatest}) of every ADMITTED admission of the clinic, in one statement
     * (one index probe per admission). An admission with no vitals is absent from the map.
     */
    Map<UUID, LatestScore> findLatestOfAdmitted(UUID tenantId);

    /** The record stored with this client key, in any admission of the clinic. */
    Optional<VitalsRow> findByClientKey(UUID tenantId, UUID clientKey);

    /** Stores one record; returns its id and the database time of the save. */
    Stored insert(UUID tenantId, NewVitals vitals);

    /** Stores one note (never empty); returns its id. */
    UUID insertNote(UUID tenantId, UUID admissionId, String note, UUID recordedBy);

    /** The newest notes of the admission first, at most {@code limit}. */
    List<NoteRow> findRecentNotes(UUID tenantId, UUID admissionId, int limit);

    record Stored(UUID id, Instant recordedAt) {
    }

    /** SAH-36: the latest score of one admission, nothing else of the record (no five values). Clinical data. */
    record LatestScore(UUID admissionId, int ewsScore, Risk ewsRisk, Instant recordedAt) {

        @Override
        public String toString() {
            return "LatestScore[admissionId=" + admissionId + "]";
        }
    }

    /** The five values are clinical data: {@code toString} shows the admission id only. */
    record NewVitals(UUID admissionId, UUID clientKey, int bpSystolic, int bpDiastolic, int pulse, int spo2,
                     BigDecimal tempC, int rr, int ewsScore, Risk ewsRisk, boolean noteAdded, UUID recordedBy) {

        @Override
        public String toString() {
            return "NewVitals[admissionId=" + admissionId + "]";
        }
    }

    record VitalsRow(UUID id, UUID admissionId, int bpSystolic, int bpDiastolic, int pulse, int spo2,
                     BigDecimal tempC, int rr, int ewsScore, Risk ewsRisk, boolean noteAdded, Instant recordedAt,
                     UUID recordedBy) {

        @Override
        public String toString() {
            return "VitalsRow[id=" + id + "]";
        }
    }

    record NoteRow(UUID id, String note, Instant recordedAt, UUID recordedBy) {

        @Override
        public String toString() {
            return "NoteRow[id=" + id + "]";
        }
    }
}
