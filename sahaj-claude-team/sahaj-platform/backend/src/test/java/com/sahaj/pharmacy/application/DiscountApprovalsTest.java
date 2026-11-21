package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-80-6: what a discount request means for a cart, and the bill save that honours an approved one (ADR-030 S14). */
class DiscountApprovalsTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);

    private final SaleWorld w = new SaleWorld();
    private final SaleBillService service = new SaleBillService(w.f.gate, w.f.guard, w.products, w.customers, w.held, w.bills,
            w.registers, w.pricer, w.approvals, w.assembler, w.support, w.numbers, w.series, w.storeSeries, w.posting, w.signature,
            w.clients, w.events, w.blind, w.f.auditLog, w.f.time);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");
    private final UUID requestId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    DiscountApprovalsTest() {
        w.shelf(product, "B-OK", FAR, 50, "280.20");
        when(w.rules.current(any())).thenReturn(Optional.empty()); // the standard preset: every discount waits for approval
    }

    private SaleCart.Cart cart(String percent, UUID request, int qty) {
        SaleInputs.CartInput in = new SaleInputs.CartInput(w.walkIn(), List.of(w.line(product, qty)), percent, request, null, null);
        List<FieldMessage> errors = new ArrayList<>();
        SaleCart.Cart typed = w.pricer.read(SaleWorld.TENANT, in, errors);
        assertThat(errors).isEmpty();
        return typed;
    }

    private void stored(String status, String percent, int qtyOfCart, UUID store) {
        SaleCart.Cart original = cart(percent, null, qtyOfCart);
        when(w.discountRequests.find(any(), eq(requestId))).thenReturn(Optional.of(new DiscountRequestRepository.Row(requestId,
                w.unitId(), store, approvalId, DiscountApprovals.digest(original), new BigDecimal(percent), 2802L, null,
                w.cashier.member().membershipId(), Instant.now())));
        when(w.approvalRepository.find(any(), eq(approvalId))).thenReturn(Optional.of(new ApprovalRepository.Request(approvalId,
                w.unitId(), TransactionType.SALE_DISCOUNT, "SALE_DISCOUNT", requestId, "Discount", store,
                w.cashier.member().membershipId(), Instant.now(), null, 2802L, false, false, false, new BigDecimal(percent), null,
                "PENDING".equals(status) ? 1 : null, Instant.now(), status, 0L)));
    }

    @Test
    void aCartWithNoRequestHasNoState() {
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", null, 2)).state())
                .isEqualTo(DiscountApprovals.State.NONE);
    }

    @Test
    void anApprovedRequestOfTheSameLinesCoversTheSamePercentAndALowerOneButNotAHigherOne() {
        stored("APPROVED", "5", 2, w.shop.id());

        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.APPROVED);
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("3", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.APPROVED);
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("6", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.VOID);
    }

    @Test
    void aChangedCartAnotherStoreAndAnUnknownRequestAreVoid() {
        stored("APPROVED", "5", 2, w.shop.id());
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", requestId, 3)).state())
                .isEqualTo(DiscountApprovals.State.VOID);
        assertThat(w.approvals.check(SaleWorld.TENANT, UUID.randomUUID(), cart("5", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.VOID);
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", UUID.randomUUID(), 2)).state())
                .isEqualTo(DiscountApprovals.State.VOID);
    }

    @Test
    void thePendingRejectedAndWithdrawnStatesAreReportedAsTheyAre() {
        stored("PENDING", "5", 2, w.shop.id());
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.PENDING);
        stored("REJECTED", "5", 2, w.shop.id());
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.REJECTED);
        stored("WITHDRAWN", "5", 2, w.shop.id());
        assertThat(w.approvals.check(SaleWorld.TENANT, w.shop.id(), cart("5", requestId, 2)).state())
                .isEqualTo(DiscountApprovals.State.WITHDRAWN);
    }

    @Test
    void theQuoteLiftsTheApprovalBlockerForAnApprovedRequest() {
        stored("APPROVED", "5", 2, w.shop.id());
        PharmacyCaller caller = w.caller(w.cashier);
        Store store = w.f.storeRepository.find(SaleWorld.TENANT, w.shop.id()).orElseThrow();

        SaleViews.SaleQuote approved = w.pricer.price(caller, store, cart("5", requestId, 2), false).quote();
        SaleViews.SaleQuote without = w.pricer.price(caller, store, cart("5", null, 2), false).quote();

        assertThat(approved.discount().approvalNeeded()).isFalse();
        assertThat(approved.blockers()).doesNotContain("DISCOUNT_APPROVAL");
        assertThat(approved.discount().approval()).isEqualTo("APPROVED");
        assertThat(without.discount().approvalNeeded()).isTrue();
        assertThat(without.blockers()).contains("DISCOUNT_APPROVAL");
    }

    @Test
    void theBillSaveAnswersNeededWithoutARequestAndVoidForAPendingOne() {
        PharmacyCaller caller = w.caller(w.cashier);
        Store store = w.f.storeRepository.find(SaleWorld.TENANT, w.shop.id()).orElseThrow();
        SaleCart.Cart noRequest = cart("5", null, 2);
        SalePricer.Priced priced = w.pricer.price(caller, store, noRequest, false);

        assertThatThrownBy(() -> save(noRequest, priced, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("discount-approval-needed"));
        stored("PENDING", "5", 2, w.shop.id());
        SaleCart.Cart pending = cart("5", requestId, 2);
        SalePricer.Priced pendingPriced = w.pricer.price(caller, store, pending, false);
        assertThatThrownBy(() -> save(pending, pendingPriced, requestId)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("discount-approval-void"));
        verify(w.bills, never()).insertBill(any(), any());
    }

    @Test
    void anApprovedRequestLetsTheBillSaveAndIsStoredOnTheBill() {
        stored("APPROVED", "5", 2, w.shop.id());
        SaleCart.Cart approved = cart("5", requestId, 2);
        PharmacyCaller caller = w.caller(w.cashier);
        Store store = w.f.storeRepository.find(SaleWorld.TENANT, w.shop.id()).orElseThrow();
        SalePricer.Priced priced = w.pricer.price(caller, store, approved, false);
        List<SaleBillRepository.BillRow> inserted = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            inserted.add(inv.getArgument(1));
            return null;
        }).when(w.bills).insertBill(any(), any());
        when(w.bills.find(any(), any())).thenAnswer(inv -> inserted.stream().findFirst());

        save(approved, priced, requestId);

        ArgumentCaptor<SaleBillRepository.BillRow> bill = ArgumentCaptor.forClass(SaleBillRepository.BillRow.class);
        verify(w.bills).insertBill(any(), bill.capture());
        assertThat(bill.getValue().discountRequestId()).isEqualTo(requestId);
        assertThat(bill.getValue().discountPaise()).isPositive();
    }

    private void save(SaleCart.Cart cart, SalePricer.Priced priced, UUID request) {
        service.create(SaleWorld.TENANT, w.cashier.userId(), shop, SaleWorld.body(new SaleInputs.BillCreate(UUID.randomUUID(),
                priced.quote().quoteVersion(), BigDecimal.valueOf(priced.math().netPayable()), cart.toInput().buyer(),
                cart.toInput().lines(), "5", request, null, null, List.of(new SaleInputs.PaymentInput("CASH",
                BigDecimal.valueOf(priced.math().netPayable()))), null, null)), null);
    }
}
