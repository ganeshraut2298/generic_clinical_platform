package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.TenantRepository;
import com.sahaj.platform.domain.TenantProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
            insert into platform.tenant (id, name, plan, created_at, plan_ends_at)
            values (:id, :name, 'FREE', :createdAt, :planEndsAt)
            """;
    private static final String FIND_NAME = "select name from platform.tenant where id = :id";
    private static final String FIND_PROFILE = """
            select name, brand, plan, plan_ends_at from platform.tenant where id = :id
            """;
    // platform.tenant_slug is the RLS-free mirror that answers the cross-tenant question (migration 1016).
    private static final String IS_SLUG_AVAILABLE = """
            select not exists (select 1 from platform.tenant_slug where slug = :slug)
            """;
    private static final String CLAIM_SLUG_AND_BRAND = """
            update platform.tenant set name = :name, brand = :brand, slug = :slug where id = :id
            """;
    private static final String MIRROR_SLUG = """
            insert into platform.tenant_slug (owner_tenant, slug) values (:id, :slug)
            on conflict (owner_tenant) do update set slug = excluded.slug, updated_at = now()
            """;

    private static final String UPDATE_NAME_AND_BRAND = """
            update platform.tenant set name = :name, brand = :brand, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, String name, Instant createdAt, Instant planEndsAt) {
        jdbc.sql(INSERT).param("id", id).param("name", name).param("createdAt", createdAt)
                .param("planEndsAt", planEndsAt).update();
    }

    @Override
    public Optional<String> findName(UUID tenantId) {
        return jdbc.sql(FIND_NAME).param("id", tenantId).query(String.class).optional();
    }

    @Override
    public Optional<TenantProfile> findProfile(UUID tenantId) {
        return jdbc.sql(FIND_PROFILE).param("id", tenantId).query(JdbcTenantRepository::mapProfile).optional();
    }

    @Override
    public boolean isSlugAvailable(String slug) {
        return Boolean.TRUE.equals(jdbc.sql(IS_SLUG_AVAILABLE).param("slug", slug).query(Boolean.class).single());
    }

    @Override
    public void claimSlugAndBrand(UUID tenantId, String name, String brand, String slug) {
        jdbc.sql(CLAIM_SLUG_AND_BRAND)
                .param("id", tenantId).param("name", name).param("brand", brand).param("slug", slug)
                .update();
        jdbc.sql(MIRROR_SLUG).param("id", tenantId).param("slug", slug).update();
    }

    @Override
    public void updateNameAndBrand(UUID tenantId, String name, String brand) {
        jdbc.sql(UPDATE_NAME_AND_BRAND).param("id", tenantId).param("name", name).param("brand", brand).update();
    }

    private static TenantProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        Timestamp planEndsAt = rs.getTimestamp("plan_ends_at");
        return new TenantProfile(rs.getString("name"), rs.getString("brand"), rs.getString("plan"),
                planEndsAt == null ? null : planEndsAt.toInstant());
    }
}
