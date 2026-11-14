package com.sahaj.ipd.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code ipd.round_note} (SAH-67-1, ADR-025 D8): one row per admission and Asia/Kolkata day, written when a doctor signs the
 * daily progress note in the EMR. Insert only: there is no update and no delete. Every method is scoped to the tenant.
 * {@code investigationsAdvised} is clinical text: screen only, never logged ({@code toString} shows ids).
 */
public interface RoundNoteRepository {

    record NewRound(UUID admissionId, LocalDate roundDate, UUID emrRecordId, UUID signedByUserId,
                    UUID signedByMembershipId, Instant signedAt, int ordersPlaced, String investigationsAdvised) {

        @Override
        public String toString() {
            return "NewRound[admissionId=" + admissionId + ", roundDate=" + roundDate + "]";
        }
    }

    record RoundRow(UUID admissionId, LocalDate roundDate, Instant signedAt, UUID signedByMembershipId,
                    String investigationsAdvised) {

        @Override
        public String toString() {
            return "RoundRow[admissionId=" + admissionId + ", roundDate=" + roundDate + "]";
        }
    }

    /** True when a row was inserted; false when the admission already had a note for that day (success for the caller). */
    boolean insertIfAbsent(UUID tenantId, NewRound round);

    /** The newest round note of the admission. */
    Optional<RoundRow> findLatest(UUID tenantId, UUID admissionId);

    /** The admissions among {@code admissionIds} that have a round note for {@code day} (one statement). */
    Set<UUID> doneOn(UUID tenantId, LocalDate day, Collection<UUID> admissionIds);
}
