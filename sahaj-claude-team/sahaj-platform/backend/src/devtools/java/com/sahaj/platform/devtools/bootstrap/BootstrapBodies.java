package com.sahaj.platform.devtools.bootstrap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Reads the two request bodies strictly (docs/api/admin.yaml {@code additionalProperties: false}). Spring Boot turns
 * unknown-field failures off for the whole application, and {@code AdminStrictJsonModule} covers only the types of the
 * admin web package, so these bodies are read here with their own mapper. Nothing from the input survives in an error.
 *
 * <p>Three states (ADR-018 D2 "Request bodies", Revision 2): <b>unreadable</b> ({@code wellFormed} false: empty, not
 * JSON, not an object, a repeated key, trailing tokens, an unknown field, or a listed field that is neither a string
 * nor JSON {@code null}); <b>readable</b> ({@code wellFormed} true); and the <b>values</b> of the fields that were
 * strings. A listed field that is missing or JSON {@code null} is a null value (the caller treats it as an empty
 * string). The string values are handed on even when the body is unreadable, so the caller can apply the contract's
 * order of answers (closed, locked, ticket, then the body). A name the caller sent is never echoed.
 *
 * <p>Both records print no value in {@code toString}, so a framework debug line cannot show a PIN, ticket or password.
 */
final class BootstrapBodies {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> UNLOCK_FIELDS = Set.of("pin");
    private static final Set<String> REGISTER_FIELDS = Set.of("ticket", "fullName", "email", "password");

    private BootstrapBodies() {
    }

    /** {@code wellFormed} is false for an unreadable body. {@code pin} is null when missing, JSON null or not a string. */
    record UnlockBody(boolean wellFormed, String pin) {
        @Override
        public String toString() {
            return "UnlockBody[]";
        }
    }

    /** {@code wellFormed} is false for an unreadable body. A field is null when missing, JSON null or not a string. */
    record RegisterBody(boolean wellFormed, String ticket, String fullName, String email, String password) {
        @Override
        public String toString() {
            return "RegisterBody[]";
        }
    }

    static UnlockBody readUnlock(InputStream in) {
        Map<String, String> values = new java.util.HashMap<>();
        boolean ok = read(in, UNLOCK_FIELDS, values);
        return new UnlockBody(ok, values.get("pin"));
    }

    static RegisterBody readRegister(InputStream in) {
        Map<String, String> values = new java.util.HashMap<>();
        boolean ok = read(in, REGISTER_FIELDS, values);
        return new RegisterBody(ok, values.get("ticket"), values.get("fullName"), values.get("email"),
                values.get("password"));
    }

    /**
     * Fills {@code values} with the allowed string fields; false when anything else was seen or parsing failed. A JSON
     * {@code null} on an allowed field is readable and adds no value.
     */
    private static boolean read(InputStream in, Set<String> allowed, Map<String, String> values) {
        JsonNode root;
        try {
            root = MAPPER.readTree(in);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        boolean ok = true;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode node = field.getValue();
            if (!allowed.contains(field.getKey()) || !(node.isTextual() || node.isNull())) {
                ok = false;
                continue;
            }
            if (node.isNull()) {
                continue;
            }
            values.put(field.getKey(), field.getValue().textValue());
        }
        return ok;
    }
}
