package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.StockState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.batch}, {@code pharmacy.stock_balance} and {@code pharmacy.stock_ledger} (SAH-79, ADR-029 P2 to
 * P5). Writes are made only by {@link StockPosting}; the reads feed the stock screens. Row level security scopes every
 * statement to the bound clinic. Money is rupees per issue unit as a decimal; the ledger and the batch are never deleted.
 */
public interface StockRepository {

    /** A balance row with its batch (a row of one store, batch and state). */
    record Bal(UUID id, UUID storeId, UUID productId, UUID batchId, StockState state, int qty, String batchNo,
               LocalDate expiryDate, Instant firstReceivedAt, BigDecimal mrp, BigDecimal purchaseRate) {
    }

    record BatchRow(UUID id, UUID productId, String batchNo, LocalDate expiryDate, BigDecimal mrp,
                    BigDecimal purchaseRate, UUID vendorId, Instant firstReceivedAt) {
    }

    /** One ledger line to write. {@code actorMembershipId} null means SYSTEM. */
    record LedgerIn(UUID movementId, String txnType, String docType, UUID docId, String docNo, UUID docLineId, UUID storeId,
                    StockState state, UUID productId, UUID batchId, int qtyIn, int qtyOut, int balanceAfter,
                    UUID actorMembershipId, UUID secondSignerMembershipId, UUID approvedByMembershipId, String reasonKey,
                    UUID reversesLedgerId, UUID admissionId) {

        public LedgerIn(UUID movementId, String txnType, String docType, UUID docId, String docNo, UUID docLineId, UUID storeId,
                        StockState state, UUID productId, UUID batchId, int qtyIn, int qtyOut, int balanceAfter,
                        UUID actorMembershipId, UUID secondSignerMembershipId, UUID approvedByMembershipId, String reasonKey,
                        UUID reversesLedgerId) {
            this(movementId, txnType, docType, docId, docNo, docLineId, storeId, state, productId, batchId, qtyIn, qtyOut,
                    balanceAfter, actorMembershipId, secondSignerMembershipId, approvedByMembershipId, reasonKey, reversesLedgerId,
                    null);
        }
    }

    /** One ledger line as read back (the line a correction points at). */
    record LedgerRow(UUID id, UUID storeId, UUID batchId, UUID productId, StockState state) {
    }

    /** {@code SET LOCAL statement_timeout} for the rest of the transaction (opening-stock posting runs up to 60 seconds). */
    void raiseStatementTimeout(int seconds);

    // ---- locks and writes (StockPosting only) ----

    /**
     * Every balance row of the store for these products, locked in one statement {@code ORDER BY product_id, batch_id, state FOR
     * UPDATE} (lock order step 7). Joined to the batch for expiry and rates.
     */
    List<Bal> lockBalances(UUID tenantId, UUID storeId, Collection<UUID> productIds);

    /** Inserts the balance row if it is missing (quantity 0), without locking an existing row (lock order step 7, first part). */
    void ensureBalance(UUID tenantId, UUID unitId, UUID storeId, UUID productId, UUID batchId, StockState state,
                       UUID actorMembershipId);

    /** Inserts the balance row if it is missing (quantity 0), then returns it locked. */
    Bal ensureBalanceLocked(UUID tenantId, UUID unitId, UUID storeId, UUID productId, UUID batchId, StockState state,
                            UUID actorMembershipId);

    /** Expired AVAILABLE rows with quantity above 0 of the store, locked {@code FOR UPDATE SKIP LOCKED} (the read sweep). */
    List<Bal> lockExpiredAvailable(UUID tenantId, UUID storeId, LocalDate today);

    void setBalance(UUID tenantId, UUID balanceId, int qty, UUID actorMembershipId);

    void insertLedger(UUID tenantId, UUID unitId, LedgerIn line);

    /** A ledger line of this store and batch (the line {@code correctsLedgerId} names); empty when there is none. */
    Optional<LedgerRow> findLedger(UUID tenantId, UUID ledgerId);

    /** Inserts the batch when no batch has this product, batch number (case and edge spaces ignored) and expiry. */
    void ensureBatch(UUID tenantId, UUID unitId, UUID productId, String batchNo, LocalDate expiryDate, BigDecimal mrp,
                     BigDecimal purchaseRate, String source, UUID actorMembershipId);

