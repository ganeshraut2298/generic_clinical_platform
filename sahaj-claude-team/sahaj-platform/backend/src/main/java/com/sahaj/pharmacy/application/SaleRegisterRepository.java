package com.sahaj.pharmacy.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Port over the append-only legal registers {@code pharmacy.h1_register_row} and {@code pharmacy.schedule_x_register_row}
 * (SAH-80-9, 80-10; ADR-030 S7, S8) and over the credit-note tables of a cancelled bill ({@code pharmacy.credit_note},
 * {@code pharmacy.refund}). A row is never changed: a cancel adds REVERSAL rows that point at the entries. The patient name, age
 * and address are kept here and nowhere else (BR-34); they are never logged.
 */
public interface SaleRegisterRepository {

    /** One Schedule H1 entry of a bill line (kind ENTRY, positive quantity). */
    record H1Entry(UUID unitId, UUID storeId, UUID billId, UUID billLineId, String billNo, UUID patientId, String patientName,
                   int patientAge, String patientAddress, String prescriberName, String prescriberRegNo,
                   LocalDate prescriptionDate, String rxSource, UUID rxRecordId, UUID productId, UUID prescribedProductId,
                   UUID batchId, int qty, UUID pharmacistMembershipId, String overrideReasonKey) {
    }

    /** One Schedule X entry of a bill line; both signers are different people. */
    record ScheduleXEntry(UUID unitId, UUID storeId, UUID billId, UUID billLineId, String billNo, UUID patientId,
                          String patientName, int patientAge, String prescriberName, String prescriberRegNo,
                          LocalDate prescriptionDate, UUID productId, UUID batchId, int qty, UUID firstSignerMembershipId,
                          UUID secondSignerMembershipId) {
    }

    record NewCreditNote(UUID id, UUID unitId, UUID storeId, String creditNoteNo, int fiscalYear, String sourceKind,
                         UUID billId, UUID salesReturnId, long amountPaise) {
    }

    record NewRefund(UUID unitId, UUID storeId, String sourceKind, UUID billId, UUID salesReturnId, String mode,
                     long amountPaise, String creditNoteNo) {
    }

    void insertH1(UUID tenantId, H1Entry entry);

    void insertScheduleX(UUID tenantId, ScheduleXEntry entry);

    /** Adds a REVERSAL row (negative quantity, reason CANCELLED) for every H1 ENTRY of the bill; returns the count. */
    int reverseH1ForCancelledBill(UUID tenantId, UUID billId);

    /**
     * Adds a REVERSAL row (negative quantity, reason CANCELLED) for every Schedule X ENTRY of the bill; returns the count. The
     * signers of a reversal are the people who cancelled it (the pharmacist and the second signer), not those of the sale (SEC M6).
     */
    int reverseScheduleXForCancelledBill(UUID tenantId, UUID billId, UUID firstSignerMembershipId, UUID secondSignerMembershipId);

    /** Adds a REVERSAL row (negative quantity {@code qty}, reason RETURNED, reference the credit note) for the H1 ENTRY of the line. */
    int reverseH1ForReturn(UUID tenantId, UUID billLineId, int qty, String creditNoteNo);

    /**
     * Adds a REVERSAL row (negative quantity, reason RETURNED, source SALES_RETURN with the return id and the credit note number) for
     * the Schedule X ENTRY of the line; the signers are the people who completed the return.
     */
    int reverseScheduleXForReturn(UUID tenantId, UUID billLineId, int qty, UUID returnId, String creditNoteNo,
                                  UUID firstSignerMembershipId, UUID secondSignerMembershipId);

    void insertCreditNote(UUID tenantId, NewCreditNote note, UUID actorMembershipId);

    void insertRefund(UUID tenantId, NewRefund refund, UUID actorMembershipId);
}
