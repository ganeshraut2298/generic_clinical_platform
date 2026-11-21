package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.DispatchRepository.Line;
import com.sahaj.pharmacy.application.DispatchRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.IndentProgress;
import com.sahaj.pharmacy.domain.IndentStatus;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.ReturnRouting;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.math.BigDecimal;
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
 * SAH-79-12: the requesting store confirms receipt of a dispatch (docs/api/pharmacy.yaml 0.3.5 pharmacyReceiveDispatch; ADR-029
 * P12). Each line is received once: accepted units go IN_TRANSIT to AVAILABLE (ledger INDENT_RECEIPT_ACCEPTED), units rejected
 * for a physical reason go IN_TRANSIT to PENDING_RETURN at this store with a return item for the Central Store (ledger
 * INDENT_RECEIPT_REJECTED), and units that did not arrive ("Short / not received") stay IN_TRANSIT with an open shortage. A Schedule
 * X line is received only by a pharmacist with the verified second signer; any other caller sends it with {@code skipped} and the
 * dispatch stays PARTLY_RECEIVED (and the indent stays as it is, with the chip) until a pharmacist receives it. A re-send for a
 * PARTLY_RECEIVED dispatch is for the lines not yet received.
 *
 * <p>Lock order: the client key, the receiving store row, the dispatch, the indent header, its lines (ascending id), then the stock
 * rows of the receiving store inside {@link StockPosting}; receipt lines are inserted before the dispatch takes its receipt
 * status; the new return items and shortages are new rows. The actor of every writer is the caller's membership id. Logs and
 * audit rows hold ids and counts only.
 */
@Service
public class DispatchReceiptService {

    static final String OP_RECEIVE = "pharmacyReceiveDispatch";
    static final String SUM_MESSAGE = "Accepted + Rejected must equal Dispatched";
    static final String REASON_MESSAGE = "Choose a reason for the rejected quantity";
    static final String EXPIRED_MESSAGE = "An expired batch cannot be accepted. Reject it.";
    static final String LIST_EVERY = "Send every line not yet received, once.";
    static final String NOTHING = "There is nothing to receive.";
    static final String NEAR_EXPIRY_KEY = "receipt-reject.near-expiry";
    static final String EXPIRED_REASON_MESSAGE = "Reject an expired batch as near expiry";
    private static final Logger log = LoggerFactory.getLogger(DispatchReceiptService.class);
    private static final String ENTITY_TYPE = "PHARMACY_DISPATCH";
    private static final String DOC_TYPE = IndentService.DOC_TYPE;
    private static final int MAX_LINES = 400;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final IndentRepository indents;
    private final DispatchRepository dispatches;
    private final ReturnItemRepository returns;
    private final ShortageRepository shortages;
    private final StockRepository stock;
    private final ProductRepository products;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final DispatchAssembler assembler;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final TransferRepository transfers;

