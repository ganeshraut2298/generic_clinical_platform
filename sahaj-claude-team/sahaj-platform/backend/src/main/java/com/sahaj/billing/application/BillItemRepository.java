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

    record BillItemOfBill(UUID billId, String name, long amountPaise, int qty) {
    }

    record BillItemRow(UUID id, UUID serviceId, String name, long amountPaise, int qty) {
    }
}
