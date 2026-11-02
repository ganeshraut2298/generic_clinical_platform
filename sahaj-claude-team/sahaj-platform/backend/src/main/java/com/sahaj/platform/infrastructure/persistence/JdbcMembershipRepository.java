package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.MembershipRepository;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code platform.membership}. {@link #findByUserId} relies on the additional self-read policy
 * {@code p_membership_self_read} (migration 1011): it only ever returns rows when
 * {@code app.user_id} is bound to the same user, never anyone else's.
 */
@Repository
public class JdbcMembershipRepository implements MembershipRepository {

    private static final String SELECT_COLUMNS = "id, tenant_id, user_id, role, status";
    private static final String FIND_BY_USER = "select " + SELECT_COLUMNS
            + " from platform.membership where user_id = :userId";
    private static final String FIND_BY_TENANT_AND_USER = "select " + SELECT_COLUMNS
            + " from platform.membership where tenant_id = :tenantId and user_id = :userId";
    private static final String INSERT = """
            insert into platform.membership (id, tenant_id, user_id, role, status, last_login_at)
            values (:id, :tenantId, :userId, :role, 'ACTIVE', :lastLoginAt)
            """;
    private static final String TOUCH_LAST_LOGIN = """
            update platform.membership set last_login_at = :at, updated_at = :at where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Membership> findByUserId(UUID userId) {
        return jdbc.sql(FIND_BY_USER).param("userId", userId).query(JdbcMembershipRepository::map).list();
    }

    @Override
    public Optional<Membership> findByTenantAndUser(UUID tenantId, UUID userId) {
        return jdbc.sql(FIND_BY_TENANT_AND_USER)
                .param("tenantId", tenantId).param("userId", userId)
                .query(JdbcMembershipRepository::map).optional();
    }

    @Override
    public void insert(UUID id, UUID tenantId, UUID userId, MembershipRole role, Instant lastLoginAt) {
        jdbc.sql(INSERT)
                .param("id", id).param("tenantId", tenantId).param("userId", userId)
                .param("role", role.name()).param("lastLoginAt", lastLoginAt)
                .update();
    }

    @Override
    public void touchLastLogin(UUID membershipId, Instant at) {
        jdbc.sql(TOUCH_LAST_LOGIN).param("id", membershipId).param("at", at).update();
    }

    private static Membership map(ResultSet rs, int rowNum) throws SQLException {
        return new Membership(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                MembershipRole.valueOf(rs.getString("role")),
                MembershipStatus.valueOf(rs.getString("status")));
    }
}
