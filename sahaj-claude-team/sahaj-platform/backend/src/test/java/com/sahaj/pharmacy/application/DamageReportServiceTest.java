package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.application.DamageReportRepository.NewRow;
import com.sahaj.pharmacy.application.DamageReportRepository.Row;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
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

/**
 * SAH-79-14 (ADR-029 P12): a damage report quarantines at once and the WRITE_OFF rule decides; level 0 applies the wish, else the
 * report waits for the HOD, whose approval writes off or sends to the Central Store and whose rejection releases or keeps the units.
 */
class DamageReportServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID HOD = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final String REASON = "damage.broken";

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final DamageReportRepository reports = mock(DamageReportRepository.class);
    private final ReturnItemRepository returnItems = mock(ReturnItemRepository.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final DocumentSeriesRepository series = mock(DocumentSeriesRepository.class);
    private final DocumentNumberPort numbers = mock(DocumentNumberPort.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final StockAuditRepository auditRows = mock(StockAuditRepository.class);
    private final DamageReportService service = new DamageReportService(gate, guard, stores, products, stock, posting, reports,
            returnItems, engine, approvals, series, numbers, clients, events, reasons, signature, team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), new BlindCount(auditRows));
    private final Store ward = new Store(WARD, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, CENTRAL, false, true, true, true, 0);
    private final Store central = new Store(CENTRAL, UNIT, "Test Central Store", "CS", StoreType.CENTRAL, null, false, true, true,
            true, 0);
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of());
    private String schedule = "NONE";
    private LocalDate expiry = LocalDate.of(2028, 3, 31);

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(WARD), eq(StoreCapability.RETURN_DAMAGE))).thenReturn(ward);
        when(guard.require(any(), eq(CENTRAL), eq(StoreCapability.RETURN_DAMAGE))).thenReturn(central);
        when(reasons.isActive(TENANT, ReasonList.DAMAGE, REASON)).thenReturn(true);
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(stock.findBatch(TENANT, BATCH)).thenAnswer(i -> Optional.of(new BatchRow(BATCH, PRODUCT, "B1", expiry,
                new BigDecimal("10.00"), new BigDecimal("4.5000"), null, NOW)));
        when(stock.lockBalances(eq(TENANT), eq(WARD), any())).thenReturn(List.of(bal(StockState.AVAILABLE, 40),
                bal(StockState.QUARANTINE, 5)));
        when(products.find(TENANT, PRODUCT)).thenAnswer(i -> Optional.of(full()));
        when(stores.findForUpdate(TENANT, WARD)).thenReturn(Optional.of(ward));
        when(stores.find(TENANT, WARD)).thenReturn(Optional.of(ward));
        when(stores.list(TENANT, true)).thenReturn(List.of(ward, central));
        when(numbers.next(TENANT, DocType.DAMAGE_REPORT)).thenReturn(87L);
        when(team.findAll(any(), any())).thenReturn(Map.of());
        when(reports.find(eq(TENANT), eq(WARD), any())).thenAnswer(i -> Optional.of(saved("WRITTEN_OFF", "WRITE_OFF", "AVAILABLE")));
    }

    private static Bal bal(StockState state, int qty) {
        return new Bal(UUID.randomUUID(), WARD, PRODUCT, BATCH, state, qty, "B1", LocalDate.of(2028, 3, 31), NOW,
                new BigDecimal("10.00"), new BigDecimal("4.5000"));
    }

    private ProductRepository.Full full() {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, PRODUCT, "P-1", "Test Paracetamol 500 mg (fake)", null,
                false, "Paracetamol", "500 mg", "Tablet", "Strip", "Tablet", 10, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, true, 0, ME);
    }

    private Row saved(String status, String after, String from) {
        return new Row(REPORT, UNIT, 87, "DMG-0087", WARD, PRODUCT, BATCH, from, 6, REASON, null, after, status, null, null, null,
                null, NOW, ME, 0);
    }

    private PharmacyInputs.DamageReportCreate input(String from, String qty, String after) {
        return new PharmacyInputs.DamageReportCreate(KEY, BATCH, from, new BigDecimal(qty), REASON, null, after, null);
    }

    private PharmacyViews.Outcome<StoreUseViews.DamageReport> create(PharmacyInputs.DamageReportCreate in, UUID signer) {
        return service.create(TENANT, USER, WARD.toString(), PharmacyInputs.Body.of(in), signer);
    }

    @SuppressWarnings("unchecked")
    private List<StockPosting.Line> posted() {
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), lines.capture());
        return lines.getValue();
    }

    private ApprovalSubjectHandler.Decision decision(String decision, String outcome) {
        ApprovalRepository.Request request = mock(ApprovalRepository.Request.class);
        when(request.subjectId()).thenReturn(REPORT);
        PharmacyInputs.ApprovalDecisionInput in = new PharmacyInputs.ApprovalDecisionInput(KEY, BigDecimal.ONE, BigDecimal.ZERO,
                decision, null, null, null, outcome);
        PharmacyCaller hod = new PharmacyCaller(TENANT, UUID.randomUUID(), HOD, "Test HOD", "NURSE", List.of());
        return new ApprovalSubjectHandler.Decision(TENANT, hod, request, "HOD", in);
    }

    @Test
    void withNoApprovalLevelTheWishIsAppliedAtOnceAWriteOffAfterTheQuarantine() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        PharmacyViews.Outcome<StoreUseViews.DamageReport> out = create(input("AVAILABLE", "6", "WRITE_OFF"), null);

        assertThat(out.created()).isTrue();
        List<StockPosting.Line> lines = posted();
        assertThat(lines).extracting(StockPosting.Line::txnType).containsExactly("DAMAGE_QUARANTINE", "DAMAGE_QUARANTINE",
                "WRITE_OFF");
        assertThat(lines).extracting(StockPosting.Line::state).containsExactly(StockState.AVAILABLE, StockState.QUARANTINE,
                StockState.QUARANTINE);
        assertThat(lines).extracting(StockPosting.Line::delta).containsExactly(-6, 6, -6);
        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        verify(reports).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().status()).isEqualTo("WRITTEN_OFF");
        assertThat(row.getValue().decided()).isTrue();
        assertThat(row.getValue().docNo()).isEqualTo("DMG-0087");
        verify(engine, never()).submit(any(), any(), any(), any(), any());
        verify(returnItems, never()).insert(any(), any());
        verify(audit).record(eq("PHARMACY_DAMAGE_REPORTED"), any(), any(), any(), any());
    }

    @Test
    void withNoLevelASendToTheCentralStoreMakesAReturnItemAfterTheReport() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        create(input("AVAILABLE", "6", "SEND_TO_CS"), null);

        assertThat(posted()).extracting(StockPosting.Line::txnType).containsExactly("DAMAGE_QUARANTINE", "DAMAGE_QUARANTINE",
                "DAMAGE_SEND_TO_CS", "DAMAGE_SEND_TO_CS");
        ArgumentCaptor<ReturnItemRepository.NewItem> item = ArgumentCaptor.forClass(ReturnItemRepository.NewItem.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(reports, returnItems);
        order.verify(reports).insert(eq(TENANT), any());
        order.verify(returnItems).insert(eq(TENANT), item.capture());
        assertThat(item.getValue().itemType()).isEqualTo("DAMAGED");
        assertThat(item.getValue().damageReportId()).isNotNull();
        assertThat(item.getValue().acceptingStoreId()).isEqualTo(CENTRAL);
        assertThat(item.getValue().defaultPutBackInto()).isEqualTo("QUARANTINE");
    }

    @Test
    void whenALevelAppliesTheUnitsAreQuarantinedAtOnceAndTheReportWaitsForTheHod() {
        ApprovalEngine.Plan plan = mock(ApprovalEngine.Plan.class);
        when(engine.plan(eq(TENANT), any())).thenReturn(plan);
        UUID requestId = UUID.randomUUID();
        when(engine.submit(eq(TENANT), eq(UNIT), eq(ME), any(), eq(plan))).thenReturn(requestId);

        create(input("AVAILABLE", "6", "WRITE_OFF"), null);

        assertThat(posted()).extracting(StockPosting.Line::txnType).containsOnly("DAMAGE_QUARANTINE");
        ArgumentCaptor<NewRow> row = ArgumentCaptor.forClass(NewRow.class);
        verify(reports).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().status()).isEqualTo("WAITING_APPROVAL");
        assertThat(row.getValue().decided()).isFalse();
        verify(reports).attachRequest(eq(TENANT), eq(row.getValue().id()), eq(requestId), eq(ME));
    }

    @Test
    void unitsAlreadyInQuarantineAreReportedFromThereWithNoQuarantineMove() {
        when(engine.plan(eq(TENANT), any())).thenReturn(null);

        create(input("QUARANTINE", "5", "WRITE_OFF"), null);

        assertThat(posted()).extracting(StockPosting.Line::txnType).containsExactly("WRITE_OFF");
        assertThatThrownBy(() -> create(input("QUARANTINE", "6", "WRITE_OFF"), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("quantity:Enter a whole number from 1 to 5"));
    }

    @Test
    void aCallerBlindAtTheStoreOnAnAuditedProductIsToldNoNumberWhenTheQuantityIsTooMuch() {
        com.sahaj.pharmacy.domain.StoreRole role = com.sahaj.pharmacy.domain.StoreRole.STORE_USER;
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of(new PharmacyCaller.Mapping(ward, null,
                role, com.sahaj.pharmacy.domain.StoreRules.access("NURSE", role, false))));
        when(auditRows.openAuditProducts(TENANT, WARD)).thenReturn(java.util.Set.of(PRODUCT));

        assertThatThrownBy(() -> create(input("AVAILABLE", "41", "WRITE_OFF"), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message())
                        .containsExactly("quantity:This is more than the quantity in stock."));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theCentralStoreCanOnlyWriteOffAndAQuantityAboveWhatIsHeldIsRefused() {
        assertThatThrownBy(() -> service.create(TENANT, USER, CENTRAL.toString(), PharmacyInputs.Body.of(
                input("AVAILABLE", "6", "SEND_TO_CS")), null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("afterApproval"));
        assertThatThrownBy(() -> create(input("AVAILABLE", "41", "WRITE_OFF"), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly("quantity:Enter a whole number from 1 to 40"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aScheduleXBatchNeedsAPharmacistAndTheSignerOnTheQuarantineAndTheSendButNotTheWriteOff() {
        schedule = "X";
        when(engine.plan(eq(TENANT), any())).thenReturn(null);
        assertThatThrownBy(() -> create(input("AVAILABLE", "6", "SEND_TO_CS"), null)).isInstanceOfSatisfying(
                ForbiddenException.class, e -> assertThat(e.problemType()).isEqualTo("schedule-x-pharmacist"));

        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        create(input("AVAILABLE", "6", "SEND_TO_CS"), SIGNER);

        assertThat(posted()).extracting(StockPosting.Line::secondSignerMembershipId).containsOnly(SIGNER);
    }

    @Test
    void anApprovedReportAppliesTheWishWithTheApproverOnTheLedger() {
        when(reports.findForUpdate(TENANT, REPORT)).thenReturn(Optional.of(saved("WAITING_APPROVAL", "WRITE_OFF", "AVAILABLE")));
        when(reports.decide(any(), any(), any(), any(), any())).thenReturn(true);

        service.onApproved(decision("APPROVED", null));

        ArgumentCaptor<StockPosting.Actor> actor = ArgumentCaptor.forClass(StockPosting.Actor.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockPosting.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(posting).post(actor.capture(), lines.capture());
        assertThat(actor.getValue().membershipId()).isEqualTo(ME);
        assertThat(actor.getValue().approvedByMembershipId()).isEqualTo(HOD);
        assertThat(lines.getValue()).singleElement().satisfies(l -> {
            assertThat(l.txnType()).isEqualTo("WRITE_OFF");
            assertThat(l.state()).isEqualTo(StockState.QUARANTINE);
            assertThat(l.delta()).isEqualTo(-6);
        });
        verify(reports).decide(TENANT, REPORT, "WRITTEN_OFF", null, HOD);
    }

    @Test
    void aRejectionReleasesOrKeepsAsTheDeciderChoseAndNeverReleasesAnExpiredBatch() {
        when(reports.findForUpdate(TENANT, REPORT)).thenReturn(Optional.of(saved("WAITING_APPROVAL", "WRITE_OFF", "AVAILABLE")));
        when(reports.findById(TENANT, REPORT)).thenReturn(Optional.of(saved("WAITING_APPROVAL", "WRITE_OFF", "AVAILABLE")));
        when(reports.decide(any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.validateDecision(decision("REJECTED", null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.field() + ":" + f.message()).containsExactly(
                                "damageRejectOutcome:" + DamageReportService.OUTCOME_NEEDED));
        service.validateDecision(decision("REJECTED", "RELEASE_TO_AVAILABLE"));
        service.validateDecision(decision("APPROVED", null));

        service.onRejected(decision("REJECTED", "RELEASE_TO_AVAILABLE"));

        assertThat(posted()).extracting(StockPosting.Line::txnType).containsOnly("DAMAGE_RELEASE");
        verify(reports).decide(TENANT, REPORT, "RELEASED", "RELEASE_TO_AVAILABLE", HOD);

        expiry = LocalDate.of(2026, 10, 31);
        assertThatThrownBy(() -> service.validateDecision(decision("REJECTED", "RELEASE_TO_AVAILABLE")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors())
                        .extracting(f -> f.message()).containsExactly(DamageReportService.NO_RELEASE_EXPIRED));
        service.validateDecision(decision("REJECTED", "KEEP_IN_QUARANTINE"));
    }

    @Test
    void aRejectionThatKeepsTheUnitsMovesNothing() {
        when(reports.findForUpdate(TENANT, REPORT)).thenReturn(Optional.of(saved("WAITING_APPROVAL", "WRITE_OFF", "AVAILABLE")));
        when(reports.decide(any(), any(), any(), any(), any())).thenReturn(true);

        service.onRejected(decision("REJECTED", "KEEP_IN_QUARANTINE"));

        verify(posting, never()).post(any(), any());
        verify(reports).decide(TENANT, REPORT, "KEPT_IN_QUARANTINE", "KEEP_IN_QUARANTINE", HOD);
    }
}
