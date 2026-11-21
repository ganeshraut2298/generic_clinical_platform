package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** SAH-80-5: cancelling a saved bill and settling its refund (docs/api/pharmacy-sales.yaml). */
class BillCancelServiceTest {

    private static final LocalDate FAR = LocalDate.of(2027, 6, 30);
    private static final Instant TODAY_AM = Instant.parse("2026-11-17T04:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-11-10T04:00:00Z");

    private final SaleWorld w = new SaleWorld();
    private final BillCancelService service = new BillCancelService(w.f.gate, w.f.guard, w.products, w.stock, w.posting, w.bills,
            w.customers, w.registers, w.assembler, w.reasons, w.numbers, w.series, w.signature, w.clients, w.events, w.blind,
            w.f.auditLog, w.f.time);
    private final String shop = w.shop.id().toString();
    private final UUID product = w.product("Testamol", "Paracetamol", "NONE");
    private final UUID batchId = w.shelf(product, "B-OK", FAR, 5, "100.00");
    private final UUID billId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private final UUID ledgerId = UUID.randomUUID();

    private SaleBillRepository.BillRow bill(Instant at, String status, UUID customerId, long udhar, long refundDue, String cn) {
        return new SaleBillRepository.BillRow(billId, w.unitId(), w.shop.id(), "SB/26-27/000001", "UNIT", 2026, status,
                customerId == null ? "WALK_IN" : "CUSTOMER", null, customerId, customerId == null ? "Test Walk-in One" : null, null,
                false, false, null, null, null, null, null, null, BigDecimal.ZERO, null, 20_000, 0, 17_858, 1_071, 1_071, 0, 20_000,
                udhar, at, w.cashier.id(), null, "CANCELLED".equals(status) ? at : null, "CANCELLED".equals(status) ? w.f.owner.id()
                        : null, "CANCELLED".equals(status) ? "cancel-bill.wrong-item" : null, null,
                "CANCELLED".equals(status) ? Boolean.TRUE : null, refundDue, cn, 7L);
    }

    private void stored(SaleBillRepository.BillRow bill, int qty, String... modes) {
        when(w.bills.find(any(), eq(billId))).thenReturn(Optional.of(bill));
        when(w.bills.findForUpdate(any(), eq(billId))).thenReturn(Optional.of(bill));
        when(w.bills.lines(any(), eq(billId))).thenReturn(List.of(new SaleBillRepository.LineRow(lineId, 1, product, batchId, qty,
                0, new BigDecimal("100.00"), new BigDecimal("12"), "30049099", 20_000, 0, 20_000, 17_858, 2_142, null, null, null,
                null, null, null, null, null, null, w.cashier.id(), null, null)));
        java.util.ArrayList<SaleBillRepository.PaymentRow> pays = new java.util.ArrayList<>();
        for (String m : modes) {
            String[] parts = m.split(":");
            pays.add(new SaleBillRepository.PaymentRow(parts[0], Long.parseLong(parts[1])));
        }
        when(w.bills.payments(any(), eq(billId))).thenReturn(pays);
        when(w.bills.saleLedger(any(), eq(billId))).thenReturn(List.of(new SaleBillRepository.SaleLedger(ledgerId, lineId,
                batchId, product, qty)));
    }

    private SaleInputs.CancelBillInput cancelInput(Boolean shelf, String reason, String note) {
        return new SaleInputs.CancelBillInput(UUID.randomUUID(), BigDecimal.valueOf(7), reason, note, shelf, null);
    }

    private PharmacyViews.Outcome<SaleViews.Bill> cancel(PharmacyFixture.Person who, SaleInputs.CancelBillInput in, UUID signer) {
        return service.cancel(SaleWorld.TENANT, who.userId(), shop, billId.toString(), SaleWorld.body(in), signer);
    }

    @SuppressWarnings("unchecked")
    private List<StockPosting.Line> posted() {
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(w.posting).post(any(), lines.capture());
        return lines.getValue();
    }

    @Test
    void theOwnerCancelsAndTheUnitsGoBackToAvailableWithALinePointingAtTheOriginal() {
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");

        PharmacyViews.Outcome<SaleViews.Bill> out = cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null);

