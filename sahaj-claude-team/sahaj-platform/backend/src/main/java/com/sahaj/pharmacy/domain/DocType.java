package com.sahaj.pharmacy.domain;

/**
 * The document types that have a numbering series, in the order of the screen (docs/api/pharmacy.yaml DocType 0.2.0, ten
 * values). Until a series is saved the defaults below are served: PO-0001, GRN-0001, IND-0001, TRF-0001 and the bill
 * SB/26-27/000001 (the only one that restarts every 1 April); the five 0.2.0 types have four digits, no financial-year part
 * and no restart (OQ-79-14).
 */
public enum DocType {
    PURCHASE_ORDER("PO-", false, 4, false),
    GRN("GRN-", false, 4, false),
    INDENT("IND-", false, 4, false),
    TRANSFER("TRF-", false, 4, false),
    BILL("SB/", true, 6, true),
    DEBIT_NOTE("DN-", false, 4, false),
    STOCK_AUDIT("AUD-", false, 4, false),
    STORE_RETURN("RET-", false, 4, false),
    DAMAGE_REPORT("DMG-", false, 4, false),
    STOCK_ADJUSTMENT("ADJ-", false, 4, false);

    private final String defaultPrefix;
    private final boolean defaultFinancialYearPart;
    private final int defaultSequenceLength;
    private final boolean defaultRestarts;

    DocType(String prefix, boolean financialYearPart, int sequenceLength, boolean restarts) {
        this.defaultPrefix = prefix;
        this.defaultFinancialYearPart = financialYearPart;
        this.defaultSequenceLength = sequenceLength;
        this.defaultRestarts = restarts;
    }

    public String defaultPrefix() {
        return defaultPrefix;
    }

    public boolean defaultFinancialYearPart() {
        return defaultFinancialYearPart;
    }

    public int defaultSequenceLength() {
        return defaultSequenceLength;
    }

    public boolean defaultRestarts() {
        return defaultRestarts;
    }
}
