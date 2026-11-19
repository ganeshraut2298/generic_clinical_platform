package com.sahaj.pharmacy.domain;

/** The statuses of a return item (docs/api/pharmacy.yaml ReturnItemStatus). WAITING_HOD belongs to damage reports (79-14). */
public enum ReturnItemStatus {
    WAITING,
    DISPUTED,
    ACCEPTED_SALEABLE,
    ACCEPTED_QUARANTINE,
    WAITING_HOD
}
