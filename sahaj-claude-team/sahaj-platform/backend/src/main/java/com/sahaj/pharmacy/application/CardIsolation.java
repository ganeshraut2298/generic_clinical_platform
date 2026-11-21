package com.sahaj.pharmacy.application;

import java.util.function.Supplier;

/**
 * Runs one piece of read work so that a failure inside it leaves the surrounding transaction usable (DEC-089 item 6).
 * The implementation lives in infrastructure (a JDBC savepoint); the work's exception is rethrown after the rollback.
 */
public interface CardIsolation {

    <T> T run(Supplier<T> work);

    /**
     * As {@link #run}, but a failure of the work answers {@code failed} instead of being rethrown (the card shows its error state and
     * the others still answer). Every failure is caught except a refusal (a store denial or any other forbidden answer), which must
     * reach the caller with its audit. The catch is in the implementation: no application class catches a RuntimeException.
     */
    <T> T runOr(Supplier<T> work, T failed);
}
