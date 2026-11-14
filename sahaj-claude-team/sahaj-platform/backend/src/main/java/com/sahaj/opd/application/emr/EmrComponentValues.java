package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation, normalisation and server stamping of the three shared component values (ADR-022 D3, D4, D8): the EMR
 * list, the prescription and the body map. The client sends a value; the server keeps what the client may set, stamps
 * what only the server may set ({@code addedAt}, {@code addedByMembershipId}, {@code recordedAt}, {@code code},
 * {@code reviewedAt}, {@code nextPinNo}, the MLC rule) from the value already stored, and answers 400 for anything
 * outside the fixed lists. Messages are fixed plain English and never echo what was typed. Clinical text is never
 * logged here; the audit notes returned hold ids, pin numbers and fixed words only.
 */
@org.springframework.stereotype.Component
public final class EmrComponentValues {

    public static final int MAX_LIST_ITEMS = 50;
    public static final int MAX_RX_ROWS = 30;
    public static final int MAX_PINS = 60;
    static final int MAX_TEXT = 200;
    static final int MAX_MEDICINE_NAME = 160;
    static final int MAX_FORM = 40;

    public static final List<String> FREQUENCIES = List.of("1-0-0", "1-0-1", "0-0-1", "1-1-1");
    public static final List<String> WHEN = List.of("AFTER_FOOD", "BEFORE_FOOD", "BEDTIME", "MORNING_EMPTY_STOMACH");
    private static final List<String> RX_SOURCES = List.of("SEARCH", "FAVOURITE", "REPEAT", "COPY", "TYPED",
            "DOSE_BY_WEIGHT", "SPECIALTY_LIST");
    /** 0.5.0 adds 0-1-0 (Calcium + Vit D3 supplement, SAH-61-4); the SAH-48 chips still offer the first four. */
    private static final List<String> RX_FREQUENCIES = List.of("1-0-0", "1-0-1", "0-0-1", "1-1-1", "0-1-0");

    /** attr1 and attr2 value sets and defaults per list kind (mockup #EmrList). */
    private record Attrs(List<String> first, String firstDefault, List<String> second, String secondDefault) {
    }

    private static final Map<String, Attrs> ATTRS = Map.of(
            "COMPLAINTS", new Attrs(List.of("D1", "D2", "D3", "D5", "W1", "W2", "M1", "OVER_M1"), "D3",
                    List.of("MILD", "MODERATE", "SEVERE"), "MODERATE"),
            "DIAGNOSIS", new Attrs(List.of("PROVISIONAL", "FINAL", "DIFFERENTIAL", "COMORBIDITY"), "PROVISIONAL",
                    List.of("ACTIVE", "CHRONIC", "RESOLVED"), "ACTIVE"),
            "INVESTIGATIONS", new Attrs(List.of("ROUTINE", "URGENT", "BEFORE_NEXT_VISIT"), "ROUTINE",
                    List.of("CLINIC_LAB", "OUTSIDE_LAB"), "CLINIC_LAB"),
            "ADVICE", new Attrs(List.of("EN", "MR", "HI"), "EN", List.of("PRINT", "WHATSAPP", "PRINT_WHATSAPP"),
                    "PRINT"),
            "SERVICES", new Attrs(List.of("1", "2", "3", "4"), "1", List.of("BILL_TODAY", "NO_CHARGE", "IN_PACKAGE"),
                    "BILL_TODAY"));

    /** The 17 drawn regions of the mockup's body map, left and right separate (key to label). */
    public static final Map<String, String> REGIONS = regions();

    private static final List<String> FINDINGS = List.of("WOUND", "SWELLING", "TENDERNESS", "BRUISE", "BURN", "RASH",
            "LUMP", "FRACTURE_SUSPECTED", "SCAR", "PAIN");
    private static final List<String> CAUSES = List.of("NO_INJURY", "FALL", "ROAD_ACCIDENT", "ASSAULT", "ANIMAL_BITE",
            "BURN", "SPORTS", "WORK_INJURY");
    private static final Set<String> MLC_CAUSES = Set.of("ROAD_ACCIDENT", "ASSAULT", "BURN");
    private static final List<String> SEVERITIES = List.of("MILD", "MODERATE", "SEVERE");
    private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
    private static final Pattern ICD = Pattern.compile("^[A-Z][0-9A-Z.]{1,9}$");

