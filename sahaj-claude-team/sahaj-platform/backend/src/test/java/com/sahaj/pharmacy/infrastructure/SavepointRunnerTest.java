package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Savepoint;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A failed card rolls back to its savepoint, a good one releases it, and outside a transaction the work just runs. */
class SavepointRunnerTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Savepoint savepoint = mock(Savepoint.class);
    private final SavepointRunner runner = new SavepointRunner(dataSource);

    @BeforeEach
    void inATransaction() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.setSavepoint()).thenReturn(savepoint);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.clear();
    }

    @Test
    void aGoodCardReleasesItsSavepoint() throws Exception {
        assertThat(runner.run(() -> "ok")).isEqualTo("ok");

        InOrder order = inOrder(connection);
        order.verify(connection).setSavepoint();
        order.verify(connection).releaseSavepoint(savepoint);
    }

    @Test
    void aFailedCardRollsBackToItsSavepointAndRethrows() throws Exception {
        assertThatThrownBy(() -> runner.run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        InOrder order = inOrder(connection);
        order.verify(connection).setSavepoint();
        order.verify(connection).rollback(savepoint);
    }

    @Test
    void runOrAnswersTheFallbackForAnyRuntimeFailureAndRollsBackToTheSavepoint() throws Exception {
        assertThat(runner.runOr(() -> {
            throw new ArithmeticException("any runtime failure");
        }, "fallback")).isEqualTo("fallback");
        assertThat(runner.runOr(() -> "ok", "fallback")).isEqualTo("ok");

        org.mockito.Mockito.verify(connection).rollback(savepoint);
    }

    @Test
    void runOrNeverSwallowsARefusal() {
        assertThatThrownBy(() -> runner.runOr(() -> {
            throw new com.sahaj.platform.api.ForbiddenException("forbidden", "Not allowed", "x");
        }, "fallback")).isInstanceOf(com.sahaj.platform.api.ForbiddenException.class);
    }

    @Test
    void outsideATransactionTheWorkJustRuns() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThat(runner.run(() -> 7)).isEqualTo(7);
        verifyNoInteractions(connection);
    }
}
