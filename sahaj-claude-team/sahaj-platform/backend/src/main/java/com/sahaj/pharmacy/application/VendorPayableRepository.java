package com.sahaj.pharmacy.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/**
 * Port over {@code pharmacy.vendor_payable} and its append-only {@code vendor_payable_entry} (SAH-79-7 and 79-8, ADR-029 P10,
 * design note section 13.4). The service writes the payable and its entry in one transaction under the payable row lock; the
 * payables of a vendor are locked in {@code due_date, id} order after the stock balances (lock order step 7a). No payment is
 * recorded in this release. The actor is a membership id.
 */
public interface VendorPayableRepository {

    record Row(UUID id, UUID vendorId, UUID goodsReceiptId, String invoiceNo, LocalDate invoiceDate, long amountPaise,
               long creditedPaise, LocalDate dueDate, String status, long version) {

        public long openPaise() {
            return amountPaise - creditedPaise;
        }
    }

    Optional<Row> findByReceipt(UUID tenantId, UUID goodsReceiptId);

    /** The OPEN payables of the vendor with something open, locked {@code FOR UPDATE} in ascending due date and id. */
    List<Row> lockOpenOfVendor(UUID tenantId, UUID vendorId);

    /** The payable of a posted receipt and its INVOICE entry. */
    void insertWithInvoiceEntry(UUID tenantId, UUID unitId, UUID id, UUID vendorId, UUID goodsReceiptId, String invoiceNo,
                                LocalDate invoiceDate, long amountPaise, LocalDate dueDate, UUID actor);

    /**
     * Applies {@code applied} paise of a credit note to the payable (the row is locked): raises the credit, settles the payable
     * when the credit reaches the invoice, and writes the CREDIT_NOTE entry for the debit note.
     */
    void applyCredit(UUID tenantId, UUID unitId, Row payable, long applied, UUID debitNoteId, UUID actor);
}
