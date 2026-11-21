package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.InvalidFieldsException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-80-6: the discount request, its states for the bill save, and the SALE_DISCOUNT approval handler. */
class DiscountRequestServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);

    private final SaleWorld w = new SaleWorld();
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final DiscountRequestService service = new DiscountRequestService(w.f.gate, w.f.guard, w.pricer, engine,
            w.approvalRepository, w.discountRequests, w.f.storeRepository, w.support, w.clients, w.events, w.f.auditLog);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");

    DiscountRequestServiceTest() {
        w.shelf(product, "B-OK", FAR, 50, "280.20");
    }

    private void leanRule() {
        ApprovalRuleRepository.Version v = new ApprovalRuleRepository.Version(UUID.randomUUID(), 1, RulePreset.STANDARD, null,
                Instant.now(), UUID.randomUUID());
        when(w.rules.current(any())).thenReturn(Optional.of(v));
        when(w.rules.rules(any(), eq(v.id()))).thenReturn(List.of());
    }

    private SaleInputs.DiscountRequestInput input(String percent) {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), percent, w.line(product, 2));
        return new SaleInputs.DiscountRequestInput(UUID.randomUUID(), null, cart.buyer(), cart.lines(), percent, null, null, null);
    }

    private ApprovalRepository.Request request(UUID id, UUID subjectId, String status) {
        return new ApprovalRepository.Request(id, w.unitId(), TransactionType.SALE_DISCOUNT, "SALE_DISCOUNT", subjectId,
                "Discount 5%", w.shop.id(), w.cashier.member().membershipId(), Instant.parse("2026-11-17T05:00:00Z"), null, 2802L,
                false, false, false, new BigDecimal("5"), null, "PENDING".equals(status) ? 1 : null,
                Instant.parse("2026-11-17T05:00:00Z"), status, 0L);
    }

    private ApprovalEngine.Plan plan() {
        return new ApprovalEngine.Plan(new ApprovalRuleRepository.Version(null, 0, RulePreset.STANDARD, null, null, null),
                List.of(new ApprovalRepository.NewLevel(1, ApproverKind.OWNER, ConditionKind.ALWAYS, null, null, null, true,
                        "Sale discount above 0%")), 1, null, false);
    }

    @Test
    void aDiscountWithinTheLimitNeedsNoRequestAndWritesNothing() {
        leanRule();
        PharmacyViews.Outcome<SaleCheckViews.DiscountRequest> out = service.request(SaleWorld.TENANT, w.cashier.userId(), shop,
                SaleWorld.body(input("5")));

        assertThat(out.view().required()).isFalse();
        assertThat(out.view().requestId()).isNull();
        assertThat(out.view().discountPaise()).isPositive();
        verify(engine, never()).submit(any(), any(), any(), any(), any());
        verify(w.discountRequests, never()).insert(any(), any(), any());
    }

    @Test
    void aDiscountAboveTheLimitIsSentToTheEngineWithTheCartDigestAndTheServersAmount() {
        when(engine.plan(any(), any())).thenReturn(plan());
        UUID approvalId = UUID.randomUUID();
        when(engine.submit(any(), any(), any(), any(), any())).thenReturn(approvalId);
        List<DiscountRequestRepository.Row> saved = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            DiscountRequestRepository.NewRow r = inv.getArgument(1);
            saved.add(new DiscountRequestRepository.Row(r.id(), r.unitId(), r.storeId(), r.approvalRequestId(), r.cartDigest(),
                    r.discountPercent(), r.discountPaise(), r.heldBillId(), r.requestedBy(), Instant.now()));
            return null;
        }).when(w.discountRequests).insert(any(), any(), any());
        when(w.discountRequests.find(any(), any())).thenAnswer(inv -> saved.stream().findFirst());
        when(w.approvalRepository.find(any(), eq(approvalId))).thenAnswer(inv -> Optional.of(request(approvalId,
                saved.get(0).id(), "PENDING")));
        when(engine.summary(any(), eq("SALE_DISCOUNT"), any())).thenReturn(new StockViews.ApprovalSummary(approvalId, 0L, 1,
                "PENDING", 1, "Sale discount above 0%", "OWNER", false, false, false, null));

        PharmacyViews.Outcome<SaleCheckViews.DiscountRequest> out = service.request(SaleWorld.TENANT, w.cashier.userId(), shop,
                SaleWorld.body(input("5")));

        assertThat(out.created()).isTrue();
        assertThat(out.view().required()).isTrue();
        assertThat(out.view().status()).isEqualTo("PENDING");
        assertThat(out.view().ruleText()).isEqualTo("Discount above 0%");
        assertThat(out.view().waitingFor()).isEqualTo("OWNER");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).cartDigest()).hasSize(32).matches("[0-9a-f]+");
        assertThat(saved.get(0).requestedBy()).isEqualTo(w.cashier.member().membershipId());
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).submit(any(), any(), eq(w.cashier.member().membershipId()), subject.capture(), any());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.SALE_DISCOUNT);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(w.shop.id());
        assertThat(subject.getValue().maxRateRisePercent()).isEqualByComparingTo("5");
        assertThat(w.f.auditActions()).contains("PHARMACY_DISCOUNT_REQUESTED");
        assertThat(w.f.audit.toString()).doesNotContain("Test Walk-in One");
    }

    @Test
    void theOwnerGetsRequiredFalseAndNoRequestEvenAboveTheLimit() {
        when(engine.plan(any(), any())).thenReturn(plan());

        PharmacyViews.Outcome<SaleCheckViews.DiscountRequest> out = service.request(SaleWorld.TENANT, w.f.owner.userId(), shop,
                SaleWorld.body(input("40")));

        assertThat(out.view().required()).isFalse();
        assertThat(out.view().requestId()).isNull();
        verify(engine, never()).submit(any(), any(), any(), any(), any());
        verify(w.discountRequests, never()).insert(any(), any(), any());
    }

    @Test
    void aPercentOfZeroOrNothingIsA400() {
        leanRule();
        assertThatThrownBy(() -> service.request(SaleWorld.TENANT, w.cashier.userId(), shop, SaleWorld.body(input("0"))))
                .isInstanceOf(InvalidFieldsException.class);
        verify(engine, never()).plan(any(), any());
    }

    @Test
    void aStoreThatDoesNotAllowSaleAnswers409BeforeTheBody() {
        UUID central = w.central.id();
        assertThatThrownBy(() -> service.request(SaleWorld.TENANT, w.f.owner.userId(), central.toString(), SaleWorld.body(
                (SaleInputs.DiscountRequestInput) null))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void withdrawingAPendingRequestWritesTheStatusOnceAndAFinishedOneIsUntouched() {
        UUID id = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        DiscountRequestRepository.Row row = new DiscountRequestRepository.Row(id, w.unitId(), w.shop.id(), approvalId,
                "0123456789abcdef0123456789abcdef", new BigDecimal("5"), 2802L, null, w.cashier.member().membershipId(),
                Instant.now());
        when(w.discountRequests.find(any(), eq(id))).thenReturn(Optional.of(row));
        when(w.approvalRepository.findForUpdate(any(), eq(approvalId))).thenReturn(Optional.of(request(approvalId, id, "PENDING")));
        when(w.approvalRepository.find(any(), eq(approvalId))).thenReturn(Optional.of(request(approvalId, id, "WITHDRAWN")));
        when(w.approvalRepository.finish(any(), any(), anyLong(), eq("WITHDRAWN"), any())).thenReturn(true);

        SaleCheckViews.DiscountRequest view = service.withdraw(SaleWorld.TENANT, w.cashier.userId(), shop, id.toString());

        assertThat(view.status()).isEqualTo("WITHDRAWN");
        verify(w.approvalRepository).finish(any(), eq(approvalId), eq(0L), eq("WITHDRAWN"), any());
        assertThat(w.f.auditActions()).contains("PHARMACY_DISCOUNT_WITHDRAWN");

        when(w.approvalRepository.findForUpdate(any(), eq(approvalId))).thenReturn(Optional.of(request(approvalId, id, "APPROVED")));
        w.f.audit.clear();
        service.withdraw(SaleWorld.TENANT, w.cashier.userId(), shop, id.toString());
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void discardingAHeldBillWithdrawsItsPendingRequestsAndLeavesDecidedOnesAlone() {
        UUID held = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        UUID decidedId = UUID.randomUUID();
        UUID pendingApproval = UUID.randomUUID();
        UUID decidedApproval = UUID.randomUUID();
        UUID by = w.cashier.member().membershipId();
        when(w.discountRequests.byHeldBill(any(), eq(w.shop.id()), eq(held))).thenReturn(List.of(
                new DiscountRequestRepository.Row(pendingId, w.unitId(), w.shop.id(), pendingApproval, "d", new BigDecimal("5"),
                        100L, held, by, Instant.now()),
                new DiscountRequestRepository.Row(decidedId, w.unitId(), w.shop.id(), decidedApproval, "d", new BigDecimal("5"),
                        100L, held, by, Instant.now())));
        when(w.approvalRepository.findForUpdate(any(), eq(pendingApproval)))
                .thenReturn(Optional.of(request(pendingApproval, pendingId, "PENDING")));
        when(w.approvalRepository.findForUpdate(any(), eq(decidedApproval)))
                .thenReturn(Optional.of(request(decidedApproval, decidedId, "APPROVED")));
        when(w.approvalRepository.finish(any(), eq(pendingApproval), anyLong(), eq("WITHDRAWN"), any())).thenReturn(true);

        service.withdrawForDiscard(SaleWorld.TENANT, w.caller(w.cashier),
                new com.sahaj.pharmacy.domain.Store(w.shop.id(), w.unitId(), "Test Main Pharmacy", "MP", com.sahaj.pharmacy.domain.StoreType.PHARMACY, null,
                        true, true, true, true, 0), held);

        verify(w.approvalRepository).finish(any(), eq(pendingApproval), eq(0L), eq("WITHDRAWN"), any());
        verify(w.approvalRepository, never()).finish(any(), eq(decidedApproval), anyLong(), any(), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_DISCOUNT_WITHDRAWN");
    }

    @Test
    void theHandlerWritesTheHistoryAndTheAuditAndNothingElse() {
        UUID id = UUID.randomUUID();
        ApprovalRepository.Request request = request(UUID.randomUUID(), id, "PENDING");
        PharmacyCaller owner = w.caller(w.f.owner);
        ApprovalSubjectHandler.Decision decision = new ApprovalSubjectHandler.Decision(SaleWorld.TENANT, owner, request, "OWNER",
                new PharmacyInputs.ApprovalDecisionInput(null, null, null, "REJECT", "approval-reject.other", "x", null, null));

        service.onRejected(decision);
        service.onApproved(decision);

        verify(w.events).insert(any(), any(), eq("SALE_DISCOUNT"), eq(id), eq("DISCOUNT_DECIDED"), any(), any(), any(), any(),
                any(), any(), eq("approval-reject.other"));
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_DISCOUNT_DECIDED", "PHARMACY_DISCOUNT_DECIDED");
        assertThat(service.subjectType()).isEqualTo("SALE_DISCOUNT");
        assertThat(service.canRead(w.caller(w.cashier), request)).isTrue();
        assertThat(service.canRead(w.caller(w.f.pharmacist), request(UUID.randomUUID(), id, "PENDING"))).isTrue();
    }
}
