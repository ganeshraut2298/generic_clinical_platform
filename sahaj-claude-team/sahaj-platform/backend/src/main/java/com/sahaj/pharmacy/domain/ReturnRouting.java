package com.sahaj.pharmacy.domain;

/**
 * Where a rejected receipt line goes back to at the Central Store by default (docs/api/pharmacy.yaml ReturnItem.defaultPutBackInto,
 * BR-8): damaged in transit and near-expiry or expired stock to quarantine; a wrong item and an excess quantity to saleable stock.
 * An expired batch is never put back into saleable stock, whatever the reason.
 */
public final class ReturnRouting {

    public static final String SALEABLE = "SALEABLE";
    public static final String QUARANTINE = "QUARANTINE";

    private static final String DAMAGED = "receipt-reject.damaged-in-transit";
    private static final String NEAR_EXPIRY = "receipt-reject.near-expiry";
    public static final String SHORT = ReasonList.FIXED_REASON_KEY;

    private ReturnRouting() {
    }

    public static String defaultPutBackInto(String rejectReasonKey, boolean batchExpired) {
        if (batchExpired || DAMAGED.equals(rejectReasonKey) || NEAR_EXPIRY.equals(rejectReasonKey)) {
            return QUARANTINE;
        }
        return SALEABLE;
    }

    /** "Short / not received" is a shortage, never a physical return (OQ-79-18). */
    public static boolean isShort(String rejectReasonKey) {
        return SHORT.equals(rejectReasonKey);
    }
}
