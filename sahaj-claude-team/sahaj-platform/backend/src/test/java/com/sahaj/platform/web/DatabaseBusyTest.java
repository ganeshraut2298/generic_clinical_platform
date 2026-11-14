package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * DEC-054: a database wait that ended (lock timeout, statement timeout, no free connection) is a 503 "database-busy"
 * with a retry hint, never a 500; a deadlock and every other failure stay a 500. The answer carries no exception text.
 */
class DatabaseBusyTest {

    private static final String SECRET_TEXT = "Key (name)=(Asha Verma) 9000000001";

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();

    @Test
    void aLockThatWasNotFreeInTimeIsBusyOfKindLock() {
        Exception error = new CannotAcquireLockException("update", new SQLException(SECRET_TEXT, "55P03"));

        assertThat(DatabaseBusy.isBusy(error)).isTrue();
        assertThat(DatabaseBusy.kind(error)).isEqualTo("lock");
    }

    @Test
    void aBareLockTimeoutStateUnderAnotherWrapperIsStillBusyOfKindLock() {
        Exception error = new UncategorizedSQLException("task", "select", new SQLException("canceling", "55P03"));

        assertThat(DatabaseBusy.isBusy(error)).isTrue();
        assertThat(DatabaseBusy.kind(error)).isEqualTo("lock");
    }

    @Test
    void aCancelledStatementIsBusyOfKindStatement() {
        Exception state = new UncategorizedSQLException("task", "update", new SQLException("canceling", "57014"));
        Exception type = new QueryTimeoutException("slow");

        assertThat(DatabaseBusy.isBusy(state)).isTrue();
        assertThat(DatabaseBusy.kind(state)).isEqualTo("statement");
        assertThat(DatabaseBusy.isBusy(type)).isTrue();
        assertThat(DatabaseBusy.kind(type)).isEqualTo("statement");
    }

    @Test
    void noFreeConnectionIsBusyOfKindConnection() {
        Exception pool = new CannotCreateTransactionException("Could not open JDBC Connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out"));
        Exception spring = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLException("no pool"));

        assertThat(DatabaseBusy.isBusy(pool)).isTrue();
        assertThat(DatabaseBusy.kind(pool)).isEqualTo("connection");
        assertThat(DatabaseBusy.isBusy(spring)).isTrue();
        assertThat(DatabaseBusy.kind(spring)).isEqualTo("connection");
    }

    @Test
    void aDeadlockAndOtherFailuresAreNotBusy() {
        Exception deadlock = new DeadlockLoserDataAccessException("deadlock", new SQLException("deadlock detected", "40P01"));

        assertThat(DatabaseBusy.isBusy(deadlock)).isFalse();
        assertThat(DatabaseBusy.isBusy(new IllegalStateException("boom"))).isFalse();
        assertThat(DatabaseBusy.isBusy(new SQLException("duplicate key", "23505"))).isFalse();
        assertThat(DatabaseBusy.kind(deadlock)).isEqualTo("other");
    }

    @Test
    void aCauseChainThatLoopsBackDoesNotHang() {
        RuntimeException first = new RuntimeException("a");
        RuntimeException second = new RuntimeException("b", first);
        first.initCause(second);

        assertThat(DatabaseBusy.isBusy(first)).isFalse();
    }

    @Test
    void theHandlerAnswers503WithARetryHintAndNoDatabaseText() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new CannotAcquireLockException("update opd.emr_record", new SQLException(SECRET_TEXT, "55P03")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType().toString()).isEqualTo("https://sahaj.in/problems/database-busy");
        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getProperties()).containsEntry("retryAfterSeconds", 5L);
        assertThat(problem.getDetail()).contains("Nothing was saved").doesNotContain("Asha").doesNotContain("9000000001");
    }

    @Test
    void aStatementTimeoutThroughTheHandlerIsAlso503() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new UncategorizedSQLException("task", "update", new SQLException("canceling statement", "57014")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void anyOtherFailureStaysA500() {
        ResponseEntity<Object> response = handler.handleUnexpected(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
