package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahaj.opd.application.emr.EmrModel.FieldDefinition;
import com.sahaj.opd.application.emr.EmrModel.LayoutSection;
import java.util.Iterator;
import java.util.Map;

/**
 * Copy from last visit (ADR-020 D8, changed by ADR-026 D13 / opd.yaml 0.7.1): a value is copied only when it still fits
 * the field definition of the NEW record's layout, the same test the draft save applies ({@link EmrValueRules#check}).
 * A value of a different kind (a toggle where the field is now a choice, free text such as "Regular, 28 days" in a field
 * that is now a choice row) or a choice key that is not an option of the new definition is left out, and so is a value
 * for a field the new layout no longer has. A section where nothing fits is not copied. Without this, the copied
 * values would fail the first draft save with a 400.
 *
 * <p>Pure and stateless; it never logs and never puts a value in a message.
 */
final class EmrCopyFit {

    private EmrCopyFit() {
    }

    /**
     * The part of {@code old} that fits {@code section}'s fields; null when nothing fits. The values of a shared
     * component field (EMR list, prescription, body map) are kept as they were: they are checked by their own rules at
     * save and have no choice list to drift from.
     */
    static ObjectNode fit(ObjectMapper mapper, LayoutSection section, JsonNode old) {
        if (old == null || !old.isObject()) {
            return null;
        }
        Map<String, FieldDefinition> fields = new java.util.HashMap<>();
        for (FieldDefinition field : section.fields()) {
            fields.put(field.key(), field);
        }
        ObjectNode kept = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> entries = old.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            FieldDefinition field = fields.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (field == null || EmrValueRules.isEmpty(value)) {
                continue;
            }
            if (field.valueKind() == EmrValueKind.COMPONENT || fits(field, value)) {
                kept.set(entry.getKey(), value);
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    /** True when the draft save would accept {@code value} for {@code field}. */
    static boolean fits(FieldDefinition field, JsonNode value) {
        try {
            EmrValueRules.check(field, value);
            return true;
        } catch (EmrValueRules.InvalidValue e) {
            return false;
        }
    }
}
