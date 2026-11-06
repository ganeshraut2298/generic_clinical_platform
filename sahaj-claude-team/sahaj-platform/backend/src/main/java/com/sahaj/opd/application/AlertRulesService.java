package com.sahaj.opd.application;

import com.sahaj.opd.application.AlertRuleRepository.AlertRuleRow;
import com.sahaj.opd.application.AlertRulesView.Options;
import com.sahaj.opd.application.AlertRulesView.Suggestion;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-18: "if this happens, then do that" rules for the patient flow. This phase only stores them;
 * the engine that checks them is a later release. Any clinic role reads, only the owner writes.
 * A clinic that never saved rules sees the five starter rules (id null); the first write saves them
 * once and records a marker, so deleting every rule never brings them back.
 */
@Service
public class AlertRulesService {

    static final List<String> WHEN = List.of("Token", "Vitals", "Consultation", "Billing", "Case paper print",
            "Discount", "Follow-up");
    static final List<String> COND = List.of("not done in 10 min", "waiting over 30 min", "waiting over 45 min",
            "bill not paid", "above role limit", "missed by 2 days", "unpaid at day closing");
    static final List<String> THEN = List.of("Alert nurse", "Alert owner", "Flag red to the doctor", "Block print",
            "Needs owner approval", "Send WhatsApp reminder", "Move up in the queue");

    static final List<AlertRuleRow> STARTER = List.of(
            new AlertRuleRow(null, "Vitals", "not done in 10 min", "Alert nurse", true),
            new AlertRuleRow(null, "Case paper print", "bill not paid", "Block print", true),
            new AlertRuleRow(null, "Discount", "above role limit", "Needs owner approval", true),
            new AlertRuleRow(null, "Follow-up", "missed by 2 days", "Send WhatsApp reminder", true),
            new AlertRuleRow(null, "Consultation", "waiting over 30 min", "Alert owner", false));

    static final List<Suggestion> SUGGESTIONS = List.of(
            new Suggestion(0, "Vitals", "BP 160/100 or above, or SpO2 below 94%", "Flag red to the doctor",
                    "Sick patients are seen first"),
            new Suggestion(1, "Token", "patient is 75+ or pregnant", "Move up in the queue",
                    "Expected courtesy in most Indian OPDs"),
            new Suggestion(2, "Consultation", "waiting over 45 min", "Send WhatsApp \"doctor delayed\" message",
                    "Fewer walk-outs and fewer arguments at the counter"),
            new Suggestion(3, "Billing", "unpaid at day closing", "Alert owner",
                    "Every visit is settled before the cash is counted"),
            new Suggestion(4, "Follow-up", "after the free window", "Charge full fee, tell front desk",
                    "Ends follow-up fee disputes"));

    private static final String ENTITY_TYPE = "ALERT_RULE";
    private static final String EXISTS = "That rule already exists.";

    private final AlertRuleRepository repository;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;

    public AlertRulesService(AlertRuleRepository repository, OwnerGuard ownerGuard, AuditLog auditLog) {
        this.repository = repository;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
    }

    @Transactional(readOnly = true)
    public AlertRulesView get(UUID tenantId) {
        List<AlertRuleRow> rules = repository.list(tenantId);
        if (rules.isEmpty() && !repository.isSeeded(tenantId)) {
            rules = STARTER;
        }
        return view(rules);
    }

