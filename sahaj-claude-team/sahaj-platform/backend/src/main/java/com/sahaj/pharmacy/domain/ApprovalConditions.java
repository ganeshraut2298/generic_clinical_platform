package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The condition of a rule level worked out once, at submit (ADR-029 P6), and the "Rule" text the document and the inbox show.
 * Free of I/O. A rule change later never touches a request: the result and the text are kept on the request's level rows.
 */
public final class ApprovalConditions {

    private ApprovalConditions() {
    }

    /** What the document is, as far as a condition can ask. */
    public record Facts(long amountPaise, boolean hasScheduleX, boolean hasHighValueItem, boolean urgent,
                        BigDecimal maxRateRisePercent, StoreType ruleStoreType) {
    }

    /** Whether the level applies to a document with these facts. */
    public static boolean applies(ApprovalCondition condition, Facts facts) {
        switch (condition.kind()) {
            case ALWAYS:
                return true;
            case ABOVE_AMOUNT:
                return facts.amountPaise() > condition.amountPaise();
            case ABOVE_PERCENT:
                return facts.maxRateRisePercent() != null && facts.maxRateRisePercent().compareTo(condition.percent()) > 0;
            case SCHEDULE_X_ITEM:
                return facts.hasScheduleX();
            case HIGH_VALUE_ITEM:
                return facts.hasHighValueItem();
            case URGENT_PRIORITY:
                return facts.urgent();
            case STORE_TYPE:
                return condition.storeTypes().contains(facts.ruleStoreType());
            default:
                return false;
        }
    }

    /** The "Rule" column: for example "Stock adjustment above ₹ 5,000", "Rate up more than 5%", "Schedule X item". */
    public static String ruleText(TransactionType type, ApprovalLevel level) {
        ApprovalCondition c = level.condition();
        switch (c.kind()) {
            case ABOVE_AMOUNT:
                return label(type) + " above ₹ " + rupees(c.amountPaise());
            case ABOVE_PERCENT:
                String percent = c.percent().stripTrailingZeros().toPlainString();
                return type == TransactionType.GRN_RATE_RISE ? "Rate up more than " + percent + "%"
                        : label(type) + " discount above " + percent + "%";
            case SCHEDULE_X_ITEM:
                return "Schedule X item";
            case HIGH_VALUE_ITEM:
                return "High-value item";
            case URGENT_PRIORITY:
                return "Urgent priority";
            case STORE_TYPE:
                return "Store type " + String.join(", ", c.storeTypes().stream()
                        .map(t -> t.name().toLowerCase(Locale.ROOT).replace('_', ' ')).toList());
            default:
                return level.approver() == ApproverKind.OWNER ? "Owner approval" : "Store HOD approval";
        }
    }

    /** Whole rupees with Indian grouping from paise: 500000 gives "5,000"; 12500000 gives "1,25,000". Paise shown when not whole. */
    public static String rupees(long paise) {
        long whole = paise / 100;
        long rest = paise % 100;
        String digits = Long.toString(whole);
        StringBuilder grouped = new StringBuilder();
        int length = digits.length();
        for (int i = 0; i < length; i++) {
            int remaining = length - i - 1;
            grouped.append(digits.charAt(i));
            if (remaining == 3 || remaining > 3 && (remaining - 3) % 2 == 0) {
                grouped.append(',');
            }
        }
        return rest == 0 ? grouped.toString() : grouped + "." + String.format(Locale.ROOT, "%02d", rest);
    }

    private static String label(TransactionType type) {
        switch (type) {
            case PURCHASE_ORDER:
                return "PO";
            case GRN_RATE_RISE:
                return "Receipt";
            case VENDOR_RETURN:
                return "Vendor return";
            case INDENT:
                return "Indent";
            case STOCK_TRANSFER:
                return "Transfer";
            case WRITE_OFF:
                return "Write-off";
            case STOCK_ADJUSTMENT:
                return "Stock adjustment";
            case SALE_DISCOUNT:
                return "Sale";
            default:
                return "Sales return";
        }
    }
}
