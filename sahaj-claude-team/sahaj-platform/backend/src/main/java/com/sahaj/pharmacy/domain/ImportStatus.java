package com.sahaj.pharmacy.domain;

/** The state of an opening-stock import (docs/api/pharmacy.yaml ImportStatus). APPROVED, REJECTED, NOT_ACCEPTED and REPLACED are final. */
public enum ImportStatus {
    DRAFT,
    NOT_ACCEPTED,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    REPLACED
}
