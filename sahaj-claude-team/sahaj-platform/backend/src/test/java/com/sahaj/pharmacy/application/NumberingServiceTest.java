package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyMasterInputs.SeriesSave;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-78-10, numbering. Today in the fixture is 17 Nov 2026, so the financial year is 26-27. */
class NumberingServiceTest {

    private static final UUID TENANT = PharmacyFixture.TENANT;

    private final MasterFixture m = new MasterFixture();
    private final PharmacyFixture f = m.f;

    private static SeriesSave series(long version, String prefix, boolean fy, int length, boolean restarts) {
        return new SeriesSave(version, prefix, fy, BigDecimal.valueOf(length), restarts);
    }

    private PharmacyMasterViews.DocumentSeries save(String type, SeriesSave in) {
        return m.numberingService.save(TENANT, f.owner.userId(), type, in);
    }

    private List<FieldMessage> errorsOf(Runnable call) {
        try {
            call.run();
        } catch (InvalidFieldsException e) {
            return e.errors();
        }
        throw new AssertionError("expected a validation error");
    }

    private PharmacyMasterViews.DocumentSeries row(String type) {
        return m.numberingService.list(TENANT, f.owner.userId()).items().stream()
                .filter(s -> s.docType().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void twelveRowsWithTheDefaultsAreServedBeforeAnythingIsSaved() {
        PharmacyMasterViews.DocumentSeriesList list = m.numberingService.list(TENANT, f.pharmacist.userId());

        assertThat(list.items()).extracting(PharmacyMasterViews.DocumentSeries::docType).containsExactly("PURCHASE_ORDER",
                "GRN", "INDENT", "TRANSFER", "BILL", "DEBIT_NOTE", "STOCK_AUDIT", "STORE_RETURN", "DAMAGE_REPORT",
                "STOCK_ADJUSTMENT", "CREDIT_NOTE", "IPD_ISSUE_SLIP");
        assertThat(list.items()).extracting(PharmacyMasterViews.DocumentSeries::example).containsExactly("PO-0001",
                "GRN-0001", "IND-0001", "TRF-0001", "SB/26-27/000001", "DN-0001", "AUD-0001", "RET-0001", "DMG-0001",
                "ADJ-0001", "CN/26-27/000001", "IS-0001");
        PharmacyMasterViews.DocumentSeries bill = row("BILL");
        assertThat(bill.prefix()).isEqualTo("SB/");
        assertThat(bill.financialYearPart()).isTrue();
        assertThat(bill.sequenceLength()).isEqualTo(6);
        assertThat(bill.restartsEachFinancialYear()).isTrue();
        assertThat(bill.renderedLength()).isEqualTo(15);
        assertThat(list.items()).allSatisfy(s -> {
            assertThat(s.saved()).isFalse();
            assertThat(s.version()).isZero();
            assertThat(s.numbersIssued()).isFalse();
        });
        assertThat(m.writes).isEmpty();
    }

    @Test
    void aSaveStoresTheSeriesAndTheExampleFollowsIt() {
        PharmacyMasterViews.DocumentSeries saved = save("PURCHASE_ORDER", series(0, "PO/", true, 5, true));

        assertThat(saved.saved()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.example()).isEqualTo("PO/26-27/00001");
        assertThat(saved.renderedLength()).isEqualTo(14);
        assertThat(row("PURCHASE_ORDER").prefix()).isEqualTo("PO/");
        assertThat(f.auditActions()).contains("PHARMACY_SERIES_CHANGED");

        PharmacyMasterViews.DocumentSeries second = save("PURCHASE_ORDER", series(1, "PO-", false, 4, false));
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.example()).isEqualTo("PO-0001");
    }

    @Test
    void aStaleVersionIsSettingsChangedWithTheCurrentSeries() {
        save("GRN", series(0, "GR-", false, 4, false));

        assertThatThrownBy(() -> save("GRN", series(0, "GRN-", false, 4, false)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> {
                    assertThat(e.slug()).isEqualTo("settings-changed");
                    assertThat(((PharmacyMasterViews.DocumentSeries) e.extensions().get("current")).prefix())
                            .isEqualTo("GR-");
                });
    }

    @Test
    void anotherTypesPrefixIsRefusedIgnoringCaseWhetherSavedOrStillTheDefault() {
        assertThatThrownBy(() -> save("PURCHASE_ORDER", series(0, "grn-", false, 4, false)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("prefix-in-use");
                    assertThat(e.getMessage()).isEqualTo("Two document types cannot use the same prefix");
                });
        save("INDENT", series(0, "REQ-", false, 4, false));
        assertThatThrownBy(() -> save("TRANSFER", series(0, "req-", false, 4, false))).isInstanceOf(ConflictException.class);
        assertThat(save("TRANSFER", series(0, "TRF-", false, 5, false)).sequenceLength()).isEqualTo(5);
    }

    @Test
    void aPrefixAStoreSeriesAlreadyUsesIsRefusedIgnoringCase() {
        org.mockito.Mockito.when(m.storeBillSeries.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new StoreBillSeriesRepository.Row(UUID.randomUUID(), UUID.randomUUID(), "MAIN-", true, 5, true, 1L)));

        assertThatThrownBy(() -> save("PURCHASE_ORDER", series(0, "main-", false, 4, false)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.problemType()).isEqualTo("prefix-in-use"));
        assertThat(save("PURCHASE_ORDER", series(0, "PO2-", false, 4, false)).prefix()).isEqualTo("PO2-");
    }

    @Test
    void theFieldRulesAreReportedTogether() {
        assertThat(errorsOf(() -> save("GRN", series(0, "TOOLONG1", false, 3, false)))).contains(
                new FieldMessage("prefix", "Use 1 to 6 letters, digits, - or /"),
                new FieldMessage("sequenceLength", "Enter a length from 4 to 8"));
        assertThat(errorsOf(() -> save("GRN", series(0, "A B", false, 4, false))))
                .containsExactly(new FieldMessage("prefix", "Use 1 to 6 letters, digits, - or /"));
        assertThat(errorsOf(() -> save("GRN", series(0, "GRN-", false, 4, true))))
                .containsExactly(new FieldMessage("restartsEachFinancialYear",
                        "Show the financial year when numbers restart every 1 April"));
        assertThat(errorsOf(() -> save("GRN", series(0, "ABCDEF", true, 8, true))))
                .containsExactly(new FieldMessage("sequenceLength", "The number must be 16 characters or fewer"));
        assertThat(errorsOf(() -> save("GRN", new SeriesSave(null, null, null, new BigDecimal("4.5"), null))))
                .extracting(FieldMessage::field).contains("version", "prefix", "sequenceLength", "financialYearPart",
                        "restartsEachFinancialYear");
    }

    @Test
    void theLongestAllowedNumberIsSixteenCharacters() {
        PharmacyMasterViews.DocumentSeries ok = save("GRN", series(0, "GRNABC", true, 4, true));

        assertThat(ok.renderedLength()).isEqualTo(16);
        assertThat(ok.example()).isEqualTo("GRNABC26-27/0001");
    }

    @Test
    void anUnknownDocumentTypeIs400BeforeTheBodyIsRead() {
        PharmacyInputs.Body<SeriesSave> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the path");
        };

        assertThatThrownBy(() -> m.numberingService.save(TENANT, f.owner.userId(), "RECEIPT", unreadable))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("bad-request"));
        assertThatThrownBy(() -> m.numberingService.save(TENANT, f.owner.userId(), "bill", unreadable))
                .isInstanceOf(PharmacyProblemException.class);
    }

    @Test
    void numbersIssuedComesFromTheCounterOfThisYearAndASaveWithNoChangeWritesNothing() {
        save("PURCHASE_ORDER", series(0, "PO-", false, 4, false));
        m.issuedThisYear.add(DocType.PURCHASE_ORDER);
        m.writes.clear();
        f.audit.clear();

        PharmacyMasterViews.DocumentSeries same = save("PURCHASE_ORDER", series(1, "PO-", false, 4, false));

        assertThat(same.numbersIssued()).isTrue();
        assertThat(row("GRN").numbersIssued()).isFalse();
        assertThat(m.writes).isEmpty();
        assertThat(f.audit).isEmpty();
    }

    @Test
    void onceANumberOfThisFinancialYearWasIssuedTheSeriesIsLockedAndNothingIsWritten() {
        save("PURCHASE_ORDER", series(0, "PO-", false, 4, false));
        m.issuedThisYear.add(DocType.PURCHASE_ORDER);
        m.writes.clear();
        f.audit.clear();

        assertThatThrownBy(() -> save("PURCHASE_ORDER", series(1, "POX-", false, 5, false)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.problemType()).isEqualTo("series-locked");
                    assertThat(e.getMessage()).isEqualTo("Numbers have already been issued this year.");
                });
        assertThat(m.writes).isEmpty();
        assertThat(f.audit).isEmpty();
        // asked under the series row lock, for the start of the financial year 26-27: 1 April 2026, 00:00 IST
        assertThat(m.seriesLocks).contains(DocType.PURCHASE_ORDER);
        assertThat(m.lockAskedSince).containsOnly(java.time.Instant.parse("2026-03-31T18:30:00Z"));
    }

    @Test
    void theLockAlsoStopsTheFirstSaveOfASeriesThatNeverHadARowButAlreadyIssuedNumbers() {
        m.issuedThisYear.add(DocType.BILL);

        assertThatThrownBy(() -> save("BILL", series(0, "INV/", true, 6, true)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.problemType()).isEqualTo("series-locked"));
        assertThat(m.writes).isEmpty();
    }

    @Test
    void theVersionIsCheckedBeforeTheLockAndAnUnchangedSaveOfALockedSeriesIsStillAnswered() {
        save("GRN", series(0, "GRN-", false, 4, false));
        m.issuedThisYear.add(DocType.GRN);

        assertThatThrownBy(() -> save("GRN", series(0, "GRP-", false, 4, false)))
                .isInstanceOfSatisfying(PharmacyProblemException.class, e -> assertThat(e.slug()).isEqualTo("settings-changed"));
        assertThat(save("GRN", series(1, "GRN-", false, 4, false)).version()).isEqualTo(1L);
    }

    @Test
    void aTypeThatIssuedNothingThisYearCanStillBeChanged() {
        save("INDENT", series(0, "IND-", false, 4, false));
        // the last number was taken on 31 March 2026 at 23:59 IST: the year before the current one (1 April 2026 IST)
        m.issuedAt.put(DocType.INDENT, java.time.Instant.parse("2026-03-31T18:29:59Z"));

        PharmacyMasterViews.DocumentSeries changed = save("INDENT", series(1, "REQ-", false, 5, false));

        assertThat(changed.prefix()).isEqualTo("REQ-");
        assertThat(changed.version()).isEqualTo(2L);
    }

    @Test
    void numbersIssuedMeansThisFinancialYearAndTurnsFalseOnOneApril() {
        m.issuedAt.put(DocType.PURCHASE_ORDER, java.time.Instant.parse("2027-03-31T18:29:59Z")); // 31 Mar 2027, 23:59 IST
        java.time.Instant lastDay = java.time.Instant.parse("2027-03-31T10:00:00Z");
        java.time.Instant newYear = java.time.Instant.parse("2027-03-31T18:30:00Z"); // 1 Apr 2027, 00:00 IST

        assertThat(listedAt(lastDay, "PURCHASE_ORDER").numbersIssued()).isTrue();
        assertThat(listedAt(newYear, "PURCHASE_ORDER").numbersIssued()).isFalse();
        // the year starts asked for: 1 April 2026 and 1 April 2027, the same instant the series lock uses
        assertThat(m.lockAskedSince).contains(java.time.Instant.parse("2026-03-31T18:30:00Z"),
                java.time.Instant.parse("2027-03-31T18:30:00Z"));
        // a number taken on 1 April 2027 at 00:00 IST is of the new year
        m.issuedAt.put(DocType.PURCHASE_ORDER, newYear);
        assertThat(listedAt(newYear, "PURCHASE_ORDER").numbersIssued()).isTrue();
        assertThat(listedAt(newYear, "GRN").numbersIssued()).isFalse();
    }

    private PharmacyMasterViews.DocumentSeries listedAt(java.time.Instant now, String type) {
        NumberingService service = new NumberingService(f.gate, f.unitService, m.seriesRepository, f.auditLog,
                new PharmacyTime(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)), m.storeBillSeries);
        return service.list(TENANT, f.owner.userId()).items().stream().filter(s -> s.docType().equals(type)).findFirst()
                .orElseThrow();
    }

    @Test
    void aPharmacistReadsAndNobodyButTheOwnerSaves() {
        PharmacyInputs.Body<SeriesSave> unreadable = () -> {
            throw new IllegalStateException("the body must not be read before the gate");
        };

        assertThat(m.numberingService.list(TENANT, f.pharmacist.userId()).items()).hasSize(12);
        assertThatThrownBy(() -> m.numberingService.save(TENANT, f.pharmacist.userId(), "GRN", unreadable))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> m.numberingService.list(TENANT, f.frontDesk.userId())).isInstanceOf(ForbiddenException.class);
    }
}
