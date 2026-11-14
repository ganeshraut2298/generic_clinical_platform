package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrFormModel.Definition;
import com.sahaj.opd.application.emr.EmrFormModel.Field;
import com.sahaj.opd.application.emr.EmrFormModel.Option;
import com.sahaj.opd.application.emr.EmrModel.ComponentRef;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The values of a clinic form inside an EMR record (SAH-59-5; ADR-022 D11): validation on save, what is Done, the score
 * and band the server works out for the record and the history, and the labelled lines a signed form shows in the
 * patient's history. Pure apart from the component values (body map, medicine list, tests, diagnoses), which reuse
 * {@link EmrComponentValues}. Rule tag for scores, bands and normal ranges: value from the EMR mockup, accepted by the
 * human in DEC-039, not clinically reviewed (C-9).
 *
 * <p>A field hidden by its show-if rule is empty: its value is dropped on save, it is not required, it adds no score and it
 * is not shown in the history. The display and computed types (heading, note, score, band) store nothing; the types Tooth
 * chart, Signature, Photo, File upload and Handwriting pad are LATER and store nothing. Messages are fixed plain English
 * and never repeat what was typed; values are clinical data and are never logged.
 */
public final class EmrFormValues {

    /** Types that store nothing because they are shown or computed. */
    static final Set<String> DISPLAY = Set.of("SECTION_HEADING", "INFO_NOTE", "SCORE", "SCORE_BAND");
    /** Types that wait for a later story (the documents service or the tooth chart). */
    static final Set<String> LATER = Set.of("TOOTH_CHART", "SIGNATURE", "PHOTO", "FILE_UPLOAD", "HANDWRITING_PAD");
    private static final Set<String> VITALS_KEYS = Set.of("bpSystolic", "bpDiastolic", "pulse", "spo2",
            "temperatureF", "weightKg", "heightCm");
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final Pattern TIME = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
    private static final int MAX_SHORT = 200;
    private static final int MAX_LONG = 4_000;
    private static final int MAX_DECIMALS = 4;
    private static final BigDecimal VITALS_LIMIT = new BigDecimal("100000");
    private static final BigDecimal NUMBER_LIMIT = new BigDecimal("1000000000");

    private EmrFormValues() {
    }

    /** A labelled value for the patient's history (setup labels and clinical values: on screen only, never logged). */
    public record Line(String label, String value) {
    }

    // ---- field kinds ----

    /** The four types that reuse a shared component. */
    static boolean isComponent(Field field) {
        return componentRef(field) != null;
    }

    private static ComponentRef componentRef(Field field) {
        return switch (field.type()) {
            case "BODY_MAP" -> new ComponentRef(EmrComponentBinding.BODY_MAP, null);
            case "MEDICINE_LIST" -> new ComponentRef(EmrComponentBinding.PRESCRIPTION, null);
            case "LAB_TESTS" -> new ComponentRef(EmrComponentBinding.EMR_LIST, "INVESTIGATIONS");
            case "DIAGNOSIS" -> new ComponentRef(EmrComponentBinding.EMR_LIST, "DIAGNOSIS");
            default -> null;
        };
    }

    /** The component field a form field stands for, so the shared component rules can check its value. */
    static FieldDefinition componentField(Field field) {
        return new FieldDefinition(field.key(), field.label(), field.type(), EmrValueKind.COMPONENT, "",
                Boolean.TRUE.equals(field.required()), "NONE", null, false, List.of(), List.of(), null, null,
                componentRef(field));
    }

    /** Keys of the form fields that hold a shared component of this type (EMR_LIST kinds are ignored here). */
    static List<String> componentKeys(Definition form, String componentType) {
        List<String> keys = new ArrayList<>();
        for (Field field : fields(form)) {
            ComponentRef ref = componentRef(field);
            if (ref != null && ref.type().equals(componentType)) {
                keys.add(field.key());
            }
        }
        return keys;
    }

