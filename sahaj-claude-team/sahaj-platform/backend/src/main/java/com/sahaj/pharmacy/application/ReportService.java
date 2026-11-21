package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ReportRepository.Filter;
import com.sahaj.pharmacy.application.ReportRepository.PersonCell;
import com.sahaj.pharmacy.application.ReportRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-18 and 79-19: the reports (docs/api/pharmacy.yaml 0.3.6). The order of the checks is: module off, access level, the
 * report key, the caller's access to that report (403), the query values (400), the store (404), then the work. A caller reaches
 * a report only through the stores the catalogue rules give them (ADR-029 P8, OQ-79-21): the owner every ACTIVE store; an approver,
 * current delegate, pharmacist member or Central Store user the Stock and Movement reports of their stores; a Store user only
 * store-stock and below-reorder; the Purchase reports only the owner and the Central Store user, on the Central Store. The stock
 * reports first move expired stock to quarantine (rule 17) and hide the quantity of an audited product from a blind caller
 * (rule 24). No row carries a patient name. Page 0 is audited as viewed and a download as exported, with ids and the row count only.
 */
@Service
public class ReportService {

    /** The line above the report cards (the mockup 15 sentence). */
    static final String LINE = "Every report can be filtered by store, date and product, and downloaded as a CSV file or printed.";
    static final String NO_ACCESS = "You do not have access to this report.";
    static final int MAX_DAYS = 366;
    static final int EXPORT_CAP = 50_000;
    static final String STOCK_LEDGER_KEY = "stock-ledger";
    static final String STOCK_LEDGER_LABEL = "Stock ledger (per product)";

    private static final Map<String, String> TRANSACTION_LABELS = new HashMap<>();

    static {
        String[][] labels = {
            {"OPENING_STOCK", "Opening stock"}, {"GRN", "GRN"}, {"EXPIRY_QUARANTINE", "Expired · quarantined"},
            {"STOCK_ADJUSTMENT", "Stock adjustment"}, {"VENDOR_RETURN_HOLD", "Vendor return · held"},
            {"VENDOR_RETURN_RELEASE", "Vendor return · released"}, {"VENDOR_RETURN", "Vendor return"},
            {"INDENT_DISPATCH", "Indent dispatch"}, {"INDENT_RECEIPT_ACCEPTED", "Indent receipt · accepted"},
            {"INDENT_RECEIPT_REJECTED", "Indent receipt · rejected"}, {"RETURN_ACCEPTED", "Return accepted by CS"},
            {"SHORTAGE_FOUND", "Shortage found"}, {"SHORTAGE_WRITE_OFF", "Shortage written off"},
            {"STORE_RETURN", "Return to CS"}, {"DAMAGE_QUARANTINE", "Damaged · quarantined"},
            {"DAMAGE_RELEASE", "Damaged · released"}, {"DAMAGE_SEND_TO_CS", "Damaged · sent to CS"},
            {"WRITE_OFF", "Write-off"}, {"TRANSFER_DISPATCH", "Stock transfer · dispatch"},
            {"TRANSFER_RECEIPT_ACCEPTED", "Stock transfer · accepted"},
            {"TRANSFER_RECEIPT_REJECTED", "Stock transfer · rejected"}, {"WARD_CONSUMPTION", "Ward consumption"},
            {"WARD_CONSUMPTION_PATIENT", "Ward consumption · patient"}, {"STOCK_AUDIT_ADJUSTMENT", "Stock audit adjustment"},
            {"COUNTER_SALE", "Counter sale"}, {"COUNTER_SALE_CANCELLED", "Counter sale · cancelled"},
            {"SALES_RETURN", "Sales return"}, {"IPD_ISSUE", "IPD issue"},
            {"WARD_RETURN_ACCEPTED", "Ward return · accepted"}, {"WARD_RETURN_WASTAGE", "Ward return · wastage"}};
        for (String[] pair : labels) {
            TRANSACTION_LABELS.put(pair[0], pair[1]);
        }
    }

    /** Indexes of the cells that hold a quantity or a value a blind caller must not read, per report. */
    private static final Map<ReportDef, int[]> BLIND_CELLS = Map.of(
            ReportDef.STORE_STOCK, new int[] {2, 3, 4, 5, 7},
            ReportDef.BATCH_EXPIRY, new int[] {5},
            ReportDef.BELOW_REORDER, new int[] {2},
            ReportDef.SCHEDULE_X_REGISTER, new int[] {3, 4, 5, 6, 7});

