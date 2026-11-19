package com.sahaj.pharmacy.domain;

import java.util.List;
import java.util.Locale;

/**
 * The opening-stock CSV template (docs/api/pharmacy.yaml GET /opening-stock/template, DEC-061: no library). The browser reads
 * the file; the server only knows the header it must have and serves the template text.
 */
public final class OpeningStockCsv {

    public static final List<String> HEADER = List.of("store code", "product code", "batch", "expiry date", "MRP",
            "purchase rate", "quantity");

    /** The header row and one example row; a row whose first cell starts with # is skipped by the browser. */
    public static final String TEMPLATE = String.join(",", HEADER) + "\n"
            + "#EXAMPLE,PRODUCT-CODE,TESTB001,31-12-2027,45.50,30.25,100\n";

    public static final String FILE_NAME = "sahaj-opening-stock-template.csv";
    public static final String HEADER_MESSAGE = "Use the Sahaj template. Save the file as CSV.";

    private static final String BYTE_ORDER_MARK = "﻿";

    private OpeningStockCsv() {
    }

    /** The columns the browser found equal the template header: same number, same order, trimmed, ignoring case. */
    public static boolean matchesHeader(List<String> columns) {
        if (columns == null || columns.size() != HEADER.size()) {
            return false;
        }
        for (int i = 0; i < HEADER.size(); i++) {
            String found = columns.get(i);
            if (found == null) {
                return false;
            }
            String cleaned = found.replace(BYTE_ORDER_MARK, "").trim();
            if (!cleaned.toLowerCase(Locale.ROOT).equals(HEADER.get(i).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
