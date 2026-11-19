package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.OpeningStockRepository.ImportRow;
import com.sahaj.pharmacy.application.OpeningStockRepository.PostingLine;
import com.sahaj.pharmacy.application.PharmacyProblemException.FailingLine;
import com.sahaj.pharmacy.application.StockRepository.BatchRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.BatchLockOrder;
import com.sahaj.pharmacy.domain.ImportStatus;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.StockState;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-1: posting an approved opening-stock import to batches and the stock ledger, and the preview before it
 * (docs/api/pharmacy.yaml 0.3.0). One transaction, all or nothing, up to 5,000 lines (statement timeout 60 seconds). Lock order
 * (ADR-029 P5, design note section 6): the client key, then the store, the import (the document), the batches in product,
 * batch key and expiry order, then the balance rows inside {@link StockPosting}; the single update at the end
 * ({@code where status = 'APPROVED' and posted_at is null}) answers 409 {@code already-posted} on 0 rows. Batches match by
 * product, batch number (case and edge spaces ignored) and expiry; a clash of MRP stops the whole posting (BR-10). Lines whose
 * expiry has passed go to QUARANTINE with the reason damage.expired. No second approval: the import was approved by another
 * owner. Logs carry ids and counts only.
 */
@Service
public class OpeningStockPostService {

    static final String OPERATION = "pharmacyPostOpeningStock";
    private static final Logger log = LoggerFactory.getLogger(OpeningStockPostService.class);
    private static final int POST_TIMEOUT_SECONDS = 60;
    private static final int MAX_LISTED = 100;
    private static final int MAX_EXPIRED_LISTED = 5000;
    private static final String ENTITY_TYPE = "PHARMACY_OPENING_STOCK_IMPORT";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final OpeningStockRepository imports;
    private final OpeningStockAssembler assembler;
    private final StockRepository stock;
    private final StockPosting posting;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;

