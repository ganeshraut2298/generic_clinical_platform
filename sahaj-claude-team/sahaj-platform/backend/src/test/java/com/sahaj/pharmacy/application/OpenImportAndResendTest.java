package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahaj.pharmacy.application.PharmacyInputs.ImportRow;
import com.sahaj.pharmacy.application.PharmacyViews.OpeningStockImport;
import com.sahaj.pharmacy.domain.OpeningStockCsv;
import com.sahaj.pharmacy.domain.ProductSchedule;
import com.sahaj.pharmacy.domain.RejectReason;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.ConflictException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-78 review round 2. N-2 (DEC-071 option A, contract 0.1.3 rule 14): while an opening-stock import is DRAFT or
 * WAITING_APPROVAL, its store cannot be switched off or get a new code, and a product on one of its lines cannot be switched
 * off or get other units or conversion; 409 {@code open-import}, nothing written. L8 (security): a row sent again with the same
 * seven cells writes nothing.
 */
class OpenImportAndResendTest {

    private final PharmacyProductFixture x = new PharmacyProductFixture();
    private final PharmacyFixture f = x.f;
    private final UUID tenant = PharmacyFixture.TENANT;
    private final PharmacyViews.Store central = f.createCentral();
    private final PharmacyViews.Store ward = f.createStore("Test Ward 3B Store", "W3B", StoreType.WARD);
    private final PharmacyFixture.Person secondOwner = f.person("OWNER", "Test Owner Two", true);
    private final String centralId = central.id().toString();

