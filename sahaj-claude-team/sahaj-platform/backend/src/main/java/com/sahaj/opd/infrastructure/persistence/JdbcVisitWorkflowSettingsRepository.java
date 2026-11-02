package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.opd.application.VisitWorkflowSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code vitals_on} column of {@code opd.opd_settings}, one row per clinic (SAH-4, SAH-12). */
@Repository
public class JdbcVisitWorkflowSettingsRepository implements VisitWorkflowSettingsRepository {

    private static final String FIND = "select vitals_on from opd.opd_settings where tenant_id = :tenantId";

    private final JdbcClient jdbc;

    public JdbcVisitWorkflowSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Boolean> vitalsOn(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(Boolean.class).optional();
    }
}