    static boolean fillable(Field field) {
        return !DISPLAY.contains(field.type()) && !LATER.contains(field.type());
    }

    private static List<Field> fields(Definition form) {
        return form == null || form.fields() == null ? List.of() : form.fields();
    }

    // ---- show-if (top to bottom; chains allowed) ----

    /** The keys of the fields hidden by their show-if rules, given the answers so far. */
    public static Set<String> hiddenKeys(Definition form, JsonNode values) {
        Set<String> hidden = new HashSet<>();
        for (Field field : fields(form)) {
            if (field.showIf() == null) {
                continue;
            }
            String source = field.showIf().fieldKey();
            if (hidden.contains(source) || !answerIncludes(values.get(source), field.showIf().optionKey())) {
                hidden.add(field.key());
            }
        }
        return hidden;
    }

    private static boolean answerIncludes(JsonNode answer, String optionKey) {
        if (answer == null || optionKey == null) {
            return false;
        }
        if (answer.isBoolean()) {
            return (answer.booleanValue() ? "yes" : "no").equals(optionKey);
        }
        if (answer.isTextual()) {
            return answer.textValue().equals(optionKey);
        }
        if (answer.isArray()) {
            for (JsonNode one : answer) {
                if (one.isTextual() && one.textValue().equals(optionKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- save ----

    /**
     * Checks the values sent for one form section against the form version the record was opened with and returns what
     * to store: hidden fields dropped, empty values removed. A wrong value adds an error named
     * {@code <where>.values.<fieldKey>} and the result is null. The section holds all its values (a save replaces them).
     */
    public static ObjectNode clean(ObjectMapper mapper, Definition form, String where, JsonNode values,
                                   JsonNode stored, EmrComponentValues components, EmrComponentValues.Context context,
                                   List<FieldMessage> errors, List<EmrComponentValues.Note> notes) {
        if (values == null || !values.isObject()) {
            errors.add(new FieldMessage(where + ".values", "Send the values as an object."));
            return null;
        }
        if (values.size() > EmrFormModel.MAX_FIELDS) {
            errors.add(new FieldMessage(where + ".values", "Send at most 80 fields."));
            return null;
        }
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (Field field : fields(form)) {
            byKey.put(field.key(), field);
        }
        Set<String> hidden = hiddenKeys(form, values);
        ObjectNode cleaned = mapper.createObjectNode();
        int before = errors.size();
        Iterator<Map.Entry<String, JsonNode>> entries = values.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            Field field = byKey.get(entry.getKey());
            JsonNode value = entry.getValue();
            String name = where + ".values." + entry.getKey();
            if (field == null) {
                errors.add(new FieldMessage(where + ".values", "This section has a field that is not in the form."));
                continue;
            }
            if (value == null || value.isNull() || hidden.contains(field.key())) {
                continue;
            }
            if (DISPLAY.contains(field.type())) {
                errors.add(new FieldMessage(name, "This field is shown or worked out; nothing is entered in it."));
                continue;
            }
            if (LATER.contains(field.type())) {
                errors.add(new FieldMessage(name, "This kind of field is available later."));
                continue;
            }
            try {
                if (isComponent(field)) {
                    EmrComponentValues.Result result = components.normalize(componentField(field), value,
                            stored == null ? null : stored.get(field.key()), context);
                    notes.addAll(result.notes());
                    cleaned.set(field.key(), result.value());
                } else {
                    check(field, value);
                    if (!EmrValueRules.isEmpty(value)) {
                        cleaned.set(field.key(), value);
                    }
                }
            } catch (EmrValueRules.InvalidValue e) {
                errors.add(new FieldMessage(name, e.getMessage()));
            }
        }
        if (errors.size() > before) {
            return null;
        }
        try {
            if (mapper.writeValueAsBytes(cleaned).length > EmrValueRules.MAX_SECTION_BYTES) {
                errors.add(new FieldMessage(where + ".values", "This section is too large (32 KB at most)."));
                return null;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            errors.add(new FieldMessage(where + ".values", "These values could not be read."));
            return null;
        }
        return cleaned;
    }

    private static void check(Field field, JsonNode value) {
        switch (field.type()) {
            case "SHORT_TEXT" -> text(value, MAX_SHORT);
            case "LONG_TEXT" -> text(value, MAX_LONG);
            case "NUMBER" -> number(field, value);
            case "DATE" -> date(value);
            case "TIME" -> {
                if (!value.isTextual() || !TIME.matcher(value.textValue()).matches()) {
                    throw new EmrValueRules.InvalidValue("Enter a time as hours and minutes, for example 14:30.");
                }
            }
            case "YES_NO" -> {
                if (!value.isBoolean()) {
                    throw new EmrValueRules.InvalidValue("Choose yes or no.");
                }
            }
            case "SINGLE_CHOICE", "DROPDOWN" -> {
                if (!value.isTextual() || !optionKeys(field).contains(value.textValue())) {
                    throw new EmrValueRules.InvalidValue("Choose one of the options.");
                }
            }
            case "MULTI_CHOICE" -> multi(field, value);
            case "VITALS_BLOCK" -> vitals(value);
            default -> throw new EmrValueRules.InvalidValue("This field cannot be filled in.");
        }
    }

    private static void text(JsonNode value, int max) {
        if (!value.isTextual()) {
            throw new EmrValueRules.InvalidValue("Enter text.");
        }
        if (value.textValue().length() > max) {
            throw new EmrValueRules.InvalidValue("This text is too long (" + max + " characters at most).");
        }
        if (HTML.matcher(value.textValue()).find()) {
            throw new EmrValueRules.InvalidValue("Enter plain text without HTML.");
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber() || (value.isDouble() && !Double.isFinite(value.doubleValue()))) {
            throw new EmrValueRules.InvalidValue("Enter a number.");
        }
        BigDecimal number = value.decimalValue();
        if (number.stripTrailingZeros().scale() > MAX_DECIMALS) {
            throw new EmrValueRules.InvalidValue("Use at most 4 decimal places.");
        }
        return number;
    }

    private static void number(Field field, JsonNode value) {
        BigDecimal number = decimal(value);
        if (number.abs().compareTo(NUMBER_LIMIT) > 0) {
            throw new EmrValueRules.InvalidValue("This number is too large.");
        }
        BigDecimal min = field.min();
        BigDecimal max = field.max();
        if ((min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0)) {
            String low = min == null ? null : min.stripTrailingZeros().toPlainString();
            String high = max == null ? null : max.stripTrailingZeros().toPlainString();
            throw new EmrValueRules.InvalidValue(low != null && high != null ? "Outside " + low + "–" + high
                    : low != null ? "Below the lowest allowed value, " + low
                    : "Above the highest allowed value, " + high);
        }
    }

    private static void date(JsonNode value) {
        if (!value.isTextual()) {
            throw new EmrValueRules.InvalidValue("Enter a date.");
        }
        try {
            LocalDate.parse(value.textValue());
        } catch (DateTimeParseException e) {
            throw new EmrValueRules.InvalidValue("Enter a date as year-month-day.");
        }
    }

    private static Set<String> optionKeys(Field field) {
        if ("YES_NO".equals(field.type())) {
            return Set.of("yes", "no");
        }
        Set<String> keys = new LinkedHashSet<>();
        if (field.options() != null) {
            for (Option option : field.options()) {
                keys.add(option.key());
            }
        }
        return keys;
    }

    private static void multi(Field field, JsonNode value) {
        if (!value.isArray()) {
            throw new EmrValueRules.InvalidValue("Choose from the options.");
        }
        Set<String> allowed = optionKeys(field);
        Set<String> seen = new HashSet<>();
        for (JsonNode one : value) {
            if (!one.isTextual() || !allowed.contains(one.textValue()) || !seen.add(one.textValue())) {
                throw new EmrValueRules.InvalidValue("Choose each option once, from the options.");
            }
        }
    }

    private static void vitals(JsonNode value) {
        if (!value.isObject()) {
            throw new EmrValueRules.InvalidValue("Enter the vitals as numbers.");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!VITALS_KEYS.contains(entry.getKey())) {
                throw new EmrValueRules.InvalidValue("This vitals block has a value that is not allowed.");
            }
            if (!entry.getValue().isNull()) {
                BigDecimal number = decimal(entry.getValue());
                if (number.signum() < 0 || number.compareTo(VITALS_LIMIT) > 0) {
                    throw new EmrValueRules.InvalidValue("Enter a number from 0 up.");
                }
            }
        }
    }

    // ---- Done ----

    /**
     * Done (ADR-020 D8 for a form): every shown, fillable required field has a value; when no shown field is required, at
     * least one fillable field has a value. A field hidden by its rule counts as not there.
     */
    public static boolean isDone(Definition form, JsonNode values) {
        Set<String> hidden = hiddenKeys(form, values);
        boolean anyRequired = false;
        boolean anyFilled = false;
        for (Field field : fields(form)) {
            if (hidden.contains(field.key()) || !fillable(field)) {
                continue;
            }
            boolean filled = !EmrValueRules.isEmpty(values.get(field.key()));
            if (Boolean.TRUE.equals(field.required())) {
                anyRequired = true;
                if (!filled) {
                    return false;
                }
            }
            anyFilled = anyFilled || filled;
        }
        return anyRequired || anyFilled;
    }

    // ---- score and band (rule C-9, tagged) ----

    /** The total of a SCORE field for these values; null (blank) until one source is answered. */
    public static BigDecimal total(Definition form, Field scoreField, JsonNode values) {
        Set<String> hidden = hiddenKeys(form, values);
        Map<String, Field> byKey = new HashMap<>();
        Map<String, List<String>> chosen = new HashMap<>();
        Map<String, BigDecimal> numbers = new HashMap<>();
        for (Field field : fields(form)) {
            byKey.put(field.key(), field);
            JsonNode value = values.get(field.key());
            if (hidden.contains(field.key()) || value == null || value.isNull()) {
                continue;
            }
            switch (field.type()) {
                case "YES_NO" -> {
                    if (value.isBoolean()) {
                        chosen.put(field.key(), List.of(value.booleanValue() ? "yes" : "no"));
                    }
                }
                case "SINGLE_CHOICE", "DROPDOWN" -> {
                    if (value.isTextual()) {
                        chosen.put(field.key(), List.of(value.textValue()));
                    }
                }
                case "MULTI_CHOICE" -> {
                    List<String> picked = new ArrayList<>();
                    for (JsonNode one : value) {
                        picked.add(one.asText());
                    }
                    chosen.put(field.key(), picked);
                }
                case "NUMBER" -> {
                    if (value.isNumber()) {
                        numbers.put(field.key(), value.decimalValue());
                    }
                }
                default -> {
                }
            }
        }
        if (scoreField.score() == null || scoreField.score().sourceFieldKeys() == null
                || scoreField.score().rules() == null) {
            return null;
        }
        return EmrFormRules.total(scoreField, byKey, chosen, numbers);
    }

    /** The band label of a SCORE_BAND field for these values; null when the total is blank. */
    public static String band(Definition form, Field bandField, JsonNode values) {
        if (bandField.band() == null || bandField.band().sourceFieldKey() == null) {
            return null;
        }
        for (Field field : fields(form)) {
            if (field.key().equals(bandField.band().sourceFieldKey()) && "SCORE".equals(field.type())) {
                return EmrFormRules.bandLabel(bandField.band(), total(form, field, values));
            }
        }
        return null;
    }

    /** True when a number is inside min and max but outside the normal range from and to ("Abnormal"). */
    public static boolean abnormal(Field field, JsonNode value) {
        if (!"NUMBER".equals(field.type()) || value == null || !value.isNumber()
                || (field.normalFrom() == null && field.normalTo() == null)) {
            return false;
        }
        BigDecimal number = value.decimalValue();
        return (field.normalFrom() != null && number.compareTo(field.normalFrom()) < 0)
                || (field.normalTo() != null && number.compareTo(field.normalTo()) > 0);
    }

    // ---- history lines ----

    /**
     * The labelled lines a signed form shows in the patient's history: each shown field that has a value, in form order,
     * with the score total and band the server works out. Choice values show the option label; a number outside its normal
     * range ends with "Abnormal".
     */
    public static List<Line> lines(Definition form, JsonNode values) {
        Set<String> hidden = hiddenKeys(form, values);
        List<Line> lines = new ArrayList<>();
        for (Field field : fields(form)) {
            if (hidden.contains(field.key())) {
                continue;
            }
            if ("SCORE".equals(field.type())) {
                BigDecimal total = total(form, field, values);
                if (total != null) {
                    lines.add(new Line(field.label(), total.stripTrailingZeros().toPlainString()));
                }
                continue;
            }
            if ("SCORE_BAND".equals(field.type())) {
                String label = band(form, field, values);
                if (label != null) {
                    lines.add(new Line(field.label(), label));
                }
                continue;
            }
            JsonNode value = values.get(field.key());
            if (!fillable(field) || EmrValueRules.isEmpty(value)) {
                continue;
            }
            String shown = display(field, value);
            if (shown != null && !shown.isBlank()) {
                lines.add(new Line(field.label(), abnormal(field, value) ? shown + " (Abnormal)" : shown));
            }
        }
        return lines;
    }

    private static String display(Field field, JsonNode value) {
        switch (field.type()) {
            case "YES_NO":
                return value.asBoolean() ? "Yes" : "No";
            case "NUMBER":
                return value.decimalValue().stripTrailingZeros().toPlainString()
                        + (field.unit() == null || field.unit().isBlank() ? "" : " " + field.unit());
            case "SINGLE_CHOICE":
            case "DROPDOWN":
                return optionLabel(field, value.asText());
            case "MULTI_CHOICE": {
                List<String> labels = new ArrayList<>();
                for (JsonNode one : value) {
                    labels.add(optionLabel(field, one.asText()));
                }
                return String.join(", ", labels);
            }
            case "VITALS_BLOCK": {
                List<String> parts = new ArrayList<>();
                value.fields().forEachRemaining(e -> {
                    if (!e.getValue().isNull()) {
                        parts.add(e.getKey() + " " + e.getValue().decimalValue().stripTrailingZeros().toPlainString());
                    }
                });
                return String.join(", ", parts);
            }
            case "MEDICINE_LIST": {
                List<String> names = new ArrayList<>();
                for (JsonNode row : value.path("rows")) {
                    names.add(row.path("name").asText());
                }
                return String.join(", ", names);
            }
            case "LAB_TESTS":
            case "DIAGNOSIS": {
                List<String> texts = new ArrayList<>();
                for (JsonNode item : value.path("items")) {
                    if (item.path("active").asBoolean(true)) {
                        texts.add(item.path("text").asText());
                    }
                }
                return String.join(", ", texts);
            }
            case "BODY_MAP": {
                int pins = value.path("pins").size();
                return pins == 0 ? "No findings" : pins + (pins == 1 ? " pin" : " pins");
            }
            default:
                return value.asText();
        }
    }

    private static String optionLabel(Field field, String key) {
        if ("YES_NO".equals(field.type())) {
            return "yes".equals(key) ? "Yes" : "No";
        }
        if (field.options() != null) {
            for (Option option : field.options()) {
                if (option.key().equals(key)) {
                    return option.label();
                }
            }
        }
        return key;
    }
}
