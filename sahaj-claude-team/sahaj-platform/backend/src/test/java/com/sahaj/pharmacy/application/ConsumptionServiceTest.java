package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.IntegrationMode;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.pharmacy.domain.Unit;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAH-79-16 over mocks: a general entry takes FEFO units out of AVAILABLE (ledger WARD_CONSUMPTION, reference "Ward general"); a
 * patient entry is Integrated-only (409 integration-off for a Standalone unit, a store with no linked ward or the no-op reader), needs
 * an admitted patient and an ordered-by, and goes CHARGE_PENDING with the admission id on the ledger; above the stock is 409; a
 * Schedule X line needs a pharmacist and the verified signer; the same key answers the first entry.
 */
class ConsumptionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID SIGNER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID WARD_STORE = UUID.randomUUID();
    private static final UUID IPD_WARD = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final String PURPOSE = "consumption-purpose.ward-general-use";
    private static final Instant NOW = Instant.parse("2026-11-20T06:30:00Z");

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final UnitRepository units = mock(UnitRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final StockRepository stock = mock(StockRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final ScheduleXSignature signature = mock(ScheduleXSignature.class);
    private final ConsumptionRepository consumptions = mock(ConsumptionRepository.class);
    private final AdmittedPatientsReader admitted = mock(AdmittedPatientsReader.class);
    private final ClientRequestRepository clients = mock(ClientRequestRepository.class);
    private final DocumentEventRepository events = mock(DocumentEventRepository.class);
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ApprovalEngine engine = mock(ApprovalEngine.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final StockAuditService stockAudits = mock(StockAuditService.class);
    private final ConsumptionService service = new ConsumptionService(gate, guard, stores, units, products, stock, posting,
            signature, consumptions, admitted, clients, events, reasons, engine, team, audit,
            new PharmacyTime(Clock.fixed(NOW, ZoneOffset.UTC)), stockAudits, new BlindCount(mock(StockAuditRepository.class)));
    private PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Ward Nurse", "NURSE", List.of());
    private final Store ward = new Store(WARD_STORE, UNIT, "Test Ward 3B", "W3B", StoreType.WARD, null, false, true, true, true, 0);
    private String schedule = "NONE";
    private IntegrationMode mode = IntegrationMode.INTEGRATED;

    @BeforeEach
    void ready() {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenAnswer(i -> caller);
        when(guard.require(any(), eq(WARD_STORE), eq(StoreCapability.CONSUME))).thenReturn(ward);
        when(stores.findForUpdate(TENANT, WARD_STORE)).thenReturn(Optional.of(ward));
        when(clients.take(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(reasons.isActive(TENANT, ReasonList.CONSUMPTION_PURPOSE, PURPOSE)).thenReturn(true);
        when(units.find(TENANT)).thenAnswer(i -> Optional.of(new Unit(UNIT, "U1", null, null, null, mode, NOW, 0)));
        when(consumptions.linkedWard(TENANT, WARD_STORE)).thenReturn(Optional.of(IPD_WARD));
        when(products.find(eq(TENANT), any())).thenAnswer(i -> Optional.of(product(i.getArgument(1))));
        when(stock.availableOfProduct(eq(TENANT), eq(WARD_STORE), any())).thenReturn(List.of(new StockRepository.Bal(UUID.randomUUID(),
                WARD_STORE, PRODUCT, BATCH, StockState.AVAILABLE, 10, "TESTB001", LocalDate.of(2027, 3, 31), NOW,
                new BigDecimal("10.00"), new BigDecimal("6.0000"))));
        when(stock.findBatch(eq(TENANT), any())).thenReturn(Optional.of(new StockRepository.BatchRow(BATCH, PRODUCT, "TESTB001",
                LocalDate.of(2027, 3, 31), new BigDecimal("10.00"), new BigDecimal("6.0000"), null, NOW)));
        when(posting.pick(any(), anyInt())).thenAnswer(i -> {
            int qty = i.getArgument(1);
            return new Fefo.Result(List.of(new Fefo.Pick(BATCH, Math.min(qty, 10))), 10, Math.max(0, qty - 10));
        });
        when(consumptions.find(eq(TENANT), eq(WARD_STORE), any())).thenAnswer(i -> Optional.of(new ConsumptionRepository.Row(
                i.getArgument(2), UNIT, WARD_STORE, "GENERAL", PURPOSE, null, null, null, null, "NOT_APPLICABLE", null, null, NOW,
                ME)));
        when(consumptions.linesOf(eq(TENANT), any())).thenReturn(java.util.Map.of());
        when(team.findAll(eq(TENANT), any())).thenReturn(java.util.Map.of());
    }

    private ProductRepository.Full product(UUID id) {
        ProductRepository.Row row = new ProductRepository.Row("OWN", null, id, "U-TCB-0001", "Test IV Fluid (fake)", null, false,
                "Sodium chloride", "0.9%", "Infusion", "Bottle", "Bottle", 1, "3004", new BigDecimal("12"), schedule, false,
                "ACTIVE", false, null);
        return new ProductRepository.Full(row, true, true, null, true, null, false, 0L, ME);
    }

    private PharmacyViews.Outcome<ConsumptionViews.Consumption> general(int quantity, UUID signer) {
        return service.create(TENANT, USER, WARD_STORE.toString(), PharmacyInputs.Body.of(new PharmacyInputs.ConsumptionCreate(KEY,
                "GENERAL", PURPOSE, null, null, null, null,
                List.of(new PharmacyInputs.TransferLineInput(PRODUCT, BigDecimal.valueOf(quantity))), null)), signer);
    }

    private PharmacyViews.Outcome<ConsumptionViews.Consumption> patient(PharmacyInputs.OrderedByInput by) {
        return service.create(TENANT, USER, WARD_STORE.toString(), PharmacyInputs.Body.of(new PharmacyInputs.ConsumptionCreate(KEY,
                "PATIENT", null, null, ADMISSION, by, null,
                List.of(new PharmacyInputs.TransferLineInput(PRODUCT, BigDecimal.valueOf(2))), null)), null);
    }

    @Test
    void aGeneralEntryTakesTheFefoUnitsOutOfAvailableWithTheWardGeneralReference() {
        PharmacyViews.Outcome<ConsumptionViews.Consumption> outcome = general(4, null);

        assertThat(outcome.created()).isTrue();
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        StockPosting.Line line = moves.getValue().get(0);
        assertThat(line.txnType()).isEqualTo("WARD_CONSUMPTION");
        assertThat(line.state()).isEqualTo(StockState.AVAILABLE);
        assertThat(line.delta()).isEqualTo(-4);
        assertThat(line.doc().no()).isEqualTo("Ward general");
        assertThat(line.doc().admissionId()).isNull();
        ArgumentCaptor<ConsumptionRepository.Row> row = ArgumentCaptor.forClass(ConsumptionRepository.Row.class);
        verify(consumptions).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().chargeStatus()).isEqualTo("NOT_APPLICABLE");
        verify(consumptions).insertLine(eq(TENANT), eq(UNIT), any(), eq(ME));
    }

    @Test
    void aGeneralEntryNeedsAPurposeFromTheList() {
        assertThatThrownBy(() -> service.create(TENANT, USER, WARD_STORE.toString(), PharmacyInputs.Body.of(
                new PharmacyInputs.ConsumptionCreate(KEY, "GENERAL", "consumption-purpose.nope", null, null, null, null,
                        List.of(new PharmacyInputs.TransferLineInput(PRODUCT, BigDecimal.ONE)), null)), null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().toString()).contains("purposeKey"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aQuantityAboveTheAvailableStockIs409WithTheAvailableFigureAndNothingIsPosted() {
        assertThatThrownBy(() -> general(11, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("insufficient-stock"));
        verify(posting, never()).post(any(), any());
        verify(consumptions, never()).insert(any(), any());
    }

    @Test
    void aPatientEntryIsIntegrationOffForAStandaloneUnitAStoreWithNoWardAndTheNoOpReader() {
        PharmacyInputs.OrderedByInput by = new PharmacyInputs.OrderedByInput(null, "Dr Test Visiting");
        mode = IntegrationMode.STANDALONE;
        assertThatThrownBy(() -> patient(by)).isInstanceOf(IntegrationOffException.class);
        mode = IntegrationMode.INTEGRATED;
        when(consumptions.linkedWard(TENANT, WARD_STORE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> patient(by)).isInstanceOf(IntegrationOffException.class);
        when(consumptions.linkedWard(TENANT, WARD_STORE)).thenReturn(Optional.of(IPD_WARD));
        when(admitted.admittedTo(TENANT, IPD_WARD)).thenThrow(new IntegrationOffException());
        assertThatThrownBy(() -> patient(by)).isInstanceOf(IntegrationOffException.class);
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aPatientEntryOfAnAdmittedPatientIsChargePendingAndCarriesTheAdmissionAndTheIpNumberOnTheLedger() {
        when(admitted.admittedTo(TENANT, IPD_WARD)).thenReturn(List.of(new AdmittedPatientsReader.Admitted(ADMISSION, "IP-1042",
                "Test Patient (fake)", "B12")));

        patient(new PharmacyInputs.OrderedByInput(null, "Dr Test Visiting"));

        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue().get(0).txnType()).isEqualTo("WARD_CONSUMPTION_PATIENT");
        assertThat(moves.getValue().get(0).doc().admissionId()).isEqualTo(ADMISSION);
        assertThat(moves.getValue().get(0).doc().no()).isEqualTo("IP-1042");
        ArgumentCaptor<ConsumptionRepository.Row> row = ArgumentCaptor.forClass(ConsumptionRepository.Row.class);
        verify(consumptions).insert(eq(TENANT), row.capture());
        assertThat(row.getValue().chargeStatus()).isEqualTo("CHARGE_PENDING");
        assertThat(row.getValue().orderedByName()).isEqualTo("Dr Test Visiting");
    }

    @Test
    void aPatientWhoIsNotAdmittedToTheWardIs400AndAnOrderedByIsRequired() {
        when(admitted.admittedTo(TENANT, IPD_WARD)).thenReturn(List.of());

        assertThatThrownBy(() -> patient(new PharmacyInputs.OrderedByInput(null, "Dr Test Visiting")))
                .isInstanceOfSatisfying(InvalidFieldsException.class,
                        e -> assertThat(e.errors().toString()).contains(ConsumptionService.NOT_ADMITTED));
        assertThatThrownBy(() -> patient(null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains("orderedBy"));
        assertThatThrownBy(() -> patient(new PharmacyInputs.OrderedByInput(DOCTOR, null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(e.errors().toString()).contains("orderedBy"));
        verify(posting, never()).post(any(), any());
    }

    @Test
    void aNurseCannotRecordAScheduleXLineAndAPharmacistNeedsTheVerifiedSigner() {
        schedule = "X";

        assertThatThrownBy(() -> general(2, SIGNER)).isInstanceOf(ForbiddenException.class);
        caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist", "PHARMACIST", List.of());
        assertThatThrownBy(() -> general(2, null)).isInstanceOfSatisfying(InvalidFieldsException.class,
                e -> assertThat(e.errors().toString()).contains("secondSigner"));
        general(2, SIGNER);

        verify(signature).recheck(TENANT, ME, SIGNER);
        ArgumentCaptor<List<StockPosting.Line>> moves = ArgumentCaptor.forClass(List.class);
        verify(posting).post(any(), moves.capture());
        assertThat(moves.getValue().get(0).secondSignerMembershipId()).isEqualTo(SIGNER);
    }

    @Test
    void theSameKeyAnswersTheFirstEntryAndPostsNothing() {
        UUID first = UUID.randomUUID();
        when(clients.own(TENANT, ME, KEY)).thenReturn(Optional.of(new ClientRequestRepository.Taken(ME,
                ConsumptionService.OP_CREATE, first)));

        PharmacyViews.Outcome<ConsumptionViews.Consumption> outcome = general(4, null);

        assertThat(outcome.created()).isFalse();
        verify(posting, never()).post(any(), any());
    }

    @Test
    void theOrderedByOptionsAreTheActiveDoctorsInNameOrder() {
        when(team.findActiveDoctors(TENANT)).thenReturn(List.of(
                new TeamMemberPort.TeamMember(UUID.randomUUID(), "Dr Zed Test", "DOCTOR", true, 0),
                new TeamMemberPort.TeamMember(DOCTOR, "Dr Abe Test", "VISITING_DOCTOR", true, 0)));

        ConsumptionViews.OrderedByList list = service.orderedByOptions(TENANT, USER);

        assertThat(list.items()).extracting(PharmacyViews.Person::name).containsExactly("Dr Abe Test", "Dr Zed Test");
    }
}
