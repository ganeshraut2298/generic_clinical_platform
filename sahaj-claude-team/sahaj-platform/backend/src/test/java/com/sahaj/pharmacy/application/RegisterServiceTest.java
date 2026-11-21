package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.InvalidFieldsException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-80-9 and 80-10: the H1 and Schedule X registers, their access, their range, their CSV and their audit rows. */
class RegisterServiceTest {

    private final SaleWorld w = new SaleWorld();
    private final RegisterReadRepository rows = mock(RegisterReadRepository.class);
    private final RegisterService service = new RegisterService(w.f.gate, w.f.guard, rows, w.products, w.stock, w.support,
            w.reasons, w.blind, w.f.auditLog, w.f.time);
    private final String shop = w.shop.id().toString();
    private final UUID h1 = w.product("Testcodeine", "Codeine", "H1");
    private final UUID x = w.product("Testmorph", "Morphine", "X");
    private final UUID batch = w.shelf(h1, "H-1", LocalDate.of(2027, 6, 30), 5, "10.00");
    private final UUID patient = UUID.randomUUID();

    RegisterServiceTest() {
        w.shelf(x, "X-1", LocalDate.of(2027, 6, 30), 5, "10.00");
        when(rows.h1Count(any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(rows.h1Page(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(h1Row("ENTRY", 2)));
        when(rows.h1All(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(h1Row("ENTRY", 2)));
        when(rows.h1Entries(any(), any(), any(), any(), eq(false))).thenReturn(4);
        when(rows.h1Entries(any(), any(), any(), any(), eq(true))).thenReturn(1);
    }

    private RegisterReadRepository.H1Row h1Row(String kind, int qty) {
        return new RegisterReadRepository.H1Row(UUID.randomUUID(), kind, null, null, null, Instant.parse("2026-11-17T05:30:00Z"),
                "SB/26-27/000001", patient, "Test Patient One", 34, "1 Test Lane (fake)", "Dr Test", "REG-1", null, "TYPED", null,
                h1, null, batch, qty, w.f.pharmacist.member().membershipId(), null);
    }

    @Test
    void theH1RegisterShowsTheLawsFieldsCountsTodayAndWritesTheViewedAudit() {
        SaleCheckViews.H1RegisterPage page = service.h1(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, null, null, null,
                null, null);

        assertThat(page.items()).hasSize(1);
        SaleCheckViews.H1RegisterRow row = page.items().get(0);
        assertThat(row.patientName()).isEqualTo("Test Patient One");
        assertThat(row.patientAddress()).isEqualTo("1 Test Lane (fake)");
        assertThat(row.batch().batchNo()).isEqualTo("H-1");
        assertThat(row.pharmacist().name()).isEqualTo("Test Pharmacist One");
        assertThat(page.entriesToday()).isEqualTo(4);
        assertThat(page.missingPrescriberToday()).isEqualTo(1);
        verify(w.patientAccess).recordOpened(any(), eq(patient), any());
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_REGISTER_VIEWED");
        assertThat(w.f.audit.toString()).doesNotContain("Test Patient One").doesNotContain("1 Test Lane");
    }

    @Test
    void aCashierIsRefusedAndNothingIsRead() {
        assertThatThrownBy(() -> service.h1(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null, null, null, null))
                .isInstanceOf(StoreAccessDeniedException.class);
        assertThatThrownBy(() -> service.scheduleX(SaleWorld.TENANT, w.cashier.userId(), shop, null, null, null))
                .isInstanceOf(StoreAccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(rows);
    }

    @Test
    void aRangeOverTwelveMonthsIsRefusedAndAnExportNeedsBothDates() {
        assertThatThrownBy(() -> service.h1(SaleWorld.TENANT, w.f.owner.userId(), shop, "2025-01-01", "2026-11-17", null, null,
                null, null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("range-too-long"));
        assertThatThrownBy(() -> service.exportH1(SaleWorld.TENANT, w.f.owner.userId(), shop, "2026-11-01", null, null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> service.h1(SaleWorld.TENANT, w.f.owner.userId(), shop, "2026-12-01", "2026-11-01", null, null,
                null, null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theH1CsvIsForTheOwnerAndAPharmacistAndNeutralisesFormulas() {
        when(rows.h1All(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                new RegisterReadRepository.H1Row(UUID.randomUUID(), "ENTRY", null, null, null, Instant.parse("2026-11-17T05:30:00Z"),
                        "SB/1", patient, "=HYPERLINK(1)", 34, "Lane", "Dr Test", "REG-1", null, "TYPED", null, h1, null, batch, 2,
                        w.f.pharmacist.member().membershipId(), null)));

        ReportViews.CsvFile file = service.exportH1(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, "2026-11-01", "2026-11-17",
                null, null);

        String text = new String(file.bytes(), StandardCharsets.UTF_8);
        assertThat(text).startsWith(ReportCsv.BOM + "Date and time,Bill number").contains("'=HYPERLINK(1)");
        assertThat(file.fileName()).isEqualTo("sahaj-h1-register-2026-11-01-2026-11-17.csv");
        assertThat(w.f.auditActions()).containsExactly("PHARMACY_REGISTER_EXPORTED");
        assertThatThrownBy(() -> service.exportH1(SaleWorld.TENANT, w.cashier.userId(), shop, "2026-11-01", "2026-11-17", null,
                null)).isInstanceOf(StoreAccessDeniedException.class);
    }

    @Test
    void anExportOfMoreThanFiftyThousandRowsIsRefusedBeforeAnyByte() {
        List<RegisterReadRepository.H1Row> many = java.util.Collections.nCopies(RegisterService.EXPORT_CAP + 1, h1Row("ENTRY", 1));
        when(rows.h1All(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(many);

        assertThatThrownBy(() -> service.exportH1(SaleWorld.TENANT, w.f.owner.userId(), shop, "2026-11-01", "2026-11-17", null,
                null)).isInstanceOfSatisfying(PharmacyProblemException.class,
                e -> assertThat(e.slug()).isEqualTo("export-too-large"));
        assertThat(w.f.audit).isEmpty();
    }

    @Test
    void theScheduleXBalanceMatchesTheLatestCountOrAsksForAReview() {
        UUID other = w.product("Testfent", "Fentanyl", "X");
        when(rows.xBalances(any(), any(), any(), any(), any())).thenReturn(List.of(
                new RegisterReadRepository.XBalance(x, false, 10, 5, 7, 8),
                new RegisterReadRepository.XBalance(other, true, 0, 4, 1, 3),
                new RegisterReadRepository.XBalance(h1, false, 2, 0, 0, 2)));
        when(rows.latestCounts(any(), any(), anyCollection())).thenReturn(Map.of(
                x, new RegisterReadRepository.XCount(8, LocalDate.of(2026, 11, 10)),
                other, new RegisterReadRepository.XCount(2, LocalDate.of(2026, 11, 10))));

        SaleCheckViews.ScheduleXRegister register = service.scheduleX(SaleWorld.TENANT, w.f.owner.userId(), shop, null, null, null);

        assertThat(register.items()).extracting(SaleCheckViews.ScheduleXBalanceRow::status)
                .containsExactly("MATCH", "REVIEW", "NOT_COUNTED");
        assertThat(register.items().get(1).difference()).isEqualTo(-1L);
        assertThat(register.toReview()).isEqualTo(1);
        assertThat(register.items().get(0).book()).isEqualTo(8L);
        assertThat(register.note()).startsWith("Two-person sign-off");
        assertThat(register.from()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    void aBlindCallerGetsNoQuantityForAnAuditedProductButTheStatusStays() {
        when(w.audits.openAuditProducts(any(), any())).thenReturn(Set.of(x));
        when(rows.xBalances(any(), any(), any(), any(), any())).thenReturn(List.of(
                new RegisterReadRepository.XBalance(x, false, 10, 5, 7, 8)));
        when(rows.latestCounts(any(), any(), anyCollection())).thenReturn(Map.of(
                x, new RegisterReadRepository.XCount(8, LocalDate.of(2026, 11, 10))));

        SaleCheckViews.ScheduleXBalanceRow row = service.scheduleX(SaleWorld.TENANT, w.f.pharmacist.userId(), shop, null, null,
                null).items().get(0);

        assertThat(row.opening()).isNull();
        assertThat(row.book()).isNull();
        assertThat(row.physical()).isNull();
        assertThat(row.difference()).isNull();
        assertThat(row.status()).isEqualTo("MATCH");
    }

    @Test
    void theScheduleXEntriesCarryBothSignersAndTheCancelKind() {
        UUID first = w.f.pharmacist.member().membershipId();
        UUID second = w.pharmacist2.member().membershipId();
        when(rows.xCount(any(), any(), any(), any(), any())).thenReturn(1L);
        when(rows.xPage(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                new RegisterReadRepository.XRow(UUID.randomUUID(), "REVERSAL", Instant.parse("2026-11-17T05:30:00Z"), "BILL",
                        "SB/1", "CANCELLED", patient, "Test Patient One", 34, "Dr Test", "REG-1", LocalDate.of(2026, 11, 17), x,
                        batch, -2, first, second)));

        SaleCheckViews.ScheduleXEntryPage page = service.scheduleXSales(SaleWorld.TENANT, w.f.owner.userId(), shop, null, null,
                null, null, null);

        SaleCheckViews.ScheduleXEntry entry = page.items().get(0);
        assertThat(entry.sourceType()).isEqualTo("BILL_CANCEL");
        assertThat(entry.firstSigner().name()).isEqualTo("Test Pharmacist One");
        assertThat(entry.secondSigner().name()).isEqualTo("Test Pharmacist Two");
        assertThat(entry.qty()).isEqualTo(-2);
        verify(w.patientAccess).recordOpened(any(), eq(patient), any());
    }
}
