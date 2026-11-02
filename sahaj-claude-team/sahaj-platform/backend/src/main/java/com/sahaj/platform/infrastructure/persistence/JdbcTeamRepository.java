package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.TeamRepository;
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
 * {@code platform.membership} with the person and doctor details (SAH-6). Row level security
 * limits every statement to the bound clinic; the tenant id is also in each query so a wrong
 * binding returns nothing rather than another clinic's row. The clinic's own name for a person is
 * {@code display_name}; {@code app_user.full_name} is never written here.
 */
@Repository
public class JdbcTeamRepository implements TeamRepository {

    private static final String SELECT = """
            select m.id, m.user_id, coalesce(m.display_name, u.full_name) as name, u.mobile,
                   m.role, m.status, m.last_login_at,
                   d.membership_id is not null as has_doctor_profile,
                   d.specialty, d.degree, d.registration_no, coalesce(d.fee_paise, 0) as fee_paise
            from platform.membership m
            join platform.app_user u on u.id = m.user_id
            left join platform.doctor_profile d on d.membership_id = m.id
            where m.tenant_id = :tenantId
            """;
    private static final String FIND_ALL = SELECT
            + " order by (m.role = 'OWNER') desc, m.created_at, m.id";
    private static final String FIND_BY_ID = SELECT + " and m.id = :id";
    private static final String FIND_BY_USER = SELECT + " and m.user_id = :userId";
    private static final String LOCK_TENANT = """
            select id from platform.tenant where id = platform.current_tenant() for update
            """;
    private static final String COUNT_ACTIVE = """
            select count(*) from platform.membership where tenant_id = :tenantId and status = 'ACTIVE'
            """;
    private static final String COUNT_OTHER_ACTIVE_OWNERS = """
            select count(*) from platform.membership
            where tenant_id = :tenantId and role = 'OWNER' and status = 'ACTIVE' and id <> :id
            """;
    private static final String INSERT = """
            insert into platform.membership (id, user_id, role, status, display_name, created_by, updated_by)
            values (:id, :userId, :role, 'ACTIVE', :displayName, :actor, :actor)
            """;
    private static final String UPDATE = """
            update platform.membership
            set role = coalesce(:role, role), display_name = coalesce(:displayName, display_name),
                updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;
    private static final String UPDATE_STATUS = """
            update platform.membership
            set status = :status, updated_by = :actor, updated_at = now(), version = version + 1
            where id = :id
            """;

    private final JdbcClient jdbc;

    public JdbcTeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TeamRow> findAll(UUID tenantId) {
        return jdbc.sql(FIND_ALL).param("tenantId", tenantId).query(JdbcTeamRepository::map).list();
    }

    @Override
    public Optional<TeamRow> findById(UUID tenantId, UUID membershipId) {
        return jdbc.sql(FIND_BY_ID).param("tenantId", tenantId).param("id", membershipId)
                .query(JdbcTeamRepository::map).optional();
    }

    @Override
    public Optional<TeamRow> findByUser(UUID tenantId, UUID userId) {
        return jdbc.sql(FIND_BY_USER).param("tenantId", tenantId).param("userId", userId)
                .query(JdbcTeamRepository::map).optional();
    }

    @Override
    public void lockTenant() {
        jdbc.sql(LOCK_TENANT).query(UUID.class).list();
    }

    @Override
    public int countActive(UUID tenantId) {
        return jdbc.sql(COUNT_ACTIVE).param("tenantId", tenantId).query(Integer.class).single();
    }

    @Override
    public int countOtherActiveOwners(UUID tenantId, UUID membershipId) {
        return jdbc.sql(COUNT_OTHER_ACTIVE_OWNERS).param("tenantId", tenantId).param("id", membershipId)
                .query(Integer.class).single();
    }

    @Override
    public void insert(UUID id, UUID userId, MembershipRole role, String displayName, UUID actorUserId) {
        jdbc.sql(INSERT).param("id", id).param("userId", userId).param("role", role.name())
                .param("displayName", displayName).param("actor", actorUserId).update();
    }

    @Override
    public void update(UUID id, MembershipRole role, String displayName, UUID actorUserId) {
        jdbc.sql(UPDATE).param("id", id).param("role", role == null ? null : role.name(), java.sql.Types.VARCHAR)
                .param("displayName", displayName, java.sql.Types.VARCHAR).param("actor", actorUserId).update();
    }

    @Override
    public void updateStatus(UUID id, MembershipStatus status, UUID actorUserId) {
        jdbc.sql(UPDATE_STATUS).param("id", id).param("status", status.name()).param("actor", actorUserId).update();
    }

    private static TeamRow map(ResultSet rs, int rowNum) throws SQLException {
        Instant lastLogin = rs.getTimestamp("last_login_at") == null
                ? null : rs.getTimestamp("last_login_at").toInstant();
        return new TeamRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("name"),
                rs.getString("mobile"),
                MembershipRole.valueOf(rs.getString("role")),
                MembershipStatus.valueOf(rs.getString("status")),
                lastLogin,
                rs.getBoolean("has_doctor_profile"),
                rs.getString("specialty"),
                rs.getString("degree"),
                rs.getString("registration_no"),
                rs.getLong("fee_paise"));
    }
}
