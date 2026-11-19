package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyInputs.Sent;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.ConditionIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.LevelIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.LimitIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.PresetApply;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.RuleIn;
import com.sahaj.pharmacy.application.PharmacyMasterInputs.RulesSave;
import com.sahaj.pharmacy.domain.ApprovalCondition;
import com.sahaj.pharmacy.domain.ApprovalLevel;
import com.sahaj.pharmacy.domain.ApprovalPresets;
import com.sahaj.pharmacy.domain.ApprovalRule;
import com.sahaj.pharmacy.domain.ConditionKind;
import com.sahaj.pharmacy.domain.RulePreset;
import com.sahaj.pharmacy.domain.TransactionType;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The field checks of the approval rules (docs/api/pharmacy.yaml 0.2.0, SAH-78-9). All errors are reported together with their
 * field path (for example {@code rules[3].levels[1].condition.amountPaise}); nothing typed is echoed back. Sahaj ships no
 * limit: a limit is always the owner's. The service reads these results; nothing here touches the database.
 */
final class ApprovalRuleChecks {

    /** The amount limit of a condition or a preset: ₹ 9,99,99,999 (DEC-078 item 1; the 0.1.x maximum was ten times it). */
    static final long MAX_PAISE = 9_999_999_900L;
    /** The high-value item amount has the same maximum as the amount limit (decision of the tech lead, D-2 round). */
    static final long HIGH_VALUE_MAX_PAISE = MAX_PAISE;
    static final String LIMIT_REQUIRED = "Enter the limit";
    static final String AMOUNT_RANGE = "Enter an amount up to ₹ 9,99,99,999";
    static final String PERCENT_RANGE = "Enter a % from 0 to 100";
    static final String HIGH_VALUE_REQUIRED = "Enter the high-value amount";

    /** A saved-by-hand set: the version read, the high-value amount and the nine rules in screen order. */
    record Rules(long baseVersionNo, Long highValueThresholdPaise, List<ApprovalRule> rules) {
    }

    /** A preset to apply with the owner's limits. */
    record Preset(long baseVersionNo, RulePreset preset, Map<TransactionType, ApprovalPresets.Limit> limits) {
    }

    private ApprovalRuleChecks() {
    }

    // ---- PUT /approval-rules ----

