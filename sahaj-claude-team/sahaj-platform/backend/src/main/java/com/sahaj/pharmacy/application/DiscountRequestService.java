package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.SaleInputs.DiscountRequestInput;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
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
 * SAH-80-6, a discount above the limit sent for approval (docs/api/pharmacy-sales.yaml pharmacyRequestDiscountApproval,
 * pharmacyGetDiscountRequest, pharmacyWithdrawDiscountRequest; ADR-030 S14) and the approval handler of the type SALE_DISCOUNT.
 *
 * <p>The cart is priced again on the server; level 0 (Lean, or a percent within the limit) writes nothing and answers
 * {@code required false}. Otherwise one engine request is submitted (subject id = the new link row, amount = the discount in paise,
 * the percent is the figure ABOVE_PERCENT compares) and the link row keeps the cart digest and percent. A new request of the same
 * seller for the same held bill (or no held bill) withdraws the seller's earlier PENDING one. The bill save reads the request through
 * {@link DiscountApprovals}. An approval changes nothing in stock or money here: the decision is the only effect, so the handler
 * writes the history row and the audit row only. Names and notes are never logged.
 */
@Service
public class DiscountRequestService implements ApprovalSubjectHandler {

    static final String OPERATION = "pharmacyRequestDiscountApproval";
    static final String SUBJECT = "SALE_DISCOUNT";
    private static final Logger log = LoggerFactory.getLogger(DiscountRequestService.class);
    private static final String ENTITY_TYPE = "PHARMACY_DISCOUNT_REQUEST";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final SalePricer pricer;
    private final ApprovalEngine engine;
    private final ApprovalRepository approvals;
    private final DiscountRequestRepository requests;
    private final StoreRepository stores;
    private final SaleSupport support;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final AuditLog auditLog;
    private final DiscountApprovals discountApprovals;

    public DiscountRequestService(PharmacyGate gate, StoreAccessGuard guard, SalePricer pricer, ApprovalEngine engine,
                                  ApprovalRepository approvals, DiscountRequestRepository requests, StoreRepository stores,
                                  SaleSupport support, ClientRequestRepository clients, DocumentEventRepository events,
                                  AuditLog auditLog, DiscountApprovals discountApprovals) {
        this.gate = gate;
        this.guard = guard;
        this.pricer = pricer;
        this.engine = engine;
        this.approvals = approvals;
        this.requests = requests;
        this.stores = stores;
        this.support = support;
        this.clients = clients;
        this.events = events;
        this.auditLog = auditLog;
        this.discountApprovals = discountApprovals;
    }

    // ---- request ----

