package com.sahaj.pharmacy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.HeldBillRepository.Row;
import com.sahaj.pharmacy.application.SaleInputs.CartInput;
import com.sahaj.pharmacy.application.SaleInputs.CartLineInput;
import com.sahaj.pharmacy.application.SaleInputs.HoldInput;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.SaleRules;
import com.sahaj.pharmacy.domain.Store;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
 * SAH-80-5, held bills (docs/api/pharmacy-sales.yaml pharmacyHoldBill, pharmacyListHeldBills, pharmacyGetHeldBill,
 * pharmacyDiscardHeldBill; ADR-030 S5, BR-11): a cart kept as ids with no number, no stock movement, no reservation and no payment.
 * At most 20 are OPEN in a store, counted under the store row lock (409 held-limit). A held bill is final once it is not OPEN. The
 * buyer of a list row is a masked name or "Walk-in". Audit rows and logs hold ids and counts only (rule 29).
 */
@Service
public class HeldBillService {

    static final String OPERATION = "pharmacyHoldBill";
    static final String DOC_TYPE = "HELD_BILL";
    static final int LIMIT = 20;
    static final String HELD_LIMIT = "Too many held bills. Finish or discard one.";
    private static final Logger log = LoggerFactory.getLogger(HeldBillService.class);
    private static final String ENTITY_TYPE = "PHARMACY_HELD_BILL";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final StoreRepository stores;
    private final HeldBillRepository held;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final SalePricer pricer;
    private final SaleSupport support;
    private final ClientRequestRepository clients;
    private final DocumentEventRepository events;
    private final AuditLog auditLog;
    private final PharmacyTime time;
    private final ObjectMapper mapper;
    private final DiscountRequestService discounts;

    public HeldBillService(PharmacyGate gate, StoreAccessGuard guard, StoreRepository stores, HeldBillRepository held,
                           ProductRepository products, CustomerRepository customers, SalePricer pricer, SaleSupport support,
                           ClientRequestRepository clients, DocumentEventRepository events, AuditLog auditLog,
                           PharmacyTime time, ObjectMapper mapper, DiscountRequestService discounts) {
        this.discounts = discounts;
        this.gate = gate;
        this.guard = guard;
        this.stores = stores;
        this.held = held;
        this.products = products;
        this.customers = customers;
        this.pricer = pricer;
        this.support = support;
        this.clients = clients;
        this.events = events;
        this.auditLog = auditLog;
        this.time = time;
        this.mapper = mapper;
    }

    /** Every OPEN held bill of the store (any holder), oldest first, at most 20. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.HeldBillList list(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        List<Row> rows = held.open(tenantId, storeId, LIMIT);
        Context context = context(tenantId, rows);
        List<SaleViews.HeldBillRow> items = new ArrayList<>();
        for (Row row : rows) {
            items.add(row(tenantId, row, context));
        }
        return new SaleViews.HeldBillList(items);
    }

    /** The stored cart with the products it names; the client quotes it again. Writes nothing. */
    @Transactional(readOnly = true)
    public SaleViews.HeldBill get(UUID tenantId, UUID userId, String rawStoreId, String rawHeldBillId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID heldBillId = PharmacyParams.pathId(rawHeldBillId);
        guard.requireSale(caller, storeId, StoreCapability.SELL);
        Row row = held.find(tenantId, heldBillId).filter(r -> r.storeId().equals(storeId))
                .orElseThrow(HeldBillService::notFound);
        return view(tenantId, row);
    }

