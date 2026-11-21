package com.sahaj.pharmacy.application;

import java.util.function.Supplier;

/**
 * Runs one piece of read work so that a failure inside it leaves the surrounding transaction usable (DEC-089 item 6).
 * The implementation lives in infrastructure (a JDBC savepoint); the work's exception is rethrown after the rollback.
 */
public interface CardIsolation {

    <T> T run(Supplier<T> work);
}
