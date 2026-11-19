package com.sahaj.pharmacy.domain;

/** The ten statuses of a purchase order (docs/api/pharmacy.yaml PurchaseOrderStatus). Overdue is a mark, not a status. */
public enum PurchaseOrderStatus {
    DRAFT,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    SENT,
    PARTLY_RECEIVED,
    RECEIVED,
    CLOSED_SHORT,
    CANCELLED,
    DISCARDED
}