    /** Holds the cart (F8). The 21st OPEN held bill of the store is 409 held-limit. 201, or 200 for a replay of the key. */
    @Transactional
    public PharmacyViews.Outcome<SaleViews.HeldBill> hold(UUID tenantId, UUID userId, String rawStoreId,
                                                          PharmacyInputs.Body<HoldInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        HoldInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        UUID me = caller.actorId();

        if (in.clientKey() != null) {
            ClientRequestRepository.Taken own = clients.own(tenantId, me, in.clientKey()).orElse(null);
            if (own != null) {
                if (!OPERATION.equals(own.operation())) {
                    throw PharmacyProblemException.clientKeyReused();
                }
                Row first = held.find(tenantId, own.subjectId()).filter(r -> r.storeId().equals(storeId))
                        .orElseThrow(HeldBillService::notFound);
                return new PharmacyViews.Outcome<>(view(tenantId, first), false);
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
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        pricer.requireReady(tenantId, cart);
        long heldNet = pricer.price(caller, store, cart, false).math().netPayable();

        UUID id = UUID.randomUUID();
        if (ClientKeys.claim(clients, tenantId, store.unitId(), me, in.clientKey(), OPERATION, id, false) != null) {
            return hold(tenantId, userId, rawStoreId, body); // L15: the other tap won; answer as its replay
        }
        // the count of OPEN held bills is taken under the store row lock
        stores.findForUpdate(tenantId, storeId).filter(Store::active).orElseThrow(HeldBillService::notFound);
        Row replaced = null;
        if (in.replacesHeldBillId() != null) {
            replaced = held.findForUpdate(tenantId, in.replacesHeldBillId()).filter(r -> r.storeId().equals(storeId))
                    .orElseThrow(HeldBillService::notFound);
            if (!"OPEN".equals(replaced.status())) {
                throw PharmacyProblemException.changed(null);
            }
        }
        int open = held.countOpen(tenantId, storeId) - (replaced == null ? 0 : 1);
        if (open >= LIMIT) {
            throw PharmacyProblemException.saleConflict("held-limit", "Too many held bills", HELD_LIMIT);
        }
        String json;
        try {
            json = mapper.writeValueAsString(cart.toInput());
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A cart could not be written", unwritable);
        }
        held.insert(tenantId, new HeldBillRepository.NewHeld(id, store.unitId(), storeId, me, json, cart.discountRequestId(),
                heldNet));
        if (replaced != null) {
            held.close(tenantId, replaced.id(), replaced.version(), "REPLACED", me);
            events.insert(tenantId, store.unitId(), DOC_TYPE, replaced.id(), "REPLACED", storeId, me, null, null, null, null,
                    null);
        }
        events.insert(tenantId, store.unitId(), DOC_TYPE, id, "HELD", storeId, me, null, null, null, cart.lines().size(), null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("heldBillId", id.toString());
        after.put("lines", cart.lines().size());
        auditLog.record("PHARMACY_BILL_HELD", ENTITY_TYPE, id, null, after);
        log.info("Bill held heldBillId={} lines={}", id, cart.lines().size());
        return new PharmacyViews.Outcome<>(view(tenantId, held.find(tenantId, id).orElseThrow()), true);
    }

    /** Status DISCARDED (kept, hidden). Not OPEN is 409 changed; a stale version is 409 changed with the current held bill. */
    @Transactional
    public SaleViews.HeldBill discard(UUID tenantId, UUID userId, String rawStoreId, String rawHeldBillId,
                                      PharmacyInputs.Body<PharmacyInputs.VersionInput> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        UUID heldBillId = PharmacyParams.pathId(rawHeldBillId);
        PharmacyInputs.VersionInput in = body.read();
        Store store = guard.requireSale(caller, storeId, StoreCapability.SELL);
        Integer version = PharmacyParams.wholeNumber(in.version(), 0, Integer.MAX_VALUE);
        if (version == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("version", UnitService.RELOAD)));
        }
        Row row = held.findForUpdate(tenantId, heldBillId).filter(r -> r.storeId().equals(storeId))
                .orElseThrow(HeldBillService::notFound);
        if (!"OPEN".equals(row.status()) || version != row.version()) {
            throw PharmacyProblemException.changed(view(tenantId, row));
        }
        UUID me = caller.actorId();
        if (!held.close(tenantId, heldBillId, row.version(), "DISCARDED", me)) {
            throw PharmacyProblemException.changed(view(tenantId, held.find(tenantId, heldBillId).orElseThrow()));
        }
        discounts.withdrawForDiscard(tenantId, caller, store, heldBillId);
        events.insert(tenantId, store.unitId(), DOC_TYPE, heldBillId, "DISCARDED", storeId, me, null, null, null, null, null);
        auditLog.record("PHARMACY_HELD_BILL_DISCARDED", ENTITY_TYPE, heldBillId, null,
                Map.of("heldBillId", heldBillId.toString()));
        log.info("Held bill discarded heldBillId={}", heldBillId);
        return view(tenantId, held.find(tenantId, heldBillId).orElseThrow());
    }

    // ---- views ----

    private record Context(Map<UUID, TeamMember> members, Map<UUID, CustomerRepository.Row> customers,
                           Map<UUID, SaleSupport.PatientCard> patients, Map<UUID, ProductRepository.Full> products,
                           Map<UUID, CartInput> carts) {
    }

    private Context context(UUID tenantId, List<Row> rows) {
        Set<UUID> people = new HashSet<>();
        Set<UUID> customerIds = new HashSet<>();
        Set<UUID> patientIds = new HashSet<>();
        Set<UUID> productIds = new HashSet<>();
        Map<UUID, CartInput> carts = new LinkedHashMap<>();
        for (Row row : rows) {
            people.add(row.holder());
            CartInput cart = cart(row);
            carts.put(row.id(), cart);
            if (cart.buyer() != null && cart.buyer().customerId() != null) {
                customerIds.add(cart.buyer().customerId());
            }
            if (cart.buyer() != null && cart.buyer().patientId() != null) {
                patientIds.add(cart.buyer().patientId());
            }
            if (cart.lines() != null) {
                for (CartLineInput l : cart.lines()) {
                    productIds.add(l.productId());
                }
            }
        }
        Map<UUID, CustomerRepository.Row> customerRows = customers.findAll(tenantId, customerIds);
        customerRows.values().forEach(c -> {
            if (c.patientId() != null) {
                patientIds.add(c.patientId());
            }
        });
        Map<UUID, SaleSupport.PatientCard> cards = Map.of();
        try {
            cards = support.patientCards(tenantId, patientIds, false);
        } catch (PharmacyProblemException unreachable) {
            // a list never fails for a name: the label then says "Patient"
        }
        return new Context(support.members(tenantId, people), customerRows, cards, products.findAll(tenantId, productIds), carts);
    }

    private SaleViews.HeldBillRow row(UUID tenantId, Row row, Context ctx) {
        CartInput cart = ctx.carts().get(row.id());
        boolean scheduled = false;
        for (CartLineInput l : cart.lines() == null ? List.<CartLineInput>of() : cart.lines()) {
            ProductRepository.Full full = ctx.products().get(l.productId());
            scheduled |= full != null && full.row().schedule() != null && !"NONE".equals(full.row().schedule());
        }
        return new SaleViews.HeldBillRow(row.id(), support.person(row.holder(), ctx.members()), row.createdAt(),
                cart.lines() == null ? 0 : cart.lines().size(), minutesSince(row.createdAt()), buyerLabel(cart, ctx), scheduled,
                discounts.heldState(tenantId, cart.discountRequestId(), row.holder()));
    }

    private SaleViews.HeldBill view(UUID tenantId, Row row) {
        Context ctx = context(tenantId, List.of(row));
        SaleViews.HeldBillRow base = row(tenantId, row, ctx);
        CartInput cart = ctx.carts().get(row.id());
        Set<UUID> named = new LinkedHashSet<>();
        for (CartLineInput l : cart.lines() == null ? List.<CartLineInput>of() : cart.lines()) {
            named.add(l.productId());
            if (l.prescribedProductId() != null) {
                named.add(l.prescribedProductId());
            }
        }
        Map<UUID, ProductRepository.Full> found = named.equals(ctx.products().keySet()) ? ctx.products()
                : products.findAll(tenantId, named);
        List<StockViews.ProductRef> refs = new ArrayList<>();
        for (UUID id : named) {
            ProductRepository.Full full = found.get(id);
            if (full != null) {
                refs.add(IndentService.productRef(full.row()));
            }
        }
        return new SaleViews.HeldBill(base.id(), base.heldBy(), base.heldAt(), base.itemCount(), base.ageMinutes(),
                base.buyerLabel(), base.hasScheduledLine(), base.discountState(), row.status(), cart, refs,
                row.heldNetPaise(), row.version());
    }

    private String buyerLabel(CartInput cart, Context ctx) {
        if (cart.buyer() == null) {
            return "Walk-in";
        }
        UUID patientId = cart.buyer().patientId();
        if (cart.buyer().customerId() != null) {
            CustomerRepository.Row c = ctx.customers().get(cart.buyer().customerId());
            if (c == null) {
                return "Walk-in";
            }
            if (c.patientId() != null) {
                patientId = c.patientId();
            } else {
                return SaleRules.maskName(c.name());
            }
        }
        if (patientId != null) {
            SaleSupport.PatientCard card = ctx.patients().get(patientId);
            return card == null ? "Patient" : SaleRules.maskName(card.name());
        }
        return "Walk-in";
    }

    private CartInput cart(Row row) {
        try {
            return mapper.readValue(row.cartJson(), CartInput.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A held cart could not be read", unreadable);
        }
    }

    private int minutesSince(Instant at) {
        return (int) Math.max(0, Duration.between(at, time.now()).toMinutes());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(SaleSupport.NOT_FOUND);
    }
}