    private final PharmacyGate gate;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final ReportRepository reports;
    private final StockPosting posting;
    private final PharmacyTime time;
    private final BlindCount blind;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final SaleSupport support;

    public ReportService(PharmacyGate gate, StoreRepository stores, ProductRepository products, ReportRepository reports,
                         StockPosting posting, PharmacyTime time, BlindCount blind, TeamMemberPort team, AuditLog auditLog,
                         SaleSupport support) {
        this.support = support;
        this.gate = gate;
        this.stores = stores;
        this.products = products;
        this.reports = reports;
        this.posting = posting;
        this.time = time;
        this.blind = blind;
        this.team = team;
        this.auditLog = auditLog;
    }

    // ---- catalogue ----

    @Transactional(readOnly = true)
    public ReportViews.Catalogue catalogue(UUID tenantId, UUID userId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        List<Store> active = caller.isOwner() ? stores.list(tenantId, false) : List.of();
        List<ReportViews.CatalogueGroup> groups = new ArrayList<>();
        for (ReportDef.Group group : ReportDef.Group.values()) {
            List<ReportViews.CatalogueItem> items = new ArrayList<>();
            if (group == ReportDef.Group.MOVEMENT && !reach(caller, ReportDef.Tier.MOVEMENT, active).isEmpty()) {
                items.add(new ReportViews.CatalogueItem(STOCK_LEDGER_KEY, STOCK_LEDGER_LABEL));
            }
            for (ReportDef def : ReportDef.values()) {
                if (def.group() == group && !reach(caller, def.tier(), active).isEmpty()) {
                    items.add(new ReportViews.CatalogueItem(def.key(), def.label()));
                }
            }
            if (!items.isEmpty()) {
                groups.add(new ReportViews.CatalogueGroup(group.name(), group.label(), items));
            }
        }
        return new ReportViews.Catalogue(LINE, groups);
    }

    // ---- run and export ----

