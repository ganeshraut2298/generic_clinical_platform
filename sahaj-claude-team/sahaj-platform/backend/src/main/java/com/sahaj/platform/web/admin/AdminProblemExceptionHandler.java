package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.admin.AdminAuthException;
import com.sahaj.platform.application.admin.AdminDbStateException;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminRealm;
import com.sahaj.platform.web.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the admin sign-in refusals to the problem types of docs/api/admin.yaml. Applies only to the
 * admin controllers and runs before the platform handler; every other exception (validation, access
 * denied, unexpected) still goes to {@code ProblemExceptionHandler}. Logs exception classes only,
 * never a message, because a message could carry a value.
 */
@RestControllerAdvice(basePackageClasses = AdminAuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnAdminRealm
public class AdminProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminProblemExceptionHandler.class);

    @ExceptionHandler(AdminAuthException.class)
    ResponseEntity<Object> handleAuth(AdminAuthException ex) {
        ProblemDetail problem = AdminProblems.of(ex);
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() > 0) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return ResponseEntity.status(problem.getStatus()).headers(headers)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** A database refusal that the flow did not translate: the session is gone or the call was wrong. */
    @ExceptionHandler(AdminDbStateException.class)
    ResponseEntity<Object> handleDbState(AdminDbStateException ex) {
        if (ex.state() == AdminDbStateException.State.NO_SESSION) {
            return problem(AdminProblems.sessionEnded());
        }
        log.error("Admin database function refused state={}", ex.state());
        return problem(Problems.internal());
    }

    @ExceptionHandler(TotpSecretCipher.TotpSecretUnreadableException.class)
    ResponseEntity<Object> handleUnreadableSecret(TotpSecretCipher.TotpSecretUnreadableException ex) {
        log.error("An admin authenticator secret could not be decrypted. Check SAHAJ_ADMIN_TOTP_KEY.");
        return problem(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Something went wrong",
                "Something went wrong on our side. Please try again."));
    }

    private static ResponseEntity<Object> problem(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
