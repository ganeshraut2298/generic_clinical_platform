package com.sahaj.opd.application.emr.specialty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A small closed-shape checker for the specialty component values of docs/api/opd.yaml 0.5.0 (ADR-023 D2): the contract's
 * {@code additionalProperties: false} objects, ranges, steps and enums written once in Java. It cleans a value: unknown
 * properties are refused, server-stamped properties sent by a client are dropped (ignored, as the contract says), and the
 * messages are fixed plain English that never repeat what was typed. It holds no clinical rule: those are in
 * {@code com.sahaj.opd.domain.emr.specialty} and in {@link SpecialtyValues}.
 */
public final class Sp {

    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Sp() {
    }

    /** The messages of one check, named by the path of the value inside the component (for example entries[0].pulse). */
    public static final class Errors {
        private final List<FieldMessage> list = new ArrayList<>();
        private final String base;

        public Errors(String base) {
            this.base = base;
        }

        public void add(String path, String message) {
            if (list.size() < 100) {
                list.add(new FieldMessage(path.isEmpty() ? base : base + "." + path, message));
            }
        }

        public boolean any() {
            return !list.isEmpty();
        }

        public List<FieldMessage> messages() {
            return list;
        }
    }

    /** One value kind. {@code check} returns the cleaned value, or null after adding an error. */
    public interface Shape {
        JsonNode check(JsonNode value, String path, Errors errors);
    }

    /** A property of an object: required, optional, or server-only (ignored when sent). */
    public record Prop(String name, Shape shape, boolean required, boolean serverOnly) {
    }

    public static Prop req(String name, Shape shape) {
        return new Prop(name, shape, true, false);
    }

    public static Prop opt(String name, Shape shape) {
        return new Prop(name, shape, false, false);
    }

    /** A property only the server sets: dropped silently when a client sends it. */
    public static Prop srv(String name) {
        return new Prop(name, (v, p, e) -> v, false, true);
    }

    // ---- scalars ----

    public static Shape bool() {
        return (v, p, e) -> {
            if (!v.isBoolean()) {
                return fail(e, p, "Choose yes or no.");
            }
            return v;
        };
    }

    public static Shape i(long min, long max) {
        return (v, p, e) -> {
            if (!v.isIntegralNumber() || !v.canConvertToLong()) {
                return fail(e, p, "Enter a whole number.");
            }
            if (v.asLong() < min || v.asLong() > max) {
                return fail(e, p, "Enter " + min + " to " + max + ".");
            }
            return v;
        };
    }

