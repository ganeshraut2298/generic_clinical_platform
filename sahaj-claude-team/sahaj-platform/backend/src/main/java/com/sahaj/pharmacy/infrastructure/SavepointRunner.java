package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.CardIsolation;
import com.sahaj.platform.api.ForbiddenException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work inside a JDBC savepoint on the current transaction's connection. In PostgreSQL a failed statement aborts the whole
 * transaction unless it is rolled back to a savepoint, so this is what lets one failed card leave the others answerable.
 * Outside a transaction the work simply runs.
 */
@Component
public class SavepointRunner implements CardIsolation {

    private static final Logger log = LoggerFactory.getLogger(SavepointRunner.class);

    private final DataSource dataSource;

    public SavepointRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T run(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint savepoint = connection.setSavepoint();
            T result;
            try {
                result = work.get();
            } catch (RuntimeException failed) {
                rollbackQuietly(connection, savepoint);
                throw failed;
            }
            connection.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException e) {
            throw new CannotGetJdbcConnectionException("Savepoint failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public <T> T runOr(Supplier<T> work, T failed) {
        try {
            return run(work);
        } catch (ForbiddenException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            log.warn("A card failed class={}", failure.getClass().getSimpleName());
            return failed;
        }
    }

    private static void rollbackQuietly(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException ignored) {
            // the original failure is the one that matters
        }
    }
}