    /** The batch by identity, locked {@code FOR UPDATE}. */
    Optional<BatchRow> findBatchForUpdate(UUID tenantId, UUID productId, String batchNo, LocalDate expiryDate);

    /** The batch by identity, not locked (the posting preview). */
    Optional<BatchRow> findBatchByIdentity(UUID tenantId, UUID productId, String batchNo, LocalDate expiryDate);

    Optional<BatchRow> findBatch(UUID tenantId, UUID batchId);

    /** The latest purchase rate (a top-up of an existing batch by opening stock keeps the rate it brings). */
    void setBatchPurchaseRate(UUID tenantId, UUID batchId, BigDecimal purchaseRate, UUID actorMembershipId);

    /** A receipt sets the latest purchase rate and the vendor of the batch (SAH-79-7, ADR-029 P2); the MRP never changes. */
    void setBatchSupply(UUID tenantId, UUID batchId, BigDecimal purchaseRate, UUID vendorId, UUID actorMembershipId);

    /** The MRP of the product's most recently first-received batch in the unit; empty when the product has no batch. */
    Optional<BigDecimal> latestBatchMrp(UUID tenantId, UUID productId);

    // ---- reads ----

    /** What the overview header and pills count for one store. */
    record Overview(int skuCount, int batchCount, long stockValuePaise, int nearExpiry30, int nearExpiry60,
                    int nearExpiry90, int expiredQuarantined, int lowStock, int scheduleProducts) {
    }

    Overview overview(UUID tenantId, UUID storeId, LocalDate today);

    /** One row per batch and state with quantity above 0, as the Batch inventory table shows it. */
    record BatchView(UUID productId, String productCode, String productName, String strength, String form, String schedule,
                     String purchaseUnit, String issueUnit, int conversion, UUID batchId, String batchNo,
                     LocalDate expiryDate, StockState state, int qty, Integer fefoRank, BigDecimal mrp,
                     BigDecimal purchaseRate, UUID vendorId, String vendorName, Integer vendorReturnDays,
                     boolean newMrp, BigDecimal gstPercent) {
    }

    record BatchSlice(List<BatchView> items, long total) {
    }

    /** {@code view} is ALL, NEAR_EXPIRY, EXPIRED or SCHEDULE; {@code like} and {@code exact} empty mean no filter. */
    BatchSlice batches(UUID tenantId, UUID storeId, LocalDate today, String view, int withinDays, String like, String exact,
                       int limit, int offset);

    /** Every batch row of one product in one store (all states, quantity above 0), FEFO order. */
    List<BatchView> batchesOfProduct(UUID tenantId, UUID storeId, UUID productId, LocalDate today);

    record LowStockRow(UUID productId, String code, String name, String strength, String form, String schedule,
                       String purchaseUnit, String issueUnit, int conversion, int available, int reorderLevel,
                       BigDecimal gstPercent) {
    }

    record LowStockSlice(List<LowStockRow> items, long total) {
    }

    LowStockSlice lowStock(UUID tenantId, UUID storeId, LocalDate today, int limit, int offset);

    /** The AVAILABLE quantity of a batch in a store (a row of quantity 0 counts); empty when the store has no such row. */
    Optional<Integer> availableQty(UUID tenantId, UUID storeId, UUID batchId);

    /** The AVAILABLE rows of a product in a store (all batches, with expiry), for the FEFO preview. */
    List<Bal> availableOfProduct(UUID tenantId, UUID storeId, UUID productId);

    /** One product in one store as the "by state" row reads it. */
    record StateRow(UUID storeId, int available, int inTransit, int pendingReturn, int quarantine, Integer reorderLevel) {
    }

    /** The sums per state of one product in these stores; a store with no rows is answered with zeros. */
    List<StateRow> statesOfProduct(UUID tenantId, UUID productId, Collection<UUID> storeIds);

    /** {@link #statesOfProduct} for many products in one statement (m-8); only stores with a balance row appear. */
    java.util.Map<UUID, List<StateRow>> statesOfProducts(UUID tenantId, Collection<UUID> productIds, Collection<UUID> storeIds);
}
