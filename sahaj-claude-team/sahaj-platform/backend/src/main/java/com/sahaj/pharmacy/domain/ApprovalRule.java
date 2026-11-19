package com.sahaj.pharmacy.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The rule of one transaction type: up to two levels (none means no approval) and the escalation values, which are only
 * stored (nothing acts on them until a later timer story, OQ-78-29). {@code urgentEscalateAfterMinutes} belongs to INDENT only.
 */
public record ApprovalRule(TransactionType type, List<ApprovalLevel> levels, Integer escalateAfterHours,
                           Integer urgentEscalateAfterMinutes) {

    public ApprovalRule {
        levels = List.copyOf(levels);
    }

    /** One line for the audit row. */
    public String describe() {
        String text = levels.isEmpty() ? "NO_APPROVAL"
                : levels.stream().map(ApprovalLevel::describe).collect(Collectors.joining("; then "));
        if (escalateAfterHours != null) {
            text += " | escalate " + escalateAfterHours + "h";
        }
        if (urgentEscalateAfterMinutes != null) {
            text += " | urgent " + urgentEscalateAfterMinutes + "m";
        }
        return text;
    }
}
