package com.sahaj.ipd.application;

import java.util.List;
import java.util.UUID;

/**
 * {@code ipd.bed_transfer} (SAH-26). Append-only: the database role can select and insert, nothing more. Every
 * method is scoped to the tenant; the database sets the id, the clinic and the transfer time.
 */
public interface TransferRepository {

    /** Returns the new transfer id. */
    UUID insert(UUID tenantId, NewTransfer transfer);

    /** The transfers of one admission, oldest first. */
    List<TransferRow> findByAdmission(UUID tenantId, UUID admissionId);

    /** The transfers of every admitted patient in one query, oldest first. */
    List<TransferRow> findForAdmitted(UUID tenantId);

    /** The reason is optional free-list text: {@code toString} leaves it out. */
    record NewTransfer(UUID admissionId, UUID fromBedId, UUID toBedId, String fromCategoryKey, String toCategoryKey,
                       int segmentDays, String newRateFrom, String reason, UUID transferredBy) {

        @Override
        public String toString() {
            return "NewTransfer[admissionId=" + admissionId + ", fromBedId=" + fromBedId + ", toBedId=" + toBedId + "]";
        }
    }

    /** What the running bill needs: the category the patient left and the bill days kept on it. */
    record TransferRow(UUID admissionId, String fromCategoryKey, int segmentDays) {
    }
}
