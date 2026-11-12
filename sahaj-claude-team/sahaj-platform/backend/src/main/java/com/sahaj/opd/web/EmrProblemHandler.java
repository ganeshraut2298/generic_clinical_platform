package com.sahaj.opd.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.sahaj.opd.application.emr.EmrProblemException;
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
 * Problems that only the EMR endpoints raise (docs/api/opd.yaml 0.2.0): the contract's own slugs bad-request,
 * template-not-found, section-not-found, hidden-section-required and section-not-hideable, and a body that cannot be
 * read. It applies to the EMR controllers only and runs before the platform handler, which handles everything else
 * (401, 403, 409, 413, validation). The text is fixed plain English: no submitted value, no field name that was sent,
 * and nothing is logged here.
 */
@RestControllerAdvice(assignableTypes = {EmrMasterController.class, EmrDoctorController.class,
        EmrTemplateController.class, EmrSettingsController.class, EmrReadOnlyPathsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmrProblemHandler {

    private static final String TYPE_BASE = "https://sahaj.in/problems/";
    private static final String RELOAD_PAGE = "Reload the page and try again.";
    private static final String INVALID_OPTION = "Choose a valid option";
    private static final List<String> RELOAD_FIELDS = List.of("revision", "version");

    @ExceptionHandler(EmrProblemException.class)
    ResponseEntity<ProblemDetail> handleEmrProblem(EmrProblemException ex) {
        return respond(problem(HttpStatus.valueOf(ex.status()), ex.slug(), ex.title(), ex.getMessage()));
    }

    /**
     * A closed body with an unknown field answers 400 validation (the platform handler would call it
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
                String last = field.substring(field.lastIndexOf('.') + 1);
                return respond(validation(field, RELOAD_FIELDS.contains(last) ? RELOAD_PAGE : INVALID_OPTION));
            }
        }
        return respond(problem(HttpStatus.BAD_REQUEST, "malformed-request", "Request could not be read",
                "The request body is missing or is not valid JSON."));
    }

    private static boolean causedByUnknownField(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof EmrStrictJsonModule.UnknownFieldException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static ProblemDetail validation(String field, String message) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation", "Check the highlighted fields",
                "Some fields are missing or not valid.");
        problem.setProperty("fieldErrors", List.of(Map.of("field", field, "message", message)));
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
