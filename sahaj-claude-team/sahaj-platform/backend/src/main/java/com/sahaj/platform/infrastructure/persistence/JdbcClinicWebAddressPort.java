package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.api.ClinicWebAddressPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code platform.tenant.slug} of the bound clinic (SAH-78). Row level security ({@code p_tenant_self}) limits
 * the read to the bound tenant whatever id is passed. Written in the caller's own transaction.
 */
@Repository
public class JdbcClinicWebAddressPort implements ClinicWebAddressPort {

    private static final String FIND = "select slug from platform.tenant where id = :tenantId";

    private final JdbcClient jdbc;

    public JdbcClinicWebAddressPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> webAddress(UUID tenantId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).query(String.class).optional()
                .filter(slug -> !slug.isBlank());
    }
}
