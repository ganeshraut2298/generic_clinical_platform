package com.sahaj.pharmacy.domain;

/** Why an owner rejected an opening-stock import: Wrong file, Wrong quantities, Wrong store, Other (needs a note). */
public enum RejectReason {
    WRONG_FILE,
    WRONG_QUANTITIES,
    WRONG_STORE,
    OTHER
}
