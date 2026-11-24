package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.InvalidFieldsException;
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

/** SAH-80-18: the sales return, its three outcomes, its checks, the credit note and the SALES_RETURN approval handler. */
class SalesReturnServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);
    private static final String SEALED = "return-condition.sealed";
    private static final String OPENED = "return-condition.opened";

    private final SaleWorld w = new SaleWorld();
    private final SalesReturnRepository returns = mock(SalesReturnRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final DocumentViews documents = new DocumentViews(w.events, w.f.storeRepository, w.f.team, engine);
    private final SalesReturnService service = new SalesReturnService(w.f.gate, w.f.guard, w.bills, returns, w.customers,
            w.products, w.stock, w.posting, w.registers, engine, w.rules, w.reasons, w.numbers, w.series, w.signature, w.clients,
            w.events, documents, w.f.storeRepository, w.support, w.blind, w.f.auditLog, w.f.time, w.mapper);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");
    private final UUID batch = w.shelf(product, "B-OK", FAR, 50, "100.00");
    private final UUID billId = UUID.randomUUID();
    private final UUID billLineId = UUID.randomUUID();
    private final Map<UUID, SalesReturnRepository.Row> stored = new HashMap<>();
    private final List<SalesReturnRepository.LineRow> storedLines = new ArrayList<>();
    private final List<SaleRegisterRepository.NewCreditNote> creditNotes = new ArrayList<>();
    private final List<SaleRegisterRepository.NewRefund> refunds = new ArrayList<>();
    private SaleBillRepository.BillRow bill;

    SalesReturnServiceTest() {
        bill = billRow("SAVED", Instant.parse("2026-11-15T05:00:00Z"));
        wire();
    }

    private SaleBillRepository.BillRow billRow(String status, Instant savedAt) {
        return new SaleBillRepository.BillRow(billId, w.unitId(), w.shop.id(), "SB/26-27/000001", "UNIT", 2026, status, "WALK_IN",
                null, null, "Test Walk-in One", null, false, false, null, null, null, null, null, null, null, null, 56040, 0, 47534,
                2852, 2852, 0, 56040, 0, savedAt, w.cashier.member().membershipId(), null, null, null, null, null, null, 0, null,
                0L);
    }

    private SaleBillRepository.LineRow lineRow(int sold, int returned) {
        return new SaleBillRepository.LineRow(billLineId, 1, product, batch, sold, returned, new BigDecimal("280.20"),
                new BigDecimal("12"), "30049099", 56040, 0, 56040, 50036, 6004, null, null, null, null, null, null, null, null,
                null, w.cashier.member().membershipId(), null, null);
    }

    private void wire() {
        when(w.bills.findForUpdate(any(), any())).thenAnswer(inv -> Optional.of(bill));
        when(w.bills.find(any(), any())).thenAnswer(inv -> Optional.of(bill));
        when(w.bills.lines(any(), any())).thenAnswer(inv -> List.of(lineRow(2, 0)));
        when(w.bills.returnWindowDays(any())).thenReturn(Optional.of(7));
        when(w.bills.refundModes(any())).thenReturn(List.of("ORIGINAL_MODE"));
        when(w.bills.payments(any(), any())).thenReturn(List.of(new SaleBillRepository.PaymentRow("CASH", 56040)));
        when(w.bills.refundedByMode(any(), any())).thenReturn(Map.of());
        when(returns.openQtyByBillLine(any(), any())).thenReturn(Map.of());
        doAnswer(inv -> {
            SalesReturnRepository.Row r = inv.getArgument(1);
            stored.put(r.id(), new SalesReturnRepository.Row(r.id(), r.unitId(), r.storeId(), r.billId(), r.status(), r.reasonKey(),
                    r.note(), r.startedBy(), Instant.parse("2026-11-17T05:35:00Z"), r.acceptedBy(), r.acceptedAt(), r.completedBy(),
                    r.completedAt(), r.approvalRequestId(), r.refundTotalPaise(), r.refundsJson(), r.creditNoteNo(), 0L));
            return null;
        }).when(returns).insert(any(), any(), any());
        doAnswer(inv -> {
            storedLines.add(inv.getArgument(3));
            return null;
        }).when(returns).insertLine(any(), any(), any(), any(), any());
        when(returns.find(any(), any())).thenAnswer(inv -> Optional.ofNullable(stored.get((UUID) inv.getArgument(1))));
        when(returns.findForUpdate(any(), any())).thenAnswer(inv -> Optional.ofNullable(stored.get((UUID) inv.getArgument(1))));
        when(returns.lines(any(), any())).thenAnswer(inv -> List.copyOf(storedLines));
        when(returns.markCompleted(any(), any(), anyLong(), any(), any(), anyLong(), any(), any(), any())).thenAnswer(inv -> {
            SalesReturnRepository.Row r = stored.get((UUID) inv.getArgument(1));
            stored.put(r.id(), new SalesReturnRepository.Row(r.id(), r.unitId(), r.storeId(), r.billId(), "COMPLETED",
                    r.reasonKey(), r.note(), r.startedBy(), r.startedAt(), r.acceptedBy(), r.acceptedAt(), inv.getArgument(3),
                    Instant.parse("2026-11-17T05:36:00Z"), r.approvalRequestId(), r.refundTotalPaise(), r.refundsJson(),
                    inv.getArgument(4), r.version() + 1));
            return true;
        });
        when(returns.markAccepted(any(), any(), anyLong(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(inv -> {
            SalesReturnRepository.Row r = stored.get((UUID) inv.getArgument(1));
            stored.put(r.id(), new SalesReturnRepository.Row(r.id(), r.unitId(), r.storeId(), r.billId(), inv.getArgument(3),
                    r.reasonKey(), r.note(), r.startedBy(), r.startedAt(), inv.getArgument(4), Instant.now(), null, null,
                    inv.getArgument(5), inv.getArgument(6), inv.getArgument(7), null, r.version() + 1));
            return true;
        });
        when(returns.markDecided(any(), any(), anyLong(), any(), any())).thenReturn(true);
        doAnswer(inv -> {
            creditNotes.add(inv.getArgument(1));
            return null;
        }).when(w.registers).insertCreditNote(any(), any(), any());
        doAnswer(inv -> {
            refunds.add(inv.getArgument(1));
            return null;
        }).when(w.registers).insertRefund(any(), any(), any());
        when(w.numbers.next(any(), any())).thenReturn(1L);
        when(engine.plan(any(), any())).thenReturn(null);
        when(w.reasons.labels(any())).thenReturn(Map.of(SEALED, "Sealed", OPENED, "Strip cut or opened", "sales-return.wrong-item-given",
                "Wrong item given"));
    }

    private SaleInputs.SalesReturnCreate create(int qty, String condition, long refundPaise) {
        return new SaleInputs.SalesReturnCreate(UUID.randomUUID(), billId, List.of(new SaleInputs.ReturnLineInput(billLineId,
                BigDecimal.valueOf(qty), condition)), "sales-return.wrong-item-given", null,
                List.of(new SaleInputs.ReturnRefundInput("CASH", BigDecimal.valueOf(refundPaise))), null);
    }

    private PharmacyViews.Outcome<SalesReturnViews.SalesReturn> start(PharmacyFixture.Person who, SaleInputs.SalesReturnCreate in) {
        return service.create(SaleWorld.TENANT, who.userId(), shop, SaleWorld.body(in), null);
    }

    // ---- pure rules ----

    @Test
    void theDestinationFollowsTheConditionTheDecisionAndTheExpiry() {
        assertThat(SalesReturnService.destination(SEALED, null, false)).isEqualTo("AVAILABLE");
        assertThat(SalesReturnService.destination(SEALED, null, true)).isEqualTo("QUARANTINE");
        assertThat(SalesReturnService.destination(OPENED, null, false)).isEqualTo("QUARANTINE");
        assertThat(SalesReturnService.destination(OPENED, "ACCEPT", false)).isEqualTo("AVAILABLE");
        assertThat(SalesReturnService.destination(OPENED, "ACCEPT_QUARANTINE", false)).isEqualTo("QUARANTINE");
        assertThat(SalesReturnService.destination(OPENED, "REFUSE", false)).isEqualTo("REFUSED");
    }

    @Test
    void refundsMustAddUpAndStayWithinWhatEachModePaidAndWhatTheUnitAllows() {
        List<com.sahaj.platform.api.InvalidFieldsException.FieldMessage> errors = new ArrayList<>();
        SalesReturnService.checkRefunds(Map.of("CASH", 100L), 150, List.of("ORIGINAL_MODE"), Map.of("CASH", 500L), null, errors);
        assertThat(errors).extracting(e -> e.message()).containsExactly("Refunds must add up to ₹ 1.50.");

        errors.clear();
        SalesReturnService.checkRefunds(Map.of("UPI", 100L), 100, List.of("ORIGINAL_MODE"), Map.of("CASH", 500L), null, errors);
        assertThat(errors).hasSize(1);

        errors.clear();
        SalesReturnService.checkRefunds(Map.of("CREDIT_NOTE", 100L), 100, List.of("CREDIT_NOTE"), Map.of(), null, errors);
        assertThat(errors).hasSize(1); // a credit note needs a named customer

        errors.clear();
        SalesReturnService.checkRefunds(Map.of("CASH", 100L), 100, List.of("ORIGINAL_MODE"), Map.of("CASH", 100L), null, errors);
        assertThat(errors).isEmpty();
    }

    // ---- create ----

    @Test
    void aCashiersReturnWaitsForThePharmacistWithNothingMovedAndNoNumber() {
        PharmacyViews.Outcome<SalesReturnViews.SalesReturn> out = start(w.cashier, create(1, SEALED, 28020));

        assertThat(out.created()).isTrue();
        assertThat(out.view().status()).isEqualTo("WAITING_PHARMACIST");
        assertThat(out.view().creditNoteNo()).isNull();
        assertThat(out.view().lines().get(0).destination()).isEqualTo("PENDING");
        assertThat(out.view().refundTotalPaise()).isEqualTo(28020);
        verify(w.posting, never()).post(any(), any());
        verify(w.numbers, never()).next(any(), any());
        verify(w.registers, never()).insertRefund(any(), any(), any());
        verify(w.bills, never()).addReturnedQty(any(), any(), anyInt());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_SALES_RETURN");
        assertThat(w.f.audit.toString()).doesNotContain("Test Walk-in One");
    }

    @Test
    void aPharmacistsSealedReturnWithinTheLimitCompletesAtOnceInTheRightOrder() {
        PharmacyViews.Outcome<SalesReturnViews.SalesReturn> out = start(w.f.pharmacist, create(1, SEALED, 28020));

        assertThat(out.view().status()).isEqualTo("COMPLETED");
        assertThat(out.view().creditNoteNo()).startsWith("CN/");
        ArgumentCaptor<List<StockPosting.Line>> posted = ArgumentCaptor.forClass(List.class);
        verify(w.posting).post(any(), posted.capture());
        StockPosting.Line line = posted.getValue().get(0);
        assertThat(line.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(line.delta()).isEqualTo(1);
        assertThat(line.txnType()).isEqualTo("SALES_RETURN");
        assertThat(line.doc().no()).startsWith("CN/");
        assertThat(creditNotes).hasSize(1);
        assertThat(creditNotes.get(0).amountPaise()).isEqualTo(28020);
        assertThat(creditNotes.get(0).sourceKind()).isEqualTo("SALES_RETURN");
        assertThat(refunds).extracting(SaleRegisterRepository.NewRefund::mode).containsExactly("CASH");
        InOrder order = inOrder(w.stock, w.numbers, w.posting, returns);
        order.verify(w.stock).lockBalances(any(), any(), any());
        order.verify(w.numbers).next(any(), any());
        order.verify(w.posting).post(any(), any());
        order.verify(returns).updateLine(any(), any(), any(), any(), anyLong(), anyLong(), anyLong(), any());
        order.verify(returns).markCompleted(any(), any(), anyLong(), any(), any(), anyLong(), any(), any(), any());
        verify(w.bills).addReturnedQty(any(), eq(billLineId), eq(1));
    }

    @Test
    void anOpenedLineGoesToQuarantineWhenAPharmacistReturnsIt() {
        start(w.f.pharmacist, create(1, OPENED, 28020));

        ArgumentCaptor<List<StockPosting.Line>> posted = ArgumentCaptor.forClass(List.class);
        verify(w.posting).post(any(), posted.capture());
        assertThat(posted.getValue().get(0).state()).isEqualTo(StockState.QUARANTINE);
    }

    @Test
    void theRefundOfTheTwoUnitsOfALineAddsUpToItsNetAmountWhateverTheSplit() {
        long first = com.sahaj.pharmacy.domain.SaleRules.returnShare(56040, 50036, 3, 0, 1).refundPaise();
        long second = com.sahaj.pharmacy.domain.SaleRules.returnShare(56040, 50036, 3, 1, 1).refundPaise();
        long third = com.sahaj.pharmacy.domain.SaleRules.returnShare(56040, 50036, 3, 2, 1).refundPaise();

        assertThat(first + second + third).isEqualTo(56040);
        com.sahaj.pharmacy.domain.SaleRules.ReturnShare all = com.sahaj.pharmacy.domain.SaleRules.returnShare(56040, 50036, 3, 0, 3);
        assertThat(all.refundPaise()).isEqualTo(56040);
        assertThat(all.taxablePaise() + all.taxPaise()).isEqualTo(all.refundPaise());
    }

    @Test
    void aReturnAboveTheLimitGoesToTheEngineAndNothingMovesUntilItIsApprovedAndFinished() {
        UUID approvalId = UUID.randomUUID();
        when(engine.plan(any(), any())).thenReturn(new ApprovalEngine.Plan(new ApprovalRuleRepository.Version(null, 0,
                RulePreset.STANDARD, null, null, null), List.of(), 1, null, false));
        when(engine.submit(any(), any(), any(), any(), any())).thenReturn(approvalId);

        PharmacyViews.Outcome<SalesReturnViews.SalesReturn> out = start(w.f.pharmacist, create(1, SEALED, 28020));

        assertThat(out.view().status()).isEqualTo("WAITING_APPROVAL");
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).submit(any(), any(), eq(w.f.pharmacist.member().membershipId()), subject.capture(), any());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.SALES_RETURN);
        assertThat(subject.getValue().amountPaise()).isEqualTo(28020);
        verify(w.posting, never()).post(any(), any());
        verify(w.numbers, never()).next(any(), any());
    }

    @Test
    void theChecksAreAnsweredTogetherAndNothingIsWritten() {
        SaleInputs.SalesReturnCreate bad = new SaleInputs.SalesReturnCreate(UUID.randomUUID(), billId, List.of(
                new SaleInputs.ReturnLineInput(billLineId, BigDecimal.valueOf(3), SEALED)), "sales-return.wrong-item-given", null,
                List.of(new SaleInputs.ReturnRefundInput("CASH", BigDecimal.valueOf(1))), null);

        assertThatThrownBy(() -> start(w.f.pharmacist, bad)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(m -> m.message()).contains("You can return at most 2."));
        verify(returns, never()).insert(any(), any(), any());
        verify(w.posting, never()).post(any(), any());
    }

    @Test
    void aWrongRefundTotalIsPaymentMismatchOnTheRefundsField() {
        assertThatThrownBy(() -> start(w.f.pharmacist, create(1, SEALED, 100))).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors()).extracting(m -> m.field()).contains("refunds"));
        verify(returns, never()).insert(any(), any(), any());
    }

    @Test
    void theWindowMustBeSetAndOpenAndTheBillNotCancelled() {
        when(w.bills.returnWindowDays(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> start(w.f.pharmacist, create(1, SEALED, 28020))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("return-window-not-set"));

        when(w.bills.returnWindowDays(any())).thenReturn(Optional.of(1));
        bill = billRow("SAVED", Instant.parse("2026-11-10T05:00:00Z"));
        assertThatThrownBy(() -> start(w.f.pharmacist, create(1, SEALED, 28020))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("return-window-over"));

        when(w.bills.returnWindowDays(any())).thenReturn(Optional.of(30));
        bill = billRow("CANCELLED", Instant.parse("2026-11-15T05:00:00Z"));
        assertThatThrownBy(() -> start(w.f.pharmacist, create(1, SEALED, 28020))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("return-state"));
    }

    @Test
    void aReturnOfAScheduleXLineByAnOwnerWhoIsNotAPharmacistIsRefused() {
        UUID x = w.product("Testmorph", "Morphine", "X");
        when(w.bills.lines(any(), any())).thenAnswer(inv -> List.of(new SaleBillRepository.LineRow(billLineId, 1, x, batch, 2, 0,
                new BigDecimal("10"), new BigDecimal("12"), "30049099", 2000, 0, 2000, 1786, 214, null, null, null, null, null,
                null, null, null, null, null, null, null)));

        assertThatThrownBy(() -> start(w.f.owner, create(1, SEALED, 1000))).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("pharmacist-required"));
    }

    // ---- accept and finish ----

    private UUID waitingReturn() {
        start(w.cashier, create(2, OPENED, 56040));
        return stored.keySet().iterator().next();
    }

    @Test
    void theAcceptorMayRefuseADoubtfulLineAndTheReturnIsRejectedWhenNothingIsLeft() {
        UUID id = waitingReturn();
        UUID lineId = storedLines.get(0).id();
        SaleInputs.AcceptSalesReturnInput in = new SaleInputs.AcceptSalesReturnInput(UUID.randomUUID(), BigDecimal.ZERO,
                List.of(new SaleInputs.ReturnDecisionInput(lineId, "REFUSE")), List.of(), null);

        SalesReturnViews.SalesReturn out = service.accept(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, id.toString(),
                SaleWorld.body(in), null);

        verify(returns).markDecided(any(), eq(id), anyLong(), eq("REJECTED"), any());
        verify(returns).updateLine(any(), eq(lineId), eq("REFUSE"), eq("REFUSED"), eq(0L), eq(0L), eq(0L), any());
        verify(w.posting, never()).post(any(), any());
        assertThat(out).isNotNull();
    }

    @Test
    void aCashierCannotAcceptAndAStatusOtherThanWaitingIsReturnState() {
        UUID id = waitingReturn();
        SaleInputs.AcceptSalesReturnInput in = new SaleInputs.AcceptSalesReturnInput(UUID.randomUUID(), BigDecimal.ZERO, List.of(),
                List.of(new SaleInputs.ReturnRefundInput("CASH", BigDecimal.valueOf(56040))), null);

        assertThatThrownBy(() -> service.accept(SaleWorld.TENANT, w.cashier.userId(), shop, id.toString(), SaleWorld.body(in),
                null)).isInstanceOf(StoreAccessDeniedException.class);

        SalesReturnRepository.Row r = stored.get(id);
        stored.put(id, new SalesReturnRepository.Row(r.id(), r.unitId(), r.storeId(), r.billId(), "COMPLETED", r.reasonKey(),
                r.note(), r.startedBy(), r.startedAt(), null, null, null, null, null, r.refundTotalPaise(), r.refundsJson(), null,
                r.version()));
        assertThatThrownBy(() -> service.accept(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, id.toString(), SaleWorld.body(in),
                null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("return-state"));
    }

    @Test
    void aFinishNeedsAnApprovedReturn() {
        UUID id = waitingReturn();
        PharmacyInputs.PostWithSigner in = new PharmacyInputs.PostWithSigner(UUID.randomUUID(), BigDecimal.ZERO, null);

        assertThatThrownBy(() -> service.finish(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, id.toString(), SaleWorld.body(in),
                null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("return-state"));
    }

    // ---- the approval handler ----

    @Test
    void anApprovalMakesTheReturnApprovedAndARejectionMakesItRejectedNothingMoves() {
        UUID id = UUID.randomUUID();
        SalesReturnRepository.Row waiting = new SalesReturnRepository.Row(id, w.unitId(), w.shop.id(), billId, "WAITING_APPROVAL",
                "sales-return.wrong-item-given", null, w.cashier.member().membershipId(), Instant.now(), null, null, null, null,
                null, 28020, "[]", null, 4L);
        stored.put(id, waiting);
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), w.unitId(), TransactionType.SALES_RETURN,
                "SALES_RETURN", id, "Return", w.shop.id(), waiting.startedBy(), Instant.now(), null, 28020, false, false, false, null,
                null, 1, Instant.now(), "PENDING", 0L);
        PharmacyCaller owner = w.caller(w.f.owner);
        ApprovalSubjectHandler.Decision decision = new ApprovalSubjectHandler.Decision(SaleWorld.TENANT, owner, request, "OWNER",
                new PharmacyInputs.ApprovalDecisionInput(null, null, null, "APPROVE", null, null, null, null));

        service.onApproved(decision);
        service.onRejected(decision);

        verify(returns).markDecided(any(), eq(id), eq(4L), eq("APPROVED"), any());
        verify(returns).markDecided(any(), eq(id), eq(4L), eq("REJECTED"), any());
        verify(w.posting, never()).post(any(), any());
        assertThat(service.subjectType()).isEqualTo("SALES_RETURN");
        assertThat(service.summary(SaleWorld.TENANT, request).chip()).isEqualTo("SALES_RETURN");
        assertThat(service.canRead(w.caller(w.cashier), request)).isTrue();
    }

    // ---- pass 1 minors 5, 7, 8 and the credit-note reconciliation ----

    private static SaleBillRepository.LineRow billLine(UUID id, int qty, int returned, long net, long taxable) {
        return new SaleBillRepository.LineRow(id, 1, UUID.randomUUID(), UUID.randomUUID(), qty, returned, new BigDecimal("100.00"),
                new BigDecimal("12"), "30049099", net, 0, net, taxable, net - taxable, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static SalesReturnRepository.LineRow returnLine(UUID billLineId, int qty, String destination, long refund) {
        return new SalesReturnRepository.LineRow(UUID.randomUUID(), billLineId, UUID.randomUUID(), UUID.randomUUID(), qty,
                "return-condition.sealed", null, destination, refund, 0, 0, null);
    }

    @Test
    void theSharesAreWorkedAgainFromTheCompletedQuantityNotFromTheOpenReturnsOfTheSameBillLine() {
        UUID bl = UUID.randomUUID();
        // 3 units sold for 100 paise (33.33 each). An earlier return of the 1st unit is still open, so the plan took 67 - 33 = 34
        // for the 2nd; that earlier return was REJECTED, nothing was completed, and the right share of the first unit is 33.
        SalesReturnRepository.LineRow planned = returnLine(bl, 1, "AVAILABLE", 34);

        List<SalesReturnRepository.LineRow> out = SalesReturnService.recomputed(List.of(planned), Map.of(bl,
                billLine(bl, 3, 0, 100, 90)));

        assertThat(out.get(0).refundPaise()).isEqualTo(33L);
        assertThat(out.get(0).id()).isEqualTo(planned.id());
        assertThat(out.get(0).destination()).isEqualTo("AVAILABLE");
    }

    @Test
    void aRefusedLineKeepsItsZeroRefundWhenTheSharesAreWorkedAgain() {
        UUID bl = UUID.randomUUID();
        SalesReturnRepository.LineRow refused = returnLine(bl, 1, "REFUSED", 0);

        assertThat(SalesReturnService.recomputed(List.of(refused), Map.of(bl, billLine(bl, 3, 0, 100, 90))).get(0).refundPaise())
                .isZero();
    }

    @Test
    void whenTheTotalMovedAParisaGoesToTheLargestNonUdharModeAndTheModesStillAddUp() {
        Map<String, Long> modes = new java.util.LinkedHashMap<>();
        modes.put("CASH", 20L);
        modes.put("UDHAR", 14L);

        Map<String, Long> out = SalesReturnService.reconciled(modes, 33L, null);

        assertThat(out).containsEntry("CASH", 19L).containsEntry("UDHAR", 14L);
        assertThat(out.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(33L);
        assertThat(SalesReturnService.reconciled(modes, 34L, null)).isSameAs(modes);
    }

    @Test
    void aReconciliationThatCannotBePlacedAnswersChangedAndAZeroModeIsDropped() {
        Map<String, Long> onlyUdhar = new java.util.LinkedHashMap<>();
        onlyUdhar.put("UDHAR", 10L);
        assertThatThrownBy(() -> SalesReturnService.reconciled(onlyUdhar, 11L, null)).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));

        Map<String, Long> two = new java.util.LinkedHashMap<>();
        two.put("CASH", 1L);
        two.put("UPI", 1L);
        assertThat(SalesReturnService.reconciled(two, 1L, null)).containsOnlyKeys("UPI").containsEntry("UPI", 1L);
    }

    @Test
    void aSealedLineAcceptedWithNoDecisionStoresADecisionOfNullNotAcceptQuarantine() {
        start(w.cashier, create(2, SEALED, 56040));
        UUID id = stored.keySet().iterator().next();
        UUID lineId = storedLines.get(0).id();
        doAnswer(inv -> {
            SalesReturnRepository.LineRow old = storedLines.get(0);
            storedLines.set(0, new SalesReturnRepository.LineRow(old.id(), old.billLineId(), old.productId(), old.batchId(), old.qty(),
                    old.conditionKey(), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5),
                    inv.getArgument(6), old.secondSigner()));
            return null;
        }).when(returns).updateLine(any(), any(), any(), any(), anyLong(), anyLong(), anyLong(), any());
        SaleInputs.AcceptSalesReturnInput in = new SaleInputs.AcceptSalesReturnInput(UUID.randomUUID(), BigDecimal.ZERO, List.of(),
                List.of(new SaleInputs.ReturnRefundInput("CASH", BigDecimal.valueOf(56040))), null);

        service.accept(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, id.toString(), SaleWorld.body(in), null);

        verify(returns, org.mockito.Mockito.atLeastOnce()).updateLine(any(), eq(lineId), org.mockito.ArgumentMatchers.isNull(),
                eq("AVAILABLE"), anyLong(), anyLong(), anyLong(), any());
        assertThat(SalesReturnService.destination(SalesReturnService.SEALED, null, false)).isEqualTo("AVAILABLE");
    }
}
