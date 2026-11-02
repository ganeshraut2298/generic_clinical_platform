package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.RefreshTokenRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.refresh_token}. {@link #revokeAllForUser} relies on the additional self-revoke
 * policy {@code p_refresh_token_self_revoke} (migration 1012: {@code app.user_id} bound, SAH-3 AC15).
 * Every other method here relies only on the ordinary tenant-scoped policy {@code p_refresh_token_tenant}.
 */
@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final String INSERT = """
            insert into platform.refresh_token (id, tenant_id, user_id, token_hash, expires_at)
            values (:id, :tenantId, :userId, :tokenHash, :expiresAt)
            """;
    private static final String FIND_BY_HASH = """
            select id, tenant_id, user_id, expires_at, revoked_at
            from platform.refresh_token where tenant_id = :tenantId and token_hash = :tokenHash
            """;
    private static final String REVOKE = "update platform.refresh_token set revoked_at = :at where id = :id";
    private static final String REVOKE_ALL_IN_TENANT = """
            update platform.refresh_token set revoked_at = :at
            where tenant_id = :tenantId and user_id = :userId and revoked_at is null
            """;
    private static final String REVOKE_ALL_FOR_USER = """
            update platform.refresh_token set revoked_at = :at where user_id = :userId and revoked_at is null
            """;

    private final JdbcClient jdbc;

    public JdbcRefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID tenantId, UUID userId, String tokenHash, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", id).param("tenantId", tenantId).param("userId", userId)
                .param("tokenHash", tokenHash).param("expiresAt", expiresAt)
                .update();
        return id;
    }

    @Override
    public Optional<RefreshTokenRow> findByTokenHash(UUID tenantId, String tokenHash) {
        return jdbc.sql(FIND_BY_HASH)
                .param("tenantId", tenantId).param("tokenHash", tokenHash)
                .query(JdbcRefreshTokenRepository::map).optional();
    }

    @Override
    public void revoke(UUID id, Instant at) {
        jdbc.sql(REVOKE).param("id", id).param("at", at).update();
    }

    @Override
    public void revokeAllForUserInTenant(UUID tenantId, UUID userId, Instant at) {
        jdbc.sql(REVOKE_ALL_IN_TENANT).param("tenantId", tenantId).param("userId", userId).param("at", at).update();
    }

    @Override
    public void revokeAllForUser(UUID userId, Instant at) {
        jdbc.sql(REVOKE_ALL_FOR_USER).param("userId", userId).param("at", at).update();
    }

    private static RefreshTokenRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshTokenRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("revoked_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
