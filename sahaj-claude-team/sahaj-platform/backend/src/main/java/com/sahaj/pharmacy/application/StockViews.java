package com.sahaj.pharmacy.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answers of SAH-79 WP-D (docs/api/pharmacy.yaml 0.3.0): posting preview, stock reads, approvals and stock adjustments.
 * Field names are the contract's. Money is whole paise; a rate is a decimal string (rupees per issue unit). People are named
 * by membership id and name; no free text typed by a person is ever logged from these.
 */
public final class StockViews {

    private StockViews() {
    }

    public record StoreRef(UUID storeId, String name, String type) {
    }

    /** {@code gstPercent} (0.3.3): the product master's GST percentage as text ("12", "5.5"); null where a read does not carry it. */
    public record ProductRef(UUID productId, String code, String name, String strength, String form, String schedule,
                             String purchaseUnit, String issueUnit, int conversion, String gstPercent) {

        public ProductRef(UUID productId, String code, String name, String strength, String form, String schedule,
                          String purchaseUnit, String issueUnit, int conversion) {
            this(productId, code, name, strength, form, schedule, purchaseUnit, issueUnit, conversion, null);
        }
    }

    public record BatchRef(UUID batchId, String batchNo, LocalDate expiryDate) {
    }

    public record ReasonRef(String reasonKey, String label, String note) {
    }

    // ---- 79-1 ----

    public record ExpiredLine(int rowNo, String productName, String batchNo, LocalDate expiryDate, int quantity) {
    }

    public record MrpMismatchLine(int rowNo, String batchNo, String fileMrp, String batchMrp) {
    }

    /** {@code postLockReason} is NOT_APPROVED, ALREADY_POSTED or MRP_MISMATCH, or null when {@code canPost}. */
    public record PostingPreview(UUID importId, StoreRef store, int lineCount, long totalQuantity, long totalValuePaise,
                                 PharmacyViews.Person uploadedBy, PharmacyViews.Person approvedBy,
                                 List<ExpiredLine> expiredLines, List<MrpMismatchLine> mrpMismatchLines, boolean canPost,
                                 String postLockReason) {
    }

    // ---- 79-3 and 79-4 ----

    public record Supplier(UUID vendorId, String name) {
    }

    /** {@code statusChip} holds SALEABLE, EXPIRES_IN, EXPIRED_QUARANTINED, NEW_MRP, IN_TRANSIT, PENDING_RETURN, QUARANTINE. */
    public record BatchRow(ProductRef product, BatchRef batch, String state, Integer qty, Integer fefoRank, String mrp,
                           String purchaseRate, String landed, String marginPercent, Supplier supplier,
                           List<String> statusChip, int daysToExpiry, boolean canVendorReturn) {
    }

    public record BatchPage(List<BatchRow> items, long total, int page, int size) {
    }

    public record Pills(int nearExpiry30, int nearExpiry60, int nearExpiry90, int expiredQuarantined, int lowStock,
                        int scheduleProducts) {
    }

    public record Overview(StoreRef store, int skuCount, int batchCount, Long stockValuePaise, Pills pills, boolean empty) {
    }

    public record ProductStock(ProductRef product, String salt, String hsn, String gstRate, String pack, String rack,
                               Integer reorderLevel, List<BatchRow> batches) {
    }

    /** {@code action} is RAISE_INDENT, ADD_TO_PURCHASE_ORDER or null. */
    public record LowStockItem(ProductRef product, Integer available, int reorderLevel, String action) {
    }

    public record LowStockPage(List<LowStockItem> items, long total, int page, int size) {
    }

    public record FefoBatch(BatchRef batch, Integer quantity, String purchaseRate) {
    }

    public record FefoPick(UUID productId, int requested, Integer availableQty, Integer shortBy, List<FefoBatch> batches) {
    }

    /** {@code status} is OK, BELOW_REORDER, INDENT_IN_TRANSIT or null (the total row). */
    public record StoreStateRow(StoreRef store, Integer available, Integer inTransit, Integer pendingReturn, Integer quarantine,
                                Integer reorderLevel, String status) {
    }

