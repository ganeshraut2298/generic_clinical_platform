package com.sahaj.opd.infrastructure.persistence;

import com.sahaj.platform.api.SpecialtyServicesPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a specialty template's starter services into {@code opd.service} (SAH-7 AC9) in the
 * caller's transaction. A name the clinic already has (any case, active or removed) is skipped by
 * the conflict clause, so nothing is overwritten and nothing is deleted. The conflict target is
 * the expression index {@code uq_service_tenant_name}.
 */
@Repository
public class JdbcSpecialtyServicesPort implements SpecialtyServicesPort {

    private static final String INSERT_IF_MISSING = """
            insert into opd.service (name, category, price_paise, created_by)
            values (:name, :category, :pricePaise, :actor)
            on conflict (tenant_id, lower(name)) do nothing
            """;

    private final JdbcClient jdbc;

    public JdbcSpecialtyServicesPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int addMissingServices(UUID tenantId, UUID actorUserId, List<TemplateService> services) {
        int added = 0;
        for (TemplateService service : services) {
            added += jdbc.sql(INSERT_IF_MISSING)
                    .param("name", service.name())
                    .param("category", service.category())
                    .param("pricePaise", service.pricePaise())
                    .param("actor", actorUserId)
                    .update();
        }
        return added;
    }
}
