package com.sahaj.pharmacy.web;

import com.sahaj.pharmacy.application.PharmacyInputs.Body;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

/**
 * Helpers for the web tests of the pharmacy controllers. The controllers hand the service a {@link Body} and the real service
 * reads it right after its gate; a mocked service reads nothing, so a test that wants the body bound (or refused) reads it
 * the way the service does.
 */
final class BodyAnswers {

    private BodyAnswers() {
    }

    /** Answers {@code result} after reading the {@link Body} given as argument number {@code index}, as the service does. */
    static <R> Answer<R> readBody(int index, R result) {
        return invocation -> {
            ((Body<?>) invocation.getArgument(index)).read();
            return result;
        };
    }

    /** Reads the single body captured by a verify, as the type the controller asked for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T read(ArgumentCaptor<? extends Body> captor) {
        return (T) captor.getValue().read();
    }

    /** A captor for the body argument (raw, so it fits the {@code Body<T>} parameter of every service method). */
    @SuppressWarnings("rawtypes")
    static ArgumentCaptor<Body> captor() {
        return ArgumentCaptor.forClass(Body.class);
    }
}
