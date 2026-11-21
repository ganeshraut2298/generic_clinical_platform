package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The response bodies of docs/api/pharmacy-sales.yaml (SAH-80), as the contract names them. */
public final class SaleViews {

    private SaleViews() {
    }

    /** StoreBillSeries: the bill series of one selling store, or "All stores (unit series)" when it has none of its own. */
    public record StoreBillSeries(StockViews.StoreRef store, boolean storeActive, boolean usesUnitSeries, String prefix,
                                  boolean financialYearPart, int sequenceLength, boolean restartsEachFinancialYear,
                                  String example, int renderedLength, boolean numbersIssued, boolean locked, long version) {
    }

    /** GET /document-series/bill-stores. */
    public record StoreBillSeriesList(List<StoreBillSeries> items) {
    }

    /** One cell of the role read-out: the column, ALLOW, NEEDS_APPROVAL, DENY or SAME_DAY_ONLY, and the rule text if any. */
    public record RoleCell(String column, String value, String ruleText) {
    }

    public record RoleRow(String action, List<RoleCell> cells) {
    }

    /** GET /compliance/role-readout: what the server enforces, never stored. */
    public record RoleReadout(String sentence, List<String> columns, List<RoleRow> rows) {
    }

    // ---- WP-I: patient, customer, account ----

    /** AllergyInfo (screen only; never logged or audited). {@code classesNotChecked} is always true (OQ-80-22). */
    public record AllergyInfo(String status, String text, boolean classesNotChecked) {

        public static AllergyInfo of(String status, String text) {
            return new AllergyInfo(status, text, true);
        }
    }

    public record SalePatientRow(UUID patientId, String name, int age, String sex, String uhid, String maskedMobile) {
    }

    public record SalePatientList(List<SalePatientRow> items) {
    }

    public record SalePatient(UUID patientId, String name, int age, String sex, String uhid, String maskedMobile,
                              Boolean abhaLinked, AllergyInfo allergy, CreditLine account) {
    }

    /** "Credit limit ₹5,000 · current due ₹0". {@code over60DaysPaise} is null unless the caller is the owner or a pharmacist. */
    public record CreditLine(UUID customerId, Long creditLimitPaise, long duePaise, Long over60DaysPaise,
                             long creditNoteBalancePaise) {
    }

    public record Customer(UUID id, String kind, UUID patientId, String name, String phone, boolean phoneMasked,
                           String allergyNote, String status, CreditLine credit, long version) {
    }

    public record CustomerPage(List<Customer> items, long total, int page, int size) {
    }

    /** One entry of the account, newest first; the amount is signed (plus raises the due or the balance). */
    public record AccountEntry(UUID entryId, String kind, Instant at, String storeName, String reference, String mode,
                               long amountPaise, long dueAfterPaise, long creditNoteBalanceAfterPaise,
                               PharmacyViews.Person by) {
    }

    public record CustomerAccount(CreditLine credit, List<AccountEntry> items, long total, int page, int size) {
    }

    public record CustomerPaymentReceipt(UUID entryId, UUID customerId, String customerName, long amountPaise, String mode,
                                         Instant receivedAt, PharmacyViews.Person receivedBy, StockViews.StoreRef store,
                                         long dueAfterPaise, PurchaseViews.PrintHeader printHeader) {
    }

    // ---- WP-I: product search, quote ----

    public record SaleProductRow(StockViews.ProductRef product, String salt, String packText, Integer availableQty,
                                 boolean outOfStock, boolean matchedOnSalt, String mrpFrom) {
    }

    public record SaleProductList(List<SaleProductRow> items) {
    }

    public record TaxByRate(String gstRate, long taxablePaise, long cgstPaise, long sgstPaise, long totalPaise) {
    }

    public record BillSummary(int itemCount, int unitCount, long mrpTotalPaise, String discountPercent, long discountPaise,
                              List<TaxByRate> taxByRate, long cgstPaise, long sgstPaise, long roundOffPaise,
                              long netPayablePaise) {
    }

    public record AllergyBlock(String allergyText, boolean overridden) {
    }

    public record SubstitutionMark(StockViews.ProductRef prescribedProduct) {
    }