    public OpeningStockPostService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores,
                                   OpeningStockRepository imports, OpeningStockAssembler assembler, StockRepository stock,
                                   StockPosting posting, ClientRequestRepository clients, DocumentEventRepository events,
                                   TeamMemberPort team, AuditLog auditLog, PharmacyTime time) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.imports = imports;
        this.assembler = assembler;
        this.stock = stock;
        this.posting = posting;
        this.clients = clients;
        this.events = events;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
    }

    /** What "Post to stock" will post. Writes nothing. */
    @Transactional(readOnly = true)
    public StockViews.PostingPreview preview(UUID tenantId, UUID userId, String rawStoreId, String rawImportId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        Store store = guard.require(caller, storeId, StoreCapability.POST_OPENING_STOCK);
        ImportRow imp = imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockPostService::notFound);

        List<StockViews.ExpiredLine> expired = new ArrayList<>();
        List<StockViews.MrpMismatchLine> mismatches = new ArrayList<>();
        if (imp.status() == ImportStatus.APPROVED && !imp.posted()) {
            LocalDate today = time.today();
            for (PostingLine line : imports.postingLines(tenantId, importId)) {
                if (line.expiryDate().isBefore(today) && expired.size() < MAX_EXPIRED_LISTED) {
                    expired.add(new StockViews.ExpiredLine(line.rowNo(), line.productName(), line.batchNo(),
                            line.expiryDate(), line.quantity()));
                }
                BatchRow batch = stock.findBatchByIdentity(tenantId, line.productId(), line.batchNo(), line.expiryDate())
                        .orElse(null);
                if (batch != null && batch.mrp().compareTo(line.mrp()) != 0 && mismatches.size() < MAX_LISTED) {
                    mismatches.add(new StockViews.MrpMismatchLine(line.rowNo(), line.batchNo(),
                            ProductAssembler.rate(line.mrp()), ProductAssembler.rate(batch.mrp())));
                }
            }
        }
        String lock = null;
        if (imp.status() != ImportStatus.APPROVED) {
            lock = "NOT_APPROVED";
        } else if (imp.posted()) {
            lock = "ALREADY_POSTED";
        } else if (!mismatches.isEmpty()) {
            lock = "MRP_MISMATCH";
        }
        Map<UUID, TeamMemberPort.TeamMember> members = people(tenantId, imp);
        return new StockViews.PostingPreview(importId, storeRef(store), imp.lineCount(), imp.totalQuantity(),
                imp.totalValuePaise(), person(imp.uploadedBy(), members),
                imp.decidedBy() == null ? null : person(imp.decidedBy(), members), expired, mismatches, lock == null, lock);
    }

    /** Posts the import once. A replay of the same key answers the import as it is now, writing nothing. */
    @Transactional
    public PharmacyViews.OpeningStockImport post(UUID tenantId, UUID userId, String rawStoreId, String rawImportId,
                                                 PharmacyInputs.Body<PharmacyInputs.ClientKeyInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID importId = PharmacyParams.pathId(rawImportId);
        PharmacyInputs.ClientKeyInput in = body.read();
        Store store = guard.require(caller, storeId, StoreCapability.POST_OPENING_STOCK);
        if (in.clientKey() == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("clientKey", UnitService.RELOAD)));
        }
        stock.raiseStatementTimeout(POST_TIMEOUT_SECONDS);

        // lock order step 0: the client key
        if (replay(tenantId, store.unitId(), caller, in.clientKey(), importId)) {
            return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockPostService::notFound));
        }
        // step 1: the store (ACTIVE), then the import
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(OpeningStockPostService::notFound);
        ImportRow imp = imports.findForUpdate(tenantId, storeId, importId).orElseThrow(OpeningStockPostService::notFound);
        if (imp.status() != ImportStatus.APPROVED) {
            throw PharmacyProblemException.notApproved();
        }
        if (imp.posted()) {
            throw PharmacyProblemException.alreadyPosted(imp.postedAt());
        }

        LocalDate today = time.today();
        List<PostingLine> lines = new ArrayList<>(imports.postingLines(tenantId, importId));
        // step 6: batches in product, batch key and expiry order
        List<PostingLine> sorted = new ArrayList<>(lines);
        sorted.sort(BatchLockOrder.by(l -> BatchLockOrder.key(l.productId(), l.batchNo(), l.expiryDate())));
        Map<Integer, UUID> batchOfRow = new HashMap<>();
        List<FailingLine> mismatches = new ArrayList<>();
        for (PostingLine line : sorted) {
            stock.ensureBatch(tenantId, store.unitId(), line.productId(), line.batchNo(), line.expiryDate(), line.mrp(),
                    line.purchaseRate(), "OPENING_STOCK", caller.actorId());
            BatchRow batch = stock.findBatchForUpdate(tenantId, line.productId(), line.batchNo(), line.expiryDate())
                    .orElseThrow();
            if (batch.mrp().compareTo(line.mrp()) != 0) {
                if (mismatches.size() < MAX_LISTED) {
                    mismatches.add(new FailingLine(line.lineId(), line.rowNo(), line.productId(), null));
                }
                continue;
            }
            if (line.purchaseRate() != null && (batch.purchaseRate() == null
                    || batch.purchaseRate().compareTo(line.purchaseRate()) != 0)) {
                stock.setBatchPurchaseRate(tenantId, batch.id(), line.purchaseRate(), caller.actorId());
            }
            batchOfRow.put(line.rowNo(), batch.id());
        }
        if (!mismatches.isEmpty()) {
            throw PharmacyProblemException.batchMrpMismatch(mismatches);
        }

        // step 7 and 8: balances and ledger, all or nothing
        List<StockPosting.Line> moves = new ArrayList<>();
        int quarantined = 0;
        for (PostingLine line : lines) {
            boolean expired = line.expiryDate().isBefore(today);
            if (expired) {
                quarantined++;
            }
            moves.add(new StockPosting.Line(storeId, line.productId(), batchOfRow.get(line.rowNo()),
                    expired ? StockState.QUARANTINE : StockState.AVAILABLE, line.quantity(), "OPENING_STOCK",
                    expired ? StockPosting.EXPIRED_REASON : null, UUID.randomUUID(), null,
                    new StockPosting.Doc("OPENING_STOCK_IMPORT", importId, null, line.lineId()), line.rowNo()));
        }
        posting.post(new StockPosting.Actor(tenantId, store.unitId(), caller.actorId(), null, null), moves);

        if (!imports.markPosted(tenantId, importId, caller.actorId())) {
            ImportRow now = imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockPostService::notFound);
            throw PharmacyProblemException.alreadyPosted(now.postedAt());
        }
        events.insert(tenantId, store.unitId(), "OPENING_STOCK_IMPORT", importId, "POSTED", storeId, caller.actorId(),
                null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("storeId", storeId.toString());
        after.put("posted", true);
        after.put("lineCount", lines.size());
        after.put("totalQuantity", imp.totalQuantity());
        after.put("totalValuePaise", imp.totalValuePaise());
        after.put("quarantinedLineCount", quarantined);
        auditLog.record("PHARMACY_OPENING_STOCK_POSTED", ENTITY_TYPE, importId, Map.of("posted", false), after);
        log.info("Opening stock posted importId={} lines={} quarantined={}", importId, lines.size(), quarantined);
        return assembler.one(caller, imports.find(tenantId, storeId, importId).orElseThrow(OpeningStockPostService::notFound));
    }

    /**
     * Takes the key. True when the same caller used it before for this operation and this import (the caller answers the
     * current state); a key of another caller, or used for something else, is 409 client-key-reused.
     */
    private boolean replay(UUID tenantId, UUID unitId, PharmacyCaller caller, UUID clientKey, UUID importId) {
        ClientRequestRepository.Taken own = clients.own(tenantId, caller.actorId(), clientKey).orElse(null);
        if (own == null) {
            if (clients.heldByAnyone(tenantId, clientKey)) {
                throw PharmacyProblemException.clientKeyReused();
            }
            if (clients.take(tenantId, unitId, caller.actorId(), clientKey, OPERATION, importId)) {
                return false;
            }
            own = clients.own(tenantId, caller.actorId(), clientKey).orElseThrow();
        }
        if (OPERATION.equals(own.operation()) && importId.equals(own.subjectId())) {
            return true;
        }
        throw PharmacyProblemException.clientKeyReused();
    }

    private Map<UUID, TeamMemberPort.TeamMember> people(UUID tenantId, ImportRow imp) {
        List<UUID> ids = new ArrayList<>();
        ids.add(imp.uploadedBy());
        if (imp.decidedBy() != null) {
            ids.add(imp.decidedBy());
        }
        return team.findAll(tenantId, ids);
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    static StockViews.StoreRef storeRef(Store store) {
        return new StockViews.StoreRef(store.id(), store.name(), store.type().name());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
