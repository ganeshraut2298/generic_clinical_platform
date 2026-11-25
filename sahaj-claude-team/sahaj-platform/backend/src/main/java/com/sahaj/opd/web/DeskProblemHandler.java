package com.sahaj.opd.web;

import com.sahaj.opd.application.OpdProblemException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The front desk console's own problem types (docs/api/opd.yaml 0.9.0, SAH-91): doctor-not-working, slot-not-on-grid,
 * slot-walk-in-only, slot-in-past, booking-closed, appointment-duplicate, appointment-changed and
 * appointment-not-reschedulable, then (SAH-92, SAH-93) appointment-already-checked-in, appointment-not-bookable,
 * appointment-not-booked, check-in-not-today, visit-stage-changed, visit-not-done, follow-up-already-booked and
 * follow-up-closed. Applies to {@link DeskApi} controllers only; every other error is the platform handler's.
 * The text is fixed plain English: no submitted value, no patient name.
 */
@RestControllerAdvice(assignableTypes = DeskApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DeskProblemHandler {

    private static final String TYPE_BASE = "https://sahaj.in/problems/";

    @ExceptionHandler(OpdProblemException.class)
    ResponseEntity<ProblemDetail> handle(OpdProblemException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(ex.status()));
        problem.setType(URI.create(TYPE_BASE + ex.slug()));
        problem.setTitle(ex.title());
        problem.setDetail(ex.getMessage());
        ex.extensions().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
