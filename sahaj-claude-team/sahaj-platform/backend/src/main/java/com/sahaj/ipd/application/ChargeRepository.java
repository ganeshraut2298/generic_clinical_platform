package com.sahaj.ipd.application;

import java.util.List;
import java.util.UUID;

/**
 * {@code ipd.admission_charge} (SAH-25). Append-only: the database role can select and insert, nothing more.
 * Every method is scoped to the tenant; the database sets the id, the clinic and the time.
 */
public interface ChargeRepository {

    /** Returns the new charge id. Quantity is 1. */
    UUID insert(UUID tenantId, UUID admissionId, String serviceKey, String serviceName, long unitPaise,
                UUID addedBy);

    /** Rows of one service already charged to the admission. */
    int countByService(UUID tenantId, UUID admissionId, String serviceKey);

    /** Oldest first. */
    List<ChargeRow> findByAdmission(UUID tenantId, UUID admissionId);

    /** The charges of every admitted patient in one query, oldest first. */
    List<ChargeRow> findForAdmitted(UUID tenantId);

    record ChargeRow(UUID admissionId, String serviceKey, String serviceName, long unitPaise, int qty) {
    }
}
