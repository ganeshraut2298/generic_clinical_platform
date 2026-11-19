package com.sahaj.pharmacy.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager for tests without a database: it records how each transaction was asked for ({@code begin}, with the
 * propagation) and how it ended ({@code commit} or {@code rollback}), in order, together with any events that tests add in
 * between (for example an audit write).
 */
final class RecordingTransactionManager implements PlatformTransactionManager {

    final List<String> events = new ArrayList<>();
    final List<TransactionDefinition> definitions = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        definitions.add(definition);
        events.add("begin");
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        events.add("commit");
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        events.add("rollback");
    }
}