    private final PharmacyViews.ProductDetail kit = x.productService.create(tenant, f.owner.userId(),
            new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test dressing kit (OT)", "Dressing kit", "1 kit", "kit", "Kit",
                    "kit", BigDecimal.ONE, PharmacyInputs.Sent.of(null), true, "3005", "12",
                    PharmacyInputs.Sent.of(ProductSchedule.NONE), null, false)).view();
    private final PharmacyViews.ProductDetail glove = x.productService.create(tenant, f.owner.userId(),
            new PharmacyInputs.OwnProduct(UUID.randomUUID(), "Test gloves (M)", "Surgical gloves", "M", "device", "Box",
                    "pair", BigDecimal.TEN, PharmacyInputs.Sent.of(null), true, "4015", "12",
                    PharmacyInputs.Sent.of(ProductSchedule.NONE), null, false)).view();

    {
        f.map(central, f.pharmacist, StoreRole.CENTRAL_STORE_USER);
    }

    // ---- helpers ----

    private static ImportRow row(int no, String store, String product, String batch, String expiry, String mrp, String rate,
                                 String qty) {
        return new ImportRow(BigDecimal.valueOf(no), store, product, batch, expiry, mrp, rate, qty);
    }

    private static ImportRow good(int no, String product, String batch) {
        return row(no, "CS", product, batch, "31-12-2027", "45.50", "30.25", "100");
    }

    private OpeningStockImport start(PharmacyFixture.Person p, String storeId, int rowCount) {
        return x.openingStock.start(tenant, p.userId(), storeId,
                new PharmacyInputs.StartImport(UUID.randomUUID(), OpeningStockCsv.HEADER, BigDecimal.valueOf(rowCount))).view();
    }

    private PharmacyViews.LinesResult send(PharmacyFixture.Person p, OpeningStockImport imp, ImportRow... rows) {
        return x.openingStock.addLines(tenant, p.userId(), imp.storeId().toString(), imp.id().toString(),
                new PharmacyInputs.ImportLines(List.of(rows)));
    }

    private OpeningStockImport submit(PharmacyFixture.Person p, OpeningStockImport imp) {
        return x.openingStock.submit(tenant, p.userId(), imp.storeId().toString(), imp.id().toString());
    }

    // ---- L8: a row sent again unchanged writes nothing ----

    @Test
    void aRowSentAgainWithTheSameSevenCellsWritesNothingAndAnswersTheSameIssues() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        PharmacyViews.LinesResult first = send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"),
                row(2, "CS", "U-SUN-0002", "B2", "soon", "15.00", "12.00", "7"));
        assertThat(first.issues()).extracting(i -> i.rowNo() + ":" + i.code()).containsExactly("2:expiry-invalid");
        x.importWrites.clear();
        int issuesBefore = x.issueRows.size();
        List<Integer> versionsBefore = x.lineRows.stream().map(l -> l.version).toList();

        PharmacyViews.LinesResult again = send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"),
                row(2, "CS", "U-SUN-0002", "B2", "soon", "15.00", "12.00", "7"));

        assertThat(x.importWrites).isEmpty();
        assertThat(x.issueRows).hasSize(issuesBefore);
        assertThat(x.issueRows).noneMatch(PharmacyProductFixture.Issue::superseded);
        assertThat(x.lineRows.stream().map(l -> l.version).toList()).isEqualTo(versionsBefore);
        assertThat(again.issues()).isEqualTo(first.issues());
        assertThat(again.receivedRows()).isEqualTo(2);
    }

    @Test
    void aChangedCellIsWrittenAgainWhileTheUnchangedRowOfTheSameRequestIsNot() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), row(2, "CS", "U-SUN-0002", "B2", "soon", "15.00", "12.00", "7"));
        x.importWrites.clear();

        PharmacyViews.LinesResult again = send(f.pharmacist, imp,
                row(2, "CS", "U-SUN-0002", "B2", "soon", "15.00", "12.00", "7"),
                row(1, "CS", "U-SUN-0001", "B1", "31-12-2027", "45.50", "30.25", "101"));

        // only row 1 (one cell changed) was replaced; row 2 is as it was, and the answer lists rows in the order sent
        assertThat(x.importWrites).containsExactly("issue.supersede", "line.upsert", "import.rowsWritten:1");
        assertThat(again.issues()).extracting(i -> i.rowNo() + ":" + i.code()).containsExactly("2:expiry-invalid");
        assertThat(x.lineRows.stream().filter(l -> l.in.rowNo() == 1).findFirst().orElseThrow().in.raw().quantity())
                .isEqualTo("101");
        assertThat(x.issueRows.stream().filter(i -> !i.superseded()).map(i -> i.rowNo() + ":" + i.code()).toList())
                .containsExactly("2:expiry-invalid");
    }

    @Test
    void aCellThatDiffersOnlyByCaseIsAChangeBecauseTheRawTextIsWhatIsStored() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        x.importWrites.clear();
        send(f.pharmacist, imp, row(1, "CS", "u-sun-0001", "B1", "31-12-2027", "45.50", "30.25", "100"));
        assertThat(x.importWrites).contains("line.upsert");
    }

    // ---- DEC-077 I7 and L8(b): the cap counts changed rows written, at most 2 x rowCount per import ----

    private static void assertImportLocked(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo("import-state");
            assertThat(ex.getMessage()).isEqualTo("This import can no longer be changed.");
        });
    }

    private int rowsWritten(OpeningStockImport imp) {
        return x.lineRowsWritten.getOrDefault(imp.id(), 0);
    }

    private static ImportRow changed(int no, String product, String batch) {
        return row(no, "CS", product, batch, "31-12-2027", "45.50", "30.25", "101");
    }

    @Test
    void theCapIsTwiceTheRowCount() {
        assertThat(OpeningStockService.rowCap(1)).isEqualTo(2);
        assertThat(OpeningStockService.rowCap(200)).isEqualTo(400);
        assertThat(OpeningStockService.rowCap(412)).isEqualTo(824);
        assertThat(OpeningStockService.rowCap(5000)).isEqualTo(10000);
    }

    @Test
    void aFileOf412RowsAllowsAFullPassAndAFullChangedResendInBatchesOf200ThenRefusesTheNextChangedRow() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 412);
        for (int from = 1; from <= 412; from += 200) {
            List<ImportRow> batch = new java.util.ArrayList<>();
            for (int no = from; no < from + 200 && no <= 412; no++) {
                batch.add(good(no, "U-SUN-0001", "B" + no));
            }
            send(f.pharmacist, imp, batch.toArray(new ImportRow[0]));
        }
        assertThat(rowsWritten(imp)).isEqualTo(412);
        for (int from = 1; from <= 412; from += 200) {
            List<ImportRow> batch = new java.util.ArrayList<>();
            for (int no = from; no < from + 200 && no <= 412; no++) {
                batch.add(changed(no, "U-SUN-0001", "B" + no));
            }
            send(f.pharmacist, imp, batch.toArray(new ImportRow[0]));
        }
        assertThat(rowsWritten(imp)).isEqualTo(824);
        x.importWrites.clear();
        int linesBefore = x.lineRows.size();
        int issuesBefore = x.issueRows.size();

        assertImportLocked(() -> send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", "B1", "31-12-2027", "45.50", "30.25", "102")));

        assertThat(x.importWrites).isEmpty();
        assertThat(x.lineRows).hasSize(linesBefore);
        assertThat(x.issueRows).hasSize(issuesBefore);
        assertThat(rowsWritten(imp)).isEqualTo(824);
        // the import is still a DRAFT and every row arrived, so submit still works
        assertThat(submit(f.pharmacist, imp).status()).isEqualTo("WAITING_APPROVAL");
    }

    @Test
    void aRequestThatWouldPassTheCapIsRefusedWholeAndAnySmallerOneStillFits() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), good(2, "U-SUN-0001", "B2"));
        send(f.pharmacist, imp, changed(1, "U-SUN-0001", "B1"));
        assertThat(rowsWritten(imp)).isEqualTo(3);
        x.importWrites.clear();
        List<String> quantitiesBefore = x.lineRows.stream().map(l -> l.in.raw().quantity()).toList();

        // two changed rows would make 5 of 4: the whole request is refused, not just the second row
        assertImportLocked(() -> send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", "B1", "31-12-2027", "45.50", "30.25", "103"),
                changed(2, "U-SUN-0001", "B2")));

        assertThat(x.importWrites).isEmpty();
        assertThat(x.lineRows.stream().map(l -> l.in.raw().quantity()).toList()).isEqualTo(quantitiesBefore);
        assertThat(rowsWritten(imp)).isEqualTo(3);
        // one changed row makes exactly 4 of 4 and is accepted; the cap is then used up
        send(f.pharmacist, imp, changed(2, "U-SUN-0001", "B2"));
        assertThat(rowsWritten(imp)).isEqualTo(4);
        assertImportLocked(() -> send(f.pharmacist, imp, row(2, "CS", "U-SUN-0001", "B2", "31-12-2027", "45.50", "30.25", "104")));
    }

    @Test
    void onceTheCapIsUsedUpEveryRequestAnswers409IncludingOneWithABadBodyAndTheCounterStays() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        send(f.pharmacist, imp, changed(1, "U-SUN-0001", "B1"));
        assertThat(rowsWritten(imp)).isEqualTo(2);

        // a row number outside the file, a missing cell, no rows, and a good unchanged row: all 409, none 400 or 200
        assertImportLocked(() -> send(f.pharmacist, imp, good(5, "U-SUN-0001", "B1")));
        assertImportLocked(() -> send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", null, "31-12-2027", "1", "1", "1")));
        assertImportLocked(() -> x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId, imp.id().toString(),
                new PharmacyInputs.ImportLines(List.of())));
        assertImportLocked(() -> send(f.pharmacist, imp, changed(1, "U-SUN-0001", "B1")));

        assertThat(rowsWritten(imp)).isEqualTo(2);
    }

    @Test
    void aRequestWhoseRowsAreAllUnchangedWritesNothingAndDoesNotCountSoRetriesNeverRunOutTheCap() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        x.importWrites.clear();

        for (int retry = 0; retry < 20; retry++) {
            send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        }

        assertThat(x.importWrites).isEmpty();
        assertThat(rowsWritten(imp)).isEqualTo(1);
        // the one changed row of a mixed request counts as one
        send(f.pharmacist, imp, changed(1, "U-SUN-0001", "B1"));
        assertThat(rowsWritten(imp)).isEqualTo(2);
        assertThat(x.importWrites).contains("import.rowsWritten:1");
    }

    @Test
    void requestsAnsweredWithAnErrorWriteNothingAndDoNotCount() {
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        // 400: a row number outside the file, and a cell that is missing
        assertThatThrownBy(() -> send(f.pharmacist, imp, good(3, "U-SUN-0001", "B1")))
                .isInstanceOf(com.sahaj.platform.api.InvalidFieldsException.class);
        assertThatThrownBy(() -> send(f.pharmacist, imp, row(1, "CS", "U-SUN-0001", null, "31-12-2027", "1", "1", "1")))
                .isInstanceOf(com.sahaj.platform.api.InvalidFieldsException.class);
        // 404: another import id
        assertThatThrownBy(() -> x.openingStock.addLines(tenant, f.pharmacist.userId(), centralId, UUID.randomUUID().toString(),
                new PharmacyInputs.ImportLines(List.of(good(1, "U-SUN-0001", "B1")))))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        assertThat(rowsWritten(imp)).isZero();
        // 200 counts its two changed rows; 409: a submitted import is refused on state and is not counted
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"), good(2, "U-SUN-0001", "B2"));
        assertThat(rowsWritten(imp)).isEqualTo(2);
        submit(f.pharmacist, imp);
        assertThatThrownBy(() -> send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"))).isInstanceOf(ConflictException.class);
        assertThat(rowsWritten(imp)).isEqualTo(2);
    }

    // ---- N-2: the store ----

    private static void assertOpenImport(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PharmacyProblemException.class, ex -> {
            assertThat(ex.status()).isEqualTo(409);
            assertThat(ex.slug()).isEqualTo("open-import");
            assertThat(ex.getMessage()).isEqualTo("Finish or reject the opening stock file of this store first.");
        });
    }

    private OpeningStockImport draftOfWard(String productCode) {
        OpeningStockImport imp = start(f.owner, ward.id().toString(), 1);
        send(f.owner, imp, row(1, "W3B", productCode, "B1", "31-12-2027", "45.50", "30.25", "100"));
        return imp;
    }

    private void rejectOfWard(OpeningStockImport imp) {
        x.openingStock.reject(tenant, secondOwner.userId(), ward.id().toString(), imp.id().toString(),
                new PharmacyInputs.RejectImport(RejectReason.WRONG_FILE, null));
    }

    private PharmacyViews.Store storeNow(PharmacyViews.Store store) {
        return f.storeService.get(tenant, f.owner.userId(), store.id());
    }

    private PharmacyInputs.StoreUpdate storeUpdate(PharmacyViews.Store s, String name, String code) {
        return PharmacyInputs.StoreUpdate.of(s.version(), name, code, s.type(), s.saleAllowed(), s.consumptionAllowed(),
                s.hod() == null ? null : s.hod().membershipId(), null);
    }

    @Test
    void aStoreWithADraftOrWaitingImportCannotBeSwitchedOffOrGetANewCode() {
        OpeningStockImport imp = draftOfWard("U-SUN-0001");
        for (int round = 0; round < 2; round++) {
            assertOpenImport(() -> f.storeService.switchOff(tenant, f.owner.userId(), ward.id()));
            PharmacyViews.Store now = storeNow(ward);
            assertOpenImport(() -> f.storeService.update(tenant, f.owner.userId(), ward.id(),
                    storeUpdate(now, now.name(), "W3C")));
            PharmacyViews.Store after = storeNow(ward);
            assertThat(after.status()).isEqualTo("ACTIVE");
            assertThat(after.code()).isEqualTo("W3B");
            assertThat(after.version()).isEqualTo(now.version());
            if (round == 0) {
                assertThat(submit(f.owner, imp).status()).isEqualTo("WAITING_APPROVAL");
            }
        }
    }

    @Test
    void theNameOfAStoreWithAnOpenImportCanStillChangeAndAnUnchangedCodeIsNotRefused() {
        draftOfWard("U-SUN-0001");
        PharmacyViews.Store now = storeNow(ward);
        PharmacyViews.Store renamed = f.storeService.update(tenant, f.owner.userId(), ward.id(),
                storeUpdate(now, "Test Ward 3B Store renamed", "W3B"));
        assertThat(renamed.name()).isEqualTo("Test Ward 3B Store renamed");
        assertThat(renamed.code()).isEqualTo("W3B");
    }

    @Test
    void whenTheImportIsRejectedOrNotAcceptedTheStoreIsFreeAgain() {
        OpeningStockImport waiting = draftOfWard("U-SUN-0001");
        submit(f.owner, waiting);
        assertOpenImport(() -> f.storeService.switchOff(tenant, f.owner.userId(), ward.id()));
        rejectOfWard(waiting);
        PharmacyViews.Store now = storeNow(ward);
        assertThat(f.storeService.update(tenant, f.owner.userId(), ward.id(), storeUpdate(now, now.name(), "W3C")).code())
                .isEqualTo("W3C");

        // a file with a row error is NOT_ACCEPTED, which is final: it does not hold the store
        OpeningStockImport bad = start(f.owner, ward.id().toString(), 1);
        send(f.owner, bad, row(1, "W3C", "NOPE-1", "B1", "31-12-2027", "45.50", "30.25", "100"));
        assertThatThrownBy(() -> submit(f.owner, bad)).isInstanceOf(PharmacyProblemException.NotAccepted.class);
        assertThat(x.importRows.get(bad.id()).row.status().name()).isEqualTo("NOT_ACCEPTED");
        assertThat(f.storeService.switchOff(tenant, f.owner.userId(), ward.id()).status()).isEqualTo("INACTIVE");
    }

    @Test
    void anotherStoreWithoutAnImportIsNotAffectedByTheOpenImportOfThisOne() {
        draftOfWard("U-SUN-0001");
        PharmacyViews.Store opd = f.createStore("Test OPD Store", "OPD", StoreType.SUB_STORE);
        assertThat(f.storeService.switchOff(tenant, f.owner.userId(), opd.id()).status()).isEqualTo("INACTIVE");
    }

    // ---- N-2: the product ----

    private PharmacyInputs.OwnProductChange kitChange(PharmacyViews.ProductDetail p, String name, String purchaseUnit,
                                                      String issueUnit, BigDecimal conversion) {
        return new PharmacyInputs.OwnProductChange(p.version(), name, "Dressing kit", "1 kit", "kit", purchaseUnit,
                issueUnit, conversion, PharmacyInputs.Sent.of(null), true, "3005", "12",
                PharmacyInputs.Sent.of(ProductSchedule.NONE), null, false);
    }

    private PharmacyInputs.LocalSettings localOf(PharmacyViews.ProductDetail p, boolean active) {
        return new PharmacyInputs.LocalSettings(p.version(), List.of(), PharmacyInputs.Sent.of(null),
                PharmacyInputs.Sent.of(null), PharmacyInputs.Sent.of(null), true, active);
    }

    @Test
    void aProductOnALineOfAnOpenImportOfAnyStoreCannotChangeUnitsOrConversionOrBeSwitchedOff() {
        // the kit is on a line of the ward store file; the gloves are on none
        OpeningStockImport imp = draftOfWard("U-SUN-0001");
        String id = kit.productId().toString();
        for (int round = 0; round < 2; round++) {
            PharmacyViews.ProductDetail now = x.searchService.get(tenant, f.owner.userId(), id);
            assertOpenImport(() -> x.productService.update(tenant, f.owner.userId(), id,
                    kitChange(now, now.name(), "Packet", "kit", BigDecimal.ONE)));
            assertOpenImport(() -> x.productService.update(tenant, f.owner.userId(), id,
                    kitChange(now, now.name(), "Kit", "piece", BigDecimal.ONE)));
            assertOpenImport(() -> x.productService.update(tenant, f.owner.userId(), id,
                    kitChange(now, now.name(), "Kit", "kit", BigDecimal.valueOf(2))));
            assertOpenImport(() -> x.productService.saveLocalSettings(tenant, f.owner.userId(), id, localOf(now, false)));
            PharmacyViews.ProductDetail after = x.searchService.get(tenant, f.owner.userId(), id);
            assertThat(after.version()).isEqualTo(now.version());
            assertThat(after.purchaseUnit()).isEqualTo("Kit");
            assertThat(after.inUnit()).isEqualTo("ACTIVE");
            if (round == 0) {
                submit(f.owner, imp);
            }
        }
        PharmacyViews.ProductDetail gloves = x.productService.saveLocalSettings(tenant, f.owner.userId(),
                glove.productId().toString(), localOf(glove, false));
        assertThat(gloves.inUnit()).isEqualTo("INACTIVE");
    }

    @Test
    void everyOtherFieldOfAProductOnAnOpenImportCanStillChange() {
        draftOfWard("U-SUN-0001");
        String id = kit.productId().toString();
        PharmacyViews.ProductDetail renamed = x.productService.update(tenant, f.owner.userId(), id,
                kitChange(kit, "Test dressing kit (OT) renamed", "Kit", "kit", BigDecimal.ONE));
        assertThat(renamed.name()).isEqualTo("Test dressing kit (OT) renamed");
        PharmacyViews.ProductDetail aliased = x.productService.saveLocalSettings(tenant, f.owner.userId(), id,
                new PharmacyInputs.LocalSettings(renamed.version(), List.of(), PharmacyInputs.Sent.of(null),
                        PharmacyInputs.Sent.of("OT kit"), PharmacyInputs.Sent.of(null), true, true));
        assertThat(aliased.alias()).isEqualTo("OT kit");
    }

    @Test
    void whenTheImportIsRejectedTheProductIsFreeAgain() {
        OpeningStockImport imp = draftOfWard("U-SUN-0001");
        submit(f.owner, imp);
        String id = kit.productId().toString();
        assertOpenImport(() -> x.productService.saveLocalSettings(tenant, f.owner.userId(), id, localOf(kit, false)));
        rejectOfWard(imp);
        assertThat(x.productService.saveLocalSettings(tenant, f.owner.userId(), id, localOf(kit, false)).inUnit())
                .isEqualTo("INACTIVE");
    }

    // ---- DEC-077 L9: the store lock is taken first, then the store is re-checked ACTIVE ----

    @Test
    void aStoreSwitchedOffBetweenTheGuardAndTheLockAnswers404ForStartSubmitAndApproveWithNothingWritten() {
        // start
        f.inactiveAtLock.add(central.id());
        assertThatThrownBy(() -> start(f.pharmacist, centralId, 1)).isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        assertThat(x.importRows).isEmpty();
        assertThat(x.importWrites).isEmpty();
        f.inactiveAtLock.clear();

        // submit
        OpeningStockImport draft = start(f.pharmacist, centralId, 1);
        send(f.pharmacist, draft, good(1, "U-SUN-0001", "B1"));
        x.importWrites.clear();
        f.inactiveAtLock.add(central.id());
        assertThatThrownBy(() -> submit(f.pharmacist, draft)).isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        assertThat(x.importRows.get(draft.id()).row.status().name()).isEqualTo("DRAFT");
        assertThat(x.importWrites).isEmpty();
        f.inactiveAtLock.clear();

        // approve
        OpeningStockImport waiting = submit(f.pharmacist, draft);
        f.inactiveAtLock.add(central.id());
        assertThatThrownBy(() -> x.openingStock.approve(tenant, secondOwner.userId(), centralId, waiting.id().toString()))
                .isInstanceOf(com.sahaj.platform.api.NotFoundException.class);
        assertThat(x.importRows.get(waiting.id()).row.status().name()).isEqualTo("WAITING_APPROVAL");
        assertThat(f.storeRows.get(central.id()).row().hasStock()).isFalse();
    }

    @Test
    void theLockOrderIsStoreThenImportThenProducts() {
        f.locks.clear();
        OpeningStockImport imp = start(f.pharmacist, centralId, 2);
        assertThat(f.locks).containsExactly("store", "drafts", "insert");

        f.locks.clear();
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        assertThat(f.locks).containsExactly("unit-share", "import", "product-share");

        f.locks.clear();
        send(f.pharmacist, imp, good(1, "U-SUN-0001", "B1"));
        assertThat(f.locks).as("an unchanged resend reads no product").containsExactly("unit-share", "import");

        send(f.pharmacist, imp, good(2, "U-SUN-0001", "B2"));
        f.locks.clear();
        submit(f.pharmacist, imp);
        assertThat(f.locks).containsExactly("store", "import");

        f.locks.clear();
        x.openingStock.approve(tenant, secondOwner.userId(), centralId, imp.id().toString());
        assertThat(f.locks).containsExactly("store", "import");
    }

    // ---- DEC-077 L10: product edits lock the product row first; submit flags a product switched off ----

    @Test
    void aProductEditLocksTheProductRowBeforeItsOpenImportCheck() {
        draftOfWard("U-SUN-0001");
        String id = kit.productId().toString();

        f.locks.clear();
        assertOpenImport(() -> x.productService.update(tenant, f.owner.userId(), id,
                kitChange(kit, kit.name(), "Packet", "kit", BigDecimal.ONE)));
        assertThat(f.locks).containsSubsequence("product", "open-import-check");

        f.locks.clear();
        assertOpenImport(() -> x.productService.saveLocalSettings(tenant, f.owner.userId(), id, localOf(kit, false)));
        assertThat(f.locks).containsSubsequence("product", "open-import-check");
        assertThat(f.locks.indexOf("product")).isLessThan(f.locks.indexOf("open-import-check"));
    }

    @Test
    void submitFlagsALineWhoseProductIsNoLongerActiveAsAnErrorProductUnknown() {
        OpeningStockImport imp = draftOfWard("U-SUN-0001");
        // the race of L10, as its result: the product was switched off after the row was written
        x.productRows.get(kit.productId()).active = false;

        assertThatThrownBy(() -> submit(f.owner, imp)).isInstanceOf(PharmacyProblemException.NotAccepted.class);
        assertThat(x.importRows.get(imp.id()).row.status().name()).isEqualTo("NOT_ACCEPTED");
        assertThat(x.importRows.get(imp.id()).row.errorCount()).isEqualTo(1);
        assertThat(x.issueRows.stream().filter(i -> !i.superseded())
                .map(i -> i.rowNo() + ":" + i.field() + ":" + i.code() + ":" + i.severity()))
                .containsExactly("1:productCode:product-unknown:ERROR");
    }

    // ---- security I10: start locks the person's own drafts before it replaces them ----

    @Test
    void startLocksTheCallersOwnDraftsBeforeReplacingThem() {
        OpeningStockImport first = start(f.pharmacist, centralId, 1);
        f.locks.clear();

        OpeningStockImport second = start(f.pharmacist, centralId, 1);

        assertThat(f.locks).containsExactly("store", "drafts", "insert");
        assertThat(x.importRows.get(first.id()).row.status().name()).isEqualTo("REPLACED");
        assertThat(x.importRows.get(first.id()).row.replacedByImportId()).isEqualTo(second.id());
        assertThat(x.importRows.get(second.id()).row.status().name()).isEqualTo("DRAFT");
    }

    // ---- DEC-077 L8: at most 10 new imports per person per store per IST day ----

    private OpeningStockService serviceAt(Instant now) {
        return new OpeningStockService(f.gate, f.guard, f.storeRepository, x.importRepository, x.productRepository, f.team,
                x.importAssembler, f.auditLog, new PharmacyTime(Clock.fixed(now, ZoneOffset.UTC)));
    }

    private PharmacyViews.Outcome<OpeningStockImport> startWith(OpeningStockService service, PharmacyFixture.Person p,
                                                                UUID key) {
        return service.start(tenant, p.userId(), centralId,
                new PharmacyInputs.StartImport(key, OpeningStockCsv.HEADER, BigDecimal.ONE));
    }

    private static void assertTooMany(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConflictException.class, ex -> {
            assertThat(ex.problemType()).isEqualTo("import-limit");
            assertThat(ex.getMessage()).isEqualTo("Too many files today. Try again tomorrow.");
        });
    }

    @Test
    void theEleventhNewImportOfAPersonForAStoreOnOneDayAnswers409ImportLimitAndWritesNothing() {
        UUID tenthKey = UUID.randomUUID();
        for (int n = 1; n <= 10; n++) {
            startWith(x.openingStock, f.pharmacist, n == 10 ? tenthKey : UUID.randomUUID());
        }
        int imports = x.importRows.size();
        x.importWrites.clear();

        assertTooMany(() -> startWith(x.openingStock, f.pharmacist, UUID.randomUUID()));

        assertThat(x.importRows).hasSize(imports);
        assertThat(x.importWrites).isEmpty();
        // the tenth is still the draft: the refused start did not replace it
        assertThat(x.importRows.values().stream().filter(i -> i.row.status().name().equals("DRAFT"))).hasSize(1);
        // a replay of the tenth is not a new start; another person and another store are not affected
        assertThat(startWith(x.openingStock, f.pharmacist, tenthKey).created()).isFalse();
        assertThat(startWith(x.openingStock, f.owner, UUID.randomUUID()).view().status()).isEqualTo("DRAFT");
        assertThat(start(f.owner, ward.id().toString(), 1).status()).isEqualTo("DRAFT");
    }

    @Test
    void theStartsLimitIsCountedUnderTheStoreLockAndResetsAtMidnightIst() {
        for (int n = 1; n <= 10; n++) {
            startWith(x.openingStock, f.pharmacist, UUID.randomUUID());
        }
        // the imports were created at 17 Nov 2026 11:05 IST. One second before midnight IST it is still the same day ...
        OpeningStockService lastSecond = serviceAt(Instant.parse("2026-11-17T18:29:59Z"));
        f.locks.clear();
        assertTooMany(() -> startWith(lastSecond, f.pharmacist, UUID.randomUUID()));
        assertThat(f.locks).as("the count is taken after the store lock").startsWith("store");
        // ... and at 00:00 IST of 18 Nov it is a new day
        OpeningStockService midnight = serviceAt(Instant.parse("2026-11-17T18:30:00Z"));
        assertThat(startWith(midnight, f.pharmacist, UUID.randomUUID()).view().status()).isEqualTo("DRAFT");
    }

    @Test
    void theIstDayStartAndTheFinancialYearStartAreWorkedOutInIst() {
        assertThat(new PharmacyTime(Clock.fixed(Instant.parse("2026-11-17T18:29:59Z"), ZoneOffset.UTC)).dayStart())
                .isEqualTo(Instant.parse("2026-11-16T18:30:00Z"));
        assertThat(new PharmacyTime(Clock.fixed(Instant.parse("2026-11-17T18:30:00Z"), ZoneOffset.UTC)).dayStart())
                .isEqualTo(Instant.parse("2026-11-17T18:30:00Z"));
        // 1 April 2026 00:00 IST for any day of 26-27, and 1 April 2025 for 31 Mar 2026 (IST)
        assertThat(f.time.financialYearStart()).isEqualTo(Instant.parse("2026-03-31T18:30:00Z"));
        assertThat(new PharmacyTime(Clock.fixed(Instant.parse("2026-03-31T17:00:00Z"), ZoneOffset.UTC)).financialYearStart())
                .isEqualTo(Instant.parse("2025-03-31T18:30:00Z"));
        assertThat(new PharmacyTime(Clock.fixed(Instant.parse("2026-03-31T18:30:00Z"), ZoneOffset.UTC)).financialYearStart())
                .isEqualTo(Instant.parse("2026-03-31T18:30:00Z"));
    }
}
