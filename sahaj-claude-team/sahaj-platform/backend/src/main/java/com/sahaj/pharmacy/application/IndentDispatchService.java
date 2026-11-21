package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DispatchRepository.Row;
import com.sahaj.pharmacy.application.IndentRepository.Line;
import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.IndentProgress;
import com.sahaj.pharmacy.domain.IndentStatus;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-11: the Central Store dispatches an approved indent (docs/api/pharmacy.yaml 0.3.5 pharmacyDispatchIndent,
 * pharmacyIndentReplacementOptions, pharmacyGetDispatch, pharmacyListIncomingDispatches; ADR-029 P12). The pick is first expiry
 * first out per line (the client never names a batch); a line may be changed (a replacement product, a smaller quantity with a
 * back-order or a closed balance) with a reason of the {@code cs-change} list. In one transaction the units leave the Central
 * Store's AVAILABLE and enter the requesting store's IN_TRANSIT (ledger INDENT_DISPATCH, two lines of one movement), the dispatch
 * and its lines are written, the indent lines take their totals and the indent moves to DISPATCHED or PARTLY_DISPATCHED.
 *
 * <p>Lock order: the client key, the Central Store row, the indent header, its lines (ascending id), then the stock rows of both
 * stores sorted by store id (inside {@link StockPosting}); the new rows are inserted last. A Schedule X line needs a pharmacist and
 * the verified second signer; {@link #precheck} says whether the controller must check a PIN before this transaction starts. The
 * actor of every writer is the caller's membership id. Logs and audit rows hold ids and counts only.
 *
 * <p>The replacement-options GET is a read-write transaction on purpose: it sweeps expired batches out of AVAILABLE first
 * ({@code posting.sweepStore}, ADR-029 P4), so what it shows is what a dispatch would pick.
 */
@Service
public class IndentDispatchService {

    static final String OP_DISPATCH = "pharmacyDispatchIndent";
    static final String NOTHING = "Nothing to dispatch";
    static final String LIST_EVERY = "List every item that still has a quantity to dispatch";
    private static final Logger log = LoggerFactory.getLogger(IndentDispatchService.class);
    private static final String ENTITY_TYPE = "PHARMACY_DISPATCH";
    private static final String DOC_TYPE = IndentService.DOC_TYPE;
    private static final int MAX_LINES = 200;
    static final int MAX_DISPATCH_LINES = 400;
    static final String SPLIT_MESSAGE = "Split this dispatch into smaller ones.";
    private static final int MAX_QTY = 999_999;
    private static final int MAX_OPTIONS = 50;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final IndentRepository indents;
    private final DispatchRepository dispatches;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final DispatchAssembler assembler;
    private final IndentService indentService;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final BlindCount blind;

    public IndentDispatchService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                                 IndentRepository indents, DispatchRepository dispatches, StockRepository stock,
                                 StockPosting posting, ScheduleXSignature signature, ClientRequestRepository clients,
                                 DocumentEventRepository events, ActiveReasonService reasons, DispatchAssembler assembler,
                                 IndentService indentService, AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.indents = indents;
        this.dispatches = dispatches;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.assembler = assembler;
        this.indentService = indentService;
        this.auditLog = auditLog;
        this.time = time;
        this.blind = blind;
    }

    /** One line of the dispatch after the checks. {@code action} is null when the whole remaining quantity goes. */
    record Checked(Line line, UUID productId, boolean replaced, int qty, int remaining, String action, String reasonKey,
                   String note, boolean scheduleX) {
    }

    // ---- replacement options ----

    /** Products the Central Store may send instead of the requested one, with their AVAILABLE unexpired quantity. */
    @Transactional
    public DispatchViews.ReplacementOptions replacementOptions(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                                               String rawLineId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        UUID lineId = PharmacyParams.pathId(rawLineId);
        Store store = centralStore(caller, storeId);
        IndentRepository.Row row = indents.findById(tenantId, indentId).filter(r -> r.toStoreId().equals(storeId))
                .orElseThrow(IndentDispatchService::notFound);
        Line line = indents.lines(tenantId, row.id()).stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(IndentDispatchService::notFound);
        posting.sweepStore(tenantId, store.unitId(), storeId, caller.actorId());
        LocalDate today = time.today();
        List<DispatchViews.ReplacementOption> items = new ArrayList<>();
        for (UUID candidate : dispatches.replacementCandidates(tenantId, line.productId(), MAX_OPTIONS)) {
            ProductRepository.Row product = products.find(tenantId, candidate).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                continue;
            }
            int available = stock.availableOfProduct(tenantId, storeId, candidate).stream()
                    .filter(b -> !b.expiryDate().isBefore(today)).mapToInt(Bal::qty).sum();
            items.add(new DispatchViews.ReplacementOption(IndentService.productRef(product), blind.available(caller, storeId, candidate, available)));
        }
        return new DispatchViews.ReplacementOptions(items);
    }

    // ---- dispatch ----

    /**
     * The second signer to verify before the dispatch transaction: only for a dispatch that has a Schedule X line with a quantity
     * above 0, only for a caller who is a pharmacist, and only when the key has not been used already. A caller who is not a
     * pharmacist answers 403 schedule-x-pharmacist here, before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawIndentId,
                                                     PharmacyInputs.Body<PharmacyInputs.IndentDispatch> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.IndentDispatch in = body.read();
        centralStore(caller, storeId);
        IndentRepository.Row peek = indents.findById(tenantId, indentId).filter(r -> r.toStoreId().equals(storeId))
                .orElseThrow(IndentDispatchService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!dispatchable(peek)) {
            return null;
        }
        boolean scheduleX = false;
        for (PharmacyInputs.DispatchLineInput d : in.lines() == null ? List.<PharmacyInputs.DispatchLineInput>of() : in.lines()) {
            if (d != null && d.dispatchProductId() != null && d.quantity() != null && d.quantity().signum() > 0) {
                scheduleX |= products.find(tenantId, d.dispatchProductId()).map(f -> GoodsReceiptService.isScheduleX(f.row()))
                        .orElse(false);
            }
        }
        if (!scheduleX) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<DispatchViews.Dispatch> dispatch(UUID tenantId, UUID userId, String rawStoreId,
                                                                  String rawIndentId,
                                                                  PharmacyInputs.Body<PharmacyInputs.IndentDispatch> body,
                                                                  UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID indentId = PharmacyParams.pathId(rawIndentId);
        PharmacyInputs.IndentDispatch in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        IndentRepository.Row peek = indents.findById(tenantId, indentId).filter(r -> r.toStoreId().equals(storeId))
                .orElseThrow(IndentDispatchService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                Row first = OP_DISPATCH.equals(own.operation()) ? dispatches.findById(tenantId, own.subjectId()).orElse(null)
                        : null;
                if (first == null || !indentId.equals(first.indentId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return new PharmacyViews.Outcome<>(assembler.view(caller, first, storeId), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.indentVersion(), errors);
        if (version != null && peek.version() != version) {
            throw PharmacyProblemException.changed(indentService.get(tenantId, userId, rawStoreId, rawIndentId));
        }
        requireDispatchable(peek);
        List<Checked> checked = checkLines(tenantId, storeId, in, indents.lines(tenantId, indentId), errors);
        throwIfAny(errors);
        UUID dispatchId = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_DISPATCH, dispatchId, false) != null) {
            return dispatch(tenantId, userId, rawStoreId, rawIndentId, body, verifiedSignerMembershipId); // L15
        }

        // lock order: the Central Store row, the header, the lines (ascending id), then the stock rows inside StockPosting
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(IndentDispatchService::notFound);
        IndentRepository.Row row = indents.findForUpdateById(tenantId, indentId).orElseThrow(IndentDispatchService::notFound);
        if (version != null && row.version() != version) {
            throw PharmacyProblemException.changed(indentService.get(tenantId, userId, rawStoreId, rawIndentId));
        }
        requireDispatchable(row);
        List<Line> locked = indents.linesForUpdate(tenantId, indentId);
        List<FieldMessage> recheck = new ArrayList<>();
        checked = checkLines(tenantId, storeId, in, locked, recheck);
        throwIfAny(recheck);

        UUID signer = null;
        if (checked.stream().anyMatch(c -> c.qty() > 0 && c.scheduleX())) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }

        Map<Checked, List<Fefo.Pick>> picks = pick(caller, tenantId, storeId, checked);
        if (picks.values().stream().mapToInt(List::size).sum() > MAX_DISPATCH_LINES) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", SPLIT_MESSAGE)));
        }
        int seq = dispatches.nextSeq(tenantId, indentId);
        String reference = row.docNo() + " / " + seq;
        List<StockPosting.Line> moves = new ArrayList<>();
        List<DispatchRepository.NewLine> newLines = new ArrayList<>();
        int rowNo = 0;
        for (Checked c : checked) {
            boolean first = true;
            for (Fefo.Pick pick : picks.getOrDefault(c, List.of())) {
                rowNo++;
                UUID lineId = UUID.randomUUID();
                UUID movement = UUID.randomUUID();
                StockPosting.Doc doc = new StockPosting.Doc("DISPATCH", dispatchId, reference, lineId);
                UUID lineSigner = c.scheduleX() ? signer : null;
                moves.add(new StockPosting.Line(storeId, c.productId(), pick.batchId(), StockState.AVAILABLE, -pick.quantity(),
                        "INDENT_DISPATCH", null, movement, null, doc, rowNo, lineSigner));
                moves.add(new StockPosting.Line(row.fromStoreId(), c.productId(), pick.batchId(), StockState.IN_TRANSIT,
                        pick.quantity(), "INDENT_DISPATCH", null, movement, null, doc, rowNo, lineSigner));
                int backOrdered = first && "BACK_ORDER".equals(c.action()) ? c.remaining() - c.qty() : 0;
                newLines.add(new DispatchRepository.NewLine(lineId, store.unitId(), dispatchId, c.line().id(), c.productId(),
                        pick.batchId(), pick.quantity(), c.replaced(), c.replaced() ? c.reasonKey() : null,
                        c.replaced() ? c.note() : null, backOrdered, c.scheduleX(), me));
                first = false;
            }
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }

        dispatches.insert(tenantId, new DispatchRepository.NewDispatch(dispatchId, store.unitId(), indentId, seq, reference,
                storeId, row.fromStoreId(), me, signer));
        newLines.forEach(l -> dispatches.insertLine(tenantId, l));
        int total = 0;
        for (Checked c : checked) {
            total += c.qty();
            int closed = "CLOSE".equals(c.action()) ? c.remaining() - c.qty() : 0;
            boolean change = c.qty() > 0 || closed > 0 || c.action() != null;
            if (!change) {
                continue;
            }
            indents.applyDispatch(tenantId, c.line().id(), c.qty(), closed, c.action(),
                    "CLOSE".equals(c.action()) ? c.reasonKey() : null, c.replaced() ? c.productId() : null,
                    c.replaced() ? c.reasonKey() : null, me);
            events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "LINE_DISPATCHED", storeId, me, null, c.line().id(),
                    c.line().dispatchedQty(), c.line().dispatchedQty() + c.qty(), c.reasonKey());
        }
        List<Line> after = indents.lines(tenantId, indentId);
        IndentStatus target = IndentProgress.afterDispatch(figures(after));
        indents.setStatusLocked(tenantId, indentId, row.status(), target.name(), me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, indentId, "DISPATCHED", storeId, me, null, null, null, null, null);
        Map<String, Object> afterValues = new LinkedHashMap<>();
        afterValues.put("dispatchId", dispatchId.toString());
        afterValues.put("status", target.name());
        afterValues.put("lineCount", newLines.size());
        afterValues.put("units", total);
        auditLog.record("PHARMACY_INDENT_DISPATCHED", ENTITY_TYPE, dispatchId, Map.of("status", row.status()), afterValues);
        log.info("Indent dispatched indentId={} dispatchId={} lines={}", indentId, dispatchId, newLines.size());
        return new PharmacyViews.Outcome<>(assembler.view(caller, dispatches.findById(tenantId, dispatchId).orElseThrow(),
                storeId), true);
    }

    // ---- reads ----

    /** The sending store (purchase) or the receiving store (receive) reads a dispatch; for anyone else it does not exist. */
    @Transactional(readOnly = true)
    public DispatchViews.Dispatch get(UUID tenantId, UUID userId, String rawStoreId, String rawDispatchId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dispatchId = PharmacyParams.pathId(rawDispatchId);
        Row row = dispatches.findById(tenantId, dispatchId).orElse(null);
        if (row != null && row.fromStoreId().equals(storeId)) {
            guard.require(caller, storeId, StoreCapability.PURCHASE);
        } else {
            guard.require(caller, storeId, StoreCapability.RECEIVE);
            if (row == null || !row.toStoreId().equals(storeId)) {
                throw notFound();
            }
        }
        return assembler.view(caller, row, storeId);
    }

    /** Dispatches in transit to this store (status IN_TRANSIT also lists PARTLY_RECEIVED), or already received. Oldest first. */
    @Transactional(readOnly = true)
    public DispatchViews.DispatchPage incoming(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawPage,
                                               String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.RECEIVE);
        String status = rawStatus == null || rawStatus.isEmpty() ? "IN_TRANSIT" : rawStatus;
        if (!"IN_TRANSIT".equals(status) && !"RECEIVED".equals(status)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("status", "Choose in transit or received")));
        }
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        DispatchRepository.Slice slice = dispatches.incoming(tenantId, storeId, "RECEIVED".equals(status), size, page * size);
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        List<DispatchViews.DispatchItem> items = slice.items().stream()
                .map(r -> new DispatchViews.DispatchItem(r.id(), r.reference(), r.sourceType(),
                        OpeningStockPostService.storeRef(storeById.get(r.fromStoreId())), r.itemCount(), r.status(),
                        r.dispatchedAt())).toList();
        return new DispatchViews.DispatchPage(items, slice.total(), page, size);
    }

    // ---- checks ----

    /**
     * All the line errors together (rule 21). A line may be sent with a smaller quantity (a back-order or a closed balance), or
     * with a replacement product; every line with a quantity still to dispatch must be listed; not all quantities may be 0.
     */
    List<Checked> checkLines(UUID tenantId, UUID storeId, PharmacyInputs.IndentDispatch in, List<Line> indentLines,
                             List<FieldMessage> errors) {
        List<PharmacyInputs.DispatchLineInput> given = in.lines() == null ? List.of() : in.lines();
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", IndentService.MAX_LINES_MESSAGE));
            return List.of();
        }
        Map<UUID, Line> byId = new HashMap<>();
        indentLines.forEach(l -> byId.put(l.id(), l));
        Map<UUID, Set<UUID>> optionsOf = new HashMap<>();
        List<Checked> checked = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        boolean any = false;
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.DispatchLineInput d = given.get(i);
            String at = "lines[" + i + "]";
            if (d == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            Line line = d.indentLineId() == null ? null : byId.get(d.indentLineId());
            if (line == null || !seen.add(d.indentLineId())) {
                errors.add(new FieldMessage(at + ".indentLineId", "Choose a line of this indent"));
                continue;
            }
            int remaining = remaining(line);
            Integer qty = PharmacyParams.wholeNumber(d.quantity(), 0, Math.min(remaining, MAX_QTY));
            boolean ok = true;
            if (qty == null) {
                errors.add(new FieldMessage(at + ".quantity", "Enter a whole number from 0 to " + remaining));
                ok = false;
            }
            UUID productId = d.dispatchProductId();
            if (productId == null) {
                errors.add(new FieldMessage(at + ".dispatchProductId", "Choose a product"));
                ok = false;
            }
            if (!ok) {
                continue;
            }
            boolean other = !productId.equals(line.productId());
            boolean replaced = other && qty > 0;
            if (replaced) {
                Set<UUID> options = optionsOf.computeIfAbsent(line.productId(), p -> new LinkedHashSet<>(
                        dispatches.replacementCandidates(tenantId, p, MAX_OPTIONS)));
                if (!options.contains(productId)) {
                    errors.add(new FieldMessage(at + ".dispatchProductId",
                            "Choose the requested product or one of the replacement options"));
                    continue;
                }
            }
            String action = d.balanceAction();
            if (qty < remaining) {
                if (!"BACK_ORDER".equals(action) && !"CLOSE".equals(action)) {
                    errors.add(new FieldMessage(at + ".balanceAction", "Choose back-order or close"));
                    continue;
                }
            } else {
                action = null;
            }
            String note = d.changeNote() == null || d.changeNote().isBlank() ? null : d.changeNote().trim();
            boolean needsReason = replaced || "CLOSE".equals(action);
            String reasonKey = needsReason ? d.changeReasonKey() : null;
            if (needsReason) {
                if (!reasons.isActive(tenantId, ReasonList.CS_CHANGE, reasonKey)) {
                    errors.add(new FieldMessage(at + ".changeReasonKey", "Choose a reason"));
                    continue;
                }
                if (reasonKey.endsWith(".other") && (note == null || note.length() < 4)) {
                    errors.add(new FieldMessage(at + ".changeNote", "Write at least 4 characters"));
                    continue;
                }
            }
            if (note != null && note.length() > 300) {
                errors.add(new FieldMessage(at + ".changeNote", "Keep the note to 300 characters"));
                continue;
            }
            ProductRepository.Row product = products.find(tenantId, productId).map(ProductRepository.Full::row).orElse(null);
            if (product == null) {
                errors.add(new FieldMessage(at + ".dispatchProductId", "Choose an active product"));
                continue;
            }
            any |= qty > 0;
            checked.add(new Checked(line, productId, replaced, qty, remaining, action, reasonKey, needsReason ? note : null,
                    GoodsReceiptService.isScheduleX(product)));
        }
        for (Line line : indentLines) {
            if (remaining(line) > 0 && !seen.contains(line.id())) {
                errors.add(new FieldMessage("lines", LIST_EVERY));
                break;
            }
        }
        if (errors.isEmpty() && !any) {
            errors.add(new FieldMessage("lines", NOTHING));
        }
        return checked;
    }

    /**
     * The first-expiry-first-out pick of each line from the Central Store's AVAILABLE unexpired rows, read now (the posting locks
     * and checks again under the locks, so a row that has changed answers 409 insufficient-stock). Lines of one product share what
     * is there. Above what is usable: 409 insufficient-stock with the first line's available quantity and the failing lines.
     */
    private Map<Checked, List<Fefo.Pick>> pick(PharmacyCaller caller, UUID tenantId, UUID storeId, List<Checked> checked) {
        Map<UUID, Integer> taken = new HashMap<>();
        Map<Checked, List<Fefo.Pick>> picks = new LinkedHashMap<>();
        List<FailingLine> failing = new ArrayList<>();
        int rowNo = 0;
        for (Checked c : checked) {
            rowNo++;
            if (c.qty() == 0) {
                continue;
            }
            List<Bal> rows = stock.availableOfProduct(tenantId, storeId, c.productId()).stream()
                    .map(b -> new Bal(b.id(), b.storeId(), b.productId(), b.batchId(), b.state(),
                            b.qty() - taken.getOrDefault(b.batchId(), 0), b.batchNo(), b.expiryDate(), b.firstReceivedAt(),
                            b.mrp(), b.purchaseRate())).toList();
            Fefo.Result result = posting.pick(rows, c.qty());
            if (result.shortBy() > 0) {
                failing.add(new FailingLine(c.line().id(), rowNo, c.productId(), result.available()));
                continue;
            }
            result.picks().forEach(p -> taken.merge(p.batchId(), p.quantity(), Integer::sum));
            picks.put(c, result.picks());
        }
        if (!failing.isEmpty()) {
            throw blind.scrub(caller, storeId, PharmacyProblemException.insufficientStock(failing.get(0).available(), failing));
        }
        return picks;
    }

    static List<IndentProgress.LineFigures> figures(List<Line> lines) {
        return lines.stream().map(l -> new IndentProgress.LineFigures(l.approvedQty() == null ? 0 : l.approvedQty(),
                l.dispatchedQty(), l.closedQty(), l.receivedQty(), l.rejectedQty(), l.shortQty())).toList();
    }

    private static int remaining(Line l) {
        return Math.max(0, (l.approvedQty() == null ? 0 : l.approvedQty()) - l.dispatchedQty() - l.closedQty());
    }

    private static boolean dispatchable(IndentRepository.Row row) {
        return IndentStatus.APPROVED.name().equals(row.status()) || IndentStatus.PARTLY_DISPATCHED.name().equals(row.status());
    }

    private static void requireDispatchable(IndentRepository.Row row) {
        if (!dispatchable(row)) {
            throw PharmacyProblemException.docState("Only an approved indent or one with a back-order open can be dispatched.");
        }
    }

    private Store centralStore(PharmacyCaller caller, UUID storeId) {
        Store store = guard.require(caller, storeId, StoreCapability.PURCHASE);
        if (!store.type().isCentral()) {
            throw PharmacyProblemException.docState("Indents are dispatched from the Central Store.");
        }
        return store;
    }

    private static Long version(java.math.BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("indentVersion", UnitService.RELOAD));
            return null;
        }
        return whole.longValue();
    }

    private static void throwIfAny(List<FieldMessage> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
