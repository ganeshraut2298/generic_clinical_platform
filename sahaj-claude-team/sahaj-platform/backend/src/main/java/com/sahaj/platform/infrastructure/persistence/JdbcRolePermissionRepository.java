package com.sahaj.platform.infrastructure.persistence;

import com.sahaj.platform.application.RolePermissionRepository;
import com.sahaj.platform.domain.MembershipRole;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * "Who can do what" (SAH-6). Reads go through {@code platform.effective_role_permissions()}, which
 * merges the global defaults with this clinic's own changes. Writes never name the clinic: the
 * {@code tenant_id} column fills itself from the bound tenant and row level security checks it.
 */
@Repository
public class JdbcRolePermissionRepository implements RolePermissionRepository {

    private static final String EFFECTIVE = """
            select role, permission, allowed from platform.effective_role_permissions()
            """;
    private static final String UPSERT = """
            insert into platform.role_permission (role, permission, allowed, updated_by)
            values (:role, :permission, :allowed, :actor)
            on conflict (tenant_id, role, permission) do update
            set allowed = excluded.allowed, updated_at = now(), updated_by = excluded.updated_by,
                version = platform.role_permission.version + 1
            """;

    private final JdbcClient jdbc;

    public JdbcRolePermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Cell> effective() {
        return jdbc.sql(EFFECTIVE)
                .query((rs, rowNum) -> new Cell(MembershipRole.valueOf(rs.getString("role")),
                        rs.getString("permission"), rs.getBoolean("allowed")))
                .list();
    }

    @Override
    public void upsert(MembershipRole role, String permission, boolean allowed, UUID actorUserId) {
        jdbc.sql(UPSERT).param("role", role.name()).param("permission", permission).param("allowed", allowed)
                .param("actor", actorUserId).update();
    }
}
