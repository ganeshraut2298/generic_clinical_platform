package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.Bal;
import com.sahaj.pharmacy.application.TransferRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.Fefo;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-15, dispatch of an approved transfer (docs/api/pharmacy.yaml 0.3.6 pharmacyDispatchTransfer). One transaction: the FEFO
 * batches are picked now (they may differ from the preview), AVAILABLE units leave the sending store and arrive IN_TRANSIT at the
 * receiving store (ledger TRANSFER_DISPATCH), a dispatch record of source TRANSFER is written, and the transfer becomes IN_TRANSIT.
 * The receiving store confirms with pharmacyReceiveDispatch ({@link DispatchReceiptService}); units it rejects return to the sending
 * store, which accepts them ({@link ReturnShortageService}).
 *
 * <p>Order: the gate, the path ids, the body, the store guard (dispatchTransfer at the sending store), the document checks, the
 * client key, the validation, then the locks: the client key, the sending store row, the transfer, the stock rows inside
 * {@link StockPosting}. A Schedule X line needs a pharmacist and the verified second signer (rule 19). Logs and audit rows hold
 * ids and counts only.
 */
@Service
public class TransferDispatchService {

    static final String OP_DISPATCH = "pharmacyDispatchTransfer";
    private static final Logger log = LoggerFactory.getLogger(TransferDispatchService.class);
    private static final String ENTITY_TYPE = "PHARMACY_TRANSFER";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final TransferRepository transfers;
    private final DispatchRepository dispatches;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final TransferService transferService;
    private final AuditLog auditLog;
    private final BlindCount blind;

