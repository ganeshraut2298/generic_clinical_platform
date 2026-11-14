package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.FieldOption;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import com.sahaj.opd.application.emr.EmrModel.SectionSettings;
import com.sahaj.opd.application.emr.EmrModel.TableColumn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the stored layout documents (ADR-020 D6): a starter layout or a department version is
 * {@code {"schemaVersion":1,"sections":[LayoutSection...]}} (the EmrLayoutSection shape of docs/api/opd.yaml), a
 * department draft is {@code {"schemaVersion":1,"sections":[SectionSettings...]}}. Reading is lenient on a missing
 * optional property (a default is used) and ignores the stored {@code valueKind}, which is always derived from the type.
 * A stored document that cannot be read at all is an {@link IllegalStateException} (a damaged row is a server error,
 * never a silent empty layout).
 */
public final class EmrLayoutJson {

    private final ObjectMapper mapper;

    public EmrLayoutJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- full layouts ----

    public String writeLayout(List<LayoutSection> sections) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", EmrModel.SCHEMA_VERSION);
        ArrayNode array = root.putArray("sections");
        for (LayoutSection section : sections) {
            ObjectNode node = mapper.valueToTree(section);
            // Derived by the application on every read, never stored (docs/database/SAH-53.md note 10).
            node.remove("hideLockReason");
            // SAH-59: a form section is stored as settings only; the form version is resolved when a record opens.
            node.remove("formVersionNo");
            node.remove("form");
            // ADR-023 D1: the specialty editor is computed on read from the record's profile, never stored.
            node.remove("specialtyEditor");
            if (node.path("formKey").isNull()) {
                node.remove("formKey");
            }
            for (JsonNode field : node.path("fields")) {
                ((ObjectNode) field).remove("valueKind");
                // 0.7.1: an option without a hint is stored without the key, as every document before 0.7.1.
                for (JsonNode option : field.path("options")) {
                    if (option.path("hint").isNull()) {
                        ((ObjectNode) option).remove("hint");
                    }
                }
            }
            array.add(node);
        }
        return root.toString();
    }

    public List<LayoutSection> readLayout(String json) {
        try {
            JsonNode sections = mapper.readTree(json).path("sections");
            if (!sections.isArray()) {
                throw new IllegalStateException("A stored EMR layout has no sections");
            }
            List<LayoutSection> result = new ArrayList<>();
            int position = 1;
            for (JsonNode node : sections) {
                result.add(section(node, position++));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored EMR layout could not be read", e);
        }
    }

    private LayoutSection section(JsonNode n, int fallbackPosition) {
        String key = text(n, "key", null);
        String name = text(n, "name", key);
        boolean locked = n.path("hideLocked").asBoolean(false);
        List<FieldDefinition> fields = new ArrayList<>();
        for (JsonNode f : n.path("fields")) {
            fields.add(field(f));
        }
        fields = new ArrayList<>(EmrComponentBinding.bind(key, fields));
        return new LayoutSection(key, text(n, "source", "LIBRARY"), name, text(n, "kind", "COMMON"),
                text(n, "description", ""), text(n, "part", EmrModel.PART_ASSESSMENT),
                n.path("position").asInt(fallbackPosition), n.path("shown").asBoolean(true),
                n.path("required").asBoolean(false), n.path("print").asBoolean(false), n.path("copy").asBoolean(false),
                n.path("collapsed").asBoolean(false), roles(n.path("whoCanFill")), locked,
                locked ? text(n, "hideLockReason", lockReason(key, name)) : null, List.copyOf(fields),
                text(n, "formKey", null), null, null);
    }

    /** The short reason shown next to a locked Show switch (UI text, not stored). */
    static String lockReason(String key, String name) {
        return "allergy".equals(key) ? "Allergies are always shown for patient safety." : name + " cannot be hidden.";
    }

    private FieldDefinition field(JsonNode f) {
        String type = text(f, "type", "TEXT");
        List<FieldOption> options = new ArrayList<>();
        for (JsonNode o : f.path("options")) {
            options.add(new FieldOption(text(o, "key", null), text(o, "label", ""), o.path("isNone").asBoolean(false),
                    o.path("alert").asBoolean(false), text(o, "hint", null)));
        }
        List<TableColumn> columns = new ArrayList<>();
        for (JsonNode c : f.path("columns")) {
            columns.add(new TableColumn(text(c, "key", null), text(c, "label", "")));
        }
        return new FieldDefinition(text(f, "key", null), text(f, "label", ""), type, EmrFieldType.valueKind(type),
                text(f, "meta", ""), f.path("required").asBoolean(false), text(f, "optionsSource", "NONE"),
                f.path("master").isTextual() ? f.path("master").asText() : null,
                f.path("allowFreeText").asBoolean(false), List.copyOf(options), List.copyOf(columns),
                number(f.path("min")), number(f.path("max")), null, text(f, "presentation", "DEFAULT"));
    }

    // ---- settings (drafts) ----

    public String writeSettings(List<SectionSettings> settings) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", EmrModel.SCHEMA_VERSION);
        ArrayNode array = root.putArray("sections");
        for (SectionSettings s : settings) {
            array.add(mapper.valueToTree(s));
        }
        return root.toString();
    }

    public List<SectionSettings> readSettings(String json) {
        try {
            JsonNode sections = mapper.readTree(json).path("sections");
            if (!sections.isArray()) {
                throw new IllegalStateException("A stored EMR draft has no sections");
            }
            List<SectionSettings> result = new ArrayList<>();
            for (JsonNode n : sections) {
                result.add(new SectionSettings(text(n, "key", null), text(n, "part", EmrModel.PART_ASSESSMENT),
                        n.path("shown").asBoolean(true), n.path("required").asBoolean(false),
                        n.path("print").asBoolean(false), n.path("copy").asBoolean(false),
                        n.path("collapsed").asBoolean(false), roles(n.path("whoCanFill"))));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored EMR draft could not be read", e);
        }
    }

    // ---- helpers ----

    /** The roles in the fixed order DOCTOR, NURSE, RECEPTION, without repeats. */
    static List<String> canonicalRoles(Iterable<String> roles) {
        List<String> sorted = new ArrayList<>();
        for (String role : EmrModel.FILLER_ROLES) {
            for (String given : roles) {
                if (role.equals(given)) {
                    sorted.add(role);
                    break;
                }
            }
        }
        return List.copyOf(sorted);
    }

    private static List<String> roles(JsonNode node) {
        List<String> given = new ArrayList<>();
        for (JsonNode role : node) {
            given.add(role.asText());
        }
        return canonicalRoles(given);
    }

    private static String text(JsonNode node, String name, String fallback) {
        JsonNode value = node.path(name);
        return value.isTextual() ? value.asText() : fallback;
    }

    private static BigDecimal number(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : null;
    }
}
