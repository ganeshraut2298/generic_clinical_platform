package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PurchaseOrderRepository.Line;
import com.sahaj.pharmacy.application.PurchaseOrderRepository.NewLine;
import com.sahaj.pharmacy.application.PurchaseOrderRepository.Row;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ApprovalConditions;
import com.sahaj.pharmacy.domain.DocType;
import com.sahaj.pharmacy.domain.DocumentNumbers;
import com.sahaj.pharmacy.domain.PrintDocType;
import com.sahaj.pharmacy.domain.PurchaseOrderStatus;
import com.sahaj.pharmacy.domain.PurchaseMath;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.pharmacy.domain.Vendor;
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
 * SAH-79-6: purchase orders of the Central Store (docs/api/pharmacy.yaml 0.3.1, ADR-029 P10, design note section 13.1).
 * DRAFT has no number; submit takes the PO number, asks the PURCHASE_ORDER rule (level 0 is APPROVED at once, otherwise
 * WAITING_APPROVAL and the approval engine finishes it through {@link ApprovalSubjectHandler}); the printed order is marked
 * SENT by hand; a receipt (SAH-79-7) moves it to PARTLY_RECEIVED or RECEIVED.
 *
 * <p>Order of every operation: the gate, the path ids, the body, the store guard (capability {@code purchase}), then the
 * Central Store check (409 doc-state), the client key, the validation (all field errors together), and then the locks: the
 * client key, the approval request (when one exists), the header {@code FOR UPDATE} (checked against its version), the number.
 * Lines are written before the header leaves DRAFT, and every read of lines is {@code not removed}. The actor of every writer
 * is the caller's membership id ({@link PharmacyCaller#actorId()}, security L13). Audit rows hold ids, counts and totals only.
 */
@Service
public class PurchaseOrderService implements ApprovalSubjectHandler {

