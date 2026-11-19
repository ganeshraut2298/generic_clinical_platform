package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three ready presets (OQ-78-28, DEC-062 Q4; docs/api/pharmacy.yaml POST /approval-rules/preset). Sahaj ships no rupee or
 * percentage limit: a preset that has an "owner above the limit" step takes the limit from the owner, or the owner says "no
 * amount condition" and the step becomes ALWAYS.
 *
 * <ul>
 *   <li>LEAN: no level for anything except the purchase order (owner above the limit).</li>
 *   <li>STANDARD: the store approver always for indent, stock transfer, write-off and vendor return; the owner above the
 *       limit for purchase order, rate rise, stock adjustment, discount and sales return.</li>
 *   <li>STRICT: the store approver always, then the owner above the limit, for every type.</li>
 * </ul>
 * Escalation defaults for every preset: indent 2 hours and 30 minutes urgent, stock transfer 24 hours.
 */
public final class ApprovalPresets {

    /** The limit of one owner step; both null means no amount condition (ALWAYS). */
    public record Limit(Long amountPaise, BigDecimal percent) {

        public static final Limit NONE = new Limit(null, null);

        ApprovalCondition condition() {
            if (amountPaise != null) {
                return ApprovalCondition.aboveAmount(amountPaise);
            }
            return percent != null ? ApprovalCondition.abovePercent(percent) : ApprovalCondition.always();
        }
    }

    private static final Set<TransactionType> STORE_APPROVER_ALWAYS = EnumSet.of(TransactionType.INDENT,
            TransactionType.STOCK_TRANSFER, TransactionType.WRITE_OFF, TransactionType.VENDOR_RETURN);

    private ApprovalPresets() {
    }

    /** The types of this preset that have an "owner above the limit" step, so need a limit (or "no amount condition"). */
    public static Set<TransactionType> limitTypes(RulePreset preset) {
        Set<TransactionType> types = EnumSet.noneOf(TransactionType.class);
        for (TransactionType type : TransactionType.values()) {
            if (ownerStep(preset, type)) {
                types.add(type);
            }
        }
        return types;
    }

    private static boolean ownerStep(RulePreset preset, TransactionType type) {
        switch (preset) {
            case LEAN:
                return type == TransactionType.PURCHASE_ORDER;
            case STANDARD:
                return !STORE_APPROVER_ALWAYS.contains(type);
            case STRICT:
                return true;
            default:
                return false;
        }
    }

    private static boolean storeApproverStep(RulePreset preset, TransactionType type) {
        return preset == RulePreset.STRICT || preset == RulePreset.STANDARD && STORE_APPROVER_ALWAYS.contains(type);
    }

    /** What a new unit starts with: STANDARD with no amounts (the owner steps are ALWAYS). */
    public static List<ApprovalRule> defaults() {
        return build(RulePreset.STANDARD, Map.of());
    }

    /** The nine rules of a preset in screen order. A type missing from {@code limits} has no amount condition. */
    public static List<ApprovalRule> build(RulePreset preset, Map<TransactionType, Limit> limits) {
        List<ApprovalRule> rules = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            List<ApprovalLevel> levels = new ArrayList<>();
            if (storeApproverStep(preset, type)) {
                levels.add(new ApprovalLevel(1, ApproverKind.STORE_APPROVER, ApprovalCondition.always()));
            }
            if (ownerStep(preset, type)) {
                Limit limit = limits.getOrDefault(type, Limit.NONE);
                levels.add(new ApprovalLevel(levels.size() + 1, ApproverKind.OWNER, limit.condition()));
            }
            Integer hours = type == TransactionType.INDENT ? Integer.valueOf(2)
                    : type == TransactionType.STOCK_TRANSFER ? Integer.valueOf(24) : null;
            Integer urgent = type == TransactionType.INDENT ? Integer.valueOf(30) : null;
            rules.add(new ApprovalRule(type, levels, hours, urgent));
        }
        return rules;
    }
}
