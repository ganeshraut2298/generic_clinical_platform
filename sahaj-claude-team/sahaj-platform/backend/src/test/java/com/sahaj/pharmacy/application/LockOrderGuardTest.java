package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Lock-order guards of the pharmacy module, worked from the source (no database):
 *
 * <ul>
 *   <li><b>L11</b> (migration 1720, docs/database/SAH-79-inventory.md section 18.1): a transaction that has issued a number holds
 *       the unit row {@code FOR SHARE} until it ends and must not later lock the same row {@code FOR UPDATE}, because that
 *       upgrade can deadlock with a series save that is waiting. The only code that takes the unit {@code FOR UPDATE} is the
 *       master writes through {@code UnitService}; no class that issues a number may use it, and a class that issues a number
 *       must be on the list below, so a new numbered document is added here on purpose.</li>
 *   <li><b>m-5</b> (migration 1720, section 18.7): every posting locks the store row with {@code findForUpdate} and keeps only an
 *       ACTIVE store ({@code filter(Store::active)}), the same lock a switch-off takes, so the two are ordered and a store
 *       switched off in between answers 404.</li>
 * </ul>
 */
class LockOrderGuardTest {

    /** The classes that call {@code DocumentNumberPort.next}: each takes a number in the document's own transaction. */
    private static final List<String> NUMBER_ISSUERS = List.of("PurchaseOrderService", "StockAdjustmentService",
            "GoodsReceiptService", "DebitNoteService", "IndentService", "StoreReturnService", "DamageReportService", "TransferService", "StockAuditService",
            "SaleBillService", "BillCancelService", "SalesReturnService");

    /** The postings that must lock the store first and read its status again. */
    private static final List<String> STORE_LOCKING_POSTINGS = List.of("OpeningStockPostService", "StockAdjustmentService",
            "GoodsReceiptService", "DebitNoteService");

    /** A field or parameter of type UnitService (its constants such as RELOAD are fine). */
    private static final Pattern UNIT_SERVICE_FIELD = Pattern.compile("UnitService\\s+[a-z]\\w*");

    private static final Pattern ACTIVE_STORE_LOCK = Pattern.compile(
            "stores\\.findForUpdate\\(tenantId, storeId\\)\\s*\\.filter\\(Store::active\\)", Pattern.DOTALL);

    @Test
    void theClassesThatIssueANumberAreTheListedOnesAndNoneOfThemEverLocksTheUnitForUpdate() throws IOException {
        TreeMap<String, String> sources = sources();
        List<String> issuers = new ArrayList<>();
        sources.forEach((name, text) -> {
            if (text.contains("numbers.next(")) {
                issuers.add(name);
            }
        });
        assertThat(issuers).as("classes that call numbers.next").containsExactlyInAnyOrderElementsOf(NUMBER_ISSUERS);
        for (String issuer : issuers) {
            String text = sources.get(issuer);
            assertThat(text).as(issuer + " must not lock the unit FOR UPDATE").doesNotContain("ensureAndLock(")
                    .doesNotContain("lockUnit(");
            assertThat(UNIT_SERVICE_FIELD.matcher(text).find()).as(issuer + " holds no UnitService").isFalse();
        }
    }

    @Test
    void theUnitIsLockedForUpdateOnlyByTheMasterWritesThroughTheUnitService() throws IOException {
        List<String> takers = new ArrayList<>();
        sources().forEach((name, text) -> {
            if (text.contains(".ensureAndLock(")) {
                takers.add(name);
            }
        });
        assertThat(takers).containsExactlyInAnyOrder("UnitService");
    }

    @Test
    void everyPostingLocksTheStoreRowAndKeepsOnlyAnActiveStoreBeforeItTakesAnyOtherLock() throws IOException {
        TreeMap<String, String> sources = sources();
        for (String posting : STORE_LOCKING_POSTINGS) {
            String text = sources.get(posting);
            assertThat(ACTIVE_STORE_LOCK.matcher(text).find()).as(posting + " locks the store and re-reads its status").isTrue();
        }
    }

    @Test
    void theStoreRowLockIsExclusiveAndTakesTheUnitSharedFirstSoItIsOrderedAgainstASwitchOff() throws IOException {
        String store = Files.readString(root().resolve("infrastructure").resolve("JdbcStoreRepository.java"),
                StandardCharsets.UTF_8);
        int unit = store.indexOf("jdbc.sql(LOCK_UNIT_SHARED)");
        int row = store.indexOf("FIND + \" for update\"");
        assertThat(unit).isPositive();
        assertThat(row).isGreaterThan(unit);
        // the switch-off and the staff writes take the same exclusive row
        String service = sources().get("StoreService");
        assertThat(service).contains("stores.findForUpdate(tenantId, storeId)");
    }

    /** The post of a numbered document takes the store lock before the number: checked in each class by text order. */
    @Test
    void inTheNumberIssuersThatPostStockTheStoreLockComesBeforeTheNumber() throws IOException {
        TreeMap<String, String> sources = sources();
        for (String name : List.of("StockAdjustmentService", "GoodsReceiptService", "DebitNoteService")) {
            String text = sources.get(name);
            int lock = text.indexOf("stores.findForUpdate(");
            int number = text.indexOf("numbers.next(");
            assertThat(lock).as(name + " store lock").isPositive();
            assertThat(number).as(name + " number").isGreaterThan(lock);
        }
    }

    private static Path root() {
        Path dir = Path.of("src", "main", "java", "com", "sahaj", "pharmacy");
        return Files.isDirectory(dir) ? dir : Path.of("backend").resolve(dir);
    }

    /** Every main source file of the pharmacy module by simple class name. */
    private static TreeMap<String, String> sources() throws IOException {
        TreeMap<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root())) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                files.put(name, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
