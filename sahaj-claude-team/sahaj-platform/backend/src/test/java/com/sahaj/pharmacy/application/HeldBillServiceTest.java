package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-5: held bills (hold, list, resume, discard) over an in-memory held-bill table. */
class HeldBillServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final Map<UUID, HeldBillRepository.Row> table = new LinkedHashMap<>();
    private final DiscountRequestService discounts = org.mockito.Mockito.mock(DiscountRequestService.class);
    private final HeldBillService service = new HeldBillService(w.f.gate, w.f.guard, w.f.storeRepository, w.held, w.products,
            w.customers, w.pricer, w.support, w.clients, w.events, w.f.auditLog, w.f.time, w.mapper, discounts);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");

    HeldBillServiceTest() {
        w.shelf(product, "B-OK", LocalDate.of(2027, 6, 30), 50, "100.00");
        doAnswer(inv -> {
            HeldBillRepository.NewHeld h = inv.getArgument(1);
            table.put(h.id(), new HeldBillRepository.Row(h.id(), h.unitId(), h.storeId(), h.holder(), h.cartJson(), "OPEN",
                    h.discountRequestId(), h.heldNetPaise(), Instant.parse("2026-11-17T05:00:00Z"), 0L));
            return null;
        }).when(w.held).insert(any(), any());
        when(w.held.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(table.get((UUID) inv.getArgument(1))));
        when(w.held.findForUpdate(any(), any())).thenAnswer(inv -> Optional.ofNullable(table.get((UUID) inv.getArgument(1))));
        when(w.held.countOpen(any(), any())).thenAnswer(inv -> (int) table.values().stream()
                .filter(r -> "OPEN".equals(r.status())).count());
        when(w.held.open(any(), any(), anyInt())).thenAnswer(inv -> table.values().stream()
                .filter(r -> "OPEN".equals(r.status())).toList());
        doAnswer(inv -> {
            HeldBillRepository.Row r = table.get((UUID) inv.getArgument(1));
            table.put(r.id(), new HeldBillRepository.Row(r.id(), r.unitId(), r.storeId(), r.holder(), r.cartJson(),
                    inv.getArgument(3), r.discountRequestId(), r.heldNetPaise(), r.createdAt(), r.version() + 1));
            return true;
        }).when(w.held).close(any(), any(), anyLong(), any(), any());
    }

    private SaleInputs.HoldInput hold(UUID key, UUID replaces) {
        return new SaleInputs.HoldInput(key, replaces, w.walkIn(), List.of(w.line(product, 2)), "5", null, null, null);
    }

    private PharmacyViews.Outcome<SaleViews.HeldBill> holdAs(PharmacyFixture.Person p, SaleInputs.HoldInput in) {
        return service.hold(SaleWorld.TENANT, p.userId(), shop, SaleWorld.body(in));
    }

    @Test
    void aCartIsHeldWithoutANumberAStockMovementOrAPayment() {
        PharmacyViews.Outcome<SaleViews.HeldBill> out = holdAs(w.cashier, hold(UUID.randomUUID(), null));

        assertThat(out.created()).isTrue();
        SaleViews.HeldBill held = out.view();
        assertThat(held.status()).isEqualTo("OPEN");
        assertThat(held.itemCount()).isEqualTo(1);
        assertThat(held.heldBy().name()).isEqualTo("Test Cashier One");
        assertThat(held.cart().lines().get(0).qty()).isEqualTo(BigDecimal.valueOf(2));
        assertThat(held.cart().discountPercent()).isEqualTo("5");
        assertThat(held.heldNetPaise()).isEqualTo(19_000L);
        assertThat(held.products()).extracting(StockViews.ProductRef::name).containsExactly("Testamol");
        assertThat(held.buyerLabel()).isEqualTo("Walk-in");
        assertThat(held.discountState()).isEqualTo("NONE");
        verify(w.posting, never()).post(any(), any());
        verify(w.numbers, never()).next(any(), any());
        verify(w.bills, never()).insertBill(any(), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_BILL_HELD");
        assertThat(w.f.audit.get(0).after()).containsEntry("lines", 1);
        verify(w.events).insert(any(), any(), eq("HELD_BILL"), eq(held.id()), eq("HELD"), eq(w.shop.id()), eq(w.cashier.id()),
                any(), any(), any(), any(), any());
    }

    @Test
    void theTwentyFirstOpenHeldBillOfAStoreIsHeldLimitAndNothingIsWritten() {
        for (int i = 0; i < 20; i++) {
            holdAs(w.cashier, hold(UUID.randomUUID(), null));
        }

        assertThatThrownBy(() -> holdAs(w.cashier, hold(UUID.randomUUID(), null))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("held-limit");
                    assertThat(e.getMessage()).isEqualTo("Too many held bills. Finish or discard one.");
                });
        assertThat(table).hasSize(20);
    }

    @Test
    void aResumedBillHeldAgainReplacesTheOldRowEvenAtTheLimit() {
        UUID first = holdAs(w.cashier, hold(UUID.randomUUID(), null)).view().id();
        for (int i = 0; i < 19; i++) {
            holdAs(w.cashier, hold(UUID.randomUUID(), null));
        }

        SaleViews.HeldBill again = holdAs(w.cashier, hold(UUID.randomUUID(), first)).view();

        assertThat(table.get(first).status()).isEqualTo("REPLACED");
        assertThat(table.get(again.id()).status()).isEqualTo("OPEN");
        assertThatThrownBy(() -> holdAs(w.cashier, hold(UUID.randomUUID(), first))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
    }

    @Test
    void aSecondTapOfTheSameKeyAnswersTheFirstHeldBillWithNothingWritten() {
        UUID key = UUID.randomUUID();
        SaleViews.HeldBill first = holdAs(w.cashier, hold(key, null)).view();
        when(w.clients.own(any(), any(), eq(key))).thenReturn(Optional.of(new ClientRequestRepository.Taken(w.cashier.id(),
                HeldBillService.OPERATION, first.id())));
        w.f.audit.clear();

        PharmacyViews.Outcome<SaleViews.HeldBill> second = holdAs(w.cashier, hold(key, null));

        assertThat(second.created()).isFalse();
        assertThat(second.view().id()).isEqualTo(first.id());
        assertThat(table).hasSize(1);
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void aCartWithoutLinesOrAKeyIsRefused() {
        assertThatThrownBy(() -> holdAs(w.cashier, new SaleInputs.HoldInput(UUID.randomUUID(), null, w.walkIn(), List.of(), null,
                null, null, null))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().get(0).message()).isEqualTo("Add at least one item."));
        assertThatThrownBy(() -> holdAs(w.cashier, hold(null, null))).isInstanceOf(InvalidFieldsException.class);
        assertThat(table).isEmpty();
    }

    @Test
    void theListShowsEveryOpenBillOldestFirstWithAMaskedBuyerAndTheScheduledMark() {
        UUID h = w.product("Testcillin", "Amoxicillin", "H");
        w.shelf(h, "B-H", LocalDate.of(2027, 6, 30), 10, "10.00");
        holdAs(w.cashier, hold(UUID.randomUUID(), null));
        UUID customerId = UUID.randomUUID();
        when(w.customers.find(any(), eq(customerId))).thenReturn(Optional.of(w.customer(customerId, "Ravi Kumar", 0, 0, 0,
                "ACTIVE")));
        when(w.customers.findAll(any(), any())).thenReturn(Map.of(customerId,
                w.customer(customerId, "Ravi Kumar", 0, 0, 0, "ACTIVE")));
        holdAs(w.f.pharmacist, new SaleInputs.HoldInput(UUID.randomUUID(), null, new SaleInputs.BuyerInput("CUSTOMER", null,
                customerId, null, null, false, false), List.of(w.line(h, 1)), null, null, null, null));

        SaleViews.HeldBillList list = service.list(SaleWorld.TENANT, w.f.pharmacist.userId(), shop);

        assertThat(list.items()).hasSize(2);
        assertThat(list.items().get(0).buyerLabel()).isEqualTo("Walk-in");
        assertThat(list.items().get(0).hasScheduledLine()).isFalse();
        assertThat(list.items().get(1).buyerLabel()).isEqualTo("R*** K***");
        assertThat(list.items().get(1).hasScheduledLine()).isTrue();
        assertThat(list.items().get(1).heldBy().name()).isEqualTo("Test Pharmacist One");
        assertThat(list.items().get(0).ageMinutes()).isEqualTo(35);
    }

    @Test
    void aHeldBillOfAnotherStoreIsNotFoundAndAReadWritesNothing() {
        SaleViews.HeldBill held = holdAs(w.cashier, hold(UUID.randomUUID(), null)).view();
        PharmacyViews.Store other = w.f.createStore("Test Second Pharmacy", "SP", com.sahaj.pharmacy.domain.StoreType.PHARMACY);
        w.f.map(other, w.cashier, com.sahaj.pharmacy.domain.StoreRole.CASHIER);
        w.f.audit.clear();

        assertThat(service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, held.id().toString()).id()).isEqualTo(held.id());
        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.cashier.userId(), other.id().toString(), held.id().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void discardKeepsTheRowAsDiscardedAndAnOldVersionOrAClosedBillIsChanged() {
        SaleViews.HeldBill held = holdAs(w.cashier, hold(UUID.randomUUID(), null)).view();
        PharmacyInputs.Body<PharmacyInputs.VersionInput> stale = SaleWorld.body(new PharmacyInputs.VersionInput(BigDecimal.valueOf(5)));
        PharmacyInputs.Body<PharmacyInputs.VersionInput> right = SaleWorld.body(new PharmacyInputs.VersionInput(BigDecimal.ZERO));

        assertThatThrownBy(() -> service.discard(SaleWorld.TENANT, w.cashier.userId(), shop, held.id().toString(), stale))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("changed");
                    assertThat(e.extensions()).containsKey("current");
                });
        SaleViews.HeldBill discarded = service.discard(SaleWorld.TENANT, w.cashier.userId(), shop, held.id().toString(), right);

        assertThat(discarded.status()).isEqualTo("DISCARDED");
        assertThat(table.get(held.id()).status()).isEqualTo("DISCARDED");
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_BILL_HELD", "PHARMACY_HELD_BILL_DISCARDED");
        org.mockito.Mockito.verify(discounts).withdrawForDiscard(eq(SaleWorld.TENANT), any(), any(), eq(held.id()));
        assertThatThrownBy(() -> service.discard(SaleWorld.TENANT, w.cashier.userId(), shop, held.id().toString(), right))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
    }

    @Test
    void theHoldLocksTheStoreRowBeforeItCountsAndTheCartKeepsNoPriceOrBatch() throws Exception {
        SaleViews.HeldBill held = holdAs(w.cashier, hold(UUID.randomUUID(), null)).view();

        assertThat(table.get(held.id()).cartJson()).doesNotContain("B-OK").doesNotContain("mrp").doesNotContain("100.00");
        assertThat(w.f.locks).contains("store");
        List<String> noDiscountHeld = new ArrayList<>();
        noDiscountHeld.add(table.get(held.id()).cartJson());
        assertThat(noDiscountHeld.get(0)).contains("\"discountPercent\":\"5\"");
    }
}
