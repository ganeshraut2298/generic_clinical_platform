package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.ImportRow;
import com.sahaj.pharmacy.application.PharmacyViews.OpeningStockImport;
import com.sahaj.pharmacy.domain.OpeningStockCsv;
import com.sahaj.pharmacy.domain.RejectReason;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78-7: the opening-stock import. Start, send rows, submit, approve or reject; the per-row checks, the file-wide check,
 * "nobody approves their own upload", one import per store, the write order of the design note, and the store guard.
 */
class OpeningStockServiceTest {

    private final PharmacyProductFixture x = new PharmacyProductFixture();
    private final PharmacyFixture f = x.f;
    private final UUID tenant = PharmacyFixture.TENANT;
    private final PharmacyViews.Store central = f.createCentral();
    private final PharmacyViews.Store ward = f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
    private final PharmacyFixture.Person secondPharmacist = f.person("PHARMACIST", "Test Pharmacist Two", true);
    private final PharmacyFixture.Person secondOwner = f.person("OWNER", "Test Owner Two", true);
    private final String centralId = central.id().toString();

    private final PharmacyViews.ProductDetail kit = x.productService.create(tenant, f.owner.userId(),
            new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test dressing kit (OT)", "Dressing kit", "1 kit", "kit", "Kit",
                    "kit", BigDecimal.ONE, PharmacyInputs.Sent.of(null), true, "3005", "12",
                    PharmacyInputs.Sent.of(com.sahaj.pharmacy.domain.ProductSchedule.NONE), null, false)).view();
    private final PharmacyViews.ProductDetail glove = x.productService.create(tenant, f.owner.userId(),
            new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test gloves (M)", "Surgical gloves", "M", "device", "Box",
                    "pair", BigDecimal.TEN, PharmacyInputs.Sent.of(null), true, "4015", "12",
                    PharmacyInputs.Sent.of(com.sahaj.pharmacy.domain.ProductSchedule.NONE), null, false)).view();

    {
        f.map(central, f.pharmacist, StoreRole.CENTRAL_STORE_USER);
        f.map(central, secondPharmacist, StoreRole.CENTRAL_STORE_USER);
    }

    // ---- helpers ----

    private static ImportRow row(int no, String store, String product, String batch, String expiry, String mrp, String rate,
                                 String qty) {
        return new ImportRow(BigDecimal.valueOf(no), store, product, batch, expiry, mrp, rate, qty);
    }

    private static ImportRow good(int no, String product, String batch) {
        return row(no, "CS", product, batch, "31-12-2027", "45.50", "30.25", "100");
    }

    private PharmacyInputs.StartImport startBody(int rowCount) {
        return new PharmacyInputs.StartImport(UUID.randomUUID(), OpeningStockCsv.HEADER, BigDecimal.valueOf(rowCount));
    }

    private OpeningStockImport start(PharmacyFixture.Person p, String storeId, int rowCount) {
        return x.openingStock.start(tenant, p.userId(), storeId, startBody(rowCount)).view();
    }

    private PharmacyViews.LinesResult send(PharmacyFixture.Person p, OpeningStockImport imp, ImportRow... rows) {
        return x.openingStock.addLines(tenant, p.userId(), imp.storeId().toString(), imp.id().toString(),
                new PharmacyInputs.ImportLines(List.of(rows)));
    }

    private OpeningStockImport submit(PharmacyFixture.Person p, OpeningStockImport imp) {
        return x.openingStock.submit(tenant, p.userId(), imp.storeId().toString(), imp.id().toString());
    }

    private OpeningStockImport get(PharmacyFixture.Person p, OpeningStockImport imp) {
        return x.openingStock.get(tenant, p.userId(), imp.storeId().toString(), imp.id().toString());
    }

    /** A two-row file from the first pharmacist, submitted and waiting for approval. */
    private OpeningStockImport waiting(PharmacyFixture.Person uploader) {
        OpeningStockImport imp = start(uploader, centralId, 2);
        send(uploader, imp, good(1, "U-SUN-0001", "B1"),
                row(2, "CS", "U-SUN-0002", "B2", "01/2028", "20", "12.50", "50"));
        return submit(uploader, imp);
    }

    private static void assertConflict(Runnable action, String slug) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class,
                ex -> assertThat(ex.problemType()).isEqualTo(slug));
    }

    private static void assertConflict(Runnable action, String slug, String sentence) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo(slug);
            assertThat(ex.getMessage()).isEqualTo(sentence);
        });
    }

    // ---- template ----

    @Test
    void theTemplateIsTheFixedHeaderAndOneExampleRowForAnyoneWithPharmacyAccess() {
        String csv = x.openingStock.template(tenant, f.pharmacist.userId());
        assertThat(csv).startsWith("store code,product code,batch,expiry date,MRP,purchase rate,quantity\n#EXAMPLE,PRODUCT-CODE,");
        assertThat(csv).isEqualTo(OpeningStockCsv.TEMPLATE);
        f.map(ward, f.nurse, StoreRole.STORE_USER);
        assertThat(x.openingStock.template(tenant, f.nurse.userId())).isEqualTo(csv);
        assertThatThrownBy(() -> x.openingStock.template(tenant, f.frontDesk.userId())).isInstanceOf(ForbiddenException.class);
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.openingStock.template(tenant, f.owner.userId())).isInstanceOf(ModuleOffException.class);
    }

    // ---- start ----

    @Test
    void startMakesADraftForTheStoreWithTheUploaderAndOneAuditRow() {
        f.audit.clear();
        PharmacyViews.Outcome<OpeningStockImport> outcome = x.openingStock.start(tenant, f.pharmacist.userId(), centralId,
                startBody(2));

        assertThat(outcome.created()).isTrue();
        OpeningStockImport imp = outcome.view();
        assertThat(imp.status()).isEqualTo("DRAFT");
        assertThat(imp.storeId()).isEqualTo(central.id());
        assertThat(imp.storeName()).isEqualTo("Central Store");
        assertThat(imp.rowCount()).isEqualTo(2);
        assertThat(imp.receivedRows()).isZero();
        assertThat(imp.uploadedBy().membershipId()).isEqualTo(f.pharmacist.id());
        assertThat(imp.uploadedBy().name()).isEqualTo("Test Pharmacist One");
        assertThat(imp.canApprove()).isFalse();
        assertThat(imp.approveLockReason()).isNull();
        assertThat(imp.posted()).isFalse();
        assertThat(imp.decidedBy()).isNull();
        assertThat(f.auditActions()).containsExactly("PHARMACY_OPENING_STOCK_STARTED");
        assertThat(f.audit.get(0).after()).containsEntry("rowCount", 2);
    }

    @Test
    void startRefusesAWrongHeaderAndARowCountOutsideTheLimitsAllTogether() {
        PharmacyInputs.StartImport bad = new PharmacyInputs.StartImport(null, List.of("name", "qty"), new BigDecimal("5001"));
        assertThatThrownBy(() -> x.openingStock.start(tenant, f.pharmacist.userId(), centralId, bad))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors())
                        .extracting(e -> e.field() + ": " + e.message()).containsExactlyInAnyOrder(
                                "clientKey: Reload the page and try again.",
                                "columns: Use the Sahaj template. Save the file as CSV.",
                                "rowCount: The file must have 1 to 5,000 rows"));
        for (String count : new String[] {"0", "-3", "5001", "2.5"}) {
            assertThatThrownBy(() -> x.openingStock.start(tenant, f.pharmacist.userId(), centralId,
                    new PharmacyInputs.StartImport(UUID.randomUUID(), OpeningStockCsv.HEADER, new BigDecimal(count))))
                    .isInstanceOf(InvalidFieldsException.class);
        }
        assertThat(x.openingStock.start(tenant, f.pharmacist.userId(), centralId,
                new PharmacyInputs.StartImport(UUID.randomUUID(), OpeningStockCsv.HEADER, new BigDecimal("5000"))).created())
                .isTrue();
        assertThat(x.importRows).hasSize(1);
    }

    @Test
    void theHeaderIsComparedTrimmedAndIgnoringCaseButNotReordered() {
        List<String> shouted = new ArrayList<>();
        OpeningStockCsv.HEADER.forEach(c -> shouted.add(" " + c.toUpperCase() + " "));
        assertThat(x.openingStock.start(tenant, f.pharmacist.userId(), centralId,
                new PharmacyInputs.StartImport(UUID.randomUUID(), shouted, BigDecimal.ONE)).created()).isTrue();
        List<String> swapped = new ArrayList<>(OpeningStockCsv.HEADER);
        swapped.set(0, OpeningStockCsv.HEADER.get(1));
        swapped.set(1, OpeningStockCsv.HEADER.get(0));
        assertThatThrownBy(() -> x.openingStock.start(tenant, f.pharmacist.userId(), centralId,
                new PharmacyInputs.StartImport(UUID.randomUUID(), swapped, BigDecimal.ONE)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void theSameClientKeyFromTheSameCallerAnswersTheImportAgainAndAnotherCallerIsRefused() {
        PharmacyInputs.StartImport body = startBody(2);
        OpeningStockImport first = x.openingStock.start(tenant, f.pharmacist.userId(), centralId, body).view();
        x.importWrites.clear();
        f.audit.clear();

        PharmacyViews.Outcome<OpeningStockImport> again = x.openingStock.start(tenant, f.pharmacist.userId(), centralId, body);
        assertThat(again.created()).isFalse();
        assertThat(again.view().id()).isEqualTo(first.id());
        assertThat(x.importWrites).isEmpty();
        assertThat(f.audit).isEmpty();

        assertConflict(() -> x.openingStock.start(tenant, secondPharmacist.userId(), centralId, body), "client-key-reused");
    }

    @Test
    void aNewStartReplacesMyEarlierDraftOfTheSameStoreOnly() {
        OpeningStockImport first = start(f.pharmacist, centralId, 2);
        f.map(ward, f.pharmacist, StoreRole.STORE_USER);
        OpeningStockImport other = start(f.owner, ward.id().toString(), 1);
        f.audit.clear();

        OpeningStockImport second = start(f.pharmacist, centralId, 3);

        assertThat(get(f.pharmacist, first).status()).isEqualTo("REPLACED");
        assertThat(get(f.pharmacist, first).replacedByImportId()).isEqualTo(second.id());
        assertThat(second.status()).isEqualTo("DRAFT");
        assertThat(x.importRows.get(other.id()).row.status().name()).isEqualTo("DRAFT");
        assertThat(f.audit.get(0).after()).containsEntry("replacedImportId", first.id().toString());
        // a replaced import cannot be changed or submitted
        assertConflict(() -> send(f.pharmacist, first, good(1, "U-SUN-0001", "B1")), "import-state");
        assertConflict(() -> submit(f.pharmacist, first), "import-state");
    }

    // ---- rows ----

    @Test
    void goodRowsAreStoredAndAnswerNoIssuesAndAWarningWhenTheRateIsAboveTheMrp() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        PharmacyViews.LinesResult result = send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"),
                row(2, "cs", " u-sun-0002 ", "B2", "2028-01-31", "10.00", "12.00", "7"));

        assertThat(result.receivedRows()).isEqualTo(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.issues()).extracting(i -> i.rowNo() + ":" + i.field() + ":" + i.code() + ":" + i.severity())
                .containsExactly("2:purchaseRate:rate-above-mrp:WARNING");
        assertThat(result.issues().get(0).message()).isEqualTo("Purchase rate is higher than MRP");
        OpeningStockImport live = get(f.pharmacist, imp);
        assertThat(live.receivedRows()).isEqualTo(2);
        assertThat(live.totalQuantity()).isEqualTo(107);
        assertThat(live.totalValuePaise()).isEqualTo(302500 + 8400);
        assertThat(live.warningCount()).isEqualTo(1);
        assertThat(live.errorCount()).isZero();
    }

    @Test
    void everyBadCellIsAnIssueOnItsRowAndNeverA400() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 8);
        PharmacyViews.LinesResult result = send(f.pharmacist, imp,
                row(1, "OTHER", "U-SUN-0001", "B1", "31-12-2027", "45.50", "30.25", "100"),
                row(2, "CS", "NOPE-1", "B1", "31-12-2027", "45.50", "30.25", "100"),
                row(3, "CS", "U-SUN-0001", " ", "31-12-2027", "45.50", "30.25", "100"),
                row(4, "CS", "U-SUN-0001", "B".repeat(21), "31-12-2027", "45.50", "30.25", "100"),
                row(5, "CS", "U-SUN-0001", "B5", "soon", "45.50", "30.25", "100"),
                row(6, "CS", "U-SUN-0001", "B6", "17-11-2026", "45.505", "0", "100"),
                row(7, "CS", "U-SUN-0001", "B7", "31-12-2027", "45.50", "30.25", "1.5"),
                row(8, "CS", "U-SUN-0001", "B8", "31-12-2027", "100000000", "30.25", "0"));

        assertThat(result.issues()).extracting(i -> i.rowNo() + ":" + i.field() + ":" + i.code()).containsExactly(
                "1:storeCode:store-mismatch", "2:productCode:product-unknown", "3:batch:batch-invalid",
                "4:batch:batch-invalid", "5:expiryDate:expiry-invalid", "6:expiryDate:expiry-past", "6:mrp:mrp-invalid",
                "6:purchaseRate:rate-invalid", "7:quantity:quantity-invalid", "8:mrp:mrp-invalid",
                "8:quantity:quantity-invalid");
        assertThat(result.issues()).allMatch(i -> i.severity().equals("ERROR") && !i.message().isBlank());
        assertThat(result.issues().stream().filter(i -> i.code().equals("expiry-past")).findFirst().orElseThrow().message())
                .isEqualTo("Expiry date must be in the future");
        assertThat(get(f.pharmacist, imp).errorCount()).isEqualTo(8);
    }

    @Test
    void theExpiryFormsAreAcceptedAndTodayIsAlreadyPast() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 6);
        PharmacyViews.LinesResult result = send(f.pharmacist, imp,
                row(1, "CS", "U-SUN-0001", "B1", "31-12-2027", "9", "8", "1"),
                row(2, "CS", "U-SUN-0001", "B2", "31/12/2027", "9", "8", "1"),
                row(3, "CS", "U-SUN-0001", "B3", "2027-12-31", "9", "8", "1"),
                row(4, "CS", "U-SUN-0001", "B4", "12/2027", "9", "8", "1"),
                row(5, "CS", "U-SUN-0001", "B5", "12-2027", "9", "8", "1"),
                row(6, "CS", "U-SUN-0001", "B6", "17-11-2026", "9", "8", "1"));
        assertThat(result.issues()).extracting(i -> i.rowNo() + ":" + i.code()).containsExactly("6:expiry-past");
        PharmacyViews.OpeningStockLinePage page = x.openingStock.lines(tenant, f.pharmacist.userId(), centralId,
                imp.id().toString(), null, null, null);
        assertThat(page.items()).extracting(l -> l.expiryDate().toString()).containsExactly("2027-12-31", "2027-12-31",
                "2027-12-31", "2027-12-31", "2027-12-31", "2026-11-17");
    }

    @Test
    void sendingARowAgainReplacesItAndItsOldIssuesStopCounting() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        assertThat(send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", "", "31-12-2027", "45.50", "30.25", "100")).issues())
                .hasSize(1);
        assertThat(get(f.pharmacist, imp).errorCount()).isEqualTo(1);

        PharmacyViews.LinesResult fixed = send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));

        assertThat(fixed.issues()).isEmpty();
        assertThat(fixed.receivedRows()).isEqualTo(1);
        assertThat(get(f.pharmacist, imp).errorCount()).isZero();
        assertThat(x.lineRows).hasSize(1);
        assertThat(x.issueRows).hasSize(1);
        assertThat(x.issueRows.get(0).superseded()).isTrue();
        assertThat(x.openingStock.lines(tenant, f.pharmacist.userId(), centralId, imp.id().toString(), "true", null, null)
                .items()).isEmpty();
    }

    @Test
    void theIssuesOfARowAreMarkedSupersededBeforeTheNewRowAndIssuesAreWritten() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", "", "31-12-2027", "45.50", "30.25", "100"));
        x.importWrites.clear();

        // one cell differs (the quantity), so the row is written again; the empty batch is still an issue
        send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", "", "31-12-2027", "45.50", "30.25", "101"));

        assertThat(x.importWrites).containsExactly("issue.supersede", "line.upsert", "issue.insert", "import.rowsWritten:1");
    }

    @Test
    void theLinesPageShowsTheParsedValuesTheProductNameAndOnlyTheProblemRowsOnRequest() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 3);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), row(2, "CS", "U-SUN-0002", "B2", "31-12-2027", "20", "12.5", "5"),
                row(3, "CS", "U-SUN-0001", "B3", "bad", "20", "12.5", "5"));

        PharmacyViews.OpeningStockLinePage all = x.openingStock.lines(tenant, f.pharmacist.userId(), centralId,
                imp.id().toString(), null, null, null);
        assertThat(all.total()).isEqualTo(3);
        PharmacyViews.OpeningStockLine first = all.items().get(0);
        assertThat(first.rowNo()).isEqualTo(1);
        assertThat(first.productId()).isEqualTo(kit.productId());
        assertThat(first.productName()).isEqualTo("Test dressing kit (OT)");
        assertThat(first.batch()).isEqualTo("B1");
        assertThat(first.mrp()).isEqualTo("45.50");
        assertThat(first.purchaseRate()).isEqualTo("30.25");
        assertThat(first.quantity()).isEqualTo(100);
        assertThat(first.valuePaise()).isEqualTo(302500L);
        assertThat(first.issues()).isEmpty();
        assertThat(all.items().get(1).purchaseRate()).isEqualTo("12.50");
        assertThat(all.items().get(2).expiryDate()).isNull();
        assertThat(all.items().get(2).issues()).extracting(PharmacyViews.RowIssue::code).containsExactly("expiry-invalid");

        PharmacyViews.OpeningStockLinePage problems = x.openingStock.lines(tenant, f.pharmacist.userId(), centralId,
                imp.id().toString(), "true", "0", "1");
        assertThat(problems.items()).extracting(PharmacyViews.OpeningStockLine::rowNo).containsExactly(3);
        assertThat(problems.total()).isEqualTo(1);
        assertThat(problems.size()).isEqualTo(1);
    }

    @Test
    void theBodyFieldsAreCheckedTogetherAfterTheGateAndNothingIsStored() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        List<ImportRow> rows = new ArrayList<>();
        rows.add(row(3, "CS", "U-SUN-0001", "B1", "31-12-2027", "1", "1", "1"));
        rows.add(row(1, "CS", "U-SUN-0001", "B".repeat(41), "31-12-2027", "1", "1", "1"));
        rows.add(row(1, "CS", "U-SUN-0001", "B2", "31-12-2027", "1", "1", "1"));
        rows.add(new ImportRow(new BigDecimal("1.5"), null, "U-SUN-0001", "B1", "31-12-2027", "1", "1", "1"));
        assertThatThrownBy(() -> x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.ImportLines(rows))).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message()).containsExactlyInAnyOrder(
                        "rows[0].rowNo: Enter a row number from 1 to 2", "rows[1].batch: Keep it to 40 characters",
                        "rows[2].rowNo: Send each row number once", "rows[3].rowNo: Enter a row number from 1 to 2",
                        "rows[3].storeCode: This field is required"));
        for (PharmacyInputs.ImportLines bad : new PharmacyInputs.ImportLines[] {new PharmacyInputs.ImportLines(null),
                new PharmacyInputs.ImportLines(List.of()),
                new PharmacyInputs.ImportLines(java.util.Collections.nCopies(201, good(1, "U-SUN-0001", "B1")))}) {
            assertThatThrownBy(() -> x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId, imp.id().toString(),
                    bad)).isInstanceOf(InvalidFieldsException.class);
        }
        assertThat(x.lineRows).isEmpty();
    }

    @Test
    void twoHundredRowsAreAccepted() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 400);
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            rows.add(good(i, "U-SUN-0001", "B" + i));
        }
        PharmacyViews.LinesResult result = x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId,
                imp.id().toString(), new PharmacyInputs.ImportLines(rows));
        assertThat(result.receivedRows()).isEqualTo(200);
        assertThat(result.rowCount()).isEqualTo(400);
    }

    // ---- submit ----

    @Test
    void submitFixesTheTotalsAndSendsTheFileForApprovalWithTheStatusWrittenLast() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"),
                row(2, "CS", "U-SUN-0002", "B2", "01/2028", "20", "12.50", "50"));
        x.importWrites.clear();
        f.audit.clear();

        OpeningStockImport waiting = submit(f.pharmacist, imp);

        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.lineCount()).isEqualTo(2);
        assertThat(waiting.totalQuantity()).isEqualTo(150);
        assertThat(waiting.totalValuePaise()).isEqualTo(302500L + 62500L);
        assertThat(waiting.submittedAt()).isEqualTo(PharmacyFixture.NOW);
        assertThat(waiting.errorCount()).isZero();
        assertThat(x.importWrites).containsExactly("import.submitted:WAITING_APPROVAL");
        assertThat(f.auditActions()).containsExactly("PHARMACY_OPENING_STOCK_SUBMITTED");
        assertThat(f.audit.get(0).after()).containsEntry("lineCount", 2).containsEntry("totalQuantity", 150L)
                .containsEntry("totalValuePaise", 365000L);
        // the fixed figures no longer follow the lines
        assertThat(get(f.owner, waiting).totalQuantity()).isEqualTo(150);
    }

    @Test
    void aFileWithAnyRowErrorBecomesNotAcceptedAndAnswers422WithTheFirstProblems() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 3);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), row(2, "CS", "NOPE", "B2", "31-12-2027", "1", "1", "1"),
                row(3, "CS", "U-SUN-0001", "B3", "31-12-2027", "1", "1", "x"));
        f.audit.clear();

        assertThatThrownBy(() -> submit(f.pharmacist, imp)).isInstanceOfSatisfying(PharmacyProblemException.NotAccepted.class,
                ex -> {
                    assertThat(ex.status()).isEqualTo(422);
                    assertThat(ex.slug()).isEqualTo("opening-stock-invalid");
                    assertThat(ex.getMessage()).isEqualTo("2 rows have problems. Fix them and upload the file again.");
                    assertThat(ex.extensions()).containsEntry("errorCount", 2);
                    @SuppressWarnings("unchecked")
                    List<PharmacyViews.RowIssue> issues = (List<PharmacyViews.RowIssue>) ex.extensions().get("rowIssues");
                    assertThat(issues).extracting(i -> i.rowNo() + ":" + i.code()).containsExactly("2:product-unknown",
                            "3:quantity-invalid");
                });

        OpeningStockImport after = get(f.pharmacist, imp);
        assertThat(after.status()).isEqualTo("NOT_ACCEPTED");
        assertThat(after.errorCount()).isEqualTo(2);
        assertThat(after.submittedAt()).isNull();
        assertThat(f.auditActions()).containsExactly("PHARMACY_OPENING_STOCK_NOT_ACCEPTED");
        // final: no more rows, no second submit, and it cannot be approved
        assertConflict(() -> send(f.pharmacist, after, good(1, "U-SUN-0001", "B1")), "import-state");
        assertConflict(() -> submit(f.pharmacist, after), "import-state");
        assertConflict(() -> x.openingStock.approve(tenant, f.owner.userId(), centralId, imp.id().toString()), "import-state");
    }

    @Test
    void theSameProductAndBatchTwiceMakesBothRowsErrorsIgnoringCase() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 3);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "AB1"), good(2, "U-SUN-0001", "ab1"), good(3, "U-SUN-0002", "AB1"));

        assertThatThrownBy(() -> submit(f.pharmacist, imp)).isInstanceOfSatisfying(PharmacyProblemException.NotAccepted.class,
                ex -> {
                    assertThat(ex.extensions()).containsEntry("errorCount", 2);
                    @SuppressWarnings("unchecked")
                    List<PharmacyViews.RowIssue> issues = (List<PharmacyViews.RowIssue>) ex.extensions().get("rowIssues");
                    assertThat(issues).extracting(i -> i.rowNo() + ":" + i.field() + ":" + i.code())
                            .containsExactly("1:batch:batch-repeated", "2:batch:batch-repeated");
                });
        assertThat(x.importWrites.indexOf("issue.insert")).isLessThan(x.importWrites.indexOf("import.submitted:NOT_ACCEPTED"));
        assertThat(x.importWrites.get(x.importWrites.size() - 1)).isEqualTo("import.submitted:NOT_ACCEPTED");
    }

    @Test
    void submitIsRefusedWhenSomeRowsDidNotArriveAndTheImportStaysADraft() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 3);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), good(2, "U-SUN-0001", "B2"));
        assertThatThrownBy(() -> submit(f.pharmacist, imp)).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo("import-incomplete");
            assertThat(ex.getMessage()).isEqualTo("Some rows did not arrive. Upload the file again.");
        });
        assertThat(get(f.pharmacist, imp).status()).isEqualTo("DRAFT");
        send(f.pharmacist, imp, good(3, "U-SUN-0001", "B3"));
        assertThat(submit(f.pharmacist, imp).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void submitReplacesMyEarlierWaitingImportBeforeTheNewOneWaits() {
        OpeningStockImport first = waiting(f.pharmacist);
        OpeningStockImport second = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, second, good(1, "U-SUN-0001", "B9"));
        x.importWrites.clear();

        OpeningStockImport now = submit(f.pharmacist, second);

        assertThat(now.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(get(f.pharmacist, first).status()).isEqualTo("REPLACED");
        assertThat(get(f.pharmacist, first).replacedByImportId()).isEqualTo(second.id());
        // the replaced row is written before anything that creates a second waiting row (the fake refuses a second one)
        assertThat(x.importWrites).containsExactly("import.replaced", "import.submitted:WAITING_APPROVAL");
        assertThat(get(f.owner, first).canApprove()).isFalse();
        assertConflict(() -> x.openingStock.approve(tenant, f.owner.userId(), centralId, first.id().toString()), "import-state");
    }

    @Test
    void anotherPersonsWaitingImportBlocksAStartAndASubmitWithTheirName() {
        waiting(f.pharmacist);
        assertThatThrownBy(() -> start(secondPharmacist, centralId, 1)).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo("import-waiting");
            assertThat(ex.getMessage()).isEqualTo("An import for this store is waiting for approval by Test Pharmacist One.");
        });
    }

    @Test
    void aDraftStartedBeforeSomeoneElsesImportWaitsCannotBeSubmittedUntilItIsDecided() {
        OpeningStockImport mine = start(secondPharmacist, centralId, 1);
        send(secondPharmacist, mine, good(1, "U-SUN-0001", "B1"));
        OpeningStockImport theirs = waiting(f.pharmacist);
        assertConflict(() -> submit(secondPharmacist, mine), "import-waiting");
        x.openingStock.reject(tenant, f.owner.userId(), centralId, theirs.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null));
        assertThat(submit(secondPharmacist, mine).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void onlyTheStarterWritesToAnImportAndTheRefusalCarriesIdsOnly() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        f.audit.clear();

        assertThatThrownBy(() -> send(secondPharmacist, imp, good(1, "U-SUN-0001", "B1")))
                .isInstanceOfSatisfying(StoreAccessDeniedException.class, denied -> {
                    assertThat(denied.storeId()).isEqualTo(UUID.fromString(centralId));
                    assertThat(denied.capability()).isEqualTo(StoreCapability.UPLOAD_OPENING_STOCK);
                    assertThat(denied.membershipId()).isNotNull();
                });
        assertThatThrownBy(() -> submit(secondPharmacist, imp)).isInstanceOf(StoreAccessDeniedException.class);
        // the service writes no denial audit: the problem handler does, after the transaction (N-1)
        assertThat(f.auditActions()).doesNotContain("PHARMACY_STORE_ACCESS_DENIED");
        // the starter can still read it and so can the other mapped person
        assertThat(get(secondPharmacist, imp).id()).isEqualTo(imp.id());
    }

    // ---- approve and reject ----

    @Test
    void anotherOwnerApprovesAndTheStoreAndItsProductsGetTheStockFlag() {
        OpeningStockImport imp = waiting(f.pharmacist);
        assertThat(get(f.owner, imp).canApprove()).isTrue();
        assertThat(get(f.owner, imp).approveLockReason()).isNull();
        PharmacyViews.ProductDetail untouched = x.productService.create(tenant, f.owner.userId(),
                new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test unused kit", "Unused kit", "1 kit", "kit", "Kit", "kit",
                        BigDecimal.ONE, PharmacyInputs.Sent.of(null), true, "3005", "12",
                        PharmacyInputs.Sent.of(com.sahaj.pharmacy.domain.ProductSchedule.NONE), null, false)).view();
        f.audit.clear();
        x.importWrites.clear();

        OpeningStockImport approved = x.openingStock.approve(tenant, f.owner.userId(), centralId, imp.id().toString());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy().membershipId()).isEqualTo(f.owner.id());
        assertThat(approved.decidedBy().name()).isEqualTo("Test Owner One");
        assertThat(approved.decidedAt()).isEqualTo(PharmacyFixture.NOW);
        assertThat(approved.canApprove()).isFalse();
        assertThat(approved.posted()).isFalse();
        assertThat(f.storeRows.get(central.id()).row().hasStock()).isTrue();
        assertThat(x.productRows.get(kit.productId()).hasStock).isTrue();
        assertThat(x.productRows.get(glove.productId()).hasStock).isTrue();
        assertThat(x.productRows.get(untouched.productId()).hasStock).isFalse();
        assertThat(x.importWrites).containsExactly("import.approved", "products.hasStock");
        assertThat(f.auditActions()).containsExactly("PHARMACY_OPENING_STOCK_APPROVED");
        assertThat(f.audit.get(0).after()).containsEntry("uploadedBy", f.pharmacist.id().toString())
                .containsEntry("approvedBy", f.owner.id().toString()).containsEntry("totalValuePaise", 365000L);
    }

    @Test
    void nobodyApprovesTheirOwnUploadNotEvenTheOwnerWhoUploadedIt() {
        OpeningStockImport imp = start(f.owner, centralId, 1);
        send(f.owner, imp, good(1, "U-SUN-0001", "B1"));
        OpeningStockImport waiting = submit(f.owner, imp);
        assertThat(waiting.canApprove()).isFalse();
        assertThat(waiting.approveLockReason()).isEqualTo("SELF_UPLOADED");

        assertThatThrownBy(() -> x.openingStock.approve(tenant, f.owner.userId(), centralId, imp.id().toString()))
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("self-approval");
                    assertThat(ex.getMessage()).isEqualTo("Another owner must approve this import.");
                });
        assertThat(get(f.owner, imp).status()).isEqualTo("WAITING_APPROVAL");
        assertThat(f.storeRows.get(central.id()).row().hasStock()).isFalse();

        assertThat(get(secondOwner, imp).canApprove()).isTrue();
        assertThat(x.openingStock.approve(tenant, secondOwner.userId(), centralId, imp.id().toString()).status())
                .isEqualTo("APPROVED");
    }

    @Test
    void aPharmacistSeesTheLockReasonNotOwnerAndCannotApproveOrReject() {
        OpeningStockImport imp = waiting(f.pharmacist);
        OpeningStockImport asPharmacist = get(f.pharmacist, imp);
        assertThat(asPharmacist.canApprove()).isFalse();
        assertThat(asPharmacist.approveLockReason()).isEqualTo("NOT_OWNER");
        assertThat(get(secondPharmacist, imp).approveLockReason()).isEqualTo("NOT_OWNER");

        assertThatThrownBy(() -> x.openingStock.approve(tenant, f.pharmacist.userId(), centralId, imp.id().toString()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.pharmacist.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null))).isInstanceOf(ForbiddenException.class);
        assertThat(get(f.owner, imp).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void anApprovedStoreCannotStartOrSubmitAnotherImportAndAnApprovedImportIsFinal() {
        OpeningStockImport imp = waiting(f.pharmacist);
        x.openingStock.approve(tenant, f.owner.userId(), centralId, imp.id().toString());

        assertThatThrownBy(() -> start(f.pharmacist, centralId, 1)).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo("opening-stock-already-approved");
            assertThat(ex.getMessage()).isEqualTo("Opening stock was already approved for this store.");
        });
        assertConflict(() -> x.openingStock.approve(tenant, secondOwner.userId(), centralId, imp.id().toString()),
                "import-state", "This import is not waiting for approval.");
        assertConflict(() -> x.openingStock.reject(tenant, secondOwner.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null)), "import-state",
                "This import can no longer be changed.");
        assertConflict(() -> send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1")), "import-state");
    }

    @Test
    void aDraftStartedBeforeAnApprovalCannotBeSubmittedAfterIt() {
        OpeningStockImport late = start(secondPharmacist, centralId, 1);
        send(secondPharmacist, late, good(1, "U-SUN-0001", "B7"));
        OpeningStockImport imp = waiting(f.pharmacist);
        x.openingStock.approve(tenant, f.owner.userId(), centralId, imp.id().toString());
        assertConflict(() -> submit(secondPharmacist, late), "opening-stock-already-approved");
    }

    @Test
    void rejectNeedsAReasonAndOtherNeedsANoteOfFourCharacters() {
        OpeningStockImport imp = waiting(f.pharmacist);
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.owner.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(null, null))).isInstanceOfSatisfying(InvalidFieldsException.class,
                ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .containsExactly("reasonKey: Choose a reason"));
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.owner.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.OTHER, " ab "))).isInstanceOfSatisfying(
                InvalidFieldsException.class, ex -> assertThat(ex.errors()).extracting(e -> e.field() + ": " + e.message())
                        .containsExactly("note: Write at least 4 characters"));
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.owner.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.OTHER, "x".repeat(301)))).isInstanceOf(InvalidFieldsException.class);
        assertThat(get(f.owner, imp).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void anyOwnerMayRejectEvenTheUploaderAndTheUploaderMayThenStartAgain() {
        OpeningStockImport imp = start(f.owner, centralId, 1);
        send(f.owner, imp, good(1, "U-SUN-0001", "B1"));
        submit(f.owner, imp);
        f.audit.clear();

        OpeningStockImport rejected = x.openingStock.reject(tenant, f.owner.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.OTHER, " Wrong batch numbers "));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectReasonKey()).isEqualTo("OTHER");
        assertThat(rejected.rejectNote()).isEqualTo("Wrong batch numbers");
        assertThat(rejected.decidedBy().membershipId()).isEqualTo(f.owner.id());
        assertThat(f.auditActions()).containsExactly("PHARMACY_OPENING_STOCK_REJECTED");
        assertThat(f.storeRows.get(central.id()).row().hasStock()).isFalse();
        assertThat(start(f.owner, centralId, 1).status()).isEqualTo("DRAFT");
    }

    @Test
    void aReasonOtherThanOtherNeedsNoNote() {
        OpeningStockImport draft = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, draft, good(1, "U-SUN-0001", "B1"));
        submit(f.pharmacist, draft);
        OpeningStockImport rejected = x.openingStock.reject(tenant, secondOwner.userId(), centralId, draft.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_STORE, null));
        assertThat(rejected.rejectNote()).isNull();
        assertThat(rejected.rejectReasonKey()).isEqualTo("WRONG_STORE");
    }

    @Test
    void anOwnerMayRejectADraftAsWellAsAWaitingImportAndAnyOtherStateIsImportState() {
        // DEC-076: a draft nobody finishes must not hold the store and its products
        OpeningStockImport draft = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, draft, good(1, "U-SUN-0001", "B1"));
        f.audit.clear();
        OpeningStockImport rejected = x.openingStock.reject(tenant, secondOwner.userId(), centralId, draft.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null));

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.submittedAt()).isNull();
        assertThat(rejected.decidedBy().membershipId()).isEqualTo(secondOwner.id());
        assertThat(f.audit).hasSize(1);
        assertThat(f.audit.get(0).action()).isEqualTo("PHARMACY_OPENING_STOCK_REJECTED");
        assertThat(f.audit.get(0).before()).isEqualTo(java.util.Map.of("status", "DRAFT"));
        assertThat(f.audit.get(0).after().keySet()).containsExactlyInAnyOrder("status", "storeId", "reasonKey", "note");
        // a file that is already rejected, replaced or not accepted is not open: import-state
        assertConflict(() -> x.openingStock.reject(tenant, secondOwner.userId(), centralId, draft.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null)), "import-state",
                "This import can no longer be changed.");
        OpeningStockImport first = start(f.pharmacist, centralId, 1);
        start(f.pharmacist, centralId, 1);
        assertThat(get(f.owner, first).status()).isEqualTo("REPLACED");
        assertConflict(() -> x.openingStock.reject(tenant, secondOwner.userId(), centralId, first.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null)), "import-state",
                "This import can no longer be changed.");
    }

    @Test
    void thoseWhoMayOnlyUploadCannotRejectTheirOwnDraftAndTheStoreIsFreedAtOnceByAnOwnersReject() {
        OpeningStockImport draft = start(f.pharmacist, centralId, 1);
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.pharmacist.userId(), centralId, draft.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null))).isInstanceOf(ForbiddenException.class);
        assertThat(get(f.owner, draft).status()).isEqualTo("DRAFT");
        assertThat(f.storeRepository.hasOpenImport(tenant, central.id())).isTrue();

        x.openingStock.reject(tenant, f.owner.userId(), centralId, draft.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null));

        assertThat(f.storeRepository.hasOpenImport(tenant, central.id())).isFalse();
        // nobody writes to a rejected draft any more
        assertConflict(() -> send(f.pharmacist, draft, good(1, "U-SUN-0001", "B1")), "import-state");
        assertConflict(() -> submit(f.pharmacist, draft), "import-state");
    }

    // ---- who may do what, and the order of the checks ----

    @Test
    void aMappedPersonWithoutTheUploadCapabilityIs403AndAnUnmappedStoreIs404() {
        f.map(ward, f.nurse, StoreRole.STORE_USER);
        f.audit.clear();
        assertThatThrownBy(() -> start(f.nurse, ward.id().toString(), 1)).isInstanceOf(StoreAccessDeniedException.class);
        assertThat(f.auditActions()).doesNotContain("PHARMACY_STORE_ACCESS_DENIED");
        f.audit.clear();
        // a pharmacist of the clinic who is not mapped to the ward store, and a store that does not exist
        assertThatThrownBy(() -> start(f.pharmacist, ward.id().toString(), 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> start(f.pharmacist, UUID.randomUUID().toString(), 1)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> x.openingStock.listForStore(tenant, f.pharmacist.userId(), ward.id().toString(), null, null))
                .isInstanceOf(NotFoundException.class);
        assertThat(f.audit).isEmpty();
        assertThat(x.importRows).isEmpty();
    }

    @Test
    void theOwnerReachesEveryStoreAndAnotherClinicsImportIs404() {
        OpeningStockImport imp = start(f.owner, ward.id().toString(), 1);
        assertThat(imp.storeId()).isEqualTo(ward.id());
        x.importRows.get(imp.id()).tenant = PharmacyFixture.OTHER_TENANT;
        assertThatThrownBy(() -> get(f.owner, imp)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> x.openingStock.approve(tenant, secondOwner.userId(), ward.id().toString(),
                imp.id().toString())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anImportOfAnotherStoreIs404UnderThisStoresPath() {
        OpeningStockImport imp = start(f.owner, ward.id().toString(), 1);
        assertThatThrownBy(() -> x.openingStock.get(tenant, f.owner.userId(), centralId, imp.id().toString()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> x.openingStock.lines(tenant, f.owner.userId(), centralId, imp.id().toString(), null, null,
                null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theContractOrderModuleOffThenAccessLevelThenPathIdThenStore() {
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.openingStock.get(tenant, f.owner.userId(), "nope", "nope")).isInstanceOf(ModuleOffException.class);
        f.pharmacyOn = true;
        assertThatThrownBy(() -> x.openingStock.get(tenant, f.frontDesk.userId(), "nope", "nope")).isInstanceOf(ForbiddenException.class);
        for (Runnable bad : List.<Runnable>of(
                () -> x.openingStock.get(tenant, f.owner.userId(), "nope", UUID.randomUUID().toString()),
                () -> x.openingStock.get(tenant, f.owner.userId(), centralId, "nope"),
                () -> x.openingStock.listForStore(tenant, f.owner.userId(), "nope", null, null),
                () -> x.openingStock.submit(tenant, f.owner.userId(), centralId, "nope"),
                () -> x.openingStock.approve(tenant, f.owner.userId(), "nope", "nope"))) {
            assertThatThrownBy(bad::run).isInstanceOfSatisfying(PharmacyProblemException.class,
                    ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
        }
        // approve and reject are owner-only before anything else
        for (PharmacyFixture.Person p : new PharmacyFixture.Person[] {f.pharmacist, f.nurse}) {
            assertThatThrownBy(() -> x.openingStock.approve(tenant, p.userId(), "nope", "nope")).isInstanceOf(ForbiddenException.class);
        }
        assertThatThrownBy(() -> x.openingStock.listAll(tenant, f.pharmacist.userId(), null, null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void queryValuesAreCheckedAfterTheStoreGuard() {
        assertThatThrownBy(() -> x.openingStock.listForStore(tenant, f.pharmacist.userId(), centralId, "-1", null))
                .isInstanceOfSatisfying(InvalidFieldsException.class, ex -> assertThat(ex.errors().get(0).field()).isEqualTo("page"));
        assertThatThrownBy(() -> x.openingStock.listForStore(tenant, f.pharmacist.userId(), centralId, null, "51"))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> x.openingStock.listAll(tenant, f.owner.userId(), "MAYBE", null, null))
                .isInstanceOf(InvalidFieldsException.class);
        assertThatThrownBy(() -> x.openingStock.lines(tenant, f.owner.userId(), centralId, UUID.randomUUID().toString(),
                "maybe", null, null)).isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void aBodyIsNeverReadBeforeTheGateAndThePathIdsHaveAnswered() {
        PharmacyInputs.Body<PharmacyInputs.StartImport> startBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        PharmacyInputs.Body<PharmacyInputs.ImportLines> rowsBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        PharmacyInputs.Body<PharmacyInputs.RejectImport> rejectBody = () -> {
            throw new IllegalStateException("the body was read");
        };
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> x.openingStock.start(tenant, f.frontDesk.userId(), centralId, startBody))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.openingStock.addLines(tenant, f.frontDesk.userId(), centralId, id, rowsBody))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.openingStock.reject(tenant, f.pharmacist.userId(), centralId, id, rejectBody))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> x.openingStock.start(tenant, f.pharmacist.userId(), "nope", startBody))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
        assertThatThrownBy(() -> x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId, "nope", rowsBody))
                .isInstanceOfSatisfying(PharmacyProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
        f.pharmacyOn = false;
        assertThatThrownBy(() -> x.openingStock.start(tenant, f.owner.userId(), centralId, startBody))
                .isInstanceOf(ModuleOffException.class);
    }

    // ---- lists ----

    @Test
    void theOwnersListShowsEveryStoreNewestFirstAndFiltersByStatus() {
        OpeningStockImport first = waiting(f.pharmacist);
        OpeningStockImport draft = start(f.owner, ward.id().toString(), 1);

        PharmacyViews.ImportPage all = x.openingStock.listAll(tenant, f.owner.userId(), null, null, null);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).extracting(OpeningStockImport::id).containsExactly(draft.id(), first.id());
        assertThat(all.items().get(0).storeName()).isEqualTo("Test Ward 3B Store");
        assertThat(all.size()).isEqualTo(20);

        PharmacyViews.ImportPage waitingOnly = x.openingStock.listAll(tenant, f.owner.userId(), "WAITING_APPROVAL", "0", "5");
        assertThat(waitingOnly.items()).extracting(OpeningStockImport::id).containsExactly(first.id());
        assertThat(waitingOnly.items().get(0).canApprove()).isTrue();
        assertThat(waitingOnly.size()).isEqualTo(5);
    }

    @Test
    void aStoresListIsForThatStoreOnlyAndAnotherClinicsImportsNeverAppear() {
        OpeningStockImport mine = waiting(f.pharmacist);
        OpeningStockImport foreign = start(f.owner, ward.id().toString(), 1);
        x.importRows.get(foreign.id()).tenant = PharmacyFixture.OTHER_TENANT;

        PharmacyViews.ImportPage page = x.openingStock.listForStore(tenant, f.pharmacist.userId(), centralId, null, null);
        assertThat(page.items()).extracting(OpeningStockImport::id).containsExactly(mine.id());
        assertThat(x.openingStock.listAll(tenant, f.owner.userId(), null, null, null).total()).isEqualTo(1);
    }

    @Test
    void nothingPatientRelatedOrPersonalIsInTheImportAnswer() {
        assertThat(OpeningStockImport.class.getRecordComponents()).extracting(c -> c.getName())
                .noneMatch(n -> n.toLowerCase().contains("mobile") || n.toLowerCase().contains("phone"));
        assertThat(PharmacyViews.Person.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("membershipId", "name");
    }
}