    public record QuoteLine(int lineNo, int inputIndex, StockViews.ProductRef product, String saltText,
                            StockViews.BatchRef batch, boolean fefo, int qty, Integer shortBy, Integer availableQty,
                            String mrp, String gstRate, long amountPaise, long discountSharePaise, long netPaise,
                            long taxablePaise, long taxPaise, Integer expiresInDays, String expiredBatchBlocked,
                            String schedule, boolean needsPharmacist, boolean needsSecondSigner, AllergyBlock allergyBlock,
                            boolean h1PrescriberMissing, boolean h1Overridden, SubstitutionMark substitution, String rx) {
    }

    /** {@code approval} is always null in this release (the approval request of WP-J is not built). */
    public record DiscountInfo(boolean approvalNeeded, String limitPercent, boolean limitNotSet, boolean lean,
                               Object approval) {
    }

    public record SaleQuote(String quoteVersion, List<QuoteLine> lines, BillSummary summary, DiscountInfo discount,
                            AllergyInfo allergy, CreditLine credit, boolean needsPharmacist, boolean needsSecondSigner,
                            boolean needsPrescription, boolean needsH1PatientDetails, boolean canSave,
                            List<String> blockers) {
    }

    // ---- WP-I: bills ----

    public record BillLine(UUID lineId, int lineNo, StockViews.ProductRef product, String hsn, StockViews.BatchRef batch,
                           int qty, String mrp, String gstRate, long amountPaise, long discountSharePaise, long netPaise,
                           long taxablePaise, long taxPaise, int returnedQty, String schedule,
                           StockViews.ProductRef prescribedProduct, StockViews.ReasonRef substitution, Boolean patientAgreed,
                           StockViews.ReasonRef allergyOverride, StockViews.ReasonRef h1Override,
                           PharmacyViews.Person decidedBy, PharmacyViews.Person secondSigner, String rxLineId) {
    }

    public record Buyer(String kind, UUID patientId, UUID customerId, String label, String uhid, String maskedMobile) {
    }

    public record Payment(String mode, long amountPaise) {
    }

    public record SavedBy(UUID membershipId, String name, String roleLabel) {
    }

    public record PharmacistLine(String name, String registrationNo) {
    }

    public record PrescriptionView(String source, String prescriberName, String prescriberRegNo, LocalDate prescriptionDate) {
    }

    public record CancelInfo(Instant at, PharmacyViews.Person by, StockViews.ReasonRef reason, boolean backOnShelf) {
    }

    public record BillPrint(PurchaseViews.PrintHeader header, String unitName, String drugLicenceNo, String gstin,
                            String footerNote) {
    }

    public record Bill(UUID id, String billNo, StockViews.StoreRef store, String status, Instant savedAt, SavedBy savedBy,
                       Buyer buyer, UUID rxId, List<BillLine> lines, BillSummary summary, List<Payment> payments,
                       long udharPaise, boolean linkPending, boolean allergyNotChecked,
                       PharmacyViews.Person discountApprovedBy, PharmacistLine pharmacist, PrescriptionView prescription,
                       CancelInfo cancel, long refundDuePaise, String refundCreditNoteNo, boolean hasReturns,
                       boolean canCancel, String cancelLockReason, BillPrint print, List<StockViews.HistoryEvent> history,
                       long version) {
    }

    public record BillRow(UUID id, String billNo, Instant savedAt, String buyerLabel, int itemCount, long netPayablePaise,
                          String status, List<String> marks) {
    }

    public record BillPage(List<BillRow> items, long total, int page, int size) {
    }

    // ---- WP-I: held bills ----

    public record HeldBillRow(UUID id, PharmacyViews.Person heldBy, Instant heldAt, int itemCount, int ageMinutes,
                              String buyerLabel, boolean hasScheduledLine, String discountState) {
    }

    public record HeldBillList(List<HeldBillRow> items) {
    }

    /** HeldBill: the row, the stored cart, the products it names and the net the cart was held at. */
    public record HeldBill(UUID id, PharmacyViews.Person heldBy, Instant heldAt, int itemCount, int ageMinutes,
                           String buyerLabel, boolean hasScheduledLine, String discountState, String status,
                           SaleInputs.CartInput cart, List<StockViews.ProductRef> products, long heldNetPaise,
                           long version) {
    }
}
