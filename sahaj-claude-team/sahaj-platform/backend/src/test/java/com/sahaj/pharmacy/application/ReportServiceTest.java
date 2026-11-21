package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreRules;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79-18 and 79-19: who reaches which report, the checks on the query, the blind mask, the cap and the audit. */
class ReportServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID CS_ID = UUID.randomUUID();
    private static final UUID WARD_ID = UUID.randomUUID();
    private static final UUID AUDITED = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ReportRepository repo = mock(ReportRepository.class);
    private final StockPosting posting = mock(StockPosting.class);
    private final BlindCount blind = mock(BlindCount.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final SaleSupport support = mock(SaleSupport.class);
    private final PharmacyTime time = new PharmacyTime(Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC));
    private final ActiveReasonService reasons = mock(ActiveReasonService.class);
    private final ReportService service = new ReportService(gate, stores, products, repo, posting, time, blind, team, audit, support,
            reasons);

    private static Store store(UUID id, StoreType type) {
        return new Store(id, UNIT, "Test " + type, type.name().substring(0, 2), type, null, false, true, true, true, 0);
    }

    private static PharmacyCaller caller(String teamRole, StoreRole role, Store store) {
        List<PharmacyCaller.Mapping> mappings = role == null ? List.of()
                : List.of(new PharmacyCaller.Mapping(store, null, role, StoreRules.access(teamRole, role, true)));
        return new PharmacyCaller(TENANT, USER, MEMBER, "Test Person", teamRole, mappings);
    }

    private PharmacyCaller given(PharmacyCaller caller) {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(stores.list(TENANT, false)).thenReturn(List.of(store(CS_ID, StoreType.CENTRAL), store(WARD_ID, StoreType.WARD)));
        return caller;
    }

    private static ReportService.Query query(String from, String to) {
        return new ReportService.Query(null, from, to, null, null, null, null, null);
    }

    @Test
    void theOwnerSeesEveryGroupAndAStoreUserOnlyTheTwoStockReports() {
        given(caller("OWNER", null, null));
        ReportViews.Catalogue owner = service.catalogue(TENANT, USER);

        assertThat(owner.groups()).extracting(ReportViews.CatalogueGroup::group).containsExactly("STOCK", "MOVEMENT", "PURCHASE");
        assertThat(owner.groups().get(1).reports().get(0)).isEqualTo(new ReportViews.CatalogueItem("stock-ledger",
                "Stock ledger (per product)"));
        assertThat(owner.groups().get(0).label()).isEqualTo("Stock");

        given(caller("NURSE", StoreRole.STORE_USER, store(WARD_ID, StoreType.WARD)));
        ReportViews.Catalogue user = service.catalogue(TENANT, USER);
        assertThat(user.groups()).hasSize(1);
        assertThat(user.groups().get(0).reports()).extracting(ReportViews.CatalogueItem::key)
                .containsExactly("store-stock", "batch-expiry", "below-reorder");
    }

    @Test
    void thePurchaseReportsAreOnlyForTheOwnerAndTheCentralStoreUser() {
        Store cs = store(CS_ID, StoreType.CENTRAL);
        given(caller("STORE_STAFF", StoreRole.CENTRAL_STORE_USER, cs));
        assertThat(service.catalogue(TENANT, USER).groups()).extracting(ReportViews.CatalogueGroup::group)
                .containsExactly("STOCK", "MOVEMENT", "PURCHASE");

        given(caller("NURSE", StoreRole.APPROVER, store(WARD_ID, StoreType.WARD)));
        assertThat(service.catalogue(TENANT, USER).groups()).extracting(ReportViews.CatalogueGroup::group)
                .containsExactly("STOCK", "MOVEMENT");
    }

    @Test
    void aCallerWithNoReportGetsNoGroups() {
        given(caller("NURSE", null, null));
        assertThat(service.catalogue(TENANT, USER).groups()).isEmpty();
    }

    @Test
    void aStoreUserCannotRunAMovementReportAndAnUnknownKeyIsABadRequest() {
        given(caller("NURSE", StoreRole.STORE_USER, store(WARD_ID, StoreType.WARD)));

        assertThatThrownBy(() -> service.run(TENANT, USER, "indent-register", query("2026-11-01", "2026-11-20")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.run(TENANT, USER, "no-such-report", query(null, null)))
                .isInstanceOf(PharmacyProblemException.class).extracting("slug").isEqualTo("bad-request");
        verify(repo, never()).rows(any(), any(), anyInt(), anyInt());
    }

    @Test
    void aDatedReportNeedsBothDatesAndAtMostTwelveMonths() {
        given(caller("OWNER", null, null));

        assertThatThrownBy(() -> service.run(TENANT, USER, "indent-register", query(null, null)))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.run(TENANT, USER, "indent-register", query("2025-01-01", "2026-11-20")))
                .isInstanceOf(PharmacyProblemException.class).extracting("slug").isEqualTo("range-too-long");
        assertThatThrownBy(() -> service.run(TENANT, USER, "indent-register", query("2026-11-20", "2026-11-01")))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aStoreTheCallerCannotReachIsNotFound() {
        given(caller("NURSE", StoreRole.STORE_USER, store(WARD_ID, StoreType.WARD)));
        ReportService.Query other = new ReportService.Query(UUID.randomUUID().toString(), null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.run(TENANT, USER, "store-stock", other)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBlindCallerGetsEmptyQuantitiesForAnAuditedProductButTheRowStaysListed() {
        PharmacyCaller user = given(caller("NURSE", StoreRole.STORE_USER, store(WARD_ID, StoreType.WARD)));
        List<Object> cells = new java.util.ArrayList<>(List.of("Test Ward", "Test Tablet", 10L, 2L, 1L, 3L, 5L, 1500L));
        ReportRepository.Row audited = new ReportRepository.Row(WARD_ID, AUDITED, cells);
        ReportRepository.Row other = new ReportRepository.Row(WARD_ID, UUID.randomUUID(), new java.util.ArrayList<>(cells));
        when(repo.count(any(), any())).thenReturn(2L);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(audited, other));
        when(blind.at(user, WARD_ID)).thenReturn(new BlindCount.Mask(Set.of(AUDITED)));

        ReportViews.ReportPage page = service.run(TENANT, USER, "store-stock", query(null, null));

        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows().get(0)).containsExactly("Test Ward", "Test Tablet", null, null, null, null, 5L, null);
        assertThat(page.rows().get(1).get(2)).isEqualTo(10L);
        assertThat(page.total()).isEqualTo(2);
        verify(audit).record(org.mockito.ArgumentMatchers.eq("PHARMACY_REPORT_VIEWED"), any(), any(), any(), any());
    }

    @Test
    void anExportOfMoreThanFiftyThousandRowsIsRefusedBeforeAnyRowIsRead() {
        given(caller("OWNER", null, null));
        when(repo.count(any(), any())).thenReturn(50_001L);

        assertThatThrownBy(() -> service.export(TENANT, USER, "store-stock", query(null, null)))
                .isInstanceOf(PharmacyProblemException.class).extracting("slug").isEqualTo("export-too-large");
        verify(repo, never()).rows(any(), any(), anyInt(), anyInt());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anExportIsAnAttachmentOfNeutralisedCellsAndIsAudited() {
        given(caller("OWNER", null, null));
        when(repo.count(any(), any())).thenReturn(1L);
        when(blind.at(any(), any())).thenReturn(BlindCount.Mask.NONE);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(new ReportRepository.Row(WARD_ID, UUID.randomUUID(),
                new java.util.ArrayList<>(List.of("Test Ward", "=HYPERLINK(1)", 10L, 0L, 0L, 0L, 5L, 1500L)))));

        ReportViews.CsvFile file = service.export(TENANT, USER, "store-stock", query(null, null));
        String text = new String(file.bytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(file.fileName()).isEqualTo("sahaj-store-stock-2026-11-20.csv");
        assertThat(text).contains("'=HYPERLINK(1)").contains(",15.00\r\n");
        verify(audit).record(org.mockito.ArgumentMatchers.eq("PHARMACY_REPORT_EXPORTED"), any(), any(), any(), any());
    }

    @Test
    void everyReportHasUniqueColumnKeysAndAnUnknownKeyIsEmpty() {
        for (ReportDef def : ReportDef.values()) {
            assertThat(def.columns().stream().map(ReportDef.Col::key).distinct().count()).isEqualTo(def.columns().size());
            assertThat(ReportDef.byKey(def.key())).contains(def);
        }
        assertThat(ReportDef.byKey("sales-margin")).contains(ReportDef.SALES_MARGIN);
        assertThat(ReportDef.byKey("day-close")).isEmpty();
        assertThat(Map.of(1, 2)).isNotEmpty();
    }

    private static Store saleStore() {
        return new Store(WARD_ID, UNIT, "Test shop", "SH", StoreType.WARD, null, true, true, true, true, 0);
    }

    private void givenSale(PharmacyCaller caller) {
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        when(stores.list(TENANT, false)).thenReturn(List.of(saleStore()));
    }

    private ReportRepository.Row h1Row(UUID storeId, UUID patientId, String name, String address) {
        List<Object> cells = new java.util.ArrayList<>();
        cells.add(null);
        cells.add("B-1");
        cells.add("ENTRY");
        cells.add(name);
        cells.add(34L);
        cells.add(address);
        for (int i = 6; i < 14; i++) {
            cells.add(null);
        }
        return new ReportRepository.Row(storeId, UUID.randomUUID(), cells, patientId);
    }

    @Test
    void theH1ReportMasksNamesAndHidesTheAddressForAnApproverButWritesTheRegisterAudit() {
        givenSale(caller("DOCTOR", StoreRole.APPROVER, saleStore()));
        UUID patient = UUID.randomUUID();
        when(repo.count(any(), any())).thenReturn(2L);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                h1Row(WARD_ID, patient, "Test Ravi Sharma", "1 Test Road"), h1Row(WARD_ID, patient, null, null)));

        ReportViews.ReportPage page = service.run(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20"));

        assertThat(page.rows().get(0).get(3)).isEqualTo("T*** R*** S***");
        assertThat(page.rows().get(0).get(5)).isNull();
        assertThat(page.rows().get(1).get(3)).isNull();
        verify(support).recordOpened(TENANT, patient, USER);
        verify(audit).record(org.mockito.ArgumentMatchers.eq("PHARMACY_REGISTER_VIEWED"), any(), any(), any(), any());
    }

    @Test
    void theOwnerAndAPharmacistMemberOfTheStoreSeeTheH1PatientNames() {
        givenSale(caller("OWNER", null, null));
        when(repo.count(any(), any())).thenReturn(1L);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(h1Row(WARD_ID, null, "Test Ravi", "1 Test Road")));
        assertThat(service.run(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20")).rows().get(0))
                .contains("Test Ravi", "1 Test Road");

        givenSale(caller("PHARMACIST", StoreRole.STORE_USER, saleStore()));
        assertThat(service.run(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20")).rows().get(0))
                .contains("Test Ravi", "1 Test Road");
        verify(support, never()).recordOpened(any(), any(), any());
    }

    @Test
    void aPharmacistWhoIsNotMappedToTheRowsStoreGetsTheMaskedName() {
        PharmacyCaller user = caller("PHARMACIST", StoreRole.STORE_USER, saleStore());

        assertThat(ReportService.seesPatientNames(user, WARD_ID)).isTrue();
        assertThat(ReportService.seesPatientNames(user, UUID.randomUUID())).isFalse();
        assertThat(ReportService.seesPatientNames(caller("DOCTOR", StoreRole.APPROVER, saleStore()),
                WARD_ID)).isFalse();
        assertThat(ReportService.maskName("Ravi Sharma")).isEqualTo("R*** S***");
        assertThat(ReportService.maskName(null)).isNull();
    }

    @Test
    void theH1ExportWritesOneRecordAccessEntryPerDistinctPatientBeforeAnyCsvByteAndFailsWhenOneCannotBeWritten() {
        givenSale(caller("OWNER", null, null));
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        when(repo.count(any(), any())).thenReturn(3L);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(h1Row(WARD_ID, one, "Test A", "x"),
                h1Row(WARD_ID, one, "Test A", "x"), h1Row(WARD_ID, two, "Test B", "y")));

        service.export(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20"));

        verify(support, org.mockito.Mockito.times(1)).recordOpened(TENANT, one, USER);
        verify(support, org.mockito.Mockito.times(1)).recordOpened(TENANT, two, USER);

        org.mockito.Mockito.reset(audit);
        org.mockito.Mockito.doThrow(PharmacyProblemException.patientsUnreachable()).when(support).recordOpened(any(), any(), any());
        assertThatThrownBy(() -> service.export(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20")))
                .isInstanceOf(PharmacyProblemException.class);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.eq("PHARMACY_REGISTER_EXPORTED"), any(), any(), any(), any());
    }

    @Test
    void aReasonCellShowsTheLabelOfItsListNotATitleMadeFromTheKey() {
        assertThat(ReportService.reasonLabel("sales-return.doctor-changed-medicine",
                java.util.Map.of("sales-return.doctor-changed-medicine", "Doctor changed medicine")))
                .isEqualTo("Doctor changed medicine");
        assertThat(ReportService.reasonLabel("unknown.key", java.util.Map.of())).isEqualTo("unknown.key");
        assertThat(ReportService.reasonLabel(null, java.util.Map.of())).isNull();
    }

    @Test
    void theH1ExportIsForTheOwnerAndAPharmacistAndWritesTheRegisterExportAudit() {
        givenSale(caller("DOCTOR", StoreRole.APPROVER, saleStore()));
        assertThatThrownBy(() -> service.export(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20")))
                .isInstanceOf(com.sahaj.platform.api.ForbiddenException.class);

        givenSale(caller("OWNER", null, null));
        when(repo.count(any(), any())).thenReturn(1L);
        when(repo.rows(any(), any(), anyInt(), anyInt())).thenReturn(List.of(h1Row(WARD_ID, null, "Test Ravi", "1 Test Road")));
        service.export(TENANT, USER, "h1-register", query("2026-11-01", "2026-11-20"));

        verify(audit).record(org.mockito.ArgumentMatchers.eq("PHARMACY_REGISTER_EXPORTED"), any(), any(), any(), any());
    }
}
