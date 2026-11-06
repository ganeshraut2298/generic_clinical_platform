package com.sahaj.patient.application;

import com.sahaj.platform.api.InvalidInputException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One consent answer ({@code ConsentInput} in {@code docs/api/patient.yaml}): {@code kind} is RECORDS or PROMOTIONAL. */
public record ConsentInput(String kind, Boolean given) {

    static final String RECORDS = "RECORDS";
    static final String PROMOTIONAL = "PROMOTIONAL";
    private static final Set<String> KINDS = Set.of(RECORDS, PROMOTIONAL);

    /** Rejects an unknown kind or a missing answer (400, field {@code kind} or {@code given}). */
    void validate() {
        if (kind == null || !KINDS.contains(kind)) {
            throw new InvalidInputException("kind", "Choose records or promotional messages");
        }
        if (given == null) {
            throw new InvalidInputException("given", "Choose agreed or not agreed");
        }
    }

    /** Validates each answer and refuses two answers of the same kind in one request. */
    static List<ConsentInput> validateAll(List<ConsentInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (ConsentInput input : inputs) {
            if (input == null) {
                throw new InvalidInputException("consents", "Each consent needs a kind and an answer");
            }
            input.validate();
            if (!seen.add(input.kind())) {
                throw new InvalidInputException("consents", "Send each consent kind only once");
            }
        }
        return List.copyOf(inputs);
    }
}
