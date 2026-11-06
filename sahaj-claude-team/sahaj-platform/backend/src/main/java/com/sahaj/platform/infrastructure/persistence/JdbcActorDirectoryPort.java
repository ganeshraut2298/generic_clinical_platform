package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.api.ActorDirectoryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The same {@code membership.display_name}, else {@code app_user.full_name} lookup
 * {@link JdbcAuditEventRepository} uses for {@code actorName} (SAH-10). Written in the caller's own
 * transaction ({@link Propagation#MANDATORY}); row level security on both tables scopes the join to
 * the bound tenant regardless of the {@code tenantId} passed in.
 */
@Repository
public class JdbcActorDirectoryPort implements ActorDirectoryPort {

    private static final String FIND = """
            select coalesce(m.display_name, u.full_name) as display_name
            from platform.membership m
            join platform.app_user u on u.id = m.user_id
            where m.tenant_id = :tenantId and m.user_id = :userId
            """;

    private static final String FIND_MANY = """
            select m.user_id, coalesce(m.display_name, u.full_name) as display_name
            from platform.membership m
            join platform.app_user u on u.id = m.user_id
            where m.tenant_id = :tenantId and m.user_id in (:userIds)
            """;

    private final JdbcClient jdbc;

    public JdbcActorDirectoryPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, String> displayNames(UUID tenantId, Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return names;
        }
        jdbc.sql(FIND_MANY).param("tenantId", tenantId).param("userIds", userIds)
                .query((rs, rowNum) -> names.put(rs.getObject("user_id", UUID.class), rs.getString("display_name")))
                .list();
        return names;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> displayName(UUID tenantId, UUID userId) {
        return jdbc.sql(FIND).param("tenantId", tenantId).param("userId", userId)
                .query(String.class).optional();
    }
}
