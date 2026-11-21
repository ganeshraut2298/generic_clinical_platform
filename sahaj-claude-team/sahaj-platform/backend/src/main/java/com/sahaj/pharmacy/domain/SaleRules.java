package com.sahaj.pharmacy.domain;

import com.sahaj.patient.api.AllergyStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The pure rules of a counter sale that are not money maths (ADR-030 S5, S9, S14): masking, the discount limit read from the
 * approval rule, the allergy keyword match, the 60-day ageing of udhar and the quote digest. No database, no clock.
 */
public final class SaleRules {

    private static final Pattern PHONE = Pattern.compile("^[0-9]{10}$");
    private static final Pattern TERM_SPLIT = Pattern.compile("[,;\\n\\r]+|\\band\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_LETTER = Pattern.compile("[^\\p{L}]+");

    private SaleRules() {
    }

    public static boolean validPhone(String phone) {
        return phone != null && PHONE.matcher(phone).matches();
    }

    /** "+91 98XXX XX214" from ten digits: the first two and the last three show. Never the whole number. */
    public static String maskMobile(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        if (digits.length() < 10) {
            return "+91 XXXXX XXXXX";
        }
        return "+91 " + digits.substring(0, 2) + "XXX XX" + digits.substring(7);
    }

    /** A name for a list: each word shows its first letter ("R*** S***"). Never the whole name. */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.substring(0, word.offsetByCodePoints(0, 1))).append("***");
        }
        return out.toString();
    }

    /** What the discount rule says about a percent (the SALE_DISCOUNT rule of the unit, ADR-030 S14). */
    public record Discount(boolean approvalNeeded, BigDecimal limitPercent, boolean limitNotSet, boolean lean) {
    }

    /**
     * No rule or a rule with no level is "lean": any percent applies at once. A level with an ABOVE_PERCENT condition makes a
     * percent above it need approval. A level that is ALWAYS means the owner has set no limit, so every discount above 0 waits
     * ("The discount limit is not set. Ask your owner.").
     */
    public static Discount discount(List<ApprovalRule> rules, BigDecimal percent) {
        ApprovalRule rule = rules == null ? null : rules.stream().filter(r -> r.type() == TransactionType.SALE_DISCOUNT)
                .findFirst().orElse(null);
        boolean positive = percent != null && percent.signum() > 0;
        if (rule == null || rule.levels().isEmpty()) {
            return new Discount(false, null, false, true);
        }
        BigDecimal limit = null;
        boolean notSet = false;
        boolean needed = false;
        for (ApprovalLevel level : rule.levels()) {
            ApprovalCondition condition = level.condition();
            if (condition.kind() == ConditionKind.ABOVE_PERCENT && condition.percent() != null) {
                limit = limit == null || condition.percent().compareTo(limit) < 0 ? condition.percent() : limit;
                needed |= positive && percent.compareTo(condition.percent()) > 0;
            } else if (condition.kind() == ConditionKind.ALWAYS) {
                notSet = true;
                needed |= positive;
            }
        }
        return new Discount(needed, limit, notSet && limit == null, false);
    }

    /**
     * ADR-030 S9. The allergy text is split into terms on commas, semicolons, line breaks and "and". A term of at least three
     * letters that is a whole word of the salt or the name blocks the line; so does the first word of the salt when it is a whole
     * word of the text. The none-words never match. Drug classes are not checked.
     */
    public static boolean allergyMatches(String allergyText, String salt, String name) {
        if (allergyText == null || AllergyStatus.isPlaceholder(allergyText)) {
            return false;
        }
        String text = allergyText.toLowerCase(Locale.ROOT);
        String saltText = salt == null ? "" : salt.toLowerCase(Locale.ROOT);
        String nameText = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String raw : TERM_SPLIT.split(text)) {
            String term = raw.trim();
            if (letters(term) >= 3 && (wholeWord(saltText, term) || wholeWord(nameText, term))) {
                return true;
            }
        }
        String first = firstWord(saltText);
        return first != null && wholeWord(text, first);
    }

    private static int letters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static String firstWord(String text) {
        for (String word : NON_LETTER.split(text)) {
            if (word.length() >= 3) {
                return word;
            }
            if (!word.isEmpty()) {
                return null;
            }
        }
        return null;
    }

    static boolean wholeWord(String haystack, String term) {
        if (haystack.isEmpty() || term.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])").matcher(haystack).find();
    }

    /** The part of the due that comes from credit bills older than 60 days: payments go to the oldest bills first (FIFO). */
    public static long over60(long duePaise, long creditBillsInLast60DaysPaise) {
        return Math.max(0L, duePaise - Math.max(0L, creditBillsInLast60DaysPaise));
    }

    /** "₹ 532" or "₹ 1,25,000.50": the money text of a message. */
    public static String rupeesText(long paise) {
        return "₹ " + ApprovalConditions.rupees(paise);
    }

    /** A rate in rupees with at least two decimals, as the contract's Rate: "46.5" is "46.50", "0.0500" is "0.05". */
    public static String rate(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() < 2) {
            v = v.setScale(2);
        }
        return v.toPlainString();
    }

    /** A percent as text: "5", "12", "5.5". */
    public static String percent(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /** The refund of some units of a bill line and its taxable and tax parts (taxable + tax = refund). */
    public record ReturnShare(long refundPaise, long taxablePaise, long taxPaise) {
    }

    /**
     * ADR-030 S5: the refund of {@code qty} units of a line that sold {@code soldQty} units for {@code netPaise} (the line's net
     * amount after its share of the bill discount; the bill's round off is never refunded). Shares are cumulative, rounded half up, so
     * the refunds of every unit of the line add up to the line's net amount exactly, whatever the order of the returns.
     * {@code alreadyQty} is what was returned (or is waiting) before.
     */
    public static ReturnShare returnShare(long netPaise, long taxablePaise, int soldQty, int alreadyQty, int qty) {
        long refund = share(netPaise, alreadyQty + qty, soldQty) - share(netPaise, alreadyQty, soldQty);
        long taxable = share(taxablePaise, alreadyQty + qty, soldQty) - share(taxablePaise, alreadyQty, soldQty);
        taxable = Math.min(taxable, refund);
        return new ReturnShare(refund, taxable, refund - taxable);
    }

    private static long share(long amount, int part, int whole) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * The digest a discount request keeps (ADR-030 S14): the product and the quantity of each line, in the order of the cart. The
     * percent is kept beside it, so a changed cart makes the request VOID and so does a higher percent. 32 lower-case hex digits.
     */
    public static String cartDigest(List<String> productQtyKeys) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            for (String key : productQtyKeys) {
                canonical.append(key).append('\n');
            }
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The digest of a priced cart: lines (product, batch, quantity, MRP) and the percent. Stable for the same priced cart. */
    public static String quoteVersion(List<String> lineKeys, BigDecimal percent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            for (String key : lineKeys) {
                canonical.append(key).append('\n');
            }
            canonical.append("pct=").append(percent == null ? "0" : percent.stripTrailingZeros().toPlainString());
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("q-");
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
