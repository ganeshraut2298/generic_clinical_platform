package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.DebitNoteRepository.Line;
import com.sahaj.pharmacy.application.DebitNoteRepository.NewLine;
import com.sahaj.pharmacy.application.DebitNoteRepository.Row;
import com.sahaj.pharmacy.application.DebitNoteRepository.StockCandidate;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * SAH-79-8 (docs/api/pharmacy.yaml 0.3.3): the vendor return and debit note rules over mocks. The eligible rows and their
 * starting credit, the line checks, the hold at submit (level 0 at once, otherwise at the approval), the lock order, the
 * Schedule X hand-over, the credit note applied to the payables, the cancel that releases the units, and that the actor stored
 * is the membership id and never the user id (security L13).
 */
class DebitNoteServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID VENDOR = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID PRODUCT_X = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID BATCH_2 = UUID.randomUUID();
    private static final UUID DN = UUID.randomUUID();
    private static final UUID GRN = UUID.randomUUID();
    private static final UUID GRN_LINE = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate SOON = TODAY.plusDays(45);
    private static final LocalDate GONE = TODAY.minusDays(10);

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final VendorRepository vendors = mock(VendorRepository.class);
    private final GoodsReceiptRepository receipts = mock(GoodsReceiptRepository.class);
    private final DebitNoteRepository notes = mock(DebitNoteRepository.class);
    private final VendorPayableRepository payables = mock(VendorPayableRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final PrintTemplateRepository templates = mock(PrintTemplateRepository.class);
    private final UnitRepository unitRows = mock(UnitRepository.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final DocumentViews views = new DocumentViews(events, stores, team, engine);
    private final DebitNoteService service = new DebitNoteService(gate, guard, stores, products, vendors, receipts, notes,
            payables, engine, approvals, series, numbers, clients, events, stock, posting, signature, reasons, views, templates,
            unitRows, audit, new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), new BlindCount(mock(StockAuditRepository.class)));
    private final PharmacyCaller pharmacist = new PharmacyCaller(TENANT, USER, ME, "Test Central Store Pharmacist", "PHARMACIST",
            List.of());
    private final PharmacyCaller clerk = new PharmacyCaller(TENANT, USER, ME, "Test Central Store Clerk", "STORE_STAFF",
            List.of());
    private Store store = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, true,
            0);
    /** Return policy: 90 days before expiry, 80 % standard, 100 % when damaged on receipt, 50 % after expiry. */
    private final Vendor vendor = new Vendor(VENDOR, "Test Vendor One", "27AAAAA0000A1Z5", true, "LIC-1",
            LocalDate.of(2027, 12, 31), null, null, 30, 90, new BigDecimal("80"), new BigDecimal("100"), new BigDecimal("50"),
            true, 0L);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(pharmacist);
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE))).thenAnswer(i -> store);
        when(stores.find(TENANT, STORE_ID)).thenAnswer(i -> Optional.of(store));
        when(stores.findForUpdate(TENANT, STORE_ID)).thenAnswer(i -> Optional.of(store));
        when(stores.list(TENANT, true)).thenAnswer(i -> List.of(store));
        when(vendors.find(TENANT, VENDOR)).thenReturn(Optional.of(vendor));
        when(products.find(TENANT, PRODUCT)).thenReturn(Optional.of(product(PRODUCT, "NONE")));
        when(products.find(TENANT, PRODUCT_X)).thenReturn(Optional.of(product(PRODUCT_X, "X")));
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.of(batch(BATCH, PRODUCT, SOON)));
        when(stock.findBatch(TENANT, BATCH_2)).thenReturn(Optional.of(batch(BATCH_2, PRODUCT_X, SOON)));
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(numbers.next(TENANT, DocType.DEBIT_NOTE)).thenReturn(9L);
        when(notes.submit(any(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(true);
        when(notes.updateDraft(any(), any(), anyLong(), any(), any(), anyLong(), anyLong(), any())).thenReturn(true);
        when(notes.markHandedOver(any(), any(), anyLong(), any(), any(), any())).thenReturn(true);
        when(notes.setStatus(any(), any(), anyLong(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.APPROVAL_REJECT, "approval-reject.not-needed")).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.VENDOR_RETURN, "vendor-return.expired")).thenReturn(true);
    }

    private ProductRepository.Full product(UUID id, String schedule) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test Paracetamol 500 mg (fake)",
                null, false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule,
                false, "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private static BatchRow batch(UUID id, UUID product, LocalDate expiry) {
        return new BatchRow(id, product, "B1", expiry, new BigDecimal("8.00"), new BigDecimal("5.0000"), VENDOR, NOW);
    }

    private static StockCandidate candidate(UUID batch, UUID product, String state, int qty, int reserved, LocalDate expiry,
                                            String causeTxn, String causeReason) {
        return new StockCandidate(product, batch, state, qty, reserved, "B1", expiry, new BigDecimal("8.00"),
                new BigDecimal("5.0000"), causeTxn, causeReason);
    }

    private Row row(String status, long version) {
        boolean numbered = !"DRAFT".equals(status);
        return new Row(DN, UNIT, STORE_ID, VENDOR, numbered ? 9L : null, numbered ? "DN-0009" : null, status, null, null, null,
                2_000L, 240L, null, null, null, null, null, null, null, NOW, ME, version);
    }

    private Line stockLine(UUID batch, UUID product, String state, int qty) {
        return new Line(UUID.randomUUID(), "STOCK_RETURN", product, 1, batch, state, null, qty, new BigDecimal("5.0000"),
                new BigDecimal("80"), false, 2_000L, new BigDecimal("12"), 240L);
    }

    private Line shortLine() {
        return new Line(UUID.randomUUID(), "SHORT_SUPPLY", PRODUCT, 2, null, null, GRN_LINE, 20, new BigDecimal("7.0000"),
                new BigDecimal("80"), false, 11_200L, new BigDecimal("12"), 1_344L);
    }

    private PharmacyInputs.DebitNoteLineInput stockInput(UUID batch, String state, String qty, String percent) {
        return new PharmacyInputs.DebitNoteLineInput("STOCK_RETURN", batch, state, null, new BigDecimal(qty), new BigDecimal(percent));
    }

    private PharmacyInputs.DebitNoteCreate create(PharmacyInputs.DebitNoteLineInput... lines) {
        return new PharmacyInputs.DebitNoteCreate(KEY, VENDOR, null, null, List.of(lines));
    }

    private void found(Row row, List<Line> lines) {
        when(notes.find(TENANT, STORE_ID, DN)).thenReturn(Optional.of(row));
        when(notes.findForUpdate(TENANT, STORE_ID, DN)).thenReturn(Optional.of(row));
        when(notes.lines(TENANT, DN)).thenReturn(lines);
    }

    private void offered(StockCandidate... candidates) {
        when(notes.stockCandidates(eq(TENANT), eq(STORE_ID), eq(VENDOR), eq(TODAY), eq(90), any())).thenReturn(List.of(candidates));
    }

    // ---- eligible ----

    @Test
    void theStoreGuardAnswersBeforeAnyQueryValueIsParsedOnTheListAndOnTheEligibleRead() {
        when(guard.require(any(), eq(STORE_ID), eq(StoreCapability.PURCHASE))).thenThrow(new NotFoundException("This record was not found."));

        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), "NOT_A_STATUS", "not-a-uuid", "abc", "-5"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.eligible(TENANT, USER, STORE_ID.toString(), "not-a-uuid"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.eligible(TENANT, USER, STORE_ID.toString(), null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theEligibleListSweepsExpiredStockFirstAndWorksTheStartingCreditOfEachRow() {
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 30, 0, GONE, "EXPIRY_QUARANTINE", "damage.expired"),
                candidate(BATCH_2, PRODUCT, "QUARANTINE", 20, 5, SOON, "DAMAGE_QUARANTINE", "damage.broken"),
                candidate(UUID.randomUUID(), PRODUCT, "QUARANTINE", 10, 10, SOON, null, null),
                candidate(UUID.randomUUID(), PRODUCT, "QUARANTINE", 4, 0, SOON, "INDENT_RECEIPT_REJECTED", null),
                candidate(UUID.randomUUID(), PRODUCT, "AVAILABLE", 12, 0, SOON, null, null),
                candidate(UUID.randomUUID(), PRODUCT, "QUARANTINE", 3, 0, TODAY.plusDays(400), "DAMAGE_QUARANTINE", "damage.broken"));
        when(receipts.shortLinesOfVendor(TENANT, VENDOR, null)).thenReturn(List.of(new GoodsReceiptRepository.ShortLine(GRN_LINE,
                GRN, "GRN-0007", PRODUCT, BATCH, 3, 10, new BigDecimal("70.00"), 10)));

        PurchaseViews.VendorReturnEligible out = service.eligible(TENANT, USER, STORE_ID.toString(), VENDOR.toString());

        verify(posting).sweepStore(TENANT, UNIT, STORE_ID, ME);
        assertThat(out.policy().returnDaysBeforeExpiry()).isEqualTo(90);
        assertThat(out.policy().creditPercent()).isEqualTo("80");
        assertThat(out.items()).extracting(PurchaseViews.EligibleRow::source).containsExactly("QUARANTINE_EXPIRED",
                "QUARANTINE_DAMAGED", "QUARANTINE_INDENT_REJECTION", "NEAR_EXPIRY", "QUARANTINE_DAMAGED", "GRN_SHORT");
        // expired: the after-expiry level; damaged: the damaged level; the others: the standard credit
        assertThat(out.items()).extracting(PurchaseViews.EligibleRow::defaultCreditPercent).containsExactly("50", "100", "80",
                "80", "100", "80");
        // units already named by another note waiting for approval are left out, a row with none left is not listed
        assertThat(out.items().get(1).availableQty()).isEqualTo(15);
        assertThat(out.items()).extracting(PurchaseViews.EligibleRow::availableQty).doesNotContain(0);
        assertThat(out.items().get(3).daysToExpiry()).isEqualTo(45);
        PurchaseViews.EligibleRow shortRow = out.items().get(5);
        assertThat(shortRow.receiptNo()).isEqualTo("GRN-0007");
        assertThat(shortRow.goodsReceiptLineId()).isEqualTo(GRN_LINE);
        assertThat(shortRow.sourceState()).isNull();
        assertThat(shortRow.rate()).isEqualTo("7.00");
        // 3 packs billed and not received x 10 tablets, 10 already on a note
        assertThat(shortRow.availableQty()).isEqualTo(20);
        assertThat(out.items().get(0).product().gstPercent()).isEqualTo("12");
    }

    @Test
    void aBatchBothDamagedAndExpiredStartsFromTheLowerLevelAndAnUnsetLevelCountsAsTheStandardCredit() {
        Vendor partial = new Vendor(VENDOR, "Test Vendor One", null, false, null, null, null, null, 30, 90, new BigDecimal("80"),
                null, new BigDecimal("60"), true, 0L);
        when(vendors.find(TENANT, VENDOR)).thenReturn(Optional.of(partial));
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 30, 0, GONE, "DAMAGE_QUARANTINE", "damage.broken"));

        PurchaseViews.VendorReturnEligible out = service.eligible(TENANT, USER, STORE_ID.toString(), VENDOR.toString());

        // the damaged level is not set, so it counts as the standard 80; the lower of 80 and 60 is 60
        assertThat(out.items().get(0).defaultCreditPercent()).isEqualTo("60");
    }

    @Test
    void aVendorWithNoStandardCreditAndNoLevelHasNoStartingCreditForARow() {
        Vendor none = new Vendor(VENDOR, "Test Vendor One", null, false, null, null, null, null, 30, 90, null, null, null, true, 0L);
        when(vendors.find(TENANT, VENDOR)).thenReturn(Optional.of(none));
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 30, 0, SOON, null, null));

        assertThat(service.eligible(TENANT, USER, STORE_ID.toString(), VENDOR.toString()).items().get(0).defaultCreditPercent())
                .isNull();
    }

    @Test
    void theEligibleListNeedsAVendorAndOnlyTheCentralStoreHasOne() {
        assertThatThrownBy(() -> service.eligible(TENANT, USER, STORE_ID.toString(), null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.eligible(TENANT, USER, STORE_ID.toString(), UUID.randomUUID().toString()))
                .isInstanceOf(NotFoundException.class);
        store = new Store(STORE_ID, UNIT, "Test Ward Store", "W1", StoreType.WARD, UUID.randomUUID(), false, false, true, true, 0);
        assertThatThrownBy(() -> service.eligible(TENANT, USER, STORE_ID.toString(), VENDOR.toString()))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verifyNoInteractions(posting);
    }

    // ---- create and update ----

    @Test
    void aDraftIsWrittenWithTheCreditAndTheGstReversalOfEachLineAndAChangedPercentIsMarked() {
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 20, 5, SOON, "DAMAGE_QUARANTINE", "damage.broken"));
        when(notes.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0)));
        when(notes.lines(eq(TENANT), any())).thenReturn(List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));

        service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(create(stockInput(BATCH, "QUARANTINE", "10", "90"))));

        ArgumentCaptor<NewLine> line = ArgumentCaptor.forClass(NewLine.class);
        InOrder order = Mockito.inOrder(clients, notes);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyCreateDebitNote"), any());
        order.verify(notes).insert(eq(TENANT), eq(UNIT), any(), eq(STORE_ID), eq(VENDOR), eq(null), eq(null), eq(null), eq(ME));
        order.verify(notes).insertLine(eq(TENANT), eq(UNIT), any(), line.capture(), eq(ME));
        order.verify(notes).updateDraft(eq(TENANT), any(), eq(0L), eq(null), eq(null), eq(4_500L), eq(540L), eq(ME));
        // 10 x 5.0000 x 90 % = 45.00 rupees; GST reversal 12 % of that is 5.40; the damaged level 100 was the start
        assertThat(line.getValue().creditPaise()).isEqualTo(4_500L);
        assertThat(line.getValue().gstReversalPaise()).isEqualTo(540L);
        assertThat(line.getValue().creditPercentChanged()).isTrue();
        assertThat(line.getValue().sourceState()).isEqualTo("QUARANTINE");
        assertThat(line.getValue().goodsReceiptLineId()).isNull();
        verify(numbers, never()).next(any(), any());
        verify(posting, never()).post(any(), anyList());
    }

    @Test
    void aQuantityAboveWhatIsAvailableAndAnItemNotOnTheListAreRefusedTogether() {
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 20, 5, SOON, null, null));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(create(
                stockInput(BATCH, "QUARANTINE", "16", "80"), stockInput(BATCH_2, "QUARANTINE", "1", "80"),
                stockInput(BATCH, "QUARANTINE", "1", "100.01")))))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.errors()).extracting(InvalidFieldsException.FieldMessage::field).contains("lines[0].qty",
                            "lines[1].batchId", "lines[2].creditPercent", "lines[2]");
                    assertThat(e.errors()).filteredOn(m -> m.field().equals("lines[0].qty")).extracting(m -> m.message())
                            .containsExactly("Enter 1 to 15");
                });
        verify(notes, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aShortSupplyLineNamesTheReceiptLineAndIsLimitedToWhatIsNotYetClaimed() {
        offered();
        when(receipts.shortLinesOfVendor(TENANT, VENDOR, null)).thenReturn(List.of(new GoodsReceiptRepository.ShortLine(GRN_LINE,
                GRN, "GRN-0007", PRODUCT, BATCH, 3, 10, new BigDecimal("70.00"), 10)));
        when(notes.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0)));
        when(notes.lines(eq(TENANT), any())).thenReturn(List.of(shortLine()));
        PharmacyInputs.DebitNoteLineInput tooMany = new PharmacyInputs.DebitNoteLineInput("SHORT_SUPPLY", null, null, GRN_LINE,
                new BigDecimal("21"), new BigDecimal("100"));
        PharmacyInputs.DebitNoteLineInput ok = new PharmacyInputs.DebitNoteLineInput("SHORT_SUPPLY", null, null, GRN_LINE,
                new BigDecimal("20"), new BigDecimal("100"));

        assertThatThrownBy(() -> service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(create(tooMany))))
                .isInstanceOf(InvalidFieldsException.class);
        service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(create(ok)));

        ArgumentCaptor<NewLine> line = ArgumentCaptor.forClass(NewLine.class);
        verify(notes).insertLine(eq(TENANT), eq(UNIT), any(), line.capture(), eq(ME));
        assertThat(line.getValue().lineType()).isEqualTo("SHORT_SUPPLY");
        assertThat(line.getValue().batchId()).isNull();
        assertThat(line.getValue().sourceState()).isNull();
        // 20 x 7.0000 x 100 % = 140.00; the standard credit 80 was the start
        assertThat(line.getValue().creditPaise()).isEqualTo(14_000L);
        assertThat(line.getValue().creditPercentChanged()).isTrue();
    }

    @Test
    void aShortSupplyOfWholeStripsIsCreditedFromTheInvoiceRatePerStripNotFromTheRoundedRatePerTablet() {
        offered();
        // 15 tablets per strip, 1,000 strips billed and not received, Rs 100.00 a strip
        when(receipts.shortLinesOfVendor(TENANT, VENDOR, null)).thenReturn(List.of(new GoodsReceiptRepository.ShortLine(GRN_LINE,
                GRN, "GRN-0007", PRODUCT, BATCH, 1_000, 0, new BigDecimal("100.00"), 15)));
        when(notes.find(eq(TENANT), eq(STORE_ID), any())).thenAnswer(i -> Optional.of(row("DRAFT", 0)));
        when(notes.lines(eq(TENANT), any())).thenReturn(List.of(shortLine()));
        PharmacyInputs.DebitNoteLineInput all = new PharmacyInputs.DebitNoteLineInput("SHORT_SUPPLY", null, null, GRN_LINE,
                new BigDecimal("15000"), new BigDecimal("100"));

        service.create(TENANT, USER, STORE_ID.toString(), PharmacyInputs.Body.of(create(all)));

        ArgumentCaptor<NewLine> line = ArgumentCaptor.forClass(NewLine.class);
        verify(notes).insertLine(eq(TENANT), eq(UNIT), any(), line.capture(), eq(ME));
        assertThat(line.getValue().rate()).isEqualByComparingTo("6.6667");
        assertThat(line.getValue().creditPaise()).isEqualTo(10_000_000L);
    }

    @Test
    void anUpdateExcludesTheNoteItselfFromWhatIsReservedAndReplacesTheLinesByPosition() {
        Row draft = row("DRAFT", 2);
        Line old = stockLine(BATCH, PRODUCT, "QUARANTINE", 10);
        found(draft, List.of(old, shortLine()));
        when(notes.stockCandidates(eq(TENANT), eq(STORE_ID), eq(VENDOR), eq(TODAY), eq(90), eq(DN)))
                .thenReturn(List.of(candidate(BATCH, PRODUCT, "QUARANTINE", 20, 0, SOON, null, null)));
        when(receipts.shortLinesOfVendor(TENANT, VENDOR, DN)).thenReturn(List.of());
        PharmacyInputs.DebitNoteUpdate in = new PharmacyInputs.DebitNoteUpdate(new BigDecimal("2"), null, null,
                List.of(stockInput(BATCH, "QUARANTINE", "20", "80")));

        service.update(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in));

        verify(notes).updateLine(eq(TENANT), eq(old.id()), any(), eq(ME));
        verify(notes).removeLine(eq(TENANT), any(), eq(ME));
        verify(notes).updateDraft(eq(TENANT), eq(DN), eq(2L), eq(null), eq(null), eq(8_000L), eq(960L), eq(ME));
    }

    @Test
    void aPreparedDraftKeepsItsRateDifferenceLineThatIsNoLongerOnTheEligibleList() {
        Row draft = row("DRAFT", 2);
        Line own = shortLine();
        found(draft, List.of(own));
        offered();
        when(receipts.shortLinesOfVendor(TENANT, VENDOR, DN)).thenReturn(List.of());
        PharmacyInputs.DebitNoteUpdate in = new PharmacyInputs.DebitNoteUpdate(new BigDecimal("2"), null, null,
                List.of(new PharmacyInputs.DebitNoteLineInput("SHORT_SUPPLY", null, null, GRN_LINE, new BigDecimal("20"),
                        new BigDecimal("80"))));
        when(notes.stockCandidates(eq(TENANT), eq(STORE_ID), eq(VENDOR), eq(TODAY), eq(90), eq(DN))).thenReturn(List.of());

        service.update(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in));

        verify(notes).updateLine(eq(TENANT), eq(own.id()), any(), eq(ME));
        verify(notes).updateDraft(eq(TENANT), eq(DN), eq(2L), eq(null), eq(null), eq(11_200L), eq(1_344L), eq(ME));
    }

    @Test
    void aSubmittedNoteCannotBeEditedOrDiscardedAndADraftCanBeDiscarded() {
        found(row("CREATED", 3), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        PharmacyInputs.VersionInput v3 = new PharmacyInputs.VersionInput(new BigDecimal("3"));
        assertThatThrownBy(() -> service.discard(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(v3)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));

        found(row("DRAFT", 3), List.of(shortLine()));
        service.discard(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(v3));
        verify(notes).setStatus(TENANT, DN, 3L, "DISCARDED", ME);
    }

    // ---- submit and the approval ----

    @Test
    void aSubmitWithNoRuleTakesTheLocksInOrderNumbersTheNoteAndHoldsTheUnitsAtOnce() {
        Line line = stockLine(BATCH, PRODUCT, "QUARANTINE", 10);
        found(row("DRAFT", 4), List.of(line));
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 10, 0, SOON, null, null));

        service.submit(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, new BigDecimal("4"))));

        InOrder order = Mockito.inOrder(clients, stores, notes, numbers, posting, events);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacySubmitDebitNote"), eq(DN));
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(notes).findForUpdate(TENANT, STORE_ID, DN);
        order.verify(numbers).next(TENANT, DocType.DEBIT_NOTE);
        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        order.verify(posting).post(actor.capture(), moves.capture());
        order.verify(notes).submit(TENANT, DN, 4L, "CREATED", 9L, "DN-0009", ME);
        // L11: the store row is locked once, before the number
        verify(stores, times(1)).findForUpdate(any(), any());
        assertThat(actor.getValue().membershipId()).isEqualTo(ME).isNotEqualTo(USER);
        assertThat(actor.getValue().secondSignerMembershipId()).isNull();
        assertThat(moves.getValue()).hasSize(2);
        StockPosting.Line out = moves.getValue().get(0);
        StockPosting.Line in = moves.getValue().get(1);
        assertThat(out.txnType()).isEqualTo("VENDOR_RETURN_HOLD");
        assertThat(out.state()).isEqualTo(StockState.QUARANTINE);
        assertThat(out.delta()).isEqualTo(-10);
        assertThat(in.state()).isEqualTo(StockState.PENDING_RETURN);
        assertThat(in.delta()).isEqualTo(10);
        assertThat(in.movementId()).isEqualTo(out.movementId());
        assertThat(in.secondSignerMembershipId()).isNull();
        assertThat(out.doc().lineId()).isEqualTo(line.id());
        verify(events).insert(TENANT, UNIT, "DEBIT_NOTE", DN, "ISSUED", STORE_ID, ME, null, null, null, null, null);
        verify(engine, never()).submit(any(), any(), any(), any(), any());
    }

    @Test
    void aSubmitThatTheRuleHoldsForApprovalHoldsNothingUntilTheApprovalAndWaitsForTheRaisersRequest() {
        found(row("DRAFT", 4), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 10, 0, SOON, null, null));
        ApprovalEngine.Plan plan = new ApprovalEngine.Plan(null, List.of(), 1, null, false);
        ArgumentCaptor<ApprovalEngine.Subject> subject = ArgumentCaptor.forClass(ApprovalEngine.Subject.class);
        when(engine.plan(eq(TENANT), subject.capture())).thenReturn(plan);
        UUID request = UUID.randomUUID();
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(request);

        service.submit(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, new BigDecimal("4"))));

        assertThat(subject.getValue().type()).isEqualTo(TransactionType.VENDOR_RETURN);
        assertThat(subject.getValue().subjectType()).isEqualTo("DEBIT_NOTE");
        assertThat(subject.getValue().subjectNo()).isEqualTo("DN-0009");
        assertThat(subject.getValue().amountPaise()).isEqualTo(2_000L);
        assertThat(subject.getValue().ruleStoreId()).isEqualTo(STORE_ID);
        verify(notes).submit(TENANT, DN, 4L, "WAITING_APPROVAL", 9L, "DN-0009", ME);
        verify(notes).attachRequest(TENANT, DN, request, ME);
        verify(posting, never()).post(any(), anyList());
    }

    @Test
    void aUnitThatIsGoneAtSubmitIsInsufficientStockNamingTheLineAndNothingIsNumberedOrHeld() {
        Line line = stockLine(BATCH, PRODUCT, "QUARANTINE", 10);
        found(row("DRAFT", 4), List.of(line));
        offered(candidate(BATCH, PRODUCT, "QUARANTINE", 6, 0, SOON, null, null));

        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, new BigDecimal("4")))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("insufficient-stock");
                    assertThat(e.extensions().get("lines").toString()).contains(line.id().toString());
                });
        verifyNoInteractions(numbers, posting);
    }

    @Test
    void aStoreSwitchedOffAfterTheGuardIsNotFoundAtSubmitAndAtHandOverAndAtCancel() {
        found(row("DRAFT", 4), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        Store off = new Store(STORE_ID, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, false, true, false, 1);
        when(stores.findForUpdate(TENANT, STORE_ID)).thenReturn(Optional.of(off));

        assertThatThrownBy(() -> service.submit(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionClientKeyInput(KEY, new BigDecimal("4"))))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.HandOver(KEY, new BigDecimal("4"), "Test Rep", null)), null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("4"), "approval-reject.not-needed", null))))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(numbers, posting);
        verify(notes, never()).findForUpdate(any(), any(), any());
    }

    @Test
    void theFinalApprovalHoldsTheUnitsWithTheRaiserAsActorAndTheApproverRecordedAndAnUnavailableUnitRollsItBack() {
        UUID raiser = UUID.randomUUID();
        PharmacyCaller approver = new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Owner", "OWNER", List.of());
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.VENDOR_RETURN,
                "DEBIT_NOTE", DN, "DN-0009", STORE_ID, raiser, NOW, UUID.randomUUID(), 2_000L, false, false, false, null, null, 1,
                NOW, "PENDING", 0L);
        Line line = stockLine(BATCH, PRODUCT, "AVAILABLE", 10);
        when(notes.findForUpdateById(TENANT, DN)).thenReturn(Optional.of(row("WAITING_APPROVAL", 5)));
        when(notes.lines(TENANT, DN)).thenReturn(List.of(line));

        service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null));

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(actor.capture(), moves.capture());
        assertThat(actor.getValue().membershipId()).isEqualTo(raiser);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(approver.actorId());
        assertThat(moves.getValue().get(0).state()).isEqualTo(StockState.AVAILABLE);
        verify(notes).setStatusLocked(TENANT, DN, "WAITING_APPROVAL", "CREATED", approver.actorId());
        verify(stores, never()).findForUpdate(any(), any());

        doThrow(PharmacyProblemException.insufficientStock(0, List.of())).when(posting).post(any(), anyList());
        assertThatThrownBy(() -> service.onApproved(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("insufficient-stock"));
        verify(notes, times(1)).setStatusLocked(any(), any(), any(), any(), any());
    }

    @Test
    void aRejectionMakesTheNoteRejectedAndHoldsNothing() {
        PharmacyCaller approver = new PharmacyCaller(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Test Owner", "OWNER", List.of());
        ApprovalRepository.Request request = new ApprovalRepository.Request(UUID.randomUUID(), UNIT, TransactionType.VENDOR_RETURN,
                "DEBIT_NOTE", DN, "DN-0009", STORE_ID, ME, NOW, UUID.randomUUID(), 2_000L, false, false, false, null, null, 1, NOW,
                "PENDING", 0L);
        when(notes.findForUpdateById(TENANT, DN)).thenReturn(Optional.of(row("WAITING_APPROVAL", 5)));

        service.onRejected(new ApprovalSubjectHandler.Decision(TENANT, approver, request, "OWNER", null));

        verify(notes).setStatusLocked(TENANT, DN, "WAITING_APPROVAL", "REJECTED", approver.actorId());
        verifyNoInteractions(posting);
        assertThat(service.subjectType()).isEqualTo("DEBIT_NOTE");
    }

    // ---- picked, hand-over ----

    @Test
    void pickedIsOnlyForACreatedNoteAndMovesNoStock() {
        found(row("CREATED", 5), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        PharmacyInputs.VersionInput v5 = new PharmacyInputs.VersionInput(new BigDecimal("5"));

        service.picked(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(v5));
        verify(notes).markPicked(TENANT, DN, 5L, ME);
        verifyNoInteractions(posting);

        found(row("PICKED", 5), List.of());
        assertThatThrownBy(() -> service.picked(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(v5)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }

    @Test
    void aHandOverTakesTheHeldUnitsOutOfPendingReturnAsVendorReturnAndKeepsTheNameOutOfTheLogs() {
        Line line = stockLine(BATCH, PRODUCT, "QUARANTINE", 10);
        found(row("PICKED", 6), List.of(line));
        PharmacyInputs.HandOver in = new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "  Test Rep  ", null);

        service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in), null);

        InOrder order = Mockito.inOrder(clients, stores, notes, posting);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyHandOverDebitNote"), eq(DN));
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(notes).findForUpdate(TENANT, STORE_ID, DN);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        order.verify(posting).post(any(), moves.capture());
        order.verify(notes).markHandedOver(TENANT, DN, 6L, "Test Rep", ME, null);
        assertThat(moves.getValue()).hasSize(1);
        assertThat(moves.getValue().get(0).txnType()).isEqualTo("VENDOR_RETURN");
        assertThat(moves.getValue().get(0).state()).isEqualTo(StockState.PENDING_RETURN);
        assertThat(moves.getValue().get(0).delta()).isEqualTo(-10);
        assertThat(in.toString()).doesNotContain("Test Rep");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("PHARMACY_DEBIT_NOTE_HANDED_OVER"), any(), eq(DN), any(), after.capture());
        assertThat(after.getValue()).containsEntry("unitsOut", 10);
    }

    @Test
    void aScheduleXHandOverNeedsAPharmacistAndASecondSignerWhoIsOnTheXLineOnly() {
        Line x = stockLine(BATCH_2, PRODUCT_X, "QUARANTINE", 5);
        Line plain = stockLine(BATCH, PRODUCT, "QUARANTINE", 10);
        found(row("CREATED", 6), List.of(x, plain));
        PharmacyInputs.HandOver none = new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "Test Rep", null);

        assertThatThrownBy(() -> service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(none),
                null)).isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(InvalidFieldsException.FieldMessage::field).containsExactly("secondSigner"));
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(clerk);
        assertThatThrownBy(() -> service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(none),
                SIGNER)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(posting);

        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(pharmacist);
        service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(none), SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue()).extracting(StockPosting.Line::secondSignerMembershipId).containsExactly(SIGNER, null);
        verify(notes).markHandedOver(TENANT, DN, 6L, "Test Rep", ME, SIGNER);
    }

    @Test
    void theSecondSignerToCheckIsAskedForOnlyForAScheduleXNoteAndAPharmacist() {
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        found(row("CREATED", 6), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        PharmacyInputs.HandOver in = new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "Test Rep", typed);
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in))).isNull();

        found(row("CREATED", 6), List.of(stockLine(BATCH_2, PRODUCT_X, "QUARANTINE", 5)));
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in))).isSameAs(typed);
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(clerk);
        assertThatThrownBy(() -> service.precheck(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void noSecondSignerIsAskedForANoteThatCannotBeHandedOverSoNoPinIsCounted() {
        PharmacyInputs.SecondSignerInput typed = new PharmacyInputs.SecondSignerInput(SIGNER, "1234");
        PharmacyInputs.HandOver in = new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "Test Rep", typed);
        for (String status : List.of("DRAFT", "WAITING_APPROVAL", "HANDED_OVER", "CREDIT_NOTE_RECEIVED", "DISCARDED")) {
            found(row(status, 6), List.of(stockLine(BATCH_2, PRODUCT_X, "QUARANTINE", 5)));
            assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in)))
                    .as(status).isNull();
        }
        found(row("PICKED", 6), List.of(stockLine(BATCH_2, PRODUCT_X, "QUARANTINE", 5)));
        assertThat(service.precheck(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in))).isSameAs(typed);
    }

    @Test
    void aNoteWithShortSupplyOnlyHasNothingToHandOverAndABlankNameIsRefused() {
        found(row("CREATED", 6), List.of(shortLine()));
        assertThatThrownBy(() -> service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "Test Rep", null)), null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        assertThatThrownBy(() -> service.handOver(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.HandOver(KEY, new BigDecimal("6"), "   ", null)), null))
                .isInstanceOf(InvalidFieldsException.class);
        verifyNoInteractions(posting);
    }

    // ---- credit note ----

    private VendorPayableRepository.Row payable(UUID id, UUID grn, long amount, long credited, LocalDate due) {
        return new VendorPayableRepository.Row(id, VENDOR, grn, "INV-" + id.toString().substring(0, 4), TODAY.minusDays(40), amount,
                credited, due, "OPEN", 0L);
    }

    @Test
    void aCreditNoteIsAppliedToTheNotesOwnReceiptFirstThenTheOldestDueAndTheRestStaysUnapplied() {
        Row handed = new Row(DN, UNIT, STORE_ID, VENDOR, 9L, "DN-0009", "HANDED_OVER", GRN, null, null, 10_000L, 1_200L, null, null,
                null, "Test Rep", NOW, ME, null, NOW, ME, 7L);
        found(handed, List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        VendorPayableRepository.Row oldest = payable(UUID.randomUUID(), UUID.randomUUID(), 5_000L, 0L, TODAY.minusDays(10));
        VendorPayableRepository.Row own = payable(UUID.randomUUID(), GRN, 4_000L, 1_000L, TODAY.plusDays(20));
        VendorPayableRepository.Row newest = payable(UUID.randomUUID(), UUID.randomUUID(), 2_000L, 0L, TODAY.plusDays(30));
        when(payables.lockOpenOfVendor(TENANT, VENDOR)).thenReturn(List.of(oldest, own, newest));
        PharmacyInputs.CreditNote in = new PharmacyInputs.CreditNote(KEY, new BigDecimal("7"), " CN-77 ", TODAY, new BigDecimal("9000"));

        service.recordCreditNote(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(in));

        InOrder order = Mockito.inOrder(clients, notes, payables);
        order.verify(clients).take(eq(TENANT), eq(UNIT), eq(ME), eq(KEY), eq("pharmacyRecordCreditNote"), eq(DN));
        order.verify(notes).findForUpdate(TENANT, STORE_ID, DN);
        order.verify(payables).lockOpenOfVendor(TENANT, VENDOR);
        // own receipt: 3,000 open; then the oldest due: 5,000 open takes the remaining 6,000 up to 5,000; then the next 1,000
        order.verify(payables).applyCredit(TENANT, UNIT, own, 3_000L, DN, ME);
        order.verify(payables).applyCredit(TENANT, UNIT, oldest, 5_000L, DN, ME);
        order.verify(payables).applyCredit(TENANT, UNIT, newest, 1_000L, DN, ME);
        // credit 10,000 + GST reversal 1,200 = 11,200 asked; 9,000 came: the difference is -2,200; all 9,000 was applied
        order.verify(notes).insertCredit(TENANT, UNIT, DN, "CN-77", TODAY, 9_000L, -2_200L, 9_000L, 0L, ME);
        order.verify(notes).setStatus(TENANT, DN, 7L, "CREDIT_NOTE_RECEIVED", ME);
        verifyNoInteractions(posting, numbers);
    }

    @Test
    void aCreditAboveWhatIsOpenStaysAsUnappliedCredit() {
        Row handed = new Row(DN, UNIT, STORE_ID, VENDOR, 9L, "DN-0009", "HANDED_OVER", null, null, null, 10_000L, 0L, null, null,
                null, "Test Rep", NOW, ME, null, NOW, ME, 7L);
        found(handed, List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        VendorPayableRepository.Row only = payable(UUID.randomUUID(), UUID.randomUUID(), 4_000L, 0L, TODAY);
        when(payables.lockOpenOfVendor(TENANT, VENDOR)).thenReturn(List.of(only));

        service.recordCreditNote(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.CreditNote(KEY, new BigDecimal("7"), "CN-78", TODAY, new BigDecimal("10000"))));

        verify(payables).applyCredit(TENANT, UNIT, only, 4_000L, DN, ME);
        verify(notes).insertCredit(TENANT, UNIT, DN, "CN-78", TODAY, 10_000L, 0L, 4_000L, 6_000L, ME);
    }

    @Test
    void aCreditNoteNeedsAHandedOverNoteOrAShortSupplyOnlyNoteAndItsFieldsAreChecked() {
        PharmacyInputs.CreditNote good = new PharmacyInputs.CreditNote(KEY, new BigDecimal("7"), "CN-1", TODAY, new BigDecimal("100"));
        found(row("CREATED", 7), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        assertThatThrownBy(() -> service.recordCreditNote(TENANT, USER, STORE_ID.toString(), DN.toString(),
                PharmacyInputs.Body.of(good))).isInstanceOfSatisfying(PharmacyProblemException.class,
                        e -> assertThat(e.slug()).isEqualTo("doc-state"));
        verifyNoInteractions(payables);

        found(row("CREATED", 7), List.of(shortLine()));
        service.recordCreditNote(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(good));
        verify(notes).setStatus(TENANT, DN, 7L, "CREDIT_NOTE_RECEIVED", ME);

        PharmacyInputs.CreditNote bad = new PharmacyInputs.CreditNote(KEY, new BigDecimal("7"), "", TODAY.plusDays(1),
                new BigDecimal("0"));
        assertThatThrownBy(() -> service.recordCreditNote(TENANT, USER, STORE_ID.toString(), DN.toString(),
                PharmacyInputs.Body.of(bad))).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(InvalidFieldsException.FieldMessage::field)
                                .containsExactly("creditNoteNo", "creditNoteDate", "amountPaise"));
    }

    // ---- cancel ----

    @Test
    void aCancelTakesTheStoreTheRequestAndTheHeaderInOrderAndReleasesTheUnitsToTheirSourceState() {
        Line line = stockLine(BATCH, PRODUCT, "AVAILABLE", 10);
        Row created = new Row(DN, UNIT, STORE_ID, VENDOR, 9L, "DN-0009", "CREATED", null, null, null, 2_000L, 240L,
                UUID.randomUUID(), null, null, null, null, null, null, NOW, ME, 5L);
        found(created, List.of(line));
        when(approvals.findForUpdate(TENANT, created.approvalRequestId())).thenReturn(Optional.empty());

        service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("5"), "approval-reject.not-needed", null)));

        InOrder order = Mockito.inOrder(stores, approvals, notes, posting);
        order.verify(stores).findForUpdate(TENANT, STORE_ID);
        order.verify(approvals).findForUpdate(TENANT, created.approvalRequestId());
        order.verify(notes).findForUpdate(TENANT, STORE_ID, DN);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        order.verify(posting).post(any(), moves.capture());
        order.verify(notes).setStatus(TENANT, DN, 5L, "CANCELLED", ME);
        assertThat(moves.getValue()).extracting(StockPosting.Line::txnType).containsOnly("VENDOR_RETURN_RELEASE");
        assertThat(moves.getValue().get(0).state()).isEqualTo(StockState.PENDING_RETURN);
        assertThat(moves.getValue().get(0).delta()).isEqualTo(-10);
        assertThat(moves.getValue().get(1).state()).isEqualTo(StockState.AVAILABLE);
        assertThat(moves.getValue().get(1).delta()).isEqualTo(10);
        assertThat(moves.getValue().get(1).movementId()).isEqualTo(moves.getValue().get(0).movementId());
    }

    @Test
    void aBatchThatHasExpiredSinceGoesBackToQuarantineInsteadOfAvailable() {
        Line line = stockLine(BATCH, PRODUCT, "AVAILABLE", 10);
        found(row("PICKED", 5), List.of(line));
        when(stock.findBatch(TENANT, BATCH)).thenReturn(Optional.of(batch(BATCH, PRODUCT, GONE)));

        service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("5"), "approval-reject.not-needed", null)));

        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue().get(1).state()).isEqualTo(StockState.QUARANTINE);
    }

    @Test
    void cancellingANoteThatWaitsWithdrawsTheRequestAndMovesNoStock() {
        UUID requestId = UUID.randomUUID();
        Row waiting = new Row(DN, UNIT, STORE_ID, VENDOR, 9L, "DN-0009", "WAITING_APPROVAL", null, null, null, 2_000L, 240L,
                requestId, null, null, null, null, null, null, NOW, ME, 5L);
        found(waiting, List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        ApprovalRepository.Request pending = new ApprovalRepository.Request(requestId, UNIT, TransactionType.VENDOR_RETURN,
                "DEBIT_NOTE", DN, "DN-0009", STORE_ID, ME, NOW, UUID.randomUUID(), 2_000L, false, false, false, null, null, 1, NOW,
                "PENDING", 3L);
        when(approvals.findForUpdate(TENANT, requestId)).thenReturn(Optional.of(pending));

        service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("5"), "approval-reject.not-needed", null)));

        verify(approvals).finish(TENANT, requestId, 3L, "WITHDRAWN", ME);
        verify(posting, never()).post(any(), anyList());
        verify(notes).setStatus(TENANT, DN, 5L, "CANCELLED", ME);
    }

    @Test
    void aNoteThatIsHandedOverCannotBeCancelledAndAReasonIsNeeded() {
        found(row("HANDED_OVER", 5), List.of(stockLine(BATCH, PRODUCT, "QUARANTINE", 10)));
        assertThatThrownBy(() -> service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("5"), "approval-reject.not-needed", null))))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
        assertThatThrownBy(() -> service.cancel(TENANT, USER, STORE_ID.toString(), DN.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.VersionReasonInput(new BigDecimal("5"), "approval-reject.nonsense", null))))
                .isInstanceOf(InvalidFieldsException.class);
        verifyNoInteractions(posting);
    }

    @Test
    void theListNeverShowsDiscardedAndOnlyTheCentralStoreHasIt() {
        assertThat(service.list(TENANT, USER, STORE_ID.toString(), "DISCARDED", null, null, null).items()).isEmpty();
        verify(notes, never()).list(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        store = new Store(STORE_ID, UNIT, "Test Ward Store", "W1", StoreType.WARD, UUID.randomUUID(), false, false, true, true, 0);
        assertThatThrownBy(() -> service.list(TENANT, USER, STORE_ID.toString(), null, null, null, null))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("doc-state"));
    }
}
