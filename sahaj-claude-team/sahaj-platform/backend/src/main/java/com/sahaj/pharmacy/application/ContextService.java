package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyViews.CallerView;
import com.sahaj.pharmacy.application.PharmacyViews.Context;
import com.sahaj.pharmacy.application.PharmacyViews.FixedRules;
import com.sahaj.pharmacy.application.PharmacyViews.MyStore;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreRole;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.RoleLabels;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-1 and 78-4: the one capability answer of the module (ADR-028 D5, {@code GET /context}) and the remembered
 * working store. The screens show only what {@code capabilities} lists. Everything here is read again on every call from
 * the membership and the mappings; nothing is cached, so a store switched off or a person removed a moment ago is gone
 * from the next answer.
 */
@Service
public class ContextService {

    static final String SETUP_VIEW = "SETUP_VIEW";
    static final String SETUP_EDIT = "SETUP_EDIT";
    static final String OPENING_STOCK_UPLOAD = "OPENING_STOCK_UPLOAD";
    static final String OPENING_STOCK_APPROVE = "OPENING_STOCK_APPROVE";
    static final String INVENTORY = "INVENTORY";
    static final String APPROVALS_INBOX = "APPROVALS_INBOX";
    static final String PURCHASE = "PURCHASE";
    static final String STOCK_REPORTS = "STOCK_REPORTS";

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final UnitService units;
    private final UnitRepository unitRepository;
    private final StaffPreferenceRepository preferences;
    private final StoreRepository storeRows;

    public ContextService(PharmacyGate gate, StoreAccessGuard guard, UnitService units, UnitRepository unitRepository,
                          StaffPreferenceRepository preferences, StoreRepository storeRows) {
        this.gate = gate;
        this.guard = guard;
        this.units = units;
        this.unitRepository = unitRepository;
        this.preferences = preferences;
        this.storeRows = storeRows;
    }

    @Transactional(readOnly = true)
    public Context get(UUID tenantId, UUID userId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        return build(caller, null);
    }

    /** For tests: the body is already read. */
    Context setWorkingStore(UUID tenantId, UUID userId, PharmacyInputs.WorkingStore input) {
        return setWorkingStore(tenantId, userId, PharmacyInputs.Body.of(input));
    }

    /**
     * Saves the working store of the caller (convenience only; every store operation still checks its own path). The store
     * must be one of the caller's stores: unknown, another clinic's, switched off and not mapped all answer 404 and the
     * remembered store does not change. Not audited. The body is read after the gate (module off 403, no access 403), so a
     * bad body never beats a 403.
     */
    @Transactional
    public Context setWorkingStore(UUID tenantId, UUID userId, PharmacyInputs.Body<PharmacyInputs.WorkingStore> body) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        PharmacyInputs.WorkingStore input = body.read();
        if (input.storeId() == null) {
            throw new InvalidFieldsException(List.of(new FieldMessage("storeId", "Choose a store")));
        }
        PharmacyCaller.Mapping mapped = guard.requireMapped(caller, input.storeId());
        preferences.save(tenantId, mapped.store().unitId(), caller.membershipId(), mapped.store().id(), userId);
        return build(caller, mapped.store().id());
    }

    /** {@code chosen} is the store just saved; null means "work it out" (remembered store, or the only one). */
    private Context build(PharmacyCaller caller, UUID chosen) {
        Unit unit = unitRepository.find(caller.tenantId()).orElse(null);
        PharmacyViews.IssuesFrom central = storeRows.activeCentral(caller.tenantId())
                .map(c -> new PharmacyViews.IssuesFrom(c.id(), c.name())).orElse(null);
        List<MyStore> stores = caller.mappings().stream()
                .sorted(Comparator.comparing((PharmacyCaller.Mapping m) -> !m.store().type().isCentral())
                        .thenComparing(m -> m.store().name().toLowerCase(Locale.ROOT)))
                .map(m -> new MyStore(m.store().id(), m.store().name(), m.store().code(), m.store().type(),
                        m.staff().storeRole(), m.effective(), m.access(), m.store().type().isCentral() ? null : central))
                .toList();

        UUID working = chosen;
        if (working == null) {
            working = preferences.lastStore(caller.tenantId(), caller.membershipId())
                    .filter(id -> stores.stream().anyMatch(s -> s.storeId().equals(id))).orElse(null);
        }
        if (working == null && stores.size() == 1) {
            working = stores.get(0).storeId();
        }
        boolean mustPick = working == null && stores.size() >= 2;
        return new Context(units.header(caller.tenantId(), unit),
                new CallerView(caller.membershipId(), caller.name(), caller.teamRole(),
                        RoleLabels.of(caller.teamRole()), setupAccess(caller)),
                capabilities(caller), stores, working, mustPick, FixedRules.ON);
    }

    private static String setupAccess(PharmacyCaller caller) {
        if (caller.meets(AccessLevel.SETUP_EDIT)) {
            return "EDIT";
        }
        return caller.meets(AccessLevel.SETUP_VIEW) ? "VIEW" : "NONE";
    }

    /**
     * What the screens may show. OPENING_STOCK_UPLOAD when the caller may upload in at least one store (always for the
     * owner); OPENING_STOCK_APPROVE for the owner only (a different owner approves, ADR-028 D7). Later phases add values.
     */
    static List<String> capabilities(PharmacyCaller caller) {
        List<String> values = new ArrayList<>();
        if (caller.meets(AccessLevel.SETUP_VIEW)) {
            values.add(SETUP_VIEW);
        }
        if (caller.meets(AccessLevel.SETUP_EDIT)) {
            values.add(SETUP_EDIT);
        }
        if (caller.isOwner() || caller.mappings().stream().anyMatch(m -> m.access().uploadOpeningStock())) {
            values.add(OPENING_STOCK_UPLOAD);
        }
        if (caller.isOwner()) {
            values.add(OPENING_STOCK_APPROVE);
        }
        // SAH-79 (contract 0.2.0; D-4): the Inventory tab, the approvals inbox, buying and the stock reports.
        boolean reads = caller.isOwner() || caller.mappings().stream().anyMatch(m -> m.access().readStock());
        if (reads) {
            values.add(INVENTORY);
        }
        if (caller.isOwner() || caller.mappings().stream().anyMatch(m -> m.access().approve())) {
            values.add(APPROVALS_INBOX);
        }
        if (caller.isOwner() || caller.mappings().stream().anyMatch(m -> m.effective() == StoreRole.CENTRAL_STORE_USER)) {
            values.add(PURCHASE);
        }
        if (reads) {
            values.add(STOCK_REPORTS);
        }
        return values;
    }
}