        assertThat(out.created()).isTrue();
        StockPosting.Line line = posted().get(0);
        assertThat(line.txnType()).isEqualTo("COUNTER_SALE_CANCELLED");
        assertThat(line.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(line.delta()).isEqualTo(2);
        assertThat(line.reversesLedgerId()).isEqualTo(ledgerId);
        assertThat(line.doc().no()).isEqualTo("SB/26-27/000001");
        assertThat(line.doc().lineId()).isEqualTo(lineId);
        verify(w.bills).markCancelled(any(), eq(billId), eq(7L), eq(w.f.owner.id()), eq("cancel-bill.wrong-item"), any(), eq(true),
                eq(20_000L));
        verify(w.registers).reverseH1ForCancelledBill(any(), eq(billId));
        verify(w.registers).reverseScheduleXForCancelledBill(any(), eq(billId));
        PharmacyFixture.Audit audit = w.f.audit.get(0);
        assertThat(audit.action()).isEqualTo("PHARMACY_BILL_CANCELLED");
        assertThat(audit.after()).containsEntry("reasonKey", "cancel-bill.wrong-item").containsEntry("refundDuePaise", 20_000L);
        assertThat(audit.toString()).doesNotContain("Test Walk-in One");
        verify(w.numbers, never()).next(any(), any());
    }

    @Test
    void notBackOnTheShelfAndAnExpiredBatchBothGoToQuarantine() {
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");
        cancel(w.f.pharmacist, cancelInput(false, "cancel-bill.wrong-item", null), null);
        assertThat(posted().get(0).state()).isEqualTo(StockState.QUARANTINE);

        org.mockito.Mockito.reset(w.posting);
        UUID expired = w.shelf(product, "B-OLD", LocalDate.of(2026, 11, 1), 3, "100.00");
        when(w.stock.findBatch(any(), eq(batchId))).thenReturn(Optional.of(new StockRepository.BatchRow(batchId, product, "B-OLD",
                LocalDate.of(2026, 11, 1), new BigDecimal("100"), BigDecimal.ONE, null, Instant.now())));
        when(w.bills.markCancelled(any(), any(), anyLong(), any(), any(), any(), anyBoolean(), anyLong())).thenReturn(true);
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");
        service.cancel(SaleWorld.TENANT, w.f.owner.userId(), shop, billId.toString(),
                SaleWorld.body(new SaleInputs.CancelBillInput(UUID.randomUUID(), BigDecimal.valueOf(7), "cancel-bill.wrong-item",
                        null, true, null)), null);
        assertThat(posted().get(0).state()).isEqualTo(StockState.QUARANTINE);
        assertThat(expired).isNotNull();
    }

    @Test
    void theUdharPartComesOffTheDueNeverBelowZeroAndACreditNoteGoesBackToTheBalance() {
        UUID customerId = UUID.randomUUID();
        when(w.customers.findForUpdate(any(), eq(customerId))).thenReturn(Optional.of(w.customer(customerId, "Test Customer One",
                500_000, 5_000, 1_000, "ACTIVE")));
        stored(bill(TODAY_AM, "SAVED", customerId, 12_000, 0, null), 2, "UDHAR:12000", "CREDIT_NOTE:3000", "CASH:5000");

        cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null);

