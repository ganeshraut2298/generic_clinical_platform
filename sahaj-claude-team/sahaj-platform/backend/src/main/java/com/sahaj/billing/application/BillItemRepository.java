package com.sahaj.billing.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code billing.bill_item} (SAH-10). Needs {@code app.tenant_id} bound. */
public interface BillItemRepository {

    /** Every extra line on a bill, oldest first; never the consultation line, which is never stored here. */
    List<BillItemRow> findByBill(UUID tenantId, UUID billId);

    void insert(UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty, UUID actorUserId);

    /** Another clinic's or another bill's item id, or any id at all, is simply absent. */
    Optional<BillItemRow> findById(UUID tenantId, UUID billId, UUID itemId);

    void delete(UUID tenantId, UUID id);

    /** Every extra line on these bills (SAH-16 revenue). */
    List<BillItemOfBill> findByBills(UUID tenantId, Collection<UUID> billIds);

    /** SAH-48-4 (ADR-022 D7): the lines the EMR made for this bill, named by the EMR item id. */
    record EmrLine(UUID id, String sourceKey, UUID serviceId, int qty, long amountPaise) {
    }

    List<EmrLine> findEmrLines(UUID tenantId, UUID billId);

    /** An EMR line: unique per bill and source key, so a retry or a double tap gives one line. */
    void insertEmr(UUID id, UUID billId, UUID serviceId, String name, long amountPaise, int qty, String sourceKey,
                   UUID actorUserId);

    /**
     * SAH-69-3 (ADR-025 D7, H13): a priced line of a dental plan row done today, source EMR_PLAN, no service. Unique per
     * bill and source key. Returns true when a line was added (false for a repeat).
     */
    boolean insertPlan(UUID id, UUID billId, String label, long amountPaise, String sourceKey, UUID actorUserId);

    void updateQty(UUID tenantId, UUID id, int qty);

    record BillItemOfBill(UUID billId, String name, long amountPaise, int qty) {
    }

    record BillItemRow(UUID id, UUID serviceId, String name, long amountPaise, int qty, String sourceKind) {

        /** A counter line (no source). */
        public BillItemRow(UUID id, UUID serviceId, String name, long amountPaise, int qty) {
            this(id, serviceId, name, amountPaise, qty, null);
        }
    }
}
