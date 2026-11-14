package com.sahaj.opd.application.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The section values of one EMR record, one JSON object stored in {@code opd.emr_record.sections} (ADR-020 D8):
 * {@code {<sectionKey>:{"values":{<fieldKey>:value},"copiedFrom":{"recordId","date"}|null,"copyConfirmed":bool,
 * "updatedAt":...,"updatedBy":membershipId}}}. The shape version is the column {@code schema_version}. The document is
 * clinical data: it is never logged or audited. Sections that are not in the record's current layout stay in the
 * document (after a department switch).
 */
public final class EmrRecordDocument {

    private final ObjectMapper mapper;
    private final ObjectNode root;

    private EmrRecordDocument(ObjectMapper mapper, ObjectNode root) {
        this.mapper = mapper;
        this.root = root;
    }

    public static EmrRecordDocument empty(ObjectMapper mapper) {
        return new EmrRecordDocument(mapper, mapper.createObjectNode());
    }

    public static EmrRecordDocument parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return empty(mapper);
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalStateException("A stored EMR record has no document");
            }
            return new EmrRecordDocument(mapper, (ObjectNode) node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored EMR record could not be read", e);
        }
    }

    public String toJson() {
        return root.toString();
    }

    private ObjectNode sections() {
        return root;
    }

    public boolean has(String sectionKey) {
        return sections().path(sectionKey).isObject();
    }

    /** The field values of a section; an empty object when nothing is stored. */
    public JsonNode values(String sectionKey) {
        JsonNode values = sections().path(sectionKey).path("values");
        return values.isObject() ? values : mapper.createObjectNode();
    }

    /** True when the section holds copied values the doctor has not confirmed yet (OQ-45-26). */
    public boolean copyPending(String sectionKey) {
        JsonNode section = sections().path(sectionKey);
        return section.path("copiedFrom").isObject() && !section.path("copyConfirmed").asBoolean(false);
    }

    public UUID copiedFromRecord(String sectionKey) {
        JsonNode from = sections().path(sectionKey).path("copiedFrom");
        return from.isObject() && from.path("recordId").isTextual() ? UUID.fromString(from.path("recordId").asText())
                : null;
    }

    public LocalDate copiedFromDate(String sectionKey) {
        JsonNode from = sections().path(sectionKey).path("copiedFrom");
        return from.isObject() && from.path("date").isTextual() ? LocalDate.parse(from.path("date").asText()) : null;
    }

    public Instant updatedAt(String sectionKey) {
        JsonNode at = sections().path(sectionKey).path("updatedAt");
        return at.isTextual() ? Instant.parse(at.asText()) : null;
    }

    /**
     * Replaces the values of one section. {@code confirmCopy} true confirms copied values; copied-from is kept
     * otherwise. Empty values (an empty object) are stored as such.
     */
    public void put(String sectionKey, ObjectNode values, boolean confirmCopy, Instant at, UUID membershipId) {
        ObjectNode section = sections().has(sectionKey) && sections().get(sectionKey).isObject()
                ? (ObjectNode) sections().get(sectionKey) : sections().putObject(sectionKey);
        section.set("values", values);
        if (!section.has("copiedFrom")) {
            section.putNull("copiedFrom");
        }
        boolean wasConfirmed = section.path("copyConfirmed").asBoolean(false);
        section.put("copyConfirmed", confirmCopy || wasConfirmed);
        section.put("updatedAt", at.toString());
        section.put("updatedBy", membershipId.toString());
    }

    /** Pre-fills one section with values copied from an earlier signed record; it waits for confirmation. */
    public void putCopied(String sectionKey, JsonNode values, UUID fromRecordId, LocalDate fromDate, Instant at,
                          UUID membershipId) {
        ObjectNode section = sections().putObject(sectionKey);
        section.set("values", values.deepCopy());
        ObjectNode from = section.putObject("copiedFrom");
        from.put("recordId", fromRecordId.toString());
        from.put("date", fromDate.toString());
        section.put("copyConfirmed", false);
        section.put("updatedAt", at.toString());
        section.put("updatedBy", membershipId.toString());
    }

    /** The section keys held in the document (including sections no longer in the layout). */
    public java.util.List<String> sectionKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        sections().fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** The stored values object of a section for in-place changes by the server; an empty detached object if none. */
    public ObjectNode mutableValues(String sectionKey) {
        JsonNode values = sections().path(sectionKey).path("values");
        return values.isObject() ? (ObjectNode) values : mapper.createObjectNode();
    }

    public JsonNode rawSection(String sectionKey) {
        return sections().path(sectionKey);
    }
}
