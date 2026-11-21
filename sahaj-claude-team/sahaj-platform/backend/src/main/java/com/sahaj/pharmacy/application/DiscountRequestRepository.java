package com.sahaj.pharmacy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code pharmacy.sale_discount_request} (SAH-80-6, ADR-030 S14): the append-only link between an approval engine request
 * (subject SALE_DISCOUNT) and the cart it was raised for. The row id is the {@code discountRequestId} of the contract and the
 * subject id of the engine request. A request is never changed; VOID is computed from the digest and the percent.
 */
public interface DiscountRequestRepository {

    record Row(UUID id, UUID unitId, UUID storeId, UUID approvalRequestId, String cartDigest, BigDecimal discountPercent,
               long discountPaise, UUID heldBillId, UUID requestedBy, Instant createdAt) {
    }

    record NewRow(UUID id, UUID unitId, UUID storeId, UUID approvalRequestId, String cartDigest, BigDecimal discountPercent,
                  long discountPaise, UUID heldBillId, UUID requestedBy) {
    }

    Optional<Row> find(UUID tenantId, UUID id);

    Optional<Row> findByApprovalRequest(UUID tenantId, UUID approvalRequestId);

    /** The requests of this seller at this store that an earlier request replaces (same held bill, or no held bill), newest first. */
    List<Row> bySeller(UUID tenantId, UUID storeId, UUID requestedBy, UUID heldBillId);

    /** Every request that names this held bill at this store (any seller), newest first. */
    List<Row> byHeldBill(UUID tenantId, UUID storeId, UUID heldBillId);

    void insert(UUID tenantId, NewRow row, UUID actorMembershipId);
}
