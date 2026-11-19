package com.sahaj.pharmacy.domain;

/** The statuses of a goods receipt (docs/api/pharmacy.yaml GoodsReceiptStatus). DISCARDED is never listed. */
public enum GoodsReceiptStatus {
    DRAFT, WAITING_APPROVAL, APPROVED, POSTED, DISCARDED
}
