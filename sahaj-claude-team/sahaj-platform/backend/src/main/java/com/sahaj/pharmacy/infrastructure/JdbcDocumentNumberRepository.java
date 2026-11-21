package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.DocumentNumberPort;
import com.sahaj.pharmacy.domain.DocType;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Calls {@code pharmacy.next_document_no(text)}; the function names the tenant from the connection, not from a parameter. */
@Repository
public class JdbcDocumentNumberRepository implements DocumentNumberPort {

    private final JdbcClient jdbc;

    public JdbcDocumentNumberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long next(UUID tenantId, DocType type) {
        return jdbc.sql("select pharmacy.next_document_no(:type)").param("type", type.name()).query(Long.class).single();
    }

    @Override
    public long nextStoreBill(UUID tenantId, UUID storeId) {
        return jdbc.sql("select pharmacy.next_store_bill_no(:storeId)").param("storeId", storeId).query(Long.class).single();
    }
}
