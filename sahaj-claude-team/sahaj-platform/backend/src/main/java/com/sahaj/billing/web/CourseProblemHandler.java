package com.sahaj.billing.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.billing.application.CourseProblemException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The problems only the course account endpoints raise (docs/api/billing.yaml 0.2.0): course-not-found,
 * receipt-not-found and amount-over-balance. It applies to {@link CourseAccountController} only and runs before the
 * platform handler, which answers everything else (401, 403, 409, validation). The text is fixed plain English: no name,
 * no amount that was sent, and nothing is logged here.
 */
@RestControllerAdvice(assignableTypes = CourseAccountController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CourseProblemHandler {

    private static final String TYPE_BASE = "https://sahaj.in/problems/";

    @ExceptionHandler(CourseProblemException.class)
    ResponseEntity<ProblemDetail> handle(CourseProblemException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(ex.status()));
        problem.setType(URI.create(TYPE_BASE + ex.slug()));
        problem.setTitle(ex.title());
        problem.setDetail(ex.getMessage());
        if (ex.field() != null) {
            problem.setProperty("fieldErrors", List.of(Map.of("field", ex.field(), "message", ex.getMessage())));
        }
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /**
     * The closed payment body with an unknown field answers 400 validation (the platform handler would call it
     * malformed-request); every other unreadable body is answered as the platform handler does.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        if (causedByUnknownField(ex)) {
            return respond(validation("body", "Send only the fields listed for this request."));
        }
        if (ex.getCause() instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull).reduce((first, second) -> first + "." + second).orElse(null);
            if (field != null) {
                return respond(validation(field, "Choose a valid option"));
            }
        }
        return respond(problem("malformed-request", "Request could not be read",
                "The request body is missing or is not valid JSON."));
    }

    private static boolean causedByUnknownField(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof CourseStrictJsonModule.UnknownFieldException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static ProblemDetail validation(String field, String message) {
        ProblemDetail problem = problem("validation", "Check the highlighted fields",
                "Some fields are missing or not valid.");
        problem.setProperty("fieldErrors", List.of(Map.of("field", field, "message", message)));
        return problem;
    }

    private static ProblemDetail problem(String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
