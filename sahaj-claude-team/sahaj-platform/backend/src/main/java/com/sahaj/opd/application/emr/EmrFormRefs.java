package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The form references a record keeps in {@code opd.emr_record.form_refs} (SAH-59; ADR-022 D11): for each clinic form
 * shown in the record, the form key, the immutable version the record was opened with and the section settings of that
 * moment ({@code [{formKey, versionNo, part, position, required, print, copy, collapsed, whoCanFill}]}). Form versions
 * never change, so a later publish, approval or retirement never changes an opened record (SAH-46-3). The document is
 * setup data (no patient data) but is small: at most 16 KB, a database check.
 */
public final class EmrFormRefs {

    /** One form of a record: the section settings are those of the moment the record was opened or switched. */
    public record Ref(String formKey, int versionNo, String part, int position, boolean required, boolean print,
                      boolean copy, boolean collapsed, List<String> whoCanFill) {
    }

    private EmrFormRefs() {
    }

    public static List<Ref> parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = mapper.readTree(json);
            if (!array.isArray()) {
                throw new IllegalStateException("A stored EMR form reference list is not a list");
            }
            List<Ref> refs = new ArrayList<>();
            for (JsonNode n : array) {
                List<String> roles = new ArrayList<>();
                for (JsonNode role : n.path("whoCanFill")) {
                    roles.add(role.asText());
                }
                refs.add(new Ref(n.path("formKey").asText(), n.path("versionNo").asInt(1),
                        n.path("part").asText(EmrModel.PART_ASSESSMENT), n.path("position").asInt(1),
                        n.path("required").asBoolean(false), n.path("print").asBoolean(true),
                        n.path("copy").asBoolean(false), n.path("collapsed").asBoolean(false),
                        EmrLayoutJson.canonicalRoles(roles)));
            }
            return List.copyOf(refs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored EMR form reference list could not be read", e);
        }
    }

    public static String write(ObjectMapper mapper, List<Ref> refs) {
        ArrayNode array = mapper.createArrayNode();
        for (Ref ref : refs) {
            ObjectNode node = array.addObject();
            node.put("formKey", ref.formKey());
            node.put("versionNo", ref.versionNo());
            node.put("part", ref.part());
            node.put("position", ref.position());
            node.put("required", ref.required());
            node.put("print", ref.print());
            node.put("copy", ref.copy());
            node.put("collapsed", ref.collapsed());
            ArrayNode roles = node.putArray("whoCanFill");
            ref.whoCanFill().forEach(roles::add);
        }
        return array.toString();
    }

    /** The references of the shown form sections of a layout that carry a form version (a doctor layout does). */
    public static List<Ref> fromSections(List<EmrModel.LayoutSection> sections) {
        List<Ref> refs = new ArrayList<>();
        for (EmrModel.LayoutSection s : sections) {
            if (s.formSection() && s.shown() && s.formVersionNo() != null) {
                refs.add(new Ref(s.key(), s.formVersionNo(), s.part(), s.position(), s.required(), s.print(), s.copy(),
                        s.collapsed(), s.whoCanFill()));
            }
        }
        return refs;
    }
}