    public static Shape n(double min, double max, double step) {
        return (v, p, e) -> {
            if (!v.isNumber()) {
                return fail(e, p, "Enter a number.");
            }
            BigDecimal value = v.decimalValue();
            if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
                return fail(e, p, "Enter " + plain(min) + " to " + plain(max) + ".");
            }
            if (step > 0 && value.remainder(BigDecimal.valueOf(step)).signum() != 0) {
                return fail(e, p, "Use steps of " + plain(step) + ".");
            }
            return v;
        };
    }

    public static Shape n(double min, double max) {
        return n(min, max, 0);
    }

    public static Shape s(int min, int max) {
        return (v, p, e) -> {
            if (!v.isTextual()) {
                return fail(e, p, "Enter text.");
            }
            String text = v.asText().strip();
            if (text.length() < min || text.length() > max) {
                return fail(e, p, min <= 0 ? "Enter up to " + max + " characters."
                        : "Enter " + min + " to " + max + " characters.");
            }
            if (HTML.matcher(text).find()) {
                return fail(e, p, "Remove the markup.");
            }
            return NODES.textNode(text);
        };
    }

    public static Shape e(String... values) {
        Set<String> allowed = Set.of(values);
        return (v, p, e) -> {
            if (!v.isTextual() || !allowed.contains(v.asText())) {
                return fail(e, p, "Choose a valid option.");
            }
            return v;
        };
    }

    public static Shape uuid() {
        return (v, p, e) -> {
            try {
                if (!v.isTextual()) {
                    return fail(e, p, "Send an id.");
                }
                UUID.fromString(v.asText());
                return NODES.textNode(v.asText().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return fail(e, p, "Send an id.");
            }
        };
    }

    public static Shape date() {
        return (v, p, e) -> {
            try {
                if (!v.isTextual()) {
                    return fail(e, p, "Enter a date.");
                }
                LocalDate.parse(v.asText());
                return v;
            } catch (DateTimeParseException ex) {
                return fail(e, p, "Enter a date.");
            }
        };
    }

    public static Shape dateTime() {
        return (v, p, e) -> {
            try {
                if (!v.isTextual()) {
                    return fail(e, p, "Enter a date and time.");
                }
                Instant.parse(v.asText());
                return v;
            } catch (DateTimeParseException ex) {
                return fail(e, p, "Enter a date and time.");
            }
        };
    }

    /** A value that may also be null (an empty string counts as null for text). */
    public static Shape nullable(Shape inner) {
        return (v, p, e) -> {
            if (v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                return NODES.nullNode();
            }
            return inner.check(v, p, e);
        };
    }

    public static Shape iN(long min, long max) {
        return nullable(i(min, max));
    }

    public static Shape nN(double min, double max, double step) {
        return nullable(n(min, max, step));
    }

    public static Shape sN(int max) {
        return nullable(s(1, max));
    }

    public static Shape eN(String... values) {
        return nullable(e(values));
    }

    public static Shape uuidN() {
        return nullable(uuid());
    }

    public static Shape dateN() {
        return nullable(date());
    }

    public static Shape dateTimeN() {
        return nullable(dateTime());
    }

    // ---- structures ----

    /** An array of at most {@code max} items; with {@code unique} no item repeats. */
    public static Shape arr(Shape item, int max, boolean unique) {
        return (v, p, e) -> {
            if (!v.isArray()) {
                return fail(e, p, "Send a list.");
            }
            if (v.size() > max) {
                return fail(e, p, "Send at most " + max + " items.");
            }
            ArrayNode out = NODES.arrayNode();
            Set<JsonNode> seen = new HashSet<>();
            boolean ok = true;
            for (int index = 0; index < v.size(); index++) {
                JsonNode cleaned = item.check(v.get(index), p + "[" + index + "]", e);
                if (cleaned == null) {
                    ok = false;
                    continue;
                }
                if (unique && !seen.add(cleaned)) {
                    ok = false;
                    e.add(p + "[" + index + "]", "This is listed twice.");
                    continue;
                }
                out.add(cleaned);
            }
            return ok ? out : null;
        };
    }

    public static Shape arr(Shape item, int max) {
        return arr(item, max, false);
    }

    public static Shape set(Shape item, int max) {
        return arr(item, max, true);
    }

    /** A closed object. Properties not listed are refused; server-only properties are dropped. */
    public static Shape obj(Prop... props) {
        Map<String, Prop> byName = new LinkedHashMap<>();
        for (Prop prop : props) {
            byName.put(prop.name(), prop);
        }
        return (v, p, e) -> {
            if (!v.isObject()) {
                return fail(e, p, "Send an object.");
            }
            ObjectNode out = NODES.objectNode();
            boolean ok = true;
            var names = v.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                Prop prop = byName.get(name);
                if (prop == null) {
                    e.add(path(p, name), "This value has a property that is not allowed.");
                    ok = false;
                    continue;
                }
                if (prop.serverOnly()) {
                    continue;
                }
                JsonNode cleaned = prop.shape().check(v.get(name), path(p, name), e);
                if (cleaned == null) {
                    ok = false;
                } else {
                    out.set(name, cleaned);
                }
            }
            for (Prop prop : props) {
                if (prop.required() && !prop.serverOnly() && !v.has(prop.name())) {
                    e.add(path(p, prop.name()), "This is needed.");
                    ok = false;
                }
            }
            return ok ? out : null;
        };
    }

    /** The two properties every component value carries. */
    public static Prop[] header(String component, Prop... more) {
        List<Prop> all = new ArrayList<>();
        all.add(req("component", e(component)));
        all.add(req("schemaVersion", i(1, 1)));
        for (Prop prop : more) {
            all.add(prop);
        }
        return all.toArray(new Prop[0]);
    }

    // ---- helpers ----

    private static JsonNode fail(Errors errors, String path, String message) {
        errors.add(path, message);
        return null;
    }

    private static String path(String parent, String name) {
        return parent.isEmpty() ? name : parent + "." + name;
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
