package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.pharmacy.domain.RejectReason;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the WP-A operations (docs/api/pharmacy.yaml, all with {@code additionalProperties: false}: an
 * unknown field is refused 400 by {@code PharmacyStrictJsonModule}). Fields are nullable here because the services
 * report every missing or wrong field together (400 validation); nothing is checked at binding time.
 *
 * <p>A field that the contract requires but allows to be {@code null} ({@code gstin}, {@code saleAllowed}, {@code delegate}
 * and so on) is a {@link Sent}: a request that leaves it out is told apart from one that sends {@code null}. Leaving a field
 * out is 400; it is never read as null, so a partial save cannot clear a saved value.
 */
public final class PharmacyInputs {

    private PharmacyInputs() {
    }

    /**
     * The request body of an operation, read when the service calls {@link #read()}. A service calls it right after its
     * gate (module off 403, access level 403), so a body that cannot be read answers 400 only to a caller who is allowed to
     * send it. {@code read()} throws the unreadable-body problem; it never returns null.
     */
    @FunctionalInterface
    public interface Body<T> {

        T read();

        /** A body that is already read (tests and internal callers). */
        static <T> Body<T> of(T value) {
            return () -> value;
        }
    }

    /** A body field with its presence: {@code present} is false when the request did not carry the field at all. */
    public record Sent<T>(boolean present, T value) {

        private static final Sent<?> ABSENT = new Sent<>(false, null);

        @SuppressWarnings("unchecked")
        public static <T> Sent<T> absent() {
            return (Sent<T>) ABSENT;
        }

        public static <T> Sent<T> of(T value) {
            return new Sent<>(true, value);
        }

        /** True when the request did not carry the field (a Java {@code null} wrapper counts as not carried). */
        public static boolean missing(Sent<?> field) {
            return field == null || !field.present();
        }

        /** The value, or {@code null} when the field was left out or sent as null. */
        public static <T> T valueOf(Sent<T> field) {
            return field == null ? null : field.value();
        }
    }

    public record WorkingStore(UUID storeId) {
    }

    public record UnitSettings(Long version, Sent<String> gstin, Sent<String> drugLicenceNo,
                               Sent<LocalDate> drugLicenceValidTill, IntegrationMode integrationMode) {

        /** A save that sends every field (a null value is sent as an explicit null). */
        public static UnitSettings of(Long version, String gstin, String drugLicenceNo, LocalDate drugLicenceValidTill,
                                      IntegrationMode integrationMode) {
            return new UnitSettings(version, Sent.of(gstin), Sent.of(drugLicenceNo), Sent.of(drugLicenceValidTill),
                    integrationMode);
        }
    }

    public record Delegate(UUID membershipId, LocalDate from, LocalDate to) {
    }

    public record StoreCreate(String name, String code, StoreType type, Boolean saleAllowed, Boolean consumptionAllowed,
                              UUID hodMembershipId, Delegate delegate) {
    }

    public record StoreUpdate(Long version, String name, String code, StoreType type, Sent<Boolean> saleAllowed,
                              Sent<Boolean> consumptionAllowed, Sent<UUID> hodMembershipId, Sent<Delegate> delegate) {

        /** A change that sends every field (a null HOD or delegate is sent as an explicit null). */
        public static StoreUpdate of(Long version, String name, String code, StoreType type, Boolean saleAllowed,
                                     Boolean consumptionAllowed, UUID hodMembershipId, Delegate delegate) {
            return new StoreUpdate(version, name, code, type, Sent.of(saleAllowed), Sent.of(consumptionAllowed),
                    Sent.of(hodMembershipId), Sent.of(delegate));
        }
    }

    public record MapStaff(UUID membershipId, StoreRole storeRole, LocalDate delegateFrom, LocalDate delegateTo) {
    }

    public record ChangeMapping(Long version, StoreRole storeRole, LocalDate delegateFrom, LocalDate delegateTo) {
    }

    // ---- WP-B: products (78-5, 78-6) ----

    /**
     * POST /products. {@code conversion} is read as a decimal so that 2.5 is refused instead of being cut to 2. The nullable
     * required fields are {@link Sent}: a request that leaves them out is 400, never read as null.
     */
    public record OwnProduct(UUID clientKey, String name, String salt, String strength, String form, String purchaseUnit,
                             String issueUnit, BigDecimal conversion, Sent<String> manufacturer, Boolean madeInHouse,
                             String hsn, String gstRate, Sent<ProductSchedule> schedule, String barcode,
                             Boolean ignoreDuplicates) {
    }