    public TransferDispatchService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                                   StockRepository stock, StockPosting posting, ScheduleXSignature signature,
                                   TransferRepository transfers, DispatchRepository dispatches, ClientRequestRepository clients,
                                   DocumentEventRepository events, TransferService transferService, AuditLog auditLog,
                                   BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.transfers = transfers;
        this.dispatches = dispatches;
        this.clients = clients;
        this.events = events;
        this.transferService = transferService;
        this.auditLog = auditLog;
        this.blind = blind;
    }

    /**
     * The second signer to verify before the dispatch transaction: only for an APPROVED transfer with a Schedule X line, only for a
     * pharmacist, and only when the key has not been used already. A caller who is not a pharmacist answers 403
     * schedule-x-pharmacist here, before any PIN is checked.
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId, String rawTransferId,
                                                     PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID transferId = PharmacyParams.pathId(rawTransferId);
        PharmacyInputs.PostWithSigner in = body.read();
        guard.require(caller, storeId, StoreCapability.DISPATCH_TRANSFER);
        Row peek = transfers.findById(tenantId, transferId).filter(t -> t.fromStoreId().equals(storeId))
                .orElseThrow(TransferService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!"APPROVED".equals(peek.status()) || !hasScheduleX(tenantId, peek)) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public TransferViews.Transfer dispatch(UUID tenantId, UUID userId, String rawStoreId, String rawTransferId,
                                           PharmacyInputs.Body<PharmacyInputs.PostWithSigner> body,
                                           UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID transferId = PharmacyParams.pathId(rawTransferId);
        PharmacyInputs.PostWithSigner in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.DISPATCH_TRANSFER);
        UUID me = caller.actorId();
        Row peek = transfers.findById(tenantId, transferId).filter(t -> t.fromStoreId().equals(storeId))
                .orElseThrow(TransferService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_DISPATCH.equals(own.operation()) || !transferId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return transferService.view(caller, peek, storeId);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Integer whole = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        long version = whole.longValue();
        if (peek.version() != version) {
            throw PharmacyProblemException.changed(transferService.view(caller, peek, storeId));
        }
        requireApproved(peek);
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_DISPATCH, transferId, true) != null) {
            return dispatch(tenantId, userId, rawStoreId, rawTransferId, body, verifiedSignerMembershipId); // L15
        }

        // lock order: the sending store row, the transfer, then the stock rows inside StockPosting
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(TransferService::notFound);
        Row row = transfers.findForUpdate(tenantId, transferId).orElseThrow(TransferService::notFound);
        if (row.version() != version) {
            throw PharmacyProblemException.changed(transferService.view(caller, row, storeId));
        }
        requireApproved(row);
        // m2: stock is not sent to a store that was switched off after the transfer was approved
        if (stores.find(tenantId, row.toStoreId()).filter(Store::active).isEmpty()) {
            throw PharmacyProblemException.docState("The receiving store is switched off. Cancel this transfer.");
        }
        List<TransferRepository.Line> lines = transfers.lines(tenantId, transferId);
        Map<UUID, Boolean> scheduleXOf = new LinkedHashMap<>();
        for (TransferRepository.Line l : lines) {
            scheduleXOf.put(l.productId(), isScheduleX(tenantId, l.productId()));
        }
        UUID signer = null;
        if (scheduleXOf.containsValue(Boolean.TRUE)) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }

        // the picks, read now; the posting locks and checks again, so a row that changed answers 409 insufficient-stock
        Map<UUID, List<Fefo.Pick>> picks = new LinkedHashMap<>();
        List<FailingLine> failing = new ArrayList<>();
        int rowNo = 0;
        for (TransferRepository.Line l : lines) {
            rowNo++;
            List<Bal> rows = stock.availableOfProduct(tenantId, storeId, l.productId());
            Fefo.Result result = posting.pick(rows, l.quantity());
            if (result.shortBy() > 0) {
                failing.add(new FailingLine(l.id(), rowNo, l.productId(), result.available()));
                continue;
            }
            picks.put(l.id(), result.picks());
        }
        if (!failing.isEmpty()) {
            throw blind.scrub(caller, storeId, PharmacyProblemException.insufficientStock(failing.get(0).available(), failing));
        }
        if (picks.values().stream().mapToInt(List::size).sum() > IndentDispatchService.MAX_DISPATCH_LINES) {
            throw new InvalidFieldsException(List.of(new FieldMessage("lines", IndentDispatchService.SPLIT_MESSAGE)));
        }

        UUID dispatchId = UUID.randomUUID();
        String reference = row.docNo();
        List<StockPosting.Line> moves = new ArrayList<>();
        List<DispatchRepository.NewTransferLine> newLines = new ArrayList<>();
        int number = 0;
        int units = 0;
        for (TransferRepository.Line l : lines) {
            boolean x = scheduleXOf.get(l.productId());
            for (Fefo.Pick pick : picks.get(l.id())) {
                number++;
                UUID lineId = UUID.randomUUID();
                UUID movement = UUID.randomUUID();
                StockPosting.Doc doc = new StockPosting.Doc("DISPATCH", dispatchId, reference, lineId);
                UUID lineSigner = x ? signer : null;
                moves.add(new StockPosting.Line(storeId, l.productId(), pick.batchId(), StockState.AVAILABLE, -pick.quantity(),
                        "TRANSFER_DISPATCH", null, movement, null, doc, number, lineSigner));
                moves.add(new StockPosting.Line(row.toStoreId(), l.productId(), pick.batchId(), StockState.IN_TRANSIT,
                        pick.quantity(), "TRANSFER_DISPATCH", null, movement, null, doc, number, lineSigner));
                newLines.add(new DispatchRepository.NewTransferLine(lineId, store.unitId(), dispatchId, l.id(), l.productId(),
                        pick.batchId(), pick.quantity(), x, me));
                units += pick.quantity();
            }
        }
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }
        dispatches.insertTransfer(tenantId, new DispatchRepository.NewTransferDispatch(dispatchId, store.unitId(), transferId,
                reference, storeId, row.toStoreId(), me, signer));
        newLines.forEach(l -> dispatches.insertTransferLine(tenantId, l));
        if (!transfers.setStatus(tenantId, transferId, row.version(), "IN_TRANSIT", me)) {
            throw new IllegalStateException("A transfer changed under its lock");
        }
        events.insert(tenantId, store.unitId(), TransferService.DOC_TYPE, transferId, "DISPATCHED", storeId, me, null, null, null,
                null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("dispatchId", dispatchId.toString());
        after.put("status", "IN_TRANSIT");
        after.put("lineCount", newLines.size());
        after.put("units", units);
        auditLog.record("PHARMACY_TRANSFER_DISPATCHED", ENTITY_TYPE, transferId, Map.of("status", "APPROVED"), after);
        log.info("Transfer dispatched transferId={} dispatchId={} lines={}", transferId, dispatchId, newLines.size());
        return transferService.view(caller, transfers.findById(tenantId, transferId).orElseThrow(), storeId);
    }

    private static void requireApproved(Row row) {
        if (!"APPROVED".equals(row.status())) {
            throw PharmacyProblemException.docState("Only an approved transfer can be dispatched.");
        }
    }

    private boolean hasScheduleX(UUID tenantId, Row row) {
        return transfers.lines(tenantId, row.id()).stream().anyMatch(l -> isScheduleX(tenantId, l.productId()));
    }

    private boolean isScheduleX(UUID tenantId, UUID productId) {
        return products.find(tenantId, productId).map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false);
    }
}
