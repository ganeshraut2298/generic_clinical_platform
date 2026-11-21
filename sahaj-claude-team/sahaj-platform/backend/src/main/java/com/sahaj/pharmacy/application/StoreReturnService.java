package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.ReturnRouting;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StoreType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * SAH-79-14, "Send to Central Store" (docs/api/pharmacy.yaml 0.3.5 pharmacyCreateStoreReturn, pharmacyGetStoreReturn; ADR-029
 * P12): a store returns AVAILABLE stock of one batch to the Central Store without an indent. In one transaction the RET number is
 * taken, the units go AVAILABLE to PENDING_RETURN at the returning store (ledger STORE_RETURN, the reference the RET number) and a
 * return item for the Central Store is written (status WAITING). Nothing enters the Central Store until it accepts (BR-29). The
 * Central Store itself cannot return stock. A Schedule X batch needs a pharmacist and the verified second signer.
 *
 * <p>Lock order: the client key, the returning store row, the RET number, then the stock rows inside {@link StockPosting}; the
 * store return is inserted before its return item. The actor of every writer is the caller's membership id. Logs and audit rows
 * hold ids and counts only.
 */
@Service
public class StoreReturnService {

    static final String OPERATION = "pharmacyCreateStoreReturn";
    static final String DOC_TYPE = "STORE_RETURN";
    private static final Logger log = LoggerFactory.getLogger(StoreReturnService.class);
    private static final String ENTITY_TYPE = "PHARMACY_STORE_RETURN";
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final StoreReturnRepository storeReturns;
    private final ReturnItemRepository returnItems;
    private final ReturnShortageService returnViews;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final ApprovalEngine engine;
    private final ScheduleXSignature signature;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final BlindCount blind;

