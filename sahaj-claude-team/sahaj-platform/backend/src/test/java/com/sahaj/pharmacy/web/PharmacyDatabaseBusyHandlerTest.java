package com.sahaj.pharmacy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sahaj.pharmacy.application.StoreDenialAuditor;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** ADR-029 P5: a deadlock, a lock timeout, a serialization failure or a statement timeout answers 503 database-busy (D-2). */
class PharmacyDatabaseBusyHandlerTest {

    private static final String SECRET = "Key (name)=(Asha Verma) 9000000001";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StoreDenialAuditor> provider = mock(ObjectProvider.class);
    private final PharmacyProblemHandler handler = new PharmacyProblemHandler(provider);

    @Test
    void aDeadlockAnswers503DatabaseBusyWithARetryHintAndNoExceptionText() {
        ResponseEntity<ProblemDetail> response = handler
                .handleBusy(new DeadlockLoserDataAccessException(SECRET, new SQLException(SECRET, "40P01")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        ProblemDetail problem = response.getBody();
        assertThat(problem.getType().toString()).isEqualTo("https://sahaj.in/problems/database-busy");
        assertThat(problem.getTitle()).isEqualTo("Sahaj is busy");
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getProperties()).containsEntry("retryAfterSeconds", 5L);
        assertThat(problem.toString()).doesNotContain("Asha").doesNotContain("9000000001");
    }

    @Test
    void aLockTimeoutASerializationFailureAndAStatementTimeoutAreTheSame503() {
        for (RuntimeException error : new RuntimeException[] {
                new CannotAcquireLockException(SECRET, new SQLException(SECRET, "55P03")),
                new CannotSerializeTransactionException(SECRET, new SQLException(SECRET, "40001")),
                new QueryTimeoutException(SECRET, new SQLException(SECRET, "57014"))}) {
            ResponseEntity<ProblemDetail> response = handler.handleBusy(error);

            assertThat(response.getStatusCode()).as(error.getClass().getSimpleName())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().getType().toString()).endsWith("/database-busy");
            assertThat(response.getBody().getDetail()).doesNotContain("Asha");
        }
    }
}
