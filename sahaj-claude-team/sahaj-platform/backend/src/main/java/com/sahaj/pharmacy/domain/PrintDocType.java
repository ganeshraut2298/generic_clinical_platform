package com.sahaj.pharmacy.domain;

import java.util.List;

/**
 * The printed documents that have a template (docs/api/pharmacy.yaml PrintTemplate.docType). Fixed fields cannot be hidden
 * (OQ-78-33): the invoice list is accepted (DEC-062 Q4); the issue slip and the debit note carry the unit name, the document
 * number and the date until the human confirms their legal fields.
 */
public enum PrintDocType {
    INVOICE(List.of("UNIT_NAME", "DOCUMENT_NO", "DATE", "GSTIN", "DRUG_LICENCE_NO", "HSN", "BATCH", "EXPIRY", "MRP",
            "GST_AMOUNTS")),
    ISSUE_SLIP(List.of("UNIT_NAME", "DOCUMENT_NO", "DATE")),
    DEBIT_NOTE(List.of("UNIT_NAME", "DOCUMENT_NO", "DATE"));

    public static final int HEADER_LINES_MAX = 3;
    public static final int HEADER_LINE_MAX = 80;
    public static final int FOOTER_MAX = 200;

    private final List<String> fixedFields;

    PrintDocType(List<String> fixedFields) {
        this.fixedFields = fixedFields;
    }

    public List<String> fixedFields() {
        return fixedFields;
    }
}
