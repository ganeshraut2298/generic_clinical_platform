package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahaj.platform.application.admin.AdminDbStateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The SQLSTATE mapping of docs/database/ADM-1.md and the commit/rollback rule of the repository. */
class JdbcAdminAuthRepositoryTest {

    @Test
    void mapsEverySqlStateOfTheContract() {
        String[][] table = {
                {"42501", "NO_SESSION"}, {"SA001", "PREAUTH_INVALID"}, {"SA002", "TOTP_REPLAY"},
                {"SA003", "RECOVERY_INVALID"}, {"SA004", "ADMIN_UNAVAILABLE"}, {"SA005", "RECOVERY_SET_INVALID"},
                {"SA006", "NO_PENDING_SECRET"}, {"SA007", "ALREADY_ENROLLED"}, {"22023", "BAD_PARAMETER"}};
        for (String[] row : table) {
            Throwable failure = new UncategorizedSQLException("task", "sql", new SQLException("message", row[0]));
            AdminDbStateException state = JdbcAdminAuthRepository.stateOf(failure);
            assertThat(state).as(row[0]).isNotNull();
            assertThat(state.state().name()).isEqualTo(row[1]);
        }
    }

    @Test
    void findsTheSqlStateDeepInTheCauseChain() {
        Throwable failure = new RuntimeException("outer", new RuntimeException("middle", new SQLException("x", "SA002")));
        assertThat(JdbcAdminAuthRepository.stateOf(failure).state()).isEqualTo(AdminDbStateException.State.TOTP_REPLAY);
    }

    @Test
    void otherSqlStatesAndPlainFailuresAreNotMapped() {
        assertThat(JdbcAdminAuthRepository.stateOf(new SQLException("x", "23505"))).isNull();
        assertThat(JdbcAdminAuthRepository.stateOf(new IllegalStateException("x"))).isNull();
    }

    @Test
    void theStateExceptionMessageCarriesNoDatabaseText() {
        AdminDbStateException state = JdbcAdminAuthRepository.stateOf(new SQLException("secret detail 123", "SA003"));
        assertThat(state.getMessage()).doesNotContain("secret detail");
    }

    @Test
    void aDatabaseRefusalRollsBackAndSurfacesAsAStateException() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        JdbcClient jdbc = mock(JdbcClient.class, invocation -> {
            throw new UncategorizedSQLException("task", "sql", new SQLException("x", "SA001"));
        });
        JdbcAdminAuthRepository repository = new JdbcAdminAuthRepository(jdbc, manager);

        assertThatThrownBy(() -> repository.preAuthGet("a".repeat(64)))
                .isInstanceOfSatisfying(AdminDbStateException.class,
                        ex -> assertThat(ex.state()).isEqualTo(AdminDbStateException.State.PREAUTH_INVALID));
        assertThat(manager.rolledBack).isTrue();
        assertThat(manager.committed).isFalse();
    }

    @Test
    void aUnknownFailureIsRethrownAsIsAfterRollback() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        JdbcClient jdbc = mock(JdbcClient.class, invocation -> {
            throw new IllegalStateException("boom");
        });
        JdbcAdminAuthRepository repository = new JdbcAdminAuthRepository(jdbc, manager);

        assertThatThrownBy(() -> repository.sessionTouch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.rolledBack).isTrue();
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {
        boolean committed;
        boolean rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed = true;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack = true;
        }
    }
}