    /** PUT /products/{productId}: the same fields without the client key, with the version read. */
    public record OwnProductChange(Long version, String name, String salt, String strength, String form,
                                   String purchaseUnit, String issueUnit, BigDecimal conversion,
                                   Sent<String> manufacturer, Boolean madeInHouse, String hsn, String gstRate,
                                   Sent<ProductSchedule> schedule, String barcode, Boolean ignoreDuplicates) {
    }

    public record StoreSettingInput(UUID storeId, Sent<BigDecimal> reorderLevel, Sent<String> rack) {
    }

    /** PUT /products/{productId}/local-settings. */
    public record LocalSettings(Long version, List<StoreSettingInput> storeSettings, Sent<UUID> preferredVendorId,
                                Sent<String> alias, Sent<String> barcode, Boolean returnable, Boolean active) {
    }

    // ---- WP-B: opening stock (78-7) ----

    /** POST .../opening-stock-imports. */
    public record StartImport(UUID clientKey, List<String> columns, BigDecimal rowCount) {
    }

    /** One row of the file as text, exactly as the browser read it. */
    public record ImportRow(BigDecimal rowNo, String storeCode, String productCode, String batch, String expiryDate,
                            String mrp, String purchaseRate, String quantity) {
    }

    /** POST .../lines. */
    public record ImportLines(List<ImportRow> rows) {
    }

    /** POST .../reject. */
    public record RejectImport(RejectReason reasonKey, String note) {
    }

    // ---- SAH-79 WP-D (docs/api/pharmacy.yaml 0.3.0) ----

    /** ClientKeyInput: a post that carries only its key. */
    public record ClientKeyInput(UUID clientKey) {
    }

    /** SecondSigner: the colleague's membership id and PIN as typed. The PIN is never stored, logged or echoed. */
    public record SecondSignerInput(UUID membershipId, String pin) {

        @Override
        public String toString() {
            return "SecondSignerInput[membershipId=" + membershipId + ", pin=***]";
        }
    }

    /** One line of an indent decision (story 79-10): the approved quantity and the remark. */
    public record IndentLineDecision(UUID indentLineId, BigDecimal approvedQty, String remarkReasonKey, String remarkNote) {
    }

    /** POST /approvals/{requestId}/decision. Whole numbers are read as decimals so that 2.5 is refused, not cut. */
    public record ApprovalDecisionInput(UUID clientKey, BigDecimal level, BigDecimal requestVersion, String decision,
                                        String reasonKey, String note, List<IndentLineDecision> indentLines,
                                        String damageRejectOutcome) {
    }

    /** POST .../stock-adjustments. */
    public record StockAdjustmentCreate(UUID clientKey, UUID batchId, BigDecimal bookQtySeen, BigDecimal physicalQty,
                                        String reasonKey, String note, UUID correctsLedgerId,
                                        SecondSignerInput secondSigner) {
    }

    // ---- SAH-79 WP-F, indents (docs/api/pharmacy.yaml 0.3.4) ----

    /** IndentLineInput: the requested quantity is in issue units. */
    public record IndentLineInput(UUID productId, BigDecimal requestedQty) {
    }

    /** POST .../indents. */
    public record IndentCreate(UUID clientKey, String priority, List<IndentLineInput> lines) {
    }

    /** PUT .../indents/{indentId}. */
    public record IndentUpdate(BigDecimal version, String priority, List<IndentLineInput> lines) {
    }

    // ---- SAH-79 WP-F, dispatch, receipt, returns and shortages (docs/api/pharmacy.yaml 0.3.5) ----

    /** One line of POST .../indents/{indentId}/dispatches. The client never names a batch (FEFO picks it). */
    public record DispatchLineInput(UUID indentLineId, UUID dispatchProductId, BigDecimal quantity, String balanceAction,
                                    String changeReasonKey, String changeNote) {
    }

    /** IndentDispatchInput. */
    public record IndentDispatch(UUID clientKey, BigDecimal indentVersion, List<DispatchLineInput> lines,
                                 SecondSignerInput secondSigner) {
    }

    /** One line of DispatchReceiptInput; {@code rejected} is checked against {@code dispatched - accepted}. */
    public record ReceiptLineInput(UUID dispatchLineId, BigDecimal accepted, BigDecimal rejected, String rejectReasonKey,
                                   String note, Boolean skipped) {
    }

    /** DispatchReceiptInput. */
    public record DispatchReceipt(UUID clientKey, BigDecimal dispatchVersion, List<ReceiptLineInput> lines,
                                  SecondSignerInput secondSigner) {
    }

