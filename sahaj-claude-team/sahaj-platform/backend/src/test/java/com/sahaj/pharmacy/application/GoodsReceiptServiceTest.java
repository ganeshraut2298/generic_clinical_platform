package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.GoodsReceiptRepository.Line;
import com.sahaj.pharmacy.application.GoodsReceiptRepository.NewLine;
import com.sahaj.pharmacy.application.GoodsReceiptRepository.Row;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.pharmacy.domain.Vendor;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-7 (docs/api/pharmacy.yaml 0.3.3): the goods receipt rules over mocks. The stored computations, the checks of a line, the
 * lock order of a post (client key, store, receipt, purchase order, number, batches, balances, then the writes; no unit lock after
 * the number, migration 1720 L11), the rate-rise path, the Schedule X rule, the payable, the purchase order moves and the debit
 * note of the short supply; and that the actor stored is the membership id and never the user id (security L13).
 */
class GoodsReceiptServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID VENDOR = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PRODUCT_X = UUID.randomUUID();
    private static final UUID GRN = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate EXPIRY = LocalDate.of(2028, 3, 31);

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final VendorRepository vendors = mock(VendorRepository.class);
    private final PurchaseOrderRepository orders = mock(PurchaseOrderRepository.class);
    private final GoodsReceiptRepository receipts = mock(GoodsReceiptRepository.class);
    private final VendorPayableRepository payables = mock(VendorPayableRepository.class);
    private final DebitNoteRepository debitNotes = mock(DebitNoteRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final DocumentViews views = new DocumentViews(events, stores, team, engine);
    private final GoodsReceiptService service = new GoodsReceiptService(gate, guard, stores, products, vendors, orders, receipts,
            payables, debitNotes, engine, approvals, series, numbers, clients, events, stock, posting, signature, views, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final PharmacyCaller pharmacist = new PharmacyCaller(TENANT, USER, ME, "Test Central Store Pharmacist", "PHARMACIST",
            List.of());
    private final PharmacyCaller clerk = new PharmacyCaller(TENANT, USER, ME, "Test Central Store Clerk", "STORE_STAFF",
            List.of());
    private Store store = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, true,
            0);
    private Vendor vendor = vendor(LocalDate.of(2027, 12, 31), 20);

    private static Vendor vendor(LocalDate licenceValidTill, int creditPercent) {
        return new Vendor(VENDOR, "Test Vendor One", "27AAAAA0000A1Z5", true, "LIC-1", licenceValidTill, null, null, 30, null,
                new BigDecimal(creditPercent), null, null, true, 0L);
    }

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(pharmacist);
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE))).thenAnswer(i -> store);
        when(stores.find(TENANT, STORE_ID)).thenAnswer(i -> Optional.of(store));
        when(stores.findForUpdate(TENANT, STORE_ID)).thenAnswer(i -> Optional.of(store));
        when(stores.list(TENANT, true)).thenAnswer(i -> List.of(store));
        when(debitNotes.updateDraft(any(), any(), anyLong(), any(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(vendors.find(TENANT, VENDOR)).thenAnswer(i -> Optional.of(vendor));
        when(products.find(TENANT, PRODUCT)).thenReturn(Optional.of(product(PRODUCT, "NONE")));
        when(products.find(TENANT, PRODUCT_X)).thenReturn(Optional.of(product(PRODUCT_X, "X")));
        when(team.findAll(any(), any())).thenReturn(java.util.Map.of());
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(numbers.next(TENANT, DocType.GRN)).thenReturn(7L);
        when(receipts.markPosted(any(), any(), anyLong(), anyLong(), any(), any())).thenReturn(true);
        when(receipts.updateDraft(any(), any(), anyLong(), any(), any(), any(), anyInt(), anyLong(), any())).thenReturn(true);
        when(receipts.sendForApproval(any(), any(), anyLong(), any(), any())).thenReturn(true);
        when(stock.findBatchForUpdate(TENANT, PRODUCT, "B1", EXPIRY)).thenAnswer(i -> Optional.of(batch(PRODUCT, "8.00")));
        when(stock.findBatchForUpdate(TENANT, PRODUCT_X, "B1", EXPIRY)).thenAnswer(i -> Optional.of(batch(PRODUCT_X, "8.00")));
    }

    private ProductRepository.Full product(UUID id, String schedule) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test Paracetamol 500 mg (fake)",
                null, false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule,
                false, "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static BatchRow batch(UUID product, String mrp) {
        return new BatchRow(BATCH, product, "B1", EXPIRY, new BigDecimal(mrp), new BigDecimal("5.0000"), null, NOW);
    }

    private Row row(String status, long version, UUID poId) {
        boolean posted = "POSTED".equals(status);
        return new Row(GRN, UNIT, STORE_ID, poId, VENDOR, posted ? 7L : null, posted ? "GRN-0007" : null, status, "INV-1",
                TODAY.minusDays(3), 30, 78_400L, null, posted ? NOW : null, posted ? ME : null, NOW, ME, version);
    }

    /** 10 strips of 10 at PTR 70.00, 2 free, MRP 8.00, GST 12 %: taxable 70,000 paise and GST 8,400, total 78,400. */
    private Line line(UUID product, UUID poLine, int qty, int billed, String ptr, BigDecimal ratePercent, Integer shortQty,
                      String shortAction) {
        BigDecimal rate = new BigDecimal(ptr);
        return new Line(UUID.randomUUID(), poLine, product, 1, "B1", EXPIRY, qty, billed, 2, rate, new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("12"), billed * rate.multiply(BigDecimal.valueOf(100)).longValue(),
                billed * rate.multiply(BigDecimal.valueOf(100)).multiply(new BigDecimal("0.12")).longValue(), rate,
                ratePercent == null ? null : new BigDecimal("70.00"), ratePercent == null ? null : "PO", ratePercent, shortQty,
                shortAction, false, null, null, null);
    }

    private Line plainLine() {
        return line(PRODUCT, null, 10, 10, "70.00", null, null, null);
    }

    private PharmacyInputs.GrnLineInput input(UUID product, UUID poLine, String qty, String billed, String ptr, String expiry) {
        return new PharmacyInputs.GrnLineInput(poLine, product, "B1", expiry, new BigDecimal(qty), new BigDecimal(billed),
                new BigDecimal("2"), new BigDecimal(ptr), new BigDecimal("8.00"), BigDecimal.ZERO, false, null);
    }

    private PharmacyInputs.GoodsReceiptCreate create(UUID poId, UUID vendorId, PharmacyInputs.GrnLineInput... lines) {
        return new PharmacyInputs.GoodsReceiptCreate(KEY, poId, vendorId, " INV-1 ", TODAY.minusDays(3), new BigDecimal("30"),
                new BigDecimal("78400"), List.of(lines));
    }

    private PurchaseOrderRepository.Row po(String status) {
        return new PurchaseOrderRepository.Row(PO, UNIT, STORE_ID, VENDOR, 7L, "PO-0007", status, TODAY, 30, TODAY.minusDays(2),
                null, 70_000L, 8_400L, 78_400L, NOW, ME, NOW, 3L);
    }

    private PurchaseOrderRepository.Line poLine(int qty, int received, boolean closedShort) {
        return new PurchaseOrderRepository.Line(PO_LINE, PRODUCT, 1, qty, new BigDecimal("70.00"), new BigDecimal("12"), 70_000L,
                8_400L, received, closedShort);
    }

    private void postingStubs(Row header, List<Line> lines, PurchaseOrderRepository.Row po) {
        when(receipts.find(TENANT, STORE_ID, GRN)).thenReturn(Optional.of(header));
        when(receipts.findForUpdate(TENANT, STORE_ID, GRN)).thenReturn(Optional.of(header));
        when(receipts.lines(TENANT, GRN)).thenReturn(lines);
        if (po != null) {
            when(orders.find(TENANT, STORE_ID, PO)).thenReturn(Optional.of(po));
            when(orders.findForUpdateById(TENANT, PO)).thenReturn(Optional.of(po));
        }
    }

    private PharmacyInputs.PostWithSigner post(long version, PharmacyInputs.SecondSignerInput signer) {
        return new PharmacyInputs.PostWithSigner(KEY, BigDecimal.valueOf(version), signer);
    }

    // ---- create ----

    @Test
    void theStoreGuardAnswersBeforeAnyQueryValueIsParsedOnTheReceiptList() {
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE))).thenThrow(new NotFoundException("This record was not found."));

        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), "NOT_A_STATUS", "abc", "-5"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDirectDraftStoresTheTypedLinesWithTheirComputedValuesAndTheMembershipAsTheActor() {
        when(receipts.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0, null)));
        when(receipts.lines(eq(TENANT), any())).thenReturn(List.of(plainLine()));

        PharmacyViews.Outcome<PurchaseViews.GoodsReceipt> out = service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, VENDOR, input(PRODUCT, null, "10", "10", "70.00", "2028-03"))));

        assertThat(out.created()).isTrue();
        verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateGoodsReceipt"), any());
        verify(receipts).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(null), eq(VENDOR), eq("INV-1"),
                eq(TODAY.minusDays(3)), eq(30), eq(78_400L), eq(ME));
        ArgumentCaptor<NewLine> stored = ArgumentCaptor.forClass(NewLine.class);
        verify(receipts).insertLine(eq(TENANT), eq(UNIT), any(), stored.capture(), eq(ME));
        NewLine line = stored.getValue();
        assertThat(line.expiryDate()).isEqualTo(EXPIRY);
        assertThat(line.taxablePaise()).isEqualTo(70_000L);
        assertThat(line.gstPaise()).isEqualTo(8_400L);
        assertThat(line.netRate()).isEqualByComparingTo("70.0000");
        assertThat(line.referenceRate()).isNull();
        assertThat(line.shortQty()).isNull();
        assertThat(line.gstRate()).isEqualByComparingTo("12");
        verify(events).insert(eq(TENANT), eq(UNIT), eq("GOODS_RECEIPT"), any(), eq("CREATED"), eq(STORE_ID), eq(ME), any(), any(),
                any(), any(), any());
        verify(numbers, never()).next(any(), any());
        verify(posting, never()).post(any(), anyList());
    }

    @Test
    void aLineAgainstAPurchaseOrderIsFlaggedForARateRiseAndForShortSupply() {
        PurchaseOrderRepository.Row sent = po("SENT");
        when(orders.find(TENANT, STORE_ID, PO)).thenReturn(Optional.of(sent));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));
        when(receipts.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0, PO)));
        when(receipts.lines(eq(TENANT), any())).thenReturn(List.of(plainLine()));

        service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(PO, null, input(PRODUCT, PO_LINE, "6", "6", "74.30", "2028-03"))));

        ArgumentCaptor<NewLine> stored = ArgumentCaptor.forClass(NewLine.class);
        verify(receipts).insertLine(eq(TENANT), eq(UNIT), any(), stored.capture(), eq(ME));
        NewLine line = stored.getValue();
        assertThat(line.referenceRate()).isEqualByComparingTo("70.00");
        assertThat(line.rateBasis()).isEqualTo("PO");
        assertThat(line.ratePercent()).isEqualByComparingTo("6.1");
        assertThat(line.shortQty()).isEqualTo(4);
        verify(receipts).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(PO), eq(VENDOR), any(), any(), anyInt(), anyLong(),
                eq(ME));
    }

    @Test
    void theLastPostedRateOfTheVendorIsTheReferenceOfADirectReceipt() {
        when(orders.lastPostedRate(TENANT, VENDOR, PRODUCT)).thenReturn(Optional.of(new BigDecimal("68.0000")));
        when(receipts.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0, null)));
        when(receipts.lines(eq(TENANT), any())).thenReturn(List.of(plainLine()));

        service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, VENDOR, input(PRODUCT, null, "10", "10", "70.00", "2028-03"))));

        ArgumentCaptor<NewLine> stored = ArgumentCaptor.forClass(NewLine.class);
        verify(receipts).insertLine(eq(TENANT), eq(UNIT), any(), stored.capture(), eq(ME));
        assertThat(stored.getValue().rateBasis()).isEqualTo("LAST");
        assertThat(stored.getValue().ratePercent()).isEqualByComparingTo("2.9");
    }

    @Test
    void everyFieldErrorIsAnsweredTogetherAndAPastExpiryHasTheContractsSentence() {
        PharmacyInputs.GrnLineInput bad = new PharmacyInputs.GrnLineInput(null, PRODUCT, "", "2026-10", new BigDecimal("0"),
                new BigDecimal("1000000"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("8.12345"),
                new BigDecimal("101"), false, "SOMETIMES");
        PharmacyInputs.GoodsReceiptCreate in = new PharmacyInputs.GoodsReceiptCreate(KEY, null, VENDOR, "", TODAY.plusDays(1),
                new BigDecimal("366"), new BigDecimal("99999999901"), List.of(bad));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).extracting(InvalidFieldsException.FieldMessage::field).contains("invoiceNo",
                            "invoiceDate", "creditDays", "invoiceTotalPaise", "lines[0].batchNo", "lines[0].expiryMonth",
                            "lines[0].billedQty", "lines[0].qty", "lines[0].ptr", "lines[0].mrp", "lines[0].discountPercent",
                            "lines[0].shortAction");
                    assertThat(e.errors()).filteredOn(m -> m.field().equals("lines[0].expiryMonth")).extracting(m -> m.message())
                            .containsExactly("Expiry must be in the future");
                });
        verify(receipts, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any());
    }

    @Test
    void aDirectReceiptFromABlockedVendorIsRefusedButAnOrderOfABlockedVendorIsAllowed() {
        vendor = vendor(TODAY.minusDays(1), 20);
        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, VENDOR, input(PRODUCT, null, "10", "10", "70.00", "2028-03")))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("vendor-blocked"));

        when(orders.find(TENANT, STORE_ID, PO)).thenReturn(Optional.of(po("SENT")));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));
        when(receipts.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0, PO)));
        when(receipts.lines(eq(TENANT), any())).thenReturn(List.of(plainLine()));
        PharmacyViews.Outcome<PurchaseViews.GoodsReceipt> out = service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(PO, null, input(PRODUCT, PO_LINE, "10", "10", "70.00", "2028-03"))));
        assertThat(out.view().warnings()).containsExactly("VENDOR_LICENCE_EXPIRED");
    }

    @Test
    void aPurchaseOrderThatIsNotSentOrPartlyReceivedAndADuplicateInvoiceAreRefused() {
        when(orders.find(TENANT, STORE_ID, PO)).thenReturn(Optional.of(po("APPROVED")));
        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(PO, null, input(PRODUCT, PO_LINE, "10", "10", "70.00", "2028-03")))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));

        when(receipts.invoiceTaken(TENANT, VENDOR, "INV-1", null)).thenReturn(true);
        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, VENDOR, input(PRODUCT, null, "10", "10", "70.00", "2028-03")))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("duplicate-invoice");
                    assertThat(e.getMessage()).isEqualTo("That invoice was already received.");
                });
    }

    @Test
    void aReplayOfTheSameKeyAnswersTheFirstResultAndWritesNothing() {
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyCreateGoodsReceipt", GRN)));
        when(receipts.find(TENANT, STORE_ID, GRN)).thenReturn(Optional.of(row("DRAFT", 0, null)));

        PharmacyViews.Outcome<PurchaseViews.GoodsReceipt> out = service.create(TENANT, USER, STORE_ID.toString(),
                PharmacyInputs.Body.of(create(null, VENDOR, input(PRODUCT, null, "10", "10", "70.00", "2028-03"))));

        assertThat(out.created()).isFalse();
        verify(receipts, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any());
        verify(clients, never()).take(any(), any(), any(), any(), any(), any());
    }

    // ---- post ----

    @Test
    void aPostTakesTheLocksInOrderAndAddsReceivedAndFreeIssueUnitsAsGrnLinesWithTheNumber() {
        postingStubs(row("DRAFT", 1, null), List.of(plainLine()), null);

        PurchaseViews.GoodsReceipt posted = service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null);

        assertThat(posted.outcome()).isEqualTo("POSTED");
        InOrder order = Mockito.inOrder(clients, stores, receipts, numbers, stock, posting, payables);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyPostGoodsReceipt"), eq(GRN));
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(receipts).findForUpdate(TENANT, STORE_ID, GRN);
        order.verify(numbers).next(TENANT, DocType.GRN);
        order.verify(stock).ensureBatch(eq(TENANT), eq(UNIT), eq(PRODUCT), eq("B1"), eq(EXPIRY), eq(new BigDecimal("8.00")), any(),
                eq("GRN"), eq(ME));
        order.verify(stock).findBatchForUpdate(TENANT, PRODUCT, "B1", EXPIRY);
        order.verify(posting).post(any(), anyList());
        order.verify(payables).insertWithInvoiceEntry(eq(TENANT), eq(UNIT), any(), eq(VENDOR), eq(GRN), eq("INV-1"),
                eq(TODAY.minusDays(3)), eq(78_400L), eq(TODAY.plusDays(27)), eq(ME));
        order.verify(receipts).markPosted(TENANT, GRN, 1L, 7L, "GRN-0007", ME);
        // L11: the number is taken after the store lock, and nothing locks the store (or the unit) again afterwards
        verify(stores, times(1)).findForUpdate(any(), any());
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        verify(posting).post(actor.capture(), moves.capture());
        assertThat(actor.getValue().membershipId()).isEqualTo(ME).isNotEqualTo(USER);
        assertThat(moves.getValue()).hasSize(1);
        StockPosting.Line move = moves.getValue().get(0);
        assertThat(move.txnType()).isEqualTo("GRN");
        assertThat(move.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(move.delta()).isEqualTo(120);
        assertThat(move.doc().no()).isEqualTo("GRN-0007");
        assertThat(move.secondSignerMembershipId()).isNull();
        verify(stock).setBatchSupply(eq(TENANT), eq(BATCH), eq(new BigDecimal("5.8333")), eq(VENDOR), eq(ME));
        verify(receipts).setLinePosting(eq(TENANT), any(), eq(BATCH), eq(new BigDecimal("5.8333")), eq(null), eq(ME));
        verify(audit, times(1)).record(eq("PHARMACY_GRN_POSTED"), any(), eq(GRN), any(), any());
    }

    @Test
    void aPostWritesThePayableWithTheDueDateAndAnInvoiceEntryAndPreparesTheDebitNoteOfShortBilledLines() {
        // billed 10, received 8: 2 strips x 10 tablets billed and not received
        Line shortLine = new Line(UUID.randomUUID(), null, PRODUCT, 1, "B1", EXPIRY, 8, 10, 2, new BigDecimal("70.00"),
                new BigDecimal("8.00"), BigDecimal.ZERO, new BigDecimal("12"), 70_000L, 8_400L, new BigDecimal("70.00"), null,
                null, null, null, null, false, null, null, null);
        postingStubs(row("DRAFT", 1, null), List.of(shortLine), null);

        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);

        ArgumentCaptor<DebitNoteRepository.NewLine> prepared = ArgumentCaptor.forClass(DebitNoteRepository.NewLine.class);
        verify(debitNotes).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(VENDOR), eq(GRN), eq(null), eq(null), eq(ME));
        verify(debitNotes).insertLine(eq(TENANT), eq(UNIT), any(), prepared.capture(), eq(ME));
        DebitNoteRepository.NewLine dn = prepared.getValue();
        assertThat(dn.lineType()).isEqualTo("SHORT_SUPPLY");
        assertThat(dn.qty()).isEqualTo(20);
        assertThat(dn.rate()).isEqualByComparingTo("7.0000");
        assertThat(dn.creditPercent()).isEqualByComparingTo("20");
        // 20 x 7.00 x 20% = 28.00
        assertThat(dn.creditPaise()).isEqualTo(2_800L);
        assertThat(dn.gstReversalPaise()).isEqualTo(336L);
        verify(debitNotes).updateDraft(eq(TENANT), any(), eq(0L), eq(null), eq(null), eq(2_800L), eq(336L), eq(ME));
    }

    @Test
    void aTickedRateDifferenceOfALineThatIsNotShortGoesOnTheDraftAtFullCredit() {
        BigDecimal net = new BigDecimal("74.30");
        Line ticked = new Line(UUID.randomUUID(), PO_LINE, PRODUCT, 1, "B1", EXPIRY, 10, 10, 0, net, new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("12"), 74_300L, 8_916L, net, new BigDecimal("70.00"), "PO", new BigDecimal("6.1"),
                0, null, true, null, null, null);
        PurchaseOrderRepository.Row sent = po("SENT");
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));
        when(orders.linesForUpdate(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));
        Row withTotal = new Row(GRN, UNIT, STORE_ID, PO, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 83_216L,
                null, null, null, NOW, ME, 1L);
        postingStubs(withTotal, List.of(ticked), sent);

        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);

        ArgumentCaptor<DebitNoteRepository.NewLine> prepared = ArgumentCaptor.forClass(DebitNoteRepository.NewLine.class);
        verify(debitNotes).insertLine(eq(TENANT), eq(UNIT), any(), prepared.capture(), eq(ME));
        assertThat(prepared.getValue().creditPercent()).isEqualByComparingTo("100");
        // 10 strips x 10 tablets x (74.30 - 70.00) / 10 = 43.00 rupees
        assertThat(prepared.getValue().qty()).isEqualTo(100);
        assertThat(prepared.getValue().creditPaise()).isEqualTo(4_300L);
    }

    @Test
    void aRateRiseThatTheRuleCatchesSendsTheReceiptForApprovalAndPostsNothing() {
        BigDecimal net = new BigDecimal("74.30");
        Line rising = new Line(UUID.randomUUID(), PO_LINE, PRODUCT, 1, "B1", EXPIRY, 10, 10, 2, net, new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("12"), 74_300L, 8_916L, net, new BigDecimal("70.00"), "PO", new BigDecimal("6.1"),
                0, null, false, null, null, null);
        PurchaseOrderRepository.Row sent = po("SENT");
        Row header = new Row(GRN, UNIT, STORE_ID, PO, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 83_216L, null,
                null, null, NOW, ME, 1L);
        postingStubs(header, List.of(rising), sent);
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));
        UUID request = UUID.randomUUID();
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        ApprovalEngine.Plan plan = new ApprovalEngine.Plan(null, List.of(), 1, null, false);
        when(engine.plan(eq(TENANT), subject.capture())).thenReturn(plan);
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(request);

        PurchaseViews.GoodsReceipt answer = service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null);

        assertThat(answer.outcome()).isEqualTo("SENT_FOR_APPROVAL");
        // the first ask is the post's; the answer's own view asks again for its needsApproval flag
        ApprovalEngine.Subject asked = subject.getAllValues().get(0);
        assertThat(asked.type()).isEqualTo(TransactionType.GRN_RATE_RISE);
        assertThat(asked.subjectType()).isEqualTo("GOODS_RECEIPT");
        assertThat(asked.maxRateRisePercent()).isEqualByComparingTo("6.1");
        assertThat(asked.amountPaise()).isEqualTo(74_300L);
        verify(receipts).sendForApproval(TENANT, GRN, 1L, request, ME);
        verifyNoInteractions(posting, numbers, payables);
        verify(debitNotes, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(stock, never()).ensureBatch(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anApprovedReceiptPostsWithoutAskingTheRateRuleAgain() {
        BigDecimal net = new BigDecimal("74.30");
        Line rising = new Line(UUID.randomUUID(), null, PRODUCT, 1, "B1", EXPIRY, 10, 10, 2, net, new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("12"), 74_300L, 8_916L, net, new BigDecimal("70.00"), "LAST", new BigDecimal("6.1"),
                null, null, false, null, null, null);
        Row approved = new Row(GRN, UNIT, STORE_ID, null, VENDOR, null, null, "APPROVED", "INV-1", TODAY.minusDays(3), 30, 83_216L,
                UUID.randomUUID(), null, null, NOW, ME, 4L);
        postingStubs(approved, List.of(rising), null);
        when(orders.lastPostedRate(TENANT, VENDOR, PRODUCT)).thenReturn(Optional.of(new BigDecimal("70.00")));

        PurchaseViews.GoodsReceipt answer = service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(4, null)), null);

        assertThat(answer.outcome()).isEqualTo("POSTED");
        verify(engine, never()).plan(any(), any());
        verify(receipts, never()).updateLine(any(), any(), any(), any());
        verify(posting).post(any(), anyList());
    }

    @Test
    void anInvoiceTotalMoreThanOneRupeeFromTheLinesIsRefusedAndNothingIsPosted() {
        Row header = new Row(GRN, UNIT, STORE_ID, null, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 78_501L,
                null, null, null, NOW, ME, 1L);
        postingStubs(header, List.of(plainLine()), null);

        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.slug()).isEqualTo("invoice-total-mismatch");
                    assertThat(e.getMessage()).isEqualTo("Invoice total does not match the lines.");
                });
        verifyNoInteractions(posting, numbers);

        // exactly 100 paise apart is allowed
        Row near = new Row(GRN, UNIT, STORE_ID, null, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 78_500L, null,
                null, null, NOW, ME, 1L);
        postingStubs(near, List.of(plainLine()), null);
        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);
        verify(posting).post(any(), anyList());
    }

    @Test
    void aShortLineAgainstThePurchaseOrderNeedsAShortActionAndTheChoiceMovesTheOrder() {
        Line missing = line(PRODUCT, PO_LINE, 6, 6, "70.00", null, 4, null);
        Row header = new Row(GRN, UNIT, STORE_ID, PO, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 47_040L, null,
                null, null, NOW, ME, 1L);
        postingStubs(header, List.of(missing), po("SENT"));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));

        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(InvalidFieldsException.FieldMessage::field)
                                .containsExactly("lines[0].shortAction"));
        verifyNoInteractions(posting);
    }

    @Test
    void aPostAgainstTheOrderAddsTheReceivedQuantityAndMovesItToPartlyReceivedOrReceived() {
        Line six = line(PRODUCT, PO_LINE, 6, 6, "70.00", null, 4, "BALANCE_TO_COME");
        Row header = new Row(GRN, UNIT, STORE_ID, PO, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 47_040L, null,
                null, null, NOW, ME, 1L);
        postingStubs(header, List.of(six), po("SENT"));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)), List.of(poLine(10, 6, false)));
        when(orders.linesForUpdate(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));

        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);

        InOrder order = Mockito.inOrder(receipts, orders, numbers, posting);
        order.verify(receipts).findForUpdate(TENANT, STORE_ID, GRN);
        order.verify(orders).findForUpdateById(TENANT, PO);
        order.verify(numbers).next(TENANT, DocType.GRN);
        order.verify(posting).post(any(), anyList());
        order.verify(orders).addReceived(TENANT, PO_LINE, 6, ME);
        verify(orders).setStatusLocked(TENANT, PO, "SENT", "PARTLY_RECEIVED", ME);
        verify(orders, never()).closeLineShort(any(), any(), any());
    }

    @Test
    void closingAShortLineMovesASentOrderToClosedShortThroughPartlyReceived() {
        Line six = line(PRODUCT, PO_LINE, 6, 6, "70.00", null, 4, "CLOSE_SHORT");
        Row header = new Row(GRN, UNIT, STORE_ID, PO, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 47_040L, null,
                null, null, NOW, ME, 1L);
        postingStubs(header, List.of(six), po("SENT"));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)), List.of(poLine(10, 6, true)));
        when(orders.linesForUpdate(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));

        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);

        verify(orders).closeLineShort(TENANT, PO_LINE, ME);
        InOrder order = Mockito.inOrder(orders);
        order.verify(orders).setStatusLocked(TENANT, PO, "SENT", "PARTLY_RECEIVED", ME);
        order.verify(orders).setStatusLocked(TENANT, PO, "PARTLY_RECEIVED", "CLOSED_SHORT", ME);
    }

    @Test
    void receivingTheWholeOrderMovesItToReceived() {
        Line ten = line(PRODUCT, PO_LINE, 10, 10, "70.00", null, 0, null);
        Row header = row("DRAFT", 1, PO);
        postingStubs(header, List.of(ten), po("PARTLY_RECEIVED"));
        when(orders.lines(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)), List.of(poLine(10, 10, false)));
        when(orders.linesForUpdate(TENANT, PO)).thenReturn(List.of(poLine(10, 0, false)));

        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), null);

        verify(orders).setStatusLocked(TENANT, PO, "PARTLY_RECEIVED", "RECEIVED", ME);
    }

    @Test
    void theSameBatchWithADifferentMrpIsRefusedNamingTheLineAndNothingIsPosted() {
        when(stock.findBatchForUpdate(TENANT, PRODUCT, "B1", EXPIRY)).thenAnswer(i -> Optional.of(batch(PRODUCT, "9.00")));
        postingStubs(row("DRAFT", 1, null), List.of(plainLine()), null);

        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null)).isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("batch-mrp-mismatch");
                    assertThat(e.getMessage()).isEqualTo("Same batch with a different MRP. Check the batch number.");
                    assertThat(e.extensions()).containsKey("lines");
                });
        verify(posting, never()).post(any(), anyList());
        verify(payables, never()).insertWithInvoiceEntry(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void aScheduleXLineNeedsAPharmacistAndASecondSignerAndOnlyThatLineCarriesTheSigner() {
        Line x = line(PRODUCT_X, null, 10, 10, "70.00", null, null, null);
        Line plain = line(PRODUCT, null, 10, 10, "70.00", null, null, null);
        Row header = new Row(GRN, UNIT, STORE_ID, null, VENDOR, null, null, "DRAFT", "INV-1", TODAY.minusDays(3), 30, 156_800L,
                null, null, null, NOW, ME, 1L);
        postingStubs(header, List.of(x, plain), null);

        // no signer
        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(InvalidFieldsException.FieldMessage::field)
                                .containsExactly("secondSigner"));
        // not a pharmacist
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(clerk);
        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), SIGNER)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(posting);

        // pharmacist with a verified signer
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(pharmacist);
        service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, null)), SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue()).extracting(StockPosting.Line::productId).containsExactly(PRODUCT_X, PRODUCT);
        assertThat(moves.getValue()).extracting(StockPosting.Line::secondSignerMembershipId).containsExactly(SIGNER, null);
        verify(receipts).setLinePosting(eq(TENANT), eq(x.id()), any(), any(), eq(SIGNER), eq(ME));
        verify(receipts).setLinePosting(eq(TENANT), eq(plain.id()), any(), any(), eq((UUID) null), eq(ME));
    }

    @Test
    void theSecondSignerToCheckIsAskedForOnlyWhenTheReceiptHasAScheduleXLineAndTheCallerIsAPharmacist() {
        postingStubs(row("DRAFT", 1, null), List.of(plainLine()), null);
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, typed)))).isNull();

        postingStubs(row("DRAFT", 1, null), List.of(line(PRODUCT_X, null, 10, 10, "70.00", null, null, null)), null);
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, typed)))).isSameAs(typed);

        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(clerk);
        assertThatThrownBy(() -> service.precheck(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, typed)))).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(signature);
    }

    @Test
    void theShortQuantityIsWorkedOutAgainOnTheLockedPurchaseOrderLinesAndANewlyShortLineNeedsAnAction() {
        UUID poLine = UUID.randomUUID();
        // stored when 10 were left to receive: 10 received, nothing short
        Line stored = line(PRODUCT, poLine, 10, 10, "70.00", null, 0, null);
        // a parallel receipt of 4 was posted since: only 6 are left, so 10 received leaves nothing short
        PurchaseOrderRepository.Line parallel = new PurchaseOrderRepository.Line(poLine, PRODUCT, 1, 10, new BigDecimal("70.00"),
                new BigDecimal("12"), 70_000L, 8_400L, 4, false);
        assertThat(service.shortQuantitiesNow(List.of(stored), List.of(parallel))).containsEntry(stored.id(), 0);

        // 20 ordered, 4 received by a parallel receipt: 16 left, this one receives 10 and bills 10: 6 short, no action chosen
        PurchaseOrderRepository.Line bigger = new PurchaseOrderRepository.Line(poLine, PRODUCT, 1, 20, new BigDecimal("70.00"),
                new BigDecimal("12"), 140_000L, 16_800L, 4, false);
        assertThatThrownBy(() -> service.shortQuantitiesNow(List.of(stored), List.of(bigger)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field).containsExactly("lines[0].shortAction"));

        Line chosen = line(PRODUCT, poLine, 10, 10, "70.00", null, 0, "CLOSE_SHORT");
        assertThat(service.shortQuantitiesNow(List.of(chosen), List.of(bigger))).containsEntry(chosen.id(), 6);

        // a line closed short already has nothing left to be short of; a receipt line with no order line is not listed
        PurchaseOrderRepository.Line closed = new PurchaseOrderRepository.Line(poLine, PRODUCT, 1, 20, new BigDecimal("70.00"),
                new BigDecimal("12"), 140_000L, 16_800L, 4, true);
        assertThat(service.shortQuantitiesNow(List.of(stored, plainLine()), List.of(closed))).containsOnlyKeys(stored.id())
                .containsEntry(stored.id(), 0);
    }

    @Test
    void noSecondSignerIsAskedForAReceiptThatCannotBePostedSoNoPinIsCounted() {
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        for (String status : List.of("POSTED", "DISCARDED", "WAITING_APPROVAL")) {
            postingStubs(row(status, 1, null), List.of(line(PRODUCT_X, null, 10, 10, "70.00", null, null, null)), null);
            assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, typed))))
                    .as(status).isNull();
        }
        postingStubs(row("APPROVED", 1, null), List.of(line(PRODUCT_X, null, 10, 10, "70.00", null, null, null)), null);
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(post(1, typed))))
                .isSameAs(typed);
    }

    @Test
    void aStoreSwitchedOffAfterTheGuardIsNotFoundAndNothingIsNumberedOrPosted() {
        // m-5 and DEC-077 L9: the store row is locked and its status read again after the lock
        postingStubs(row("DRAFT", 1, null), List.of(plainLine()), null);
        Store off = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, false, 1);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(off));

        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(1, null)), null)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(numbers, posting, payables);
        verify(receipts, never()).findForUpdate(any(), any(), any());
    }

    @Test
    void aStaleVersionIsChangedAndAStatusThatCannotBePostedIsDocState() {
        postingStubs(row("DRAFT", 5, null), List.of(plainLine()), null);
        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(4, null)), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.slug()).isEqualTo("changed"));

        postingStubs(row("WAITING_APPROVAL", 5, null), List.of(plainLine()), null);
        assertThatThrownBy(() -> service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(5, null)), null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verifyNoInteractions(posting, numbers);
    }

    @Test
    void aReplayOfThePostAnswersTheCurrentStateAndTakesNothingAgain() {
        postingStubs(row("POSTED", 6, null), List.of(plainLine()), null);
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                "pharmacyPostGoodsReceipt", GRN)));

        PurchaseViews.GoodsReceipt answer = service.post(TENANT, USER, STORE_ID.toString(), GRN.toString(),
                PharmacyInputs.Body.of(post(5, null)), null);

        assertThat(answer.outcome()).isEqualTo("POSTED");
        verifyNoInteractions(posting, numbers);
        verify(clients, never()).take(any(), any(), any(), any(), any(), any());
    }

    // ---- update, discard, reopen, approval ----

    @Test
    void anEditKeepsTheLinesByPositionAndFlagsTheOnesTakenOff() {
        Line one = plainLine();
        Line two = plainLine();
        postingStubs(row("DRAFT", 2, null), List.of(one, two), null);
        PharmacyInputs.GoodsReceiptUpdate in = new PharmacyInputs.GoodsReceiptUpdate(new BigDecimal("2"), VENDOR, "INV-1",
                TODAY.minusDays(3), new BigDecimal("30"), new BigDecimal("78400"),
                List.of(input(PRODUCT, null, "10", "10", "70.00", "2028-03")));

        service.update(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(in));

        verify(receipts).updateLine(eq(TENANT), eq(one.id()), any(), eq(ME));
        verify(receipts).removeLine(TENANT, two.id(), ME);
        verify(receipts, never()).insertLine(any(), any(), any(), any(), any());
        verify(receipts).updateDraft(TENANT, GRN, 2L, VENDOR, "INV-1", TODAY.minusDays(3), 30, 78_400L, ME);
        verify(numbers, never()).next(any(), any());
    }

    @Test
    void aSubmittedReceiptCannotBeEdited() {
        postingStubs(row("WAITING_APPROVAL", 2, null), List.of(plainLine()), null);
        PharmacyInputs.GoodsReceiptUpdate in = new PharmacyInputs.GoodsReceiptUpdate(new BigDecimal("2"), VENDOR, "INV-1",
                TODAY.minusDays(3), new BigDecimal("30"), new BigDecimal("78400"),
                List.of(input(PRODUCT, null, "10", "10", "70.00", "2028-03")));

        assertThatThrownBy(() -> service.update(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void discardingAnApprovedReceiptGoesThroughDraftBecauseTheGuardAllowsNothingElse() {
        postingStubs(row("APPROVED", 3, null), List.of(plainLine()), null);

        service.discard(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionInput(new BigDecimal("3"))));

        InOrder order = Mockito.inOrder(receipts);
        order.verify(receipts).setStatus(TENANT, GRN, 3L, "DRAFT", ME);
        order.verify(receipts).setStatus(TENANT, GRN, 4L, "DISCARDED", ME);
    }

    @Test
    void aPostedReceiptCannotBeDiscardedOrReopened() {
        postingStubs(row("POSTED", 3, null), List.of(plainLine()), null);
        PharmacyInputs.VersionInput in = new PharmacyInputs.VersionInput(new BigDecimal("3"));

        assertThatThrownBy(() -> service.discard(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        assertThatThrownBy(() -> service.reopen(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void reopeningAnApprovedReceiptMakesItADraftAgain() {
        postingStubs(row("APPROVED", 3, null), List.of(plainLine()), null);

        service.reopen(TENANT, USER, STORE_ID.toString(), GRN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionInput(new BigDecimal("3"))));

        verify(receipts).setStatus(TENANT, GRN, 3L, "DRAFT", ME);
    }

    @Test
    void theFinalApprovalMakesTheReceiptApprovedAndARejectionReturnsItToDraft() {
        PharmacyCaller approver = new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Owner", "OWNER", List.of());
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.GRN_RATE_RISE,
                "GOODS_RECEIPT", GRN, null, STORE_ID, ME, NOW, UUID.randomUUID(), 74_300L, false, false, false,
                new BigDecimal("6.1"), null, 1, NOW, "PENDING", 0L);
        when(receipts.findForUpdateById(TENANT, GRN)).thenReturn(Optional.of(row("WAITING_APPROVAL", 3, null)));

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null));
        verify(receipts).setStatusLocked(TENANT, GRN, "WAITING_APPROVAL", "APPROVED", approver.actorId());

        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null));
        verify(receipts).setStatusLocked(TENANT, GRN, "WAITING_APPROVAL", "DRAFT", approver.actorId());
        verifyNoInteractions(posting, numbers);
        assertThat(service.subjectType()).isEqualTo("GOODS_RECEIPT");
    }

    @Test
    void theInboxRowShowsTheRisingLineWithItsOldAndNewRateAndPercent() {
        BigDecimal net = new BigDecimal("74.30");
        Line rising = new Line(UUID.randomUUID(), PO_LINE, PRODUCT, 1, "B1", EXPIRY, 10, 10, 2, net, new BigDecimal("8.00"),
                BigDecimal.ZERO, new BigDecimal("12"), 74_300L, 8_916L, net, new BigDecimal("70.00"), "PO", new BigDecimal("6.1"),
                0, null, false, null, null, null);
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.GRN_RATE_RISE,
                "GOODS_RECEIPT", GRN, null, STORE_ID, ME, NOW, UUID.randomUUID(), 74_300L, false, false, false,
                new BigDecimal("6.1"), null, 1, NOW, "PENDING", 0L);
        when(receipts.findById(TENANT, GRN)).thenReturn(Optional.of(row("WAITING_APPROVAL", 3, null)));
        when(receipts.lines(TENANT, GRN)).thenReturn(List.of(rising));

        ApprovalSubjectHandler.Summary summary = service.summary(TENANT, request);

        assertThat(summary.what()).contains("Test Paracetamol 500 mg (fake) · ₹ 70.00 → ₹ 74.30 (+6.1%)");
        assertThat(summary.chip()).isEqualTo("RATE_INCREASE");
        assertThat(summary.subjectStoreId()).isEqualTo(STORE_ID);
    }

    @Test
    void theListNeverShowsDiscardedAndReadsTheCentralStoreOnly() {
        PurchaseViews.GrnPage page = service.list(TENANT, USER, STORE_ID.toString(), "DISCARDED", null, null);
        assertThat(page.items()).isEmpty();
        verify(receipts, never()).list(any(), any(), any(), anyInt(), anyInt());

        store = new Store(STORE_ID, UNIT, "Test Ward Store", "W1", StoreType.WARD, UUID.randomUUID(), false, false, true, true, 0);
        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), null, null, null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }
}