        verify(w.customers).setBalances(any(), eq(customerId), eq(0L), eq(4_000L), any());
        ArgumentCaptor<CustomerRepository.NewEntry> entries = ArgumentCaptor.forClass(CustomerRepository.NewEntry.class);
        verify(w.customers, org.mockito.Mockito.times(2)).insertEntry(any(), entries.capture());
        assertThat(entries.getAllValues()).extracting(CustomerRepository.NewEntry::kind).containsExactly("CANCELLED_BILL",
                "CREDIT_NOTE_ISSUED");
        assertThat(entries.getAllValues().get(0).amountPaise()).isEqualTo(5_000L);
        assertThat(entries.getAllValues().get(0).dueAfterPaise()).isZero();
        assertThat(entries.getAllValues().get(1).amountPaise()).isEqualTo(3_000L);
        verify(w.bills).markCancelled(any(), eq(billId), anyLong(), any(), any(), any(), anyBoolean(), eq(5_000L));
    }

    @Test
    void theBillAndCustomerRowsAreLockedBeforeTheStockRows() {
        UUID customerId = UUID.randomUUID();
        when(w.customers.findForUpdate(any(), eq(customerId))).thenReturn(Optional.of(w.customer(customerId, "Test Customer One",
                500_000, 0, 0, "ACTIVE")));
        stored(bill(TODAY_AM, "SAVED", customerId, 0, 0, null), 2, "CASH:20000");

        cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null);

        InOrder order = inOrder(w.bills, w.customers, w.posting);
        order.verify(w.bills).findForUpdate(any(), eq(billId));
        order.verify(w.customers).findForUpdate(any(), eq(customerId));
        order.verify(w.posting).post(any(), any());
    }

    @Test
    void aCashierIsRefusedAndAPharmacistOnALaterDayIsCancelNotAllowed() {
        stored(bill(EARLIER, "SAVED", null, 0, 0, null), 2, "CASH:20000");

        assertThatThrownBy(() -> cancel(w.cashier, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> cancel(w.f.pharmacist, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOfSatisfying(ForbiddenException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Only the owner can cancel a bill from an earlier day.");
                });
        verify(w.posting, never()).post(any(), any());
        assertThat(cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null).created()).isTrue();
    }

    @Test
    void anAlreadyCancelledBillABillWithReturnsAndAnOldVersionAreConflicts() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:20000");
        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.slug()).isEqualTo("already-cancelled");
                });

        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");
        when(w.bills.hasReturns(any(), eq(billId))).thenReturn(true);
        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("has-returns"));

        when(w.bills.hasReturns(any(), eq(billId))).thenReturn(false);
        assertThatThrownBy(() -> service.cancel(SaleWorld.TENANT, w.f.owner.userId(), shop, billId.toString(), SaleWorld.body(
                new SaleInputs.CancelBillInput(UUID.randomUUID(), BigDecimal.valueOf(3), "cancel-bill.wrong-item", null, true,
                        null)), null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                            assertThat(e.slug()).isEqualTo("changed");
                            assertThat(e.extensions()).containsKey("current");
                        });
        verify(w.posting, never()).post(any(), any());
    }

    @Test
    void theReasonTheShelfAnswerAndTheOtherNoteAreChecked() {
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");
        when(w.reasons.isActive(any(), any(com.sahaj.pharmacy.domain.ReasonList.class), eq("cancel-bill.gone"))).thenReturn(false);

        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(null, "cancel-bill.gone", null), null)).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).extracting(m -> m.field()).containsExactly("reasonKey",
                        "backOnShelf"));
        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(true, "cancel-bill.other", "ab"), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors().get(0).message()).isEqualTo("Enter a note of at least 4 characters"));
        verify(w.bills, never()).findForUpdate(any(), any());
    }

    @Test
    void aScheduleXBillIsCancelledOnlyByAPharmacistWithTheVerifiedSecondSigner() {
        UUID x = w.product("Testdone", "Methadone", "X");
        UUID xBatch = w.shelf(x, "B-X", FAR, 5, "10.00");
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 1, "CASH:20000");
        when(w.bills.lines(any(), eq(billId))).thenReturn(List.of(new SaleBillRepository.LineRow(lineId, 1, x, xBatch, 1, 0,
                new BigDecimal("10.00"), new BigDecimal("12"), null, 1000, 0, 1000, 893, 107, null, null, null, null, null, null,
                null, null, null, w.f.pharmacist.id(), w.pharmacist2.id(), null)));
        when(w.stock.findBatch(any(), eq(xBatch))).thenReturn(Optional.of(new StockRepository.BatchRow(xBatch, x, "B-X", FAR,
                BigDecimal.TEN, BigDecimal.ONE, null, Instant.now())));
        PharmacyInputs.SecondSignerInput signer = new PharmacyInputs.SecondSignerInput(w.pharmacist2.id(), "1234");
        SaleInputs.CancelBillInput withSigner = new SaleInputs.CancelBillInput(UUID.randomUUID(), BigDecimal.valueOf(7),
                "cancel-bill.wrong-item", null, true, signer);

        assertThat(service.precheck(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, billId.toString(),
                SaleWorld.body(withSigner))).isSameAs(signer);
        assertThat(service.precheck(SaleWorld.TENANT, w.f.owner.userId(), shop, billId.toString(), SaleWorld.body(withSigner)))
                .isNull();
        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("pharmacist-required"));
        assertThatThrownBy(() -> cancel(w.f.pharmacist, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().get(0).field())
                        .isEqualTo("secondSigner"));

        cancel(w.f.pharmacist, cancelInput(true, "cancel-bill.wrong-item", null), w.pharmacist2.id());
        verify(w.signature).recheck(SaleWorld.TENANT, w.f.pharmacist.id(), w.pharmacist2.id());
    }

    @Test
    void aReplayOfTheKeyAnswersTheBillAsItIsNowAndAKeyOnAnotherBillIsReused() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:20000");
        SaleInputs.CancelBillInput in = cancelInput(true, "cancel-bill.wrong-item", null);
        when(w.clients.own(any(), any(), eq(in.clientKey()))).thenReturn(Optional.of(new ClientRequestRepository.Taken(
                w.f.owner.id(), BillCancelService.CANCEL, billId)));

        PharmacyViews.Outcome<SaleViews.Bill> again = cancel(w.f.owner, in, null);

        assertThat(again.created()).isFalse();
        assertThat(again.view().status()).isEqualTo("CANCELLED");
        assertThat(again.view().cancel().reason().label()).isEqualTo("Wrong item");
        verify(w.posting, never()).post(any(), any());

        SaleInputs.CancelBillInput other = cancelInput(true, "cancel-bill.wrong-item", null);
        when(w.clients.own(any(), any(), eq(other.clientKey()))).thenReturn(Optional.of(new ClientRequestRepository.Taken(
                w.f.owner.id(), BillCancelService.CANCEL, UUID.randomUUID())));
        assertThatThrownBy(() -> cancel(w.f.owner, other, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    @Test
    void aBillOfAnotherStoreIs404() {
        when(w.bills.findForUpdate(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cancel(w.f.owner, cancelInput(true, "cancel-bill.wrong-item", null), null))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- refund ----

    private SaleInputs.RefundInput refund(long version, SaleInputs.RefundLine... lines) {
        return new SaleInputs.RefundInput(UUID.randomUUID(), BigDecimal.valueOf(version), List.of(lines));
    }

    private SaleInputs.RefundLine line(String mode, long paise) {
        return new SaleInputs.RefundLine(mode, BigDecimal.valueOf(paise));
    }

    private PharmacyViews.Outcome<SaleViews.Bill> refundAs(PharmacyFixture.Person who, SaleInputs.RefundInput in) {
        return service.refund(SaleWorld.TENANT, who.userId(), shop, billId.toString(), SaleWorld.body(in));
    }

    @Test
    void aRefundTakesACreditNoteNumberLastWritesTheRowsAndSetsTheNumberOnTheBill() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:12000", "UPI:8000");
        when(w.bills.refundModes(any())).thenReturn(List.of("ORIGINAL_MODE"));
        when(w.numbers.next(any(), eq(com.sahaj.pharmacy.domain.DocType.CREDIT_NOTE))).thenReturn(7L);

        PharmacyViews.Outcome<SaleViews.Bill> out = refundAs(w.cashier, refund(7, line("CASH", 12_000), line("UPI", 8_000)));

        assertThat(out.created()).isTrue();
        ArgumentCaptor<SaleRegisterRepository.NewCreditNote> note = ArgumentCaptor
                .forClass(SaleRegisterRepository.NewCreditNote.class);
        verify(w.registers).insertCreditNote(any(), note.capture(), eq(w.cashier.id()));
        assertThat(note.getValue().creditNoteNo()).isEqualTo("CN/26-27/000007");
        assertThat(note.getValue().amountPaise()).isEqualTo(20_000L);
        assertThat(note.getValue().sourceKind()).isEqualTo("CANCELLED_BILL");
        ArgumentCaptor<SaleRegisterRepository.NewRefund> refunds = ArgumentCaptor.forClass(SaleRegisterRepository.NewRefund.class);
        verify(w.registers, org.mockito.Mockito.times(2)).insertRefund(any(), refunds.capture(), any());
        assertThat(refunds.getAllValues()).extracting(SaleRegisterRepository.NewRefund::mode).containsExactly("CASH", "UPI");
        verify(w.bills).setRefundCreditNote(any(), eq(billId), eq(7L), eq("CN/26-27/000007"), eq(w.cashier.id()));
        InOrder order = inOrder(w.numbers, w.registers, w.bills);
        order.verify(w.numbers).next(any(), any());
        order.verify(w.registers).insertCreditNote(any(), any(), any());
        order.verify(w.bills).setRefundCreditNote(any(), any(), anyLong(), any(), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_BILL_REFUND_RECORDED");
        assertThat(w.f.audit.get(0).after()).containsEntry("creditNoteNo", "CN/26-27/000007");
    }

    @Test
    void refundsThatDoNotAddUpToTheRefundDueAreAFieldErrorWithTheAmount() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:20000");
        when(w.bills.refundModes(any())).thenReturn(List.of("ORIGINAL_MODE"));

        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 19_000)))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("Refunds must add up to ₹ 200."));
        verify(w.numbers, never()).next(any(), any());
        verify(w.registers, never()).insertCreditNote(any(), any(), any());
    }

    @Test
    void aModeTheOwnerHasNotAllowedAndOriginalModeBeyondWhatWasPaidAreRefused() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:12000", "UPI:8000");

        // no refund mode ticked yet
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 20_000)))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("This refund mode is not allowed. Ask your owner."));
        // original mode: no more than that mode paid
        when(w.bills.refundModes(any())).thenReturn(List.of("ORIGINAL_MODE"));
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 20_000)))).isInstanceOf(InvalidFieldsException.class);
        // CASH ticked on its own may take the whole amount
        when(w.bills.refundModes(any())).thenReturn(List.of("CASH"));
        assertThat(refundAs(w.cashier, refund(7, line("CASH", 20_000))).created()).isTrue();
    }

    @Test
    void aCreditNoteRefundNeedsACustomerAndMovesTheBalanceAndAnUdharRefundLowersTheDue() {
        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:20000");
        when(w.bills.refundModes(any())).thenReturn(List.of("CREDIT_NOTE", "UDHAR"));
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CREDIT_NOTE", 20_000)))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors().get(0).message())
                        .isEqualTo("Choose a customer to refund to a credit note or udhar."));

        UUID customerId = UUID.randomUUID();
        when(w.customers.findForUpdate(any(), eq(customerId))).thenReturn(Optional.of(w.customer(customerId, "Test Customer One",
                500_000, 6_000, 1_000, "ACTIVE")));
        stored(bill(TODAY_AM, "CANCELLED", customerId, 0, 20_000, null), 2, "CASH:20000");

        refundAs(w.cashier, refund(7, line("CREDIT_NOTE", 14_000), line("UDHAR", 6_000)));

        verify(w.customers).setBalances(any(), eq(customerId), eq(0L), eq(15_000L), any());
        ArgumentCaptor<CustomerRepository.NewEntry> entries = ArgumentCaptor.forClass(CustomerRepository.NewEntry.class);
        verify(w.customers, org.mockito.Mockito.times(2)).insertEntry(any(), entries.capture());
        assertThat(entries.getAllValues()).extracting(CustomerRepository.NewEntry::kind).containsExactly("RETURN_TO_UDHAR",
                "CREDIT_NOTE_ISSUED");
    }

    @Test
    void aBillThatIsNotCancelledOrAlreadySettledOrAnOldVersionIsAConflict() {
        stored(bill(TODAY_AM, "SAVED", null, 0, 0, null), 2, "CASH:20000");
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 20_000)))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("not-cancelled"));

        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, "CN/26-27/000001"), 2, "CASH:20000");
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 20_000)))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("refund-already-recorded"));

        stored(bill(TODAY_AM, "CANCELLED", null, 0, 20_000, null), 2, "CASH:20000");
        assertThatThrownBy(() -> refundAs(w.cashier, refund(2, line("CASH", 20_000)))).isInstanceOfSatisfying(
                PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));
        verify(w.registers, never()).insertCreditNote(any(), any(), any());
    }

    @Test
    void theRefundFieldsAreCheckedBeforeAnyLock() {
        assertThatThrownBy(() -> refundAs(w.cashier, new SaleInputs.RefundInput(null, null, List.of()))).isInstanceOfSatisfying(
                InvalidFieldsException.class, e -> assertThat(e.errors()).extracting(m -> m.field()).containsExactly("clientKey",
                        "version", "refunds"));
        assertThatThrownBy(() -> refundAs(w.cashier, refund(7, line("CASH", 100), line("CASH", 100), line("BARTER", 1),
                line("UPI", 0)))).isInstanceOf(InvalidFieldsException.class);
        verify(w.bills, never()).findForUpdate(any(), any());
        assertThat(Map.of()).isEmpty();
    }
}
