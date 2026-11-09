package com.sahaj.ipd.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.sahaj.ipd.domain.card.CardSetup;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import java.util.List;
import java.util.Optional;

/**
 * SAH-37 the JSON of a card setup: the text stored in {@code jsonb} (lenient read, so an older or damaged row still
 * parses and is repaired) and the strict read of a request body (an unknown property, a missing property or a value of
 * the wrong type is a 400 with the path of the property, nothing is stored). The messages are fixed text and never echo
 * what was sent. Labels are owner-typed text and are never logged.
 */
final class CardSetupJson {

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private CardSetupJson() {
    }

    /** The setup as the text stored in {@code jsonb}. */
    static String write(CardSetup setup) {
        try {
            return LENIENT.writeValueAsString(setup);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The card setup could not be written", e);
        }
    }

    /** The stored text as a setup, or empty when it cannot be read at all (the caller answers the standard setup). */
    static Optional<CardSetup> read(String json) {
        try {
            return Optional.ofNullable(LENIENT.readValue(json, CardSetup.class));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * A request body as {@code CardSetupViews.CardSetupWrite}. Anything that is not exactly the declared shape is an
     * {@link InvalidFieldsException} (400 validation) naming the path of the property.
     */
    static CardSetupViews.CardSetupWrite readRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("", "The request body must be a JSON object.");
        }
        try {
            return STRICT.treeToValue(body, CardSetupViews.CardSetupWrite.class);
        } catch (JsonProcessingException e) {
            throw invalid(path(e), message(e));
        } catch (IllegalArgumentException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JsonProcessingException json) {
                throw invalid(path(json), message(json));
            }
            throw invalid("", "Check this value.");
        }
    }

    private static String message(JsonProcessingException e) {
        if (e instanceof UnrecognizedPropertyException) {
            return "This property is not allowed.";
        }
        if (e.getMessage() != null && e.getMessage().contains("Missing creator property")) {
            return "This value is missing.";
        }
        return "Check this value.";
    }

    /** The path as {@code setup.sections[2].on}; for an unknown property the path of its parent (nothing typed is echoed). */
    private static String path(JsonProcessingException e) {
        if (!(e instanceof JsonMappingException mapping)) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        List<JsonMappingException.Reference> references = mapping.getPath();
        boolean unknown = e instanceof UnrecognizedPropertyException;
        for (int i = 0; i < references.size(); i++) {
            JsonMappingException.Reference ref = references.get(i);
            if (unknown && i == references.size() - 1) {
                break;
            }
            if (ref.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static InvalidFieldsException invalid(String path, String message) {
        return new InvalidFieldsException(List.of(new FieldMessage(path.isEmpty() ? "setup" : path, message)), true);
    }
}
