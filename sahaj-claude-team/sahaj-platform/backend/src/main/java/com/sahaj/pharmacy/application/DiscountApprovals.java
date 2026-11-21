package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.SaleRules;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a discount request means for one cart (SAH-80-6, ADR-030 S14). Shared by the quote, the bill save and the request service,
 * so all three read the same answer. A request lifts the approval need only when it is APPROVED, was raised at this store, was raised
 * for the same lines (the digest) and its percent covers the cart's percent. A changed cart or a higher percent is VOID; a request
 * that is pending, rejected or withdrawn is reported as it is. Reads only; takes no lock.
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

    public DiscountApprovals(DiscountRequestRepository requests, ApprovalRepository approvals) {
        this.requests = requests;
        this.approvals = approvals;
    }

    /** The digest of the cart as a request keeps it. */
    static String digest(SaleCart.Cart cart) {
        List<String> keys = new ArrayList<>();
        for (SaleCart.Line l : cart.lines()) {
            keys.add(l.productId() + "|" + l.qty());
        }
        return SaleRules.cartDigest(keys);
    }

    /** The state of the request the cart names, as it applies to this cart now. */
    Check check(UUID tenantId, UUID storeId, SaleCart.Cart cart) {
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
        return new Check(stateOf(approval.status()), row, approval);
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
