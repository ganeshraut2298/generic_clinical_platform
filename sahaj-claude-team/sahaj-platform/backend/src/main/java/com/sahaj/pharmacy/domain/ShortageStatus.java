package com.sahaj.pharmacy.domain;

/** The statuses of a shortage (docs/api/pharmacy.yaml ShortageStatus). */
public enum ShortageStatus {
    OPEN,
    CLAIM_OPEN,
    WAITING_WRITE_OFF,
    FOUND,
    WRITTEN_OFF
}