    static final String DOC_TYPE = "PURCHASE_ORDER";
    static final String OP_CREATE = "pharmacyCreatePurchaseOrder";
    static final String OP_FROM_SUGGESTIONS = "pharmacyCreatePurchaseOrdersFromSuggestions";
    static final String OP_SUBMIT = "pharmacySubmitPurchaseOrder";
    static final String OP_COPY = "pharmacyCopyPurchaseOrder";
    static final String CENTRAL_ONLY = "Purchase orders are raised at the Central Store.";
    static final String NOT_EDITABLE = "A submitted purchase order cannot be edited. Cancel it and raise a new one.";
    static final String VENDOR_MESSAGE = "Choose an active vendor that supplies the Central Store";
    static final String MAX_LINES_MESSAGE = "Add 1 to 200 items";
    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);
    private static final String ENTITY_TYPE = "PHARMACY_PURCHASE_ORDER";
    private static final int MAX_LINES = 200;
    private static final int MAX_QTY = 999_999;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final VendorRepository vendors;
    private final PurchaseOrderRepository orders;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DocumentSeriesRepository series;
    private final DocumentNumberPort numbers;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final ActiveReasonService reasons;
    private final UnitRepository unitRows;
    private final PrintTemplateRepository templates;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final BlindCount blind;

    public PurchaseOrderService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, ProductRepository products,
                                VendorRepository vendors, PurchaseOrderRepository orders, ApprovalEngine engine,
                                ApprovalRepository approvals, DocumentSeriesRepository series, DocumentNumberPort numbers,
                                ClientRequestRepository clients, DocumentEventRepository events, ActiveReasonService reasons,
                                UnitRepository unitRows, PrintTemplateRepository templates, TeamMemberPort team,
                                AuditLog auditLog, PharmacyTime time, BlindCount blind) {
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.products = products;
        this.vendors = vendors;
        this.orders = orders;
        this.engine = engine;
        this.approvals = approvals;
        this.series = series;
        this.numbers = numbers;
        this.clients = clients;
        this.events = events;
        this.reasons = reasons;
        this.unitRows = unitRows;
        this.templates = templates;
        this.team = team;
        this.auditLog = auditLog;
        this.time = time;
        this.blind = blind;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public PurchaseViews.PoPage list(UUID tenantId, UUID userId, String rawStoreId, String rawStatus, String rawOverdue,
                                     String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        centralStore(caller, storeId); // the store guard first (m-1)
        List<String> statuses = statuses(rawStatus);
        boolean overdue = PharmacyParams.bool("overdue", rawOverdue, false);
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 20, 1, 50);
        LocalDate today = time.today();
        PurchaseOrderRepository.Slice slice = orders.list(tenantId, storeId, statuses, overdue, today, size, page * size);
        List<PurchaseViews.PoRow> items = new ArrayList<>();
        for (PurchaseOrderRepository.ListRow r : slice.items()) {
            Integer overdueDays = overdueDays(r.status(), r.deliveryExpected(), r.outstanding(), today);
            boolean closedShort = "CLOSED_SHORT".equals(r.status());
            items.add(new PurchaseViews.PoRow(r.id(), r.docNo(), r.vendorName(), r.totalPaise(), r.status(), overdueDays,
                    closedShort ? r.shortLineCount() : null));
        }
        PurchaseOrderRepository.Tiles t = orders.tiles(tenantId, storeId, today);
        return new PurchaseViews.PoPage(items, slice.total(), page, size,
                new PurchaseViews.PoTiles(t.draft(), t.waitingApproval(), t.sent(), t.partlyReceived(), t.overdue()));
    }

    /** A current approver of the order's request reads it without the purchase capability. */
    @Transactional(readOnly = true)
    public PurchaseViews.PurchaseOrder get(UUID tenantId, UUID userId, String rawStoreId, String rawPoId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        Row row = orders.find(tenantId, storeId, poId).orElse(null);
        if (row != null && isCurrentApprover(caller, row)) {
            return view(caller, row);
        }
        guard.require(caller, storeId, StoreCapability.PURCHASE);
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound));
    }

    @Transactional(readOnly = true)
    public PurchaseViews.ReorderPage reorder(UUID tenantId, UUID userId, String rawStoreId, String rawPage, String rawSize) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        centralStore(caller, storeId); // the store guard first (m-1)
        int page = PharmacyParams.intValue("page", rawPage, 0, 0, 1000);
        int size = PharmacyParams.intValue("size", rawSize, 50, 1, 100);
        LocalDate today = time.today();
        List<PurchaseViews.ReorderRow> items = new ArrayList<>();
        BlindCount.Mask mask = blind.at(caller, storeId); // rule 24: the Central Store figure of an audited product
        // rule 24: being on this list says "at or below the level", so a blind caller's list leaves the audited products out
        List<PurchaseOrderRepository.ReorderRow> rows = mask.active()
                ? orders.reorder(tenantId, storeId, today, size, page * size, mask.products())
                : orders.reorder(tenantId, storeId, today, size, page * size);
        for (PurchaseOrderRepository.ReorderRow r : rows) {
            ProductRepository.Full full = products.find(tenantId, r.productId()).orElse(null);
            if (full == null) {
                continue;
            }
            ProductRepository.Row p = full.row();
            UUID preferred = full.preferredVendorId();
            PurchaseViews.VendorRef vendor = preferred == null ? null
                    : vendors.find(tenantId, preferred).map(v -> vendorRef(v, today)).orElse(null);
            String lastRate = preferred == null ? null : orders.lastPostedRate(tenantId, preferred, r.productId())
                    .map(PurchaseMath::rateText).orElse(null);
            int need = r.reorderLevel() - (r.csStock() + r.openQtyIssueUnits());
            int suggested = need <= 0 ? (r.openPoNo() != null ? 0 : 1)
                    : Math.max(1, (need + p.conversion() - 1) / p.conversion());
            Integer all = caller.isOwner() ? allStores(tenantId, r.productId()) : null;
            items.add(new PurchaseViews.ReorderRow(productRef(p), mask.qty(r.productId(), r.csStock()), all, r.openPurchaseUnits(), r.openPoNo(),
                    r.reorderLevel(), suggested, vendor, lastRate));
        }
        long total = mask.active() ? orders.reorderTotal(tenantId, storeId, today, mask.products())
                : orders.reorderTotal(tenantId, storeId, today);
        return new PurchaseViews.ReorderPage(items, total, page, size);
    }

    private Integer allStores(UUID tenantId, UUID productId) {
        return orders.allStoresStock(tenantId, productId, time.today());
    }

    // ---- create ----

    @Transactional
    public PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> create(UUID tenantId, UUID userId, String rawStoreId,
                                                                    PharmacyInputs.Body<PharmacyInputs.PurchaseOrderCreate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.PurchaseOrderCreate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row replay = replay(caller, in.clientKey(), OP_CREATE, storeId);
        if (replay != null) {
            return new PharmacyViews.Outcome<>(view(caller, replay), false);
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Checked checked = check(tenantId, storeId, in.vendorId(), in.deliveryExpected(), in.creditDays(), in.lines(), true,
                errors);
        throwIfAny(errors);
        if (checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_CREATE, id, false) != null) {
            return create(tenantId, userId, rawStoreId, body); // L15: the same key committed first, answer as its replay
        }
        write(tenantId, store, me, id, checked);
        auditCreated(id, storeId, checked, null);
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, null);
        log.info("Purchase order created purchaseOrderId={} lines={}", id, checked.lines().size());
        return new PharmacyViews.Outcome<>(view(caller, orders.find(tenantId, storeId, id).orElseThrow()), true);
    }

    @Transactional
    public PharmacyViews.Outcome<PurchaseViews.PoDrafts> createFromSuggestions(UUID tenantId, UUID userId, String rawStoreId,
            PharmacyInputs.Body<PharmacyInputs.PurchaseOrdersFromSuggestions> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        PharmacyInputs.PurchaseOrdersFromSuggestions in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row first = replay(caller, in.clientKey(), OP_FROM_SUGGESTIONS, storeId);
        if (first != null) {
            List<PurchaseViews.PurchaseOrder> all = new ArrayList<>();
            for (UUID id : orders.createdTogether(tenantId, storeId, first.createdBy(), first.createdAt())) {
                all.add(view(caller, orders.find(tenantId, storeId, id).orElseThrow()));
            }
            return new PharmacyViews.Outcome<>(new PurchaseViews.PoDrafts(all), false);
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        List<PharmacyInputs.PoSuggestionRow> rows = in.rows() == null ? List.of() : in.rows();
        if (rows.isEmpty() || rows.size() > MAX_LINES) {
            errors.add(new FieldMessage("rows", MAX_LINES_MESSAGE));
        }
        Map<UUID, List<PharmacyInputs.PoLineInput>> byVendor = new LinkedHashMap<>();
        Map<UUID, List<Integer>> rowIndexes = new HashMap<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            PharmacyInputs.PoSuggestionRow row = rows.get(i);
            if (row == null) {
                errors.add(new FieldMessage("rows[" + i + "]", UnitService.REQUIRED));
                continue;
            }
            if (row.vendorId() == null) {
                errors.add(new FieldMessage("rows[" + i + "].vendorId", "Choose a vendor"));
                continue;
            }
            if (row.productId() != null && !seen.add(row.productId())) {
                errors.add(new FieldMessage("rows[" + i + "].productId", "Already on this purchase order"));
            }
            byVendor.computeIfAbsent(row.vendorId(), k -> new ArrayList<>())
                    .add(new PharmacyInputs.PoLineInput(row.productId(), row.qty(), row.rate()));
            rowIndexes.computeIfAbsent(row.vendorId(), k -> new ArrayList<>()).add(i);
        }
        Map<UUID, Checked> checkedByVendor = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            for (Map.Entry<UUID, List<PharmacyInputs.PoLineInput>> entry : byVendor.entrySet()) {
                List<FieldMessage> vendorErrors = new ArrayList<>();
                Checked checked = check(tenantId, storeId, entry.getKey(), null, null, entry.getValue(), false, vendorErrors);
                vendorErrors.forEach(e -> errors.add(rowError(e, rowIndexes.get(entry.getKey()))));
                checkedByVendor.put(entry.getKey(), checked);
            }
        }
        throwIfAny(errors);
        LocalDate today = time.today();
        for (Checked checked : checkedByVendor.values()) {
            if (checked.vendor().blocked(today)) {
                throw PharmacyProblemException.vendorBlocked();
            }
        }
        List<PurchaseViews.PurchaseOrder> drafts = new ArrayList<>();
        boolean keyTaken = false;
        List<UUID> ids = new ArrayList<>();
        for (Checked checked : checkedByVendor.values()) {
            UUID id = UUID.randomUUID();
            if (!keyTaken) {
                if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_FROM_SUGGESTIONS, id, false) != null) {
                    return createFromSuggestions(tenantId, userId, rawStoreId, body); // L15
                }
                keyTaken = true;
            }
            write(tenantId, store, me, id, checked);
            auditCreated(id, storeId, checked, null);
            events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, null);
            ids.add(id);
        }
        List<UUID> byName = new ArrayList<>();
        Row any = orders.find(tenantId, storeId, ids.get(0)).orElseThrow();
        for (UUID id : orders.createdTogether(tenantId, storeId, me, any.createdAt())) {
            byName.add(id);
        }
        for (UUID id : byName) {
            drafts.add(view(caller, orders.find(tenantId, storeId, id).orElseThrow()));
        }
        log.info("Purchase orders created from the reorder list count={}", ids.size());
        return new PharmacyViews.Outcome<>(new PurchaseViews.PoDrafts(drafts), true);
    }

    // ---- update, discard ----

    @Transactional
    public PurchaseViews.PurchaseOrder update(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                              PharmacyInputs.Body<PharmacyInputs.PurchaseOrderUpdate> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.PurchaseOrderUpdate in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        Checked checked = check(tenantId, storeId, in.vendorId(), in.deliveryExpected(), in.creditDays(), in.lines(), true,
                errors);
        throwIfAny(errors);
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        if (!"DRAFT".equals(row.status())) {
            throw PharmacyProblemException.docState(NOT_EDITABLE);
        }
        if (checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        Map<UUID, Line> live = new LinkedHashMap<>();
        orders.lines(tenantId, poId).forEach(l -> live.put(l.productId(), l));
        Set<UUID> kept = new LinkedHashSet<>();
        for (NewLine line : checked.lines()) {
            Line existing = live.get(line.productId());
            if (existing != null) {
                orders.updateLine(tenantId, existing.id(), line, me);
                kept.add(line.productId());
            }
        }
        live.forEach((productId, line) -> {
            if (!kept.contains(productId)) {
                orders.removeLine(tenantId, line.id(), me);
            }
        });
        for (NewLine line : checked.lines()) {
            if (!live.containsKey(line.productId())) {
                orders.insertLine(tenantId, store.unitId(), poId, line, me);
            }
        }
        if (!orders.updateDraft(tenantId, poId, row.version(), checked.vendor().id(), checked.deliveryExpected(),
                checked.creditDays(), checked.subtotal(), checked.gst(), checked.total(), me)) {
            throw new IllegalStateException("A purchase order changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "CHANGED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_PO_CHANGED", ENTITY_TYPE, poId, Map.of("version", row.version()),
                values(storeId, checked.vendor().id(), checked.lines().size(), checked.total(), "DRAFT"));
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    @Transactional
    public PurchaseViews.PurchaseOrder discard(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                               PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = centralStore(caller, storeId);
        orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        if (!"DRAFT".equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft can be discarded.");
        }
        UUID me = caller.actorId();
        orders.setStatus(tenantId, poId, row.version(), "DISCARDED", me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "CANCELLED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_PO_DISCARDED", ENTITY_TYPE, poId, Map.of("status", "DRAFT"), Map.of("status", "DISCARDED"));
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    // ---- submit ----

    @Transactional
    public PurchaseViews.PurchaseOrder submit(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                              PharmacyInputs.Body<PharmacyInputs.VersionClientKeyInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.VersionClientKeyInput in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        ClientRequestRepository.Taken own = in.clientKey() == null ? null : clients.own(tenantId, me, in.clientKey()).orElse(null);
        if (own != null) {
            if (!OP_SUBMIT.equals(own.operation()) || !poId.equals(own.subjectId())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
        }
        if (in.clientKey() != null && clients.heldByAnyone(tenantId, in.clientKey())) {
            throw PharmacyProblemException.clientKeyReused();
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        Long version = version(in.version(), errors);
        throwIfAny(errors);
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_SUBMIT, poId, true) != null) {
            return submit(tenantId, userId, rawStoreId, rawPoId, body); // L15
        }
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        if (!"DRAFT".equals(row.status())) {
            throw PharmacyProblemException.docState("Only a draft can be submitted.");
        }
        List<Line> lines = orders.lines(tenantId, poId);
        List<FieldMessage> recheck = new ArrayList<>();
        List<PharmacyInputs.PoLineInput> inputs = lines.stream()
                .map(l -> new PharmacyInputs.PoLineInput(l.productId(), BigDecimal.valueOf(l.qty()), l.rate())).toList();
        Checked checked = check(tenantId, storeId, row.vendorId(), row.deliveryExpected(), BigDecimal.valueOf(row.creditDays()),
                inputs, true, recheck);
        throwIfAny(recheck);
        if (checked.vendor().blocked(time.today())) {
            throw PharmacyProblemException.vendorBlocked();
        }
        long seq = numbers.next(tenantId, DocType.PURCHASE_ORDER);
        String docNo = render(tenantId, seq, time.today());
        long total = row.totalPaise();
        long maxLine = lines.stream().mapToLong(l -> l.amountPaise() + l.gstPaise()).max().orElse(0L);
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.PURCHASE_ORDER, DOC_TYPE, poId, docNo,
                storeId, total, false, maxLine, false, null);
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        String status = plan == null ? "APPROVED" : "WAITING_APPROVAL";
        if (!orders.submit(tenantId, poId, row.version(), status, seq, docNo, me)) {
            throw new IllegalStateException("A purchase order changed under its lock");
        }
        if (plan != null) {
            UUID requestId = engine.submit(tenantId, store.unitId(), me, subject, plan);
            orders.attachRequest(tenantId, poId, requestId, me);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "SUBMITTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("number", docNo);
        after.put("status", status);
        after.put("totalPaise", total);
        auditLog.record("PHARMACY_PO_SUBMITTED", ENTITY_TYPE, poId, Map.of("status", "DRAFT"), after);
        log.info("Purchase order submitted purchaseOrderId={} status={}", poId, status);
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    // ---- mark sent, cancel, close short, copy ----

    @Transactional
    public PurchaseViews.PurchaseOrder markSent(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                                PharmacyInputs.Body<PharmacyInputs.MarkSentInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.MarkSentInput in = body.read();
        Store store = centralStore(caller, storeId);
        orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        if (in.sentOn() == null) {
            errors.add(new FieldMessage("sentOn", "Choose the date it was sent"));
        }
        throwIfAny(errors);
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        if (!"APPROVED".equals(row.status())) {
            throw PharmacyProblemException.docState("Only an approved purchase order can be marked as sent.");
        }
        LocalDate today = time.today();
        LocalDate approvedOn = row.updatedAt() == null ? today
                : LocalDate.ofInstant(row.updatedAt(), PharmacyTime.IST);
        if (in.sentOn().isAfter(today) || in.sentOn().isBefore(approvedOn)) {
            throw new InvalidFieldsException(List.of(new FieldMessage("sentOn",
                    "Choose a date from the approval date to today")));
        }
        UUID me = caller.actorId();
        if (!orders.markSent(tenantId, poId, row.version(), in.sentOn(), me)) {
            throw new IllegalStateException("A purchase order changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "SENT", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_PO_SENT", ENTITY_TYPE, poId, Map.of("status", "APPROVED"),
                Map.of("status", "SENT", "sentOn", in.sentOn().toString()));
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    @Transactional
    public PurchaseViews.PurchaseOrder cancel(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                              PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.VersionReasonInput in = body.read();
        Store store = centralStore(caller, storeId);
        Row peek = orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        String note = reason(tenantId, in.reasonKey(), in.note(), errors);
        throwIfAny(errors);
        // lock order: the approval request (step 2) before the header (step 3)
        ApprovalRepository.Request request = peek.approvalRequestId() == null ? null
                : approvals.findForUpdate(tenantId, peek.approvalRequestId()).orElse(null);
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        boolean cancellable = List.of("WAITING_APPROVAL", "APPROVED", "SENT").contains(row.status());
        if (!cancellable || orders.lines(tenantId, poId).stream().anyMatch(l -> l.receivedQty() > 0)) {
            throw PharmacyProblemException.docState("Only a purchase order with nothing received can be cancelled.");
        }
        UUID me = caller.actorId();
        if (request != null && request.pending()) {
            approvals.finish(tenantId, request.id(), request.version(), "WITHDRAWN", me);
        }
        orders.setStatus(tenantId, poId, row.version(), "CANCELLED", me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "CANCELLED", storeId, me, null, null, null, null, in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reasonKey", in.reasonKey());
        auditLog.record("PHARMACY_PO_CANCELLED", ENTITY_TYPE, poId, Map.of("status", row.status()), after);
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    @Transactional
    public PurchaseViews.PurchaseOrder closeShort(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                                  PharmacyInputs.Body<PharmacyInputs.VersionReasonInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.VersionReasonInput in = body.read();
        Store store = centralStore(caller, storeId);
        orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        List<FieldMessage> errors = new ArrayList<>();
        Long version = version(in.version(), errors);
        reason(tenantId, in.reasonKey(), in.note(), errors);
        throwIfAny(errors);
        Row row = orders.findForUpdate(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        requireVersion(caller, row, version);
        if (!"PARTLY_RECEIVED".equals(row.status())) {
            throw PharmacyProblemException.docState("Only a partly received purchase order can be closed short.");
        }
        UUID me = caller.actorId();
        int closed = orders.closeShort(tenantId, poId, row.version(), me);
        if (closed < 0) {
            throw new IllegalStateException("A purchase order changed under its lock");
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, poId, "CLOSED_SHORT", storeId, me, null, null, null, null,
                in.reasonKey());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CLOSED_SHORT");
        after.put("reasonKey", in.reasonKey());
        after.put("shortLineCount", closed);
        auditLog.record("PHARMACY_PO_CLOSED_SHORT", ENTITY_TYPE, poId, Map.of("status", "PARTLY_RECEIVED"), after);
        return view(caller, orders.find(tenantId, storeId, poId).orElseThrow());
    }

    @Transactional
    public PharmacyViews.Outcome<PurchaseViews.PurchaseOrder> copy(UUID tenantId, UUID userId, String rawStoreId, String rawPoId,
                                                                  PharmacyInputs.Body<PharmacyInputs.ClientKeyInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID poId = PharmacyParams.pathId(rawPoId);
        PharmacyInputs.ClientKeyInput in = body.read();
        Store store = centralStore(caller, storeId);
        UUID me = caller.actorId();
        Row source = orders.find(tenantId, storeId, poId).orElseThrow(PurchaseOrderService::notFound);
        Row replay = replay(caller, in.clientKey(), OP_COPY, storeId);
        if (replay != null) {
            return new PharmacyViews.Outcome<>(view(caller, replay), false);
        }
        if (in.clientKey() == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("clientKey", UnitService.RELOAD)));
        }
        if (!"REJECTED".equals(source.status())) {
            throw PharmacyProblemException.docState("Only a rejected purchase order can be copied.");
        }
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OP_COPY, id, false) != null) {
            return copy(tenantId, userId, rawStoreId, rawPoId, body); // L15
        }
        orders.insert(tenantId, store.unitId(), id, storeId, source.vendorId(), null, source.creditDays(), me);
        int sort = 1;
        long subtotal = 0;
        long gst = 0;
        for (Line l : orders.lines(tenantId, poId)) {
            ProductRepository.Row p = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElse(null);
            BigDecimal gstRate = p == null || p.gstRate() == null ? l.gstRate() : p.gstRate();
            long amount = PurchaseMath.amountPaise(l.qty(), l.rate());
            long tax = PurchaseMath.gstPaise(amount, gstRate);
            orders.insertLine(tenantId, store.unitId(), id, new NewLine(l.productId(), sort++, l.qty(), l.rate(), gstRate, amount,
                    tax), me);
            subtotal += amount;
            gst += tax;
        }
        orders.updateDraft(tenantId, id, 0L, source.vendorId(), null, source.creditDays(), subtotal, gst, subtotal + gst, me);
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "CREATED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = values(storeId, source.vendorId(), sort - 1, subtotal + gst, "DRAFT");
        after.put("copiedFrom", poId.toString());
        auditLog.record("PHARMACY_PO_CREATED", ENTITY_TYPE, id, null, after);
        return new PharmacyViews.Outcome<>(view(caller, orders.find(tenantId, storeId, id).orElseThrow()), true);
    }

    // ---- approval handler (79-2 engine, type PURCHASE_ORDER) ----

    @Override
    public String subjectType() {
        return DOC_TYPE;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        Row row = orders.findById(tenantId, request.subjectId()).orElse(null);
        if (row == null) {
            return new Summary(request.subjectNo(), "PURCHASE_ORDER", request.ruleStoreId(), List.of("REVIEW", "REJECT"));
        }
        String vendor = vendors.find(tenantId, row.vendorId()).map(Vendor::name).orElse("");
        return new Summary(row.docNo() + " · " + vendor + " · ₹ " + ApprovalConditions.rupees(row.totalPaise()),
                "PURCHASE_ORDER", row.storeId(), List.of("REVIEW", "REJECT"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.PURCHASE);
    }

    /** The final approval makes the order APPROVED, ready to print and send. */
    @Override
    public void onApproved(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = orders.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(PurchaseOrderService::notFound);
        if (!"WAITING_APPROVAL".equals(row.status())) {
            return;
        }
        UUID approver = decision.approver().actorId();
        orders.setStatusLocked(tenantId, row.id(), "WAITING_APPROVAL", "APPROVED", approver);
        events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(), null,
                null, null, null);
        auditLog.record("PHARMACY_PO_APPROVED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                Map.of("status", "APPROVED"));
    }

    /** A rejection returns the order as REJECTED; the raiser can copy it into a new draft. */
    @Override
    public void onRejected(Decision decision) {
        UUID tenantId = decision.tenantId();
        Row row = orders.findForUpdateById(tenantId, decision.request().subjectId()).orElseThrow(PurchaseOrderService::notFound);
        if ("WAITING_APPROVAL".equals(row.status())) {
            UUID approver = decision.approver().actorId();
            orders.setStatusLocked(tenantId, row.id(), "WAITING_APPROVAL", "REJECTED", approver);
            events.insert(tenantId, row.unitId(), DOC_TYPE, row.id(), "DECIDED", row.storeId(), approver, decision.actingAs(),
                    null, null, null, decision.input() == null ? null : decision.input().reasonKey());
            auditLog.record("PHARMACY_PO_REJECTED", ENTITY_TYPE, row.id(), Map.of("status", "WAITING_APPROVAL"),
                    Map.of("status", "REJECTED"));
        }
    }

    // ---- checks ----

    /** One checked order: the vendor, the fields and the lines with their amounts. */
    private record Checked(Vendor vendor, LocalDate deliveryExpected, int creditDays, List<NewLine> lines, long subtotal,
                           long gst, long total) {
    }

    /**
     * All the field errors together (rule 21). A null {@code creditDays} falls back to the vendor's; a null delivery date is
     * refused when {@code requireDelivery} (create, update and submit) and allowed on a draft made from the reorder list.
     */
    private Checked check(UUID tenantId, UUID storeId, UUID vendorId, LocalDate delivery, BigDecimal creditDaysIn,
                          List<PharmacyInputs.PoLineInput> lines, boolean requireDelivery, List<FieldMessage> errors) {
        LocalDate today = time.today();
        Vendor vendor = vendorId == null ? null : vendors.find(tenantId, vendorId).orElse(null);
        if (vendor == null || !vendor.active() || !orders.vendorSupplies(tenantId, vendor.id(), storeId)) {
            errors.add(new FieldMessage("vendorId", VENDOR_MESSAGE));
            vendor = null;
        }
        if (delivery == null && requireDelivery) {
            errors.add(new FieldMessage("deliveryExpected", "Choose the delivery date"));
        } else if (delivery != null && delivery.isBefore(today)) {
            errors.add(new FieldMessage("deliveryExpected", "Choose today or a later date"));
        }
        Integer creditDays = creditDaysIn == null ? (vendor == null ? Integer.valueOf(0) : Integer.valueOf(vendor.creditDays()))
                : PharmacyParams.wholeNumber(creditDaysIn, 0, 365);
        if (creditDays == null) {
            errors.add(new FieldMessage("creditDays", "Enter 0 to 365 days"));
        }
        List<PharmacyInputs.PoLineInput> given = lines == null ? List.of() : lines;
        if (given.isEmpty() || given.size() > MAX_LINES) {
            errors.add(new FieldMessage("lines", MAX_LINES_MESSAGE));
        }
        List<NewLine> built = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        long subtotal = 0;
        long gst = 0;
        for (int i = 0; i < given.size() && given.size() <= MAX_LINES; i++) {
            PharmacyInputs.PoLineInput line = given.get(i);
            String at = "lines[" + i + "]";
            if (line == null) {
                errors.add(new FieldMessage(at, UnitService.REQUIRED));
                continue;
            }
            ProductRepository.Full full = line.productId() == null ? null : products.find(tenantId, line.productId()).orElse(null);
            if (full == null || !full.active()) {
                errors.add(new FieldMessage(at + ".productId", "Choose an active product"));
            } else if (!seen.add(line.productId())) {
                errors.add(new FieldMessage(at + ".productId", "Already on this purchase order"));
            }
            Integer qty = PharmacyParams.wholeNumber(line.qty(), 1, MAX_QTY);
            if (qty == null) {
                errors.add(new FieldMessage(at + ".qty", "Enter a whole number from 1 to 999999"));
            }
            if (!PurchaseMath.validRate(line.rate())) {
                errors.add(new FieldMessage(at + ".rate", "Enter a rate above 0, up to 4 decimals"));
            }
            if (full != null && full.active() && qty != null && PurchaseMath.validRate(line.rate())) {
                BigDecimal gstRate = full.row().gstRate() == null ? BigDecimal.ZERO : full.row().gstRate();
                long amount = PurchaseMath.amountPaise(qty, line.rate());
                long tax = PurchaseMath.gstPaise(amount, gstRate);
                built.add(new NewLine(line.productId(), built.size() + 1, qty, line.rate(), gstRate, amount, tax));
                subtotal += amount;
                gst += tax;
            }
        }
        return new Checked(vendor, delivery, creditDays == null ? 0 : creditDays, built, subtotal, gst, subtotal + gst);
    }

    /** A field error of a vendor group, named as the rows of the request: lines[j] is the j-th row of the group. */
    private static FieldMessage rowError(FieldMessage e, List<Integer> indexes) {
        String field = e.field();
        if (field.startsWith("lines[")) {
            int end = field.indexOf(']');
            int j = Integer.parseInt(field.substring(6, end));
            return new FieldMessage("rows[" + indexes.get(j) + "]" + field.substring(end + 1), e.message());
        }
        if ("vendorId".equals(field) || "lines".equals(field)) {
            return new FieldMessage("rows[" + indexes.get(0) + "].vendorId", e.message());
        }
        return e;
    }

    private void write(UUID tenantId, Store store, UUID me, UUID id, Checked checked) {
        orders.insert(tenantId, store.unitId(), id, store.id(), checked.vendor().id(), checked.deliveryExpected(),
                checked.creditDays(), me);
        for (NewLine line : checked.lines()) {
            orders.insertLine(tenantId, store.unitId(), id, line, me);
        }
        if (!orders.updateDraft(tenantId, id, 0L, checked.vendor().id(), checked.deliveryExpected(), checked.creditDays(),
                checked.subtotal(), checked.gst(), checked.total(), me)) {
            throw new IllegalStateException("A purchase order changed under its lock");
        }
    }

    private void auditCreated(UUID id, UUID storeId, Checked checked, UUID copiedFrom) {
        Map<String, Object> after = values(storeId, checked.vendor().id(), checked.lines().size(), checked.total(), "DRAFT");
        if (copiedFrom != null) {
            after.put("copiedFrom", copiedFrom.toString());
        }
        auditLog.record("PHARMACY_PO_CREATED", ENTITY_TYPE, id, null, after);
    }

    private String reason(UUID tenantId, String reasonKey, String note, List<FieldMessage> errors) {
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (!reasons.isActive(tenantId, ReasonList.APPROVAL_REJECT, reasonKey)) {
            errors.add(new FieldMessage("reasonKey", "Choose a reason"));
        } else if (reasonKey.endsWith(".other") && (trimmed == null || trimmed.length() < 4)) {
            errors.add(new FieldMessage("note", "Write at least 4 characters"));
        }
        if (trimmed != null && trimmed.length() > 300) {
            errors.add(new FieldMessage("note", "Keep the note to 300 characters"));
        }
        return trimmed;
    }

    private static Long version(BigDecimal raw, List<FieldMessage> errors) {
        Integer whole = PharmacyParams.wholeNumber(raw, 0, Integer.MAX_VALUE);
        if (whole == null) {
            errors.add(new FieldMessage("version", UnitService.RELOAD));
            return null;
        }
        return whole.longValue();
    }

    private void requireVersion(PharmacyCaller caller, Row row, Long version) {
        if (version == null || row.version() != version) {
            throw PharmacyProblemException.changed(view(caller, row));
        }
    }

    private static void throwIfAny(List<FieldMessage> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private Row replay(PharmacyCaller caller, UUID clientKey, String operation, UUID storeId) {
        if (clientKey == null) {
            return null;
        }
        ClientRequestRepository.Taken own = clients.own(caller.tenantId(), caller.actorId(), clientKey).orElse(null);
        if (own != null) {
            if (!operation.equals(own.operation())) {
                throw PharmacyProblemException.clientKeyReused();
            }
            return orders.find(caller.tenantId(), storeId, own.subjectId()).orElseThrow(PurchaseOrderService::notFound);
        }
        if (clients.heldByAnyone(caller.tenantId(), clientKey)) {
            throw PharmacyProblemException.clientKeyReused();
        }
        return null;
    }

    private Store centralStore(PharmacyCaller caller, UUID storeId) {
        Store store = guard.require(caller, storeId, StoreCapability.PURCHASE);
        if (!store.type().isCentral()) {
            throw PharmacyProblemException.docState(CENTRAL_ONLY);
        }
        return store;
    }

    private static List<String> statuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            try {
                found.add(PurchaseOrderStatus.valueOf(name).name());
            } catch (IllegalArgumentException unknown) {
                throw new InvalidFieldsException(List.of(new FieldMessage("status", "Choose a valid status")));
            }
        }
        return found;
    }

    private static Integer overdueDays(String status, LocalDate expected, boolean outstanding, LocalDate today) {
        if (expected == null || !outstanding || !("SENT".equals(status) || "PARTLY_RECEIVED".equals(status))
                || !expected.isBefore(today)) {
            return null;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(expected, today);
    }

    private boolean isCurrentApprover(PharmacyCaller caller, Row row) {
        if (row.approvalRequestId() == null) {
            return false;
        }
        ApprovalRepository.Request request = approvals.find(caller.tenantId(), row.approvalRequestId()).orElse(null);
        if (request == null || !request.pending()) {
            return false;
        }
        return engine.approvers(caller.tenantId(), request, approvals.levels(caller.tenantId(), request.id()),
                approvals.decisions(caller.tenantId(), request.id())).canDecide(caller.actorId());
    }

    private String render(UUID tenantId, long seq, LocalDate today) {
        DocumentSeriesRepository.Row saved = series.find(tenantId, DocType.PURCHASE_ORDER).orElse(null);
        if (saved == null) {
            return DocumentNumbers.render(DocType.PURCHASE_ORDER.defaultPrefix(), DocType.PURCHASE_ORDER.defaultFinancialYearPart(),
                    DocType.PURCHASE_ORDER.defaultSequenceLength(), seq, today);
        }
        return DocumentNumbers.render(saved.prefix(), saved.financialYearPart(), saved.sequenceLength(), seq, today);
    }

    private static Map<String, Object> values(UUID storeId, UUID vendorId, int lines, long totalPaise, String status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storeId", storeId.toString());
        values.put("vendorId", vendorId.toString());
        values.put("lineCount", lines);
        values.put("totalPaise", totalPaise);
        values.put("status", status);
        return values;
    }

    // ---- views ----

    private PurchaseViews.VendorRef vendorRef(Vendor v, LocalDate today) {
        return new PurchaseViews.VendorRef(v.id(), v.name(), v.gstin(), v.blocked(today), false);
    }

    private static StockViews.ProductRef productRef(ProductRepository.Row p) {
        return new StockViews.ProductRef(p.productId(), p.code(), p.name(), p.strength(), p.form(), p.schedule(),
                p.purchaseUnit(), p.issueUnit(), p.conversion(), PurchaseMath.percentText(p.gstRate()));
    }

    private PurchaseViews.PurchaseOrder view(PharmacyCaller caller, Row row) {
        UUID tenantId = caller.tenantId();
        LocalDate today = time.today();
        Vendor vendor = vendors.find(tenantId, row.vendorId()).orElseThrow();
        Store store = stores.find(tenantId, row.storeId()).orElseThrow();
        List<Line> lines = orders.lines(tenantId, row.id());
        List<PurchaseViews.PoLine> lineViews = new ArrayList<>();
        boolean outstanding = false;
        for (Line l : lines) {
            ProductRepository.Row p = products.find(tenantId, l.productId()).map(ProductRepository.Full::row).orElseThrow();
            lineViews.add(new PurchaseViews.PoLine(l.id(), productRef(p), l.qty(), PurchaseMath.rateText(l.rate()),
                    PurchaseMath.percentText(l.gstRate()), l.amountPaise(), l.gstPaise(), l.receivedQty(), l.closedShort()));
            outstanding |= !l.closedShort() && l.receivedQty() < l.qty();
        }
        List<DocumentEventRepository.Event> history = events.forDocument(tenantId, DOC_TYPE, row.id());
        Set<UUID> people = new LinkedHashSet<>();
        people.add(row.createdBy());
        history.forEach(e -> {
            if (e.actorMembershipId() != null) {
                people.add(e.actorMembershipId());
            }
        });
        Map<UUID, TeamMemberPort.TeamMember> members = team.findAll(tenantId, people);
        Map<UUID, Store> storeById = new HashMap<>();
        stores.list(tenantId, true).forEach(s -> storeById.put(s.id(), s));
        List<StockViews.HistoryEvent> historyViews = new ArrayList<>();
        for (DocumentEventRepository.Event e : history) {
            Store eventStore = storeById.get(e.storeId());
            historyViews.add(new StockViews.HistoryEvent(e.event(), e.occurredAt(),
                    e.actorMembershipId() == null ? null : person(e.actorMembershipId(), members), e.actingAs(),
                    eventStore == null ? null : OpeningStockPostService.storeRef(eventStore), null, e.qtyBefore(), e.qtyAfter(),
                    engine.reasonRef(tenantId, e.reasonKey(), null)));
        }
        List<PurchaseViews.PoReceipt> receipts = orders.receipts(tenantId, row.id()).stream()
                .map(r -> new PurchaseViews.PoReceipt(r.grnId(), r.docNo(), r.postedAt())).toList();
        boolean closedShort = "CLOSED_SHORT".equals(row.status());
        return new PurchaseViews.PurchaseOrder(row.id(), row.docNo(), OpeningStockPostService.storeRef(store),
                vendorRef(vendor, today), row.status(), row.deliveryExpected(), row.creditDays(), row.sentOn(),
                overdueDays(row.status(), row.deliveryExpected(), outstanding, today),
                closedShort ? orders.shortLineCount(tenantId, row.id()) : null, lineViews, row.subtotalPaise(), row.gstPaise(),
                row.totalPaise(), engine.ruleNote(tenantId, TransactionType.PURCHASE_ORDER, "POs"),
                engine.summary(caller, DOC_TYPE, row.id()), receipts, person(row.createdBy(), members), row.createdAt(),
                historyViews, printHeader(tenantId), row.version());
    }

    private PurchaseViews.PrintHeader printHeader(UUID tenantId) {
        PrintTemplateRepository.Row template = templates.find(tenantId, PrintDocType.DEBIT_NOTE).orElse(null);
        com.sahaj.pharmacy.domain.Unit unit = unitRows.find(tenantId).orElse(null);
        return new PurchaseViews.PrintHeader("DEBIT_NOTE", template == null ? List.of() : template.headerLines(),
                template == null ? null : template.footerNote(), template == null ? "A4" : template.paperSize(),
                unit == null ? null : unit.gstin(), unit == null ? null : unit.drugLicenceNo());
    }

    private static PharmacyViews.Person person(UUID membershipId, Map<UUID, TeamMemberPort.TeamMember> members) {
        TeamMemberPort.TeamMember member = members.get(membershipId);
        return new PharmacyViews.Person(membershipId, member == null ? OpeningStockAssembler.UNKNOWN_MEMBER : member.name());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("This record was not found.");
    }
}
