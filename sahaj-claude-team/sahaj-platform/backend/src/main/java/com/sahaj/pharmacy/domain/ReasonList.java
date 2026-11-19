package com.sahaj.pharmacy.domain;

import java.util.Optional;

/**
 * The reason lists, in the order of the screen (docs/api/pharmacy.yaml ListKeyValue 0.2.0, eleven lists; OQ-78-32,
 * OQ-79-15). The first four are the SAH-78 lists, the other seven are added by SAH-79. The seeded reasons are global rows in
 * {@code pharmacy.reason_default}; the labels of the lists are written here.
 */
public enum ReasonList {
    STORE_RETURN("store-return", "Store return"),
    SALES_RETURN("sales-return", "Sales return"),
    DAMAGE("damage", "Damage"),
    STOCK_ADJUSTMENT("stock-adjustment", "Stock adjustment"),
    RECEIPT_REJECT("receipt-reject", "Receipt rejection"),
    CS_CHANGE("cs-change", "Central Store change"),
    APPROVAL_REJECT("approval-reject", "Approval rejection"),
    TRANSFER("transfer", "Stock transfer"),
    CONSUMPTION_PURPOSE("consumption-purpose", "Consumption purpose"),
    DISPUTE("dispute", "Dispute"),
    VENDOR_RETURN("vendor-return", "Vendor return");

    /** "Short / not received": the one reason that cannot be switched off (OQ-79-15). */
    public static final String FIXED_REASON_KEY = "receipt-reject.short";

    private final String key;
    private final String label;

    ReasonList(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /**
     * True for a reason that cannot be switched off: "Short / not received" (OQ-79-15, DEC-078 item 6) and the three
     * {@code return-condition} reasons, which decide where returned stock goes (DEC-079 O-13).
     */
    public static boolean isFixed(String reasonKey) {
        return FIXED_REASON_KEY.equals(reasonKey) || reasonKey != null && reasonKey.startsWith("return-condition.");
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static Optional<ReasonList> fromKey(String key) {
        for (ReasonList list : values()) {
            if (list.key.equals(key)) {
                return Optional.of(list);
            }
        }
        return Optional.empty();
    }
}
