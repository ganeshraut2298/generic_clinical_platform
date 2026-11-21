package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ApproverKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** SAH-80-3, 80-4, 80-9, 80-10: the sale transaction, its checks and its lists (docs/api/pharmacy-sales.yaml). */
class SaleBillServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);

    private final SaleWorld w = new SaleWorld();
    private final SaleRegisterRepository registers = w.registers;
    private final SaleBillService service = new SaleBillService(w.f.gate, w.f.guard, w.products, w.customers, w.held, w.bills,
            registers, w.pricer, w.approvals, w.assembler, w.support, w.numbers, w.series, w.storeSeries, w.posting, w.signature,
            w.clients, w.events, w.blind, w.f.auditLog, w.f.time);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");
    private final Map<UUID, SaleBillRepository.BillRow> billRows = new HashMap<>();
    private final List<SaleBillRepository.LineRow> lineRows = new ArrayList<>();
    private final List<SaleBillRepository.PaymentRow> paymentRows = new ArrayList<>();

    SaleBillServiceTest() {
        w.shelf(product, "B-OK", FAR, 50, "280.20");
        leanDiscount();
        doAnswer(inv -> {
            SaleBillRepository.BillRow b = inv.getArgument(1);
            billRows.put(b.id(), b);
            return null;
        }).when(w.bills).insertBill(any(), any());
        doAnswer(inv -> {
            lineRows.add(inv.getArgument(3));
            return null;
        }).when(w.bills).insertLine(any(), any(), any(), any(), any());
        doAnswer(inv -> {
            paymentRows.add(new SaleBillRepository.PaymentRow(inv.getArgument(4), inv.getArgument(5)));
            return null;
        }).when(w.bills).insertPayment(any(), any(), any(), any(), any(), anyLong(), any());
        when(w.bills.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(billRows.get((UUID) inv.getArgument(1))));
        when(w.bills.lines(any(), any())).thenAnswer(inv -> lineRows);
        when(w.bills.payments(any(), any())).thenAnswer(inv -> paymentRows);
        when(w.stock.findBatch(any(), any())).thenAnswer(inv -> w.balances.values().stream().flatMap(List::stream)
                .filter(b -> b.batchId().equals(inv.getArgument(1))).findFirst().map(b -> new StockRepository.BatchRow(
                        b.batchId(), b.productId(), b.batchNo(), b.expiryDate(), b.mrp(), b.purchaseRate(), null, b.firstReceivedAt())));
    }

    private void leanDiscount() {
        ApprovalRuleRepository.Version v = new ApprovalRuleRepository.Version(UUID.randomUUID(), 1, RulePreset.STANDARD, null,
                Instant.now(), UUID.randomUUID());
        when(w.rules.current(any())).thenReturn(Optional.of(v));
        when(w.rules.rules(any(), eq(v.id()))).thenReturn(List.of());
    }

    private SaleViews.SaleQuote quote(SaleInputs.CartInput cart) {
        PharmacyCaller caller = w.caller(w.f.pharmacist);
        return w.pricer.price(caller, w.f.storeRepository.find(SaleWorld.TENANT, w.shop.id()).orElseThrow(),
                typed(cart), false).quote();
    }

    private SaleCart.Cart typed(SaleInputs.CartInput cart) {
        List<com.sahaj.platform.api.InvalidFieldsException.FieldMessage> errors = new ArrayList<>();
        SaleCart.Cart typed = w.pricer.read(SaleWorld.TENANT, cart, errors);
        assertThat(errors).isEmpty();
        return typed;
    }

    private SaleInputs.BillCreate bill(SaleInputs.CartInput cart, SaleViews.SaleQuote q, List<SaleInputs.PaymentInput> payments) {
        return new SaleInputs.BillCreate(UUID.randomUUID(), q.quoteVersion(), BigDecimal.valueOf(q.summary().netPayablePaise()),
                cart.buyer(), cart.lines(), cart.discountPercent(), null, cart.prescription(), null, payments, null, null);
    }

    private static List<SaleInputs.PaymentInput> pay(String mode, long paise) {
        return List.of(new SaleInputs.PaymentInput(mode, BigDecimal.valueOf(paise)));
    }

    private PharmacyViews.Outcome<SaleViews.Bill> save(PharmacyFixture.Person who, SaleInputs.BillCreate in, UUID signer) {
        return service.create(SaleWorld.TENANT, who.userId(), shop, SaleWorld.body(in), signer);
    }

    private PharmacyViews.Outcome<SaleViews.Bill> saveCash(PharmacyFixture.Person who, SaleInputs.CartInput cart) {
        SaleViews.SaleQuote q = quote(cart);
        return save(who, bill(cart, q, pay("CASH", q.summary().netPayablePaise())), null);
    }

    @Test
    void aCashBillIsSavedWithTheWorkedExampleMoneyTheNumberLastAndOneLedgerLine() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), "5", w.line(product, 2));

        PharmacyViews.Outcome<SaleViews.Bill> out = saveCash(w.cashier, cart);

        assertThat(out.created()).isTrue();
        SaleViews.Bill b = out.view();
        assertThat(b.billNo()).isEqualTo("SB/26-27/000001");
        assertThat(b.status()).isEqualTo("SAVED");
        assertThat(b.summary().mrpTotalPaise()).isEqualTo(56_040L);
        assertThat(b.summary().discountPaise()).isEqualTo(2_802L);
        assertThat(b.summary().roundOffPaise()).isEqualTo(-38L);
        assertThat(b.summary().netPayablePaise()).isEqualTo(53_200L);
        assertThat(b.summary().taxByRate().get(0).taxablePaise()).isEqualTo(47_534L);
        assertThat(b.payments()).extracting(SaleViews.Payment::mode).containsExactly("CASH");
        assertThat(b.payments().get(0).amountPaise()).isEqualTo(53_200L);
        assertThat(b.udharPaise()).isZero();
        assertThat(b.buyer().kind()).isEqualTo("WALK_IN");
        assertThat(b.savedBy().name()).isEqualTo("Test Cashier One");
        assertThat(b.savedBy().roleLabel()).isEqualTo("Billing");
        assertThat(b.pharmacist()).isNull();
        assertThat(b.lines()).hasSize(1);
        assertThat(b.canCancel()).isFalse();
        assertThat(b.cancelLockReason()).isEqualTo("NOT_ALLOWED");
        InOrder order = inOrder(w.stock, w.numbers, w.bills, w.posting);
        order.verify(w.stock).lockBalances(any(), any(), any());
        order.verify(w.numbers).next(any(), any());
        order.verify(w.bills).insertBill(any(), any());
        order.verify(w.posting).post(any(), any());
    }

    @Test
    void theLedgerLineIsACounterSaleOutOfAvailableWithTheBillNumberAsReference() {
        saveCash(w.cashier, w.cart(w.walkIn(), null, w.line(product, 3)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(w.posting).post(actor.capture(), lines.capture());
        assertThat(lines.getValue()).hasSize(1);
        StockPosting.Line line = lines.getValue().get(0);
        assertThat(line.txnType()).isEqualTo("COUNTER_SALE");
        assertThat(line.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(line.delta()).isEqualTo(-3);
        assertThat(line.storeId()).isEqualTo(w.shop.id());
        assertThat(line.doc().type()).isEqualTo("BILL");
        assertThat(line.doc().no()).isEqualTo("SB/26-27/000001");
        assertThat(line.doc().lineId()).isEqualTo(lineRows.get(0).id());
        assertThat(line.secondSignerMembershipId()).isNull();
        assertThat(actor.getValue().membershipId()).isEqualTo(w.cashier.id());
    }

    @Test
    void aStoreWithItsOwnSeriesTakesTheNumberFromThatSeries() {
        when(w.storeSeries.find(any(), eq(w.shop.id()))).thenReturn(Optional.of(new StoreBillSeriesRepository.Row(UUID.randomUUID(),
                w.shop.id(), "MP/", false, 5, false, 1L)));
        when(w.numbers.nextStoreBill(any(), eq(w.shop.id()))).thenReturn(42L);

        SaleViews.Bill b = saveCash(w.cashier, w.cart(w.walkIn(), null, w.line(product, 1))).view();

        assertThat(b.billNo()).isEqualTo("MP/00042");
        assertThat(billRows.values().iterator().next().seriesKind()).isEqualTo("STORE");
        verify(w.numbers, never()).next(any(), any());
    }

    @Test
    void aReplayOfTheKeyAnswersTheSavedBillAndWritesNothingMore() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(cart);
        SaleInputs.BillCreate in = bill(cart, q, pay("CASH", q.summary().netPayablePaise()));
        UUID id = save(w.cashier, in, null).view().id();
        when(w.clients.own(any(), any(), eq(in.clientKey()))).thenReturn(Optional.of(new ClientRequestRepository.Taken(w.cashier.id(),
                SaleBillService.OPERATION, id)));
        w.f.audit.clear();

        PharmacyViews.Outcome<SaleViews.Bill> again = save(w.cashier, in, null);

        assertThat(again.created()).isFalse();
        assertThat(again.view().id()).isEqualTo(id);
        assertThat(billRows).hasSize(1);
        verify(w.posting, org.mockito.Mockito.times(1)).post(any(), any());
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void aChangedPriceOrBatchIsSaleChangedWithTheNewQuoteAndNothingIsSaved() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(cart);
        SaleInputs.BillCreate stale = new SaleInputs.BillCreate(UUID.randomUUID(), "q-old", BigDecimal.valueOf(q.summary()
                .netPayablePaise()), cart.buyer(), cart.lines(), null, null, null, null, pay("CASH", q.summary().netPayablePaise()),
                null, null);

        assertThatThrownBy(() -> save(w.cashier, stale, null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.slug()).isEqualTo("sale-changed");
            assertThat(e.getMessage()).isEqualTo("Prices or batches changed. Check the bill and save again.");
            assertThat(((SaleViews.SaleQuote) e.extensions().get("quote")).quoteVersion()).isEqualTo(q.quoteVersion());
        });
        SaleInputs.BillCreate otherNet = new SaleInputs.BillCreate(UUID.randomUUID(), q.quoteVersion(), BigDecimal.valueOf(100),
                cart.buyer(), cart.lines(), null, null, null, null, pay("CASH", 100), null, null);
        assertThatThrownBy(() -> save(w.cashier, otherNet, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("sale-changed"));
        verify(w.numbers, never()).next(any(), any());
        verify(w.posting, never()).post(any(), any());
        verify(w.bills, never()).insertBill(any(), any());
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void stockShortAtSaveIsInsufficientStockWithTheAvailableQuantityAndNoNumberIsTaken() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), null, w.line(product, 80));
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("CASH", 100)), null)).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("insufficient-stock");
                    assertThat(e.extensions().get("available")).isEqualTo(50);
                });
        verify(w.numbers, never()).next(any(), any());
        verify(w.posting, never()).post(any(), any());
    }

    @Test
    void paymentsThatDoNotAddUpAreAFieldErrorWithTheNetAndNothingIsSaved() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), "5", w.line(product, 2));
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("CASH", 53_100)), null)).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).extracting(m -> m.field() + ":" + m.message())
                            .containsExactly("payments:Payments must add up to ₹ 532.");
                });
        verify(w.numbers, never()).next(any(), any());
        verify(w.posting, never()).post(any(), any());
    }

    @Test
    void thePaymentsAreCheckedForModeAmountAndRepeatsBeforeAnyLock() {
        SaleInputs.CartInput cart = w.cart(w.walkIn(), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(cart);
        List<SaleInputs.PaymentInput> bad = List.of(new SaleInputs.PaymentInput("BARTER", BigDecimal.TEN),
                new SaleInputs.PaymentInput("CASH", BigDecimal.ZERO), new SaleInputs.PaymentInput("UPI", BigDecimal.TEN),
                new SaleInputs.PaymentInput("UPI", BigDecimal.TEN));

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, bad), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(m -> m.field()).containsExactly("payments[0].mode",
                        "payments[1].amountPaise", "payments[3].mode"));
        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, List.of()), null)).isInstanceOf(InvalidFieldsException.class);
        verify(w.stock, never()).lockBalances(any(), any(), any());
    }

    @Test
    void aDiscountRequestThatDoesNotExistIsVoid() {
        when(w.rules.current(any())).thenReturn(Optional.empty()); // the standard preset: no limit set, every discount waits
        SaleInputs.CartInput cart = w.cart(w.walkIn(), "5", w.line(product, 2));
        SaleViews.SaleQuote q = quote(cart);
        SaleInputs.BillCreate in = new SaleInputs.BillCreate(UUID.randomUUID(), q.quoteVersion(), BigDecimal.valueOf(q.summary()
                .netPayablePaise()), cart.buyer(), cart.lines(), "5", UUID.randomUUID(), null, null,
                pay("CASH", q.summary().netPayablePaise()), null, null);

        assertThatThrownBy(() -> save(w.cashier, in, null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.slug()).isEqualTo("discount-approval-void");
            assertThat(e.getMessage()).isEqualTo(SaleBillService.DISCOUNT_APPROVAL_VOID);
        });
        verify(w.numbers, never()).next(any(), any());
        verify(w.bills, never()).insertBill(any(), any());
    }

    // ---- udhar and credit notes ----

    private SaleInputs.BuyerInput customerBuyer(UUID id) {
        return new SaleInputs.BuyerInput("CUSTOMER", null, id, null, null, false, false);
    }

    private void customer(CustomerRepository.Row row) {
        when(w.customers.find(any(), eq(row.id()))).thenReturn(Optional.of(row));
        when(w.customers.findForUpdate(any(), eq(row.id()))).thenReturn(Optional.of(row));
    }

    @Test
    void udharLocksTheCustomerFirstRaisesTheDueAndWritesACreditBillEntry() {
        UUID id = UUID.randomUUID();
        customer(w.customer(id, "Test Customer One", 500_000, 100_000, 20_000, "ACTIVE"));
        SaleInputs.CartInput cart = w.cart(customerBuyer(id), null, w.line(product, 2));
        SaleViews.SaleQuote q = quote(cart);
        long net = q.summary().netPayablePaise();
        List<SaleInputs.PaymentInput> payments = List.of(new SaleInputs.PaymentInput("CASH", BigDecimal.valueOf(10_000)),
                new SaleInputs.PaymentInput("CREDIT_NOTE", BigDecimal.valueOf(5_000)),
                new SaleInputs.PaymentInput("UDHAR", BigDecimal.valueOf(net - 15_000)));

        SaleViews.Bill b = save(w.cashier, bill(cart, q, payments), null).view();

        assertThat(b.udharPaise()).isEqualTo(net - 15_000);
        assertThat(b.buyer().kind()).isEqualTo("CUSTOMER");
        InOrder order = inOrder(w.customers, w.stock, w.numbers);
        order.verify(w.customers).findForUpdate(any(), eq(id));
        order.verify(w.stock).lockBalances(any(), any(), any());
        order.verify(w.numbers).next(any(), any());
        verify(w.customers).setBalances(any(), eq(id), eq(100_000 + net - 15_000), eq(15_000L), any());
        ArgumentCaptor<CustomerRepository.NewEntry> entries = ArgumentCaptor.forClass(CustomerRepository.NewEntry.class);
        verify(w.customers, org.mockito.Mockito.times(2)).insertEntry(any(), entries.capture());
        assertThat(entries.getAllValues()).extracting(CustomerRepository.NewEntry::kind).containsExactly("CREDIT_NOTE_USED",
                "CREDIT_BILL");
        assertThat(entries.getAllValues().get(1).dueAfterPaise()).isEqualTo(100_000 + net - 15_000);
        assertThat(entries.getAllValues().get(1).creditNoteBalanceAfterPaise()).isEqualTo(15_000L);
        assertThat(entries.getAllValues().get(0).billId()).isEqualTo(b.id());
    }

    @Test
    void udharAboveTheLimitIsCreditLimitExceededAndNothingIsSaved() {
        UUID id = UUID.randomUUID();
        customer(w.customer(id, "Test Customer One", 50_000, 10_000, 0, "ACTIVE"));
        SaleInputs.CartInput cart = w.cart(customerBuyer(id), null, w.line(product, 2));
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("UDHAR", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("This is above the credit limit. Reduce the udhar amount or ask the owner."));
        verify(w.numbers, never()).next(any(), any());
        verify(w.customers, never()).setBalances(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void udharForAWalkInOrWithAnAnonymousBuyerIsRefusedAndASwitchedOffCustomerIs409() {
        SaleInputs.CartInput walkIn = w.cart(w.walkIn(), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(walkIn);
        assertThatThrownBy(() -> save(w.cashier, bill(walkIn, q, pay("UDHAR", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("Choose a customer for udhar."));

        UUID id = UUID.randomUUID();
        customer(w.customer(id, "Test Customer One", 500_000, 0, 0, "INACTIVE"));
        SaleInputs.CartInput off = w.cart(customerBuyer(id), null, w.line(product, 1));
        SaleViews.SaleQuote qOff = quote(off);
        assertThatThrownBy(() -> save(w.cashier, bill(off, qOff, pay("UDHAR", qOff.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("customer-inactive"));
        // the same customer may still pay cash
        assertThat(save(w.cashier, bill(off, qOff, pay("CASH", qOff.summary().netPayablePaise())), null).created()).isTrue();
    }

    @Test
    void aCreditNoteAboveTheBalanceIsRefused() {
        UUID id = UUID.randomUUID();
        customer(w.customer(id, "Test Customer One", 500_000, 0, 1_000, "ACTIVE"));
        SaleInputs.CartInput cart = w.cart(customerBuyer(id), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("CREDIT_NOTE", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("The credit note balance is lower than this amount."));
    }

    @Test
    void saveToCustomersMakesACustomerRecordAndTheBillNamesIt() {
        SaleInputs.BuyerInput buyer = new SaleInputs.BuyerInput("WALK_IN", null, null, "Test Walk-in One", "9000000101", true,
                false);
        SaleInputs.CartInput cart = w.cart(buyer, null, w.line(product, 1));

        SaleViews.Bill b = saveCash(w.cashier, cart).view();

        ArgumentCaptor<CustomerRepository.NewCustomer> created = ArgumentCaptor.forClass(CustomerRepository.NewCustomer.class);
        verify(w.customers).insert(any(), created.capture(), any());
        assertThat(created.getValue().kind()).isEqualTo("WALK_IN");
        assertThat(billRows.get(b.id()).buyerKind()).isEqualTo("CUSTOMER");
        assertThat(billRows.get(b.id()).customerId()).isEqualTo(created.getValue().id());
        assertThat(billRows.get(b.id()).walkInName()).isNull();
    }

    // ---- schedules and the registers ----

    private SaleInputs.PrescriptionInput card(String doctor, String reg, int age, String address) {
        return new SaleInputs.PrescriptionInput("TYPED", doctor, reg, SaleWorld.TODAY.minusDays(1), "Test Walk-in One",
                BigDecimal.valueOf(age), address);
    }

    private SaleInputs.CartInput withCard(UUID p, SaleInputs.PrescriptionInput card) {
        return new SaleInputs.CartInput(w.walkIn(), List.of(w.line(p, 1)), null, null, card, null);
    }

    @Test
    void aCashierOrTheOwnerSavingAScheduledLineIsPharmacistRequiredWithTheSentenceOfTheOperation() {
        UUID h = w.product("Testcillin", "Amoxicillin", "H");
        UUID x = w.product("Testdone", "Methadone", "X");
        w.shelf(h, "B-H", FAR, 10, "10.00");
        w.shelf(x, "B-X", FAR, 10, "10.00");
        SaleInputs.CartInput hCart = w.cart(w.walkIn(), null, w.line(h, 1));
        SaleViews.SaleQuote q = quote(hCart);
        SaleInputs.CartInput xCart = withCard(x, card("Dr Test One", "MH-1", 40, null));
        SaleViews.SaleQuote qx = quote(xCart);

        assertThatThrownBy(() -> save(w.cashier, bill(hCart, q, pay("CASH", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.slug()).isEqualTo("pharmacist-required");
                    assertThat(e.getMessage()).isEqualTo("A pharmacist must complete this bill.");
                });
        assertThatThrownBy(() -> save(w.f.owner, bill(xCart, qx, pay("CASH", qx.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Only a pharmacist can sell a Schedule X medicine."));
        assertThat(save(w.f.pharmacist, bill(hCart, q, pay("CASH", q.summary().netPayablePaise())), null).created()).isTrue();
        verify(w.posting, org.mockito.Mockito.times(1)).post(any(), any());
    }

    @Test
    void anOverrideOrASubstitutionByANonPharmacistIsPharmacistRequired() {
        UUID other = w.product("Testamol Plus", "Paracetamol", "NONE");
        w.shelf(other, "B-O", FAR, 10, "10.00");
        SaleInputs.CartLineInput substituted = new SaleInputs.CartLineInput(other, BigDecimal.ONE, product,
                new SaleInputs.SubstitutionInput("substitution.out-of-stock", null, true), null, null, null);
        SaleInputs.CartInput cart = new SaleInputs.CartInput(w.walkIn(), List.of(substituted), null, null, null, null);
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("CASH", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("pharmacist-required"));
        SaleViews.Bill saved = save(w.f.pharmacist, bill(cart, q, pay("CASH", q.summary().netPayablePaise())), null).view();
        assertThat(saved.lines().get(0).substitution().reasonKey()).isEqualTo("substitution.out-of-stock");
        assertThat(saved.lines().get(0).prescribedProduct().name()).isEqualTo("Testamol");
        assertThat(saved.lines().get(0).patientAgreed()).isTrue();
        assertThat(saved.lines().get(0).decidedBy().name()).isEqualTo("Test Pharmacist One");
        assertThat(saved.pharmacist().name()).isEqualTo("Test Pharmacist One");
    }

    @Test
    void anH1LineWritesOneRegisterRowWithThePatientAndPrescriberDetails() {
        UUID p = w.product("Testtuss", "Codeine", "H1");
        w.shelf(p, "B-H1", FAR, 10, "10.00");
        SaleInputs.CartInput cart = withCard(p, card("Dr Test One", "MH-1234", 40, "Test Lane 1, Test Town"));

        SaleViews.Bill b = saveCash(w.f.pharmacist, cart).view();

        ArgumentCaptor<SaleRegisterRepository.H1Entry> row = ArgumentCaptor.forClass(SaleRegisterRepository.H1Entry.class);
        verify(registers).insertH1(any(), row.capture());
        SaleRegisterRepository.H1Entry e = row.getValue();
        assertThat(e.billId()).isEqualTo(b.id());
        assertThat(e.billLineId()).isEqualTo(lineRows.get(0).id());
        assertThat(e.billNo()).isEqualTo(b.billNo());
        assertThat(e.patientName()).isEqualTo("Test Walk-in One");
        assertThat(e.patientAge()).isEqualTo(40);
        assertThat(e.patientAddress()).isEqualTo("Test Lane 1, Test Town");
        assertThat(e.prescriberName()).isEqualTo("Dr Test One");
        assertThat(e.prescriberRegNo()).isEqualTo("MH-1234");
        assertThat(e.qty()).isEqualTo(1);
        assertThat(e.pharmacistMembershipId()).isEqualTo(w.f.pharmacist.id());
        assertThat(e.overrideReasonKey()).isNull();
        verify(registers, never()).insertScheduleX(any(), any());
        assertThat(w.f.audit.toString()).doesNotContain("Test Walk-in One").doesNotContain("Test Lane 1");
    }

    @Test
    void anH1LineWithoutPrescriberOrCardIsRefusedAndAnOverrideIsKeptOnTheRowAndTheBillLine() {
        UUID p = w.product("Testtuss", "Codeine", "H1");
        w.shelf(p, "B-H1", FAR, 10, "10.00");
        SaleInputs.CartInput missing = w.cart(w.walkIn(), null, w.line(p, 1));
        SaleViews.SaleQuote q = quote(missing);

        assertThatThrownBy(() -> save(w.f.pharmacist, bill(missing, q, pay("CASH", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).extracting(m -> m.message())
                        .contains("Prescriber name and registration number are needed for Schedule H1.",
                                "Enter the prescription details for the Schedule H1 and X items."));
        verify(w.numbers, never()).next(any(), any());

        SaleInputs.CartLineInput overridden = new SaleInputs.CartLineInput(p, BigDecimal.ONE, null, null, null,
                new SaleInputs.ReasonInput("h1-override.confirmed", null), null);
        SaleInputs.CartInput cart = new SaleInputs.CartInput(w.walkIn(), List.of(overridden), null, null,
                new SaleInputs.PrescriptionInput("TYPED", null, null, null, "Test Walk-in One", BigDecimal.valueOf(40), "Test Lane 1"),
                null);
        SaleViews.SaleQuote q2 = quote(cart);
        SaleViews.Bill b = save(w.f.pharmacist, bill(cart, q2, pay("CASH", q2.summary().netPayablePaise())), null).view();

        ArgumentCaptor<SaleRegisterRepository.H1Entry> row = ArgumentCaptor.forClass(SaleRegisterRepository.H1Entry.class);
        verify(registers).insertH1(any(), row.capture());
        assertThat(row.getValue().overrideReasonKey()).isEqualTo("h1-override.confirmed");
        assertThat(row.getValue().prescriberName()).isNull();
        assertThat(b.lines().get(0).h1Override().label()).isEqualTo("Prescriber confirmed");
    }

    @Test
    void aScheduleXLineNeedsTheVerifiedSecondSignerAndWritesBothSignersOnTheLedgerTheLineAndTheRegister() {
        UUID x = w.product("Testdone", "Methadone", "X");
        w.shelf(x, "B-X", FAR, 10, "10.00");
        SaleInputs.CartInput cart = withCard(x, card("Dr Test One", "MH-1234", 40, null));
        SaleViews.SaleQuote q = quote(cart);
        SaleInputs.BillCreate in = bill(cart, q, pay("CASH", q.summary().netPayablePaise()));

        assertThatThrownBy(() -> save(w.f.pharmacist, in, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().get(0).message()).isEqualTo("A second signature is needed for Schedule X items."));
        verify(w.numbers, never()).next(any(), any());

        save(w.f.pharmacist, in, w.pharmacist2.id());

        verify(w.signature).recheck(SaleWorld.TENANT, w.f.pharmacist.id(), w.pharmacist2.id());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(w.posting).post(any(), lines.capture());
        assertThat(lines.getValue().get(0).secondSignerMembershipId()).isEqualTo(w.pharmacist2.id());
        assertThat(lineRows.get(0).secondSigner()).isEqualTo(w.pharmacist2.id());
        ArgumentCaptor<SaleRegisterRepository.ScheduleXEntry> row = ArgumentCaptor
                .forClass(SaleRegisterRepository.ScheduleXEntry.class);
        verify(registers).insertScheduleX(any(), row.capture());
        assertThat(row.getValue().firstSignerMembershipId()).isEqualTo(w.f.pharmacist.id());
        assertThat(row.getValue().secondSignerMembershipId()).isEqualTo(w.pharmacist2.id());
        assertThat(row.getValue().prescriberRegNo()).isEqualTo("MH-1234");
        verify(registers, never()).insertH1(any(), any());
    }

    @Test
    void thePrecheckReturnsTheSignerOnlyForAPharmacistSavingAScheduleXCart() {
        UUID x = w.product("Testdone", "Methadone", "X");
        w.shelf(x, "B-X", FAR, 10, "10.00");
        PharmacyInputs.SecondSignerInput signer = new PharmacyInputs.SecondSignerInput(w.pharmacist2.id(), "1234");
        SaleInputs.BillCreate xBill = new SaleInputs.BillCreate(UUID.randomUUID(), "q", BigDecimal.ONE, w.walkIn(),
                List.of(w.line(x, 1)), null, null, null, null, pay("CASH", 100), null, signer);
        SaleInputs.BillCreate plain = new SaleInputs.BillCreate(UUID.randomUUID(), "q", BigDecimal.ONE, w.walkIn(),
                List.of(w.line(product, 1)), null, null, null, null, pay("CASH", 100), null, signer);

        assertThat(service.precheck(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, SaleWorld.body(xBill))).isSameAs(signer);
        assertThat(service.precheck(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, SaleWorld.body(plain))).isNull();
        assertThat(service.precheck(SaleWorld.TENANT, w.cashier.userId(), shop, SaleWorld.body(xBill))).isNull();
        assertThat(service.precheck(SaleWorld.TENANT, w.f.owner.userId(), shop, SaleWorld.body(xBill))).isNull();
    }

    @Test
    void aRecordedAllergyIsReadAgainAtSaveAndBlocksUntilAPharmacistOverridesIt() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientClinical.find(any(), eq(patientId))).thenReturn(Optional.of(new PatientClinicalPort.PatientClinical(
                patientId, "UHID-1", "Test Patient One", 34, "F", "Penicillin")));
        UUID p = w.product("Pen-Test", "Penicillin V", "NONE");
        w.shelf(p, "B-PEN", FAR, 10, "10.00");
        SaleInputs.BuyerInput buyer = new SaleInputs.BuyerInput("PATIENT", patientId, null, null, null, false, false);
        SaleInputs.CartInput cart = w.cart(buyer, null, w.line(p, 1));
        SaleViews.SaleQuote q = quote(cart);

        assertThatThrownBy(() -> save(w.cashier, bill(cart, q, pay("CASH", q.summary().netPayablePaise())), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors().get(0).field()).isEqualTo("lines[0].allergyOverride");
                    assertThat(e.errors().get(0).message()).doesNotContain("Penicillin");
                });
        SaleInputs.CartLineInput override = new SaleInputs.CartLineInput(p, BigDecimal.ONE, null, null,
                new SaleInputs.ReasonInput("allergy-override.confirmed", null), null, null);
        SaleInputs.CartInput overridden = new SaleInputs.CartInput(buyer, List.of(override), null, null, null, null);
        SaleViews.SaleQuote q2 = quote(overridden);
        SaleViews.Bill b = save(w.f.pharmacist, bill(overridden, q2, pay("CASH", q2.summary().netPayablePaise())), null).view();

        assertThat(b.lines().get(0).allergyOverride().reasonKey()).isEqualTo("allergy-override.confirmed");
        assertThat(billRows.get(b.id()).patientId()).isEqualTo(patientId);
        assertThat(billRows.get(b.id()).allergyNotChecked()).isFalse();
        verify(w.events).insert(any(), any(), eq("BILL"), eq(b.id()), eq("ALLERGY_OVERRIDE"), any(), any(), any(), any(), any(),
                any(), eq("allergy-override.confirmed"));
        assertThat(w.f.audit.toString()).doesNotContain("Penicillin").doesNotContain("Test Patient One");
    }

    @Test
    void anUncheckedAllergyIsMarkedOnTheBillAndALinkPendingWalkInIsSavedAsSuch() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientClinical.find(any(), eq(patientId))).thenThrow(new IllegalStateException("down"));
        SaleInputs.CartInput cart = w.cart(new SaleInputs.BuyerInput("PATIENT", patientId, null, null, null, false, false), null,
                w.line(product, 1));
        SaleViews.Bill b = saveCash(w.cashier, cart).view();
        assertThat(billRows.get(b.id()).allergyNotChecked()).isTrue();
        assertThat(b.allergyNotChecked()).isTrue();

        SaleInputs.BuyerInput pending = new SaleInputs.BuyerInput("WALK_IN", null, null, "Test Walk-in One", null, false, true);
        SaleViews.Bill linked = saveCash(w.cashier, w.cart(pending, null, w.line(product, 1))).view();
        assertThat(linked.linkPending()).isTrue();
        assertThat(billRows.get(linked.id()).buyerKind()).isEqualTo("WALK_IN");
    }

    // ---- held bill, prescriptions, access ----

    @Test
    void aResumedHeldBillIsClosedAsResumedAndAnotherStoresHeldBillIs404() {
        UUID heldId = UUID.randomUUID();
        HeldBillRepository.Row row = new HeldBillRepository.Row(heldId, w.unitId(), w.shop.id(), w.cashier.id(), "{}", "OPEN",
                null, 0, Instant.now(), 4L);
        when(w.held.findForUpdate(any(), eq(heldId))).thenReturn(Optional.of(row));
        SaleInputs.CartInput cart = w.cart(w.walkIn(), null, w.line(product, 1));
        SaleViews.SaleQuote q = quote(cart);
        SaleInputs.BillCreate in = new SaleInputs.BillCreate(UUID.randomUUID(), q.quoteVersion(), BigDecimal.valueOf(q.summary()
                .netPayablePaise()), cart.buyer(), cart.lines(), null, null, null, null,
                pay("CASH", q.summary().netPayablePaise()), heldId, null);

        save(w.cashier, in, null);

        verify(w.held).close(any(), eq(heldId), eq(4L), eq("RESUMED"), eq(w.cashier.id()));

        UUID elsewhere = UUID.randomUUID();
        when(w.held.findForUpdate(any(), eq(elsewhere))).thenReturn(Optional.of(new HeldBillRepository.Row(elsewhere, w.unitId(),
                UUID.randomUUID(), w.cashier.id(), "{}", "OPEN", null, 0, Instant.now(), 0L)));
        SaleInputs.BillCreate other = new SaleInputs.BillCreate(UUID.randomUUID(), q.quoteVersion(), BigDecimal.valueOf(q.summary()
                .netPayablePaise()), cart.buyer(), cart.lines(), null, null, null, null,
                pay("CASH", q.summary().netPayablePaise()), elsewhere, null);
        assertThatThrownBy(() -> save(w.cashier, other, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anOpdPrescriptionPartIsNotConnectedAndAStoreThatDoesNotAllowSaleIs409() {
        SaleInputs.CartLineInput rx = new SaleInputs.CartLineInput(product, BigDecimal.ONE, null, null, null, null, "rx-line-1");
        SaleInputs.BillCreate in = new SaleInputs.BillCreate(UUID.randomUUID(), "q", BigDecimal.ONE, w.walkIn(), List.of(rx), null,
                null, null, null, pay("CASH", 100), null, null);

        assertThatThrownBy(() -> save(w.cashier, in, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("not-connected"));
        PharmacyViews.Store ward = w.f.createStore("Test Ward Store", "WS", com.sahaj.pharmacy.domain.StoreType.SUB_STORE);
        w.f.map(ward, w.f.pharmacist, com.sahaj.pharmacy.domain.StoreRole.STORE_USER);
        assertThatThrownBy(() -> service.create(SaleWorld.TENANT, w.f.pharmacist.userId(), ward.id().toString(),
                SaleWorld.body(in), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("sale-not-allowed"));
    }

    @Test
    void theAuditRowAndTheHistoryHoldIdsNumbersAndCountsButNoNames() {
        SaleViews.Bill b = saveCash(w.cashier, w.cart(w.walkIn(), null, w.line(product, 2))).view();

        PharmacyFixture.Audit audit = w.f.audit.get(0);
        assertThat(audit.action()).isEqualTo("PHARMACY_BILL_SAVED");
        assertThat(audit.entityId()).isEqualTo(b.id());
        assertThat(audit.after()).containsEntry("billNo", "SB/26-27/000001").containsEntry("lines", 1).containsEntry("units", 2);
        assertThat(audit.toString()).doesNotContain("Test Walk-in One");
        verify(w.events).insert(any(), any(), eq("BILL"), eq(b.id()), eq("SAVED"), eq(w.shop.id()), eq(w.cashier.id()), any(), any(),
                any(), eq(2), any());
    }

    // ---- reads ----

    private SaleBillRepository.BillRow stored(String no, Instant at, UUID savedBy) {
        return new SaleBillRepository.BillRow(UUID.randomUUID(), w.unitId(), w.shop.id(), no, "UNIT", 2026, "SAVED", "WALK_IN",
                null, null, "Test Walk-in One", null, false, false, null, null, null, null, null, null, BigDecimal.ZERO, null,
                1000, 0, 893, 54, 53, 0, 1000, 0, at, savedBy, null, null, null, null, null, null, 0, null, 0L);
    }

    @Test
    void theListIsPagedFiltersByDayAndAnswersMasksAndMarksForACashier() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Ravi Kumar", 34, "M", null, false, "UHID-1")));
        when(w.patientLookup.mobilesOf(any(), any())).thenReturn(Map.of(patientId, "9876543214"));
        Instant at = Instant.parse("2026-11-17T05:00:00Z");
        when(w.bills.page(any(), eq(w.shop.id()), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new SaleBillRepository.ListSlice(List.of(
                        new SaleBillRepository.ListRow(UUID.randomUUID(), "SB/26-27/000002", at, "PATIENT", patientId, null, null,
                                null, 2, 53_200, "SAVED", true, true, false, false, false, false, false),
                        new SaleBillRepository.ListRow(UUID.randomUUID(), "SB/26-27/000001", at, "WALK_IN", null, null, null,
                                "Test Walk-in One", 1, 1_000, "CANCELLED", false, false, true, false, false, false, false)), 2));

        SaleViews.BillPage cashierPage = service.list(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null, null, null, null);
        SaleViews.BillPage ownerPage = service.list(SaleWorld.TENANT, w.f.owner.userId(), shop, null, null, null, null, "0", "10");

        assertThat(cashierPage.items().get(0).buyerLabel()).isEqualTo("R*** K*** · +91 98XXX XX214");
        assertThat(ownerPage.items().get(0).buyerLabel()).isEqualTo("Ravi Kumar · +91 98XXX XX214");
        assertThat(cashierPage.items().get(0).marks()).containsExactly("LINK_PENDING", "ALLERGY_NOT_CHECKED");
        assertThat(cashierPage.items().get(1).marks()).containsExactly("REFUND_DUE");
        assertThat(cashierPage.items().get(1).buyerLabel()).isEqualTo("Walk-in");
        assertThat(cashierPage.total()).isEqualTo(2);
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(w.bills, org.mockito.Mockito.atLeastOnce()).page(any(), any(), eq(""), from.capture(), any(), eq(""), eq(""),
                eq(25), eq(0));
        assertThat(from.getAllValues().get(0)).isEqualTo(Instant.parse("2026-11-16T18:30:00Z"));
        verify(w.patientAccess, never()).recordOpened(any(), any(), any());
    }

    @Test
    void theListRefusesABadStatusAndADateThatIsNotADate() {
        assertThatThrownBy(() -> service.list(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, "OPEN", null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.list(SaleWorld.TENANT, w.cashier.userId(), shop, null, "17/11/2026", null, null, null,
                null)).isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.list(SaleWorld.TENANT, w.f.nurse.userId(), shop, null, null, null, null, null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aBillReadShowsCanCancelForTheOwnerAnytimeAndForAPharmacistOnlyOnTheDayOfTheBill() {
        SaleBillRepository.BillRow today = stored("SB/26-27/000001", Instant.parse("2026-11-17T04:00:00Z"), w.cashier.id());
        SaleBillRepository.BillRow earlier = stored("SB/26-27/000002", Instant.parse("2026-11-10T04:00:00Z"), w.cashier.id());
        billRows.put(today.id(), today);
        billRows.put(earlier.id(), earlier);

        assertThat(service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, today.id().toString()).canCancel()).isTrue();
        SaleViews.Bill late = service.get(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, earlier.id().toString());
        assertThat(late.canCancel()).isFalse();
        assertThat(late.cancelLockReason()).isEqualTo("NOT_SAME_DAY");
        assertThat(service.get(SaleWorld.TENANT, w.f.owner.userId(), shop, earlier.id().toString()).canCancel()).isTrue();
        assertThat(service.get(SaleWorld.TENANT, w.cashier.userId(), shop, today.id().toString()).cancelLockReason())
                .isEqualTo("NOT_ALLOWED");
        when(w.bills.hasReturns(any(), eq(today.id()))).thenReturn(true);
        assertThat(service.get(SaleWorld.TENANT, w.f.owner.userId(), shop, today.id().toString()).cancelLockReason())
                .isEqualTo("HAS_RETURNS");
        assertThat(service.get(SaleWorld.TENANT, w.f.owner.userId(), shop, today.id().toString()).hasReturns()).isTrue();
    }

    @Test
    void aBillOfAnotherStoreOrAnUnknownBillIs404() {
        SaleBillRepository.BillRow elsewhere = new SaleBillRepository.BillRow(UUID.randomUUID(), w.unitId(), UUID.randomUUID(),
                "SB/26-27/000009", "UNIT", 2026, "SAVED", "NONE", null, null, null, null, false, false, null, null, null, null,
                null, null, BigDecimal.ZERO, null, 0, 0, 0, 0, 0, 0, 0, 0, Instant.now(), w.cashier.id(), null, null, null, null,
                null, null, 0, null, 0L);
        billRows.put(elsewhere.id(), elsewhere);

        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.cashier.userId(), shop, elsewhere.id().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(SaleWorld.TENANT, w.cashier.userId(), shop, UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBillOfARegisteredPatientWritesOneRecordAccessEntryWhenItIsOpened() {
        UUID patientId = UUID.randomUUID();
        when(w.patientLookup.findAllCurrent(any(), any())).thenReturn(Map.of(patientId,
                new PatientLookupPort.PatientSummary(patientId, "Test Patient One", 34, "F", null, false, "UHID-1")));
        when(w.patientLookup.mobilesOf(any(), any())).thenReturn(Map.of(patientId, "9876543214"));
        SaleBillRepository.BillRow row = new SaleBillRepository.BillRow(UUID.randomUUID(), w.unitId(), w.shop.id(),
                "SB/26-27/000003", "UNIT", 2026, "SAVED", "PATIENT", patientId, null, null, null, false, false, null, null, null,
                null, null, null, BigDecimal.ZERO, null, 0, 0, 0, 0, 0, 0, 0, 0, Instant.now(), w.cashier.id(), null, null, null,
                null, null, null, 0, null, 0L);
        billRows.put(row.id(), row);

        SaleViews.Bill b = service.get(SaleWorld.TENANT, w.cashier.userId(), shop, row.id().toString());

        assertThat(b.buyer().label()).isEqualTo("Test Patient One");
        assertThat(b.buyer().uhid()).isEqualTo("UHID-1");
        assertThat(b.buyer().maskedMobile()).isEqualTo("+91 98XXX XX214");
        verify(w.patientAccess, org.mockito.Mockito.times(1)).recordOpened(SaleWorld.TENANT, patientId, w.cashier.userId());
    }
}
