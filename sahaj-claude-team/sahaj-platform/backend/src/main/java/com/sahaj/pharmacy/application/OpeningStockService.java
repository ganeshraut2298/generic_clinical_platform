package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.OpeningStockRepository.ImportRow;
import com.sahaj.pharmacy.application.OpeningStockRepository.LineIn;
import com.sahaj.pharmacy.application.OpeningStockRepository.LineRef;
import com.sahaj.pharmacy.application.ProductRepository.ProductRef;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.OpeningStockCsv;
import com.sahaj.pharmacy.domain.OpeningStockRules;
import com.sahaj.pharmacy.domain.RejectReason;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-7: the opening-stock import. The browser reads the CSV (the file is never stored) and sends the rows as text, at most
 * 200 a request; the server parses and checks every row, fixes the totals at submit, and a different OWNER approves or
 * rejects. Nothing is posted to a ledger here (SAH-79). Nobody approves their own upload: the service refuses it with 403
 * {@code self-approval} and the database refuses it too.
 *
 * <p>Order of checks (contract rule 2): module off 403, access level 403, path ids 400, body that cannot be read 400, the
 * store guard (404, or 403 {@code store-permission-denied}), 404 for the import, then body fields 400, state 409, content 422.
 * Write order (design note "Opening stock write order"): lines, then issues, then the totals and status of the import last;
 * the update that sets an import to REPLACED runs before anything that would create a second WAITING_APPROVAL row. Logs carry
 * ids and counts only, never file text, batch numbers or quantities.
 */
@Service
public class OpeningStockService {

    static final int MAX_ROWS = 5000;
    static final int MAX_BATCH = 200;
    static final int FIELD_MAX = 40;
    static final int ERROR_LIST_MAX = 100;
    static final String ROWS_MESSAGE = "The file must have 1 to 5,000 rows";
    static final String STATE_LOCKED = "This import can no longer be changed.";
    static final String NOT_WAITING = "This import is not waiting for approval.";
    static final String ALREADY_APPROVED = "Opening stock was already approved for this store.";
    static final String SELF_APPROVAL = "Another owner must approve this import.";
    static final String TOO_MANY_STARTS = "Too many files today. Try again tomorrow.";
    /** DEC-077 L8: new imports per person per store per IST day. */
    static final int MAX_STARTS_PER_DAY = 10;
    static final String INCOMPLETE = "Some rows did not arrive. Upload the file again.";
    private static final String ENTITY_TYPE = "PHARMACY_OPENING_STOCK_IMPORT";
    private static final Logger log = LoggerFactory.getLogger(OpeningStockService.class);

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final OpeningStockRepository imports;
    private final ProductRepository products;
    private final TeamMemberPort team;
    private final OpeningStockAssembler assembler;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public OpeningStockService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores,
                               OpeningStockRepository imports, ProductRepository products, TeamMemberPort team,
                               OpeningStockAssembler assembler, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.imports = imports;
        this.products = products;
        this.team = team;
        this.assembler = assembler;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- template and lists ----

    /** The fixed CSV template: any person with access to Pharmacy and Inventory. */
    @Transactional(readOnly = true)
    public String template(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        return OpeningStockCsv.TEMPLATE;
    }

