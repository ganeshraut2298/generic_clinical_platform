package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.DocType;
import java.util.UUID;

/**
 * Takes the next number of a document series through {@code pharmacy.next_document_no()} (ADR-028 D8, ADR-029 P9): gapless for
 * saved documents, because the counter row is locked and incremented in the caller's own transaction (a rolled-back document
 * gives its number back). Lock order step 4. The caller renders the text from the series and keeps it on the document.
 */
public interface DocumentNumberPort {

    /** The next sequence value of the type for the bound clinic. Must run inside the caller's transaction. */
    long next(UUID tenantId, DocType type);

    /**
     * The next sequence value of the store's own BILL series through {@code pharmacy.next_store_bill_no()} (ADR-030 S3). Raises
     * a database error when the store has no series of its own: the caller asks {@code StoreBillSeriesRepository.find} first.
     */
    long nextStoreBill(UUID tenantId, UUID storeId);
}
