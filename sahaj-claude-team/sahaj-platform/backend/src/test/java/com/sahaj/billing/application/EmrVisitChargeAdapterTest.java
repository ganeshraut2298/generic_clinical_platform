package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.application.BillItemRepository.EmrLine;
import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.billing.application.PaymentRepository.PaymentRow;
import com.sahaj.opd.api.EmrVisitChargePort.Line;
import com.sahaj.opd.api.EmrVisitChargePort.Outcome;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.api.ServiceLookupPort.ServiceSummary;
import com.sahaj.platform.api.AuditLog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmrVisitChargeAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID BILL = UUID.randomUUID();
    private static final UUID DRESSING = UUID.randomUUID();
    private static final UUID INJECTION = UUID.randomUUID();

    private final BillRepository bills = mock(BillRepository.class);
    private final BillItemRepository items = mock(BillItemRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ServiceLookupPort services = mock(ServiceLookupPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private EmrVisitChargeAdapter adapter;

    @BeforeEach
    void setUp() {
        when(services.find(TENANT, DRESSING)).thenReturn(Optional.of(new ServiceSummary(DRESSING, "Dressing", 15000,
                true)));
        when(services.find(TENANT, INJECTION)).thenReturn(Optional.of(new ServiceSummary(INJECTION, "Injection",
                8000, false)));
        when(bills.findByVisit(TENANT, VISIT)).thenReturn(Optional.of(new BillRow(BILL, VISIT, 0, null, 1)));
        adapter = new EmrVisitChargeAdapter(bills, items, payments, services, audit);
    }

    @Test
    void aMissingLineIsInsertedWithTheCurrentPriceAndAudited() {
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of());

        Outcome outcome = adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("item-1", DRESSING, 2)));

        assertThat(outcome).isEqualTo(Outcome.SYNCED);
        verify(items).insertEmr(any(UUID.class), eq(BILL), eq(DRESSING), eq("Dressing"), eq(15000L), eq(2),
                eq("item-1"), eq(ACTOR));
        verify(audit).record(eq("BILL_ITEM_ADDED_FROM_EMR"), eq("BILL"), eq(BILL), any(), any());
    }

    @Test
    void theSameSetAgainChangesNothing() {
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of(new EmrLine(UUID.randomUUID(), "item-1", DRESSING, 2,
                15000)));

        Outcome outcome = adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("item-1", DRESSING, 2)));

        assertThat(outcome).isEqualTo(Outcome.SYNCED);
        verify(items, never()).insertEmr(any(), any(), any(), anyString(), anyLong(), anyInt(), anyString(), any());
        verify(items, never()).delete(any(), any());
        verify(items, never()).updateQty(any(), any(), anyInt());
    }

    @Test
    void aRemovedItemDeletesItsLineAndAQtyChangeUpdatesIt() {
        UUID gone = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of(new EmrLine(gone, "item-1", DRESSING, 1, 15000),
                new EmrLine(kept, "item-2", DRESSING, 1, 15000)));
        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of());

        Outcome outcome = adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("item-2", DRESSING, 3)));

        assertThat(outcome).isEqualTo(Outcome.SYNCED);
        verify(items).delete(TENANT, gone);
        verify(items).updateQty(TENANT, kept, 3);
        verify(audit).record(eq("BILL_ITEM_REMOVED_FROM_EMR"), eq("BILL"), eq(BILL), any(), any());
        verify(audit).record(eq("BILL_ITEM_QTY_CHANGED_FROM_EMR"), eq("BILL"), eq(BILL), any(), any());
    }

    @Test
    void onceAnyPaymentExistsARemovalOrQtyChangeIsAlreadyBilledAndNothingChanges() {
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of(new EmrLine(UUID.randomUUID(), "item-1", DRESSING, 1,
                15000)));
        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of(mock(PaymentRow.class)));

        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of())).isEqualTo(Outcome.ALREADY_BILLED);
        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("item-1", DRESSING, 2))))
                .isEqualTo(Outcome.ALREADY_BILLED);
        verify(items, never()).delete(any(), any());
        verify(items, never()).updateQty(any(), any(), anyInt());
    }

    @Test
    void addingALineIsStillAllowedOnAPaidBill() {
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of(new EmrLine(UUID.randomUUID(), "item-1", DRESSING, 1,
                15000)));
        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of(mock(PaymentRow.class)));

        Outcome outcome = adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("item-1", DRESSING, 1),
                new Line("item-2", DRESSING, 1)));

        assertThat(outcome).isEqualTo(Outcome.SYNCED);
        verify(items).insertEmr(any(UUID.class), eq(BILL), eq(DRESSING), eq("Dressing"), eq(15000L), eq(1),
                eq("item-2"), eq(ACTOR));
    }

    @Test
    void anInactiveUnknownOrOutOfRangeLineIsInvalidAndNothingIsTouched() {
        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("a", INJECTION, 1))))
                .isEqualTo(Outcome.INVALID_SERVICE);
        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("a", UUID.randomUUID(), 1))))
                .isEqualTo(Outcome.INVALID_SERVICE);
        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of(new Line("a", DRESSING, 21))))
                .isEqualTo(Outcome.INVALID_SERVICE);
        verify(items, never()).insertEmr(any(), any(), any(), anyString(), anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void noBillAndNothingWantedMakesNoBill() {
        when(bills.findByVisit(TENANT, VISIT)).thenReturn(Optional.empty());

        assertThat(adapter.syncEmrLines(TENANT, ACTOR, VISIT, List.of())).isEqualTo(Outcome.SYNCED);
        verify(bills, never()).getOrCreate(any(), any(), any());
    }

    @Test
    void billedStateSaysPaidOrNot() {
        when(items.findEmrLines(TENANT, BILL)).thenReturn(List.of(new EmrLine(UUID.randomUUID(), "item-1", DRESSING, 1,
                15000)));
        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of());
        assertThat(adapter.billedState(TENANT, VISIT)).containsEntry("item-1", "ON_BILL");

        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of(mock(PaymentRow.class)));
        assertThat(adapter.billedState(TENANT, VISIT)).containsEntry("item-1", "ON_PAID_BILL");
    }
}
