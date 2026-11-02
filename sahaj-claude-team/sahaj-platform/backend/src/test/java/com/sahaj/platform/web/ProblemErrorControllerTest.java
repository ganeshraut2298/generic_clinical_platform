package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController();

    private ResponseEntity<ProblemDetail> errorWith(Object status) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (status != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        }
        return controller.error(request);
    }

    @Test
    void returnsTheContainerStatusAsAProblem() {
        ResponseEntity<ProblemDetail> response = errorWith(400);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/bad-request");
    }

    @Test
    void usesTheGenericInternalProblemFor500() {
        ResponseEntity<ProblemDetail> response = errorWith(500);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getType().toString()).isEqualTo("https://sahaj.in/problems/internal-error");
    }

    @Test
    void treatsAMissingOrOddStatusAs500() {
        assertThat(errorWith(null).getStatusCode().value()).isEqualTo(500);
        assertThat(errorWith(200).getStatusCode().value()).isEqualTo(500);
        assertThat(errorWith("oops").getStatusCode().value()).isEqualTo(500);
    }
}
