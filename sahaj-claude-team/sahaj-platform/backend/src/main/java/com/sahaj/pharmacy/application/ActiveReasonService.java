package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.ReasonRepository.OwnRow;
import com.sahaj.pharmacy.application.ReasonRepository.SeededRow;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.ReasonList;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79 common (C-5, OQ-79-15): the active reasons of named lists for the Phase 2 screens
 * ({@code GET /reasons?lists=a,b}), and the lookups other Phase 2 services use to check a reason key and to print its
 * label. Seeded reasons come first in list order, then the unit's own in the order added; a switched-off reason is not listed.
 * Reads only.
 */
@Service
public class ActiveReasonService {

    static final String UNKNOWN_LIST = "Choose a valid list";
    private static final int MAX_LISTS = 11;

    private final PharmacyGate gate;
    private final ReasonRepository reasons;

    public ActiveReasonService(PharmacyGate gate, ReasonRepository reasons) {
        this.gate = gate;
        this.reasons = reasons;
    }

    @Transactional(readOnly = true)
    public StockViews.ActiveReasons list(UUID tenantId, UUID userId, String rawLists) {
        gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        Set<ReasonList> wanted = parse(rawLists);
        Map<String, List<StockViews.ActiveReason>> byList = new LinkedHashMap<>();
        for (ReasonList list : wanted) {
            byList.put(list.key(), new ArrayList<>());
        }
        for (SeededRow r : reasons.seeded(tenantId)) {
            List<StockViews.ActiveReason> items = byList.get(r.listKey());
            if (items != null && r.active()) {
                items.add(view(r.reasonKey(), r.label()));
            }
        }
        for (OwnRow r : reasons.own(tenantId)) {
            List<StockViews.ActiveReason> items = byList.get(r.listKey());
            if (items != null && r.active()) {
                items.add(view(r.reasonKey(), r.label()));
            }
        }
        List<StockViews.ActiveReasonList> lists = new ArrayList<>();
        byList.forEach((key, items) -> lists.add(new StockViews.ActiveReasonList(key, items)));
        return new StockViews.ActiveReasons(lists);
    }

    /** The lists named in {@code lists}: at least one, at most 11, each a known list; repeats are read once. */
    static Set<ReasonList> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("Choose at least one list");
        }
        Set<ReasonList> wanted = new LinkedHashSet<>();
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_LISTS) {
            throw invalid(UNKNOWN_LIST);
        }
        for (String part : parts) {
            wanted.add(ReasonList.fromKey(part.trim()).orElseThrow(() -> invalid(UNKNOWN_LIST)));
        }
        return wanted;
    }

    /** Whether the key is an active reason of the list (seeded and not switched off, or the unit's own and active). */
    boolean isActive(UUID tenantId, ReasonList list, String reasonKey) {
        if (reasonKey == null) {
            return false;
        }
        for (SeededRow r : reasons.seeded(tenantId)) {
            if (r.reasonKey().equals(reasonKey) && r.listKey().equals(list.key())) {
                return r.active();
            }
        }
        for (OwnRow r : reasons.own(tenantId)) {
            if (r.reasonKey().equals(reasonKey) && r.listKey().equals(list.key())) {
                return r.active();
            }
        }
        return false;
    }

    /** The label of every reason of the unit (active or not), by key: for the history and the approval screens. */
    Map<String, String> labels(UUID tenantId) {
        Map<String, String> labels = new HashMap<>();
        reasons.seeded(tenantId).forEach(r -> labels.put(r.reasonKey(), r.label()));
        reasons.own(tenantId).forEach(r -> labels.put(r.reasonKey(), r.label()));
        return labels;
    }

    private static StockViews.ActiveReason view(String key, String label) {
        return new StockViews.ActiveReason(key, label, key.endsWith(".other"), ReasonList.isFixed(key));
    }

    private static InvalidFieldsException invalid(String message) {
        return new InvalidFieldsException(List.of(new FieldMessage("lists", message)));
    }
}
