package com.sahaj.ipd.domain.order;

/** SAH-76 (ADR-027 D6): how a "review after dose" item was closed. */
public enum ReviewOutcome {
    REVIEWED, VERIFIED, REJECTED, DOSE_UNDONE
}
