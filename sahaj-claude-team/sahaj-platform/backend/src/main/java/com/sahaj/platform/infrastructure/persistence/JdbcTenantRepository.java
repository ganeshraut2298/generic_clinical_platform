package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.TenantRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.tenant}. Row level security ({@code p_tenant_self}) requires {@code app.tenant_id}
 * to already equal the id being inserted or read - the caller binds it first (SAH-3 verify, ADR-002 D4).
 */
@Repository
public class JdbcTenantRepository implements TenantRepository {

    private static final String INSERT = """
            insert into platform.tenant (id, name, plan, plan_ends_at) values (:id, :name, 'FREE', :planEndsAt)
            """;
    private static final String FIND_NAME = "select name from platform.tenant where id = :id";

    private final JdbcClient jdbc;

    public JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, String name, Instant planEndsAt) {
        jdbc.sql(INSERT).param("id", id).param("name", name).param("planEndsAt", planEndsAt).update();
    }

    @Override
    public Optional<String> findName(UUID tenantId) {
        return jdbc.sql(FIND_NAME).param("id", tenantId).query(String.class).optional();
    }
}
