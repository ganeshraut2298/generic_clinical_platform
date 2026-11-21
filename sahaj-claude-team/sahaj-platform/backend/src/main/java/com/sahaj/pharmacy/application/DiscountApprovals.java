package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.SaleRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a discount request means for one cart (SAH-80-6, ADR-030 S14). Shared by the quote, the bill save and the request service,
 * so all three read the same answer. A request lifts the approval need only when it is APPROVED, was raised at this store, was raised
 * by the seller who now uses it, was raised for the same lines (the digest), its percent covers the cart's percent, it is used on the
 * IST day on which it was approved (the latest APPROVED decision), no bill carries it yet (single use), and, when it was raised for a
 * held bill, the bill comes from that held bill. Anything else is VOID; a request that is pending, rejected or withdrawn is reported
 * as it is. Reads only; takes no lock (the unique index on bill.discount_request_id is the backstop for a race).
 */
@Component
public class DiscountApprovals {

    /** NONE: the cart names no request. */
    public enum State { NONE, PENDING, APPROVED, REJECTED, WITHDRAWN, VOID }

    public record Check(State state, DiscountRequestRepository.Row row, ApprovalRepository.Request approval) {

        boolean approved() {
            return state == State.APPROVED;
        }
    }

    private final DiscountRequestRepository requests;
    private final ApprovalRepository approvals;
    private final SaleBillRepository bills;
    private final PharmacyTime time;

    public DiscountApprovals(DiscountRequestRepository requests, ApprovalRepository approvals, SaleBillRepository bills,
                             PharmacyTime time) {
        this.requests = requests;
        this.approvals = approvals;
        this.bills = bills;
        this.time = time;
    }

    /** The digest of the cart as a request keeps it. */
    static String digest(SaleCart.Cart cart) {
        List<String> keys = new ArrayList<>();
        for (SaleCart.Line l : cart.lines()) {
            keys.add(l.productId() + "|" + l.qty());
        }
        return SaleRules.cartDigest(keys);
    }

    /** The state as the quote reads it: the held bill is not known there, so it is not compared. */
    Check check(UUID tenantId, UUID storeId, SaleCart.Cart cart, UUID seller) {
        return check(tenantId, storeId, cart, seller, null);
    }

    /** The held bill of a bill save: its id and the discount request id its cart carries; both null when the save names none. */
    public record Held(UUID heldBillId, UUID carriedRequestId) {
    }

    /**
     * The state of the request the cart names, as it applies to this cart now. {@code seller} is the membership using the request;
     * {@code held} is set only at the bill save (null at the quote, which takes no held bill).
     */
    Check check(UUID tenantId, UUID storeId, SaleCart.Cart cart, UUID seller, Held held) {
        if (cart.discountRequestId() == null) {
            return new Check(State.NONE, null, null);
        }
        DiscountRequestRepository.Row row = requests.find(tenantId, cart.discountRequestId()).orElse(null);
        if (row == null || !row.storeId().equals(storeId)) {
            return new Check(State.VOID, null, null);
        }
        ApprovalRepository.Request approval = approvals.find(tenantId, row.approvalRequestId()).orElse(null);
        if (approval == null) {
            return new Check(State.VOID, row, null);
        }
        boolean sameCart = row.cartDigest().equals(digest(cart))
                && cart.percent() != null && cart.percent().compareTo(row.discountPercent()) <= 0;
        if (!sameCart) {
            return new Check(State.VOID, row, approval);
        }
        State state = stateOf(approval.status());
        if (state != State.APPROVED) {
            return new Check(state, row, approval);
        }
        boolean heldOk = held == null || heldBillMatches(row, held);
        if (!heldOk || !usable(tenantId, row, approval, seller)) {
            return new Check(State.VOID, row, approval);
        }
        return new Check(State.APPROVED, row, approval);
    }

    /**
     * True when an APPROVED request can still be used by {@code seller}: raised by that seller, approved on today's IST day and
     * carried by no bill. The request read (pharmacyGetDiscountRequest) answers VOID when this is false.
     */
    boolean usable(UUID tenantId, DiscountRequestRepository.Row row, ApprovalRepository.Request approval, UUID seller) {
        return seller != null && seller.equals(row.requestedBy()) && approvedToday(tenantId, approval)
                && !bills.existsWithDiscountRequest(tenantId, row.id());
    }

    /**
     * A request raised for a held bill is used only by a save of that held bill; one raised on a live cart is used by a save that
     * names no held bill or a held bill that carries the request.
     */
    private static boolean heldBillMatches(DiscountRequestRepository.Row row, Held held) {
        if (row.heldBillId() != null) {
            return row.heldBillId().equals(held.heldBillId());
        }
        return held.heldBillId() == null || row.id().equals(held.carriedRequestId());
    }

    /** The approval time is the latest APPROVED decision of the request, read as an IST date (approval_request has no decided_at). */
    private boolean approvedToday(UUID tenantId, ApprovalRepository.Request approval) {
        Instant approvedAt = approvals.decisions(tenantId, approval.id()).stream()
                .filter(d -> "APPROVED".equals(d.decision()) && d.decidedAt() != null)
                .map(ApprovalRepository.Decision::decidedAt).max(Instant::compareTo).orElse(null);
        return approvedAt != null && approvedAt.atZone(PharmacyTime.IST).toLocalDate().equals(time.today());
    }

    static State stateOf(String status) {
        switch (status) {
            case "PENDING":
                return State.PENDING;
            case "APPROVED":
                return State.APPROVED;
            case "REJECTED":
                return State.REJECTED;
            case "WITHDRAWN":
                return State.WITHDRAWN;
            default:
                return State.VOID;
        }
    }
}
