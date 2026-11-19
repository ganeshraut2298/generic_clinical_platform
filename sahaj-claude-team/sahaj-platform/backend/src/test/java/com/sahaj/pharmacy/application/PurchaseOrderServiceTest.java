package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.PurchaseOrderRepository.Line;
import com.sahaj.pharmacy.application.PurchaseOrderRepository.NewLine;
import com.sahaj.pharmacy.application.PurchaseOrderRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.pharmacy.domain.Vendor;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-6 (docs/api/pharmacy.yaml 0.3.1): the purchase order rules over mocks. The order of the steps, the stored amounts,
 * the number at submit, the level-0 and waiting paths, the states each operation accepts, and that the actor stored is the
 * membership id and never the user id (security L13).
 */
class PurchaseOrderServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID VENDOR = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final VendorRepository vendors = mock(VendorRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final UnitRepository unitRows = mock(UnitRepository.class);
    private final PrintTemplateRepository templates = mock(PrintTemplateRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final PurchaseOrderService service = new PurchaseOrderService(gate, guard, stores, products, vendors, orders, engine,
            approvals, series, numbers, clients, events, reasons, unitRows, templates, team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Central Store User", "PHARMACIST",
            List.of());
    private Store store = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, true,
            0);
    private Vendor vendor = vendor(LocalDate.of(2027, 12, 31));

    private static Vendor vendor(LocalDate licenceValidTill) {
        return new Vendor(VENDOR, "Test Vendor One", "27AAAAA0000A1Z5", true, "LIC-1", licenceValidTill, null, null, 30, null,
                null, null, null, true, 0L);
    }

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE))).thenAnswer(i -> store);
        when(stores.find(TENANT, STORE_ID)).thenAnswer(i -> Optional.of(store));
        when(stores.list(TENANT, true)).thenAnswer(i -> List.of(store));
        when(vendors.find(TENANT, VENDOR)).thenAnswer(i -> Optional.of(vendor));
        when(orders.vendorSupplies(TENANT, VENDOR, STORE_ID)).thenReturn(true);
        when(products.find(TENANT, PRODUCT)).thenReturn(Optional.of(product()));
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, "approval-reject.quantity-too-high")).thenReturn(true);
    }

    private ProductRepository.Full product() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "U-TCB-0001", "Test Paracetamol 500 mg (fake)",
                null, false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), "NONE",
                false, "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private Row row(String status, long version, UUID requestId) {
        return new Row(PO, UNIT, STORE_ID, VENDOR, "DRAFT".equals(status) ? null : 7L, "DRAFT".equals(status) ? null : "PO-0007",
                status, TODAY.plusDays(3), 30, null, requestId, 70_000L, 8_400L, 78_400L, NOW, ME, NOW, version);
    }

    private Line line(int qty, int received) {
        return new Line(UUID.randomUUID(), PRODUCT, 1, qty, new BigDecimal("70.00"), new BigDecimal("12"), 70_000L, 8_400L,
                received, false);
    }

    private void found(Row row) {
        when(orders.find(eq(TENANT), eq(STORE_ID), any())).thenReturn(Optional.of(row));
        when(orders.findForUpdate(TENANT, STORE_ID, PO)).thenReturn(Optional.of(row));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(line(10, 0)));
    }

    private PharmacyInputs.PurchaseOrderCreate create(LocalDate delivery, String qty, String rate) {
        return new PharmacyInputs.PurchaseOrderCreate(KEY, VENDOR, delivery, BigDecimal.valueOf(30),
                List.of(new PharmacyInputs.PoLineInput(PRODUCT, new BigDecimal(qty), new BigDecimal(rate))));
    }

    @Test
    void theStoreGuardAnswersBeforeAnyQueryValueIsParsedOnTheListAndOnTheReorderList() {
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE)))
                .thenThrow(new com.sahaj.platform.api.NotFoundException("This record was not found."));

        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), "NOT_A_STATUS", "maybe", "abc", "-5"))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        assertThatThrownBy(() -> service.reorder(TENANT, USER, STORE_ID.toString(), "abc", "-5"))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
    }

    @Test
    void aDraftIsWrittenLinesBeforeTheHeaderLeavesDraftWithRoundedAmountsAndTheMembershipAsActor() {
        when(orders.find(TENANT, STORE_ID, PO)).thenAnswer(i -> Optional.of(row("DRAFT", 1, null)));
        when(orders.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 1, null)));
        when(orders.lines(eq(TENANT), any())).thenReturn(List.of(line(10, 0)));
        when(orders.updateDraft(any(), any(), anyLong(), any(), any(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);

        PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> outcome = service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(TODAY.plusDays(6), "10", "70.125")));

        assertThat(outcome.created()).isTrue();
        InOrder order = Mockito.inOrder(clients, orders);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreatePurchaseOrder"), any());
        order.verify(orders).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(VENDOR), eq(TODAY.plusDays(6)), eq(30), eq(ME));
        ArgumentCaptor<NewLine> line = ArgumentCaptor.forClass(NewLine.class);
        order.verify(orders).insertLine(eq(TENANT), eq(UNIT), any(), line.capture(), eq(ME));
        order.verify(orders).updateDraft(eq(TENANT), any(), eq(0L), eq(VENDOR), any(), eq(30), eq(70_125L), eq(8_415L),
                eq(78_540L), eq(ME));
        assertThat(line.getValue().amountPaise()).as("10 x 70.125 = 701.25 rupees").isEqualTo(70_125L);
        assertThat(line.getValue().gstPaise()).as("12 percent of 701.25, half up").isEqualTo(8_415L);
        verify(audit).record(eq("PHARMACY_PO_CREATED"), any(), any(), any(), any());
        UserIdNeverWritten.assertNone(USER, orders, clients, events, audit);
    }

    @Test
    void everyFieldErrorComesTogetherAndNothingIsWritten() {
        PharmacyInputs.PurchaseOrderCreate bad = new PharmacyInputs.PurchaseOrderCreate(KEY, UUID.randomUUID(), TODAY.minusDays(1),
                BigDecimal.valueOf(400), List.of(new PharmacyInputs.PoLineInput(PRODUCT, new BigDecimal("0"), new BigDecimal("0")),
                        new PharmacyInputs.PoLineInput(PRODUCT, new BigDecimal("1.5"), new BigDecimal("1.00001"))));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(bad)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors()).extracting("field")
                        .contains("vendorId", "deliveryExpected", "creditDays", "lines[0].qty", "lines[0].rate",
                                "lines[1].productId", "lines[1].qty", "lines[1].rate"));
        verify(orders, never()).insert(any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(clients, never()).take(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDeliveryDateIsNeededOnAManualOrder() {
        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, "1", "5.00")))).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting("field").containsExactly("deliveryExpected"));
    }

    @Test
    void aBlockedVendorIs409VendorBlockedAfterTheFieldChecks() {
        vendor = vendor(LocalDate.of(2026, 11, 18));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(TODAY, "1", "5.00")))).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("vendor-blocked");
                    assertThat(e.getMessage()).isEqualTo("This vendor's drug licence has expired. It cannot get a new purchase order.");
                });
        verify(orders, never()).insert(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aStoreThatIsNotTheCentralStoreIs409DocState() {
        store = new Store(STORE_ID, UNIT, "Test Ward", "W1", StoreType.WARD, null, false, false, true, true, 0);

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(TODAY, "1", "5.00")))).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("doc-state");
                    assertThat(e.getMessage()).isEqualTo("Purchase orders are raised at the Central Store.");
                });
    }

    @Test
    void theSameKeyAnswersTheFirstOrderWithNoNewWrite() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyCreatePurchaseOrder", PO)));
        found(row("DRAFT", 1, null));

        PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> outcome = service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(TODAY, "1", "5.00")));

        assertThat(outcome.created()).isFalse();
        verify(orders, never()).insert(any(), any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void aKeyUsedForAnotherOperationIs409ClientKeyReused() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME, "pharmacyCopyPurchaseOrder",
                PO)));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(TODAY, "1", "5.00")))).isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.slug()).isEqualTo("client-key-reused"));
    }

    @Test
    void submitWithNoRuleTakesTheNumberAndIsApprovedAtOnce() {
        found(row("DRAFT", 3, null));
        when(numbers.next(TENANT, DocType.PURCHASE_ORDER)).thenReturn(7L);
        when(engine.plan(eq(TENANT), any())).thenReturn(null);
        when(orders.submit(any(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(true);

        service.submit(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionClientKeyInput(KEY, BigDecimal.valueOf(3))));

        InOrder order = Mockito.inOrder(clients, orders, numbers, engine);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacySubmitPurchaseOrder"), eq(PO));
        order.verify(orders).findForUpdate(TENANT, STORE_ID, PO);
        order.verify(numbers).next(TENANT, DocType.PURCHASE_ORDER);
        order.verify(orders).submit(TENANT, PO, 3L, "APPROVED", 7L, "PO-0007", ME);
        verify(engine, never()).submit(any(), any(), any(), any(), any());
        verify(audit).record(eq("PHARMACY_PO_SUBMITTED"), any(), eq(PO), any(), any());
        UserIdNeverWritten.assertNone(USER, orders, clients, events, audit);
    }

    @Test
    void submitWithARuleWaitsForApprovalAndLinksTheRequestAfterTheHeaderMoved() {
        found(row("DRAFT", 3, null));
        when(numbers.next(TENANT, DocType.PURCHASE_ORDER)).thenReturn(7L);
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        UUID requestId = UUID.randomUUID();
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(requestId);
        when(orders.submit(any(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(true);

        service.submit(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionClientKeyInput(KEY, BigDecimal.valueOf(3))));

        InOrder order = Mockito.inOrder(orders, engine);
        order.verify(orders).submit(TENANT, PO, 3L, "WAITING_APPROVAL", 7L, "PO-0007", ME);
        order.verify(engine).submit(eq(TENANT), eq(UNIT), eq(ME), any(ApprovalEngine.Subject.class), eq(plan));
        order.verify(orders).attachRequest(TENANT, PO, requestId, ME);
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        verify(engine).plan(eq(TENANT), subject.capture());
        assertThat(subject.getValue().type()).isEqualTo(TransactionType.PURCHASE_ORDER);
        assertThat(subject.getValue().amountPaise()).as("the total including GST, BR-17").isEqualTo(78_400L);
    }

    @Test
    void aStaleVersionIs409ChangedAndASubmittedOrderCannotBeSubmittedAgain() {
        found(row("DRAFT", 4, null));
        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionClientKeyInput(KEY, BigDecimal.valueOf(3)))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("changed"));

        found(row("APPROVED", 4, null));
        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionClientKeyInput(UUID.randomUUID(), BigDecimal.valueOf(4)))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void aSubmittedOrderCannotBeEditedAndTheSentenceSaysWhatToDo() {
        found(row("WAITING_APPROVAL", 2, UUID.randomUUID()));
        PharmacyInputs.PurchaseOrderUpdate in = new PharmacyInputs.PurchaseOrderUpdate(BigDecimal.valueOf(2), VENDOR,
                TODAY.plusDays(2), BigDecimal.valueOf(30),
                List.of(new PharmacyInputs.PoLineInput(PRODUCT, BigDecimal.ONE, new BigDecimal("5.00"))));

        assertThatThrownBy(() -> service.update(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("doc-state");
                    assertThat(e.getMessage()).isEqualTo("A submitted purchase order cannot be edited. Cancel it and raise a new one.");
                });
        verify(orders, never()).updateLine(any(), any(), any(), any());
    }

    @Test
    void anEditUpdatesTheKeptLineRemovesTheOthersAndAddsTheNewOnesBeforeTheHeader() {
        Row draft = row("DRAFT", 1, null);
        found(draft);
        UUID other = UUID.randomUUID();
        Line kept = line(10, 0);
        Line gone = new Line(UUID.randomUUID(), other, 2, 5, new BigDecimal("3.00"), new BigDecimal("5"), 1_500L, 75L, 0, false);
        when(orders.lines(TENANT, PO)).thenReturn(List.of(kept, gone), List.of(kept));
        when(orders.updateDraft(any(), any(), anyLong(), any(), any(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);
        PharmacyInputs.PurchaseOrderUpdate in = new PharmacyInputs.PurchaseOrderUpdate(BigDecimal.ONE, VENDOR, TODAY.plusDays(2),
                BigDecimal.valueOf(15), List.of(new PharmacyInputs.PoLineInput(PRODUCT, BigDecimal.valueOf(20),
                        new BigDecimal("70.00"))));

        service.update(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in));

        InOrder order = Mockito.inOrder(orders);
        order.verify(orders).updateLine(eq(TENANT), eq(kept.id()), any(NewLine.class), eq(ME));
        order.verify(orders).removeLine(TENANT, gone.id(), ME);
        order.verify(orders).updateDraft(eq(TENANT), eq(PO), eq(1L), eq(VENDOR), eq(TODAY.plusDays(2)), eq(15), eq(140_000L),
                eq(16_800L), eq(156_800L), eq(ME));
        verify(orders, never()).insertLine(any(), any(), any(), any(), any());
    }

    @Test
    void discardIsForADraftOnly() {
        found(row("DRAFT", 1, null));
        service.discard(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionInput(BigDecimal.ONE)));
        verify(orders).setStatus(TENANT, PO, 1L, "DISCARDED", ME);

        found(row("APPROVED", 1, null));
        assertThatThrownBy(() -> service.discard(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.VersionInput(BigDecimal.ONE))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void markSentNeedsAnApprovedOrderAndADateFromTheApprovalDayToToday() {
        found(row("APPROVED", 5, null));
        when(orders.markSent(any(), any(), anyLong(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.markSent(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.MarkSentInput(BigDecimal.valueOf(5), TODAY.plusDays(1)))))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.markSent(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.MarkSentInput(BigDecimal.valueOf(5), TODAY.minusDays(1)))))
                .isInstanceOf(InvalidFieldsException.class);

        service.markSent(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.MarkSentInput(BigDecimal.valueOf(5), TODAY)));
        verify(orders).markSent(TENANT, PO, 5L, TODAY, ME);

        found(row("SENT", 6, null));
        assertThatThrownBy(() -> service.markSent(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.MarkSentInput(BigDecimal.valueOf(6), TODAY))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void cancelWithdrawsAWaitingRequestFirstAndRefusesAnOrderThatReceivedGoods() {
        UUID requestId = UUID.randomUUID();
        found(row("WAITING_APPROVAL", 2, requestId));
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.id()).thenReturn(requestId);
        when(request.pending()).thenReturn(true);
        when(request.version()).thenReturn(4L);
        when(approvals.findForUpdate(TENANT, requestId)).thenReturn(Optional.of(request));
        PharmacyInputs.VersionReasonInput in = new PharmacyInputs.VersionReasonInput(BigDecimal.valueOf(2),
                "approval-reject.quantity-too-high", null);

        service.cancel(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in));

        InOrder order = Mockito.inOrder(approvals, orders);
        order.verify(approvals).findForUpdate(TENANT, requestId);
        order.verify(orders).findForUpdate(TENANT, STORE_ID, PO);
        order.verify(approvals).finish(TENANT, requestId, 4L, "WITHDRAWN", ME);
        order.verify(orders).setStatus(TENANT, PO, 2L, "CANCELLED", ME);

        found(row("SENT", 2, null));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(line(10, 4)));
        assertThatThrownBy(() -> service.cancel(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void cancelAndCloseShortNeedAnActiveReasonOfTheApprovalRejectList() {
        found(row("SENT", 2, null));
        PharmacyInputs.VersionReasonInput bad = new PharmacyInputs.VersionReasonInput(BigDecimal.valueOf(2), "nonsense", null);

        assertThatThrownBy(() -> service.cancel(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(bad)))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting("field").containsExactly("reasonKey"));
        assertThatThrownBy(() -> service.closeShort(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(bad))).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void closeShortIsForAPartlyReceivedOrderOnlyAndClosesTheOpenLines() {
        found(row("PARTLY_RECEIVED", 8, null));
        when(orders.closeShort(TENANT, PO, 8L, ME)).thenReturn(2);
        PharmacyInputs.VersionReasonInput in = new PharmacyInputs.VersionReasonInput(BigDecimal.valueOf(8),
                "approval-reject.quantity-too-high", null);

        service.closeShort(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in));

        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("PHARMACY_PO_CLOSED_SHORT"), any(), eq(PO), any(), after.capture());
        assertThat(after.getValue()).containsEntry("shortLineCount", 2);

        found(row("SENT", 8, null));
        assertThatThrownBy(() -> service.closeShort(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void aRejectedOrderIsCopiedIntoANewDraftWithNoDeliveryDateAndOnlyThatState() {
        found(row("REJECTED", 3, UUID.randomUUID()));
        when(orders.updateDraft(any(), any(), anyLong(), any(), any(), anyInt(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);

        service.copy(TENANT, USER, STORE_ID.toString(), PO.toString(), PharmacyInputs.Body.of(new PharmacyInputs.ClientKeyInput(KEY)));

        verify(orders).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(VENDOR), eq(null), eq(30), eq(ME));
        verify(orders).insertLine(eq(TENANT), eq(UNIT), any(), any(NewLine.class), eq(ME));

        found(row("APPROVED", 3, null));
        assertThatThrownBy(() -> service.copy(TENANT, USER, STORE_ID.toString(), PO.toString(),
                PharmacyInputs.Body.of(new PharmacyInputs.ClientKeyInput(UUID.randomUUID()))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void theApprovalMakesTheOrderApprovedOrRejectedUnderItsLockAndOnlyWhenItIsWaiting() {
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(PO);
        UUID approverUser = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        PharmacyCaller approverCaller = new PharmacyCaller(TENANT, approverUser, approver, "Test Owner", "OWNER", List.of());
        when(orders.findForUpdateById(TENANT, PO)).thenReturn(Optional.of(row("WAITING_APPROVAL", 2, UUID.randomUUID())));

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "OWNER", null));
        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "OWNER", null));

        verify(orders).setStatusLocked(TENANT, PO, "WAITING_APPROVAL", "APPROVED", approver);
        verify(orders).setStatusLocked(TENANT, PO, "WAITING_APPROVAL", "REJECTED", approver);
        UserIdNeverWritten.assertNone(approverUser, orders, events, audit);

        when(orders.findForUpdateById(TENANT, PO)).thenReturn(Optional.of(row("CANCELLED", 2, null)));
        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approverCaller, request, "OWNER", null));
        verify(orders, Mockito.times(1)).setStatusLocked(TENANT, PO, "WAITING_APPROVAL", "APPROVED", approver);
    }

    @Test
    void theApprovalSummaryReadsTheOrderWithNoLockBecauseTheInboxRunsReadOnly() {
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(PO);
        when(orders.findById(TENANT, PO)).thenReturn(Optional.of(row("WAITING_APPROVAL", 2, UUID.randomUUID())));

        ApprovalSubjectHandler.Summary summary = service.summary(TENANT, request);

        assertThat(summary.what()).contains("PO-0007").contains("Test Vendor One");
        verify(orders).findById(TENANT, PO);
        verify(orders, never()).findForUpdateById(any(), any());
        verify(orders, never()).findForUpdate(any(), any(), any());
    }

    @Test
    void theReorderListSuggestsWholePurchaseUnitsAndZeroWhenAnOpenOrderCoversIt() {
        when(orders.reorder(eq(TENANT), eq(STORE_ID), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PurchaseOrderRepository.ReorderRow(PRODUCT, 12, 100, 0, 0, null)));
        when(orders.reorderTotal(eq(TENANT), eq(STORE_ID), any())).thenReturn(1L);

        PurchaseViews.ReorderPage page = service.reorder(TENANT, USER, STORE_ID.toString(), null, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).suggestedQty()).as("(100 - 12) / 10 rounded up").isEqualTo(9);
        assertThat(page.items().get(0).allStoresStock()).as("null for a caller who is not the owner").isNull();

        when(orders.reorder(eq(TENANT), eq(STORE_ID), any(), anyInt(), anyInt())).thenReturn(List.of(
                new PurchaseOrderRepository.ReorderRow(PRODUCT, 50, 100, 50, 5, "PO-0003")));
        assertThat(service.reorder(TENANT, USER, STORE_ID.toString(), null, null).items().get(0).suggestedQty()).isZero();
    }

    @Test
    void aBadStatusFilterIs400() {
        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), "DRAFT,NOPE", null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
    }
}
