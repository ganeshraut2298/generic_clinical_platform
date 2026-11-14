package com.sahaj.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.EmrVisitChargePort.Outcome;
import com.sahaj.opd.api.EmrVisitChargePort.PricedLine;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.platform.api.AuditLog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SAH-69-3 and SAH-68-2 (ADR-025 D7, D14, H13): a dental plan row done today becomes a priced bill line with no service,
 * also on a bill that has a payment (a new open line, never a change); the visit-type fee is only recomputed while the bill
 * has no payment.
 */
class EmrVisitChargeAdapterPlanLinesTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID VISIT = UUID.randomUUID();
    private static final UUID BILL = UUID.randomUUID();

    private final BillRepository bills = mock(BillRepository.class);
    private final BillItemRepository items = mock(BillItemRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private EmrVisitChargePort adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmrVisitChargeAdapter(bills, items, payments, mock(ServiceLookupPort.class), audit);
        BillRow bill = mock(BillRow.class);
        when(bill.id()).thenReturn(BILL);
        when(bills.findByVisit(TENANT, VISIT)).thenReturn(Optional.of(bill));
    }

    @Test
    void aPlanLineIsAddedOnceWithItsLabelAndAmountAndAuditedWithIdsAndMoneyOnly() {
        when(items.insertPlan(any(), eq(BILL), eq("Root canal · tooth 16"), eq(150000L), eq("plan:r1:1"), eq(ACTOR)))
                .thenReturn(true);

        Outcome outcome = adapter.addPricedLines(TENANT, ACTOR, VISIT,
                List.of(new PricedLine("plan:r1:1", "Root canal · tooth 16", 150000)));

        assertThat(outcome).isEqualTo(Outcome.SYNCED);
        verify(audit).record("BILL_ITEM_ADDED_FROM_EMR_PLAN", "BILL", BILL, null,
                Map.of("visitId", VISIT, "qty", 1, "amountPaise", 150000L));
    }

    @Test
    void aRepeatAddsNothingAndAuditsNothing() {
        when(items.insertPlan(any(), any(), anyString(), anyLong(), anyString(), any())).thenReturn(false);

        assertThat(adapter.addPricedLines(TENANT, ACTOR, VISIT, List.of(new PricedLine("plan:r1:1", "Filling", 50000))))
                .isEqualTo(Outcome.SYNCED);
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aPaidBillStillTakesANewPlanLine() {
        when(payments.findByBill(TENANT, BILL)).thenReturn(List.of(mock(PaymentRepository.PaymentRow.class)));
        when(items.insertPlan(any(), any(), anyString(), anyLong(), anyString(), any())).thenReturn(true);

        assertThat(adapter.hasPayment(TENANT, VISIT)).isTrue();
        assertThat(adapter.addPricedLines(TENANT, ACTOR, VISIT, List.of(new PricedLine("plan:r2:1", "Scaling", 80000))))
                .isEqualTo(Outcome.SYNCED);
    }

    @Test
    void aLabelOrAmountOutOfRangeIsRefusedWithoutWritingAnything() {
        for (PricedLine bad : List.of(new PricedLine("k", "", 1), new PricedLine("k", "x".repeat(121), 1),
                new PricedLine("k", "ok", -1), new PricedLine("k".repeat(81), "ok", 1))) {
            assertThat(adapter.addPricedLines(TENANT, ACTOR, VISIT, List.of(bad))).isEqualTo(Outcome.INVALID_SERVICE);
        }
        verify(items, never()).insertPlan(any(), any(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void noBillMeansNoPayment() {
        UUID other = UUID.randomUUID();
        when(bills.findByVisit(TENANT, other)).thenReturn(Optional.empty());

        assertThat(adapter.hasPayment(TENANT, other)).isFalse();
        assertThat(adapter.addPricedLines(TENANT, ACTOR, other, List.of())).isEqualTo(Outcome.SYNCED);
    }
}
