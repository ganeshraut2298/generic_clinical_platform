package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.ReturnItemStatus;
import com.sahaj.pharmacy.domain.ReturnRouting;
import com.sahaj.pharmacy.domain.ShortageOutcome;
import com.sahaj.pharmacy.domain.ShortageStatus;
import com.sahaj.pharmacy.domain.StockMath;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
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
 * SAH-79-13: the accepting store (the Central Store) deals with what the requesting stores rejected and what never arrived
 * (docs/api/pharmacy.yaml 0.3.5 pharmacyListWaitingForAction, pharmacyGetReturnItem, pharmacyAcceptReturnItem,
 * pharmacyDisputeReturnItem, pharmacyReplyReturnItem, pharmacyListShortages, pharmacySettleShortage; ADR-029 P12).
 *
 * <p>A return item holds the rejected units as PENDING_RETURN at the returning store until the accepting store accepts the whole
 * item into saleable stock or quarantine (ledger RETURN_ACCEPTED, two lines; Schedule X: a pharmacist and the second signer).
 * Until then no balance of the accepting store changes (BR-25). A dispute sets the reason once and leaves the units where they
 * are; either side may add notes. A shortage keeps its units IN_TRANSIT at the receiving store; settled as found they go to this
 * store (ledger SHORTAGE_FOUND, with the signer for Schedule X), as a transit loss they are written off (ledger
 * SHORTAGE_WRITE_OFF, no signer) now or after the WRITE_OFF approval rule, and a carrier claim only records the claim.
 *
 * <p>Lock order: the client key, the accepting (settling) store row, the item or shortage, then the stock rows of both stores
 * inside {@link StockPosting}. This service is also the approval handler of the shortage write-off: the approval request was
 * locked first, so no store lock is taken there; the raiser is the actor of the ledger line and the approver is recorded as the
 * approver. The actor of every writer is the caller's membership id. Logs and audit rows hold ids and counts only.
 */
@Service
public class ReturnShortageService implements ApprovalSubjectHandler {

    static final String RETURN_DOC = "RETURN_ITEM";
    static final String SHORTAGE_DOC = "SHORTAGE";
    static final String OP_ACCEPT = "pharmacyAcceptReturnItem";
    static final String OP_REPLY = "pharmacyReplyReturnItem";
    static final String OP_SETTLE = "pharmacySettleShortage";
    private static final Logger log = LoggerFactory.getLogger(ReturnShortageService.class);
    private static final String RETURN_ENTITY = "PHARMACY_RETURN_ITEM";
    private static final String SHORTAGE_ENTITY = "PHARMACY_SHORTAGE";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ScheduleXSignature signature;
    private final ReturnItemRepository returns;
    private final ShortageRepository shortages;
    private final ApprovalEngine engine;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final DocumentViews documentViews;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public ReturnShortageService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                                 StockRepository stock, StockPosting posting, ScheduleXSignature signature,
                                 ReturnItemRepository returns, ShortageRepository shortages, ApprovalEngine engine,
                                 ClientRequestRepository clients, DocumentEventRepository events, ActiveReasonService reasons,
                                 DocumentViews documentViews, TeamMemberPort team, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.stock = stock;
        this.posting = posting;
        this.signature = signature;
        this.returns = returns;
        this.shortages = shortages;
        this.engine = engine;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.documentViews = documentViews;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- return items: reads ----