    static Rules rules(RulesSave in) {
        List<FieldMessage> errors = new ArrayList<>();
        long base = baseVersion(in.baseVersionNo(), errors);
        Long high = null;
        if (Sent.missing(in.highValueThresholdPaise())) {
            add(errors, "highValueThresholdPaise", UnitService.REQUIRED);
        } else if (Sent.valueOf(in.highValueThresholdPaise()) != null) {
            high = paise(Sent.valueOf(in.highValueThresholdPaise()), HIGH_VALUE_MAX_PAISE);
            if (high == null) {
                add(errors, "highValueThresholdPaise", AMOUNT_RANGE);
            }
        }
        Map<TransactionType, ApprovalRule> byType = new EnumMap<>(TransactionType.class);
        if (in.rules() == null) {
            add(errors, "rules", UnitService.REQUIRED);
        } else {
            for (int i = 0; i < in.rules().size(); i++) {
                RuleIn rule = in.rules().get(i);
                String path = "rules[" + i + "]";
                if (rule == null || rule.transactionType() == null) {
                    add(errors, path + ".transactionType", "Choose a transaction type");
                    continue;
                }
                if (byType.containsKey(rule.transactionType())) {
                    add(errors, path + ".transactionType", "Each transaction type can appear once");
                    continue;
                }
                ApprovalRule checked = rule(rule, path, high != null, errors);
                byType.put(rule.transactionType(), checked);
            }
            if (byType.size() != TransactionType.values().length) {
                add(errors, "rules", "Send all nine transaction types once");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        List<ApprovalRule> ordered = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            ordered.add(byType.get(type));
        }
        return new Rules(base, high, ordered);
    }

    private static ApprovalRule rule(RuleIn in, String path, boolean highValueSet, List<FieldMessage> errors) {
        TransactionType type = in.transactionType();
        List<ApprovalLevel> levels = levels(in.levels(), type, path, highValueSet, errors);
        Integer hours = null;
        if (Sent.missing(in.escalateAfterHours())) {
            add(errors, path + ".escalateAfterHours", UnitService.REQUIRED);
        } else if (Sent.valueOf(in.escalateAfterHours()) != null) {
            hours = PharmacyParams.wholeNumber(Sent.valueOf(in.escalateAfterHours()), 1, 168);
            if (hours == null) {
                add(errors, path + ".escalateAfterHours", "Enter 1 to 168 hours");
            }
        }
        Integer urgent = null;
        if (Sent.missing(in.urgentEscalateAfterMinutes())) {
            add(errors, path + ".urgentEscalateAfterMinutes", UnitService.REQUIRED);
        } else if (Sent.valueOf(in.urgentEscalateAfterMinutes()) != null) {
            if (type != TransactionType.INDENT) {
                add(errors, path + ".urgentEscalateAfterMinutes", "Only the indent has an urgent time");
            } else {
                urgent = PharmacyParams.wholeNumber(Sent.valueOf(in.urgentEscalateAfterMinutes()), 5, 1440);
                if (urgent == null) {
                    add(errors, path + ".urgentEscalateAfterMinutes", "Enter 5 to 1440 minutes");
                }
            }
        }
        return new ApprovalRule(type, levels, hours, urgent);
    }

    private static List<ApprovalLevel> levels(List<LevelIn> in, TransactionType type, String path, boolean highValueSet,
                                               List<FieldMessage> errors) {
        if (in == null) {
            add(errors, path + ".levels", UnitService.REQUIRED);
            return List.of();
        }
        if (in.size() > 2) {
            add(errors, path + ".levels", "A rule has at most 2 levels");
            return List.of();
        }
        Map<Integer, Integer> indexOfLevel = new HashMap<>();
        List<ApprovalLevel> levels = new ArrayList<>();
        for (int j = 0; j < in.size(); j++) {
            LevelIn level = in.get(j);
            String levelPath = path + ".levels[" + j + "]";
            if (level == null) {
                add(errors, levelPath, "Fill level 1 first");
                continue;
            }
            Integer number = PharmacyParams.wholeNumber(level.level(), 1, 2);
            if (number == null) {
                add(errors, levelPath + ".level", "Choose level 1 or 2");
                continue;
            }
            if (indexOfLevel.containsKey(number)) {
                add(errors, levelPath + ".level", "Each level can appear once");
                continue;
            }
            indexOfLevel.put(number, j);
            if (level.approver() == null) {
                add(errors, levelPath + ".approver", "Choose who approves");
            }
            ApprovalCondition condition = condition(level.condition(), type, levelPath + ".condition", highValueSet, errors);
            if (level.approver() != null && condition != null) {
                levels.add(new ApprovalLevel(number, level.approver(), condition));
            }
        }
        if (indexOfLevel.containsKey(2) && !indexOfLevel.containsKey(1)) {
            add(errors, path + ".levels[" + indexOfLevel.get(2) + "]", "Fill level 1 first");
        }
        levels.sort(Comparator.comparingInt(ApprovalLevel::level));
        return levels;
    }

    private static ApprovalCondition condition(ConditionIn in, TransactionType type, String path, boolean highValueSet,
                                               List<FieldMessage> errors) {
        if (in == null || in.kind() == null) {
            add(errors, path, "Choose a condition");
            return null;
        }
        ConditionKind kind = in.kind();
        if (type == TransactionType.GRN_RATE_RISE && kind != ConditionKind.ALWAYS && kind != ConditionKind.ABOVE_PERCENT) {
            add(errors, path + ".kind", "The rate-rise limit is a percentage");
            return null;
        }
        switch (kind) {
            case ABOVE_AMOUNT:
                if (type == TransactionType.SALE_DISCOUNT) {
                    add(errors, path + ".kind", "The discount limit is a percentage");
                    return null;
                }
                if (in.amountPaise() == null) {
                    add(errors, path + ".amountPaise", LIMIT_REQUIRED);
                    return null;
                }
                Long amount = paise(in.amountPaise());
                if (amount == null) {
                    add(errors, path + ".amountPaise", AMOUNT_RANGE);
                    return null;
                }
                return ApprovalCondition.aboveAmount(amount);
            case ABOVE_PERCENT:
                if (type != TransactionType.SALE_DISCOUNT && type != TransactionType.GRN_RATE_RISE) {
                    add(errors, path + ".kind", "A % limit is only for discounts and rate rises");
                    return null;
                }
                BigDecimal percent = percent(in.percent(), path + ".percent", errors);
                return percent == null ? null : ApprovalCondition.abovePercent(percent);
            case STORE_TYPE:
                if (in.storeTypes() == null || in.storeTypes().isEmpty()) {
                    add(errors, path + ".storeTypes", "Choose at least one store type");
                    return null;
                }
                if (in.storeTypes().stream().anyMatch(java.util.Objects::isNull)) {
                    add(errors, path + ".storeTypes", "Choose a valid option");
                    return null;
                }
                return new ApprovalCondition(kind, null, null, in.storeTypes());
            case HIGH_VALUE_ITEM:
                if (!highValueSet) {
                    add(errors, "highValueThresholdPaise", HIGH_VALUE_REQUIRED);
                }
                return new ApprovalCondition(kind, null, null, null);
            case URGENT_PRIORITY:
                if (type != TransactionType.INDENT && type != TransactionType.STOCK_TRANSFER) {
                    add(errors, path + ".kind", "Urgent priority is only for indents and transfers");
                    return null;
                }
                return new ApprovalCondition(kind, null, null, null);
            case SCHEDULE_X_ITEM:
            case ALWAYS:
            default:
                return new ApprovalCondition(kind, null, null, null);
        }
    }

    // ---- POST /approval-rules/preset ----

    static Preset preset(PresetApply in) {
        List<FieldMessage> errors = new ArrayList<>();
        long base = baseVersion(in.baseVersionNo(), errors);
        RulePreset preset = in.preset();
        if (preset == null || preset == RulePreset.CUSTOM) {
            add(errors, "preset", "Choose a preset");
        }
        Map<TransactionType, ApprovalPresets.Limit> limits = new EnumMap<>(TransactionType.class);
        if (in.limits() == null) {
            add(errors, "limits", UnitService.REQUIRED);
        } else if (preset != null && preset != RulePreset.CUSTOM) {
            Set<TransactionType> needed = ApprovalPresets.limitTypes(preset);
            for (int i = 0; i < in.limits().size(); i++) {
                LimitIn limit = in.limits().get(i);
                String path = "limits[" + i + "]";
                if (limit == null || limit.transactionType() == null) {
                    add(errors, path + ".transactionType", "Choose a transaction type");
                    continue;
                }
                TransactionType type = limit.transactionType();
                if (limits.containsKey(type)) {
                    add(errors, path + ".transactionType", "Each transaction type can appear once");
                    continue;
                }
                if (!needed.contains(type)) {
                    add(errors, "limits." + type, "This preset has no limit for that item");
                    continue;
                }
                ApprovalPresets.Limit checked = limit(limit, type, path, errors);
                if (checked != null) {
                    limits.put(type, checked);
                }
            }
            for (TransactionType type : needed) {
                if (!limits.containsKey(type) && !hasEntry(in.limits(), type)) {
                    add(errors, "limits." + type, LIMIT_REQUIRED);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
        return new Preset(base, preset, limits);
    }

    private static boolean hasEntry(List<LimitIn> limits, TransactionType type) {
        return limits.stream().anyMatch(l -> l != null && l.transactionType() == type);
    }

    /** One entry: "no amount condition", or an amount (a percent for the discount and for a rate rise). */
    private static ApprovalPresets.Limit limit(LimitIn in, TransactionType type, String path, List<FieldMessage> errors) {
        boolean none = Boolean.TRUE.equals(in.noAmountCondition());
        boolean hasAmount = in.amountPaise() != null;
        boolean hasPercent = in.percent() != null && !in.percent().isBlank();
        if (none) {
            if (hasAmount || hasPercent) {
                add(errors, path, "Enter a limit or choose no amount condition, not both");
                return null;
            }
            return ApprovalPresets.Limit.NONE;
        }
        if (type == TransactionType.SALE_DISCOUNT || type == TransactionType.GRN_RATE_RISE) {
            if (hasAmount) {
                add(errors, path + ".amountPaise", type == TransactionType.SALE_DISCOUNT
                        ? "The discount limit is a percentage" : "The rate-rise limit is a percentage");
                return null;
            }
            BigDecimal percent = percent(in.percent(), path + ".percent", errors);
            return percent == null ? null : new ApprovalPresets.Limit(null, percent);
        }
        if (hasPercent) {
            add(errors, path + ".percent", "Enter an amount");
            return null;
        }
        if (!hasAmount) {
            add(errors, "limits." + type, LIMIT_REQUIRED);
            return null;
        }
        Long amount = paise(in.amountPaise());
        if (amount == null) {
            add(errors, path + ".amountPaise", AMOUNT_RANGE);
            return null;
        }
        return new ApprovalPresets.Limit(amount, null);
    }

    // ---- shared ----

    private static long baseVersion(Long value, List<FieldMessage> errors) {
        if (value == null || value < 0) {
            add(errors, "baseVersionNo", UnitService.RELOAD);
            return 0L;
        }
        return value;
    }

    /** A whole number of paise from 0 to 9,99,99,999 rupees, or null. */
    static Long paise(BigDecimal value) {
        return paise(value, MAX_PAISE);
    }

    private static Long paise(BigDecimal value, long max) {
        if (value == null || value.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return null;
        }
        return value.longValueExact();
    }

    private static BigDecimal percent(String text, String path, List<FieldMessage> errors) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            add(errors, path, LIMIT_REQUIRED);
            return null;
        }
        if (!VendorService.PERCENT.matcher(trimmed).matches()) {
            add(errors, path, PERCENT_RANGE);
            return null;
        }
        return new BigDecimal(trimmed);
    }

    private static void add(List<FieldMessage> errors, String field, String message) {
        FieldMessage error = new FieldMessage(field, message);
        if (!errors.contains(error)) {
            errors.add(error);
        }
    }
}
