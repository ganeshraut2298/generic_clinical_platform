package com.sahaj.pharmacy.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The per-row checks of an opening-stock file (docs/api/pharmacy.yaml POST .../lines, story 78-7). Pure, no I/O: the file
 * rows arrive as text, exactly as the browser read them, and each is parsed here. A bad cell is an issue on the row, never a
 * 400; the row keeps its raw text and whatever parsed. The file-wide check (same product and batch twice) is done at submit.
 */
public final class OpeningStockRules {

    public static final int BATCH_MAX = 20;
    public static final BigDecimal RATE_MAX = new BigDecimal("9999999.99");
    public static final int QUANTITY_MAX = 999_999;

    private static final Pattern DAY_FIRST = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})$");
    private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern MONTH_YEAR = Pattern.compile("^(\\d{1,2})[-/](\\d{4})$");
    private static final Pattern RATE = Pattern.compile("^[0-9]{1,7}(\\.[0-9]{1,2})?$");
    private static final Pattern WHOLE = Pattern.compile("^[0-9]{1,7}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private OpeningStockRules() {
    }

    /** The seven cells of a row as read from the file. */
    public record Raw(String storeCode, String productCode, String batch, String expiryDate, String mrp,
                      String purchaseRate, String quantity) {
    }

    /** One problem on a row: {@code field} is the contract field name, {@code severity} ERROR or WARNING. */
    public record Issue(String field, String code, String severity) {

        public static final String ERROR = "ERROR";
        public static final String WARNING = "WARNING";
    }

    /** What could be read from the row; a value that failed its check is null. */
    public record Parsed(String batchNo, LocalDate expiry, BigDecimal mrp, BigDecimal purchaseRate, Integer quantity,
                         Long valuePaise) {
    }

    public record Checked(Parsed parsed, List<Issue> issues) {

        public boolean hasError() {
            return issues.stream().anyMatch(i -> Issue.ERROR.equals(i.severity()));
        }
    }

    /**
     * Checks one row. {@code storeCode} is the code of the store of the import, {@code productKnown} whether the product
     * code is an ACTIVE adopted or own product of the unit, {@code today} the IST date.
     */
    public static Checked check(Raw raw, String storeCode, boolean productKnown, LocalDate today) {
        List<Issue> issues = new ArrayList<>();
        if (!sameCode(raw.storeCode(), storeCode)) {
            issues.add(error("storeCode", "store-mismatch"));
        }
        if (!productKnown) {
            issues.add(error("productCode", "product-unknown"));
        }
        String batch = raw.batch() == null ? "" : raw.batch().trim();
        String batchNo = null;
        if (batch.isEmpty() || batch.length() > BATCH_MAX) {
            issues.add(error("batch", "batch-invalid"));
        } else {
            batchNo = batch;
        }
        LocalDate expiry = parseExpiry(raw.expiryDate());
        if (expiry == null) {
            issues.add(error("expiryDate", "expiry-invalid"));
        } else if (!expiry.isAfter(today)) {
            issues.add(error("expiryDate", "expiry-past"));
        }
        BigDecimal mrp = parseRate(raw.mrp());
        if (mrp == null) {
            issues.add(error("mrp", "mrp-invalid"));
        }
        BigDecimal rate = parseRate(raw.purchaseRate());
        if (rate == null) {
            issues.add(error("purchaseRate", "rate-invalid"));
        }
        Integer quantity = parseQuantity(raw.quantity());
        if (quantity == null) {
            issues.add(error("quantity", "quantity-invalid"));
        }
        if (mrp != null && rate != null && rate.compareTo(mrp) > 0) {
            issues.add(new Issue("purchaseRate", "rate-above-mrp", Issue.WARNING));
        }
        Long value = rate != null && quantity != null ? valuePaise(rate, quantity) : null;
        return new Checked(new Parsed(batchNo, expiry, mrp, rate, quantity, value), List.copyOf(issues));
    }

    /** DD-MM-YYYY, DD/MM/YYYY, YYYY-MM-DD, MM/YYYY or MM-YYYY; a month form is the last day of that month. Null when unreadable. */
    public static LocalDate parseExpiry(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        try {
            Matcher m = DAY_FIRST.matcher(value);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
            m = YEAR_FIRST.matcher(value);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            m = MONTH_YEAR.matcher(value);
            if (m.matches()) {
                return YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1))).atEndOfMonth();
            }
        } catch (DateTimeException notADate) {
            return null;
        }
        return null;
    }

    /** An amount above 0 with at most 2 decimals and at most 99,99,999.99; null otherwise. */
    public static BigDecimal parseRate(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (!RATE.matcher(value).matches()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(value);
        return amount.signum() > 0 && amount.compareTo(RATE_MAX) <= 0 ? amount : null;
    }

    /** A whole number from 1 to 9,99,999; null otherwise. */
    public static Integer parseQuantity(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (!WHOLE.matcher(value).matches()) {
            return null;
        }
        int quantity = Integer.parseInt(value);
        return quantity >= 1 && quantity <= QUANTITY_MAX ? quantity : null;
    }

    /** Rate (rupees per issue unit) times quantity, in paise, rounded half-up to the paisa. */
    public static long valuePaise(BigDecimal rate, int quantity) {
        return rate.multiply(BigDecimal.valueOf(quantity)).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The plain-English sentence for an issue code (shown next to the row). */
    public static String message(String code) {
        switch (code) {
            case "store-mismatch":
                return "Store code does not match this store";
            case "product-unknown":
                return "Product code is not an active product of this unit";
            case "batch-invalid":
                return "Enter a batch of 1 to 20 characters";
            case "batch-repeated":
                return "This product and batch appear more than once in the file";
            case "expiry-invalid":
                return "Enter a valid expiry date";
            case "expiry-past":
                return "Expiry date must be in the future";
            case "mrp-invalid":
                return "Enter an MRP above 0 with at most 2 decimals";
            case "rate-invalid":
                return "Enter a purchase rate above 0 with at most 2 decimals";
            case "quantity-invalid":
                return "Enter a whole number from 1 to 9,99,999";
            case "rate-above-mrp":
                return "Purchase rate is higher than MRP";
            default:
                return "Check this value";
        }
    }

    private static boolean sameCode(String found, String expected) {
        return found != null && !found.isBlank() && found.trim().equalsIgnoreCase(expected);
    }

    private static Issue error(String field, String code) {
        return new Issue(field, code, Issue.ERROR);
    }
}
