package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.TableColumn;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure rules for the value of one EMR field (ADR-020 D3, "Value kind"): the shape and limits per value kind, and what
 * "empty" means for the Done rule. Messages are fixed plain English and never echo the value. The limits are the
 * contract's (docs/api/opd.yaml EmrValueKind); no clinical range is invented: a number is bounded by the field's own
 * min and max when set, else by plus or minus 1,000,000 (the seed sets none, ADR-020 Human items).
 */
public final class EmrValueRules {

    public static final int MAX_SHORT_TEXT = 2_000;
    public static final int MAX_RICH_TEXT = 10_000;
    public static final int MAX_CHOICE_TEXT = 200;
    public static final int MAX_LIST = 50;
    public static final int MAX_CELL = 500;
    public static final int MAX_UNIT = 20;
    public static final int MAX_STRUCTURED_TEXT = 2_000;
    public static final int MAX_ITEM_KEY = 40;
    public static final int MAX_SECTION_BYTES = 32 * 1024;
    private static final BigDecimal LIMIT = new BigDecimal("1000000");
    private static final int MAX_DECIMALS = 4;
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");

    private EmrValueRules() {
    }

    /** A value that does not fit its field; the message is shown next to the field. */
    public static final class InvalidValue extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidValue(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Checks one non-null value against its field. Returns normally when it is acceptable; throws
     * {@link InvalidValue} otherwise. A DISPLAY_ONLY or LATER field accepts nothing.
     */
    public static void check(FieldDefinition field, JsonNode value) {
        switch (field.valueKind()) {
            case TEXT -> checkText(field, value);
            case NUMBER -> checkNumber(field, value);
            case NUMBER_UNIT -> checkNumberUnit(field, value);
            case DATE -> checkDate(value);
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new InvalidValue("Choose yes or no.");
                }
            }
            case CHOICE -> checkChoice(field, value);
            case MULTI_CHOICE -> checkMultiChoice(field, value);
            case ITEM_LIST -> checkItemList(value);
            case TABLE -> checkTable(field, value);
            case STRUCTURED -> checkStructured(value);
            default -> throw new InvalidValue("This field cannot be filled in.");
        }
    }

    private static void checkText(FieldDefinition field, JsonNode value) {
        if (!value.isTextual()) {
            throw new InvalidValue("Enter text.");
        }
        int max = "RICH_TEXT".equals(field.type()) ? MAX_RICH_TEXT : MAX_SHORT_TEXT;
        if (value.textValue().length() > max) {
            throw new InvalidValue("This text is too long.");
        }
        if (HTML.matcher(value.textValue()).find()) {
            throw new InvalidValue("Enter plain text without HTML.");
        }
    }

    private static BigDecimal number(JsonNode value) {
        if (!value.isNumber() || (value.isDouble() && !Double.isFinite(value.doubleValue()))) {
            throw new InvalidValue("Enter a number.");
        }
        BigDecimal number = value.decimalValue();
        if (number.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw new InvalidValue("Use at most 4 decimal places.");
        }
        return number;
    }

    private static void checkNumber(FieldDefinition field, JsonNode value) {
        BigDecimal number = number(value);
        BigDecimal min = field.min() != null ? field.min() : LIMIT.negate();
        BigDecimal max = field.max() != null ? field.max() : LIMIT;
        if (number.compareTo(min) < 0 || number.compareTo(max) > 0) {
            throw new InvalidValue("This number is outside the allowed range.");
        }
    }

    private static void checkNumberUnit(FieldDefinition field, JsonNode value) {
        if (!value.isObject() || !onlyProperties(value, Set.of("value", "unit"))) {
            throw new InvalidValue("Enter a number and a unit.");
        }
        checkNumber(field, value.path("value"));
        JsonNode unit = value.path("unit");
        if (!unit.isTextual() || unit.textValue().length() > MAX_UNIT || HTML.matcher(unit.textValue()).find()) {
            throw new InvalidValue("Enter a unit of up to 20 characters.");
        }
    }

    private static void checkDate(JsonNode value) {
        if (!value.isTextual()) {
            throw new InvalidValue("Enter a date.");
        }
        try {
            LocalDate.parse(value.textValue());
        } catch (DateTimeParseException e) {
            throw new InvalidValue("Enter a date as year-month-day.");
        }
    }

    private static boolean hasOptions(FieldDefinition field) {
        return field.options() != null && !field.options().isEmpty();
    }

    private static void checkOne(FieldDefinition field, JsonNode value) {
        if (!value.isTextual()) {
            throw new InvalidValue("Choose an option.");
        }
        String text = value.textValue();
        if (hasOptions(field)) {
            for (FieldOption option : field.options()) {
                if (option.key().equals(text)) {
                    return;
                }
            }
            throw new InvalidValue("Choose an option from the list.");
        }
        if (text.length() > MAX_CHOICE_TEXT || HTML.matcher(text).find()) {
            throw new InvalidValue("This text is too long.");
        }
    }

    private static void checkChoice(FieldDefinition field, JsonNode value) {
        checkOne(field, value);
    }

    private static void checkMultiChoice(FieldDefinition field, JsonNode value) {
        if (!value.isArray()) {
            throw new InvalidValue("Choose from the list.");
        }
        if (value.size() > MAX_LIST) {
            throw new InvalidValue("Choose at most 50.");
        }
        for (JsonNode item : value) {
            checkOne(field, item);
        }
    }

    private static void checkItemList(JsonNode value) {
        if (!value.isArray()) {
            throw new InvalidValue("Add items to the list.");
        }
        if (value.size() > MAX_LIST) {
            throw new InvalidValue("Add at most 50.");
        }
        for (JsonNode item : value) {
            if (!item.isObject() || !onlyProperties(item, Set.of("itemKey", "text", "newToList"))) {
                throw new InvalidValue("Each item needs a key, a name and a new-to-list flag.");
            }
            JsonNode key = item.path("itemKey");
            JsonNode text = item.path("text");
            JsonNode isNew = item.path("newToList");
            if (!isNew.isBoolean() || !text.isTextual() || text.textValue().isBlank()
                    || text.textValue().length() > MAX_CHOICE_TEXT || HTML.matcher(text.textValue()).find()) {
                throw new InvalidValue("Each item needs a name of up to 200 characters.");
            }
            boolean keyed = key.isTextual();
            if ((keyed && (key.textValue().isEmpty() || key.textValue().length() > MAX_ITEM_KEY))
                    || (!keyed && !key.isNull() && !key.isMissingNode())) {
                throw new InvalidValue("An item key is up to 40 characters.");
            }
            if (keyed == isNew.booleanValue()) {
                throw new InvalidValue("A typed item has no key; a listed item has one.");
            }
        }
    }

    private static List<String> columnKeys(FieldDefinition field) {
        List<String> keys = new ArrayList<>();
        if (field.columns() == null || field.columns().isEmpty()) {
            keys.add("entry");
        } else {
            for (TableColumn column : field.columns()) {
                keys.add(column.key());
            }
        }
        return keys;
    }

    private static void checkTable(FieldDefinition field, JsonNode value) {
        if (!value.isArray()) {
            throw new InvalidValue("Add rows to the table.");
        }
        if (value.size() > MAX_LIST) {
            throw new InvalidValue("Add at most 50 rows.");
        }
        List<String> columns = columnKeys(field);
        for (JsonNode row : value) {
            if (!row.isObject()) {
                throw new InvalidValue("Each row needs its columns.");
            }
            Iterator<String> names = row.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!columns.contains(name)) {
                    throw new InvalidValue("A row has a column this table does not have.");
                }
                JsonNode text = row.get(name);
                if (!text.isTextual() || text.textValue().length() > MAX_CELL
                        || HTML.matcher(text.textValue()).find()) {
                    throw new InvalidValue("Each cell is text of up to 500 characters.");
                }
            }
        }
    }

    private static void checkStructured(JsonNode value) {
        if (!value.isObject() || !onlyProperties(value, Set.of("text"))) {
            throw new InvalidValue("Enter text for this field.");
        }
        JsonNode text = value.path("text");
        if (!text.isTextual() || text.textValue().length() > MAX_STRUCTURED_TEXT
                || HTML.matcher(text.textValue()).find()) {
            throw new InvalidValue("Enter text of up to 2,000 characters.");
        }
    }

    private static boolean onlyProperties(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the stored value counts as "nothing entered" (ADR-020 D8): null, empty or blank text, an empty array,
     * a number-with-unit with no number, a structured value with no text, a table whose rows are all blank. A yes or
     * no answer, including no, counts as entered.
     */
    public static boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (value.isTextual()) {
            return value.textValue().isBlank();
        }
        if (value.isArray()) {
            if (value.isEmpty()) {
                return true;
            }
            boolean allBlankRows = true;
            for (JsonNode item : value) {
                if (!item.isObject() || item.has("itemKey") || !isBlankRow(item)) {
                    allBlankRows = false;
                    break;
                }
            }
            return allBlankRows;
        }
        if (value.isObject()) {
            if (value.has("component")) {
                return !EmrComponentValues.isFilled(value);
            }
            if (value.has("value") && value.has("unit")) {
                return value.path("value").isNull();
            }
            return isBlankRow(value);
        }
        return false;
    }

    private static boolean isBlankRow(JsonNode object) {
        Iterator<JsonNode> cells = object.elements();
        while (cells.hasNext()) {
            JsonNode cell = cells.next();
            if (!(cell.isNull() || (cell.isTextual() && cell.textValue().isBlank()))) {
                return false;
            }
        }
        return true;
    }
}