    /** ACCEPTING lists what this store accepts (capability purchase); RETURNING what it holds (any mapped person). Newest first. */
    @Transactional(readOnly = true)
    public DispatchViews.ReturnItemPage waiting(UUID tenantId, UUID userId, String rawStoreId, String rawSide, String rawStatus,
                                                String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        if (rawSide == null || !"ACCEPTING".equals(rawSide) && !"RETURNING".equals(rawSide)) {
            // the store guard first (contract rule 2): the side decides the capability, so an unknown side is read as the least
            guard.require(caller, storeId, StoreCapability.RECEIVE);
            throw new InvalidFieldsException(List.of(new FieldMessage("side", "Choose accepting or returning")));
        }
        boolean accepting = "ACCEPTING".equals(rawSide);
        if (accepting) {
            acceptor(caller, storeId);
        } else {
            guard.require(caller, storeId, StoreCapability.RECEIVE);
        }
        ReturnItemStatus status = PharmacyParams.optionalEnum("status", rawStatus, ReturnItemStatus.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        ReturnItemRepository.Slice slice = returns.page(tenantId, storeId, accepting, status == null ? null : status.name(), size,
                page * size);
        Map<UUID, List<ReturnItemRepository.Note>> notes = returns.notesOf(tenantId, slice.items().stream()
                .map(ReturnItemRepository.Row::id).toList());
        Set<UUID> people = new LinkedHashSet<>();
        slice.items().forEach(r -> collectPeople(people, r, notes.getOrDefault(r.id(), List.of())));
        Map<UUID, TeamMemberPort.TeamMember> members = people.isEmpty() ? Map.of() : team.findAll(tenantId, people);
        Map<UUID, Store> storeById = storeMap(tenantId);
        List<DispatchViews.ReturnItem> items = slice.items().stream().map(r -> returnView(tenantId, r,
                notes.getOrDefault(r.id(), List.of()), members, storeById, List.of())).toList();
        return new DispatchViews.ReturnItemPage(items, slice.total(), page, size);
    }

    /** The accepting store (purchase) or the holding store (receive) reads an item; for anyone else it does not exist. */
    @Transactional(readOnly = true)
    public DispatchViews.ReturnItem get(UUID tenantId, UUID userId, String rawStoreId, String rawItemId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID itemId = PharmacyParams.pathId(rawItemId);
        ReturnItemRepository.Row row = returns.findById(tenantId, itemId).orElse(null);
        if (row != null && row.acceptingStoreId().equals(storeId)) {
            acceptor(caller, storeId);
        } else {
            guard.require(caller, storeId, StoreCapability.RECEIVE);
            if (row == null || !row.holdingStoreId().equals(storeId)) {
                throw notFound();
            }
        }
        return returnView(tenantId, row);
    }

    // ---- return items: accept ----

    /** The second signer to verify before the acceptance transaction (Schedule X item, pharmacist, key not used yet). */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckAccept(UUID tenantId, UUID userId, String rawStoreId, String rawItemId,
                                                           PharmacyInputs.Body<PharmacyInputs.AcceptReturn> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID itemId = PharmacyParams.pathId(rawItemId);
        PharmacyInputs.AcceptReturn in = body.read();
        acceptor(caller, storeId);
        ReturnItemRepository.Row peek = returns.findById(tenantId, itemId).filter(r -> r.acceptingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!open(peek) || !peek.scheduleX()) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public DispatchViews.ReturnItem accept(UUID tenantId, UUID userId, String rawStoreId, String rawItemId,
                                           PharmacyInputs.Body<PharmacyInputs.AcceptReturn> body,
                                           UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID itemId = PharmacyParams.pathId(rawItemId);
        PharmacyInputs.AcceptReturn in = body.read();
        Store store = acceptor(caller, storeId);
        UUID me = caller.actorId();
        ReturnItemRepository.Row peek = returns.findById(tenantId, itemId).filter(r -> r.acceptingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_ACCEPT.equals(own.operation()) || !itemId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return returnView(tenantId, peek);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        boolean saleable = "SALEABLE".equals(in.putBackInto());
        if (!saleable && !"QUARANTINE".equals(in.putBackInto())) {
            errors.add(new FieldMessage("putBackInto", "Choose saleable stock or quarantine"));
        }
        throwIfAny(errors);
        if (!open(peek) || peek.version() != version) {
            throw PharmacyProblemException.changed(returnView(tenantId, peek));
        }
        StockRepository.BatchRow batch = stock.findBatch(tenantId, peek.batchId()).orElseThrow(ReturnShortageService::notFound);
        if (saleable && batch.expiryDate().isBefore(time.today())) {
            throw PharmacyProblemException.batchExpired();
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_ACCEPT, itemId, true) != null) {
            return accept(tenantId, userId, rawStoreId, rawItemId, body, verifiedSignerMembershipId); // L15
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(ReturnShortageService::notFound);
        ReturnItemRepository.Row row = returns.findForUpdate(tenantId, itemId).orElseThrow(ReturnShortageService::notFound);
        if (!open(row) || row.version() != version) {
            throw PharmacyProblemException.changed(returnView(tenantId, row));
        }
        UUID signer = null;
        if (row.scheduleX()) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSignerMembershipId == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSignerMembershipId);
            signer = verifiedSignerMembershipId;
        }
        StockState target = saleable ? StockState.AVAILABLE : StockState.QUARANTINE;
        UUID movement = UUID.randomUUID();
        StockPosting.Doc doc = new StockPosting.Doc(RETURN_DOC, itemId, row.docNo(), null);
        List<StockPosting.Line> moves = List.of(
                new StockPosting.Line(row.holdingStoreId(), row.productId(), row.batchId(), StockState.PENDING_RETURN, -row.qty(),
                        "RETURN_ACCEPTED", null, movement, null, doc, null, signer),
                new StockPosting.Line(storeId, row.productId(), row.batchId(), target, row.qty(), "RETURN_ACCEPTED", null,
                        movement, null, doc, null, signer));
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), moves);
        String resultNote = "Return accepted. " + row.qty() + " units added to " + store.name() + ": "
                + (saleable ? "saleable stock" : "quarantine") + ".";
        String status = saleable ? ReturnItemStatus.ACCEPTED_SALEABLE.name() : ReturnItemStatus.ACCEPTED_QUARANTINE.name();
        if (!returns.accept(tenantId, itemId, row.version(), status, me, signer, resultNote)) {
            throw new IllegalStateException("A return item changed under its lock");
        }
        events.insert(tenantId, store.unitId(), RETURN_DOC, itemId, "ACCEPTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("units", row.qty());
        auditLog.record("PHARMACY_RETURN_ACCEPTED", RETURN_ENTITY, itemId, Map.of("status", row.status()), after);
        log.info("Return item accepted returnItemId={} status={}", itemId, status);
        return returnView(tenantId, returns.findById(tenantId, itemId).orElseThrow());
    }

