package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DocumentSeriesRepository.Row;
import com.sahaj.pharmacy.application.SaleInputs.StoreBillSeriesInput;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-80-1, the bill series of a selling store (docs/api/pharmacy-sales.yaml pharmacyListStoreBillSeries and
 * pharmacyPutStoreBillSeries, ADR-030 S3). A store without a series of its own bills from the unit BILL series; giving it one
 * changes only the numbers taken after the save. Both operations are masters: gated by the access level (SETUP_VIEW, SETUP_EDIT),
 * never by a store mapping. The number itself is taken by the database function {@code pharmacy.next_store_bill_no()} in the
 * sale transaction; nothing here issues one.
 *
 * <p>Lock order: the unit row, the unit BILL series row (the prefix check of ADR-030 S3), then the store's own series row.
 */
@Service
public class StoreBillSeriesService {

    static final String CHOOSE_SALE_STORE = "Choose a store where sale is allowed.";
    private static final String ENTITY_TYPE = "PHARMACY_STORE_BILL_SERIES";

    private final PharmacyGate gate;
    private final UnitService units;
    private final StoreRepository stores;
    private final StoreBillSeriesRepository storeSeries;
    private final DocumentSeriesRepository unitSeries;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public StoreBillSeriesService(PharmacyGate gate, UnitService units, StoreRepository stores,
                                  StoreBillSeriesRepository storeSeries, DocumentSeriesRepository unitSeries,
                                  AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.units = units;
        this.stores = stores;
        this.storeSeries = storeSeries;
        this.unitSeries = unitSeries;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * One row per ACTIVE store that allows sale and is not the Central Store, plus a row per switched-off store whose own series
     * issued a number (kept, never reused). Small and complete. Writes nothing.
     */
    @Transactional(readOnly = true)
    public SaleViews.StoreBillSeriesList list(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        Map<UUID, StoreBillSeriesRepository.Row> own = storeSeries.list(tenantId).stream()
                .collect(Collectors.toMap(StoreBillSeriesRepository.Row::storeId, Function.identity()));
        Set<UUID> everIssued = storeSeries.storesWithNumbers(tenantId);
        Instant fyStart = time.financialYearStart();
        Set<UUID> issuedThisYear = storeSeries.storesIssuedSince(tenantId, fyStart);
        Row unitRow = unitSeries.find(tenantId, DocType.BILL).orElse(null);
        boolean unitIssued = unitSeries.issuedSince(tenantId, DocType.BILL, fyStart);
        LocalDate today = time.today();

        List<SaleViews.StoreBillSeries> items = new ArrayList<>();
        stores.list(tenantId, true).stream()
                .filter(store -> offered(store, everIssued))
                .sorted(Comparator.comparing((Store s) -> s.name().toLowerCase(Locale.ROOT)))
                .forEach(store -> items.add(view(store, own.get(store.id()), unitRow, unitIssued,
                        issuedThisYear.contains(store.id()), today)));
        return new SaleViews.StoreBillSeriesList(items);
    }

    private static boolean offered(Store store, Set<UUID> everIssued) {
        if (store.active()) {
            return store.saleAllowed() && !store.type().isCentral();
        }
        return everIssued.contains(store.id());
    }

    /** For tests: the body is already read. */
    SaleViews.StoreBillSeries put(UUID tenantId, UUID userId, String rawStoreId, StoreBillSeriesInput input) {
        return put(tenantId, userId, rawStoreId, PharmacyInputs.Body.of(input));
    }

    /**
     * Owner only. The raw path id and the body are read after the gate (module off 403, owner only 403, then 400, then 404).
     * The store must be ACTIVE, allow sale and not be the Central Store.
     */
    @Transactional
    public SaleViews.StoreBillSeries put(UUID tenantId, UUID userId, String rawStoreId,
                                         PharmacyInputs.Body<StoreBillSeriesInput> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        StoreBillSeriesInput in = body.read();
        Store store = stores.find(tenantId, storeId).orElseThrow(StoreBillSeriesService::notFound);
        Checked checked = check(in, store);

        Unit unit = units.ensureUnit(tenantId, userId);
        Row unitRow = unitSeries.findForUpdate(tenantId, DocType.BILL).orElse(null);
        StoreBillSeriesRepository.Row row = storeSeries.find(tenantId, storeId).orElse(null);
        long currentVersion = row == null ? 0L : row.version();
        LocalDate today = time.today();
        Instant fyStart = time.financialYearStart();
        if (checked.version() != currentVersion) {
            throw PharmacyProblemException.settingsChanged(view(store, row, unitRow,
                    unitSeries.issuedSince(tenantId, DocType.BILL, fyStart),
                    storeSeries.storesIssuedSince(tenantId, fyStart).contains(storeId), today));
        }
        requireFreePrefix(tenantId, checked.prefix(), storeId);

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (row == null) {
            before.put("prefix", null);
            after.put("prefix", checked.prefix());
        } else {
            track(before, after, "prefix", row.prefix(), checked.prefix());
            track(before, after, "financialYearPart", row.financialYearPart(), checked.financialYearPart());
            track(before, after, "sequenceLength", row.sequenceLength(), checked.sequenceLength());
            track(before, after, "restartsEachFinancialYear", row.restartsEachFinancialYear(), checked.restarts());
            if (before.isEmpty()) {
                return view(store, row, unitRow, unitSeries.issuedSince(tenantId, DocType.BILL, fyStart),
                        storeSeries.storesIssuedSince(tenantId, fyStart).contains(storeId), today);
            }
        }
        if (storeSeries.storesIssuedSince(tenantId, fyStart).contains(storeId)) {
            throw new ConflictException("series-locked", "Series locked", NumberingService.SERIES_LOCKED);
        }
        UUID id;
        if (row == null) {
            id = storeSeries.insert(tenantId, unit.id(), storeId, checked.prefix(), checked.financialYearPart(),
                    checked.sequenceLength(), checked.restarts(), userId);
        } else {
            id = row.id();
            if (!storeSeries.update(tenantId, id, row.version(), checked.prefix(), checked.financialYearPart(),
                    checked.sequenceLength(), checked.restarts(), userId)) {
                StoreBillSeriesRepository.Row current = storeSeries.find(tenantId, storeId).orElse(row);
                throw PharmacyProblemException.settingsChanged(view(store, current, unitRow,
                        unitSeries.issuedSince(tenantId, DocType.BILL, fyStart), false, today));
            }
        }
        before.put("storeId", storeId.toString());
        after.put("storeId", storeId.toString());
        auditLog.record("PHARMACY_SERIES_CHANGED", ENTITY_TYPE, id, before, after);
        return view(store, storeSeries.find(tenantId, storeId).orElseThrow(), unitRow,
                unitSeries.issuedSince(tenantId, DocType.BILL, fyStart), false, today);
    }

    /** 409 prefix-in-use: the same prefix (ignoring case) on any unit document series or on another store's series. */
    private void requireFreePrefix(UUID tenantId, String prefix, UUID storeId) {
        List<Row> saved = unitSeries.list(tenantId);
        for (DocType type : DocType.values()) {
            String used = saved.stream().filter(r -> r.docType() == type).map(Row::prefix).findFirst()
                    .orElse(type.defaultPrefix());
            if (used.equalsIgnoreCase(prefix)) {
                throw prefixInUse();
            }
        }
        for (StoreBillSeriesRepository.Row other : storeSeries.list(tenantId)) {
            if (!other.storeId().equals(storeId) && other.prefix().equalsIgnoreCase(prefix)) {
                throw prefixInUse();
            }
        }
    }

    private static ConflictException prefixInUse() {
        return new ConflictException("prefix-in-use", "Prefix already used", "Two document types cannot use the same prefix");
    }

    // ---- views ----

    static SaleViews.StoreBillSeries view(Store store, StoreBillSeriesRepository.Row own, Row unitRow, boolean unitIssued,
                                          boolean ownIssued, LocalDate today) {
        String prefix;
        boolean fy;
        int length;
        boolean restarts;
        boolean issued;
        if (own != null) {
            prefix = own.prefix();
            fy = own.financialYearPart();
            length = own.sequenceLength();
            restarts = own.restartsEachFinancialYear();
            issued = ownIssued;
        } else if (unitRow != null) {
            prefix = unitRow.prefix();
            fy = unitRow.financialYearPart();
            length = unitRow.sequenceLength();
            restarts = unitRow.restartsEachFinancialYear();
            issued = unitIssued;
        } else {
            DocType bill = DocType.BILL;
            prefix = bill.defaultPrefix();
            fy = bill.defaultFinancialYearPart();
            length = bill.defaultSequenceLength();
            restarts = bill.defaultRestarts();
            issued = unitIssued;
        }
        String example = DocumentNumbers.render(prefix, fy, length, 1L, today);
        return new SaleViews.StoreBillSeries(new StockViews.StoreRef(store.id(), store.name(), store.type().name()),
                store.active(), own == null, prefix, fy, length, restarts, example, example.length(), issued, issued,
                own == null ? 0L : own.version());
    }

    // ---- checks ----

    private record Checked(long version, String prefix, boolean financialYearPart, int sequenceLength, boolean restarts) {
    }

    /** All field errors together (400 validation); nothing typed is echoed back. */
    private static Checked check(StoreBillSeriesInput in, Store store) {
        List<FieldMessage> errors = new ArrayList<>();
        if (!store.active() || !store.saleAllowed() || store.type().isCentral()) {
            errors.add(new FieldMessage("storeId", CHOOSE_SALE_STORE));
        }
        if (in.version() == null || in.version() < 0) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        String prefix = in.prefix();
        boolean prefixOk = prefix != null && DocumentNumbers.PREFIX.matcher(prefix).matches();
        if (!prefixOk) {
            errors.add(new FieldMessage("prefix", NumberingService.PREFIX_MESSAGE));
        }
        Integer length = PharmacyParams.wholeNumber(in.sequenceLength(), DocumentNumbers.MIN_SEQUENCE,
                DocumentNumbers.MAX_SEQUENCE);
        if (length == null) {
            errors.add(new FieldMessage("sequenceLength", "Enter a length from 4 to 8"));
        }
        if (in.financialYearPart() == null) {
            errors.add(new FieldMessage("financialYearPart", UnitService.REQUIRED));
        }
        if (in.restartsEachFinancialYear() == null) {
            errors.add(new FieldMessage("restartsEachFinancialYear", UnitService.REQUIRED));
        }
        boolean fy = Boolean.TRUE.equals(in.financialYearPart());
        boolean restarts = Boolean.TRUE.equals(in.restartsEachFinancialYear());
        if (restarts && !fy && in.financialYearPart() != null) {
            errors.add(new FieldMessage("restartsEachFinancialYear", NumberingService.RESTART_NEEDS_YEAR));
        }
        if (prefixOk && length != null && DocumentNumbers.renderedLength(prefix, fy, length) > DocumentNumbers.MAX_LENGTH) {
            errors.add(new FieldMessage("sequenceLength", NumberingService.TOO_LONG));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Checked(in.version(), prefix, fy, length, restarts);
    }

    private static void track(Map<String, Object> before, Map<String, Object> after, String field, Object was, Object now) {
        if (!was.equals(now)) {
            before.put(field, was.toString());
            after.put(field, now.toString());
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
