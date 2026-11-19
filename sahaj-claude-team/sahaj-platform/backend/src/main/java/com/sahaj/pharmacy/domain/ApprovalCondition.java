package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * The condition of one approval level. Only the value that belongs to the kind is kept: {@code amountPaise} for
 * ABOVE_AMOUNT, {@code percent} for ABOVE_PERCENT, {@code storeTypes} for STORE_TYPE (the database insists on exactly that).
 * Values are normalised on construction (percent to two decimals, store types sorted and without repeats) so two
 * conditions that mean the same are equal, which is how an unchanged save is recognised.
 */
public record ApprovalCondition(ConditionKind kind, Long amountPaise, BigDecimal percent, List<StoreType> storeTypes) {

    public ApprovalCondition {
        amountPaise = kind == ConditionKind.ABOVE_AMOUNT ? amountPaise : null;
        percent = kind == ConditionKind.ABOVE_PERCENT && percent != null ? percent.setScale(2, RoundingMode.HALF_UP) : null;
        storeTypes = kind == ConditionKind.STORE_TYPE && storeTypes != null
                ? storeTypes.stream().distinct().sorted(Comparator.naturalOrder()).toList() : null;
    }

    public static ApprovalCondition always() {
        return new ApprovalCondition(ConditionKind.ALWAYS, null, null, null);
    }

    public static ApprovalCondition aboveAmount(long amountPaise) {
        return new ApprovalCondition(ConditionKind.ABOVE_AMOUNT, amountPaise, null, null);
    }

    public static ApprovalCondition abovePercent(BigDecimal percent) {
        return new ApprovalCondition(ConditionKind.ABOVE_PERCENT, null, percent, null);
    }

    /** A short fixed text for the audit row, for example {@code ABOVE_AMOUNT 5000000}. No free text is ever in it. */
    public String describe() {
        switch (kind) {
            case ABOVE_AMOUNT:
                return kind + " " + amountPaise;
            case ABOVE_PERCENT:
                return kind + " " + percent.stripTrailingZeros().toPlainString();
            case STORE_TYPE:
                return kind + " " + storeTypes;
            default:
                return kind.name();
        }
    }
}