    /** The owner's list over every store of the clinic, newest first. */
    @Transactional(readOnly = true)
    public PharmacyViews.ImportPage listAll(UUID tenantId, UUID userId, String rawStatus, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        ImportStatus status = PharmacyParams.optionalEnum("status", rawStatus, ImportStatus.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        OpeningStockRepository.Slice<ImportRow> slice = imports.listAll(tenantId, status, size, page * size);
        return new PharmacyViews.ImportPage(assembler.all(caller, slice.items()), slice.total(), page, size);
    }

    @Transactional(readOnly = true)
    public PharmacyViews.ImportPage listForStore(UUID tenantId, UUID userId, String rawStoreId, String rawPage,
                                                 String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        OpeningStockRepository.Slice<ImportRow> slice = imports.listForStore(tenantId, storeId, size, page * size);
        return new PharmacyViews.ImportPage(assembler.all(caller, slice.items()), slice.total(), page, size);
    }

    @Transactional(readOnly = true)
    public PharmacyViews.OpeningStockImport get(UUID tenantId, UUID userId, String rawStoreId, String rawImportId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound));
    }

    @Transactional(readOnly = true)
    public PharmacyViews.OpeningStockLinePage lines(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                                    String rawWithIssues, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        boolean withIssues = PharmacyParams.bool("withIssues", rawWithIssues, false);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 50, 1, 200);
        imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound);
        OpeningStockRepository.Slice<OpeningStockRepository.LineRow> slice = imports.lines(tenantId, importId, withIssues,
                size, page * size);
        List<UUID> ids = slice.items().stream().map(OpeningStockRepository.LineRow::id).toList();
        return new PharmacyViews.OpeningStockLinePage(
                OpeningStockAssembler.lines(slice.items(), imports.issuesOfLines(tenantId, ids)), slice.total(), page, size);
    }

    // ---- start ----

    /** For tests: the body is already read. */
    PharmacyViews.Outcome<PharmacyViews.OpeningStockImport> start(UUID tenantId, UUID userId, String rawStoreId,
                                                                  PharmacyInputs.StartImport input) {
        return start(tenantId, userId, rawStoreId, PharmacyInputs.Body.of(input));
    }

    @Transactional
    public PharmacyViews.Outcome<PharmacyViews.OpeningStockImport> start(UUID tenantId, UUID userId, String rawStoreId,
                                                                         PharmacyInputs.Body<PharmacyInputs.StartImport> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StartImport in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);

        ImportRow replay = replay(caller, storeId, in.clientKey());
        if (replay != null) {
            return new PharmacyViews.Outcome<>(assembler.one(caller, replay), false);
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        if (in.columns() == null || !OpeningStockCsv.matchesHeader(in.columns())) {
            errors.add(new FieldMessage("columns", OpeningStockCsv.HEADER_MESSAGE));
        }
        Integer rowCount = PharmacyParams.wholeNumber(in.rowCount(), 1, MAX_ROWS);
        if (rowCount == null) {
            errors.add(new FieldMessage("rowCount", ROWS_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }

        lockActiveStore(tenantId, storeId);
        replay = replay(caller, storeId, in.clientKey());
        if (replay != null) {
            return new PharmacyViews.Outcome<>(assembler.one(caller, replay), false);
        }
        if (imports.findWithStatus(tenantId, storeId, ImportStatus.APPROVED).isPresent()) {
            throw alreadyApproved();
        }
        requireNoOtherWaiting(caller, tenantId, storeId);
        // DEC-077 L8: counted under the store lock, so two starts cannot both take the tenth place
        Instant dayStart = time.dayStart();
        if (imports.countStarted(tenantId, storeId, caller.membershipId(), dayStart, dayStart.plus(1, ChronoUnit.DAYS))
                >= MAX_STARTS_PER_DAY) {
            throw new ConflictException("import-limit", "Too many files today", TOO_MANY_STARTS);
        }

        // security I10: the person's own drafts are locked (a line write in flight finishes first) before they are replaced
        List<ImportRow> drafts = imports.draftsOf(tenantId, storeId, caller.membershipId());
        UUID id = imports.insert(tenantId, store.unitId(), storeId, rowCount, caller.membershipId(), in.clientKey());
        UUID replaced = null;
        for (ImportRow draft : drafts) {
            if (!draft.id().equals(id) && imports.markReplaced(tenantId, draft.id(), draft.version(), id,
                    caller.membershipId())) {
                replaced = draft.id();
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("rowCount", rowCount);
        if (replaced != null) {
            after.put("replacedImportId", replaced.toString());
        }
        auditLog.record("PHARMACY_OPENING_STOCK_STARTED", ENTITY_TYPE, id, null, after);
        log.info("Opening stock import started importId={} storeId={} rowCount={}", id, storeId, rowCount);
        return new PharmacyViews.Outcome<>(assembler.one(caller, imports.find(tenantId, storeId, id).orElseThrow()), true);
    }

    /** The same client key from the same caller for the same store answers the import again; any other use is refused. */
    private ImportRow replay(PharmacyCaller caller, UUID storeId, UUID clientKey) {
        if (clientKey == null) {
            return null;
        }
        List<ImportRow> found = imports.findByClientKey(caller.tenantId(), clientKey);
        if (found.isEmpty()) {
            return null;
        }
        for (ImportRow row : found) {
            if (row.uploadedBy().equals(caller.membershipId()) && row.storeId().equals(storeId)) {
                return row;
            }
        }
        throw new ConflictException("client-key-reused", "Request already used", ProductService.KEY_REUSED);
    }

    /** Another person's import that waits for approval blocks a new one ("waiting for approval by <name>"). */
    private void requireNoOtherWaiting(PharmacyCaller caller, UUID tenantId, UUID storeId) {
        ImportRow waiting = imports.findWithStatus(tenantId, storeId, ImportStatus.WAITING_APPROVAL).orElse(null);
        if (waiting != null && !waiting.uploadedBy().equals(caller.membershipId())) {
            String name = team.find(tenantId, waiting.uploadedBy()).map(TeamMemberPort.TeamMember::name)
                    .orElse("another person");
            throw new ConflictException("import-waiting", "An import is waiting",
                    "An import for this store is waiting for approval by " + name + ".");
        }
    }

    // ---- rows ----

    /** For tests: the body is already read. */
    PharmacyViews.LinesResult addLines(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                       PharmacyInputs.ImportLines input) {
        return addLines(tenantId, userId, rawStoreId, rawImportId, PharmacyInputs.Body.of(input));
    }

    @Transactional
    public PharmacyViews.LinesResult addLines(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                              PharmacyInputs.Body<PharmacyInputs.ImportLines> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        PharmacyInputs.ImportLines in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        // D-2: the unit row (shared) comes first, as in every writer; a product edit or store change holds it FOR UPDATE
        imports.lockUnitShared(tenantId);
        ImportRow imp = imports.findForUpdate(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound);
        requireStarter(caller, imp, storeId);
        if (imp.status() != ImportStatus.DRAFT) {
            throw new ConflictException("import-state", "Import cannot be changed", STATE_LOCKED);
        }
        // DEC-077 I7 and L8(b): at most 2 x rowCount changed rows written per import, counted under the import row lock.
        // Once the cap is used up every request is refused here, before its body is read for field errors.
        int written = imports.lineRowsWritten(tenantId, importId);
        int cap = rowCap(imp.rowCount());
        if (written >= cap) {
            throw new ConflictException("import-state", "Import cannot be changed", STATE_LOCKED);
        }
        List<PharmacyInputs.ImportRow> rows = checkRows(in, imp.rowCount());

        // Security L8(a): a row sent again with the same seven cells writes nothing (no replace, no new issues); its
        // current issues are answered as they are. Only the changed and the new rows are checked and written.
        Map<Integer, OpeningStockRepository.StoredRow> stored = new java.util.HashMap<>();
        imports.storedRows(tenantId, importId, rows.stream().map(r -> r.rowNo().intValueExact()).toList())
                .forEach(row -> stored.put(row.rowNo(), row));
        List<PharmacyInputs.ImportRow> changed = new ArrayList<>();
        List<OpeningStockRepository.StoredRow> unchanged = new ArrayList<>();
        for (PharmacyInputs.ImportRow row : rows) {
            OpeningStockRepository.StoredRow was = stored.get(row.rowNo().intValueExact());
            if (was != null && was.raw().equals(rawOf(row))) {
                unchanged.add(was);
            } else {
                changed.add(row);
            }
        }

        // a request whose changed rows would pass the cap is refused whole; nothing has been written yet
        if (written + changed.size() > cap) {
            throw new ConflictException("import-state", "Import cannot be changed", STATE_LOCKED);
        }

        // L10: each named product is read FOR SHARE, so a product edit (which locks the row first) waits for this write
        Map<String, ProductRef> known = changed.isEmpty() ? Map.of() : products.activeByCodesForShare(tenantId,
                changed.stream().map(r -> r.productCode().trim().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet()));
        LocalDate today = time.today();
        List<LineIn> lines = new ArrayList<>();
        List<OpeningStockRules.Checked> checks = new ArrayList<>();
        for (PharmacyInputs.ImportRow row : changed) {
            OpeningStockRules.Raw raw = rawOf(row);
            ProductRef product = known.get(row.productCode().trim().toLowerCase(Locale.ROOT));
            OpeningStockRules.Checked checked = OpeningStockRules.check(raw, store.code(), product != null, today);
            checks.add(checked);
            lines.add(new LineIn(row.rowNo().intValueExact(), raw, product == null ? null : product.id(),
                    checked.parsed()));
        }

        if (!lines.isEmpty()) {
            imports.supersedeIssues(tenantId, importId, lines.stream().map(LineIn::rowNo).toList());
        }
        List<UUID> lineIds = new ArrayList<>();
        for (LineIn line : lines) {
            lineIds.add(imports.upsertLine(tenantId, store.unitId(), importId, line, caller.membershipId()));
        }
        List<PharmacyViews.RowIssue> answered = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            for (OpeningStockRules.Issue issue : checks.get(i).issues()) {
                imports.insertIssue(tenantId, store.unitId(), lineIds.get(i), issue.field(), issue.code(),
                        issue.severity(), caller.membershipId());
                answered.add(OpeningStockAssembler.issue(lines.get(i).rowNo(), issue.field(), issue.code(),
                        issue.severity()));
            }
        }
        if (!unchanged.isEmpty()) {
            imports.issuesOfLines(tenantId, unchanged.stream().map(OpeningStockRepository.StoredRow::lineId).toList())
                    .forEach(issue -> answered.add(OpeningStockAssembler.issue(issue)));
            // answered in the order the rows were sent
            Map<Integer, Integer> position = new java.util.HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                position.put(rows.get(i).rowNo().intValueExact(), i);
            }
            answered.sort(java.util.Comparator.comparingInt(issue -> position.get(issue.rowNo())));
        }
        if (!lines.isEmpty()) {
            imports.addLineRowsWritten(tenantId, importId, lines.size(), caller.membershipId());
        }
        int received = imports.find(tenantId, storeId, importId).orElseThrow().receivedRows();
        log.info("Opening stock rows stored importId={} rows={} unchangedRows={} issues={}", importId, rows.size(),
                unchanged.size(), answered.size());
        return new PharmacyViews.LinesResult(received, imp.rowCount(), answered);
    }

    /** Changed rows an import accepts in all: twice its row count (412 rows: 824). */
    static int rowCap(int rowCount) {
        return 2 * rowCount;
    }

    private static OpeningStockRules.Raw rawOf(PharmacyInputs.ImportRow row) {
        return new OpeningStockRules.Raw(row.storeCode(), row.productCode(), row.batch(), row.expiryDate(), row.mrp(),
                row.purchaseRate(), row.quantity());
    }

    /** Body fields, all errors together: 1 to 200 rows, each row number inside the file once, every cell present and short. */
    private static List<PharmacyInputs.ImportRow> checkRows(PharmacyInputs.ImportLines in, int rowCount) {
        List<FieldMessage> errors = new ArrayList<>();
        if (in.rows() == null || in.rows().isEmpty()) {
            errors.add(new FieldMessage("rows", UnitService.REQUIRED));
        } else if (in.rows().size() > MAX_BATCH) {
            errors.add(new FieldMessage("rows", "Send at most 200 rows at a time"));
        } else {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < in.rows().size(); i++) {
                PharmacyInputs.ImportRow row = in.rows().get(i);
                String at = "rows[" + i + "]";
                if (row == null) {
                    errors.add(new FieldMessage(at, UnitService.REQUIRED));
                    continue;
                }
                Integer rowNo = PharmacyParams.wholeNumber(row.rowNo(), 1, rowCount);
                if (rowNo == null) {
                    errors.add(new FieldMessage(at + ".rowNo", "Enter a row number from 1 to " + rowCount));
                } else if (!seen.add(rowNo)) {
                    errors.add(new FieldMessage(at + ".rowNo", "Send each row number once"));
                }
                cell(at + ".storeCode", row.storeCode(), FIELD_MAX, errors);
                cell(at + ".productCode", row.productCode(), FIELD_MAX, errors);
                cell(at + ".batch", row.batch(), FIELD_MAX, errors);
                cell(at + ".expiryDate", row.expiryDate(), 20, errors);
                cell(at + ".mrp", row.mrp(), 20, errors);
                cell(at + ".purchaseRate", row.purchaseRate(), 20, errors);
                cell(at + ".quantity", row.quantity(), 20, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return in.rows();
    }

    private static void cell(String field, String value, int max, List<FieldMessage> errors) {
        if (value == null) {
            errors.add(new FieldMessage(field, UnitService.REQUIRED));
        } else if (value.length() > max) {
            errors.add(new FieldMessage(field, "Keep it to " + max + " characters"));
        }
    }

    // ---- submit ----

    /**
     * Checks the whole file and sends it for approval, all or nothing. A file with any row error becomes NOT_ACCEPTED (final)
     * and answers 422; that change must be kept, so this method does not roll back for {@code NotAccepted}.
     */
    @Transactional(noRollbackFor = PharmacyProblemException.NotAccepted.class)
    public PharmacyViews.OpeningStockImport submit(UUID tenantId, UUID userId, String rawStoreId, String rawImportId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        Store store = guard.require(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        lockActiveStore(tenantId, storeId);
        ImportRow imp = imports.findForUpdate(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound);
        requireStarter(caller, imp, storeId);
        if (imp.status() != ImportStatus.DRAFT) {
            throw new ConflictException("import-state", "Import cannot be changed", STATE_LOCKED);
        }
        if (imp.receivedRows() < imp.rowCount()) {
            throw new ConflictException("import-incomplete", "Some rows are missing", INCOMPLETE);
        }
        if (imports.findWithStatus(tenantId, storeId, ImportStatus.APPROVED).isPresent()) {
            throw alreadyApproved();
        }
        requireNoOtherWaiting(caller, tenantId, storeId);

        flagRepeatedBatches(tenantId, store, importId, caller.membershipId());
        flagInactiveProducts(tenantId, store, importId, caller.membershipId());
        ImportRow now = imports.find(tenantId, storeId, importId).orElseThrow();
        Instant at = time.now();
        if (now.errorCount() > 0) {
            fix(tenantId, now, ImportStatus.NOT_ACCEPTED, null, caller);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("storeId", storeId.toString());
            after.put("errorCount", now.errorCount());
            auditLog.record("PHARMACY_OPENING_STOCK_NOT_ACCEPTED", ENTITY_TYPE, importId, null, after);
            log.info("Opening stock import not accepted importId={} errorRows={}", importId, now.errorCount());
            List<PharmacyViews.RowIssue> first = imports.errorIssues(tenantId, importId, ERROR_LIST_MAX).stream()
                    .map(OpeningStockAssembler::issue).toList();
            throw new PharmacyProblemException.NotAccepted(now.errorCount(), first);
        }

        UUID replaced = null;
        ImportRow mine = imports.findWithStatus(tenantId, storeId, ImportStatus.WAITING_APPROVAL).orElse(null);
        if (mine != null) {
            // my earlier waiting import goes first: only one import per store may wait for approval
            if (!imports.markReplaced(tenantId, mine.id(), mine.version(), importId, caller.membershipId())) {
                throw new IllegalStateException("An opening stock import changed under the store lock");
            }
            replaced = mine.id();
        }
        fix(tenantId, now, ImportStatus.WAITING_APPROVAL, at, caller);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("lineCount", now.lineCount());
        after.put("totalQuantity", now.totalQuantity());
        after.put("totalValuePaise", now.totalValuePaise());
        after.put("warningCount", now.warningCount());
        if (replaced != null) {
            after.put("replacedImportId", replaced.toString());
        }
        auditLog.record("PHARMACY_OPENING_STOCK_SUBMITTED", ENTITY_TYPE, importId, null, after);
        log.info("Opening stock import submitted importId={} lines={} warningRows={}", importId, now.lineCount(),
                now.warningCount());
        return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow());
    }

    /** The same product and batch twice: both rows are errors. Issues first, the import status last. */
    private void flagRepeatedBatches(UUID tenantId, Store store, UUID importId, UUID actor) {
        List<LineRef> repeated = imports.repeatedBatchLines(tenantId, importId);
        if (repeated.isEmpty()) {
            return;
        }
        Set<UUID> flagged = new HashSet<>(imports.linesWithIssue(tenantId, importId, "batch-repeated"));
        for (LineRef line : repeated) {
            if (!flagged.contains(line.id())) {
                imports.insertIssue(tenantId, store.unitId(), line.id(), "batch", "batch-repeated",
                        OpeningStockRules.Issue.ERROR, actor);
            }
        }
    }

    /** DEC-077 L10: a product switched off after its row was written makes that row an ERROR, like an unknown code. */
    private void flagInactiveProducts(UUID tenantId, Store store, UUID importId, UUID actor) {
        List<LineRef> inactive = imports.linesWithInactiveProduct(tenantId, importId);
        if (inactive.isEmpty()) {
            return;
        }
        Set<UUID> flagged = new HashSet<>(imports.linesWithIssue(tenantId, importId, "product-unknown"));
        for (LineRef line : inactive) {
            if (!flagged.contains(line.id())) {
                imports.insertIssue(tenantId, store.unitId(), line.id(), "productCode", "product-unknown",
                        OpeningStockRules.Issue.ERROR, actor);
            }
        }
    }

    private void fix(UUID tenantId, ImportRow now, ImportStatus status, Instant submittedAt, PharmacyCaller caller) {
        if (!imports.markSubmitted(tenantId, now.id(), now.version(), status, now.lineCount(), now.totalQuantity(),
                now.totalValuePaise(), now.errorCount(), now.warningCount(), submittedAt, caller.membershipId())) {
            throw new IllegalStateException("An opening stock import changed under its lock");
        }
    }

    // ---- decision ----

    @Transactional
    public PharmacyViews.OpeningStockImport approve(UUID tenantId, UUID userId, String rawStoreId, String rawImportId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        guard.require(caller, storeId, StoreCapability.APPROVE);
        lockActiveStore(tenantId, storeId);
        ImportRow imp = imports.findForUpdate(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound);
        if (imp.uploadedBy().equals(caller.membershipId())) {
            throw new ForbiddenException("self-approval", "Not allowed", SELF_APPROVAL);
        }
        if (imp.status() != ImportStatus.WAITING_APPROVAL) {
            throw new ConflictException("import-state", "Import is not waiting", NOT_WAITING);
        }
        if (imports.findWithStatus(tenantId, storeId, ImportStatus.APPROVED).isPresent()) {
            throw alreadyApproved();
        }
        Instant at = time.now();
        if (!imports.markApproved(tenantId, importId, imp.version(), caller.membershipId(), at)) {
            throw new IllegalStateException("An opening stock import changed under its lock");
        }
        stores.markHasStock(tenantId, storeId, userId);
        imports.markProductsHasStock(tenantId, importId, userId);
        Map<String, Object> before = Map.of("status", ImportStatus.WAITING_APPROVAL.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ImportStatus.APPROVED.name());
        after.put("storeId", storeId.toString());
        after.put("uploadedBy", imp.uploadedBy().toString());
        after.put("approvedBy", caller.membershipId().toString());
        after.put("approvedAt", at.toString());
        after.put("lineCount", imp.lineCount());
        after.put("totalQuantity", imp.totalQuantity());
        after.put("totalValuePaise", imp.totalValuePaise());
        auditLog.record("PHARMACY_OPENING_STOCK_APPROVED", ENTITY_TYPE, importId, before, after);
        log.info("Opening stock import approved importId={} lines={}", importId, imp.lineCount());
        return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow());
    }

    /** For tests: the body is already read. */
    PharmacyViews.OpeningStockImport reject(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                            PharmacyInputs.RejectImport input) {
        return reject(tenantId, userId, rawStoreId, rawImportId, PharmacyInputs.Body.of(input));
    }

    /**
     * Any owner may reject, the uploader included: it approves nothing. An import in DRAFT may be rejected as well as one that
     * waits for approval (DEC-076): a draft nobody finishes must not hold the store and its products. Any other state is 409.
     */
    @Transactional
    public PharmacyViews.OpeningStockImport reject(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                                   PharmacyInputs.Body<PharmacyInputs.RejectImport> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        PharmacyInputs.RejectImport in = body.read();
        guard.require(caller, storeId, StoreCapability.APPROVE);
        ImportRow imp = imports.findForUpdate(tenantId, storeId, importId).orElseThrow(OpeningStockService::notFound);

        List<FieldMessage> errors = new ArrayList<>();
        String note = in.note() == null ? null : in.note().trim();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (in.reasonKey() == null) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey() == RejectReason.OTHER && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (imp.status() != ImportStatus.WAITING_APPROVAL && imp.status() != ImportStatus.DRAFT) {
            throw new ConflictException("import-state", "Import cannot be changed", STATE_LOCKED);
        }
        if (!imports.markRejected(tenantId, importId, imp.version(), caller.membershipId(), time.now(), in.reasonKey(),
                note)) {
            throw new IllegalStateException("An opening stock import changed under its lock");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ImportStatus.REJECTED.name());
        after.put("storeId", storeId.toString());
        after.put("reasonKey", in.reasonKey().name());
        after.put("note", note);
        auditLog.record("PHARMACY_OPENING_STOCK_REJECTED", ENTITY_TYPE, importId,
                Map.of("status", imp.status().name()), after);
        log.info("Opening stock import rejected importId={} reason={}", importId, in.reasonKey());
        return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow());
    }

    // ---- helpers ----

    /**
     * Takes the store row lock (the repository takes the unit row, shared, just before it: lock order unit, store, import,
     * products; D-2) and re-checks that the store is still ACTIVE: a store switched off between the guard's check and this
     * lock answers 404, nothing written (DEC-077 L9).
     */
    private Store lockActiveStore(UUID tenantId, UUID storeId) {
        return stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(OpeningStockService::notFound);
    }

    /** Only the person who started the import writes to it (403 store-permission-denied, audited). */
    private void requireStarter(PharmacyCaller caller, ImportRow imp, UUID storeId) {
        if (!imp.uploadedBy().equals(caller.membershipId())) {
            throw guard.refuse(caller, storeId, StoreCapability.UPLOAD_OPENING_STOCK);
        }
    }

    private static ConflictException alreadyApproved() {
        return new ConflictException("opening-stock-already-approved", "Already approved", ALREADY_APPROVED);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }

}