    public record ProductStockByStore(ProductRef product, List<StoreStateRow> rows, StoreStateRow total, boolean empty) {
    }

    // ---- approvals (79-2) ----

    /** The approval of a document as the document's GET embeds it. */
    /** 0.3.5: {@code requestVersion} and {@code levelNo} (equal to currentLevel; null once finished) go to the decision as they are. */
    public record ApprovalSummary(UUID requestId, long requestVersion, Integer levelNo, String status, Integer currentLevel,
                                  String ruleText, String waitingFor,
                                  boolean escalated, boolean needsAnotherApprover, boolean canDecide,
                                  ReasonRef rejectReason) {
    }

    /** {@code chip}: INDENT, PURCHASE_ORDER, RATE_INCREASE, VENDOR_RETURN, TRANSFER, WRITE_OFF, STOCK_ADJUSTMENT, AUDIT_VARIANCE. */
    public record InboxRow(UUID requestId, String transactionType, String chip, String subjectType, UUID subjectId,
                           UUID subjectStoreId, String what, Long amountPaise, PharmacyViews.Person raisedBy,
                           StoreRef raisedStore, Instant raisedAt, long waitingMinutes, String ruleText, int level,
                           boolean urgent, boolean escalated, boolean needsAnotherApprover, List<String> actions) {
    }

    public record InboxCounts(int all, int indents, int purchase, int stock) {
    }

    /** {@code moreWaiting}: the inbox scan stopped at its cap (L19); not yet in the contract (handed to the architect). */
    public record InboxPage(List<InboxRow> items, long total, int page, int size, InboxCounts counts, String signedInAs,
                            boolean moreWaiting) {
    }

    public record LevelView(int level, String approver, String ruleText, boolean applies) {
    }

    public record DecisionView(int level, String decision, PharmacyViews.Person decidedBy, String actingAs,
                               Instant decidedAt, ReasonRef reason) {
    }

    /** {@code decideLockReason}: SELF_APPROVAL, ALREADY_APPROVED_LEVEL_1, NOT_APPROVER, FINISHED or null. */
    public record ApprovalRequest(UUID id, String transactionType, String subjectType, UUID subjectId, String subjectNo,
                                  UUID subjectStoreId, StoreRef ruleStore, PharmacyViews.Person raisedBy, Instant raisedAt,
                                  long amountPaise, int ruleVersionNo, String status, Integer currentLevel,
                                  List<LevelView> levels, List<DecisionView> decisions, boolean escalated,
                                  boolean needsAnotherApprover, boolean canDecide, String decideLockReason, long version) {
    }

    // ---- stock adjustments (79-5) ----

    /** One person who may be the second signer (0.3.2). */
    public record SecondSignerCandidate(UUID membershipId, String name, String roleLabel) {
    }

    public record SecondSignerCandidates(List<SecondSignerCandidate> items) {
    }

    public record HistoryEvent(String event, Instant at, PharmacyViews.Person by, String actingAs, StoreRef store,
                               String lineLabel, Integer qtyBefore, Integer qtyAfter, ReasonRef reason) {
    }

    public record StockAdjustment(UUID id, String number, StoreRef store, ProductRef product, BatchRef batch, Integer bookQty,
                                  Integer physicalQty, int difference, long valuePaise, ReasonRef reason,
                                  PharmacyViews.Person raisedBy, Instant raisedAt, PharmacyViews.Person secondSigner,
                                  String status, ApprovalSummary approval, List<HistoryEvent> history, long version) {
    }

    public record StockAdjustmentPage(List<StockAdjustment> items, long total, int page, int size) {
    }

    // ---- reasons (common) ----

    public record ActiveReason(String reasonKey, String label, boolean needsNote, boolean fixed) {
    }

    public record ActiveReasonList(String listKey, List<ActiveReason> reasons) {
    }

    public record ActiveReasons(List<ActiveReasonList> lists) {
    }
}
