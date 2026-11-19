package com.sahaj.pharmacy.domain;

/**
 * The ten statuses of an indent (docs/api/pharmacy.yaml IndentStatus). The indent has no PARTLY_RECEIVED status (a dispatch has it,
 * 0.3.4): it stays PARTLY_DISPATCHED while a back-order is open or Schedule X lines wait for a pharmacist.
 */
public enum IndentStatus {
    DRAFT,
    WAITING_APPROVAL,
    APPROVED,
    PARTLY_DISPATCHED,
    DISPATCHED,
    RECEIVED,
    CLOSED,
    REJECTED,
    CANCELLED,
    DISCARDED
}
