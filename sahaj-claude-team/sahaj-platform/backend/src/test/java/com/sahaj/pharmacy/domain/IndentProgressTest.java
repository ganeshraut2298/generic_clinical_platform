package com.sahaj.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.pharmacy.domain.IndentProgress.LineFigures;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SAH-79-11 and 79-12: the status an indent takes after a dispatch or a receipt (IndentStatus 0.3.5). */
class IndentProgressTest {

    private static LineFigures line(int approved, int dispatched, int closed, int received, int rejected, int shortQty) {
        return new LineFigures(approved, dispatched, closed, received, rejected, shortQty);
    }

    @Test
    void aDispatchOfEverythingApprovedIsDispatchedAndABackOrderKeepsItPartlyDispatched() {
        assertThat(IndentProgress.afterDispatch(List.of(line(20, 20, 0, 0, 0, 0), line(10, 10, 0, 0, 0, 0))))
                .isEqualTo(IndentStatus.DISPATCHED);
        assertThat(IndentProgress.afterDispatch(List.of(line(20, 15, 0, 0, 0, 0), line(10, 10, 0, 0, 0, 0))))
                .isEqualTo(IndentStatus.PARTLY_DISPATCHED);
    }

    @Test
    void aClosedBalanceStopsCountingAsABackOrder() {
        assertThat(IndentProgress.afterDispatch(List.of(line(20, 15, 5, 0, 0, 0)))).isEqualTo(IndentStatus.DISPATCHED);
        assertThat(line(20, 15, 5, 0, 0, 0).remaining()).isZero();
        assertThat(line(20, 15, 0, 0, 0, 0).remaining()).isEqualTo(5);
    }

    @Test
    void anIndentIsReceivedOnlyWhenEveryApprovedQuantityIsDispatchedAndReceivedRejectedOrShort() {
        List<LineFigures> done = List.of(line(20, 20, 0, 18, 1, 1), line(10, 10, 0, 10, 0, 0));
        assertThat(IndentProgress.afterReceipt(IndentStatus.DISPATCHED, done, 0)).isEqualTo(IndentStatus.RECEIVED);
        assertThat(IndentProgress.afterReceipt(IndentStatus.PARTLY_DISPATCHED, done, 0)).isEqualTo(IndentStatus.RECEIVED);
    }

    @Test
    void aBackOrderOrUnitsStillInTransitKeepTheStatusAfterAReceipt() {
        List<LineFigures> backOrder = List.of(line(20, 15, 0, 15, 0, 0));
        assertThat(IndentProgress.afterReceipt(IndentStatus.PARTLY_DISPATCHED, backOrder, 0))
                .isEqualTo(IndentStatus.PARTLY_DISPATCHED);
        List<LineFigures> inTransit = List.of(line(20, 20, 0, 10, 0, 0));
        assertThat(IndentProgress.afterReceipt(IndentStatus.DISPATCHED, inTransit, 0)).isEqualTo(IndentStatus.DISPATCHED);
    }

    @Test
    void aScheduleXLineWaitingForAPharmacistKeepsTheIndentFromBeingReceived() {
        List<LineFigures> done = List.of(line(20, 20, 0, 20, 0, 0));
        assertThat(IndentProgress.afterReceipt(IndentStatus.PARTLY_DISPATCHED, done, 2))
                .isEqualTo(IndentStatus.PARTLY_DISPATCHED);
        assertThat(IndentProgress.afterReceipt(IndentStatus.DISPATCHED, done, 2)).isEqualTo(IndentStatus.DISPATCHED);
    }

    @Test
    void theBackOrderChipIsOnlyOnAPartlyDispatchedIndent() {
        assertThat(IndentProgress.backOrderOpen(IndentStatus.PARTLY_DISPATCHED, List.of(false, true))).isTrue();
        assertThat(IndentProgress.backOrderOpen(IndentStatus.PARTLY_DISPATCHED, List.of(false, false))).isFalse();
        assertThat(IndentProgress.backOrderOpen(IndentStatus.RECEIVED, List.of(true))).isFalse();
    }

    @Test
    void theReturnRoutingSendsDamagedAndNearExpiryToQuarantineAndAnExpiredBatchNeverToSaleableStock() {
        assertThat(ReturnRouting.defaultPutBackInto("receipt-reject.damaged-in-transit", false)).isEqualTo("QUARANTINE");
        assertThat(ReturnRouting.defaultPutBackInto("receipt-reject.near-expiry", false)).isEqualTo("QUARANTINE");
        assertThat(ReturnRouting.defaultPutBackInto("receipt-reject.wrong-item", false)).isEqualTo("SALEABLE");
        assertThat(ReturnRouting.defaultPutBackInto("receipt-reject.excess-quantity", false)).isEqualTo("SALEABLE");
        assertThat(ReturnRouting.defaultPutBackInto("receipt-reject.wrong-item", true)).isEqualTo("QUARANTINE");
        assertThat(ReturnRouting.isShort("receipt-reject.short")).isTrue();
        assertThat(ReturnRouting.isShort("receipt-reject.wrong-item")).isFalse();
    }
}
