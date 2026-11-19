package com.sahaj.pharmacy.domain;

/** What the settling store decides about a shortage (docs/api/pharmacy.yaml SettleShortageInput.outcome, OQ-79-18). */
public enum ShortageOutcome {
    FOUND,
    TRANSIT_LOSS,
    CARRIER_CLAIM
}
