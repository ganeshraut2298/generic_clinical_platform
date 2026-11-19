package com.sahaj.pharmacy.domain;

/** Labels: Central Store, Pharmacy, Sub-store, Ward store (docs/api/pharmacy.yaml StoreType). */
public enum StoreType {
    CENTRAL,
    PHARMACY,
    SUB_STORE,
    WARD;

    public boolean isCentral() {
        return this == CENTRAL;
    }
}