    @Transactional
    public ReportViews.ReportPage run(UUID tenantId, UUID userId, String rawKey, Query query) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        ReportDef def = ReportDef.byKey(rawKey).orElseThrow(PharmacyProblemException::badRequest);
        List<Store> reachable = require(caller, def.tier());
        Prepared prepared = prepare(caller, def, reachable, query);
        int page = PharmacyParams.intValue("page", query.page(), 0, 0, 10000);
        int size = PharmacyParams.intValue("size", query.size(), 50, 1, 200);
        sweep(caller, def, prepared.stores());
        long total = reports.count(def, prepared.filter());
        List<Row> rows = reports.rows(def, prepared.filter(), size, page * size);
        List<List<Object>> cells = present(caller, def, rows, false);
        List<ReportViews.TotalRow> totals = null;
        if (def == ReportDef.PURCHASE_REGISTER) {
            totals = reports.purchaseTotals(prepared.filter()).stream()
                    .map(t -> new ReportViews.TotalRow(t.label(), jsonCells(def, t.cells()))).toList();
        }
        if (def == ReportDef.H1_REGISTER) {
            // DEC-089 item 3: every page of the H1 report is a register read (record access per patient, one audit entry)
            Set<UUID> patients = new LinkedHashSet<>();
            rows.forEach(r -> {
                if (r.patientId() != null) {
                    patients.add(r.patientId());
                }
            });
            patients.forEach(id -> support.recordOpened(tenantId, id, userId));
            audit("PHARMACY_REGISTER_VIEWED", def.key(), prepared, rows.size());
        } else if (page == 0) {
            audit("PHARMACY_REPORT_VIEWED", def.key(), prepared, null);
        }
        List<ReportViews.Column> columns = def.columns().stream()
                .map(c -> new ReportViews.Column(c.key(), c.label(), c.kind().name())).toList();
        return new ReportViews.ReportPage(def.key(), def.label(), columns, cells, totals, total, page, size);
    }

    @Transactional
    public ReportViews.CsvFile export(UUID tenantId, UUID userId, String rawKey, Query query) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        ReportDef def = ReportDef.byKey(rawKey).orElseThrow(PharmacyProblemException::badRequest);
        List<Store> reachable = require(caller, def.tier());
        if (def == ReportDef.H1_REGISTER && !caller.isOwner() && !caller.isPharmacist()) {
            // the H1 download carries the patient name and address: the owner and a pharmacist member only (SAH-80-9)
            throw new ForbiddenException("forbidden", "Not allowed", NO_ACCESS);
        }
        Prepared prepared = prepare(caller, def, reachable, query);
        sweep(caller, def, prepared.stores());
        long total = reports.count(def, prepared.filter());
        if (total > EXPORT_CAP) {
            throw PharmacyProblemException.exportTooLarge();
        }
        List<Row> rows = reports.rows(def, prepared.filter(), EXPORT_CAP, 0);
        List<List<Object>> cells = present(caller, def, rows, true);
        List<String> header = def.columns().stream().map(ReportDef.Col::label).toList();
        List<List<String>> lines = new ArrayList<>();
        for (List<Object> row : cells) {
            List<String> line = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                line.add(ReportCsv.cell(row.get(i), def.columns().get(i).kind()));
            }
            lines.add(line);
        }
        audit(def == ReportDef.H1_REGISTER ? "PHARMACY_REGISTER_EXPORTED" : "PHARMACY_REPORT_EXPORTED", def.key(), prepared,
                rows.size());
        return new ReportViews.CsvFile("sahaj-" + def.key() + "-" + time.today() + ".csv", ReportCsv.write(header, lines));
    }

    // ---- stock ledger ----

    @Transactional
    public ReportViews.StockLedgerPage ledger(UUID tenantId, UUID userId, Query query) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        List<Store> reachable = require(caller, ReportDef.Tier.MOVEMENT);
        LedgerScope scope = ledgerScope(caller, reachable, query);
        int page = PharmacyParams.intValue("page", query.page(), 0, 0, 10000);
        int size = PharmacyParams.intValue("size", query.size(), 50, 1, 200);
        sweep(caller, scope.stores());
        long total = reports.ledgerCount(scope.filter());
        List<ReportRepository.LedgerLine> lines = reports.ledger(scope.filter(), size, page * size);
        List<ReportViews.LedgerItem> items = ledgerItems(caller, scope, lines);
        if (page == 0) {
            audit("PHARMACY_REPORT_VIEWED", STOCK_LEDGER_KEY, scope.stores(), scope.filter(), null);
        }
        List<StockViews.StoreRef> refs = scope.stores().stream().map(OpeningStockPostService::storeRef).toList();
        return new ReportViews.StockLedgerPage(items, scope.product(), refs, scope.filter().from(), scope.filter().to(), total,
                page, size);
    }

    @Transactional
    public ReportViews.CsvFile exportLedger(UUID tenantId, UUID userId, Query query) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        List<Store> reachable = require(caller, ReportDef.Tier.MOVEMENT);
        LedgerScope scope = ledgerScope(caller, reachable, query);
        sweep(caller, scope.stores());
        long total = reports.ledgerCount(scope.filter());
        if (total > EXPORT_CAP) {
            throw PharmacyProblemException.exportTooLarge();
        }
        List<ReportRepository.LedgerLine> lines = reports.ledger(scope.filter(), EXPORT_CAP, 0);
        List<ReportViews.LedgerItem> items = ledgerItems(caller, scope, lines);
        List<String> header = List.of("Time", "Transaction", "Reference", "Store · state", "Batch", "In", "Out", "Balance",
                "User", "Second signer", "Reverses");
        List<List<String>> out = new ArrayList<>();
        for (ReportViews.LedgerItem item : items) {
            out.add(List.of(ReportCsv.time(item.at()), ReportCsv.neutralise(item.transaction()),
                    ReportCsv.neutralise(item.ref() == null ? "" : item.ref()),
                    ReportCsv.neutralise(item.store().name() + " · " + item.state()), ReportCsv.neutralise(item.batch().batchNo()),
                    item.qtyIn() == null ? "" : item.qtyIn().toString(), item.qtyOut() == null ? "" : item.qtyOut().toString(),
                    item.balance() == null ? "" : item.balance().toString(),
                    ReportCsv.neutralise(item.user() == null ? "System" : item.user().name()),
                    item.secondSigner() == null ? "" : ReportCsv.neutralise(item.secondSigner().name()),
                    item.reversesLedgerId() == null ? "" : item.reversesLedgerId().toString()));
        }
        audit("PHARMACY_REPORT_EXPORTED", STOCK_LEDGER_KEY, scope.stores(), scope.filter(), lines.size());
        return new ReportViews.CsvFile("sahaj-stock-ledger-" + scope.filter().from() + "-" + scope.filter().to() + ".csv",
                ReportCsv.write(header, out));
    }

    private record LedgerScope(Filter filter, List<Store> stores, StockViews.ProductRef product) {
    }

    private LedgerScope ledgerScope(PharmacyCaller caller, List<Store> reachable, Query query) {
        UUID productId = PharmacyParams.optionalUuid("productId", query.productId(), "Choose a product");
        if (productId == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("productId", "Choose a product")));
        }
        Dates dates = dates(query, true);
        List<Store> scoped = scopeStores(reachable, query.storeId());
        ProductRepository.Row row = products.find(caller.tenantId(), productId)
                .orElseThrow(() -> new NotFoundException("This record was not found.")).row();
        StockViews.ProductRef ref = new StockViews.ProductRef(row.productId(), row.code(), row.name(), row.strength(), row.form(),
                row.schedule(), row.purchaseUnit(), row.issueUnit(), row.conversion(),
                com.sahaj.pharmacy.domain.PurchaseMath.percentText(row.gstRate()));
        Filter filter = new Filter(caller.tenantId(), scoped.stream().map(Store::id).toList(), dates.from(), dates.to(), productId,
                null, null, time.today());
        return new LedgerScope(filter, scoped, ref);
    }

    private List<ReportViews.LedgerItem> ledgerItems(PharmacyCaller caller, LedgerScope scope,
                                                     List<ReportRepository.LedgerLine> lines) {
        Set<UUID> people = new LinkedHashSet<>();
        for (ReportRepository.LedgerLine line : lines) {
            if (line.actorMembershipId() != null) {
                people.add(line.actorMembershipId());
            }
            if (line.secondSignerMembershipId() != null) {
                people.add(line.secondSignerMembershipId());
            }
        }
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of()
                : team.findAll(caller.tenantId(), people);
        Map<UUID, Boolean> hidden = new HashMap<>();
        UUID productId = scope.filter().productId();
        List<ReportViews.LedgerItem> items = new ArrayList<>();
        for (ReportRepository.LedgerLine line : lines) {
            boolean hide = hidden.computeIfAbsent(line.storeId(), s -> blind.hides(caller, s, productId));
            items.add(new ReportViews.LedgerItem(line.ledgerId(), line.at(),
                    TRANSACTION_LABELS.getOrDefault(line.transaction(), line.transaction()), line.ref(),
                    new StockViews.StoreRef(line.storeId(), line.storeName(), line.storeType()), line.state().name(),
                    new StockViews.BatchRef(line.batchId(), line.batchNo(), line.expiryDate()),
                    hide ? null : Integer.valueOf(line.qtyIn()), hide ? null : Integer.valueOf(line.qtyOut()),
                    hide ? null : Integer.valueOf(line.balance()), person(line.actorMembershipId(), members, true),
                    person(line.secondSignerMembershipId(), members, false), line.reversesLedgerId()));
        }
        return items;
    }

    private static PharmacyViews.Person person(UUID id, Map<UUID, TeamMemberPort.TeamMember> members, boolean always) {
        if (id == null) {
            return null;
        }
        TeamMemberPort.TeamMember member = members.get(id);
        return new PharmacyViews.Person(id, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    // ---- access ----

    /** The query values as text, read after the access check (docs/api/pharmacy.yaml general rule 2). */
    public record Query(String storeId, String from, String to, String productId, String vendorId, String status, String page,
                        String size) {
    }

    /** The stores the caller reaches for this tier; 403 when there are none. */
    private List<Store> require(PharmacyCaller caller, ReportDef.Tier tier) {
        List<Store> active = caller.isOwner() ? stores.list(caller.tenantId(), false) : List.of();
        List<Store> reachable = reach(caller, tier, active);
        if (reachable.isEmpty()) {
            throw new ForbiddenException("forbidden", "Not allowed", NO_ACCESS);
        }
        return reachable;
    }

    /** The stores a caller can run a report of this tier on. {@code ownerStores} are the ACTIVE stores, read only for the owner. */
    static List<Store> reach(PharmacyCaller caller, ReportDef.Tier tier, List<Store> ownerStores) {
        List<Store> reachable = new ArrayList<>();
        if (caller.isOwner()) {
            for (Store store : ownerStores) {
                if (store.active() && (tier != ReportDef.Tier.PURCHASE || store.type().isCentral())
                        && (tier != ReportDef.Tier.SALES || store.saleAllowed())) {
                    reachable.add(store);
                }
            }
            return reachable;
        }
        for (PharmacyCaller.Mapping mapping : caller.mappings()) {
            StoreRole role = mapping.effective();
            boolean allowed;
            switch (tier) {
                case STOCK_ONLY:
                    allowed = true;
                    break;
                case MOVEMENT:
                    allowed = role == StoreRole.APPROVER || role == StoreRole.DELEGATE || role == StoreRole.CENTRAL_STORE_USER
                            || caller.isPharmacist();
                    break;
                case SALES:
                    allowed = mapping.store().saleAllowed() && (caller.isPharmacist() || role == StoreRole.APPROVER
                            || role == StoreRole.DELEGATE);
                    break;
                case PURCHASE:
                default:
                    allowed = role == StoreRole.CENTRAL_STORE_USER && mapping.store().type().isCentral();
                    break;
            }
            if (allowed && mapping.store().active()) {
                reachable.add(mapping.store());
            }
        }
        return reachable;
    }

    private List<Store> scopeStores(List<Store> reachable, String rawStoreId) {
        UUID storeId = PharmacyParams.optionalUuid("storeId", rawStoreId, "Choose a store");
        if (storeId == null) {
            return reachable;
        }
        return reachable.stream().filter(s -> s.id().equals(storeId)).findFirst().map(List::of)
                .orElseThrow(StoreAccessGuard::notFound);
    }

    private record Dates(LocalDate from, LocalDate to) {
    }

    private Dates dates(Query query, boolean required) {
        List<FieldMessage> errors = new ArrayList<>();
        LocalDate from = date("from", query.from(), required, errors);
        LocalDate to = date("to", query.to(), required, errors);
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new InvalidFieldsException(List.of(new FieldMessage("from", "Choose a start date on or before the end date")));
            }
            if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
                throw PharmacyProblemException.rangeTooLong();
            }
        }
        return new Dates(from, to);
    }

    private static LocalDate date(String name, String raw, boolean required, List<FieldMessage> errors) {
        if (raw == null || raw.isEmpty()) {
            if (required) {
                errors.add(new FieldMessage(name, "Enter a valid date"));
            }
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException notADate) {
            errors.add(new FieldMessage(name, "Enter a valid date"));
            return null;
        }
    }

    private record Prepared(Filter filter, List<Store> stores) {
    }

    private Prepared prepare(PharmacyCaller caller, ReportDef def, List<Store> reachable, Query query) {
        Dates dates = dates(query, def.dated());
        UUID productId = PharmacyParams.optionalUuid("productId", query.productId(), "Choose a product");
        UUID vendorId = PharmacyParams.optionalUuid("vendorId", query.vendorId(), "Choose a vendor");
        String status = query.status() == null || query.status().isBlank() ? null : query.status().trim();
        if (status != null && status.length() > 30) {
            throw new InvalidFieldsException(List.of(new FieldMessage("status", "Choose a valid option")));
        }
        List<Store> scoped = scopeStores(reachable, query.storeId());
        // the stock reports are "now"; their dates are ignored
        boolean dated = def.dated();
        Filter filter = new Filter(caller.tenantId(), scoped.stream().map(Store::id).toList(), dated ? dates.from() : null,
                dated ? dates.to() : null, productId, vendorId, status, time.today());
        return new Prepared(filter, scoped);
    }

    private void sweep(PharmacyCaller caller, ReportDef def, List<Store> scoped) {
        if (def.tier() == ReportDef.Tier.STOCK_ONLY || def == ReportDef.WARD_CONSUMPTION) {
            sweep(caller, scoped);
        }
    }

    /** Rule 17: expired AVAILABLE stock of the stores in scope is moved to quarantine first. */
    private void sweep(PharmacyCaller caller, List<Store> scoped) {
        for (Store store : scoped) {
            posting.sweepStore(caller.tenantId(), store.unitId(), store.id(), caller.membershipId());
        }
    }

    // ---- presenting ----

    private List<List<Object>> present(PharmacyCaller caller, ReportDef def, List<Row> rows, boolean forCsv) {
        Set<UUID> people = new LinkedHashSet<>();
        for (Row row : rows) {
            for (Object cell : row.cells()) {
                if (cell instanceof PersonCell person) {
                    people.add(person.membershipId());
                }
            }
        }
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(caller.tenantId(), people);
        int[] hiddenCells = BLIND_CELLS.get(def);
        Map<UUID, BlindCount.Mask> masks = new HashMap<>();
        List<List<Object>> out = new ArrayList<>();
        for (Row row : rows) {
            List<Object> cells = new ArrayList<>(row.cells());
            if (hiddenCells != null && row.storeId() != null && row.productId() != null) {
                BlindCount.Mask mask = masks.computeIfAbsent(row.storeId(), s -> blind.at(caller, s));
                if (mask.hides(row.productId())) {
                    for (int index : hiddenCells) {
                        cells.set(index, null);
                    }
                }
            }
            if (def == ReportDef.H1_REGISTER && !seesPatientNames(caller, row.storeId())) {
                cells.set(H1_NAME, maskName(cells.get(H1_NAME)));
                cells.set(H1_ADDRESS, null);
            }
            if (def == ReportDef.SALES_MARGIN && !caller.isOwner() && !caller.isPharmacist()) {
                cells.set(7, null); // cost and margin: the owner and a pharmacist only (OQ-79-6)
                cells.set(8, null);
            }
            for (int i = 0; i < cells.size(); i++) {
                Object cell = cells.get(i);
                if (cell instanceof PersonCell person) {
                    TeamMemberPort.TeamMember member = members.get(person.membershipId());
                    cells.set(i, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
                } else if (!forCsv) {
                    cells.set(i, json(cell, def.columns().get(i).kind()));
                }
            }
            out.add(cells);
        }
        return out;
    }

    private static final int H1_NAME = 3;
    private static final int H1_ADDRESS = 5;

    /** The owner and a PHARMACIST member of that store read the patient names of the H1 report (DEC-089, DEC-090). */
    static boolean seesPatientNames(PharmacyCaller caller, UUID storeId) {
        return caller.isOwner() || (caller.isPharmacist() && storeId != null && caller.mappingFor(storeId).isPresent());
    }

    /** "Ravi Sharma" becomes "R*** S***". */
    static Object maskName(Object name) {
        if (name == null) {
            return null;
        }
        StringBuilder masked = new StringBuilder();
        for (String word : name.toString().trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (masked.length() > 0) {
                masked.append(' ');
            }
            masked.append(word.substring(0, word.offsetByCodePoints(0, 1))).append("***");
        }
        return masked.length() == 0 ? null : masked.toString();
    }

    private static List<Object> jsonCells(ReportDef def, List<Object> cells) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            out.add(json(cells.get(i), def.columns().get(i).kind()));
        }
        return out;
    }

    /** The JSON cell: whole numbers for paise and counts, strings for everything else (dates in ISO form, IST days). */
    static Object json(Object cell, ReportDef.Kind kind) {
        if (cell == null) {
            return null;
        }
        switch (kind) {
            case PAISE:
            case INTEGER:
                return cell instanceof Number number ? (Object) Long.valueOf(number.longValue()) : cell.toString();
            case DATE:
                if (cell instanceof Instant instant) {
                    return instant.atZone(PharmacyTime.IST).toLocalDate().toString();
                }
                return cell.toString();
            case DATE_TIME:
                return cell instanceof Instant instant ? instant.toString() : cell.toString();
            case PERCENT:
            case RATE:
                return cell instanceof Number number ? new java.math.BigDecimal(number.toString())
                        .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : cell.toString();
            case TEXT:
            default:
                return cell.toString();
        }
    }

    // ---- audit ----

    private void audit(String action, String key, Prepared prepared, Integer rows) {
        audit(action, key, prepared.stores(), prepared.filter(), rows);
    }

    private void audit(String action, String key, List<Store> scoped, Filter filter, Integer rows) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reportKey", key);
        after.put("storeIds", scoped.stream().map(s -> s.id().toString()).toList());
        if (filter.productId() != null) {
            after.put("productId", filter.productId().toString());
        }
        if (filter.vendorId() != null) {
            after.put("vendorId", filter.vendorId().toString());
        }
        if (rows != null) {
            after.put("rowCount", rows);
        }
        auditLog.record(action, "PHARMACY_REPORT", null, null, after);
    }
}
