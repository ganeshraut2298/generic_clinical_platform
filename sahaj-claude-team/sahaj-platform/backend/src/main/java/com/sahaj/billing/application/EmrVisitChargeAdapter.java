package com.sahaj.billing.application;

import com.sahaj.billing.application.BillItemRepository.EmrLine;
import com.sahaj.billing.application.BillRepository.BillRow;
import com.sahaj.opd.api.EmrVisitChargePort;
import com.sahaj.opd.api.ServiceLookupPort;
import com.sahaj.opd.api.ServiceLookupPort.ServiceSummary;
import com.sahaj.platform.api.AuditLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-48-4 (ADR-022 D7): implements {@link EmrVisitChargePort} so the Doctor EMR can make the visit bill's EMR lines
 * equal to the "Bill today" services without {@code opd} depending on {@code billing}. It runs in the EMR save
 * transaction, so a refusal rolls the whole save back. Lines are named by the EMR item id (unique per bill), so a retry
 * gives one line; counter lines are never touched. The price is the clinic's price at this moment (a later change does
 * not change the line). When the bill has any payment, a removal or a quantity change is refused
 * ({@link Outcome#ALREADY_BILLED}); adding a line is still allowed. Audits carry ids and money only.
 */
@Component
class EmrVisitChargeAdapter implements EmrVisitChargePort {

    private static final String ENTITY_BILL = "BILL";
    private static final int MAX_LABEL = 120;
    private static final int MAX_SOURCE_KEY = 80;
    private static final long MAX_PAISE = 100_000_000L;

    private final BillRepository bills;
    private final BillItemRepository items;
    private final PaymentRepository payments;
    private final ServiceLookupPort services;
    private final AuditLog auditLog;

    EmrVisitChargeAdapter(BillRepository bills, BillItemRepository items, PaymentRepository payments,
                          ServiceLookupPort services, AuditLog auditLog) {
        this.bills = bills;
        this.items = items;
        this.payments = payments;
        this.services = services;
        this.auditLog = auditLog;
    }

    @Override
    public Outcome syncEmrLines(UUID tenantId, UUID actorUserId, UUID visitId, List<Line> wanted) {
        Map<UUID, ServiceSummary> prices = new HashMap<>();
        for (Line line : wanted) {
            if (line.qty() < 1 || line.qty() > 20) {
                return Outcome.INVALID_SERVICE;
            }
            Optional<ServiceSummary> service = prices.containsKey(line.serviceId())
                    ? Optional.of(prices.get(line.serviceId()))
                    : services.find(tenantId, line.serviceId()).filter(ServiceSummary::active);
            if (service.isEmpty()) {
                return Outcome.INVALID_SERVICE;
            }
            prices.put(line.serviceId(), service.get());
        }
        Optional<BillRow> bill = bills.findByVisit(tenantId, visitId);
        if (bill.isEmpty() && wanted.isEmpty()) {
            return Outcome.SYNCED;
        }
        UUID billId = bill.map(BillRow::id).orElseGet(() -> bills.getOrCreate(tenantId, visitId, actorUserId));
        Map<String, EmrLine> existing = new LinkedHashMap<>();
        for (EmrLine line : items.findEmrLines(tenantId, billId)) {
            existing.put(line.sourceKey(), line);
        }
        Set<String> wantedKeys = new HashSet<>();
        for (Line line : wanted) {
            wantedKeys.add(line.sourceKey());
        }
        List<EmrLine> removals = existing.values().stream().filter(l -> !wantedKeys.contains(l.sourceKey())).toList();
        List<Line> changes = wanted.stream().filter(l -> existing.containsKey(l.sourceKey())
                && existing.get(l.sourceKey()).qty() != l.qty()).toList();
        if ((!removals.isEmpty() || !changes.isEmpty()) && !payments.findByBill(tenantId, billId).isEmpty()) {
            return Outcome.ALREADY_BILLED;
        }
        for (EmrLine line : removals) {
            items.delete(tenantId, line.id());
            audit("BILL_ITEM_REMOVED_FROM_EMR", billId, visitId, line.serviceId(), line.qty(), line.amountPaise());
        }
        for (Line line : changes) {
            EmrLine old = existing.get(line.sourceKey());
            items.updateQty(tenantId, old.id(), line.qty());
            audit("BILL_ITEM_QTY_CHANGED_FROM_EMR", billId, visitId, line.serviceId(), line.qty(), old.amountPaise());
        }
        for (Line line : wanted) {
            if (!existing.containsKey(line.sourceKey())) {
                ServiceSummary service = prices.get(line.serviceId());
                items.insertEmr(UUID.randomUUID(), billId, service.id(), service.name(), service.pricePaise(),
                        line.qty(), line.sourceKey(), actorUserId);
                audit("BILL_ITEM_ADDED_FROM_EMR", billId, visitId, service.id(), line.qty(), service.pricePaise());
            }
        }
        return Outcome.SYNCED;
    }

    @Override
    public boolean hasPayment(UUID tenantId, UUID visitId) {
        return bills.findByVisit(tenantId, visitId)
                .map(bill -> !payments.findByBill(tenantId, bill.id()).isEmpty()).orElse(false);
    }

    @Override
    public Outcome addPricedLines(UUID tenantId, UUID actorUserId, UUID visitId, List<PricedLine> lines) {
        for (PricedLine line : lines) {
            if (line.label() == null || line.label().isBlank() || line.label().length() > MAX_LABEL
                    || line.sourceKey() == null || line.sourceKey().length() > MAX_SOURCE_KEY
                    || line.amountPaise() < 0 || line.amountPaise() > MAX_PAISE) {
                return Outcome.INVALID_SERVICE;
            }
        }
        if (lines.isEmpty()) {
            return Outcome.SYNCED;
        }
        Optional<BillRow> bill = bills.findByVisit(tenantId, visitId);
        UUID billId = bill.map(BillRow::id).orElseGet(() -> bills.getOrCreate(tenantId, visitId, actorUserId));
        for (PricedLine line : lines) {
            if (items.insertPlan(UUID.randomUUID(), billId, line.label(), line.amountPaise(), line.sourceKey(),
                    actorUserId)) {
                auditLog.record("BILL_ITEM_ADDED_FROM_EMR_PLAN", ENTITY_BILL, billId, null,
                        Map.of("visitId", visitId, "qty", 1, "amountPaise", line.amountPaise()));
            }
        }
        return Outcome.SYNCED;
    }

    @Override
    public Map<String, String> billedState(UUID tenantId, UUID visitId) {
        Map<String, String> state = new LinkedHashMap<>();
        Optional<BillRow> bill = bills.findByVisit(tenantId, visitId);
        if (bill.isEmpty()) {
            return state;
        }
        boolean paid = !payments.findByBill(tenantId, bill.get().id()).isEmpty();
        for (EmrLine line : items.findEmrLines(tenantId, bill.get().id())) {
            state.put(line.sourceKey(), paid ? "ON_PAID_BILL" : "ON_BILL");
        }
        return state;
    }

    private void audit(String action, UUID billId, UUID visitId, UUID serviceId, int qty, long amountPaise) {
        auditLog.record(action, ENTITY_BILL, billId, null,
                Map.of("visitId", visitId, "serviceId", serviceId, "qty", qty, "amountPaise", amountPaise));
    }
}
