package com.sahaj.ipd.domain.order;

import java.util.Optional;

/**
 * SAH-76 (ADR-027 D1): the pure order transitions. The database guard trigger repeats the one-way rule; this class is
 * what services call first. Roles, the self-verification rule and Read-only IPD are checked by the services (they are
 * not part of the state table): {@link #allowedInReadOnly} states which actions an IPD in Read-only still allows.
 */
public final class OrderLifecycle {

    private OrderLifecycle() {
    }

    /**
     * The status after {@code action}, or empty when the action is not allowed from {@code from}. SIGN needs
     * {@code from} null (a new order); every other action needs an existing status.
     *
     * @param verificationRequired used by SIGN and CHANGE: the version needs the pharmacist check (PENDING) or not (ACTIVE)
     */
    public static Optional<OrderStatus> next(OrderStatus from, OrderAction action, boolean verificationRequired) {
        if (action == OrderAction.SIGN) {
            return from == null ? Optional.of(entryStatus(verificationRequired)) : Optional.empty();
        }
        if (from == null) {
            return Optional.empty();
        }
        switch (action) {
            case VERIFY:
                return from == OrderStatus.PENDING ? Optional.of(OrderStatus.ACTIVE) : Optional.empty();
            case REJECT:
                return from == OrderStatus.PENDING ? Optional.of(OrderStatus.REJECTED) : Optional.empty();
            case QUERY:
                return from == OrderStatus.PENDING ? Optional.of(OrderStatus.QUERIED) : Optional.empty();
            case REPLY:
                return from == OrderStatus.QUERIED ? Optional.of(OrderStatus.PENDING) : Optional.empty();
            case CHANGE:
                return from == OrderStatus.PENDING || from == OrderStatus.QUERIED || from == OrderStatus.ACTIVE
                        ? Optional.of(entryStatus(verificationRequired)) : Optional.empty();
            case HOLD:
                return from == OrderStatus.ACTIVE ? Optional.of(OrderStatus.ON_HOLD) : Optional.empty();
            case RESUME:
                return from == OrderStatus.ON_HOLD ? Optional.of(OrderStatus.ACTIVE) : Optional.empty();
            case STOP:
                return from == OrderStatus.PENDING || from == OrderStatus.QUERIED || from == OrderStatus.ACTIVE
                        || from == OrderStatus.ON_HOLD ? Optional.of(OrderStatus.STOPPED) : Optional.empty();
            default:
                return Optional.empty();
        }
    }

    /** Whether the action is allowed from the status (verification does not change the answer). */
    public static boolean canApply(OrderStatus from, OrderAction action) {
        return next(from, action, true).isPresent();
    }

    /** Actions that stay allowed while IPD is Read-only: those that only make an order not givable or close an item. */
    public static boolean allowedInReadOnly(OrderAction action) {
        return action == OrderAction.REJECT || action == OrderAction.QUERY || action == OrderAction.HOLD
                || action == OrderAction.STOP;
    }

    /** A change, stop or reject closes an open pharmacist query (BR-5). */
    public static boolean closesOpenQuery(OrderAction action) {
        return action == OrderAction.CHANGE || action == OrderAction.STOP || action == OrderAction.REJECT;
    }

    private static OrderStatus entryStatus(boolean verificationRequired) {
        return verificationRequired ? OrderStatus.PENDING : OrderStatus.ACTIVE;
    }
}
