package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyMasterInputs.ReasonAdd;
import com.sahaj.pharmacy.application.PharmacyMasterViews.Reason;
import com.sahaj.pharmacy.application.ReasonRepository.OwnRow;
import com.sahaj.pharmacy.application.ReasonRepository.SeededRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.pharmacy.domain.Unit;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-10, reason lists (docs/api/pharmacy.yaml 0.2.0: eleven lists). Seeded reasons are global rows present for every unit;
 * the unit's own reasons follow at the end in the order added. A reason is switched off or on, never renamed or deleted, and
 * stays on old records. The last active reason of the Damage list and the "Short / not received" reason of a receipt cannot be
 * switched off. Every write answers the whole set of lists.
 */
@Service
public class ReasonService {

    static final String LABEL_MESSAGE = "Enter the reason (up to 40 characters)";
    static final int LABEL_MAX = 40;
    private static final String ENTITY_TYPE = "PHARMACY_REASON";

    private final PharmacyGate gate;
    private final UnitService units;
    private final ReasonRepository reasons;
    private final AuditLog auditLog;

    public ReasonService(PharmacyGate gate, UnitService units, ReasonRepository reasons, AuditLog auditLog) {
        this.gate = gate;
        this.units = units;
        this.reasons = reasons;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public PharmacyMasterViews.ReasonLists list(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        return lists(tenantId);
    }

    /** For tests: the body is already read. */
    PharmacyMasterViews.ReasonLists add(UUID tenantId, UUID userId, String rawListKey, ReasonAdd input) {
        return add(tenantId, userId, rawListKey, PharmacyInputs.Body.of(input));
    }

    /** Owner only. The new reason is a chip at the end of the list. An unknown list is 400 bad-request, read before the body. */
    @Transactional
    public PharmacyMasterViews.ReasonLists add(UUID tenantId, UUID userId, String rawListKey,
                                               PharmacyInputs.Body<ReasonAdd> body) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        ReasonList list = list(rawListKey);
        ReasonAdd in = body.read();
        String label = in.label() == null ? "" : in.label().trim();
        if (label.isEmpty() || label.length() > LABEL_MAX) {
            throw new InvalidFieldsException(List.of(new FieldMessage("label", LABEL_MESSAGE)));
        }
        Unit unit = units.ensureUnit(tenantId, userId);
        String lower = label.toLowerCase(Locale.ROOT);
        boolean exists = reasons.seeded(tenantId).stream()
                .anyMatch(r -> r.listKey().equals(list.key()) && r.label().toLowerCase(Locale.ROOT).equals(lower))
                || reasons.own(tenantId).stream()
                .anyMatch(r -> r.listKey().equals(list.key()) && r.label().toLowerCase(Locale.ROOT).equals(lower));
        if (exists) {
            throw new ConflictException("reason-exists", "Reason already in the list", "That reason is already in the list");
        }
        String key = "c-" + UUID.randomUUID();
        UUID id = reasons.insertOwn(tenantId, unit.id(), list.key(), key, label, reasons.nextOwnSort(tenantId, list.key()),
                userId);
        auditLog.record("PHARMACY_REASON_ADDED", ENTITY_TYPE, id, null, values(list, key, label));
        return lists(tenantId);
    }

    /** Owner only. Already off answers 200 and writes nothing. */
    @Transactional
    public PharmacyMasterViews.ReasonLists switchOff(UUID tenantId, UUID userId, String rawListKey, String reasonKey) {
        return switchTo(tenantId, userId, rawListKey, reasonKey, false);
    }

    /** Owner only. Already on answers 200 and writes nothing. */
    @Transactional
    public PharmacyMasterViews.ReasonLists switchOn(UUID tenantId, UUID userId, String rawListKey, String reasonKey) {
        return switchTo(tenantId, userId, rawListKey, reasonKey, true);
    }

