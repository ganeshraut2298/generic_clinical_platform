package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.api.ColleaguePinCheck;
import com.sahaj.platform.api.PinCheck;
import com.sahaj.platform.api.PinCheck.NotSetReason;
import com.sahaj.platform.api.PinCheck.Outcome;
import com.sahaj.platform.api.StaffPinException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** SAH-32, ADR-011 D7: the colleague (second nurse) problems as the error handler answers them. */
class ProblemExceptionHandlerColleaguePinTest {

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();

    private ResponseEntity<Object> answer(PinCheck check) {
        try {
            ColleaguePinCheck.failed(check).requireOk();
        } catch (StaffPinException ex) {
            return handler.handleStaffPin(ex);
        }
        throw new AssertionError("requireOk did not throw");
    }

    private static ProblemDetail problem(ResponseEntity<Object> response) {
        return (ProblemDetail) response.getBody();
    }

    @Test
    void aWrongColleaguePinIs403WrongPinWithNoTriesLeftCount() {
        ResponseEntity<Object> response = answer(new PinCheck(Outcome.WRONG, 2, null, 0));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/wrong-pin");
        assertThat(problem(response).getDetail()).isEqualTo("That PIN is not right.");
        assertThat(problem(response).getProperties()).isNullOrEmpty();
    }

    @Test
    void aLockedColleagueIs403PinLockedWithRetryAfterSeconds() {
        ResponseEntity<Object> response = answer(
                new PinCheck(Outcome.LOCKED, 0, Instant.parse("2026-11-06T05:50:00Z"), 840));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/pin-locked");
        assertThat(problem(response).getDetail()).isEqualTo("The second nurse's PIN is locked after 5 wrong tries. "
                + "Choose another nurse, or try again at 11:20 AM.");
        assertThat(problem(response).getProperties()).containsEntry("retryAfterSeconds", 840L);
    }

    @Test
    void aColleagueWithoutAPinIs409PinNotSet() {
        ResponseEntity<Object> response = answer(new PinCheck(Outcome.NOT_SET, 0, null, 0, NotSetReason.RESET_BY_OWNER));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/pin-not-set");
        assertThat(problem(response).getDetail()).isEqualTo("The second nurse has not set a PIN yet. Choose another nurse.");
    }

    @Test
    void tooManyColleagueTriesIs429RateLimitAndAMissingPepperIs503() {
        ResponseEntity<Object> limited = answer(new PinCheck(Outcome.RATE_LIMITED, 0, null, 600));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(problem(limited).getType().toString()).isEqualTo("https://sahaj.in/problems/rate-limit");
        assertThat(problem(limited).getProperties()).containsEntry("retryAfterSeconds", 600L);

        ResponseEntity<Object> off = answer(new PinCheck(Outcome.UNAVAILABLE, 0, null, 0));
        assertThat(off.getStatusCode().value()).isEqualTo(503);
        assertThat(problem(off).getType().toString()).isEqualTo("https://sahaj.in/problems/pin-unavailable");
        assertThat(problem(off).getDetail()).isEqualTo("PIN checks are not available right now. Try again later.");
    }

    @Test
    void thePairLimitIs429ColleaguePinLimitWithNoRetryAfterAndNoCount() {
        ResponseEntity<Object> response = answer(new PinCheck(Outcome.PAIR_LIMIT, 0, null, 0));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/colleague-pin-limit");
        assertThat(problem(response).getTitle()).isEqualTo("Too many wrong PIN tries");
        assertThat(problem(response).getDetail()).isEqualTo("Too many wrong PIN tries for this nurse. Choose another nurse.");
        assertThat(problem(response).getProperties()).isNullOrEmpty();
    }

    @Test
    void aFailedCheckIs503PinCheckFailedWithNoRetryAfter() {
        ResponseEntity<Object> response = answer(new PinCheck(Outcome.CHECK_FAILED, 0, null, 0));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/pin-check-failed");
        assertThat(problem(response).getTitle()).isEqualTo("PIN check failed");
        assertThat(problem(response).getDetail()).isEqualTo("We could not check the PIN. Try again.");
        assertThat(problem(response).getProperties()).isNullOrEmpty();
    }

    @Test
    void anIneligibleColleagueIs400ValidationOnSecondNurseIdWithTheMessageAsDetail() {
        try {
            ColleaguePinCheck.notEligible().requireOk();
        } catch (com.sahaj.platform.api.InvalidFieldsException ex) {
            ResponseEntity<Object> response = handler.handleInvalidFields(ex);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(problem(response).getType().toString()).isEqualTo("https://sahaj.in/problems/validation");
            assertThat(problem(response).getDetail()).isEqualTo("Choose a nurse from the list.");
            assertThat(String.valueOf(problem(response).getProperties())).contains("secondNurseId");
            return;
        }
        throw new AssertionError("requireOk did not throw");
    }
}