    private static Map<String, String> regions() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("head", "Head");
        r.put("neck", "Neck");
        r.put("chest", "Chest");
        r.put("abdomen", "Abdomen");
        r.put("pelvis", "Pelvis and groin");
        r.put("ruarm", "Right upper arm");
        r.put("luarm", "Left upper arm");
        r.put("rfarm", "Right forearm");
        r.put("lfarm", "Left forearm");
        r.put("rhand", "Right hand");
        r.put("lhand", "Left hand");
        r.put("rthigh", "Right thigh");
        r.put("lthigh", "Left thigh");
        r.put("rleg", "Right leg");
        r.put("lleg", "Left leg");
        r.put("rfoot", "Right foot");
        r.put("lfoot", "Left foot");
        return Collections.unmodifiableMap(r);
    }

    /** A medicine of the SAH-7 master with the phase 3 allergy flag and row defaults. */
    public record MedicineRef(UUID id, String name, String form, String allergyClass, String defaultFrequency,
                              Integer defaultDays, String defaultWhen) {
    }

    /** What the normaliser asks of the database; implemented over the repositories, faked in tests. */
    public interface Lookups {
        Optional<MedicineRef> medicine(UUID medicineId);

        /** True when the service is one of this clinic's active services. */
        boolean activeService(UUID serviceId);

        /** SAH-67-1: true when the drug and frequency keys are in ipd's catalogue (a fake in tests accepts every key). */
        default boolean ipdOrderKeys(String drugKey, String frequencyKey) {
            return true;
        }
    }

    /** The request: who, when, and the default language for new advice items. */
    public record Context(Instant now, UUID membershipId, String adviceLanguage, Lookups lookups) {
    }

    /** One thing worth an audit entry, found while normalising (ids, pin numbers and fixed words only). */
    public record Note(String kind, Map<String, Object> values) {
    }

    public record Result(ObjectNode value, List<Note> notes) {
    }

    private final ObjectMapper mapper;

    public EmrComponentValues(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Normalises a COMPONENT field's new value against the stored one (null when nothing is stored yet). */
    public Result normalize(FieldDefinition field, JsonNode value, JsonNode stored, Context context) {
        JsonNode old = upgrade(field, stored);
        return switch (field.component().type()) {
            case EmrComponentBinding.EMR_LIST -> list(field.component().listKind(), value, old, context);
            case EmrComponentBinding.PRESCRIPTION -> prescription(value, old, context);
            case EmrComponentBinding.FOLLOW_UP -> followUp(value);
            case EmrComponentBinding.IPD_ORDERS -> ipdOrders(value, old, context);
            default -> bodyMap(value, old, context);
        };
    }

    // ---- legacy values (ADR-022 D2) ----

    /**
     * Reads a phase 2 value as the component value: an ITEM_LIST array becomes active items with default attributes,
     * {@code {text}} of a body map becomes a body map with no pins and the text kept. A component value is returned
     * as it is. Null stays null.
     */
    public JsonNode upgrade(FieldDefinition field, JsonNode stored) {
        if (stored == null || stored.isNull() || stored.isMissingNode() || field.component() == null) {
            return stored;
        }
        if (stored.isObject() && stored.has("component")) {
            return stored;
        }
        String type = field.component().type();
        if (EmrComponentBinding.FOLLOW_UP.equals(type)) {
            // a phase 2 date or text is not an interval: it stays in the stored draft and is not read as one (ADR-025 D6)
            return null;
        }
        if (EmrComponentBinding.EMR_LIST.equals(type) && stored.isArray()) {
            String kind = field.component().listKind();
            Attrs attrs = ATTRS.get(kind);
            ObjectNode root = mapper.createObjectNode();
            root.put("component", type);
            root.put("kind", kind);
            root.put("schemaVersion", 1);
            ArrayNode items = root.putArray("items");
            for (JsonNode entry : stored) {
                if (!entry.isObject() || !entry.path("text").isTextual() || items.size() >= MAX_LIST_ITEMS) {
                    continue;
                }
                ObjectNode item = items.addObject();
                item.put("id", UUID.nameUUIDFromBytes((field.key() + "/" + items.size() + "/"
                        + entry.path("text").asText()).getBytes(StandardCharsets.UTF_8)).toString());
                if (entry.path("itemKey").isTextual()) {
                    item.put("itemKey", entry.path("itemKey").asText());
                } else {
                    item.putNull("itemKey");
                }
                item.put("text", entry.path("text").asText());
                item.putNull("code");
                item.put("newToList", entry.path("newToList").asBoolean(false));
                item.put("attr1", attrs.firstDefault());
                item.put("attr2", attrs.secondDefault());
                item.put("active", true);
                item.putNull("addedAt");
                item.putNull("addedByMembershipId");
            }
            return root;
        }
        if (EmrComponentBinding.BODY_MAP.equals(type) && stored.isObject() && stored.path("text").isTextual()) {
            ObjectNode root = mapper.createObjectNode();
            root.put("component", EmrComponentBinding.BODY_MAP);
            root.put("schemaVersion", 1);
            root.putArray("pins");
            root.put("nextPinNo", 1);
            root.put("noFindingsConfirmed", false);
            root.put("legacyText", stored.path("text").asText());
            return root;
        }
        return stored;
    }

    // ---- the Done rule (ADR-022 D2) ----

    /** True when a component value counts as filled: an active item, a prescription row, a pin or "no findings". */
    public static boolean isFilled(JsonNode value) {
        if (value == null || !value.isObject()) {
            return false;
        }
        switch (value.path("component").asText("")) {
            case EmrComponentBinding.EMR_LIST:
                for (JsonNode item : value.path("items")) {
                    if (item.path("active").asBoolean(true)) {
                        return true;
                    }
                }
                return false;
            case EmrComponentBinding.PRESCRIPTION:
                return value.path("rows").isArray() && !value.path("rows").isEmpty();
            case EmrComponentBinding.FOLLOW_UP:
                return value.path("interval").isTextual();
            case EmrComponentBinding.IPD_ORDERS:
                return value.path("rows").isArray() && !value.path("rows").isEmpty();
            case EmrComponentBinding.BODY_MAP:
                return (value.path("pins").isArray() && !value.path("pins").isEmpty())
                        || value.path("noFindingsConfirmed").asBoolean(false);
            default:
                return false;
        }
    }

    // ---- EMR list ----

    private Result list(String kind, JsonNode value, JsonNode old, Context context) {
        ObjectNode input = object(value, "Send the list as an object.");
        only(input, Set.of("component", "kind", "schemaVersion", "items"),
                "This list has a property that is not allowed.");
        if (!EmrComponentBinding.EMR_LIST.equals(input.path("component").asText())
                || !kind.equals(input.path("kind").asText()) || input.path("schemaVersion").asInt(1) != 1) {
            throw new EmrValueRules.InvalidValue("This is not a list of the right kind.");
        }
        JsonNode items = input.path("items");
        if (!items.isArray()) {
            throw new EmrValueRules.InvalidValue("Add items to the list.");
        }
        if (items.size() > MAX_LIST_ITEMS) {
            throw new EmrValueRules.InvalidValue("Add at most 50 items.");
        }
        Attrs attrs = ATTRS.get(kind);
        Map<String, JsonNode> before = byId(old, "items");
        ObjectNode out = mapper.createObjectNode();
        out.put("component", EmrComponentBinding.EMR_LIST);
        out.put("kind", kind);
        out.put("schemaVersion", 1);
        ArrayNode result = out.putArray("items");
        Set<String> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (JsonNode raw : items) {
            ObjectNode item = object(raw, "Each item must be an object.");
            only(item, Set.of("id", "itemKey", "text", "code", "newToList", "attr1", "attr2", "active", "addedAt",
                    "addedByMembershipId", "addedByName", "unitPricePaise", "billed"),
                    "An item has a property that is not allowed.");
            UUID id = uuid(item.path("id"), "Each item needs an id.");
            if (!ids.add(id.toString())) {
                throw new EmrValueRules.InvalidValue("An item id is used twice.");
            }
            String text = text(item.path("text"), 1, MAX_TEXT, "Each item needs a name of up to 200 characters.");
            String itemKey = null;
            if (item.path("itemKey").isTextual()) {
                itemKey = item.path("itemKey").asText();
                if (itemKey.isEmpty() || itemKey.length() > EmrValueRules.MAX_ITEM_KEY) {
                    throw new EmrValueRules.InvalidValue("An item key is up to 40 characters.");
                }
            } else if (!item.path("itemKey").isNull() && !item.path("itemKey").isMissingNode()) {
                throw new EmrValueRules.InvalidValue("An item key is up to 40 characters.");
            }
            boolean typed = itemKey == null;
            if (item.path("newToList").isBoolean() && item.path("newToList").asBoolean() != typed) {
                throw new EmrValueRules.InvalidValue("A typed item has no key; a listed item has one.");
            }
            if (typed) {
                text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
            if (!texts.add(text.toLowerCase(Locale.ROOT))) {
                throw EmrProblemException.duplicateItem();
            }
            String defaultFirst = "ADVICE".equals(kind) && context.adviceLanguage() != null
                    && attrs.first().contains(context.adviceLanguage()) ? context.adviceLanguage()
                    : attrs.firstDefault();
            String attr1 = attribute(item.path("attr1"), attrs.first(), defaultFirst);
            String attr2 = attribute(item.path("attr2"), attrs.second(), attrs.secondDefault());
            if ("ADVICE".equals(kind) && !"PRINT".equals(attr2)) {
                throw EmrProblemException.needsWhatsappService();
            }
            if ("SERVICES".equals(kind)) {
                UUID serviceId = null;
                if (!typed) {
                    try {
                        serviceId = UUID.fromString(itemKey);
                    } catch (IllegalArgumentException e) {
                        serviceId = null;
                    }
                }
                if (serviceId == null || !context.lookups().activeService(serviceId)) {
                    throw new EmrValueRules.InvalidValue("Choose a service from the clinic's price list.");
                }
            }
            String code = null;
            if ("DIAGNOSIS".equals(kind) && !typed) {
                if (!ICD.matcher(itemKey).matches()) {
                    throw new EmrValueRules.InvalidValue("Choose a diagnosis from the list.");
                }
                code = itemKey;
            }
            if (item.has("active") && !item.path("active").isBoolean()) {
                throw new EmrValueRules.InvalidValue("Active is yes or no.");
            }
            ObjectNode stored = result.addObject();
            stored.put("id", id.toString());
            if (typed) {
                stored.putNull("itemKey");
            } else {
                stored.put("itemKey", itemKey);
            }
            stored.put("text", text);
            if (code == null) {
                stored.putNull("code");
            } else {
                stored.put("code", code);
            }
            stored.put("newToList", typed);
            stored.put("attr1", attr1);
            stored.put("attr2", attr2);
            stored.put("active", !item.has("active") || item.path("active").asBoolean(true));
            stamp(stored, before.get(id.toString()), "addedAt", "addedByMembershipId", context);
        }
        return new Result(out, List.of());
    }

    private static String attribute(JsonNode node, List<String> allowed, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (!node.isTextual() || !allowed.contains(node.asText())) {
            throw new EmrValueRules.InvalidValue("Choose a value from the list.");
        }
        return node.asText();
    }

    // ---- follow-up interval (ADR-025 D6) ----

    static final List<String> FOLLOW_UP_INTERVALS = List.of("D3", "D7", "D15", "M1", "M3");

    private Result followUp(JsonNode value) {
        ObjectNode input = object(value, "Send the follow-up as an object.");
        only(input, Set.of("component", "schemaVersion", "interval", "dueDate"),
                "This follow-up has a property that is not allowed.");
        if (!EmrComponentBinding.FOLLOW_UP.equals(input.path("component").asText())
                || input.path("schemaVersion").asInt(1) != 1) {
            throw new EmrValueRules.InvalidValue("This is not a follow-up.");
        }
        JsonNode interval = input.path("interval");
        if (!interval.isNull() && !interval.isMissingNode()
                && (!interval.isTextual() || !FOLLOW_UP_INTERVALS.contains(interval.asText()))) {
            throw new EmrValueRules.InvalidValue("Choose 3 days, 7 days, 15 days, 1 month or 3 months.");
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("component", EmrComponentBinding.FOLLOW_UP);
        out.put("schemaVersion", 1);
        if (interval.isTextual()) {
            out.put("interval", interval.asText());
        } else {
            out.putNull("interval");
        }
        return new Result(out, List.of());
    }

    // ---- IPD round orders (ADR-025 D8) ----

    static final int MAX_IPD_ORDER_ROWS = 20;

    private Result ipdOrders(JsonNode value, JsonNode old, Context context) {
        ObjectNode input = object(value, "Send the medicines as an object.");
        only(input, Set.of("component", "schemaVersion", "rows"),
                "These medicines have a property that is not allowed.");
        if (!EmrComponentBinding.IPD_ORDERS.equals(input.path("component").asText())
                || input.path("schemaVersion").asInt(1) != 1) {
            throw new EmrValueRules.InvalidValue("These are not round medicines.");
        }
        JsonNode rows = input.path("rows");
        if (!rows.isArray()) {
            throw new EmrValueRules.InvalidValue("Add rows to the medicines.");
        }
        if (rows.size() > MAX_IPD_ORDER_ROWS) {
            throw new EmrValueRules.InvalidValue("Add at most 20 medicines.");
        }
        Map<String, JsonNode> before = byId(old, "rows");
        ObjectNode out = mapper.createObjectNode();
        out.put("component", EmrComponentBinding.IPD_ORDERS);
        out.put("schemaVersion", 1);
        ArrayNode result = out.putArray("rows");
        Set<String> ids = new HashSet<>();
        for (JsonNode raw : rows) {
            ObjectNode row = object(raw, "Each medicine must be an object.");
            only(row, Set.of("id", "drugKey", "frequencyKey", "overrideReasonKey", "checkToken", "orderId",
                    "orderedAt"), "A medicine has a property that is not allowed.");
            String id = row.path("id").asText("");
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new EmrValueRules.InvalidValue("Each medicine needs an id.");
            }
            if (!ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new EmrValueRules.InvalidValue("A medicine is listed twice.");
            }
            String drug = row.path("drugKey").asText("");
            String frequency = row.path("frequencyKey").asText("");
            if (drug.isBlank() || drug.length() > 40 || frequency.isBlank() || frequency.length() > 20
                    || !context.lookups().ipdOrderKeys(drug, frequency)) {
                throw new EmrValueRules.InvalidValue("Choose a medicine and how often from the lists.");
            }
            ObjectNode clean = result.addObject();
            clean.put("id", id);
            clean.put("drugKey", drug);
            clean.put("frequencyKey", frequency);
            // orderId and orderedAt are stamped by the server at sign: what the client sends is ignored
            JsonNode previous = before.get(id.toLowerCase(Locale.ROOT));
            if (previous != null && previous.path("orderId").isTextual()) {
                clean.put("orderId", previous.path("orderId").asText());
                clean.set("orderedAt", previous.path("orderedAt"));
            } else {
                clean.putNull("orderId");
                clean.putNull("orderedAt");
            }
        }
        return new Result(out, List.of());
    }

    // ---- prescription ----

    private Result prescription(JsonNode value, JsonNode old, Context context) {
        ObjectNode input = object(value, "Send the prescription as an object.");
        only(input, Set.of("component", "schemaVersion", "rows"),
                "This prescription has a property that is not allowed.");
        if (!EmrComponentBinding.PRESCRIPTION.equals(input.path("component").asText())
                || input.path("schemaVersion").asInt(1) != 1) {
            throw new EmrValueRules.InvalidValue("This is not a prescription.");
        }
        JsonNode rows = input.path("rows");
        if (!rows.isArray()) {
            throw new EmrValueRules.InvalidValue("Add rows to the prescription.");
        }
        if (rows.size() > MAX_RX_ROWS) {
            throw new EmrValueRules.InvalidValue("Add at most 30 medicines.");
        }
        Map<String, JsonNode> before = byId(old, "rows");
        ObjectNode out = mapper.createObjectNode();
        out.put("component", EmrComponentBinding.PRESCRIPTION);
        out.put("schemaVersion", 1);
        ArrayNode result = out.putArray("rows");
        List<Note> notes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode raw : rows) {
            ObjectNode row = object(raw, "Each medicine must be an object.");
            only(row, Set.of("id", "medicineId", "name", "form", "frequency", "days", "when", "source",
                    "allergyReview", "addedAt", "addedByMembershipId", "doseText", "frequencyText", "note",
                    "calculation"),
                    "A medicine has a property that is not allowed.");
            UUID id = uuid(row.path("id"), "Each medicine needs an id.");
            if (!ids.add(id.toString())) {
                throw new EmrValueRules.InvalidValue("A medicine id is used twice.");
            }
            UUID medicineId = null;
            String name;
            String form;
            if (row.path("medicineId").isTextual()) {
                medicineId = uuid(row.path("medicineId"), "Choose a medicine from the list.");
                MedicineRef medicine = context.lookups().medicine(medicineId)
                        .orElseThrow(() -> new EmrValueRules.InvalidValue("Choose a medicine from the list."));
                name = medicine.name();
                form = medicine.form();
            } else if (row.path("medicineId").isNull() || row.path("medicineId").isMissingNode()) {
                name = text(row.path("name"), 1, MAX_MEDICINE_NAME, "Enter a medicine name of up to 160 characters.");
                form = formOf(row.path("form"));
            } else {
                throw new EmrValueRules.InvalidValue("Choose a medicine from the list.");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw EmrProblemException.duplicateItem();
            }
            String frequencyText = optionalText(row.path("frequencyText"), 120, "Enter up to 120 characters.");
            String frequency = row.path("frequency").isNull() || row.path("frequency").isMissingNode() ? null
                    : choice(row.path("frequency"), RX_FREQUENCIES, "Choose a frequency from the list.");
            if (frequency == null && frequencyText == null) {
                throw new EmrValueRules.InvalidValue("Choose a frequency from the list.");
            }
            String doseText = optionalText(row.path("doseText"), 60, "Enter up to 60 characters.");
            String note = optionalText(row.path("note"), 120, "Enter up to 120 characters.");
            ObjectNode calculation = calculation(row.path("calculation"));
            String when = choice(row.path("when"), WHEN, "Choose when to take it from the list.");
            JsonNode daysNode = row.path("days");
            if (!daysNode.isIntegralNumber() || daysNode.asInt() < 1 || daysNode.asInt() > 365) {
                throw new EmrValueRules.InvalidValue("Days are from 1 to 365.");
            }
            String source = "TYPED";
            if (medicineId != null) {
                source = choice(row.path("source"), RX_SOURCES, "Choose where this medicine came from.");
                if ("TYPED".equals(source)) {
                    source = "SEARCH";
                }
            }
            ObjectNode stored = result.addObject();
            stored.put("id", id.toString());
            if (medicineId == null) {
                stored.putNull("medicineId");
            } else {
                stored.put("medicineId", medicineId.toString());
            }
            stored.put("name", name);
            if (form == null) {
                stored.putNull("form");
            } else {
                stored.put("form", form);
            }
            if (frequency == null) {
                stored.putNull("frequency");
            } else {
                stored.put("frequency", frequency);
            }
            putOrNull(stored, "doseText", doseText);
            putOrNull(stored, "frequencyText", frequencyText);
            putOrNull(stored, "note", note);
            if (calculation == null) {
                stored.putNull("calculation");
            } else {
                stored.set("calculation", calculation);
            }
            stored.put("days", daysNode.asInt());
            stored.put("when", when);
            stored.put("source", source);
            JsonNode previous = before.get(id.toString());
            stamp(stored, previous, "addedAt", "addedByMembershipId", context);
            review(row.path("allergyReview"), previous, stored, id, medicineId, context, notes);
        }
        return new Result(out, List.copyOf(notes));
    }

    private static String optionalText(JsonNode node, int max, String message) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual() || node.asText().strip().length() > max || HTML.matcher(node.asText()).find()) {
            throw new EmrValueRules.InvalidValue(message);
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static void putOrNull(ObjectNode target, String property, String value) {
        if (value == null) {
            target.putNull(property);
        } else {
            target.put(property, value);
        }
    }

    /**
     * Dose by weight (SAH-62-3, ADR-023 D4, tagged C-5): the server recomputes mg = min(max mg, round(weight x mg per kg))
     * and ml = round(mg / mg per ml, 1) for the dose rule and refuses a mismatch (400 dose-calculation-mismatch).
     */
    private ObjectNode calculation(JsonNode sent) {
        if (sent == null || sent.isNull() || sent.isMissingNode()) {
            return null;
        }
        ObjectNode calc = object(sent, "The calculation must be an object.");
        only(calc, Set.of("doseRuleKey", "weightKg", "mg", "ml", "capped"),
                "The calculation has a property that is not allowed.");
        var rule = com.sahaj.opd.domain.emr.specialty.PaedsRules.doseRule(calc.path("doseRuleKey").asText(null));
        if (rule == null || !calc.path("weightKg").isNumber() || calc.path("weightKg").asDouble() < 0.5
                || calc.path("weightKg").asDouble() > 150 || !calc.path("mg").isIntegralNumber()
                || !calc.path("ml").isNumber()) {
            throw new EmrValueRules.InvalidValue("Check the dose by weight.");
        }
        var dose = com.sahaj.opd.domain.emr.specialty.PaedsRules.dose(rule, calc.path("weightKg").asDouble());
        if (dose == null || dose.mg() != calc.path("mg").asInt()
                || Math.abs(dose.ml() - calc.path("ml").asDouble()) > 0.001) {
            throw EmrProblemException.of(400, "dose-calculation-mismatch", "Dose does not match the weight",
                    "The dose does not match the weight. Add the dose again.");
        }
        ObjectNode stored = mapper.createObjectNode();
        stored.put("doseRuleKey", rule.key());
        stored.put("weightKg", calc.path("weightKg").asDouble());
        stored.put("mg", dose.mg());
        stored.put("ml", dose.ml());
        stored.put("capped", dose.capped());
        return stored;
    }

    private void review(JsonNode sent, JsonNode previous, ObjectNode stored, UUID rowId, UUID medicineId,
                        Context context, List<Note> notes) {
        if (sent == null || sent.isNull() || sent.isMissingNode()) {
            stored.putNull("allergyReview");
            return;
        }
        ObjectNode review = object(sent, "The allergy review must be an object.");
        only(review, Set.of("allergyClass", "confirmed", "reviewedAt", "reviewedByMembershipId"),
                "The allergy review has a property that is not allowed.");
        String allergyClass = text(review.path("allergyClass"), 1, 20, "Choose the allergy class.");
        if (!review.path("confirmed").isBoolean()) {
            throw new EmrValueRules.InvalidValue("Confirm the allergy review with yes or no.");
        }
        if (!review.path("confirmed").asBoolean()) {
            stored.putNull("allergyReview");
            return;
        }
        ObjectNode kept = stored.putObject("allergyReview");
        kept.put("allergyClass", allergyClass);
        kept.put("confirmed", true);
        JsonNode earlier = previous == null ? null : previous.path("allergyReview");
        if (earlier != null && earlier.path("confirmed").asBoolean(false)
                && allergyClass.equals(earlier.path("allergyClass").asText())
                && earlier.path("reviewedAt").isTextual()) {
            kept.set("reviewedAt", earlier.get("reviewedAt"));
            kept.set("reviewedByMembershipId", earlier.path("reviewedByMembershipId"));
        } else {
            kept.put("reviewedAt", context.now().toString());
            kept.put("reviewedByMembershipId", context.membershipId().toString());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("rowId", rowId.toString());
            values.put("medicineKey", medicineId == null ? null : medicineId.toString());
            values.put("allergyClass", allergyClass);
            notes.add(new Note("ALLERGY_REVIEWED", values));
        }
    }

    // ---- body map ----

    /**
     * SAH-75-5 (ADR-026 D5): the same pin rules, stamps and MLC rule as the shared BODY_MAP component, for the body map
     * inside the oncology examination value.
     */
    public Result normalizeBodyMap(JsonNode value, JsonNode old, Context context) {
        return bodyMap(value, old, context);
    }

    private Result bodyMap(JsonNode value, JsonNode old, Context context) {
        ObjectNode input = object(value, "Send the body map as an object.");
        only(input, Set.of("component", "schemaVersion", "pins", "nextPinNo", "noFindingsConfirmed", "legacyText"),
                "This body map has a property that is not allowed.");
        if (!EmrComponentBinding.BODY_MAP.equals(input.path("component").asText())
                || input.path("schemaVersion").asInt(1) != 1) {
            throw new EmrValueRules.InvalidValue("This is not a body map.");
        }
        JsonNode pins = input.path("pins");
        if (!pins.isArray()) {
            throw new EmrValueRules.InvalidValue("Add pins to the body map.");
        }
        if (pins.size() > MAX_PINS) {
            throw new EmrValueRules.InvalidValue("Add at most 60 pins.");
        }
        if (input.has("noFindingsConfirmed") && !input.path("noFindingsConfirmed").isBoolean()) {
            throw new EmrValueRules.InvalidValue("No findings is yes or no.");
        }
        Map<Integer, JsonNode> before = new HashMap<>();
        int nextBefore = 1;
        if (old != null && old.isObject() && "BODY_MAP".equals(old.path("component").asText())) {
            for (JsonNode pin : old.path("pins")) {
                before.put(pin.path("no").asInt(), pin);
            }
            nextBefore = Math.max(1, old.path("nextPinNo").asInt(1));
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("component", EmrComponentBinding.BODY_MAP);
        out.put("schemaVersion", 1);
        ArrayNode result = out.putArray("pins");
        List<Note> notes = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        int highest = nextBefore - 1;
        for (JsonNode raw : pins) {
            ObjectNode pin = object(raw, "Each pin must be an object.");
            only(pin, Set.of("no", "side", "x", "y", "region", "where", "finding", "cause", "severity", "sizeCm",
                    "note", "mlc", "recordedAt", "recordedByMembershipId", "recordedByName"),
                    "A pin has a property that is not allowed.");
            JsonNode noNode = pin.path("no");
            if (!noNode.isIntegralNumber() || noNode.asInt() < 1 || noNode.asInt() > 999) {
                throw new EmrValueRules.InvalidValue("A pin number is from 1 to 999.");
            }
            int no = noNode.asInt();
            if (!numbers.add(no)) {
                throw new EmrValueRules.InvalidValue("A pin number is used twice.");
            }
            JsonNode previous = before.get(no);
            if (previous == null && no < nextBefore) {
                throw new EmrValueRules.InvalidValue("A pin number is never reused.");
            }
            String side = choice(pin.path("side"), List.of("FRONT", "BACK"), "Choose front or back.");
            double x = unit(pin.path("x"));
            double y = unit(pin.path("y"));
            String region = text(pin.path("region"), 1, 40, "Choose a body region.");
            if (!REGIONS.containsKey(region)) {
                throw new EmrValueRules.InvalidValue("Choose a body region from the drawing.");
            }
            String where = text(pin.path("where"), 0, 80, "Where is up to 80 characters.");
            String finding = choice(pin.path("finding"), FINDINGS, "Choose a finding from the list.");
            String cause = choice(pin.path("cause"), CAUSES, "Choose a cause from the list.");
            String severity = choice(pin.path("severity"), SEVERITIES, "Choose a severity from the list.");
            BigDecimal size = pin.path("sizeCm").isNumber() ? pin.path("sizeCm").decimalValue() : null;
            if (size == null || size.compareTo(BigDecimal.ZERO) < 0 || size.compareTo(new BigDecimal("50")) > 0
                    || size.multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
                throw new EmrValueRules.InvalidValue("Size is 0 to 50 cm in steps of 0.5.");
            }
            String note = text(pin.path("note"), 0, 500, "The note is up to 500 characters.");
            if (!pin.path("mlc").isBoolean()) {
                throw new EmrValueRules.InvalidValue("Medico-legal case is yes or no.");
            }
            boolean causeChanged = previous == null || !cause.equals(previous.path("cause").asText());
            boolean mlc = pin.path("mlc").asBoolean() || (MLC_CAUSES.contains(cause) && causeChanged);
            boolean wasMlc = previous != null && previous.path("mlc").asBoolean(false);
            if (mlc != wasMlc) {
                notes.add(mlcNote(no, mlc));
            }
            ObjectNode stored = result.addObject();
            stored.put("no", no);
            stored.put("side", side);
            stored.put("x", x);
            stored.put("y", y);
            stored.put("region", region);
            stored.put("where", where);
            stored.put("finding", finding);
            stored.put("cause", cause);
            stored.put("severity", severity);
            stored.put("sizeCm", size);
            stored.put("note", note);
            stored.put("mlc", mlc);
            stamp(stored, previous, "recordedAt", "recordedByMembershipId", context);
            highest = Math.max(highest, no);
        }
        for (Map.Entry<Integer, JsonNode> removed : before.entrySet()) {
            if (!numbers.contains(removed.getKey()) && removed.getValue().path("mlc").asBoolean(false)) {
                notes.add(mlcNote(removed.getKey(), false));
            }
        }
        out.put("nextPinNo", Math.max(nextBefore, highest + 1));
        out.put("noFindingsConfirmed", input.path("noFindingsConfirmed").asBoolean(false));
        if (old != null && old.path("legacyText").isTextual()) {
            out.put("legacyText", old.path("legacyText").asText());
        }
        return new Result(out, List.copyOf(notes));
    }

    private static Note mlcNote(int no, boolean mlc) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("pinNo", no);
        values.put("mlc", mlc);
        return new Note("MLC_CHANGED", values);
    }

    /** True when any pin of a body map value is an MLC pin (record header "mlc"). */
    public static boolean hasMlcPin(JsonNode value) {
        if (value == null || !EmrComponentBinding.BODY_MAP.equals(value.path("component").asText())) {
            return false;
        }
        for (JsonNode pin : value.path("pins")) {
            if (pin.path("mlc").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    // ---- helpers ----

    private static void stamp(ObjectNode stored, JsonNode previous, String atKey, String byKey, Context context) {
        if (previous != null && previous.path(atKey).isTextual()) {
            stored.set(atKey, previous.get(atKey));
            stored.set(byKey, previous.path(byKey));
        } else {
            stored.put(atKey, context.now().toString());
            stored.put(byKey, context.membershipId().toString());
        }
    }

    private Map<String, JsonNode> byId(JsonNode old, String arrayKey) {
        Map<String, JsonNode> byId = new HashMap<>();
        if (old != null && old.isObject()) {
            for (JsonNode entry : old.path(arrayKey)) {
                if (entry.path("id").isTextual()) {
                    byId.put(entry.path("id").asText(), entry);
                }
            }
        }
        return byId;
    }

    private static ObjectNode object(JsonNode node, String message) {
        if (node == null || !node.isObject()) {
            throw new EmrValueRules.InvalidValue(message);
        }
        return (ObjectNode) node;
    }

    private static void only(ObjectNode node, Set<String> allowed, String message) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new EmrValueRules.InvalidValue(message);
            }
        }
    }

    private static UUID uuid(JsonNode node, String message) {
        if (node == null || !node.isTextual()) {
            throw new EmrValueRules.InvalidValue(message);
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new EmrValueRules.InvalidValue(message);
        }
    }

    private static String text(JsonNode node, int min, int max, String message) {
        if (node == null || !node.isTextual()) {
            if (min == 0 && (node == null || node.isMissingNode() || node.isNull())) {
                return "";
            }
            throw new EmrValueRules.InvalidValue(message);
        }
        String text = node.asText().strip();
        if (text.length() < min || text.length() > max || HTML.matcher(text).find()) {
            throw new EmrValueRules.InvalidValue(message);
        }
        return text;
    }

    private static String formOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String form = text(node, 0, MAX_FORM, "The form is up to 40 characters.");
        return form.isEmpty() ? null : form;
    }

    private static String choice(JsonNode node, List<String> allowed, String message) {
        if (node == null || !node.isTextual() || !allowed.contains(node.asText())) {
            throw new EmrValueRules.InvalidValue(message);
        }
        return node.asText();
    }

    private static double unit(JsonNode node) {
        if (node == null || !node.isNumber() || node.asDouble() < 0 || node.asDouble() > 1) {
            throw new EmrValueRules.InvalidValue("A pin position is from 0 to 1.");
        }
        return node.asDouble();
    }
}
