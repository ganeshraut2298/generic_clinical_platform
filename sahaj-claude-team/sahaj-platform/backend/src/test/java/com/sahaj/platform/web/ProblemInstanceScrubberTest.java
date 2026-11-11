package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * The scrubber clears the {@code instance} Spring fills from the request path for every controller, except one marked
 * {@link KeepProblemInstance} (the dev-only bootstrap registration, which sets a fixed path itself).
 */
class ProblemInstanceScrubberTest {

    static class Plain {
        public ProblemDetail handle() {
            return null;
        }
    }

    @KeepProblemInstance
    static class Marked {
        public ProblemDetail handle() {
            return null;
        }
    }

    /** What a CGLIB proxy of a marked controller looks like: a subclass that does not repeat the annotation. */
    static class MarkedProxy extends Marked {
    }

    private final ProblemInstanceScrubber scrubber = new ProblemInstanceScrubber();

    private static MethodParameter returnTypeOf(Class<?> controller) throws Exception {
        return new MethodParameter(controller.getMethod("handle"), -1).withContainingClass(controller);
    }

    @Test
    void anOrdinaryControllerIsScrubbedAndItsInstanceIsCleared() throws Exception {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setInstance(URI.create("/api/v1/clinics/abc;secret=1"));

        assertThat(scrubber.supports(returnTypeOf(Plain.class), null)).isTrue();
        scrubber.beforeBodyWrite(problem, returnTypeOf(Plain.class), MediaType.APPLICATION_PROBLEM_JSON, null, null, null);

        assertThat(problem.getInstance()).isNull();
    }

    @Test
    void aControllerMarkedKeepProblemInstanceIsLeftAloneAlsoThroughAProxySubclass() throws Exception {
        assertThat(scrubber.supports(returnTypeOf(Marked.class), null)).isFalse();
        assertThat(scrubber.supports(returnTypeOf(MarkedProxy.class), null)).isFalse();
    }
}