    private PharmacyMasterViews.ReasonLists switchTo(UUID tenantId, UUID userId, String rawListKey, String reasonKey,
                                                     boolean on) {
        gate.require(tenantId, userId, AccessLevel.SETUP_EDIT);
        ReasonList list = list(rawListKey);
        SeededRow seeded = reasonKey == null ? null : reasons.seeded(tenantId).stream()
                .filter(r -> r.listKey().equals(list.key()) && r.reasonKey().equals(reasonKey)).findFirst().orElse(null);
        OwnRow own = seeded != null || reasonKey == null ? null : reasons.own(tenantId).stream()
                .filter(r -> r.listKey().equals(list.key()) && r.reasonKey().equals(reasonKey)).findFirst().orElse(null);
        if (seeded == null && own == null) {
            throw new NotFoundException("This record was not found.");
        }
        boolean active = seeded != null ? seeded.active() : own.active();
        String label = seeded != null ? seeded.label() : own.label();
        if (!on) {
            refuseIfKept(tenantId, list, reasonKey, active);
        }
        if (active == on) {
            return lists(tenantId);
        }
        Unit unit = units.ensureUnit(tenantId, userId);
        if (seeded != null) {
            reasons.setSeededActive(tenantId, unit.id(), reasonKey, on, userId);
        } else {
            reasons.setOwnActive(tenantId, own.id(), on, userId);
        }
        auditLog.record(on ? "PHARMACY_REASON_SWITCHED_ON" : "PHARMACY_REASON_SWITCHED_OFF", ENTITY_TYPE,
                own == null ? null : own.id(), Map.of("active", !on), values(list, reasonKey, label, on));
        return lists(tenantId);
    }

    /** A fixed reason (receipt-reject.short, the return-condition keys) and the last active Damage reason stay on. */
    private void refuseIfKept(UUID tenantId, ReasonList list, String reasonKey, boolean active) {
        if (ReasonList.isFixed(reasonKey)) {
            throw new ConflictException("last-active-reason", "Reason cannot be switched off", "This reason is fixed.");
        }
        if (list == ReasonList.DAMAGE && active) {
            long activeCount = lists(tenantId).lists().stream().filter(l -> l.listKey().equals(list.key()))
                    .flatMap(l -> l.reasons().stream()).filter(Reason::active).count();
            if (activeCount <= 1) {
                throw new ConflictException("last-active-reason", "Keep one reason", "Keep at least one damage reason.");
            }
        }
    }

    // ---- views ----

    /** The lists in screen order: seeded reasons by their order, then the unit's own in the order added. */
    private PharmacyMasterViews.ReasonLists lists(UUID tenantId) {
        Map<String, List<Reason>> byList = new LinkedHashMap<>();
        for (ReasonList list : ReasonList.values()) {
            byList.put(list.key(), new ArrayList<>());
        }
        for (SeededRow r : reasons.seeded(tenantId)) {
            List<Reason> items = byList.get(r.listKey());
            if (items != null) {
                items.add(new Reason(r.reasonKey(), r.label(), true, r.active(), ReasonList.isFixed(r.reasonKey())));
            }
        }
        for (OwnRow r : reasons.own(tenantId)) {
            List<Reason> items = byList.get(r.listKey());
            if (items != null) {
                items.add(new Reason(r.reasonKey(), r.label(), false, r.active(), ReasonList.isFixed(r.reasonKey())));
            }
        }
        List<PharmacyMasterViews.ReasonListView> views = new ArrayList<>();
        for (ReasonList list : ReasonList.values()) {
            views.add(new PharmacyMasterViews.ReasonListView(list.key(), list.label(), byList.get(list.key())));
        }
        return new PharmacyMasterViews.ReasonLists(views);
    }

    private static ReasonList list(String rawListKey) {
        return ReasonList.fromKey(rawListKey).orElseThrow(PharmacyProblemException::badRequest);
    }

    private static Map<String, Object> values(ReasonList list, String key, String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("listKey", list.key());
        map.put("reasonKey", key);
        map.put("label", label);
        return map;
    }

    private static Map<String, Object> values(ReasonList list, String key, String label, boolean active) {
        Map<String, Object> map = values(list, key, label);
        map.put("active", active);
        return map;
    }
}