    @Transactional
    public AlertRulesView saveStarter(UUID tenantId, UUID actorUserId) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        materialiseStarter(tenantId, actorUserId);
        return view(repository.list(tenantId));
    }

    @Transactional
    public AlertRulesView add(UUID tenantId, UUID actorUserId, String when, String cond, String then) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        validate(when, cond, then);
        materialiseStarter(tenantId, actorUserId);
        insertOrConflict(tenantId, actorUserId, when, cond, then);
        return view(repository.list(tenantId));
    }

    @Transactional
    public AlertRulesView addSuggestion(UUID tenantId, UUID actorUserId, int index) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (index < 0 || index >= SUGGESTIONS.size()) {
            throw new NotFoundException("Suggestion not found");
        }
        Suggestion suggestion = SUGGESTIONS.get(index);
        // Suggestions are one per (when, cond) on purpose: the database is unique on when+cond+then, so this
        // wider check stops "Token / always" being added twice with a different action.
        materialiseStarter(tenantId, actorUserId);
        if (isAdded(repository.list(tenantId), suggestion.when(), suggestion.cond())) {
            throw new ConflictException("alert-rule-exists", "That rule already exists", EXISTS);
        }
        insertOrConflict(tenantId, actorUserId, suggestion.when(), suggestion.cond(), suggestion.then());
        return view(repository.list(tenantId));
    }

    @Transactional
    public AlertRulesView setEnabled(UUID tenantId, UUID actorUserId, UUID id, Boolean enabled) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        if (enabled == null) {
            throw new InvalidInputException("enabled", "Choose on or off");
        }
        materialiseStarter(tenantId, actorUserId);
        AlertRuleRow before = repository.find(tenantId, id).orElseThrow(() -> new NotFoundException("Rule not found"));
        if (repository.setEnabled(tenantId, id, enabled, actorUserId) == 0) {
            throw new NotFoundException("Rule not found");
        }
        auditLog.record("ALERT_RULE_TOGGLED", ENTITY_TYPE, id, snapshot(before),
                snapshot(new AlertRuleRow(id, before.when(), before.cond(), before.then(), enabled)));
        return view(repository.list(tenantId));
    }

    @Transactional
    public AlertRulesView delete(UUID tenantId, UUID actorUserId, UUID id) {
        ownerGuard.requireOwner(tenantId, actorUserId);
        materialiseStarter(tenantId, actorUserId);
        AlertRuleRow before = repository.find(tenantId, id).orElseThrow(() -> new NotFoundException("Rule not found"));
        if (repository.delete(tenantId, id) == 0) {
            throw new NotFoundException("Rule not found");
        }
        auditLog.record("ALERT_RULE_DELETED", ENTITY_TYPE, id, snapshot(before), null);
        return view(repository.list(tenantId));
    }

    /** Saves the five starter rules once; only the call that creates the marker writes them. */
    private void materialiseStarter(UUID tenantId, UUID actorUserId) {
        if (!repository.markSeeded(tenantId)) {
            return;
        }
        for (AlertRuleRow starter : STARTER) {
            UUID id = repository.insert(tenantId, starter.when(), starter.cond(), starter.then(), starter.enabled(),
                    actorUserId).orElse(null);
            if (id != null) {
                auditLog.record("ALERT_RULE_ADDED", ENTITY_TYPE, id, null, snapshot(starter));
            }
        }
    }

    private void insertOrConflict(UUID tenantId, UUID actorUserId, String when, String cond, String then) {
        UUID id = repository.insert(tenantId, when, cond, then, true, actorUserId)
                .orElseThrow(() -> new ConflictException("alert-rule-exists", "That rule already exists", EXISTS));
        auditLog.record("ALERT_RULE_ADDED", ENTITY_TYPE, id, null, snapshot(new AlertRuleRow(id, when, cond, then, true)));
    }

    private static void validate(String when, String cond, String then) {
        if (when == null || !WHEN.contains(when)) {
            throw new InvalidInputException("when", "Choose what the rule watches");
        }
        if (cond == null || !COND.contains(cond)) {
            throw new InvalidInputException("cond", "Choose when the rule applies");
        }
        if (then == null || !THEN.contains(then)) {
            throw new InvalidInputException("then", "Choose what should happen");
        }
    }

    private static boolean isAdded(List<AlertRuleRow> rules, String when, String cond) {
        for (AlertRuleRow rule : rules) {
            if (rule.when().equals(when) && rule.cond().equals(cond)) {
                return true;
            }
        }
        return false;
    }

    private static AlertRulesView view(List<AlertRuleRow> rules) {
        List<Suggestion> open = new ArrayList<>();
        for (Suggestion s : SUGGESTIONS) {
            if (!isAdded(rules, s.when(), s.cond())) {
                open.add(s);
            }
        }
        return new AlertRulesView(rules, open, new Options(WHEN, COND, THEN));
    }

    private static Map<String, Object> snapshot(AlertRuleRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("when", row.when());
        values.put("cond", row.cond());
        values.put("then", row.then());
        values.put("enabled", row.enabled());
        return values;
    }
}
