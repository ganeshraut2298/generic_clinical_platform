package com.sahaj.pharmacy.domain;

/**
 * The nine transaction types an approval rule can be set for, in the order of the screen (OQ-78-25;
 * docs/api/pharmacy.yaml TransactionType). Opening stock is not here: it has the fixed rule "a different owner approves".
 */
public enum TransactionType {
    PURCHASE_ORDER,
    GRN_RATE_RISE,
    VENDOR_RETURN,
    INDENT,
    STOCK_TRANSFER,
    WRITE_OFF,
    STOCK_ADJUSTMENT,
    SALE_DISCOUNT,
    SALES_RETURN
}
