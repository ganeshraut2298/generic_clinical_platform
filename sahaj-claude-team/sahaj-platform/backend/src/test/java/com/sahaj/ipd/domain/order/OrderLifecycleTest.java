package com.sahaj.ipd.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderLifecycleTest {

    @Test
    void signGivesPendingOrActiveByVerification() {
        assertThat(OrderLifecycle.next(null, OrderAction.SIGN, true)).contains(OrderStatus.PENDING);
        assertThat(OrderLifecycle.next(null, OrderAction.SIGN, false)).contains(OrderStatus.ACTIVE);
        assertThat(OrderLifecycle.next(OrderStatus.ACTIVE, OrderAction.SIGN, true)).isEmpty();
    }

    @Test
    void theTableOfAdr027D1() {
        assertThat(OrderLifecycle.next(OrderStatus.PENDING, OrderAction.VERIFY, true)).contains(OrderStatus.ACTIVE);
        assertThat(OrderLifecycle.next(OrderStatus.PENDING, OrderAction.REJECT, true)).contains(OrderStatus.REJECTED);
        assertThat(OrderLifecycle.next(OrderStatus.PENDING, OrderAction.QUERY, true)).contains(OrderStatus.QUERIED);
        assertThat(OrderLifecycle.next(OrderStatus.QUERIED, OrderAction.REPLY, true)).contains(OrderStatus.PENDING);
        assertThat(OrderLifecycle.next(OrderStatus.ACTIVE, OrderAction.HOLD, true)).contains(OrderStatus.ON_HOLD);
        assertThat(OrderLifecycle.next(OrderStatus.ON_HOLD, OrderAction.RESUME, true)).contains(OrderStatus.ACTIVE);
        for (OrderStatus s : new OrderStatus[] {OrderStatus.PENDING, OrderStatus.QUERIED, OrderStatus.ACTIVE,
                OrderStatus.ON_HOLD}) {
            assertThat(OrderLifecycle.next(s, OrderAction.STOP, true)).contains(OrderStatus.STOPPED);
        }
    }

    @Test
    void changeFollowsVerificationAndIsRefusedOnHoldAndFinal() {
        assertThat(OrderLifecycle.next(OrderStatus.ACTIVE, OrderAction.CHANGE, true)).contains(OrderStatus.PENDING);
        assertThat(OrderLifecycle.next(OrderStatus.QUERIED, OrderAction.CHANGE, false)).contains(OrderStatus.ACTIVE);
        assertThat(OrderLifecycle.next(OrderStatus.ON_HOLD, OrderAction.CHANGE, true)).isEmpty();
        assertThat(OrderLifecycle.next(OrderStatus.STOPPED, OrderAction.CHANGE, true)).isEmpty();
    }

    @Test
    void finalStatusesAcceptNothing() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.STOPPED, OrderStatus.REJECTED}) {
            assertThat(s.isFinal()).isTrue();
            for (OrderAction a : OrderAction.values()) {
                Optional<OrderStatus> next = OrderLifecycle.next(s, a, true);
                assertThat(next).as(s + " " + a).isEmpty();
            }
        }
    }

    @Test
    void holdNeedsActiveAndResumeNeedsHold() {
        assertThat(OrderLifecycle.canApply(OrderStatus.PENDING, OrderAction.HOLD)).isFalse();
        assertThat(OrderLifecycle.canApply(OrderStatus.ACTIVE, OrderAction.RESUME)).isFalse();
        assertThat(OrderLifecycle.canApply(null, OrderAction.STOP)).isFalse();
    }

    @Test
    void readOnlyAllowsOnlyTheNotGivableActions() {
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.STOP)).isTrue();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.HOLD)).isTrue();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.QUERY)).isTrue();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.REJECT)).isTrue();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.RESUME)).isFalse();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.CHANGE)).isFalse();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.REPLY)).isFalse();
        assertThat(OrderLifecycle.allowedInReadOnly(OrderAction.VERIFY)).isFalse();
    }
}
