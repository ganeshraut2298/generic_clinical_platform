package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * Security L13 (DEC-080): the actor of every SAH-79 writer is {@code caller.actorId()}, the membership id. A test gives the
 * caller a user id that differs from the membership id, runs the writer, and then asks this helper that no call on a
 * repository, the posting component, the event log or the audit received the user id, directly or inside a record or map
 * (the text of the argument is searched). The gate takes the user id by design and is not listed.
 */
final class UserIdNeverWritten {

    private UserIdNeverWritten() {
    }

    static void assertNone(UUID userId, Object... mocks) {
        List<String> leaks = new ArrayList<>();
        for (Object mock : mocks) {
            for (Invocation call : Mockito.mockingDetails(mock).getInvocations()) {
                for (Object argument : call.getArguments()) {
                    if (argument != null && String.valueOf(argument).contains(userId.toString())) {
                        leaks.add(call.getMethod().getDeclaringClass().getSimpleName() + "." + call.getMethod().getName());
                    }
                }
            }
        }
        assertThat(leaks).as("calls that received the user id instead of the membership id").isEmpty();
    }

    /** The check can fail: a call that carries the id is found. */
    static boolean carries(UUID userId, Object mock) {
        return Mockito.mockingDetails(mock).getInvocations().stream()
                .anyMatch(call -> java.util.Arrays.stream(call.getArguments()).anyMatch(a -> a != null
                        && String.valueOf(a).contains(userId.toString())));
    }
}
