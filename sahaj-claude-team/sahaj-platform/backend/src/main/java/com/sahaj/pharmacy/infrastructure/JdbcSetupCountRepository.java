package com.sahaj.pharmacy.infrastructure;

import com.sahaj.pharmacy.application.SetupCountRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Counts over {@code pharmacy.store} and {@code pharmacy.vendor} for the setup tabs. Read only. */
@Repository
public class JdbcSetupCountRepository implements SetupCountRepository {

    private static final String STORES =
            "select count(*) from pharmacy.store where tenant_id = :tenantId and status = 'ACTIVE'";
    private static final String VENDORS =
            "select count(*) from pharmacy.vendor where tenant_id = :tenantId and status = 'ACTIVE'";

    private final JdbcClient jdbc;

    public JdbcSetupCountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int activeStores(UUID tenantId) {
        return jdbc.sql(STORES).param("tenantId", tenantId).query(Integer.class).single();
    }

    @Override
    public int activeVendors(UUID tenantId) {
        return jdbc.sql(VENDORS).param("tenantId", tenantId).query(Integer.class).single();
    }
}