    /** In a read-write transaction: pricing may run the expiry sweep of the store (ADR-029 P4). */
    @Transactional
    public PharmacyViews.Outcome<SaleCheckViews.DiscountRequest> request(UUID tenantId, UUID userId, String rawStoreId,
                                                                        PharmacyInputs.Body<DiscountRequestInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        DiscountRequestInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                DiscountRequestRepository.Row first = requests.find(tenantId, own.subjectId()).orElse(null);
                return new PharmacyViews.Outcome<>(first == null ? notRequired(first) : view(caller, first), false);
            }
            if (clients.heldByAnyone(tenantId, in.clientKey())) {
                throw PharmacyProblemException.clientKeyReused();
            }
        }
        List<FieldMessage> errors = new ArrayList<>();
        if (in.clientKey() == null) {
            errors.add(new FieldMessage("clientKey", UnitService.RELOAD));
        }
        SaleCart.Cart cart = pricer.read(tenantId, in.cart(), errors);
        if (cart != null && (cart.percent() == null || cart.percent().signum() <= 0)) {
            errors.add(new FieldMessage("discountPercent", "Enter a discount above 0 and up to 100"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, id, false) != null) {
            return request(tenantId, userId, rawStoreId, body); // L15: answer as the replay
        }
        SalePricer.Priced priced = pricer.price(caller, store, cart, false);
        long discount = priced.math().discount();
        if (!priced.discount().approvalNeeded()) {
            return new PharmacyViews.Outcome<>(new SaleCheckViews.DiscountRequest(false, null, null,
                    SaleRules.percent(cart.percent()), discount, null, null, false, false, false, null, null, null), true);
        }
        ApprovalEngine.Subject subject = new ApprovalEngine.Subject(TransactionType.SALE_DISCOUNT, SUBJECT, id,
                "Discount " + SaleRules.percent(cart.percent()) + "%", storeId, discount, false, 0L, false, cart.percent());
        ApprovalEngine.Plan plan = engine.plan(tenantId, subject);
        if (plan == null) {
            return new PharmacyViews.Outcome<>(new SaleCheckViews.DiscountRequest(false, null, null,
                    SaleRules.percent(cart.percent()), discount, null, null, false, false, false, null, null, null), true);
        }
        withdrawEarlier(tenantId, caller, storeId, in.heldBillId());
        UUID approvalId = engine.submit(tenantId, store.unitId(), me, subject, plan);
        requests.insert(tenantId, new DiscountRequestRepository.NewRow(id, store.unitId(), storeId, approvalId,
                DiscountApprovals.digest(cart), cart.percent(), discount, in.heldBillId(), me), me);
        events.insert(tenantId, store.unitId(), SUBJECT, id, "DISCOUNT_REQUESTED", storeId, me, null, null, null, null, null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", id.toString());
        after.put("percent", SaleRules.percent(cart.percent()));
        after.put("discountPaise", discount);
        auditLog.record("PHARMACY_DISCOUNT_REQUESTED", ENTITY_TYPE, id, null, after);
        log.info("Discount requested requestId={} discountPaise={}", id, discount);
        return new PharmacyViews.Outcome<>(view(caller, requests.find(tenantId, id).orElseThrow()), true);
    }

    /** The seller's earlier PENDING request of the same held bill (or of no held bill) is withdrawn by the new one. */
    private void withdrawEarlier(UUID tenantId, PharmacyCaller caller, UUID storeId, UUID heldBillId) {
        for (DiscountRequestRepository.Row old : requests.bySeller(tenantId, storeId, caller.actorId(), heldBillId)) {
            ApprovalRepository.Request approval = approvals.findForUpdate(tenantId, old.approvalRequestId()).orElse(null);
            if (approval != null && approval.pending()) {
                approvals.finish(tenantId, approval.id(), approval.version(), "WITHDRAWN", caller.actorId());
            }
        }
    }

    /**
     * DEC-089 item 4: discarding a held bill withdraws its PENDING discount requests in the caller's transaction (audit
     * PHARMACY_DISCOUNT_WITHDRAWN and the history line; no decision row, the engine request is only finished as WITHDRAWN).
     */
    public void withdrawForDiscard(UUID tenantId, PharmacyCaller caller, Store store, UUID heldBillId) {
        for (DiscountRequestRepository.Row row : requests.byHeldBill(tenantId, store.id(), heldBillId)) {
            ApprovalRepository.Request approval = approvals.findForUpdate(tenantId, row.approvalRequestId()).orElse(null);
            if (approval == null || !approval.pending()) {
                continue;
            }
            if (!approvals.finish(tenantId, approval.id(), approval.version(), "WITHDRAWN", caller.actorId())) {
                throw PharmacyProblemException.changed(null);
            }
            events.insert(tenantId, store.unitId(), SUBJECT, row.id(), "DISCOUNT_WITHDRAWN", store.id(), caller.actorId(), null,
                    null, null, null, null);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("requestId", row.id().toString());
            auditLog.record("PHARMACY_DISCOUNT_WITHDRAWN", ENTITY_TYPE, row.id(), Map.of("status", "PENDING"), after);
        }
    }

    /**
     * The discountState of a held bill row (contract NONE, PENDING, APPROVED, REJECTED, VOID): the state of the request its cart names.
     * A withdrawn request, an unknown one, or an APPROVED one that can no longer be used by the holder reads VOID. Reads only.
     */
    @Transactional(readOnly = true)
    public String heldState(UUID tenantId, UUID requestId, UUID holder) {
        if (requestId == null) {
            return "NONE";
        }
        DiscountRequestRepository.Row row = requests.find(tenantId, requestId).orElse(null);
        ApprovalRepository.Request approval = row == null ? null : approvals.find(tenantId, row.approvalRequestId()).orElse(null);
        if (approval == null) {
            return "VOID";
        }
        switch (approval.status()) {
            case "PENDING":
                return "PENDING";
            case "REJECTED":
                return "REJECTED";
            case "APPROVED":
                return discountApprovals.usable(tenantId, row, approval, holder) ? "APPROVED" : "VOID";
            default:
                return "VOID";
        }
    }

    // ---- get and withdraw ----

    @Transactional(readOnly = true)
    public SaleCheckViews.DiscountRequest get(UUID tenantId, UUID userId, String rawStoreId, String rawRequestId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID requestId = PharmacyParams.pathId(rawRequestId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        return view(caller, requests.find(tenantId, requestId).filter(r -> r.storeId().equals(storeId))
                .orElseThrow(DiscountRequestService::notFound));
    }

    /** PENDING becomes WITHDRAWN and leaves the inbox; a decided or withdrawn request answers as it is, nothing written. */
    @Transactional
    public SaleCheckViews.DiscountRequest withdraw(UUID tenantId, UUID userId, String rawStoreId, String rawRequestId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID requestId = PharmacyParams.pathId(rawRequestId);
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        DiscountRequestRepository.Row row = requests.find(tenantId, requestId).filter(r -> r.storeId().equals(storeId))
                .orElseThrow(DiscountRequestService::notFound);
        ApprovalRepository.Request approval = approvals.findForUpdate(tenantId, row.approvalRequestId())
                .orElseThrow(DiscountRequestService::notFound);
        if (approval.pending()) {
            if (!approvals.finish(tenantId, approval.id(), approval.version(), "WITHDRAWN", caller.actorId())) {
                throw PharmacyProblemException.changed(null);
            }
            events.insert(tenantId, store.unitId(), SUBJECT, row.id(), "DISCOUNT_WITHDRAWN", storeId, caller.actorId(), null,
                    null, null, null, null);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("requestId", row.id().toString());
            auditLog.record("PHARMACY_DISCOUNT_WITHDRAWN", ENTITY_TYPE, row.id(), Map.of("status", "PENDING"), after);
        }
        return view(caller, row);
    }

    // ---- view ----

    private SaleCheckViews.DiscountRequest notRequired(DiscountRequestRepository.Row ignored) {
        return new SaleCheckViews.DiscountRequest(false, null, null, null, 0L, null, null, false, false, false, null, null, null);
    }

    /** An APPROVED request reads VOID once it is used, its IST day is over, or the reader is not the seller who raised it. */
    private String statusOf(PharmacyCaller caller, DiscountRequestRepository.Row row, ApprovalRepository.Request approval) {
        if (approval == null) {
            return null;
        }
        if ("APPROVED".equals(approval.status())
                && !discountApprovals.usable(caller.tenantId(), row, approval, caller.actorId())) {
            return "VOID";
        }
        return approval.status();
    }

    private SaleCheckViews.DiscountRequest view(PharmacyCaller caller, DiscountRequestRepository.Row row) {
        UUID tenantId = caller.tenantId();
        ApprovalRepository.Request approval = approvals.find(tenantId, row.approvalRequestId()).orElse(null);
        StockViews.ApprovalSummary summary = engine.summary(caller, SUBJECT, row.id());
        PharmacyViews.Person decidedBy = null;
        if (approval != null && "APPROVED".equals(approval.status())) {
            ApprovalRepository.Decision last = approvals.decisions(tenantId, approval.id()).stream()
                    .filter(d -> "APPROVED".equals(d.decision())).reduce((a, b) -> b).orElse(null);
            if (last != null) {
                decidedBy = support.person(last.decidedBy(), support.members(tenantId, List.of(last.decidedBy())));
            }
        }
        boolean pending = approval != null && approval.pending();
        String ruleText = summary == null || summary.ruleText() == null ? null
                : summary.ruleText().replaceFirst("^Sale discount", "Discount");
        return new SaleCheckViews.DiscountRequest(true, row.id(), statusOf(caller, row, approval),
                SaleRules.percent(row.discountPercent()), row.discountPaise(), ruleText,
                summary == null ? null : summary.waitingFor(), summary != null && summary.escalated(),
                summary != null && summary.needsAnotherApprover(), pending && summary != null
                        && summary.needsAnotherApprover() && row.requestedBy().equals(caller.actorId()),
                decidedBy, summary == null ? null : summary.rejectReason(), approval == null ? null : approval.raisedAt());
    }

    // ---- approval handler (SALE_DISCOUNT) ----

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Summary summary(UUID tenantId, ApprovalRepository.Request request) {
        DiscountRequestRepository.Row row = requests.find(tenantId, request.subjectId()).orElse(null);
        String storeName = stores.find(tenantId, request.ruleStoreId()).map(Store::name).orElse("");
        String what = row == null ? request.subjectNo() : "Discount " + SaleRules.percent(row.discountPercent()) + " · "
                + SaleRules.rupeesText(row.discountPaise()) + " · " + storeName;
        return new Summary(what, "DISCOUNT", request.ruleStoreId(), List.of("APPROVE", "REJECT", "REVIEW"));
    }

    @Override
    public boolean canRead(PharmacyCaller caller, ApprovalRepository.Request request) {
        return StoreAccessGuard.holds(caller, request.ruleStoreId(), StoreCapability.SELL);
    }

    /** The approval is the whole effect: the bill save reads it. Writes the history row and the audit row. */
    @Override
    public void onApproved(Decision decision) {
        recordDecision(decision, "APPROVED", null);
    }

    @Override
    public void onRejected(Decision decision) {
        recordDecision(decision, "REJECTED", decision.input() == null ? null : decision.input().reasonKey());
    }

    private void recordDecision(Decision decision, String outcome, String reasonKey) {
        ApprovalRepository.Request request = decision.request();
        UUID tenantId = decision.tenantId();
        events.insert(tenantId, request.unitId(), SUBJECT, request.subjectId(), "DISCOUNT_DECIDED", request.ruleStoreId(),
                decision.approver().actorId(), decision.actingAs(), null, null, null, reasonKey);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("requestId", request.subjectId().toString());
        after.put("outcome", outcome);
        auditLog.record("PHARMACY_DISCOUNT_DECIDED", ENTITY_TYPE, request.subjectId(), Map.of("status", "PENDING"), after);
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
