package com.sahaj.pharmacy.domain;

/** The four states of stock in a store (BR-4). Only AVAILABLE and unexpired stock is usable. */
public enum StockState {
    AVAILABLE,
    IN_TRANSIT,
    PENDING_RETURN,
    QUARANTINE
}
