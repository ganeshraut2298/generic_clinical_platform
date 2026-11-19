package com.sahaj.pharmacy.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.pharmacy.application.PharmacyInputs.Body;

/**
 * Reads a request body when the service asks for it (docs/api/pharmacy.yaml, general rule 2: module off 403, access level
 * 403, then 400 for the body, a path id or a query value, then 404 or the store guard).
 *
 * <p>The controllers take the body as raw text and hand the service a {@link Body}; the service calls {@code read()} right
 * after its gate. The text is read with the application's own {@link ObjectMapper}, so the closed bodies
 * ({@code PharmacyStrictJsonModule}) and the present-or-absent fields ({@code PharmacySentJsonModule}) behave exactly as
 * they did when the framework bound the body.
 */
final class PharmacyBodies {

    private PharmacyBodies() {
    }

    /** A body that is read, as {@code type}, when {@code read()} is called. Nothing is parsed before that. */
    static <T> Body<T> of(ObjectMapper mapper, String raw, Class<T> type) {
        return () -> read(mapper, raw, type);
    }

    static <T> T read(ObjectMapper mapper, String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            throw new UnreadableBodyException(null);
        }
        T value;
        try {
            value = mapper.readValue(raw, type);
        } catch (JsonProcessingException unreadable) {
            throw new UnreadableBodyException(unreadable);
        }
        if (value == null) {
            throw new UnreadableBodyException(null);
        }
        return value;
    }
}