    // ---- return items: dispute and reply ----

    @Transactional
    public DispatchViews.ReturnItem dispute(UUID tenantId, UUID userId, String rawStoreId, String rawItemId,
                                            PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID itemId = PharmacyParams.pathId(rawItemId);
        PharmacyInputs.VersionReasonInput in = body.read();
        Store store = acceptor(caller, storeId);
        UUID me = caller.actorId();
        ReturnItemRepository.Row peek = returns.findById(tenantId, itemId).filter(r -> r.acceptingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (!reasons.isActive(tenantId, ReasonList.DISPUTE, in.reasonKey())) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (in.reasonKey().endsWith(".other") && (note == null || note.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        throwIfAny(errors);
        if (!ReturnItemStatus.WAITING.name().equals(peek.status()) || peek.version() != version) {
            throw PharmacyProblemException.changed(returnView(tenantId, peek));
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(ReturnShortageService::notFound);
        ReturnItemRepository.Row row = returns.findForUpdate(tenantId, itemId).orElseThrow(ReturnShortageService::notFound);
        if (!ReturnItemStatus.WAITING.name().equals(row.status()) || row.version() != version) {
            throw PharmacyProblemException.changed(returnView(tenantId, row));
        }
        if (!returns.dispute(tenantId, itemId, row.version(), in.reasonKey(), me)) {
            throw new IllegalStateException("A return item changed under its lock");
        }
        if (note != null) {
            returns.insertNote(tenantId, store.unitId(), itemId, note, me);
        }
        events.insert(tenantId, store.unitId(), RETURN_DOC, itemId, "DISPUTED", storeId, me, null, null, null, null,
                in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ReturnItemStatus.DISPUTED.name());
        after.put("reasonKey", in.reasonKey());
        auditLog.record("PHARMACY_RETURN_DISPUTED", RETURN_ENTITY, itemId, Map.of("status", row.status()), after);
        return returnView(tenantId, returns.findById(tenantId, itemId).orElseThrow());
    }

    /** The holding store adds a note to a waiting or disputed item. No stock effect. Answers the item (the caller sends 201). */
    @Transactional
    public DispatchViews.ReturnItem reply(UUID tenantId, UUID userId, String rawStoreId, String rawItemId,
                                          PharmacyInputs.Body<PharmacyInputs.NoteInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID itemId = PharmacyParams.pathId(rawItemId);
        PharmacyInputs.NoteInput in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.RECEIVE);
        UUID me = caller.actorId();
        ReturnItemRepository.Row peek = returns.findById(tenantId, itemId).filter(r -> r.holdingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_REPLY.equals(own.operation()) || !itemId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return returnView(tenantId, peek);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        String note = in.note() == null ? "" : in.note().trim();
        if (note.length() < 4 || note.length() > 300) {
            errors.add(new FieldMessage("note", "Write 4 to 300 characters"));
        }
        throwIfAny(errors);
        if (!open(peek)) {
            throw PharmacyProblemException.changed(returnView(tenantId, peek));
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_REPLY, itemId, true) != null) {
            return reply(tenantId, userId, rawStoreId, rawItemId, body); // L15
        }
        ReturnItemRepository.Row row = returns.findForUpdate(tenantId, itemId).orElseThrow(ReturnShortageService::notFound);
        if (!open(row)) {
            throw PharmacyProblemException.changed(returnView(tenantId, row));
        }
        returns.insertNote(tenantId, store.unitId(), itemId, note, me);
        events.insert(tenantId, store.unitId(), RETURN_DOC, itemId, "NOTE_ADDED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_RETURN_NOTE_ADDED", RETURN_ENTITY, itemId, null, Map.of("status", row.status()));
        return returnView(tenantId, returns.findById(tenantId, itemId).orElseThrow());
    }

    // ---- shortages ----

    /** The shortages of dispatches sent from this store (capability purchase), open ones first, oldest first. */
    @Transactional(readOnly = true)
    public DispatchViews.ShortagePage listShortages(UUID tenantId, UUID userId, String rawStoreId, String rawStatus,
                                                    String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        acceptor(caller, storeId);
        ShortageStatus status = PharmacyParams.optionalEnum("status", rawStatus, ShortageStatus.class);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        ShortageRepository.Slice slice = shortages.page(tenantId, storeId, status == null ? null : status.name(), size,
                page * size);
        List<DispatchViews.Shortage> items = slice.items().stream().map(s -> shortageView(caller, s, false)).toList();
        return new DispatchViews.ShortagePage(items, slice.total(), page, size);
    }

    /** The second signer to verify before the settlement transaction (outcome FOUND of a Schedule X item, pharmacist). */
    @Transactional(readOnly = true)
    public PharmacyInputs.SecondSignerInput precheckSettle(UUID tenantId, UUID userId, String rawStoreId, String rawShortageId,
                                                           PharmacyInputs.Body<PharmacyInputs.SettleShortage> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID shortageId = PharmacyParams.pathId(rawShortageId);
        PharmacyInputs.SettleShortage in = body.read();
        acceptor(caller, storeId);
        ShortageRepository.Row peek = shortages.findById(tenantId, shortageId).filter(s -> s.settlingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        if (in.clientKey() != null && clients.own(tenantId, caller.actorId(), in.clientKey()).isPresent()) {
            return null;
        }
        if (!ShortageOutcome.FOUND.name().equals(in.outcome()) || !settleable(peek) || !scheduleX(tenantId, peek.productId())) {
            return null;
        }
        if (!caller.isPharmacist()) {
            throw ScheduleXSignature.onlyPharmacist();
        }
        return in.secondSigner();
    }

    /** {@code verifiedSignerMembershipId} comes from {@link ScheduleXSignature#verify}, called before this transaction. */
    @Transactional
    public DispatchViews.Shortage settle(UUID tenantId, UUID userId, String rawStoreId, String rawShortageId,
                                         PharmacyInputs.Body<PharmacyInputs.SettleShortage> body,
                                         UUID verifiedSignerMembershipId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID shortageId = PharmacyParams.pathId(rawShortageId);
        PharmacyInputs.SettleShortage in = body.read();
        Store store = acceptor(caller, storeId);
        UUID me = caller.actorId();
        ShortageRepository.Row peek = shortages.findById(tenantId, shortageId).filter(s -> s.settlingStoreId().equals(storeId))
                .orElseThrow(ReturnShortageService::notFound);
        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OP_SETTLE.equals(own.operation()) || !shortageId.equals(own.subjectId())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                return shortageView(caller, peek, true);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        ShortageOutcome outcome = null;
        try {
            outcome = in.outcome() == null ? null : ShortageOutcome.valueOf(in.outcome());
        } catch (IllegalArgumentException unknown) {
            outcome = null;
        }
        if (outcome == null) {
            errors.add(new FieldMessage("outcome", "Choose found, transit loss or claim from carrier"));
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        throwIfAny(errors);
        if (!settleable(peek) || peek.version() != version) {
            throw PharmacyProblemException.changed(shortageView(caller, peek, true));
        }
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_SETTLE, shortageId, true) != null) {
            return settle(tenantId, userId, rawStoreId, rawShortageId, body, verifiedSignerMembershipId); // L15
        }
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(ReturnShortageService::notFound);
        ShortageRepository.Row row = shortages.findForUpdate(tenantId, shortageId).orElseThrow(ReturnShortageService::notFound);
        if (!settleable(row) || row.version() != version) {
            throw PharmacyProblemException.changed(shortageView(caller, row, true));
        }
        String before = row.status();
        switch (outcome) {
            case CARRIER_CLAIM:
                if (ShortageStatus.CLAIM_OPEN.name().equals(row.status())) {
                    throw PharmacyProblemException.docState("A claim from the carrier is already open for this shortage.");
                }
                shortages.move(tenantId, shortageId, row.version(), ShortageStatus.CLAIM_OPEN.name(), note, null, null, null, me);
                break;
            case FOUND:
                found(caller, store, row, note, verifiedSignerMembershipId);
                break;
            case TRANSIT_LOSS:
            default:
                transitLoss(caller, store, row, note);
                break;
        }
        events.insert(tenantId, store.unitId(), SHORTAGE_DOC, shortageId, "SETTLED_" + outcome.name(), storeId, me, null, null,
                null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        ShortageRepository.Row result = shortages.findById(tenantId, shortageId).orElseThrow();
        after.put("status", result.status());
        after.put("outcome", outcome.name());
        after.put("units", row.qty());
        auditLog.record("PHARMACY_SHORTAGE_SETTLED", SHORTAGE_ENTITY, shortageId, Map.of("status", before), after);
        log.info("Shortage settled shortageId={} outcome={} status={}", shortageId, outcome, result.status());
        return shortageView(caller, result, true);
    }

    /** FOUND: IN_TRANSIT at the receiving store to AVAILABLE here (QUARANTINE when the batch has expired). */
    private void found(PharmacyCaller caller, Store store, ShortageRepository.Row row, String note, UUID verifiedSigner) {
        UUID tenantId = caller.tenantId();
        UUID me = caller.actorId();
        UUID signer = null;
        if (scheduleX(tenantId, row.productId())) {
            if (!caller.isPharmacist()) {
                throw ScheduleXSignature.onlyPharmacist();
            }
            if (verifiedSigner == null) {
                throw new InvalidFieldsException(List.of(new FieldMessage("secondSigner", ScheduleXSignature.NEEDED)));
            }
            signature.recheck(tenantId, me, verifiedSigner);
            signer = verifiedSigner;
        }
        StockRepository.BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow(ReturnShortageService::notFound);
        boolean expired = batch.expiryDate().isBefore(time.today());
        UUID movement = UUID.randomUUID();
        StockPosting.Doc doc = new StockPosting.Doc(SHORTAGE_DOC, row.id(), row.dispatchReference(), null);
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), me, null, null), List.of(
                new StockPosting.Line(row.receivingStoreId(), row.productId(), row.batchId(), StockState.IN_TRANSIT, -row.qty(),
                        "SHORTAGE_FOUND", null, movement, null, doc, null, signer),
                new StockPosting.Line(row.settlingStoreId(), row.productId(), row.batchId(),
                        expired ? StockState.QUARANTINE : StockState.AVAILABLE, row.qty(), "SHORTAGE_FOUND", null, movement, null,
                        doc, null, signer)));
        String resultNote = "Shortage settled. " + row.qty() + " units found and added to " + store.name() + ": "
                + (expired ? "quarantine" : "saleable stock") + ".";
        shortages.move(tenantId, row.id(), row.version(), ShortageStatus.FOUND.name(), resultNote, signer, me, null, me);
    }

    /** TRANSIT_LOSS: the WRITE_OFF rule decides; level 0 writes off now, otherwise the approval writes it off. */
    private void transitLoss(PharmacyCaller caller, Store store, ShortageRepository.Row row, String note) {
        UUID tenantId = caller.tenantId();
        UUID me = caller.actorId();
        StockRepository.BatchRow batch = stock.findBatch(tenantId, row.batchId()).orElseThrow(ReturnShortageService::notFound);
        long amount = batch.purchaseRate() == null ? 0 : StockMath.valuePaise(row.qty(), batch.purchaseRate());
        boolean x = scheduleX(tenantId, row.productId());
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.WRITE_OFF, SHORTAGE_DOC, row.id(),
                row.dispatchReference(), store.id(), amount, x, amount, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        if (plan == null) {
            if (x && !caller.isPharmacist()) { // I23: level 0 writes off at once, so only a pharmacist may write off Schedule X units
                throw ScheduleXSignature.onlyPharmacist();
            }
            writeOff(tenantId, store.unitId(), row, me, null);
            return;
        }
        UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
        shortages.move(tenantId, row.id(), row.version(), ShortageStatus.WAITING_WRITE_OFF.name(), note, null, null, requestId, me);
    }

    /** IN_TRANSIT out of the receiving store (ledger SHORTAGE_WRITE_OFF, no signer); the shortage is WRITTEN_OFF. */
    private void writeOff(UUID tenantId, UUID unitId, ShortageRepository.Row row, UUID actor, UUID approver) {
        StockPosting.Doc doc = new StockPosting.Doc(SHORTAGE_DOC, row.id(), row.dispatchReference(), null);
        posting.post(new StockPosting.Actor(tenantId, unitId, actor, null, approver), List.of(
                new StockPosting.Line(row.receivingStoreId(), row.productId(), row.batchId(), StockState.IN_TRANSIT, -row.qty(),
                        "SHORTAGE_WRITE_OFF", null, UUID.randomUUID(), null, doc, null, null)));
        String resultNote = "Shortage settled. " + row.qty() + " units written off as transit loss. No stock added back.";
        shortages.move(tenantId, row.id(), row.version(), ShortageStatus.WRITTEN_OFF.name(), resultNote, null, actor, null,
                approver == null ? actor : approver);
    }

    // ---- approval handler (79-2 engine, type SHORTAGE: the transit-loss write-off) ----

    @Override
    public String subjectType() {
        return SHORTAGE_DOC;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        ShortageRepository.Row row = shortages.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "WRITE_OFF", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        return new Summary("Transit loss · " + row.dispatchReference() + " · " + row.qty() + " units", "WRITE_OFF",
                row.settlingStoreId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        UUID ruleStore = request.ruleStoreId();
        boolean transferSide = ruleStore != null && stores.find(caller.tenantId(), ruleStore).map(s -> !s.type().isCentral()).orElse(false);
        return StoreAccessGuard.holds(caller, ruleStore, transferSide ? StoreCapability.DISPATCH_TRANSFER : StoreCapability.PURCHASE);
    }

    /**
     * The capability that accepts returns and settles shortages (0.3.6, acceptReturn): purchase at the Central Store (indent
     * returns and shortages), dispatchTransfer at a store that sent a transfer. Unknown or switched-off store: 404 as the guard.
     */
    private Store acceptor(PharmacyCaller caller, UUID storeId) {
        Store known = stores.find(caller.tenantId(), storeId).filter(Store::active).orElse(null);
        StoreCapability capability = known != null && !known.type().isCentral() ? StoreCapability.DISPATCH_TRANSFER : StoreCapability.PURCHASE;
        return guard.require(caller, storeId, capability);
    }

    /**
     * The final approval writes the units off: the raiser is the actor of the ledger line and the approver is recorded as the
     * approver; when the units are gone the refusal (409 insufficient-stock) rolls the whole decision back. No store lock here:
     * the request was locked first, so a store lock would be out of order.
     */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        ShortageRepository.Row row = shortages.findForUpdate(tenantId, decision.request().subjectId())
                .orElseThrow(ReturnShortageService::notFound);
        if (!ShortageStatus.WAITING_WRITE_OFF.name().equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        writeOff(tenantId, row.unitId(), row, decision.request().raisedBy(), approver);
        events.insert(tenantId, row.unitId(), SHORTAGE_DOC, row.id(), "WRITTEN_OFF", row.settlingStoreId(), approver,
                decision.actingAs(), null, null, null, null);
        auditLog.record("PHARMACY_SHORTAGE_SETTLED", SHORTAGE_ENTITY, row.id(), Map.of("status", "WAITING_WRITE_OFF"),
                Map.of("status", "WRITTEN_OFF", "outcome", ShortageOutcome.TRANSIT_LOSS.name(), "units", row.qty()));
    }

    /** A rejection puts the shortage back to OPEN (the write-off was refused); the units stay IN_TRANSIT. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        ShortageRepository.Row row = shortages.findForUpdate(tenantId, decision.request().subjectId())
                .orElseThrow(ReturnShortageService::notFound);
        if (ShortageStatus.WAITING_WRITE_OFF.name().equals(row.status())) {
            UUID approver = decision.approver().actorId();
            shortages.move(tenantId, row.id(), row.version(), ShortageStatus.OPEN.name(), null, null, null, null, approver);
            events.insert(tenantId, row.unitId(), SHORTAGE_DOC, row.id(), "WRITE_OFF_REJECTED", row.settlingStoreId(), approver,
                    decision.actingAs(), null, null, null, decision.input() == null ? null : decision.input().reasonKey());
            auditLog.record("PHARMACY_SHORTAGE_SETTLED", SHORTAGE_ENTITY, row.id(), Map.of("status", "WAITING_WRITE_OFF"),
                    Map.of("status", "OPEN", "outcome", ShortageOutcome.TRANSIT_LOSS.name()));
        }
    }

    // ---- views ----

    DispatchViews.ReturnItem returnView(UUID tenantId, ReturnItemRepository.Row row) {
        List<ReturnItemRepository.Note> notes = returns.notes(tenantId, row.id());
        Set<UUID> people = new LinkedHashSet<>();
        collectPeople(people, row, notes);
        DocumentViews.Loaded loaded = documentViews.load(tenantId, RETURN_DOC, row.id(), people);
        return returnView(tenantId, row, notes, loaded.members(), storeMap(tenantId), documentViews.history(tenantId, loaded));
    }

    private DispatchViews.ReturnItem returnView(UUID tenantId, ReturnItemRepository.Row r,
                                                List<ReturnItemRepository.Note> notes,
                                                Map<UUID, TeamMemberPort.TeamMember> members, Map<UUID, Store> storeById,
                                                List<StockViews.HistoryEvent> history) {
        ProductRepository.Row product = products.find(tenantId, r.productId()).map(ProductRepository.Full::row).orElseThrow();
        StockRepository.BatchRow batch = stock.findBatch(tenantId, r.batchId()).orElseThrow();
        LocalDate today = time.today();
        DispatchViews.Dispute dispute = r.disputeReasonKey() == null ? null
                : new DispatchViews.Dispute(engine.reasonRef(tenantId, r.disputeReasonKey(), null),
                        person(r.disputeBy(), members), r.disputeAt());
        List<DispatchViews.ReturnNote> noteViews = notes.stream()
                .map(n -> new DispatchViews.ReturnNote(n.note(), person(n.createdBy(), members), n.createdAt())).toList();
        return new DispatchViews.ReturnItem(r.id(), r.docNo(), r.itemType(),
                OpeningStockPostService.storeRef(storeById.get(r.holdingStoreId())),
                OpeningStockPostService.storeRef(storeById.get(r.acceptingStoreId())), IndentService.productRef(product),
                new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), r.qty(),
                engine.reasonRef(tenantId, r.reasonKey(), null), person(r.raisedBy(), members), r.raisedAt(), r.status(),
                r.defaultPutBackInto(), batch.expiryDate().isBefore(today), r.scheduleX(), dispute, noteViews, r.resultNote(),
                history, r.version());
    }

    private DispatchViews.Shortage shortageView(PharmacyCaller caller, ShortageRepository.Row s, boolean withHistory) {
        UUID tenantId = caller.tenantId();
        Map<UUID, Store> storeById = storeMap(tenantId);
        ProductRepository.Row product = products.find(tenantId, s.productId()).map(ProductRepository.Full::row).orElseThrow();
        StockRepository.BatchRow batch = stock.findBatch(tenantId, s.batchId()).orElseThrow();
        Store receiving = storeById.get(s.receivingStoreId());
        List<StockViews.HistoryEvent> history = List.of();
        if (withHistory) {
            DocumentViews.Loaded loaded = documentViews.load(tenantId, SHORTAGE_DOC, s.id(), List.of(s.raisedBy()));
            history = documentViews.history(tenantId, loaded);
        }
        String banner = "Shortage, not a return. " + s.qty() + " units never reached " + (receiving == null ? "" : receiving.name())
                + ", so nothing comes back to the shelf. Settle it as found, a transit loss or a claim from the carrier.";
        StockViews.ApprovalSummary approval = s.approvalRequestId() == null ? null
                : engine.summary(caller, SHORTAGE_DOC, s.id());
        return new DispatchViews.Shortage(s.id(), s.dispatchReference(), OpeningStockPostService.storeRef(receiving),
                OpeningStockPostService.storeRef(storeById.get(s.settlingStoreId())), IndentService.productRef(product),
                new StockViews.BatchRef(batch.id(), batch.batchNo(), batch.expiryDate()), s.qty(), s.status(), approval, banner,
                s.resultNote(), history, s.version());
    }

    private Map<UUID, Store> storeMap(UUID tenantId) {
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        return storeById;
    }

    private static void collectPeople(Set<UUID> people, ReturnItemRepository.Row r, List<ReturnItemRepository.Note> notes) {
        people.add(r.raisedBy());
        if (r.disputeBy() != null) {
            people.add(r.disputeBy());
        }
        notes.forEach(n -> people.add(n.createdBy()));
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    // ---- small checks ----

    private static boolean open(ReturnItemRepository.Row row) {
        return ReturnItemStatus.WAITING.name().equals(row.status()) || ReturnItemStatus.DISPUTED.name().equals(row.status());
    }

    private static boolean settleable(ShortageRepository.Row row) {
        return ShortageStatus.OPEN.name().equals(row.status()) || ShortageStatus.CLAIM_OPEN.name().equals(row.status());
    }

    private boolean scheduleX(UUID tenantId, UUID productId) {
        return products.find(tenantId, productId).map(f -> GoodsReceiptService.isScheduleX(f.row())).orElse(false);
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
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