    /** AcceptReturnInput. */
    public record AcceptReturn(UUID clientKey, BigDecimal version, String putBackInto, SecondSignerInput secondSigner) {
    }

    /** NoteInput. */
    public record NoteInput(UUID clientKey, String note) {
    }

    /** SettleShortageInput. */
    public record SettleShortage(UUID clientKey, BigDecimal version, String outcome, String note, SecondSignerInput secondSigner) {
    }

    // ---- SAH-79 WP-E, purchase orders (docs/api/pharmacy.yaml 0.3.1) ----

    /** PurchaseOrderLineInput: quantity in purchase units, rate per purchase unit. */
    public record PoLineInput(UUID productId, BigDecimal qty, BigDecimal rate) {
    }

    /** POST .../purchase-orders. */
    public record PurchaseOrderCreate(UUID clientKey, UUID vendorId, LocalDate deliveryExpected, BigDecimal creditDays,
                                      List<PoLineInput> lines) {
    }

    /** PUT .../purchase-orders/{poId}. */
    public record PurchaseOrderUpdate(BigDecimal version, UUID vendorId, LocalDate deliveryExpected, BigDecimal creditDays,
                                      List<PoLineInput> lines) {
    }

    /** One selected row of the reorder list. */
    public record PoSuggestionRow(UUID productId, UUID vendorId, BigDecimal qty, BigDecimal rate) {
    }

    /** POST .../purchase-orders/from-suggestions. */
    public record PurchaseOrdersFromSuggestions(UUID clientKey, List<PoSuggestionRow> rows) {
    }

    /** VersionInput. */
    public record VersionInput(BigDecimal version) {
    }

    /** VersionClientKeyInput. */
    public record VersionClientKeyInput(UUID clientKey, BigDecimal version) {
    }

    /** VersionReasonInput. */
    public record VersionReasonInput(BigDecimal version, String reasonKey, String note) {
    }

    /** MarkSentInput. */
    public record MarkSentInput(BigDecimal version, LocalDate sentOn) {
    }

    // ---- SAH-79 WP-E, goods receipts (79-7) and debit notes (79-8) ----

    /** GoodsReceiptLineInput: quantities in purchase units, PTR per purchase unit, MRP per issue unit, expiry as YYYY-MM. */
    public record GrnLineInput(UUID purchaseOrderLineId, UUID productId, String batchNo, String expiryMonth, BigDecimal qty,
                               BigDecimal billedQty, BigDecimal freeQty, BigDecimal ptr, BigDecimal mrp,
                               BigDecimal discountPercent, Boolean claimRateDifference, String shortAction) {
    }

    /** POST .../goods-receipts. */
    public record GoodsReceiptCreate(UUID clientKey, UUID purchaseOrderId, UUID vendorId, String invoiceNo,
                                     LocalDate invoiceDate, BigDecimal creditDays, BigDecimal invoiceTotalPaise,
                                     List<GrnLineInput> lines) {
    }

    /** PUT .../goods-receipts/{grnId}. */
    public record GoodsReceiptUpdate(BigDecimal version, UUID vendorId, String invoiceNo, LocalDate invoiceDate,
                                     BigDecimal creditDays, BigDecimal invoiceTotalPaise, List<GrnLineInput> lines) {
    }

    /** PostWithSignerInput. */
    public record PostWithSigner(UUID clientKey, BigDecimal version, SecondSignerInput secondSigner) {
    }

    /** DebitNoteLineInput. */
    public record DebitNoteLineInput(String type, UUID batchId, String sourceState, UUID goodsReceiptLineId, BigDecimal qty,
                                     BigDecimal creditPercent) {
    }

    /** POST .../debit-notes. */
    public record DebitNoteCreate(UUID clientKey, UUID vendorId, String reasonKey, String note,
                                  List<DebitNoteLineInput> lines) {
    }

    /** PUT .../debit-notes/{dnId}. */
    public record DebitNoteUpdate(BigDecimal version, String reasonKey, String note, List<DebitNoteLineInput> lines) {
    }

    /** HandOverInput. The representative's name is never logged. */
    public record HandOver(UUID clientKey, BigDecimal version, String representativeName, SecondSignerInput secondSigner) {

        @Override
        public String toString() {
            return "HandOver[clientKey=" + clientKey + ", version=" + version + ", representativeName=***, secondSigner="
                    + secondSigner + "]";
        }
    }

    /** CreditNoteInput. */
    public record CreditNote(UUID clientKey, BigDecimal version, String creditNoteNo, LocalDate creditNoteDate,
                             BigDecimal amountPaise) {
    }
}
