package com.sahaj.opd.application;

import com.sahaj.opd.application.CasePaperRepository.PaperRow;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-7 AC11: how the case paper prints: size, second language, pre-printed letterhead, medicine
 * names in capitals and which sections show. Any clinic user reads; only the owner changes it.
 * Medicines (Rx) is always on; the doctor's registration number always prints and is not stored.
 * Until the first save the clinic sees the defaults (A5, English only, letterhead off, capitals off,
 * every section on except past history).
 */
@Service
public class CasePaperService {

    /** Section keys in the order of the mockup. */
    static final List<String> SECTION_KEYS =
            List.of("complaints", "vitals", "history", "exam", "diagnosis", "inv", "rx", "advice", "next");

    static final PaperRow DEFAULTS = new PaperRow("A5", null, false, false, defaultSections());

    private static final Set<String> SIZES = Set.of("A5", "A4");
    private static final Set<String> LANGUAGES = Set.of("mr", "hi");
    private static final String ENTITY_TYPE = "CASE_PAPER_SETTINGS";

    private final CasePaperRepository repository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public CasePaperService(CasePaperRepository repository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.repository = repository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public PaperRow get(UUID tenantId) {
        return repository.find(tenantId).orElse(DEFAULTS);
    }

    @Transactional
    public PaperRow save(UUID tenantId, UUID actorUserId, CasePaperInput input) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (input.size() == null || !SIZES.contains(input.size())) {
            throw new InvalidInputException("size", "Choose A5 or A4");
        }
        String language = input.secondLanguage() == null || input.secondLanguage().isBlank()
                ? null : input.secondLanguage();
        if (language != null && !LANGUAGES.contains(language)) {
            throw new InvalidInputException("secondLanguage", "Choose English only, मराठी or हिंदी");
        }
        if (input.preprinted() == null) {
            throw new InvalidInputException("preprinted", "Choose whether you use a pre-printed letterhead");
        }
        if (input.caps() == null) {
            throw new InvalidInputException("caps", "Choose whether medicine names are in capitals");
        }
        PaperRow after = new PaperRow(input.size(), language, input.preprinted(), input.caps(),
                sections(input.sections()));

        PaperRow before = repository.find(tenantId).orElse(null);
        if (after.equals(before)) {
            return before;
        }
        repository.upsert(tenantId, after, actorUserId);
        auditLog.record("CASE_PAPER_SETTINGS_UPDATED", ENTITY_TYPE, tenantId, snapshot(before == null ? DEFAULTS : before),
                snapshot(after));
        return after;
    }

    /** Every known section, in mockup order: one that is not sent keeps its default; {@code rx} cannot be switched off. */
    private static Map<String, Boolean> sections(Map<String, Boolean> given) {
        if (given == null) {
            throw new InvalidInputException("sections", "Choose which sections show");
        }
        Map<String, Boolean> clean = defaultSections();
        for (Map.Entry<String, Boolean> entry : given.entrySet()) {
            if (!SECTION_KEYS.contains(entry.getKey()) || entry.getValue() == null) {
                throw new InvalidInputException("sections", "Choose sections from the list");
            }
            clean.put(entry.getKey(), entry.getValue());
        }
        if (!clean.get("rx")) {
            throw new InvalidInputException("sections", "Medicines (Rx) cannot be switched off");
        }
        return clean;
    }

    private static Map<String, Boolean> defaultSections() {
        Map<String, Boolean> sections = new LinkedHashMap<>();
        for (String key : SECTION_KEYS) {
            sections.put(key, !key.equals("history"));
        }
        return sections;
    }

    private static Map<String, Object> snapshot(PaperRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("size", row.size());
        values.put("secondLanguage", row.secondLanguage());
        values.put("preprinted", row.preprinted());
        values.put("caps", row.caps());
        values.put("sections", new LinkedHashMap<String, Object>(row.sections()));
        return values;
    }
}
