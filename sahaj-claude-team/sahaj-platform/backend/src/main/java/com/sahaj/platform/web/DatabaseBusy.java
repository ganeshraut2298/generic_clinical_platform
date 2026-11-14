package com.sahaj.platform.web;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * DEC-054: tells "the database was too busy" from every other failure, so a request that waited too long answers a
 * clear 503 and never a 500 (and never hangs: the wait itself is bounded by the lock and statement timeouts the request
 * pool is started with, see {@code spring.datasource.hikari.data-source-properties.options} in application.yml).
 *
 * <p>Three causes count: a row or advisory lock that was not free within the lock timeout (SQLSTATE 55P03), a statement
 * that ran past the statement timeout (57014), and no pooled connection free within the pool's connection timeout. A
 * deadlock (40P01) is not one of them: PostgreSQL has already ended it and it is a bug to fix, not a wait to retry.
 */
final class DatabaseBusy {

    static final String LOCK_NOT_AVAILABLE = "55P03";
    static final String QUERY_CANCELED = "57014";
    private static final int MAX_DEPTH = 12;

    private DatabaseBusy() {
    }

    static boolean isBusy(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof CannotAcquireLockException || current instanceof QueryTimeoutException
                    || current instanceof CannotGetJdbcConnectionException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sql && isTimeoutState(sql.getSQLState())) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean isTimeoutState(String sqlState) {
        return LOCK_NOT_AVAILABLE.equals(sqlState) || QUERY_CANCELED.equals(sqlState);
    }

    /** "lock", "statement" or "connection": the kind of wait that ended, for the log line (no value, no message). */
    static String kind(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof CannotAcquireLockException
                    || current instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return "lock";
            }
            if (current instanceof QueryTimeoutException
                    || current instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return "statement";
            }
            if (current instanceof CannotGetJdbcConnectionException || current instanceof SQLTransientConnectionException) {
                return "connection";
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return "other";
    }
}
