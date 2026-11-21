package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.bill}, {@code bill_line} and {@code bill_payment} (SAH-80-3, 80-5; ADR-030 S3). A bill is final once
 * saved: only the cancel, the refund credit-note number and the one-time patient link change it (the table guard). Lines hold one
 * row per batch. The reads are plain; {@link #findForUpdate} is the document lock of a cancel or a refund.
 */
public interface SaleBillRepository {

    /** The bill as stored. {@code savedAt} and the cancel fields are set by the database or the cancel. */
    record BillRow(UUID id, UUID unitId, UUID storeId, String billNo, String seriesKind, int fiscalYear, String status,
                   String buyerKind, UUID patientId, UUID customerId, String walkInName, String walkInPhone,
                   boolean linkPending, boolean allergyNotChecked, UUID rxRecordId, String rxSource, String prescriberName,
                   String prescriberRegNo, LocalDate prescriptionDate, UUID heldBillId, BigDecimal discountPercent,
                   UUID discountRequestId, long grossPaise, long discountPaise, long taxablePaise, long cgstPaise,
                   long sgstPaise, long roundOffPaise, long netPaise, long udharPaise, Instant savedAt, UUID savedBy,
                   UUID pharmacistMembershipId, Instant cancelledAt, UUID cancelledBy, String cancelReasonKey,
                   String cancelNote, Boolean backOnShelf, long refundDuePaise, String refundCreditNoteNo, long version) {
    }

    /** One bill line (one batch). */
    record LineRow(UUID id, int lineNo, UUID productId, UUID batchId, int qty, int returnedQty, BigDecimal mrp,
                   BigDecimal gstRate, String hsn, long grossPaise, long discountPaise, long netPaise, long taxablePaise,
                   long taxPaise, UUID prescribedProductId, String substitutionReasonKey, String substitutionNote,
                   Boolean patientAgreed, String allergyOverrideKey, String allergyOverrideNote, String h1OverrideKey,
                   String h1OverrideNote, UUID overrideDecidedBy, UUID actor, UUID secondSigner, String rxLineKey) {
    }

    record PaymentRow(String mode, long amountPaise) {
    }

    /** What the list of bills shows, before the buyer label is worked out. */
    record ListRow(UUID id, String billNo, Instant savedAt, String buyerKind, UUID patientId, UUID customerPatientId,
                   String customerName, String walkInName, int itemCount, long netPaise, String status, boolean linkPending,
                   boolean allergyNotChecked, boolean refundDue, boolean allergyOverride, boolean h1Override,
                   boolean substituted, boolean hasReturns) {
    }

    record ListSlice(List<ListRow> items, long total) {
    }

    /** A ledger line the sale wrote (the line a cancel points at). */
    record SaleLedger(UUID ledgerId, UUID billLineId, UUID batchId, UUID productId, int qtyOut) {
    }

    Optional<BillRow> find(UUID tenantId, UUID id);

    Optional<BillRow> findForUpdate(UUID tenantId, UUID id);

    /** Many bills in one statement (no N+1); an unknown id is absent from the map. */
    java.util.Map<UUID, BillRow> findAll(UUID tenantId, java.util.Collection<UUID> ids);

    List<LineRow> lines(UUID tenantId, UUID billId);

    List<PaymentRow> payments(UUID tenantId, UUID billId);

    /** True when any line of the bill has a returned quantity or a sales return of the bill is still open. */
    boolean hasReturns(UUID tenantId, UUID billId);

    /** True when a bill (any status, a cancelled one too) already carries this discount request id (single use, ADR-030 S14). */
    boolean existsWithDiscountRequest(UUID tenantId, UUID discountRequestId);

    void insertBill(UUID tenantId, BillRow bill);

    void insertLine(UUID tenantId, UUID unitId, UUID billId, LineRow line, UUID actorMembershipId);

    void insertPayment(UUID tenantId, UUID unitId, UUID storeId, UUID billId, String mode, long amountPaise,
                       UUID actorMembershipId);

    /**
     * Filters: {@code number} (exact, ignoring case; empty is none), the IST day as {@code [from, to)}, {@code status} (empty is
     * none) and {@code mark} (LINK_PENDING, ALLERGY_NOT_CHECKED, REFUND_DUE; empty is none). Newest first.
     */
    ListSlice page(UUID tenantId, UUID storeId, String number, Instant from, Instant to, String status, String mark,
                   int limit, int offset);

    /** SAVED to CANCELLED with the cancel fields; false when the version is no longer the stored one. */
    boolean markCancelled(UUID tenantId, UUID id, long version, UUID cancelledBy, String reasonKey, String note,
                          boolean backOnShelf, long refundDuePaise);

    /** Sets the refund credit-note number once on a cancelled bill. */
    boolean setRefundCreditNote(UUID tenantId, UUID id, long version, String creditNoteNo, UUID actorMembershipId);

    /** The COUNTER_SALE ledger lines of the bill, oldest first. */
    List<SaleLedger> saleLedger(UUID tenantId, UUID billId);

    /** The registration number a pharmacist member printed on a bill (SAH-80-19); empty when none is recorded. */
    Optional<String> pharmacistRegistration(UUID tenantId, UUID membershipId);

    /** The refund modes the owner allows (ORIGINAL_MODE, CASH, CREDIT_NOTE, UDHAR); empty until ticked. */
    List<String> refundModes(UUID tenantId);

    /** Links a "Link to patient pending" walk-in bill to a patient, once; false when the version is not the stored one. */
    boolean linkPatient(UUID tenantId, UUID id, long version, UUID patientId, UUID actorMembershipId);

    /** The unit's return window in days (1 to 365); empty until the owner sets it (SAH-80-18). */
    Optional<Integer> returnWindowDays(UUID tenantId);

    /** The bill of this store with this number, compared ignoring case ({@code lowerNumber} is lower case). */
    Optional<BillRow> findByNumber(UUID tenantId, UUID storeId, String lowerNumber);

    /**
     * Bills of this store saved at or after {@code since} whose customer or walk-in buyer has a name containing {@code termLower} or
     * the phone {@code phone} (null when the term is not 10 digits), newest first, at most {@code limit}.
     */
    List<BillRow> findByBuyer(UUID tenantId, UUID storeId, String termLower, String phone, Instant since, int limit);

    /** Adds to the returned quantity of a bill line (the table guard lets it only grow). */
    void addReturnedQty(UUID tenantId, UUID billLineId, int qty);

    /** The refunds already paid on this bill, by mode (cancel refunds and sales returns). */
    java.util.Map<String, Long> refundedByMode(UUID tenantId, UUID billId);
}
