package com.sahaj.pharmacy.domain;

/** The statuses of a debit note (docs/api/pharmacy.yaml DebitNoteStatus). DISCARDED is never listed. */
public enum DebitNoteStatus {
    DRAFT, WAITING_APPROVAL, REJECTED, CREATED, PICKED, HANDED_OVER, CREDIT_NOTE_RECEIVED, CANCELLED, DISCARDED
}