    public DispatchReceiptService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, IndentRepository indents,
                                  DispatchRepository dispatches, ReturnItemRepository returns, ShortageRepository shortages,
                                  StockRepository stock, ProductRepository products, StockPosting posting,
                                  ScheduleXSignature signature,
                                  ClientRequestRepository clients, DocumentEventRepository events, ActiveReasonService reasons,
                                  DispatchAssembler assembler, AuditLog auditLog, PharmacyTime time,
                                  TransferRepository transfers) {
        this.transfers = transfers;
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.indents = indents;
        this.dispatches = dispatches;
        this.returns = returns;
        this.shortages = shortages;
        this.stock = stock;
        this.products = products;
        this.posting = posting;
        this.signature = signature;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.assembler = assembler;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * One line of the receipt after the checks. {@code skipped} lines are left for a pharmacist; for the others
     * {@code accepted + rejected} is the dispatched quantity and {@code shortQty} is the part of {@code rejected} that never
     * arrived (reason "Short / not received"), the rest being a physical return.
     */
    record Checked(Line line, boolean skipped, int accepted, int rejected, String reasonKey, String note, boolean expired) {

        boolean isShort() {
            return !skipped && rejected > 0 && ReturnRouting.isShort(reasonKey);
        }

        int shortQty() {
            return isShort() ? rejected : 0;
        }

        int returnedQty() {
            return !skipped && rejected > 0 && !isShort() ? rejected : 0;
        }
    }

    /**
     * The second signer to verify before the receipt transaction: only when a Schedule X line that is not yet received is sent
     * without {@code skipped}, only for a pharmacist's receipt, and only when the key has not been used already. A caller who is
     * not a pharmacist answers 403 schedule-x-pharmacist here, before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawDispatchId,
                                                     PharmacyInputs.Body<PharmacyInputs.DispatchReceipt> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dispatchId = PharmacyParams.pathId(rawDispatchId);
        PharmacyInputs.DispatchReceipt in = body.read();
        guard.require(caller, storeId, StoreCapability.RECEIVE);
        Row peek = dispatches.findById(tenantId, dispatchId).filter(d -> d.toStoreId().equals(storeId) && (d.indentId() != null || d.transferId() != null))
                .orElseThrow(DispatchReceiptService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if ("RECEIVED".equals(peek.status())) {
            return null;
        }
        Map<UUID, Line> open = new HashMap<>();
        marked(tenantId, dispatches.unreceivedLines(tenantId, dispatchId)).forEach(l -> open.put(l.id(), l));
        boolean scheduleX = false;
        for (PharmacyInputs.ReceiptLineInput d : in.lines() == null ? List.<PharmacyInputs.ReceiptLineInput>of() : in.lines()) {
            Line line = d == null || d.dispatchLineId() == null ? null : open.get(d.dispatchLineId());
            scheduleX |= line != null && line.scheduleX() && !Boolean.TRUE.equals(d.skipped());
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
    public DispatchViews.Dispatch receive(UUID tenantId, UUID userId, String rawStoreId, String rawDispatchId,
                                          PharmacyInputs.Body<PharmacyInputs.DispatchReceipt> body,
                                          UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID dispatchId = PharmacyParams.pathId(rawDispatchId);
        PharmacyInputs.DispatchReceipt in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RECEIVE);
        UUID me = caller.actorId();
        Row peek = dispatches.findById(tenantId, dispatchId).filter(d -> d.toStoreId().equals(storeId) && (d.indentId() != null || d.transferId() != null))
                .orElseThrow(DispatchReceiptService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_RECEIVE.equals(own.operation()) || !dispatchId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return assembler.view(caller, peek, storeId);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.dispatchVersion(), errors);
        if ("RECEIVED".equals(peek.status()) || version != null && peek.version() != version) {
            throw PharmacyProblemException.changed(assembler.view(caller, peek, storeId));
        }
        List<Checked> checked = checkLines(tenantId, peek, marked(tenantId, dispatches.lines(tenantId, dispatchId)),
                marked(tenantId, dispatches.unreceivedLines(tenantId, dispatchId)), in, errors);
        throwIfAny(errors);
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_RECEIVE, dispatchId, true) != null) {
            return receive(tenantId, userId, rawStoreId, rawDispatchId, body, verifiedSignerMembershipId); // L15
        }

        // lock order: the receiving store row, the dispatch, the indent header, its lines (ascending id), then the stock rows
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(DispatchReceiptService::notFound);
        Row row = dispatches.findForUpdate(tenantId, dispatchId).orElseThrow(DispatchReceiptService::notFound);
        if ("RECEIVED".equals(row.status()) || version != null && row.version() != version) {
            throw PharmacyProblemException.changed(assembler.view(caller, row, storeId));
        }
        IndentRepository.Row indent = row.indentId() == null ? null
                : indents.findForUpdateById(tenantId, row.indentId()).orElseThrow(DispatchReceiptService::notFound);
        TransferRepository.Row transfer = row.transferId() == null ? null
                : transfers.findForUpdate(tenantId, row.transferId()).orElseThrow(DispatchReceiptService::notFound);
        if (indent != null) {
            indents.linesForUpdate(tenantId, indent.id());
        }
        boolean viaTransfer = transfer != null;
        String docType = viaTransfer ? TransferService.DOC_TYPE : DOC_TYPE;
        UUID docId = viaTransfer ? transfer.id() : indent.id();
        String acceptedType = viaTransfer ? "TRANSFER_RECEIPT_ACCEPTED" : "INDENT_RECEIPT_ACCEPTED";
        String rejectedType = viaTransfer ? "TRANSFER_RECEIPT_REJECTED" : "INDENT_RECEIPT_REJECTED";
        List<FieldMessage> recheck = new ArrayList<>();
        checked = checkLines(tenantId, row, marked(tenantId, dispatches.lines(tenantId, dispatchId)),
                marked(tenantId, dispatches.unreceivedLines(tenantId, dispatchId)), in, recheck);
        throwIfAny(recheck);

        UUID signer = null;
        if (checked.stream().anyMatch(c -> !c.skipped() && c.line().scheduleX())) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }

        // the stock: accepted units to AVAILABLE, units sent back to PENDING_RETURN; short units stay IN_TRANSIT
        List<StockPosting.Line> moves = new ArrayList<>();
        int rowNo = 0;
        for (Checked c : checked) {
            rowNo++;
            if (c.skipped()) {
                continue;
            }
            Line l = c.line();
            StockPosting.Doc doc = new StockPosting.Doc("DISPATCH", dispatchId, row.reference(), l.id());
            UUID lineSigner = l.scheduleX() ? signer : null;
            if (c.accepted() > 0) {
                UUID movement = UUID.randomUUID();
                moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.IN_TRANSIT, -c.accepted(),
                        acceptedType, null, movement, null, doc, rowNo, lineSigner));
                moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.AVAILABLE, c.accepted(),
                        acceptedType, null, movement, null, doc, rowNo, lineSigner));
            }
            if (c.returnedQty() > 0) {
                UUID movement = UUID.randomUUID();
                moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.IN_TRANSIT, -c.returnedQty(),
                        rejectedType, c.reasonKey(), movement, null, doc, rowNo, lineSigner));
                moves.add(new StockPosting.Line(storeId, l.productId(), l.batchId(), StockState.PENDING_RETURN, c.returnedQty(),
                        rejectedType, c.reasonKey(), movement, null, doc, rowNo, lineSigner));
            }
        }
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);

        // receipt lines before the dispatch takes its status; totals on the indent lines; return items and shortages
        Map<UUID, int[]> totals = new LinkedHashMap<>();
        int skipped = 0;
        for (Checked c : checked) {
            if (c.skipped()) {
                skipped++;
                continue;
            }
            Line l = c.line();
            dispatches.insertReceipt(tenantId, new DispatchRepository.NewReceipt(store.unitId(), dispatchId, l.id(),
                    l.dispatchedQty(), c.accepted(), c.rejected(), c.rejected() > 0 ? c.reasonKey() : null, c.note(), me,
                    l.scheduleX() ? signer : null));
            if (!viaTransfer) {
                int[] sum = totals.computeIfAbsent(l.indentLineId(), k -> new int[3]);
                sum[0] += c.accepted();
                sum[1] += c.returnedQty();
                sum[2] += c.shortQty();
            }
            if (c.returnedQty() > 0) {
                UUID itemId = UUID.randomUUID();
                returns.insert(tenantId, new ReturnItemRepository.NewItem(itemId, store.unitId(), row.reference(),
                        viaTransfer ? "TRANSFER_REJECTION" : "INDENT_REJECTION", l.id(), storeId, row.fromStoreId(), l.productId(), l.batchId(), c.returnedQty(),
                        c.reasonKey(), ReturnRouting.defaultPutBackInto(c.reasonKey(), c.expired()), l.scheduleX(), me));
                events.insert(tenantId, store.unitId(), ReturnShortageService.RETURN_DOC, itemId, "RAISED", storeId, me, null, null,
                        null, null, c.reasonKey());
                events.insert(tenantId, store.unitId(), docType, docId, "LINE_REJECTED", storeId, me, null,
                        l.indentLineId(), 0, c.returnedQty(), c.reasonKey());
            }
            if (c.shortQty() > 0) {
                UUID shortageId = UUID.randomUUID();
                shortages.insert(tenantId, new ShortageRepository.NewShortage(shortageId, store.unitId(), l.id(), storeId,
                        row.fromStoreId(), l.productId(), l.batchId(), c.shortQty(), me));
                events.insert(tenantId, store.unitId(), ReturnShortageService.SHORTAGE_DOC, shortageId, "RAISED", storeId, me, null,
                        null, null, null, null);
                events.insert(tenantId, store.unitId(), docType, docId, "LINE_SHORT", storeId, me, null,
                        l.indentLineId(), 0, c.shortQty(), c.reasonKey());
            }
        }
        totals.forEach((indentLineId, sum) -> indents.applyReceipt(tenantId, indentLineId, sum[0], sum[1], sum[2], me));

        // the dispatch: RECEIVED, or PARTLY_RECEIVED while Schedule X lines wait for a pharmacist
        String status = skipped > 0 ? "PARTLY_RECEIVED" : "RECEIVED";
        int returnedNow = 0;
        int shortNow = 0;
        for (DispatchRepository.Receipt r : dispatches.receipts(tenantId, dispatchId)) {
            if (ReturnRouting.isShort(r.rejectReasonKey())) {
                shortNow += r.rejectedQty();
            } else {
                returnedNow += r.rejectedQty();
            }
        }
        String rejectedNote = returnedNow == 0 ? null : "The " + returnedNow + " rejected units do not enter " + store.name()
                + " stock. They wait for Central Store to accept them back.";
        String shortNote = shortNow == 0 ? null : "The " + shortNow + " short units stay in transit while Central Store investigates.";
        if (!dispatches.markReceived(tenantId, dispatchId, row.version(), status, me, signer, rejectedNote, shortNote)) {
            throw new IllegalStateException("A dispatch changed under its lock");
        }

        // the indent: RECEIVED only when every approved quantity is dispatched and received; otherwise it keeps its status
        String targetName;
        if (viaTransfer) {
            // a transfer is RECEIVED when its one dispatch is; while Schedule X lines wait it stays IN_TRANSIT
            targetName = skipped == 0 ? "RECEIVED" : transfer.status();
            if (skipped == 0 && !transfers.setStatus(tenantId, transfer.id(), transfer.version(), "RECEIVED", me)) {
                throw new IllegalStateException("A transfer changed under its lock");
            }
        } else {
            List<IndentRepository.Line> lines = indents.lines(tenantId, indent.id());
            int waiting = dispatches.scheduleXWaiting(tenantId, indent.id());
            IndentStatus current = IndentStatus.valueOf(indent.status());
            IndentStatus target = IndentProgress.afterReceipt(current, IndentDispatchService.figures(lines), waiting);
            indents.setStatusLocked(tenantId, indent.id(), indent.status(), target.name(), me);
            targetName = target.name();
        }
        events.insert(tenantId, store.unitId(), docType, docId, "RECEIVED", storeId, me, null, null, null, null, null);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dispatchStatus", status);
        after.put(viaTransfer ? "transferStatus" : "indentStatus", targetName);
        after.put("lineCount", checked.size() - skipped);
        after.put("skippedLineCount", skipped);
        after.put("returnedUnits", checked.stream().mapToInt(Checked::returnedQty).sum());
        after.put("shortUnits", checked.stream().mapToInt(Checked::shortQty).sum());
        auditLog.record("PHARMACY_DISPATCH_RECEIVED", ENTITY_TYPE, dispatchId, Map.of("status", row.status()), after);
        log.info("Dispatch received dispatchId={} status={} skipped={}", dispatchId, status, skipped);
        return assembler.view(caller, dispatches.findById(tenantId, dispatchId).orElseThrow(), storeId);
    }

    // ---- checks ----

    /**
     * All the line errors together (rule 21). Every line not yet received must be sent (a Schedule X line may be skipped); a line
     * missing, sent twice or already received is one 400 on lines; accepted is a whole number from 0 to the dispatched quantity; rejected is the rest
     * (a sent value that differs is 400); a rejected quantity needs a reason of the receipt-reject list; an expired batch cannot
     * be accepted.
     */
    List<Checked> checkLines(UUID tenantId, Row dispatch, List<Line> allLines, List<Line> unreceived,
                             PharmacyInputs.DispatchReceipt in, List<FieldMessage> errors) {
        List<PharmacyInputs.ReceiptLineInput> given = in.lines() == null ? List.of() : in.lines();
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", "Add 1 to " + MAX_LINES + " lines"));
            return List.of();
        }
        Map<UUID, Line> byId = new HashMap<>();
        allLines.forEach(l -> byId.put(l.id(), l));
        Set<UUID> open = new LinkedHashSet<>();
        unreceived.forEach(l -> open.add(l.id()));
        LocalDate today = time.today();
        Map<UUID, LocalDate> expiry = new HashMap<>();
        List<Checked> checked = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        boolean badSet = false;
        boolean anyReceived = false;
        for (int i = 0; i < given.size(); i++) {
            PharmacyInputs.ReceiptLineInput d = given.get(i);
            String at = "lines[" + i + "]";
            if (d == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            Line line = d.dispatchLineId() == null ? null : byId.get(d.dispatchLineId());
            if (line == null) {
                errors.add(new FieldMessage(at + ".dispatchLineId", "Choose a line of this dispatch"));
                continue;
            }
            if (!seen.add(d.dispatchLineId()) || !open.contains(line.id())) {
                badSet = true;
                continue;
            }
            String note = d.note() == null || d.note().isBlank() ? null : d.note().trim();
            if (note != null && note.length() > 300) {
                errors.add(new FieldMessage(at + ".note", "Keep the note to 300 characters"));
                continue;
            }
            if (Boolean.TRUE.equals(d.skipped())) {
                if (!line.scheduleX()) {
                    errors.add(new FieldMessage(at + ".skipped", "Only a Schedule X line can be left for a pharmacist"));
                    continue;
                }
                checked.add(new Checked(line, true, 0, 0, null, null, false));
                continue;
            }
            int dispatched = line.dispatchedQty();
            Integer accepted = PharmacyParams.wholeNumber(d.accepted(), 0, dispatched);
            Integer rejected = PharmacyParams.wholeNumber(d.rejected(), 0, dispatched);
            boolean ok = true;
            if (accepted == null) {
                errors.add(new FieldMessage(at + ".accepted", "Enter a whole number from 0 to " + dispatched));
                ok = false;
            }
            if (rejected == null) {
                errors.add(new FieldMessage(at + ".rejected", "Enter a whole number from 0 to " + dispatched));
                ok = false;
            }
            if (!ok) {
                continue;
            }
            if (accepted + rejected != dispatched) {
                errors.add(new FieldMessage(at + ".rejected", SUM_MESSAGE));
                continue;
            }
            String reasonKey = null;
            if (rejected > 0) {
                reasonKey = d.rejectReasonKey();
                if (!reasons.isActive(tenantId, ReasonList.RECEIPT_REJECT, reasonKey)) {
                    errors.add(new FieldMessage(at + ".rejectReasonKey", REASON_MESSAGE));
                    continue;
                }
            }
            LocalDate expiryDate = expiry.computeIfAbsent(line.batchId(), id -> stock.findBatch(tenantId, id)
                    .map(StockRepository.BatchRow::expiryDate).orElseThrow(DispatchReceiptService::notFound));
            boolean expired = expiryDate.isBefore(today);
            if (accepted > 0 && expired) {
                errors.add(new FieldMessage(at + ".accepted", EXPIRED_MESSAGE));
                continue;
            }
            if (expired && rejected > 0 && !NEAR_EXPIRY_KEY.equals(reasonKey)) {
                errors.add(new FieldMessage(at + ".rejectReasonKey", EXPIRED_REASON_MESSAGE));
                continue;
            }
            anyReceived = true;
            checked.add(new Checked(line, false, accepted, rejected, reasonKey, note, expired));
        }
        if (badSet || !seen.containsAll(open)) {
            errors.add(new FieldMessage("lines", LIST_EVERY));
        } else if (errors.isEmpty() && !anyReceived) {
            errors.add(new FieldMessage("lines", NOTHING));
        }
        return checked;
    }

    /** I24: a line is Schedule X when its dispatch-line copy says so or the product is Schedule X now (the product's own first). */
    private List<Line> marked(UUID tenantId, List<Line> lines) {
        Map<UUID, Boolean> byProduct = new HashMap<>();
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            boolean x = l.scheduleX() || byProduct.computeIfAbsent(l.productId(), p -> products.find(tenantId, p)
                    .map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false));
            out.add(x == l.scheduleX() ? l : new Line(l.id(), l.dispatchId(), l.indentLineId(), l.productId(), l.batchId(),
                    l.dispatchedQty(), l.replacedByCs(), l.changeReasonKey(), l.changeNote(), l.backOrderedQty(), true));
        }
        return out;
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("dispatchVersion", UnitService.RELOAD));
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