    public StoreReturnService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                              StockRepository stock, StockPosting posting, StoreReturnRepository storeReturns,
                              ReturnItemRepository returnItems, ReturnShortageService returnViews,
                              DocumentSeriesRepository series, DocumentNumberPort numbers, ClientRequestRepository clients,
                              DocumentEventRepository events, ActiveReasonService reasons, ApprovalEngine engine,
                              ScheduleXSignature signature, TeamMemberPort team, AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.storeReturns = storeReturns;
        this.returnItems = returnItems;
        this.returnViews = returnViews;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.engine = engine;
        this.signature = signature;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
        this.blind = blind;
    }

    /**
     * The first step of a create, before any PIN is checked: the gate, the path id, the body and the store guard, in that order.
     * Returns the second signer the body names only for a pharmacist returning a Schedule X batch of this store (no other caller
     * can make a PIN attempt against a colleague).
     */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheck(UUID tenantId, UUID userId, String rawStoreId,
                                                     PharmacyInputs.Body<PharmacyInputs.StoreReturnCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StoreReturnCreate in = body.read();
        guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
        if (in.secondSigner() == null || !caller.isPharmacist() || in.batchId() == null) {
            return null;
        }
        return isScheduleX(tenantId, in.batchId()) ? in.secondSigner() : null;
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public PharmacyViews.Outcome<StoreUseViews.StoreReturn> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                   PharmacyInputs.Body<PharmacyInputs.StoreReturnCreate> body,
                                                                   UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.StoreReturnCreate in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                StoreReturnRepository.Row first = storeReturns.findById(tenantId, own.subjectId())
                        .filter(r -> r.storeId().equals(storeId)).orElseThrow(StoreReturnService::notFound);
                return new PharmacyViews.Outcome<>(view(tenantId, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }

        // validation, all together (rule 21): what needs no lock
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        if (store.type() == StoreType.CENTRAL) {
            errors.add(new FieldMessage("storeId", "The Central Store cannot send stock to itself."));
        }
        LocalDate today = time.today();
        BatchRow batch = in.batchId() == null ? null : stock.findBatch(tenantId, in.batchId()).orElse(null);
        Integer available = batch == null ? null : stock.availableQty(tenantId, storeId, batch.id()).orElse(null);
        if (batch == null || available == null || available < 1 || batch.expiryDate().isBefore(today)) {
            errors.add(new FieldMessage("batchId", "Choose a batch that is available and not expired in this store"));
        }
        Integer quantity = PharmacyParams.wholeNumber(in.quantity(), 1, MAX_QTY);
        // rule 24: a caller blind at this store on a product of its open audit is told no number (0.3.6 wording)
        boolean blindHere = batch != null && blind.hides(caller, storeId, batch.productId());
        if (quantity == null) {
            errors.add(new FieldMessage("quantity", "Enter a whole number from 1 to "
                    + (available == null || blindHere ? MAX_QTY : available)));
        } else if (available != null && quantity > available) {
            errors.add(new FieldMessage("quantity", blindHere ? BlindCount.MORE_THAN_IN_STOCK
                    : "Enter a whole number from 1 to " + available));
        }
        String note = StoreUseSupport.blank(in.note());
        if (!reasons.isActive(tenantId, ReasonList.STORE_RETURN, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Enter a note of at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        boolean scheduleX = batch != null && isScheduleX(tenantId, batch.id());
        if (scheduleX && caller.isPharmacist() && verifiedSignerMembershipId == null) {
            errors.add(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        if (scheduleX && !caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        UUID signer = null;
        if (scheduleX) {
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        Store central = StoreUseSupport.centralStoreOf(stores, tenantId, store);

        // lock order: the client key, the returning store, the RET number, then the stock rows
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body, verifiedSignerMembershipId); // L15: answer as the replay
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(StoreReturnService::notFound);
        long seq = numbers.next(tenantId, DocType.STORE_RETURN);
        String docNo = StoreUseSupport.render(series, tenantId, DocType.STORE_RETURN, seq, today);

        UUID movement = UUID.randomUUID();
        StockPosting.Doc doc = new StockPosting.Doc(DOC_TYPE, id, docNo, null);
        try {
            posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), List.of(
                    new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.AVAILABLE, -quantity, "STORE_RETURN",
                            in.reasonKey(), movement, null, doc, null, signer),
                    new StockPosting.Line(storeId, batch.productId(), batch.id(), StockState.PENDING_RETURN, quantity,
                            "STORE_RETURN", in.reasonKey(), movement, null, doc, null, signer)));
        } catch (PharmacyProblemException refused) {
            throw blind.scrub(caller, storeId, refused);
        }

        storeReturns.insert(tenantId, new StoreReturnRepository.NewRow(id, store.unitId(), seq, docNo, storeId, batch.productId(),
                batch.id(), quantity, in.reasonKey(), note, signer, me));
        UUID itemId = UUID.randomUUID();
        returnItems.insert(tenantId, new ReturnItemRepository.NewItem(itemId, store.unitId(), docNo, "RETURN_TO_CS", null, id,
                null, storeId, central.id(), batch.productId(), batch.id(), quantity, in.reasonKey(),
                ReturnRouting.defaultPutBackInto(in.reasonKey(), false), scheduleX, me));
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, quantity, in.reasonKey());
        events.insert(tenantId, store.unitId(), ReturnShortageService.RETURN_DOC, itemId, "RAISED", storeId, me, null, null, null,
                null, in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("batchId", batch.id().toString());
        after.put("units", quantity);
        after.put("returnItemId", itemId.toString());
        auditLog.record("PHARMACY_STORE_RETURN_CREATED", ENTITY_TYPE, id, null, after);
        log.info("Store return created returnId={} units={}", id, quantity);
        return new PharmacyViews.Outcome<>(view(tenantId, storeReturns.findById(tenantId, id).orElseThrow()), true);
    }

    /** The returning store (returnDamage or receive) or the Central Store that receives it (accept return); otherwise 404. */
    @Transactional(readOnly = true)
    public StoreUseViews.StoreReturn get(UUID tenantId, UUID userId, String rawStoreId, String rawReturnId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID returnId = PharmacyParams.pathId(rawReturnId);
        StoreReturnRepository.Row row = storeReturns.findById(tenantId, returnId).orElse(null);
        ReturnItemRepository.Row item = row == null ? null : itemOf(tenantId, row);
        if (item != null && item.acceptingStoreId().equals(storeId)) {
            guard.require(caller, storeId, StoreCapability.PURCHASE);
        } else {
            guard.require(caller, storeId, StoreCapability.RETURN_DAMAGE);
            if (row == null || !row.storeId().equals(storeId)) {
                throw notFound();
            }
        }
        return view(tenantId, row);
    }

    // ---- helpers ----

    private boolean isScheduleX(UUID tenantId, UUID batchId) {
        BatchRow batch = stock.findBatch(tenantId, batchId).orElse(null);
        return batch != null && products.find(tenantId, batch.productId()).map(f -> GoodsReceiptService.isScheduleX(f.row()))
                .orElse(false);
    }

    private ReturnItemRepository.Row itemOf(UUID tenantId, StoreReturnRepository.Row row) {
        return returnItems.findByStoreReturn(tenantId, row.id()).orElse(null);
    }

    private StoreUseViews.StoreReturn view(UUID tenantId, StoreReturnRepository.Row row) {
        ReturnItemRepository.Row item = itemOf(tenantId, row);
        Set<UUID> people = new LinkedHashSet<>();
        people.add(row.createdBy());
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, DOC_TYPE, row.id());
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId, people);
        Map<UUID, Store> storeById = new java.util.HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        ProductRepository.Row p = products.find(tenantId, row.productId()).map(ProductRepository.Full::row).orElseThrow();
        BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow();
        List<StockViews.HistoryEvent> historyViews = new ArrayList<>();
        for (DocumentEventRepository.Event e : history) {
            Store eventStore = storeById.get(e.storeId());
            historyViews.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(),
                    e.actorMembershipId() == null ? null : StoreUseSupport.person(e.actorMembershipId(), members), e.actingAs(),
                    eventStore == null ? null : OpeningStockPostService.storeRef(eventStore), null, e.qtyBefore(), e.qtyAfter(),
                    engine.reasonRef(tenantId, e.reasonKey(), null)));
        }
        Store store = storeById.get(row.storeId());
        return new StoreUseViews.StoreReturn(row.id(), row.docNo(), store == null ? null : OpeningStockPostService.storeRef(store),
                IndentService.productRef(p), new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()),
                row.quantity(), engine.reasonRef(tenantId, row.reasonKey(), row.note()),
                item == null ? null : returnViews.returnView(tenantId, item), historyViews);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
